(ns oscope.typed-span-aggregate-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oscope.live :as live]
            [oscope.query :as query]
            [oscope.query.chdb :as query-chdb]
            [oscope.typed-catalog :as typed-catalog]
            [oscope.typed-query :as typed-query]
            [oscope.ui.web :as web]
            [oscope.view-model :as view-model]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.exporter.chdb.schema :as schema]))

(def now 2000000000000000000)
(def int64-binding
  {:field-id "attribute_11111111111111111111"
   :attribute-key "game.score" :attribute-type :int64 :manifest-version 3})
(def catalog [int64-binding])

(def selection
  {:mode :typed-span-int64-aggregate
   :schema-binding int64-binding
   :predicate {:gte -10 :lt 100}
   :group-by [:service-name]
   :aggregates [:count :min :max :avg]
   :window :1h
   :limit 12})

(def coverage
  {:valid 7 :present-empty 1 :absent 2 :invalid 3
   :historical-untyped-fallback 4
   :historical-untyped-unavailable 5 :total 22})

(def result
  {:attribute-key "game.score" :attribute-type :int64
   :field-id (:field-id int64-binding) :manifest-version 3 :signal :spans
   :coverage coverage
   :aggregates
   [{:attribute-key "game.score" :field-id (:field-id int64-binding)
     :manifest-version 3 :signal :spans :source :typed :typed-status 3
     :service-name "naval-battle" :count 3 :min -4 :max 99 :avg 47.5}]})

(deftest aggregate-plan-is-closed-schema-bound-and-half-open
  (let [plan (query/compile-query selection now)]
    (is (= selection (:selection plan)))
    (is (= {:schema-binding int64-binding
            :predicate {:gte -10 :lt 100}
            :group-by [:service-name]
            :aggregates [:count :min :max :avg]
            :start-unix-nano (- now (get query/windows :1h))
            :end-unix-nano now :limit 12}
           (:request plan)))
    (doseq [invalid [(assoc selection :schema-binding
                            (dissoc int64-binding :manifest-version))
                     (assoc selection :predicate {:gte 5 :lt 5})
                     (assoc selection :predicate {})
                     (assoc selection :group-by [:span-name])
                     (assoc selection :aggregates [:count :sum])]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (query/compile-query invalid now))))))

(deftest executor-uses-only-exporter-owned-aggregate-and-coverage-apis
  (let [plan (query/compile-query selection now)
        calls (atom [])]
    (with-redefs [explorer/typed-span-int64-aggregates
                  (fn [connection descriptors request]
                    (swap! calls conj [:aggregate connection descriptors request])
                    (:aggregates result))
                  explorer/typed-span-coverage
                  (fn [connection descriptors request]
                    (swap! calls conj [:coverage connection descriptors request])
                    (dissoc result :aggregates))]
      (is (= result
             (query-chdb/run ::connection plan
                             {:typed-span-descriptors ::descriptors
                              :typed-span-fields catalog})))
      (is (= [:coverage :aggregate] (mapv first @calls)))
      (is (= {:signal :spans :attribute-key "game.score"
              :predicate {:gte -10 :lt 100}
              :group-by [:service-name]
              :aggregates [:count :min :max :avg]
              :start-unix-nano (- now (get query/windows :1h))
              :end-unix-nano now :limit 12}
             (nth (second @calls) 3)))
      (is (= {:signal :spans :attribute-key "game.score"
              :schema-binding int64-binding
              :start-unix-nano (- now (get query/windows :1h))
              :end-unix-nano now}
             (nth (first @calls) 3)))
      (is (not (str/includes? (pr-str @calls) "sql"))))))

