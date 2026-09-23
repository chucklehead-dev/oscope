package oscope

import (
	"context"
	"encoding/hex"
	"errors"
	"os"
	"strings"
	"testing"
	"time"
)

func TestMain(m *testing.M) {
	if err := Start(Config{Service: "oscope-go-test", RingBytes: 64 << 20, BatchRows: 8192}); err != nil {
		panic(err)
	}
	code := m.Run()
	Stop()
	os.Exit(code)
}

func TestRoundTrip(t *testing.T) {
	ctx, root := StartSpan(context.Background(), "go-root", KindServer)
	root.SetString("http.route", "/orders/{id}")
	_, child := StartSpan(ctx, "go-child", KindInternal)
	child.SetInt("db.rows", 3)
	child.SetFloat("ratio", 0.5)
	child.SetBool("cached", true)
	child.SetError(errors.New("boom"))
	child.End()
	Log(ctx, SevInfo, "hello", Attr{Key: "order.id", Int: 42, IsInt: true})
	rootSC := root.Context() // capture before End: the span returns to a pool
	root.End()
	if err := Flush(5 * time.Second); err != nil {
		t.Fatal(err)
	}
	// the store is shared by every test run in this process: scope to this trace
	tid := hex.EncodeToString(rootSC.TraceID[:])
	out, err := Query(`SELECT c.SpanName, c.StatusCode, c.SpanAttributes['db.rows'], c.SpanAttributes['ratio'],
	  c.SpanAttributes['cached'], c.SpanAttributes['exception.message'], p.SpanName
	  FROM otel_traces c JOIN otel_traces p ON c.ParentSpanId = p.SpanId AND c.TraceId = p.TraceId
	  WHERE c.SpanName = 'go-child' AND c.TraceId = '`+tid+`'`, "TSV")
	if err != nil {
		t.Fatal(err)
	}
	if got, want := strings.TrimSpace(out), "go-child\tError\t3\t0.5\ttrue\tboom\tgo-root"; got != want {
		t.Fatalf("got %q want %q", got, want)
	}
	out, _ = Query(`SELECT l.Body, l.LogAttributes['order.id'], t.SpanName FROM otel_logs l
	  JOIN otel_traces t ON l.SpanId = t.SpanId WHERE l.Body = 'hello' AND l.TraceId = '`+tid+`'`, "TSV")
	if got, want := strings.TrimSpace(out), "hello\t42\tgo-root"; got != want {
		t.Fatalf("log: got %q want %q", got, want)
	}
}

// start + 3 attributes + end: the shape of an instrumented function call.
func BenchmarkSpan(b *testing.B) {
	ctx := context.Background()
	Intern("bench.span")
	b.ReportAllocs()
	for b.Loop() {
		_, s := StartSpan(ctx, "bench.span", KindInternal)
		s.SetString("http.route", "/orders/{id}")
		s.SetInt("http.response.status_code", 200)
		s.SetString("user.tier", "gold")
		s.End()
	}
}

func BenchmarkSpanParallel(b *testing.B) {
	ctx := context.Background()
	b.ReportAllocs()
	b.RunParallel(func(pb *testing.PB) {
		for pb.Next() {
			_, s := StartSpan(ctx, "bench.span", KindInternal)
			s.SetString("http.route", "/orders/{id}")
			s.SetInt("http.response.status_code", 200)
			s.SetString("user.tier", "gold")
			s.End()
		}
	})
}

func BenchmarkLog(b *testing.B) {
	ctx := context.Background()
	b.ReportAllocs()
	for b.Loop() {
		Log(ctx, SevInfo, "order served", Attr{Key: "order.id", Int: 42, IsInt: true})
	}
}

var (
	bkName   = NewKey("bench.span")
	bkRoute  = NewKey("http.route")
	bkStatus = NewKey("http.response.status_code")
	bkTier   = NewKey("user.tier")
)

// Same shape with pre-interned keys: what generated instrumentation uses.
func BenchmarkSpanKeys(b *testing.B) {
	ctx := context.Background()
	b.ReportAllocs()
	for b.Loop() {
		_, s := StartSpanK(ctx, bkName, KindInternal)
		s.SetStringK(bkRoute, "/orders/{id}")
		s.SetIntK(bkStatus, 200)
		s.SetStringK(bkTier, "gold")
		s.End()
	}
}

// Keys, but one cgo crossing per span (batching off).
func BenchmarkSpanKeysDirect(b *testing.B) {
	ctx := context.Background()
	submitAll()
	directMode = true
	defer func() { directMode = false }()
	b.ReportAllocs()
	for b.Loop() {
		_, s := StartSpanK(ctx, bkName, KindInternal)
		s.SetStringK(bkRoute, "/orders/{id}")
		s.SetIntK(bkStatus, 200)
		s.SetStringK(bkTier, "gold")
		s.End()
	}
}

func BenchmarkSpanKeysParallel(b *testing.B) {
	ctx := context.Background()
	b.ReportAllocs()
	b.RunParallel(func(pb *testing.PB) {
		for pb.Next() {
			_, s := StartSpanK(ctx, bkName, KindInternal)
			s.SetStringK(bkRoute, "/orders/{id}")
			s.SetIntK(bkStatus, 200)
			s.SetStringK(bkTier, "gold")
			s.End()
		}
	})
}
