(ns oscope.plotje-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing thrown-with-msg?]]
            [oscope.plotje.spec :as spec]
            [oscope.plotje.svg :as svg]))

(defn- finite-svg? [rendered]
  (not (re-find #"(?:NaN|Infinity|##Inf)" rendered)))

(def latency
  {:title "Checkout latency by percentile"
   :x-label "Minute"
   :y-label "Latency (ms)"
   :width 760
   :height 420
   :data [{:minute 0 :latency-ms 31 :series "p50"}
          {:minute 1 :latency-ms 34 :series "p50"}
          {:minute 0 :latency-ms 94 :series "p95"}
          {:minute 1 :latency-ms 102 :series "p95"}]
   :layers [{:mark :line :x :minute :y :latency-ms :color :series}
            {:mark :point :x :minute :y :latency-ms :color :series}]})

(deftest colored-line-and-point-layers-preserve-portable-semantics
  (let [rendered (svg/spec->svg latency)]
    (is (str/starts-with? rendered "<svg"))
    (is (= 2 (count (re-seq #"<polyline" rendered))))
    (is (= 4 (count (re-seq #"<circle" rendered))))
    (is (str/includes? rendered "#e41a1c"))
    (is (str/includes? rendered "#377eb8"))))

(deftest layered-area-rule-tick-and-bounded-style-options-render
  (let [rendered
        (svg/spec->svg
         {:title "Latency budget"
          :palette ["#2563eb" "#dc2626"]
          :grid? true
          :data [{:x 1 :latency 18 :budget 25}
                 {:x 2 :latency 29 :budget 25}]
          :layers [{:mark :area :x :x :y :latency :fill "#93c5fd"
                    :stroke "#2563eb" :opacity 0.4}
                   {:mark :rule :x :x :y :budget :stroke "#dc2626"
                    :stroke-width 2}
                   {:mark :tick :x :x :y :latency :stroke "#1d4ed8"}]})]
    (is (str/includes? rendered "<polygon"))
    (is (= 2 (count (re-seq #"class=\"plotje-rule\"" rendered))))
    (is (= 2 (count (re-seq #"class=\"plotje-tick\"" rendered))))
    (is (str/includes? rendered "class=\"plotje-grid\""))
    (is (str/includes? rendered "fill=\"#93c5fd\""))))

(deftest negative-bars-have-valid-nonnegative-svg-heights
  (let [rendered
        (svg/spec->svg
         {:data [{:label "gain" :value 3}
                 {:label "loss" :value -2}]
          :layers [{:mark :bar :x :label :y :value}]})]
    (is (= 2 (count (re-seq #"<rect x=" rendered))))
    (is (str/includes? rendered ">gain</text>"))
    (is (str/includes? rendered ">loss</text>"))
    (is (not (str/includes? rendered "height=\"-")))))

(deftest singleton-numeric-domains-render-visible-finite-marks
  (let [timestamp 1699999800000000000
        rendered
        (svg/spec->svg
         {:data [{:time timestamp :value 0.0 :budget 0.0}]
          :layers [{:mark :line :x :time :y :value}
                   {:mark :point :x :time :y :value}
                   {:mark :area :x :time :y :value}
                   {:mark :rule :x :time :y :budget}]})]
    (is (finite-svg? rendered))
    (is (str/includes? rendered
                       "class=\"plotje-line-singleton\" cx=\"403.00\""))
    (is (str/includes? rendered "<circle cx=\"403.00\""))
    (is (str/includes? rendered "class=\"plotje-area-singleton\""))
    (is (str/includes? rendered
                       "class=\"plotje-rule\" x1=\"70.00\" x2=\"736.00\""))))

(deftest ordinary-two-point-domain-retains-endpoint-geometry
  (let [rendered
        (svg/spec->svg
         {:data [{:time 10.0 :value -2.0}
                 {:time 20.0 :value 4.0}]
          :layers [{:mark :line :x :time :y :value}]})]
    (is (str/includes? rendered
                       "points=\"70.00,362.00 736.00,46.00\""))
    (is (not (str/includes? rendered "plotje-line-singleton")))))

(deftest non-finite-layer-input-fails-before-svg-serialization
  (doseq [[mark key value]
          [[:line :time ##NaN]
           [:point :value ##Inf]
           [:area :time ##-Inf]
           [:rule :value ##NaN]]]
    (let [chart {:data [{:time 1.0 :value 2.0}]
                 :layers [{:mark mark :x :time :y :value}]}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"(?:invalid bounded scalar|finite numbers)"
           (svg/spec->svg (assoc-in chart [:data 0 key] value)))
          (str mark " must reject " value)))))

(deftest extreme-finite-and-equal-domains-render-finitely
  (doseq [rows [[{:x 0.0 :y 0.0}]
                [{:x -7.0 :y -9.0}]
                [{:x 1.7976931348623157E308 :y 1.7976931348623157E308}]
                [{:x -1.7976931348623157E308 :y -1.7976931348623157E308}
                 {:x 1.7976931348623157E308 :y 1.7976931348623157E308}]
                [{:x 42.0 :y -3.0} {:x 42.0 :y -3.0}]]]
    (is (finite-svg?
         (svg/spec->svg
          {:data rows
           :layers [{:mark :line :x :x :y :y}
                    {:mark :point :x :x :y :y}]}))
        (pr-str rows))))

(deftest parser-and-spec-caps-fail-closed
  (testing "bounded EDN parses without evaluation"
    (is (= (spec/validate-spec latency)
           (spec/parse-spec (pr-str latency))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/parse-spec "#inst \"2026-08-28\"")))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/parse-spec
                  (apply str (repeat (inc spec/max-spec-chars) "x"))))))
  (testing "shape, row, and numeric domains stay bounded"
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate-spec (assoc latency :javascript "alert(1)"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate-spec
                  (assoc latency :data
                         (vec (repeat 513 {:minute 0 :latency-ms 1}))))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate-spec
                  (assoc latency :data
                         [{:minute 0 :latency-ms ##Inf :series "p50"}]))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate-spec
                  (assoc-in latency [:layers 0 :stroke] "url(javascript:evil)"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (spec/validate-spec
                  (assoc-in latency [:layers 0 :opacity] 2.0))))))

(deftest named-data-sources-project-fields-and-keep-chart-text-reusable
  (let [template (assoc latency :data {:source :current-query
                                       :select [:minute :latency-ms :series]})
        first-rows [{:minute 0 :latency-ms 31 :series "p50" :ignored "one"}]
        later-rows [{:minute 8 :latency-ms 73 :series "p95" :ignored "two"}]
        first-chart (spec/parse-spec (pr-str template)
                                     {:current-query first-rows})
        later-chart (spec/parse-spec (pr-str template)
                                     {:current-query later-rows})]
    (is (= [{:minute 0 :latency-ms 31 :series "p50"}] (:data first-chart)))
    (is (= [{:minute 8 :latency-ms 73 :series "p95"}] (:data later-chart)))
    (is (= (dissoc first-chart :data) (dissoc later-chart :data)))
    (is (not (str/includes? (pr-str template) "73")))))

(deftest named-data-sources-fail-clearly-and-remain-bounded
  (let [template (assoc latency :data {:source :current-query
                                       :select [:minute :latency-ms :series]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data source :current-query is not available"
                          (spec/validate-spec template)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"selected field :latency-ms is missing"
                          (spec/validate-spec
                           template
                           {:current-query [{:minute 0 :series "p50"}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"unsupported keys: :aggregate"
                          (spec/validate-spec
                           (assoc template :data
                                  {:source :current-query
                                   :select [:minute :latency-ms :series]
                                   :aggregate :avg})
                           {:current-query (:data latency)})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data source :unused must contain from 1 to 512 rows"
                          (spec/validate-spec latency
                                              {:unused (vec (repeat 513
                                                                    {:value 1}))})))))
