(ns oscope.query-view-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing thrown-with-msg?]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jdbc.core :as jdbc]
            [oscope.plotje.svg :as plotje]
            [oscope.query :as query]
            [oscope.query.chdb :as query-chdb]
            [oscope.view-model :as view-model]
            [otel.exporter.chdb.explorer :as explorer]))

(def now 2000000000000000000)

(defn- ordered-selection [order selected]
  (vec (filter selected order)))

(deftest selection-compiles-to-the-bounded-explorer-contract
  (let [plan (query/compile-query
              {:signal :logs :field :severity-text :window :15m :limit 7}
              now)]
    (is (= {:signal :logs :fields [:severity-text]
            :start-unix-nano (- now (* 15 60 1000000000))
            :end-unix-nano now
            :limit 7}
           (:request plan)))
    (with-redefs [explorer/top-values
                  (fn [connection request]
                    (is (= ::connection connection))
                    (is (= (:request plan) request))
                    [])]
      (is (= [] (query-chdb/run ::connection plan))))))

(deftest chdb-adapter-refuses-field-contract-drift
  (let [plan (query/compile-query query/default-selection now)]
    (with-redefs [explorer/supported-fields
                  (fn [] (assoc (query/supported-fields)
                                :spans [:service-name]))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"query fields do not match"
           (query-chdb/run ::connection plan))))))

(deftest metric-series-selection-compiles-to-the-bounded-explorer-contract
  (let [selection {:mode :metric-series :metric-kind :gauge
                   :metric-name "http.server.active_requests"
                   :group-by [:service-name] :bucket :5m
                   :aggregates [:avg :p95 :p99] :window :1h :limit 40}
        plan (query/compile-query selection now)]
    (is (= {:metric-kind :gauge
            :metric-name "http.server.active_requests"
            :group-by [:service-name] :bucket :5m
            :aggregates [:avg :p95 :p99]
            :start-unix-nano (- now (* 60 60 1000000000))
            :end-unix-nano now :limit 40}
           (:request plan)))
    (with-redefs [explorer/metric-series
                  (fn [connection request]
                    (is (= ::connection connection))
                    (is (= (:request plan) request))
                    [])]
      (is (= [] (query-chdb/run ::connection plan))))))

(deftest metric-series-contract-drift-and-unsafe-recipes-fail-closed
  (let [selection query/default-metric-series-selection
        plan (query/compile-query selection now)]
    (with-redefs [explorer/supported-metric-series
                  (fn [] (assoc query/metric-series-options
                                :buckets [:none :30s]))]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"metric series choices do not match"
           (query-chdb/run ::connection plan))))
    (doseq [invalid [(assoc selection :metric-name "")
                     (assoc selection :group-by [:service-name :service-name])
                     (assoc selection :group-by [:service-name :metric-unit])
                     (assoc selection :group-by [:arbitrary-attribute])
                     (assoc selection :bucket :30s)
                     (assoc selection :aggregates [])
                     (assoc selection :aggregates [:rate])
                     (assoc selection :sql "SELECT *")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (query/compile-query invalid now))
          (pr-str invalid)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/validate-plan
                  (assoc-in plan [:request :aggregates] [:sum]))))))

(deftest cumulative-counter-selection-retains-provenance-and-dispatches
  (let [selection {:mode :counter-series :metric-kind :sum
                   :temporality :cumulative :monotonic? true
                   :metric-name "http.server.requests"
                   :group-by [:service-name] :bucket :none
                   :aggregates [:increase :rate] :window :1h :limit 40}
        plan (query/compile-query selection now)]
    (is (= {:metric-kind :sum :temporality :cumulative :monotonic? true
            :metric-name "http.server.requests" :group-by [:service-name]
            :bucket :none :aggregates [:increase :rate]
            :start-unix-nano (- now (* 60 60 1000000000))
            :end-unix-nano now :limit 40}
           (:request plan)))
    (with-redefs [explorer/cumulative-counter-series
                  (fn [connection request]
                    (is (= ::connection connection))
                    (is (= (:request plan) request))
                    [])]
      (is (= [] (query-chdb/run ::connection plan))))))

