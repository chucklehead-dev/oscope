(ns oscope.embedded-test
  (:require [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable]
            [jdbc.core :as jdbc]
            [jolt.host :as host]
            [oscope.embedded :as embedded]
            [oscope.live :as live]
            [oscope.typed-schema :as typed-schema]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.otlp :as otlp]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as sdk-logs]
            [otel.trace :as trace]))

(defn- await! [pred]
  (loop [attempt 0]
    (cond
      (pred) true
      (< attempt 1000) (do (Thread/sleep 2) (recur (inc attempt)))
      :else false)))

(deftest embedded-runtime-shares-one-writer-and-retires-in-order
  (let [events (atom [])
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        exporter ::exporter
        source {:close! #(swap! events conj :source-close)}
        handle ::sdk-handle
        exporter-options (atom nil)
        installed-options (atom nil)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (fn [spec]
                                    (swap! events conj [:connection-open spec])
                                    connection)
                  chdb-export/exporter
                  (fn [options]
                    (reset! exporter-options options)
                    (swap! events conj :exporter-open)
                    exporter)
                  live/open! (fn [options]
                               (is (= {:connection connection
                                       :ensure-schema? false}
                                      options))
                               (swap! events conj :source-open)
                               source)
                  sdk/init! (fn [options]
                              (reset! installed-options options)
                              (swap! events conj :sdk-start)
                              handle)
                  sdk/force-flush! (fn [actual]
                                     (is (= handle actual))
                                     (swap! events conj :sdk-flush)
                                     true)
                  sdk/shutdown! (fn [actual]
                                  (is (= handle actual))
                                  (swap! events conj :sdk-stop)
                                  true)
                  jdbc.chdb.durable/checkpoint!
                  (fn [actual]
                    (is (= connection actual))
                    (swap! events conj :checkpoint)
                    {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:service-name "embedded-test"
                            :metrics? false :logs? true}})]
        (is (= {:connection connection
                :signals #{:spans :logs}
                :durable? true}
               @exporter-options))
        (is (= exporter (:exporter @installed-options)))
        (is (= #{:spans :logs} (:signals lifecycle)))
        (is (true? (embedded/force-flush! lifecycle)))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))
        (is (false? (embedded/force-flush! lifecycle)))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))
        (is (= [[:connection-open ::durable]
                :exporter-open :source-open :sdk-start :sdk-flush
                :sdk-stop :source-close :checkpoint :connection-close]
               @events))))))

(deftest embedded-runtime-retries-an-unconfirmed-persistence-boundary
  (let [attempts (atom 0)
        events (atom [])
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        source {:close! #(swap! events conj :source-close)}]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly ::exporter)
                  live/open! (constantly source)
                  sdk/init! (constantly ::sdk-handle)
                  sdk/shutdown! (fn [_] (swap! events conj :sdk-stop) true)
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    (if (= 1 (swap! attempts inc))
                      {:status :unexpected}
                      {:status :reconciled}))]
      (let [lifecycle (embedded/start! {:db-spec ::durable})
            first-stop (embedded/stop! lifecycle)]
        (is (= :closing (:status first-stop)))
        (is (= :persisting (:phase first-stop)))
        (is (= 1 (count (:errors first-stop))))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))
        (is (= [:sdk-stop :source-close :checkpoint :checkpoint
                :connection-close]
               @events))))))

(deftest embedded-runtime-rejects-conflicting-sdk-ownership-before-opening
  (let [opened? (atom false)]
    (with-redefs [sdk/tracer-provider (constantly ::already-configured)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (fn [_] (reset! opened? true))]
      (is (thrown? Exception
                   (embedded/start! {:db-spec ::durable})))
      (is (false? @opened?))))
  (is (thrown? Exception
               (embedded/start!
                {:db-spec ::durable
                 :sdk-options {:exporter ::caller-owned}})))
  (is (thrown? Exception
               (embedded/start!
                {:db-spec ::durable
                 :sdk-options {:span-processors [::caller-owned]}})))
  (is (thrown? Exception (embedded/stop! nil)))
  (is (thrown? Exception (embedded/force-flush! {}))))

