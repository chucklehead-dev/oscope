(ns oscope.durable-cadence-integration-test
  "Real socket/checkpoint composition, separate from fake cadence properties."
  (:require [clojure.test :as test :refer [deftest is]]
            [jdbc.chdb.durable :as durable]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.local-posix :as local]
            [oscope.config :as config]
            [oscope.durable-config-runtime :as runtime]
            [oscope.durable-integration-test :as fixture]
            [oscope.durable-native-child-runner :as child]
            [oscope.server :as server])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *json-backend* :configured)
(def ^:dynamic *insert-format* :json-each-row)

(deftest logical-cadence-checkpoints-real-physical-inserts-before-response
  (let [root (Files/createTempDirectory "oscope-cadence-native-" (make-array FileAttribute 0))
        settled (atom false) operations (atom [])
        store (local/local-backend root)
        options (runtime/server-options
                 (config/resolve-config
                  [[:file {:version 2 :server {:port 0}
                           :ingest {:type :otlp-http-json :json-backend *json-backend*
                                    :insert-format *insert-format*}
                           :storage {:type :durable-local :root (str root)
                                     :owner "oscope-cadence" :instance "writer-1"
                                     :database "default" :lease-ttl-ms 30000
                                     :checkpoint-every-batches 2}}]]) {})
        wrap (fn [operation f]
               (fn [connection]
                 (let [result (f connection)]
                   (swap! operations conj [operation (:status result)]) result)))
        options (-> options
                    (assoc-in [:durability :checkpoint!] (wrap :checkpoint durable/checkpoint!))
                    (assoc-in [:durability :flush!] (wrap :flush durable/flush!)))]
    (try
      (let [lifecycle (server/start! options)]
        (try
          (reset! operations [])
          (let [now (* (System/currentTimeMillis) 1000000)
                initial-base (get-in (control/read-head! store) [:head "manifest" "base"])]
            (doseq [[index path payload]
                    [[0 "/v1/traces" (@#'fixture/trace-wire now)]
                     [1 "/v1/logs" (@#'fixture/log-wire now)]
                     [2 "/v1/metrics" (@#'fixture/metric-wire now)]]]
              (is (= 200 (@#'fixture/post-json! (:port lifecycle) path payload)))
              (is (= (inc index) (count @operations)) "barrier completes before HTTP response")
              (when (= index 1)
                (let [head (:head (control/read-head! store))]
                  (is (not= initial-base (get-in head ["manifest" "base"])))
                  (is (empty? (get-in head ["manifest" "wal"]))))))
            (is (= [:flush :checkpoint :flush] (mapv first @operations)))
            (is (every? #(contains? #{:empty :committed :reconciled} (second %)) @operations)))
          (is (= :closed (:status (server/stop! lifecycle))))
          (finally (server/stop! lifecycle))))
      (let [head (:head (control/read-head! store))]
        ;; The third logical request produced three independent metric inserts.
        (is (= 3 (count (get-in head ["manifest" "wal"]))))
        (is (nil? (get-in head ["lease" "owner"]))))
      (child/run-reader! :standalone root settled)
      (finally (when @settled (@#'fixture/delete-tree! root))))))

(defn -main [& _]
  (let [result (test/run-tests 'oscope.durable-cadence-integration-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
