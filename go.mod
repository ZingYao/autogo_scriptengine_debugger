module github.com/ZingYao/autogo_scriptengine_debugger

go 1.25.0

require (
	github.com/gdamore/tcell/v2 v2.13.8
	github.com/rivo/tview v0.42.0
	github.com/yuin/gopher-lua v1.1.2
	gopkg.in/yaml.v3 v3.0.1
	rogchap.com/v8go v0.9.0
)

require (
	github.com/gdamore/encoding v1.0.1 // indirect
	github.com/lucasb-eyer/go-colorful v1.3.0 // indirect
	github.com/rivo/uniseg v0.4.7 // indirect
	golang.org/x/sys v0.38.0 // indirect
	golang.org/x/term v0.37.0 // indirect
	golang.org/x/text v0.31.0 // indirect
)

// 使用最新版本的 autogo_scriptengine（包含字节码支持）
replace github.com/ZingYao/autogo_scriptengine => /tmp/autogo_scriptengine
