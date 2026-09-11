(ns oscope.typed-schema-config-test
  (:require [clojure.test :refer [deftest is]]
            [oscope.config :as config]
            [oscope.config-cli :as cli]
            [oscope.server-main :as server-main]
            [oscope.typed-schema-config :as typed-config]
            [oscope.typed-schema-runtime :as typed-runtime]
            [otel.exporter.chdb.attribute-manifest :as manifest]))

(def selector
  {:dataset-id "private-dataset"
   :application-id "private-app"
   :lineage "private-lineage"
   :version 1})

(defn- approved-manifest []
  (manifest/compile-manifest
   (assoc selector
          :fragments
          [{:schema manifest/reviewed-fragment-schema
            :authority :runtime-reviewed
            :source "test/private-attributes.edn"
            :entries
            [{:signal :spans
              :table "otel_traces"
              :location :span-attributes
              :key "private.counter"
              :type :int64}]}])))

(defn- manifest-bytes []
  (.getBytes (manifest/render (approved-manifest)) "UTF-8"))

(defn- install-section [digest]
  {:mode :install
   :manifest {:path "/private/typed-attributes.edn" :sha256 digest}
   :registry selector})

(defn- config-text [typed-attributes]
  (config/encode (assoc config/defaults :typed-attributes typed-attributes)))

(deftest install-handoff-verifies-exact-bytes-and-exporter-contract
  (let [bytes (manifest-bytes)
        section (install-section (typed-config/sha256-bytes bytes))
        calls (atom [])
        handoff (typed-config/load-handoff
                 section
                 (fn [path maximum]
                   (swap! calls conj [path maximum])
                   bytes))]
    (is (= [["/private/typed-attributes.edn"
             typed-config/max-manifest-bytes]]
           @calls))
    (is (= :install (:mode handoff)))
    (is (= selector (:registry handoff)))
    (is (= (approved-manifest) (:approved-manifest handoff)))
    (is (not (contains? handoff :path)))
    (is (not (contains? handoff :bytes)))))

(deftest digest-mismatch-precedes-edn-and-exporter-validation
  (let [bytes (.getBytes "not edn and contains private-content" "UTF-8")
        section (install-section (apply str (repeat 64 "0")))
        error (try
                (typed-config/load-handoff section (fn [& _] bytes))
                nil
                (catch clojure.lang.ExceptionInfo error error))]
    (is (= :oscope.typed-schema-config/manifest-digest-mismatch
           (:type (ex-data error))))
    (is (nil? (ex-cause error)))
    (is (not (.contains (pr-str [(ex-message error) (ex-data error)])
                        "private-content")))))

(deftest invalid-manifest-and-selector-errors-drop-private-input-and-causes
  (let [invalid-bytes (.getBytes "{:private \"do-not-report\"}" "UTF-8")
        invalid-section
        (install-section (typed-config/sha256-bytes invalid-bytes))
        invalid-error
        (try
          (typed-config/load-handoff invalid-section (fn [& _] invalid-bytes))
          nil
          (catch clojure.lang.ExceptionInfo error error))
        good-bytes (manifest-bytes)
        mismatch-section
        (assoc (install-section (typed-config/sha256-bytes good-bytes))
               :registry (assoc selector :lineage "other-private-lineage"))
        mismatch-error
        (try
          (typed-config/load-handoff mismatch-section (fn [& _] good-bytes))
          nil
          (catch clojure.lang.ExceptionInfo error error))]
    (is (= :oscope.typed-schema-config/invalid-manifest
           (:type (ex-data invalid-error))))
    (is (= :oscope.typed-schema-config/selector-mismatch
           (:type (ex-data mismatch-error))))
    (doseq [error [invalid-error mismatch-error]]
      (is (nil? (ex-cause error)))
      (let [rendered (pr-str [(ex-message error) (ex-data error)])]
        (is (not (.contains rendered "do-not-report")))
        (is (not (.contains rendered "private-lineage")))
        (is (not (.contains rendered "other-private-lineage")))))))

(deftest manifest-read-errors-drop-private-paths-and-causes
  (let [section (install-section (apply str (repeat 64 "0")))
        error
        (try
          (typed-config/load-handoff
           section
           (fn [& _]
             (throw (ex-info "failed at /private/typed-attributes.edn"
                             {:path "/private/typed-attributes.edn"}))))
          nil
          (catch clojure.lang.ExceptionInfo error error))
        rendered (pr-str [(ex-message error) (ex-data error)])]
    (is (= :oscope.typed-schema-config/manifest-unreadable
           (:type (ex-data error))))
    (is (nil? (ex-cause error)))
    (is (not (.contains rendered "/private")))))

