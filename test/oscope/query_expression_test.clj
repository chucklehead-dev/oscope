(ns oscope.query-expression-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing thrown-with-msg?]]
            [jdbc.core :as jdbc]
            [oscope.query.expression :as expression]
            [oscope.query.expression.chdb :as expression-chdb]
            [otel.context :as context]))

(def now 2000000000000000000)
(def all-aggregates
  {:signal :spans :window :15m :group-by [:service-name]
   :filters [{:field :status-code :op :eq :value "OK"}]
   :series [{:as :requests :op :count}
            {:as :total-ns :op :sum :field :duration-ns}
            {:as :average-ns :op :avg :field :duration-ns}
            {:as :minimum-ns :op :min :field :duration-ns}
            {:as :maximum-ns :op :max :field :duration-ns}
            {:as :p95-ns :op :percentile :field :duration-ns :percentile 95}]
   :limit 20})

(def calculated-aggregates
  (assoc all-aggregates
         :calculations
         [{:as :mean-from-total :op :divide :args [:total-ns :requests]}
          {:as :extrema-total :op :add :args [:minimum-ns :maximum-ns]}
          {:as :extrema-range :op :subtract :args [:maximum-ns :minimum-ns]}
          {:as :average-percent :op :multiply :args [:average-ns 100]}]))

(deftest expression-is-a-closed-typed-aggregate-contract
  (is (= [:service-name :requests :total-ns :average-ns :minimum-ns
          :maximum-ns :p95-ns]
         (expression/output-fields all-aggregates)))
  (doseq [percentile [50 75 90 95 99]]
    (is (= percentile
           (-> (expression/validate-expression
                (assoc all-aggregates :series
                       [{:as :latency :op :percentile :field :duration-ns
                         :percentile percentile}]))
               :series first :percentile))))
  (testing "unsupported syntax and signal-specific numeric fields fail closed"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"p50, p75, p90, p95, or p99"
                          (expression/validate-expression
                           (assoc-in all-aggregates [:series 5 :percentile] 93))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"numeric field is unsupported"
                          (expression/validate-expression
                           (assoc-in all-aggregates [:series 1 :field] :sql))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"filter op must be :eq"
                          (expression/validate-expression
                           (assoc-in all-aggregates [:filters 0 :op] :regex))))))

