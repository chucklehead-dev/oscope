// Package dbsql is woven into the standard library's database/sql: every
// (*DB) and (*Tx) QueryContext / ExecContext gets a CLIENT span. All other
// query methods (Query, QueryRow, Exec, QueryRowContext, ...) funnel into
// those four, so this covers the whole package without double counting.
//
// Because this package ends up imported BY database/sql, its own imports must
// never lead back to database/sql. It depends only on context, reflect,
// strings, sync and the oscope binding.
package dbsql

import (
	"context"
	"reflect"
	"strings"
	"sync"

	"github.com/chucklehead-dev/oscope/spike/oscope-core/go/oscope"
)

var (
	kSystem    = oscope.NewKey("db.system.name")
	kOperation = oscope.NewKey("db.operation.name")
	kQuery     = oscope.NewKey("db.query.text")
	kTx        = oscope.NewKey("db.in_transaction")
	systems    sync.Map // reflect.Type -> string
)

// system derives db.system.name from the driver's package path, once per type
// (e.g. modernc.org/sqlite -> "sqlite", github.com/lib/pq -> "pq").
func system(driver any) string {
	if driver == nil {
		return ""
	}
	t := reflect.TypeOf(driver)
	if v, ok := systems.Load(t); ok {
		return v.(string)
	}
	for t.Kind() == reflect.Pointer {
		t = t.Elem()
	}
	p := t.PkgPath()
	name := p[strings.LastIndexByte(p, '/')+1:]
	systems.Store(reflect.TypeOf(driver), name)
	return name
}

func operation(q string) string {
	q = strings.TrimSpace(q)
	if i := strings.IndexAny(q, " \t\n("); i > 0 {
		q = q[:i]
	}
	return strings.ToUpper(q)
}

// Start is called from the woven database/sql methods. driver is the
// owning *DB's driver; for a *Tx the woven code reads the
// unexported tx.db field, which it can because it is compiled into package sql.
func Start(ctx context.Context, method string, driver any, inTx bool, query string) (context.Context, func(error)) {
	op := operation(query)
	ctx, s := oscope.StartSpan(ctx, op, oscope.KindClient)
	if sys := system(driver); sys != "" {
		s.SetStringK(kSystem, sys)
	}
	s.SetStringK(kOperation, op)
	if len(query) > 512 {
		query = query[:512]
	}
	s.SetStringK(kQuery, query)
	s.SetBoolK(kTx, inTx)
	return ctx, func(err error) {
		s.SetError(err)
		s.End()
	}
}
