// Package oscope is a cgo binding to liboscope_core: an in-process OTel
// recorder that stores spans and logs in embedded chDB.
//
// Each span accumulates its attributes in a pooled Go byte buffer and crosses
// into C exactly once, at End, through osc_submit. The buffer holds no Go
// pointers, so it satisfies cgo's pointer-passing rules without pinning.
package oscope

/*
#cgo CFLAGS: -I${SRCDIR}/../../include
#cgo LDFLAGS: -Wl,--no-as-needed -lchdb -Wl,--as-needed -loscope_core
#include <stdlib.h>
#include "oscope.h"
*/
import "C"

import (
	"context"
	"encoding/binary"
	"errors"
	"math"
	"math/rand/v2"
	"sync"
	"time"
	"unsafe"
)

type Kind uint8

const (
	KindInternal Kind = 1
	KindServer   Kind = 2
	KindClient   Kind = 3
	KindProducer Kind = 4
	KindConsumer Kind = 5
)

type Status uint8

const (
	StatusUnset Status = 0
	StatusOK    Status = 1
	StatusError Status = 2
)

// Severity numbers follow the OTel log data model.
const (
	SevDebug uint8 = 5
	SevInfo  uint8 = 9
	SevWarn  uint8 = 13
	SevError uint8 = 17
)

type Config struct {
	DBPath    string // "" = in-memory
	WALPath   string // "" = no WAL
	WALFsync  bool
	Service   string
	BatchRows uint32
	FlushMs   uint32
	RingBytes uint32
	// KeepEngineSignalHandlers leaves chDB's signal handlers installed. Only
	// for demonstrating why they are off by default in Go hosts.
	KeepEngineSignalHandlers bool
	// DirectSubmit makes every End/Log cross into C immediately instead of
	// batching (lower latency to the ring, ~95 ns more per record).
	DirectSubmit bool
}

// Start boots the embedded engine. It first turns off chDB's own signal
// handlers: the Go runtime relies on SIGSEGV/SIGBUS for nil-pointer panics
// and would crash instead of panicking if the engine replaced them.
func Start(c Config) error {
	if !c.KeepEngineSignalHandlers {
		C.osc_set_engine_signal_handlers(0)
	}
	cs := func(s string) *C.char {
		if s == "" {
			return nil
		}
		return C.CString(s)
	}
	cfg := C.osc_config{
		db_path:           cs(c.DBPath),
		wal_path:          cs(c.WALPath),
		service_name:      cs(c.Service),
		batch_rows:        C.uint32_t(c.BatchRows),
		flush_interval_ms: C.uint32_t(c.FlushMs),
		ring_bytes:        C.uint32_t(c.RingBytes),
	}
	if c.WALFsync {
		cfg.wal_fsync = 1
	}
	defer func() {
		for _, p := range []*C.char{cfg.db_path, cfg.wal_path, cfg.service_name} {
			if p != nil {
				C.free(unsafe.Pointer(p))
			}
		}
	}()
	if C.osc_start(&cfg) != 0 {
		return errors.New("oscope: osc_start failed")
	}
	directMode = c.DirectSubmit
	if !directMode {
		startTicker()
	}
	return nil
}

func Flush(timeout time.Duration) error {
	submitAll()
	if C.osc_flush(C.uint32_t(timeout.Milliseconds())) != 0 {
		return errors.New("oscope: flush timed out")
	}
	return nil
}

func Stop() error {
	if tickerStop != nil {
		close(tickerStop)
		<-tickerDone
		tickerStop = nil
	}
	submitAll()
	if C.osc_stop() != 0 {
		return errors.New("oscope: not started")
	}
	return nil
}

func Dropped() uint64 { return uint64(C.osc_dropped()) }

// NowNs is the engine's clock (unix ns); mostly useful to measure cgo cost.
func NowNs() uint64 { return uint64(C.osc_now_ns()) }

// Query runs a read-only query on the embedded engine's reader connection.
func Query(sql, format string) (string, error) {
	f := C.CString(format)
	defer C.free(unsafe.Pointer(f))
	b := []byte(sql)
	var out *C.uint8_t
	var n C.size_t
	if C.osc_query(C.osc_str{ptr: (*C.uint8_t)(unsafe.Pointer(&b[0])), len: C.size_t(len(b))}, f, &out, &n) != 0 {
		return "", errors.New("oscope: query failed")
	}
	defer C.osc_free(out, n)
	return C.GoStringN((*C.char)(unsafe.Pointer(out)), C.int(n)), nil
}

// ---------------------------------------------------------------- interning

var interned sync.Map // string -> uint32

// Intern returns the id for a span name or attribute key. The first call for
// a string crosses into C; later calls are a map lookup.
func Intern(s string) uint32 {
	if v, ok := interned.Load(s); ok {
		return v.(uint32)
	}
	b := []byte(s)
	var p *C.uint8_t
	if len(b) > 0 {
		p = (*C.uint8_t)(unsafe.Pointer(&b[0]))
	}
	id := uint32(C.osc_intern(C.osc_str{ptr: p, len: C.size_t(len(b))}))
	interned.Store(s, id)
	return id
}

