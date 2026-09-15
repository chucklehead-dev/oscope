(ns minimal-embedded-app
  (:require [db.jdbc]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]
            [jdbc.proto :as jdbc-proto]
            [oscope.embedded :as embedded]
            [oscope.embedded.query :as embedded-query]
            [otel.exporter.chdb.attribute-manifest :as manifest]
            [otel.sdk :as sdk]
            [otel.trace :as trace]))

(def ^:private application-row
  {:id 7 :state "authoritative"})

(def ^:private span-name "minimal embedded fixture")
(def ^:private typed-attribute-key "fixture.confirmed")
(def ^:private query-timeout-ms 5000)
(def ^:private query-ready-wait-ms 15000)

(defn- require! [condition message & [data]]
  (when-not condition
    (throw (ex-info message (or data {})))))

(defn- throws? [f]
  (try
    (f)
    false
    (catch Throwable _ true)))

(defn- closed? [connection]
  (.isClosed (jdbc-proto/connection connection)))

(defn- delete-tree! [root]
  (when (and root (.exists root))
    (doseq [file (reverse (file-seq root))]
      (java.nio.file.Files/deleteIfExists (.toPath file)))))

(defn- approved-manifest []
  (manifest/compile-manifest
   {:dataset-id "oscope-minimal-embedded"
    :application-id "fixture"
    :lineage "fixture-v1"
    :version 1
    :fragments
    [{:schema manifest/reviewed-fragment-schema
      :authority :runtime-reviewed
      :source "test/fixtures/minimal-embedded-app"
      :entries
      [{:signal :spans
        :table "otel_traces"
        :location :span-attributes
        :key typed-attribute-key
        :type :boolean}]}]}))

(defn- await-ready [query]
  ;; Leave a separate scheduling envelope beyond the query's own timeout.
  (let [deadline (+ (System/currentTimeMillis) query-ready-wait-ms)]
    (loop []
      (let [snapshot (embedded-query/snapshot query)]
        (cond
          (= :ready (:status snapshot)) snapshot
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 5) (recur))
          :else snapshot)))))

(defn- typed-span-rows [runtime binding]
  (get-in
   ((:load-command (:source runtime))
    :minimal-embedded-readback
    {:mode :typed-span-filter
     :schema-binding binding
     :operator :eq
     :value true
     :window :15m
     :limit 10})
   [:table :rows]))

