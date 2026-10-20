(ns oscope.fused-json-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is run-tests]]
            [oscope.fused-json :as fused]))

(def finder (ns-resolve 'clojure.data.json 'next-string-special))
(def scan (delay (fused/open-finder)))
(defn selected [f] (with-redefs-fn {finder @scan} f))

(deftest nearest-special-at-every-valid-position
  (doseq [s ["" "ordinary" "quote\"tail" "slash\\tail" "🚀é\\\""
             (str (apply str (repeat 65 "\\n")) "\"tail")]
          start (range (inc (count s)))]
    (is (= (@finder s start) (@scan s start)))))

(defn token-outcome [source start candidate?]
  (let [stream (#'json/string-pbr source)
        _ (.setPosition stream start)
        run #(try {:value (#'json/read-quoted-string-from-string stream)
                   :position (.position stream)}
                  (catch Throwable e
                    {:class (class e) :error (ex-message e)
                     :position (.position stream)}))]
    (if candidate? (selected run) (run))))

(deftest exact-token-error-and-cursor-parity
  (doseq [body ["plain\"tail" "a\\n\\t\\\\\\\"z\"tail"
                "\\u0041\"tail" "\\ud83d\\ude80\"tail"
                "\\uD800" "\\uDC00\"" "\\uD800\\u0000\""
                "\\uZZZZ\"" "\\u+041\"" "\\u-001\""
                "\\u" "\\u12" "\\q\"" "unterminated" "trailing\\"]
          start [0 2 63 64 65]
          :let [source (str (apply str (repeat start "p")) body)]]
    (is (= (token-outcome source start false)
           (token-outcome source start true)))))

(deftest generated-string-and-option-parity
  (doseq [cp (concat (range 256) [2048 55295 57344 65535 65536 128640 1114111])
          escape? [true false]
          :let [text (str "left" (char cp) "\"/\\right")
                input (json/write-str {"text" text "active" false}
                                     :escape-unicode escape?)]]
    (is (= (json/read-str input :key-fn keyword)
           (selected #(json/read-str input :key-fn keyword))))))

(deftest restores-original-on-success-and-failure
  (let [original @finder]
    (is (= "ok" (selected #(json/read-str "\"ok\""))))
    (is (identical? original @finder))
    (is (thrown? Throwable (selected #(json/read-str "\"\\q\""))))
    (is (identical? original @finder))))

(defn -main [& _]
  (let [result (run-tests 'oscope.fused-json-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
