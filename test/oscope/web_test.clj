(ns oscope.web-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.http.body :as http-body]
            [oscope.raw-export :as raw-export]
            [oscope.sample :as sample]
            [oscope.ui.web :as web]))

(defn- result [selection bytes]
  (merge {:oscope.export/version 1 :selection selection
          :byte-count (count bytes) :bytes bytes}
         (raw-export/response-metadata selection)))

(defn- drain! [body]
  (let [written (atom [])
        sink (reify http-body/Sink
               (sink-write! [_ bytes offset length]
                 (swap! written into (mapv #(bit-and % 255)
                                           (take length (drop offset bytes)))))
               (sink-close! [_]))]
    (http-body/write-body-to-sink body {} sink)
    @written))

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

(deftest live-refresh-is-opt-in-bounded-and-freezes-the-displayed-plan
  (let [calls (atom [])
        source {:load-command
                (fn [request-id selection]
                  (swap! calls conj [request-id selection])
                  (sample/screen-for-selection selection))
                :export-command (fn [& _] (throw (Exception. "not called")))}
        handler (web/handler source)
        plain (handler {:request-method :get :uri "/oscope"})
        live (handler {:request-method :get :uri "/oscope"
                       :query-string
                       "signal=logs&field=severity-text&window=15m&limit=3&live=1"})
        refresh (handler {:request-method :get :uri "/oscope/refresh"
                          :query-string
                          "signal=logs&field=severity-text&window=15m&limit=3"})
        asset (handler {:request-method :get :uri "/oscope/live.js"})
        screen (sample/screen-for-selection
                {:signal :logs :field :severity-text :window :15m :limit 3})
        start (get-in screen [:query-plan :request :start-unix-nano])
        end (get-in screen [:query-plan :request :end-unix-nano])]
    (is (not (re-find #"<script" (:body plain)))
        "the default page remains fully static and no-JavaScript")
    (is (re-find #"name=\"live\" type=\"checkbox\" value=\"1\""
                 (:body plain)))
    (is (re-find #"<script defer src=\"/oscope/live.js\"></script>"
                 (:body live)))
    (is (re-find #"data-oscope-refresh-path=\"/oscope/refresh\""
                 (:body live)))
    (is (re-find #"data-oscope-freeze" (:body live)))
    (is (re-find (re-pattern (str "data-oscope-query-start=\"" start "\""))
                 (:body live)))
    (is (re-find (re-pattern (str "data-oscope-query-end=\"" end "\""))
                 (:body live)))
    (is (re-find (re-pattern (str "data-oscope-export-start type=\"number\" min=\"0\" value=\""
                                  start "\""))
                 (:body live)))
    (is (re-find (re-pattern (str "data-oscope-export-end type=\"number\" min=\"1\" value=\""
                                  end "\""))
                 (:body live)))
    (is (= 200 (:status refresh)))
    (is (not (re-find #"<!doctype" (:body refresh))))
    (is (re-find #"^<section id=\"oscope-screen\" data-oscope-view-version=\"1\""
                 (:body refresh)))
    (is (re-find #"Severity Text in Logs" (:body refresh)))
    (is (= "text/javascript; charset=UTF-8"
           (get-in asset [:headers "Content-Type"])))
    (is (re-find #"if\(inFlight\|\|!live\|\|document.hidden\)return"
                 (:body asset))
        "one refresh may be in flight and hidden tabs do no work")
    (is (re-find #"AbortController" (:body asset)))
    (is (re-find #"mine!==generation" (:body asset))
        "a frozen or cancelled request cannot install a late screen")
    (is (re-find #"Math.min\(backoffMs\*2,maxBackoffMs\)" (:body asset)))
    (is (re-find #"form.querySelector\('\[data-oscope-export-start\]'\).value=screen.dataset.oscopeQueryStart"
                 (:body asset)))
    (is (re-find #"form.querySelector\('\[data-oscope-export-end\]'\).value=screen.dataset.oscopeQueryEnd"
                 (:body asset)))
    (is (not (re-find #"eval\(|innerHTML" (:body asset))))
    (is (= 405 (:status (handler {:request-method :post
                                  :uri "/oscope/refresh"}))))
    (is (= 405 (:status (handler {:request-method :post
                                  :uri "/oscope/live.js"}))))
    (is (= [:web :web :web-refresh]
           (mapv (comp first first) @calls))
        "the asset is static and only page/refresh execute bounded queries")))

(deftest ring-handler-can-be-mounted-below-a-host-prefix
  (let [source {:load-command (fn [_ selection]
                                (sample/screen-for-selection selection))}
        handler (web/handler source {:path "/admin/telemetry"})
        response (handler {:request-method :get :uri "/admin/telemetry"})]
    (is (= 200 (:status response)))
    (is (re-find #"<form method=\"get\" action=\"/admin/telemetry\""
                 (:body response)))
    (is (re-find #"action=\"/admin/telemetry/export\"" (:body response)))
    (is (= "/admin/telemetry/refresh"
           (web/refresh-path "/admin/telemetry")))
    (is (= "/admin/telemetry/live.js"
           (web/live-asset-path "/admin/telemetry")))
    (is (web/handled-path? "/admin/telemetry"
                           "/admin/telemetry/refresh"))
    (is (web/handled-path? "/admin/telemetry"
                           "/admin/telemetry/live.js"))
    (is (re-find #"Raw export is unavailable in sample mode" (:body response)))
    (is (nil? (handler {:request-method :get :uri "/oscope"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (web/handler source {:path "https://example.invalid/oscope"})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (web/handler source {:path "/admin/"})))))

(deftest malformed-and-unsupported-query-values-fall-back
  (let [selection (web/selection-from-params
                   {"signal" "not-a-signal" "field" "sql"
                    "window" "forever" "limit" "999999"})]
    (is (= {:signal :spans :field :service-name :window :1h :limit 12}
           selection))))

(deftest live-screen-links-to-an-exact-current-chart-editor-selection
  (let [source {:load-command (fn [_ selection]
                                (sample/screen-for-selection selection))}
        handler (web/handler
                 source {:visualization-editor-path "/oscope/edit/plotje"})
        response (handler {:request-method :get :uri "/oscope"
                           :query-string
                           "signal=logs&field=severity-text&window=15m&limit=3"})]
    (is (= 200 (:status response)))
    (is (re-find
         #"href=\"/oscope/edit/plotje\?signal=logs&amp;field=severity-text&amp;window=15m&amp;limit=3\""
         (:body response)))
    (is (re-find #"aria-label=\"Visualization utilities\"" (:body response)))))

(deftest export-route-is-closed-binary-and-derived-from-the-mount
  (let [calls (atom [])
        bytes (byte-array [(byte 65) (byte 82) (byte 82) (byte 79)
                           (byte 87) (byte 49)])
        source {:load-command (fn [_ selection]
                                (sample/screen-for-selection selection))
                :export-command
                (fn [request-id selection]
                  (swap! calls conj [request-id selection])
                  (result selection bytes))}
        handler (web/handler source {:path "/admin/telemetry"})
        response (handler
                  {:request-method :get :uri "/admin/telemetry/export"
                   :query-params
                   {"signal" "metrics" "metric-kind" "sum" "format" "arrow"
                    "start-unix-nano" "1700000000000000000"
                    "end-unix-nano" "1700000001000000000"
                    "max-rows" "5" "max-bytes" "4096"}})]
    (is (= 200 (:status response)))
    (is (= [65 82 82 79 87 49] (drain! (:body response))))
    (is (= "application/vnd.apache.arrow.file"
           (get-in response [:headers "Content-Type"])))
    (is (= "6" (get-in response [:headers "Content-Length"])))
    (is (= (str "attachment; filename=\""
                "oscope-metrics-sum-1700000000000000000-1700000001000000000.arrow\"")
           (get-in response [:headers "Content-Disposition"])))
    (is (= {:signal :metrics :metric-kind :sum
            :start-unix-nano 1700000000000000000
            :end-unix-nano 1700000001000000000
            :format :arrow :max-rows 5 :max-bytes 4096}
           (second (first @calls))))))

(deftest export-admission-is-held-until-the-body-drains-or-fails
  (let [bytes (byte-array [(byte 80) (byte 65) (byte 82) (byte 49)])
        admission {:capacity 1 :active (atom 0)}
        source {:load-command (fn [_ selection]
                                (sample/screen-for-selection selection))
                :export-admission admission
                :export-command (fn [_ selection] (result selection bytes))}
        handler (web/handler source)
        request {:request-method :get :uri "/oscope/export"
                 :query-params
                 {"signal" "spans" "format" "parquet"
                  "start-unix-nano" "1700000000000000000"
                  "end-unix-nano" "1700000001000000000"
                  "max-rows" "5" "max-bytes" "4096"}}
        first-response (handler request)]
    (is (= 200 (:status first-response)))
    (is (= 1 @(:active admission)))
    (is (= 503 (:status (handler request))))
    (is (= "1" (get-in (handler request) [:headers "Retry-After"])))
    (is (= [80 65 82 49] (drain! (:body first-response))))
    (is (zero? @(:active admission)))
    (let [failed-response (handler request)
          failing-sink
          (reify http-body/Sink
            (sink-write! [_ _ _ _]
              (throw (ex-info "client disconnected" {:test/error true})))
            (sink-close! [_]))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (http-body/write-body-to-sink
                    (:body failed-response) failed-response failing-sink)))
      (is (zero? @(:active admission))))
    (let [discarded (handler request)]
      (is (= 1 @(:active admission)))
      (web/release-export-body! (:body discarded))
      (web/release-export-body! (:body discarded))
      (is (zero? @(:active admission))))
    (let [failed-handler
          (web/handler
           (assoc source :export-command
                  (fn [& _] (throw (java.sql.SQLException. "driver failed")))))]
      (is (= 500 (:status (failed-handler request))))
      (is (zero? @(:active admission))
          "driver failures release admission before returning"))))

(deftest export-route-rejects-ingress-and-sample-mode-remains-safe
  (let [sample (web/sample-handler)
        unavailable (sample {:request-method :get :uri "/oscope/export"})]
    (is (= 404 (:status unavailable)))
    (is (= 405 (:status (sample {:request-method :post
                                 :uri "/oscope/export"}))))
    (let [handler (web/handler
                   {:load-command (fn [_ selection]
                                    (sample/screen-for-selection selection))
                    :export-command (fn [& _] (throw (Exception. "not called")))})
          base {"signal" "spans" "metric-kind" "gauge" "format" "parquet"
                "start-unix-nano" "1700000000000000000"
                "end-unix-nano" "1700000001000000000"
                "max-rows" "5" "max-bytes" "4096"}]
      (doseq [extra ["sql" "table" "column" "path" "filename"]]
        (is (= 400
               (:status (handler {:request-method :get :uri "/oscope/export"
                                  :query-params (assoc base extra "attacker input")})))))
      (is (= 400
             (:status (handler {:request-method :get :uri "/oscope/export"
                                :query-params
                                (assoc base "end-unix-nano"
                                       "9999999999999999999")})))))))

(deftest export-rejects-cross-site-fetches-before-running-work
  (let [called? (atom false)
        handler (web/handler
                 {:load-command (fn [_ selection]
                                  (sample/screen-for-selection selection))
                  :export-command (fn [& _] (reset! called? true))})
        response (handler {:request-method :get :uri "/oscope/export"
                           :headers {"sec-fetch-site" "cross-site"}})]
    (is (= 403 (:status response)))
    (is (false? @called?))
    (is (= "nosniff" (get-in response [:headers "X-Content-Type-Options"])))
    (is (= "nosniff"
           (get-in (handler {:request-method :post :uri "/oscope/export"})
                   [:headers "X-Content-Type-Options"])))))
