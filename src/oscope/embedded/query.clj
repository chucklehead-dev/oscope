(ns oscope.embedded.query
  "Bounded background query lifecycle for embedded oscope consumers.

  Query selection and display-model construction remain caller-owned. This
  namespace owns only execution policy: blocking work runs on one OS thread,
  while a Jolt fiber controls cadence and publishes immutable snapshots."
  (:require [clojure.core.async :as async]
            [jolt.fibers :as fibers]
            [oscope.error :as error])
  (:import [java.util.concurrent Executors TimeUnit]))

(def default-options
  {:interval-ms 1000
   :timeout-ms 5000
   :stop-timeout-ms 1000
   :max-rows 100})

(def ^:private timeout-token ::timeout)
(def ^:private stopped-token ::stopped)

(defn- fail! [type message value]
  (throw (ex-info message
                  {:oscope.embedded.query/error true
                   :type type
                   :value value})))

(defn- bounded-integer! [value key minimum maximum]
  (when-not (and (integer? value) (<= minimum value maximum))
    (fail! ::invalid-option
           (str (name key) " must be between " minimum " and " maximum)
           value))
  value)

(defn- normalize-options [options]
  (when-not (map? options)
    (fail! ::invalid-options "embedded query options must be a map" options))
  (let [options (merge default-options options)]
    (when-not (ifn? (:load! options))
      (fail! ::invalid-load "embedded query :load! must be callable"
             (:load! options)))
    (-> options
        (update :interval-ms bounded-integer! :interval-ms 1 60000)
        (update :timeout-ms bounded-integer! :timeout-ms 1 60000)
        (update :stop-timeout-ms bounded-integer! :stop-timeout-ms 1 60000)
        (update :max-rows bounded-integer! :max-rows 1 1000))))

(defn- failure-summary
  "Return a closed public query-failure descriptor.

  The Throwable is deliberately not accepted: class names, messages, causes,
  ex-data, stacks, SQL, values, paths, endpoints, and credentials therefore
  cannot become reachable from the immutable snapshot."
  [phase category]
  {:type :query-failure
   :phase phase
   :category category})

(defn- bounded-rows [rows max-rows]
  (when-not (or (nil? rows) (sequential? rows))
    (fail! ::invalid-result
           "embedded query :load! must return a sequential row collection or nil"
           (type rows)))
  ;; Realize on the owned query thread so lazy JDBC work cannot escape to the
  ;; cadence fiber or a UI/render caller.
  (vec (take max-rows rows)))

(defn- execute-query! [executor task]
  (.execute executor task))

(defn- submit-query! [executor {:keys [load! max-rows]}]
  (let [result (async/chan 1)
        attempted-at (System/currentTimeMillis)]
    (try
      (execute-query!
       executor
       (fn []
         (async/>!!
          result
          (try
            {:status :ready
             :rows (bounded-rows (load!) max-rows)}
            (catch Throwable _
              {:status :failed
               :failure (failure-summary :load :load-failed)})))))
      (catch Throwable _
        (async/>!! result {:status :failed
                           :failure (failure-summary
                                     :submission
                                     :executor-submission-failed)})))
    {:result result :attempted-at-unix-ms attempted-at}))

(defn- publish-outcome! [model job outcome]
  (let [attempted-at (:attempted-at-unix-ms job)]
    (if (= :ready (:status outcome))
      (reset! model {:status :ready
                     :rows (:rows outcome)
                     :attempted-at-unix-ms attempted-at
                     :sampled-at-unix-ms (System/currentTimeMillis)})
      (let [prior @model
            usable? (some? (:sampled-at-unix-ms prior))]
        (reset! model
                (assoc prior
                       :status (if usable? :stale :error)
                       :attempted-at-unix-ms attempted-at
                       :failure (:failure outcome)))))))

(defn- publish-timeout! [model job timeout-ms]
  (let [prior @model
        usable? (some? (:sampled-at-unix-ms prior))]
    (reset! model
            (assoc prior
                   :status (if usable? :stale :error)
                   :attempted-at-unix-ms (:attempted-at-unix-ms job)
                   :failure (assoc (failure-summary :load :timeout)
                                   :timeout-ms timeout-ms)))))

(defn- stop-requested? [stop-signal timeout-ms]
  (let [tick (async/timeout timeout-ms)
        [_ port] (async/alts! [stop-signal tick] :priority true)]
    (not (identical? tick port))))

(defn- select-result-stop-or-timeout [result stop-signal timeout]
  (let [[outcome port]
        ;; Preserve the prior stop-first boundary behavior. A ready result wins
        ;; over a simultaneous timeout; stop wins over both and suppresses any
        ;; publication after lifecycle shutdown has begun.
        (async/alts! [stop-signal result timeout] :priority true)]
    (cond
      (identical? stop-signal port) stopped-token
      (identical? result port) outcome
      :else timeout-token)))

