(ns oscope.embedded-test
  (:require [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable]
            [jdbc.core :as jdbc]
            [oscope.embedded :as embedded]
            [oscope.live :as live]
            [otel.exporter.chdb :as chdb-export]
            [otel.sdk :as sdk]))

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
  (is (thrown? Exception (embedded/stop! nil)))
  (is (thrown? Exception (embedded/force-flush! {}))))
