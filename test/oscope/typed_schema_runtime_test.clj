(ns oscope.typed-schema-runtime-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.typed-schema-runtime :as runtime]))

(def selector
  {:dataset-id "private-dataset"
   :application-id "private-app"
   :lineage "private-lineage"
   :version 1})

(deftest disabled-mode-does-not-construct-storage
  (is (nil?
       (runtime/standalone-typed-schema
        {:type :memory} {:mode :disabled}
        (fn [& _] (throw (ex-info "must not open" {})))
        (fn [& _] (throw (ex-info "must not scope" {})))))))

(deftest local-install-uses-a-persistent-database-scoped-backend
  (let [namespace-backend (Object.)
        scoped-backend (Object.)
        approved (Object.)
        calls (atom [])
        result
        (runtime/standalone-typed-schema
         {:type :local-path :path "/private/oscope-data"}
         {:mode :install :approved-manifest approved :registry selector}
         (fn [root]
           (swap! calls conj [:namespace root])
           namespace-backend)
         (fn [backend object-id]
           (swap! calls conj [:scope backend object-id])
           scoped-backend))]
    (is (= [[:namespace "/private/oscope-data.oscope-registry"]
            [:scope namespace-backend "typed-attribute-registry-v1"]]
           @calls))
    (is (= :install (:mode result)))
    (is (identical? approved (:approved-manifest result)))
    (is (identical? scoped-backend (:registry-backend result)))
    (is (not (contains? result :registry)))))

(deftest local-acquire-retains-the-exact-selector-for-read-only-confirmation
  (let [scoped-backend (Object.)
        result
        (runtime/standalone-typed-schema
         {:type :local-path :path "/private/oscope-data"}
         {:mode :acquire :registry selector}
         (fn [_] ::namespace)
         (fn [_ _] scoped-backend))]
    (is (= :acquire (:mode result)))
    (is (= selector (:selector result)))
    (is (identical? scoped-backend (:registry-backend result)))
    (is (not (contains? result :approved-manifest)))))

(deftest path-spellings-and-declared-datasets-share-one-physical-catalog
  (let [calls (atom [])
        construct
        (fn [path dataset-id]
          (runtime/standalone-typed-schema
           {:type :local-path :path path}
           {:mode :acquire
            :registry (assoc selector :dataset-id dataset-id)}
           (fn [root] (swap! calls conj [:root root]) ::namespace)
           (fn [_ object-id]
             (swap! calls conj [:object object-id]) ::scoped)))]
    (construct "/private/a/../oscope-data" "dataset-a")
    (construct "/private/oscope-data" "dataset-b")
    (is (= [[:root "/private/oscope-data.oscope-registry"]
            [:object "typed-attribute-registry-v1"]
            [:root "/private/oscope-data.oscope-registry"]
            [:object "typed-attribute-registry-v1"]]
           @calls))))

(deftest ephemeral-and-unowned-storage-fail-before-backend-construction
  (doseq [storage [{:type :memory}
                   {:type :durable-local :root "/private/durable"}
                   {:type :durable-s3 :s3 {}}]]
    (let [calls (atom 0)
          error
          (try
            (runtime/standalone-typed-schema
             storage {:mode :acquire :registry selector}
             (fn [& _] (swap! calls inc))
             (fn [& _] (swap! calls inc)))
            nil
            (catch clojure.lang.ExceptionInfo error error))]
      (is (= 0 @calls))
      (is (= :oscope.typed-schema-runtime/unsupported-storage
             (:type (ex-data error))))
      (is (nil? (ex-cause error))))))

(deftest backend-construction-failure-is-redacted-and-cause-free
  (doseq [[namespace-fn object-fn]
          [[(fn [_]
              (throw (ex-info "failed /private/oscope-data private-dataset"
                              {:path "/private/oscope-data"
                               :dataset "private-dataset"})))
            (fn [& _] (throw (ex-info "must not scope" {})))]
           [(fn [_] ::namespace)
            (fn [& _]
              (throw (ex-info "scope failed for private-dataset"
                              {:dataset "private-dataset"})))]]]
    (let [error
          (try
            (runtime/standalone-typed-schema
             {:type :local-path :path "/private/oscope-data"}
             {:mode :acquire :registry selector}
             namespace-fn object-fn)
            nil
            (catch clojure.lang.ExceptionInfo error error))
          rendered (pr-str [(ex-message error) (ex-data error)])]
      (is (= :oscope.typed-schema-runtime/backend-unavailable
             (:type (ex-data error))))
      (is (nil? (ex-cause error)))
      (is (not (.contains rendered "/private")))
      (is (not (.contains rendered "private-dataset"))))))
