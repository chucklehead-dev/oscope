(ns oscope.export-options-test
  (:require [clojure.test :as test :refer [deftest is]]
            [jdbc.core :as jdbc]
            [jdbc.chdb.durable :as durable]
            [oscope.config :as config]
            [oscope.config-cli :as cli]
            [oscope.durable-config-runtime :as runtime]
            [oscope.embedded :as embedded]
            [oscope.export-options :as encoding]
            [oscope.export-options-native-test-runner :as native-runner]
            [oscope.durable-native-child-runner :as native-child]
            [jdbc.chdb.native :as native]
            [oscope.server :as server]
            [otel.exporter.chdb :as exporter]
            [otel.exporter.chdb.schema :as schema]))

(def compact {:insert-format :json-compact-each-row :json-backend :configured})
(def owned {:insert-format :json-compact-each-row
            :json-backend :native-guarded-byte-batch :owned-statement-output? true})
(defn- error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest startup-reaches-the-pinned-exporters-version-fence-before-ddl
  (doseq [wire [:auto :iso-utc :raw-ticks] kind [:embedded :standalone]]
    (let [closed (atom false) ddl (atom 0)
          connection (reify java.io.Closeable (close [_] (reset! closed true)))
          schema-sentinel (ex-info "Reached schema after native version admission" {})
          failure
          (with-redefs [jdbc/connection (constantly connection)
                        durable/connection-role (constantly :writer)
                        native/ensure-loaded! (constantly nil)
                        native/chdb-version (constantly "26.7.3")
                        schema/ensure-schema! (fn [_] (swap! ddl inc) (throw schema-sentinel))]
            (error #(if (= kind :embedded)
                      (embedded/start! {:db-spec {:vendor "chdb-durable"}
                                        :sdk-options {:metrics? false}
                                        :export-options {:datetime64-wire wire}})
                      (server/start! {:db-spec {:vendor "chdb-durable"} :port 0
                                      :durability {:checkpoint! (fn [_] true) :flush! (fn [_] true)}
                                      :export-options {:datetime64-wire wire}}))))]
      ;; Exercise the actual exporter, not a mocked exporter that invents the
      ;; guard. Auto is the positive control: it reaches the schema sentinel.
      (if (= :auto wire)
        (is (identical? schema-sentinel failure))
        (is (= ::exporter/unqualified-timestamp-wire (:type (ex-data failure)))))
      (is (= (if (= :auto wire) 1 0) @ddl))
      (is @closed))))

