(ns time-provider-durable.producer
  "Independently compiled Durable writer for Oscope #119 qualification."
  (:require [db.jdbc]
            [jolt.time]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter]
           [java.util Locale]))

(def ^:private expected-time "2024 January 02")

(defn- require! [predicate message]
  (when-not predicate (throw (ex-info message {}))))

(defn- deterministic-time! []
  (let [formatter (DateTimeFormatter/ofPattern "yyyy MMMM dd" Locale/US)]
    (require! (= expected-time (.format formatter (LocalDate/of 2024 1 2)))
              "jolt.time Locale formatting changed")))

(defn -main [& [root]]
  (require! (seq root) "producer requires a private run root")
  (deterministic-time!)
  (let [store (local-posix/local-backend (str root "/objects"))]
    (with-open [connection
                (jdbc/connection
                 (durable/writer-dbspec
                  {:backend store
                   :owner "oscope-time-provider-fixture"
                   :instance "oscope-time-provider-producer"
                   :database "default"
                   :scratch-parent root
                   :lease-ttl-ms 30000}))]
      (jdbc/execute! connection
                     "CREATE TABLE fixture_rows (id UInt64, body String) ENGINE = MergeTree ORDER BY id")
      (jdbc/execute! connection "INSERT INTO fixture_rows VALUES (7, 'fixed')")
      (require! (= [{:id 7 :body "fixed"}]
                   (jdbc/fetch connection "SELECT id, body FROM fixture_rows"))
                "writer did not retain its fixed relation/row")
      (require! (= :committed (:status (durable/flush! connection)))
                "writer did not checkpoint Durable state"))))
