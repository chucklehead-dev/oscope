(ns oscope.embedded-durable-integration-test
  (:require [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable.backend :as backend]
            [jdbc.core :as jdbc]
            [oscope.embedded :as embedded]
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
