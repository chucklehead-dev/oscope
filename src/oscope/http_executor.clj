(ns oscope.http-executor
  "Bounded admission for the standalone jolt-http handler executor."
  (:import [java.util.concurrent ArrayBlockingQueue Executor ThreadPoolExecutor
            TimeUnit]))

(def ^:private shutdown-timeout-ms 5000)

(defn start!
  "Create a fixed handler pool with `workers` running slots and at most
  `queue-capacity` additional admitted tasks.

  Jolt 0.8.3 models ThreadPoolExecutor's ArrayBlockingQueue constructor, but
  its executor still uses an unbounded internal queue. The Executor wrapper is
  therefore the portable rejection boundary: one token covers each running or
  waiting task, and a failed `offer` rejects synchronously before the delegate
  can retain more work. Tokens are released by the task or, if delegation
  itself fails, by the submitting thread."
  [{:keys [workers queue-capacity]}]
  (let [delegate (ThreadPoolExecutor.
                  workers workers 0 TimeUnit/MILLISECONDS
                  (ArrayBlockingQueue. queue-capacity))
        admissions (ArrayBlockingQueue. (+ workers queue-capacity))
        executor
        (reify Executor
          (execute [_ task]
            (when-not (.offer admissions true)
              (throw (ex-info "oscope HTTP executor is at capacity"
                              {:oscope.http-executor/error true
                               :type ::rejected
                               :workers workers
                               :queue-capacity queue-capacity})))
            (try
              (.execute delegate
                        (fn []
                          (try
                            (task)
                            (finally
                              (.poll admissions)))))
              (catch Throwable error
                (.poll admissions)
                (throw error)))))]
    {:executor executor
     :delegate delegate
     :admissions admissions
     :workers workers
     :queue-capacity queue-capacity
     :rejection-policy :abort}))

(defn active-count [executor]
  (.size (:admissions executor)))

(defn queue-depth [executor]
  (.size (.getQueue (:delegate executor))))

(defn terminated? [executor]
  (.isTerminated (:delegate executor)))

(defn stop!
  "Drain and terminate the owned delegate after jolt-http has stopped ingress."
  [executor]
  (let [delegate (:delegate executor)]
    (.shutdown delegate)
    (when-not (.awaitTermination delegate shutdown-timeout-ms
                                 TimeUnit/MILLISECONDS)
      (throw (ex-info "oscope HTTP executor did not terminate"
                      {:oscope.http-executor/error true
                       :type ::termination-timeout
                       :timeout-ms shutdown-timeout-ms})))
    true))