(defn- test-exporter [batches shutdowns]
  (reify
    export/SpanExporter
    (export-spans! [_ spans]
      (swap! batches conj spans)
      true)
    (flush-exporter! [_] true)
    (shutdown-exporter! [_]
      (swap! shutdowns inc)
      true)

    export/MetricExporter
    (export-metrics! [_ _ _] true)
    (shutdown-metric-exporter! [_] true)

    sdk-logs/LogRecordExporter
    (export-logs! [_ _] true)
    (shutdown-log-exporter! [_] true)))

(deftest embedded-lifecycle-retains-background-remote-export-failure
  (let [entered (promise)
        release (promise)
        remote-export-calls (atom 0)
        remote-flushes (atom 0)
        remote-shutdowns (atom 0)
        local-batches (atom [])
        local-shutdowns (atom 0)
        local-exporter (test-exporter local-batches local-shutdowns)
        remote-exporter
        (reify export/SpanExporter
          (export-spans! [_ _]
            (swap! remote-export-calls inc)
            (deliver entered true)
            @release
            false)
          ;; This matches the stateless OTLP exporter contract. It must not
          ;; erase a failure from a batch that the worker already dequeued.
          (flush-exporter! [_]
            (swap! remote-flushes inc)
            true)
          (shutdown-exporter! [_]
            (swap! remote-shutdowns inc)
            true))
        events (atom [])
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))]
    (with-redefs [host/getenv (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly local-exporter)
                  otlp/exporter (constantly remote-exporter)
                  live/open!
                  (constantly {:close! #(swap! events conj :source-close)})
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:service-name "oscope.pipeline-isolation-test"
                            :metrics? false :logs? false}
              :span-pipelines
              {:local {:schedule-delay-ms 1}
               :remote {:endpoint "http://collector:4318"
                        :schedule-delay-ms 1
                        :max-queue-size 2
                        :max-export-batch-size 1}}})]
        (try
          (trace/with-span [_ (sdk/tracer "oscope.pipeline-isolation") "first"])
          (is (= true (deref entered 2000 ::timeout))
              "the remote worker owns a dequeued batch before it fails")
          (dotimes [index 10]
            (trace/with-span [_ (sdk/tracer "oscope.pipeline-isolation")
                              (str "local-" index)]))
          (is (await! #(= 11 (reduce + (map count @local-batches))))
              "local export completes while remote exporter I/O is blocked")
          (is (= 8 (get-in (embedded/span-pipeline-stats lifecycle)
                           [:remote :dropped-count]))
              "only the two free remote queue slots accept later spans")
          (is (zero? (get-in (embedded/span-pipeline-stats lifecycle)
                             [:local :dropped-count])))
          (deliver release true)
          (is (= {:sdk {:ok? true}
                  :span-pipelines
                  {:local {:ok? true}
                   :remote {:ok? false :failure :returned-false}}}
                 (embedded/force-flush! lifecycle))
              "a true OTLP flush cannot erase its failed background batch")
          (is (= {:queue-size 0
                  :attempted-span-count 11
                  :exported-span-count 11
                  :failed-span-count 0
                  :dropped-count 0}
                 (:local (embedded/span-pipeline-stats lifecycle))))
          (is (= {:queue-size 0
                  :attempted-span-count 3
                  :exported-span-count 0
                  :failed-span-count 3
                  :dropped-count 8}
                 (:remote (embedded/span-pipeline-stats lifecycle))))
          (let [stopped (embedded/stop! lifecycle)]
            (is (= {:ok? true} (get-in stopped [:telemetry :sdk])))
            (is (= {:ok? true}
                   (get-in stopped [:telemetry :span-pipelines :local])))
            (is (= {:ok? false :failure :returned-false}
                   (get-in stopped [:telemetry :span-pipelines :remote])))
            (is (= :closed (:status stopped))))
          (is (= 3 @remote-export-calls))
          (is (= 2 @remote-flushes)
              "force-flush and shutdown both attempt the exporter callback")
          (is (= 1 @local-shutdowns))
          (is (= 1 @remote-shutdowns))
          (is (= [:source-close :checkpoint :connection-close] @events))
          (finally
            (deliver release true)
            (when (sdk/tracer-provider)
              (embedded/stop! lifecycle))))))))

