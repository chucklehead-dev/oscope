(ns oscope.config-cli
  "Small side-effect boundary around the pure oscope.config model."
  (:require [clojure.string :as str]
            [oscope.config :as config]
            [oscope.typed-schema-config :as typed-schema-config]
            [oscope.typed-schema-runtime :as typed-schema-runtime]))

(def environment-names
  ["OSCOPE_CONFIG" "OSCOPE_HOST" "OSCOPE_PORT" "OSCOPE_CHDB_SPEC"
   "OSCOPE_HTTP_WORKERS" "OSCOPE_HTTP_QUEUE_CAPACITY"
   "XDG_CONFIG_HOME" "APPDATA" "HOME"])

(defn system-environment []
  (into {} (map (fn [name] [name (System/getenv name)])) environment-names))

(defn system-properties []
  {"os.name" (System/getProperty "os.name")
   "user.home" (System/getProperty "user.home")})

(defn config-file? [path]
  (try
    (.isFile (java.io.File. path))
    (catch Throwable _ false)))

(defn parse-args [arguments]
  (loop [remaining arguments result {:check-config? false :layer {}}]
    (if-let [argument (first remaining)]
      (case argument
        "--check-config" (recur (next remaining)
                                (assoc result :check-config? true))
        ("--config" "--host" "--port" "--db-spec")
        (let [value (second remaining)]
          (when (nil? value)
            (throw (ex-info (str argument " requires a value")
                            {:oscope.config/error true :argument argument})))
          (when (contains? result (keyword (subs argument 2)))
            (throw (ex-info (str argument " may be supplied only once")
                            {:oscope.config/error true :argument argument})))
          (let [key (keyword (subs argument 2))
                result (assoc result key value)
                result (case argument
                         "--host" (assoc-in result [:layer :server :host] value)
                         "--port" (assoc-in result [:layer :server :port]
                                             (or (parse-long value)
                                                 (throw (ex-info
                                                         "--port must be an integer"
                                                         {:oscope.config/error true
                                                          :argument argument}))))
                         "--db-spec" (assoc-in result [:layer :storage]
                                               (config/db-spec->storage value))
                         result)]
            (recur (nnext remaining) result)))
        (throw (ex-info "unknown oscope argument"
                        {:oscope.config/error true})))
      result)))

(defn- nonblank [value]
  (when-not (str/blank? value) value))

(defn- append-path [root separator suffix]
  (str root
       (when-not (if (= separator "\\")
                   (or (.endsWith ^String root "/")
                       (.endsWith ^String root "\\"))
                   (.endsWith ^String root "/"))
         separator)
       suffix))

(defn- unix-absolute? [path]
  (and path (.startsWith ^String path "/")))

