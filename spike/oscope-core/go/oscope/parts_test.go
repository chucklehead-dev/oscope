package oscope

import (
	"context"
	"testing"
)

// Decomposition: what a bare cgo call costs here, and the span path without it.
func BenchmarkCgoCall(b *testing.B) {
	for b.Loop() {
		_ = NowNs()
	}
}

func BenchmarkSpanNoSubmit(b *testing.B) {
	ctx := context.Background()
	b.ReportAllocs()
	for b.Loop() {
		_, s := StartSpan(ctx, "bench.span", KindInternal)
		s.SetString("http.route", "/orders/{id}")
		s.SetInt("http.response.status_code", 200)
		s.SetString("user.tier", "gold")
		s.encode()
		spanPool.Put(s)
	}
}
