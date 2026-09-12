(ns oscope.typed-standalone-restart-integration-test
  "Real file-config, socket, typed-query, and persistent restart composition."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [oscope.config :as config]
            [oscope.config-cli :as config-cli]
            [oscope.server :as server]
            [oscope.typed-schema-config :as typed-schema-config]
            [oscope.ui.web :as web]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]
            [teensyp.client :as client]))

(def ^:private selector
  {:dataset-id "oscope-standalone" :application-id "typed-restart"
   :lineage "typed-restart-v1" :version 1})

(def ^:private values
  {"typed.ready" false
   "typed.note" ""
   "typed.exact" 9007199254740993
   "typed.minimum" -9223372036854775808
   "typed.maximum" 9223372036854775807})

(defn- copy-range [source start end]
  (let [result (byte-array (- end start))]
    (System/arraycopy source start result 0 (- end start))
    result))

(defn- receive-all! [connection]
  (let [scratch (byte-array 8192)]
    (loop [chunks [] total 0]
      (if-let [length (client/receive-into! connection scratch 0 (alength scratch)
                                             {:timeout-ms 5000})]
        (recur (conj chunks (copy-range scratch 0 length)) (+ total length))
        (let [result (byte-array total)]
          (loop [remaining chunks offset 0]
            (when-let [chunk (first remaining)]
              (System/arraycopy chunk 0 result offset (alength chunk))
              (recur (next remaining) (+ offset (alength chunk)))))
          result)))))

