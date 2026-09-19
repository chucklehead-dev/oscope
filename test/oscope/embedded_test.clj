(ns oscope.embedded-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [jdbc.chdb.durable]
            [jdbc.core :as jdbc]
            [jolt.host :as host]
            [oscope.embedded :as embedded]
            [oscope.live :as live]
            [oscope.typed-schema :as typed-schema]
            [otel.instrument.runtime :as runtime]
            [otel.exporter.chdb :as chdb-export]
            [otel.exporter.otlp :as otlp]
            [otel.sdk :as sdk]
            [otel.sdk.export :as export]
            [otel.sdk.lifecycle :as sdk-lifecycle]
            [otel.sdk.logs :as sdk-logs]
            [otel.trace :as trace]))

(defn- await! [pred]
  (loop [attempt 0]
    (cond
      (pred) true
      (< attempt 1000) (do (Thread/sleep 2) (recur (inc attempt)))
      :else false)))

(use-fixtures :each
  (fn [test]
    (let [observe sdk/shutdown-status]
      (with-redefs [sdk/shutdown-status
                    (fn [handle]
                      ;; Historical unit fixtures explicitly use inert keyword
                      ;; SDK owners, with no worker or exporter resource users.
                      ;; Real maintained handles ALWAYS use the real witness.
                      (if (contains? #{::sdk-handle ::handle} handle)
                        {:otel.sdk.shutdown-status/version 1
                         :sdk-quiescence :confirmed
                         :exporter-quiescence :confirmed
                         :quiescence :confirmed}
                        (observe handle)))]
        (test)))))

(defn- owned-stop-fixture [handle events]
  {:sdk-handle handle :lock (Object.)
   :state (atom {:phase :open}) :checkpoint-on-close? true
   :source {:close! #(swap! events conj :source-close)}
   :connection (reify java.io.Closeable
                 (close [_] (swap! events conj :connection-close)))})

(defn- cleanup-settlement-valid? [events]
  ;; A consumer-only oracle: SDK proof is observed through the public API;
  ;; exporter users are counted independently by this controlled fixture.
  ;; Native owners below are inert. This is not native-persistence proof.
  (try
    (when (> (count events) 128)
      (throw (ex-info "cleanup journal exceeded bound" {})))
    (reduce
     (fn [{:keys [proof cleanup] :as state} [index entry]]
       (when-not (and (= index (:seq entry)) (= :embedded (:owner entry))
                      (keyword? (:operation entry))
                      (every? #(or (nil? %) (boolean? %) (keyword? %)
                                   (and (integer? %) (<= 0 % 128)))
                              (vals entry)))
         (throw (ex-info "invalid cleanup envelope" {})))
       (case (:event entry)
         :proof (do
                  (when-not (= 1 (:version entry))
                    (throw (ex-info "unsupported settlement version" {})))
                  (when (and (= :confirmed (:overall entry))
                             (or (pos? (:users entry))
                                 (not= :confirmed (:sdk entry))
                                 (not= :confirmed (:exporter entry))))
                    (throw (ex-info "premature aggregate proof" {})))
                  (assoc state :proof entry))
         (:source-close :checkpoint :connection-close)
         (do (when-not (and (= :confirmed (:overall proof))
                            (zero? (:users entry))
                            (= (:event entry)
                               (nth [:source-close :checkpoint :connection-close]
                                    (count cleanup) nil)))
               (throw (ex-info "cleanup without fresh settled permission" {})))
             (update state :cleanup conj (:event entry)))
         (throw (ex-info "unknown cleanup event" {}))))
     {:proof nil :cleanup []} (map-indexed vector events))
    true
    (catch :default _ false)))

(defn- partial-sdk-cleanup-trace [early-aggregate?]
  (let [events (atom []) finish (promise) started (promise)
        background (Thread. #(do (deliver started true) @finish))
        retired (atom false) releases (atom 0)
        users #(if (.isAlive background) 1 0)
        record! (fn [operation event data]
                  (locking events
                    (swap! events conj
                           (merge data {:seq (count @events) :owner :embedded
                                        :operation operation :event event}))))
        pending (reify export/SpanExporter
                  (export-spans! [_ _] true) (flush-exporter! [_] true)
                  (shutdown-exporter! [_]
                    (swap! releases inc) (reset! retired true) false)
                  sdk-lifecycle/SettlementWitness
                  (settlement-status [_]
                    {:quiescence (if (and @retired (zero? (users)))
                                   :confirmed :unconfirmed)}))
        settled (reify export/SpanExporter
                  (export-spans! [_ _] true) (flush-exporter! [_] true)
                  (shutdown-exporter! [_] true))
        handle (sdk/init! {:exporter :none
                           :span-processors [(export/simple-processor settled)
                                             (export/simple-processor pending)]
                           :metrics? false :logs? false :runtime-metrics? false})
        owner {:sdk-handle handle :lock (Object.) :state (atom {:phase :open})
               :checkpoint-on-close? true
               :source {:close! #(record! :cleanup :source-close {:users (users)})}
               :connection (reify java.io.Closeable
                             (close [_] (record! :cleanup :connection-close
                                               {:users (users)})))}
        observe sdk/shutdown-status
        combine sdk-lifecycle/combined-settlement]
    (.start background)
    (try
      (is (= true (deref started 2000 :timeout)))
      (with-redefs [sdk-lifecycle/combined-settlement
                    (fn [components terminal]
                      ;; Alter the real aggregate permission path: confirming
                      ;; only the first component must not unlock cleanup.
                      (combine (if early-aggregate? (take 1 components) components)
                               terminal))
                    sdk/shutdown-status
                    (fn [h]
                      (let [proof (observe h)]
                        (record! :observe :proof
                                 {:version (:otel.sdk.shutdown-status/version proof)
                                  :sdk (:sdk-quiescence proof)
                                  :exporter (:exporter-quiescence proof)
                                  :overall (:quiescence proof) :users (users)})
                        proof))
                    jdbc.chdb.durable/checkpoint!
                    (fn [_] (record! :cleanup :checkpoint {:users (users)})
                      {:status :committed})]
        (let [first-stop (#'embedded/stop-lifecycle! owner)]
          (when-not early-aggregate?
            (is (= :closing (:status first-stop)))
            (is (= [:proof] (mapv :event @events)))
            (is (= :unconfirmed (get-in first-stop [:settlement :exporter-quiescence])))
            (is (= :confirmed (get-in first-stop [:settlement :sdk-quiescence]))))
          (deliver finish true) (.join background 2000)
          (is (not (.isAlive background)))
          (let [closed (#'embedded/stop-lifecycle! owner)]
            (is (= :closed (:status closed)))
            (is (= {:ok? false :failure :returned-false}
                   (get-in closed [:telemetry :sdk])))
            (is (= 1 @releases))
            (is (= closed (#'embedded/stop-lifecycle! owner))))))
      @events
      (finally (deliver finish true) (.join background 2000)))))

(deftest actual-partial-sdk-proof-refresh-controls-consumer-cleanup
  (let [events (partial-sdk-cleanup-trace false)]
    (is (cleanup-settlement-valid? events))
    (is (= [:proof :proof :source-close :checkpoint :connection-close]
           (mapv :event events))))
  (is (not (cleanup-settlement-valid? (partial-sdk-cleanup-trace true)))
      "same consumer oracle rejects actual first-component-only permission"))

(defn- startup-cleanup-settlement-valid? [events]
  ;; The partial-startup owner is a real maintained pipeline, not a SDK handle.
  ;; Native connection below is inert; this checks cleanup permission only.
  (and (<= (count events) 128)
       (contains? #{[:pipeline-proof] [:pipeline-proof :connection-close]}
                  (mapv :event events))
       (every? true?
               (map-indexed
                (fn [index event]
                  (and (= index (:seq event)) (= :startup (:owner event))
                       (= :cleanup (:operation event))
                       (= #{:seq :owner :operation :event :users :quiescence}
                          (set (keys event)))
                       (contains? #{:confirmed :unconfirmed} (:quiescence event))
                       (integer? (:users event)) (<= 0 (:users event) 1)))
                events))
       (every? #(or (= :pipeline-proof (:event %))
                    (and (zero? (:users %)) (= :confirmed (:quiescence %))))
               events)))

(defn- partial-startup-cleanup-trace
  ([user-settled?] (partial-startup-cleanup-trace user-settled? :pre-sdk))
  ([user-settled? mode]
  (let [events (atom []) started (promise) finish (promise)
        background (Thread. #(do (deliver started true) @finish))
        retired (atom false) releases (atom 0) pipelines (atom nil)
        sdk-starts (atom 0) source-opens (atom 0)
        startup-error (ex-info "controlled partial startup failure" {})
        users #(if (.isAlive background) 1 0)
        proof-owner (atom nil)
        proof #(sdk-lifecycle/component-settlement @proof-owner)
        record! (fn [event]
                  (locking events
                    (swap! events conj {:seq (count @events) :owner :startup
                                        :operation :cleanup :event event
                                        :users (users)
                                        :quiescence (:quiescence (proof))})))
        local (reify export/SpanExporter
                (export-spans! [_ _] true) (flush-exporter! [_] true)
                (shutdown-exporter! [_]
                  (swap! releases inc) (reset! retired true)
                  (when (= :local-only mode) (record! :pipeline-proof))
                  false)
                sdk-lifecycle/SettlementWitness
                (settlement-status [_]
                  ;; Truthful permanent retirement, not momentary idleness.
                  {:quiescence (if (and @retired (zero? (users)))
                                 :confirmed :unconfirmed)}))
        remote (reify export/SpanExporter
                 (export-spans! [_ _] true) (flush-exporter! [_] true)
                 (shutdown-exporter! [_] true))
        connection (reify java.io.Closeable
                     (close [_] (record! :connection-close)))
        make-sdk sdk/init!
        original-env host/getenv
        shutdown-pipelines export/shutdown-pipelines!]
    (.start background)
    (try
      (is (= true (deref started 2000 :timeout)))
      (when user-settled?
        (deliver finish true) (.join background 2000)
        (is (not (.isAlive background))))
      (when (= :local-only mode) (reset! proof-owner local))
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil)
                    sdk/logger-provider (constantly nil)
                    jdbc/connection (constantly connection)
                    chdb-export/exporter (constantly local)
                    otlp/exporter (constantly remote)
                    live/open! (fn [_] (swap! source-opens inc)
                                 (if (contains? #{:disabled :init-throws} mode)
                                   ::source (throw startup-error)))
                    live/close! (constantly nil)
                    host/getenv (fn [name]
                                  (if (= "OTEL_SDK_DISABLED" name)
                                    (if (= :disabled mode) "true" "false")
                                    (original-env name)))
                    sdk/init! (if (= :disabled mode) make-sdk
                                (fn [_] (swap! sdk-starts inc)
                                  (throw startup-error)))
                    export/shutdown-pipelines!
                    (fn [owner]
                      (reset! pipelines owner)
                      (reset! proof-owner owner)
                      ;; Observe the original maintained terminal pathway and
                      ;; preserve its value/Throwable. Never manufacture proof.
                      (try (shutdown-pipelines owner)
                           (finally (record! :pipeline-proof))))]
        (let [error (try
                      (embedded/start!
                       (cond-> {:db-spec ::durable
                                :sdk-options {:metrics? false :logs? false}}
                        (not= :local-only mode)
                        (assoc :span-pipelines
                               {:local {:schedule-delay-ms 60000}
                                :remote {:endpoint "http://127.0.0.1:4318"
                                         :insecure? true :schedule-delay-ms 60000}})))
                      nil
                      (catch Throwable error error))]
          (if (and user-settled? (not= :init-throws mode))
            (if (= :disabled mode)
              (is (= :oscope.embedded/sdk-disabled (:type (ex-data error))))
              (is (identical? startup-error error)))
            (let [data (ex-data error) retry-stop! (:retry-stop! data)]
              (is (= :oscope.readiness/startup-cleanup-incomplete (:type data)))
              (is (= #{:oscope.readiness/error :type :operation :retry-stop!}
                     (set (keys data))))
              (is (nil? (.getCause error)))
              (is (fn? retry-stop!))
              (is (= [:pipeline-proof] (mapv :event @events)))
              (is (= :closing (:status (retry-stop!))))
              (is (= 1 @releases))
              (deliver finish true) (.join background 2000)
              (is (not (.isAlive background)))
              (if (= :init-throws mode)
                (do (is (= :closing (:status (retry-stop!))))
                    (is (= [:pipeline-proof] (mapv :event @events))))
                (do (is (= :closed (:status (retry-stop!))))
                    (is (= :closed (:status (retry-stop!))))
                    (is (= [:pipeline-proof :connection-close]
                           (mapv :event @events)))))))
          (is (= 1 @source-opens))
          ;; Only the intentionally foreign failing stub increments this seam.
          (is (= (if (= :init-throws mode) 1 0) @sdk-starts))
          (is (= 1 @releases))
          (is (= :confirmed (:quiescence (proof))))
          (is (= :pipeline-proof (:event (first @events))))
          @events))
      (finally
        (deliver finish true) (.join background 2000)
        (is (not (.isAlive background)))
        ;; No queued exports exist in this fixture. Join actual maintained SDK
        ;; workers after observations; cleanup does not alter recorded proof.
        (doseq [[_ processor] (:pipelines @pipelines)]
          (let [worker (:worker processor)]
            (when (.isAlive worker) (.interrupt worker))
            (.join worker 2000)
            (is (not (.isAlive worker))))))))))

(deftest partial-startup-requires-settlement-before-native-close
  (is (startup-cleanup-settlement-valid? (partial-startup-cleanup-trace true))
      "same oracle permits an actually settled partial owner")
  (is (startup-cleanup-settlement-valid? (partial-startup-cleanup-trace false))
      "failed startup defers native cleanup until actual settlement"))

(deftest partial-startup-disabled-and-local-face-settlement
  (doseq [mode [:disabled :local-only] settled? [true false]]
    (is (startup-cleanup-settlement-valid?
         (partial-startup-cleanup-trace settled? mode))
        "disabled SDK must not mask pipelines; orphan faces need their own proof")))

(deftest partial-startup-unobservable-sdk-constructor-fails-closed
  (is (startup-cleanup-settlement-valid?
       (partial-startup-cleanup-trace false :init-throws))
      "known pipelines retiring cannot prove unobservable SDK workers retired"))

(defn- concurrent-startup-retries! [retry-stop!]
  (let [go (promise) results [(promise) (promise)]
        workers (mapv (fn [result]
                        (Thread. #(do @go (deliver result (retry-stop!))))) results)]
    (try
      (doseq [worker workers] (.start worker))
      (deliver go true)
      (doseq [result results] (is (= :closed (:status (deref result 2000 {})))))
      (finally
        (deliver go true)
        (doseq [worker workers] (.join worker 2000) (is (not (.isAlive worker))))))))

(defn- actual-startup-face-trace [mode]
  (let [events (atom []) cleanup-events (atom nil)
        releases (atom {:spans 0 :metrics 0 :logs 0 :remote 0})
        owners (atom []) sentinel (ex-info "actual startup seam" {})
        sdk-evidence (atom nil)
        settled (atom false) entered (promise) finish (promise) users (atom 0)
        resource-ref (atom nil)
        background (Thread. (fn [] (swap! users inc)
                              (try (deliver entered true) @finish
                                   (export/export-metrics! @resource-ref {} [])
                                   (finally (swap! users dec)))))
        held? (contains? #{:held-false :held-throw :foreign-pipeline
                          :foreign-returned-pipeline :foreign-sdk-disabled
                          :foreign-sdk-failure} mode)
        record! #(swap! events conj %)
        release! (fn [signal]
                   (swap! releases update signal inc)
                   (record! [:release signal])
                   (case mode :held-false false :held-throw (throw sentinel) true))
        local (reify export/SpanExporter
                (export-spans! [_ _] true) (flush-exporter! [_] true)
                (shutdown-exporter! [_] (release! :spans))
                export/MetricExporter
                (export-metrics! [_ _ _] true)
                (shutdown-metric-exporter! [_] (release! :metrics))
                sdk-logs/LogRecordExporter
                (export-logs! [_ _] true)
                (shutdown-log-exporter! [_] (release! :logs))
                sdk-lifecycle/SettlementWitness
                (settlement-status [_]
                  {:quiescence (if (and @settled (zero? @users))
                                 :confirmed :unconfirmed)}))
        remote (reify export/SpanExporter
                 (export-spans! [_ _] true) (flush-exporter! [_] true)
                 (shutdown-exporter! [_] (release! :remote)))
        connection (reify java.io.Closeable (close [_] (record! :connection-close)))
        original-start sdk-lifecycle/start-owned-worker!
        original-pipelines export/independent-batch-pipelines
        original-factory export/batch-processor
        make-sdk sdk/init!
        original-env host/getenv
        original-tracer sdk/tracer-provider
        original-meter sdk/meter-provider
        original-logger sdk/logger-provider
        dual? (contains? #{:dual :alias :partial :pipeline-post-success :foreign-pipeline
                          :foreign-returned-pipeline} mode)
        expected {:spans (if (= :alias mode) 2 1) :metrics 1 :logs 1
                  :remote (if (and dual? (not= :alias mode)) 1 0)}
        count-oracle #(= expected @releases)]
    (try
      (reset! resource-ref local)
      (when (and held? (not (contains? #{:foreign-pipeline :foreign-returned-pipeline
                                       :foreign-sdk-disabled :foreign-sdk-failure} mode)))
        (.start background)
        (is (= true (deref entered 2000 ::timeout))))
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil) sdk/logger-provider (constantly nil)
                    jdbc/connection (constantly connection)
                    chdb-export/exporter (constantly local)
                    otlp/exporter (constantly (if (= :alias mode) local remote))
                    live/open! (fn [_] (record! :source-open) ::source)
                    live/close! (fn [_] (record! :source-close))
                    host/getenv (fn [name]
                                  (if (= "OTEL_SDK_DISABLED" name)
                                    (if (= :foreign-sdk-disabled mode) "true" "false")
                                    (original-env name)))
                    runtime/register! (fn [_] (throw sentinel))
                    sdk-lifecycle/start-owned-worker!
                    (fn [receipt owner worker]
                      (swap! owners conj owner)
                      (let [result (original-start receipt owner worker)]
                        (when (and (= :partial mode) (= 1 (count @owners)))
                          (throw sentinel))
                        result))
                    export/batch-processor
                    (if (= :foreign-pipeline mode)
                      (fn [& _] (.start background)
                        (when-not (= true (deref entered 2000 ::timeout))
                          (throw (ex-info "foreign user missing" {})))
                        (throw sentinel))
                      original-factory)
                    export/independent-batch-pipelines
                    (if (contains? #{:pipeline-post-success :foreign-returned-pipeline} mode)
                      (fn [& args]
                        (let [owner (apply original-pipelines args)]
                          (when (= :foreign-returned-pipeline mode)
                            (.start background)
                            (when-not (= true (deref entered 2000 ::timeout))
                              (throw (ex-info "foreign returned user missing" {}))))
                          (when (= :pipeline-post-success mode) (throw sentinel))
                          owner))
                      original-pipelines)
                    sdk/init! (if (contains? #{:foreign-sdk-disabled :foreign-sdk-failure} mode)
                                (fn [options]
                                  (.start background)
                                  (when-not (= true (deref entered 2000 ::timeout))
                                    (throw (ex-info "foreign SDK user missing" {})))
                                  (try
                                    (let [handle (make-sdk options)]
                                      (reset! sdk-evidence {:handle handle})
                                      handle)
                                    (catch Throwable error
                                      (reset! sdk-evidence
                                              {:receipt (:construction-receipt options)
                                               :error error})
                                      (throw error))))
                                make-sdk)]
        (let [error (try (embedded/start!
                         (cond-> {:db-spec ::durable
                                  :sdk-options (cond-> {:logs? true :runtime-metrics? true
                                                       :bridge-logging? false}
                                                 (= :validation mode)
                                                 (assoc :max-queue-size 0))}
                           dual? (assoc :span-pipelines
                                        {:local {:schedule-delay-ms 60000}
                                         :remote {:endpoint "http://127.0.0.1:4318"
                                                  :insecure? true
                                                  :schedule-delay-ms 60000}})))
                        nil (catch Throwable error error))
              retry-stop! (:retry-stop! (ex-data error))]
          (if (contains? #{:held-false :held-throw :foreign-pipeline :pipeline-post-success
                          :foreign-returned-pipeline :foreign-sdk-disabled
                          :foreign-sdk-failure} mode)
            (do
              (is (= :oscope.readiness/startup-cleanup-incomplete (:type (ex-data error))))
              (is (nil? (.getCause error)))
              (is (= #{:oscope.readiness/error :type :operation :retry-stop!}
                     (set (keys (ex-data error)))))
              (is (fn? retry-stop!))
              (is (not (some #{:source-close :connection-close} @events)))
              (is (= :closing (:status (retry-stop!))))
              (when (= :foreign-sdk-disabled mode)
                (is (true? (:disabled? (:handle @sdk-evidence))))
                (is (= :confirmed
                       (:quiescence (sdk/shutdown-status (:handle @sdk-evidence)))))
                (is (= {:spans 0 :metrics 0 :logs 0 :remote 0} @releases)))
              (when (= :foreign-sdk-failure mode)
                (is (identical? sentinel (:error @sdk-evidence)))
                (is (= :confirmed
                       (:quiescence
                        (sdk-lifecycle/construction-failure-status
                         (:receipt @sdk-evidence) sentinel))))
                (is (count-oracle)))
              (when held?
                (is (= true (deref entered 2000 ::timeout)))
                (is (= 1 @users))
                (is (.isAlive background)))
              (let [before @releases]
                (deliver finish true) (.join background 2000)
                (is (not (.isAlive background)))
                (reset! settled true)
                (if (contains? #{:foreign-pipeline :pipeline-post-success
                                :foreign-returned-pipeline :foreign-sdk-disabled
                                :foreign-sdk-failure} mode)
                  (do (is (= :closing (:status (retry-stop!))))
                      (is (not (some #{:source-close :connection-close} @events))))
                  (do (concurrent-startup-retries! retry-stop!)
                      (is (count-oracle))))
                (is (= before @releases))))
            (do
              (is (some? error))
              (when (not= :validation mode) (is (identical? sentinel error)))
              (is (count-oracle))
              (is (= :connection-close (last @events)))
              (when (not= :partial mode)
                (is (= [:source-close :connection-close] (vec (take-last 2 @events)))))
              ;; Freeze actual cleanup observations before the deliberately
              ;; invalid extra consumer release appends its own event.
              (reset! cleanup-events @events)
              ;; Hypothetical extra consumer release: EXACT SAME count oracle.
              (export/shutdown-exporter! local)
              (is (not (count-oracle)))))
          (when (= :foreign-pipeline mode)
            (is (= {:spans 0 :metrics 0 :logs 0 :remote 0} @releases)))
          ;; Inspect actual globals, not the preflight nil stubs above.
          (is (nil? (original-tracer)))
          (is (nil? (original-meter)))
          (is (nil? (original-logger)))
          {:events @events :cleanup-events @cleanup-events
           :releases @releases :workers (count @owners)}))
      (finally
        (deliver finish true)
        (when held? (.join background 2000))
        (is (not (.isAlive background)))
        (doseq [owner @owners]
          (try (export/shutdown! owner) (catch Throwable _ nil))
          (.join (:worker owner) 2000)
          (is (not (.isAlive (:worker owner)))))))))

(deftest actual-maintained-startup-faces-clean-up-without-replay
  (doseq [mode [:runtime :validation :dual :alias :partial]]
    (let [trace (actual-startup-face-trace mode)]
      (is (= :connection-close (last (:cleanup-events trace)))))))

(deftest actual-startup-failed-release-refreshes-proof-not-callbacks
  (doseq [mode [:held-false :held-throw]]
    (let [trace (actual-startup-face-trace mode)]
      (is (= [:source-close :connection-close] (vec (take-last 2 (:events trace))))))))

(deftest actual-unreturned-pipeline-evidence-remains-unknown
  (doseq [mode [:foreign-pipeline :pipeline-post-success :foreign-returned-pipeline]]
    (let [trace (actual-startup-face-trace mode)]
      (is (not (some #{:source-close :connection-close} (:events trace)))))))

(deftest actual-foreign-sdk-constructor-provenance-remains-unknown
  ;; An empty returned SDK or a genuinely matching, settled maintained failure
  ;; receipt cannot account for the wrapper's independently held metric user.
  (doseq [mode [:foreign-sdk-disabled :foreign-sdk-failure]]
    (let [trace (actual-startup-face-trace mode)]
      (is (not (some #{:source-close :connection-close} (:events trace)))))))

(deftest sdk-exporter-settlement-controls-native-cleanup
  ;; These are real maintained processor and SDK terminal owners. Only the
  ;; native/query owners are inert fixtures; no all-true status stub is used.
  (doseq [failure [:returned-false :threw]]
    (let [events (atom []) calls (atom 0) settled (atom false)
          exporter (reify
                     export/SpanExporter
                     (export-spans! [_ _] true)
                     (flush-exporter! [_] true)
                     (shutdown-exporter! [_]
                       (swap! calls inc)
                       (if (= failure :threw)
                         (throw (ex-info "private-exporter-value" {})) false))
                     sdk-lifecycle/SettlementWitness
                     (settlement-status [_]
                       {:quiescence (if @settled :confirmed :unconfirmed)}))
          processor (export/simple-processor exporter)
          handle (sdk/init! {:exporter exporter :span-processors [processor]
                             :metrics? false :logs? false})
          owner (owned-stop-fixture handle events)]
      (with-redefs [jdbc.chdb.durable/checkpoint!
                    (fn [_] (swap! events conj :checkpoint) {:status :committed})]
        (let [first-stop (#'embedded/stop-lifecycle! owner)]
          (is (= :closing (:status first-stop)))
          (is (= :open (:phase first-stop)))
          (is (= :unconfirmed (get-in first-stop [:settlement :exporter-quiescence])))
          (is (empty? @events))
          (is (= 1 @calls))
          (is (= :closing (:status (#'embedded/stop-lifecycle! owner))))
          (is (= 1 @calls))
          ;; Explicit exporter-owned stable retired settlement becomes known
          ;; later; the cached failed delivery never changes or replays.
          (reset! settled true)
          (let [closed (#'embedded/stop-lifecycle! owner)]
            (is (= :closed (:status closed)))
            (is (= {:ok? false :failure failure}
                   (get-in closed [:telemetry :sdk])))
            (is (= [:source-close :checkpoint :connection-close] @events))
            (is (= 1 @calls))
            (is (= closed (#'embedded/stop-lifecycle! owner)))
            (is (not (str/includes? (pr-str closed) "private-exporter-value")))))))))

(deftest unknown-and-malformed-settlement-never-release-native-owners
  (doseq [status [nil {} {:otel.sdk.shutdown-status/version 1
                         :quiescence :confirmed :sdk-quiescence :confirmed
                         :exporter-quiescence :unconfirmed}
                  {:otel.sdk.shutdown-status/version 2
                   :quiescence :confirmed :sdk-quiescence :confirmed
                   :exporter-quiescence :confirmed}]]
    (let [events (atom []) calls (atom 0)
          owner (owned-stop-fixture ::unknown events)]
      (with-redefs [sdk/shutdown! (fn [_] (swap! calls inc) true)
                    sdk/shutdown-status (constantly status)]
        (is (= :closing (:status (#'embedded/stop-lifecycle! owner))))
        (is (= :closing (:status (#'embedded/stop-lifecycle! owner))))
        (is (= 1 @calls))
        (is (empty? @events))))))

(declare test-exporter)

(deftest maintained-dual-pipeline-adapter-preserves-settlement-and-delivery
  (let [events (atom []) releases (atom 0) settled (atom false)
        local (reify export/SpanExporter
                (export-spans! [_ _] true)
                (flush-exporter! [_] true)
                (shutdown-exporter! [_] (swap! releases inc) false)
                sdk-lifecycle/SettlementWitness
                (settlement-status [_]
                  {:quiescence (if @settled :confirmed :unconfirmed)}))
        remote (test-exporter (atom []) (atom 0))
        pipelines (export/independent-batch-pipelines
                   {:local {:exporter local :config {:schedule-delay-ms 60000}}
                    :remote {:exporter remote :config {:schedule-delay-ms 60000}}})
        results (atom nil)
        processor (#'embedded/local-required-processor pipelines results)
        handle (sdk/init! {:exporter local :span-processors [processor]
                           :metrics? false :logs? false})
        owner (assoc (owned-stop-fixture handle events) :pipeline-results results)]
    (with-redefs [jdbc.chdb.durable/checkpoint!
                  (fn [_] (swap! events conj :checkpoint) {:status :committed})]
      (let [pending (#'embedded/stop-lifecycle! owner)]
        (is (= :closing (:status pending)))
        (is (= :open (:phase pending)))
        (is (empty? @events))
        (is (= {:ok? false :failure :returned-false}
               (get-in pending [:telemetry :span-pipelines :local])))
        (is (= {:ok? true} (get-in pending [:telemetry :span-pipelines :remote])))
        (is (= 1 @releases))
        (reset! settled true)
        (let [closed (#'embedded/stop-lifecycle! owner)]
          (is (= :closed (:status closed)))
          (is (= {:ok? false :failure :returned-false}
                 (get-in closed [:telemetry :sdk])))
          (is (= [:source-close :checkpoint :connection-close] @events))
          (is (= 1 @releases))
          (is (= closed (#'embedded/stop-lifecycle! owner))))))))

(defn- assert-public-result-safe! [result canary]
  (let [values (tree-seq coll? seq result)]
    (is (not-any? #(instance? Throwable %) values))
    (is (not-any? #(and (string? %) (str/includes? % canary)) values))
    (is (not (str/includes? (pr-str result) canary)))))

(deftest invalid-typed-envelope-is-rejected-before-owner-acquisition
  (let [acquisitions (atom [])
        acquire (fn [phase]
                  (fn [& _]
                    (swap! acquisitions conj phase)
                    (throw (ex-info "unexpected owner acquisition" {}))))]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (acquire :connection)
                  otlp/exporter (acquire :remote-exporter)
                  chdb-export/exporter (acquire :local-exporter)
                  live/open! (acquire :source)
                  sdk/init! (acquire :sdk)]
      (doseq [invalid [false true 0 {} [] "not-an-envelope" ::not-an-envelope
                       {:approved-manifest {:secret "private-envelope-marker"}
                        :registry-backend ::registry
                        :unexpected "private-envelope-marker"}]]
        (let [error (try (embedded/start!
                         {:db-spec ::durable :typed-schema invalid
                          :span-pipelines {:local {}
                                           :remote {:traces-url "https://example.invalid/v1/traces"}}})
                         (catch Throwable error error))]
          (is (= "oscope typed schema requires a closed startup envelope" (ex-message error)))
          (is (= {:oscope.typed-schema/error true
                  :type :oscope.typed-schema/invalid-options} (ex-data error)))
          (is (nil? (ex-cause error)))
          (assert-public-result-safe! [(ex-message error) (ex-data error)]
                                     "private-envelope-marker")))
      (is (empty? @acquisitions)))))

(deftest embedded-runtime-shares-one-writer-and-retires-in-order
  (let [events (atom [])
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        exporter ::exporter
        source {:close! #(swap! events conj :source-close)}
        handle ::sdk-handle
        exporter-options (atom nil)
        installed-options (atom nil)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (fn [spec]
                                    (swap! events conj [:connection-open spec])
                                    connection)
                  chdb-export/exporter
                  (fn [options]
                    (reset! exporter-options options)
                    (swap! events conj :exporter-open)
                    exporter)
                  live/open! (fn [options]
                               (is (= {:connection connection
                                       :ensure-schema? false}
                                      options))
                               (swap! events conj :source-open)
                               source)
                  sdk/init! (fn [options]
                              (reset! installed-options options)
                              (swap! events conj :sdk-start)
                              handle)
                  sdk/force-flush! (fn [actual]
                                     (is (= handle actual))
                                     (swap! events conj :sdk-flush)
                                     true)
                  sdk/shutdown! (fn [actual]
                                  (is (= handle actual))
                                  (swap! events conj :sdk-stop)
                                  true)
                  jdbc.chdb.durable/checkpoint!
                  (fn [actual]
                    (is (= connection actual))
                    (swap! events conj :checkpoint)
                    {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:service-name "embedded-test"
                            :metrics? false :logs? true}})]
        (is (= {:connection connection
                :signals #{:spans :logs}
                :durable? true}
               @exporter-options))
        (is (= exporter (:exporter @installed-options)))
        (is (= #{:spans :logs} (:signals lifecycle)))
        (is (= {:oscope.embedded.status/version 1
                :phase :open
                :span-pipelines
                {:mode :direct-local
                 :local {:availability :unavailable
                         :queue-size :unavailable
                         :attempted-span-count :unavailable
                         :exported-span-count :unavailable
                         :failed-span-count :unavailable
                         :dropped-count :unavailable}
                 :remote {:availability :unavailable
                          :queue-size :unavailable
                          :attempted-span-count :unavailable
                          :exported-span-count :unavailable
                          :failed-span-count :unavailable
                          :dropped-count :unavailable}}
                :durable {:view-current? :unavailable
                          :last-successful-persistence :unavailable}}
               (embedded/status lifecycle)))
        (is (true? (embedded/force-flush! lifecycle)))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))
        (is (= :closed (:phase (embedded/status lifecycle))))
        (is (false? (embedded/force-flush! lifecycle)))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))
        (is (= [[:connection-open ::durable]
                :exporter-open :source-open :sdk-start :sdk-flush
                :sdk-stop :source-close :checkpoint :connection-close]
               @events))))))

(defn- durable-writer-observation
  ([] (durable-writer-observation :recovered :recovered :unavailable 0 true))
  ([state boundary last-success sequence current?]
   {:availability :available
    :role :writer
    :state state
    :view-current? current?
    :confirmed-boundary boundary
    :confirmed-sequence sequence
    :last-successful-persistence last-success}))

(def ^:private unavailable-durable-observation
  {:availability :unavailable})

(deftest embedded-status-v2-projects-only-closed-durable-evidence
  (let [canary "forged-durable-observation-secret"
        valid (durable-writer-observation :confirmed :checkpoint :checkpoint 7 true)
        pending (durable-writer-observation :pending :wal :wal 7 false)
        reader {:availability :available
                :role :reader
                :state :snapshot
                :view-current? false
                :confirmed-boundary :recovered
                :confirmed-sequence 3
                :last-successful-persistence :unavailable}
        project #'embedded/project-durable-observation]
    (is (= valid (project valid)))
    (is (= pending (project pending)))
    (is (= reader (project reader)))
    (doseq [forged [(assoc valid :state :snapshot)
                    (assoc valid :view-current? false)
                    (assoc valid :confirmed-sequence -1)
                    (assoc valid :confirmed-sequence 9007199254740992)
                    (assoc valid :last-successful-persistence :wal)
                    (assoc valid :backend canary)
                    (dissoc valid :role)
                    (assoc valid :role canary)
                    (assoc reader :view-current? true)
                    {:availability :unavailable :private canary}
                    canary nil]]
      (let [actual (project forged)]
        (is (= unavailable-durable-observation actual))
        (is (not (str/includes? (pr-str actual) canary)))))))

(deftest embedded-status-v2-fails-closed-and-never-observes-terminal-connection
  (let [calls (atom 0)
        closed? (atom false)
        connection (reify java.io.Closeable
                     (close [_] (reset! closed? true)))
        good (durable-writer-observation :confirmed :checkpoint :checkpoint 9 true)
        status-result (atom good)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly ::exporter)
                  live/open! (constantly {:close! (fn [])})
                  sdk/init! (constantly ::sdk-handle)
                  sdk/shutdown! (constantly true)
                  jdbc.chdb.durable/checkpoint! (constantly {:status :committed})
                  jdbc.chdb.durable/persistence-observation
                  (fn [actual]
                    (is (identical? connection actual))
                    (swap! calls inc)
                    (when @closed?
                      (throw (ex-info "post-close-observer-secret" {})))
                    (let [result @status-result]
                      (if (instance? Throwable result)
                        (throw result)
                        result)))]
      (let [lifecycle (embedded/start! {:db-spec ::durable})]
        (is (= good (:durable (embedded/status-v2 lifecycle))))
        (reset! status-result (ex-info "observer-secret" {:token "observer-secret"}))
        (let [exception-result (:durable (embedded/status-v2 lifecycle))]
          (is (= unavailable-durable-observation exception-result))
          (is (not (str/includes? (pr-str exception-result) "observer-secret"))))
        (reset! status-result (assoc good :backend "forged-backend-secret"))
        (is (= unavailable-durable-observation
               (:durable (embedded/status-v2 lifecycle))))
        (reset! status-result good)
        (is (= {:status :closed :phase :closed} (embedded/stop! lifecycle)))
        (let [before @calls
              terminal (embedded/status-v2 lifecycle)]
          (is (= unavailable-durable-observation (:durable terminal)))
          (is (= before @calls)
              "status-v2 must not invoke Durable after its owned connection closes"))))))

(deftest embedded-status-excludes-arbitrary-lifecycle-values
  (let [canary "embedded-status-secret"
        connection (reify java.io.Closeable (close [_]))]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly ::exporter)
                  live/open! (constantly {:close! (fn [])})
                  sdk/init! (constantly ::sdk-handle)
                  sdk/shutdown! (constantly true)
                  jdbc.chdb.durable/checkpoint!
                  (constantly {:status :committed})]
      (let [lifecycle (embedded/start! {:db-spec canary})]
        (try
          ;; This state remains public only for backward-compatible local-only
          ;; lifecycles. Poison it to prove status copies a closed phase enum.
          (swap! (:state lifecycle) assoc :phase canary)
          (let [snapshot (embedded/status lifecycle)]
            (is (= :unknown (:phase snapshot)))
            (is (not (str/includes? (pr-str snapshot) canary)))
            (is (= #{:oscope.embedded.status/version :phase
                     :span-pipelines :durable}
                   (set (keys snapshot)))))
          (finally
            (swap! (:state lifecycle) assoc :phase :open)
            (embedded/stop! lifecycle)))))))

(deftest embedded-local-only-never-reads-remote-env-or-constructs-otlp-exporter
  (let [connection (reify java.io.Closeable (close [_]))
        source {:close! (fn [])}]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  host/getenv (fn [name]
                                (throw (ex-info "remote environment read"
                                                {:name name})))
                  otlp/exporter (fn [_]
                                  (throw (ex-info "remote exporter constructed" {})))
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly ::local-exporter)
                  live/open! (constantly source)
                  sdk/init! (constantly ::sdk-handle)
                  sdk/shutdown! (constantly true)
                  jdbc.chdb.durable/checkpoint!
                  (constantly {:status :committed})]
      (let [lifecycle (embedded/start! {:db-spec ::durable})]
        (is (= ::local-exporter (:exporter lifecycle)))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))))))

(deftest embedded-runtime-retries-an-unconfirmed-persistence-boundary
  (let [attempts (atom 0)
        events (atom [])
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        source {:close! #(swap! events conj :source-close)}]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly ::exporter)
                  live/open! (constantly source)
                  sdk/init! (constantly ::sdk-handle)
                  sdk/shutdown! (fn [_] (swap! events conj :sdk-stop) true)
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    (if (= 1 (swap! attempts inc))
                      {:status :unexpected}
                      {:status :reconciled}))]
      (let [lifecycle (embedded/start! {:db-spec ::durable})
            first-stop (embedded/stop! lifecycle)]
        (is (= :closing (:status first-stop)))
        (is (= :persisting (:phase first-stop)))
        (is (= 1 (count (:errors first-stop))))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))
        (is (= [:sdk-stop :source-close :checkpoint :checkpoint
                :connection-close]
               @events))))))

(deftest embedded-shutdown-results-replace-secret-bearing-throwables
  (let [canary "embedded-lifecycle-secret"
        source-attempts (atom 0)
        connection (reify java.io.Closeable (close [_] nil))]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly ::exporter)
                  sdk/init! (constantly ::sdk-handle)
                  sdk/shutdown! (constantly true)
                  live/open!
                  (constantly
                   {:close!
                    (fn []
                      (when (= 1 (swap! source-attempts inc))
                        (throw
                         (ex-info (str canary "-message")
                                  {:path (str "/private/" canary)
                                   :headers {"authorization" canary}
                                   :attribute-value canary}
                                  (Exception. (str canary "-cause"))))))})
                  jdbc.chdb.durable/checkpoint!
                  (constantly {:status :committed})]
      (let [lifecycle (embedded/start! {:db-spec ::durable})
            failed (embedded/stop! lifecycle)]
        (is (= {:status :closing
                :phase :retiring-oscope
                :errors [{:type :oscope.error/lifecycle-operation-threw
                          :operation :close-source}]}
               failed))
        (assert-public-result-safe! failed canary)
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))))))

(deftest embedded-runtime-rejects-conflicting-sdk-ownership-before-opening
  (let [opened? (atom false)]
    (with-redefs [sdk/tracer-provider (constantly ::already-configured)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (fn [_] (reset! opened? true))]
      (is (thrown? Exception
                   (embedded/start! {:db-spec ::durable})))
      (is (false? @opened?))))
  (is (thrown? Exception
               (embedded/start!
                {:db-spec ::durable
                 :sdk-options {:exporter ::caller-owned}})))
  (is (thrown? Exception
               (embedded/start!
                {:db-spec ::durable
                 :sdk-options {:span-processors [::caller-owned]}})))
  (is (thrown? Exception (embedded/stop! nil)))
  (is (thrown? Exception (embedded/force-flush! {})))
  (is (thrown? Exception (embedded/status nil))))

(defn- test-exporter [batches shutdowns]
  (reify
    export/SpanExporter
    (export-spans! [_ spans]
      (swap! batches conj spans)
      true)
    (flush-exporter! [_] true)
    (shutdown-exporter! [_]
      (swap! shutdowns inc)
      true)

    export/MetricExporter
    (export-metrics! [_ _ _] true)
    (shutdown-metric-exporter! [_] true)

    sdk-logs/LogRecordExporter
    (export-logs! [_ _] true)
    (shutdown-log-exporter! [_] true)))

(deftest embedded-lifecycle-retains-background-remote-export-failure
  (let [entered (promise)
        release (promise)
        remote-export-calls (atom 0)
        remote-flushes (atom 0)
        remote-shutdowns (atom 0)
        local-batches (atom [])
        local-shutdowns (atom 0)
        local-exporter (test-exporter local-batches local-shutdowns)
        remote-exporter
        (reify export/SpanExporter
          (export-spans! [_ _]
            (swap! remote-export-calls inc)
            (deliver entered true)
            @release
            false)
          ;; This matches the stateless OTLP exporter contract. It must not
          ;; erase a failure from a batch that the worker already dequeued.
          (flush-exporter! [_]
            (swap! remote-flushes inc)
            true)
          (shutdown-exporter! [_]
            (swap! remote-shutdowns inc)
            true))
        events (atom [])
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))]
    (with-redefs [host/getenv (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly local-exporter)
                  otlp/exporter (constantly remote-exporter)
                  live/open!
                  (constantly {:close! #(swap! events conj :source-close)})
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:service-name "oscope.pipeline-isolation-test"
                            :metrics? false :logs? false}
              :span-pipelines
              {:local {:schedule-delay-ms 1}
               :remote {:endpoint "http://collector:4318"
                        :schedule-delay-ms 1
                        :max-queue-size 2
                        :max-export-batch-size 1}}})]
        (try
          (trace/with-span [_ (sdk/tracer "oscope.pipeline-isolation") "first"])
          (is (= true (deref entered 2000 ::timeout))
              "the remote worker owns a dequeued batch before it fails")
          (dotimes [index 10]
            (trace/with-span [_ (sdk/tracer "oscope.pipeline-isolation")
                              (str "local-" index)]))
          (is (await! #(= 11 (reduce + (map count @local-batches))))
              "local export completes while remote exporter I/O is blocked")
          (is (= 8 (get-in (embedded/span-pipeline-stats lifecycle)
                           [:remote :dropped-count]))
              "only the two free remote queue slots accept later spans")
          (is (zero? (get-in (embedded/span-pipeline-stats lifecycle)
                             [:local :dropped-count])))
          (let [snapshot (embedded/status lifecycle)]
            (is (= :open (:phase snapshot)))
            (is (= :available
                   (get-in snapshot [:span-pipelines :local :availability])))
            (is (= 11
                   (get-in snapshot
                           [:span-pipelines :local :exported-span-count])))
            (is (= :available
                   (get-in snapshot [:span-pipelines :remote :availability])))
            (is (= 2
                   (get-in snapshot [:span-pipelines :remote :queue-size])))
            (is (= 8
                   (get-in snapshot [:span-pipelines :remote :dropped-count])))
            (is (= {:view-current? :unavailable
                    :last-successful-persistence :unavailable}
                   (:durable snapshot))))
          (deliver release true)
          (is (= {:sdk {:ok? true}
                  :span-pipelines
                  {:local {:ok? true}
                   :remote {:ok? false :failure :returned-false}}}
                 (embedded/force-flush! lifecycle))
              "a true OTLP flush cannot erase its failed background batch")
          (is (= {:queue-size 0
                  :attempted-span-count 11
                  :exported-span-count 11
                  :failed-span-count 0
                  :dropped-count 0}
                 (:local (embedded/span-pipeline-stats lifecycle))))
          (is (= {:queue-size 0
                  :attempted-span-count 3
                  :exported-span-count 0
                  :failed-span-count 3
                  :dropped-count 8}
                 (:remote (embedded/span-pipeline-stats lifecycle))))
          (let [stopped (embedded/stop! lifecycle)]
            (is (= {:ok? true} (get-in stopped [:telemetry :sdk])))
            (is (= {:ok? true}
                   (get-in stopped [:telemetry :span-pipelines :local])))
            (is (= {:ok? false :failure :returned-false}
                   (get-in stopped [:telemetry :span-pipelines :remote])))
            (is (= :closed (:status stopped))))
          (is (= 3 @remote-export-calls))
          (is (= 2 @remote-flushes)
              "force-flush and shutdown both attempt the exporter callback")
          (is (= 1 @local-shutdowns))
          (is (= 1 @remote-shutdowns))
          (is (= [:source-close :checkpoint :connection-close] @events))
          (is (= :closed (:phase (embedded/status lifecycle))))
          (finally
            (deliver release true)
            (when (sdk/tracer-provider)
              (embedded/stop! lifecycle))))))))

(deftest dual-span-pipelines-preserve-one-canonical-span-and-private-credentials
  (let [events (atom [])
        local-batches (atom [])
        remote-batches (atom [])
        local-shutdowns (atom 0)
        remote-shutdowns (atom 0)
        local-exporter (test-exporter local-batches local-shutdowns)
        remote-exporter (test-exporter remote-batches remote-shutdowns)
        remote-options (atom nil)
        sdk-options (atom nil)
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        source {:close! #(swap! events conj :source-close)}
        canonical-span {:name "child"
                        :span-context {:trace-id "00112233445566778899aabbccddeeff"
                                       :span-id "0011223344556677"
                                       :trace-flags 1}
                        :parent-span-id "ffeeddccbbaa9988"}]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  host/getenv (fn [name]
                                (when (= "OSCOPE_REMOTE_HEADERS" name)
                                  "authorization=super-secret"))
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (fn [_] local-exporter)
                  otlp/exporter (fn [options]
                                  (reset! remote-options options)
                                  remote-exporter)
                  live/open! (constantly source)
                  sdk/init! (fn [options]
                              (reset! sdk-options options)
                              ::sdk-handle)
                  sdk/force-flush! (fn [_]
                                     (export/force-flush!
                                      (first (:span-processors @sdk-options))))
                  sdk/shutdown! (fn [_]
                                  (export/shutdown!
                                   (first (:span-processors @sdk-options))))
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:metrics? true :logs? true}
              :span-pipelines
              {:local {:schedule-delay-ms 60000 :max-queue-size 8}
               :remote {:traces-url "https://collector.example/v1/traces"
                        :headers-env "OSCOPE_REMOTE_HEADERS"
                        :timeout-ms 1234 :max-retries 0 :insecure? false
                        :schedule-delay-ms 60000 :max-queue-size 8}}})
            processor (first (:span-processors @sdk-options))]
        (is (identical? local-exporter (:exporter @sdk-options))
            "the local exporter remains the SDK logs and metrics owner")
        (is (= {"authorization" "super-secret"} (:headers @remote-options)))
        (is (nil? (:sdk-handle lifecycle))
            "the public dual lifecycle does not expose the private processor")
        (is (not (.contains (pr-str lifecycle) "super-secret")))
        (is (not (.contains (pr-str lifecycle) "OSCOPE_REMOTE_HEADERS")))
        (export/on-start processor canonical-span ::parent-context)
        (export/on-end processor canonical-span)
        (is (= {:sdk {:ok? true}
                :span-pipelines
                {:local {:ok? true} :remote {:ok? true}}}
               (embedded/force-flush! lifecycle)))
        (is (identical? canonical-span (-> @local-batches first first)))
        (is (identical? canonical-span (-> @remote-batches first first)))
        (is (= (:span-context (-> @local-batches first first))
               (:span-context (-> @remote-batches first first))))
        (is (= (:parent-span-id (-> @local-batches first first))
               (:parent-span-id (-> @remote-batches first first))))
        (is (= #{:local :remote}
               (set (keys (embedded/span-pipeline-stats lifecycle)))))
        (let [canary "pipeline-status-secret"
              snapshot
              (with-redefs
               [export/pipeline-stats
                (constantly
                 {:local {:queue-size 1
                          :attempted-span-count 2
                          :exported-span-count 2
                          :failed-span-count 0
                          :dropped-count 0
                          :endpoint canary}
                  :remote {:queue-size canary
                           :attempted-span-count 2
                           :exported-span-count 2
                           :failed-span-count 0
                           :dropped-count 0}})]
                (embedded/status lifecycle))]
          (is (= {:availability :available
                  :queue-size 1
                  :attempted-span-count 2
                  :exported-span-count 2
                  :failed-span-count 0
                  :dropped-count 0}
                 (get-in snapshot [:span-pipelines :local])))
          (is (= :unavailable
                 (get-in snapshot [:span-pipelines :remote :availability])))
          (is (every? #(= :unavailable %)
                      (vals (dissoc
                             (get-in snapshot [:span-pipelines :remote])
                             :availability))))
          (is (not (str/includes? (pr-str snapshot) canary))))
        (is (= {:status :closed
                :phase :closed
                :telemetry
                {:sdk {:ok? true}
                 :span-pipelines
                 {:local {:ok? true} :remote {:ok? true}}}}
               (embedded/stop! lifecycle)))
        (is (= 1 @local-shutdowns))
        (is (= 1 @remote-shutdowns))
        (is (= [:source-close :checkpoint :connection-close] @events))))))

(deftest remote-span-failure-does-not-waive-local-durable-retirement
  (doseq [remote-failure [:returned-false :threw]]
    (let [events (atom [])
          checkpoints (atom 0)
          connection (reify java.io.Closeable
                       (close [_] (swap! events conj :connection-close)))
          local (test-exporter (atom []) (atom 0))
          remote
          (reify export/SpanExporter
            (export-spans! [_ _] false)
            (flush-exporter! [_] false)
            (shutdown-exporter! [_]
              (swap! events conj :remote-shutdown)
              (if (= :threw remote-failure)
                (throw (ex-info "remote-secret" {:token "remote-secret"}))
                false)))
          installed (atom nil)]
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil)
                    sdk/logger-provider (constantly nil)
                    jdbc/connection (constantly connection)
                    chdb-export/exporter (constantly local)
                    otlp/exporter (constantly remote)
                    live/open! (constantly {:close! #(swap! events conj :source-close)})
                    sdk/init! (fn [options] (reset! installed options) ::handle)
                    sdk/shutdown! (fn [_]
                                    (export/shutdown!
                                     (first (:span-processors @installed))))
                    jdbc.chdb.durable/checkpoint!
                    (fn [_]
                      (swap! events conj :checkpoint)
                      (if (= 1 (swap! checkpoints inc))
                        {:status :unexpected}
                        {:status :reconciled}))]
        (let [lifecycle
              (embedded/start!
               {:db-spec ::durable
                :sdk-options {:metrics? false :logs? false}
                :span-pipelines
                {:local {:schedule-delay-ms 60000}
                 :remote {:endpoint "http://collector:4318"
                          :schedule-delay-ms 60000}}})
              first-stop (embedded/stop! lifecycle)
              final-stop (embedded/stop! lifecycle)]
          (is (= :closing (:status first-stop)))
          (is (= :persisting (:phase first-stop)))
          (is (= :closed (:status final-stop)))
          (is (= {:ok? true} (get-in final-stop
                                      [:telemetry :span-pipelines :local])))
          (is (= {:ok? false :failure remote-failure}
                 (get-in final-stop
                         [:telemetry :span-pipelines :remote])))
          (is (= {:ok? true} (get-in final-stop [:telemetry :sdk])))
          (is (= [:remote-shutdown :source-close :checkpoint :checkpoint
                  :connection-close]
                 @events))
          (is (not (.contains (pr-str final-stop) "collector")))
          (is (not (.contains (pr-str final-stop) "remote-secret"))))))))

(deftest terminal-telemetry-failure-does-not-strand-durable-owner
  (doseq [[local-failure remote-failure]
          [[:returned-false nil]
           [:threw :threw]]]
    (let [events (atom [])
          exporter
          (fn [destination failure]
            (reify export/SpanExporter
              (export-spans! [_ _] true)
              (flush-exporter! [_] true)
              (shutdown-exporter! [_]
                (swap! events conj (keyword (str (name destination) "-shutdown")))
                (case failure
                  :returned-false false
                  :threw (throw (ex-info "telemetry-secret"
                                         {:token "telemetry-secret"}))
                  true))))
          installed (atom nil)
          connection (reify java.io.Closeable
                       (close [_] (swap! events conj :connection-close)))]
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil)
                    sdk/logger-provider (constantly nil)
                    jdbc/connection (constantly connection)
                    chdb-export/exporter
                    (constantly (exporter :local local-failure))
                    otlp/exporter
                    (constantly (exporter :remote remote-failure))
                    live/open!
                    (constantly {:close! #(swap! events conj :source-close)})
                    sdk/init! (fn [options] (reset! installed options) ::handle)
                    sdk/shutdown!
                    (fn [_]
                      (export/shutdown! (first (:span-processors @installed))))
                    jdbc.chdb.durable/checkpoint!
                    (fn [_]
                      (swap! events conj :checkpoint)
                      {:status :committed})]
        (let [lifecycle
              (embedded/start!
               {:db-spec ::durable
                :sdk-options {:metrics? false :logs? false}
                :span-pipelines
                {:local {:schedule-delay-ms 60000}
                 :remote {:endpoint "http://collector:4318"
                          :schedule-delay-ms 60000}}})
              stopped (embedded/stop! lifecycle)
              stopped-again (embedded/stop! lifecycle)]
          (is (= :closed (:status stopped)))
          (is (= stopped stopped-again))
          (is (= {:ok? false :failure :returned-false}
                 (get-in stopped [:telemetry :sdk])))
          (is (= {:ok? false :failure local-failure}
                 (get-in stopped [:telemetry :span-pipelines :local])))
          (is (= (if remote-failure
                   {:ok? false :failure remote-failure}
                   {:ok? true})
                 (get-in stopped [:telemetry :span-pipelines :remote])))
          (is (= [:local-shutdown :remote-shutdown :source-close :checkpoint
                  :connection-close]
                 @events))
          (is (not (.contains (pr-str stopped) "telemetry-secret"))))))))

(deftest sdk-wide-failure-is-not-attributed-to-a-span-destination
  (let [shutdown-attempts (atom 0)
        connection (reify java.io.Closeable (close [_] nil))
        local (test-exporter (atom []) (atom 0))
        remote (test-exporter (atom []) (atom 0))
        installed (atom nil)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  chdb-export/exporter (constantly local)
                  otlp/exporter (constantly remote)
                  live/open! (constantly {:close! (fn [] nil)})
                  sdk/init! (fn [options] (reset! installed options) ::handle)
                  sdk/shutdown!
                  (fn [_]
                    ;; The span processor succeeds. The first overall false
                    ;; models a later metric/log component failure.
                    (export/shutdown! (first (:span-processors @installed)))
                    (> (swap! shutdown-attempts inc) 1))
                  jdbc.chdb.durable/checkpoint!
                  (constantly {:status :committed})]
      (let [lifecycle
            (embedded/start!
             {:db-spec ::durable
              :sdk-options {:metrics? true :logs? true}
              :span-pipelines
              {:local {:schedule-delay-ms 60000}
               :remote {:endpoint "http://collector:4318"
                        :schedule-delay-ms 60000}}})
            first-stop (embedded/stop! lifecycle)]
        (is (= :closed (:status first-stop)))
        (is (= {:ok? false :failure :returned-false}
               (get-in first-stop [:telemetry :sdk])))
        (is (= {:local {:ok? true} :remote {:ok? true}}
               (get-in first-stop [:telemetry :span-pipelines]))
            "an SDK-wide failure must not overwrite the local span result")
        (is (= first-stop (embedded/stop! lifecycle)))
        (is (= 1 @shutdown-attempts)
            "a terminal SDK result must not be presented as retryable")))))

(deftest embedded-remote-config-ignores-ambient-otel-values
  (let [ambient {"OTEL_EXPORTER_OTLP_ENDPOINT" "https://ambient-base.invalid"
                 "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"
                 "https://ambient-user:secret@collector.invalid/v1/traces"
                 "OTEL_EXPORTER_OTLP_HEADERS" "authorization=ambient-secret"
                 "OTEL_EXPORTER_OTLP_TIMEOUT" "1"
                 "OSCOPE_REMOTE_HEADERS" "authorization=approved-secret"}
        reads (atom [])
        built (atom nil)
        direct-exporter otlp/exporter]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  host/getenv (fn [name]
                                (swap! reads conj name)
                                (get ambient name))
                  otlp/exporter (fn [options]
                                  (let [exporter (direct-exporter options)]
                                    (reset! built exporter)
                                    exporter))
                  jdbc/connection
                  (fn [_]
                    (throw (ex-info "stop after remote construction" {})))]
      (is (thrown? Exception
                   (embedded/start!
                    {:db-spec ::durable
                     :span-pipelines
                     {:local {}
                      :remote {:endpoint "https://approved.invalid:4318"
                               :headers-env "OSCOPE_REMOTE_HEADERS"}}})))
      (is (= "https://approved.invalid:4318/v1/traces" (:url @built)))
      (is (= {"authorization" "approved-secret"} (:headers @built)))
      (is (= 10000 (:timeout-ms @built)))
      (is (= ["OSCOPE_REMOTE_HEADERS"] @reads)
          "the explicit secret reference is the only environment read"))))

(deftest dual-span-configuration-fails-before-jdbc-or-workers
  (doseq [span-pipelines
          [{:local {} :remote {}}
           {:local {} :remote {:endpoint "http://one" :traces-url "http://two"}}
           {:local {} :remote {:endpoint "grpc://collector"}}
           {:local {} :remote {:endpoint "http://"}}
           {:local {} :remote {:endpoint "https://bad host"}}
           {:local {} :remote {:endpoint "https://user@collector"}}
           {:local {} :remote {:endpoint "https://collector?token=secret"}}
           {:local {} :remote {:traces-url "https://collector/v1/traces#secret"}}
           {:local {:max-queue-size 0} :remote {:endpoint "http://collector"}}
           {:local {} :remote {:endpoint "http://collector" :headers {"x" "secret"}}}
           {:local {} :remote {:endpoint "http://collector" :headers-env "bad-name"}}
           {:local {} :remote {:endpoint "http://collector" :max-retries -1}}
           {:local {} :remote {:endpoint "http://collector" :insecure? :yes}}
           {:local {} :remote {:endpoint "http://collector"} :extra {}}]]
    (let [opened? (atom false)
          remote-opened? (atom false)]
      (with-redefs [sdk/tracer-provider (constantly nil)
                    sdk/meter-provider (constantly nil)
                    sdk/logger-provider (constantly nil)
                    jdbc/connection (fn [_] (reset! opened? true))
                    otlp/exporter (fn [_] (reset! remote-opened? true))]
        (is (thrown? Exception
                     (embedded/start! {:db-spec ::durable
                                       :span-pipelines span-pipelines})))
        (is (false? @opened?))
        (is (false? @remote-opened?))))))

(defn- typed-schema-options []
  {:approved-manifest ::approved
   :registry-backend ::registry})

(deftest embedded-installs-confirmed-schema-before-exporter-and-sdk
  (let [events (atom [])
        descriptor-set (Object.)
        typed-options (typed-schema-options)
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))
        exporter-options (atom nil)
        source-options (atom nil)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (fn [_]
                                    (swap! events conj :connection-open)
                                    connection)
                  jdbc.chdb.durable/connection-role (constantly :writer)
                  typed-schema/install!
                  (fn [actual options]
                    (is (= connection actual))
                    (is (identical? typed-options options))
                    (swap! events conj :schema-installed)
                    {:descriptor-set descriptor-set})
                  typed-schema/descriptor-options
                  (fn [context]
                    (is (identical? descriptor-set (:descriptor-set context)))
                    {:typed-span-descriptors descriptor-set})
                  jdbc.chdb.durable/checkpoint!
                  (fn [_]
                    (swap! events conj :checkpoint)
                    {:status :committed})
                  chdb-export/exporter
                  (fn [options]
                    (reset! exporter-options options)
                    (swap! events conj :exporter-open)
                    ::exporter)
                  live/open!
                  (fn [options]
                    (reset! source-options options)
                    (swap! events conj :source-open)
                    {:close! #(swap! events conj :source-close)})
                  sdk/init! (fn [_]
                              (swap! events conj :sdk-start)
                              ::sdk-handle)
                  sdk/shutdown! (fn [_]
                                  (swap! events conj :sdk-stop)
                                  true)]
      (let [lifecycle
            (embedded/start! {:db-spec ::durable
                              :typed-schema typed-options})]
        (is (= [:connection-open :schema-installed :checkpoint :exporter-open
                :source-open :sdk-start]
               @events))
        (is (false? (:create-schema? @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors @source-options)))
        (is (identical? descriptor-set
                        (:typed-span-descriptors lifecycle)))
        (is (= {:status :closed :phase :closed} (embedded/stop! lifecycle)))))))

(deftest embedded-routes-log-capability-only-to-log-export
  (let [descriptor-set (Object.)
        connection (reify java.io.Closeable (close [_]))
        exporter-options (atom nil)
        source-options (atom nil)]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  jdbc.chdb.durable/connection-role (constantly :writer)
                  typed-schema/install!
                  (fn [& _] {:descriptor-set descriptor-set})
                  typed-schema/descriptor-options
                  (fn [_] {:typed-log-descriptors descriptor-set})
                  jdbc.chdb.durable/checkpoint!
                  (constantly {:status :committed})
                  chdb-export/exporter
                  (fn [options]
                    (reset! exporter-options options)
                    ::exporter)
                  live/open!
                  (fn [options]
                    (reset! source-options options)
                    {:close! (fn [])})
                  sdk/init! (constantly ::sdk-handle)
                  sdk/shutdown! (constantly true)]
      (let [lifecycle
            (embedded/start! {:db-spec ::durable
                              :typed-schema (typed-schema-options)})]
        (is (identical? descriptor-set
                        (:typed-log-descriptors @exporter-options)))
        (is (nil? (:typed-span-descriptors @exporter-options)))
        (is (identical? descriptor-set
                        (:typed-log-descriptors @source-options)))
        (is (identical? descriptor-set (:typed-log-descriptors lifecycle)))
        (is (nil? (:typed-span-descriptors lifecycle)))
        (is (= {:status :closed :phase :closed}
               (embedded/stop! lifecycle)))))))

(deftest embedded-schema-failure-closes-before-exporter-or-sdk
  (let [events (atom [])
        typed-options (typed-schema-options)
        connection (reify java.io.Closeable
                     (close [_] (swap! events conj :connection-close)))]
    (with-redefs [sdk/tracer-provider (constantly nil)
                  sdk/meter-provider (constantly nil)
                  sdk/logger-provider (constantly nil)
                  jdbc/connection (constantly connection)
                  jdbc.chdb.durable/connection-role (constantly :writer)
                  typed-schema/install! (fn [& _]
                                          (swap! events conj :schema-failed)
                                          (throw (ex-info "failed" {})))
                  chdb-export/exporter (fn [_]
                                         (swap! events conj :exporter-open))
                  sdk/init! (fn [_] (swap! events conj :sdk-start))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (embedded/start! {:db-spec ::durable
                                     :typed-schema typed-options})))
      (is (= [:schema-failed :connection-close] @events)))))