(deftest dual-span-pipelines-preserve-one-canonical-span-and-private-credentials
  (let [events (atom [])
        local-batches (atom [])
        remote-batches (atom [])
        local-shutdowns (atom 0)
        remote-shutdowns (atom 0)
        local-exporter (test-exporter local-batches local-shutdowns)
        remote-exporter (test-exporter remote-batches remote-shutdowns)
        remote-options (atom nil)
        sdk-options (atom nil)
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        source {:close! #(swap! events conj :source-close)}
        canonical-span {:name "child"
                        :span-context {:trace-id "00112233445566778899aabbccddeeff"
                                       :span-id "0011223344556677"
                                       :trace-flags 1}
                        :parent-span-id "ffeeddccbbaa9988"}]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  host/getenv (fn [name]
                                (when (= "OSCOPE_REMOTE_HEADERS" name)
                                  "authorization=super-secret"))
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (fn [_] local-exporter)
                  otlp/exporter (fn [options]
                                  (reset! remote-options options)
                                  remote-exporter)
                  live/open! (constantly source)
                  sdk/init! (fn [options]
                              (reset! sdk-options options)
                              ::sdk-handle)
                  sdk/force-flush! (fn [_]
                                     (export/force-flush!
                                      (first (:span-processors @sdk-options))))
                  sdk/shutdown! (fn [_]
                                  (export/shutdown!
                                   (first (:span-processors @sdk-options))))
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:metrics? true :logs? true}
              :span-pipelines
              {:local {:schedule-delay-ms 60000 :max-queue-size 8}
               :remote {:traces-url "https://collector.example/v1/traces"
                        :headers-env "OSCOPE_REMOTE_HEADERS"
                        :timeout-ms 1234 :max-retries 0 :insecure? false
                        :schedule-delay-ms 60000 :max-queue-size 8}}})
            processor (first (:span-processors @sdk-options))]
        (is (identical? local-exporter (:exporter @sdk-options))
            "the local exporter remains the SDK logs and metrics owner")
        (is (= {"authorization" "super-secret"} (:headers @remote-options)))
        (is (nil? (:sdk-handle lifecycle))
            "the public dual lifecycle does not expose the private processor")
        (is (not (.contains (pr-str lifecycle) "super-secret")))
        (is (not (.contains (pr-str lifecycle) "OSCOPE_REMOTE_HEADERS")))
        (export/on-start processor canonical-span ::parent-context)
        (export/on-end processor canonical-span)
        (is (= {:sdk {:ok? true}
                :span-pipelines
                {:local {:ok? true} :remote {:ok? true}}}
               (embedded/force-flush! lifecycle)))
        (is (identical? canonical-span (-> @local-batches first first)))
        (is (identical? canonical-span (-> @remote-batches first first)))
        (is (= (:span-context (-> @local-batches first first))
               (:span-context (-> @remote-batches first first))))
        (is (= (:parent-span-id (-> @local-batches first first))
               (:parent-span-id (-> @remote-batches first first))))
        (is (= #{:local :remote}
               (set (keys (embedded/span-pipeline-stats lifecycle)))))
        (is (= {:status :closed
                :phase :closed
                :telemetry
                {:sdk {:ok? true}
                 :span-pipelines
                 {:local {:ok? true} :remote {:ok? true}}}}
               (embedded/stop! lifecycle)))
        (is (= 1 @local-shutdowns))
        (is (= 1 @remote-shutdowns))
        (is (= [:source-close :checkpoint :connection-close] @events))))))

