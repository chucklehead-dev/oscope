(ns oscope.plotje-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [oscope.plotje.spec :as spec]
            [oscope.plotje.svg :as svg]))

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
