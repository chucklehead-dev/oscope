(ns oscope.query-view-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [oscope.plotje.svg :as plotje]
            [oscope.query :as query]
            [oscope.view-model :as view-model]
            [otel.exporter.chdb.explorer :as explorer]))

(def now 2000000000000000000)

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
      (is (= [] (query/run ::connection plan))))))

(deftest unsupported-or-tampered-plans-fail-closed
  (testing "unknown controls and signal-specific fields cannot imply SQL"
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/compile-query {:group-by :freeform} now)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/compile-query
                  {:signal :logs :field :span-name} now)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/compile-query
                  {:limit (inc explorer/max-result-limit)} now))))
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
