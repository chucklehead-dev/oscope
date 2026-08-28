(ns oscope.telemetry
  "Bounded ClickStack-shaped trace and correlated-log queries.

  This namespace owns data shape and query policy, not presentation. Hosts may
  pass its results directly to jolt-otel-viewer or enrich a trace at the
  rendering boundary with library-supplied Kindly metadata."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [otel.exporter.chdb.explorer :as explorer]))

(def windows
  {"15m" (* 15 60 1000000000)
   "1h" (* 60 60 1000000000)
   "24h" explorer/max-time-range-nanos})
(def window-options
  [{:value "15m" :label "Last 15 minutes"}
   {:value "1h" :label "Last hour"}
   {:value "24h" :label "Last 24 hours"}])
(def default-selection
  {:service "" :operation "" :status "" :min-duration-ms "" :window "1h"})

(def ^:private duration-pattern #"[0-9]+(?:\.[0-9]+)?")
(def ^:private trace-id-pattern #"[0-9a-f]{32}")
(def ^:private max-span-events 64)
(def ^:private max-span-events-json-bytes 65536)

(defn- value-of [row key]
  (get row (keyword (str/lower-case (name key)))))

(defn- bounded [value maximum]
  (let [value (str/trim (or value ""))]
    (subs value 0 (min maximum (count value)))))

(defn normalize-selection [selection]
  (let [selection (if (map? selection) selection {})
        service (bounded (:service selection) 100)
        operation (bounded (:operation selection) 200)
        status (:status selection)
        duration (bounded (:min-duration-ms selection) 32)
        window (:window selection)]
    {:service service
     :operation operation
     :status (if (contains? #{"ok" "error"} status) status "")
     :min-duration-ms (if (re-matches duration-pattern duration) duration "")
     :window (if (contains? windows window) window "1h")}))

(defn- duration-nanos [text]
  (when-not (str/blank? text)
    (let [milliseconds (Double/parseDouble text)]
      (when (and (= milliseconds milliseconds)
                 (<= 0.0 milliseconds 9000000000000.0))
        (long (* milliseconds 1000000.0))))))

(defn trace-query [selection now-unix-nano]
  (when-not (and (integer? now-unix-nano) (pos? now-unix-nano))
    (throw (ex-info "oscope trace query time must be epoch nanoseconds"
                    {:oscope.telemetry/error true :now-unix-nano now-unix-nano})))
  (let [{:keys [service operation status min-duration-ms window]}
        (normalize-selection selection)
        minimum-duration (duration-nanos min-duration-ms)
        start (max 0 (- now-unix-nano (get windows window)))
        having (cond-> []
                 (not (str/blank? service))
                 (conj "countIf(ServiceName = ?) > 0")
                 (not (str/blank? operation))
                 (conj "countIf(positionCaseInsensitiveUTF8(SpanName, ?) > 0) > 0")
                 (= status "error")
                 (conj "countIf(lower(StatusCode) = 'error') > 0")
                 (= status "ok")
                 (conj "countIf(lower(StatusCode) = 'error') = 0")
                 minimum-duration (conj "max(Duration) >= ?"))
        parameters (cond-> [start]
                     (not (str/blank? service)) (conj service)
                     (not (str/blank? operation)) (conj operation)
                     minimum-duration (conj minimum-duration))]
    (into
     [(str "SELECT TraceId AS traceId, min(Timestamp) AS startedAt, "
           "max(Duration) AS durationNs, argMin(ServiceName, Timestamp) AS service, "
           "argMin(SpanName, Timestamp) AS rootSpan, count() AS spanCount, "
           "if(countIf(lower(StatusCode) = 'error') > 0, 'error', 'ok') AS status "
           "FROM otel_traces WHERE TraceId != '' "
           "AND Timestamp >= fromUnixTimestamp64Nano(?) GROUP BY TraceId"
           (when (seq having) (str " HAVING " (str/join " AND " having)))
           " ORDER BY startedAt DESC LIMIT 100")]
     parameters)))

(defn query-summary [connection]
  (let [spans (first
               (jdbc/fetch
                connection
                "SELECT uniqExactIf(TraceId, TraceId != '') AS traceCount,
                        count() AS spanCount,
                        countIf(lower(StatusCode) = 'error') AS errorCount
                   FROM otel_traces"))
        logs (first (jdbc/fetch connection "SELECT count() AS logCount FROM otel_logs"))]
    {:traceCount (or (value-of spans :traceCount) 0)
     :spanCount (or (value-of spans :spanCount) 0)
     :logCount (or (value-of logs :logCount) 0)
     :errorCount (or (value-of spans :errorCount) 0)}))

(defn query-traces
  ([connection selection]
   (query-traces connection selection (* (System/currentTimeMillis) 1000000)))
  ([connection selection now-unix-nano]
   (mapv (fn [row]
           {:traceId (value-of row :traceId)
            :startedAt (value-of row :startedAt)
            :durationNs (value-of row :durationNs)
            :service (value-of row :service)
            :rootSpan (value-of row :rootSpan)
            :spanCount (value-of row :spanCount)
            :status (value-of row :status)})
         (jdbc/fetch connection (trace-query selection now-unix-nano)))))

(defn query-filter-options [connection selection now-unix-nano]
  (let [selection (normalize-selection selection)
        start (max 0 (- now-unix-nano explorer/max-time-range-nanos))
        rows (explorer/top-values
              connection {:signal :spans :fields [:service-name]
                          :start-unix-nano start :end-unix-nano now-unix-nano
                          :limit 49})
        discovered (mapv (fn [{:keys [value count]}]
                           {:value value :label (str value " (" count ")")})
                         rows)
        selected (:service selection)
        services (if (or (str/blank? selected)
                         (some #(= selected (:value %)) discovered))
                   discovered
                   (into [{:value selected :label selected}] discovered))]
    {:selected selection
     :service-options services
     :status-options [{:value "ok" :label "OK"}
                      {:value "error" :label "Error"}]
     :window-options window-options}))

(defn- span-events [row]
  (let [raw (value-of row :EventsJSON)]
    (if (and (string? raw) (<= (count raw) max-span-events-json-bytes))
      (try
        (let [events (json/read-str raw :key-fn keyword)]
          (if (vector? events) (vec (take max-span-events events)) []))
        (catch Throwable _ []))
      [])))

(defn- span-row [row]
  {:timestamp (value-of row :Timestamp)
   :timestampUnixNano (value-of row :TimestampUnixNano)
   :traceId (value-of row :TraceId)
   :spanId (value-of row :SpanId)
   :parentSpanId (value-of row :ParentSpanId)
   :service (value-of row :ServiceName)
   :name (value-of row :SpanName)
   :kind (some-> (value-of row :SpanKind) str/lower-case)
   :durationNs (value-of row :Duration)
   :status (some-> (value-of row :StatusCode) str/lower-case)
   :statusMessage (value-of row :StatusMessage)
   :attributes (value-of row :SpanAttributes)
   :events (span-events row)})

(defn- log-row [row]
  {:timestamp (value-of row :Timestamp)
   :severity (value-of row :SeverityText)
   :service (value-of row :ServiceName)
   :body (value-of row :Body)
   :traceId (value-of row :TraceId)
   :spanId (value-of row :SpanId)
   :attributes (value-of row :LogAttributes)})

(defn span-tree [spans]
  (let [spans (vec spans)
        by-id (into {} (keep #(when-let [id (:spanId %)] [id %])) spans)
        children (reduce
                  (fn [result {:keys [spanId parentSpanId]}]
                    (if (and spanId (not (str/blank? parentSpanId))
                             (not= spanId parentSpanId)
                             (contains? by-id parentSpanId))
                      (update result parentSpanId (fnil conj []) spanId)
                      result))
                  {} spans)
        roots (keep (fn [{:keys [spanId parentSpanId]}]
                      (when (and spanId
                                 (or (str/blank? parentSpanId)
                                     (= spanId parentSpanId)
                                     (not (contains? by-id parentSpanId))))
                        spanId))
                    spans)
        seen (atom #{})]
    (letfn [(walk [id path]
              (when (and id (not (contains? path id))
                         (not (contains? @seen id)))
                (swap! seen conj id)
                (assoc (get by-id id) :children
                       (into [] (keep #(walk % (conj path id)))
                             (get children id [])))))]
      (reduce (fn [forest {:keys [spanId]}]
                (if-let [node (walk spanId #{})] (conj forest node) forest))
              (into [] (keep #(walk % #{}) roots)) spans))))

(defn query-trace [connection trace-id]
  (when-not (and (string? trace-id) (re-matches trace-id-pattern trace-id))
    (throw (ex-info "trace id must be 32 lowercase hex characters"
                    {:oscope.telemetry/error true :trace-id trace-id})))
  (let [spans
        (mapv span-row
              (jdbc/fetch
               connection
               ["SELECT Timestamp, toUnixTimestamp64Nano(Timestamp) AS TimestampUnixNano,
                        TraceId, SpanId, ParentSpanId, ServiceName, SpanName,
                        SpanKind, Duration, StatusCode, StatusMessage,
                        SpanAttributes, EventsJSON FROM otel_traces
                   WHERE TraceId = ? ORDER BY Timestamp, SpanId LIMIT 1000"
                trace-id]))]
    {:traceId trace-id
     :spans spans
     :spanTree (span-tree spans)
     :logs (mapv log-row
                 (jdbc/fetch
                  connection
                  ["SELECT Timestamp, SeverityText, ServiceName, Body, TraceId,
                           SpanId, LogAttributes FROM otel_logs
                      WHERE TraceId = ? ORDER BY Timestamp, SpanId LIMIT 500"
                   trace-id]))}))

(defn query-logs [connection]
  (mapv log-row
        (jdbc/fetch
         connection
         "SELECT Timestamp, SeverityText, ServiceName, Body, TraceId, SpanId,
                 LogAttributes FROM otel_logs ORDER BY Timestamp DESC LIMIT 100")))
