(ns oscope.durable-fault-server-main
  "Woven subprocess entry point for the Durable HTTP acknowledgement boundary."
  (:require [jdbc.chdb.durable.control :as control]
            [jolt.aspect-packs.chdb-durable.faults :as faults]
            [oscope.durable-server-main :as durable-main]
            [oscope.server-main :as server-main]))

(defn around-request-wal [join-point args proceed]
  ;; Startup can contain any number of checkpoint commits. Only the first
  ;; nonempty WAL commit in this single-request fixture consumes the fault.
  ;; Observe one closed discriminator, never SQL, keys or reference payloads.
  (if (= :durable/commit-reference (:id join-point))
    (case (:kind (get args 2))
      :checkpoint (proceed)
      :wal (faults/around-control join-point args proceed)
      (throw (ex-info "fault fixture requires a known reference kind"
                      {:oscope.durable-fault/error true})))
    (proceed)))

(def aspect-provider
  (assoc faults/aspect-provider :roles
         {:durable/control {:fn 'oscope.durable-fault-server-main/around-request-wal
                            :contract :control-v1}}))

(defn fault-action [phase]
  (case phase
    "before"
    {:operation :durable/commit-reference
     :phase :before
     :hit 1
     :effect :throw
     :error (ex-info "injected definite commit failure"
                     {:type ::control/lease-fenced})}

    "after"
    {:operation :durable/commit-reference
     :phase :after
     :hit 1
     :effect :throw
     :error (ex-info "injected ambiguous commit outcome"
                     {:type ::control/commit-ambiguous})}

    (throw (ex-info "OSCOPE_DURABLE_FAULT_PHASE must be before or after"
                    {:oscope.durable-fault/error true}))))

(defn -main [& _]
  ;; The binding must cover connection open. The Durable writer fiber is
  ;; spawned there and conveys the SAME action/counter to the request flush.
  ;; The local provider excludes checkpoint commits from that counter.
  (faults/call-with-fault
   (fault-action (System/getenv "OSCOPE_DURABLE_FAULT_PHASE"))
   #(server-main/run! (durable-main/env-options))))
