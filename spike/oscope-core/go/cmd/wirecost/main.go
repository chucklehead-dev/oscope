// wirecost drives one fixed workload through different telemetry paths and
// measures what each costs the application process.
//
// Workload per "request": a SERVER span with three children (4 spans, 3
// attributes each) plus one log correlated to the server span.
//
// Modes:
//
//	none            no telemetry (baseline for the pacing loop)
//	oscope          in-process: liboscope_core -> embedded chDB on disk
//	otlp-http       OTel Go SDK -> OTLP/HTTP protobuf -> wiresink
//	otlp-http-gzip  same, gzip compression
//	otlp-grpc       OTel Go SDK -> OTLP/gRPC -> wiresink
//	dd              dd-trace-go v2 -> Datadog agent protocol (msgpack) -> wiresink
//	                (spans only: dd-trace-go does not ship logs)
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"net/http"
	"os"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/DataDog/dd-trace-go/v2/ddtrace/tracer"
	"github.com/chucklehead-dev/oscope/spike/oscope-core/go/oscope"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploggrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlplog/otlploghttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	otellog "go.opentelemetry.io/otel/log"
	sdklog "go.opentelemetry.io/otel/sdk/log"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	semconv "go.opentelemetry.io/otel/semconv/v1.26.0"
	"go.opentelemetry.io/otel/trace"
)

type backend interface {
	request(ctx context.Context, i int)
	flush() error
	counts() (spans, logs int64, err error)
	stop()
}

var childNames = [3]string{"load-order", "priceOrder", "db.query"}

// ------------------------------------------------------------------ none

type none struct{}

func (none) request(context.Context, int)  {}
func (none) flush() error                  { return nil }
func (none) counts() (int64, int64, error) { return 0, 0, nil }
func (none) stop()                         {}

// ------------------------------------------------------------------ oscope

type osc struct{}

func (osc) request(ctx context.Context, i int) {
	ctx, root := oscope.StartSpan(ctx, "GET /orders/{id}", oscope.KindServer)
	root.SetString("http.route", "/orders/{id}")
	root.SetString("http.request.method", "GET")
	root.SetInt("http.response.status_code", 200)
	for _, n := range childNames {
		_, c := oscope.StartSpan(ctx, n, oscope.KindInternal)
		c.SetString("code.function", n)
		c.SetInt("order.id", int64(i))
		c.SetString("user.tier", "gold")
		c.End()
	}
	oscope.Log(ctx, oscope.SevInfo, "order served",
		oscope.Attr{Key: "order.id", Int: int64(i), IsInt: true}, oscope.Attr{Key: "user.tier", Str: "gold"})
	root.End()
}
func (osc) flush() error { return oscope.Flush(30 * time.Second) }
func (osc) counts() (int64, int64, error) {
	out, err := oscope.Query("SELECT (SELECT count() FROM otel_traces), (SELECT count() FROM otel_logs)", "TSV")
	if err != nil {
		return 0, 0, err
	}
	f := strings.Fields(out)
	s, _ := strconv.ParseInt(f[0], 10, 64)
	l, _ := strconv.ParseInt(f[1], 10, 64)
	return s, l, nil
}
func (osc) stop() { oscope.Stop() }

// ------------------------------------------------------------------ OTel SDK

type otelB struct {
	tp *sdktrace.TracerProvider
	lp *sdklog.LoggerProvider
	tr trace.Tracer
	lg otellog.Logger
}

