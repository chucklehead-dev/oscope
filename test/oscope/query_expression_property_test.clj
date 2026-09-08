(ns oscope.query-expression-property-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [oscope.query :as query]
            [oscope.query.expression :as expression]))

(def now 2000000000000000000)

(def ^:private signal-domains
  {:spans {:dimensions [:service-name :span-name :span-kind :status-code
                        :scope-name :http-request-method
                        :http-response-status-code :deployment-environment]
           :number :duration-ns}
   :logs {:dimensions [:service-name :severity-text :event-name :scope-name
                       :deployment-environment]
          :number :severity-number}
   :metrics {:dimensions [:service-name :metric-name :metric-unit :scope-name
                          :deployment-environment]
             :number :value}})

(defn- aliases-generator [prefix size]
  (g/vector
   {:size size :unique? true}
   (g/fmap #(keyword (str prefix %)) (g/uuid))))

(defn- draw-filter [dimensions value]
  {:field (h/draw! (g/sampled-from dimensions))
   :op :eq
   :value value})

(defn- draw-expression! []
  (let [signal (h/draw! (g/sampled-from (vec (sort (keys signal-domains)))))
        {:keys [dimensions number]} (get signal-domains signal)
        group-by (h/draw! (g/vector {:max-size 2 :unique? true}
                                    (g/sampled-from dimensions)))
        series-count (h/draw! (g/integer 1 expression/max-series))
        series-aliases (h/draw! (aliases-generator "result-" series-count))
        requested-series-filter-counts
        (h/draw! (g/vector {:size series-count}
                           (g/integer 0 expression/max-series-filters)))
        series-filter-counts
        (loop [requested requested-series-filter-counts
               remaining expression/max-total-series-filters
               counts []]
          (if-let [requested-count (first requested)]
            (let [accepted (min requested-count remaining)]
              (recur (next requested) (- remaining accepted)
                     (conj counts accepted)))
            counts))
        global-filter-count (h/draw! (g/integer 0 expression/max-filters))
        filter-count (+ global-filter-count (reduce + series-filter-counts))
        filter-values (h/draw!
                       (g/vector
                        {:size filter-count :unique? true}
                        (g/fmap #(str "private-filter-" %) (g/uuid))))
        global-filter-values (take global-filter-count filter-values)
        series-filter-values (atom (seq (drop global-filter-count filter-values)))
        filters (mapv #(draw-filter dimensions %) global-filter-values)
        series
        (mapv
         (fn [alias series-filter-count]
           (let [op (h/draw! (g/sampled-from (vec (sort expression/aggregate-ops))))
                 percentile (when (= :percentile op)
                              (h/draw! (g/sampled-from
                                        (vec (sort expression/percentile-presets)))))
                 filter-values (take series-filter-count @series-filter-values)]
             (swap! series-filter-values #(nthnext % series-filter-count))
             (cond-> {:as alias :op op}
               (not= :count op) (assoc :field number)
               percentile (assoc :percentile percentile)
               (seq filter-values)
               (assoc :filters (mapv #(draw-filter dimensions %) filter-values)))))
         series-aliases series-filter-counts)
        calculation-count (h/draw! (g/integer 0 expression/max-calculations))
        calculation-aliases (h/draw! (aliases-generator "derived-" calculation-count))
        operand-generator (g/sampled-from (vec (concat series-aliases [0.5 -2 7])))
        calculations
        (mapv (fn [alias]
                {:as alias
                 :op (h/draw! (g/sampled-from
                                (vec (sort expression/calculation-ops))))
                 :args [(h/draw! operand-generator) (h/draw! operand-generator)]})
              calculation-aliases)]
    {:signal signal
     :window (h/draw! (g/sampled-from (vec (sort (keys query/windows)))))
     :group-by group-by
     :filters filters
     :series series
     :calculations calculations
     :limit (h/draw! (g/integer 1 query/max-result-limit))}))

(defn- all-filter-values [{:keys [filters series]}]
  (vec (concat (map :value filters)
               (mapcat #(map :value (:filters %)) series))))

(deftest generated-aggregate-expressions-compile-with-private-data-parameterized
  (let [result
        (h/run-test!
         {:name "oscope reusable Plotje expression compilation"
          :database "" :verbosity :quiet :derandomize? true :test-cases 240}
         (fn [_]
           (let [input (draw-expression!)
                 {:keys [expression sqlvec columns calculations] :as plan}
                 (expression/compile-query input now)
                 [sql & params] sqlvec
                 external-aliases (concat (:group-by expression)
                                          (map :as (:series expression))
                                          (map :as calculations))
                 filter-values (all-filter-values expression)
                 private-values (concat (map name external-aliases) filter-values)]
             (when-not (= expression (expression/validate-expression input))
               (throw (ex-info "compiled expression differs from validated input"
                               {:hegel/origin "plotje-expression/validation-drift"})))
             (when-not (and (= (vec (concat (:group-by expression)
                                             (map :as (:series expression))))
                               (mapv second columns))
                            (= (expression/output-fields expression)
                               (vec (concat (map second columns)
                                            (map :as calculations)))))
               (throw (ex-info "compiled result columns differ from expression outputs"
                               {:hegel/origin "plotje-expression/result-column-drift"})))
             (when-let [leaked (some #(when (str/includes? sql %) %) private-values)]
               (throw (ex-info "private expression data leaked into generated SQL"
                               {:hegel/origin "plotje-expression/sql-data-leak"
                                :leaked leaked})))
             (when-not (every? #(= 1 (count (filter #{%} params))) filter-values)
               (throw (ex-info "filter value was not bound exactly once"
                               {:hegel/origin "plotje-expression/filter-binding-drift"})))
             (when-not (and (= expression (:expression plan))
                            (str/includes? sql "SETTINGS max_rows_to_read = 100000")
                            (str/includes? sql "max_threads = 1"))
               (throw (ex-info "compiled query lost its normalized or bounded contract"
                               {:hegel/origin "plotje-expression/bounds-drift"}))))))]
    (is (:passed? result)
        (pr-str (select-keys result [:status :seed :failures :final :error])))
    (is (false? (:flaky? result)))))

(deftest invalid-expression-mutants-fail-closed-before-query-execution
  (let [valid {:signal :spans :window :15m :bucket :none
               :group-by [:service-name]
               :filters [{:field :status-code :op :eq :value "private-global"}]
               :series [{:as :requests :op :count}
                        {:as :errors :op :count
                         :filters [{:field :status-code :op :eq
                                    :value "private-series"}]}
                        {:as :p95 :op :percentile :field :duration-ns
                         :percentile 95}]
               :calculations [{:as :error-rate :op :divide
                               :args [:errors :requests]}]
               :limit 20}
        mutants
        [["raw SQL key" (assoc valid :sql "SELECT private-global")
          ::expression/unsupported-key]
         ["aggregate function" (assoc-in valid [:series 0 :op] :raw-sql)
          ::expression/unsupported-aggregate]
         ["percentile preset" (assoc-in valid [:series 2 :percentile] 93)
          ::expression/unsupported-percentile]
         ["empty filter value" (assoc-in valid [:filters 0 :value] "")
          ::expression/invalid-filter-value]
         ["series alias collision" (assoc-in valid [:series 1 :as] :requests)
          ::expression/duplicate-alias]
         ["calculation dependency" (assoc-in valid [:calculations 0 :args]
                                              [:error-rate :requests])
          ::expression/invalid-calculation-operand]
         ["series filter cap" (assoc-in valid [:series 1 :filters]
                                        (vec (repeat 3 {:field :status-code
                                                        :op :eq :value "ERROR"})))
          ::expression/invalid-series-filters]]]
    (is (= valid (:expression (expression/compile-query valid now))))
    (doseq [[label mutant expected-type] mutants]
      (testing label
        (try
          (expression/compile-query mutant now)
          (is false "mutant unexpectedly compiled")
          (catch clojure.lang.ExceptionInfo error
            (is (true? (:oscope.query-expression/error (ex-data error))))
            (is (= expected-type (:type (ex-data error))))))))))
