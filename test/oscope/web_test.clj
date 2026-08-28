(ns oscope.web-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.sample :as sample]
            [oscope.ui.web :as web]))

(deftest ring-handler-is-zero-js-bounded-and-embeddable
  (let [calls (atom [])
        source {:load-command
                (fn [request-id selection]
                  (swap! calls conj [request-id selection])
                  (sample/screen-for-selection selection))}
        handler (web/handler source)
        response (handler {:request-method :get :uri "/oscope"
                           :query-string "signal=logs&field=severity-text&window=15m&limit=3"})]
    (is (= 200 (:status response)))
    (is (= {:signal :logs :field :severity-text :window :15m :limit 3}
           (second (first @calls))))
    (is (re-find #"Severity Text in Logs" (:body response)))
    (is (not (re-find #"<script" (:body response))))
    (is (re-find #"form-action 'self'" (get-in response [:headers "Content-Security-Policy"])))
    (is (nil? (handler {:request-method :get :uri "/elsewhere"})))
    (is (= 405 (:status (handler {:request-method :post :uri "/oscope"}))))))

(deftest ring-handler-can-be-mounted-below-a-host-prefix
  (let [source {:load-command (fn [_ selection]
                                (sample/screen-for-selection selection))}
        handler (web/handler source {:path "/admin/telemetry"})
        response (handler {:request-method :get :uri "/admin/telemetry"})]
    (is (= 200 (:status response)))
    (is (re-find #"<form method=\"get\" action=\"/admin/telemetry\""
                 (:body response)))
    (is (nil? (handler {:request-method :get :uri "/oscope"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (web/handler source {:path "https://example.invalid/oscope"})))))

(deftest malformed-and-unsupported-query-values-fall-back
  (let [selection (web/selection-from-params
                   {"signal" "not-a-signal" "field" "sql"
                    "window" "forever" "limit" "999999"})]
    (is (= {:signal :spans :field :service-name :window :1h :limit 12}
           selection))))