func newOtel(mode string) *otelB {
	ctx := context.Background()
	res := resource.NewWithAttributes(semconv.SchemaURL, semconv.ServiceName("wirecost"))
	var se sdktrace.SpanExporter
	var le sdklog.Exporter
	var err error
	switch mode {
	case "otlp-http", "otlp-http-gzip":
		c := otlptracehttp.NoCompression
		lc := otlploghttp.NoCompression
		if mode == "otlp-http-gzip" {
			c, lc = otlptracehttp.GzipCompression, otlploghttp.GzipCompression
		}
		se, err = otlptracehttp.New(ctx, otlptracehttp.WithEndpoint("127.0.0.1:4318"), otlptracehttp.WithInsecure(), otlptracehttp.WithCompression(c))
		if err == nil {
			le, err = otlploghttp.New(ctx, otlploghttp.WithEndpoint("127.0.0.1:4318"), otlploghttp.WithInsecure(), otlploghttp.WithCompression(lc))
		}
	case "otlp-grpc":
		se, err = otlptracegrpc.New(ctx, otlptracegrpc.WithEndpoint("127.0.0.1:4317"), otlptracegrpc.WithInsecure())
		if err == nil {
			le, err = otlploggrpc.New(ctx, otlploggrpc.WithEndpoint("127.0.0.1:4317"), otlploggrpc.WithInsecure())
		}
	}
	if err != nil {
		panic(err)
	}
	// Queue sized so a 5k req/s run does not drop: the SDK default (2048) is
	// smaller than one second of this workload.
	tp := sdktrace.NewTracerProvider(sdktrace.WithResource(res), sdktrace.WithSampler(sdktrace.AlwaysSample()),
		sdktrace.WithBatcher(se, sdktrace.WithMaxQueueSize(65536), sdktrace.WithMaxExportBatchSize(2048)))
	lp := sdklog.NewLoggerProvider(sdklog.WithResource(res),
		sdklog.WithProcessor(sdklog.NewBatchProcessor(le, sdklog.WithMaxQueueSize(65536), sdklog.WithExportMaxBatchSize(2048))))
	return &otelB{tp: tp, lp: lp, tr: tp.Tracer("wirecost"), lg: lp.Logger("wirecost")}
}

func (o *otelB) request(ctx context.Context, i int) {
	ctx, root := o.tr.Start(ctx, "GET /orders/{id}", trace.WithSpanKind(trace.SpanKindServer),
		trace.WithAttributes(attribute.String("http.route", "/orders/{id}"), attribute.String("http.request.method", "GET")))
	root.SetAttributes(attribute.Int("http.response.status_code", 200))
	for _, n := range childNames {
		_, c := o.tr.Start(ctx, n, trace.WithAttributes(attribute.String("code.function", n),
			attribute.Int("order.id", i), attribute.String("user.tier", "gold")))
		c.End()
	}
	var r otellog.Record
	r.SetTimestamp(time.Now())
	r.SetSeverity(otellog.SeverityInfo)
	r.SetBody(attribute.StringValue("order served"))
	r.AddAttributes(attribute.Int("order.id", i), attribute.String("user.tier", "gold"))
	o.lg.Emit(ctx, r)
	root.End()
}
func (o *otelB) flush() error {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	if err := o.tp.ForceFlush(ctx); err != nil {
		return err
	}
	return o.lp.ForceFlush(ctx)
}
func (o *otelB) counts() (int64, int64, error) { return sinkCounts() }
func (o *otelB) stop() {
	o.tp.Shutdown(context.Background())
	o.lp.Shutdown(context.Background())
}

// ------------------------------------------------------------------ dd-trace-go

type ddB struct{}

