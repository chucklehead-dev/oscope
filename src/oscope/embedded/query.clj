(ns oscope.embedded.query
  "Bounded background query lifecycle for embedded oscope consumers.

  Query selection and display-model construction remain caller-owned. This
  namespace owns only execution policy: blocking work runs on one OS thread,
  while a Jolt fiber controls cadence and publishes immutable snapshots."
  (:require [jolt.fibers :as fibers])
  (:import [java.util.concurrent Executors TimeUnit]))

(def default-options
  {:interval-ms 1000
   :timeout-ms 5000
   :stop-timeout-ms 1000
   :max-rows 100
   :max-error-chars 160})

(def ^:private timeout-token ::timeout)
(def ^:private continue-token ::continue)
(def ^:private tick-token ::tick)
(def ^:private stopped-token ::stopped)
(def ^:private result-poll-ms 10)

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
        (update :max-rows bounded-integer! :max-rows 1 1000)
        (update :max-error-chars bounded-integer! :max-error-chars 0 1000))))

(defn- bounded-string [value limit]
  (let [value (str (or value ""))
        length (count value)]
    {:text (subs value 0 (min length limit))
     :truncated? (> length limit)}))

(defn- error-summary [error max-error-chars]
  (let [{class-text :text class-truncated? :truncated?}
        (bounded-string (str (class error)) max-error-chars)
        {message-text :text message-truncated? :truncated?}
        (bounded-string (try (ex-message error) (catch Throwable _ nil))
                        max-error-chars)]
    {:type :query-error
     :class class-text
     :class-truncated? class-truncated?
     :message message-text
     :message-truncated? message-truncated?}))

(defn- bounded-rows [rows max-rows]
  (when-not (or (nil? rows) (sequential? rows))
    (fail! ::invalid-result
           "embedded query :load! must return a sequential row collection or nil"
           (type rows)))
  ;; Realize on the owned query thread so lazy JDBC work cannot escape to the
  ;; cadence fiber or a UI/render caller.
  (vec (take max-rows rows)))

(defn- submit-query! [executor {:keys [load! max-rows max-error-chars]}]
  (let [result (promise)
        attempted-at (System/currentTimeMillis)]
    (try
      (.execute executor
                (fn []
                  (deliver
                   result
                   (try
                     {:status :ready
                      :rows (bounded-rows (load!) max-rows)}
                     (catch Throwable error
                       {:status :failed
                        :failure (error-summary error max-error-chars)})))))
      (catch Throwable error
        (deliver result {:status :failed
                         :failure (error-summary error max-error-chars)})))
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
                   :failure {:type :timeout :timeout-ms timeout-ms}))))

(defn- stop-requested? [stop-signal timeout-ms]
  (not= tick-token (deref stop-signal timeout-ms tick-token)))

(defn- await-result-or-stop [result stop-signal timeout-ms]
  (loop [remaining timeout-ms]
    (if (not= continue-token (deref stop-signal 0 continue-token))
      stopped-token
      (let [wait-ms (min result-poll-ms remaining)
            outcome (deref result wait-ms timeout-token)]
        (cond
          (not= timeout-token outcome) outcome
          (= wait-ms remaining) timeout-token
          :else (recur (- remaining wait-ms)))))))

(defn- run-worker! [executor model stop-signal options]
  (loop [job nil]
    (if (not= continue-token (deref stop-signal 0 continue-token))
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

(defn- stop-result [state]
  {:status (if (= :closed (:phase state)) :closed :stopping)
   :phase (:phase state)})

(defn- stop-lifecycle!
  [{:keys [stop-signal worker executor options state lock]}]
  (locking lock
    (if (= :closed (:phase @state))
      (stop-result @state)
      (do
        (deliver stop-signal true)
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
          (.shutdown executor)
          (swap! state assoc :executor-shutdown? true))
        (when (and (:executor-shutdown? @state)
                   (.awaitTermination executor (:stop-timeout-ms options)
                                      TimeUnit/MILLISECONDS))
          (swap! state assoc :phase :closed))
        (stop-result @state)))))

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
        stop-signal (promise)
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