(deftest remote-span-failure-does-not-waive-local-durable-retirement
  (doseq [remote-failure [:returned-false :threw]]
    (let [events (atom [])
          checkpoints (atom 0)
          connection (reify java.io.Closeable
                       (close [_] (swap! events conj :connection-close)))
          local (test-exporter (atom []) (atom 0))
          remote
          (reify export/SpanExporter
            (export-spans! [_ _] false)
            (flush-exporter! [_] false)
            (shutdown-exporter! [_]
              (swap! events conj :remote-shutdown)
              (if (= :threw remote-failure)
                (throw (ex-info "remote-secret" {:token "remote-secret"}))
                false)))
          installed (atom nil)]
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil)
                    sdk/logger-provider (constantly nil)
                    jdbc/connection (constantly connection)
                    chdb-export/exporter (constantly local)
                    otlp/exporter (constantly remote)
                    live/open! (constantly {:close! #(swap! events conj :source-close)})
                    sdk/init! (fn [options] (reset! installed options) ::handle)
                    sdk/shutdown! (fn [_]
                                    (export/shutdown!
                                     (first (:span-processors @installed))))
                    jdbc.chdb.durable/checkpoint!
                    (fn [_]
                      (swap! events conj :checkpoint)
                      (if (= 1 (swap! checkpoints inc))
                        {:status :unexpected}
                        {:status :reconciled}))]
        (let [lifecycle
              (embedded/start!
               {:db-spec ::durable
                :sdk-options {:metrics? false :logs? false}
                :span-pipelines
                {:local {:schedule-delay-ms 60000}
                 :remote {:endpoint "http://collector:4318"
                          :schedule-delay-ms 60000}}})
              first-stop (embedded/stop! lifecycle)
              final-stop (embedded/stop! lifecycle)]
          (is (= :closing (:status first-stop)))
          (is (= :persisting (:phase first-stop)))
          (is (= :closed (:status final-stop)))
          (is (= {:ok? true} (get-in final-stop
                                      [:telemetry :span-pipelines :local])))
          (is (= {:ok? false :failure remote-failure}
                 (get-in final-stop
                         [:telemetry :span-pipelines :remote])))
          (is (= {:ok? true} (get-in final-stop [:telemetry :sdk])))
          (is (= [:remote-shutdown :source-close :checkpoint :checkpoint
                  :connection-close]
                 @events))
          (is (not (.contains (pr-str final-stop) "collector")))
          (is (not (.contains (pr-str final-stop) "remote-secret"))))))))

(deftest terminal-telemetry-failure-does-not-strand-durable-owner
  (doseq [[local-failure remote-failure]
          [[:returned-false nil]
           [:threw :threw]]]
    (let [events (atom [])
          exporter
          (fn [destination failure]
            (reify export/SpanExporter
              (export-spans! [_ _] true)
              (flush-exporter! [_] true)
              (shutdown-exporter! [_]
                (swap! events conj (keyword (str (name destination) "-shutdown")))
                (case failure
                  :returned-false false
                  :threw (throw (ex-info "telemetry-secret"
                                         {:token "telemetry-secret"}))
                  true))))
          installed (atom nil)
          connection (reify java.io.Closeable
                       (close [_] (swap! events conj :connection-close)))]
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil)
                    sdk/logger-provider (constantly nil)
                    jdbc/connection (constantly connection)
                    chdb-export/exporter
                    (constantly (exporter :local local-failure))
                    otlp/exporter
                    (constantly (exporter :remote remote-failure))
                    live/open!
                    (constantly {:close! #(swap! events conj :source-close)})
                    sdk/init! (fn [options] (reset! installed options) ::handle)
                    sdk/shutdown!
                    (fn [_]
                      (export/shutdown! (first (:span-processors @installed))))
                    jdbc.chdb.durable/checkpoint!
                    (fn [_]
                      (swap! events conj :checkpoint)
                      {:status :committed})]
        (let [lifecycle
              (embedded/start!
               {:db-spec ::durable
                :sdk-options {:metrics? false :logs? false}
                :span-pipelines
                {:local {:schedule-delay-ms 60000}
                 :remote {:endpoint "http://collector:4318"
                          :schedule-delay-ms 60000}}})
              stopped (embedded/stop! lifecycle)
              stopped-again (embedded/stop! lifecycle)]
          (is (= :closed (:status stopped)))
          (is (= stopped stopped-again))
          (is (= {:ok? false :failure :returned-false}
                 (get-in stopped [:telemetry :sdk])))
          (is (= {:ok? false :failure local-failure}
                 (get-in stopped [:telemetry :span-pipelines :local])))
          (is (= (if remote-failure
                   {:ok? false :failure remote-failure}
                   {:ok? true})
                 (get-in stopped [:telemetry :span-pipelines :remote])))
          (is (= [:local-shutdown :remote-shutdown :source-close :checkpoint
                  :connection-close]
                 @events))
          (is (not (.contains (pr-str stopped) "telemetry-secret"))))))))

