(ns oscope.durable-history-assertions
  (:require [jolt.aspect-packs.chdb-durable.model :as durable-model]
            [jolt.aspect-packs.history :as history]))

(defn- publication-kinds [expected]
  (if (integer? expected)
    (into [:checkpoint] (repeat expected :wal))
    expected))

(defn assert-publication-order!
  "Require the expected checkpoint/WAL publication and exact-commit order."
  [commands expected]
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
      (when-not (= (publication-kinds expected) (mapv :kind pairs))
        (throw
         (ex-info "oscope Durable history has the wrong publication cadence"
                  {:publication-kinds (mapv :kind pairs)
                   :expected-publication-kinds
                   (publication-kinds expected)})))
      pairs)))

(defn select-ingest-commands!
  "Select the one Durable object with the expected publication frequencies."
  [commands expected]
  (let [expected-kinds (publication-kinds expected)
        expected-checkpoints (get (frequencies expected-kinds) :checkpoint 0)
        expected-wals (get (frequencies expected-kinds) :wal 0)
        candidates
        (filterv
         (fn [object-commands]
           (let [frequencies (frequencies (map :command object-commands))]
             (and (= expected-checkpoints
                     (get frequencies :checkpoint-publish 0))
                  (= expected-wals (get frequencies :publish 0))
                  (= (count expected-kinds)
                     (get frequencies :commit-attempt 0)))))
         (vals (group-by :durable-object commands)))]
    (when-not (= 1 (count candidates))
      (throw (ex-info "oscope Durable history did not identify one ingest lifecycle"
                      {:candidate-count (count candidates)
                       :expected-publication-kinds expected-kinds})))
    (first candidates)))

(defn assert-renewal-before-release!
  "Require a successful heartbeat renewal before the owning writer releases."
  [events durable-object writer]
  (let [invocations
        (into {} (map (juxt :operation-id identity))
              (filter #(= :invoke (:phase %)) events))
        renewals
        (keep
         (fn [event]
           (let [invoke (get invocations (:operation-id event))]
             (when (and (= :return (:phase event))
                        (= :durable/renew (:operation invoke))
                        (= durable-object
                           (get-in invoke [:input :durable-object]))
                        (= writer (get-in invoke [:input :writer]))
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
                                   (= writer (:writer release))
                                   (= (:writer renewal) (:writer release))))
                            releases))
                    renewals)
      (throw (ex-info "oscope Durable history did not renew before release"
                      {:phases (mapv (juxt :operation :phase) events)})))))

(defn assert-ingest-history!
  "Validate one woven oscope lifecycle without retaining private Durable data.

  The startup checkpoint and request persistence operations must follow the
  parameterized publication cadence. Returns the checked commands."
  [{:keys [journal events context-id private-values expected-wal-count
           expected-publication-kinds require-renewal?]
    :or {expected-wal-count 3 require-renewal? false}}]
  (let [expected (or expected-publication-kinds expected-wal-count)
        all-commands (durable-model/commands events)
        commands (select-ingest-commands! all-commands expected)
        durable-object (:durable-object (first commands))
        writer (:writer (some #(when (= :checkpoint-publish (:command %)) %)
                              commands))
        command-set (set (map :command commands))
        printed (pr-str events)]
    (when-not (every? command-set
                      (cond-> [:acquire :checkpoint-publish :publish
                               :commit-attempt :release-attempt]
                        require-renewal? (conj :renew-attempt)))
      (throw (ex-info "oscope Durable history omitted a control boundary"
                      {:commands (mapv :command commands)})))
    (assert-publication-order! commands expected)
    (when require-renewal?
      (assert-renewal-before-release! events durable-object writer))
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
