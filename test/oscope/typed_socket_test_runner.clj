(ns oscope.typed-socket-test-runner
  "Fresh process per fixture: a logical last close does not reset chDB's anchor."
  (:require [clojure.test :as test]
            [clojure.string :as str]
            [oscope.child-support :as child]))

(def ^:private fixtures
  ['oscope.typed-standalone-restart-integration-test/file-configured-typed-standalone-restarts-read-only
   'oscope.typed-standalone-restart-integration-test/standalone-log-capability-crosses-real-socket-and-retains-fallback
   'oscope.typed-standalone-restart-integration-test/file-configured-typed-log-query-survives-acquire-restart
   'oscope.synchronous-finite-socket-integration-test/retirement-helper-preserves-primary-and-accounts-for-ownership
   'oscope.synchronous-finite-socket-integration-test/synchronous-finite-inputs-cross-local-socket-without-poisoning-series])

(defn startup-failure-summary
  "Fixed test-only diagnostics; never render exception messages, data or causes."
  [phase error]
  {:phase (case phase
            :fixture-select :fixture-select
            :fixture-require :fixture-require
            :fixture-inventory :fixture-inventory
            :fixture-resolve :fixture-resolve
            :fixture-test :fixture-test
            :runner-executable :runner-executable
            :runner-directory :runner-directory
            :runner-children :runner-children
            :runner-arguments :runner-arguments
            :unknown)
   :type (try
           ;; Compare against an allowlist, but emit only hardcoded tokens.
           (case (.getSimpleName (class error))
             "ExceptionInfo" :exception-info
             "IllegalArgumentException" :illegal-argument
             "IllegalStateException" :illegal-state
             "ClassNotFoundException" :class-not-found
             "FileNotFoundException" :file-not-found
             "RuntimeException" :runtime
             "AssertionError" :assertion
             :other)
           (catch Throwable _ :other))})

(defn report-startup-failure!
  [phase error]
  (let [{:keys [phase type]} (startup-failure-summary phase error)]
    (println :typed-socket-startup-failure :phase phase :type type)))

(defn prepare-fixture!
  "Resolve one fixture without execution; phase is a caller-owned diagnostic atom."
  [index phase]
  (let [fixture (get fixtures index)
        ns-name (some-> fixture namespace symbol)]
    (reset! phase :fixture-select)
    (when-not fixture (throw (ex-info "unknown fixture" {})))
    (reset! phase :fixture-require)
    (require ns-name)
    ;; Detect an added/removed deftest rather than silently leaving it unrun.
    (reset! phase :fixture-inventory)
    (let [expected (set (filter #(= (namespace fixture) (namespace %)) fixtures))
          actual (set (for [[name var] (ns-publics ns-name)
                            :when (:test (meta var))]
                        (symbol (str ns-name) (str name))))]
      (when-not (= expected actual)
        (throw (ex-info "fixture inventory changed" {}))))
    (reset! phase :fixture-resolve)
    (or (ns-resolve ns-name (symbol (name fixture)))
        (throw (ex-info "fixture var unavailable" {})))))

(defn- run-fixture! [index phase]
  (let [fixture-var (prepare-fixture! index phase)]
    (println :typed-socket-executed index)
    (reset! phase :fixture-test)
    (let [counts (ref test/*initial-report-counters*)]
      (binding [test/*report-counters* counts]
        (test/test-vars [fixture-var]))
      (let [{:keys [test pass fail error]} @counts]
        (println :typed-socket-receipt index test pass fail error)
        (System/exit (if (and (= 1 test) (pos? (+ pass fail error))
                              (zero? (+ fail error))) 0 1))))))

(defn- executable! []
  (let [path (System/getenv "JOLT_TEST_CHILD_EXECUTABLE")
        file (when path (java.io.File. path))]
    ;; PATH lookup may be shadowed by a toolchain wrapper. Never qualify it.
    (when-not (and file (.isAbsolute file) (.isFile file) (.canExecute file))
      (throw (ex-info "absolute child executable required" {})))
    path))

(defn run-isolated!
  "Test-only orchestration seam. Callers own directory; logs are retained.
  The CLI validates its executable and allocates its own directory first."
  [executable directory]
  (let [totals (atom {:test 0 :pass 0 :fail 0 :error 0})
        qualified (atom true)
        settled (atom true)]
    (println :typed-socket-evidence directory)
    (doseq [index (range (count fixtures)) :while @settled]
      (let [out-file (java.io.File. (str directory "/fixture-" index ".log"))
            err-file (java.io.File. (str directory "/fixture-" index ".err"))
            result (child/run!
                    [executable "-Srepro" "-A:test-typed-socket" "-m"
                     "oscope.typed-socket-test-runner" "--fixture" (str index)]
                    {:out out-file :err err-file}
                    {:timeout-ms 120000 :settlement-ms 5000})
            terminal? (and (integer? (:exit result))
                           (not= false (:child/terminal? result)))
            output (if (and terminal? (.exists out-file)) (slurp out-file) "")
            executed (filter #(str/starts-with? % ":typed-socket-executed ")
                             (str/split-lines output))
            receipts (filter #(str/starts-with? % ":typed-socket-receipt")
                             (str/split-lines output))
            receipt (when (= 1 (count receipts))
                      (re-matches
                       #":typed-socket-receipt ([0-9]{1,9}) ([0-9]{1,9}) ([0-9]{1,9}) ([0-9]{1,9}) ([0-9]{1,9})"
                       (first receipts)))
            numbers (when receipt (mapv parse-long (rest receipt)))
            valid? (and terminal? (nil? (:child/status result))
                        (= 1 (count executed)) (= 1 (count receipts)) receipt
                        (= (str ":typed-socket-executed " index) (first executed))
                        (= index (get numbers 0)) (= 1 (get numbers 1))
                        (pos? (reduce + 0 (drop 2 numbers))))]
        (println :typed-socket-child index :exit (:exit result)
                 :terminal terminal? :receipt-valid (boolean valid?))
        ;; An unconfirmed child still owns its native process state. Never
        ;; launch another fixture until its settlement is confirmed. Settled
        ;; failures may continue so the complete causal inventory is retained.
        (when-not terminal? (reset! settled false))
        ;; Forward settled child reporting and fixed SDK witnesses unchanged.
        ;; No child emits a summary; only this validated aggregate does.
        (when terminal? (print output))
        (if valid?
          (swap! totals #(merge-with + % (zipmap [:test :pass :fail :error]
                                                 (rest numbers))))
          (reset! qualified false))
        (when-not (and valid? (zero? (:exit result))
                       (zero? (+ (get numbers 3 0) (get numbers 4 0))))
          (reset! qualified false))))
    ;; Logs are retained, even after confirmed completion. Unsettled fixtures
    ;; must never be deleted by this parent. Direct-child receipt is not a
    ;; universal descendant-retirement guarantee.
    (println :typed-socket-total @totals)
    (when (= (count fixtures) (:test @totals))
      (println (str "Ran " (:test @totals) " tests. " (:pass @totals)
                    " assertions passed, " (:fail @totals) " failures, "
                    (:error @totals) " errors.")))
    (when (and @qualified (= (count fixtures) (:test @totals)))
      (println :typed-socket-qualified))
    {:exit (if (and @qualified (= (count fixtures) (:test @totals))) 0 1)
     :totals @totals :qualified? (and @qualified (= (count fixtures) (:test @totals)))
     :settled? @settled :directory directory}))

(defn -main [& args]
  (let [phase (atom :runner-arguments)]
    (try
    (if (and (= 2 (count args)) (= "--fixture" (first args))
             (re-matches #"[0-4]" (second args)))
      (run-fixture! (parse-long (second args)) phase)
      (if (empty? args)
        (let [executable (do (reset! phase :runner-executable) (executable!))
              directory (do
                          (reset! phase :runner-directory)
                          (str (java.nio.file.Files/createTempDirectory
                                "oscope-typed-socket-children-"
                                (make-array java.nio.file.attribute.FileAttribute 0))))]
          (reset! phase :runner-children)
          (System/exit (:exit (run-isolated! executable directory))))
          (throw (ex-info "unknown runner arguments" {}))))
    (catch Throwable error
      (report-startup-failure! @phase error)
      (println :typed-socket-runner-failed)
      (System/exit 1)))))
