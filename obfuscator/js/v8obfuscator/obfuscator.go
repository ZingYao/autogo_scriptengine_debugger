// Package obfuscator provides functionality to obfuscate JavaScript code
// using JavaScript Obfuscator through v8go.
// This is a modified version that supports custom options.
package jsobfuscator

import (
	_ "embed"
	"fmt"
	"strings"

	"rogchap.com/v8go"
)

// JsCode contains the embedded JavaScript obfuscator code
//
//go:embed obfuscation.js
var JsCode string

// Options represents JavaScript obfuscation options
type Options struct {
	Compact                     bool
	ControlFlowFlattening       bool
	ControlFlowFlatteningThreshold float64
	NumbersToExpressions        bool
	Simplify                    bool
	StringArrayShuffle          bool
	SplitStrings                bool
	StringArrayThreshold        float64
	IgnoreImports               bool     // 忽略 require 和 import
	ReservedNames               []string // 保留的变量名
	ReservedStrings             []string // 保留的字符串
	Target                      string   // 目标环境: "browser", "node", "browser-no-eval"
	DisableConsoleOutput        bool     // 禁用控制台输出
	SelfDefending               bool     // 自我保护
}

// DefaultOptions returns default options for JavaScript obfuscation
func DefaultOptions() Options {
	return Options{
		Compact:                     true,
		ControlFlowFlattening:       true,
		ControlFlowFlatteningThreshold: 1,
		NumbersToExpressions:        true,
		Simplify:                    true,
		StringArrayShuffle:          true,
		SplitStrings:                true,
		StringArrayThreshold:        1,
		IgnoreImports:               false,
		ReservedNames:               []string{},
		ReservedStrings:             []string{},
		Target:                      "browser",
		DisableConsoleOutput:        false,
		SelfDefending:               false,
	}
}

// ModuleFriendlyOptions returns options that are compatible with CommonJS modules
// and goja engine (ES5.1+ compatible)
func ModuleFriendlyOptions() Options {
	return Options{
		Compact:                     true,
		ControlFlowFlattening:       false, // 禁用控制流扁平化，减少复杂语法
		ControlFlowFlatteningThreshold: 0,
		NumbersToExpressions:        false, // 禁用数字转表达式，避免复杂语法
		Simplify:                    false, // 禁用简化，避免生成不兼容的代码
		StringArrayShuffle:          false, // 禁用字符串数组打乱
		SplitStrings:                false, // 禁用字符串分割
		StringArrayThreshold:        0,     // 禁用字符串数组
		IgnoreImports:               true,  // 忽略 require 和 import
		ReservedNames: []string{
			"^module$",
			"^exports$",
			"^require$",
			"^__dirname$",
			"^__filename$",
		},
		ReservedStrings:      []string{},
		Target:               "browser", // 使用 browser 目标，更兼容 ES5
		DisableConsoleOutput: false,
		SelfDefending:        false,
	}
}

// optionsToJS converts Options to JavaScript object string
func optionsToJS(opts Options) string {
	var sb strings.Builder
	sb.WriteString("const options = {\n")
	sb.WriteString(fmt.Sprintf("    compact: %v,\n", opts.Compact))
	sb.WriteString(fmt.Sprintf("    controlFlowFlattening: %v,\n", opts.ControlFlowFlattening))
	sb.WriteString(fmt.Sprintf("    controlFlowFlatteningThreshold: %v,\n", opts.ControlFlowFlatteningThreshold))
	sb.WriteString(fmt.Sprintf("    numbersToExpressions: %v,\n", opts.NumbersToExpressions))
	sb.WriteString(fmt.Sprintf("    simplify: %v,\n", opts.Simplify))
	sb.WriteString(fmt.Sprintf("    stringArrayShuffle: %v,\n", opts.StringArrayShuffle))
	sb.WriteString(fmt.Sprintf("    splitStrings: %v,\n", opts.SplitStrings))
	sb.WriteString(fmt.Sprintf("    stringArrayThreshold: %v,\n", opts.StringArrayThreshold))
	sb.WriteString(fmt.Sprintf("    ignoreImports: %v,\n", opts.IgnoreImports))
	
	if len(opts.ReservedNames) > 0 {
		sb.WriteString("    reservedNames: [")
		for i, name := range opts.ReservedNames {
			if i > 0 {
				sb.WriteString(", ")
			}
			sb.WriteString(fmt.Sprintf("'%s'", name))
		}
		sb.WriteString("],\n")
	}
	
	if len(opts.ReservedStrings) > 0 {
		sb.WriteString("    reservedStrings: [")
		for i, s := range opts.ReservedStrings {
			if i > 0 {
				sb.WriteString(", ")
			}
			sb.WriteString(fmt.Sprintf("'%s'", s))
		}
		sb.WriteString("],\n")
	}
	
	sb.WriteString(fmt.Sprintf("    target: '%s'\n", opts.Target))
	sb.WriteString("}\n")
	
	return sb.String()
}