(defn- windows-absolute? [path]
  (and path
       (or (.startsWith ^String path "\\\\")
           (boolean (re-find #"(?i)^[a-z]:[\\\\/]" path)))))

(defn default-config-path
  "Return the platform user-config candidate, without checking the filesystem.

  XDG_CONFIG_HOME wins on Unix-like systems when it is absolute. Relative XDG
  roots are ignored so discovery can never become an implicit cwd lookup."
  [environment properties]
  (let [os-name (str/lower-case (or (get properties "os.name") ""))
        environment-home (nonblank (get environment "HOME"))
        property-home (nonblank (get properties "user.home"))
        unix-home (or (when (unix-absolute? environment-home) environment-home)
                      (when (unix-absolute? property-home) property-home))
        windows-home
        (or (when (windows-absolute? environment-home) environment-home)
            (when (windows-absolute? property-home) property-home))
        xdg-root (nonblank (get environment "XDG_CONFIG_HOME"))
        absolute-xdg (when (and xdg-root
                                (.isAbsolute (java.io.File. xdg-root)))
                       xdg-root)]
    (cond
      (str/includes? os-name "windows")
      (when-let [root (or (let [appdata (nonblank (get environment "APPDATA"))]
                          (when (windows-absolute? appdata) appdata))
                          (when windows-home
                            (append-path windows-home "\\" "AppData\\Roaming")))]
        (append-path root "\\" "oscope\\config.edn"))

      absolute-xdg
      (append-path absolute-xdg "/" "oscope/config.edn")

      (or (str/includes? os-name "mac")
          (str/includes? os-name "darwin"))
      (when unix-home
        (append-path unix-home "/" "Library/Application Support/oscope/config.edn"))

      :else
      (when unix-home
        (append-path unix-home "/" ".config/oscope/config.edn")))))

(defn config-path
  "Choose --config, then OSCOPE_CONFIG, then a present user-default file.

  Discovery never considers the working directory. The four-argument form is
  the pure boundary used by causal tests."
  ([parsed environment]
   (config-path parsed environment (system-properties) config-file?))
  ([parsed environment properties present?]
   (or (:config parsed)
       (nonblank (get environment "OSCOPE_CONFIG"))
       (when-let [candidate (default-config-path environment properties)]
         (when (present? candidate) candidate)))))

(defn- load-config* [arguments environment environment-layer
                     read-text read-manifest-bytes]
  (let [parsed (parse-args arguments)
         path (config-path parsed environment)
         file-layer (if path
                      (try
                        (config/file-document (config/parse (read-text path)))
                        (catch Throwable error
                          (if (:oscope.config/error (ex-data error))
                            (throw error)
                            (throw (ex-info "could not read configuration file"
                                            {:oscope.config/error true})))))
                      {})
         resolved (config/resolve-config [[:file file-layer]
                                          [:environment environment-layer]
                                          [:cli (:layer parsed)]])
         typed-handoff
         (typed-schema-config/load-handoff
          (get-in resolved [:config :typed-attributes])
          read-manifest-bytes)]
    (cond-> (assoc resolved :check-config? (:check-config? parsed))
      ;; A check proves the manifest but deliberately drops the manifest and
      ;; selector capability before returning to the diagnostic renderer.
      (not (:check-config? parsed))
      (assoc :typed-attributes-handoff typed-handoff))))

(defn load-config
  "Resolve startup config and validate any typed manifest before startup.

  Readers are injected for causal tests. The typed reader receives the absolute
  manifest path and a hard byte limit."
  ([arguments environment]
   (load-config arguments environment slurp
                typed-schema-config/read-bounded-file!))
  ([arguments environment read-text]
   (load-config arguments environment read-text
                typed-schema-config/read-bounded-file!))
  ([arguments environment read-text read-manifest-bytes]
   (load-config* arguments environment (config/environment-layer environment)
                 read-text read-manifest-bytes)))

(defn load-config-with-environment-layer
  "Resolve configuration with a caller-supplied, already sanitized env layer.

  This is used by ownership-specific launchers whose legacy environment aliases
  must become config data without retaining credential values."
  ([arguments environment environment-layer]
   (load-config-with-environment-layer
    arguments environment environment-layer slurp
    typed-schema-config/read-bounded-file!))
  ([arguments environment environment-layer read-text read-manifest-bytes]
   (load-config* arguments environment environment-layer
                 read-text read-manifest-bytes)))

(defn typed-attributes-handoff
  "Return the validated plan consumed by the storage-owned runtime adapter."
  [resolved]
  (:typed-attributes-handoff resolved))

(defn server-options
  ([resolved]
   (server-options resolved typed-schema-runtime/standalone-typed-schema))
  ([resolved typed-schema-fn]
   (let [document (:config resolved)
         storage (:storage document)
         handoff (or (typed-attributes-handoff resolved) {:mode :disabled})
         typed-schema (typed-schema-fn storage handoff)]
     (cond->
      {:host (get-in document [:server :host])
       :port (get-in document [:server :port])
       :http-workers (get-in document [:server :http-workers])
       :http-queue-capacity (get-in document [:server :http-queue-capacity])
       :db-spec (config/storage->db-spec storage)}
       typed-schema (assoc :typed-schema typed-schema)))))

(defn check-output
  "Return deterministic, redacted diagnostic EDN for `--check-config`."
  [resolved]
  (config/encode-diagnostic resolved))
