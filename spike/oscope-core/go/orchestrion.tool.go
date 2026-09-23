// This file is how Orchestrion learns which integrations to weave in. It
// lists oscope's integration package and nothing from dd-trace-go.

//go:build tools

package tools

import (
	_ "github.com/DataDog/orchestrion"
	_ "github.com/chucklehead-dev/oscope/spike/oscope-core/go/instr"
)