// Obfuscator represents a JavaScript obfuscator instance
type Obfuscator struct {
	CachedData *v8go.CompilerCachedData
}

// NewObfuscator creates and initializes a new JavaScript obfuscator
func NewObfuscator() (*Obfuscator, error) {
	isolate := v8go.NewIsolate()
	defer isolate.Dispose()
	context := v8go.NewContext(isolate)
	defer context.Close()
	o := &Obfuscator{}
	if err := o.setupJSCode(isolate, context); err != nil {
		return nil, fmt.Errorf("failed to setup JS code: %w", err)
	}
	return o, nil
}

// setupJSCode loads the JavaScript obfuscator code into the V8 context
func (o *Obfuscator) setupJSCode(
	isolate *v8go.Isolate,
	context *v8go.Context,
) error {
	code := fmt.Sprintf(`
  (function() {
    var self = this;
    var window = this;
    var module = {};
    var exports = {};
    module.exports = exports;
    %s
    globalThis.JavaScriptObfuscator = module.exports;
	})()
  `, JsCode)
	opts := v8go.CompileOptions{}
	if o.CachedData != nil {
		opts.CachedData = o.CachedData
	}
	script, err := isolate.CompileUnboundScript(code, "obfuscation.js", opts)
	if err != nil {
		return fmt.Errorf("failed to compile script: %w", err)
	}
	if _, err := script.Run(context); err != nil {
		return fmt.Errorf("failed to run script: %w", err)
	}
	if o.CachedData == nil {
		o.CachedData = script.CreateCodeCache()
	}
	return nil
}

// Obfuscate transforms the provided JavaScript code using the obfuscator with default options
func (o *Obfuscator) Obfuscate(code string) (string, error) {
	return o.ObfuscateWithOptions(code, DefaultOptions())
}

// ObfuscateWithOptions transforms the provided JavaScript code using custom options
func (o *Obfuscator) ObfuscateWithOptions(code string, opts Options) (string, error) {
	// Escape backticks in the input code to prevent JavaScript template literal issues
	if strings.Contains(code, "`") {
		return "", fmt.Errorf("code cannot contain backtick (`) ")
	}
	isolate := v8go.NewIsolate()
	defer isolate.Dispose()
	context := v8go.NewContext(isolate)
	defer context.Close()
	if err := o.setupJSCode(isolate, context); err != nil {
		return "", fmt.Errorf("failed to setup JS code: %w", err)
	}
	
	optionsJS := optionsToJS(opts)
	
	codeString := fmt.Sprintf(
		"const code = `%s`; %s ;const obfuscatedCode = JavaScriptObfuscator.obfuscate(code, options).getObfuscatedCode();obfuscatedCode;",
		code,
		optionsJS,
	)
	val, err := context.RunScript(codeString, "run.js")
	if err != nil {
		return "", fmt.Errorf("obfuscation error: %w", err)
	}
	obfuscatedCode := val.String()
	if obfuscatedCode == "" {
		return "", fmt.Errorf("obfuscated code is empty")
	}
	return obfuscatedCode, nil
}