(defn- header-end [bytes]
  (loop [index 0]
    (when (<= (+ index 4) (alength bytes))
      (if (= [13 10 13 10]
             (mapv #(bit-and 255 (int (aget bytes %))) (range index (+ index 4))))
        index
        (recur (inc index))))))

(defn- request! [port target body]
  (let [connection (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms 5000})
        encoded (if body (.getBytes body "UTF-8") (byte-array 0))]
    (try
      (client/send-all!
       connection
       (.getBytes
        (str (if body "POST" "GET") " " target " HTTP/1.1\r\n"
             "Host: 127.0.0.1:" port "\r\n"
             (when body "Content-Type: application/json\r\n")
             "Content-Length: " (alength encoded) "\r\n"
             "Connection: close\r\n\r\n")
        "UTF-8")
       {:timeout-ms 5000})
      (when body
        (client/send-all! connection encoded {:timeout-ms 5000}))
      (let [raw (receive-all! connection)
            end (header-end raw)
            head (String. (copy-range raw 0 end) "UTF-8")
            [_ status] (re-find #"HTTP/1\.1 ([0-9]{3})" head)]
        {:status (parse-long status)
         :body (copy-range raw (+ end 4) (alength raw))})
      (finally
        (client/close! connection)))))

(defn- post! [port payload]
  (request! port "/v1/traces" (json/write-str payload)))

(defn- attribute [key kind value]
  {"key" key "value" {kind value}})

(defn- span [now offset trace-id span-id name attributes]
  {"traceId" trace-id "spanId" span-id "name" name "kind" 2
   "startTimeUnixNano" (str (- now (* offset 2000000)))
   "endTimeUnixNano" (str (- now (* offset 1000000)))
   "attributes" attributes})

(defn- trace-request [spans]
  {"resourceSpans"
   [{"resource" {"attributes"
                  [{"key" "service.name"
                    "value" {"stringValue" "oscope-typed-restart"}}]}
     "scopeSpans" [{"scope" {"name" "oscope.typed-restart"}
                    "spans" spans}]}]})

(defn- historical-request [now]
  (trace-request
   [(span now 2 "50000000000000000000000000000000" "5000000000000000"
          "typed.historical-fallback"
          [(attribute "typed.exact" "intValue" "7")])
    (span now 3 "60000000000000000000000000000000" "6000000000000000"
          "typed.historical-unavailable" [])]))

(defn- typed-request [now]
  (trace-request
   [(span now 1 "30000000000000000000000000000000" "3000000000000000"
          "typed.valid"
          (mapv (fn [[key value]]
                  (attribute key
                             (cond (boolean? value) "boolValue"
                                   (string? value) "stringValue"
                                   :else "intValue")
                             (if (integer? value) (str value) value)))
                values))
    (span now 4 "70000000000000000000000000000000" "7000000000000000"
          "typed.invalid"
          [(attribute "typed.exact" "stringValue" "not-an-int")])
    (span now 5 "80000000000000000000000000000000" "8000000000000000"
          "typed.absent" [])]))

(defn- approved-manifest []
  (manifest/compile-manifest
   (assoc selector :fragments
          [{:schema manifest/reviewed-fragment-schema
            :authority :runtime-reviewed
            :source "test/typed-standalone-restart.edn"
            :entries
            (mapv (fn [[key type]]
                    {:signal :spans :table "otel_traces"
                     :location :span-attributes :key key :type type})
                  [["typed.ready" :boolean] ["typed.note" :string]
                   ["typed.exact" :int64] ["typed.minimum" :int64]
                   ["typed.maximum" :int64]])}])))

(defn- selection [binding value]
  {:mode :typed-span-filter :schema-binding binding
   :operator :eq :value value :window :1h :limit 10})

(defn- screen [source binding value]
  ((:load-command source) [:typed-restart (:field-id binding)]
   (selection binding value)))

(defn- selected-row [source binding value]
  (first (get-in (screen source binding value) [:table :rows])))

(defn- coverage [source binding value]
  (into {} (map (juxt :status :count))
        (:coverage (screen source binding value))))

(defn- catalog-by-key [catalog]
  (into {} (map (juxt :attribute-key identity)) catalog))

(defn- catalog-tuples [catalog]
  (set (map (juxt :field-id :attribute-key :attribute-type :manifest-version)
            catalog)))

(defn- confirmed-tuples [lifecycle]
  (set
   (map (fn [{:keys [id key type identity]}]
          [id key type (:version identity)])
        (projection/confirmed-span-fields
         (:typed-span-descriptors lifecycle) (:connection lifecycle)))))

(defn- checked-results [lifecycle bindings]
  (into {}
        (map
         (fn [[key value]]
           (let [binding (get bindings key)
                 row (selected-row (:source lifecycle) binding value)
                 response (request!
                           (:port lifecycle)
                           (str "/oscope"
                                (web/selection-query-string
                                 (selection binding value))) nil)
                 expected-display (if (= "" value) "(empty string)" (str value))]
             (is (= value (:attribute-value row)))
             (is (= (if (= "" value) 2 3) (:typed-status row)))
             (is (= 200 (:status response)))
             (is (.contains (String. (:body response) "UTF-8") expected-display))
             [key (select-keys row
                               [:attribute-key :attribute-type :attribute-value
                                :field-id :manifest-version :typed-status
                                :trace-id :span-id :span-name])]))
         values)))

(defn- stop-twice! [lifecycle]
  (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
  (is (= {:status :closed :phase :closed} (server/stop! lifecycle))))

(defn- delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (java.nio.file.Files/deleteIfExists (.toPath file))))

(deftest file-configured-typed-standalone-restarts-read-only
  (let [directory (java.nio.file.Files/createTempDirectory
                   "oscope-typed-restart-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        root (java.io.File. (str directory))
        database-path (str (.resolve directory "telemetry"))
        manifest-path (str (.resolve directory "typed-manifest.edn"))
        config-path (str (.resolve directory "oscope.edn"))
        rendered (manifest/render (approved-manifest))
        base (assoc config/defaults
                    :server (assoc (:server config/defaults) :port 0)
                    :storage {:type :local-path :path database-path})
        install (assoc base :typed-attributes
                       {:mode :install
                        :manifest
                        {:path manifest-path
                         :sha256 (typed-schema-config/sha256-bytes
                                  (.getBytes rendered "UTF-8"))}
                        :registry selector})
        acquire (assoc base :typed-attributes
                       {:mode :acquire :registry selector})
        now (* (System/currentTimeMillis) 1000000)
        historical* (atom nil) install* (atom nil) acquire* (atom nil)]
    (try
      (spit manifest-path rendered)
      (spit config-path (config/encode
                         (assoc base :typed-attributes {:mode :disabled})))
      (let [lifecycle
            (server/start!
             (config-cli/server-options
              (config-cli/load-config ["--config" config-path] {})))]
        (reset! historical* lifecycle)
        (is (= 200 (:status (post! (:port lifecycle)
                                   (historical-request now)))))
        (stop-twice! lifecycle))

      (spit config-path (config/encode install))
      (let [options (config-cli/server-options
                     (config-cli/load-config ["--config" config-path] {}))
            lifecycle (server/start! options)]
        (reset! install* lifecycle)
        (is (= (str "chdb:" database-path) (:db-spec options)))
        (is (= 200 (:status (post! (:port lifecycle) (typed-request now)))))
        (let [catalog (:typed-span-fields (:source lifecycle))
              bindings (catalog-by-key catalog)
              descriptors (:typed-span-descriptors lifecycle)
              results (checked-results lifecycle bindings)
              exact-coverage (coverage (:source lifecycle)
                                       (get bindings "typed.exact")
                                       (get values "typed.exact"))]
          (is (some? descriptors))
          (is (identical? descriptors
                          (:typed-span-descriptors (:source lifecycle))))
          (is (= (catalog-tuples catalog) (confirmed-tuples lifecycle)))
          (is (= (set (keys values)) (set (keys bindings))))
          (is (= {:valid 1 :present-empty 0 :absent 1 :invalid 1
                  :historical-untyped-fallback 1
                  :historical-untyped-unavailable 1}
                 exact-coverage))
          (is (= 1 (:present-empty
                    (coverage (:source lifecycle)
                              (get bindings "typed.note") ""))))
          (stop-twice! lifecycle)

          (spit config-path (config/encode acquire))
          (let [resolved
                (config-cli/load-config
                 ["--config" config-path] {} slurp
                 (fn [& _] (throw (ex-info "manifest read prohibited" {}))))
                restarted
                (with-redefs
                  [schema/ensure-schema!
                   (fn [& _] (throw (ex-info "schema ensure prohibited" {})))
                   installer/install-approved!
                   (fn [& _] (throw (ex-info "manifest install prohibited" {})))
                   jdbc/execute!
                   (fn [& _] (throw (ex-info "DDL prohibited" {})))]
                  (server/start! (config-cli/server-options resolved)))]
            (reset! acquire* restarted)
            (let [restarted-catalog (:typed-span-fields (:source restarted))]
              (is (identical? (:typed-span-descriptors restarted)
                              (:typed-span-descriptors (:source restarted))))
              (is (= (catalog-tuples restarted-catalog)
                     (confirmed-tuples restarted)))
              (is (= catalog restarted-catalog))
              (is (= results (checked-results restarted bindings)))
              (is (= exact-coverage
                     (coverage (:source restarted)
                               (get bindings "typed.exact")
                               (get values "typed.exact"))))
              (is (= 1 (:present-empty
                        (coverage (:source restarted)
                                  (get bindings "typed.note") ""))))
              (let [stale (update (get bindings "typed.exact")
                                  :manifest-version inc)
                    response
                    (request!
                     (:port restarted)
                     (str "/oscope"
                          (web/selection-query-string
                           (selection stale (get values "typed.exact")))) nil)]
                (is (= 409 (:status response)))
                (is (.contains (String. (:body response) "UTF-8")
                               "Typed schema changed")))
              (stop-twice! restarted)))))
      (finally
        (doseq [lifecycle [@acquire* @install* @historical*]]
          (when lifecycle (server/stop! lifecycle)))
        (delete-tree! root)
        (delete-tree! root)
        (is (not (.exists root)))))))
