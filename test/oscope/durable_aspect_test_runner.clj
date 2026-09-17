(ns oscope.durable-aspect-test-runner
  (:require [clojure.string :as str]
            [clojure.test :as test]
            [jolt.aspect-packs.history :as history]
            [oscope.child-support :as child]
            [oscope.durable-history-assertions :as assertions]
            [oscope.durable-history-assertions-test]
            [oscope.durable-integration-test]
            [oscope.durable-native-child-runner :as native-child]
            [oscope.durable-telemetry-assertions :as telemetry]
            [otel.exporter.memory :as memory]
            [otel.sdk :as sdk]))

(def ^:private woven-fixtures
  [(var oscope.durable-integration-test/startup-rejects-live-owner-and-forced-takeover-fences-it)
   (var oscope.durable-integration-test/corrupt-head-fails-before-ingress)
   (var oscope.durable-integration-test/standalone-server-flushes-through-durable-jdbc-adapter)])

(defn prepare-fixture! [index]
  (let [v (get woven-fixtures index)
        actual (set (for [[name v] (ns-publics 'oscope.durable-integration-test)
                          :when (:test (meta v))] name))
        expected #{'startup-rejects-live-owner-and-forced-takeover-fences-it
                   'corrupt-head-fails-before-ingress
                   'standalone-server-flushes-through-durable-jdbc-adapter}]
    (when-not (and (= actual expected) v (ifn? (:test (meta v))))
      (throw (ex-info "woven native fixture inventory changed" {})))
    v))

(defn checked-woven-receipt [result output index]
  (let [receipt (native-child/checked-receipt result output "fixture" index)
        markers (filter #(str/starts-with? % ":durable-woven-history ")
                        (str/split-lines output))
        match (when (= 1 (count markers))
                (re-matches #":durable-woven-history fixture 2 ([0-9]{1,9}) ([0-9]{1,9})"
                            (first markers)))
        history? (if (= index 2)
                   (and match (pos? (parse-long (second match)))
                        (pos? (parse-long (nth match 2))))
                   (empty? markers))]
    ;; Physical settlement and successful assertion/history delivery differ.
    (assoc receipt :ok? (boolean (and (:ok? receipt) history?)))))

(defn run-isolated! [executable directory]
  ;; The caller owns the whole parent wall bound. Three60s waits plus5s
  ;; settlement fit195s; an unconfirmed nested final stops further launches.
  (let [deadline (+ (System/nanoTime) (* 195000 1000000))]
    (loop [index 0 totals {:test 0 :pass 0 :fail 0 :error 0} qualified? true]
      (if (= 3 index)
        {:totals totals :qualified? qualified? :settled? true}
        (if (> (+ (System/nanoTime) (* 65000 1000000)) deadline)
          {:totals totals :qualified? false :settled? true}
          (let [out (java.io.File. (str directory "/woven-" index ".log"))
                err (java.io.File. (str directory "/woven-" index ".err"))
                result (child/run! [executable "--fixture" (str index)]
                                   {:out out :err err}
                                   {:timeout-ms 60000 :settlement-ms 5000})
                terminal? (and (integer? (:exit result))
                               (not= false (:child/terminal? result)))
                receipt (checked-woven-receipt
                         result (if (and terminal? (.exists out)) (slurp out) "") index)
                totals (if (:valid? receipt)
                         (merge-with + totals (:counts receipt)) totals)]
            (println :durable-woven-child index :exit (:exit result)
                     :valid (:valid? receipt) :settled (:settled? receipt)
                     :qualified (:ok? receipt))
            (flush)
            (if (:settled? receipt)
              (recur (inc index) totals (and qualified? (:ok? receipt)))
              {:totals totals :qualified? false :settled? false})))))))

(defn- validate-standalone! [journal exporter handle private-values]
  (let [events (history/events journal)
        commands
        (assertions/assert-ingest-history!
         {:journal journal :events events
          :context-id :oscope-durable-integration
          :private-values private-values
          ;; Exporter schema checkpoint, then server ingress checkpoint;
          ;; both precede the three confirmed logical ingest batches.
          :expected-publication-kinds [:checkpoint :checkpoint :wal :wal :wal]
          :require-renewal? true})
        [spans durations] (telemetry/validate! exporter handle private-values)
        printed (pr-str [events spans durations])]
    (doseq [private-value private-values]
      (when (.contains printed private-value)
        (throw (ex-info "oscope Durable diagnostics retained private data"
                        {:secret-class :durable-private-data}))))
    (println :durable-woven-history "fixture" 2 (count commands) (count spans))
    (flush)))

(defn- run-fixture! [index]
  (let [v (prepare-fixture! index)
        journal (history/journal)
        exporter (memory/multisignal-exporter)
        handle (sdk/init! {:service-name "oscope-durable-aspect-test"
                           :exporter exporter :processor :simple
                           :runtime-metrics? false :logs? false
                           :bridge-logging? false})
        private-values ["oscope-test" "oscope-test-instance" "head.json"
                        "wal/" "checkpoints/"]]
    (try
      (println :durable-native-executed "fixture" index)
      (flush)
      (binding [history/*journal* journal
                history/*context-id* :oscope-durable-integration]
        (test/test-vars [v]))
      (let [{:keys [test pass fail error]} @test/counters]
        (when (and (= index 2) (= test 1) (pos? pass) (zero? (+ fail error)))
          (validate-standalone! journal exporter handle private-values)))
      (finally (sdk/shutdown! handle)))
    (let [{:keys [test pass fail error]} @test/counters
          settled (native-child/known-native-subtree-settled?)]
      (println :durable-native-receipt "fixture" index test pass fail error
               (if settled 1 0))
      (flush)
      (System/exit (if (and (= 1 test) (pos? pass) (zero? (+ fail error)) settled) 0 1)))))

(defn -main [& args]
  (try
    (if (and (= 2 (count args)) (= "--fixture" (first args))
             (re-matches #"[0-2]" (second args)))
      (run-fixture! (parse-long (second args)))
      (do
        (when (seq args) (throw (ex-info "unknown woven child arguments" {})))
        (let [result (test/run-tests 'oscope.durable-history-assertions-test)]
          (when-not (and (= 6 (:test result)) (pos? (:pass result))
                         (zero? (+ (:fail result) (:error result))))
            (throw (ex-info "woven history controls did not qualify" {}))))
        (let [executable (System/getenv "OSCOPE_DURABLE_WOVEN_EXECUTABLE")
              file (when executable (java.io.File. executable))
              directory (str (java.nio.file.Files/createTempDirectory
                              "oscope-durable-woven-"
                              (make-array java.nio.file.attribute.FileAttribute 0)))]
          (when-not (and file (.isAbsolute file) (.isFile file) (.canExecute file))
            (throw (ex-info "absolute woven executable required" {})))
          (println :durable-woven-evidence directory)
          (let [result (run-isolated! executable directory)]
            (println :durable-woven-total (:totals result))
            (flush)
            (when-not (and (:qualified? result) (:settled? result)
                           (= 3 (get-in result [:totals :test])))
              (throw (ex-info "woven native fixtures did not qualify" {})))))))
    (catch Throwable _
      (when-not (native-child/known-native-subtree-settled?)
        (println :durable-native-nested-unsettled))
      (println :durable-woven-failed)
      (flush)
      (System/exit 1))))
