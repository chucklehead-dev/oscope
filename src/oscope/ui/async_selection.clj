(ns oscope.ui.async-selection
  "Latest-wins execution policy for blocking native-view selections."
  (:require [clojure.walk :as walk]
            [oscope.command :as command]
            [oscope.effect :as effect])
  (:import [java.util.concurrent ArrayBlockingQueue Executors TimeUnit]))

(def ^:private stop-token ::stop)
(def ^:private max-error-chars 160)

(defn- bounded-string [value]
  (let [value (str (or value ""))]
    (subs value 0 (min max-error-chars (count value)))))

(defn- error-summary [error]
  {:type :query-error
   :class (bounded-string (str (class error)))
   :message (bounded-string (try (ex-message error)
                                 (catch Throwable _ nil)))})

(defn- fully-realize [screen]
  ;; JDBC-backed rows may be lazy even after the loader returns. Walking the
  ;; complete value here keeps every deferred read on the owned OS worker.
  (walk/postwalk identity screen))

(defn- run-job [loader command]
  (try
    {:status :ready
     :screen (fully-realize (effect/run-command loader command))}
    (catch Throwable error
      {:status :error :failure (error-summary error)})))

(defn- publish-outcome! [model generation outcome]
  (if (= :ready (:status outcome))
    (reset! model
            (assoc (:screen outcome)
                   :query-state {:status :ready :generation generation}))
    (swap! model assoc
           :status :stale
           :query-state {:status :error
                         :generation generation
                         :failure (:failure outcome)})))

(defn- worker-loop!
  [{:keys [queue loader model generation closed? lock state worker-thread]}]
  (reset! worker-thread (Thread/currentThread))
  (try
    (loop []
      (let [job (.take queue)]
        (when-not (= stop-token job)
          (let [outcome (run-job loader (:command job))]
            (locking lock
              ;; Selection and publication share this lock. A completion can
              ;; therefore never pass its generation check and publish after a
              ;; newer select! has become current.
              (when (and (not @closed?)
                         (= (:generation job) @generation))
                (publish-outcome! model (:generation job) outcome)))
            (recur)))))
    (finally
      (reset! worker-thread nil)
      (swap! state assoc :phase :worker-exited))))

(defn- offer-latest! [queue job]
  (when-not (.offer queue job)
    ;; Capacity is exactly one. Replacement occurs while select! owns the
    ;; lifecycle lock, so concurrent callers cannot drop each other's newest
    ;; request between poll and offer.
    (.poll queue)
    (when-not (.offer queue job)
      (throw (ex-info "oscope native selection queue rejected its replacement"
                      {:oscope.ui.selection/error true
                       :type ::queue-rejected})))))

(defn- select-lifecycle! [{:keys [queue model generation closed? lock id-prefix]}
                          selected]
  (locking lock
    (when @closed?
      (throw (ex-info "oscope native adapter is closed"
                      {:oscope.ui/error true :type ::closed})))
    (let [generation (swap! generation inc)
          command (command/query-command [id-prefix generation] selected)]
      (swap! model assoc
             :status :loading
             :query-state {:status :loading
                           :generation generation
                           :selection (:selection command)})
      (offer-latest! queue {:generation generation :command command})
      {:status :accepted :generation generation})))

(defn- await-worker! [executor]
  ;; Native/JDBC work must not be interrupted. Keep waiting until the physical
  ;; worker has exited so callers may safely retire its source after close!.
  (let [interrupted?
        (loop [interrupted? false]
          (let [result
                (try
                  (if (.awaitTermination executor 100 TimeUnit/MILLISECONDS)
                    :terminated
                    :waiting)
                  (catch InterruptedException _ :interrupted))]
            (case result
              :terminated interrupted?
              :interrupted (recur true)
              :waiting (recur interrupted?))))]
    (when interrupted?
      (.interrupt (Thread/currentThread)))))

(defn- close-lifecycle!
  [{:keys [queue executor closed? lock state worker-thread] :as lifecycle}]
  (when (identical? @worker-thread (Thread/currentThread))
    (throw (ex-info "oscope native selection worker cannot close itself"
                    {:oscope.ui.selection/error true
                     :type ::self-close})))
  (locking lock
    (when (compare-and-set! closed? false true)
      (swap! state assoc :phase :closing)
      (.clear queue)
      (offer-latest! queue stop-token)
      (.shutdown executor)))
  (await-worker! executor)
  (swap! state assoc :phase :closed)
  {:status :closed :phase :closed})

(defn start!
  "Start one latest-wins selection owner for a native adapter.

  `select!` only validates and enqueues. The loader and realization of its
  result run on one owned OS worker. `close!` rejects new selections, discards
  pending work, and waits for the physical worker before returning."
  [{:keys [model loader id-prefix]}]
  (when-not (and model (ifn? loader) (keyword? id-prefix))
    (throw (ex-info "invalid native selection lifecycle options"
                    {:oscope.ui.selection/error true
                     :type ::invalid-options})))
  (let [executor (Executors/newSingleThreadExecutor)
        lifecycle {:queue (ArrayBlockingQueue. 1)
                   :executor executor
                   :model model
                   :loader loader
                   :id-prefix id-prefix
                   :generation (atom 0)
                   :closed? (atom false)
                   :worker-thread (atom nil)
                   :state (atom {:phase :open})
                   :lock (Object.)}]
    (try
      (.execute executor #(worker-loop! lifecycle))
      (assoc lifecycle
             :select! #(select-lifecycle! lifecycle %)
             :close! #(close-lifecycle! lifecycle))
      (catch Throwable error
        (.shutdown executor)
        (throw error)))))
