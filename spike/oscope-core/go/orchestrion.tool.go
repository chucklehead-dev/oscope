// This file is how Orchestrion learns which integrations to weave in. It
// lists oscope's integration packages and nothing from dd-trace-go.

//go:build tools

package tools

import (
	_ "github.com/DataDog/orchestrion"
	_ "github.com/chucklehead-dev/oscope/spike/oscope-core/go/instr"       // app: main, //oscope:span, net/http, slog
	_ "github.com/chucklehead-dev/oscope/spike/oscope-core/go/instr/dbsql" // standard library: database/sql
	_ "github.com/chucklehead-dev/oscope/spike/oscope-core/go/instr/lib"   // third-party module: example.com/inventory
)