(deftest counter-contract-drift-and-unsafe-provenance-fail-closed
  (let [selection query/default-counter-series-selection
        plan (query/compile-query selection now)]
    (with-redefs [explorer/supported-cumulative-counter-series
                  (fn [] (assoc query/counter-series-options
                                :buckets [:none :30s]))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"counter series choices do not match"
                            (query-chdb/run ::connection plan))))
    (doseq [invalid [(assoc selection :metric-kind :gauge)
                     (assoc selection :temporality :delta)
                     (assoc selection :monotonic? false)
                     (assoc selection :aggregates [:avg])
                     (assoc selection :group-by [:service-name :metric-unit])
                     (assoc selection :sql "SELECT *")]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (query/compile-query invalid now))
          (pr-str invalid)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/validate-plan
                  (assoc-in plan [:request :monotonic?] false))))))

(deftest generated-counter-recipes-round-trip-explicit-provenance
  (let [result
        (h/run-test!
         {:name "oscope cumulative counter recipe plan"
          :database "" :verbosity :quiet :derandomize? true :test-cases 120}
         (fn [_]
           (let [aggregates-set
                 (h/draw! (g/set {:min-size 1 :max-size 2}
                                 (g/sampled-from [:increase :rate])))
                 groups-set
                 (h/draw! (g/set {:min-size 0 :max-size 1}
                                 (g/sampled-from
                                  (:group-by query/counter-series-options))))
                 selection
                 {:mode :counter-series :metric-kind :sum
                  :temporality :cumulative :monotonic? true
                  :metric-name (h/draw! (g/sampled-from
                                         ["requests.total"
                                          "private' OR 1 = 1 --" "unicode.λ"]))
                  :group-by (ordered-selection
                             (:group-by query/counter-series-options) groups-set)
                  :bucket (h/draw! (g/sampled-from
                                    (:buckets query/counter-series-options)))
                  :aggregates (ordered-selection [:increase :rate]
                                                 aggregates-set)
                  :window (h/draw! (g/sampled-from (vec (keys query/windows))))
                  :limit (h/draw! (g/integer 1 query/max-result-limit))}
                 plan (query/compile-query selection now)]
             (when-not (and (= selection (:selection plan))
                            (= plan (query/validate-plan plan))
                            (= {:metric-kind :sum :temporality :cumulative
                                :monotonic? true}
                               (select-keys (:request plan)
                                            [:metric-kind :temporality
                                             :monotonic?])))
               (throw (ex-info "counter recipe provenance drifted"
                               {:hegel/origin
                                "oscope-counter-plan/provenance-drift"}))))))]
    (is (:passed? result)
        (pr-str (select-keys result [:status :seed :failures :error])))
    (is (false? (:flaky? result)))))

