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

(defn- fail! [message data]
  ;; Never retain endpoint URLs, headers, response bodies, or nested causes.
  (throw (ex-info message (assoc data :oscope.langfuse-interop/error true))))

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
    (loop [last-status nil]
      (let [{:keys [status rows]} (observation-response url headers
                                                       (:trace-id ids))
            exact (vec (filter #(= (:trace-id ids) (:traceId %)) rows))]
        (cond
          (= 2 (count exact)) exact
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 1000) (recur status))
          :else
          (fail! "Langfuse observations did not become semantically readable"
                 {:last-status (or status last-status) :matching-observation-count
                  (count exact)}))))))

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
  (let [{:keys [traces-url observations-url headers]} (config!)
        local (server/start! {:port 0 :db-spec "chdb::memory:"})
        runtime* (atom nil)]
    (try
      (let [local-exporter
            (otlp/exporter
             {:endpoint (str "http://127.0.0.1:" (:port local))
              :environment? false :timeout-ms 10000 :max-retries 0})
            runtime (gate/dual-sdk! {:local-exporter local-exporter
                                     :remote-url traces-url
                                     :remote-headers headers})
            _ (reset! runtime* runtime)
            ids (gate/emit-nested-trace!)]
        (when-not (= {:local {:ok? true} :remote {:ok? true}}
                     (export/force-flush-pipelines! (:pipelines runtime)))
          (fail! "dual span export did not flush both destinations" {}))
        (when-not (= (expected-local ids)
                     (local-identities (:connection local) (:trace-id ids)))
          (fail! "standalone Oscope did not read back the canonical trace" {}))
        (verify-remote! ids (await-observations! observations-url headers ids))
        ids)
      (finally
        (when-let [runtime @runtime*]
          (try (sdk/shutdown! (:handle runtime)) (catch Throwable _ nil)))
        (server/stop! local)))))

(defn -main [& _]
  (try
    (let [ids (run!)]
      (println (str "PASS: Oscope and Langfuse preserved nested trace "
                    (:trace-id ids)))
      (System/exit 0))
    (catch Throwable _
      (binding [*out* *err*]
        (println "FAIL: Oscope/Langfuse semantic interoperability gate failed"))
      (System/exit 1))))
