(ns oscope.typed-schema-runtime
  "Storage-owned adapter from validated file config to typed-schema startup.

  The ordinary standalone launcher currently owns local-path chDB storage. Its
  typed registry is a persistent, database-scoped Durable ObjectBackend beside
  that database path. Its catalog contains every declared dataset targeting
  that physical database. Memory storage is deliberately unsupported: an
  ephemeral registry would make restart and read-only acquisition claims
  false."
  (:require [jdbc.chdb.durable.backend :as backend]
            [jdbc.chdb.durable.local-posix :as local-posix])
  (:import [java.io File]))

(def ^:private registry-object-id "typed-attribute-registry-v1")

(defn- fail! [message type]
  ;; Never cross this adapter with database paths, dataset identities, backend
  ;; exception data, or nested causes.
  (throw (ex-info message
                  {:oscope.typed-schema-runtime/error true :type type})))

(defn- registry-root
  "Derive the persistent registry namespace beside one canonical chDB path.

  File canonicalization resolves existing symlinks and the longest existing
  ancestor even when the database leaf has not been created yet. Thus two path
  spellings for the same configured database cannot silently fork catalogs."
  [database-path]
  (str (.getCanonicalPath (File. database-path)) ".oscope-registry"))

(defn standalone-typed-schema
  "Construct the typed-schema envelope owned by ordinary standalone storage.

  The injected arity is for causal tests. `namespace-backend-fn` receives the
  derived persistent registry root; `object-backend-fn` applies a fixed object
  scope within that database-owned namespace. All declared dataset identities
  for one physical database therefore share the exporter's collision catalog."
  ([storage handoff]
   (standalone-typed-schema storage handoff
                            local-posix/local-backend backend/object-backend))
  ([storage handoff namespace-backend-fn object-backend-fn]
   (case (:mode handoff)
     :disabled nil

     (:install :acquire)
     (do
       (when-not (= :local-path (:type storage))
         (fail! "typed attributes require persistent standalone local-path storage"
                ::unsupported-storage))
       (let [selector (:registry handoff)
             registry-backend
             (try
               (object-backend-fn
                (namespace-backend-fn (registry-root (:path storage)))
                registry-object-id)
               (catch Throwable _
                 (fail! "typed attribute registry backend could not be opened"
                        ::backend-unavailable)))]
         (case (:mode handoff)
           :install
           {:mode :install
            :approved-manifest (:approved-manifest handoff)
            :registry-backend registry-backend}

           :acquire
           {:mode :acquire
            :selector selector
            :registry-backend registry-backend})))

     (fail! "typed attribute runtime handoff is invalid" ::invalid-handoff))))
