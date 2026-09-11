(ns oscope.config
  "Pure, versioned oscope configuration parsing and resolution.

  This namespace deliberately performs no filesystem, database, Durable, or
  network operations. Callers supply already-read EDN text and environment
  maps, then perform any requested startup only after `resolve-config` returns."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]))

(def version 2)

(def defaults
  {:version version
   :server {:host "127.0.0.1" :port 4318
            :http-workers 2 :http-queue-capacity 8}
   :ingest {:type :otlp-http-json}
   :storage {:type :local-path :path "./oscope-data"}
   :typed-attributes {:mode :disabled}})

(def ^:private legacy-top-keys #{:version :server :ingest :storage})
(def ^:private top-keys (conj legacy-top-keys :typed-attributes))
(def ^:private server-keys
  #{:host :port :http-workers :http-queue-capacity})
(def ^:private ingest-keys #{:type})
(def ^:private durable-common-keys
  #{:owner :instance :database :scratch-parent :lease-ttl-ms
    :heartbeat-interval-ms :clock-skew-ms :force? :max-attempts
    :retry-deadline-ms :retry-initial-backoff-ms :retry-max-backoff-ms
    :checkpoint-every-batches})
(def ^:private s3-keys
  #{:endpoint :bucket :prefix :region :object-id :credentials
    :max-attempts :connect-timeout-ms :timeout-ms :retry-deadline-ms
    :retry-initial-backoff-ms :retry-max-backoff-ms})
(def ^:private credential-keys
  #{:type :access-key-env :secret-key-env :session-token-env})

(def ^:private selector-value-pattern #"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
(def ^:private sha256-pattern #"[0-9a-f]{64}")
(def ^:private max-version 9223372036854775807)

(def ^:private typed-attributes-schema
  (m/schema
   [:or
    [:map {:closed true}
     [:mode [:= :disabled]]]
    [:map {:closed true}
     [:mode [:= :install]]
     [:manifest
      [:map {:closed true}
       [:path string?]
       [:sha256 string?]]]
     [:registry
      [:map {:closed true}
       [:dataset-id string?]
       [:application-id string?]
       [:lineage string?]
       [:version integer?]]]]
    [:map {:closed true}
     [:mode [:= :acquire]]
     [:registry
      [:map {:closed true}
       [:dataset-id string?]
       [:application-id string?]
       [:lineage string?]
       [:version integer?]]]]]))

(defn- config-error [message data]
  (throw (ex-info message (assoc data :oscope.config/error true))))

(defn- closed! [path allowed value]
  (when-not (map? value)
    (config-error "configuration section must be a map" {:path path}))
  (when-let [unknown (seq (remove allowed (keys value)))]
    (config-error "configuration contains unknown keys"
                  {:path path :unknown-key-count (count unknown)}))
  value)

(defn- positive-int! [path value]
  (when-not (and (integer? value) (pos? value))
    (config-error "configuration value must be a positive integer"
                  {:path path}))
  value)

(defn- nonnegative-int! [path value]
  (when-not (and (integer? value) (not (neg? value)))
    (config-error "configuration value must be a nonnegative integer"
                  {:path path}))
  value)

(defn- nonblank! [path value]
  (when-not (and (string? value) (not (str/blank? value)))
    (config-error "configuration value must be a nonblank string"
                  {:path path}))
  value)

(defn- absolute-path! [path value]
  (nonblank! path value)
  (when-not (.isAbsolute (java.io.File. value))
    (config-error "configuration path must be absolute" {:path path}))
  value)

(defn- validate-selector! [selector]
  (doseq [key [:dataset-id :application-id :lineage]]
    (let [value (get selector key)]
      (when-not (and (string? value)
                     (boolean (re-matches selector-value-pattern value)))
        (config-error "typed attribute registry selector is invalid"
                      {:path [:typed-attributes :registry key]}))))
  (let [value (:version selector)]
    (when-not (and (integer? value) (pos? value) (<= value max-version))
      (config-error "typed attribute registry selector version is invalid"
                    {:path [:typed-attributes :registry :version]})))
  selector)

(defn validate-typed-attributes
  "Validate one closed version-2 typed-attribute section without I/O."
  [typed-attributes]
  ;; Malli owns the closed sum-of-products shape. Do not attach its explanation:
  ;; it could echo a private path or deployment selector into an exception.
  (when-not (m/validate typed-attributes-schema typed-attributes)
    (config-error "typed attributes must use a closed version-2 variant"
                  {:path [:typed-attributes]}))
  (case (:mode typed-attributes)
    :disabled nil
    :install
    (do
      (absolute-path! [:typed-attributes :manifest :path]
                      (get-in typed-attributes [:manifest :path]))
      (when-not (boolean
                 (re-matches sha256-pattern
                             (get-in typed-attributes [:manifest :sha256])))
        (config-error "typed attribute manifest digest must be lowercase SHA-256"
                      {:path [:typed-attributes :manifest :sha256]}))
      (validate-selector! (:registry typed-attributes)))
    :acquire (validate-selector! (:registry typed-attributes)))
  typed-attributes)

(defn- normalize-version [document]
  (case (:version document)
    1 (do
        (closed! [] legacy-top-keys document)
        (assoc document
               :version version
               :typed-attributes {:mode :disabled}))
    2 document
    (config-error "unsupported configuration version" {:path [:version]})))

(defn- optional-positive! [storage key]
  (when (contains? storage key)
    (positive-int! [:storage key] (get storage key))))

(defn- validate-durable-common! [storage]
  (doseq [key [:owner :instance :database :scratch-parent]]
    (when (contains? storage key)
      (nonblank! [:storage key] (get storage key))))
  (when (and (:database storage)
             (not (re-matches #"[A-Za-z_][A-Za-z0-9_]{0,254}"
                              (:database storage))))
    (config-error "Durable database name is invalid"
                  {:path [:storage :database]}))
  (doseq [key [:lease-ttl-ms :heartbeat-interval-ms :max-attempts
               :retry-deadline-ms :retry-initial-backoff-ms
               :retry-max-backoff-ms :checkpoint-every-batches]]
    (optional-positive! storage key))
  (when (contains? storage :clock-skew-ms)
    (nonnegative-int! [:storage :clock-skew-ms] (:clock-skew-ms storage)))
  (when (and (contains? storage :force?) (not (boolean? (:force? storage))))
    (config-error "configuration value must be boolean"
                  {:path [:storage :force?]}))
  (when (and (:heartbeat-interval-ms storage) (:lease-ttl-ms storage)
             (> (:heartbeat-interval-ms storage)
                (quot (:lease-ttl-ms storage) 3)))
    (config-error "Durable heartbeat exceeds one third of the lease TTL"
                  {:path [:storage :heartbeat-interval-ms]}))
  (when (and (:retry-initial-backoff-ms storage)
             (:retry-max-backoff-ms storage)
             (> (:retry-initial-backoff-ms storage)
                (:retry-max-backoff-ms storage)))
    (config-error "Durable retry initial backoff exceeds its maximum"
                  {:path [:storage :retry-initial-backoff-ms]})))

(defn- validate-credentials! [credentials]
  (closed! [:storage :s3 :credentials] credential-keys credentials)
  (when-not (= :environment (:type credentials))
    (config-error "Durable S3 credentials must be environment references"
                  {:path [:storage :s3 :credentials :type]}))
  (doseq [key [:access-key-env :secret-key-env]]
    (let [value (nonblank! [:storage :s3 :credentials key]
                           (get credentials key))]
      (when-not (re-matches #"[A-Za-z_][A-Za-z0-9_]*" value)
        (config-error "credential reference must name an environment variable"
                      {:path [:storage :s3 :credentials key]}))))
  (when (contains? credentials :session-token-env)
    (let [value (nonblank! [:storage :s3 :credentials :session-token-env]
                           (:session-token-env credentials))]
      (when-not (re-matches #"[A-Za-z_][A-Za-z0-9_]*" value)
        (config-error "credential reference must name an environment variable"
                      {:path [:storage :s3 :credentials :session-token-env]})))))

(defn- validate-s3! [s3]
  (closed! [:storage :s3] s3-keys s3)
  (doseq [key [:endpoint :bucket :region :object-id]]
    (nonblank! [:storage :s3 key] (get s3 key)))
  (when (and (contains? s3 :prefix) (not (string? (:prefix s3))))
    (config-error "configuration value must be a string"
                  {:path [:storage :s3 :prefix]}))
  (validate-credentials! (:credentials s3))
  (doseq [key [:max-attempts :connect-timeout-ms :timeout-ms
               :retry-deadline-ms :retry-initial-backoff-ms
               :retry-max-backoff-ms]]
    (when (contains? s3 key)
      (positive-int! [:storage :s3 key] (get s3 key))))
  (when (and (:max-attempts s3) (> (:max-attempts s3) 8))
    (config-error "Durable S3 maximum attempts must not exceed eight"
                  {:path [:storage :s3 :max-attempts]}))
  (when (and (:retry-initial-backoff-ms s3)
             (:retry-max-backoff-ms s3)
             (> (:retry-initial-backoff-ms s3)
                (:retry-max-backoff-ms s3)))
    (config-error "Durable S3 retry initial backoff exceeds its maximum"
                  {:path [:storage :s3 :retry-initial-backoff-ms]})))

(defn- validate-storage! [storage]
  (let [kind (:type storage)]
    (case kind
      :memory
      (closed! [:storage] #{:type} storage)

      :local-path
      (do (closed! [:storage] #{:type :path} storage)
          (nonblank! [:storage :path] (:path storage)))

      :durable-local
      (do (closed! [:storage]
                   (conj durable-common-keys :type :root) storage)
          (nonblank! [:storage :root] (:root storage))
          (validate-durable-common! storage))

      :durable-s3
      (do (closed! [:storage]
                   (conj durable-common-keys :type :s3) storage)
          (validate-s3! (:s3 storage))
          (validate-durable-common! storage))

      (config-error "configuration has an unknown storage type"
                    {:path [:storage :type]}))))

(defn validate
  "Validate and return one normalized version-2 config document.

  A closed version-1 document is upgraded to version 2 with typed attributes
  disabled. Version 1 cannot opt into behavior that its schema did not name."
  [document]
  (let [document (normalize-version document)]
    (closed! [] top-keys document)
    (let [server (closed! [:server] server-keys (:server document))
          ingest (closed! [:ingest] ingest-keys (:ingest document))]
      (when-not (= "127.0.0.1" (:host server))
        (config-error "server host must be 127.0.0.1"
                      {:path [:server :host]}))
      (when-not (and (integer? (:port server)) (<= 0 (:port server) 65535))
        (config-error "server port must be between 0 and 65535"
                      {:path [:server :port]}))
      (doseq [key [:http-workers :http-queue-capacity]]
        (positive-int! [:server key] (get server key)))
      (when-not (= :otlp-http-json (:type ingest))
        (config-error "unknown ingest type" {:path [:ingest :type]}))
      (validate-storage! (:storage document))
      (validate-typed-attributes (:typed-attributes document))
      document)))

(defn- plain-edn! [value]
  (cond
    (or (nil? value) (boolean? value) (number? value) (string? value)
        (keyword? value) (symbol? value)) value
    (map? value) (do (doseq [[key item] value]
                       (plain-edn! key)
                       (plain-edn! item))
                     value)
    (or (vector? value) (set? value) (sequential? value))
    (do (doseq [item value] (plain-edn! item)) value)
    :else (config-error "tagged values are not allowed" {:path []})))

(defn parse
  "Parse an EDN config string without evaluating tagged values or doing I/O."
  [text]
  (when-not (string? text)
    (config-error "configuration input must be text" {:path []}))
  (try
    (let [document (edn/read-string {:readers {} :default (fn [tag _]
                                                            (config-error
                                                             "tagged values are not allowed"
                                                             {:path []}))}
                                    text)]
      (plain-edn! document)
      (when-not (map? document)
        (config-error "configuration document must be a map" {:path []}))
      document)
    (catch Throwable error
      (if (:oscope.config/error (ex-data error))
        (throw error)
        (config-error "configuration is not valid EDN" {:path []})))))

(defn file-document
  "Require and normalize the explicit version on a parsed file document."
  [document]
  (when-not (contains? document :version)
    (config-error "configuration file must declare its version"
                  {:path [:version]}))
  (normalize-version document))

(defn- ordered [value]
  (cond
    (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                       (map (fn [[key item]] [key (ordered item)])) value)
    (vector? value) (mapv ordered value)
    (set? value) (into (sorted-set-by #(compare (pr-str %1) (pr-str %2)))
                       (map ordered) value)
    (sequential? value) (map ordered value)
    :else value))

(defn encode
  "Encode a validated config as deterministic, plain EDN."
  [document]
  (str (pr-str (ordered (validate document))) "\n"))

(defn db-spec->storage
  "Translate the compatibility db-spec into a closed storage variant."
  [db-spec]
  (cond
    (= "chdb::memory:" db-spec) {:type :memory}
    (and (string? db-spec) (str/starts-with? db-spec "chdb:")
         (not (str/blank? (subs db-spec 5))))
    {:type :local-path :path (subs db-spec 5)}
    :else (config-error "db spec must be chdb::memory: or chdb:PATH"
                        {:path [:storage]})))

(defn storage->db-spec [storage]
  (case (:type storage)
    :memory "chdb::memory:"
    :local-path (str "chdb:" (:path storage))
    (config-error "this launcher requires memory or local-path storage"
                  {:path [:storage :type]})))

(defn environment-layer
  "Translate existing ordinary-server environment aliases into config data."
  [environment]
  (cond-> {}
    (not (str/blank? (get environment "OSCOPE_HOST")))
    (assoc-in [:server :host] (get environment "OSCOPE_HOST"))

    (not (str/blank? (get environment "OSCOPE_PORT")))
    (assoc-in [:server :port]
              (or (parse-long (get environment "OSCOPE_PORT"))
                  (config-error "OSCOPE_PORT must be an integer"
                                {:path [:server :port]})))

    (not (str/blank? (get environment "OSCOPE_CHDB_SPEC")))
    (assoc :storage (db-spec->storage (get environment "OSCOPE_CHDB_SPEC")))

    (not (str/blank? (get environment "OSCOPE_HTTP_WORKERS")))
    (assoc-in [:server :http-workers]
              (or (parse-long (get environment "OSCOPE_HTTP_WORKERS"))
                  (config-error "OSCOPE_HTTP_WORKERS must be an integer"
                                {:path [:server :http-workers]})))

    (not (str/blank? (get environment "OSCOPE_HTTP_QUEUE_CAPACITY")))
    (assoc-in [:server :http-queue-capacity]
              (or (parse-long (get environment "OSCOPE_HTTP_QUEUE_CAPACITY"))
                  (config-error
                   "OSCOPE_HTTP_QUEUE_CAPACITY must be an integer"
                   {:path [:server :http-queue-capacity]})))))

(defn- leaf-paths
  ([value] (leaf-paths [] value))
  ([path value]
   (if (map? value)
     (mapcat (fn [[key child]] (leaf-paths (conj path key) child)) value)
     [path])))

(defn- merge-section [lower upper]
  (reduce-kv (fn [result key value]
               (if (and (contains? #{:server :ingest} key)
                        (map? value) (map? (get result key)))
                 (assoc result key (merge (get result key) value))
                 (assoc result key value)))
             lower upper))

(defn- beneath? [prefix path]
  (= prefix (subvec path 0 (min (count prefix) (count path)))))

(defn resolve-config
  "Merge `[source partial-document]` layers from lowest to highest priority.

  Server and ingest fields merge independently. A higher-priority storage
  or typed-attributes section replaces the whole lower variant so stale
  fields cannot survive. The result includes leaf provenance for
  status/settings UIs."
  [layers]
  (let [{:keys [config provenance]}
        (reduce
         (fn [{:keys [config provenance]} [source layer]]
           (when-not (map? layer)
             (config-error "configuration layer must be a map" {:source source}))
           (let [layer (if (contains? layer :version)
                         (normalize-version layer)
                         layer)
                 replaced (filter #(contains? layer %)
                                  [:storage :typed-attributes])
                 provenance (reduce
                             (fn [current section]
                               (into {} (remove (fn [[path _]]
                                                  (beneath? [section] path)))
                                     current))
                             provenance replaced)]
             {:config (merge-section config layer)
              :provenance (reduce #(assoc %1 %2 source)
                                  provenance (leaf-paths layer))}))
         {:config defaults
          :provenance (into {} (map (fn [path] [path :default])
                                    (leaf-paths defaults)))}
         layers)]
    {:config (validate config) :provenance provenance}))

(defn redact
  "Return a diagnostic-safe config/provenance result.

  Local paths, object-store identity, typed manifest paths, and typed deployment
  selector strings are suppressed. Credential values are impossible in the
  schema; environment variable references remain visible."
  [{:keys [config provenance] :as resolved}]
  (let [kind (get-in config [:storage :type])
        storage-paths (case kind
                        :local-path [[:storage :path]]
                        :durable-local [[:storage :root] [:storage :scratch-parent]
                                        [:storage :owner] [:storage :instance]
                                        [:storage :database]]
                        :durable-s3 [[:storage :s3 :endpoint] [:storage :s3 :bucket]
                                     [:storage :s3 :prefix] [:storage :s3 :object-id]
                                     [:storage :scratch-parent] [:storage :owner]
                                     [:storage :instance] [:storage :database]]
                        [])
        typed-mode (get-in config [:typed-attributes :mode])
        typed-paths
        (if (contains? #{:install :acquire} typed-mode)
          (cond-> [[:typed-attributes :registry :dataset-id]
                   [:typed-attributes :registry :application-id]
                   [:typed-attributes :registry :lineage]]
            (= :install typed-mode)
            (conj [:typed-attributes :manifest :path]))
          [])
        paths (into storage-paths typed-paths)]
    (assoc resolved :config
           (reduce (fn [document path]
                     (if (get-in document path)
                       (assoc-in document path "<redacted>")
                       document))
                   config paths))))

(defn encode-diagnostic
  "Encode a resolved config for diagnostics without validating placeholders.

  The real config is validated before redaction. Redaction deliberately uses
  conspicuous placeholders that need not satisfy the live schema (for example,
  a redacted Durable database name), so the diagnostic document is parseable
  EDN but is not a reusable configuration file."
  [{:keys [config] :as resolved}]
  (let [validated (assoc resolved :config (validate config))]
    (str (pr-str (ordered (:config (redact validated)))) "\n")))
