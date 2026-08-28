(ns oscope.server-test
  (:require [clojure.test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.live :as live]
            [oscope.otlp :as otlp]
            [oscope.server :as server]
            [oscope.server-main :as server-main]
            [oscope.ui.events :as events]
            [oscope.ui.workbench :as workbench]
            [oscope.ui.web :as web]
            [otel.exporter.chdb :as chdb-export]
            [otel.sdk.export :as export]
            [otel.sdk.logs :as logs]))

(deftest route-composition-is-small-and-explicit
  (let [seen (atom [])
        h (server/handler
           {:otlp-handler (fn [r] (swap! seen conj [:otlp (:uri r)]) {:status 200})
            :oscope-handler (fn [r] (swap! seen conj [:oscope (:uri r)])
                              {:status 201})
            :visualization-editor-handler
            (fn [r] (swap! seen conj [:editor (:uri r)]) {:status 202})})
        request (fn [method uri]
                  {:request-method method :uri uri
                   :headers {"host" "127.0.0.1:4318"}})]
    (is (= 200 (:status (h (request :post "/v1/logs")))))
    (is (= 201 (:status (h (request :get "/oscope")))))
    (is (= 201 (:status (h (request :get "/oscope/export")))))
    (is (= 201 (:status (h (request :get "/oscope/refresh")))))
    (is (= 201 (:status (h (request :get "/oscope/live.js")))))
    (is (= 202 (:status (h (request :get "/oscope/edit/plotje")))))
    (is (= 202 (:status (h (request :post "/oscope/edit/hiccup/preview")))))
    (is (= 200 (:status (h (request :get "/healthz")))))
    (is (= 303 (:status (h (request :get "/")))))
    (is (= "/oscope" (get-in (h (request :get "/"))
                                [:headers "Location"])))
    (is (= 404 (:status (h (request :get "/missing")))))
    (is (= [[:otlp "/v1/logs"] [:oscope "/oscope"]
            [:oscope "/oscope/export"] [:oscope "/oscope/refresh"]
            [:oscope "/oscope/live.js"] [:editor "/oscope/edit/plotje"]
            [:editor "/oscope/edit/hiccup/preview"]]
           @seen))))

