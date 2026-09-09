(ns oscope.async-selection-test
  (:require [clojure.test :refer [deftest is testing]]
            [oscope.sample :as sample]
            [oscope.ui.async-selection :as async-selection]))

(defn- create-owner [{:keys [screen loader]}]
  (let [model (atom screen)
        owner (async-selection/start!
               {:model model :loader loader :id-prefix :test})]
    {:model model
     :select! (:select! owner)
     :close! (:close! owner)
     :closed? (:closed? owner)
     :selection-owner owner}))

(def ^:private creators
  [[:shared-owner create-owner]])

(def ^:private selection-a
  {:signal :logs :field :severity-text :window :15m :limit 7})
(def ^:private selection-b
  {:signal :metrics :field :metric-name :window :6h :limit 8})
(def ^:private selection-c
  {:signal :spans :field :span-name :window :1h :limit 9})

(defn- await! [pred]
  (loop [attempt 0]
    (cond
      (pred) true
      (< attempt 500) (do (Thread/sleep 2) (recur (inc attempt)))
      :else false)))

(deftest native-selections-are-nonblocking-buffer-one-and-latest-wins
  (doseq [[label create] creators]
    (testing (name label)
      (let [a-started (promise)
            release-a (promise)
            executed (atom [])
            published (atom [])
            loader (fn [selection]
                     (swap! executed conj selection)
                     (when (= selection-a selection)
                       (deliver a-started true)
                       @release-a)
                     (sample/screen-for-selection selection))
            instance (create {:screen sample/default-screen :loader loader})]
        (add-watch (:model instance) ::published
                   (fn [_ _ _ next]
                     (swap! published conj
                            [(:status next) (:selection next)
                             (get-in next [:query-state :generation])])))
        (try
          (let [started-at (System/nanoTime)
                accepted ((:select! instance) selection-a)
                elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
            (is (= :accepted (:status accepted)))
            (is (< elapsed-ms 100.0)
                (str "select! callback took " elapsed-ms " ms")))
          (is (= true (deref a-started 1000 false)))

          ;; The UI/caller remains schedulable while native work is held.
          (let [heartbeat (promise)
                heartbeat-thread (Thread. #(deliver heartbeat :responsive))]
            (.start heartbeat-thread)
            (is (= :responsive (deref heartbeat 100 :timed-out)))
            (.join heartbeat-thread))

          ((:select! instance) selection-b)
          ((:select! instance) selection-c)
          (is (= :loading (:status @(:model instance))))
          (is (= selection-c (get-in @(:model instance)
                                     [:query-state :selection])))
          (deliver release-a true)
          (is (await! #(and (= :ready (:status @(:model instance)))
                            (= selection-c (:selection @(:model instance))))))
          (is (= [selection-a selection-c] @executed)
              "B is replaced while A is running")
          (is (not-any? #(= [:ready selection-a 1] %) @published)
              "A cannot publish after C becomes current")
          (finally
            (remove-watch (:model instance) ::published)
            ((:close! instance))))))))

(deftest native-close-waits-for-physical-worker-before-source-retirement
  (doseq [[label create] creators]
    (testing (name label)
      (let [started (promise)
            release (promise)
            close-returned (promise)
            source-retired? (atom false)
            source-used-after-retirement? (atom false)
            loader (fn [selection]
                     (deliver started true)
                     @release
                     (when @source-retired?
                       (reset! source-used-after-retirement? true))
                     (sample/screen-for-selection selection))
            instance (create {:screen sample/default-screen :loader loader})
            closer (Thread.
                    #(do ((:close! instance))
                         (let [interrupted?
                               (.isInterrupted (Thread/currentThread))]
                           (reset! source-retired? true)
                           (deliver close-returned
                                    {:interrupted? interrupted?}))))]
        ((:select! instance) selection-a)
        (is (= true (deref started 1000 false)))
        (.start closer)
        (is (= :waiting (deref close-returned 30 :waiting)))
        (is @(:closed? instance))
        (is (= {:phase :closing}
               @(get-in instance [:selection-owner :state])))
        (.interrupt closer)
        (is (= :waiting (deref close-returned 30 :waiting))
            "interrupt cannot weaken the physical worker join")
        (deliver release true)
        (is (= {:interrupted? true}
               (deref close-returned 1000 false)))
        (.join closer)
        (is @source-retired?)
        (is (false? @source-used-after-retirement?)
            "source stays live throughout the blocking load")
        (is (= {:phase :closed}
               @(get-in instance [:selection-owner :state])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"adapter is closed"
                              ((:select! instance) selection-c)))))))

(deftest native-worker-rejects-reentrant-self-close-before-waiting
  (doseq [[label create] creators]
    (testing (name label)
      (let [instance-ref (atom nil)
            observed (promise)
            loader (fn [selection]
                     (let [error (try
                                   ((:close! @instance-ref))
                                   nil
                                   (catch Throwable error error))]
                       (deliver observed error))
                     (sample/screen-for-selection selection))
            instance (create {:screen sample/default-screen :loader loader})]
        (reset! instance-ref instance)
        (try
          ((:select! instance) selection-a)
          (let [error (deref observed 1000 ::timed-out)]
            (is (not= ::timed-out error))
            (is (= ::async-selection/self-close (:type (ex-data error)))))
          (is (await! #(= :ready (get-in @(:model instance)
                                         [:query-state :status]))))
          (is (false? @(:closed? instance)))
          (is (= {:phase :open}
                 @(get-in instance [:selection-owner :state])))
          (finally ((:close! instance))))))))

(deftest native-query-errors-are-bounded-explicit-and-do-not-retain-throwables
  (doseq [[label create] creators]
    (testing (name label)
      (let [instance (create
                      {:screen sample/default-screen
                       :loader (fn [_]
                                 (throw
                                  (ex-info (apply str (repeat 300 "x"))
                                           {:secret-object (Object.)})))})]
        (try
          ((:select! instance) selection-a)
          (is (await! #(= :error (get-in @(:model instance)
                                         [:query-state :status]))))
          (let [screen @(:model instance)
                failure (get-in screen [:query-state :failure])]
            (is (= :stale (:status screen)))
            (is (= :query-error (:type failure)))
            (is (= 160 (count (:message failure))))
            (is (string? (:class failure)))
            (is (not-any? #(instance? Throwable %)
                          (tree-seq coll? seq screen))))
          (finally ((:close! instance))))))))

(deftest lazy-screen-values-are-realized-on-the-owned-worker
  (doseq [[label create] creators]
    (testing (name label)
      (let [loader-thread (atom nil)
            realization-thread (atom nil)
            loader (fn [selection]
                     (reset! loader-thread (.getName (Thread/currentThread)))
                     (update-in (sample/screen-for-selection selection)
                                [:table :rows]
                                #(map (fn [row]
                                        (reset! realization-thread
                                                (.getName (Thread/currentThread)))
                                        row)
                                      %)))
            instance (create {:screen sample/default-screen :loader loader})]
        (try
          ((:select! instance) selection-c)
          (is (await! #(= :ready (get-in @(:model instance)
                                         [:query-state :status]))))
          (is (= @loader-thread @realization-thread))
          (is (not= (.getName (Thread/currentThread)) @loader-thread))
          (finally ((:close! instance))))))))
