(ns oscope.field-slot-native-probe
  "Test-only witness around the existing typed SDK capture/live-query fixture.
  Numeric counters only; this is not timing or a delivery receipt by itself."
  (:require [clojure.test :as test]
            [oscope.embedded :as embedded]
            [oscope.export-options-native-test-runner :as fixture]))

(defn field-slot-factory-var []
  (require 'otel.exporter.chdb.native-projection)
  (let [v (ns-resolve 'otel.exporter.chdb.native-projection 'load-trace-field-slots!)]
    (when-not (and v (ifn? @v))
      (throw (ex-info "Bounded field-slot exporter capability is unavailable"
                      {:type ::unavailable-field-slots})))
    v))

(defn -main [& args]
  (when (seq args)
    (throw (ex-info "Unexpected field-slot probe arguments" {})))
  (let [factory-var (field-slot-factory-var)
        factory @factory-var
        start embedded/start!
        run-tests test/test-vars
        active? (atom false)
        selected (atom 0)]
    (with-redefs-fn
      {factory-var
       (fn [& arguments]
         (let [project (apply factory arguments)]
           (if project
             (fn [span]
               (let [result (project span)]
                 ;; Native false AND nil mean fallback, not a selected result.
                 (when (and @active? result) (swap! selected inc))
                 result))
             project)))
       #'embedded/start!
       (fn [options]
         (reset! active? false)
         (reset! selected 0)
         (let [owner (start options)]
           (reset! active? true)
           owner))
       #'test/test-vars
       (fn [vars]
         (let [result (run-tests vars)]
           ;; Executed before the existing child emits its terminal receipt.
           ;; No-op/declined/startup-only activity cannot satisfy this gate.
           (when-not (and @active? (pos? @selected))
             (throw (ex-info "Post-startup bounded field-slot ingestion was not observed"
                             {:type ::unobserved-field-slots})))
           (println :bounded-field-slots-ingestion-calls @selected)
           result))}
      #(fixture/-main "stock-codec" "embedded-typed"))))
