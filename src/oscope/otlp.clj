(ns oscope.otlp
  "Bounded jolt-http adapter for the transport-neutral OTLP/HTTP receiver."
  (:require [clojure.data.json :as json]
            [jolt.http.body :as http-body]
            [otel.otlp.http-receiver :as receiver]))

(def max-body-bytes (* 1024 1024))
(def max-concurrency 1)

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
  [request limit]
  (let [encoded (bounded-bytes (:body request) limit)]
    {:value (json/read-str (String. encoded "UTF-8"))
     :encoded-bytes (alength encoded)}))

(defn wrap-close-rejected-bodies
  "Prevent reuse when a rejected request may have an unread streaming body."
  [receive]
  (fn [request]
    (let [{:keys [status] :as response} (receive request)]
      (if (<= 200 status 299)
        response
        (assoc-in response [:headers "Connection"] "close")))))

(defn handler [exporter]
  (wrap-close-rejected-bodies
   (receiver/handler {:parse-body parse-json-body
                      :exporter exporter
                      :max-body-bytes max-body-bytes
                      :max-concurrency max-concurrency})))