(deftest ordinary-stream-fallback-witness-rejects-unconditional-streaming
  (let [failure (atom nil)]
    (with-redefs [embedded/start! (fn [_] nil)
                  native/chdb-stream-insert-n (fn [& _] :synthetic-result)
                  native-child/-main (fn [& _]
                                        (native/chdb-stream-insert-n)
                                        (test/test-vars []))]
      (with-out-str
        (reset! failure (error #(native-runner/-main "stream-fallback" "embedded")))))
    (is (= ::native-runner/unexpected-stream (:type (ex-data @failure))))))

(deftest streaming-fixture-rejects-a-no-op-and-routes-only-writer-options
  (doseq [execute? [false true] mode ["stream" "stream-budget"]]
    (let [seen (atom nil)
          failure (atom nil)]
      (with-redefs [embedded/start! #(reset! seen %)
                    native/chdb-stream-insert-n (fn [& _] :synthetic-result)
                    native-child/-main
                    (fn [& _]
                      (embedded/start! {:db-spec {:vendor "chdb-durable"}})
                      (when execute? (native/chdb-stream-insert-n))
                      (test/test-vars []))]
        (with-out-str
          (reset! failure (error #(native-runner/-main mode "embedded")))))
      (is (= (when-not execute? ::native-runner/unobserved-stream)
             (:type (ex-data @failure))))
      (is (true? (get-in @seen [:db-spec :owned-compact-stream?])))
      (is (= :raw-ticks (get-in @seen [:export-options :datetime64-wire])))
      (is (= (when (= mode "stream-budget") (* 16 1024 1024))
             (get-in @seen [:export-options :max-insert-bytes])))
      (is (not (contains? (:export-options @seen) :owned-compact-stream?))))))

(deftest standalone-streaming-fixture-does-not-repair-dropped-file-options
  (let [effects (atom []) failure (atom nil)]
    (with-redefs [server/start! (fn [_] (swap! effects conj :server))
                  native-child/-main (fn [& _] (server/start! {:db-spec {:vendor "chdb-durable"}}))]
      (with-out-str
        (reset! failure (error #(native-runner/-main "stream" "standalone")))))
    (is (= ::native-runner/unrouted-stream-options (:type (ex-data @failure))))
    (is (empty? @effects))))

(deftest timestamp-wire-options-retain-the-durable-boundary
  (doseq [wire [:auto :iso-utc :raw-ticks] durable? [false true]
          :let [options {:datetime64-wire wire}]]
    (if (and (= :raw-ticks wire) (not durable?))
      (is (= :oscope.export-options/incompatible-timestamp-wire
             (:type (ex-data (error #(encoding/validate! options durable?))))))
      (is (identical? options
                     (try (encoding/validate! options durable?)
                          (catch clojure.lang.ExceptionInfo _ nil))))))
  (doseq [wire [nil "auto" :unix-nanos :unknown]]
    (is (= :oscope.export-options/invalid-options
           (:type (ex-data (error #(encoding/validate! {:datetime64-wire wire} true))))))))

(deftest insert-byte-limit-is-bounded-and-rejected-before-resources
  (doseq [durable? [false true] value [nil 1 (* 8 1024 1024) (* 64 1024 1024)]
          :let [options {:max-insert-bytes value}]]
    (is (identical? options (encoding/validate! options durable?))))
  (let [effects (atom [])]
    (with-redefs [jdbc/connection (fn [& _] (swap! effects conj :connection))
                  exporter/exporter (fn [& _] (swap! effects conj :exporter))]
      (doseq [start [embedded/start! server/start!]
              value [0 -1 1.5 true "PRIVATE-CANARY" {} (inc (* 64 1024 1024))]]
        (let [failure (error #(start {:db-spec ::unused :export-options {:max-insert-bytes value}}))]
          (is (= ::encoding/invalid-options (:type (ex-data failure))))
          (is (nil? (ex-cause failure)))
          (is (not (.contains (str (ex-message failure) (pr-str (ex-data failure)))
                             "PRIVATE-CANARY")))))
      (is (empty? @effects)))))

(deftest insert-byte-limit-file-roundtrips-and-reaches-both-storage-runtimes
  (doseq [value [nil 1 (* 32 1024 1024) (* 64 1024 1024)]
          type [:memory :durable-local]
          :let [options {:max-insert-bytes value}
                storage (cond-> {:type type :export-options options}
                          (= type :durable-local) (assoc :root "/not-opened"))
                resolved (config/resolve-config [[:file {:version 2 :storage storage}]])]]
    (is (= options (get-in (config/parse (config/encode (:config resolved)))
                           [:storage :export-options])))
    (if (= type :memory)
      (is (= options (:export-options (cli/server-options resolved (fn [& _] nil)))))
      (with-redefs [durable/writer-dbspec identity]
        (is (= options (:export-options
                        (runtime/server-options resolved {}
                          {:local-backend-fn (fn [_] ::backend)
                           :object-backend-fn (fn [& _] (throw (Exception. "Unexpected registry")))
                           :instance-fn (constantly "fixture")}))))))))

(deftest streaming-is-a-version-two-writer-option-not-an-encoding-option
  (doseq [enabled? [false true]
          storage [{:type :durable-local :root "/not-opened"}
                   {:type :durable-s3
                    :s3 {:endpoint "https://s3.example.test" :bucket "telemetry"
                         :region "us-west-2" :object-id "fixture"
                         :credentials {:type :environment :access-key-env "ACCESS"
                                       :secret-key-env "SECRET"}}}]
          :let [storage (assoc storage :owned-compact-stream? enabled?
                               :export-options (assoc owned :datetime64-wire :raw-ticks))
                resolved (try (config/resolve-config [[:file {:version 2 :storage storage}]])
                              (catch clojure.lang.ExceptionInfo _ nil))]]
    (is (some? resolved))
    (when resolved
      (with-redefs [durable/writer-dbspec identity]
        (let [options (runtime/server-options
                        resolved {"ACCESS" "synthetic-access" "SECRET" "synthetic-secret"}
                        {:local-backend-fn (fn [_] ::backend)
                         :s3-backend-fn (fn [_] ::backend)
                         :object-backend-fn (fn [& _] (throw (Exception. "Unexpected registry")))
                         :instance-fn (constantly "fixture")})]
          (is (= enabled? (get-in options [:db-spec :owned-compact-stream?])))
          (is (= :raw-ticks (get-in options [:export-options :datetime64-wire])))
          (is (not (contains? (:export-options options) :owned-compact-stream?)))))))
  (doseq [enabled? [nil "true" 1]]
    (is (some? (error #(config/resolve-config
                        [[:file {:version 2 :storage {:type :durable-local :root "/not-opened"
                                                     :owned-compact-stream? enabled?}}]])))))
  (is (some? (error #(config/resolve-config
                      [[:file {:version 1 :storage {:type :durable-local :root "/not-opened"
                                                   :owned-compact-stream? true}}]]))))
  (is (= :oscope.export-options/invalid-options
         (:type (ex-data (error #(encoding/validate! {:owned-compact-stream? true} true)))))))

(deftest option-domain-and-combinations-are-closed
  (is (= {} (encoding/validate! {} false)))
  (doseq [format [:json-each-row :json-compact-each-row]
          backend [:configured :native-guarded :native-guarded-string-cache :native-guarded-byte-batch]
          owned? [false true] durable? [false true]
          :let [options {:insert-format format :json-backend backend :owned-statement-output? owned?}
                valid? (or (not owned?) (and durable? (= format :json-compact-each-row)
                                              (= backend :native-guarded-byte-batch)))]]
    (if valid?
      (is (identical? options (encoding/validate! options durable?)))
      (is (= :oscope.export-options/incompatible-owned-output
             (:type (ex-data (error #(encoding/validate! options durable?))))))))
  (doseq [options [nil false [] {:insert-format :unknown} {:json-backend "configured"}
                   {:owned-statement-output? nil}]]
    (is (= :oscope.export-options/invalid-options
           (:type (ex-data (error #(encoding/validate! options true))))))))

(deftest ownership-overrides-never-enter-startup-and-do-not-leak-values
  (let [effects (atom [])]
    (with-redefs [jdbc/connection (fn [& _] (swap! effects conj :connection))]
      (doseq [key [:connection :db-spec :signals :durable? :create-schema?
                   :typed-span-descriptors :persistence-barrier :headers :owned-compact-stream?]
              start [embedded/start! server/start!]]
        (let [failure (error #(start {:db-spec ::unused :export-options {key "PRIVATE-CANARY"}}))]
          (is (= :oscope.export-options/invalid-options (:type (ex-data failure))))
          (is (nil? (ex-cause failure)))
          (is (not (.contains (str (ex-message failure) (pr-str (ex-data failure))) "PRIVATE-CANARY")))))
      (is (empty? @effects)))))

(deftest validated-options-reach-both-public-exporter-construction-sites
  (doseq [[kind options durable?]
          (concat
           [[:embedded compact true] [:embedded owned true]
            [:standalone compact false] [:standalone owned true]
            [:embedded (assoc owned :datetime64-wire :raw-ticks) true]
            [:standalone (assoc owned :datetime64-wire :raw-ticks) true]
            [:standalone {:datetime64-wire :iso-utc} false]]
           (for [kind [:embedded :standalone] durable? [false true]
                 :when (or (= kind :standalone) durable?)
                 value [nil 1 (* 8 1024 1024) (* 64 1024 1024)]]
             [kind {:max-insert-bytes value} durable?]))]
    (let [seen (atom nil) closed (atom false)
          connection (reify java.io.Closeable (close [_] (reset! closed true)))
          sentinel (ex-info "Stop after exporter handoff" {})]
      (with-redefs [jdbc/connection (constantly connection)
                    exporter/exporter (fn [options] (reset! seen options) (throw sentinel))]
        (let [failure (try
                        (if (= kind :embedded)
                          (embedded/start! {:db-spec ::unused :sdk-options {:metrics? false}
                                            :export-options options})
                          (server/start! (cond-> {:db-spec ::unused :port 0 :export-options options}
                                           durable? (assoc :db-spec {:vendor "chdb-durable"}
                                                           :durability {:checkpoint! (fn [_] true)
                                                                        :flush! (fn [_] true)}))))
                        nil (catch Throwable e e))]
          (is (identical? sentinel failure))
          (is (= (merge options {:connection connection
                                :signals (if (= kind :embedded) #{:spans} #{:spans :logs :metrics})}
                        (when durable? {:durable? true})) @seen))
          (is @closed))))))

(deftest file-options-reach-local-and-durable-runtime-without-owning-resources
  (let [ordinary (config/resolve-config [[:file {:version 2 :storage {:type :memory :export-options compact}}]])
        durable-config (config/resolve-config
                         [[:file {:version 2 :storage {:type :durable-local :root "/not-opened"
                                                      :export-options owned}}]])]
    (is (= compact (:export-options (cli/server-options ordinary (fn [& _] nil)))))
    (with-redefs [durable/writer-dbspec identity]
      (is (= owned (:export-options
                     (runtime/server-options durable-config {}
                       {:local-backend-fn (fn [_] ::backend)
                        :object-backend-fn (fn [& _] (throw (Exception. "Unexpected registry")))
                        :instance-fn (constantly "fixture")})))))
    (is (= compact (get-in (config/parse (config/encode (:config ordinary))) [:storage :export-options])))
    (is (some? (error #(config/resolve-config
                        [[:file {:version 1 :storage {:type :memory :export-options compact}}]]))))
    (is (some? (error #(config/resolve-config
                        [[:file {:version 2 :storage {:type :memory :export-options owned}}]]))))))

(defn -main [& _]
  (let [result (test/run-tests 'oscope.export-options-test)]
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
