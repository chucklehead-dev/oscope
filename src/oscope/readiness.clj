(ns oscope.readiness
  "Opt-in listener discovery, never a telemetry or Durable freshness report."
  (:require [clojure.string :as str]))

(def ^:private option-keys #{:publish! :file :instance-id})
(def ^:private max-record-bytes 4096)

(defn- failure [reason]
  (ex-info "oscope readiness operation failed"
           {:oscope.readiness/error true :reason reason}))

(defn- valid-instance? [value]
  (and (string? value) (boolean (re-matches #"[A-Za-z0-9_-]{1,128}" value))))

(defn- validate! [options]
  (when-not (and (map? options)
                 (every? option-keys (keys options))
                 (or (fn? (:publish! options)) (string? (:file options)))
                 (or (not (contains? options :publish!)) (fn? (:publish! options)))
                 (or (not (contains? options :file))
                     (and (string? (:file options))
                          (not (str/blank? (:file options)))
                          (not (str/includes? (:file options) "\u0000"))
                          (valid-instance? (:instance-id options))))
                 (or (not (contains? options :instance-id))
                     (valid-instance? (:instance-id options))))
    (throw (failure :invalid-options))))

(defn file-operations
  "Load the already-qualified Linux x86-64 private POSIX edge only on opt-in."
  []
  (require 'oscope.managed-config-posix)
  ((resolve 'oscope.managed-config-posix/operations)
   max-record-bytes {:nonblocking-lock? true}))

(defn- write-record! [{:keys [operations target lock]} record]
  (let [bytes (.getBytes (str (pr-str record) "\n") "UTF-8")]
    (when (> (alength bytes) max-record-bytes)
      (throw (failure :record-too-large)))
    (let [temp ((:create-temp! operations) target lock)]
      (try
        ((:write! operations) temp bytes)
        ((:force-file! operations) temp)
        ((:verify-temp! operations) temp)
        ((:atomic-replace! operations) temp target lock)
        ((:verify-target! operations) target lock)
        ((:force-directory! operations) target lock)
        (finally
          ((:delete-temp! operations) temp))))))

(defn- publish! [handle record]
  ;; File first: a callback can immediately read the same generation's record.
  (when-let [file (:file handle)] (write-record! file record))
  (when-let [publish (:publish! handle)] (publish record)))

(defn- release! [handle]
  (when-let [{:keys [operations lock]} (:file handle)]
    ((:release-lock! operations) lock)))

(declare fail-startup!)

(defn prepare!
  "Claim a private readiness directory before listener/application acquisition.

  File callers supply a fresh launch-unique :instance-id and accept only that
  identity's :ready record. The file must live in a dedicated private directory;
  its lifetime nonblocking lock rejects another live owner without waiting."
  [options storage-mode]
  (when (some? options)
    (validate! options)
    (let [file (when-let [path (:file options)]
                 (let [operations (try (file-operations)
                                       (catch Throwable _
                                         (throw (failure :file-claim-failed))))
                       target (.toAbsolutePath (.toPath (java.io.File. path)))]
                   (when-not (:supported? operations)
                     (throw (failure :unsupported-file-host)))
                   (try
                     ((:ensure-directory! operations) target)
                     {:operations operations :target target
                      :lock ((:acquire-lock! operations) target)}
                     (catch Throwable _ (throw (failure :file-claim-failed))))))
          record {:oscope.readiness/version 1
                  :instance-id (or (:instance-id options) (str (random-uuid)))
                  :status :starting :storage-mode storage-mode}
          handle {:file file :publish! (:publish! options)
                  :record (atom record) :closed? (atom false)
                  :terminal-result (atom {:status :terminal}) :lock (Object.)}]
      (try
        (publish! handle record)
        handle
        (catch Throwable _
          (fail-startup! handle (failure :publication-failed) []))))))

(defn ready!
  "Publish only after the HTTP transport confirms reactor registration and
  the listener's exact numeric authority is installed."
  [handle host port url]
  (when handle
    (locking (:lock handle)
      (when @(:closed? handle) (throw (failure :retired-generation)))
      (when-not (and (= "127.0.0.1" host) (integer? port) (<= 1 port 65535)
                     (string? url))
        (throw (failure :invalid-bound-address)))
      (let [record (assoc @(:record handle) :status :ready
                          :host host :port port :url url)]
        (try
          (publish! handle record)
          (when @(:closed? handle) (throw (failure :retired-generation)))
          (reset! (:record handle) record)
          (catch Throwable _ (throw (failure :publication-failed))))))))

(defn terminal!
  "Best-effort terminal publication never masks an application cleanup error.
  :stopping invalidates discovery but retains the claim until actual closure."
  [handle reason]
  (if-not handle
    {:status :terminal}
    (locking (:lock handle)
      (if @(:closed? handle)
        @(:terminal-result handle)
        (let [record (assoc @(:record handle) :status :terminal :reason reason)]
          (reset! (:record handle) record)
          (try
            (publish! handle record)
            (when (not= :stopping reason)
              ;; Never release a claim while a stale :ready record may remain.
              (reset! (:closed? handle) true)
              ;; close(2) failure is ambiguous: never retry an old numeric FD.
              (try (release! handle)
                   (catch Throwable _
                     (reset! (:terminal-result handle)
                             {:status :failed :reason :claim-release-unconfirmed}))))
            @(:terminal-result handle)
            (catch Throwable _
              {:status :failed :reason :terminal-publication-failed})))))))

(defn finish-stop!
  "Retain ownership and expose a bounded retryable result on sink failure."
  [handle result]
  (if (and (= :closed (:status result))
           (= :failed (:status (terminal! handle :closed))))
    {:status :closing :phase :publishing-readiness
     :errors [{:type ::terminal-publication-failed}]}
    result))

(defn fail-startup!
  "Rethrow the original startup error only after acquired resources retire.
  An incomplete rollback publishes one opaque retry owner, without error causes,
  configuration or ownership-bearing resources in diagnostic data."
  [handle original steps]
  (let [state (atom {:done #{} :operation nil :original original})
        lock (Object.)
        retry-stop!
        (fn []
          (locking lock
            (try
              (terminal! handle :stopping)
              (doseq [[operation close!] steps]
                (when-not (contains? (:done @state) operation)
                  (swap! state assoc :operation operation)
                  (close!)
                  (swap! state update :done conj operation)))
              (swap! state assoc :operation :publish-readiness)
              (if (= :terminal (:status (terminal! handle :startup-failed)))
                {:status :closed :phase :closed}
                {:status :closing :phase :publishing-readiness
                 :errors [{:type ::terminal-publication-failed}]})
              (catch Throwable _
                {:status :closing :phase :startup-rollback
                 :errors [{:type ::cleanup-operation-failed
                           :operation (:operation @state)}]}))))
        result (retry-stop!)]
    (if (= :closed (:status result))
      (throw original)
      (throw (ex-info "oscope startup cleanup remains incomplete"
                      {:oscope.readiness/error true
                       :type ::startup-cleanup-incomplete
                       :operation (:operation @state)
                       :retry-stop! retry-stop!})))))
