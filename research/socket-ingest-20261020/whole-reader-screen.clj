(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[jolt.host :as host]
         '[oscope.socket-ingest :as socket])

(def reader (native/load-reader!))
(defn parse [selected? input]
  (binding [json/*experimental-native-reader* (when selected? reader)]
    (json/read-str input)))

(let [input (json/write-str (socket/payload 512 1800000000000000000))
      expected (parse false input)
      native-result (atom nil)
      ;; Non-timed proof that the real fixture is selected, not quietly declined.
      tracked (fn [& args]
                (let [result (apply reader args)]
                  (reset! native-result (vector? result)) result))
      selected (binding [json/*experimental-native-reader* tracked]
                 (json/read-str input))
      _ (assert (true? @native-result))
      _ (assert (= expected selected))
      samples (atom {})]
  (doseq [round (range 14)
          selected? (if (even? round) [false true] [true false])]
    (let [before (+ (host/gc-bytes) (host/bytes-allocated))
          start (System/nanoTime)
          result (parse selected? input)
          elapsed (/ (double (- (System/nanoTime) start)) 1000000.0)
          after (+ (host/gc-bytes) (host/bytes-allocated))]
      (assert (= expected result))
      (when (>= round 2)
        (swap! samples update selected? (fnil conj [])
               {:ms elapsed :estimated-bytes (- after before)}))))
  (doseq [selected? [false true]]
    (let [raw (get @samples selected?) ordered (vec (sort (map :ms raw)))
          q (fn [p] (nth ordered (dec (long (Math/ceil (* p (count ordered)))))))]
      (prn {:mode (if selected? :whole-reader :portable)
            :fixture :actual-socket-wire :spans 512 :characters (count input)
            :native-fixture-selected? @native-result
            :samples raw :p50-ms (q 0.5) :p90-ms (q 0.9)
            :p95-ms (q 0.95) :p99-ms (q 0.99)
            :estimated-mean-bytes (/ (double (reduce + (map :estimated-bytes raw))) (count raw))}))))
