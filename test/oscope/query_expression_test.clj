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

(def filtered-aggregates
  {:signal :spans :window :15m :group-by [:service-name]
   :filters [{:field :span-name :op :eq :value "aggregate.work"}]
   :series [{:as :requests :op :count}
            {:as :errors :op :count
             :filters [{:field :status-code :op :eq :value "ERROR"}]}
            {:as :error-total :op :sum :field :duration-ns
             :filters [{:field :status-code :op :eq :value "ERROR"}]}
            {:as :error-avg :op :avg :field :duration-ns
             :filters [{:field :status-code :op :eq :value "ERROR"}]}
            {:as :error-min :op :min :field :duration-ns
             :filters [{:field :status-code :op :eq :value "ERROR"}]}
            {:as :error-max :op :max :field :duration-ns
             :filters [{:field :status-code :op :eq :value "ERROR"}]}
            {:as :error-p95 :op :percentile :field :duration-ns :percentile 95
             :filters [{:field :status-code :op :eq :value "ERROR"}]}]
   :calculations [{:as :error-rate :op :divide :args [:errors :requests]}]
   :limit 20})

(def bucketed-aggregates
  (assoc filtered-aggregates :bucket :5m))

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
                           (assoc-in all-aggregates [:filters 0 :op] :regex))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bucket must be"
                          (expression/validate-expression
                           (assoc all-aggregates :bucket :30s))))))

(deftest fixed-buckets-are-closed-output-dimensions
  (is (= [:bucket-start-unix-nano :service-name :requests :errors
          :error-total :error-avg :error-min :error-max :error-p95
          :error-rate]
         (expression/output-fields bucketed-aggregates)))
  (is (= :none (:bucket (expression/validate-expression all-aggregates))))
  (doseq [[bucket nanos] expression/bucket-presets]
    (let [[sql & params]
          (:sqlvec (expression/compile-query
                    (assoc all-aggregates :bucket bucket) now))]
      (is (= bucket
             (:bucket (expression/validate-expression
                       (assoc all-aggregates :bucket bucket)))))
      (if nanos
        (do (is (str/includes? sql " AS bucket0"))
            (is (= [nanos nanos] (subvec (vec params) 0 2))))
        (is (not (str/includes? sql " AS bucket0"))))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"grouping or bucket fields"
       (expression/validate-expression
        (assoc bucketed-aggregates
               :series [{:as :bucket-start-unix-nano :op :count}]))))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"must not replace"
       (expression/validate-expression
        (assoc bucketed-aggregates
               :calculations [{:as :bucket-start-unix-nano
                               :op :add :args [:requests 1]}])))))

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
            #"must not replace"]
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

(deftest per-series-filters-are-closed-and-bounded
  (is (= (:series filtered-aggregates)
         (:series (expression/validate-expression filtered-aggregates))))
  (testing "series predicates use the same signal-specific equality allowlist"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"filter op must be :eq"
                          (expression/validate-expression
                           (assoc-in filtered-aggregates
                                     [:series 1 :filters 0 :op] :regex))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"filter field is unsupported"
                          (expression/validate-expression
                           (assoc-in filtered-aggregates
                                     [:series 1 :filters 0 :field] :sql))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"non-empty bounded string"
                          (expression/validate-expression
                           (assoc-in filtered-aggregates
                                     [:series 1 :filters 0 :value] "")))))
  (testing "both per-series and expression-wide predicate counts are capped"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"up to 2 entries"
                          (expression/validate-expression
                           (assoc-in filtered-aggregates [:series 1 :filters]
                                     (vec (repeat 3 {:field :status-code
                                                     :op :eq :value "ERROR"}))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"at most 8 series filters"
                          (expression/validate-expression
                           (assoc filtered-aggregates :series
                                  (mapv (fn [index]
                                          {:as (keyword (str "count-" index))
                                           :op :count
                                           :filters [{:field :status-code :op :eq
                                                      :value "ERROR"}
                                                     {:field :span-kind :op :eq
                                                      :value "SERVER"}]})
                                        (range 5))))))))

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

(deftest compiler-uses-parameterized-aggregate-filter-combinators
  (let [[sql & params] (:sqlvec (expression/compile-query filtered-aggregates now))]
    (is (str/includes? sql "count() AS series0"))
    (is (str/includes? sql "countIf(toString(StatusCode) = ?) AS series1"))
    (is (str/includes? sql "sumOrNullIf(Duration, toString(StatusCode) = ?) AS series2"))
    (is (str/includes? sql "avgOrNullIf(Duration, toString(StatusCode) = ?) AS series3"))
    (is (str/includes? sql "minOrNullIf(Duration, toString(StatusCode) = ?) AS series4"))
    (is (str/includes? sql "maxOrNullIf(Duration, toString(StatusCode) = ?) AS series5"))
    (is (str/includes? sql
                       "quantileExactOrNullIf(0.95)(Duration, toString(StatusCode) = ?) AS series6"))
    (is (str/includes? sql "toString(SpanName) = ?"))
    (is (not (str/includes? sql "ERROR")))
    (is (not (str/includes? sql "aggregate.work")))
    (is (= (vec (concat [160] (repeat 6 "ERROR")
                        [(- now (* 15 60 1000000000)) now "aggregate.work" 20]))
           params))))

(deftest compiler-uses-parameterized-epoch-aligned-time-buckets
  (let [{:keys [sqlvec columns]} (expression/compile-query bucketed-aggregates now)
        [sql & params] sqlvec
        bucket-nanos (get expression/bucket-presets :5m)]
    (is (str/includes?
         sql
         "intDiv(toUnixTimestamp64Nano(Timestamp), ?) * ? AS bucket0"))
    (is (str/includes?
         sql "GROUP BY bucket0, toString(ServiceName)"))
    (is (str/includes?
         sql "ORDER BY bucket0 ASC, toString(ServiceName) ASC"))
    (is (= [[:bucket0 :bucket-start-unix-nano]
            [:dimension0 :service-name]
            [:series0 :requests] [:series1 :errors]
            [:series2 :error-total] [:series3 :error-avg]
            [:series4 :error-min] [:series5 :error-max]
            [:series6 :error-p95]]
           columns))
    (is (= (vec (concat [bucket-nanos bucket-nanos 160]
                        (repeat 6 "ERROR")
                        [(- now (* 15 60 1000000000)) now
                         "aggregate.work" 20]))
           params))
    (testing ":none keeps the original aggregate ordering and parameter shape"
      (let [[plain-sql & plain-params]
            (:sqlvec (expression/compile-query
                      (assoc bucketed-aggregates :bucket :none) now))]
        (is (not (str/includes? plain-sql "intDiv(")))
        (is (str/includes? plain-sql "ORDER BY series0 DESC"))
        (is (= (vec (concat [160] (repeat 6 "ERROR")
                            [(- now (* 15 60 1000000000)) now
                             "aggregate.work" 20]))
               plain-params))))))

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
