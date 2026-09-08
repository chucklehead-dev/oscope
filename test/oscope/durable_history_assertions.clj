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

(defn assert-ingest-history!
  "Validate one woven oscope lifecycle without retaining private Durable data.

  The startup checkpoint must publish and commit before the parameterized OTLP
  requests publish and commit one WAL apiece. Returns the checked commands."
  [{:keys [journal events context-id private-values expected-wal-count]
    :or {expected-wal-count 3}}]
  (let [commands (durable-model/commands events)
        command-set (set (map :command commands))
        printed (pr-str events)]
    (when-not (every? command-set
                      [:acquire :checkpoint-publish :publish
                       :commit-attempt :release-attempt])
      (throw (ex-info "oscope Durable history omitted a control boundary"
                      {:commands (mapv :command commands)})))
    (assert-publication-order! commands expected-wal-count)
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
