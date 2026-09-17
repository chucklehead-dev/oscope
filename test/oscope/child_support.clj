(ns oscope.child-support
  "Test-only subprocess ownership. A termination request is not an exit receipt."
  (:require [jolt.process :as process]))

(defn- await-child [child milliseconds]
  (try
    (let [result (deref child milliseconds ::timeout)]
      (cond
        (= ::timeout result) {:failure :timeout}
        (and (map? result) (integer? (:exit result))) {:result result}
        :else {:failure :invalid-receipt}))
    (catch Throwable _ {:failure :wait-threw})))

(defn run!
  "Return the original process result on ordinary settled completion.
  timeout-ms bounds initial wait; settlement-ms bounds the SAME child's wait
  after owned tree termination. after-terminal! runs exactly once only after
  a process exit receipt, including nonzero exit. Never delete owned scratch
  outside that callback. Failure descriptors do not include raw exceptions.
  This proves direct-child settlement, not universal descendant retirement."
  [command process-options {:keys [timeout-ms settlement-ms after-terminal!]
                            :or {timeout-ms 120000 settlement-ms 5000}}]
  (let [spawn (try {:child (process/process command process-options)}
                   (catch Throwable _ {:failure :spawn-threw}))]
    (if-let [child (:child spawn)]
      (let [initial (await-child child timeout-ms)
            terminate-error (when (:failure initial)
                              (try (process/destroy-tree child) nil
                                   (catch Throwable _ :termination-threw)))
            settled (if (:failure initial)
                      (await-child child settlement-ms) initial)
            result (:result settled)
            cleanup-error (when (and result after-terminal!)
                            (try (after-terminal!) nil
                                 (catch Throwable _ :cleanup-threw)))
            primary (:failure initial)]
        (if (and result (nil? primary) (nil? cleanup-error))
          result
          {:exit 1 :out "" :err ""
           :child/status (if result :settled-failure :cleanup-incomplete)
           :child/primary (or primary (when (and result (not (zero? (:exit result))))
                                       :nonzero-exit) :none)
           :child/terminal? (boolean result)
           :child/termination (or terminate-error :requested-or-unneeded)
           :child/cleanup (or cleanup-error (if result :completed :preserved))}))
      {:exit 1 :out "" :err "" :child/status :spawn-failed
       :child/primary :spawn-threw :child/terminal? false
       :child/cleanup :preserved})))
