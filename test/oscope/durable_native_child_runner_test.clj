(ns oscope.durable-native-child-runner-test
  (:require [clojure.data.json :as json]
            [clojure.test :as test :refer [deftest is]]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.time-domain :as time-domain]
            [oscope.child-support :as child]
            [oscope.durable-integration-test]
            [oscope.durable-s3-integration-test :as s3-fixture]
            [oscope.durable-native-child-runner :as runner]))

(deftest lease-renewal-observer-preserves-v1-fractional-seconds
  ;; PURE observer control: no native connection, writer, or backend operation.
  (let [await! (ns-resolve 'oscope.durable-integration-test
                          'await-lease-renewal!)
        values (atom [1.25 1.5])
        reads (atom 0)
        read! (fn [_]
                (swap! reads inc)
                (let [value (first @values)]
                  (swap! values next)
                  {:head {"lease" {"expires_at" value}}}))]
    (with-redefs [control/read-head! read!]
      (is (true? (await! nil 1.25 100)))
      (is (= 2 @reads)))
    ;; Causal old integer-only predicate rejects the otherwise valid renewal.
    (with-redefs [time-domain/supported-wire-epoch-seconds? integer?
                  control/read-head! (fn [_] {:head {"lease" {"expires_at" 1.5}}})]
      (is (false? (await! nil 1.25 0))))
    (with-redefs [control/read-head! (fn [_] {:head {"lease" {"expires_at" 1.25}}})]
      (is (false? (await! nil 1.25 0))))
    (doseq [invalid [nil -1 ##Inf ##-Inf ##NaN "1" {}]]
      (with-redefs [control/read-head! (fn [_] {:head {"lease" {"expires_at" 1.5}}})]
        (is (false? (await! nil invalid 0))))
      (with-redefs [control/read-head! (fn [_] {:head {"lease" {"expires_at" invalid}}})]
        (is (false? (await! nil 1.25 0)))))))

(defn- receipt [kind index tests passes failures errors settled]
  ;; SYNTHETIC reporting fixture, never an actual native qualification receipt.
  (str ":durable-native-executed " kind " " index "\n"
       ":durable-native-receipt " kind " " index " " tests " " passes " "
       failures " " errors " " settled "\n"))

(deftest s3-reader-dispatch-and-sealed-head-controls
  ;; PURE: no backend, native connection or child process is created.
  (is (= [0 1 2] (mapv runner/reader-index! [:standalone :embedded :s3])))
  (doseq [unknown [nil :other 's3 "s3" 2]]
    (is (try (runner/reader-index! unknown) false (catch Throwable _ true))))
  (let [head {"lease" {"owner" nil}
              "manifest" {"seq" 6 "base" {"key" "checkpoint-public"}
                          "wal" [{"key" "wal-public"}]}}
        hash! (ns-resolve 'oscope.durable-s3-integration-test 'sha256)
        seal (fn [value]
               (let [wire (json/write-str value)]
                 {"version" 1 "endpoint" "http://127.0.0.1:1"
                  "head-json" wire "head-sha256" (hash! wire)}))
        good (seal head)
        rejected? #(try (s3-fixture/checked-seal! %) false
                        (catch Throwable _ true))]
    (is (= head (s3-fixture/checked-seal! good)))
    (doseq [bad [(assoc good "head-sha256" (apply str (repeat 64 "0")))
                 (assoc good "head-json" (str (get good "head-json") " "))
                 (assoc good "version" 2)
                 (assoc good "endpoint" nil)
                 (seal (assoc-in head ["manifest" "seq"] 4))
                 (seal (assoc-in head ["lease" "owner"] "writer"))
                 (seal (assoc-in head ["manifest" "base"] nil))
                 (seal (assoc-in head ["manifest" "wal"] []))]]
      (is (rejected? bad))))
  (let [good (receipt "reader" 2 0 20 0 0 1)]
    (is (:ok? (runner/checked-receipt {:exit 0} good "reader" 2)))
    (doseq [bad ["" (str good good)
                 (receipt "reader" 1 0 20 0 0 1)
                 (receipt "fixture" 2 1 20 0 0 1)
                 (receipt "reader" 2 0 20 0 0 0)]]
      (is (not (:ok? (runner/checked-receipt {:exit 0} bad "reader" 2)))))
    (is (not (:ok? (runner/checked-receipt {:exit 1} good "reader" 2))))))

(deftest s3-reader-handoff-is-bounded-and-source-coupled
  (let [root (java.io.File. "/public/sealed-head.json")
        settled (atom false)
        calls (atom [])]
    (with-redefs [runner/executable! (fn [] "/approved/root-jolt")
                  test/counters (atom {:test 0 :pass 0 :fail 0 :error 0})
                  child/run! (fn [command options bounds]
                               (swap! calls conj [command bounds])
                               (spit (:out options) (receipt "reader" 2 0 20 0 0 1))
                               {:exit 0})]
      (binding [test/*report-counters* nil]
        (runner/run-reader! :s3 root settled)))
    (is (true? @settled))
    (is (= [["/usr/bin/timeout" "--kill-after=5s" "30s"
             "/approved/root-jolt" "-Srepro" "-A:test-durable" "-m"
             "oscope.durable-native-child-runner" "--reader" "s3"
             "/public/sealed-head.json"]
            {:timeout-ms 35000 :settlement-ms 5000}]
           (first @calls)))
    (is (= 1 (count @calls)))
    (with-redefs [runner/executable! (fn [] "/approved/root-jolt")
                  child/run! (fn [_ options _]
                               (spit (:out options) ":durable-native-executed reader 2\n")
                               {:exit 143})]
      (is (try (runner/run-reader! :s3 root settled) false
               (catch Throwable _ true))))
    (is (false? @settled))))

(deftest strict-native-receipts-reject-missing-extra-empty-and-wrong-counts
  (let [result {:exit 0}
        good (receipt "fixture" 0 1 2 0 0 1)
        check #(runner/checked-receipt result % "fixture" 0)]
    (is (:ok? (check good)))
    (is (not (:settled? (check ""))))
    (is (= {:test 1 :pass 2 :fail 0 :error 0} (:counts (check good))))
    (doseq [bad ["" (str good good)
                 (receipt "fixture" 1 1 2 0 0 1)
                 (receipt "reader" 0 0 2 0 0 1)
                 (receipt "fixture" 0 0 2 0 0 1)
                 (receipt "fixture" 0 1 0 0 0 1)]]
      (is (not (:valid? (check bad)))))
    (doseq [bad [(receipt "fixture" 0 1 2 1 0 1)
                 (receipt "fixture" 0 1 2 0 1 1)
                 (receipt "fixture" 0 1 2 0 0 0)
                 (str good ":durable-native-nested-unsettled\n")]]
      (is (not (:ok? (check bad)))))
    (is (not (:ok? (runner/checked-receipt {:exit 1} good "fixture" 0))))
    (is (not (:valid? (runner/checked-receipt
                      {:exit 0 :child/status :settled-failure} good "fixture" 0))))
    (is (not (:settled? (runner/checked-receipt
                        {:exit 1 :child/terminal? false} good "fixture" 0))))
    (is (:ok? (runner/checked-receipt {:exit 0}
                                    (receipt "reader" 1 0 3 0 0 1) "reader" 1)))))

(deftest native-child-orchestration-preserves-commands-bounds-and-settlement
  (let [directory (str (java.nio.file.Files/createTempDirectory
                        "oscope-native-runner-control-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        calls (atom [])]
    ;; These controls spawn NO process. They validate the orchestration seam.
    (with-redefs [child/run!
                  (fn [command options bounds]
                    (let [index (parse-long (last command))]
                      (swap! calls conj [command bounds])
                      (spit (:out options) (receipt "fixture" index 1 2 0 0 1))
                      {:exit 0}))]
      (let [result (runner/run-isolated! "/approved/jolt" directory)]
        (is (:qualified? result))
        (is (:settled? result))
        (is (= {:test 6 :pass 12 :fail 0 :error 0} (:totals result)))
        (is (= 6 (count @calls)))
        (is (every? #(= {:timeout-ms 60000 :settlement-ms 5000} (second %)) @calls))
        (is (= ["/approved/jolt" "-Srepro" "-A:test-durable" "-m"
                "oscope.durable-native-child-runner" "--fixture" "0"]
               (ffirst @calls)))))
    (reset! calls [])
    (with-redefs [child/run!
                  (fn [command options _]
                    (swap! calls conj command)
                    (spit (:out options) (receipt "fixture" 0 1 2 0 0 0))
                    {:exit 0})]
      (let [result (runner/run-isolated! "/approved/jolt" directory)]
        (is (not (:qualified? result)))
        (is (not (:settled? result)))
        (is (= 1 (count @calls)))))
    (reset! calls [])
    (with-redefs [child/run!
                  (fn [command _ _]
                    (swap! calls conj command)
                    {:exit 1 :child/terminal? false :child/status :cleanup-incomplete})]
      (let [result (runner/run-isolated! "/approved/jolt" directory)]
        (is (not (:qualified? result)))
        (is (not (:settled? result)))
        (is (= 1 (count @calls)))))
    ;; Logs remain even on unsettled synthetic outcomes, matching parent policy.
    (is (.exists (java.io.File. directory)))))

(deftest ancestor-timeout-with-missing-final-receipt-stops-further-fixtures
  (let [directory (str (java.nio.file.Files/createTempDirectory
                        "oscope-native-parent-timeout-control-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        calls (atom 0)]
    (with-redefs [child/run! (fn [_ options _]
                              (swap! calls inc)
                              (spit (:out options) ":durable-native-executed fixture 0\n")
                              {:exit 143})]
      (let [result (runner/run-isolated! "/approved/jolt" directory)]
        (is (not (:settled? result)))
        (is (not (:qualified? result)))
        (is (= 1 @calls))
        (is (.exists (java.io.File. directory)))))))

(deftest bounded-reader-handoff-keeps-counter-paths-distinct-and-preserves-store
  (let [root (java.nio.file.Files/createTempDirectory
              "oscope-native-reader-control-" (make-array java.nio.file.attribute.FileAttribute 0))
        settled (atom true)
        capture (atom nil)
        global-counts (atom {:test 0 :pass 0 :fail 0 :error 0})
        report-counts (ref test/*initial-report-counters*)]
    (with-redefs [runner/executable! (fn [] "/approved/jolt")
                  test/counters global-counts
                  child/run! (fn [command options bounds]
                               (reset! capture [command bounds])
                               (spit (:out options) (receipt "reader" 0 0 3 0 0 1))
                               {:exit 0})]
      (binding [test/*report-counters* report-counts]
        (runner/run-reader! :standalone root settled)))
    (is (= 3 (:pass @global-counts)))
    (is (= 3 (:pass @report-counts)))
    (is @settled)
    (is (= ["/usr/bin/timeout" "--kill-after=5s" "30s" "/approved/jolt"]
           (vec (take 4 (first @capture)))))
    (is (= {:timeout-ms 35000 :settlement-ms 5000} (second @capture)))
    (with-redefs [runner/executable! (fn [] "/approved/jolt")
                  child/run! (fn [_ options _]
                               (spit (:out options) ":durable-native-executed reader 0\n")
                               {:exit 143})]
      (let [failed? (try (runner/run-reader! :standalone root settled) false
                         (catch Throwable _ true))]
        (is failed?)
        (is (false? @settled))
        ;; Model the actual fixture's cleanup guard, not native FD injection.
        (when @settled (java.nio.file.Files/deleteIfExists root))
        (is (.exists (java.io.File. (str root))))))))
