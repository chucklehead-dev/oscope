(ns oscope.workbench-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [oscope.telemetry :as telemetry]
            [oscope.ui.workbench :as workbench]))

(def trace-id "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

(defn- with-model [f]
  (with-redefs [telemetry/query-summary (constantly
                                         {:traceCount 1 :spanCount 2
                                          :logCount 1 :errorCount 0})
                telemetry/query-traces (fn [& _]
                                         [{:traceId trace-id :rootSpan "GET /"
                                           :service "api" :spanCount 2
                                           :status "ok"}])
                telemetry/query-filter-options
                (fn [_ selection _]
                  {:selected selection :service-options []
                   :status-options [] :window-options []})
                telemetry/query-logs (constantly
                                      [{:body "ready" :service "api"
                                        :traceId trace-id}])
                telemetry/query-trace
                (fn [_ id]
                  {:traceId id :spanTree [{:spanId "root" :name "GET /"
                                           :children []}] :logs []})]
    (f)))

(deftest workbench-is-static-first-live-enhanced-and-mountable
  (with-model
    (fn []
      (let [handler (workbench/handler ::connection {:now-fn (constantly 10)})
            page (handler {:request-method :get :uri "/oscope/telemetry"})
            refresh (handler {:request-method :get
                              :uri "/oscope/telemetry/refresh"})
            live (handler {:request-method :get :uri "/oscope/telemetry/live.js"})]
        (is (= 200 (:status page)))
        (is (str/includes? (:body page) "Telemetry workbench"))
        (is (str/includes? (:body page) "Traces &amp; correlated logs"))
        (is (str/includes? (:body page) "Logs, metrics &amp; charts"))
        (is (str/includes? (:body page) (str "/oscope/telemetry/traces/" trace-id)))
        (is (str/includes? (:body page) "<script defer"))
        (is (str/includes? (:body refresh) "id=\"otel-live\""))
        (is (not (str/includes? (:body live) "eval(")))
        (is (not (str/includes? (:body live) "innerHTML")))
        (is (nil? (handler {:request-method :get :uri "/elsewhere"})))))))

(deftest detail-advice-is-host-owned-and-invalid-ids-do-not-dispatch
  (with-model
    (fn []
      (let [advised (atom [])
            handler (workbench/handler
                     ::connection
                     {:advise-trace #(do (swap! advised conj (:traceId %))
                                         (assoc % :advised true))})
            detail (handler {:request-method :get
                             :uri (str "/oscope/telemetry/traces/" trace-id)})]
        (is (= 200 (:status detail)))
        (is (= [trace-id] @advised))
        (is (nil? (handler {:request-method :get
                            :uri "/oscope/telemetry/traces/../../etc"})))))))
