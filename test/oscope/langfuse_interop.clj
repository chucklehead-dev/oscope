(ns oscope.langfuse-interop
  "Opt-in real Oscope plus Langfuse semantic interoperability gate."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.host :as host]
            [jolt.http-client :as http]
            [oscope.langfuse-gate :as gate]
            [oscope.server :as server]
            [otel.exporter.otlp :as otlp]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]))

(def ^:private observation-deadline-ms 90000)

(def ^:private diagnostic-prefix
  "FAIL: Oscope/Langfuse semantic interoperability gate failed")

(def ^:private success-summary
  "PASS: Oscope and Langfuse preserved the qualified nested trace")

(def ^:private diagnostic-stage-labels
  {:config-loaded "config-loaded"
   :local-ingest "local-ingest"
   :local-readback "local-readback"
   :remote-export-flush "remote-export/flush"
   :remote-observation "remote-observation"
   :semantic-compare "semantic-compare"
   :cleanup "cleanup"})

(def ^:private diagnostic-statuses
  #{:success :failure :timeout})

(def ^:private diagnostic-matching-counts
  #{:none :one :multiple})

(defn- fail! [message data]
  ;; Never retain endpoint URLs, headers, response bodies, or nested causes.
  (throw (ex-info message (assoc data :oscope.langfuse-interop/error true))))

(defn- matching-count [count]
  (cond
    (not (number? count)) nil
    (zero? count) :none
    (= 1 count) :one
    :else :multiple))

(defn- diagnostic-view [data]
  (let [stage (if (contains? diagnostic-stage-labels (:stage data))
                (:stage data)
                :unknown)
        status (let [candidate (:status data)]
                 (when (contains? diagnostic-statuses candidate) candidate))
        count (when (= :remote-observation stage)
                (let [candidate (or (:matching-count data)
                                    (some-> (:matching-observation-count data)
                                            matching-count))]
                  (when (contains? diagnostic-matching-counts candidate)
                    candidate)))]
    (cond-> {:stage stage}
      status (assoc :status status)
      count (assoc :matching-count count))))

(defn diagnostic-line
  "Render only allowlisted failure categories; never interpolate raw failures."
  [data]
  (let [{:keys [stage status matching-count]} (diagnostic-view data)]
    (str diagnostic-prefix " [stage="
         (get diagnostic-stage-labels stage "unknown")
         (when status (str " status=" (name status)))
         (when matching-count (str " matching-count=" (name matching-count)))
         "]")))

(defn success-line
  "Render the generic success summary without retaining generated trace IDs."
  []
  success-summary)

(defn- stage-error [stage error]
  ;; Retain no cause and rebuild ex-data from bounded diagnostic categories.
  (let [data (or (ex-data error) {})]
    (ex-info diagnostic-prefix
             (assoc (diagnostic-view
                     {:stage stage
                      :status (if (contains? diagnostic-statuses (:status data))
                                (:status data)
                                :failure)
                      :matching-observation-count
                      (:matching-observation-count data)})
                    :oscope.langfuse-interop/error true))))

(defn- at-stage! [stage thunk]
  (try
    (thunk)
    (catch Throwable error
      (throw (stage-error stage error)))))

(defn- run-with-cleanup! [operation cleanup-thunks]
  (let [outcome (try
                  {:value (operation)}
                  (catch Throwable error {:error error}))
        cleanup-error
        (reduce
         (fn [first-error cleanup]
           (try
             (cleanup)
             first-error
             (catch Throwable error
               (or first-error error))))
         nil
         cleanup-thunks)]
    (cond
      (:error outcome) (throw (:error outcome))
      cleanup-error (throw (stage-error :cleanup cleanup-error))
      :else (:value outcome))))

(defn- required-env! [name]
  (let [value (host/getenv name)]
    (when (str/blank? value)
      (fail! "required Langfuse qualification setting is absent"
             {:setting name}))
    value))

(defn- config! []
  (let [base (str/replace (required-env! "OSCOPE_LANGFUSE_BASE_URL") #"/+$" "")
        headers (otlp/parse-headers
                 (required-env! "OSCOPE_LANGFUSE_OTLP_HEADERS"))
        normalized (gate/normalized-headers headers)
        authorization (get normalized "authorization")]
    (when-not (re-matches #"https?://[^\s/?#@]+(?:/[^\s?#]*)?" base)
      (fail! "Langfuse base URL must be credential-free HTTP(S)"
             {:setting "OSCOPE_LANGFUSE_BASE_URL"}))
    (when-not (and authorization (str/starts-with? authorization "Basic "))
      (fail! "Langfuse qualification requires Basic authorization"
             {:setting "OSCOPE_LANGFUSE_OTLP_HEADERS"}))
    (when-not (= "4" (get normalized "x-langfuse-ingestion-version"))
      (fail! "Langfuse qualification requires ingestion version 4"
             {:setting "OSCOPE_LANGFUSE_OTLP_HEADERS"}))
    {:traces-url (str base "/api/public/otel/v1/traces")
     :observations-url (str base "/api/public/v2/observations")
     :headers headers}))

(defn- expected-local [ids]
  #{{:trace-id (:trace-id ids) :span-id (:root-span-id ids)
     :parent-span-id "" :name gate/root-name}
    {:trace-id (:trace-id ids) :span-id (:child-span-id ids)
     :parent-span-id (:root-span-id ids) :name gate/child-name}})

(defn- local-identities [connection trace-id]
  (set
   (map (fn [row]
          {:trace-id (:traceid row) :span-id (:spanid row)
           :parent-span-id (:parentspanid row) :name (:spanname row)})
        (jdbc/fetch
         connection
         ["select TraceId, SpanId, ParentSpanId, SpanName
             from otel_traces where TraceId=? order by SpanId"
          trace-id]))))

(defn observation-response
  "Fetch one observations page and retain only fields needed by the gate."
  [url headers trace-id]
  (try
    (let [response
          (http/get
           (str url "?traceId=" trace-id "&fields=core,basic,io&limit=100")
           {:headers headers :connection-timeout 10000 :socket-timeout 10000
            :throw-exceptions false})]
      (if (= 200 (:status response))
        {:status 200
         :rows (mapv gate/observation-view
                     (:data (json/read-str (:body response) :key-fn keyword)))}
        {:status (:status response)}))
    (catch Throwable _
      {:status :transport-failure})))

(defn- await-observations! [url headers ids]
  (let [deadline (+ (System/currentTimeMillis) observation-deadline-ms)]
    (loop []
      (let [{:keys [rows]} (observation-response url headers
                                                (:trace-id ids))
            exact (vec (filter #(= (:trace-id ids) (:traceId %)) rows))]
        (cond
          (= 2 (count exact)) exact
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 1000) (recur))
          :else
          (fail! "Langfuse observations did not become semantically readable"
                 {:status :timeout
                  :matching-observation-count (count exact)}))))))

(defn- verify-remote! [ids observations]
  (let [by-id (into {} (map (juxt :id identity)) observations)
        root (get by-id (:root-span-id ids))
        child (get by-id (:child-span-id ids))]
    (when-not
     (and (= gate/root-name (:name root))
          (= (:trace-id ids) (:traceId root))
          (or (nil? (:parentObservationId root))
              (= "" (:parentObservationId root)))
          (= "span" (some-> (:type root) str/lower-case))
          (= "qualification-input" (:input root))
          (= "qualification-output" (:output root))
          (= gate/child-name (:name child))
          (= (:trace-id ids) (:traceId child))
          (= (:root-span-id ids) (:parentObservationId child))
          (= "generation" (some-> (:type child) str/lower-case))
          (= "generation-input" (:input child))
          (= "generation-output" (:output child)))
      (fail! "Langfuse observations did not preserve expected trace semantics"
             {:matching-observation-count (count observations)}))
    true))

(defn run! []
  (let [{:keys [traces-url observations-url headers]}
        (at-stage! :config-loaded config!)
        local (at-stage! :local-ingest
                         #(server/start! {:port 0 :db-spec "chdb::memory:"}))
        runtime* (atom nil)]
    (run-with-cleanup!
     (fn []
       (let [local-exporter
             (at-stage!
              :local-ingest
              #(otlp/exporter
                {:endpoint (str "http://127.0.0.1:" (:port local))
                 :environment? false :timeout-ms 10000 :max-retries 0}))
             runtime
             (at-stage! :remote-export-flush
                        #(gate/dual-sdk! {:local-exporter local-exporter
                                         :remote-url traces-url
                                         :remote-headers headers}))
             _ (reset! runtime* runtime)
             ids (at-stage! :local-ingest gate/emit-nested-trace!)]
         (at-stage!
          :remote-export-flush
          #(when-not (= {:local {:ok? true} :remote {:ok? true}}
                        (export/force-flush-pipelines! (:pipelines runtime)))
             (fail! "dual span export did not flush both destinations" {})))
         (at-stage!
          :local-readback
          #(when-not (= (expected-local ids)
                        (local-identities (:connection local) (:trace-id ids)))
             (fail! "standalone Oscope did not read back the canonical trace"
                    {})))
         (let [observations
               (at-stage! :remote-observation
                          #(await-observations! observations-url headers ids))]
           (at-stage! :semantic-compare
                      #(verify-remote! ids observations)))
         ids))
     [(fn []
        (when-let [runtime @runtime*]
          (sdk/shutdown! (:handle runtime))))
      #(server/stop! local)])))

(defn -main [& _]
  (try
    (do
      (run!)
      (println (success-line))
      (System/exit 0))
    (catch Throwable error
      (binding [*out* *err*]
        (println (diagnostic-line (ex-data error))))
      (System/exit 1))))
