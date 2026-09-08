(ns oscope.typed-check
  "JVM-only runner for oscope's bounded Typed Clojure pilot."
  (:require [clojure.string :as str]
            [oscope.typed.contracts]
            [typed.clojure :as t]))

(def valid-namespaces
  ['oscope.typed.contracts
   'oscope.query
   'oscope.query.expression
   'oscope.command
   'oscope.view-model
   'oscope.typed.driver])

(def mutant-controls
  [{:namespace 'oscope.typed.controls.wrong-command-variant
    :evidence [":query" ":export"]}
   {:namespace 'oscope.typed.controls.wrong-signal-field
    :evidence ["oscope.typed.contracts/QuerySelection" ":unknown"]}
   {:namespace 'oscope.typed.controls.wrong-window
    :evidence ["oscope.typed.contracts/QuerySelection" ":forever"]}
   {:namespace 'oscope.typed.controls.wrong-limit
    :evidence ["oscope.typed.contracts/QuerySelection" "many"]}
   {:namespace 'oscope.typed.controls.wrong-plan-time
    :evidence ["AnyInteger" "later"]}
   {:namespace 'oscope.typed.controls.wrong-bucket
    :evidence ["oscope.typed.contracts/BucketedTelemetryExpression" ":30s"]}
   {:namespace 'oscope.typed.controls.wrong-counter-provenance
    :evidence [":cumulative" ":delta"]}
   {:namespace 'oscope.typed.controls.wrong-histogram-temporality
    :evidence [":cumulative" ":delta"]}
   {:namespace 'oscope.typed.controls.wrong-view-row
    :evidence ["oscope.typed.contracts/RawDistributionRow" "three"]}])

(def expected-mutant-count 9)

(def production-check-config
  {:check-config {:unannotated-def :unchecked
                  :unannotated-var :unchecked}})

(defn- require! [description condition]
  (when-not condition
    (throw (ex-info (str "typed-check failed: " description)
                    {:description description})))
  (println "PASS" description))

(defn- errors-of [value]
  (cond
    (nil? value) nil
    (map? value) (or (:delayed-errors value) (:errors value))
    :else nil))

(defn- expected-type-error? [errors evidence]
  (and (= 1 (count errors))
       (let [error (first errors)
             report (str error)]
         (and (instance? clojure.lang.ExceptionInfo error)
              (= :clojure.core.typed.errors/type-error
                 (:type-error (ex-data error)))
              (every? #(str/includes? report %) evidence)))))

(defn- normalize-report [result]
  (let [errors (errors-of result)]
    (if (seq errors)
      {:ok? false :errors errors}
      {:ok? true :errors []})))

(defn- check-ns-report
  ([ns-sym] (check-ns-report ns-sym {}))
  ([ns-sym opts]
   ;; A namespace that cannot load is an infrastructure failure, never an
   ;; expected mutant rejection.
   (require ns-sym :reload)
   (try
     (normalize-report (t/check-ns-clj ns-sym opts))
     (catch clojure.lang.ExceptionInfo error
       (let [errors (errors-of (ex-data error))]
         (if (seq errors)
           {:ok? false :errors errors}
           (throw (ex-info (str "check-ns-clj on " ns-sym
                                " raised a non-type-error exception")
                           {:namespace ns-sym} error))))))))

(defn -main [& _]
  (let [started (System/nanoTime)]
    (println "Typed Clojure checker 1.3.0; Clojure" (clojure-version)
             "; JVM" (System/getProperty "java.version"))
    (require! (str "registers exactly " expected-mutant-count
                   " named mutant controls")
              (= expected-mutant-count (count mutant-controls)))
    (doseq [ns-sym valid-namespaces]
      (let [{:keys [ok? errors]}
            (check-ns-report ns-sym production-check-config)]
        (require! (str ns-sym " type-checks with no reported errors ("
                       (count errors) " found)")
                  ok?)))
    (doseq [{:keys [namespace evidence]} mutant-controls]
      (let [{:keys [ok? errors]} (check-ns-report namespace)]
        (require! (str namespace
                       " is rejected by its expected structured type error")
                  (and (not ok?) (expected-type-error? errors evidence)))))
    (println "Typed pilot elapsed ms:"
             (format "%.2f" (/ (- (System/nanoTime) started) 1000000.0)))
    (flush)))
