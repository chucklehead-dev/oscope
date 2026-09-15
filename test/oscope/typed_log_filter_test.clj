(ns oscope.typed-log-filter-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oscope.query :as query]
            [oscope.query.chdb :as query-chdb]
            [oscope.typed-catalog :as typed-catalog]
            [oscope.ui.web :as web]
            [oscope.view-model :as view-model]
            [otel.exporter.chdb.attribute-projection :as projection]
            [otel.exporter.chdb.typed-log-explorer :as log-explorer]))

(def now 2000000000000000000)
(def binding
  {:field-id "attribute_0123456789abcdefabcd"
   :attribute-key "job.attempt" :attribute-type :int64
   :attribute-location :log-attributes :manifest-version 4})
(def catalog [binding])
(def selection
  {:mode :typed-log-filter :schema-binding binding :operator :gte
   :value 9007199254740993 :window :1h :limit 12})
(def result
  {:attribute-key "job.attempt" :attribute-type :int64
   :attribute-location :log-attributes
   :field-id (:field-id binding) :manifest-version 4 :signal :logs
   :filter {:operator :gte :value 9007199254740993}
   :coverage {:valid 1 :present-empty 0 :absent 1 :invalid 1
              :historical-untyped-fallback 1
              :historical-untyped-unavailable 1 :total 5}
   :matches [{:attribute-key "job.attempt" :attribute-type :int64
              :attribute-location :log-attributes
              :attribute-value 9007199254740993 :body "retry"
              :field-id (:field-id binding) :manifest-version 4
              :service-name "worker" :severity-text "WARN"
              :signal :logs :source :typed :span-id "span"
              :timestamp-unix-nano 1 :trace-id "trace" :typed-status 3}]})

(deftest confirmed-log-catalog-is-redacted-and-target-scoped
  (with-redefs [projection/confirmed-log-fields
                (fn [descriptors connection]
                  (is (= [::descriptors ::connection] [descriptors connection]))
                  [{:id (:field-id binding) :key "job.attempt" :type :int64
                    :location :log-attributes :identity {:version 4}
                    :physical {:value-column "secret-column"}
                    :provenance {:path "secret-path"}}
                   {:id "attribute_11111111111111111111" :key "ignored"
                    :type :int64 :location :span-attributes
                    :identity {:version 4}}])]
    (let [fields (typed-catalog/acquire-logs ::connection ::descriptors)]
      (is (= catalog fields))
      (is (not (str/includes? (pr-str fields) "secret"))))))

(deftest typed-log-plan-and-execution-preserve-the-exact-binding
  (let [plan (query/compile-query selection now)
        seen (atom nil)]
    (is (= binding (get-in plan [:request :schema-binding])))
    (with-redefs [log-explorer/typed-log-filtered-records
                  (fn [connection descriptors request]
                    (reset! seen [connection descriptors request]) result)]
      (is (= result (query-chdb/run ::connection plan
                                    {:typed-log-descriptors ::descriptors
                                     :typed-log-fields catalog})))
      (is (= binding (get-in @seen [2 :schema-binding])))
      (is (= :logs (get-in @seen [2 :signal]))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"no longer available"
         (query-chdb/run ::connection
                         (query/compile-query
                          (assoc-in selection [:schema-binding :manifest-version] 3)
                          now)
                         {:typed-log-descriptors ::descriptors
                          :typed-log-fields catalog})))))

(deftest typed-log-view-validates-coverage-and-renders-bounded-values
  (let [plan (query/compile-query selection now)
        screen (view-model/screen plan result {:typed-log-fields catalog})
        html (web/render-page screen)]
    (is (= :telemetry-typed-log-filter (:view screen)))
    (is (= "9007199254740993"
           (get-in screen [:table :rows 0 :display-value])))
    (doseq [text ["Typed Int64 logs" "Filter typed logs"
                  "Historical value unavailable" "retry"]]
      (is (str/includes? html text) text))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not match"
         (view-model/screen plan (assoc-in result [:coverage :total] 6)
                            {:typed-log-fields catalog})))))

(deftest web-round-trip-retains-complete-log-schema-binding
  (let [params {"mode" "typed-log-filter"
                "typed-field-id" (:field-id binding)
                "typed-attribute-key" "job.attempt"
                "typed-attribute-type" "int64"
                "typed-attribute-location" "log-attributes"
                "typed-manifest-version" "4"
                "typed-operator" "gte" "typed-value" "9007199254740993"
                "window" "1h" "limit" "12"}
        parsed (web/selection-from-params params nil catalog)]
    (is (= selection parsed))
    (is (str/includes? (web/selection-query-string parsed)
                       "typed-manifest-version=4"))
    (testing "saved URL fails closed after a schema version change"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"no longer available"
           (web/selection-from-params
            (assoc params "typed-manifest-version" "3") nil catalog))))))
