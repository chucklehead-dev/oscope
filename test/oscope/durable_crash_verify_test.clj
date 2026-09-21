(ns oscope.durable-crash-verify-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.durable-crash-verify :as verify]))

(def ^:private complete
  {:traces {:n 2 :first 1 :second 1}
   :logs {:n 2 :first 1 :second 1}
   :gauge {:n 2 :first 1 :second 1}
   :sum {:n 2 :first 1 :second 1}
   :histogram {:n 2 :first 1 :second 1}})

(defn- failure-type [value]
  (try
    (verify/assert-recovered-counts! value)
    nil
    (catch Throwable error
      (:type (ex-data error)))))

(deftest fresh-reader-count-oracle-rejects-loss-duplication-and-name-substitution
  (is (true? (verify/assert-recovered-counts! complete)))
  ;; These are causal red controls for the fresh-reader oracle. Each preserves
  ;; the remaining four signal tables so a broad aggregate count cannot hide a
  ;; lost, duplicated, or wrongly identified logical signal.
  (doseq [mutant [(assoc-in complete [:logs :n] 1)
                  (assoc-in complete [:sum :n] 3)
                  (assoc-in complete [:histogram :second] 0)]]
    (is (= :oscope.durable-crash-verify/recovery-mismatch
           (failure-type mutant)))))
