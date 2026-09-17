(ns oscope.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.error :as error]
            [oscope.http-executor :as http-executor]
            [oscope.live :as live]
            [oscope.otlp :as otlp]
            [oscope.server :as server]
            [oscope.server-main :as server-main]
            [oscope.typed-schema :as typed-schema]
            [oscope.ui.events :as events]
            [oscope.ui.workbench :as workbench]
            [oscope.ui.web :as web]
            [otel.exporter.chdb :as chdb-export]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(deftest invalid-typed-envelope-is-rejected-before-standalone-acquisition
  (let [acquisitions (atom [])
        acquire (fn [phase]
                  (fn [& _]
                    (swap! acquisitions conj phase)
                    (throw (ex-info "unexpected standalone acquisition" {}))))]
    (with-redefs [jdbc/connection (acquire :connection)
                  http-executor/start! (acquire :http-workers)
                  chdb-export/exporter (acquire :exporter)
                  live/open! (acquire :source)
                  http/run-server (acquire :listener)
                  typed-schema/install! (acquire :schema)]
      (doseq [invalid [false true 0 {} [] "not-an-envelope" ::not-an-envelope
                       {:approved-manifest {:secret "private-envelope-marker"}
                        :registry-backend ::registry
                        :unexpected "private-envelope-marker"}]]
        (let [error (try (server/start! {:port 0 :db-spec ::database :typed-schema invalid})
                         (catch Throwable error error))]
          (is (= "oscope typed schema requires a closed startup envelope" (ex-message error)))
          (is (= {:oscope.typed-schema/error true
                  :type :oscope.typed-schema/invalid-options} (ex-data error)))
          (is (nil? (ex-cause error)))
          (is (not (str/includes? (pr-str [(ex-message error) (ex-data error)])
                                  "private-envelope-marker")))))
      (is (empty? @acquisitions)))))

