// shop is an ordinary Go HTTP service with no telemetry code: no tracing
// imports, no middleware, no span calls. Built with plain `go build` it
// records nothing. Built with `orchestrion go build` it records server,
// client and function spans plus correlated logs into in-process chDB.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"os"
	"sync"
	"time"
)

type order struct {
	ID    string   `json:"id"`
	Items []string `json:"items"`
	Total float64  `json:"total"`
}

var errNotFound = errors.New("order not found")

//oscope:span span.name:load-order db.system:memory
func loadOrder(ctx context.Context, id string) (*order, error) {
	time.Sleep(time.Duration(200+rand.IntN(300)) * time.Microsecond)
	if len(id) > 0 && id[len(id)-1] == '7' { // every 10th id
		return nil, errNotFound
	}
	return &order{ID: id, Items: []string{"widget", "gadget"}}, nil
}

//oscope:span
func priceOrder(ctx context.Context, o *order) float64 {
	time.Sleep(time.Duration(50+rand.IntN(100)) * time.Microsecond)
	return float64(len(o.Items)) * 19.99
}

func handleOrder(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	id := r.PathValue("id")
	o, err := loadOrder(ctx, id)
	if err != nil {
		slog.ErrorContext(ctx, "order lookup failed", "order.id", id, "error", err)
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	o.Total = priceOrder(ctx, o)
	slog.InfoContext(ctx, "order served", "order.id", id, "items", len(o.Items))
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(o)
}

// A handler with a nil-pointer bug. Go turns the SIGSEGV into a panic and
// net/http recovers it; that only works if the engine's signal handlers are off.
func handleCrash(w http.ResponseWriter, r *http.Request) {
	var o *order
	fmt.Fprint(w, o.ID)
}

//oscope:span span.name:load-generator
func runLoad(ctx context.Context, base string, n, workers int) error {
	var wg sync.WaitGroup
	var mu sync.Mutex
	var failures int
	for w := 0; w < workers; w++ {
		wg.Add(1)
		go func(w int) {
			defer wg.Done()
			for i := w; i < n; i += workers {
				req, _ := http.NewRequestWithContext(ctx, "GET", fmt.Sprintf("%s/orders/%d", base, 1000+i), nil)
				resp, err := http.DefaultClient.Do(req)
				if err != nil {
					mu.Lock()
					failures++
					mu.Unlock()
					continue
				}
				resp.Body.Close()
			}
		}(w)
	}
	wg.Wait()
	if failures > 0 {
		return fmt.Errorf("%d requests failed", failures)
	}
	return nil
}

func main() {
	n := flag.Int("n", 2000, "requests to send")
	workers := flag.Int("workers", 8, "concurrent clients")
	quiet := flag.Bool("quiet", true, "discard the app's own slog output")
	flag.Parse()
	if *quiet {
		slog.SetDefault(slog.New(slog.NewTextHandler(io.Discard, nil)))
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /orders/{id}", handleOrder)
	mux.HandleFunc("GET /crash", handleCrash)
	go func() {
		if err := http.ListenAndServe("127.0.0.1:18080", mux); err != nil {
			slog.Error("server", "error", err)
			os.Exit(1)
		}
	}()
	base := "http://127.0.0.1:18080"
	for i := 0; i < 100; i++ {
		if c, err := http.Get(base + "/orders/1"); err == nil {
			c.Body.Close()
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	ctx := context.Background()

	req, _ := http.NewRequestWithContext(ctx, "GET", base+"/crash", nil)
	if resp, err := http.DefaultClient.Do(req); err != nil {
		fmt.Println("crash endpoint: request failed as expected, process still alive:", err)
	} else {
		resp.Body.Close()
	}

	start := time.Now()
	if err := runLoad(ctx, base, *n, *workers); err != nil {
		fmt.Println("load:", err)
	}
	el := time.Since(start)
	fmt.Printf("served %d requests in %s (%.0f req/s)\n", *n, el.Round(time.Millisecond), float64(*n)/el.Seconds())
}
