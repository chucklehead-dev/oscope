(ns oscope.durable-fault-server-main
  "Woven subprocess entry point for the Durable HTTP acknowledgement boundary."
  (:require [clojure.data.json :as json]
            [jdbc.chdb.durable.control :as control]
            [jolt.aspect-packs.chdb-durable.faults :as faults]
            [oscope.durable-server-main :as durable-main]
            [oscope.server-main :as server-main])
  (:import [java.nio.file Files Paths StandardOpenOption]))

(def ^:dynamic *fault-witness!* nil)

(defn fault-witness [failure]
  (let [action faults/*action*]
    (when (and (identical? failure (:error action))
               (= :throw (:effect action))
               (= :durable/commit-reference (:operation action))
               (= 1 (:hit action))
               (= 1 (get @(:counts action) :durable/commit-reference)))
      ;; Stage names the production call topology established by the pinned
      ;; exporter: its complete-batch! barrier owns this first request WAL.
      ;; The observed witness itself proves exception identity and hit only.
      {"version" 1 "kind" "wal" "hit" 1
       "phase" (name (:phase action))
       "stage" "exporter-publication"})))

(defn publish-witness! [path witness]
  ;; CREATE_NEW makes a second witness a failure, not an overwrite. The
  ;; payload is closed fixture metadata, never the injected exception.
  (Files/write (Paths/get path (into-array String []))
               (.getBytes (json/write-str witness) "UTF-8")
               (into-array java.nio.file.OpenOption
                           [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE])))

(defn around-request-wal [join-point args proceed]
  ;; Startup can contain any number of checkpoint commits. Only the first
  ;; nonempty WAL commit in this single-request fixture consumes the fault.
  ;; Observe one closed discriminator, never SQL, keys or reference payloads.
  (if (= :durable/commit-reference (:id join-point))
    (case (:kind (get args 2))
      :checkpoint (proceed)
      :wal (try
             (faults/around-control join-point args proceed)
             (catch Throwable failure
               (when-let [witness (fault-witness failure)]
                 (when *fault-witness!*
                   (try (*fault-witness!* witness)
                        (catch Throwable _
                          ;; Keep the original injected failure. The harness
                          ;; separately rejects this closed failure marker,
                          ;; even if an IO failure left a complete file.
                          (println "FAIL: request WAL fault witness persistence failed")))))
               (throw failure)))
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
  (let [path (System/getenv "OSCOPE_DURABLE_FAULT_WITNESS")]
    (when-not (and (string? path) (not (empty? path)))
      (throw (ex-info "fault witness file required" {:oscope.durable-fault/error true})))
    (binding [*fault-witness!* #(publish-witness! path %)]
      (faults/call-with-fault
       (fault-action (System/getenv "OSCOPE_DURABLE_FAULT_PHASE"))
       #(server-main/run! (durable-main/env-options))))))