(deftest storage-documentation-pins-the-current-recovery-boundary
  (let [readme (str/replace (slurp "README.md") #"\s+" " ")
        durable-sha "dbc2db22130c7e783739c79bc24691dcbba21906"
        aspect-sha "3773a67801bdcbd63c6484f95fa07a4b8afddb72"
        compiler-sha "f00bc93bdd8274b14087b74272aadeffb60e0447"
        compiler-version "jolt v0.8.6-5-gf00bc93b"
        compiler-current?
        (fn [workflow]
          (and (str/includes? workflow compiler-sha)
               (str/includes? workflow compiler-version)))
        s3-workflow (slurp ".github/workflows/durable-s3-e2e.yml")
        aws-workflow (slurp ".github/workflows/durable-aws.yml")]
    (is (= "chdb:./oscope-data" server/default-db-spec))
    (is (str/includes? readme "**local path persistence**"))
    (is (str/includes? readme
                       "It is not object-backed Durable recovery."))
    (is (zero? (count (re-seq #"Proposed, not implemented" readme))))
    (is (str/includes?
         readme
         "`jolt -M:durable-server-dev --config config/oscope-durable.example.edn`"))
    (is (str/includes?
         readme
         "Available with a qualified chDB Durable V1 native library."))
    (is (str/includes?
         readme
         "Available with the same qualified native library through the Jolt-native libcurl/SigV4 backend."))
    (is (str/includes? readme durable-sha))
    (is (str/includes? s3-workflow durable-sha))
    (is (str/includes? aws-workflow durable-sha))
    (is (str/includes? readme aspect-sha))
    (is (str/includes? s3-workflow aspect-sha))
    (is (str/includes? readme compiler-sha))
    (is (str/includes? readme compiler-version))
    (is (compiler-current? s3-workflow))
    (is (false?
         (compiler-current?
          (str/replace s3-workflow compiler-sha
                       "5d56b9e5d295fe0968df07e535c45353050611f7"))))
    (is (false?
         (compiler-current?
          (str/replace s3-workflow compiler-version
                       "jolt v0.8.3-41-g5d56b9e5"))))
    (is (str/includes?
         readme
         "docs/durable/protocol-v1.mdx"))
    (is (str/includes?
         readme
         "OSCOPE_CHDB_SPEC=chdb:/absolute/path/to/oscope-data"))
    (is (str/includes?
         readme
         "Arrow and Parquet remain bounded data exports, not"))))

(deftest route-composition-is-small-and-explicit
  (let [seen (atom [])
        h (server/handler
           {:otlp-handler (fn [r] (swap! seen conj [:otlp (:uri r)]) {:status 200})
            :oscope-handler (fn [r] (swap! seen conj [:oscope (:uri r)])
                              {:status 201})
            :visualization-editor-handler
            (fn [r] (swap! seen conj [:editor (:uri r)]) {:status 202})})
        request (fn [method uri]
                  {:request-method method :uri uri
                   :headers {"host" "127.0.0.1:4318"}})]
    (is (= 200 (:status (h (request :post "/v1/logs")))))
    (is (= 201 (:status (h (request :get "/oscope")))))
    (is (= 201 (:status (h (request :get "/oscope/export")))))
    (is (= 201 (:status (h (request :get "/oscope/refresh")))))
    (is (= 201 (:status (h (request :get "/oscope/live.js")))))
    (is (= 202 (:status (h (request :get "/oscope/edit/plotje")))))
    (is (= 202 (:status (h (request :post "/oscope/edit/hiccup/preview")))))
    (is (= 200 (:status (h (request :get "/healthz")))))
    (is (= 303 (:status (h (request :get "/")))))
    (is (= "/oscope" (get-in (h (request :get "/"))
                                [:headers "Location"])))
    (is (= 404 (:status (h (request :get "/missing")))))
    (is (= [[:otlp "/v1/logs"] [:oscope "/oscope"]
            [:oscope "/oscope/export"] [:oscope "/oscope/refresh"]
            [:oscope "/oscope/live.js"] [:editor "/oscope/edit/plotje"]
            [:editor "/oscope/edit/hiccup/preview"]]
           @seen))))

(deftest standalone-composition-routes-detailed-telemetry-surfaces
  (let [seen (atom [])
        h (server/handler
           {:otlp-handler (constantly {:status 200})
            :oscope-handler (constantly {:status 201})
            :workbench-handler
            (fn [request] (swap! seen conj [:workbench (:uri request)])
              {:status 202})
            :events-handler
            (fn [request] (swap! seen conj [:events (:uri request)])
              {:status 203})})
        request (fn [uri] {:request-method :get :uri uri
                           :headers {"host" "127.0.0.1:4318"}})]
    (is (= 202 (:status (h (request workbench/default-path)))))
    (is (= 202 (:status
                (h (request (str workbench/default-path
                                 "/traces/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))))
    (is (= 203 (:status (h (request events/default-path)))))
    (is (= 203 (:status (h (request (str events/default-path "/refresh"))))))
    (is (= workbench/default-path
           (get-in (h (request "/")) [:headers "Location"])))
    (is (= [[:workbench workbench/default-path]
            [:workbench (str workbench/default-path
                             "/traces/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
            [:events events/default-path]
            [:events (str events/default-path "/refresh")]]
           @seen))))

(deftest standalone-rejects-untrusted-authorities-before-dispatch
  (let [seen (atom [])
        h (server/handler
           {:otlp-handler (fn [request]
                            (swap! seen conj [:otlp (:uri request)])
                            {:status 200})
            :oscope-handler (fn [request]
                              (swap! seen conj [:oscope (:uri request)])
                              {:status 200})
            :visualization-editor-handler
            (fn [request]
              (swap! seen conj [:editor (:uri request)])
              {:status 200})
            :authority "127.0.0.1:4318"})]
    (doseq [[method uri] [[:post "/v1/traces"]
                          [:post "/v1/logs"]
                          [:post "/v1/metrics"]
                          [:get "/oscope"]
                          [:get "/oscope/export"]
                          [:get "/oscope/refresh"]
                          [:get "/oscope/live.js"]
                          [:get "/oscope/edit/plotje"]
                          [:post "/oscope/edit/plotje/preview"]
                          [:get "/oscope/edit/hiccup"]
                          [:get "/oscope/edit/editor.js"]
                          [:get "/healthz"]
                          [:get "/"]]]
      (let [response (h {:request-method method :uri uri
                         :headers {"Host" "attacker.example"}})]
        (is (= 421 (:status response)))
        (is (= "close" (get-in response [:headers "Connection"])))))
    (is (= 421 (:status (h {:request-method :get :uri "/healthz"
                            :headers {}}))))
    (is (empty? @seen))))

(defn- fake-exporter
  ([events]
   (fake-exporter events (fn [face] (swap! events conj face))))
  ([_events retire!]
   (reify
     export/SpanExporter
     (export-spans! [_ _] true)
     (flush-exporter! [_] true)
     (shutdown-exporter! [_] (retire! :spans) true)
     export/MetricExporter
     (export-metrics! [_ _ _] true)
     (shutdown-metric-exporter! [_] (retire! :metrics) true)
     logs/LogRecordExporter
     (export-logs! [_ _] true)
     (shutdown-log-exporter! [_] (retire! :logs) true))))

(defn- assert-public-result-safe! [result canary]
  (let [values (tree-seq coll? seq result)]
    (is (not-any? #(instance? Throwable %) values))
    (is (not-any? #(and (string? %) (str/includes? % canary)) values))
    (is (not (str/includes? (pr-str result) canary)))))

(deftest standalone-rejects-unqualified-durable-mode-before-acquisition
  (let [policy {:checkpoint! (fn [_] {:status :committed})
                :flush! (fn [_] {:status :empty})}]
    (doseq [[options expected-type]
            (concat
             (map (fn [invalid]
                    [{:db-spec {:vendor "chdb-durable"}
                      :durability invalid} :oscope.server/invalid-durability])
                  [nil false {} [] {:flush! (fn [_])}
                   {:checkpoint! 42 :flush! (fn [_])}])
             (map (fn [read-only]
                    [{:db-spec {:vendor "chdb-durable" :read-only? read-only}
                      :durability policy} :oscope.server/durable-writer-required])
                  [true 0 "reader"]))]
      (let [acquisitions (atom [])
            acquire (fn [phase]
                      (fn [& _]
                        (swap! acquisitions conj phase)
                        (throw (ex-info "unexpected acquisition" {}))))]
        (with-redefs [jdbc/connection (acquire :connection)
                      typed-schema/install! (acquire :typed-ddl)
                      http-executor/start! (acquire :workers)
                      chdb-export/exporter (acquire :exporter)
                      live/open! (acquire :source)
                      http/run-server (acquire :ingress)]
          (let [error (try (server/start! (assoc options :port 0))
                           (catch Throwable error error))]
            (is (= expected-type (:type (ex-data error))))
            (is (nil? (ex-cause error)))
            (is (empty? @acquisitions))))))))

(deftest standalone-selects-exporter-mode-from-canonical-dbspec
  (doseq [[db-spec durable?]
          [[{:vendor "chdb-durable"} true]
           [{:vendor "chdb"} false]
           ["chdb::memory:" false]]]
    (let [events (atom [])
          seen (atom [])
          conn (reify java.io.Closeable (close [_]))
          exporter (fake-exporter events)]
      (with-redefs [jdbc/connection (constantly conn)
                    chdb-export/exporter
                    (fn [options] (swap! seen conj options) exporter)
                    live/open! (constantly {:close! (fn [])})
                    otlp/handler (fn [& _] (constantly {:status 200}))
                    web/handler (fn [& _] (constantly {:status 200}))
                    http/run-server (fn [& _]
                                      (swap! events conj :ingress)
                                      {:port 9190})
                    http/stop-server (fn [_])]
        (let [lifecycle
              (server/start!
               {:port 0 :db-spec db-spec
                ;; Custom persistence callbacks alone do not identify a
                ;; Durable JDBC connection: ordinary modes keep their contract.
                :durability {:checkpoint! (fn [actual]
                                            (is (identical? conn actual))
                                            (swap! events conj :checkpoint)
                                            {:status :committed})
                             :flush! (fn [_] {:status :empty})}})]
          (try
            (is (= 1 (count @seen)))
            (is (identical? conn (:connection (first @seen))))
            (is (= durable? (true? (:durable? (first @seen)))))
            (is (nil? (:persistence-barrier (first @seen))))
            (is (= [:checkpoint :ingress] @events))
            (finally (server/stop! lifecycle))))))))

(deftest standalone-shares-one-connection-and-retires-in-order
  (let [events (atom [])
        conn (reify java.io.Closeable
               (close [_] (swap! events conj :connection)))
        exporter (fake-exporter events)
        source {:close! #(swap! events conj :oscope)}
        seen-exporter-options (atom nil)
        seen-source-options (atom nil)
        seen-server-options (atom nil)]
    (with-redefs [jdbc/connection (fn [spec]
                                    (swap! events conj [:connection spec]) conn)
                  chdb-export/exporter (fn [opts]
                                         (reset! seen-exporter-options opts)
                                         exporter)
                  live/open! (fn [opts] (reset! seen-source-options opts) source)
                  otlp/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [_ & options]
                                    (reset! seen-server-options
                                            (apply hash-map options))
                                    (swap! events conj :ingress-started)
                                    {:port 9191})
                  http/stop-server (fn [_] (swap! events conj :ingress-stopped))]
      (let [lifecycle (server/start! {:port 0 :db-spec "chdb:test"})]
        (is (= conn (:connection lifecycle)))
        (is (= {:connection conn :signals #{:spans :logs :metrics}}
               @seen-exporter-options))
        (is (= {:connection conn :ensure-schema? false} @seen-source-options))
        (is (= #{:port :server-name :reuse-address? :executor}
               (set (keys @seen-server-options))))
        (is (= 2 (get-in lifecycle [:http-executor :workers])))
        (is (= 8 (get-in lifecycle [:http-executor :queue-capacity])))
        (is (identical? (get-in lifecycle [:http-executor :executor])
                        (:executor @seen-server-options)))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
        (is (http-executor/terminated? (:http-executor lifecycle)))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
        (is (= [[:connection "chdb:test"] :ingress-started :ingress-stopped
                :oscope :spans :logs :metrics :connection]
               @events))))))

(defn- typed-schema-options []
  {:approved-manifest ::approved
   :registry-backend ::registry})

(deftest standalone-installs-confirmed-schema-before-exporter-and-ingress
  (let [events (atom [])
        descriptor-set (Object.)
        typed-options (typed-schema-options)
        conn (reify java.io.Closeable
               (close [_] (swap! events conj :connection-close)))
        exporter (fake-exporter events)
        exporter-options (atom nil)
        source-options (atom nil)]
    (with-redefs [jdbc/connection (fn [_]
                                    (swap! events conj :connection-open)
                                    conn)
                  typed-schema/install!
                  (fn [actual options]
                    (is (= conn actual))
                    (is (identical? typed-options options))
                    (swap! events conj :schema-installed)
                    {:descriptor-set descriptor-set})
                  typed-schema/descriptor-options
                  (fn [context]
                    (is (identical? descriptor-set (:descriptor-set context)))
                    {:typed-span-descriptors descriptor-set})
                  chdb-export/exporter
                  (fn [options]
                    (reset! exporter-options options)
                    (swap! events conj :exporter-open)
                    exporter)
                  live/open!
                  (fn [options]
                    (reset! source-options options)
                    (swap! events conj :source-open)
                    {:close! #(swap! events conj :source-close)})
                  otlp/handler (fn [& _] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _]
                                    (swap! events conj :ingress-started)
                                    {:port 9198})
                  http/stop-server (fn [_]
                                     (swap! events conj :ingress-stopped))]
      (let [lifecycle
            (server/start!
             {:port 0
              :typed-schema typed-options
              :durability {:checkpoint! (fn [_]
                                          (swap! events conj :checkpoint)
                                          {:status :committed})
                           :flush! (fn [_] {:status :empty})}})]
        (is (= [:connection-open :schema-installed :exporter-open :checkpoint
                :source-open :ingress-started]
               @events))
        (is (false? (:create-schema? @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors @source-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors lifecycle)))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))))))

(deftest standalone-routes-log-capability-only-to-log-export
  (let [descriptor-set (Object.)
        conn (reify java.io.Closeable (close [_]))
        exporter (fake-exporter (atom []))
        exporter-options (atom nil)
        source-options (atom nil)]
    (with-redefs [jdbc/connection (constantly conn)
                  typed-schema/install!
                  (fn [& _] {:descriptor-set descriptor-set})
                  typed-schema/descriptor-options
                  (fn [_] {:typed-log-descriptors descriptor-set})
                  chdb-export/exporter
                  (fn [options]
                    (reset! exporter-options options)
                    exporter)
                  live/open!
                  (fn [options]
                    (reset! source-options options)
                    {:close! (fn [])})
                  otlp/handler (fn [& _] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9191})
                  http/stop-server (fn [_])]
      (let [lifecycle (server/start! {:port 0 :typed-schema
                                      (typed-schema-options)})]
        (is (identical? descriptor-set
                        (:typed-log-descriptors @exporter-options)))
        (is (nil? (:typed-span-descriptors @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-log-descriptors @source-options)))
        (is (identical? descriptor-set (:typed-log-descriptors lifecycle)))
        (is (nil? (:typed-span-descriptors lifecycle)))
        (is (= {:status :closed :phase :closed}
               (server/stop! lifecycle)))))))

(deftest standalone-schema-failure-closes-before-exporter-or-ingress
  (let [events (atom [])
        conn (reify java.io.Closeable
               (close [_] (swap! events conj :connection-close)))]
    (with-redefs [jdbc/connection (constantly conn)
                  typed-schema/install! (fn [& _]
                                          (swap! events conj :schema-failed)
                                          (throw (ex-info "failed" {})))
                  chdb-export/exporter (fn [_]
                                         (swap! events conj :exporter-open))
                  http/run-server (fn [& _]
                                    (swap! events conj :ingress-started))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (server/start! {:port 0
                                   :typed-schema (typed-schema-options)})))
      (is (= [:schema-failed :connection-close] @events)))))

(deftest standalone-http-executor-configuration-is-bounded-and-validated
  (is (= {:http-workers 2 :http-queue-capacity 8}
         (server/http-executor-env-options {})))
  (is (= {:http-workers 3 :http-queue-capacity 11}
         (server/http-executor-env-options
          {"OSCOPE_HTTP_WORKERS" "3"
           "OSCOPE_HTTP_QUEUE_CAPACITY" "11"})))
  (doseq [environment [{"OSCOPE_HTTP_WORKERS" "0"}
                       {"OSCOPE_HTTP_WORKERS" "many"}
                       {"OSCOPE_HTTP_QUEUE_CAPACITY" "0"}
                       {"OSCOPE_HTTP_QUEUE_CAPACITY" "many"}]]
    (is (thrown? Exception
                 (server/http-executor-env-options environment))))
  (let [opened (atom 0)]
    (with-redefs [jdbc/connection (fn [_] (swap! opened inc))]
      (doseq [options [{:http-workers 0}
                       {:http-queue-capacity 0}]]
        (is (thrown? Exception (server/start! options)))))
    (is (zero? @opened))))

(deftest durable-startup-checkpoints-before-ingress-and-bounds-request-wal
  (let [events (atom [])
        conn (reify java.io.Closeable
               (close [_] (swap! events conj :connection)))
        exporter (fake-exporter events)
        source {:close! #(swap! events conj :oscope)}
        received-otlp-options (atom nil)]
    (with-redefs [jdbc/connection (fn [_] (swap! events conj :connection-open) conn)
                  chdb-export/exporter (fn [_]
                                         (swap! events conj :schema-ready)
                                         exporter)
                  live/open! (fn [_] (swap! events conj :source-open) source)
                  otlp/handler (fn [_ options]
                                 (reset! received-otlp-options options)
                                 (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _]
                                    (swap! events conj :ingress-started)
                                    {:port 9194})
                  http/stop-server (fn [_] (swap! events conj :ingress-stopped))]
      (let [lifecycle
            (server/start!
             {:port 0
              :durability
              {:checkpoint! (fn [actual]
                              (is (= conn actual))
                              (swap! events conj :checkpoint)
                              {:status :committed})
               :flush! (fn [actual]
                         (is (= conn actual))
                         (swap! events conj :flush)
                         {:status :committed})
               :checkpoint-every-batches 2}})]
        (is (= [:connection-open :schema-ready :checkpoint :source-open
                :ingress-started]
               @events))
        ((:after-success! @received-otlp-options))
        ((:after-success! @received-otlp-options))
        ((:after-success! @received-otlp-options))
        (is (= [:flush :checkpoint :flush] (subvec @events 5)))
        (server/stop! lifecycle)))))

(deftest failed-periodic-checkpoint-remains-due
  (let [events (atom [])
        checkpoint-attempt (atom 0)
        conn (reify java.io.Closeable (close [_] nil))
        exporter (fake-exporter events)
        source {:close! (fn [] nil)}
        received-otlp-options (atom nil)]
    (with-redefs [jdbc/connection (constantly conn)
                  chdb-export/exporter (constantly exporter)
                  live/open! (constantly source)
                  otlp/handler (fn [_ options]
                                 (reset! received-otlp-options options)
                                 (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9195})
                  http/stop-server (fn [_] nil)]
      (let [lifecycle
            (server/start!
             {:port 0
              :durability
              {:checkpoint!
               (fn [_]
                 (case (swap! checkpoint-attempt inc)
                   1 {:status :committed}
                   2 (do (swap! events conj :checkpoint-failed)
                         {:status :unexpected})
                   (do (swap! events conj :checkpoint-retried)
                       {:status :reconciled})))
               :flush! (fn [_]
                         (swap! events conj :flush)
                         {:status :committed})
               :checkpoint-every-batches 2}})
            after-success! (:after-success! @received-otlp-options)]
        (is (true? (after-success!)))
        (is (thrown? Exception (after-success!)))
        (is (true? (after-success!)))
        (is (= [:flush :checkpoint-failed :checkpoint-retried] @events))
        (server/stop! lifecycle)))))

(deftest durability-observations-use-the-actual-startup-and-http-path
  (doseq [operation [:flush :checkpoint]
          known? [false true]]
    (let [events (atom [])
          conn (reify java.io.Closeable (close [_] nil))
          exporter (reify
                     export/SpanExporter
                     (export-spans! [_ spans]
                       (swap! events conj [:exported (count spans)]) true)
                     (flush-exporter! [_] true)
                     (shutdown-exporter! [_] true)
                     export/MetricExporter
                     (export-metrics! [_ _ _] true)
                     (shutdown-metric-exporter! [_] true)
                     logs/LogRecordExporter
                     (export-logs! [_ _] true)
                     (shutdown-log-exporter! [_] true))
          app* (atom nil)
          options* (atom nil)
          observations (atom [])
          original-handler otlp/handler
          failure (ex-info "private-failure-marker"
                           {:type (if known?
                                    :jdbc.chdb.durable.control/lease-fenced
                                    ::private-type)
                            :sql "private-failure-marker"
                            :endpoint "private-failure-marker"})
          attempt (atom 0)
          fail-first! (fn [_]
                        (swap! events conj :persist)
                        (if (= 1 (swap! attempt inc))
                          (throw failure)
                          {:status :reconciled}))]
      (with-redefs [jdbc/connection (constantly conn)
                    chdb-export/exporter (constantly exporter)
                    live/open! (constantly {:close! (fn [] nil)})
                    web/handler (fn [& _] (constantly {:status 200}))
                    otlp/handler (fn [exporter options]
                                   (reset! options* options)
                                   (original-handler exporter options))
                    http/run-server (fn [app & _] (reset! app* app) {:port 9197})
                    http/stop-server (fn [_] nil)]
        (let [startup? (atom true)
              runtime
              (server/start!
               {:port 0
                :durability-diagnostic!
                (fn [record]
                  (swap! events conj :observed)
                  (swap! observations conj record)
                  ;; A throwing sink must not replace persistence failure.
                  (throw (ex-info "private-sink-marker" {})))
                :durability
                {:checkpoint! (fn [actual]
                                (if (compare-and-set! startup? true false)
                                  {:status :committed}
                                  (fail-first! actual)))
                 :flush! fail-first!
                 :checkpoint-every-batches (when (= :checkpoint operation) 1)}})
              expected {:oscope.durability-boundary/version 1
                        :operation operation
                        :phase :after-export-before-http-ack
                        :outcome :failed
                        :category (if known? :lease-fenced :unclassified)}
              request {:request-method :post :uri "/v1/traces"
                       :headers {"content-type" "application/json"
                                 "host" "127.0.0.1:9197"}
                       :body "{\"resourceSpans\":[{\"scopeSpans\":[{\"spans\":[{\"traceId\":\"10000000000000000000000000000000\",\"spanId\":\"2000000000000000\",\"name\":\"fixture\",\"startTimeUnixNano\":\"1\",\"endTimeUnixNano\":\"2\"}]}]}]}"}]
          (try
            ;; Directly invoke the real startup-created barrier once to witness
            ;; original exception identity, despite a throwing diagnostic sink.
            (is (identical? failure
                            (try ((:after-success! @options*))
                                 (catch Throwable error error))))
            (is (= expected (some-> runtime :durability-observation deref)))
            (is (= [expected] @observations))
            ;; Repeat the persistence fault through the actual HTTP adapter.
            (reset! attempt 0)
            (let [response (@app* request)]
              (is (= 503 (:status response)))
              (is (= "durability boundary failed\n" (:body response)))
              (is (= expected (some-> runtime :durability-observation deref))))
            (is (not (str/includes? (pr-str @observations) "private-")))
            (is (= 200 (:status (@app* request))))
            (is (= (assoc expected :outcome :confirmed :category :none)
                   (some-> runtime :durability-observation deref)))
            (is (= 2 @attempt))
            (is (= 3 (count @observations)))
            (is (= [:persist :observed [:exported 1] :persist :observed
                    [:exported 1] :persist :observed] @events))
            (finally (server/stop! runtime))))))))

(deftest invalid-durability-diagnostic-sink-fails-before-acquisition
  (let [opened (atom 0)]
    (with-redefs [jdbc/connection (fn [_] (swap! opened inc))]
      (doseq [invalid [false {} :not-a-function]]
        (let [failure (try (server/start! {:durability-diagnostic! invalid})
                           (catch Throwable failure failure))]
          (is (= {:oscope.server/error true
                  :type :oscope.server/invalid-durability-diagnostic}
                 (ex-data failure)))))
      (is (zero? @opened)))))

(defn- diagnostic-observation [& args]
  ;; Missing baseline API is an assertion failure, never an analyzer abort.
  (let [observe (ns-resolve 'oscope.error 'durability-boundary-observation)]
    (is (some? observe) "the closed observation API must exist")
    (if observe (apply observe args) {:missing-observation-api true})))

(deftest durability-classification-is-bounded-and-does-not-stop-at-unknown-types
  (let [known (ex-info "private-root" {:type :jdbc.chdb.durable.control/commit-ambiguous})
        wrap (fn [cause] (ex-info "private-wrapper" {:type ::unknown-wrapper} cause))
        descriptor (fn [failure]
                     (diagnostic-observation :flush :failed failure))]
    (is (= :commit-ambiguous (:category (descriptor (wrap known)))))
    (is (= :commit-ambiguous
           (:category (descriptor (nth (iterate wrap known) 7)))))
    (is (= :unclassified
           (:category (descriptor (nth (iterate wrap known) 8)))))
    (is (= #{:oscope.durability-boundary/version :operation :phase :outcome :category}
           (set (keys (descriptor known)))))
    (is (not (str/includes? (pr-str (descriptor (wrap known))) "private")))))

(deftest unknown-diagnostic-types-are-not-hashed-or-realized
  (let [realized (atom 0)
        bomb (lazy-seq (swap! realized inc)
                       (throw (ex-info "private-realization-marker" {})))
        known (ex-info "private-root" {:type :jdbc.chdb.durable.control/timeout})
        expected {:oscope.durability-boundary/version 1
                  :operation :flush :phase :after-export-before-http-ack
                  :outcome :failed :category :storage-timeout}]
    (doseq [type [bomb {"private" "fixture"} "private-type" false nil]]
      (is (= expected
             (diagnostic-observation
              :flush :failed (ex-info "private-wrapper" {:type type} known))))
      (is (= (assoc expected :category :unclassified)
             (diagnostic-observation
              :flush :failed (ex-info "private-wrapper" {:type type})))))
    (is (zero? @realized))))

(deftest unknown-diagnostic-operations-are-not-hashed-or-realized
  (let [realized (atom 0)
        bomb (lazy-seq (swap! realized inc)
                       (throw (ex-info "private-operation-marker" {})))
        expected {:oscope.durability-boundary/version 1
                  :operation :unknown :phase :after-export-before-http-ack
                  :outcome :confirmed :category :none}]
    (doseq [operation [bomb {} "flush" false nil :unknown-operation]]
      (is (= expected (diagnostic-observation operation :confirmed nil))))
    (is (zero? @realized))))

(deftest concurrent-durability-boundaries-select-one-checkpoint
  (let [events (atom [])
        checkpoint-calls (atom 0)
        checkpoint-entered (promise)
        release-checkpoint (promise)
        second-started (promise)
        first-result (promise)
        second-result (promise)
        conn (reify java.io.Closeable (close [_] nil))
        exporter (fake-exporter events)
        source {:close! (fn [] nil)}
        received-otlp-options (atom nil)]
    (with-redefs [jdbc/connection (constantly conn)
                  chdb-export/exporter (constantly exporter)
                  live/open! (constantly source)
                  otlp/handler (fn [_ options]
                                 (reset! received-otlp-options options)
                                 (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9197})
                  http/stop-server (fn [_] nil)]
      (let [lifecycle
            (server/start!
             {:port 0
              :durability
              {:checkpoint!
               (fn [_]
                 (when (> (swap! checkpoint-calls inc) 1)
                   (swap! events conj :checkpoint-entered)
                   (deliver checkpoint-entered true)
                   @release-checkpoint
                   (swap! events conj :checkpoint-finished))
                 {:status :committed})
               :flush! (fn [_]
                         (swap! events conj :flush)
                         {:status :committed})
               :checkpoint-every-batches 2}})
            after-success! (:after-success! @received-otlp-options)
            first-worker (Thread. #(deliver first-result (after-success!)))
            second-worker
            (Thread. #(do (deliver second-started true)
                          (deliver second-result (after-success!))))]
        (try
          (is (true? (after-success!)))
          (.start first-worker)
          (is (= true (deref checkpoint-entered 1000 ::timeout)))
          (.start second-worker)
          (is (= true (deref second-started 1000 ::timeout)))
          (.join second-worker 50)
          (is (.isAlive second-worker)
              "the second worker cannot finish while the boundary is owned")
          (is (= [:flush :checkpoint-entered] @events)
              "the blocked worker cannot double-select the checkpoint")
          (deliver release-checkpoint true)
          (.join first-worker 1000)
          (.join second-worker 1000)
          (is (not (.isAlive first-worker)))
          (is (not (.isAlive second-worker)))
          (is (true? (deref first-result 1000 ::timeout)))
          (is (true? (deref second-result 1000 ::timeout)))
          (is (= [:flush :checkpoint-entered :checkpoint-finished :flush]
                 @events))
          (is (= 2 @checkpoint-calls)
              "startup plus exactly one periodic checkpoint")
          (finally
            (deliver release-checkpoint true)
            (.join first-worker 1000)
            (.join second-worker 1000)
            (server/stop! lifecycle)))))))

(deftest invalid-checkpoint-cadence-opens-no-connection
  (let [opened (atom 0)]
    (with-redefs [jdbc/connection (fn [_] (swap! opened inc))]
      (is (thrown? Exception
                   (server/start!
                    {:durability
                     {:checkpoint! (constantly {:status :committed})
                      :flush! (constantly {:status :committed})
                      :checkpoint-every-batches 0}}))))
    (is (zero? @opened))))

(deftest durable-startup-rejects-an-unconfirmed-checkpoint-before-ingress
  (let [opened (atom 0)
        closed (atom 0)
        conn (reify java.io.Closeable (close [_] (swap! closed inc)))]
    (with-redefs [jdbc/connection (fn [_] (swap! opened inc) conn)
                  chdb-export/exporter (constantly (fake-exporter (atom [])))
                  http/run-server (fn [& _]
                                    (throw (ex-info "ingress must not start" {})))]
      (doseq [result [nil false {:status :unexpected}]]
        (is (thrown? Exception
                     (server/start!
                      {:port 0
                       :durability {:checkpoint! (constantly result)
                                    :flush! (constantly {:status :empty})}})))))
    (is (= 3 @opened))
    (is (= 3 @closed))))

(deftest shutdown-stops-at-a-failed-boundary-and-retries-it
  (let [attempts (atom 0)
        events (atom [])
        conn (reify java.io.Closeable (close [_] (swap! events conj :connection)))
        exporter (fake-exporter events)
        source {:close! #(swap! events conj :oscope)}]
    (with-redefs [jdbc/connection (constantly conn)
                  chdb-export/exporter (constantly exporter)
                  live/open! (constantly source)
                  otlp/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9192})
                  http/stop-server
                  (fn [_]
                    (swap! events conj :ingress-stop-attempt)
                    (when (= 1 (swap! attempts inc))
                      (throw (ex-info "retry me" {}))))]
      (let [lifecycle (server/start! {:port 0})
            first-stop (server/stop! lifecycle)]
        (is (= :closing (:status first-stop)))
        (is (= :open (:phase first-stop)))
        (is (= 1 (count (:errors first-stop))))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
        (is (= [:ingress-stop-attempt :ingress-stop-attempt :oscope
                :spans :logs :metrics :connection]
               @events))))))

(deftest shutdown-results-replace-secret-bearing-throwables
  (let [canary "standalone-lifecycle-secret"
        attempts (atom 0)
        conn (reify java.io.Closeable (close [_] nil))
        exporter (fake-exporter (atom []))
        source {:close! (fn [] nil)}]
    (with-redefs [jdbc/connection (constantly conn)
                  chdb-export/exporter (constantly exporter)
                  live/open! (constantly source)
                  otlp/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9199})
                  http/stop-server
                  (fn [_]
                    (when (= 1 (swap! attempts inc))
                      (throw (ex-info (str canary "-message")
                                      {:path (str "/private/" canary)
                                       :headers {"authorization" canary}
                                       :attribute-value canary}
                                      (Exception. (str canary "-cause"))))))]
      (let [lifecycle (server/start! {:port 0})
            failed (server/stop! lifecycle)]
        (is (= {:status :closing
                :phase :open
                :errors [{:type :oscope.error/lifecycle-operation-threw
                          :operation :stop-ingress}]}
               failed))
        (assert-public-result-safe! failed canary)
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))))))

(deftest terminal-owner-drives-retryable-shutdown-or-fails-visibly
  (let [attempts (atom 0)]
    (with-redefs [server/stop!
                  (fn [_]
                    (if (< (swap! attempts inc) 3)
                      {:status :closing :phase :retiring-oscope
                       :errors [(ex-info "retry" {})]}
                      {:status :closed :phase :closed}))]
      (is (= {:status :closed :phase :closed}
             (server-main/stop-until-closed! ::lifecycle 3)))
      (is (= 3 @attempts))))
  (let [attempts (atom 0)
        error (with-redefs [server/stop!
                            (fn [_]
                              (swap! attempts inc)
                              {:status :closing :phase :closing-connection
                               :errors [(ex-info "still open" {})]})]
                (try
                  (server-main/stop-until-closed! ::lifecycle 2)
                  nil
                  (catch Throwable error error)))]
    (is (= 2 @attempts))
    (is (= 2 (:attempts (ex-data error))))
    (is (= :closing-connection (:phase (ex-data error))))
    (is (= 2 (count (:errors (ex-data error)))))))

(deftest terminal-owner-retries-every-owned-shutdown-boundary
  (doseq [failed-boundary [:oscope :spans :logs :metrics :connection]]
    (let [attempts (atom {})
          retire! (fn [boundary]
                    (let [attempt (get (swap! attempts update boundary
                                              (fnil inc 0)) boundary)]
                      (when (and (= failed-boundary boundary) (= 1 attempt))
                        (throw (ex-info "injected retirement failure"
                                        {:boundary boundary})))))
          conn (reify java.io.Closeable
                 (close [_] (retire! :connection)))
          exporter (fake-exporter (atom []) retire!)
          source {:close! #(retire! :oscope)}]
      (with-redefs [jdbc/connection (constantly conn)
                    chdb-export/exporter (constantly exporter)
                    live/open! (constantly source)
                    otlp/handler (fn [_] (constantly {:status 200}))
                    web/handler (fn [& _] (constantly {:status 200}))
                    http/run-server (fn [& _] {:port 9193})
                    http/stop-server (fn [_] nil)]
        (let [lifecycle (server/start! {:port 0})]
          (is (= {:status :closed :phase :closed}
                 (server-main/stop-until-closed! lifecycle 2))
              (str "terminal owner retries " failed-boundary))
          (is (= 2 (get @attempts failed-boundary))
              (str "failed boundary retried exactly once: " failed-boundary))
          (doseq [boundary [:oscope :spans :logs :metrics :connection]]
            (is (= (if (= boundary failed-boundary) 2 1)
                   (get @attempts boundary 0))
                (str "completed boundaries are not repeated while retrying "
                     failed-boundary ": " boundary))))))))
