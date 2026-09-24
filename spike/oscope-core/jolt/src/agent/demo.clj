(ns agent.demo
  "A simulated LLM agent, instrumented the way samizdat instruments itself,
  recording into oscope's in-process store. No OTel SDK, no exporter, no
  network: every span and log goes through the C ABI into embedded chDB.
  Afterwards the demo reads the store back as Langfuse-style views."
  (:require [oscope.core :as o]
            [jolt.host :as host]))

;; Span names and attribute keys, interned once.
(o/defkeys
  n-run "agent.run" n-turn "agent.turn" n-chat "model.chat" n-tool "tool"
  k-session "langfuse.session.id" k-obs-type "langfuse.observation.type"
  k-op "gen_ai.operation.name" k-provider "gen_ai.provider.name"
  k-model "gen_ai.request.model" k-in "gen_ai.usage.input_tokens"
  k-out "gen_ai.usage.output_tokens" k-finish "gen_ai.response.finish_reasons"
  k-turn "agent.turn.index" k-tool "gen_ai.tool.name" k-chars "tool.output.chars")

(def models ["claude-sonnet-5" "claude-haiku-4-5" "claude-opus-5-5"])
(def tools ["read_file" "grep" "run_tests" "edit_file"])

(defn- spin-us
  "Stand-in for real work: burn roughly n microseconds without parking the thread."
  [n]
  (let [until (+ (host/mono-nanos) (* 1000 n))]
    (loop [] (when (< (host/mono-nanos) until) (recur)))))

(defn- call-model [rng model turn]
  (o/with-span [s n-chat :client k-obs-type "generation" k-op "chat"
                k-provider "anthropic" k-model model k-turn turn]
    (let [in (+ 800 (* turn 600) (.nextInt rng 400))
          out (+ 40 (.nextInt rng 400))
          done? (and (> turn 1) (< (.nextInt rng 100) 40))]
      (spin-us (+ 50 (.nextInt rng 150)))
      (o/attr! s k-in in)
      (o/attr! s k-out out)
      (o/attr! s k-finish (if done? "end_turn" "tool_use"))
      done?)))

(defn- run-tool [rng tool]
  (o/with-span [s n-tool :internal k-obs-type "tool" k-tool tool]
    (spin-us (+ 20 (.nextInt rng 80)))
    (if (and (= tool "run_tests") (< (.nextInt rng 100) 20))
      ;; a failure the tool itself reports: record the message on its span
      (let [e (ex-info "2 tests failed" {:tool tool})]
        (o/record-error! s e)
        (throw e))
      (o/attr! s k-chars (+ 100 (.nextInt rng 5000))))))

(defn- run-session [rng session]
  (let [model (nth models (.nextInt rng (count models)))]
    (o/with-span [_ n-run :server k-obs-type "agent" k-session session k-model model]
      (loop [turn 1]
        (let [done? (o/with-span [_ n-turn k-turn turn]
                      (let [done? (call-model rng model turn)]
                        (when-not done?
                          (let [tool (nth tools (.nextInt rng (count tools)))]
                            (try (run-tool rng tool)
                                 (catch Exception e
                                   (o/log! :warn "tool failed" k-tool tool k-session session)))))
                        done?))]
          (if (or done? (>= turn 8)) turn (recur (inc turn)))))
      (o/log! :info "run finished" k-session session))))

(defn- report [title sql]
  (println)
  (println title)
  (print (o/query sql "PrettyCompactMonoBlock")))

(defn -main [& args]
  (let [sessions (Integer/parseInt (or (first args) "2000"))
        workers 4
        db (or (System/getenv "OSCOPE_DB") "./oscope-data")]
    (o/start! {:db db :service "jolt-agent"})
    (let [t0 (host/mono-nanos)
          jobs (doall (for [w (range workers)]
                        (future
                          (let [rng (java.util.Random. (+ 42 w))]
                            (doseq [i (range w sessions workers)]
                              (run-session rng (str "session-" i)))))))]
      (doseq [j jobs] @j)
      (let [ms (/ (- (host/mono-nanos) t0) 1e6)]
        (println (format "ran %d agent sessions on %d threads in %.0f ms" sessions workers ms))))
    (when-not (o/flush! 30000) (throw (ex-info "flush timed out" {})))
    (println "dropped records:" (o/dropped))

    (report "Generations by model (Langfuse 'generation' observations)" "
SELECT SpanAttributes['gen_ai.request.model'] AS model, count() AS generations,
       sum(toUInt64(SpanAttributes['gen_ai.usage.input_tokens'])) AS input_tokens,
       sum(toUInt64(SpanAttributes['gen_ai.usage.output_tokens'])) AS output_tokens,
       round(quantile(0.5)(Duration) / 1e3) AS p50_us
FROM otel_traces WHERE SpanAttributes['langfuse.observation.type'] = 'generation'
GROUP BY model ORDER BY generations DESC")

    (report "Tool calls" "
SELECT SpanAttributes['gen_ai.tool.name'] AS tool, count() AS calls,
       countIf(StatusCode = 'Error') AS errors,
       anyIf(SpanAttributes['exception.message'], StatusCode = 'Error') AS sample_error
FROM otel_traces WHERE SpanAttributes['langfuse.observation.type'] = 'tool'
GROUP BY tool ORDER BY calls DESC")

    (report "Sessions (top 5 by tokens)" "
WITH sess AS (SELECT TraceId, any(SpanAttributes['langfuse.session.id']) AS session
              FROM otel_traces WHERE SpanName = 'agent.run' GROUP BY TraceId)
SELECT s.session, countIf(t.SpanName = 'agent.turn') AS turns,
       sum(toUInt64OrZero(t.SpanAttributes['gen_ai.usage.input_tokens'])
         + toUInt64OrZero(t.SpanAttributes['gen_ai.usage.output_tokens'])) AS tokens,
       countIf(t.StatusCode = 'Error') AS errors
FROM otel_traces t INNER JOIN sess s ON t.TraceId = s.TraceId
GROUP BY s.session ORDER BY tokens DESC, s.session LIMIT 5")

    (report "Integrity: orphan spans, and logs attached to a span" "
SELECT (SELECT count() FROM otel_traces WHERE ParentSpanId != ''
          AND ParentSpanId NOT IN (SELECT SpanId FROM otel_traces)) AS orphan_spans,
       (SELECT count() FROM otel_logs) AS logs,
       (SELECT count() FROM otel_logs WHERE SpanId IN (SELECT SpanId FROM otel_traces)) AS logs_with_span")

    (report "One session, as a trace" "
WITH (SELECT TraceId FROM otel_traces WHERE SpanName = 'tool' AND StatusCode = 'Error' LIMIT 1) AS tid
SELECT repeat('  ', multiIf(SpanName = 'agent.run', 0, SpanName = 'agent.turn', 1, 2)) || SpanName AS span,
       StatusCode, round(Duration / 1e3) AS us,
       SpanAttributes['gen_ai.request.model'] AS model,
       SpanAttributes['gen_ai.usage.output_tokens'] AS out_tokens,
       SpanAttributes['gen_ai.tool.name'] AS tool,
       SpanAttributes['exception.message'] AS error
FROM otel_traces WHERE TraceId = tid ORDER BY Timestamp")
    (o/stop!)
    (shutdown-agents)))
