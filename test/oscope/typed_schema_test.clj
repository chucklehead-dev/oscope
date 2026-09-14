(ns oscope.typed-schema-test
  (:require [clojure.test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [oscope.typed-schema :as typed-schema]
            [otel.exporter.chdb.attribute-registry-installer :as installer]
            [otel.exporter.chdb.schema :as schema]))

(defn- options []
  {:approved-manifest ::approved
   :registry-backend ::registry})

(deftest approved-installation-owns-base-schema-and-connection-target
  (let [events (atom [])
        descriptor-set (Object.)
        emit! #(swap! events conj [:emit %])]
    (with-redefs [schema/ensure-schema!
                  #(swap! events conj [:base-schema %])
                  jdbc/execute!
                  (fn [connection statement]
                    (swap! events conj [:ddl connection statement]))
                  jdbc/fetch
                  (fn [connection statement]
                    (swap! events conj [:observe connection statement])
                    [{:name "base" :type "String"}])
                  installer/install-approved!
                  (fn [backend manifest runtime]
                    (swap! events conj [:install backend manifest runtime])
                    ((:execute-ddl! runtime) "ALTER TABLE owned")
                    ((:observe-columns runtime))
                    {:status :active
                     :descriptor-set descriptor-set
                     :record ::active-record})
                  installer/descriptor-set-data
                  (fn [actual]
                    (is (identical? descriptor-set actual))
                    {:record {:manifest
                              {:fields
                               [{:signal :spans :table "otel_traces"
                                 :location :span-attributes}]}}})]
      (let [result (typed-schema/install!
                    ::connection (assoc (options) :emit! emit!))
            [_ backend manifest runtime] (second @events)]
        (is (= [:base-schema ::connection] (first @events)))
        (is (= ::registry backend))
        (is (= ::approved manifest))
        (is (= ::connection (:target runtime)))
        (is (identical? emit! (:emit! runtime)))
        (is (= [:ddl ::connection "ALTER TABLE owned"] (nth @events 2)))
        (is (= #{[:observe ::connection "DESCRIBE TABLE otel_logs"]
                 [:observe ::connection "DESCRIBE TABLE otel_traces"]}
               (set (filter #(= :observe (first %)) @events))))
        (is (= 2 (count (filter #(= :observe (first %)) @events)))
            "four supported logical locations observe their two physical tables")
        (is (identical? descriptor-set (:descriptor-set result)))
        (is (= #{:descriptor-set :installation} (set (keys result))))
        (is (= false
               (:create-schema?
                (typed-schema/exporter-options {:connection ::connection}
                                               result))))
        (is (identical? descriptor-set
                        (:typed-span-descriptors
                         (typed-schema/exporter-options {} result))))))))

(deftest confirmed-capability-is-routed-by-its-closed-export-target
  (let [descriptor-set (Object.)
        context {:descriptor-set descriptor-set}
        route (fn [field]
                (with-redefs [installer/descriptor-set-data
                              (fn [actual]
                                (is (identical? descriptor-set actual))
                                {:record {:manifest {:fields [field]}}})]
                  {:exporter (typed-schema/exporter-options
                              {:connection ::connection} context)
                   :source (typed-schema/source-options
                            {:connection ::connection} context)}))]
    (let [{:keys [exporter source]}
          (route {:signal :spans :table "otel_traces"
                  :location :resource-attributes})]
      (is (identical? descriptor-set (:typed-span-descriptors exporter)))
      (is (identical? descriptor-set (:typed-span-descriptors source)))
      (is (nil? (:typed-log-descriptors exporter))))
    (let [{:keys [exporter source]}
          (route {:signal :logs :table "otel_logs"
                  :location :log-attributes})]
      (is (identical? descriptor-set (:typed-log-descriptors exporter)))
      (is (= {:connection ::connection} source)
          "log projection is enabled without pretending a log query API exists")
      (is (nil? (:typed-span-descriptors exporter))))
    (with-redefs [installer/descriptor-set-data
                  (constantly
                   {:record
                    {:manifest
                     {:fields [{:signal :logs :table "otel_logs"
                                :location :resource-attributes}]}}})]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"unsupported export target"
           (typed-schema/exporter-options {} context))))))

(deftest read-only-acquisition-observes-without-base-schema-or-ddl
  (let [events (atom [])
        descriptor-set (Object.)
        selector {:dataset-id "dataset" :application-id "app"
                  :lineage "lineage" :version 1}]
    (with-redefs [schema/ensure-schema!
                  (fn [& _] (throw (ex-info "must not ensure schema" {})))
                  jdbc/execute!
                  (fn [& _] (throw (ex-info "must not execute DDL" {})))
                  jdbc/fetch
                  (fn [connection statement]
                    (swap! events conj [:observe connection statement])
                    [{:name "base" :type "String"}])
                  installer/acquire-active!
                  (fn [backend supplied-selector runtime]
                    (swap! events conj
                           [:acquire backend supplied-selector
                            (:target runtime)
                            (contains? runtime :execute-ddl!)])
                    ((:observe-columns runtime))
                    {:status :active
                     :descriptor-set descriptor-set
                     :record ::active-record})]
      (let [result
            (typed-schema/install!
             ::connection
             {:mode :acquire :selector selector
              :registry-backend ::registry})]
        (is (= [:acquire ::registry selector ::connection false]
               (first @events)))
        (is (seq (filter #(= :observe (first %)) @events)))
        (is (identical? descriptor-set (:descriptor-set result)))
        (is (= ::active-record (get-in result [:acquisition :record])))))))

(deftest descriptors-are-withheld-unless-installation-is-confirmed-active
  (with-redefs [schema/ensure-schema! (fn [_] nil)
                installer/install-approved!
                (fn [& _] {:status :preparing :descriptor-set nil})]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"not confirmed active"
         (typed-schema/install!
          ::connection
          (options)))))
  (is (= {:connection ::connection}
         (typed-schema/exporter-options {:connection ::connection} nil))))

(deftest descriptors-are-withheld-unless-acquisition-is-confirmed-active
  (with-redefs [installer/acquire-active!
                (fn [& _] {:status :preparing :descriptor-set nil})]
    (let [error
          (try
            (typed-schema/install!
             ::connection
             {:mode :acquire
              :selector {:dataset-id "dataset" :application-id "app"
                         :lineage "lineage" :version 1}
              :registry-backend ::registry})
            nil
            (catch clojure.lang.ExceptionInfo error error))]
      (is (= :oscope.typed-schema/acquisition-unconfirmed
             (:type (ex-data error))))
      (is (nil? (ex-cause error))))))

(deftest startup-envelope-is-closed-before-effects
  (let [valid (options)
        explicit-install (assoc valid :mode :install)
        acquire {:mode :acquire
                 :selector {:dataset-id "dataset" :application-id "app"
                            :lineage "lineage" :version 1}
                 :registry-backend ::registry}]
    (is (= valid (typed-schema/validate-options valid)))
    (is (= explicit-install
           (typed-schema/validate-options explicit-install)))
    (is (= acquire (typed-schema/validate-options acquire)))
    (doseq [invalid [(assoc valid :unexpected true)
                     (dissoc valid :approved-manifest)
                     (assoc valid :emit! ::not-a-function)
                     (assoc-in acquire [:selector :unexpected] true)
                     (dissoc acquire :selector)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (typed-schema/validate-options invalid))))))

(deftest installer-failure-does-not-leak-manifest-or-cause
  (let [secret-manifest {:deployment "do-not-report"}
        failure
        (with-redefs [schema/ensure-schema! (fn [_] nil)
                      installer/install-approved!
                      (fn [& _]
                        (throw (ex-info "upstream included manifest"
                                        {:manifest secret-manifest})))]
          (try
            (typed-schema/install!
             ::connection
             (assoc (options)
                    :approved-manifest secret-manifest))
            nil
            (catch clojure.lang.ExceptionInfo error error)))]
    (is (= :oscope.typed-schema/installation-failed
           (:type (ex-data failure))))
    (is (nil? (ex-cause failure)))
    (is (not (.contains (.getMessage failure) "do-not-report")))
    (is (not (contains? (ex-data failure) :manifest)))))

(deftest legacy-base-schema-failure-boundary-is-unchanged
  (let [original (ex-info "legacy base schema failure" {:legacy true})
        failure
        (with-redefs [schema/ensure-schema! (fn [_] (throw original))
                      installer/install-approved!
                      (fn [& _] (throw (ex-info "must not install" {})))]
          (try
            (typed-schema/install! ::connection (options))
            nil
            (catch clojure.lang.ExceptionInfo error error)))]
    (is (identical? original failure))))

(deftest acquisition-failure-does-not-leak-selector-or-cause
  (let [selector {:dataset-id "do-not-report" :application-id "private-app"
                  :lineage "private-lineage" :version 1}
        failure
        (with-redefs [installer/acquire-active!
                      (fn [& _]
                        (throw (ex-info "upstream included private selector"
                                        {:selector selector})))]
          (try
            (typed-schema/install!
             ::connection
             {:mode :acquire :selector selector
              :registry-backend ::registry})
            nil
            (catch clojure.lang.ExceptionInfo error error)))
        rendered (pr-str [(ex-message failure) (ex-data failure)])]
    (is (= :oscope.typed-schema/acquisition-failed
           (:type (ex-data failure))))
    (is (nil? (ex-cause failure)))
    (is (not (.contains rendered "do-not-report")))
    (is (not (.contains rendered "private-app")))
    (is (not (.contains rendered "private-lineage")))))
