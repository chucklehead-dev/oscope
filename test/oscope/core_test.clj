(ns oscope.core-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [oscope.command :as command]
            [oscope.effect :as effect]
            [oscope.plotje.spec :as plotje-spec]
            [oscope.plotje.svg :as plotje-svg]
            [oscope.query :as query]
            [oscope.sample :as sample]
            [oscope.ui.selection :as selection]
            [oscope.ui.web :as web]
            [oscope.view-model :as view-model]))

(deftest canonical-contract-is-serializable-and-self-consistent
  (let [screen sample/default-screen]
    (is (= screen (edn/read-string (pr-str screen))))
    (is (= 1 (:oscope.view/version screen)))
    (is (= :telemetry-distribution (:view screen)))
    (is (= (:selection screen) (get-in screen [:query-plan :selection])))
    (is (= (get-in screen [:table :rows]) (get-in screen [:chart :data])))))

(deftest selection-and-command-contract-fail-closed
  (let [screen sample/default-screen
        logs (selection/select-signal screen :logs)]
    (is (= {:signal :logs :field :service-name :window :1h :limit 12} logs))
    (is (= logs (:selection (command/validate (command/query-command 1 logs)))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (query/normalize-selection (assoc logs :sql "select 1"))))
    (is (thrown? clojure.lang.ExceptionInfo
                 (command/validate {:command/type :query :selection logs})))))

(deftest effect-only-installs-the-validated-whole-screen
  (let [state (atom sample/default-screen)
        selected {:signal :metrics :field :metric-name :window :15m :limit 5}]
    (effect/apply-command! state sample/screen-for-selection
                           (command/query-command 2 selected))
    (is (= selected (:selection @state)))
    (is (= selected (get-in @state [:query-plan :selection])))
    (is (= "Metric Name in Metrics" (:title @state)))))

(deftest bounded-plotje-svg-is-escaped-and-portable
  (let [chart (assoc (:chart sample/default-screen)
                     :title "<unsafe> & chart")
        svg (plotje-svg/spec->svg chart)]
    (is (= chart (plotje-spec/validate-spec chart)))
    (is (re-find #"&lt;unsafe&gt; &amp; chart" svg))
    (is (not (re-find #"<unsafe>" svg)))
    (is (re-find #"plotje-x-tick" svg))))

(deftest empty-screen-remains-accessible
  (let [screen (view-model/screen
                (query/compile-query {} sample/sample-time) [])
        html (web/render-page screen)]
    (is (= :empty (:status screen)))
    (is (re-find #"No telemetry matched" html))
    (is (re-find #"<table" html))))
