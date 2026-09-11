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
                     :record ::active-record})]
      (let [result (typed-schema/install!
                    ::connection (assoc (options) :emit! emit!))
            [_ backend manifest runtime] (second @events)]
        (is (= [:base-schema ::connection] (first @events)))
        (is (= ::registry backend))
        (is (= ::approved manifest))
        (is (= ::connection (:target runtime)))
        (is (identical? emit! (:emit! runtime)))
        (is (= [:ddl ::connection "ALTER TABLE owned"] (nth @events 2)))
        (is (= [:observe ::connection "DESCRIBE TABLE otel_traces"]
               (nth @events 3)))
        (is (identical? descriptor-set (:descriptor-set result)))
        (is (= false
               (:create-schema?
                (typed-schema/exporter-options {:connection ::connection}
                                               result))))))))

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

(deftest startup-envelope-is-closed-before-effects
  (let [valid (options)]
    (is (= valid (typed-schema/validate-options valid)))
    (doseq [invalid [(assoc valid :unexpected true)
                     (dissoc valid :approved-manifest)
                     (assoc valid :emit! ::not-a-function)]]
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
