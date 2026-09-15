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

(def ^:private failure-canaries
  #{"CANARY-EXCEPTION-MESSAGE-97"
    "CANARY-EX-DATA-97"
    "CANARY-CAUSE-MESSAGE-97"
    "CANARY-CAUSE-DATA-97"
    "SELECT CANARY-SQL-97"
    "CANARY-PARAMETER-97"
    "/private/CANARY-PATH-97/database.chdb"
    "https://CANARY-ENDPOINT-97.invalid/query"
    "CANARY-CREDENTIAL-97"})

(defn- causal-failure []
  (ex-info
   "CANARY-EXCEPTION-MESSAGE-97"
   {:arbitrary "CANARY-EX-DATA-97"
    :sql "SELECT CANARY-SQL-97"
    :parameter "CANARY-PARAMETER-97"
    :path "/private/CANARY-PATH-97/database.chdb"
    :endpoint "https://CANARY-ENDPOINT-97.invalid/query"
    :credential "CANARY-CREDENTIAL-97"}
   (ex-info "CANARY-CAUSE-MESSAGE-97"
            {:arbitrary "CANARY-CAUSE-DATA-97"})))

(defn- assert-closed-failure! [expected failure snapshot]
  (let [rendered (pr-str snapshot)]
    (is (= expected (:failure snapshot)))
    (is (= (set (keys expected)) (set (keys (:failure snapshot))))
        "the public failure shape is exact")
    (is (every? #(not (.contains rendered %)) failure-canaries)
        "no causal message, data, SQL, value, path, endpoint, or credential leaks")
    (is (not (.contains rendered "clojure.lang.ExceptionInfo"))
        "the Throwable class name is not projected")
    (is (not-any? #(identical? % failure) (tree-seq coll? seq snapshot)))
    (is (not-any? #(instance? Throwable %) (tree-seq coll? seq snapshot))
        "no Throwable, cause, or stack remains reachable")))

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
        (is (= {:type :query-failure
                :phase :load
                :category :timeout
                :timeout-ms 10}
               (:failure snapshot)))
        (is (= 1 @calls) "timeouts do not enqueue duplicate JDBC work"))
      (deliver release true)
      (is (= [{:value "late"}]
             (:rows (eventually lifecycle #(= :ready (:status %))))))
      (finally
        (deliver release true)
        (stop-until-closed! lifecycle)))))

(deftest load-failures-are-closed-and-a-later-sample-recovers
  (let [allow-success (promise)
        failure (causal-failure)
        calls (atom 0)
        lifecycle
        (embedded-query/start!
         {:load! (fn []
                   (if (= 1 (swap! calls inc))
                     (throw failure)
                     (do @allow-success [{:value "ok"}])))
          :interval-ms 10 :timeout-ms 100})]
    (try
      (let [snapshot (eventually lifecycle #(= :error (:status %)))]
        (assert-closed-failure!
         {:type :query-failure
          :phase :load
          :category :load-failed}
         failure snapshot))
      (deliver allow-success true)
      (let [snapshot (eventually lifecycle #(= :ready (:status %)))]
        (is (= [{:value "ok"}] (:rows snapshot)))
        (is (not (contains? snapshot :failure))
            "success clears the prior failure descriptor"))
      (finally
        (deliver allow-success true)
        (stop-until-closed! lifecycle)))))

(deftest executor-submission-failures-use-the-same-closed-boundary
  (let [execute-var (ns-resolve 'oscope.embedded.query 'execute-query!)
        execute-query! @execute-var
        failure (causal-failure)
        allow-recovery (promise)
        submissions (atom 0)]
    (with-redefs-fn
      {execute-var
       (fn [executor task]
         (if (= 1 (swap! submissions inc))
           (throw failure)
           (execute-query! executor task)))}
      (fn []
        (let [lifecycle
              (embedded-query/start!
               {:load! (fn []
                         @allow-recovery
                         [{:value "recovered"}])
                :interval-ms 10 :timeout-ms 100})]
          (try
            (let [snapshot (eventually lifecycle #(= :error (:status %)))]
              (assert-closed-failure!
               {:type :query-failure
                :phase :submission
                :category :executor-submission-failed}
               failure snapshot))
            (deliver allow-recovery true)
            (let [snapshot (eventually lifecycle #(= :ready (:status %)))]
              (is (= [{:value "recovered"}] (:rows snapshot)))
              (is (not (contains? snapshot :failure))))
            (finally
              (deliver allow-recovery true)
              (stop-until-closed! lifecycle))))))))

(deftest load-failure-after-success-keeps-last-good-rows-and-recovers
  (let [calls (atom 0)
        failure (causal-failure)
        second-entered (promise)
        release-recovery (promise)
        lifecycle
        (embedded-query/start!
         {:load! (fn []
                   (case (swap! calls inc)
                     1 [{:value "good"}]
                     2 (do
                         (deliver second-entered true)
                         (throw failure))
                     (do
                       @release-recovery
                       [{:value "new"}])))
          :interval-ms 10 :timeout-ms 100})]
    (try
      (is (= :ready (:status (eventually lifecycle #(= :ready (:status %))))))
      (is (true? (deref second-entered 1000 false)))
      (let [snapshot (eventually lifecycle #(= :stale (:status %)))]
        (is (= [{:value "good"}] (:rows snapshot)))
        (assert-closed-failure!
         {:type :query-failure
          :phase :load
          :category :load-failed}
         failure snapshot))
      (deliver release-recovery true)
      (is (= [{:value "new"}]
             (:rows (eventually lifecycle
                               #(= [{:value "new"}] (:rows %))))))
      (finally
        (deliver release-recovery true)
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
        (is (= {:type :query-failure
                :phase :load
                :category :load-failed}
               (:failure snapshot)))
        (is (= [] (:rows snapshot)))
        (is (not (contains? snapshot :value))
            "the invalid return value is not retained"))
      (finally
        (stop-until-closed! lifecycle))))
  (is (thrown? Exception (embedded-query/snapshot nil)))
  (is (thrown? Exception (embedded-query/stop! nil))))
