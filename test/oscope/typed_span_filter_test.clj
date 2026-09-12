(ns oscope.typed-span-filter-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oscope.live :as live]
            [oscope.query :as query]
            [oscope.query.chdb :as query-chdb]
            [oscope.typed-catalog :as typed-catalog]
            [oscope.typed-query :as typed-query]
            [oscope.ui.web :as web]
            [oscope.view-model :as view-model]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.explorer :as explorer]
            [otel.exporter.chdb.schema :as schema]))

(def now 2000000000000000000)
(def bool-binding
  {:field-id "attribute_0123456789abcdefabcd"
   :attribute-key "game.ready" :attribute-type :boolean :manifest-version 3})
(def string-binding
  {:field-id "attribute_abcdef0123456789abcd"
   :attribute-key "game.phase" :attribute-type :string :manifest-version 3})
(def int64-binding
  {:field-id "attribute_11111111111111111111"
   :attribute-key "game.score" :attribute-type :int64 :manifest-version 3})
(def catalog [bool-binding string-binding int64-binding])

(defn- selection [binding value]
  {:mode :typed-span-filter :schema-binding binding :operator :eq :value value
   :window :1h :limit 12})

(defn- result [binding value]
  {:attribute-key (:attribute-key binding)
   :attribute-type (:attribute-type binding)
   :coverage {:valid 1 :present-empty 1 :absent 2 :invalid 3
              :historical-untyped-fallback 4
              :historical-untyped-unavailable 5 :total 16}
   :field-id (:field-id binding) :filter {:operator :eq :value value}
   :manifest-version 3 :signal :spans
   :matches [{:attribute-key (:attribute-key binding)
              :attribute-type (:attribute-type binding)
              :attribute-value value :field-id (:field-id binding)
              :manifest-version 3 :parent-span-id "" :service-name "game"
              :signal :spans :source :typed :span-id "span"
              :span-name "turn" :timestamp-unix-nano 1
              :trace-id "trace"
              :typed-status (if (and (= :string (:attribute-type binding))
                                     (= "" value))
                              2 3)}]})

(deftest confirmed-catalog-is-logical-redacted-and-bounded-for-display
  (with-redefs [projection/confirmed-span-fields
                (fn [descriptor target]
                  (is (= [::descriptors ::connection] [descriptor target]))
                  [{:id (:field-id bool-binding) :key "game.ready" :type :boolean
                    :identity {:version 3 :dataset-id "secret-dataset"}
                    :physical {:value-column "private_column"}
                    :provenance [{:source "private/path"}]}
                   {:id (:field-id int64-binding) :key "game.score"
                    :type :int64 :identity {:version 3}}])]
    (let [actual (typed-catalog/acquire ::connection ::descriptors)]
      (is (= [bool-binding int64-binding] actual))
      (is (not (str/includes? (pr-str actual) "secret")))
      (is (not (str/includes? (pr-str actual) "private"))))))

(deftest typed-plan-is-logical-and-executor-requires-the-exact-binding
  (doseq [[binding value operator]
          [[bool-binding false :eq]
           [string-binding "" :eq]
           [int64-binding 9007199254740993 :gte]
           [int64-binding typed-query/int64-min :eq]
           [int64-binding typed-query/int64-max :lt]]]
    (let [plan (query/compile-query (assoc (selection binding value)
                                          :operator operator) now)
          seen (atom nil)]
      (is (= value (get-in plan [:request :value])))
      (is (empty? (filter #(contains? (:request plan) %)
                          [:sql :capability :physical :attribute-key])))
      (with-redefs [explorer/typed-span-filtered-traces
                    (fn [connection descriptors request]
                      (reset! seen [connection descriptors request])
                      (assoc (result binding value)
                             :filter {:operator operator :value value}))]
        (is (= (assoc (result binding value)
                      :filter {:operator operator :value value})
               (query-chdb/run ::connection plan
                               {:typed-span-descriptors ::descriptors
                                :typed-span-fields catalog})))
        (is (= ::descriptors (second @seen)))
        (is (= (:attribute-key binding) (get-in @seen [2 :attribute-key]))))))
  (let [plan (query/compile-query (selection (assoc bool-binding :manifest-version 2)
                                             false) now)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no longer available"
                          (query-chdb/run ::connection plan
                                          {:typed-span-descriptors ::descriptors
                                           :typed-span-fields catalog})))))

