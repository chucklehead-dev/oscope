(ns oscope.typed-schema
  "Explicit operator-authorized typed-schema startup boundary."
  (:require [jdbc.core :as jdbc]
            [malli.core :as m]
            [otel.exporter.chdb.attribute-identity :as identity]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]))

(def startup-options-schema
  "Closed in-memory authorization envelope accepted at startup.

  This deliberately models capabilities as predicates instead of serializable
  configuration. File configuration resolves into this envelope later."
  [:or
   ;; Backward-compatible programmatic install envelope from the first slice.
   [:map {:closed true}
    [:approved-manifest some?]
    [:registry-backend some?]
    [:emit! {:optional true} fn?]]
   [:map {:closed true}
    [:mode [:= :install]]
    [:approved-manifest some?]
    [:registry-backend some?]
    [:emit! {:optional true} fn?]]
   [:map {:closed true}
    [:mode [:= :acquire]]
    [:selector
     [:map {:closed true}
      [:dataset-id string?]
      [:application-id string?]
      [:lineage string?]
      [:version integer?]]]
    [:registry-backend some?]
    [:emit! {:optional true} fn?]]])

(defn- fail! [message type]
  (throw (ex-info message {:oscope.typed-schema/error true :type type})))

(defn validate-options
  "Validate the closed startup envelope without performing schema effects."
  [options]
  (when options
    ;; Do not attach Malli's explanation: it contains the manifest and registry
    ;; capabilities that this boundary must never expose through diagnostics.
    (when-not (m/validate startup-options-schema options)
      (fail! "oscope typed schema requires a closed startup envelope"
             ::invalid-options)))
  options)

(defn- observe-supported-columns [connection]
  (mapv
   (fn [{:keys [signal table]}]
     {:signal signal
      :table table
      :columns
      (into {}
            (map (juxt :name :type))
            (jdbc/fetch connection (str "DESCRIBE TABLE " table)))})
   (sort-by (juxt (comp str :signal) :table)
            identity/physically-supported-targets)))

(defn- options-mode [options]
  (or (:mode options) :install))

(defn- installer-runtime [connection emit!]
  (cond->
   {:target connection
    :observe-columns #(observe-supported-columns connection)}
    emit! (assoc :emit! emit!)))

(defn- confirmed-context [kind activation]
  (let [descriptor-set (:descriptor-set activation)]
    (when-not (and (= :active (:status activation)) descriptor-set)
      (if (= :install kind)
        ;; Preserve the first slice's public failure type and wording for the
        ;; existing programmatic install envelope.
        (fail! "oscope typed schema installation was not confirmed active"
               ::installation-unconfirmed)
        (fail! "oscope typed schema acquisition was not confirmed active"
               ::acquisition-unconfirmed)))
    ;; Preserve the exact install result shape for programmatic callers.
    (if (= :install kind)
      {:descriptor-set descriptor-set :installation activation}
      {:descriptor-set descriptor-set :acquisition activation})))

(defn install!
  "Activate explicitly authorized typed descriptors for one connection.

  Install mode ensures the base schema and runs the approved additive installer.
  Acquire mode performs only registry reads and fresh physical observation; it
  never supplies a DDL effect. Oscope derives every database effect from the
  exact startup connection, so application code cannot redirect its target."
  [connection options]
  (when-let [{:keys [approved-manifest registry-backend selector emit!] :as options}
             (validate-options options)]
    (let [kind (options-mode options)
          runtime (installer-runtime connection emit!)
          activation
          (case kind
            :install
            (do
              ;; Keep the original programmatic envelope's base-schema
              ;; boundary unchanged: only exporter installer failures are
              ;; translated by this namespace.
              (schema/ensure-schema! connection)
              (try
                (installer/install-approved!
                 registry-backend approved-manifest
                 (assoc runtime :execute-ddl! #(jdbc/execute! connection %)))
                (catch Throwable _
                  ;; Exporter validation failures may carry the entire manifest
                  ;; in ex-data. Cross with a fresh, cause-free error.
                  (fail! "oscope typed schema installation failed"
                         ::installation-failed))))

            :acquire
            (try
              (installer/acquire-active!
               registry-backend selector runtime)
              (catch Throwable _
                ;; Registry failures may carry selector identities, records, or
                ;; physical column details. Do not retain their cause chain.
                (fail! "oscope typed schema acquisition failed"
                       ::acquisition-failed))))]
      (confirmed-context kind activation))))

(defn exporter-options [options schema-context]
  (if schema-context
    (assoc options
           :create-schema? false
           :typed-span-descriptors (:descriptor-set schema-context))
    options))