(deftest manifest-reader-cannot-forge-the-public-error-marker
  (let [section (install-section (apply str (repeat 64 "0")))
        error
        (try
          (typed-config/load-handoff
           section
           (fn [& _]
             (throw (ex-info "forged /private/typed-attributes.edn"
                             {:oscope.typed-schema-config/error true
                              :type :forged
                              :selector selector}))))
          nil
          (catch clojure.lang.ExceptionInfo error error))
        rendered (pr-str [(ex-message error) (ex-data error)])]
    (is (= :oscope.typed-schema-config/manifest-unreadable
           (:type (ex-data error))))
    (is (nil? (ex-cause error)))
    (is (not (.contains rendered "/private")))
    (is (not (.contains rendered "private-dataset")))))

(deftest bounded-reader-stops-at-one-byte-past-the-limit
  (let [file (java.io.File/createTempFile "oscope-manifest" ".edn")]
    (try
      (spit file "12345")
      (let [error (try
                    (typed-config/read-bounded-file! (.getPath file) 4)
                    nil
                    (catch clojure.lang.ExceptionInfo error error))]
        (is (= :oscope.typed-schema-config/manifest-too-large
               (:type (ex-data error))))
        (is (nil? (ex-cause error))))
      (finally
        (.delete file)))))

(deftest check-config-validates-and-drops-runtime-capabilities
  (let [bytes (manifest-bytes)
        section (install-section (typed-config/sha256-bytes bytes))
        reads (atom 0)
        resolved
        (cli/load-config
         ["--config" "/private/oscope.edn" "--check-config"] {}
         (fn [path]
           (is (= "/private/oscope.edn" path))
           (config-text section))
         (fn [path maximum]
           (is (= "/private/typed-attributes.edn" path))
           (is (= typed-config/max-manifest-bytes maximum))
           (swap! reads inc)
           bytes))
        output (cli/check-output resolved)]
    (is (= 1 @reads))
    (is (nil? (cli/typed-attributes-handoff resolved)))
    (is (not (.contains output "/private")))
    (is (not (.contains output "private-dataset")))
    (is (not (.contains output "private-app")))
    (is (not (.contains output "private-lineage")))
    (is (.contains output ":install"))
    (is (.contains output "<redacted>"))
    (is (= output
           (with-out-str
             (with-redefs [server-main/run!
                           (fn [_]
                             (throw (ex-info "must not start" {})))]
               (server-main/execute! resolved)))))))

(deftest ordinary-launch-routes-the-validated-handoff-into-server-options
  (let [bytes (manifest-bytes)
        section (install-section (typed-config/sha256-bytes bytes))
        resolved (cli/load-config
                  ["--config" "/private/oscope.edn"] {}
                  (fn [_] (config-text section))
                  (fn [& _] bytes))
        handoff (cli/typed-attributes-handoff resolved)
        typed-schema (Object.)
        seen (atom nil)
        options (cli/server-options
                 resolved
                 (fn [storage supplied-handoff]
                   (reset! seen [storage supplied-handoff])
                   typed-schema))]
    (is (= :install (:mode handoff)))
    (is (= selector (:registry handoff)))
    (is (= [(:storage (:config resolved)) handoff] @seen))
    (is (identical? typed-schema (:typed-schema options)))
    (is (= "chdb:./oscope-data" (:db-spec options)))))

(deftest ordinary-execute-passes-the-storage-owned-envelope-to-the-server
  (let [bytes (manifest-bytes)
        section (install-section (typed-config/sha256-bytes bytes))
        resolved (cli/load-config
                  ["--config" "/private/oscope.edn"] {}
                  (fn [_] (config-text section))
                  (fn [& _] bytes))
        typed-schema (Object.)
        received (atom nil)]
    (with-redefs [typed-runtime/standalone-typed-schema
                  (fn [storage handoff]
                    (is (= (:storage (:config resolved)) storage))
                    (is (= (cli/typed-attributes-handoff resolved) handoff))
                    typed-schema)
                  server-main/run! #(reset! received %)]
      (server-main/execute! resolved))
    (is (identical? typed-schema (:typed-schema @received)))
    (is (= "chdb:./oscope-data" (:db-spec @received)))))

(deftest acquire-mode-does-not-read-a-manifest
  (let [section {:mode :acquire :registry selector}
        resolved (cli/load-config
                  ["--config" "/private/oscope.edn"] {}
                  (fn [_] (config-text section))
                  (fn [& _]
                    (throw (ex-info "manifest reader must not run" {}))))]
    (is (= {:mode :acquire :registry selector}
           (cli/typed-attributes-handoff resolved)))))
