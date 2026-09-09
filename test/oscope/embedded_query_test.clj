(ns oscope.embedded-query-test
  (:require [clojure.test :refer [deftest is testing]]
            [jolt.fibers :as fibers]
            [oscope.embedded.query :as embedded-query]))

(defn- eventually [lifecycle predicate]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (let [snapshot (embedded-query/snapshot lifecycle)]
        (cond
          (predicate snapshot) snapshot
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 2) (recur))
          :else snapshot)))))

(defn- stop-until-closed! [lifecycle]
  (loop [attempt 0]
    (let [result (embedded-query/stop! lifecycle)]
      (cond
        (= :closed (:status result)) result
        (< attempt 100) (do (Thread/sleep 2) (recur (inc attempt)))
        :else result))))

(deftest blocking-load-runs-off-fiber-and-publication-is-bounded
  (let [realization-contexts (atom [])
        rows (fn rows [id]
               (lazy-seq
                (swap! realization-contexts conj (fibers/in-fiber?))
                (cons {:id id} (rows (inc id)))))
        load! (fn []
                (when (fibers/in-fiber?)
                  (throw (ex-info "known-bad direct fiber query" {})))
                (rows 1))
        direct-result
        (fibers/join
         (fibers/spawn
          #(try (load!)
                ::unexpected-success
                (catch Throwable _ ::direct-fiber-rejected))))]
    (is (= ::direct-fiber-rejected direct-result)
        "known-bad direct execution on a fiber is rejected")
    (let [lifecycle (embedded-query/start! {:load! load!
                                            :interval-ms 1000
                                            :timeout-ms 100
                                            :max-rows 2})]
      (try
        (is (= [{:id 1} {:id 2}]
               (:rows (eventually lifecycle #(= :ready (:status %))))))
        (is (= [false false] @realization-contexts)
            "lazy row realization is bounded and stays on the OS thread")
        (is (= {:status :closed :phase :closed}
               (stop-until-closed! lifecycle)))
        (finally
          (stop-until-closed! lifecycle))))))

(deftest timeout-retains-one-inflight-query-and-recovers
  (let [entered (promise)
        release (promise)
        calls (atom 0)
        lifecycle
        (embedded-query/start!
         {:load! (fn []
                   (swap! calls inc)
                   (deliver entered true)
                   @release
                   [{:value "late"}])
          :interval-ms 10 :timeout-ms 10 :stop-timeout-ms 20})]
    (try
      (is (true? (deref entered 1000 false)))
      (let [snapshot (eventually lifecycle #(= :error (:status %)))]
        (is (= :timeout (get-in snapshot [:failure :type])))
        (is (= 1 @calls) "timeouts do not enqueue duplicate JDBC work"))
      (deliver release true)
      (is (= [{:value "late"}]
             (:rows (eventually lifecycle #(= :ready (:status %))))))
      (finally
        (deliver release true)
        (stop-until-closed! lifecycle)))))

(deftest failures-are-bounded-and-a-later-sample-recovers
  (let [allow-success (promise)
        failure (ex-info "sensitive-detail-that-must-be-truncated" {})
        calls (atom 0)
        lifecycle
        (embedded-query/start!
         {:load! (fn []
                   (if (= 1 (swap! calls inc))
                     (throw failure)
                     (do @allow-success [{:value "ok"}])))
          :interval-ms 10 :timeout-ms 100 :max-error-chars 9})]
    (try
      (let [snapshot (eventually lifecycle #(= :error (:status %)))]
        (is (= :query-error (get-in snapshot [:failure :type])))
        (is (= "sensitive" (get-in snapshot [:failure :message])))
        (is (true? (get-in snapshot [:failure :message-truncated?])))
        (is (not-any? #(identical? % failure) (tree-seq coll? seq snapshot))
            "failure snapshots retain data only, never the Throwable"))
      (deliver allow-success true)
      (is (= [{:value "ok"}]
             (:rows (eventually lifecycle #(= :ready (:status %))))))
      (finally
        (deliver allow-success true)
        (stop-until-closed! lifecycle)))))

(deftest stale-snapshot-keeps-the-last-good-rows
  (let [calls (atom 0)
        second-entered (promise)
        release-second (promise)
        lifecycle
        (embedded-query/start!
         {:load! (fn []
                   (if (= 1 (swap! calls inc))
                     [{:value "good"}]
                     (do
                       (deliver second-entered true)
                       @release-second
                       [{:value "new"}])))
          :interval-ms 10 :timeout-ms 10})]
    (try
      (is (= :ready (:status (eventually lifecycle #(= :ready (:status %))))))
      (is (true? (deref second-entered 1000 false)))
      (let [snapshot (eventually lifecycle #(= :stale (:status %)))]
        (is (= [{:value "good"}] (:rows snapshot)))
        (is (= :timeout (get-in snapshot [:failure :type]))))
      (deliver release-second true)
      (is (= [{:value "new"}]
             (:rows (eventually lifecycle
                               #(= [{:value "new"}] (:rows %))))))
      (finally
        (deliver release-second true)
        (stop-until-closed! lifecycle)))))

(deftest stop-does-not-claim-closed-before-the-query-thread-joins
  (let [entered (promise)
        release (promise)
        events (atom [])
        lifecycle
        (embedded-query/start!
         {:load! (fn []
                   (swap! events conj :query-enter)
                   (deliver entered true)
                   @release
                   (swap! events conj :query-exit)
                   [])
          :interval-ms 10 :timeout-ms 60000 :stop-timeout-ms 25})]
    (is (true? (deref entered 1000 false)))
    (let [first-stop (embedded-query/stop! lifecycle)]
      (is (= :stopping (:status first-stop)))
      (is (= :joining-query (:phase first-stop))
          "cadence observes stop without waiting for the 60s query timeout"))
    (is (= [:query-enter] @events))
    (deliver release true)
    (is (= {:status :closed :phase :closed}
           (stop-until-closed! lifecycle)))
    (is (= [:query-enter :query-exit] @events))))

(deftest invalid-options-fail-before-a-worker-is-created
  (testing "caller must supply the complete bounded contract"
    (is (thrown? Exception (embedded-query/start! {})))
    (is (thrown? Exception
                 (embedded-query/start! {:load! (constantly []) :max-rows 0}))))
  (let [lifecycle (embedded-query/start! {:load! (constantly {})
                                          :interval-ms 1000})]
    (try
      (let [snapshot (eventually lifecycle #(= :error (:status %)))]
        (is (= :error (:status snapshot)))
        (is (= :query-error (get-in snapshot [:failure :type])))
        (is (= [] (:rows snapshot)))
        (is (not (contains? snapshot :value))
            "the invalid return value is not retained"))
      (finally
        (stop-until-closed! lifecycle))))
  (is (thrown? Exception (embedded-query/snapshot nil)))
  (is (thrown? Exception (embedded-query/stop! nil))))
