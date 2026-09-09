(ns oscope.config-cli
  "Small side-effect boundary around the pure oscope.config model."
  (:require [oscope.config :as config]))

(def environment-names
  ["OSCOPE_CONFIG" "OSCOPE_HOST" "OSCOPE_PORT" "OSCOPE_CHDB_SPEC"
   "OSCOPE_HTTP_WORKERS" "OSCOPE_HTTP_QUEUE_CAPACITY"])

(defn system-environment []
  (into {} (map (fn [name] [name (System/getenv name)])) environment-names))

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

(defn config-path
  "Choose an explicit CLI path, then OSCOPE_CONFIG. No cwd file is implicit."
  [parsed environment]
  (or (:config parsed) (not-empty (get environment "OSCOPE_CONFIG"))))

(defn load-config
  "Resolve startup config. `read-text` is injected to keep filesystem I/O here."
  ([arguments environment]
   (load-config arguments environment slurp))
  ([arguments environment read-text]
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
         resolved (config/resolve-config
                   [[:file file-layer]
                    [:environment (config/environment-layer environment)]
                    [:cli (:layer parsed)]])]
     (assoc resolved :check-config? (:check-config? parsed)))))

(defn server-options [resolved]
  (let [document (:config resolved)]
    {:host (get-in document [:server :host])
     :port (get-in document [:server :port])
     :http-workers (get-in document [:server :http-workers])
     :http-queue-capacity (get-in document [:server :http-queue-capacity])
     :db-spec (config/storage->db-spec (:storage document))}))

(defn check-output
  "Return deterministic, redacted diagnostic EDN for `--check-config`."
  [resolved]
  (config/encode-diagnostic resolved))
