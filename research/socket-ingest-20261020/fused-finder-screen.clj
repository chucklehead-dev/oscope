(require '[clojure.data.json :as json]
         '[jolt.scheme :as scheme]
         '[jolt.host :as host])

;; Experiment only: scalar return avoids allocating [token position] per string.
;; The original parser still owns decoding, error behavior and cursor updates.
(def finder (ns-resolve 'clojure.data.json 'next-string-special))
(assert finder "Run with the selected 0f51b991 data.json revision")
(def original @finder)
(def fused
  (scheme/eval-string
    "(lambda (s start)
       (let ((n (string-length s)))
         (let loop ((i start))
           (if (fx>= i n) -1
             (case (char->integer (string-ref s i))
               ((34 92) i)
               (else (loop (fx+ i 1))))))))"))

(doseq [s ["" "plain" "quote\"tail" "slash\\tail" "🚀é\\\""
           "\\\\\\\"long-tail" "no specials anywhere"]
        start (range (inc (count s)))]
  (assert (= (original s start) (fused s start))))

(defn parse [native? input]
  (if native?
    (with-redefs-fn {finder fused} #(json/read-str input))
    (json/read-str input)))

(doseq [input ["null" "[false,0,-1,1.25]" "{\"a\":\"\",\"b\":\"plain\"}"
               "[\"\\n\\t\\\\\\\"\",\"\\u00e9\",\"\\ud83d\\ude80\"]"
               "[\"unterminated" "[\"\\q\"]" "[\"\\u12zz\"]"]]
  (let [outcome (fn [native?]
                  (try [:ok (parse native? input)]
                       (catch Throwable e [:error (class e) (ex-message e)])))]
    (assert (= (outcome false) (outcome true)))))
(prn {:controls :passed :scope :experimental-private-finder-only})

(require 'oscope.socket-ingest)
(let [payload ((ns-resolve 'oscope.socket-ingest 'payload) 512 1800000000000000000)
      input (json/write-str payload)
      expected (json/read-str input)
      samples (atom {})]
  (doseq [round (range 14) native? (if (even? round) [false true] [true false])]
    ;; Separate coarse snapshots; estimated total allocation, not an exact meter.
    (let [before (+ (host/gc-bytes) (host/bytes-allocated))
          start (System/nanoTime)
          result (parse native? input)
          elapsed (/ (double (- (System/nanoTime) start)) 1000000.0)
          after (+ (host/gc-bytes) (host/bytes-allocated))]
      (assert (= expected result))
      (when (>= round 2)
        (swap! samples update native? (fnil conj [])
               {:ms elapsed :estimated-bytes (- after before)}))))
  (doseq [native? [false true]]
    (let [raw (get @samples native?) ordered (vec (sort (map :ms raw)))
          q (fn [p] (nth ordered (dec (long (Math/ceil (* p (count ordered)))))))]
      (prn {:mode (if native? :fused-finder :original-finder)
            :fixture :actual-socket-wire :spans 512 :characters (count input)
            :samples raw :p50-ms (q 0.5) :p90-ms (q 0.9)
            :p95-ms (q 0.95) :p99-ms (q 0.99)
            :estimated-mean-bytes (/ (double (reduce + (map :estimated-bytes raw))) (count raw))}))))
