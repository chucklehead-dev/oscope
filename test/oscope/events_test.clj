(ns oscope.events-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oscope.telemetry :as telemetry]
            [oscope.ui.events :as events]))

(deftest event-explorer-is-mountable-static-first-and-live-enhanced
  (let [seen (atom [])]
    (with-redefs
      [telemetry/query-events
       (fn [_ selection now]
         (swap! seen conj [selection now])
         (if (= :logs (:signal selection))
           [{:timestamp "now" :severity "ERROR" :service "api"
             :body "request timed out"
             :traceId "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]
           [{:timestamp "now" :kind "gauge" :service "worker"
             :name "queue.depth" :value 4.0 :unit "1"}]))]
      (let [handler (events/handler ::connection {:now-fn (constantly 1000)})
            logs (handler {:request-method :get :uri "/oscope/events"
                           :query-string "signal=logs&severity=ERROR&search=timeout&limit=7"})
            metrics (handler {:request-method :get :uri "/oscope/events/refresh"
                              :query-string "signal=metrics&metric-kind=gauge&limit=9"})
            asset (handler {:request-method :get :uri "/oscope/events/live.js"})]
        (is (= 200 (:status logs)))
        (is (str/includes? (:body logs) "request timed out"))
        (is (str/includes? (:body logs)
                           "/oscope/telemetry/traces/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
        (is (str/includes? (:body logs) "Charts &amp; distributions"))
        (is (str/includes? (:body logs) "<script defer"))
        (is (= 200 (:status metrics)))
        (is (str/includes? (:body metrics) "queue.depth"))
        (is (not (str/includes? (:body metrics) "<!doctype")))
        (is (not (str/includes? (:body asset) "eval(")))
        (is (not (str/includes? (:body asset) "innerHTML")))
        (is (= :logs (get-in @seen [0 0 :signal])))
        (is (= "ERROR" (get-in @seen [0 0 :severity])))
        (is (= 7 (get-in @seen [0 0 :limit])))
        (is (= :metrics (get-in @seen [1 0 :signal])))
        (is (nil? (handler {:request-method :get :uri "/elsewhere"})))))))

(deftest malformed-event-query-values-fall-back-without-broadening-bounds
  (is (= {:signal :logs :service "" :search "" :severity ""
          :metric-kind "" :window "1h" :limit 50}
         (events/selection-from-query
          "signal=unknown&severity=ROOT&window=forever&limit=999"))))
