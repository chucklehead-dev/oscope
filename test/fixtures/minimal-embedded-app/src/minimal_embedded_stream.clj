(ns minimal-embedded-stream
  "Experimental streaming variant of the minimal application fixture."
  (:require [minimal-embedded-app :as ordinary]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.native :as native]
            [jdbc.core :as jdbc]
            [oscope.embedded :as embedded]))

(defn- typed-readback! [connection]
  (let [field (first (:fields (@#'ordinary/approved-manifest)))
        value-column (get-in field [:physical :value-column])
        status-column (get-in field [:physical :status-column])]
      ;; These independently expected values are read from the physical table,
      ;; not inferred from a count, installer receipt, or successful flush.
      (assert (= [{:span_name "minimal embedded fixture" :typed_value true
                   :typed_status 3 :generic_value "true"}]
                 (jdbc/fetch connection
                             (str "SELECT SpanName AS span_name, `" value-column
                                  "` AS typed_value, `" status-column
                                  "` AS typed_status, SpanAttributes['fixture.confirmed']"
                                  " AS generic_value FROM otel_traces"))))))

(defn -main [& args]
  (let [[mode] args
        streams (atom 0)
        writer-spec durable/writer-dbspec
        start embedded/start!
        stream native/chdb-stream-insert-n
        reader @#'ordinary/run-reader!]
    (assert (contains? #{"writer" "reader" "mutant"} mode))
    (with-redefs
      [ordinary/run-reader!
       (fn [root seal marker] (reader root seal marker typed-readback!))
       durable/writer-dbspec
       (fn [options] (writer-spec (assoc options :owned-compact-stream? true)))
       embedded/start!
       (fn [options]
         (start (assoc options :json-backend :native-guarded-byte-batch
                               :insert-format :json-compact-each-row
                               :owned-statement-output? true
                               :datetime64-wire :raw-ticks)))
       native/chdb-stream-insert-n
       (fn [& values] (swap! streams inc) (apply stream values))]
      (apply ordinary/-main args)
      (case mode
        "writer" (assert (= 1 @streams))
        "reader" (assert (zero? @streams))
        "mutant" (assert (zero? @streams))))
    ;; A fixed marker, not a payload/status/path or native response dump.
    (println "minimal embedded stream fixture: PASS")))
