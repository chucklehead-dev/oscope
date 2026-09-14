(ns oscope.embedded-viewer-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.http.server :as http]
            [oscope.embedded.viewer :as viewer]
            [oscope.http-executor :as http-executor]
            [oscope.ui.events :as events]
            [oscope.ui.visualization-editor :as visualization-editor]
            [oscope.ui.web :as web]
            [oscope.ui.workbench :as workbench]
            [otel.otlp.http-receiver :as receiver]))

(deftest viewer-only-borrows-owner-and-excludes-otlp-routes
  (let [events-seen (atom [])
        owner-events (atom [])
        app-handler (atom nil)
        run-options (atom nil)
        connection (reify java.io.Closeable
                     (close [_] (swap! owner-events conj :connection-close)))
        source {:close! #(swap! owner-events conj :source-close)}
        executor {:executor ::executor}
        owner {:connection connection :source source}]
    (with-redefs [http-executor/start!
                  (fn [options]
                    (swap! events-seen conj [:workers-start options])
                    executor)
                  http-executor/stop!
                  (fn [actual]
                    (is (identical? executor actual))
                    (swap! events-seen conj :workers-stop)
                    true)
                  workbench/handler
                  (fn [actual]
                    (is (identical? connection actual))
                    (constantly {:status 201}))
                  events/handler (fn [_] (constantly {:status 202}))
                  web/handler
                  (fn [actual _]
                    (is (identical? source actual))
                    (constantly {:status 203}))
                  visualization-editor/handler
                  (fn [actual]
                    (is (identical? source actual))
                    (constantly {:status 204}))
                  http/run-server
                  (fn [handler & options]
                    (reset! app-handler handler)
                    (reset! run-options (apply hash-map options))
                    (swap! events-seen conj :listener-start)
                    {:port 9187})
                  http/stop-server
                  (fn [_] (swap! events-seen conj :listener-stop))]
      (let [viewer-lifecycle (viewer/start! owner)
            request (fn [method uri host]
                      (@app-handler {:request-method method :uri uri
                                     :headers {"Host" host}}))]
        (is (= "http://127.0.0.1:9187/oscope/telemetry"
               (:url viewer-lifecycle)))
        (is (= {:port 0 :server-name "127.0.0.1"
                :reuse-address? true :executor ::executor}
               @run-options))
        (doseq [path receiver/receiver-paths]
          (is (= 404 (:status (request :post path "127.0.0.1:9187")))
              (str path " is absent from the viewer-only route table")))
        (is (= 201 (:status (request :get "/oscope/telemetry"
                                     "127.0.0.1:9187"))))
        (is (= 421 (:status (request :get "/oscope/telemetry"
                                     "attacker.example"))))
        (is (= 421 (:status
                    (@app-handler {:request-method :get
                                   :uri "/oscope/telemetry"
                                   :headers {}}))))
        (is (= {:status :closed :phase :closed}
               (viewer/stop! viewer-lifecycle)))
        (is (= {:status :closed :phase :closed}
               (viewer/stop! viewer-lifecycle)))
        (is (= [[:workers-start {:workers 2 :queue-capacity 8}]
                :listener-start :listener-stop :workers-stop]
               @events-seen))
        (is (empty? @owner-events))
        (is (identical? connection (:connection owner)))
        (is (identical? source (:source owner)))))))

(deftest viewer-only-rolls-back-and-retries-only-incomplete-boundaries
  (let [started? (atom false)]
    (with-redefs [http-executor/start! (fn [_] (reset! started? true))]
      (is (thrown? Exception
                   (viewer/start!
                    {:source ::source :connection ::connection}
                    {:host "localhost"})))
      (is (false? @started?))))
  (let [events-seen (atom [])
        executor {:executor ::executor}]
    (with-redefs [http-executor/start! (fn [_] executor)
                  http-executor/stop! (fn [_]
                                        (swap! events-seen conj :workers-stop)
                                        true)
                  workbench/handler (fn [_] (constantly {:status 200}))
                  events/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  visualization-editor/handler
                  (fn [_] (constantly {:status 200}))
                  http/run-server
                  (fn [& _]
                    (swap! events-seen conj :listener-start)
                    {:port nil})
                  http/stop-server
                  (fn [_] (swap! events-seen conj :listener-stop))]
      (is (thrown? Exception
                   (viewer/start!
                    {:source ::source :connection ::connection})))
      (is (= [:listener-start :listener-stop :workers-stop] @events-seen))))
  (let [attempts (atom 0)
        events-seen (atom [])
        executor {:executor ::executor}]
    (with-redefs [http-executor/start! (constantly executor)
                  http-executor/stop! (fn [_]
                                        (swap! events-seen conj :workers-stop)
                                        true)
                  workbench/handler (fn [_] (constantly {:status 200}))
                  events/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  visualization-editor/handler
                  (fn [_] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9188})
                  http/stop-server
                  (fn [_]
                    (swap! events-seen conj :listener-stop)
                    (when (= 1 (swap! attempts inc))
                      (throw (ex-info "retry" {}))))]
      (let [viewer-lifecycle
            (viewer/start! {:source ::source :connection ::connection})]
        (is (= {:status :closing
                :phase :open
                :errors [{:type :oscope.error/lifecycle-operation-threw
                          :operation :stop-listener}]}
               (viewer/stop! viewer-lifecycle)))
        (is (= {:status :closed :phase :closed}
               (viewer/stop! viewer-lifecycle)))
        (is (= [:listener-stop :listener-stop :workers-stop] @events-seen))))))

(deftest viewer-only-retries-a-transient-query-worker-stop
  (let [attempts (atom 0)
        events-seen (atom [])
        executor {:executor ::executor}]
    (with-redefs [http-executor/start! (constantly executor)
                  http-executor/stop!
                  (fn [_]
                    (swap! events-seen conj :workers-stop)
                    (when (= 1 (swap! attempts inc))
                      (throw (ex-info "retry" {})))
                    true)
                  workbench/handler (fn [_] (constantly {:status 200}))
                  events/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  visualization-editor/handler
                  (fn [_] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9189})
                  http/stop-server
                  (fn [_] (swap! events-seen conj :listener-stop))]
      (let [viewer-lifecycle
            (viewer/start! {:source ::source :connection ::connection})]
        (is (= {:status :closing
                :phase :stopping-query-workers
                :errors [{:type :oscope.error/lifecycle-operation-threw
                          :operation :stop-query-workers}]}
               (viewer/stop! viewer-lifecycle)))
        (is (= {:status :closed :phase :closed}
               (viewer/stop! viewer-lifecycle)))
        (is (= [:listener-stop :workers-stop :workers-stop] @events-seen))))))
