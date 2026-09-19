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
   ;; A closed application-side observation starts each real OTLP request in
   ;; the fixture. It deliberately contains no request body, endpoint, or
   ;; signal value: it is only the categorical link into the Durable trace.
   :application #{:event :batch :phase}
   :request #{:event :batch}
   :insert #{:event :batch :table}
   :barrier-start #{:event :batch :owner :kind}
   :barrier-end #{:event :batch :owner :kind}
   :publication #{:event :batch :owner :kind}
   :response #{:event :batch :outcome}
   ;; This is a pure-fixture receipt, not a native child-process claim. The
   ;; separately qualified native harness owns that evidence.
   :generation-receipt #{:event :batch :reader :settlement}})

(defn- assert-envelope! [events]
  (doseq [{:keys [event batch owner kind table outcome phase reader settlement]
           :as observation} events]
    (when-not (and (map? observation)
                   (= (get event-fields event) (set (keys observation)))
                   (contains? #{:startup :traces :logs :metrics :shutdown} batch)
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
                     :application (and (contains? #{:traces :logs :metrics} batch)
                                       (= :observed phase))
                     :generation-receipt
                     (and (= :shutdown batch)
                          (contains? #{:fresh :wrong} reader)
                          (= :settled settlement))
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
  A synthetic settled-reader receipt links the fixture's app-to-publication
  history to its generation boundary; native/process evidence remains separate."
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
      (let [applications (select :application batch)
            requests (select :request batch)
            inserts (select :insert batch)
            exporter-start (filterv #(= :exporter (get-in % [1 :owner]))
                                    (select :barrier-start batch))
            exporter (filterv #(= :exporter (get-in % [1 :owner]))
                              (select :barrier-end batch))
            server-start (filterv #(= :server (get-in % [1 :owner]))
                                  (select :barrier-start batch))
            server (filterv #(= :server (get-in % [1 :owner]))
                            (select :barrier-end batch))
            exporter-publications
            (filterv #(= :exporter (get-in % [1 :owner]))
                     (select :publication batch))
            server-publications
            (filterv #(= :server (get-in % [1 :owner]))
                     (select :publication batch))
            checkpoint? (zero? (mod (inc ordinal) checkpoint-every))
            responses (select :response batch)
            expected-tables (case batch :traces [:traces] :logs [:logs]
                                  :metrics [:gauge :sum :histogram])]
        (when-not (and (= (frequencies expected-tables)
                         (frequencies (map #(get-in % [1 :table]) inserts)))
                       (= 1 (count applications)) (= 1 (count requests))
                       (= 1 (count exporter-start)) (= 1 (count exporter))
                       ;; Exporter flush always publishes this logical request.
                       ;; The server then publishes an additional checkpoint on
                       ;; cadence; treating both as one publication was an
                       ;; impossible constraint for a scheduled checkpoint.
                       (= 1 (count exporter-publications))
                       (= :wal (get-in exporter-publications [0 1 :kind]))
                       (= 1 (count server-start)) (= 1 (count server))
                       (< (ffirst applications) (ffirst requests))
                       (< (ffirst requests) (ffirst inserts))
                       (< (first (last inserts)) (ffirst exporter-start))
                       (< (ffirst exporter-start) (ffirst exporter-publications))
                       (< (ffirst exporter-publications) (ffirst exporter))
                       (< (ffirst exporter) (ffirst server-start))
                       (< (ffirst server-start) (ffirst server)))
          (reject! :logical-batch-boundaries))
        (when-not (and (= 1 (count responses))
                       (= :success (get-in responses [0 1 :outcome]))
                       (< (ffirst server) (ffirst responses)))
          (reject! :ack-before-boundary))
        (when-not (and (= (if checkpoint? :checkpoint :flush)
                         (get-in server [0 1 :kind]))
                       (= (if checkpoint? 1 0) (count server-publications))
                       (or (not checkpoint?)
                           (and (= :checkpoint
                                   (get-in server-publications [0 1 :kind]))
                                (< (ffirst server-start)
                                   (ffirst server-publications))
                                (< (ffirst server-publications)
                                   (ffirst server)))))
          (reject! :server-cadence))))
    (let [expected (publication-projection contract)
          publications (filterv #(= :publication (get-in % [1 :event])) indexed)
          actual (mapv #(get-in % [1 :kind]) publications)
          receipts (select :generation-receipt :shutdown)
          responses (filterv #(= :response (get-in % [1 :event])) indexed)]
      (when-not (= expected actual) (reject! :publication-cadence))
      (when-not (and (= 1 (count receipts))
                     (= :fresh (get-in receipts [0 1 :reader]))
                     (= :settled (get-in receipts [0 1 :settlement]))
                     (< (first (last publications)) (ffirst receipts))
                     (< (first (last responses)) (ffirst receipts)))
        (reject! :generation-receipt))
      expected)))
