(ns oscope.managed-config-load-worker
  "Independent normal-config loader for the managed-store restart proof."
  (:require [oscope.config-cli :as config-cli])
  (:import [java.nio.file Files Paths]))

(defn -main [root expected-port result-path]
  (let [resolved (config-cli/load-config
                  [] {"XDG_CONFIG_HOME" root})
        accepted? (and (= :managed-user (:config-origin resolved))
                       (= (parse-long expected-port)
                          (get-in resolved [:config :server :port]))
                       (= :file
                          (get-in resolved
                                  [:provenance [:server :port]])))]
    (Files/write (Paths/get result-path (make-array String 0))
                 (.getBytes (if accepted? "ok" "mismatch") "UTF-8")
                 (make-array java.nio.file.OpenOption 0))
    (shutdown-agents)))
