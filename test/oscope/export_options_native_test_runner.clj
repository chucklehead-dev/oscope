(ns oscope.export-options-native-test-runner
  "Run existing real capture/fresh-reader fixtures with explicit encoding.
  One native writer lifetime per invocation; reader receipt checks are unchanged."
  (:require [clojure.test :as test]
            [jdbc.chdb.native :as native]
            [jdbc.chdb.json-each-row :as row-encoder]
            [oscope.durable-native-child-runner :as child]
            [oscope.embedded :as embedded]
            [oscope.server :as server]))

(defn- json-companion-capability []
  (require 'clojure.data.json.jolt-native)
  (let [adapter (ns-resolve 'clojure.data.json.jolt-native 'write-str)
        companion (ns-resolve 'clojure.data.json.jolt-native '*batch-string-output-writer*)]
    (when-not (and adapter companion)
      (throw (ex-info "Required JSON companion capability is unavailable"
                      {:type ::missing-json-companion})))
    {:adapter adapter :companion companion}))

(defn -main [mode consumer & extra]
  (when (and (seq extra) (not= ["--require-json-companion"] (vec extra)))
    (throw (ex-info "Unknown native encoding fixture option" {})))
  (let [options (case mode
                  "configured" {:insert-format :json-compact-each-row :json-backend :configured}
                  "owned" {:insert-format :json-compact-each-row
                           :json-backend :native-guarded-byte-batch
                           :owned-statement-output? true}
                  "stream" {:insert-format :json-compact-each-row
                            :json-backend :native-guarded-byte-batch
                            :owned-statement-output? true
                            :datetime64-wire :raw-ticks}
                  "stream-budget" {:insert-format :json-compact-each-row
                                   :json-backend :native-guarded-byte-batch
                                   :owned-statement-output? true
                                   :datetime64-wire :raw-ticks
                                   :max-insert-bytes (* 16 1024 1024)}
                  "stream-fallback" {:insert-format :json-compact-each-row
                                     :json-backend :configured
                                     :datetime64-wire :raw-ticks}
                  ("stock-codec" "stock-reader") {:insert-format :json-compact-each-row
                                 :json-backend :native-guarded-byte-batch
                                 :owned-statement-output? true
                                 :datetime64-wire :raw-ticks
                                 :experimental-span-codec? true
                                 :experimental-span-payload-cache? true
                                 :experimental-span-flat-map-cache? true
                                 :experimental-span-nested-fragment-cache? true}
                  (throw (ex-info "Unknown encoding fixture mode" {})))
        index (case consumer "standalone" "2" "embedded" "4" "embedded-typed" "3"
                    (throw (ex-info "Unknown encoding fixture consumer" {})))
        start-embedded embedded/start!
        start-server server/start!
        run-tests test/test-vars
        insert native/chdb-stream-insert-n
        stream-calls (atom 0)
        require-json? (seq extra)
        stock-reader? (= mode "stock-reader")
        _ (when (and stock-reader? (not= consumer "standalone"))
            (throw (ex-info "HTTP reader fixture requires standalone ingress" {})))
        stock-codec? (contains? #{"stock-codec" "stock-reader"} mode)
        _ (when (and stock-codec? require-json?)
            (throw (ex-info "Stock codec fixture does not use the legacy JSON companion" {})))
        codec-var (when stock-codec?
                    (require 'jolt.codec.experimental)
                    (ns-resolve 'jolt.codec.experimental 'encode-prefixed-records))
        original-codec (when codec-var @codec-var)
        codec-calls (atom 0)
        reader-var (when stock-reader?
                     (require 'jolt.codec.reader.experimental)
                     (ns-resolve 'jolt.codec.reader.experimental 'try-read-string))
        original-reader (when reader-var @reader-var)
        reader-results (atom {:accepted 0 :declined 0})
        {:keys [adapter companion]} (when require-json? (json-companion-capability))
        original-adapter (when adapter @adapter)
        observations (atom {:calls 0 :bound 0 :empty-bound 0})
        json-overrides
        (if require-json?
          {adapter
           (fn [value]
             ;; Test-only numeric observation. Keep the real adapter and its
             ;; guards; do not replace a hook or alter encoded values.
             (let [bound? (some? @companion)]
               (swap! observations
                      (fn [state]
                        (cond-> (update state :calls inc)
                          bound? (update :bound inc)
                          (and bound? (vector? value) (empty? value))
                          (update :empty-bound inc)))))
             (original-adapter value))}
          {})
        overrides (if stock-codec?
                    (assoc json-overrides
                           codec-var (fn [& args] (swap! codec-calls inc) (apply original-codec args))
                           #'row-encoder/open-encoder
                           (fn [& _] (throw (ex-info "Stock codec fixture loaded legacy JSON writer" {}))))
                    json-overrides)
        overrides (cond-> overrides
                    stock-reader? (assoc reader-var
                                     (fn [& args]
                                       (let [result (apply original-reader args)]
                                         (swap! reader-results update (if result :accepted :declined) inc)
                                         result))))
        streaming? (contains? #{"stream" "stream-budget" "stream-fallback" "stock-codec" "stock-reader"} mode)
        handoff (fn [args]
                  (if (and streaming? (= consumer "standalone"))
                    ;; This fixture must route options from its versioned file,
                    ;; not repair a dropped option at the server handoff.
                    (do
                      (when-not (and (= options (:export-options args))
                                     (or (not stock-reader?) (= :experimental-stock (:json-reader args)))
                                     (true? (get-in args [:db-spec :owned-compact-stream?])))
                        (throw (ex-info "Configured streaming options were not routed"
                                        {:type ::unrouted-stream-options})))
                      args)
                    (cond-> (assoc args :export-options options)
                      streaming? (assoc-in [:db-spec :owned-compact-stream?] true))))]
    (println :export-encoding-fixture mode consumer)
    (with-redefs-fn overrides
      (fn []
        (with-redefs [embedded/start!
                  (fn [args]
                    (let [lifecycle (start-embedded (handoff args))]
                      ;; Constructor activity is not ingestion evidence.
                      (when require-json?
                        (reset! observations {:calls 0 :bound 0 :empty-bound 0}))
                      (when stock-codec?
                        (reset! codec-calls 0)
                        (reset! stream-calls 0)
                        (reset! reader-results {:accepted 0 :declined 0}))
                      lifecycle))
                  server/start!
                  (fn [args]
                    (let [lifecycle (start-server (handoff args))]
                      (when require-json?
                        (reset! observations {:calls 0 :bound 0 :empty-bound 0}))
                      (when stock-codec?
                        (reset! codec-calls 0)
                        (reset! stream-calls 0)
                        (reset! reader-results {:accepted 0 :declined 0}))
                      lifecycle))
                  native/chdb-stream-insert-n
                  (fn [& args] (swap! stream-calls inc) (apply insert args))
                  test/test-vars
                  (fn [vars]
                    (let [result
                          (if (and streaming? (= consumer "standalone"))
                            (let [binding-var (ns-resolve 'oscope.durable-integration-test
                                                         '*storage-options*)]
                              (when-not binding-var
                                (throw (ex-info "Configured fixture binding is unavailable" {})))
                              (with-bindings (cond-> {binding-var {:owned-compact-stream? true
                                                                 :export-options options}}
                                               stock-reader?
                                               (assoc (ns-resolve 'oscope.durable-integration-test '*ingest-options*)
                                                      {:json-reader :experimental-stock}))
                                (run-tests vars)))
                            (run-tests vars))]
                      ;; Before the child emits its terminal receipt/exits.
                      ;; A no-op fixture must not qualify streaming merely by
                      ;; accepting configuration or keeping the old assertions.
                      (when (contains? #{"stream" "stream-budget" "stock-codec" "stock-reader"} mode)
                        (when-not (pos? @stream-calls)
                          (throw (ex-info "Native stream execution was not observed"
                                          {:type ::unobserved-stream})))
                        (println :stream-native-calls @stream-calls))
                      (when stock-codec?
                        (when-not (pos? @codec-calls)
                          (throw (ex-info "Explicit span codec execution was not observed"
                                          {:type ::unobserved-stock-codec})))
                        (println :stock-codec-calls @codec-calls))
                      (when stock-reader?
                        (when-not (= {:accepted 3 :declined 0} @reader-results)
                          (throw (ex-info "Three actual native HTTP document reads were not observed"
                                          {:type ::unobserved-stock-reader})))
                        (println :stock-reader-results @reader-results))
                      (when (= mode "stream-fallback")
                        (when-not (zero? @stream-calls)
                          (throw (ex-info "Ordinary statements unexpectedly used streaming"
                                          {:type ::unexpected-stream})))
                        (println :stream-fallback-native-calls @stream-calls))
                      (when require-json?
                        (let [{:keys [calls bound empty-bound]} @observations]
                          (if (= :native-guarded-byte-batch (:json-backend options))
                            (when-not (and (pos? bound) (pos? empty-bound))
                              (throw (ex-info "JSON companion execution was not observed"
                                              {:type ::unobserved-json-companion})))
                            (when-not (zero? calls)
                              (throw (ex-info "Configured encoding used the native JSON adapter"
                                              {:type ::unexpected-json-companion}))))
                          (println :json-companion-calls calls :bound bound
                                   :empty-bound empty-bound)))
                      result))]
          (child/-main "--fixture" index))))))