(deftest generated-metric-series-recipes-round-trip-the-pure-plan
  (let [result
        (h/run-test!
         {:name "oscope metric series recipe plan"
          :database "" :verbosity :quiet :derandomize? true :test-cases 160}
         (fn [_]
           (let [kind (h/draw!
                       (g/sampled-from
                        (:metric-kinds query/metric-series-options)))
                 aggregate-order (get-in query/metric-series-options
                                         [:aggregates kind])
                 aggregates
                 (ordered-selection
                  aggregate-order
                  (h/draw! (g/set {:min-size 1 :max-size 4}
                                  (g/sampled-from aggregate-order))))
                 groups
                 (ordered-selection
                  (:group-by query/metric-series-options)
                  (h/draw! (g/set {:min-size 0 :max-size 1}
                                  (g/sampled-from
                                   (:group-by query/metric-series-options)))))
                 metric-name
                 (h/draw! (g/sampled-from
                           ["queue.depth" "http.server.duration"
                            "metric' OR 1 = 1 --" "unicode.λ"]))
                 selection {:mode :metric-series :metric-kind kind
                            :metric-name metric-name :group-by groups
                            :bucket (h/draw!
                                     (g/sampled-from
                                      (:buckets query/metric-series-options)))
                            :aggregates aggregates
                            :window (h/draw! (g/sampled-from (vec (keys query/windows))))
                            :limit (h/draw! (g/integer 1 query/max-result-limit))}
                 plan (query/compile-query selection now)]
             (when-not (and (= selection (:selection plan))
                            (= plan (query/validate-plan plan))
                            (= metric-name (get-in plan [:request :metric-name]))
                            (every? (set aggregate-order)
                                    (get-in plan [:request :aggregates])))
               (throw (ex-info "generated metric series plan drifted"
                               {:selection selection :plan plan}))))))]
    (is (:passed? result))
    (is (false? (:flaky? result)))))

(deftest unsupported-or-tampered-plans-fail-closed
  (testing "unknown controls and signal-specific fields cannot imply SQL"
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/compile-query {:group-by :freeform} now)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/compile-query
                  {:signal :logs :field :span-name} now)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/compile-query
                  {:limit (inc query/max-result-limit)} now))))
  (testing "portable request data cannot drift from its selection"
    (let [plan (query/compile-query {} now)]
      (is (= plan (query/validate-plan plan)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (query/validate-plan
                    (assoc-in plan [:request :fields] [:span-name])))))))

(deftest results-project-to-one-serializable-accessible-screen
  (let [plan (query/compile-query
              {:signal :spans :field :service-name :window :1h :limit 12}
              now)
        rows [{:signal :spans :field :service-name
               :value "gateway" :count 17}
              {:signal :spans :field :service-name
               :value "checkout" :count 9}]
        screen (view-model/screen plan rows)]
    (is (= screen (edn/read-string (pr-str screen))))
    (is (= :ready (:status screen)))
    (is (= plan (:query-plan screen)))
    (is (= [{:value "gateway" :count 17}
            {:value "checkout" :count 9}]
           (get-in screen [:table :rows])))
    (is (string? (plotje/spec->svg (:chart screen))))))

(deftest metric-series-results-project-to-named-plotje-fields
  (let [plan (query/compile-query query/default-metric-series-selection now)
        rows [{:bucket-start-unix-nano (- now 300000000000)
               :service-name "checkout" :avg 4.5 :p95 8.0}
              {:bucket-start-unix-nano (dec now)
               :service-name "checkout" :avg 5.0 :p95 9.0}]
        screen (view-model/screen plan rows)]
    (is (= screen (edn/read-string (pr-str screen))))
    (is (= :telemetry-metric-series (:view screen)))
    (is (= rows (get-in screen [:table :rows])))
    (is (= rows (get-in screen [:chart :data])))
    (is (= [:avg]
           (mapv :y (get-in screen [:chart :layers]))))
    (is (= [:bucket-start-unix-nano]
           (mapv :x (get-in screen [:chart :layers]))))
    (is (= [:service-name]
           (mapv :color (get-in screen [:chart :layers]))))
    (is (string? (plotje/spec->svg (:chart screen))))))

(deftest counter-results-retain-reset-duration-and-rate-units
  (let [plan (query/compile-query query/default-counter-series-selection now)
        rows [{:service-name "checkout" :increase 25.0 :rate (/ 25.0 35.0)
               :metric-kind :sum :temporality :cumulative :monotonic? true
               :interval-count 4 :reset-count 2
               :observed-duration-nanos 35000000000}]
        screen (view-model/screen plan rows)]
    (is (= :telemetry-metric-series (:view screen)))
    (is (= rows (get-in screen [:table :rows])))
    (is (= [:increase] (mapv :y (get-in screen [:chart :layers]))))
    (is (= [:service-name] (mapv :x (get-in screen [:chart :layers]))))
    (is (= [:increase :rate :interval-count :reset-count
            :observed-duration-nanos :metric-kind :temporality :monotonic?]
           (mapv :key (drop 1 (get-in screen [:table :columns])))))
    (is (string? (plotje/spec->svg (:chart screen))))))

