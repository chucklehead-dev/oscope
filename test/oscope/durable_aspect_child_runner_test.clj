(ns oscope.durable-aspect-child-runner-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.child-support :as child]
            [oscope.durable-aspect-test-runner :as runner]))

(defn- receipt [index passes]
  ;; SYNTHETIC fixture contract, not actual native or woven history evidence.
  (str ":durable-native-executed fixture " index "\n"
       (when (= 2 index) ":durable-woven-history fixture 2 12 8\n")
       (when (= 2 index) ":durable-woven-reader fixture 2 standalone 0 0 16 0 0 1 1 1 1\n")
       ":durable-native-receipt fixture " index " 1 " passes " 0 0 1\n"))

(deftest woven-native-inventory-retains-all-three-true-vars
  (doseq [index [0 1 2]]
    (is (ifn? (:test (meta (runner/prepare-fixture! index)))))))

(deftest woven-final-receipts-require-real-history-and-nonzero-counts
  (let [check #(runner/checked-woven-receipt {:exit 0} % 2)
        good (receipt 2 36)]
    (is (:ok? (check good)))
    (is (= {:test 1 :pass 36 :fail 0 :error 0} (:counts (check good))))
    (doseq [bad ["" (str good good)
                 (receipt 1 36)
                 (receipt 2 0)
                 (.replace good ":durable-woven-history fixture 2 12 8\n" "")
                 (.replace good ":durable-woven-reader fixture 2 standalone 0 0 16 0 0 1 1 1 1\n" "")
                 (.replace good "fixture 2 12 8" "fixture 2 0 8")
                 (.replace good "fixture 2 12 8" "fixture 2 12 0")
                 (.replace good "standalone 0 0 16 0 0 1 1 1 1" "embedded 1 0 16 0 0 1 1 1 1")
                 (.replace good "standalone 0 0 16 0 0 1 1 1 1" "standalone 0 1 15 0 0 1 1 1 1")
                 (.replace good "standalone 0 0 16 0 0 1 1 1 1" "standalone 0 0 16 0 0 1 1 0 1")
                 (.replace good
                           (str ":durable-woven-history fixture 2 12 8\n"
                                ":durable-woven-reader fixture 2 standalone 0 0 16 0 0 1 1 1 1\n")
                           (str ":durable-woven-reader fixture 2 standalone 0 0 16 0 0 1 1 1 1\n"
                                ":durable-woven-history fixture 2 12 8\n"))
                 (str good ":durable-woven-history fixture 2 12 8\n")
                 (str good ":durable-woven-reader fixture 2 standalone 0 0 16 0 0 1 1 1 1\n")
                 (str good ":durable-native-nested-unsettled\n")]]
      (is (not (:ok? (check bad)))))
    (is (not (:settled? (check ""))))
    (is (not (:ok? (runner/checked-woven-receipt {:exit 1} good 2))))
    (is (not (:ok? (runner/checked-woven-receipt
                   {:exit 0 :child/terminal? false} good 2))))
    (is (:ok? (runner/checked-woven-receipt {:exit 0} (receipt 0 4) 0)))
    (is (not (:ok? (runner/checked-woven-receipt
                   {:exit 0} (str (receipt 0 4)
                                  ":durable-woven-history fixture 2 12 8\n") 0))))))

(deftest woven-orchestration-uses-the-same-image-and-stops-unconfirmed-ownership
  (let [directory (str (java.nio.file.Files/createTempDirectory
                        "oscope-woven-runner-control-"
                        (make-array java.nio.file.attribute.FileAttribute 0)))
        calls (atom [])]
    (with-redefs [child/run!
                  (fn [command options bounds]
                    (let [index (parse-long (last command))]
                      (swap! calls conj [command bounds])
                      (spit (:out options) (receipt index (if (= 2 index) 36 4)))
                      {:exit 0}))]
      (let [result (runner/run-isolated! "/owned/same-woven-image" directory)]
        (is (:qualified? result))
        (is (:settled? result))
        (is (= {:test 3 :pass 44 :fail 0 :error 0} (:totals result)))
        (is (= [["/owned/same-woven-image" "--fixture" "0"]
                ["/owned/same-woven-image" "--fixture" "1"]
                ["/owned/same-woven-image" "--fixture" "2"]]
               (mapv first @calls)))
        (is (every? #(= {:timeout-ms 60000 :settlement-ms 5000} (second %)) @calls))))
    (reset! calls [])
    (with-redefs [child/run!
                  (fn [command options _]
                    (swap! calls conj command)
                    ;; Terminal outer process without final nested ownership.
                    (spit (:out options) "")
                    {:exit 143})]
      (let [result (runner/run-isolated! "/owned/same-woven-image" directory)]
        (is (not (:qualified? result)))
        (is (false? (:settled? result)))
        (is (= 1 (count @calls)))
        (is (= {:test 0 :pass 0 :fail 0 :error 0} (:totals result)))))))
