// Package instr is what Orchestrion injects into an application. Nothing in
// the application imports it by hand: orchestrion.yml in this directory tells
// the compiler where to call it.
//
// Configuration comes from the environment, so an unmodified app can be
// pointed at a store: OSCOPE_DB (default ./oscope-data), OSCOPE_WAL,
// OSCOPE_WAL_FSYNC=1, OSCOPE_SERVICE (default: executable name).
package instr

import (
	"context"
	"encoding/hex"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/chucklehead-dev/oscope/spike/oscope-core/go/oscope"
)

var (
	startOnce sync.Once
	started   bool
)

// Keys are interned once; hot paths then skip the per-call lookup.
var (
	kCodeFunction = oscope.NewKey("code.function")
	kRoute        = oscope.NewKey("http.route")
	kMethod       = oscope.NewKey("http.request.method")
	kPath         = oscope.NewKey("url.path")
	kStatus       = oscope.NewKey("http.response.status_code")
	kException    = oscope.NewKey("exception.message")
	kURL          = oscope.NewKey("url.full")
	kServer       = oscope.NewKey("server.address")
)

func ensureStarted() bool {
	startOnce.Do(func() {
		cfg := oscope.Config{
			DBPath:   envOr("OSCOPE_DB", "./oscope-data"),
			WALPath:  os.Getenv("OSCOPE_WAL"),
			WALFsync: os.Getenv("OSCOPE_WAL_FSYNC") == "1",
			Service:  envOr("OSCOPE_SERVICE", filepath.Base(os.Args[0])),
			FlushMs:  250,
			// OSCOPE_ENGINE_SIGNALS=1 keeps chDB's handlers (demonstration only)
			KeepEngineSignalHandlers: os.Getenv("OSCOPE_ENGINE_SIGNALS") == "1",
		}
		if err := oscope.Start(cfg); err != nil {
			fmt.Fprintln(os.Stderr, "oscope: telemetry disabled:", err)
			return
		}
		started = true
	})
	return started
}

func envOr(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

// Init is injected at the top of main.main.
func Init() { ensureStarted() }

// Shutdown is deferred at the top of main.main: flush, then stop the engine.
func Shutdown() {
	if !started {
		return
	}
	if err := oscope.Flush(10 * time.Second); err != nil {
		fmt.Fprintln(os.Stderr, "oscope:", err)
	}
	if n := oscope.Dropped(); n > 0 {
		fmt.Fprintf(os.Stderr, "oscope: %d records dropped\n", n)
	}
	_ = oscope.Stop()
}

// ------------------------------------------------------------ //oscope:span

// StartSpan backs the //oscope:span directive. extra is key, value pairs from
// the directive's arguments.
func StartSpan(ctx context.Context, name string, extra ...string) (context.Context, func(error)) {
	if !ensureStarted() {
		return ctx, func(error) {}
	}
	ctx, s := oscope.StartSpan(ctx, name, oscope.KindInternal)
	s.SetStringK(kCodeFunction, name)
	for i := 0; i+1 < len(extra); i += 2 {
		s.SetString(extra[i], extra[i+1])
	}
	return ctx, func(err error) {
		s.SetError(err)
		s.End()
	}
}

// ------------------------------------------------------------ net/http server

type statusWriter struct {
	http.ResponseWriter
	code int
}

func (w *statusWriter) WriteHeader(c int)           { w.code = c; w.ResponseWriter.WriteHeader(c) }
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

// WrapHandler records a SERVER span per request, continuing any incoming W3C
// traceparent. The span is named after the ServeMux pattern once routing has
// happened.
func WrapHandler(h http.Handler) http.Handler {
	if h == nil {
		h = http.DefaultServeMux
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !ensureStarted() {
			h.ServeHTTP(w, r)
			return
		}
		ctx := r.Context()
		if sc, ok := parseTraceparent(r.Header.Get("traceparent")); ok {
			ctx = oscope.ContextWithSpanContext(ctx, sc)
		}
		ctx, s := oscope.StartSpan(ctx, r.Method, oscope.KindServer)
		sw := &statusWriter{ResponseWriter: w, code: 200}
		r = r.WithContext(ctx)
		defer func() {
			rec := recover()
			if rec != nil {
				sw.code = 500
				s.SetStringK(kException, fmt.Sprint(rec))
			}
			if r.Pattern != "" {
				s.SetName(r.Pattern)
				s.SetStringK(kRoute, r.Pattern)
			}
			s.SetStringK(kMethod, r.Method)
			s.SetStringK(kPath, r.URL.Path)
			s.SetIntK(kStatus, int64(sw.code))
			if sw.code >= 500 {
				s.SetStatus(oscope.StatusError)
			}
			s.End()
			if rec != nil {
				panic(rec) // net/http's own recovery still applies
			}
		}()
		h.ServeHTTP(sw, r)
	})
}

