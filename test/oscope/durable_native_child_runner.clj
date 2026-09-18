(ns oscope.durable-native-child-runner
  "Test-only native storage generation ownership: one lifetime per process."
  (:require [clojure.test :as test]
            [clojure.string :as str]
            [oscope.child-support :as child]))

(def fixtures
  ['oscope.durable-integration-test/startup-rejects-live-owner-and-forced-takeover-fences-it
   'oscope.durable-integration-test/corrupt-head-fails-before-ingress
   'oscope.durable-integration-test/standalone-server-flushes-through-durable-jdbc-adapter
   'oscope.embedded-durable-integration-test/approved-manifest-drives-embedded-sdk-ingestion-and-live-query
   'oscope.embedded-durable-integration-test/direct-sdk-exports-survive-a-fresh-durable-reader
   'oscope.embedded-durable-integration-test/dual-export-preserves-local-and-remote-trace-identity])

(def ^:private nested-settled? (atom true))

(defn known-native-subtree-settled?
  "Final receipt evidence for the independently joined recovery-reader seam."
  []
  @nested-settled?)
(def ^:private readers
  {'standalone 'oscope.durable-integration-test/assert-fresh-reader!
   'embedded 'oscope.embedded-durable-integration-test/assert-fresh-reader!
   's3 'oscope.durable-s3-integration-test/assert-fresh-reader!})

(defn reader-index! [reader]
  (case reader
    :standalone 0
    :embedded 1
    :s3 2
    (throw (ex-info "unknown native reader" {}))))

(defn executable! []
  (let [path (System/getenv "JOLT_BIN")
        file (when path (java.io.File. path))]
    (when-not (and file (.isAbsolute file) (.isFile file) (.canExecute file))
      (throw (ex-info "absolute qualified native executable required" {})))
    path))

(defn prepare-fixture! [index]
  (let [fixture (get fixtures index)]
    (when-not fixture (throw (ex-info "unknown native fixture" {})))
    (let [n (symbol (namespace fixture))]
      (require n)
      (let [expected (set (filter #(= (namespace fixture) (namespace %)) fixtures))
            actual (set (for [[name v] (ns-publics n) :when (:test (meta v))]
                          (symbol (str n) (str name))))
            v (eval (list 'var fixture))]
        (when-not (and (= expected actual) (ifn? (:test (meta v))))
          (throw (ex-info "native fixture inventory changed" {})))
        v))))

(defn checked-receipt
  "Only one fixed execution marker and numeric receipt qualify a settled child."
  [result output kind index]
  (let [terminal? (and (integer? (:exit result))
                       (not= false (:child/terminal? result)))
        lines (str/split-lines output)
        executed (filter #(str/starts-with? % ":durable-native-executed ") lines)
        receipts (filter #(str/starts-with? % ":durable-native-receipt ") lines)
        match (when (= 1 (count receipts))
                (re-matches #":durable-native-receipt (fixture|reader) ([0-9]{1,9}) ([0-9]{1,9}) ([0-9]{1,9}) ([0-9]{1,9}) ([0-9]{1,9}) ([01])"
                            (first receipts)))
        numbers (when match (mapv parse-long (drop 2 match)))
        valid? (and terminal? (nil? (:child/status result)) match
                    (= kind (second match)) (= index (get numbers 0))
                    (= 1 (count executed))
                    (= (str ":durable-native-executed " kind " " index) (first executed))
                    (= (if (= kind "fixture") 1 0) (get numbers 1))
                    (pos? (reduce + 0 (take 3 (drop 2 numbers)))))
        settled? (boolean
                  (and terminal? valid?
                       (not (some #{":durable-native-nested-unsettled"} lines))
                       (= 1 (get numbers 5))))]
    {:terminal? terminal? :settled? settled? :valid? (boolean valid?)
     :counts (when valid? (zipmap [:test :pass :fail :error] (take 4 (drop 1 numbers))))
     :ok? (boolean (and valid? settled? (zero? (:exit result))
                       (= 0 (get numbers 3)) (= 0 (get numbers 4))))}))

(defn- launch! [executable directory kind index arguments timeout]
  (let [out (java.io.File. (str directory "/" kind "-" index ".log"))
        err (java.io.File. (str directory "/" kind "-" index ".err"))
        command (into [executable "-Srepro" "-A:test-durable" "-m"
                       "oscope.durable-native-child-runner"] arguments)
        ;; Independent Linux guardian survives an abrupt fixture-parent exit.
        ;; It bounds the leaf; only a valid final receipt proves joined ownership.
        command (if (= kind "reader")
                  (into ["/usr/bin/timeout" "--kill-after=5s" "30s"] command)
                  command)
        result (child/run! command
                           {:out out :err err}
                           {:timeout-ms timeout :settlement-ms 5000})
        terminal? (and (integer? (:exit result)) (not= false (:child/terminal? result)))
        receipt (checked-receipt result (if (and terminal? (.exists out)) (slurp out) "")
                                 kind index)]
    (println :durable-native-child kind index :exit (:exit result)
             :terminal (:terminal? receipt) :settled (:settled? receipt)
             :valid (:valid? receipt))
    receipt))

(defn- merge-counts! [counts]
  (swap! test/counters #(merge-with + % counts))
  (when test/*report-counters*
    (dosync (commute test/*report-counters* #(merge-with + % counts)))))

(defn failure-evidence-root
  "Explicit opt-in for the owned synthetic S3 fixture, not general diagnostics."
  []
  (System/getenv "OSCOPE_DURABLE_FAILURE_EVIDENCE_ROOT"))

(defn- evidence-file! [path directory?]
  (let [file (java.io.File. (str path))]
    (when-not (and (.isAbsolute file)
                   (= (.getAbsolutePath file) (.getCanonicalPath file))
                   (if directory? (.isDirectory file) (.isFile file)))
      (throw (ex-info "invalid owned failure evidence path" {})))
    file))

(defn prepare-reader-evidence!
  "Copy exact private seal bytes before launch into a fresh owned reader dir.
  No seal, backend or log is rewritten for diagnostic display."
  [root seal]
  (let [directory (evidence-file! root true)
        scope (evidence-file! (str directory "/scope") false)
        source (evidence-file! seal false)
        reader (java.io.File. (str directory "/reader"))]
    (when-not (and (<= (.length scope) 64)
                   (= "oscope-synthetic-s3-v1\n" (slurp scope))
                   (pos? (.length source)) (<= (.length source) 1048576)
                   (.mkdir reader))
      (throw (ex-info "invalid synthetic reader evidence scope" {})))
    (let [wire (java.nio.file.Files/readAllBytes (.toPath source))]
      (when-not (<= 1 (alength wire) 1048576)
        (throw (ex-info "bounded reader seal required" {})))
      (java.nio.file.Files/write
       (.toPath (java.io.File. (str reader "/seal.json"))) wire
       (into-array java.nio.file.OpenOption
                   [java.nio.file.StandardOpenOption/CREATE_NEW
                    java.nio.file.StandardOpenOption/WRITE])))
    (str reader)))

(defn publish-reader-evidence!
  "Closed numeric observation of the checked receipt; absent means unknown.
  CREATE_NEW rejects duplicate observations instead of replacing evidence."
  [directory receipt]
  (let [counts (:counts receipt)
        values (mapv #(get counts % 0) [:test :pass :fail :error])]
    (when-not (and (every? #(and (integer? %) (<= 0 % 999999999)) values)
                   (every? #(contains? #{true false} (get receipt %))
                           [:terminal? :valid? :settled? :ok?]))
      (throw (ex-info "invalid reader evidence receipt" {})))
    (java.nio.file.Files/write
     (.toPath (java.io.File. (str directory "/settlement.receipt")))
     (.getBytes (str (str/join " "
                               (concat [1 2] values
                                       (map #(if (get receipt %) 1 0)
                                            [:terminal? :valid? :settled? :ok?])))
                    "\n") "UTF-8")
     (into-array java.nio.file.OpenOption
                 [java.nio.file.StandardOpenOption/CREATE_NEW
                  java.nio.file.StandardOpenOption/WRITE]))))

(defn run-reader!
  "Reader assertions execute after writer retirement in a different process.
  Caller deletes its store ONLY when settlement is confirmed; logs are retained."
  [reader root settled]
  (reset! settled false)
  (reset! nested-settled? false)
  (let [index (reader-index! reader)
        evidence (when (= reader :s3) (failure-evidence-root))
        directory (if evidence
                    (prepare-reader-evidence! evidence root)
                    (str (java.nio.file.Files/createTempDirectory
                          "oscope-durable-reader-" (make-array java.nio.file.attribute.FileAttribute 0))))
        receipt (launch! (executable!) directory "reader" index
                         ["--reader" (name reader) (str root)] 35000)]
    ;; Persist the observation before permitting the fixture's cleanup guard.
    ;; IO failure remains a qualification failure with the seal/logs retained.
    (when evidence (publish-reader-evidence! directory receipt))
    (reset! settled (:settled? receipt))
    (reset! nested-settled? (:settled? receipt))
    (when (:valid? receipt) (merge-counts! (:counts receipt)))
    (when-not (:ok? receipt)
      (throw (ex-info "fresh native reader did not qualify" {})))))

(defn run-isolated! [executable directory]
  ;; Six bounded waits plus settlement total at most390s, leaving30s budget
  ;; for orchestration. The monotonic launch deadline prevents another spawn
  ;; once that native-phase budget is consumed; callers still own outer timeout.
  (let [deadline (+ (System/nanoTime) (* 420000 1000000))]
  (loop [index 0 totals {:test 0 :pass 0 :fail 0 :error 0} qualified? true]
    (if (= index (count fixtures))
      {:totals totals :qualified? qualified? :settled? true}
      (if (> (+ (System/nanoTime) (* 65000 1000000)) deadline)
        {:totals totals :qualified? false :settled? true}
      (let [receipt (launch! executable directory "fixture" index
                             ["--fixture" (str index)] 60000)
            totals (if (:valid? receipt) (merge-with + totals (:counts receipt)) totals)]
        (if (:settled? receipt)
          (recur (inc index) totals (and qualified? (:ok? receipt)))
          {:totals totals :qualified? false :settled? false})))))))

(defn- emit-receipt! [kind index expected-tests]
  (let [{:keys [test pass fail error]} @test/counters
        settled @nested-settled?]
    (println :durable-native-receipt kind index test pass fail error (if settled 1 0))
    (flush)
    (System/exit (if (and (= expected-tests test) (pos? (+ pass fail error))
                          settled (zero? (+ fail error))) 0 1))))

(defn -main [& args]
  (try
    (cond
      (and (= 2 (count args)) (= "--fixture" (first args))
           (re-matches #"[0-5]" (second args)))
      (let [index (parse-long (second args)) v (prepare-fixture! index)]
        (println :durable-native-executed "fixture" index)
        (test/test-vars [v])
        (emit-receipt! "fixture" index 1))

      (and (= 3 (count args)) (= "--reader" (first args))
           (contains? readers (symbol (second args))))
      (let [reader (symbol (second args)) fixture (get readers reader)
            n (symbol (namespace fixture))
            index (reader-index! (keyword (str reader)))]
        (require n)
        (println :durable-native-executed "reader" index)
        ((ns-resolve n (symbol (name fixture))) (java.io.File. (nth args 2)))
        (emit-receipt! "reader" index 0))

      :else (throw (ex-info "unknown native child arguments" {})))
    (catch Throwable _
      (when-not @nested-settled? (println :durable-native-nested-unsettled))
      (println :durable-native-startup-failed)
      (flush)
      (System/exit 1))))
