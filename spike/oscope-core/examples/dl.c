// Host loads the collector at runtime, the way ctypes / jolt.ffi / JNA would.
#include "oscope.h"
#include <dlfcn.h>
#include <stdio.h>
int main(void) {
  void *h = dlopen("liboscope_core.so", RTLD_NOW | RTLD_LOCAL);
  if (!h) { fprintf(stderr, "dlopen: %s\n", dlerror()); return 1; }
  int32_t (*start)(const osc_config *) = dlsym(h, "osc_start");
  uint32_t (*intern)(osc_str) = dlsym(h, "osc_intern");
  int32_t (*rec)(const osc_span_rec *) = dlsym(h, "osc_span_record");
  int32_t (*flush)(uint32_t) = dlsym(h, "osc_flush");
  int32_t (*query)(osc_str, const char *, uint8_t **, size_t *) = dlsym(h, "osc_query");
  osc_config cfg = {.service_name = "dl-demo", .batch_rows = 256, .flush_interval_ms = 50};
  if (start(&cfg)) return 1;
  uint32_t name = intern(OSC_S("frame")), k = intern(OSC_S("frame.ms"));
  for (int i = 0; i < 1000; i++) {
    osc_attr a = {.key = k, .tag = OSC_F64, .v.f = 16.6 + (i % 5)};
    osc_span_rec r = {.name = name, .kind = OSC_KIND_INTERNAL, .start_ns = 1758600000000000000ull + i * 16600000ull,
                      .end_ns = 1758600000000000000ull + i * 16600000ull + 16000000, .attrs = &a, .n_attrs = 1};
    rec(&r);
  }
  if (flush(5000)) return 1;
  uint8_t *out; size_t n;
  query(OSC_S("SELECT count(), round(avg(toFloat64(SpanAttributes['frame.ms'])),2) FROM otel_traces"), "TSV", &out, &n);
  fwrite(out, 1, n, stdout);
  return 0;
}