(defn- await-result-or-stop [result stop-signal timeout-ms]
  (select-result-stop-or-timeout result stop-signal (async/timeout timeout-ms)))

(defn- run-worker! [executor model stop-signal options]
  (loop [job nil]
    (if (some? (async/poll! stop-signal))
      :stopped
      (let [job (or job (submit-query! executor options))
            outcome (await-result-or-stop (:result job) stop-signal
                                          (:timeout-ms options))]
        (cond
          (= stopped-token outcome) :stopped

          (= timeout-token outcome)
          (do
            (publish-timeout! model job (:timeout-ms options))
            (if (stop-requested? stop-signal (:interval-ms options))
              :stopped
              (recur job)))

          :else
          (do
            (publish-outcome! model job outcome)
            (if (stop-requested? stop-signal (:interval-ms options))
              :stopped
              (recur nil))))))))

(defn- stop-result [state failure]
  (cond-> {:status (if (= :closed (:phase state)) :closed :stopping)
           :phase (:phase state)}
    failure (assoc :errors [failure])))

(defn- shutdown-query-executor! [executor]
  (.shutdown executor))

(defn- await-query-executor! [executor timeout-ms]
  (.awaitTermination executor timeout-ms TimeUnit/MILLISECONDS))

(defn- stop-lifecycle!
  [{:keys [stop-signal worker executor options state lock]}]
  (locking lock
    (if (= :closed (:phase @state))
      (stop-result @state nil)
      (try
        (async/offer! stop-signal true)
        (when-not (:worker-stopped? @state)
          (swap! state assoc :phase :stopping-cadence)
          (when-not (= timeout-token
                       (fibers/join worker (:stop-timeout-ms options)
                                    timeout-token))
            (swap! state assoc :worker-stopped? true
                   :phase :joining-query)))
        (when (and (:worker-stopped? @state)
                   (not (:executor-shutdown? @state)))
          ;; Never interrupt a native/JDBC operation: publish shutdown and wait
          ;; for the one owned thread to finish. A timed-out stop stays retryable.
          (shutdown-query-executor! executor)
          (swap! state assoc :executor-shutdown? true))
        (when (and (:executor-shutdown? @state)
                   (await-query-executor! executor
                                          (:stop-timeout-ms options)))
          (swap! state assoc :phase :closed))
        (stop-result @state nil)
        (catch Throwable failure
          (stop-result @state
                       (error/lifecycle-failure :stop-query-workers
                                                failure)))))))

(defn start!
  "Start a bounded background query lifecycle.

  `:load!` runs only on one owned OS thread and must return a sequential row
  collection (or nil). It must bound database work itself; `:max-rows` is a
  second retention bound. `:timeout-ms` marks a late sample stale/error but does
  not unsafely interrupt native work. Callers must obtain `:closed` from `stop!`
  before retiring the source or connection used by `:load!`."
  [{:keys [load!] :as options}]
  (let [options (normalize-options (assoc options :load! load!))
        model (atom {:status :loading
                     :rows []
                     :attempted-at-unix-ms nil
                     :sampled-at-unix-ms nil})
        stop-signal (async/promise-chan)
        executor (Executors/newSingleThreadExecutor)]
    (try
      (let [worker (fibers/spawn
                    #(run-worker! executor model stop-signal options))
            lifecycle {:model model
                       :stop-signal stop-signal
                       :worker worker
                       :executor executor
                       :options options
                       :state (atom {:phase :open
                                     :worker-stopped? false
                                     :executor-shutdown? false})
                       :lock (Object.)}]
        (assoc lifecycle :stop! #(stop-lifecycle! lifecycle)))
      (catch Throwable error
        (.shutdown executor)
        (throw error)))))

(defn snapshot
  "Return the current immutable display snapshot without running a query."
  [lifecycle]
  (if-let [model (:model lifecycle)]
    @model
    (fail! ::invalid-lifecycle "invalid embedded query lifecycle" lifecycle)))

(defn stop!
  "Stop cadence and join the owned query thread before reporting `:closed`.

  A timed-out call returns `{:status :stopping ...}` and is safe to retry. The
  cadence fiber observes stop independently of the query timeout, but native
  work is never interrupted: `:joining-query` may remain until that work really
  finishes. The caller must not close the shared oscope source until `:closed`
  is observed."
  [lifecycle]
  (if-let [stop-fn (:stop! lifecycle)]
    (stop-fn)
    (fail! ::invalid-lifecycle "invalid embedded query lifecycle" lifecycle)))
