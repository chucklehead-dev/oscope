(ns oscope.durable-composition-assertions
  "Pure application-to-publication correspondence; not a Durable CAS model.")

(defn publication-projection
  "Derive publications from startup owners and logical requests, never from
  the publication transcript. Each fixture request carries nonempty input."
  [{:keys [startup-owners logical-batches checkpoint-every]}]
  (into (vec (repeat (count startup-owners) :checkpoint))
        (mapcat (fn [[index _]]
                  (cond-> [:wal]
                    (zero? (mod (inc index) checkpoint-every))
                    (conj :checkpoint)))
                (map-indexed vector logical-batches))))

(defn- reject! [obligation]
  (throw (ex-info "Durable composition correspondence failed"
                  {:obligation obligation})))

(def ^:private event-fields
  {:exporter-start #{:event :batch}
   :schema #{:event :batch}
   :ingress #{:event :batch}
   :request #{:event :batch}
   :insert #{:event :batch :table}
   :barrier-start #{:event :batch :owner :kind}
   :barrier-end #{:event :batch :owner :kind}
   :publication #{:event :batch :owner :kind}
   :response #{:event :batch :outcome}})

(defn- assert-envelope! [events]
  (doseq [{:keys [event batch owner kind table outcome] :as observation} events]
    (when-not (and (map? observation)
                   (= (get event-fields event) (set (keys observation)))
                   (contains? #{:startup :traces :logs :metrics} batch)
                   (or (not (contains? observation :owner))
                       (contains? #{:exporter :server} owner))
                   (or (not (contains? observation :table))
                       (contains? #{:traces :logs :gauge :sum :histogram} table))
                   (or (not (contains? observation :outcome))
                       (contains? #{:success :failure} outcome))
                   (case event
                     (:barrier-start :barrier-end) (contains? #{:checkpoint :flush} kind)
                     :publication (contains? #{:checkpoint :wal} kind)
                     (:exporter-start :schema :ingress) (= :startup batch)
                     true))
      (reject! :event-envelope)))
  (let [unfinished
        (reduce (fn [stack {:keys [event batch owner kind]}]
                  (case event
                    :barrier-start (conj stack [batch owner kind])
                    :barrier-end (if (= (peek stack) [batch owner kind])
                                   (pop stack) (reject! :boundary-completeness))
                    stack))
                [] events)]
    (when (seq unfinished) (reject! :boundary-completeness))))

(defn assert-correspondence!
  "Closed categorical observations qualify this finite nonempty fixture.
  Native/protocol correctness and process ownership are separate gates."
  [events {:keys [startup-owners logical-batches checkpoint-every] :as contract}]
  (assert-envelope! events)
  (let [indexed (mapv vector (range) events)
        select (fn [event batch]
                 (filterv #(and (= event (get-in % [1 :event]))
                                (= batch (get-in % [1 :batch]))) indexed))
        ingresses (select :ingress :startup)
        ingress (first ingresses)
        startup (select :barrier-end :startup)]
    (when-not (and (= 1 (count ingresses))
                   (= 1 (count (select :exporter-start :startup)))
                   (= (frequencies startup-owners)
                      (frequencies (map #(get-in % [1 :owner]) startup)))
                   (every? #(and (< (first %) (first ingress))
                                 (= :checkpoint (get-in % [1 :kind]))) startup))
      (reject! :startup-barriers))
    (when-not (= logical-batches
                 (mapv #(get-in % [1 :batch])
                       (filterv #(= :request (get-in % [1 :event])) indexed)))
      (reject! :logical-input-coverage))
    (doseq [[ordinal batch] (map-indexed vector logical-batches)]
      (let [inserts (select :insert batch)
            exporter (filterv #(= :exporter (get-in % [1 :owner]))
                              (select :barrier-end batch))
            server (filterv #(= :server (get-in % [1 :owner]))
                            (select :barrier-end batch))
            responses (select :response batch)
            expected-tables (case batch :traces [:traces] :logs [:logs]
                                  :metrics [:gauge :sum :histogram])]
        (when-not (and (= (frequencies expected-tables)
                         (frequencies (map #(get-in % [1 :table]) inserts)))
                       (< (ffirst (select :request batch)) (ffirst inserts))
                       (= 1 (count exporter)) (= 1 (count server))
                       (< (first (last inserts)) (ffirst exporter))
                       (< (ffirst exporter) (ffirst server)))
          (reject! :logical-batch-boundaries))
        (when-not (and (= 1 (count responses))
                       (= :success (get-in responses [0 1 :outcome]))
                       (< (ffirst server) (ffirst responses)))
          (reject! :ack-before-boundary))
        (when-not (= (if (zero? (mod (inc ordinal) checkpoint-every))
                      :checkpoint :flush)
                     (get-in server [0 1 :kind]))
          (reject! :server-cadence))))
    (let [expected (publication-projection contract)
          actual (mapv :kind (filterv #(= :publication (:event %)) events))]
      (when-not (= expected actual) (reject! :publication-cadence))
      expected)))
