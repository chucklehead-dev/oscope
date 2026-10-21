(ns oscope.field-slot-native-probe-test
  "Pure non-vacuity controls; no database, real codec or native storage opens."
  (:require [clojure.test :as test :refer [deftest is]]
            [oscope.embedded :as embedded]
            [oscope.export-options-native-test-runner :as fixture]
            [oscope.field-slot-native-probe :as probe]))

(defn fake-factory [& _] (fn [_] []))

(defn- run-control [scenario]
  (let [projector (atom nil) failure (atom nil)
        output
        (with-out-str
          (with-redefs
            [probe/field-slot-factory-var (fn [] #'fake-factory)
             fake-factory
             (fn [& _]
               (when-not (= scenario :missing-projector)
                 (fn [_] (case scenario :declined-false false :declined-nil nil []))))
             embedded/start!
             (fn [_]
               (when (= scenario :startup-only) (@projector {}))
               ::owner)
             test/test-vars
             (fn [_]
               (when (contains? #{:selected :declined-false :declined-nil} scenario)
                 (@projector {})))
             fixture/-main
             (fn [mode consumer]
               (is (= ["stock-codec" "embedded-typed"] [mode consumer]))
               (reset! projector (@#'fake-factory))
               (embedded/start! {})
               (test/test-vars []))]
            (try (probe/-main) (catch Throwable ex (reset! failure ex)))))]
    {:output output :failure @failure}))

(deftest actual-projection-must-run-after-startup
  (let [good (run-control :selected)]
    (is (nil? (:failure good)))
    (is (.contains (:output good) ":bounded-field-slots-ingestion-calls 1")))
  (doseq [scenario [:missing-projector :constructor-only :startup-only :declined-false :declined-nil]]
    (let [bad (run-control scenario)]
      (is (= ::probe/unobserved-field-slots (:type (ex-data (:failure bad))))
          (str scenario))
      (is (not (.contains (:output bad) ":bounded-field-slots-ingestion-calls"))))))
