(ns oscope.typed-browser-server-main
  "Deterministic typed-schema server fixture for the Playwright gate."
  (:require [clojure.data.json :as json]
            [jdbc.chdb.durable.backend :as backend]
            [oscope.otlp :as otlp]
            [oscope.server :as server]
            [otel.exporter.chdb.attribute-manifest :as manifest]))

(defn- approved-manifest []
  (manifest/compile-manifest
   {:dataset-id "oscope-browser"
    :application-id "typed-int64"
    :lineage "typed-int64-v1"
    :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :runtime-reviewed
      :source "test/browser/typed-attributes.spec.js"
      :entries [{:signal :spans :table "otel_traces"
                 :location :span-attributes
                 :key "game.score" :type :int64}]}]}))

(defn- req-attribute [key kind value]
  {"key" key "value" {kind value}})

(defn- historical-request []
  (let [now (* (System/currentTimeMillis) 1000000)]
    {"resourceSpans"
     [{"resource"
       {"attributes" [(req-attribute "service.name" "stringValue"
                                      "oscope-typed-history")]}
       "scopeSpans"
       [{"scope" {"name" "oscope.browser.typed-history"}
         "spans"
         [{"traceId" "91000000000000000000000000000000"
           "spanId" "9100000000000000" "name" "historical.fallback"
           "startTimeUnixNano" (str (- now 4000000))
           "endTimeUnixNano" (str (- now 3000000))
           "attributes" [(req-attribute "game.score" "intValue" "7")]}
          {"traceId" "92000000000000000000000000000000"
           "spanId" "9200000000000000" "name" "historical.unavailable"
           "startTimeUnixNano" (str (- now 2000000))
           "endTimeUnixNano" (str (- now 1000000))
           "attributes" []}]}]}]}))

(defn- ingest-historical! [lifecycle]
  (let [body (json/write-str (historical-request))
        response
        ((otlp/handler (:exporter lifecycle))
         {:request-method :post :uri "/v1/traces"
          :headers {"content-type" "application/json"
                    "content-length" (str (alength (.getBytes body "UTF-8")))}
          :body body})]
    (when-not (= 200 (:status response))
      (throw (ex-info "typed browser historical fixture ingest failed"
                      {:status (:status response)})))))

(defn- delete-tree! [root]
  (when (.exists root)
    (doseq [file (reverse (file-seq root))]
      (java.nio.file.Files/deleteIfExists (.toPath file)))))

(defn- configured-port []
  (let [value (or (System/getenv "OSCOPE_PORT") "18318")]
    (when-not (re-matches #"[0-9]{1,5}" value)
      (throw (ex-info "typed browser port is invalid" {})))
    (let [port (parse-long value)]
      (when-not (<= 1 port 65535)
        (throw (ex-info "typed browser port is invalid" {})))
      port)))

(defn -main [& _]
  (let [directory
        (java.nio.file.Files/createTempDirectory
         "oscope-typed-browser-"
         (make-array java.nio.file.attribute.FileAttribute 0))
        root (java.io.File. (str directory))
        db-spec (str "chdb:" (.resolve directory "telemetry"))
        lifecycle* (atom nil)
        stopped? (atom false)
        stop!
        (fn []
          (when (compare-and-set! stopped? false true)
            (when-let [lifecycle @lifecycle*]
              (server/stop! lifecycle))
            (delete-tree! root)))]
    (try
      (let [historical (server/start! {:port 0 :db-spec db-spec})]
        (try
          (ingest-historical! historical)
          (finally
            (server/stop! historical))))
      (let [lifecycle
            (server/start!
             {:port (configured-port) :db-spec db-spec
              :typed-schema
              {:approved-manifest (approved-manifest)
               :registry-backend (backend/memory-backend)}})]
        (reset! lifecycle* lifecycle)
        (.addShutdownHook (Runtime/getRuntime) (Thread. stop!))
        (println (str "oscope typed browser fixture ready at http://127.0.0.1:"
                      (:port lifecycle) "/oscope"))
        @(promise))
      (finally
        (stop!)))))
