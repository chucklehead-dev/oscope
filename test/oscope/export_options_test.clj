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
            [otel.exporter.chdb :as exporter]))

(def compact {:insert-format :json-compact-each-row :json-backend :configured})
(def owned {:insert-format :json-compact-each-row
            :json-backend :native-guarded-byte-batch :owned-statement-output? true})
(defn- error [f] (try (f) nil (catch clojure.lang.ExceptionInfo e e)))

(deftest streaming-fixture-rejects-a-no-op-and-routes-only-writer-options
  (doseq [execute? [false true]]
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
          (reset! failure (error #(native-runner/-main "stream" "embedded")))))
      (is (= (when-not execute? ::native-runner/unobserved-stream)
             (:type (ex-data @failure))))
      (is (true? (get-in @seen [:db-spec :owned-compact-stream?])))
      (is (= :raw-ticks (get-in @seen [:export-options :datetime64-wire])))
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
  (doseq [[kind options durable?] [[:embedded compact true] [:embedded owned true]
                                  [:standalone compact false] [:standalone owned true]
                                  [:embedded (assoc owned :datetime64-wire :raw-ticks) true]
                                  [:standalone (assoc owned :datetime64-wire :raw-ticks) true]
                                  [:standalone {:datetime64-wire :iso-utc} false]]]
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
