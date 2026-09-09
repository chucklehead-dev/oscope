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

(deftest periodic-checkpoint-cadence-is-checked-exactly
  (let [commands (valid-commands)
        periodic (-> commands
                     (assoc-in [4 :command] :checkpoint-publish)
                     (assoc-in [5 :kind] :checkpoint))]
    (is (= [:checkpoint :wal :checkpoint :wal]
           (mapv :kind
                 (assertions/assert-publication-order!
                  periodic [:checkpoint :wal :checkpoint :wal]))))
    (is (thrown? Exception
                 (assertions/assert-publication-order!
                  periodic [:checkpoint :wal :wal :checkpoint])))))

(deftest ingest-lifecycle-is-selected-by-durable-object
  (let [ingest (valid-commands)
        other-object
        [(publication :checkpoint-publish {:kind :checkpoint
                                           :object "opaque-7"})
         (commit :checkpoint {:kind :checkpoint :object "opaque-7"})]
        other-object (mapv #(assoc % :durable-object "opaque-6")
                           other-object)]
    (is (= ingest
           (assertions/select-ingest-commands!
            (into other-object ingest) 3)))
    (is (thrown? Exception
                 (assertions/select-ingest-commands!
                  (into ingest
                        (map #(assoc % :durable-object "opaque-8") ingest))
                  3)))))

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

(deftest successful-renewal-must-precede-a-release
  (let [identity {:durable-object "opaque-1"
                  :writer {:writer "opaque-2" :generation 1}}
        renew-invoke {:seq 1 :phase :invoke :operation :durable/renew
                      :operation-id 10 :input identity}
        renew-return {:seq 2 :phase :return :operation-id 10
                      :value {:outcome :committed}}
        release (fn [seq identity]
                  {:seq seq :phase :invoke :operation :durable/release
                   :operation-id 11 :input identity})]
    (is (nil? (assertions/assert-renewal-before-release!
               [renew-invoke renew-return (release 3 identity)]
               "opaque-1" (:writer identity))))
    (is (thrown? Exception
                 (assertions/assert-renewal-before-release!
                  [renew-invoke (release 2 identity)
                   (assoc renew-return :seq 3)]
                  "opaque-1" (:writer identity))))
    (is (thrown? Exception
                 (assertions/assert-renewal-before-release!
                  [renew-invoke
                   (assoc-in renew-return [:value :outcome] :error)
                   (release 3 identity)]
                  "opaque-1" (:writer identity))))
    (is (thrown? Exception
                 (assertions/assert-renewal-before-release!
                  [renew-invoke renew-return
                   (release 3 (assoc-in identity [:writer :generation] 2))]
                  "opaque-1" (:writer identity))))
    (is (thrown? Exception
                 (assertions/assert-renewal-before-release!
                  [renew-invoke renew-return
                   (release 3 (assoc identity :durable-object "opaque-9"))]
                  "opaque-1" (:writer identity))))
    (is (thrown? Exception
                 (assertions/assert-renewal-before-release!
                  [renew-invoke renew-return (release 3 identity)]
                  "opaque-9" (:writer identity))))
    (is (thrown? Exception
                 (assertions/assert-renewal-before-release!
                  [renew-invoke renew-return (release 3 identity)]
                  "opaque-1" {:writer "opaque-7" :generation 2})))))
