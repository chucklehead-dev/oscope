// Package lib is woven into third-party library code: a span per exported
// method on the configured receiver types (see orchestrion.yml).
package lib

import (
	"context"

	"github.com/chucklehead-dev/oscope/spike/oscope-core/go/oscope"
)

var kLibrary = oscope.NewKey("code.namespace")

// Start opens an INTERNAL span named "<namespace>.<Method>".
func Start(ctx context.Context, namespace, method string) (context.Context, func(error)) {
	ctx, s := oscope.StartSpan(ctx, namespace+"."+method, oscope.KindInternal)
	s.SetStringK(kLibrary, namespace)
	return ctx, func(err error) {
		s.SetError(err)
		s.End()
	}
}
