# Python host via ctypes: same library, no Python-side OTel SDK.
import ctypes as C, time
lib = C.CDLL("target/release/liboscope_core.so")
class Str(C.Structure): _fields_ = [("ptr", C.c_char_p), ("len", C.c_size_t)]
class Cfg(C.Structure): _fields_ = [("db_path", C.c_char_p), ("wal_path", C.c_char_p), ("wal_fsync", C.c_uint8),
    ("service_name", C.c_char_p), ("batch_rows", C.c_uint32), ("flush_interval_ms", C.c_uint32), ("ring_bytes", C.c_uint32)]
def s(b): return Str(b, len(b))
lib.osc_intern.restype = C.c_uint32; lib.osc_intern.argtypes = [Str]
lib.osc_span_start.restype = C.c_uint64; lib.osc_span_start.argtypes = [C.c_uint32, C.c_uint8]
lib.osc_span_attr_i64.argtypes = [C.c_uint64, C.c_uint32, C.c_int64]
lib.osc_span_attr_str.argtypes = [C.c_uint64, C.c_uint32, Str]
lib.osc_span_end.argtypes = [C.c_uint64, C.c_uint8]
lib.osc_query.argtypes = [Str, C.c_char_p, C.POINTER(C.POINTER(C.c_uint8)), C.POINTER(C.c_size_t)]
assert lib.osc_start(C.byref(Cfg(None, None, 0, b"py-demo", 8192, 100, 1 << 26))) == 0
name, k1, k2 = lib.osc_intern(s(b"llm.generate")), lib.osc_intern(s(b"gen_ai.usage.input_tokens")), lib.osc_intern(s(b"gen_ai.request.model"))
model = s(b"claude-sonnet")
N = 200_000
t = time.perf_counter()
for i in range(N):
    sp = lib.osc_span_start(name, 3)
    lib.osc_span_attr_i64(sp, k1, i % 900)
    lib.osc_span_attr_str(sp, k2, model)
    lib.osc_span_end(sp, 1)
el = time.perf_counter() - t
assert lib.osc_flush(10000) == 0
out, n = C.POINTER(C.c_uint8)(), C.c_size_t()
lib.osc_query(s(b"SELECT count(), countIf(mapContains(SpanAttributes, 'gen_ai.usage.input_tokens')) with_tokens, countIf(mapContains(SpanAttributes, 'gen_ai.request.model')) with_model, any(SpanAttributes) FROM otel_traces"), b"TSV", C.byref(out), C.byref(n))
print(f"python ctypes: {el / N * 1e9:.0f} ns/span (4 FFI calls), chDB says: {C.string_at(out, n.value).decode().strip()}")
lib.osc_dropped.restype = C.c_uint64
print("dropped:", lib.osc_dropped())
time.sleep(2)
lib.osc_query(s(b"SELECT count() FROM otel_traces"), b"TSV", C.byref(out), C.byref(n))
print("count after 2s:", C.string_at(out, n.value).decode().strip())