func newDD() ddB {
	for k, v := range map[string]string{
		"DD_INSTRUMENTATION_TELEMETRY_ENABLED": "false", "DD_TRACE_STARTUP_LOGS": "false",
		"DD_REMOTE_CONFIGURATION_ENABLED": "false", "DD_TRACE_STATS_COMPUTATION_ENABLED": "false",
		"DD_TRACE_SAMPLE_RATE": "1", "DD_TRACE_SAMPLING_RULES": `[{"sample_rate":1}]`,
	} {
		os.Setenv(k, v)
	}
	if err := tracer.Start(tracer.WithAgentAddr("127.0.0.1:8126"), tracer.WithService("wirecost"), tracer.WithLogStartup(false)); err != nil {
		panic(err)
	}
	return ddB{}
}
func (ddB) request(ctx context.Context, i int) {
	root, ctx := tracer.StartSpanFromContext(ctx, "GET /orders/{id}", tracer.SpanType("web"),
		tracer.Tag("http.route", "/orders/{id}"), tracer.Tag("http.request.method", "GET"))
	root.SetTag("http.response.status_code", 200)
	for _, n := range childNames {
		c, _ := tracer.StartSpanFromContext(ctx, n, tracer.Tag("code.function", n),
			tracer.Tag("order.id", i), tracer.Tag("user.tier", "gold"))
		c.Finish()
	}
	root.Finish()
}
func (ddB) flush() error {
	tracer.Flush()
	time.Sleep(2 * time.Second) // Flush is async; give the transport time to deliver
	return nil
}
func (ddB) counts() (int64, int64, error) { return sinkCounts() }
func (ddB) stop()                         { tracer.Stop() }

// ------------------------------------------------------------------ measurement

func sinkCounts() (int64, int64, error) {
	var st map[string]int64
	r, err := http.Get("http://127.0.0.1:4318/stats")
	if err != nil {
		return 0, 0, err
	}
	defer r.Body.Close()
	if err := json.NewDecoder(r.Body).Decode(&st); err != nil {
		return 0, 0, err
	}
	sinkStats = st
	return st["spans"], st["logs"], nil
}

var sinkStats map[string]int64

// threadCPU sums utime+stime per thread name from /proc (Linux only). Go's
// threads carry the executable's name; the recorder's are osc-drain and
// osc-writer; everything else in an oscope run is chDB's own thread pools.
func threadCPU() map[string]time.Duration {
	out := map[string]time.Duration{}
	dirs, _ := os.ReadDir("/proc/self/task")
	for _, d := range dirs {
		comm, err1 := os.ReadFile("/proc/self/task/" + d.Name() + "/comm")
		stat, err2 := os.ReadFile("/proc/self/task/" + d.Name() + "/stat")
		if err1 != nil || err2 != nil {
			continue
		}
		f := strings.Fields(string(stat[strings.LastIndexByte(string(stat), ')')+2:]))
		ut, _ := strconv.ParseInt(f[11], 10, 64)
		st, _ := strconv.ParseInt(f[12], 10, 64)
		out[strings.TrimSpace(string(comm))] += time.Duration(ut+st) * 10 * time.Millisecond
	}
	return out
}

func groupCPU(before, after map[string]time.Duration) map[string]float64 {
	self := strings.TrimSpace(func() string { b, _ := os.ReadFile("/proc/self/comm"); return string(b) }())
	g := map[string]float64{}
	for name, v := range after {
		k := "chdb"
		switch {
		case name == self:
			k = "go"
		case strings.HasPrefix(name, "osc-"):
			k = name
		}
		g[k] += float64((v - before[name]).Microseconds())
	}
	return g
}

func rusage() (cpu time.Duration, maxRSSKB int64) {
	var ru syscall.Rusage
	syscall.Getrusage(syscall.RUSAGE_SELF, &ru)
	return time.Duration(ru.Utime.Nano() + ru.Stime.Nano()), ru.Maxrss
}

