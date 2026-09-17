(ns oscope.durable-fault-target-test
  "Pure real-provider controls, not native CAS or HTTP acknowledgement proof."
  (:require [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable.control :as control]
            [jolt.aspect-packs.chdb-durable.faults :as faults]
            [oscope.durable-fault-server-main :as target]
            [oscope.durable-fault-verify :as verify]))

(def commit-point {:id :durable/commit-reference})
(defn- invoke [kind proceed]
  (target/around-request-wal commit-point [::store ::token {:kind kind}] proceed))

(deftest startup-checkpoints-cannot-consume-request-fault
  (doseq [phase ["before" "after"] prefix [0 1 2 5]]
    (let [calls (atom []) error (:error (target/fault-action phase))]
      (faults/call-with-fault
       (assoc (target/fault-action phase) :error error)
       (fn []
         (dotimes [_ prefix]
           (invoke :checkpoint #(do (swap! calls conj :checkpoint) :committed)))
         (is (= prefix (count @calls)))
         (let [caught (try
                        (invoke :wal #(do (swap! calls conj :wal) :committed))
                        nil
                        (catch Throwable caught caught))]
           (is (identical? error caught))
           (is (= (cond-> (vec (repeat prefix :checkpoint))
                    (= phase "after") (conj :wal)) @calls)))
         ;; Only the selected first WAL is injected; later WALs are unchanged.
         (is (= :committed (invoke :wal (constantly :committed)))))))))

(deftest unknown-reference-kind-fails-before-proceed
  (doseq [args [nil [] [::store ::token {}]
               [::store ::token {:kind :other}]]]
    (let [calls (atom 0)
          caught (try (target/around-request-wal
                       commit-point args #(swap! calls inc)) nil
                      (catch Throwable caught caught))]
      (is (= true (:oscope.durable-fault/error (ex-data caught))))
      (is (zero? @calls))))
  (is (= :untouched (target/around-request-wal
                    {:id :durable/renew} nil (constantly :untouched)))))

(defn- reference [kind seq]
  {"key" (str (if (= kind :wal) "wal/" "checkpoints/")
              "1-" seq "-abcdef01." (if (= kind :wal) "jsonl" "tar.gz"))
   "size" 1 "sha256" (apply str (repeat 64 "a"))})

(defn- startup [seq]
  (-> (control/fresh-head {:owner "fault-owner" :instance "fault-instance"
                           :expires-at 3000 :database "default"
                           :engine-version "26.7.3" :backup-format 1
                           :min-reader "26.7.3"})
      (assoc "manifest" {"db" "default" "seq" seq
                         "base" (reference :checkpoint seq) "wal" []})))

(defn- after-head [head]
  (-> head
      (update-in ["manifest" "seq"] inc)
      (assoc-in ["manifest" "wal"]
                [(reference :wal (inc (get-in head ["manifest" "seq"])))])))

(deftest manifest-projection-uses-frozen-startup-not-an-ordinal
  (doseq [seq [1 2 5]]
    (let [head (startup seq)
          sealed (verify/checked-startup-seal! (verify/startup-seal head))]
      (is (= head sealed))
      ;; Heartbeat expiry may advance without changing captured owner identity.
      (is (true? (verify/assert-manifest!
                  sealed (assoc-in head ["lease" "expires_at"] 3001) "before")))
      (is (true? (verify/assert-manifest! sealed (after-head head) "after"))))))

(deftest frozen-head-and-transition-controls-reject-mismatches
  (let [head (startup 2) final (after-head head)]
    (doseq [[candidate phase]
            [[final "before"] [head "after"]
             [(assoc-in final ["manifest" "base"] (reference :checkpoint 1)) "after"]
             [(assoc-in final ["manifest" "db"] "different") "after"]
             [(assoc-in final ["lease" "instance"] "different") "after"]
             [(assoc-in final ["engine" "version"] "26.7.4") "after"]
             [(assoc final "unexpected" "private") "after"]
             [(assoc-in final ["manifest" "wal"] []) "after"]]]
      (is (try (verify/assert-manifest! head candidate phase) false
               (catch Throwable _ true))))
    (doseq [seal [nil {} (assoc (verify/startup-seal head) "version" 2)
                 (assoc (verify/startup-seal head) "head-sha256" "wrong")
                 (assoc (verify/startup-seal head) "unexpected" "private")]]
      (is (try (verify/checked-startup-seal! seal) false
               (catch Throwable _ true))))))
