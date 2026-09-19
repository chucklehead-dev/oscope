(ns time-provider-durable.reader
  "Independently compiled Durable snapshot reader for Oscope #119 qualification."
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
  (require! (seq root) "reader requires a private run root")
  (deterministic-time!)
  (let [store (local-posix/local-backend (str root "/objects"))]
    (with-open [connection
                (jdbc/connection
                 (durable/snapshot-dbspec
                  {:backend store :scratch-parent root}))]
      (require! (= [{:id 7 :body "fixed"}]
                   (jdbc/fetch connection "SELECT id, body FROM fixture_rows"))
                "fresh snapshot reader did not recover fixed relation/row"))))
