(ns oscope.s3-checksum-native-test-runner
  "Loopback-only application capture with real TLS and a separate reader."
  (:require [clojure.data.json :as json]
            [clojure.test :as test :refer [deftest is]]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.s3-curl :as curl]
            [oscope.config :as config]
            [oscope.durable-config-runtime :as runtime]
            [oscope.durable-s3-integration-test :as fixture]
            [oscope.server :as server]
            [typed-storage-confirmation-profile :as profile]))

(def ^:private settings (atom nil))

(defn- fixture-call [name & args]
  (apply (ns-resolve 'oscope.durable-s3-integration-test name) args))

(defn- configured-options [endpoint instance selected?]
  (runtime/server-options
   (config/resolve-config
    [[:file {:version 2 :server {:port 0}
             :storage
             {:type :durable-s3 :instance instance :lease-ttl-ms 30000
              :scratch-parent (:root @settings) :checkpoint-every-batches 2
              :s3 (cond-> {:endpoint endpoint :bucket "oscope-durable"
                           :prefix "integration" :region "us-east-1"
                           :object-id "private-object-7f2c91"
                           :credentials {:type :environment
                                         :access-key-env "ACCESS_REF"
                                         :secret-key-env "SECRET_REF"
                                         :session-token-env "SESSION_REF"}}
                    selected? (assoc :wal-upload-confirmation :sha256-single-put))}}]])
   {"ACCESS_REF" "ACCESS" "SECRET_REF" "SECRET" "SESSION_REF" "SESSION"}))

(defn- write-capture! []
  (let [{:keys [endpoint root]} @settings
        bucket (curl/request!
                {:method :put :url (str endpoint "/oscope-durable") :headers {}
                 :request-body {:bytes (byte-array 0) :byte-count 0}
                 :auth {:access-key "ACCESS" :secret-key "SECRET" :session-token "SESSION"}
                 :region "us-east-1" :connect-timeout-ms 2000 :timeout-ms 10000})
        options (configured-options endpoint "s3-writer" true)
        store (backend/object-backend (get-in options [:db-spec :namespace-backend])
                                      (get-in options [:db-spec :object-id]))]
    (is (= 200 (:status bucket)))
    (let [lifecycle (server/start! options)]
      (try
        (is (pos? (:port lifecycle)))
        (is (= 2 (get-in (control/read-head! store) [:head "manifest" "seq"])))
        (let [now (* (System/currentTimeMillis) 1000000)]
          (doseq [[path payload expected]
                  [["/v1/traces" (fixture-call 'trace-wire now) 3]
                   ["/v1/logs" (fixture-call 'log-wire now) 5]
                   ["/v1/metrics" (fixture-call 'metric-wire now) 8]]]
            (is (= 200 (fixture-call 'post-json! (:port lifecycle) path payload)))
            (is (= expected (get-in (control/read-head! store) [:head "manifest" "seq"])))))
        (finally
          (is (= :closed (:status (server/stop! lifecycle)))))))
    (let [head (:head (control/read-head! store)) wire (json/write-str head)]
      (is (nil? (get-in head ["lease" "owner"])))
      (is (= 8 (get-in head ["manifest" "seq"])))
      (is (= 3 (count (get-in head ["manifest" "wal"]))))
      (spit (str root "/seal.json")
            (json/write-str {"version" 1 "endpoint" endpoint
                             "head-json" wire "head-sha256" (fixture-call 'sha256 wire)})))))

(deftest application-capture-or-independent-reader
  (let [{:keys [phase root]} @settings counts (atom (profile/initial-counts))]
    (profile/with-observers!
     counts
     (fn []
       (if (= phase "reader")
         ;; Reuse the exact full-value/export-format assertions. Only replace
         ;; its MinIO configuration helper with our validated TLS configuration;
         ;; reader configuration deliberately omits the confirmation option.
         (with-redefs-fn
           {(ns-resolve 'oscope.durable-s3-integration-test 'options)
            (fn [endpoint instance] (configured-options endpoint instance false))}
           #(fixture/assert-fresh-reader! (java.io.File. (str root "/seal.json"))))
         (write-capture!))))
    (if (= phase "reader")
      (is (= (profile/initial-counts) @counts))
      (do
        ;; Exactly five physical telemetry inserts: traces, logs and three
        ;; metric tables. Startup/cadence checkpoints retain normal verification.
        (is (= 5 (:file-publications @counts)))
        (println :s3-checksum-app-observed (:file-publications @counts)
                 (:verification-completions @counts) (:valid-proof-observations @counts))
        (try
          (profile/report! counts "candidate" "window")
          (catch Throwable _
            (println :s3-checksum-app-selection-rejected)
            (throw (ex-info "Application did not select checksum confirmation" {}))))))))

(defn -main [phase endpoint ca root]
  (when-not (and (contains? #{"writer" "writer-drop-option" "reader"} phase)
                 (re-matches #"https://127\.0\.0\.1:[0-9]{1,5}" endpoint)
                 (.isFile (java.io.File. ca)) (.isDirectory (java.io.File. root)))
    (throw (ex-info "Explicit loopback TLS fixture arguments required" {})))
  (reset! settings {:phase phase :endpoint endpoint :root root})
  (when (not= phase "reader")
    (let [code (try (curl/request! {:method :get :url (str endpoint "/oscope-durable")
                                   :headers {} :connect-timeout-ms 2000 :timeout-ms 10000
                                   :auth {:access-key "ACCESS" :secret-key "SECRET" :session-token "SESSION"}
                                   :region "us-east-1"})
                    nil (catch Throwable e (:curl-code (ex-data e))))]
      (when-not (= 60 code) (throw (ex-info "Untrusted fixture CA was not rejected" {:curl-code code})))))
  (let [set-string! @#'curl/set-string! backend-fn curl/s3-backend
        overrides (cond->
                   {#'curl/set-string!
                    (fn [handle option value stage]
                      (when (= :url stage) (set-string! handle 10065 ca :test-ca))
                      (set-string! handle option value stage))}
                    (= phase "writer-drop-option")
                    (assoc #'curl/s3-backend
                           (fn [options] (backend-fn (dissoc options :wal-upload-confirmation)))))]
    (with-redefs-fn overrides
      (fn []
        (let [result (test/run-tests 'oscope.s3-checksum-native-test-runner)]
          (println :s3-checksum-app-result (:test result) (:pass result)
                   (:fail result) (:error result))
          (when (or (not= 1 (:test result))
                    (< (:pass result) (if (= phase "reader") 21 14))
                    (pos? (+ (:fail result) (:error result))))
            (throw (ex-info "Application checksum fixture failed" {})))
          (println :s3-checksum-app-qualified phase))))))
