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
  [:map {:closed true}
   [:approved-manifest some?]
   [:registry-backend some?]
   [:emit! {:optional true} fn?]])

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

(defn install!
  "Ensure the base schema, then install one explicitly approved manifest.

  Supplying the approved manifest and registry backend is the operator
  authority. Oscope derives DDL and physical observation from the exact startup
  connection, so application code cannot redirect the effects or target."
  [connection options]
  (when-let [{:keys [approved-manifest registry-backend emit!]}
             (validate-options options)]
    (schema/ensure-schema! connection)
    (let [installation
          (try
            (installer/install-approved!
             registry-backend approved-manifest
             (cond->
              {:target connection
               :execute-ddl! #(jdbc/execute! connection %)
               :observe-columns #(observe-supported-columns connection)}
               emit! (assoc :emit! emit!)))
            (catch Throwable _
              ;; Exporter validation failures may carry the entire manifest in
              ;; ex-data. Cross this boundary with a fresh, cause-free error.
              (fail! "oscope typed schema installation failed"
                     ::installation-failed)))
          descriptor-set (:descriptor-set installation)]
      (when-not (and (= :active (:status installation)) descriptor-set)
        (fail! "oscope typed schema installation was not confirmed active"
               ::installation-unconfirmed))
      {:descriptor-set descriptor-set :installation installation})))

(defn exporter-options [options schema-context]
  (if schema-context
    (assoc options
           :create-schema? false
           :typed-span-descriptors (:descriptor-set schema-context))
    options))
