(ns oscope.typed-schema-config
  "Bounded file boundary for operator-approved typed attribute configuration.

  This namespace does not construct registry storage, connect to chDB, or run
  schema effects. It produces the smallest handoff the storage-owned
  startup boundary needs."
  (:require [oscope.config :as config]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.exporter.chdb.attribute-registry :as registry])
  (:import [java.io FileInputStream]
           [java.security MessageDigest]))

(def max-manifest-bytes
  "Maximum compiled manifest file size accepted at startup."
  (* 8 1024 1024))

(defn- fail! [message type]
  ;; Keep this envelope deliberately free of paths, manifests, deployment
  ;; selectors, validation explanations, and nested causes.
  (throw (ex-info message
                  {:oscope.typed-schema-config/error true :type type})))

(defn- fail-read! [error]
  ;; Reconstruct even our own classifications. An injected reader must not be
  ;; able to forge the namespace marker and smuggle its message, ex-data, or
  ;; cause through this boundary.
  (if (= ::manifest-too-large (:type (ex-data error)))
    (fail! "typed attribute manifest exceeds its byte bound"
           ::manifest-too-large)
    (fail! "typed attribute manifest could not be read"
           ::manifest-unreadable)))

(defn read-bounded-file!
  "Accept at most `maximum` bytes from `path` using a fixed maximum+1 buffer.

  The caller validates that the path is absolute. Errors from the filesystem
  are replaced at the public boundary so they cannot echo that path."
  [path maximum]
  (when-not (and (integer? maximum) (pos? maximum))
    (fail! "typed attribute manifest read bound is invalid" ::invalid-bound))
  (let [capacity (inc maximum)
        bytes (byte-array capacity)]
    (try
      (with-open [input (FileInputStream. path)]
        (loop [offset 0]
          (if (= offset capacity)
            (fail! "typed attribute manifest exceeds its byte bound"
                   ::manifest-too-large)
            (let [read-count (.read input bytes offset (- capacity offset))]
              (if (= -1 read-count)
                (let [result (byte-array offset)]
                  (System/arraycopy bytes 0 result 0 offset)
                  result)
                (recur (+ offset read-count)))))))
      (catch Throwable error
        (fail-read! error)))))

(defn sha256-bytes
  "Return the lowercase SHA-256 digest of one exact byte array."
  [bytes]
  (when-not (bytes? bytes)
    (fail! "typed attribute manifest reader returned a non-byte value"
           ::invalid-reader-result))
  (apply str
         (map #(format "%02x" (bit-and % 0xff))
              (.digest (MessageDigest/getInstance "SHA-256") bytes))))

(defn- manifest-selector [approved]
  (select-keys approved [:dataset-id :application-id :lineage :version]))

(defn- load-install-handoff [typed-attributes read-bytes]
  (let [{:keys [path sha256]} (:manifest typed-attributes)
        selector (:registry typed-attributes)
        bytes
        (try
          (read-bytes path max-manifest-bytes)
          (catch Throwable error
            (fail-read! error)))]
    ;; Digest the exact bytes before constructing text or invoking EDN. A
    ;; mismatched artifact can therefore never influence parsing or validation.
    (when-not (= sha256 (sha256-bytes bytes))
      (fail! "typed attribute manifest digest does not match configuration"
             ::manifest-digest-mismatch))
    (let [approved
          (try
            (-> (String. bytes "UTF-8")
                config/parse
                manifest/validate-manifest)
            (catch Throwable _
              ;; Exporter validation may attach the complete manifest or its
              ;; fields. Do not retain that cause or its ex-data.
              (fail! "typed attribute manifest is not an approved compiled manifest"
                     ::invalid-manifest)))]
      ;; `prepare` is the exporter's pure approval/target gate. It rejects
      ;; manifests this registry slice cannot install, without storage effects.
      (try
        (registry/prepare approved [])
        (catch Throwable _
          (fail! "typed attribute manifest is not supported by the registry"
                 ::unsupported-manifest)))
      (when-not (= selector (manifest-selector approved))
        (fail! "typed attribute manifest does not match the registry selector"
               ::selector-mismatch))
      ;; Raw bytes, text, and path do not cross this boundary. The approved
      ;; value and exact selector are capabilities needed by the runtime
      ;; adapter and must never be printed by diagnostic rendering.
      {:mode :install
       :approved-manifest approved
       :registry selector})))

(defn load-handoff
  "Validate typed attribute config and return a side-effect-free runtime plan.

  `read-bytes` receives `[absolute-path maximum-bytes]`. Install mode verifies
  bytes, parses plain EDN, invokes exporter validation, and proves selector
  equality. Acquire mode carries only the already-validated closed selector.
  Registry backend construction remains with the storage-owning lifecycle."
  ([typed-attributes]
   (load-handoff typed-attributes read-bounded-file!))
  ([typed-attributes read-bytes]
   (let [typed-attributes (config/validate-typed-attributes typed-attributes)]
     (case (:mode typed-attributes)
       :disabled {:mode :disabled}
       :install (load-install-handoff typed-attributes read-bytes)
       :acquire {:mode :acquire :registry (:registry typed-attributes)}))))
