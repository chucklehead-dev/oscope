(ns oscope.embedded-durable-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [oscope.embedded :as embedded]
            [oscope.embedded.query :as embedded-query]
            [oscope.live :as live]
            [oscope.sample-emitter :as sample]))

(defn- values [source request-id signal field]
  (set
   (map :value
        (get-in
         ((:load-command source)
          request-id
          {:signal signal :field field :window :15m :limit 20})
         [:table :rows]))))

(defn- assert-signals-visible! [source suffix]
  (is (contains? (values source (keyword (str "spans-" suffix))
                         :spans :span-name)
                 "POST /checkout"))
  (is (contains? (values source (keyword (str "logs-" suffix))
                         :logs :severity-text)
                 "INFO"))
  (is (= #{"sample.checkout.requests"
           "sample.checkout.queue.depth"
           "sample.checkout.duration"}
         (values source (keyword (str "metrics-" suffix))
                 :metrics :metric-name))))

(defn- await-ready [lifecycle]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [snapshot (embedded-query/snapshot lifecycle)]
        (cond
          (= :ready (:status snapshot)) snapshot
          (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 5) (recur))
          :else snapshot)))))

(deftest direct-sdk-exports-survive-a-fresh-durable-reader
  (let [store (backend/memory-backend)
        db-spec {:vendor "chdb-durable"
                 :backend store
                 :owner "oscope-embedded-test"
                 :instance "oscope-embedded-test-instance"
                 :database "default"
                 :lease-ttl-ms 30000}
        lifecycle
        (embedded/start!
         {:db-spec db-spec
          :sdk-options {:service-name "oscope-embedded-test"
                        :processor :simple
                        :metrics? true
                        :runtime-metrics? false
                        :logs? true
                        :bridge-logging? false}})]
    (try
      (sample/emit-scenario!)
      (is (true? (embedded/force-flush! lifecycle)))
      (assert-signals-visible! (:source lifecycle) "writer")
      ;; This is the real counted-lock regression: the cadence runs on a Jolt
      ;; fiber, but the live source/JDBC query must execute on the facade's OS
      ;; thread rather than attempting to park the fiber while locks are held.
      (let [query-lifecycle
            (embedded-query/start!
             {:load! #(get-in
                       ((:load-command (:source lifecycle))
                        :embedded-background-query
                        {:signal :spans :field :span-name
                         :window :15m :limit 20})
                       [:table :rows])
              :interval-ms 1000 :timeout-ms 2000 :stop-timeout-ms 2000})]
        (try
          (let [snapshot (await-ready query-lifecycle)]
            (is (= :ready (:status snapshot)))
            (is (some #(= "POST /checkout" (:value %)) (:rows snapshot))))
          (finally
            ;; The query thread is joined before embedded/stop! retires source.
            (is (= :closed (:status
                            (embedded-query/stop! query-lifecycle)))))))
      (is (= {:status :closed :phase :closed}
             (embedded/stop! lifecycle)))
      (with-open [reader (jdbc/connection
                          {:vendor "chdb-durable"
                           :backend store
                           :read-only? true})]
        (let [source (live/open! {:connection reader :ensure-schema? false})]
          (try
            (assert-signals-visible! source "reader")
            (finally
              (live/close! source)))))
      (finally
        (embedded/stop! lifecycle)))))
