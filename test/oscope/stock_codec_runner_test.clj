(ns oscope.stock-codec-runner-test
  (:require [clojure.test :as test :refer [deftest is]]
            [jolt.codec.experimental :as codec]
            [jdbc.chdb.native :as native]
            [oscope.embedded :as embedded]
            [oscope.durable-native-child-runner :as child]
            [oscope.export-options-native-test-runner :as fixture]))

(deftest fixture-requires-both-codec-and-native-stream-execution
  (doseq [[stream? encode? expected]
          [[false false ::fixture/unobserved-stream]
           [true false ::fixture/unobserved-stock-codec]
           [true true nil]]]
    (let [failure (atom nil)]
      (with-redefs [embedded/start! (fn [_] nil)
                    native/chdb-stream-insert-n (fn [& _] :synthetic-result)
                    child/-main
                    (fn [& _]
                      (embedded/start! {:db-spec {}})
                      (when stream? (native/chdb-stream-insert-n))
                      (when encode?
                        (codec/encode-prefixed-records (codec/open-codec) "" [1] vector 100))
                      (test/test-vars []))]
        (with-out-str
          (try (fixture/-main "stock-codec" "embedded")
               (catch clojure.lang.ExceptionInfo e (reset! failure e)))))
      (is (= expected (:type (ex-data @failure)))))))

(defn -main [& _]
  (let [result (test/run-tests 'oscope.stock-codec-runner-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
