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
    (is (str/includes? rendered "rgb(228,26,28)"))
    (is (str/includes? rendered "rgb(55,126,184)"))))

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
                         [{:minute 0 :latency-ms ##Inf :series "p50"}]))))))