(deftest query-uses-the-canonical-typed-capability
  (with-redefs [typed-query/filter-capability
                {:operators {:boolean [:prefix] :string [:eq]}
                 :signals [:spans] :types [:boolean :string]}]
    (is (= :prefix
           (:operator
            (query/normalize-selection
             (assoc (selection bool-binding false) :operator :prefix)))))))

(deftest table-screen-preserves-false-and-empty-string-and-explains-coverage
  (doseq [[binding value display] [[bool-binding false "false"]
                                   [string-binding "" "(empty string)"]
                                   [int64-binding 9007199254740993
                                    "9007199254740993"]]]
    (let [plan (query/compile-query (selection binding value) now)
          screen (view-model/screen plan (result binding value)
                                    {:typed-span-fields catalog})]
      (is (= :telemetry-typed-span-filter (:view screen)))
      (is (nil? (:chart screen)))
      (is (= value (get-in screen [:table :rows 0 :attribute-value])))
      (is (= display (get-in screen [:table :rows 0 :display-value])))
      (is (str/includes? (:freshness-notice screen) "two bounded live queries"))
      (is (= [{:status :valid :count 1}
              {:status :present-empty :count 1}
              {:status :absent :count 2}
              {:status :invalid :count 3}
              {:status :historical-untyped-fallback :count 4}
              {:status :historical-untyped-unavailable :count 5}
              {:status :total :count 16}]
             (:coverage screen))))))

(deftest int64-web-values-remain-exact-decimal-text-until-bounded-parsing
  (doseq [[operator value] [[:eq 9007199254740993]
                            [:gte typed-query/int64-min]
                            [:lt typed-query/int64-max]]]
    (let [selected (assoc (selection int64-binding value) :operator operator)
          query-string (web/selection-query-string selected)
          parsed (web/selection-from-params
                  (web/parse-query-params (subs query-string 1)) catalog)]
      (is (= selected parsed))
      (is (str/includes? query-string (str "typed-value=" value)))))
  (let [screen (view-model/screen
                (query/compile-query (selection int64-binding
                                                9007199254740993) now)
                (result int64-binding 9007199254740993)
                {:typed-span-fields catalog})
        html (web/render-page screen)]
    (is (str/includes? html "inputmode=\"numeric\""))
    (is (not (str/includes? html "type=\"number\" name=\"typed-value\"")))
    (is (= {:status :total :count 16}
           (last (:coverage screen))))))

(deftest typed-results-require-conserved-closed-coverage-and-bounded-rows
  (let [plan (query/compile-query (selection bool-binding false) now)
        base (result bool-binding false)]
    (doseq [invalid [(assoc-in base [:coverage :valid] -1)
                     (assoc-in base [:coverage :valid] 1.5)
                     (assoc-in base [:coverage :total] 17)
                     (assoc-in base [:coverage :unexpected] 0)
                     (assoc-in base [:matches 0 :timestamp-unix-nano] -1)
                     (assoc-in base [:matches 0 :trace-id] 42)
                     (assoc-in base [:matches 0 :service-name]
                               (apply str (repeat 161 "x")))]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (view-model/screen plan invalid
                                      {:typed-span-fields catalog}))))))