(deftest standalone-composition-routes-detailed-telemetry-surfaces
  (let [seen (atom [])
        h (server/handler
           {:otlp-handler (constantly {:status 200})
            :oscope-handler (constantly {:status 201})
            :workbench-handler
            (fn [request] (swap! seen conj [:workbench (:uri request)])
              {:status 202})
            :events-handler
            (fn [request] (swap! seen conj [:events (:uri request)])
              {:status 203})})
        request (fn [uri] {:request-method :get :uri uri
                           :headers {"host" "127.0.0.1:4318"}})]
    (is (= 202 (:status (h (request workbench/default-path)))))
    (is (= 202 (:status
                (h (request (str workbench/default-path
                                 "/traces/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))))))
    (is (= 203 (:status (h (request events/default-path)))))
    (is (= 203 (:status (h (request (str events/default-path "/refresh"))))))
    (is (= workbench/default-path
           (get-in (h (request "/")) [:headers "Location"])))
    (is (= [[:workbench workbench/default-path]
            [:workbench (str workbench/default-path
                             "/traces/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
            [:events events/default-path]
            [:events (str events/default-path "/refresh")]]
           @seen))))

(deftest standalone-rejects-untrusted-authorities-before-dispatch
  (let [seen (atom [])
        h (server/handler
           {:otlp-handler (fn [request]
                            (swap! seen conj [:otlp (:uri request)])
                            {:status 200})
            :oscope-handler (fn [request]
                              (swap! seen conj [:oscope (:uri request)])
                              {:status 200})
            :visualization-editor-handler
            (fn [request]
              (swap! seen conj [:editor (:uri request)])
              {:status 200})
            :authority "127.0.0.1:4318"})]
    (doseq [[method uri] [[:post "/v1/traces"]
                          [:post "/v1/logs"]
                          [:post "/v1/metrics"]
                          [:get "/oscope"]
                          [:get "/oscope/export"]
                          [:get "/oscope/refresh"]
                          [:get "/oscope/live.js"]
                          [:get "/oscope/edit/plotje"]
                          [:post "/oscope/edit/plotje/preview"]
                          [:get "/oscope/edit/hiccup"]
                          [:get "/oscope/edit/editor.js"]
                          [:get "/healthz"]
                          [:get "/"]]]
      (let [response (h {:request-method method :uri uri
                         :headers {"Host" "attacker.example"}})]
        (is (= 421 (:status response)))
        (is (= "close" (get-in response [:headers "Connection"])))))
    (is (= 421 (:status (h {:request-method :get :uri "/healthz"
                            :headers {}}))))
    (is (empty? @seen))))

(defn- fake-exporter
  ([events]
   (fake-exporter events (fn [face] (swap! events conj face))))
  ([_events retire!]
   (reify
     export/SpanExporter
     (export-spans! [_ _] true)
     (flush-exporter! [_] true)
     (shutdown-exporter! [_] (retire! :spans) true)
     export/MetricExporter
     (export-metrics! [_ _ _] true)
     (shutdown-metric-exporter! [_] (retire! :metrics) true)
     logs/LogRecordExporter
     (export-logs! [_ _] true)
     (shutdown-log-exporter! [_] (retire! :logs) true))))

(deftest standalone-shares-one-connection-and-retires-in-order
  (let [events (atom [])
        conn (reify java.io.Closeable
               (close [_] (swap! events conj :connection)))
        exporter (fake-exporter events)
        source {:close! #(swap! events conj :oscope)}
        seen-exporter-options (atom nil)
        seen-source-options (atom nil)]
    (with-redefs [jdbc/connection (fn [spec]
                                    (swap! events conj [:connection spec]) conn)
                  chdb-export/exporter (fn [opts]
                                         (reset! seen-exporter-options opts)
                                         exporter)
                  live/open! (fn [opts] (reset! seen-source-options opts) source)
                  otlp/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [_ & _]
                                    (swap! events conj :ingress-started)
                                    {:port 9191})
                  http/stop-server (fn [_] (swap! events conj :ingress-stopped))]
      (let [lifecycle (server/start! {:port 0 :db-spec "chdb:test"})]
        (is (= conn (:connection lifecycle)))
        (is (= {:connection conn :signals #{:spans :logs :metrics}}
               @seen-exporter-options))
        (is (= {:connection conn :ensure-schema? false} @seen-source-options))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
        (is (= [[:connection "chdb:test"] :ingress-started :ingress-stopped
                :oscope :spans :logs :metrics :connection]
               @events))))))

(deftest shutdown-stops-at-a-failed-boundary-and-retries-it
  (let [attempts (atom 0)
        events (atom [])
        conn (reify java.io.Closeable (close [_] (swap! events conj :connection)))
        exporter (fake-exporter events)
        source {:close! #(swap! events conj :oscope)}]
    (with-redefs [jdbc/connection (constantly conn)
                  chdb-export/exporter (constantly exporter)
                  live/open! (constantly source)
                  otlp/handler (fn [_] (constantly {:status 200}))
                  web/handler (fn [& _] (constantly {:status 200}))
                  http/run-server (fn [& _] {:port 9192})
                  http/stop-server
                  (fn [_]
                    (swap! events conj :ingress-stop-attempt)
                    (when (= 1 (swap! attempts inc))
                      (throw (ex-info "retry me" {}))))]
      (let [lifecycle (server/start! {:port 0})
            first-stop (server/stop! lifecycle)]
        (is (= :closing (:status first-stop)))
        (is (= :open (:phase first-stop)))
        (is (= 1 (count (:errors first-stop))))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
        (is (= [:ingress-stop-attempt :ingress-stop-attempt :oscope
                :spans :logs :metrics :connection]
               @events))))))

(deftest terminal-owner-drives-retryable-shutdown-or-fails-visibly
  (let [attempts (atom 0)]
    (with-redefs [server/stop!
                  (fn [_]
                    (if (< (swap! attempts inc) 3)
                      {:status :closing :phase :retiring-oscope
                       :errors [(ex-info "retry" {})]}
                      {:status :closed :phase :closed}))]
      (is (= {:status :closed :phase :closed}
             (server-main/stop-until-closed! ::lifecycle 3)))
      (is (= 3 @attempts))))
  (let [attempts (atom 0)
        error (with-redefs [server/stop!
                            (fn [_]
                              (swap! attempts inc)
                              {:status :closing :phase :closing-connection
                               :errors [(ex-info "still open" {})]})]
                (try
                  (server-main/stop-until-closed! ::lifecycle 2)
                  nil
                  (catch Throwable error error)))]
    (is (= 2 @attempts))
    (is (= 2 (:attempts (ex-data error))))
    (is (= :closing-connection (:phase (ex-data error))))
    (is (= 2 (count (:errors (ex-data error)))))))

(deftest terminal-owner-retries-every-owned-shutdown-boundary
  (doseq [failed-boundary [:oscope :spans :logs :metrics :connection]]
    (let [attempts (atom {})
          retire! (fn [boundary]
                    (let [attempt (get (swap! attempts update boundary
                                              (fnil inc 0)) boundary)]
                      (when (and (= failed-boundary boundary) (= 1 attempt))
                        (throw (ex-info "injected retirement failure"
                                        {:boundary boundary})))))
          conn (reify java.io.Closeable
                 (close [_] (retire! :connection)))
          exporter (fake-exporter (atom []) retire!)
          source {:close! #(retire! :oscope)}]
      (with-redefs [jdbc/connection (constantly conn)
                    chdb-export/exporter (constantly exporter)
                    live/open! (constantly source)
                    otlp/handler (fn [_] (constantly {:status 200}))
                    web/handler (fn [& _] (constantly {:status 200}))
                    http/run-server (fn [& _] {:port 9193})
                    http/stop-server (fn [_] nil)]
        (let [lifecycle (server/start! {:port 0})]
          (is (= {:status :closed :phase :closed}
                 (server-main/stop-until-closed! lifecycle 2))
              (str "terminal owner retries " failed-boundary))
          (is (= 2 (get @attempts failed-boundary))
              (str "failed boundary retried exactly once: " failed-boundary))
          (doseq [boundary [:oscope :spans :logs :metrics :connection]]
            (is (= (if (= boundary failed-boundary) 2 1)
                   (get @attempts boundary 0))
                (str "completed boundaries are not repeated while retrying "
                     failed-boundary ": " boundary))))))))
