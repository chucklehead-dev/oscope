(ns oscope.durable-history-assertions-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.durable-history-assertions :as assertions]))

(defn- publication [command reference]
  {:command command
   :durable-object "opaque-1"
   :result {:reference reference}})

(defn- commit [kind reference]
  {:command :commit-attempt
   :kind kind
   :reference reference
   :durable-object "opaque-1"
   :result {:outcome :committed}})

(defn- valid-commands []
  (let [checkpoint {:kind :checkpoint :object "opaque-2"}
        wal-1 {:kind :wal :object "opaque-3"}
        wal-2 {:kind :wal :object "opaque-4"}
        wal-3 {:kind :wal :object "opaque-5"}]
    [(publication :checkpoint-publish checkpoint)
     (commit :checkpoint checkpoint)
     (publication :publish wal-1)
     (commit :wal wal-1)
     (publication :publish wal-2)
     (commit :wal wal-2)
     (publication :publish wal-3)
     (commit :wal wal-3)]))

(deftest checkpoint-commit-precedes-parameterized-ingest-wals
  (is (= [:checkpoint :wal :wal :wal]
         (mapv :kind
               (assertions/assert-publication-order! (valid-commands) 3)))))

(deftest mismatched-reference-and-wal-first-controls-are-rejected
  (let [commands (valid-commands)]
    (is (thrown? Exception
                 (assertions/assert-publication-order!
                  (assoc-in commands [1 :reference]
                            {:kind :checkpoint :object "opaque-99"})
                  3)))
    (is (thrown? Exception
                 (assertions/assert-publication-order!
                  (assoc-in commands [0 :command] :publish)
                  3)))))