// ListenAndServe replaces calls to net/http.ListenAndServe.
func ListenAndServe(addr string, h http.Handler) error {
	return http.ListenAndServe(addr, WrapHandler(h))
}

// ------------------------------------------------------------ net/http client

// Do replaces (*net/http.Client).Do: a CLIENT span, with traceparent injected
// so the server side joins the same trace.
func Do(c *http.Client, req *http.Request) (*http.Response, error) {
	if !ensureStarted() {
		return c.Do(req)
	}
	ctx, s := oscope.StartSpan(req.Context(), req.Method, oscope.KindClient)
	sc := s.Context()
	req = req.Clone(ctx)
	req.Header.Set("traceparent", "00-"+hex.EncodeToString(sc.TraceID[:])+"-"+fmt.Sprintf("%016x", sc.SpanID)+"-01")
	s.SetStringK(kMethod, req.Method)
	s.SetStringK(kURL, req.URL.String())
	s.SetStringK(kServer, req.URL.Host)
	resp, err := c.Do(req)
	if err != nil {
		s.SetError(err)
	} else {
		s.SetIntK(kStatus, int64(resp.StatusCode))
		if resp.StatusCode >= 500 {
			s.SetStatus(oscope.StatusError)
		}
	}
	s.End()
	return resp, err
}

func parseTraceparent(h string) (oscope.SpanContext, bool) {
	var sc oscope.SpanContext
	p := strings.Split(h, "-")
	if len(p) != 4 || len(p[1]) != 32 || len(p[2]) != 16 {
		return sc, false
	}
	if _, err := hex.Decode(sc.TraceID[:], []byte(p[1])); err != nil {
		return sc, false
	}
	id, err := strconv.ParseUint(p[2], 16, 64)
	if err != nil || id == 0 {
		return sc, false
	}
	sc.SpanID = id
	return sc, true
}

// ------------------------------------------------------------ log/slog

func toAttrs(args []any) []oscope.Attr {
	out := make([]oscope.Attr, 0, len(args)/2)
	for i := 0; i+1 < len(args); i += 2 {
		k, ok := args[i].(string)
		if !ok {
			continue
		}
		switch v := args[i+1].(type) {
		case int:
			out = append(out, oscope.Attr{Key: k, Int: int64(v), IsInt: true})
		case int64:
			out = append(out, oscope.Attr{Key: k, Int: v, IsInt: true})
		default:
			out = append(out, oscope.Attr{Key: k, Str: fmt.Sprint(v)})
		}
	}
	return out
}

// InfoContext / ErrorContext replace the slog functions of the same name:
// record a correlated log, then log as the app intended.
func InfoContext(ctx context.Context, msg string, args ...any) {
	if ensureStarted() {
		oscope.Log(ctx, oscope.SevInfo, msg, toAttrs(args)...)
	}
	slog.InfoContext(ctx, msg, args...)
}

func ErrorContext(ctx context.Context, msg string, args ...any) {
	if ensureStarted() {
		oscope.Log(ctx, oscope.SevError, msg, toAttrs(args)...)
	}
	slog.ErrorContext(ctx, msg, args...)
}
