(ns oscope.check
  "End-to-end checks of the binding's span guard against a real store."
  (:require [oscope.core :as o]
            [clojure.string :as str]))

(o/defkeys n-parent "check.parent" n-child "check.child" n-sibling "check.sibling"
  n-caught "check.caught" k-case "check.case")

(defn- q [sql] (str/trim (o/query sql)))

(defn- expect [label got want]
  (println (format "%-62s %s" label (if (= got want) "ok" (str "FAIL: got " (pr-str got) " want " (pr-str want)))))
  (= got want))

(defn -main [& _]
  (o/start! {})
  ;; an exception escapes a child span; the parent catches it and carries on
  (o/with-span [p n-parent k-case "escape"]
    (try (o/with-span [c n-child k-case "escape"] (throw (ex-info "escaped" {})))
         (catch Exception _ nil))
    (o/with-span [s n-sibling k-case "escape"]))
  ;; record-error! from inside the span keeps the message
  (try (o/with-span [c n-caught k-case "caught"]
         (let [e (ex-info "kept message" {})] (o/record-error! c e) (throw e)))
       (catch Exception _ nil))
  ;; an exception escaping the outermost span still closes it
  (try (o/with-span [p n-parent k-case "top"] (throw (ex-info "top" {})))
       (catch Exception _ nil))
  (o/flush!)
  (let [results
        [(expect "escaped child: status Error, exception.escaped=true"
                 (q "SELECT StatusCode, SpanAttributes['exception.escaped'] FROM otel_traces WHERE SpanName = 'check.child'")
                 "Error\ttrue")
         (expect "sibling after the escape is parented to the parent, not the child"
                 (q "SELECT p.SpanName FROM otel_traces s JOIN otel_traces p ON s.ParentSpanId = p.SpanId WHERE s.SpanName = 'check.sibling'")
                 "check.parent")
         (expect "parent that caught the exception ends Unset"
                 (q "SELECT StatusCode FROM otel_traces WHERE SpanName = 'check.parent' AND SpanAttributes['check.case'] = 'escape'")
                 "Unset")
         (expect "record-error! keeps the message, and the span is recorded once"
                 (q "SELECT count(), any(StatusCode), any(SpanAttributes['exception.message']) FROM otel_traces WHERE SpanName = 'check.caught'")
                 "1\tError\tkept message")
         (expect "outermost span closed by the guard"
                 (q "SELECT StatusCode, ParentSpanId = '' AS is_root FROM otel_traces WHERE SpanName = 'check.parent' AND SpanAttributes['check.case'] = 'top'")
                 "Error\t1")]]
    (o/stop!)
    (println (if (every? true? results) "ALL CHECKS PASSED" "CHECKS FAILED"))
    (when-not (every? true? results) (System/exit 1))))
