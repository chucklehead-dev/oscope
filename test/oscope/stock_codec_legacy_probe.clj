(ns oscope.stock-codec-legacy-probe
  "Actual old Git component control, not a mocked missing Var."
  (:require [clojure.java.io :as io]
            [jdbc.core :as jdbc]
            [oscope.embedded :as embedded]
            [oscope.server :as server]))

(defn -main [& [mode]]
  (case mode
    "missing-codec"
    (do (assert (nil? (io/resource "jolt/codec/experimental.clj")))
        (assert (ifn? @(ns-resolve 'otel.exporter.chdb 'check-experimental-span-codec!))))
    nil
    (do (assert (.contains (str (io/resource "otel/exporter/chdb.clj"))
                          "0fba3865810d16b9abd544ed720a57a6e079365a"))
        (assert (nil? (ns-resolve 'otel.exporter.chdb 'check-experimental-span-codec!))))
    (throw (ex-info "Unknown unsupported codec probe mode" {})))
  (let [calls (atom 0)
        options {:db-spec {:vendor "chdb-durable"}
                 :durability {:checkpoint! identity :flush! identity}
                 :export-options {:insert-format :json-compact-each-row
                                  :json-backend :native-guarded-byte-batch
                                  :owned-statement-output? true
                                  :experimental-span-codec? true}}]
    (with-redefs [jdbc/connection (fn [& _] (swap! calls inc)
                                   (throw (Exception. "Unexpected storage")))]
      (doseq [start [embedded/start! server/start!]]
        (let [failure (try (start options) nil
                          (catch clojure.lang.ExceptionInfo e e))]
          (assert (= :oscope.export-options/span-codec-unavailable
                     (:type (ex-data failure)))))))
    (assert (zero? @calls)))
  (println (if (= mode "missing-codec")
             :actual-missing-codec-rejected-before-storage
             :actual-legacy-exporter-rejected-before-storage)))
