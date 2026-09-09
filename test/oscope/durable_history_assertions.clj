(ns oscope.durable-history-assertions
  (:require [jolt.aspect-packs.chdb-durable.model :as durable-model]
            [jolt.aspect-packs.history :as history]))

(defn assert-publication-order!
  "Require one checkpoint publication/commit before `expected-wal-count` WALs."
  [commands expected-wal-count]
  (let [publication-commands
        (filterv #(contains? #{:checkpoint-publish :publish :commit-attempt}
                             (:command %))
                 commands)]
    (when (odd? (count publication-commands))
      (throw (ex-info "Durable history has an unmatched publication command"
                      {:commands (mapv :command publication-commands)})))
    (let [pairs
          (mapv
           (fn [[publication commit]]
             (let [kind (if (= :checkpoint-publish (:command publication))
                          :checkpoint
                          :wal)
                   reference (get-in publication [:result :reference])]
               (when-not (and (contains? #{:checkpoint-publish :publish}
                                         (:command publication))
                              (= :commit-attempt (:command commit))
                              (= kind (:kind commit))
                              (= reference (:reference commit))
                              (= (:durable-object publication)
                                 (:durable-object commit))
                              (contains? #{:committed :reconciled}
                                         (get-in commit [:result :outcome])))
                 (throw
                  (ex-info
                   "Durable publication was not followed by its exact commit"
                   {:publication publication :commit commit})))
               {:kind kind :reference reference}))
           (partition 2 publication-commands))]
      (when-not (= (into [:checkpoint] (repeat expected-wal-count :wal))
                   (mapv :kind pairs))
        (throw
         (ex-info "oscope Durable history did not checkpoint before ingest WALs"
                  {:publication-kinds (mapv :kind pairs)
                   :expected-wal-count expected-wal-count})))
      pairs)))

(defn assert-renewal-before-release!
  "Require a successful heartbeat renewal before the owning writer releases."
  [events]
  (let [invocations
        (into {} (map (juxt :operation-id identity))
              (filter #(= :invoke (:phase %)) events))
        renewals
        (keep
         (fn [event]
           (let [invoke (get invocations (:operation-id event))]
             (when (and (= :return (:phase event))
                        (= :durable/renew (:operation invoke))
                        (contains? #{:committed :reconciled}
                                   (get-in event [:value :outcome])))
               [(:seq event) (:input invoke)])))
         events)
        releases
        (keep
         (fn [event]
           (when (and (= :invoke (:phase event))
                      (= :durable/release (:operation event)))
             [(:seq event) (:input event)]))
         events)]
    (when-not (some (fn [[renew-seq renewal]]
                      (some (fn [[release-seq release]]
                              (and (< renew-seq release-seq)
                                   (= (:durable-object renewal)
                                      (:durable-object release))
                                   (= (:writer renewal) (:writer release))))
                            releases))
                    renewals)
      (throw (ex-info "oscope Durable history did not renew before release"
                      {:phases (mapv (juxt :operation :phase) events)})))))

(defn assert-ingest-history!
  "Validate one woven oscope lifecycle without retaining private Durable data.

  The startup checkpoint must publish and commit before the parameterized OTLP
  requests publish and commit one WAL apiece. Returns the checked commands."
  [{:keys [journal events context-id private-values expected-wal-count
           require-renewal?]
    :or {expected-wal-count 3 require-renewal? false}}]
  (let [commands (durable-model/commands events)
        command-set (set (map :command commands))
        printed (pr-str events)]
    (when-not (every? command-set
                      (cond-> [:acquire :checkpoint-publish :publish
                               :commit-attempt :release-attempt]
                        require-renewal? (conj :renew-attempt)))
      (throw (ex-info "oscope Durable history omitted a control boundary"
                      {:commands (mapv :command commands)})))
    (assert-publication-order! commands expected-wal-count)
    (when require-renewal?
      (assert-renewal-before-release! events))
    (when-not (every? #(= context-id (:context-id %))
                      (filter #(= :invoke (:phase %)) events))
      (throw (ex-info "oscope Durable history lost its test context"
                      {:context-id context-id})))
    (doseq [private-value private-values]
      (when (.contains printed private-value)
        (throw (ex-info "oscope Durable history retained private data"
                        {:secret-class :durable-private-data}))))
    (history/assert-complete! journal)
    commands))
