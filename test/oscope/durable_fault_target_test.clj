(ns oscope.durable-fault-target-test
  "Pure real-provider controls, not native CAS or HTTP acknowledgement proof."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [jdbc.chdb.durable.control :as control]
            [jolt.aspect-packs.chdb-durable.faults :as faults]
            [oscope.durable-fault-server-main :as target]
            [oscope.durable-fault-verify :as verify]))

(def commit-point {:id :durable/commit-reference})
(defn- invoke [kind proceed]
  (target/around-request-wal commit-point [::store ::token {:kind kind}] proceed))

(defn- witness [phase]
  {"version" 1 "kind" "wal" "hit" 1 "phase" phase
   "stage" "exporter-publication"})

(deftest injected-witness-is-identity-checked-and-unique
  (doseq [phase ["before" "after"]]
    (let [records (atom []) action (target/fault-action phase) error (:error action)]
      (binding [target/*fault-witness!* #(swap! records conj %)]
        (faults/call-with-fault
         action
         (fn []
           (is (nil? (target/fault-witness
                      (ex-info "unrelated failure" (ex-data error)))))
           (is (identical? error (try (invoke :wal (constantly :committed))
                                     (catch Throwable caught caught))))
           (is (= [(witness phase)] @records))
           (is (= :committed (invoke :wal (constantly :committed))))
           (is (= [(witness phase)] @records)))))))
  ;; A witness sink failure cannot replace the primary injected failure. It
  ;; must leave a closed, explicit failure receipt, which the shell rejects.
  (let [action (target/fault-action "before") error (:error action)
        output (java.io.StringWriter.) caught (atom nil)]
    (binding [*out* output
              target/*fault-witness!* (fn [_] (throw (ex-info "private IO failure" {})))]
      (faults/call-with-fault
       action #(reset! caught (try (invoke :wal (constantly :committed))
                                  (catch Throwable caught caught)))))
    (is (identical? error @caught))
    (is (= "FAIL: request WAL fault witness persistence failed\n" (str output)))))

(deftest response-oracle-requires-exact-exporter-rejection-and-witness
  (let [response {"code" 14 "message" "OTLP span export failed"}]
    (doseq [phase ["before" "after"]]
      (is (true? (verify/assert-response! "503" response (witness phase) phase))))
    ;; Causal red control: the old whole-request text is not the selected
    ;; exporter-owned publication failure and must remain rejected.
    (doseq [[status body record]
            [["503" "durability boundary failed" (witness "before")]
             ["200" response (witness "before")]
             ["503" (assoc response "code" 14.0) (witness "before")]
             ["503" (assoc response "code" 13) (witness "before")]
             ["503" (assoc response "message" "other failure") (witness "before")]
             ["503" (assoc response "extra" "private") (witness "before")]
             ["503" {"code" 14} (witness "before")]
             ["503" response nil]
             ["503" response [(witness "before") (witness "before")]]
             ["503" response (witness "after")]
             ["503" response (assoc (witness "before") "hit" 2)]
             ["503" response (assoc (witness "before") "hit" 1.0)]
             ["503" response (assoc (witness "before") "version" 1.0)]
             ["503" response (assoc (witness "before") "stage" "whole-request")]
             ["503" response (assoc (witness "before") "extra" true)]]]
      (is (try (verify/assert-response! status body record "before") false
               (catch Throwable _ true))))))

(deftest witness-file-create-new-and-response-file-controls
  (let [file (java.io.File/createTempFile "oscope-fault-pure-" ".json")
        path (.getAbsolutePath file) record (witness "before")]
    (try
      (is (.delete file))
      (is (try (verify/read-response-json! path) false (catch Throwable _ true)))
      (target/publish-witness! path record)
      (is (= record (verify/read-response-json! path)))
      (let [frozen (slurp file)]
        (is (try (target/publish-witness! path (witness "after")) false
                 (catch Throwable _ true)))
        (is (= frozen (slurp file)) "a duplicate witness cannot overwrite evidence"))
      (doseq [wire ["" "{" (apply str (repeat 1025 "x"))]]
        (spit file wire)
        (is (try (verify/read-response-json! path) false (catch Throwable _ true))))
      (finally (.delete file)))))

(deftest response-json-controls-reject-discarded-or-unbounded-input
  (let [response {"code" 14 "message" "OTLP span export failed"}]
    (is (= response (verify/checked-response-json! (json/write-str response))))
    (is (= (witness "before")
           (verify/checked-response-json! (json/write-str (witness "before")))))
    (doseq [wire [nil "" "{" (apply str (repeat 1025 "x"))
                 "{\"code\":14,\"code\":14,\"message\":\"OTLP span export failed\"}"
                 (str (json/write-str response) "{}")
                 (str (json/write-str (witness "before"))
                      (json/write-str (witness "before")))]]
      (is (try (verify/checked-response-json! wire) false
               (catch Throwable _ true))))))

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
