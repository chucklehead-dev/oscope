(ns oscope.raw-export-test
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.chdb :as chdb]
            [oscope.command :as command]
            [oscope.effect :as effect]
            [oscope.raw-export :as raw-export]))

(def start 1700000000000000000)
(def end (+ start 1000000000))
(defn selection [signal metric-kind]
  {:signal signal :metric-kind metric-kind
   :start-unix-nano start :end-unix-nano end
   :format :parquet :max-rows 17 :max-bytes 4096})

(deftest closed-selections-compile-all-five-physical-sources
  (doseq [[signal kind table]
          [[:spans nil "otel_traces"]
           [:logs nil "otel_logs"]
           [:metrics :gauge "otel_metrics_gauge"]
           [:metrics :sum "otel_metrics_sum"]
           [:metrics :histogram "otel_metrics_histogram"]]]
    (let [{:keys [query] :as plan}
          (raw-export/compile-query (selection signal kind))]
      (is (= plan (raw-export/validate-plan plan)))
      (is (.contains (first query) (str "FROM " table " WHERE ")))
      (is (.contains (first query) " >= ?"))
      (is (.contains (first query) " < ?"))
      (is (.endsWith (first query) "LIMIT ?"))
      (is (= [start end 17] (subvec query 1))))))

(deftest selection-bounds-and-ingress-fail-closed
  (testing "absolute half-open window and hard caps"
    (doseq [bad [(assoc (selection :spans nil) :end-unix-nano start)
                 (assoc (selection :logs nil) :end-unix-nano
                        (+ start raw-export/max-time-range-nanos 1))
                 (assoc (selection :spans nil) :max-rows 100001)
                 (assoc (selection :spans nil) :max-bytes 67108865)
                 (selection :metrics nil)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (raw-export/normalize-selection bad)))))
  (testing "SQL, identifiers, filenames, and paths cannot enter the contract"
    (doseq [key [:sql :table :column :path :filename]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (raw-export/normalize-selection
                    (assoc (selection :spans nil) key "attacker input")))))))

(deftest encoded-driver-result-is-copied-into-an-oscope-owned-envelope
  (let [bytes (byte-array [(byte 80) (byte 65) (byte 82) (byte 49)])
        calls (atom [])
        selected (selection :metrics :histogram)]
    (with-redefs [chdb/query-bytes
                  (fn [connection query options]
                    (swap! calls conj [connection query options])
                    {:format :parquet :content-type "ignored/by/oscope"
                     :extension "ignored" :byte-count 4 :bytes bytes})]
      (let [result (effect/run-export-command
                    #(raw-export/execute! ::connection %)
                    (command/export-command :request selected))]
        (is (= "application/vnd.apache.parquet" (:content-type result)))
        (is (= (str "oscope-metrics-histogram-" start "-" end ".parquet")
               (:suggested-filename result)))
        (is (identical? bytes (:bytes result)))
        (is (= {:format :parquet :max-rows 17 :max-bytes 4096}
               (nth (first @calls) 2)))))))

(deftest effect-validates-the-complete-export-result-contract
  (let [selected (selection :spans nil)
        bytes (byte-array [(byte 80) (byte 65) (byte 82) (byte 49)])
        valid (merge {:oscope.export/version 1 :selection selected
                      :byte-count 4 :bytes bytes}
                     (raw-export/response-metadata selected))
        command (command/export-command :request selected)]
    (is (= valid (effect/run-export-command (constantly valid) command)))
    (doseq [invalid [(assoc valid :unknown true)
                     (dissoc valid :bytes)
                     (assoc valid :byte-count 3)
                     (assoc valid :content-type "text/html")
                     (assoc valid :selection (assoc selected :format :arrow))]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (effect/run-export-command (constantly invalid) command))))))
