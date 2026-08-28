(ns oscope.server-main
  (:require [oscope.server :as server]))

(def max-stop-attempts 3)

(defn stop-until-closed!
  "Drive the retryable server lifecycle to its terminal state or fail visibly."
  ([lifecycle] (stop-until-closed! lifecycle max-stop-attempts))
  ([lifecycle maximum-attempts]
   (when-not (and (integer? maximum-attempts) (pos? maximum-attempts))
     (throw (ex-info "oscope shutdown attempts must be positive"
                     {:oscope.server-main/error true
                      :maximum-attempts maximum-attempts})))
   (loop [attempt 1 errors []]
     (let [result (server/stop! lifecycle)
           errors (into errors (:errors result))]
       (cond
         (= :closed (:status result)) result
         (< attempt maximum-attempts) (recur (inc attempt) errors)
         :else
         (throw (ex-info "oscope could not complete ordered shutdown"
                         {:oscope.server-main/error true
                          :attempts attempt
                          :phase (:phase result)
                          :errors errors})))))))

(defn -main [& _]
  (let [lifecycle (server/start! (server/env-options))]
    (println (str "oscope receiving OTLP/HTTP and serving its viewer at http://"
                  (:host lifecycle) ":" (:port lifecycle) "/oscope"))
    (try
      @(promise)
      (finally
        ;; Do not report a clean process exit while an owned lifecycle boundary
        ;; remains open. A thrown terminal failure produces a non-zero exit.
        (stop-until-closed! lifecycle)
        (System/exit 0)))))
