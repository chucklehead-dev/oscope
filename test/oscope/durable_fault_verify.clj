(ns oscope.durable-fault-verify
  "Fresh read-only verification after a faulted writer has been SIGKILLed."
  (:require [clojure.data.json :as json]
            [db.jdbc]
            [jdbc.chdb.durable]
            [jdbc.chdb.durable.control :as control]
            [jdbc.chdb.durable.head :as head-codec]
            [jdbc.chdb.durable.local-posix :as local-posix]
            [jdbc.core :as jdbc]))

(defn- expectation [phase]
  (case phase
    "before" {:span-name "durable.fault.before" :expected 0}
    "after" {:span-name "durable.fault.after" :expected 1}
    (throw (ex-info "fault verification phase must be before or after"
                    {:oscope.durable-fault/error true}))))

(defn- fail! [message]
  (throw (ex-info message {:oscope.durable-fault/error true})))

(defn assert-response! [status response witness phase]
  (expectation phase)
  ;; This fixture targets the exporter's own post-batch publication barrier.
  ;; Its false result is translated to the SDK's closed OTLP failure envelope;
  ;; Oscope's later whole-request barrier must not be mistaken for this one.
  (when-not (and (= "503" status)
                 (map? response)
                 (= #{"code" "message"} (set (keys response)))
                 (integer? (get response "code"))
                 (= 14 (get response "code"))
                 (= "OTLP span export failed" (get response "message"))
                 (map? witness)
                 (integer? (get witness "version"))
                 (integer? (get witness "hit"))
                 (= {"version" 1 "kind" "wal" "hit" 1 "phase" phase
                     "stage" "exporter-publication"} witness))
    (fail! "fault rejection requires the exact OTLP failure and unique injected witness"))
  true)

(defn checked-response-json! [wire]
  (when-not (and (string? wire) (pos? (count wire))
                 (<= (alength (.getBytes wire "UTF-8")) 1024))
    (fail! "bounded response or witness JSON required"))
  (let [value (json/read-str wire)]
    ;; Both fixture producers write canonical JSON through this pinned codec.
    ;; Exact re-encoding rejects duplicate keys or trailing JSON that read-str
    ;; alone could otherwise discard while retaining an apparently valid map.
    (when-not (= wire (json/write-str value))
      (fail! "canonical response or witness JSON required"))
    value))

(defn read-response-json! [path]
  (let [file (java.io.File. path)]
    (when-not (and (.isFile file) (pos? (.length file)) (<= (.length file) 1024)
                   (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
      (fail! "bounded response or witness file required"))
    (checked-response-json! (slurp file))))

(defn- sha256 [wire]
  (apply str (map #(format "%02x" (bit-and % 255))
                  (.digest (java.security.MessageDigest/getInstance "SHA-256")
                           (.getBytes wire "UTF-8")))))

(defn startup-seal [head]
  (head-codec/validate! head)
  (when-not (and (some? (get-in head ["lease" "owner"]))
                 (some? (get-in head ["manifest" "base"]))
                 (empty? (get-in head ["manifest" "wal"]))
                 (< (get-in head ["manifest" "seq"]) head-codec/max-safe-integer))
    (fail! "fault fixture requires an active checkpoint-only startup head"))
  (let [wire (json/write-str head)]
    (when (> (count wire) 65536) (fail! "startup head seal exceeds fixture bound"))
    {"version" 1 "head-json" wire "head-sha256" (sha256 wire)}))

(defn checked-startup-seal! [seal]
  (let [wire (get seal "head-json")]
    (when-not (and (= #{"version" "head-json" "head-sha256"} (set (keys seal)))
                   (= 1 (get seal "version"))
                   (string? wire) (<= (count wire) 65536)
                   (= (sha256 wire) (get seal "head-sha256")))
      (fail! "invalid startup head seal"))
    ;; Use the maintained bounded protocol decoder, not a custom head parser.
    (let [head (head-codec/decode (.getBytes wire "UTF-8"))]
      (startup-seal head)
      head)))

(defn assert-manifest! [startup final phase]
  (expectation phase)
  (head-codec/validate! startup)
  (head-codec/validate! final)
  (let [before (get startup "manifest") after (get final "manifest")
        expected-manifest (if (= phase "before") before
                            (assoc before "seq" (inc (get before "seq"))
                                          "wal" (get after "wal")))
        ;; Only same-owner heartbeat expiry and the selected WAL publication
        ;; may differ, including unknown extension fields retained by V1.
        expected-head (assoc startup "manifest" expected-manifest
                             "lease" (assoc (get startup "lease") "expires_at"
                                            (get-in final ["lease" "expires_at"])))]
    (when-not (and (= expected-head final)
                   (= (if (= phase "before") 0 1) (count (get after "wal"))))
      (fail! "fault recovery differs from frozen startup manifest")))
  true)

(defn- read-seal! [path]
  (let [file (java.io.File. path)]
    (when-not (and (.isFile file) (<= (.length file) 262144)
                   (not (java.nio.file.Files/isSymbolicLink (.toPath file))))
      (fail! "bounded startup seal file required"))
    (checked-startup-seal! (json/read-str (slurp file)))))

(defn- capture-startup! [root path]
  (let [backend (local-posix/local-backend root)
        head (some-> (control/read-head-read-only! backend) :head)
        seal (startup-seal head)
        file (java.io.File. path)]
    (when-not (.createNewFile file) (fail! "startup seal must not overwrite evidence"))
    (spit file (json/write-str seal))
    (println "PASS: bounded startup head frozen before HTTP request")))

(defn- verify! [root phase seal-path]
  (let [{:keys [span-name expected]} (expectation phase)
        startup (read-seal! seal-path)
        backend (local-posix/local-backend root)
        head (some-> (control/read-head-read-only! backend) :head)]
    (assert-manifest! startup head phase)
    (with-open [connection
                (jdbc/connection
                 (jdbc.chdb.durable/snapshot-dbspec {:backend backend}))]
      (let [{:keys [n selected]}
            (jdbc/fetch-one
             connection
             (str "select count() as n, countIf(SpanName = '"
                  span-name "') as selected from otel_traces"))]
        (when-not (and (= expected n) (= expected selected))
          (throw (ex-info "fault recovery count mismatch"
                          {:oscope.durable-fault/error true
                           :phase phase
                           :expected expected
                           :actual n
                           :selected selected})))
        (println (str "PASS: " phase " fault returned no acknowledgement and "
                      "fresh recovery observed " n " rows"))))))

(defn -main [& args]
  (cond
    (and (= 5 (count args)) (= "--verify-response" (first args)))
    (do (assert-response! (second args)
                          (read-response-json! (nth args 2))
                          (read-response-json! (nth args 3)) (nth args 4))
        (println "PASS: exact OTLP rejection and unique injected WAL witness confirmed"))

    (and (= 3 (count args)) (= "--capture-startup" (first args)))
    (capture-startup! (second args) (nth args 2))

    (and (= 3 (count args)) (contains? #{"before" "after"} (second args)))
    (verify! (first args) (second args) (nth args 2))

    :else (fail! "invalid fault verifier arguments")))
