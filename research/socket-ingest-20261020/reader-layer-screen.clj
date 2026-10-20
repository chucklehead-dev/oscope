(require '[clojure.data.json :as json]
         '[clojure.data.json.jolt-native :as native]
         '[clojure.string :as str])

(def reader (native/load-string-reader!))
(assert (ifn? reader))
(def modes
  [[:portable #(json/read-str %)]
   [:native-token #(binding [json/*experimental-native-string-reader* reader]
                     (json/read-str %))]
   [:reader-backed #(json/read (java.io.StringReader. %))]])

;; Fixed valid/invalid controls. Compare values or acceptance, not error wording.
(doseq [input ["null" "[false,0,-1,1.25]" "{\"a\":\"\",\"b\":\"plain\"}"
               "[\"\\n\\t\\\\\\\"\",\"\\u00e9\",\"\\ud83d\\ude80\"]"
               "[\"unterminated" "[\"\\q\"]" "[\"\\u12zz\"]"]]
  (let [outcomes (mapv (fn [[_ parse]]
                         (try [:ok (parse input)]
                              (catch Throwable _ [:error]))) modes)]
    (assert (apply = outcomes))))
(prn {:controls :passed :native-info (native/string-reader-info)})

(defn quantile [sorted-values q]
  (nth sorted-values (dec (long (Math/ceil (* q (count sorted-values)))))))

(doseq [kind (if (seq *command-line-args*) [] [:plain :escaped]) n [256 1024 2048]]
  (let [value (if (= kind :plain) "ordinary-field-value"
                  "unicode-\u00e9-\n-\ud83d\ude80")
        input (str "[" (str/join "," (repeat n (json/write-str value))) "]")
        expected (vec (repeat n value))
        samples (atom {})]
    ;; Interleave the three modes, reversing order on alternate rounds.
    (doseq [round (range 14)
            [mode parse] (if (even? round) modes (reverse modes))]
      (let [start (System/nanoTime)
            result (parse input)
            elapsed (/ (double (- (System/nanoTime) start)) 1000000.0)]
        (assert (= expected result))
        (when (>= round 2) (swap! samples update mode (fnil conj []) elapsed))))
    (doseq [[mode _] modes]
      (let [raw (get @samples mode) ordered (vec (sort raw))]
        (prn {:kind kind :strings n :characters (count input) :mode mode
              :samples-ms raw :p50-ms (quantile ordered 0.5)
              :p90-ms (quantile ordered 0.9) :p95-ms (quantile ordered 0.95)
              :p99-ms (quantile ordered 0.99)})))))

(when (= ["socket-fixture"] (vec *command-line-args*))
  (require 'oscope.socket-ingest)
  (let [payload ((ns-resolve 'oscope.socket-ingest 'payload) 512 1800000000000000000)
        input (json/write-str payload)
        ;; JSON changes key/numeric representations; portable parse is the oracle.
        expected (json/read-str input)
        samples (atom {})]
    (doseq [round (range 14)
            [mode parse] (if (even? round) modes (reverse modes))]
      (let [start (System/nanoTime)
            result (parse input)
            elapsed (/ (double (- (System/nanoTime) start)) 1000000.0)]
        (assert (= expected result))
        (when (>= round 2) (swap! samples update mode (fnil conj []) elapsed))))
    (doseq [[mode _] modes]
      (let [raw (get @samples mode) ordered (vec (sort raw))]
        (prn {:kind :actual-socket-wire-fixture :spans 512
              :characters (count input) :mode mode
              :samples-ms raw :p50-ms (quantile ordered 0.5)
              :p90-ms (quantile ordered 0.9) :p95-ms (quantile ordered 0.95)
              :p99-ms (quantile ordered 0.99)})))))
