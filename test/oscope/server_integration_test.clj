(ns oscope.server-integration-test
  "Real loopback proof for the standalone receiver/viewer composition."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [oscope.sample-emitter :as sample-emitter]
            [oscope.server :as server]
            [teensyp.client :as client]))

(defn- copy-range [source start end]
  (let [copy (byte-array (- end start))]
    (System/arraycopy source start copy 0 (- end start))
    copy))

(defn- concat-bytes [chunks]
  (let [length (reduce + 0 (map alength chunks))
        result (byte-array length)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (do
          (System/arraycopy chunk 0 result offset (alength chunk))
          (recur (next remaining) (+ offset (alength chunk))))
        result))))

(defn- receive-all! [connection]
  (let [scratch (byte-array 8192)]
    (loop [chunks []]
      (let [length (client/receive-into! connection scratch 0 (alength scratch)
                                         {:timeout-ms 5000})]
        (if (nil? length)
          (concat-bytes chunks)
          (recur (conj chunks (copy-range scratch 0 length))))))))

(defn- unsigned-byte [bytes index]
  (bit-and 255 (int (aget bytes index))))

(defn- header-end [bytes]
  (loop [index 0]
    (cond
      (> (+ index 4) (alength bytes)) nil
      (and (= 13 (unsigned-byte bytes index))
           (= 10 (unsigned-byte bytes (+ index 1)))
           (= 13 (unsigned-byte bytes (+ index 2)))
           (= 10 (unsigned-byte bytes (+ index 3)))) index
      :else (recur (inc index)))))

