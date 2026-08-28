(ns oscope.http-export-integration-test
  "Real jolt-http loopback proof for binary oscope downloads."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [db.jdbc]
            [jdbc.core :as jdbc]
            [jolt.http.server :as http]
            [oscope.live :as live]
            [oscope.ui.web :as web]
            [teensyp.client :as client]))

(def test-now 1700000001000000000)
(def mounted-path "/host/tools/oscope")

(defn- copy-of-length [source length]
  (let [copy (byte-array length)]
    (System/arraycopy source 0 copy 0 length)
    copy))

(defn- copy-range [source start end]
  (let [copy (byte-array (- end start))]
    (System/arraycopy source start copy 0 (- end start))
    copy))

(defn- concat-bytes [chunks]
  (let [length (reduce + 0 (map alength chunks))
        result (byte-array length)]
    (loop [remaining chunks offset 0]
      (if-let [chunk (first remaining)]
        (do (System/arraycopy chunk 0 result offset (alength chunk))
            (recur (rest remaining) (+ offset (alength chunk))))
        result))))

(defn- receive-all! [connection]
  (let [scratch (byte-array 8192)]
    (loop [chunks []]
      (let [length (client/receive-into! connection scratch 0 (alength scratch)
                                         {:timeout-ms 5000})]
        (if (nil? length)
          (concat-bytes chunks)
          (recur (conj chunks (copy-of-length scratch length))))))))

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
  (let [end (header-end raw)]
    (when-not end
      (throw (ex-info "loopback response has no header terminator"
                      {:byte-count (alength raw)})))
    (let [head (String. (copy-range raw 0 end) "UTF-8")
          body (copy-range raw (+ end 4) (alength raw))
          [status-line & lines] (str/split head #"\r\n")
          [_ status] (re-matches #"HTTP/1\.1 ([0-9]{3}) .*" status-line)
          headers
          (into {}
                (map (fn [line]
                       (let [colon (str/index-of line ":")]
                         [(str/lower-case (subs line 0 colon))
                          (str/trim (subs line (inc colon)))])))
                lines)]
      {:status (parse-long status) :headers headers :body body})))

(defn- download! [port target]
  (let [connection (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms 5000})]
    (try
      (let [request (.getBytes
                     (str "GET " target " HTTP/1.1\r\n"
                          "Host: 127.0.0.1:" port "\r\n"
                          "Connection: close\r\n\r\n")
                     "UTF-8")]
        (client/send-all! connection request {:timeout-ms 5000})
        (parse-response (receive-all! connection)))
      (finally
        (client/close! connection)))))

(defn- prefix [bytes length]
  (mapv #(bit-and 255 %) (take length bytes)))

(defn- target [format start end]
  (str mounted-path "/export?signal=spans&metric-kind=gauge&format="
       (name format) "&start-unix-nano=" start "&end-unix-nano=" end
       "&max-rows=10&max-bytes=4194304"))

(deftest mounted-export-delivers-arrow-and-parquet-over-real-jolt-http
  (let [source (live/open! {:db-spec "chdb::memory:"
                            :now-fn (constantly test-now)})
        server* (atom nil)
        start (- test-now 2000000000)
        end test-now]
    (try
      (jdbc/execute!
       (:connection source)
       ["INSERT INTO otel_traces
           (Timestamp, TraceId, SpanId, ServiceName, SpanName)
           VALUES (fromUnixTimestamp64Nano(?), 'wire-trace', 'wire-span',
                   'wire-test', 'wire.export')"
        (- test-now 1000000)])
      (let [server (http/run-server
                    (web/handler source {:path mounted-path})
                    :port 0 :reuse-address? true :pool-size 2)
            _ (reset! server* server)
            parquet (download! (:port server) (target :parquet start end))
            arrow (download! (:port server) (target :arrow start end))]
        (doseq [[format response content-type magic]
                [[:parquet parquet "application/vnd.apache.parquet"
                  [80 65 82 49]]
                 [:arrow arrow "application/vnd.apache.arrow.file"
                  [65 82 82 79 87 49]]]]
          (is (= 200 (:status response)))
          (is (= content-type (get-in response [:headers "content-type"])))
          (is (= (str (alength (:body response)))
                 (get-in response [:headers "content-length"])))
          (is (= (str "attachment; filename=\"oscope-spans-" start "-" end
                      "." (name format) "\"")
                 (get-in response [:headers "content-disposition"])))
          (is (= magic (prefix (:body response) (count magic))))))
      (finally
        (when-let [server @server*]
          (http/stop-server server))
        (live/close! source)))))