(deftest bucket-crossing-counter-interval-fails-clearly-without-raw-values
  (let [selection (assoc query/default-counter-series-selection :bucket :1m)
        plan (query/compile-query selection now)
        interval-end (- now 10000000000)
        epoch-start (- (get-in plan [:request :start-unix-nano]) 1000000000)
        row (fn [time value]
              {:starttimenano epoch-start :timenano time
               :servicename "secret-service"
               :streamservice "secret-service" :streammetricunit "{request}"
               :streamscopename "private.scope" :streamscopeversion "1"
               :streamresourceschemaurl "" :streamscopeschemaurl ""
               :streamresourceattributes {"token" "private-resource"}
               :streamscopeattributes {} :streamattributes {"route" "/secret"}
               :value value :aggregationtemporality 2 :ismonotonic true})
        rows [(row (- interval-end 30000000000) 10.0)
              (row interval-end 20.0)]]
    (with-redefs [jdbc/fetch (fn [& _] rows)]
      (try
        (query-chdb/run ::connection plan)
        (is false "bucket-crossing counter interval unexpectedly rendered")
        (catch clojure.lang.ExceptionInfo error
          (let [data (ex-data error)
                rendered (pr-str data)]
            (is (re-find #"crosses a requested bucket boundary"
                         (ex-message error)))
            (is (= :otel.exporter.chdb.explorer/counter-interval-crosses-bucket
                   (:type data)))
            (is (not-any? #(contains? data %)
                          [:row :projection :value :previous-value :increase]))
            (is (not (str/includes? rendered "secret")))
            (is (not (str/includes? rendered "private")))
            (is (not (str/includes? rendered "20.0")))))))))

(deftest unbucketed-series-keeps-the-exact-query-name-outside-plotje-data
  (let [metric-name (apply str (repeat 256 "m"))
        selection (assoc query/default-metric-series-selection
                         :metric-name metric-name
                         :group-by []
                         :bucket :none
                         :aggregates [:avg])
        plan (query/compile-query selection now)
        screen (view-model/screen plan [{:avg 4.5}])
        display-name (get-in screen [:table :rows 0 :metric-name])]
    (is (= metric-name (get-in screen [:selection :metric-name])))
    (is (<= (count display-name) 160))
    (is (not= metric-name display-name))
    (is (= [:metric-name :avg]
           (mapv :key (get-in screen [:table :columns]))))
    (is (= [:avg] (mapv :y (get-in screen [:chart :layers]))))
    (is (string? (plotje/spec->svg (:chart screen))))))

(deftest malformed-metric-series-results-fail-closed
  (let [plan (query/compile-query query/default-metric-series-selection now)
        base {:bucket-start-unix-nano (dec now) :service-name "checkout"
              :avg 4.5 :p95 8.0}]
    (doseq [row [(assoc base :sql "unexpected")
                 (assoc base :p95 ##Inf)
                 (assoc base :service-name 42)
                 (assoc base :bucket-start-unix-nano now)
                 (dissoc base :avg)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (view-model/screen plan [row]))
          (pr-str row)))))

(deftest malformed-cross-query-and-over-limit-results-fail-closed
  (let [plan (query/compile-query
              {:signal :metrics :field :metric-name :limit 12} now)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (view-model/screen
                  plan [{:signal :spans :field :span-name
                         :value "wrong" :count 1}])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (view-model/screen
                  plan (vec (repeat 13
                                    {:signal :metrics :field :metric-name
                                     :value "too-many" :count 1})))))))