// ---------------------------------------------------------------- spans

// SpanContext is what travels in context.Context and across processes.
type SpanContext struct {
	TraceID [16]byte
	SpanID  uint64
}

func (sc SpanContext) Valid() bool { return sc.SpanID != 0 }

type ctxKey struct{}

// spanCtx carries a SpanContext with one allocation per span;
// context.WithValue would also box the value, making two.
type spanCtx struct {
	context.Context
	sc SpanContext
}

func (c *spanCtx) Value(key any) any {
	if key == (ctxKey{}) {
		return c
	}
	return c.Context.Value(key)
}

func ContextWithSpanContext(ctx context.Context, sc SpanContext) context.Context {
	return &spanCtx{Context: ctx, sc: sc}
}

func SpanContextFrom(ctx context.Context) SpanContext {
	if c, ok := ctx.(*spanCtx); ok {
		return c.sc
	}
	if c, ok := ctx.Value(ctxKey{}).(*spanCtx); ok {
		return c.sc
	}
	return SpanContext{}
}

// Key is a pre-interned span name or attribute key. Declare keys once at
// package level; using one skips the per-call intern lookup.
type Key struct{ id uint32 }

func NewKey(s string) Key { return Key{Intern(s)} }

const (
	tagStr  = 1
	tagI64  = 2
	tagF64  = 3
	tagBool = 4
	kFull   = 4
	kLog    = 5
	hdrLen  = 4 + 1 + 16 + 8 + 8 + 4 + 1 + 1 + 8 + 8 + 4 // frame len + fixed K_FULL fields
)

// Span is not safe for concurrent use and must not be used after End.
type Span struct {
	sc      SpanContext
	parent  uint64
	name    uint32
	kind    Kind
	status  Status
	startNs int64     // wall clock, unix ns
	t0      time.Time // carries the monotonic reading End measures from
	nattrs  uint32
	buf     []byte // header space + encoded attributes
}

var spanPool = sync.Pool{New: func() any { return &Span{buf: make([]byte, hdrLen, 512)} }}

func newTraceID() (t [16]byte) {
	binary.BigEndian.PutUint64(t[:8], rand.Uint64())
	binary.BigEndian.PutUint64(t[8:], rand.Uint64()|1)
	return
}

// StartSpan starts a child of the span in ctx (or a new trace) and returns a
// context carrying the new span.
func StartSpan(ctx context.Context, name string, kind Kind) (context.Context, *Span) {
	return StartSpanK(ctx, Key{Intern(name)}, kind)
}

// StartSpanK is StartSpan with a pre-interned name.
func StartSpanK(ctx context.Context, name Key, kind Kind) (context.Context, *Span) {
	parent := SpanContextFrom(ctx)
	s := spanPool.Get().(*Span)
	s.parent = parent.SpanID
	if parent.Valid() {
		s.sc.TraceID = parent.TraceID
	} else {
		s.sc.TraceID = newTraceID()
	}
	s.sc.SpanID = rand.Uint64() | 1
	s.name, s.kind, s.status, s.nattrs = name.id, kind, StatusUnset, 0
	s.buf = s.buf[:hdrLen]
	s.t0 = time.Now()
	s.startNs = s.t0.UnixNano()
	return ContextWithSpanContext(ctx, s.sc), s
}

func (s *Span) Context() SpanContext { return s.sc }

// SetName renames the span before End (e.g. once an HTTP route is known).
func (s *Span) SetName(name string) { s.name = Intern(name) }
func (s *Span) SetNameK(name Key)   { s.name = name.id }

func (s *Span) key(tag byte, id uint32) {
	s.buf = append(s.buf, tag)
	s.buf = binary.LittleEndian.AppendUint32(s.buf, id)
	s.nattrs++
}

func (s *Span) SetStringK(k Key, v string) {
	s.key(tagStr, k.id)
	s.buf = binary.LittleEndian.AppendUint32(s.buf, uint32(len(v)))
	s.buf = append(s.buf, v...)
}

func (s *Span) SetIntK(k Key, v int64) {
	s.key(tagI64, k.id)
	s.buf = binary.LittleEndian.AppendUint64(s.buf, uint64(v))
}

func (s *Span) SetFloatK(k Key, v float64) {
	s.key(tagF64, k.id)
	s.buf = binary.LittleEndian.AppendUint64(s.buf, math.Float64bits(v))
}

func (s *Span) SetBoolK(k Key, v bool) {
	s.key(tagBool, k.id)
	b := byte(0)
	if v {
		b = 1
	}
	s.buf = append(s.buf, b)
}

func (s *Span) SetString(k, v string)        { s.SetStringK(Key{Intern(k)}, v) }
func (s *Span) SetInt(k string, v int64)     { s.SetIntK(Key{Intern(k)}, v) }
func (s *Span) SetFloat(k string, v float64) { s.SetFloatK(Key{Intern(k)}, v) }
func (s *Span) SetBool(k string, v bool)     { s.SetBoolK(Key{Intern(k)}, v) }

var keyExceptionMessage = Key{} // interned lazily: Intern needs the library loaded

