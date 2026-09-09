(ns oscope.http-executor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [jolt.http.server :as http]
            [oscope.http-executor :as http-executor]
            [teensyp.client :as client])
  (:import [java.util.concurrent ArrayBlockingQueue ThreadPoolExecutor
            TimeUnit]))

(defn- wait-for! [promise label]
  (is (= true (deref promise 1000 ::timeout)) label))

(defn- submit-blocked-pair! [execute! gate]
  (let [started-a (promise)
        started-b (promise)]
    (execute! #(do (deliver started-a true) @gate))
    (execute! #(do (deliver started-b true) @gate))
    (wait-for! started-a "first worker entered its task")
    (wait-for! started-b "second worker entered its task")))

(deftest jolt-thread-pool-capacity-is-an-explicit-negative-control
  ;; Jolt 0.8.3 retains this task even though the constructor receives a
  ;; one-element ArrayBlockingQueue. Keep the control beside the wrapper proof:
  ;; removing wrapper admission makes the next test fail for the demonstrated
  ;; reason instead of merely asserting an implementation detail.
  (let [gate (promise)
        executor (ThreadPoolExecutor.
                  2 2 0 TimeUnit/MILLISECONDS (ArrayBlockingQueue. 1))]
    (try
      (submit-blocked-pair! #(.execute executor %) gate)
      (.execute executor (fn [] :queued))
      (is (= 1 (.size (.getQueue executor))))
      (is (= :accepted
             (try
               (.execute executor (fn [] :overflow))
               :accepted
               (catch Throwable _ :rejected)))
          "the raw modeled executor does not enforce its advisory capacity")
      (is (= 2 (.size (.getQueue executor))))
      (finally
        (deliver gate true)
        (.shutdown executor)
        (is (.awaitTermination executor 2000 TimeUnit/MILLISECONDS))))))

(deftest admission-wrapper-bounds-and-promptly-rejects-overload
  (let [gate (promise)
        owned (http-executor/start! {:workers 2 :queue-capacity 1})
        execute! #(.execute (:executor owned) %)]
    (try
      (submit-blocked-pair! execute! gate)
      (execute! (fn [] :queued))
      (is (= 3 (http-executor/active-count owned)))
      (is (= 1 (http-executor/queue-depth owned)))
      (let [before (System/nanoTime)
            error (try
                    (execute! (fn [] :overflow))
                    nil
                    (catch Throwable error error))
            elapsed-ms (/ (- (System/nanoTime) before) 1000000.0)]
        (is (= ::http-executor/rejected (:type (ex-data error))))
        (is (< elapsed-ms 500.0)
            (str "overload rejection took " elapsed-ms "ms")))
      (is (= 3 (http-executor/active-count owned)))
      (is (= 1 (http-executor/queue-depth owned)))
      (finally
        (deliver gate true)
        (is (true? (http-executor/stop! owned)))
        (is (http-executor/terminated? owned))
        (is (zero? (http-executor/active-count owned)))))))

(defn- linux-rss-kib []
  (let [status (java.io.File. "/proc/self/status")]
    (when (.isFile status)
      (some->> (str/split-lines (slurp status))
               (some #(second (re-matches #"VmRSS:\s+([0-9]+)\s+kB" %)))
               parse-long))))

(deftest rejection-load-keeps-the-queue-bound-and-records-rss
  (let [gate (promise)
        owned (http-executor/start! {:workers 2 :queue-capacity 1})
        execute! #(.execute (:executor owned) %)
        rss-before (linux-rss-kib)
        max-depth (atom 0)
        attempts 10000]
    (try
      (submit-blocked-pair! execute! gate)
      (execute! (fn [] :queued))
      (let [rejected
            (reduce
             (fn [count _]
               (swap! max-depth max (http-executor/queue-depth owned))
               (try
                 (execute! (fn [] :overflow))
                 count
                 (catch Throwable error
                   (if (= ::http-executor/rejected
                          (:type (ex-data error)))
                     (inc count)
                     (throw error)))))
             0
             (range attempts))
            rss-after (linux-rss-kib)]
        (println "oscope HTTP overload load"
                 {:attempts attempts :rejected rejected
                  :max-queue-depth @max-depth
                  :rss-before-kib rss-before :rss-after-kib rss-after})
        (is (= attempts rejected))
        (is (= 1 @max-depth))
        (is (or (nil? rss-after) (pos? rss-after))))
      (finally
        (deliver gate true)
        (http-executor/stop! owned)))))

(deftest failed-delegate-submission-releases-its-admission
  (let [owned (http-executor/start! {:workers 1 :queue-capacity 1})]
    (http-executor/stop! owned)
    (is (thrown? Throwable
                 (.execute (:executor owned) (fn [] :never-runs))))
    (is (zero? (http-executor/active-count owned)))))

(defn- send-health-request! [connection port]
  (client/send-all!
   connection
   (.getBytes (str "GET /healthz HTTP/1.1\r\n"
                   "Host: 127.0.0.1:" port "\r\n"
                   "Connection: close\r\n\r\n")
              "UTF-8")
   {:timeout-ms 1000}))

(deftest jolt-http-closes-a-connection-rejected-before-handler-execution
  (let [gate (promise)
        entered-a (promise)
        entered-b (promise)
        entered-count (atom 0)
        owned (http-executor/start! {:workers 2 :queue-capacity 1})
        server (http/run-server
                (fn [_]
                  (let [n (swap! entered-count inc)]
                    (deliver (if (= n 1) entered-a entered-b) true)
                    @gate
                    {:status 200 :body "ok"}))
                :port 0 :reuse-address? true
                :error-logger (fn [_] nil)
                :executor (:executor owned))
        port (:port server)
        clients (atom [])]
    (try
      (doseq [entered [entered-a entered-b]]
        (let [connection (client/connect "127.0.0.1" port
                                         {:connect-timeout-ms 1000})]
          (swap! clients conj connection)
          (send-health-request! connection port)
          (wait-for! entered "a handler worker entered its Ring handler")))
      (let [queued (client/connect "127.0.0.1" port
                                   {:connect-timeout-ms 1000})]
        (swap! clients conj queued)
        (send-health-request! queued port))
      (let [deadline (+ (System/nanoTime) 1000000000)]
        (loop []
          (when (and (< (System/nanoTime) deadline)
                     (zero? (http-executor/queue-depth owned)))
            (Thread/yield)
            (recur))))
      (is (= 1 (http-executor/queue-depth owned)))
      (let [rejected (client/connect "127.0.0.1" port
                                     {:connect-timeout-ms 1000})
            scratch (byte-array 64)
            before (System/nanoTime)]
        (swap! clients conj rejected)
        (let [closed?
              (try
                (send-health-request! rejected port)
                (nil? (client/receive-into! rejected scratch 0 (alength scratch)
                                            {:timeout-ms 1000}))
                (catch Throwable _ true))
              elapsed-ms (/ (- (System/nanoTime) before) 1000000.0)]
          (is closed? "jolt-tcp retires the synchronously rejected connection")
          (is (< elapsed-ms 1500.0)
              (str "rejected connection close took " elapsed-ms "ms"))))
      (is (= 2 @entered-count)
          "the rejected connection never reaches the Ring handler")
      (is (= 1 (http-executor/queue-depth owned)))
      (finally
        (deliver gate true)
        (http/stop-server server)
        (http-executor/stop! owned)
        (doseq [connection @clients]
          (client/close! connection))))))
