(ns oscope.durable-cadence-property-test
  (:require [clojure.test :refer [deftest is]]
            [db.jdbc]
            [hegel.core :as h]
            [hegel.generator :as g]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.live :as live]
            [oscope.otlp :as otlp]
            [oscope.server :as server]
            [oscope.ui.web :as web]
            [otel.exporter.chdb :as chdb-export]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(defn- fake-exporter []
  (reify
    export/SpanExporter
    (export-spans! [_ _] true)
    (flush-exporter! [_] true)
    (shutdown-exporter! [_] true)
    export/MetricExporter
    (export-metrics! [_ _ _] true)
    (shutdown-metric-exporter! [_] true)
    logs/LogRecordExporter
    (export-logs! [_ _] true)
    (shutdown-log-exporter! [_] true)))

(deftest generated-checkpoint-cadence-matches-the-configured-bound
  (let [conn (reify java.io.Closeable (close [_] nil))
        received-otlp-options (atom nil)]
    (with-redefs [jdbc/connection (constantly conn)
                  chdb-export/exporter (constantly (fake-exporter))
                  live/open! (constantly {:close! (fn [] nil)})
                  otlp/handler (fn [_ options]
                                 (reset! received-otlp-options options)
                                 (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9196})
                  http/stop-server (fn [_] nil)]
      (let [result
            (h/run-test!
             {:name "oscope Durable checkpoint cadence"
              :database "" :verbosity :quiet :derandomize? true
              :test-cases 60}
             (fn [_]
               (let [every (h/draw! (g/integer 1 8))
                     steps (h/draw! (g/integer 1 32))
                     operations (atom [])
                     checkpoint-calls (atom 0)
                     lifecycle
                     (server/start!
                      {:port 0
                       :durability
                       {:checkpoint!
                        (fn [_]
                          (when (> (swap! checkpoint-calls inc) 1)
                            (swap! operations conj :checkpoint))
                          {:status :committed})
                        :flush! (fn [_]
                                  (swap! operations conj :flush)
                                  {:status :committed})
                        :checkpoint-every-batches every}})
                     after-success! (:after-success!
                                     @received-otlp-options)]
                 (try
                   (dotimes [_ steps] (after-success!))
                   (let [expected
                         (mapv #(if (zero? (mod % every))
                                  :checkpoint
                                  :flush)
                               (range 1 (inc steps)))]
                     (when-not (= expected @operations)
                       (throw
                        (ex-info "Durable checkpoint cadence drifted"
                                 {:hegel/origin
                                  "oscope/durable-checkpoint-cadence"}))))
                   (finally
                     (server/stop! lifecycle))))))]
        (is (:passed? result)
            (pr-str (select-keys result [:status :seed :failures :error])))
        (is (false? (:flaky? result)))))))
