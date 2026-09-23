// A C program embedding the collector in-process: record nested spans and
// logs from two threads, flush, then read the results back through osc_query.
#include "oscope.h"
#include <pthread.h>
#include <stdio.h>
#include <string.h>

static uint32_t N_REQ, N_DB, K_ROUTE, K_ROWS, K_ORDER;

static void *worker(void *arg) {
  long id = (long)arg;
  for (int i = 0; i < 1000; i++) {
    uint64_t req = osc_span_start(N_REQ, OSC_KIND_SERVER);
    osc_span_attr_str(req, K_ROUTE, OSC_S("/api/orders/{id}"));
    uint64_t db = osc_span_start(N_DB, OSC_KIND_CLIENT); // child of req
    osc_span_attr_i64(db, K_ROWS, i % 7);
    osc_span_end(db, OSC_STATUS_OK);
    osc_attr a = {.key = K_ORDER, .tag = OSC_I64, .v.i = id * 100000 + i};
    osc_log(9, OSC_S("order placed"), &a, 1); // correlated with req
    osc_span_end(req, i % 50 == 0 ? OSC_STATUS_ERROR : OSC_STATUS_OK);
  }
  return NULL;
}

int main(void) {
  osc_config cfg = {.service_name = "c-demo", .batch_rows = 512, .flush_interval_ms = 100};
  if (osc_start(&cfg) != 0) return 1;
  N_REQ = osc_intern(OSC_S("GET /api/orders/{id}"));
  N_DB = osc_intern(OSC_S("SELECT orders"));
  K_ROUTE = osc_intern(OSC_S("http.route"));
  K_ROWS = osc_intern(OSC_S("db.rows"));
  K_ORDER = osc_intern(OSC_S("order.id"));

  pthread_t t[2];
  for (long i = 0; i < 2; i++) pthread_create(&t[i], NULL, worker, (void *)i);
  for (int i = 0; i < 2; i++) pthread_join(t[i], NULL);
  if (osc_flush(5000) != 0) { fprintf(stderr, "flush failed\n"); return 1; }

  const char *q =
      "SELECT s.SpanName, s.SpanKind, s.StatusCode, count() n, "
      "countIf(p.SpanId != '') with_parent_in_table, "
      "sum(l.cnt) correlated_logs "
      "FROM otel_traces s "
      "LEFT JOIN otel_traces p ON s.ParentSpanId = p.SpanId "
      "LEFT JOIN (SELECT SpanId, count() cnt FROM otel_logs GROUP BY SpanId) l ON l.SpanId = s.SpanId "
      "GROUP BY 1,2,3 ORDER BY 1,3";
  uint8_t *out; size_t len;
  if (osc_query((osc_str){(const uint8_t *)q, strlen(q)}, "PrettyCompactMonoBlock", &out, &len) != 0) return 1;
  fwrite(out, 1, len, stdout);
  osc_free(out, len);
  printf("dropped=%llu\n", (unsigned long long)osc_dropped());
  return osc_stop();
}
