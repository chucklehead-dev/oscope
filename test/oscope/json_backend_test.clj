(ns oscope.json-backend-test
  (:require [clojure.test :as test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [jdbc.chdb.json-each-row :as encoder]
            [jdbc.chdb.durable.backend :as durable-backend]
            [oscope.config :as config]
            [oscope.config-cli :as cli]
            [oscope.embedded :as embedded]
            [oscope.durable-config-runtime :as durable-runtime]
            [oscope.embedded-durable-integration-test :as embedded-fixture]
            [oscope.json-backend :as backend]
            [oscope.server :as server]
            [oscope.typed-standalone-restart-integration-test :as socket-fixture]))

(deftest explicit-config-is-closed-and-reaches-standalone-options
  (doseq [value [:configured :native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
    (let [resolved (config/resolve-config
                    [[:file {:version 2 :ingest {:json-backend value}}]])]
      (is (= value (:json-backend (cli/server-options resolved))))
      (is (= (:config resolved)
             (config/parse (config/encode (:config resolved)))))))
  (doseq [value [:unknown nil false "native-guarded"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (config/resolve-config [[:file {:ingest {:json-backend value}}]]))))
  (is (thrown? clojure.lang.ExceptionInfo
               (config/validate (-> config/defaults
                                    (assoc :version 1)
                                    (dissoc :typed-attributes)
                                    (assoc-in [:ingest :json-backend] :native-guarded)))))
  (is (not (contains? (cli/server-options (config/resolve-config [])) :json-backend))))

(deftest runtime-rejects-before-database-acquisition
  (let [opens (atom 0)
        unavailable (ex-info "Native fixture unavailable" {:type :fixture})]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc))]
      (doseq [start [server/start! #(embedded/start! (assoc % :db-spec ::unused))] value [:unknown false]]
        (is (thrown? clojure.lang.ExceptionInfo (start {:json-backend value}))))
      (with-redefs [encoder/open-encoder (fn [_] (throw unavailable))]
        (doseq [start [server/start! #(embedded/start! (assoc % :db-spec ::unused))] selected [:native-guarded :native-guarded-string-cache :native-guarded-byte-batch]]
          (is (identical? unavailable
                          (try (start {:json-backend selected})
                               (catch Throwable error error))))))
      (is (zero? @opens)))))

(deftest configured-startup-does-not-load-native-writer
  (with-redefs [encoder/open-encoder
                (fn [_] (throw (ex-info "Must not probe default" {})))]
    (is (= :configured (backend/validate! :configured)))))

(deftest explicit-native-startup-probe-is-real
  (is (= :native-guarded (backend/validate! :native-guarded))))

(deftest native-file-selected-typed-socket-and-restart
  (binding [socket-fixture/*json-backend* :native-guarded]
    (socket-fixture/file-configured-typed-standalone-restarts-read-only)))

(deftest native-embedded-approved-schema-and-live-query
  (binding [embedded-fixture/*json-backend* :native-guarded]
    (embedded-fixture/approved-manifest-drives-embedded-sdk-ingestion-and-live-query)))

(deftest compact-config-and-runtime-validation-are-closed
  (doseq [format [:json-each-row :json-compact-each-row]]
    (let [resolved (config/resolve-config [[:file {:version 2 :ingest {:insert-format format}}]])]
      (is (= format (:insert-format (cli/server-options resolved))))
      (is (= (:config resolved) (config/parse (config/encode (:config resolved)))))
      (is (= format (backend/validate-format! format)))))
  (is (not (contains? (cli/server-options (config/resolve-config [])) :insert-format)))
  (is (thrown? clojure.lang.ExceptionInfo
               (config/validate (-> config/defaults
                                    (assoc :version 1)
                                    (dissoc :typed-attributes)
                                    (assoc-in [:ingest :insert-format] :json-compact-each-row)))))
  (let [opens (atom 0)]
    (with-redefs [jdbc/connection (fn [& _] (swap! opens inc))]
      (doseq [format [:unknown false "JSONCompactEachRow"]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (config/resolve-config [[:file {:ingest {:insert-format format}}]])))
        (doseq [start [server/start! #(embedded/start! (assoc % :db-spec ::unused))]]
          (is (thrown? clojure.lang.ExceptionInfo (start {:insert-format format})))))
      (is (zero? @opens)))))

(deftest durable-config-plumbing-retains-both-selections
  (let [resolved (config/resolve-config
                  [[:file {:version 2
                           :ingest {:json-backend :native-guarded-string-cache
                                    :insert-format :json-compact-each-row}
                           :storage {:type :durable-local :root "/tmp/oscope-control-not-opened"}}]])
        options (durable-runtime/server-options
                 resolved (fn [& _] (throw (Exception. "must not request credentials")))
                 {:local-backend-fn (fn [_] (durable-backend/memory-backend))
                  :instance-fn (constantly "control-instance")})]
    (is (= {:json-backend :native-guarded-string-cache :insert-format :json-compact-each-row}
           (select-keys options [:json-backend :insert-format])))))

(deftest explicit-string-cache-startup-probe-is-real
  (is (= :native-guarded-string-cache (backend/validate! :native-guarded-string-cache))))

(deftest compact-string-cache-typed-socket-and-restart
  (binding [socket-fixture/*json-backend* :native-guarded-string-cache
            socket-fixture/*insert-format* :json-compact-each-row]
    (socket-fixture/file-configured-typed-standalone-restarts-read-only)))

(deftest compact-string-cache-embedded-approved-schema-and-live-query
  (binding [embedded-fixture/*json-backend* :native-guarded-string-cache
            embedded-fixture/*insert-format* :json-compact-each-row]
    (embedded-fixture/approved-manifest-drives-embedded-sdk-ingestion-and-live-query)))

(deftest explicit-byte-batch-startup-probe-is-real
  (is (= :native-guarded-byte-batch (backend/validate! :native-guarded-byte-batch))))

(deftest byte-batch-typed-socket-and-restart
  (binding [socket-fixture/*json-backend* :native-guarded-byte-batch
            socket-fixture/*insert-format* :json-compact-each-row]
    (socket-fixture/file-configured-typed-standalone-restarts-read-only)))

(deftest byte-batch-embedded-approved-schema-and-live-query
  (binding [embedded-fixture/*json-backend* :native-guarded-byte-batch
            embedded-fixture/*insert-format* :json-compact-each-row]
    (embedded-fixture/approved-manifest-drives-embedded-sdk-ingestion-and-live-query)))

(deftest byte-batch-direct-sdk-recovery-uses-prefixed-materialization
  (require 'clojure.data.json.jolt-native)
  (let [writer-var (ns-resolve 'clojure.data.json.jolt-native 'write-prefixed-batch-text!)
        writer (when writer-var @writer-var) calls (atom 0)]
    (is (ifn? writer))
    (when (ifn? writer)
      (with-redefs-fn
        {writer-var (fn [prefix rows limit]
                      (swap! calls inc)
                      (writer prefix rows limit))}
        #(binding [embedded-fixture/*json-backend* :native-guarded-byte-batch
                   embedded-fixture/*insert-format* :json-compact-each-row]
           (embedded-fixture/direct-sdk-exports-survive-a-fresh-durable-reader)))
      ;; Observing delegated calls proves the real application path selected
      ;; this codec; a green test with silently ignored options cannot pass.
      (is (pos? @calls)))))

(defn -main [& [slice]]
  ;; Native storage lifetimes are process-owned; do not combine ordinary socket
  ;; storage with an independent Durable writer in one process.
  (let [vars (case (or slice "controls")
               "controls" [#'explicit-config-is-closed-and-reaches-standalone-options
                            #'runtime-rejects-before-database-acquisition
                            #'configured-startup-does-not-load-native-writer
                            #'explicit-native-startup-probe-is-real
                            #'compact-config-and-runtime-validation-are-closed]
               "byte-controls" [#'explicit-config-is-closed-and-reaches-standalone-options
                                 #'runtime-rejects-before-database-acquisition
                                 #'configured-startup-does-not-load-native-writer
                                 #'compact-config-and-runtime-validation-are-closed
                                 #'durable-config-plumbing-retains-both-selections
                                 #'explicit-byte-batch-startup-probe-is-real]
               "byte-standalone" [#'byte-batch-typed-socket-and-restart]
               "byte-embedded" [#'byte-batch-embedded-approved-schema-and-live-query]
               "byte-recovery" [#'byte-batch-direct-sdk-recovery-uses-prefixed-materialization]
               "cached-controls" [#'explicit-config-is-closed-and-reaches-standalone-options
                                   #'runtime-rejects-before-database-acquisition
                                   #'configured-startup-does-not-load-native-writer
                                   #'compact-config-and-runtime-validation-are-closed
                                   #'durable-config-plumbing-retains-both-selections
                                   #'explicit-string-cache-startup-probe-is-real]
               "cached-standalone" [#'compact-string-cache-typed-socket-and-restart]
               "cached-embedded" [#'compact-string-cache-embedded-approved-schema-and-live-query]
               "standalone" [#'native-file-selected-typed-socket-and-restart]
               "embedded" [#'native-embedded-approved-schema-and-live-query]
               (throw (ex-info "Unknown native JSON test slice" {})))
        counters (ref test/*initial-report-counters*)]
    (binding [test/*report-counters* counters]
      (test/test-vars vars)
      (test/do-report (assoc @counters :type :summary)))
    (let [result @counters]
      (System/exit (if (and (pos? (:pass result))
                           (zero? (+ (:fail result) (:error result)))) 0 1)))))