(deftest visible-catalog-is-capped-and-keeps-an-outside-selected-binding
  (let [large (mapv (fn [index]
                      {:field-id (str "attribute_" (format "%020x" index))
                       :attribute-key (str "game.field." index)
                       :attribute-type :string :manifest-version 3})
                    (range 101))
        selected (last large)
        visible (typed-query/visible-catalog large selected)]
    (is (= 100 (count (:fields visible))))
    (is (= 101 (:total visible)))
    (is (true? (:truncated? visible)))
    (is (some #{selected} (:fields visible)))
    (is (not (some #{(nth large 99)} (:fields visible))))))

(deftest web-round-trips-an-exact-binding-and-rejects-stale-urls-visibly
  (let [selected (selection bool-binding false)
        params (web/parse-query-params (subs (web/selection-query-string selected) 1))]
    (is (= selected (web/selection-from-params params catalog))))
  (let [handler (web/handler {:typed-span-fields catalog
                              :load-command (fn [_ _] (throw (AssertionError.)))})
        response (handler {:request-method :get :uri "/oscope"
                           :query-params {"mode" "typed-span-filter"
                                          "typed-field-id" "attribute_99999999999999999999"
                                          "typed-value" "false"}})]
    (is (= 409 (:status response)))
    (is (str/includes? (:body response) "Typed schema changed"))))

(deftest raw-boolean-false-form-canonicalizes-before-query-execution
  (let [loads (atom 0)
        handler
        (web/handler
         {:typed-span-fields catalog
          :load-command
          (fn [_ selected]
            (swap! loads inc)
            (view-model/screen (query/compile-query selected now)
                               (result bool-binding false)
                               {:typed-span-fields catalog}))})
        response
        (handler {:request-method :get :uri "/oscope"
                  :query-params
                  {"mode" "typed-span-filter"
                   "typed-field-id" (:field-id bool-binding)
                   "typed-operator" "eq" "typed-value" "false"
                   "window" "1h" "limit" "12"}})
        location (get-in response [:headers "Location"])]
    (is (= 303 (:status response)))
    (is (zero? @loads))
    (doseq [part ["typed-field-id=attribute_0123456789abcdefabcd"
                  "typed-attribute-key=game.ready"
                  "typed-attribute-type=boolean"
                  "typed-manifest-version=3"
                  "typed-operator=eq" "typed-value=false"]]
      (is (str/includes? location part)))
    (let [canonical
          (handler {:request-method :get :uri "/oscope"
                    :query-string (subs location (inc (str/index-of location "?")))})]
      (is (= 200 (:status canonical)))
      (is (= 1 @loads)))))

(deftest malformed-typed-web-filters-return-bounded-bad-requests
  (let [handler (web/handler {:typed-span-fields catalog
                              :load-command (fn [_ _] (throw (AssertionError.)))})]
    (doseq [params [{"mode" "typed-span-filter"
                     "typed-field-id" (:field-id bool-binding)
                     "typed-value" "not-a-boolean"}
                    {"mode" "typed-span-filter"
                     "typed-field-id" (:field-id string-binding)
                     "typed-operator" "prefix" "typed-value" ""}
                    {"mode" "typed-span-filter"
                     "typed-field-id" (:field-id string-binding)
                     "typed-operator" "contains" "typed-value" ""}]]
      (let [response (handler {:request-method :get :uri "/oscope"
                               :query-params params})]
        (is (= 400 (:status response)))
        (is (= "<!doctype html><html lang=\"en\"><body><main><h1>Invalid typed filter</h1><p>The typed span filter request is invalid.</p></main></body></html>"
               (:body response)))))))

(deftest malformed-and-out-of-range-int64-web-values-return-bad-requests
  (let [handler (web/handler {:typed-span-fields catalog
                              :load-command (fn [_ _] (throw (AssertionError.)))})]
    (doseq [params (conj
                    (mapv (fn [value]
                            {"mode" "typed-span-filter"
                             "typed-field-id" (:field-id int64-binding)
                             "typed-operator" "gte" "typed-value" value})
                          ["1.5" "9e15" "9223372036854775808"
                           "-9223372036854775809" ""])
                    {"mode" "typed-span-filter"
                     "typed-field-id" (:field-id int64-binding)
                     "typed-operator" "contains" "typed-value" "1"})]
      (let [response (handler {:request-method :get :uri "/oscope"
                               :query-params params})]
        (is (= 400 (:status response)))))))

(deftest live-source-discovers-once-and-shares-catalog-with-loading
  (let [catalog-calls (atom 0) execution (atom nil)]
    (with-redefs [schema/ensure-schema! (fn [_])
                  typed-catalog/acquire (fn [connection descriptors]
                                        (swap! catalog-calls inc)
                                        (is (= [::connection ::descriptors]
                                               [connection descriptors]))
                                        catalog)
                  query-chdb/run (fn [_ plan context]
                                   (reset! execution context)
                                   (if (= :typed-span-filter
                                          (get-in plan [:selection :mode]))
                                     (result bool-binding false)
                                     []))]
      (let [source (live/open! {:connection ::connection
                                :ensure-schema? false
                                :typed-span-descriptors ::descriptors
                                :now-fn (constantly now)})]
        (is (= 1 @catalog-calls))
        (is (= catalog (:typed-span-fields source)))
        (is (= catalog (get-in source [:screen :controls :typed-span-fields])))
        (is (= 3 (get-in source [:screen :controls :typed-span-field-total])))
        (is (false? (get-in source [:screen :controls
                                    :typed-span-fields-truncated?])))
        ((:loader source) (selection bool-binding false))
        (is (= catalog (:typed-span-fields @execution)))
        (live/close! source)))))