(deftest sdk-wide-failure-is-not-attributed-to-a-span-destination
  (let [shutdown-attempts (atom 0)
        connection (reify java.io.Closeable (close [_] nil))
        local (test-exporter (atom []) (atom 0))
        remote (test-exporter (atom []) (atom 0))
        installed (atom nil)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly local)
                  otlp/exporter (constantly remote)
                  live/open! (constantly {:close! (fn [] nil)})
                  sdk/init! (fn [options] (reset! installed options) ::handle)
                  sdk/shutdown!
                  (fn [_]
                    ;; The span processor succeeds. The first overall false
                    ;; models a later metric/log component failure.
                    (export/shutdown! (first (:span-processors @installed)))
                    (> (swap! shutdown-attempts inc) 1))
                  jdbc.chdb.durable/checkpoint!
                  (constantly {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:metrics? true :logs? true}
              :span-pipelines
              {:local {:schedule-delay-ms 60000}
               :remote {:endpoint "http://collector:4318"
                        :schedule-delay-ms 60000}}})
            first-stop (embedded/stop! lifecycle)]
        (is (= :closed (:status first-stop)))
        (is (= {:ok? false :failure :returned-false}
               (get-in first-stop [:telemetry :sdk])))
        (is (= {:local {:ok? true} :remote {:ok? true}}
               (get-in first-stop [:telemetry :span-pipelines]))
            "an SDK-wide failure must not overwrite the local span result")
        (is (= first-stop (embedded/stop! lifecycle)))
        (is (= 1 @shutdown-attempts)
            "a terminal SDK result must not be presented as retryable")))))

(deftest embedded-remote-config-ignores-ambient-otel-values
  (let [ambient {"OTEL_EXPORTER_OTLP_ENDPOINT" "https://ambient-base.invalid"
                 "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"
                 "https://ambient-user:secret@collector.invalid/v1/traces"
                 "OTEL_EXPORTER_OTLP_HEADERS" "authorization=ambient-secret"
                 "OTEL_EXPORTER_OTLP_TIMEOUT" "1"
                 "OSCOPE_REMOTE_HEADERS" "authorization=approved-secret"}
        reads (atom [])
        built (atom nil)
        direct-exporter otlp/exporter]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  host/getenv (fn [name]
                                (swap! reads conj name)
                                (get ambient name))
                  otlp/exporter (fn [options]
                                  (let [exporter (direct-exporter options)]
                                    (reset! built exporter)
                                    exporter))
                  jdbc/connection
                  (fn [_]
                    (throw (ex-info "stop after remote construction" {})))]
      (is (thrown? Exception
                   (embedded/start!
                    {:db-spec ::durable
                     :span-pipelines
                     {:local {}
                      :remote {:endpoint "https://approved.invalid:4318"
                               :headers-env "OSCOPE_REMOTE_HEADERS"}}})))
      (is (= "https://approved.invalid:4318/v1/traces" (:url @built)))
      (is (= {"authorization" "approved-secret"} (:headers @built)))
      (is (= 10000 (:timeout-ms @built)))
      (is (= ["OSCOPE_REMOTE_HEADERS"] @reads)
          "the explicit secret reference is the only environment read"))))

