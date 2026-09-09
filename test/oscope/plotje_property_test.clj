(ns oscope.plotje-property-test
  (:require [clojure.test :refer [deftest is]]
            [hegel.core :as h]
            [hegel.generator :as g]
            [oscope.plotje.svg :as svg]))

(defn- finite-svg? [rendered]
  (not (re-find #"(?:NaN|Infinity|##Inf)" rendered)))

(deftest generated-finite-domains-never-produce-non-finite-svg-geometry
  (let [finite-double (g/double {:nan? false :infinity? false})
        result
        (h/run-test!
         {:name "oscope Plotje finite numeric geometry"
          :database "" :verbosity :quiet :derandomize? true :test-cases 240}
         (fn [_]
           (let [x (h/draw! finite-double)
                 y (h/draw! finite-double)
                 equal-domain? (h/draw! (g/boolean))
                 x2 (if equal-domain? x (h/draw! finite-double))
                 y2 (if equal-domain? y (h/draw! finite-double))
                 layers [{:mark :line :x :x :y :y}
                         {:mark :point :x :x :y :y}
                         {:mark :area :x :x :y :y}
                         {:mark :tick :x :x :y :y}]
                 rendered
                 (mapv #(svg/spec->svg {:data % :layers layers})
                       [[{:x x :y y}]
                        [{:x x :y y} {:x x2 :y y2}]])]
             ;; Exercise the singleton mark branches in every generated case,
             ;; alongside a generated equal-or-distinct two-row domain.
             (when-not (every? finite-svg? rendered)
               (throw
                (ex-info "finite chart data produced non-finite SVG geometry"
                         {:hegel/origin "oscope.plotje/finite-geometry"}))))))]
    (is (:passed? result)
        (pr-str (select-keys result [:status :seed :failures :final :error])))
    (is (false? (:flaky? result)))))