(defn -main [& _]
  (let [directory (java.nio.file.Files/createTempDirectory
                   "oscope-minimal-embedded-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        root (java.io.File. (str directory))
        application-db (str "sqlite:" (.resolve directory "application.sqlite"))
        application* (atom nil)
        runtime* (atom nil)
        query* (atom nil)]
    (try
      (let [application (jdbc/connection application-db)]
        (reset! application* application)
        (jdbc/execute! application
                       "create table application_state (id integer primary key, state text not null)")
        (jdbc/execute! application
                       ["insert into application_state (id, state) values (?, ?)"
                        (:id application-row) (:state application-row)])
        (require! (= [application-row]
                     (jdbc/fetch application
                                 "select id, state from application_state"))
                  "SQLite did not retain authoritative application state")

        (let [telemetry-store
              (local-posix/local-backend
               (str (.resolve directory "telemetry-store")))
              registry-store
              (local-posix/local-backend
               (str (.resolve directory "schema-registry")))
              db-spec
              (durable/writer-dbspec
               {:backend telemetry-store
                :owner "oscope-minimal-embedded-fixture"
                :instance (str "oscope-minimal-embedded-fixture-"
                               (random-uuid))
                :database "default"
                :scratch-parent (str directory)
                :lease-ttl-ms 30000})
              runtime
              (embedded/start!
               {:db-spec db-spec
                :sdk-options {:service-name "oscope-minimal-embedded-fixture"
                              :processor :simple
                              :metrics? false
                              :runtime-metrics? false
                              :logs? false}
                :typed-schema
                {:mode :install
                 :approved-manifest (approved-manifest)
                 :registry-backend registry-store}})]
          (reset! runtime* runtime)
          (let [sdk-handle (:sdk-handle runtime)
                descriptor-set (:typed-span-descriptors runtime)
                binding (first (get-in runtime [:source :typed-span-fields]))]
            (require! (and sdk-handle
                           (identical? (sdk/tracer-provider)
                                       (:tracer-provider sdk-handle)))
                      "fixture did not start exactly one process SDK owner")
            (require! (and descriptor-set binding
                           (identical? descriptor-set
                                       (get-in runtime
                                               [:source :typed-span-descriptors])))
                      "confirmed span descriptors did not reach exporter/query state")
            (trace/with-span
              [_ (sdk/tracer "minimal-embedded-app") span-name
               {:attributes {typed-attribute-key true}}])
            (require! (true? (embedded/force-flush! runtime))
                      "real chDB exporter did not confirm the span flush")
            (require! (= [application-row]
                         (jdbc/fetch application
                                     "select id, state from application_state"))
                      "SQLite application state was not usable beside telemetry")

            (let [query
                  (embedded-query/start!
                   {:load! #(typed-span-rows runtime binding)
                    :interval-ms 60000
                    :timeout-ms query-timeout-ms
                    :stop-timeout-ms 5000
                    :max-rows 10})]
              (reset! query* query)
              (let [snapshot (await-ready query)
                    row (first (:rows snapshot))]
                (require! (= :ready (:status snapshot))
                          "bounded telemetry query did not become ready"
                          {:status (:status snapshot)})
                (require! (= {:span-name span-name
                              :attribute-key typed-attribute-key
                              :attribute-type :boolean
                              :attribute-value true
                              :attribute-location :span-attributes
                              :manifest-version 1}
                             (select-keys
                              row
                              [:span-name :attribute-key :attribute-type
                               :attribute-value :attribute-location
                               :manifest-version]))
                          "flushed span did not round-trip through confirmed schema"))
              (require! (= :closed (:status (embedded-query/stop! query)))
                        "bounded query helper did not retire")
              (require! (= {:phase :closed
                            :worker-stopped? true
                            :executor-shutdown? true}
                           @(:state query))
                        "bounded query helper did not close all owned work")
              (require! (= :closed (:status (embedded-query/stop! query)))
                        "bounded query helper stop was not idempotent"))

            (let [terminal (:terminal sdk-handle)
                  stopped (embedded/stop! runtime)
                  terminal-outcome @terminal]
              (require! (= {:status :closed :phase :closed} stopped)
                        "embedded Durable lifecycle did not close")
              (require! (and terminal-outcome
                             (nil? (sdk/tracer-provider))
                             (true? (:sdk-stopped? @(:state runtime)))
                             (true? (:oscope-retired? @(:state runtime)))
                             (true? (:persisted? @(:state runtime)))
                             (true? (:connection-closed? @(:state runtime)))
                             (true? @(:shutdown? (:tracer-provider sdk-handle)))
                             (closed? (:connection runtime)))
                        "SDK/source/checkpoint/connection shutdown was incomplete")
              (require! (= stopped (embedded/stop! runtime))
                        "embedded lifecycle stop was not idempotent")
              (require! (identical? terminal-outcome @terminal)
                        "SDK terminal action ran more than once")
              (require! (throws? #(jdbc/fetch (:connection runtime)
                                             "select 1 as live"))
                        "Durable connection remained usable after close"))))

        (.close application)
        (require! (and (closed? application)
                       (throws? #(jdbc/fetch application "select 1 as live")))
                  "SQLite connection remained usable after close")
        (reset! application* nil))

      (require! (nil? (find-ns 'oscope.server))
                "minimal fixture loaded the server namespace")
      (require! (nil? (find-ns 'oscope.embedded.viewer))
                "minimal fixture loaded the viewer namespace")
      (println "minimal embedded native fixture: PASS")
      (finally
        (when-let [query @query*]
          (try (embedded-query/stop! query) (catch Throwable _ nil)))
        (when-let [runtime @runtime*]
          (try (embedded/stop! runtime) (catch Throwable _ nil)))
        (when-let [application @application*]
          (try (.close application) (catch Throwable _ nil)))
        (delete-tree! root)))))
