(ns oscope.otlp
  "Bounded jolt-http adapter for the transport-neutral OTLP/HTTP receiver."
  (:require [clojure.data.json :as json]
            [jolt.http.body :as http-body]
            [otel.otlp.http-receiver :as receiver]))

(def max-body-bytes (* 1024 1024))
(def max-concurrency 1)

(defn open-json-reader
  "Resolve an explicit reader at startup; default loads no optional component.
  No database, telemetry or native resource ownership is acquired here."
  [selection]
  (case selection
    :configured json/read-str
    :experimental-stock
    (try
      (require 'jolt.codec.reader.experimental)
      (let [open (ns-resolve 'jolt.codec.reader.experimental 'open-reader)
            read (ns-resolve 'jolt.codec.reader.experimental 'try-read-string)
            ordinary json/read-str]
        (when-not (and open read (ifn? @open) (ifn? @read))
          (throw (ex-info "Reader capability unavailable" {})))
        (let [adapter (fn [source options] (apply ordinary source (mapcat identity options)))
              capability (@open {:number-reader adapter :fallback-reader adapter})
              read @read]
          (fn [source]
            ;; Explicit attempt/fallback composition also makes actual native
            ;; selection observable once per document, without token counters.
            (if-let [result (read capability source {})]
              (first result)
              (ordinary source)))))
      (catch Throwable _
        (throw (ex-info "Experimental JSON reader requires its optional compatible component"
                        {:oscope.otlp/error true :type ::json-reader-unavailable}))))
    (throw (ex-info "Unknown JSON reader selection"
                    {:oscope.otlp/error true :type ::invalid-json-reader}))))

(defn- concat-chunks [chunks total]
  (let [out (byte-array total)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (let [n (alength chunk)]
          (System/arraycopy chunk 0 out offset n)
          (recur (next remaining) (+ offset n)))
        out))))

(defn- bounded-stream-bytes [body limit]
  (loop [chunks [] total 0]
    (if-let [chunk (http-body/body-recv body)]
      (let [actual (+ total (alength chunk))]
        (when (> actual limit)
          (throw (receiver/body-too-large limit actual)))
        (recur (conj chunks chunk) actual))
      (concat-chunks chunks total))))

(defn- bounded-bytes [body limit]
  (let [encoded (cond
                  (string? body) (.getBytes body "UTF-8")
                  (bytes? body) body
                  (satisfies? http-body/RequestBody body)
                  (bounded-stream-bytes body limit)
                  :else
                  (throw (ex-info "unsupported OTLP Ring request body"
                                  {:oscope.otlp/error true
                                   :body-type (type body)})))
        actual (alength encoded)]
    (when (> actual limit)
      (throw (receiver/body-too-large limit actual)))
    encoded))

(defn parse-json-body
  "Parse a Ring body while enforcing the limit on consumed UTF-8 bytes."
  ([request limit] (parse-json-body request limit json/read-str))
  ([request limit read-json]
   (let [encoded (bounded-bytes (:body request) limit)]
     {:value (read-json (String. encoded "UTF-8"))
      :encoded-bytes (alength encoded)})))

(defn wrap-close-rejected-bodies
  "Prevent reuse when a rejected request may have an unread streaming body."
  [receive]
  (fn [request]
    (let [{:keys [status] :as response} (receive request)]
      (if (<= 200 status 299)
        response
        (assoc-in response [:headers "Connection"] "close")))))

(defn- wrap-after-success [receive after-success!]
  (if-not after-success!
    receive
    ;; The receiver's own admission slot ends when export returns. Keep a
    ;; second, nonblocking slot around export plus its durability barrier so a
    ;; later request cannot enter the shared connection before the earlier
    ;; request has either crossed the barrier or failed closed.
    (let [active? (atom false)]
      (fn [request]
        (if-not (compare-and-set! active? false true)
          {:status 429
           :headers {"Content-Type" "text/plain; charset=UTF-8"
                     "Cache-Control" "no-store"}
           :body "durability boundary busy\n"}
          (try
            (let [{:keys [status] :as response} (receive request)]
              (if-not (<= 200 status 299)
                response
                (try
                  (if (true? (after-success!))
                    response
                    (throw
                     (ex-info "durability callback did not confirm success"
                              {:oscope.otlp/error true
                               :type ::durability-unconfirmed})))
                  (catch Throwable _
                    {:status 503
                     :headers {"Content-Type" "text/plain; charset=UTF-8"
                               "Cache-Control" "no-store"}
                     :body "durability boundary failed\n"}))))
            (finally
              (reset! active? false))))))))

(defn handler
  ([exporter] (handler exporter {}))
  ([exporter {:keys [after-success! json-read] :as options}]
   (when (and (contains? options :json-read) (not (ifn? json-read)))
     (throw (ex-info "JSON reader must be callable"
                     {:oscope.otlp/error true :type ::invalid-json-reader})))
   (wrap-close-rejected-bodies
    (wrap-after-success
     (receiver/handler {:parse-body (if (contains? options :json-read)
                                     (fn [request limit] (parse-json-body request limit json-read))
                                     parse-json-body)
                        :exporter exporter
                        :max-body-bytes max-body-bytes
                        :max-concurrency max-concurrency})
     after-success!))))
