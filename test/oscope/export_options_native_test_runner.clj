(ns oscope.export-options-native-test-runner
  "Run existing real capture/fresh-reader fixtures with explicit encoding.
  One native writer lifetime per invocation; reader receipt checks are unchanged."
  (:require [clojure.test :as test]
            [jdbc.chdb.native :as native]
            [oscope.durable-native-child-runner :as child]
            [oscope.embedded :as embedded]
            [oscope.server :as server]))

(defn -main [mode consumer]
  (let [options (case mode
                  "configured" {:insert-format :json-compact-each-row :json-backend :configured}
                  "owned" {:insert-format :json-compact-each-row
                           :json-backend :native-guarded-byte-batch
                           :owned-statement-output? true}
                  "stream" {:insert-format :json-compact-each-row
                            :json-backend :native-guarded-byte-batch
                            :owned-statement-output? true
                            :datetime64-wire :raw-ticks}
                  "stream-fallback" {:insert-format :json-compact-each-row
                                     :json-backend :configured
                                     :datetime64-wire :raw-ticks}
                  (throw (ex-info "Unknown encoding fixture mode" {})))
        index (case consumer "standalone" "2" "embedded" "4" "embedded-typed" "3"
                    (throw (ex-info "Unknown encoding fixture consumer" {})))
        start-embedded embedded/start!
        start-server server/start!
        run-tests test/test-vars
        insert native/chdb-stream-insert-n
        stream-calls (atom 0)
        streaming? (contains? #{"stream" "stream-fallback"} mode)
        handoff (fn [args]
                  (if (and streaming? (= consumer "standalone"))
                    ;; This fixture must route options from its versioned file,
                    ;; not repair a dropped option at the server handoff.
                    (do
                      (when-not (and (= options (:export-options args))
                                     (true? (get-in args [:db-spec :owned-compact-stream?])))
                        (throw (ex-info "Configured streaming options were not routed"
                                        {:type ::unrouted-stream-options})))
                      args)
                    (cond-> (assoc args :export-options options)
                      streaming? (assoc-in [:db-spec :owned-compact-stream?] true))))]
    (println :export-encoding-fixture mode consumer)
    (with-redefs [embedded/start! (fn [args] (start-embedded (handoff args)))
                  server/start! (fn [args] (start-server (handoff args)))
                  native/chdb-stream-insert-n
                  (fn [& args] (swap! stream-calls inc) (apply insert args))
                  test/test-vars
                  (fn [vars]
                    (let [result
                          (if (and streaming? (= consumer "standalone"))
                            (let [binding-var (ns-resolve 'oscope.durable-integration-test
                                                         '*storage-options*)]
                              (when-not binding-var
                                (throw (ex-info "Configured fixture binding is unavailable" {})))
                              (with-bindings {binding-var {:owned-compact-stream? true
                                                           :export-options options}}
                                (run-tests vars)))
                            (run-tests vars))]
                      ;; Before the child emits its terminal receipt/exits.
                      ;; A no-op fixture must not qualify streaming merely by
                      ;; accepting configuration or keeping the old assertions.
                      (when (= mode "stream")
                        (when-not (pos? @stream-calls)
                          (throw (ex-info "Native stream execution was not observed"
                                          {:type ::unobserved-stream})))
                        (println :stream-native-calls @stream-calls))
                      (when (= mode "stream-fallback")
                        (when-not (zero? @stream-calls)
                          (throw (ex-info "Ordinary statements unexpectedly used streaming"
                                          {:type ::unexpected-stream})))
                        (println :stream-fallback-native-calls @stream-calls))
                      result))]
      (child/-main "--fixture" index))))
