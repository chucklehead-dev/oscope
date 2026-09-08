(ns oscope.durable-fault-report-verify
  "Validate the opt-in control provider and exact physical Durable join points."
  (:require [clojure.edn :as edn]))

(def ^:private expected
  {:durable/acquire 'jdbc.chdb.durable.control/acquire!
   :durable/publish-wal 'jdbc.chdb.durable.control/publish-wal-bytes!
   :durable/publish-checkpoint
   'jdbc.chdb.durable.control/publish-checkpoint-file!
   :durable/commit-reference 'jdbc.chdb.durable.control/commit-reference!
   :durable/renew 'jdbc.chdb.durable.control/renew!
   :durable/release 'jdbc.chdb.durable.control/release!})

(defn -main [report-path]
  (let [report (edn/read-string (slurp report-path))
        aspects (into {} (map (juxt :id identity)) (:aspects report))]
    (when-not (and (= 1 (:schema report))
                   (= "jolt.aspect-ir/v1" (:weaver report))
                   (true? (:control-enabled? report)))
      (throw (ex-info "Durable fault report did not enable control advice"
                      {:oscope.durable-fault/error true})))
    (when-not (= (set (keys expected)) (set (keys aspects)))
      (throw (ex-info "Durable fault report selected the wrong aspects"
                      {:oscope.durable-fault/error true
                       :actual (keys aspects)})))
    (doseq [[id entry] expected
            :let [aspect (get aspects id)
                  consumer (first (:consumers aspect))]]
      (when-not (and (= entry (get-in aspect [:match :entry]))
                     (= 1 (count (:sites aspect)))
                     (= 1 (count (:consumers aspect)))
                     (= 'jolt.aspect-packs.chdb-durable.faults/aspect-provider
                        (:provider consumer))
                     (= :control-v1 (:contract consumer)))
        (throw (ex-info "Durable fault join point was not woven exactly once"
                        {:oscope.durable-fault/error true :aspect id}))))
    (println "PASS: Durable fault control advice woven at six exact join points")))