func main() {
	mode := flag.String("mode", "oscope", "none|oscope|otlp-http|otlp-http-gzip|otlp-grpc|dd")
	rps := flag.Int("rps", 5000, "requests per second")
	secs := flag.Int("secs", 10, "measured seconds")
	dbPath := flag.String("db", "", "oscope store path (required for -mode oscope)")
	batchRows := flag.Uint("batch", 8192, "oscope: rows per insert")
	flushMs := flag.Uint("flushms", 1000, "oscope: max ms between inserts")
	flag.Parse()

	var b backend
	switch *mode {
	case "none":
		b = none{}
	case "oscope":
		if err := oscope.Start(oscope.Config{DBPath: *dbPath, Service: "wirecost", RingBytes: 16 << 20, BatchRows: uint32(*batchRows), FlushMs: uint32(*flushMs)}); err != nil {
			panic(err)
		}
		b = osc{}
	case "otlp-http", "otlp-http-gzip", "otlp-grpc":
		b = newOtel(*mode)
	case "dd":
		b = newDD()
	default:
		panic("unknown mode")
	}
	ctx := context.Background()
	// warm up (connections, interning, pools), then reset the receiver
	for i := 0; i < 2000; i++ {
		b.request(ctx, i)
	}
	if err := b.flush(); err != nil {
		panic(err)
	}
	warmSpans, warmLogs, _ := b.counts()
	if *mode != "oscope" && *mode != "none" {
		http.Get("http://127.0.0.1:4318/reset")
		warmSpans, warmLogs = 0, 0
	}
	runtime.GC()

	var ms0, ms1 runtime.MemStats
	runtime.ReadMemStats(&ms0)
	cpu0, _ := rusage()
	th0 := threadCPU()
	start := time.Now()
	total := *rps * *secs
	var inCall time.Duration
	tick := time.NewTicker(time.Millisecond)
	per := *rps / 1000
	for i := 0; i < total; {
		<-tick.C
		t0 := time.Now()
		for k := 0; k < per && i < total; k++ {
			b.request(ctx, i)
			i++
		}
		inCall += time.Since(t0)
	}
	tick.Stop()
	gen := time.Since(start)
	if err := b.flush(); err != nil {
		panic(err)
	}
	cpu1, rss := rusage()
	th1 := threadCPU()
	runtime.ReadMemStats(&ms1)
	spans, logs, err := b.counts()
	if err != nil {
		panic(err)
	}
	spans -= warmSpans
	logs -= warmLogs
	b.stop()

	wantSpans, wantLogs := int64(total*4), int64(total)
	if *mode == "dd" {
		wantLogs = 0
	}
	if *mode == "none" {
		wantSpans, wantLogs = 0, 0
	}
	res := map[string]any{
		"mode": *mode, "requests": total, "gen_s": gen.Seconds(),
		"caller_ns_per_req":   float64(inCall.Nanoseconds()) / float64(total),
		"process_cpu_ms":      float64((cpu1 - cpu0).Microseconds()) / 1000,
		"cpu_us_per_req":      float64((cpu1 - cpu0).Microseconds()) / float64(total),
		"allocs_per_req":      float64(ms1.Mallocs-ms0.Mallocs) / float64(total),
		"alloc_bytes_per_req": float64(ms1.TotalAlloc-ms0.TotalAlloc) / float64(total),
		"gc_cycles":           ms1.NumGC - ms0.NumGC,
		"max_rss_mb":          float64(rss) / 1024,
		"spans":               spans, "logs": logs,
		"complete": spans == wantSpans && logs == wantLogs,
	}
	if *mode == "oscope" {
		per := map[string]float64{}
		for k, v := range groupCPU(th0, th1) {
			per[k] = v / float64(total)
		}
		res["thread_cpu_us_per_req"] = per
		res["batch_rows"] = *batchRows
	} else if *mode != "none" {
		per := map[string]float64{}
		for k, v := range groupCPU(th0, th1) {
			per[k] = v / float64(total)
		}
		res["thread_cpu_us_per_req"] = per
	}
	if sinkStats != nil {
		res["wire_bytes_per_req"] = float64(sinkStats["wire_bytes"]) / float64(total)
		res["sink_cpu_us_per_req"] = float64(sinkStats["cpu_ns"]) / 1000 / float64(total)
		res["sink_requests"] = sinkStats["requests"]
		res["sink_decode_errors"] = sinkStats["decode_errors"]
	}
	out, _ := json.Marshal(res)
	fmt.Println(string(out))
}
