(ns oscope.chdb-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [oscope.live :as live]))

(def test-now 1700000001000000000)
(deftest owned-live-source-migrates-queries-and-closes
  (let [source (live/open! {:db-spec "chdb::memory:"
                            :now-fn (constantly test-now)})]
    (try
      (is (:owned? source))
      (is (= :empty (get-in source [:screen :status])))
      (is (= [1 2 3 4]
             (mapv :version
                   (jdbc/fetch (:connection source)
                               "select Version as version from otel_schema_migrations order by Version"))))
      (doseq [service ["api" "api" "worker"]]
        (jdbc/execute!
         (:connection source)
         ["INSERT INTO otel_traces (Timestamp, ServiceName, SpanName)
             VALUES (fromUnixTimestamp64Nano(?), ?, 'live.work')"
          (- test-now 1000000) service]))
      (let [screen ((:load-command source) :after-insert
                    {:signal :spans :field :service-name :window :15m :limit 10})]
        (is (= [{:value "api" :count 2} {:value "worker" :count 1}]
               (get-in screen [:table :rows]))))
      (finally (live/close! source)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"source is closed"
                          ((:loader source) (:selection (:screen source)))))))

(deftest shared-real-connection-remains-usable
  (with-open [connection (jdbc/connection "chdb::memory:")]
    (let [source (live/open! {:connection connection
                              :now-fn (constantly test-now)})]
      (live/close! source)
      (is (= [{:answer 1}] (jdbc/fetch connection "select 1 as answer"))))))
