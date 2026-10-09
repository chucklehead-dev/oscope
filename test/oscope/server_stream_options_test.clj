(ns oscope.server-stream-options-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.server :as server] [oscope.json-backend :as backend]
            [oscope.typed-schema :as typed] [oscope.http-executor :as workers]
            [jdbc.core :as jdbc] [jdbc.chdb.native :as native]
            [otel.exporter.chdb :as exporter]))

(deftest rejects-invalid-stream-options-before-acquisition
  (let [effects (atom [])]
    (with-redefs [jdbc/connection (fn [& _] (swap! effects conj :connection))
                  typed/install! (fn [& _] (swap! effects conj :ddl))
                  workers/start! (fn [& _] (swap! effects conj :workers))]
      (doseq [opts [{:owned-statement-output? nil} {:owned-statement-output? "true"}
                   {:owned-statement-output? true} {:datetime64-wire nil}
                   {:datetime64-wire :guess}]]
        (is (thrown? clojure.lang.ExceptionInfo (server/start! (assoc opts :port 0)))))
      (with-redefs [native/ensure-loaded! (constantly nil) native/chdb-version (constantly "26.7.3")]
        (doseq [wire [:iso-utc :raw-ticks]]
          (is (thrown? clojure.lang.ExceptionInfo (server/start! {:port 0 :datetime64-wire wire})))))
      (with-redefs [native/ensure-loaded! (constantly nil) native/chdb-version (constantly "26.9.0")]
        (doseq [db ["chdb::memory:" {:vendor "chdb-durable" :read-only? true}]
                opts [{:datetime64-wire :raw-ticks}
                      {:owned-statement-output? true :json-backend :native-guarded-byte-batch
                       :insert-format :json-compact-each-row}]]
          (is (thrown? clojure.lang.ExceptionInfo (server/start! (assoc opts :port 0 :db-spec db))))))
      (is (empty? @effects)))))

(deftest forwards-explicit-options-and-retains-omitted-defaults
  (let [seen (atom nil) connection (reify java.io.Closeable (close [_]))
        base {:port 0 :db-spec {:vendor "chdb-durable"}
              :durability {:checkpoint! (constantly {:status :committed})
                           :flush! (constantly {:status :committed})}}]
    (with-redefs [native/ensure-loaded! (constantly nil) native/chdb-version (constantly "26.9.0")
                  backend/validate! identity jdbc/connection (constantly connection)
                  workers/start! (constantly ::workers) workers/stop! (constantly true)
                  exporter/exporter (fn [options] (reset! seen options) (throw (ex-info "forwarding witness" {})))]
      (doseq [[owned wire] [[true :raw-ticks] [false :auto]]]
        (is (thrown? clojure.lang.ExceptionInfo
                     (server/start! (assoc base :json-backend :native-guarded-byte-batch
                                           :insert-format :json-compact-each-row
                                           :owned-statement-output? owned :datetime64-wire wire))))
        (is (= owned (:owned-statement-output? @seen)))
        (is (= wire (:datetime64-wire @seen)))
        (is (true? (:durable? @seen))))
      (is (thrown? clojure.lang.ExceptionInfo (server/start! base)))
      (is (not (contains? @seen :owned-statement-output?)))
      (is (not (contains? @seen :datetime64-wire))))))
