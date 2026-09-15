(ns minimal-embedded-app
  (:require [jdbc.chdb.durable :as durable]
            [jdbc.core :as jdbc]
            [oscope.embedded :as embedded]
            [oscope.embedded.query :as embedded-query]
            [oscope.live :as live]
            [oscope.typed-schema :as typed-schema]
            [otel.exporter.chdb :as chdb-export]
            [otel.sdk :as sdk]))

(defn- require! [condition message]
  (when-not condition
    (throw (ex-info message {}))))

(defn -main [& _]
  (let [sdk-starts (atom 0)
        sdk-stops (atom 0)
        schema-installs (atom 0)
        source-closes (atom 0)
        connection-closes (atom 0)
        descriptor-set (Object.)
        exporter-options (atom nil)
        connection (reify java.io.Closeable
                     (close [_] (swap! connection-closes inc)))]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  durable/connection-role (constantly :writer)
                  typed-schema/validate-options (constantly nil)
                  typed-schema/install!
                  (fn [_ _]
                    (swap! schema-installs inc)
                    {:descriptor-set descriptor-set})
                  typed-schema/descriptor-options
                  (constantly {:typed-span-descriptors descriptor-set})
                  chdb-export/exporter (fn [options]
                                         (reset! exporter-options options)
                                         ::exporter)
                  live/open! (constantly {:close! #(swap! source-closes inc)})
                  sdk/init! (fn [_]
                              (swap! sdk-starts inc)
                              ::sdk-owner)
                  sdk/shutdown! (fn [_]
                                  (swap! sdk-stops inc)
                                  true)
                  durable/checkpoint! (constantly {:status :committed})]
      (let [runtime (embedded/start! {:db-spec ::durable
                                      :typed-schema {:approved-manifest ::approved
                                                     :registry-backend ::registry}
                                      :sdk-options {:metrics? false
                                                    :logs? false}})
            query (embedded-query/start! {:load! (constantly [])
                                          :interval-ms 60000})]
        (require! (= 1 @sdk-starts) "fixture must start exactly one SDK owner")
        (require! (= 1 @schema-installs)
                  "fixture must install or acquire its typed schema once")
        (require! (identical? descriptor-set
                              (:typed-span-descriptors @exporter-options))
                  "confirmed descriptors did not reach the local exporter")
        (require! (nil? (find-ns 'oscope.server))
                  "minimal fixture loaded the server namespace")
        (require! (nil? (find-ns 'oscope.embedded.viewer))
                  "minimal fixture loaded the viewer namespace")
        (require! (= :closed (:status (embedded-query/stop! query)))
                  "bounded query helper did not retire")
        (require! (= :closed (:status (embedded/stop! runtime)))
                  "embedded lifecycle did not close")
        (embedded/stop! runtime)
        (require! (= [1 1 1 1]
                     [@sdk-starts @sdk-stops @source-closes @connection-closes])
                  "owned resources were not closed exactly once")))
    (println "minimal embedded fixture: PASS")))