(deftest calculations-are-a-closed-typed-series-contract
  (is (= [:service-name :requests :total-ns :average-ns :minimum-ns
          :maximum-ns :p95-ns :mean-from-total :extrema-total
          :extrema-range :average-percent]
         (expression/output-fields calculated-aggregates)))
  (is (= (:calculations calculated-aggregates)
         (:calculations (expression/validate-expression calculated-aggregates))))
  (doseq [[query message]
          [[(assoc calculated-aggregates :calculations
                   [{:as :bad :op :power :args [:requests 2]}])
            #"must be :add, :subtract, :multiply, or :divide"]
           [(assoc calculated-aggregates :calculations
                   [{:as :bad :op :divide :args [:requests]}])
            #"exactly two operands"]
           [(assoc calculated-aggregates :calculations
                   [{:as :bad :op :divide :args [:unknown :requests]}])
            #"aggregate series aliases or finite numbers"]
           [(assoc calculated-aggregates :calculations
                   [{:as :first :op :divide :args [:total-ns :requests]}
                    {:as :second :op :multiply :args [:first 100]}])
            #"aggregate series aliases or finite numbers"]
           [(assoc calculated-aggregates :calculations
                   [{:as :requests :op :divide :args [:total-ns :requests]}])
            #"must not replace group-by fields or aggregate series"]
           [(assoc calculated-aggregates :calculations
                   [{:as :same :op :add :args [:requests 1]}
                    {:as :same :op :subtract :args [:requests 1]}])
            #"aliases must be unique"]
           [(assoc calculated-aggregates :calculations
                   [{:as :bad :op :multiply :args [:requests ##Inf]}])
            #"aggregate series aliases or finite numbers"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                          (expression/validate-expression query)))))

(deftest calculations-have-explicit-numeric-null-and-zero-semantics
  (is (= {:requests 2 :total-ns 40 :average-ns 20 :minimum-ns 10
          :maximum-ns 30 :mean-from-total 20.0 :extrema-total 40.0
          :extrema-range 20.0 :average-percent 2000.0}
         (expression/apply-calculations
          {:requests 2 :total-ns 40 :average-ns 20
           :minimum-ns 10 :maximum-ns 30}
          (:calculations calculated-aggregates))))
  (testing "nil propagates, zero division and non-finite results become nil"
    (is (= {:a nil :b 2 :ratio nil}
           (expression/apply-calculations
            {:a nil :b 2} [{:as :ratio :op :divide :args [:a :b]}])))
    (is (= {:a 2 :b 0 :ratio nil}
           (expression/apply-calculations
            {:a 2 :b 0} [{:as :ratio :op :divide :args [:a :b]}])))
    (is (nil? (:ratio
               (expression/apply-calculations
                {:a 2 :b -0.0} [{:as :ratio :op :divide :args [:a :b]}]))))
    (is (nil? (:product
               (expression/apply-calculations
                {:a Double/MAX_VALUE :b 2}
                [{:as :product :op :multiply :args [:a :b]}])))))
  (testing "missing and non-numeric aggregate results are contract failures"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"aggregate result is missing"
                          (expression/apply-calculations
                           {:a 1} [{:as :result :op :add :args [:a :b]}])))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"finite number or nil"
                          (expression/apply-calculations
                           {:a "1"} [{:as :result :op :add :args [:a 1]}])))))

(deftest compiler-binds-values-and-uses-only-allowlisted-identifiers
  (let [{:keys [sqlvec columns calculations limit]}
        (expression/compile-query calculated-aggregates now)
        [sql & params] sqlvec]
    (is (str/includes? sql "FROM otel_traces"))
    (is (str/includes? sql "sum(Duration) AS series1"))
    (is (str/includes? sql "quantileExact(0.95)(Duration) AS series5"))
    (is (str/includes? sql "toString(StatusCode) = ?"))
    (is (str/includes? sql "GROUP BY toString(ServiceName)"))
    (is (not (str/includes? sql "GROUP BY dimension0")))
    (is (str/includes? sql "max_rows_to_read = 100000"))
    (is (str/includes? sql "max_bytes_to_read = 67108864"))
    (is (str/includes? sql "max_execution_time = 5"))
    (is (str/includes? sql "max_memory_usage = 134217728"))
    (is (str/includes? sql "max_threads = 1"))
    (is (not (str/includes? sql "OK")))
    (is (not (str/includes? sql "requests")))
    (is (not (str/includes? sql "mean-from-total")))
    (is (= [160 (- now (* 15 60 1000000000)) now "OK" 20] params))
    (is (= [[:dimension0 :service-name] [:series0 :requests]
            [:series1 :total-ns] [:series2 :average-ns]
            [:series3 :minimum-ns] [:series4 :maximum-ns]
            [:series5 :p95-ns]] columns))
    (is (= (:calculations calculated-aggregates) calculations))
    (is (= 20 limit))))

(deftest reusable-metric-expressions-retain-gauge-provenance
  (let [expression {:signal :metrics :window :15m
                    :group-by [:metric-name] :filters []
                    :series [{:as :p95 :op :percentile
                              :field :value :percentile 95}]
                    :limit 10}
        sql (first (:sqlvec (expression/compile-query expression now)))]
    (is (str/includes? sql "FROM otel_metrics_gauge"))
    (is (not (str/includes? sql "UNION ALL")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"numeric field is unsupported"
                          (expression/validate-expression
                           (assoc-in expression [:series 0 :field] :sum))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"group-by field is unsupported"
                          (expression/validate-expression
                           (assoc expression :group-by [:metric-kind]))))))

(deftest executor-renames-generated-columns-and-suppresses-instrumentation
  (let [seen (atom nil)]
    (with-redefs [jdbc/fetch
                  (fn [connection sqlvec options]
                    (reset! seen [connection sqlvec options
                                  (context/instrumentation-suppressed?)])
                    [{:dimension0 "api" :series0 2 :series1 40.0
                      :series2 20.0 :series3 10 :series4 30 :series5 30}])]
      (is (= [{:service-name "api" :requests 2 :total-ns 40.0
               :average-ns 20.0 :minimum-ns 10 :maximum-ns 30 :p95-ns 30
               :mean-from-total 20.0 :extrema-total 40.0
               :extrema-range 20.0 :average-percent 2000.0}]
             (expression-chdb/execute! ::connection calculated-aggregates now)))
      (is (= ::connection (first @seen)))
      (is (= {:max-rows 20} (nth @seen 2)))
      (is (true? (nth @seen 3))))))
