(ns oscope.embedded-stream-options-test
  (:require [clojure.test :refer [deftest is]] [oscope.embedded :as embedded]
            [oscope.json-backend :as backend] [jdbc.chdb.native :as native]
            [jdbc.core :as jdbc] [oscope.live :as live] [otel.sdk :as sdk]
            [otel.exporter.chdb :as exporter]))

(deftest reject-invalid-options-before-storage
  (let [opened (atom 0)]
    (with-redefs [jdbc/connection (fn [& _] (swap! opened inc))]
      (doseq [options [{:owned-statement-output? nil} {:owned-statement-output? "true"}
                       {:owned-statement-output? true}
                       {:datetime64-wire nil} {:datetime64-wire :guess}]]
        (is (thrown? clojure.lang.ExceptionInfo (embedded/start! (assoc options :db-spec ::durable)))))
      (with-redefs [native/ensure-loaded! (constantly nil) native/chdb-version (constantly "26.7.3")]
        (doseq [wire [:iso-utc :raw-ticks]]
          (is (thrown? clojure.lang.ExceptionInfo
                       (embedded/start! {:db-spec ::durable :datetime64-wire wire})))))
      (is (zero? @opened)))))

(deftest forwards-explicit-export-options-without-changing-defaults
  (let [seen (atom nil) connection (reify java.io.Closeable (close [_]))]
    (with-redefs [native/ensure-loaded! (constantly nil) native/chdb-version (constantly "26.9.0")
                  backend/validate! identity
                  sdk/tracer-provider (constantly nil) sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil) jdbc/connection (constantly connection)
                  exporter/exporter (fn [options] (reset! seen options) (throw (ex-info "stop after forwarding" {})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (embedded/start! {:db-spec ::durable :json-backend :native-guarded-byte-batch
                                     :insert-format :json-compact-each-row
                                     :owned-statement-output? true :datetime64-wire :raw-ticks})))
      (is (= true (:owned-statement-output? @seen)))
      (is (= :raw-ticks (:datetime64-wire @seen)))
      (is (= true (:durable? @seen)))
      (reset! seen nil)
      (is (thrown? clojure.lang.ExceptionInfo (embedded/start! {:db-spec ::durable})))
      (is (not (contains? @seen :owned-statement-output?)))
      (is (not (contains? @seen :datetime64-wire))))))
