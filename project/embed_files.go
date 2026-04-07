package project

import "embed"

//go:embed scripts/*
var scriptsFS embed.FS

//go:embed debugger.go.code
var DebuggerGoCode string

//go:embed build.go.code
var BuildGoCode string
