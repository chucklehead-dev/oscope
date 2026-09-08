(ns oscope.durable-fault-server-main
  "Woven subprocess entry point for the Durable HTTP acknowledgement boundary."
  (:require [jdbc.chdb.durable.control :as control]
            [jolt.aspect-packs.chdb-durable.faults :as faults]
            [oscope.durable-server-main :as durable-main]
            [oscope.server-main :as server-main]))

(defn- fault-action [phase]
  (case phase
    "before"
    {:operation :durable/commit-reference
     :phase :before
     :hit 2
     :effect :throw
     :error (ex-info "injected definite commit failure"
                     {:type ::control/lease-fenced})}

    "after"
    {:operation :durable/commit-reference
     :phase :after
     :hit 2
     :effect :throw
     :error (ex-info "injected ambiguous commit outcome"
                     {:type ::control/commit-ambiguous})}

    (throw (ex-info "OSCOPE_DURABLE_FAULT_PHASE must be before or after"
                    {:oscope.durable-fault/error true}))))

(defn -main [& _]
  ;; The binding must cover connection open. The Durable writer fiber is
  ;; spawned there and conveys this binding to the later request flush.
  (faults/call-with-fault
   (fault-action (System/getenv "OSCOPE_DURABLE_FAULT_PHASE"))
   #(server-main/run! (durable-main/env-options))))