(deftest dual-span-configuration-fails-before-jdbc-or-workers
  (doseq [span-pipelines
          [{:local {} :remote {}}
           {:local {} :remote {:endpoint "http://one" :traces-url "http://two"}}
           {:local {} :remote {:endpoint "grpc://collector"}}
           {:local {} :remote {:endpoint "http://"}}
           {:local {} :remote {:endpoint "https://bad host"}}
           {:local {} :remote {:endpoint "https://user@collector"}}
           {:local {} :remote {:endpoint "https://collector?token=secret"}}
           {:local {} :remote {:traces-url "https://collector/v1/traces#secret"}}
           {:local {:max-queue-size 0} :remote {:endpoint "http://collector"}}
           {:local {} :remote {:endpoint "http://collector" :headers {"x" "secret"}}}
           {:local {} :remote {:endpoint "http://collector" :headers-env "bad-name"}}
           {:local {} :remote {:endpoint "http://collector" :max-retries -1}}
           {:local {} :remote {:endpoint "http://collector" :insecure? :yes}}
           {:local {} :remote {:endpoint "http://collector"} :extra {}}]]
    (let [opened? (atom false)
          remote-opened? (atom false)]
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil)
                    sdk/logger-provider (constantly nil)
                    jdbc/connection (fn [_] (reset! opened? true))
                    otlp/exporter (fn [_] (reset! remote-opened? true))]
        (is (thrown? Exception
                     (embedded/start! {:db-spec ::durable
                                       :span-pipelines span-pipelines})))
        (is (false? @opened?))
        (is (false? @remote-opened?))))))

(defn- typed-schema-options []
  {:approved-manifest ::approved
   :registry-backend ::registry})

(deftest embedded-installs-confirmed-schema-before-exporter-and-sdk
  (let [events (atom [])
        descriptor-set (Object.)
        typed-options (typed-schema-options)
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        exporter-options (atom nil)
        source-options (atom nil)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (fn [_]
                                    (swap! events conj :connection-open)
                                    connection)
                  jdbc.chdb.durable/connection-role (constantly :writer)
                  typed-schema/install!
                  (fn [actual options]
                    (is (= connection actual))
                    (is (identical? typed-options options))
                    (swap! events conj :schema-installed)
                    {:descriptor-set descriptor-set})
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    {:status :committed})
                  chdb-export/exporter
                  (fn [options]
                    (reset! exporter-options options)
                    (swap! events conj :exporter-open)
                    ::exporter)
                  live/open!
                  (fn [options]
                    (reset! source-options options)
                    (swap! events conj :source-open)
                    {:close! #(swap! events conj :source-close)})
                  sdk/init! (fn [_]
                              (swap! events conj :sdk-start)
                              ::sdk-handle)
                  sdk/shutdown! (fn [_]
                                  (swap! events conj :sdk-stop)
                                  true)]
      (let [lifecycle
            (embedded/start! {:db-spec ::durable
                              :typed-schema typed-options})]
        (is (= [:connection-open :schema-installed :checkpoint :exporter-open
                :source-open :sdk-start]
               @events))
        (is (false? (:create-schema? @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors @source-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors lifecycle)))
        (is (= {:status :closed :phase :closed} (embedded/stop! lifecycle)))))))

(deftest embedded-schema-failure-closes-before-exporter-or-sdk
  (let [events (atom [])
        typed-options (typed-schema-options)
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  jdbc.chdb.durable/connection-role (constantly :writer)
                  typed-schema/install! (fn [& _]
                                          (swap! events conj :schema-failed)
                                          (throw (ex-info "failed" {})))
                  chdb-export/exporter (fn [_]
                                         (swap! events conj :exporter-open))
                  sdk/init! (fn [_] (swap! events conj :sdk-start))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (embedded/start! {:db-spec ::durable
                                     :typed-schema typed-options})))
      (is (= [:schema-failed :connection-close] @events)))))