(defn- parse-response [raw]
  (let [end (header-end raw)
        head (String. (copy-range raw 0 end) "UTF-8")
        body (copy-range raw (+ end 4) (alength raw))
        [status-line & lines] (str/split head #"\r\n")
        [_ status] (re-matches #"HTTP/1\.1 ([0-9]{3}) .*" status-line)]
    {:status (parse-long status)
     :headers (into {}
                    (map (fn [line]
                           (let [colon (str/index-of line ":")]
                             [(str/lower-case (subs line 0 colon))
                              (str/trim (subs line (inc colon)))])))
                    lines)
     :body body}))

(defn- request!
  ([port method target body content-type]
   (request! port method target body content-type (str "127.0.0.1:" port)))
  ([port method target body content-type authority]
   (let [connection (client/connect "127.0.0.1" port
                                    {:connect-timeout-ms 5000})
         encoded (if body (.getBytes body "UTF-8") (byte-array 0))]
     (try
       (let [request (.getBytes
                      (str method " " target " HTTP/1.1\r\n"
                           "Host: " authority "\r\n"
                           (when content-type
                             (str "Content-Type: " content-type "\r\n"))
                           "Content-Length: " (alength encoded) "\r\n"
                           "Connection: close\r\n\r\n")
                      "UTF-8")]
         (client/send-all! connection request {:timeout-ms 5000})
         (when (pos? (alength encoded))
           (client/send-all! connection encoded {:timeout-ms 5000}))
         (parse-response (receive-all! connection)))
       (finally (client/close! connection))))))

(defn- post-json! [port path value]
  (request! port "POST" path (json/write-str value) "application/json"))

(defn- resource [service]
  {"attributes" [{"key" "service.name"
                   "value" {"stringValue" service}}]})

(defn- trace-wire [now]
  {"resourceSpans"
   [{"resource" (resource "oscope-loopback")
     "scopeSpans"
     [{"scope" {"name" "oscope.integration"}
       "spans"
       [{"traceId" "11111111111111111111111111111111"
         "spanId" "2222222222222222"
         "name" "loopback.work"
         "kind" 2
         "startTimeUnixNano" (str (- now 2000000))
         "endTimeUnixNano" (str (- now 1000000))}]}]}]})

(defn- log-wire [now]
  {"resourceLogs"
   [{"resource" (resource "oscope-loopback")
     "scopeLogs"
     [{"scope" {"name" "oscope.integration"}
       "logRecords"
       [{"timeUnixNano" (str (- now 1000000))
         "observedTimeUnixNano" (str now)
         "severityNumber" 9 "severityText" "INFO"
         "body" {"stringValue" "standalone receiver log"}
         "traceId" "11111111111111111111111111111111"
         "spanId" "2222222222222222"}]}]}]})

(defn- metric-wire [now]
  {"resourceMetrics"
   [{"resource" (resource "oscope-loopback")
     "scopeMetrics"
     [{"scope" {"name" "oscope.integration"}
       "metrics"
       [{"name" "oscope.loopback.requests" "unit" "1"
         "gauge" {"dataPoints" [{"timeUnixNano" (str now)
                                  "asInt" "1"}]}}]}]}]})

(defn- rows [source selection]
  (get-in ((:load-command source) [:integration (:signal selection)] selection)
          [:table :rows]))

(defn- physical-counts [connection]
  (mapv :count
        [(first (jdbc/fetch connection "select count() as count from otel_traces"))
         (first (jdbc/fetch connection "select count() as count from otel_logs"))
         (first (jdbc/fetch connection "select count() as count from otel_metrics_gauge"))]))

(defn- delete-generated-tree! [root]
  ;; `root` is created by this test, never supplied by a caller. Delete children
  ;; before their parent and fail the gate if chDB leaves anything owned behind.
  (doseq [file (reverse (file-seq root))]
    ;; Files/deleteIfExists deletes the link itself. File.delete follows chDB's
    ;; broken post-close metadata links when deciding whether to call rmdir.
    (try
      (java.nio.file.Files/deleteIfExists (.toPath file))
      (catch Throwable error
        (throw (ex-info "could not clean persistent oscope integration database"
                        {:oscope.integration/error true
                         :target (if (.isDirectory file) :directory :file)}
                        error))))))

(deftest standalone-loopback-ingests-queries-views-and-exports
  (let [lifecycle (server/start! {:port 0 :db-spec "chdb::memory:"})
        port (:port lifecycle)
        now (* (System/currentTimeMillis) 1000000)]
    (try
      (doseq [[path payload] [["/v1/traces" (trace-wire now)]
                              ["/v1/logs" (log-wire now)]
                              ["/v1/metrics" (metric-wire now)]]]
        (is (= 200 (:status (post-json! port path payload)))))
      (is (= [{:value "oscope-loopback" :count 1}]
             (rows (:source lifecycle)
                   {:signal :spans :field :service-name :window :15m :limit 10})))
      (is (= [{:value "INFO" :count 1}]
             (rows (:source lifecycle)
                   {:signal :logs :field :severity-text :window :15m :limit 10})))
      (is (= [{:value "oscope.loopback.requests" :count 1}]
             (rows (:source lifecycle)
                   {:signal :metrics :field :metric-name :window :15m :limit 10})))
      (let [before (physical-counts (:connection lifecycle))
            hostile [(request! port "GET" "/oscope" nil nil "attacker.example")
                     (request! port "GET" "/oscope/export" nil nil
                               "attacker.example")
                     (request! port "POST" "/v1/traces"
                               (json/write-str (trace-wire now))
                               "application/json" "attacker.example")]
            health (request! port "GET" "/healthz" nil nil)
            root (request! port "GET" "/" nil nil)
            page (request! port "GET" "/oscope?signal=spans&field=service-name&window=15m&limit=10"
                           nil nil)
            workbench (request! port "GET" "/oscope/telemetry?window=15m"
                                nil nil)
            trace-detail
            (request! port "GET"
                      "/oscope/telemetry/traces/11111111111111111111111111111111"
                      nil nil)
            log-events
            (request! port "GET"
                      "/oscope/events?signal=logs&service=oscope-loopback&severity=INFO&search=receiver&window=15m&limit=10"
                      nil nil)
            metric-events
            (request! port "GET"
                      "/oscope/events?signal=metrics&metric-kind=gauge&search=loopback.requests&window=15m&limit=10"
                      nil nil)
            editor-page
            (request! port "GET"
                      "/oscope/edit/plotje?signal=spans&field=service-name&window=15m&limit=10"
                      nil nil)
            editor-preview
            (request! port "POST" "/oscope/edit/hiccup/preview"
                      "spec=%5B%3Ap+%22feedback-free%22%5D"
                      "application/x-www-form-urlencoded")
            start (- now 1000000000)
            end (+ now 1000000000)
            export (request!
                    port "GET"
                    (str "/oscope/export?signal=spans&metric-kind=gauge&format=parquet"
                         "&start-unix-nano=" start "&end-unix-nano=" end
                         "&max-rows=10&max-bytes=4194304") nil nil)]
        (is (= [421 421 421] (mapv :status hostile)))
        (is (= before (physical-counts (:connection lifecycle)))
            "hostile authorities cannot view, export, or ingest telemetry")
        (is (= 200 (:status health)))
        (is (= "ok\n" (String. (:body health) "UTF-8")))
        (is (= 303 (:status root)))
        (is (= "/oscope/telemetry" (get-in root [:headers "location"])))
        (is (= 200 (:status page)))
        (is (.contains (String. (:body page) "UTF-8") "oscope-loopback"))
        (is (= 200 (:status workbench)))
        (is (.contains (String. (:body workbench) "UTF-8") "loopback.work"))
        (is (.contains (String. (:body workbench) "UTF-8")
                       "Logs &amp; metrics"))
        (is (.contains (String. (:body workbench) "UTF-8")
                       "Charts &amp; distributions"))
        (is (= 200 (:status trace-detail)))
        (is (.contains (String. (:body trace-detail) "UTF-8")
                       "standalone receiver log"))
        (is (= 200 (:status log-events)))
        (is (.contains (String. (:body log-events) "UTF-8")
                       "standalone receiver log"))
        (is (.contains (String. (:body log-events) "UTF-8")
                       "/oscope/telemetry/traces/11111111111111111111111111111111"))
        (is (= 200 (:status metric-events)))
        (is (.contains (String. (:body metric-events) "UTF-8")
                       "oscope.loopback.requests"))
        (is (= 200 (:status editor-page)))
        (is (.contains (String. (:body editor-page) "UTF-8")
                       "oscope-loopback"))
        (is (= 200 (:status editor-preview)))
        (is (.contains (String. (:body editor-preview) "UTF-8")
                       "feedback-free"))
        (is (= 200 (:status export)))
        (is (= "application/vnd.apache.parquet"
               (get-in export [:headers "content-type"])))
        (is (= [80 65 82 49]
               (mapv #(bit-and 255 %) (take 4 (:body export)))))
        (is (= before (physical-counts (:connection lifecycle)))
            "viewer, editor, health, redirect, and export requests create no telemetry"))
      (finally
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))))))

(deftest sample-companion-emits-parented-correlated-telemetry
  (let [lifecycle (server/start! {:port 0 :db-spec "chdb::memory:"})
        endpoint (str "http://127.0.0.1:" (:port lifecycle))]
    (try
      (let [trace-id (sample-emitter/run! {:endpoint endpoint})
            spans (jdbc/fetch
                   (:connection lifecycle)
                   ["select TraceId, SpanId, ParentSpanId, SpanName
                       from otel_traces
                      where TraceId=?"
                    trace-id])
            by-name (into {} (map (juxt :spanname identity)) spans)
            root (get by-name "POST /checkout")
            validate (get by-name "validate cart")
            inventory (get by-name "GET /inventory/{sku}")
            query (get by-name "SELECT inventory")
            publish (get by-name "fulfillment enqueue")
            logs (jdbc/fetch
                  (:connection lifecycle)
                  ["select TraceId, SpanId, Body
                       from otel_logs
                      where TraceId=?"
                   trace-id])]
        (is (= 5 (count spans)))
        (is (= "" (:parentspanid root)))
        (is (= (:spanid root) (:parentspanid validate)))
        (is (= (:spanid root) (:parentspanid inventory)))
        (is (= (:spanid inventory) (:parentspanid query)))
        (is (= (:spanid root) (:parentspanid publish)))
        (is (= 3 (count logs)))
        (is (every? #(= trace-id (:traceid %)) logs))
        (is (every? (comp not empty? :spanid) logs))
        (is (= #{"sample.checkout.requests"}
               (set (map :metricname
                         (jdbc/fetch (:connection lifecycle)
                                     "select MetricName from otel_metrics_sum")))))
        (is (= #{"sample.checkout.queue.depth"}
               (set (map :metricname
                         (jdbc/fetch (:connection lifecycle)
                                     "select MetricName from otel_metrics_gauge")))))
        (is (= #{"sample.checkout.duration"}
               (set (map :metricname
                         (jdbc/fetch (:connection lifecycle)
                                     "select MetricName from otel_metrics_histogram"))))))
      (finally
        (is (= {:status :closed :phase :closed} (server/stop! lifecycle)))))))

(deftest standalone-persistent-restart-retains-telemetry
  (let [directory (java.nio.file.Files/createTempDirectory
                   "oscope-server-restart-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        root (java.io.File. (str directory))
        db-spec (str "chdb:" (.resolve directory "telemetry"))
        now (* (System/currentTimeMillis) 1000000)
        first* (atom nil)
        second* (atom nil)]
    (try
      (let [first (server/start! {:port 0 :db-spec db-spec})]
        (reset! first* first)
        (is (= 200 (:status (post-json! (:port first) "/v1/traces"
                                        (trace-wire now)))))
        (is (= [1 0 0] (physical-counts (:connection first))))
        (is (= {:status :closed :phase :closed} (server/stop! first))))
      (let [second (server/start! {:port 0 :db-spec db-spec})]
        (reset! second* second)
        (is (= [1 0 0] (physical-counts (:connection second))))
        (is (= [{:value "oscope-loopback" :count 1}]
               (rows (:source second)
                     {:signal :spans :field :service-name
                      :window :15m :limit 10})))
        (is (= {:status :closed :phase :closed} (server/stop! second))))
      (finally
        (when-let [second @second*] (server/stop! second))
        (when-let [first @first*] (server/stop! first))
        (delete-generated-tree! root)))))
