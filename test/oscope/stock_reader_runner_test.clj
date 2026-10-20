(ns oscope.stock-reader-runner-test
  "Witness controls only; real storage qualification is the separate fixture."
  (:require [clojure.test :as test :refer [deftest is]]
            [jdbc.chdb.native :as native]
            [jolt.codec.experimental :as codec]
            [jolt.codec.reader.experimental :as reader]
            [oscope.durable-integration-test :as integration]
            [oscope.durable-native-child-runner :as child]
            [oscope.export-options-native-test-runner :as fixture]
            [oscope.server :as server]))

(deftest reader-witness-rejects-startup-only-and-declining-implementations
  (let [capability (reader/open-reader
                     {:number-reader (fn [text _] (Long/parseLong text))
                      :fallback-reader (fn [& _] :fallback)})]
    (doseq [[calls decline? expected]
            [[0 false ::fixture/unobserved-stock-reader]
             [3 true ::fixture/unobserved-stock-reader]
             [3 false nil]]]
      (let [failure (atom nil)]
        (with-redefs [server/start!
                      (fn [_]
                        ;; Exactly the later required count, all at startup.
                        ;; Without reset, a no-ingest fixture would qualify.
                        (dotimes [_ 3] (reader/try-read-string capability "{}"))
                        nil)
                      native/chdb-stream-insert-n (fn [& _] :synthetic)
                      test/test-vars
                      (fn [_]
                        (server/start!
                          {:json-reader (:json-reader integration/*ingest-options*)
                           :export-options (:export-options integration/*storage-options*)
                           :db-spec {:owned-compact-stream? true}})
                        (native/chdb-stream-insert-n)
                        (codec/encode-prefixed-records (codec/open-codec) "" [1] vector 100)
                        (dotimes [_ calls]
                          (reader/try-read-string capability "{}" (if decline? {:future true} {}))))
                      child/-main (fn [& _] (test/test-vars []))]
          (with-out-str
            (try (fixture/-main "stock-reader" "standalone")
                 (catch Exception e (reset! failure e)))))
        (is (= expected (:type (ex-data @failure))))))))

(defn -main [& _]
  (let [result (test/run-tests 'oscope.stock-reader-runner-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
