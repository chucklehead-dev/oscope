(ns oscope.export-options-native-runner-test
  "Pure controls for the real native fixture's execution witness. No DB opens."
  (:require [clojure.test :as test :refer [deftest is]]
            [jdbc.chdb.native :as native]
            [oscope.durable-native-child-runner :as child]
            [oscope.export-options-native-test-runner :as fixture]))

(def ^:private ^:dynamic *fake-companion* nil)
(defn- fake-adapter [_] "[]")

(defn- run-control [mode exercise]
  (let [output (atom nil) failure (atom nil)]
    (with-redefs [fixture/json-companion-capability
                  (fn [] {:adapter #'fake-adapter :companion #'*fake-companion*})
                  native/chdb-stream-insert-n (fn [& _] nil)
                  test/test-vars (fn [_] (exercise))
                  child/-main (fn [& _] (test/test-vars []))]
      (reset! output
              (with-out-str
                (try (fixture/-main mode "embedded" "--require-json-companion")
                     (catch Throwable e (reset! failure e))))))
    {:output @output :failure @failure}))

(defn- observed-adapter []
  ;; Dereference explicitly so this exercises the fixture's replacement even
  ;; if a host elects to direct-link ordinary private test function calls.
  (@#'fake-adapter []))

(deftest actual-binding-witness-is-required-not-merely-an-adapter-call
  (let [good (run-control "stream"
                          #(do (native/chdb-stream-insert-n)
                               (binding [*fake-companion* :active]
                                 (observed-adapter))))
        missing (run-control "stream"
                             #(do (native/chdb-stream-insert-n)
                                  (observed-adapter)))]
    (is (nil? (:failure good)))
    (is (.contains (:output good) ":json-companion-calls 1 :bound 1 :empty-bound 1"))
    ;; The earlier standalone adapter also ran but declined the bound hook.
    ;; If the gate only counted adapter calls, this negative control would pass.
    (is (= ::fixture/unobserved-json-companion (:type (ex-data (:failure missing)))))))

(deftest noop-ingestion-cannot-qualify-the-positive-witness
  (let [result (run-control "stream" #(native/chdb-stream-insert-n))]
    (is (= ::fixture/unobserved-json-companion (:type (ex-data (:failure result)))))))

(deftest configured-control-forbids-all-native-adapter-calls
  (let [good (run-control "stream-fallback" (fn [] nil))
        bad (run-control "stream-fallback" #(binding [*fake-companion* :active]
                                               (observed-adapter)))]
    (is (nil? (:failure good)))
    (is (.contains (:output good) ":json-companion-calls 0 :bound 0 :empty-bound 0"))
    (is (= ::fixture/unexpected-json-companion (:type (ex-data (:failure bad)))))))