// SetError marks the span failed and records the error text.
func (s *Span) SetError(err error) {
	if err == nil {
		return
	}
	s.status = StatusError
	if keyExceptionMessage.id == 0 {
		keyExceptionMessage = NewKey("exception.message")
	}
	s.SetStringK(keyExceptionMessage, err.Error())
}

func (s *Span) SetStatus(st Status) { s.status = st }

// End encodes the span header in front of its attributes and hands the whole
// record to the recorder in one cgo call.
func (s *Span) End() {
	submit(s.encode())
	if cap(s.buf) <= 64<<10 { // don't pool pathological spans
		spanPool.Put(s)
	}
}

func (s *Span) encode() []byte {
	end := s.startNs + int64(time.Since(s.t0)) // one monotonic read
	b := s.buf
	binary.LittleEndian.PutUint32(b[0:], uint32(len(b)-4))
	b[4] = kFull
	copy(b[5:21], s.sc.TraceID[:])
	binary.LittleEndian.PutUint64(b[21:], s.sc.SpanID)
	binary.LittleEndian.PutUint64(b[29:], s.parent)
	binary.LittleEndian.PutUint32(b[37:], s.name)
	b[41] = byte(s.kind)
	b[42] = byte(s.status)
	binary.LittleEndian.PutUint64(b[43:], uint64(s.startNs))
	binary.LittleEndian.PutUint64(b[51:], uint64(end))
	binary.LittleEndian.PutUint32(b[59:], s.nattrs)
	return b
}

// ---------------------------------------------------------------- batching

// Finished records are appended to one of a few mutex-striped buffers and
// handed to the recorder in one osc_submit call per ~32 KiB, instead of one
// cgo call (~95 ns here) per record. A background goroutine submits partial
// buffers every 20 ms; Flush and Stop submit everything first.
const (
	nStripes         = 16
	stripeFlushBytes = 32 << 10
)

type stripe struct {
	mu  sync.Mutex
	buf []byte
	_   [32]byte // keep stripes on separate cache lines
}

var (
	stripes    [nStripes]stripe
	directMode bool // Config.DirectSubmit
	tickerStop chan struct{}
	tickerDone chan struct{}
)

func submit(rec []byte) {
	if directMode {
		C.osc_submit((*C.uint8_t)(unsafe.Pointer(&rec[0])), C.size_t(len(rec)))
		return
	}
	st := &stripes[rand.Uint32()%nStripes]
	st.mu.Lock()
	st.buf = append(st.buf, rec...)
	if len(st.buf) >= stripeFlushBytes {
		submitLocked(st)
	}
	st.mu.Unlock()
}

func submitLocked(st *stripe) {
	if len(st.buf) == 0 {
		return
	}
	C.osc_submit((*C.uint8_t)(unsafe.Pointer(&st.buf[0])), C.size_t(len(st.buf)))
	st.buf = st.buf[:0]
}

func submitAll() {
	for i := range stripes {
		st := &stripes[i]
		st.mu.Lock()
		submitLocked(st)
		st.mu.Unlock()
	}
}

func startTicker() {
	tickerStop, tickerDone = make(chan struct{}), make(chan struct{})
	go func() {
		defer close(tickerDone)
		t := time.NewTicker(20 * time.Millisecond)
		defer t.Stop()
		for {
			select {
			case <-t.C:
				submitAll()
			case <-tickerStop:
				return
			}
		}
	}()
}

// ---------------------------------------------------------------- logs

type Attr struct {
	Key   string
	Str   string
	Int   int64
	IsInt bool
}

var logBufs = sync.Pool{New: func() any { b := make([]byte, 0, 512); return &b }}

// Log records a log correlated with the span in ctx (if any).
func Log(ctx context.Context, severity uint8, body string, attrs ...Attr) {
	sc := SpanContextFrom(ctx)
	bp := logBufs.Get().(*[]byte)
	b := append((*bp)[:0], 0, 0, 0, 0, kLog)
	b = binary.LittleEndian.AppendUint64(b, uint64(time.Now().UnixNano()))
	b = append(b, sc.TraceID[:]...)
	b = binary.LittleEndian.AppendUint64(b, sc.SpanID)
	b = append(b, severity)
	b = binary.LittleEndian.AppendUint32(b, uint32(len(body)))
	b = append(b, body...)
	b = binary.LittleEndian.AppendUint32(b, uint32(len(attrs)))
	for _, a := range attrs {
		if a.IsInt {
			b = append(b, tagI64)
			b = binary.LittleEndian.AppendUint32(b, Intern(a.Key))
			b = binary.LittleEndian.AppendUint64(b, uint64(a.Int))
		} else {
			b = append(b, tagStr)
			b = binary.LittleEndian.AppendUint32(b, Intern(a.Key))
			b = binary.LittleEndian.AppendUint32(b, uint32(len(a.Str)))
			b = append(b, a.Str...)
		}
	}
	binary.LittleEndian.PutUint32(b, uint32(len(b)-4))
	submit(b)
	*bp = b
	logBufs.Put(bp)
}
