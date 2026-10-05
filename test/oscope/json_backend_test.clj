(ns oscope.json-backend-test
  (:require [clojure.test :as test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [jdbc.chdb.json-each-row :as encoder]
            [oscope.config :as config]
            [oscope.config-cli :as cli]
            [oscope.embedded :as embedded]
            [oscope.embedded-durable-integration-test :as embedded-fixture]
            [oscope.json-backend :as backend]
            [oscope.server :as server]
            [oscope.typed-standalone-restart-integration-test :as socket-fixture]))

(deftest explicit-config-is-closed-and-reaches-standalone-options
  (doseq [value [:configured :native-guarded]]
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
      (doseq [start [#(server/start! %)
                    #(embedded/start! (assoc % :db-spec ::unused))]
              value [:unknown false]]
        (is (thrown? clojure.lang.ExceptionInfo (start {:json-backend value}))))
      (with-redefs [encoder/open-encoder (fn [_] (throw unavailable))]
        (doseq [start [#(server/start! %)
                      #(embedded/start! (assoc % :db-spec ::unused))]]
          (is (identical? unavailable
                          (try (start {:json-backend :native-guarded})
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

(defn -main [& [slice]]
  ;; Native storage lifetimes are process-owned; do not combine ordinary socket
  ;; storage with an independent Durable writer in one process.
  (let [vars (case (or slice "controls")
               "controls" [#'explicit-config-is-closed-and-reaches-standalone-options
                            #'runtime-rejects-before-database-acquisition
                            #'configured-startup-does-not-load-native-writer
                            #'explicit-native-startup-probe-is-real]
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
