(ns oscope.typed-schema-chdb-integration-test
  (:require [clojure.test :refer [deftest is]]
            [db.jdbc]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [oscope.typed-schema :as typed-schema]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.sdk.export :as export]))

(def attribute-key "checkout.complete")

(defn- approved-manifest []
  (manifest/compile-manifest
   {:dataset-id "oscope-native"
    :application-id "typed-startup"
    :lineage "typed-startup-v1"
    :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :advice
      :source "test/typed-startup.edn"
      :entries [{:signal :spans
                 :table "otel_traces"
                 :location :span-attributes
                 :key attribute-key
                 :type :boolean}]}]}))

(defn- span []
  {:name "typed-startup-native"
   :kind :internal
   :start-time-unix-nano 1700000000000000000
   :end-time-unix-nano 1700000000000000001
   :span-context {:trace-id "10000000000000000000000000000000"
                  :span-id "1000000000000000"}
   :resource {:attributes {}}
   :scope {:name "oscope.typed-startup-test"}
   :attributes {attribute-key true}
   :events []
   :links []
   :status {:code :unset}})

(deftest approved-startup-schema-drives-real-typed-ingestion
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (let [schema-context
          (typed-schema/install!
           connection
           {:approved-manifest (approved-manifest)
            :registry-backend (backend/memory-backend)})
          installation (:installation schema-context)
          field (first (get-in installation [:record :manifest :fields]))
          value-column (get-in field [:physical :value-column])
          status-column (get-in field [:physical :status-column])
          exporter
          (chdb-export/exporter
           (typed-schema/exporter-options
            {:connection connection :signals #{:spans}}
            schema-context))]
      (try
        (is (= :active (:status installation)))
        (is (export/export-spans! exporter [(span)]))
        (is (= [{:value 1 :status 3}]
               (jdbc/fetch
                connection
                [(str "SELECT toUInt8(`" value-column "`) AS value, `"
                      status-column "` AS status FROM otel_traces "
                      "WHERE SpanName = ?")
                 "typed-startup-native"])))
        (finally
          (export/shutdown-exporter! exporter))))))
