(ns oscope.export-options-native-test-runner
  "Run existing real capture/fresh-reader fixtures with explicit encoding.
  One native writer lifetime per invocation; reader receipt checks are unchanged."
  (:require [oscope.durable-native-child-runner :as child]
            [oscope.embedded :as embedded]
            [oscope.server :as server]))

(defn -main [mode consumer]
  (let [options (case mode
                  "configured" {:insert-format :json-compact-each-row :json-backend :configured}
                  "owned" {:insert-format :json-compact-each-row
                           :json-backend :native-guarded-byte-batch
                           :owned-statement-output? true}
                  (throw (ex-info "Unknown encoding fixture mode" {})))
        index (case consumer "standalone" "2" "embedded" "4" "embedded-typed" "3"
                    (throw (ex-info "Unknown encoding fixture consumer" {})))
        start-embedded embedded/start!
        start-server server/start!]
    (println :export-encoding-fixture mode consumer)
    (with-redefs [embedded/start! (fn [args] (start-embedded (assoc args :export-options options)))
                  server/start! (fn [args] (start-server (assoc args :export-options options)))]
      (child/-main "--fixture" index))))