(deftest executor-rejects-exporter-vocabulary-drift-before-querying
  (let [plan (query/compile-query selection now)]
    (with-redefs [explorer/supported-typed-span-int64-aggregates
                  (fn [] (assoc typed-query/int64-aggregate-capability
                                :aggregates [:count]))
                  explorer/typed-span-int64-aggregates
                  (fn [& _] (throw (AssertionError. "query must not run")))
                  explorer/typed-span-coverage
                  (fn [& _] (throw (AssertionError. "query must not run")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"do not match the chDB explorer"
                            (query-chdb/run
                             ::connection plan
                             {:typed-span-descriptors ::descriptors
                              :typed-span-fields catalog}))))))

(deftest aggregate-screen-shows-values-and-conserved-six-way-coverage-plus-total
  (let [screen (view-model/screen (query/compile-query selection now) result
                                  {:typed-span-fields catalog})]
    (is (= :telemetry-typed-span-int64-aggregate (:view screen)))
    (is (= (:aggregates result) (get-in screen [:table :rows])))
    (is (= [{:status :valid :count 7}
            {:status :present-empty :count 1}
            {:status :absent :count 2}
            {:status :invalid :count 3}
            {:status :historical-untyped-fallback :count 4}
            {:status :historical-untyped-unavailable :count 5}
            {:status :total :count 22}]
           (:coverage screen)))
    (is (str/includes? (:freshness-notice screen) "two bounded live queries"))
    (let [html (web/render-page screen)]
      (is (str/includes? html "historical-untyped-unavailable"))
      (is (str/includes? html "Typed Int64 span aggregate"))
      (is (str/includes? html "Maximum, exclusive")))))

(deftest aggregate-screen-rejects-unconserved-coverage-and-open-rows
  (let [plan (query/compile-query selection now)]
    (doseq [invalid [(assoc result :untrusted "extra")
                     (assoc-in result [:coverage :total] 23)
                     (assoc-in result [:coverage :unknown] 0)
                     (update-in result [:aggregates 0] dissoc :typed-status)
                     (assoc-in result [:aggregates 0 :avg] ##Inf)
                     (assoc-in result [:aggregates 0 :min]
                               9223372036854775808)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (view-model/screen plan invalid
                                      {:typed-span-fields catalog}))))))

(deftest saved-url-round-trips-exact-binding-and-aggregate-recipe
  (let [query-string (web/selection-query-string selection)
        parsed (web/selection-from-params
                (web/parse-query-params (subs query-string 1)) catalog)]
    (is (= selection parsed))
    (doseq [part ["typed-attribute-key=game.score"
                  "typed-attribute-type=int64"
                  "typed-manifest-version=3"
                  "typed-gte=-10" "typed-lt=100"
                  "aggregate-count=1" "aggregate-min=1"
                  "aggregate-max=1" "aggregate-avg=1"]]
      (is (str/includes? query-string part)))))

(deftest raw-typed-forms-redirect-before-execution-to-exact-canonical-bindings
  (let [loads (atom 0)
        handler
        (web/handler
         {:typed-span-fields catalog
          :load-command
          (fn [_ selected]
            (swap! loads inc)
            (view-model/screen (query/compile-query selected now) result
                               {:typed-span-fields catalog}))})
        aggregate-response
        (handler {:request-method :get :uri "/oscope"
                  :query-params
                  {"mode" "typed-span-int64-aggregate"
                   "typed-field-id" (:field-id int64-binding)
                   "typed-gte" "-10" "typed-lt" "100"
                   "group-by" "service-name" "aggregates-present" "1"
                   "aggregate-count" "1" "aggregate-min" "1"
                   "aggregate-max" "1" "aggregate-avg" "1"
                   "window" "1h" "limit" "12" "live" "1"}})
        location (get-in aggregate-response [:headers "Location"])]
    (is (= 303 (:status aggregate-response)))
    (is (= 0 @loads))
    (is (str/starts-with? location "/oscope?mode=typed-span-int64-aggregate"))
    (doseq [part ["typed-field-id=attribute_11111111111111111111"
                  "typed-attribute-key=game.score"
                  "typed-attribute-type=int64"
                  "typed-manifest-version=3" "live=1"]]
      (is (str/includes? location part)))
    (let [canonical-response
          (handler {:request-method :get :uri "/oscope"
                    :query-string (subs location (inc (str/index-of location "?")))})]
      (is (= 200 (:status canonical-response)))
      (is (= 1 @loads)))
    (let [filter-response
          (handler {:request-method :get :uri "/oscope"
                    :query-params
                    {"mode" "typed-span-filter"
                     "typed-field-id" (:field-id int64-binding)
                     "typed-operator" "eq" "typed-value" "1"
                     "window" "1h" "limit" "12"}})]
      (is (= 303 (:status filter-response)))
      (is (= 1 @loads))
      (doseq [part ["typed-attribute-key=game.score"
                    "typed-attribute-type=int64"
                    "typed-manifest-version=3"]]
        (is (str/includes? (get-in filter-response [:headers "Location"])
                           part))))))

(deftest serialized-bindings-are-all-or-none-and-use-positive-int64-versions
  (let [boundary-binding (assoc int64-binding
                                :manifest-version 9223372036854775807)
        boundary-selection (assoc selection :schema-binding boundary-binding)
        encoded (web/selection-query-string boundary-selection)]
    (is (typed-query/binding? boundary-binding))
    (is (not (typed-query/binding?
              (assoc boundary-binding :manifest-version 9223372036854775808))))
    (is (= boundary-selection
           (web/selection-from-params
            (web/parse-query-params (subs encoded 1)) [boundary-binding]))))
  (let [handler (web/handler
                 {:typed-span-fields catalog
                  :load-command (fn [& _] (throw (AssertionError.)))})
        base {"mode" "typed-span-int64-aggregate"
              "typed-field-id" (:field-id int64-binding)
              "typed-gte" "0" "aggregate-count" "1"}]
    (doseq [binding-params
            [{"typed-attribute-key" "game.score"}
             {"typed-attribute-key" "game.score"
              "typed-attribute-type" "int64"}
             {"typed-manifest-version" "3"}
             {"typed-attribute-key" "game.score"
              "typed-attribute-type" "int64"
              "typed-manifest-version" "0"}
             {"typed-attribute-key" "game.score"
              "typed-attribute-type" "int64"
              "typed-manifest-version" "9223372036854775808"}
             {"typed-attribute-key" "game.score"
              "typed-attribute-type" "int64"
              "typed-manifest-version" "3x"}]]
      (is (= 400
             (:status
              (handler {:request-method :get :uri "/oscope"
                        :query-params (merge base binding-params)})))))))

(deftest typed-aggregate-web-errors-are-bounded-and-visible
  (let [handler (web/handler
                 {:typed-span-fields catalog
                  :load-command (fn [_ _] (throw (AssertionError.)))})]
    (testing "stale exact binding"
      (let [response
            (handler {:request-method :get :uri "/oscope"
                      :query-params
                      {"mode" "typed-span-int64-aggregate"
                       "typed-field-id" (:field-id int64-binding)
                       "typed-attribute-key" "game.score"
                       "typed-attribute-type" "int64"
                       "typed-manifest-version" "2"
                       "typed-gte" "0" "aggregate-count" "1"}})]
        (is (= 409 (:status response)))
        (is (str/includes? (:body response) "Typed schema changed"))))
    (testing "stale canonical reload"
      (let [canonical (web/selection-query-string selection)
            stale (str/replace canonical
                               "typed-manifest-version=3"
                               "typed-manifest-version=2")
            response (handler {:request-method :get :uri "/oscope"
                               :query-string (subs stale 1)})]
        (is (= 409 (:status response)))
        (is (str/includes? (:body response) "Typed schema changed"))))
    (testing "empty half-open range"
      (let [response
            (handler {:request-method :get :uri "/oscope"
                      :query-params
                      {"mode" "typed-span-int64-aggregate"
                       "typed-field-id" (:field-id int64-binding)
                       "typed-gte" "5" "typed-lt" "5"
                       "aggregate-count" "1"}})]
        (is (= 400 (:status response)))
        (is (str/includes? (:body response) "Invalid typed aggregate"))))
    (testing "explicit blank required lower bound"
      (let [response
            (handler {:request-method :get :uri "/oscope"
                      :query-params
                      {"mode" "typed-span-int64-aggregate"
                       "typed-field-id" (:field-id int64-binding)
                       "typed-gte" "" "typed-lt" ""
                       "aggregate-count" "1"}})]
        (is (= 400 (:status response)))))))

(deftest shared-live-source-routes-aggregate-with-confirmed-startup-context
  (let [seen (atom nil)]
    (with-redefs [schema/ensure-schema! (fn [_])
                  typed-catalog/acquire
                  (fn [connection descriptors]
                    (is (= [::connection ::descriptors]
                           [connection descriptors]))
                    catalog)
                  query-chdb/run
                  (fn [_ plan context]
                    (if (= :typed-span-int64-aggregate
                           (get-in plan [:selection :mode]))
                      (do (reset! seen context) result)
                      []))]
      (let [source (live/open! {:connection ::connection
                                :ensure-schema? false
                                :typed-span-descriptors ::descriptors
                                :now-fn (constantly now)})]
        (try
          (let [screen ((:loader source) selection)]
            (is (= :telemetry-typed-span-int64-aggregate (:view screen)))
            (is (identical? ::descriptors (:typed-span-descriptors @seen)))
            (is (= catalog (:typed-span-fields @seen))))
          (finally
            (live/close! source)))))))
