(ns agent.bench
  "What recording costs a Jolt process, in two parts.

  1. Microbenchmarks: 200k operations per phase, flushed between phases, so
     nothing is dropped and counts are checked exactly. Time and Scheme heap
     per operation, measured the way jolt-otel-clickhouse's benchmarks measure
     heap (delta of bytes-allocated + gc-bytes).
  2. The fixed-rate workload from the Go comparison (go/cmd/wirecost): 5,000
     requests/s for 10 s, each a SERVER span with three children (3 attributes
     each) plus one correlated log. Reports whole-process CPU per request from
     /proc (so the drain thread and chDB are included), minus a no-telemetry
     baseline of the same pacing loop."
  (:require [oscope.core :as o]
            [clojure.string :as str]
            [jolt.host :as host]))

(o/defkeys n-span "bench.span" n-root "GET /orders/{id}" n-child "load-order"
  k-route "http.route" k-method "http.request.method" k-status "http.response.status_code"
  k-fn "code.function" k-order "order.id" k-tier "user.tier")

(defn- heap [] (+ (host/bytes-allocated) (host/gc-bytes)))

(defn- process-cpu-ns
  "utime + stime for the whole process, from /proc/self/stat (clock ticks of 10 ms)."
  []
  (let [s (slurp "/proc/self/stat")
        f (str/split (subs s (+ 2 (str/last-index-of s ")"))) #" ")]
    (* 10000000 (+ (parse-long (nth f 11)) (parse-long (nth f 12))))))

(defn- counts []
  (let [[s l] (str/split (str/trim (o/query "SELECT (SELECT count() FROM otel_traces), (SELECT count() FROM otel_logs)")) #"\t")]
    [(parse-long s) (parse-long l)]))

(defn- measure [label n f expect-spans expect-logs]
  (let [[s0 l0] (counts) gc0 (host/gc-count) h0 (heap) c0 (host/cpu-nanos) t0 (host/mono-nanos)]
    (f n)
    (let [wall (/ (double (- (host/mono-nanos) t0)) n)
          cpu (/ (double (- (host/cpu-nanos) c0)) n)
          bytes (/ (double (- (heap) h0)) n)
          gcs (- (host/gc-count) gc0)]
      (o/flush! 60000)
      (let [[s1 l1] (counts)
            ok (and (= (- s1 s0) (* expect-spans n)) (= (- l1 l0) (* expect-logs n)))]
        (println (format "%-44s %5.0f ns/op wall %5.0f ns/op thread CPU %6.1f heap B/op %3d GCs  counts %s"
                         label wall cpu bytes gcs (if ok "exact" (str "MISMATCH " (- s1 s0) "/" (- l1 l0)))))))))

(defn- spans-literal [n]
  (dotimes [_ n]
    (o/with-span [s n-span :server k-route "/orders/{id}" k-status 200 k-tier "gold"])))

(defn- spans-dynamic [n]
  (let [tier "gold"]
    (dotimes [i n]
      (o/with-span [s n-span :server k-route "/orders/{id}" k-tier tier]
        (o/attr! s k-order i)))))

(defn- logs [n]
  (dotimes [_ n] (o/log! :info "order served" k-tier "gold")))

;; ------------------------------------------------------------------ workload

(def ^:private child-names ["load-order" "priceOrder" "db.query"])
(def ^:private child-keys (mapv o/key child-names))

(defn- request [i]
  (o/with-span [root n-root :server k-route "/orders/{id}" k-method "GET" k-status 200]
    (dotimes [c 3]
      (o/with-span [s (nth child-keys c) k-fn (nth child-names c) k-tier "gold"]
        (o/attr! s k-order i)))
    (o/log! :info "order served" k-order i k-tier "gold")))

(defn- paced
  "Run (f i) rps times a second for secs seconds in 1 ms ticks. Returns
  [process-cpu-ns heap-bytes gc-count ns-inside-f]."
  [rps secs f]
  (let [per-tick (quot rps 1000) total (* rps secs)
        cpu0 (process-cpu-ns) h0 (heap) gc0 (host/gc-count) start (host/mono-nanos)]
    (loop [i 0 inside 0 tick 1]
      (if (< i total)
        (let [t0 (host/mono-nanos)]
          (dotimes [k per-tick] (f (+ i k)))
          (let [spent (- (host/mono-nanos) t0)
                wait-ms (- (quot (* tick 1000000) 1000000) (quot (- (host/mono-nanos) start) 1000000))]
            (when (pos? wait-ms) (Thread/sleep wait-ms))
            (recur (+ i per-tick) (+ inside spent) (inc tick))))
        (do (o/flush! 60000)
            [(- (process-cpu-ns) cpu0) (- (heap) h0) (- (host/gc-count) gc0) inside])))))

(defn -main [& args]
  (let [n (parse-long (or (first args) "200000"))]
    (o/start! {:db (System/getenv "OSCOPE_DB") :service "jolt-bench" :ring-bytes (* 256 1024 1024)})
    (spans-literal 20000) (logs 20000) (o/flush! 60000) ; warm
    (println (format "Microbenchmarks: %d ops per phase, one thread, flushed between phases" n))
    (measure "span: start + 3 literal attrs + end" n spans-literal 1 0)
    (measure "span: 2 literal + 1 runtime attr (attr!)" n spans-dynamic 1 0)
    (measure "log with 1 attribute" n logs 0 1)

    (println)
    (println "Fixed-rate workload: 5,000 req/s x 10 s; a request = 4 spans + 1 log")
    (let [[s0 l0] (counts)
          [bcpu] (paced 5000 10 (fn [_] nil))
          [cpu hp gcs inside] (paced 5000 10 request)
          [s1 l1] (counts)
          reqs 50000.0]
      (println (format "  baseline pacing loop: %.1f us/req process CPU" (/ bcpu reqs 1000)))
      (println (format "  with oscope:          %.1f us/req process CPU (net %.1f), %.0f heap B/req, %d GCs, %.1f us/req inside instrumentation"
                       (/ cpu reqs 1000) (/ (- cpu bcpu) reqs 1000) (/ hp reqs) gcs (/ inside reqs 1000)))
      (println (format "  recorded %d spans + %d logs (expected 200000 + 50000); dropped %d"
                       (- s1 s0) (- l1 l0) (o/dropped))))
    (o/stop!)))
