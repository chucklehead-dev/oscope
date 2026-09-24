(ns oscope.core
  "Jolt binding to liboscope_core: record spans and logs into an embedded chDB
  store from inside the process, with no OTel SDK, no exporter and no JSON.

  Every call goes straight to the C ABI through jolt.ffi. A call crossing costs
  about 14 ns here, so unlike Go there is no batching layer: the recorder's
  per-thread ring already makes each call a plain memory write. String
  arguments use jolt.ffi's :string type, which Chez converts natively; that
  measured far cheaper than building (pointer, length) pairs through
  .getBytes and ffi/write-bytes.

  Parentage comes from the recorder's per-OS-thread span stack, so a span
  must start and end on the same OS thread with nothing parked in between.
  Code that parks a fiber inside a span needs explicit parents (osc_span_record)."
  (:refer-clojure :exclude [key])
  (:require [jolt.ffi :as ffi]))

;; The foreign functions with-span expands into are public and named -start,
;; -end, -abort, -attr-str, -attr-i64: the macro calls them directly, because
;; a Jolt wrapper fn around each costs ~60 ns per span.

;; Loaded when the namespace loads, so keys can be interned at load time
;; (the recorder's interner works before start!).
(ffi/load-library (or (System/getenv "OSCOPE_LIB") "liboscope_core.so"))

(ffi/defcfn ^:private c-set-signal-handlers "osc_set_engine_signal_handlers" [:uint8] :void)
(ffi/defcfn ^:private c-start "osc_start_cstr" [:string :string :uint8 :string :uint32 :uint32 :uint32] :int)
(ffi/defcfn ^:private c-flush "osc_flush" [:uint32] :int :blocking)
(ffi/defcfn ^:private c-stop "osc_stop" [] :int :blocking)
(ffi/defcfn ^:private c-dropped "osc_dropped" [] :uint64)
(ffi/defcfn ^:private c-intern "osc_intern_cstr" [:string] :uint32)
(ffi/defcfn -start "osc_span_start" [:uint32 :uint8] :uint64)
(ffi/defcfn -attr-str "osc_span_attr_cstr" [:uint64 :uint32 :string] :void)
(ffi/defcfn -attr-i64 "osc_span_attr_i64" [:uint64 :uint32 :int64] :void)
(ffi/defcfn ^:private c-attr-f64 "osc_span_attr_f64" [:uint64 :uint32 :double] :void)
(ffi/defcfn ^:private c-attr-bool "osc_span_attr_bool" [:uint64 :uint32 :uint8] :void)
(ffi/defcfn -end "osc_span_end" [:uint64 :uint8] :void)
(ffi/defcfn -abort "osc_span_abort" [:uint64] :void)
(ffi/defcfn ^:private c-log3 "osc_log_cstr3" [:uint8 :string :uint32 :string :uint32 :string :uint32 :string] :int)
;; not :blocking: Chez does not allow :string arguments on a collect-safe call
(ffi/defcfn ^:private c-query "osc_query_text" [:string :string] :pointer)
(ffi/defcfn ^:private c-free-text "osc_free_text" [:pointer] :void)

;; ------------------------------------------------------------------ lifecycle

(defn start!
  "Boot the embedded store. Options: :db (path; nil = in-memory), :wal (path),
  :fsync? , :service, :batch-rows, :flush-ms, :ring-bytes (per recording
  thread; default 1 MiB). Engine signal handlers are turned off first so the
  host runtime keeps its own."
  [{:keys [db wal fsync? service batch-rows flush-ms ring-bytes]}]
  (c-set-signal-handlers 0)
  (when-not (zero? (c-start (or db "") (or wal "") (if fsync? 1 0) (or service "")
                            (or batch-rows 0) (or flush-ms 0) (or ring-bytes 0)))
    (throw (ex-info "oscope: start failed" {:db db})))
  :started)

(defn flush!
  "Barrier: everything recorded before the call is committed when it returns true."
  ([] (flush! 10000))
  ([timeout-ms] (zero? (c-flush timeout-ms))))

(defn stop! [] (zero? (c-stop)))

(defn dropped [] (c-dropped))

(defn query
  "Run a read-only query against the store. Returns the result as a string."
  ([sql] (query sql "TSV"))
  ([sql format]
   (let [p (c-query sql format)]
     (when (ffi/null? p)
       (throw (ex-info "oscope: query failed" {:sql sql})))
     (try (ffi/ptr->string p) (finally (c-free-text p))))))

;; ------------------------------------------------------------------ keys

(defn key
  "Intern a span name or attribute key once; pass the returned id afterwards.
  Declare keys at namespace level: (def k-model (oscope/key \"gen_ai.request.model\"))."
  [s]
  (c-intern s))

(defmacro defkeys
  "(defkeys k-route \"http.route\" k-status \"http.response.status_code\")"
  [& pairs]
  `(do ~@(for [[sym s] (partition 2 pairs)] `(def ~sym (key ~s)))))

;; ------------------------------------------------------------------ spans

(def kinds {:internal 1 :server 2 :client 3 :producer 4 :consumer 5})
(def ^:private k-exception (key "exception.message"))

(defn attr!
  "Set an attribute on an open span. k is an interned key."
  [span k v]
  (cond
    (string? v) (-attr-str span k v)
    (integer? v) (-attr-i64 span k v)
    (float? v) (c-attr-f64 span k v)
    (boolean? v) (c-attr-bool span k (if v 1 0))
    (nil? v) nil
    :else (-attr-str span k (str v))))

(defn- literal-attr-form
  "Pick the C call at macroexpansion time when the value is a literal."
  [span k v]
  (cond
    (string? v) `(~'oscope.core/-attr-str ~span ~k ~v)
    (integer? v) `(~'oscope.core/-attr-i64 ~span ~k ~v)
    :else `(attr! ~span ~k ~v)))


(defn record-error!
  "Attach an exception's message to a span and mark it Error. For code that
  already catches the exception inside the span."
  [span e]
  (-attr-str span k-exception (or (ex-message e) (str e)))
  (-end span 2))

(defmacro with-span
  "Run body inside a span. name is an interned key; opts are a kind keyword
  followed by attribute key/value pairs. The span symbol is bound for attr!.

    (with-span [s k-chat :client k-model \"claude\" k-turn 3]
      (attr! s k-out-tokens n)
      ...)

  The guard is a try/finally, not a catch: in Jolt a catch costs ~300 bytes of
  heap on every entry even when nothing throws, a finally ~100. Normal
  completion ends the span OK; the finally calls osc_span_abort, which is a
  no-op by then. An escaping exception reaches the abort with the span still
  open, so it ends Error with exception.escaped=true. The message is not
  visible to a finally; call record-error! from a catch that wants it."
  [[span-sym name & opts] & body]
  (let [[kind attrs] (if (keyword? (first opts)) [(first opts) (rest opts)] [:internal opts])
        kind-code (get kinds kind 1)]
    `(let [~span-sym (-start ~name ~kind-code)]
       (try
         ~@(for [[k v] (partition 2 attrs)] (literal-attr-form span-sym k v))
         (let [r# (do ~@body)]
           (-end ~span-sym 0)
           r#)
         (finally
           (-abort ~span-sym))))))

;; ------------------------------------------------------------------ logs

(def severities {:debug 5 :info 9 :warn 13 :error 17})

(defn log!
  "Record a log correlated with this thread's innermost open span. Up to three
  attributes, as interned-key / string pairs."
  ([sev body] (c-log3 (severities sev 9) body 0 nil 0 nil 0 nil))
  ([sev body k1 v1] (c-log3 (severities sev 9) body k1 (str v1) 0 nil 0 nil))
  ([sev body k1 v1 k2 v2] (c-log3 (severities sev 9) body k1 (str v1) k2 (str v2) 0 nil))
  ([sev body k1 v1 k2 v2 k3 v3] (c-log3 (severities sev 9) body k1 (str v1) k2 (str v2) k3 (str v3))))
