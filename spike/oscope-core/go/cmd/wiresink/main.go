// wiresink is a stand-in receiver for the wire-cost comparison. It fully
// decodes what it is sent (so span/log counts are verified and decode cost is
// real) but stores nothing. It serves:
//   :4318  OTLP/HTTP protobuf (optionally gzip)   /v1/traces, /v1/logs
//   :4317  OTLP/gRPC                              TraceService, LogsService
//   :8126  Datadog agent                          /v0.4/traces (msgpack)
// GET :4318/stats returns counters and this process's CPU time; /reset zeroes them.
package main

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/tinylib/msgp/msgp"
	collogs "go.opentelemetry.io/proto/otlp/collector/logs/v1"
	coltrace "go.opentelemetry.io/proto/otlp/collector/trace/v1"
	"google.golang.org/grpc"
	_ "google.golang.org/grpc/encoding/gzip"
	"google.golang.org/protobuf/proto"
)

var (
	reqs, wireBytes, spans, logs atomic.Int64
	decodeErrs                   atomic.Int64
)

func cpuNow() time.Duration {
	var ru syscall.Rusage
	syscall.Getrusage(syscall.RUSAGE_SELF, &ru)
	return time.Duration(ru.Utime.Nano() + ru.Stime.Nano())
}

var cpuBase atomic.Int64

func body(r *http.Request) ([]byte, error) {
	var rd io.Reader = r.Body
	if r.Header.Get("Content-Encoding") == "gzip" {
		z, err := gzip.NewReader(r.Body)
		if err != nil {
			return nil, err
		}
		rd = z
	}
	b, err := io.ReadAll(rd)
	return b, err
}

type countingConn struct{ net.Conn }

func (c countingConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	wireBytes.Add(int64(n))
	return n, err
}

type countingListener struct{ net.Listener }

func (l countingListener) Accept() (net.Conn, error) {
	c, err := l.Listener.Accept()
	if err != nil {
		return nil, err
	}
	return countingConn{c}, nil
}

func listen(addr string) net.Listener {
	l, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatal(err)
	}
	return countingListener{l}
}

func otlpHTTP() {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/traces", func(w http.ResponseWriter, r *http.Request) {
		reqs.Add(1)
		b, err := body(r)
		var m coltrace.ExportTraceServiceRequest
		if err == nil {
			err = proto.Unmarshal(b, &m)
		}
		if err != nil {
			decodeErrs.Add(1)
			http.Error(w, err.Error(), 400)
			return
		}
		for _, rs := range m.ResourceSpans {
			for _, ss := range rs.ScopeSpans {
				spans.Add(int64(len(ss.Spans)))
			}
		}
		w.Header().Set("Content-Type", "application/x-protobuf")
		out, _ := proto.Marshal(&coltrace.ExportTraceServiceResponse{})
		w.Write(out)
	})
	mux.HandleFunc("POST /v1/logs", func(w http.ResponseWriter, r *http.Request) {
		reqs.Add(1)
		b, err := body(r)
		var m collogs.ExportLogsServiceRequest
		if err == nil {
			err = proto.Unmarshal(b, &m)
		}
		if err != nil {
			decodeErrs.Add(1)
			http.Error(w, err.Error(), 400)
			return
		}
		for _, rl := range m.ResourceLogs {
			for _, sl := range rl.ScopeLogs {
				logs.Add(int64(len(sl.LogRecords)))
			}
		}
		w.Header().Set("Content-Type", "application/x-protobuf")
		out, _ := proto.Marshal(&collogs.ExportLogsServiceResponse{})
		w.Write(out)
	})
	mux.HandleFunc("GET /reset", func(w http.ResponseWriter, r *http.Request) {
		reqs.Store(0)
		wireBytes.Store(0)
		spans.Store(0)
		logs.Store(0)
		decodeErrs.Store(0)
		cpuBase.Store(int64(cpuNow()))
	})
	mux.HandleFunc("GET /stats", func(w http.ResponseWriter, r *http.Request) {
		json.NewEncoder(w).Encode(map[string]int64{
			"requests": reqs.Load(), "wire_bytes": wireBytes.Load(), "spans": spans.Load(),
			"logs": logs.Load(), "decode_errors": decodeErrs.Load(),
			"cpu_ns": int64(cpuNow()) - cpuBase.Load(),
		})
	})
	log.Fatal(http.Serve(listen("127.0.0.1:4318"), mux))
}

type traceSrv struct {
	coltrace.UnimplementedTraceServiceServer
}

func (traceSrv) Export(_ context.Context, m *coltrace.ExportTraceServiceRequest) (*coltrace.ExportTraceServiceResponse, error) {
	reqs.Add(1)
	for _, rs := range m.ResourceSpans {
		for _, ss := range rs.ScopeSpans {
			spans.Add(int64(len(ss.Spans)))
		}
	}
	return &coltrace.ExportTraceServiceResponse{}, nil
}

type logsSrv struct {
	collogs.UnimplementedLogsServiceServer
}

func (logsSrv) Export(_ context.Context, m *collogs.ExportLogsServiceRequest) (*collogs.ExportLogsServiceResponse, error) {
	reqs.Add(1)
	for _, rl := range m.ResourceLogs {
		for _, sl := range rl.ScopeLogs {
			logs.Add(int64(len(sl.LogRecords)))
		}
	}
	return &collogs.ExportLogsServiceResponse{}, nil
}

func otlpGRPC() {
	s := grpc.NewServer()
	coltrace.RegisterTraceServiceServer(s, traceSrv{})
	collogs.RegisterLogsServiceServer(s, logsSrv{})
	log.Fatal(s.Serve(listen("127.0.0.1:4317")))
}

// countV04 walks a v0.4 payload: array of traces, each an array of span maps.
func countV04(b []byte) (int64, error) {
	nt, b, err := msgp.ReadArrayHeaderBytes(b)
	if err != nil {
		return 0, err
	}
	var n int64
	for i := uint32(0); i < nt; i++ {
		ns, rest, err := msgp.ReadArrayHeaderBytes(b)
		if err != nil {
			return n, err
		}
		b = rest
		for j := uint32(0); j < ns; j++ {
			if b, err = msgp.Skip(b); err != nil {
				return n, err
			}
			n++
		}
	}
	return n, nil
}

func ddAgent() {
	mux := http.NewServeMux()
	mux.HandleFunc("/v0.4/traces", func(w http.ResponseWriter, r *http.Request) {
		reqs.Add(1)
		b, _ := io.ReadAll(r.Body)
		n, err := countV04(b)
		if err != nil {
			decodeErrs.Add(1)
		}
		spans.Add(n)
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"rate_by_service":{}}`))
	})
	mux.HandleFunc("/info", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"version":"7.99.0","endpoints":["/v0.4/traces"],"client_drop_p0s":false}`))
	})
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		io.Copy(io.Discard, r.Body)
		w.WriteHeader(200)
	})
	log.Fatal(http.Serve(listen("127.0.0.1:8126"), mux))
}

func main() {
	cpuBase.Store(int64(cpuNow()))
	go otlpGRPC()
	go ddAgent()
	otlpHTTP()
}
