(ns oscope.managed-config-store
  "Private, CAS-protected storage for Oscope's canonical user config.

  Public results contain only closed enums and opaque revisions. Paths,
  documents, bytes, exceptions, and credential-reference names stay inside the
  store closure."
  (:require [clojure.string :as str]
            [oscope.config :as config]
            [oscope.config-cli :as config-cli])
  (:import [java.nio.file Paths]
           [java.security MessageDigest]))

(def version 1)
(def max-config-bytes (* 1024 1024))

(defn- sha256 [bytes]
  (apply str
         (map #(format "%02x" (bit-and % 0xff))
              (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(def absent-revision
  (str "sha256:"
       (sha256 (.getBytes "oscope.managed-config-store/absent/v1" "UTF-8"))))

(defn- present-revision [bytes]
  (str "sha256:" (sha256 bytes)))

(defn- revision? [value]
  (and (string? value)
       (boolean (re-matches #"sha256:[0-9a-f]{64}" value))))

(defn- result
  ([status origin] {:oscope.managed-config-store/version version
                    :status status :origin origin})
  ([status origin key value] (assoc (result status origin) key value)))

(defn- error-result [origin reason]
  (result :error origin :reason reason))

(defn- prepare-document [document]
  ;; Validation and deterministic encoding happen before the store is allowed
  ;; to create a directory, lock, or temporary file.
  (let [document (config/validate document)
        bytes (.getBytes (config/encode document) "UTF-8")]
    (when (> (alength bytes) max-config-bytes)
      (throw (ex-info "managed configuration exceeds its byte bound"
                      {:stage :validation})))
    {:document document :bytes bytes}))

(defn- decode-bytes [bytes]
  (when (> (alength bytes) max-config-bytes)
    (throw (ex-info "managed configuration exceeds its byte bound" {})))
  (-> (String. bytes "UTF-8") config/parse config/file-document config/validate))

(defn- default-operations []
  ;; Do not even load POSIX foreign symbols on unsupported hosts. In
  ;; particular, a Windows namespace load must produce a fixed unavailable
  ;; result rather than fail while resolving openat/renameat.
  (let [os (str/lower-case (or (System/getProperty "os.name") ""))
        arch (str/lower-case (or (System/getProperty "os.arch") ""))]
    (if (and (str/includes? os "linux")
             (contains? #{"amd64" "x86_64"} arch))
      (try
        ((requiring-resolve 'oscope.managed-config-posix/operations)
         max-config-bytes)
        (catch Throwable _ {:supported? false}))
      {:supported? false})))

(defprotocol ManagedConfigStore
  (selection-origin [store])
  (snapshot! [store])
  (replace! [store expected-revision document]))

(defn- current-snapshot [ops target lock]
  (if-let [bytes ((:read-bytes ops) target lock)]
    (do (decode-bytes bytes)
        {:revision (present-revision bytes)})
    {:revision absent-revision}))

(defn- stage-reason [stage]
  (get {:directory :unsafe-directory
        :lock :conflict
        :read :unreadable
        :temp :temporary-file-failed
        :write :write-failed
        :force :force-failed
        :readback :readback-failed
        :move :atomic-replace-failed
        :directory-force :directory-force-failed}
       stage :store-failed))

(def ^:private operation-reasons
  #{:conflict :unsafe-target :unsafe-directory :permission-unverified
    :unreadable :temporary-file-failed :write-failed :force-failed
    :readback-failed :atomic-replace-failed :directory-force-failed})

(defn- classified-reason [error stage]
  (let [reason (:managed-reason (ex-data error))]
    (if (contains? operation-reasons reason)
      reason
      (stage-reason stage))))

(defn managed-store
  "Create a store for the current closed config selection.

  The five-argument form is the causal-test seam. `operations` is private to
  the returned closure and must never appear in results or diagnostics."
  ([arguments environment]
   (let [properties (config-cli/system-properties)]
     (managed-store arguments environment properties config-cli/config-file?
                    (default-operations))))
  ([arguments environment properties present? operations]
   (let [parsed (config-cli/parse-args arguments)
         selection (config-cli/config-selection parsed environment properties present?)
         origin (:origin selection)
         canonical (config-cli/default-config-path environment properties)
         target (when canonical
                  (try (Paths/get canonical (make-array String 0))
                       (catch Throwable _ nil)))
         writable? (contains? #{:managed-user :none} origin)]
     (reify ManagedConfigStore
       (selection-origin [_] origin)
       (snapshot! [_]
         (cond
           (not writable?) (error-result origin :explicit-selection)
           (nil? target) (error-result origin :managed-path-unavailable)
           (not (:supported? operations)) (error-result origin :unsupported-platform)
           :else
           (try
             (let [{:keys [revision]} (current-snapshot operations target nil)]
               (result :ok origin :revision revision))
             (catch Throwable error
               (error-result origin (classified-reason error :read))))))
       (replace! [_ expected-revision document]
         (let [encoded
               (try
                 (prepare-document document)
                 (catch Throwable _ {:error :invalid-document}))]
           (cond
             (:error encoded) (error-result origin (:error encoded))
             (not writable?) (error-result origin :explicit-selection)
             (nil? target) (error-result origin :managed-path-unavailable)
             (not (:supported? operations)) (error-result origin :unsupported-platform)
             (not (revision? expected-revision)) (error-result origin :invalid-revision)
             :else
             (let [stage (atom :directory)
                   lock (atom nil)
                   temp (atom nil)
                   outcome (atom nil)
                   primary? (atom false)
                   cleanup-failed? (atom false)]
               (try
                 ((:ensure-directory! operations) target)
                 (reset! stage :lock)
                 (reset! lock ((:acquire-lock! operations) target))
                 (reset! stage :read)
                 (let [current (:revision (current-snapshot operations target @lock))]
                   (when-not (= expected-revision current)
                     (throw (ex-info "stale managed configuration revision"
                                     {:managed-reason :conflict}))))
                 (reset! stage :temp)
                 (reset! temp ((:create-temp! operations) target @lock))
                 (reset! stage :write)
                 ((:write! operations) @temp (:bytes encoded))
                 (reset! stage :force)
                 ((:force-file! operations) @temp)
                 ((:verify-temp! operations) @temp)
                 (reset! stage :readback)
                 (let [readback ((:read-temp! operations) @temp)]
                   (when-not (= (seq (:bytes encoded)) (seq readback))
                     (throw (ex-info "managed configuration readback differs" {})))
                   (when-not (= (:document encoded) (decode-bytes readback))
                     (throw (ex-info "managed configuration readback is invalid" {}))))
                 (reset! stage :move)
                 ((:atomic-replace! operations) @temp target @lock)
                 (reset! temp nil)
                 ((:verify-target! operations) target @lock)
                 (reset! stage :directory-force)
                 ((:force-directory! operations) target @lock)
                 (reset! outcome
                         (result :ok origin :revision
                                 (present-revision (:bytes encoded))))
                 (catch Throwable error
                   (let [reason (classified-reason error @stage)]
                     (reset! primary? true)
                     (reset! outcome (error-result origin reason))))
                 (finally
                   (when @temp
                     (try ((:delete-temp! operations) @temp)
                          (catch Throwable _ (reset! cleanup-failed? true))))
                   (when @lock
                     (try ((:release-lock! operations) @lock)
                          (catch Throwable _ (reset! cleanup-failed? true))))))
               (if (and @cleanup-failed? (not @primary?))
                 (error-result origin :cleanup-failed)
                 @outcome)))))))))
