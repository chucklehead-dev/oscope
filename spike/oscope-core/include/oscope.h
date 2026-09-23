/* oscope-core C ABI (spike). All strings are borrowed (ptr,len); nothing on
 * the recording path allocates, locks, or blocks. Handles are integers. */
#ifndef OSCOPE_H
#define OSCOPE_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

typedef struct { const uint8_t *ptr; size_t len; } osc_str;
typedef union { int64_t i; double f; uint8_t b; osc_str s; } osc_value;
enum { OSC_STR = 1, OSC_I64 = 2, OSC_F64 = 3, OSC_BOOL = 4 };
typedef struct { uint32_t key; uint8_t tag; osc_value v; } osc_attr;

enum { OSC_KIND_INTERNAL = 1, OSC_KIND_SERVER, OSC_KIND_CLIENT, OSC_KIND_PRODUCER, OSC_KIND_CONSUMER };
enum { OSC_STATUS_UNSET = 0, OSC_STATUS_OK = 1, OSC_STATUS_ERROR = 2 };

typedef struct {
  uint8_t trace_id[16];      /* all-zero: inherit current / new trace */
  uint64_t span_id;          /* 0: generate */
  uint64_t parent_span_id;
  uint32_t name;             /* interned */
  uint8_t kind, status;
  uint64_t start_ns, end_ns; /* unix epoch ns */
  const osc_attr *attrs;
  uint32_t n_attrs;
} osc_span_rec;

typedef struct {
  const char *db_path;       /* NULL = in-memory chDB */
  const char *wal_path;      /* NULL = ephemeral */
  uint8_t wal_fsync;
  const char *service_name;
  uint32_t batch_rows, flush_interval_ms, ring_bytes;
} osc_config;

int32_t  osc_start(const osc_config *cfg);
int32_t  osc_stop(void);
int32_t  osc_flush(uint32_t timeout_ms);
uint64_t osc_dropped(void);

uint32_t osc_intern(osc_str s);          /* setup time */
uint64_t osc_now_ns(void);

/* incremental API: parent = innermost open span on this thread */
uint64_t osc_span_start(uint32_t name, uint8_t kind);
void     osc_span_attr_str(uint64_t span, uint32_t key, osc_str v);
void     osc_span_attr_i64(uint64_t span, uint32_t key, int64_t v);
void     osc_span_attr_f64(uint64_t span, uint32_t key, double v);
void     osc_span_attr_bool(uint64_t span, uint32_t key, uint8_t v);
void     osc_span_end(uint64_t span, uint8_t status);

/* one-shot API: one crossing per finished span */
int32_t  osc_span_record(const osc_span_rec *rec);
int32_t  osc_log(uint8_t severity, osc_str body, const osc_attr *attrs, uint32_t n_attrs);

/* read side, for an embedded UI in any language */
int32_t  osc_query(osc_str sql, const char *format, uint8_t **out, size_t *out_len);
void     osc_free(uint8_t *p, size_t len);

#define OSC_S(lit) ((osc_str){ (const uint8_t *)(lit), sizeof(lit) - 1 })
#ifdef __cplusplus
}
#endif
#endif
