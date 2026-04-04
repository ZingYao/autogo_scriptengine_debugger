package project

import "embed"

//go:embed scripts/*
var scriptsFS embed.FS

//go:embed main.go.code
var mainGoCode string
