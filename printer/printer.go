package printer

import (
	"fmt"
	"io"
	"os"
)

// 颜色代码（支持 ANSI 和 tview 格式）
const (
	// ANSI 颜色代码（用于终端输出）
	RedANSI     = "\033[0;31m"
	GreenANSI   = "\033[0;32m"
	YellowANSI  = "\033[1;33m"
	BlueANSI    = "\033[0;34m"
	CyanANSI    = "\033[0;36m"
	MagentaANSI = "\033[0;35m"
	ResetANSI   = "\033[0m"
	
	// tview 颜色标签（用于 TUI 输出）
	Red     = "[red]"
	Green   = "[green]"
	Yellow  = "[yellow]"
	Blue    = "[blue]"
	Cyan    = "[cyan]"
	Magenta = "[magenta]"
	Reset   = "[white]"
)

// LogLevel 日志级别
type LogLevel int

const (
	LogLevelDebug LogLevel = iota
	LogLevelInfo
	LogLevelWarning
	LogLevelError
)

// Printer 打印工具
type Printer struct {
	debug  bool
	writer io.Writer
	level  LogLevel // 最小日志级别
}

// New 创建打印工具
func New(debug bool) *Printer {
	level := LogLevelInfo
	if debug {
		level = LogLevelDebug
	}
	
	return &Printer{
		debug:  debug,
		writer: os.Stdout,
		level:  level,
	}
}

// SetWriter 设置输出目标
func (p *Printer) SetWriter(w io.Writer) {
	p.writer = w
}

// GetWriter 获取当前输出目标
func (p *Printer) GetWriter() io.Writer {
	return p.writer
}

// SetLevel 设置最小日志级别
func (p *Printer) SetLevel(level LogLevel) {
	p.level = level
}

// GetLevel 获取当前日志级别
func (p *Printer) GetLevel() LogLevel {
	return p.level
}

// IsDebugEnabled 检查是否启用调试模式
func (p *Printer) IsDebugEnabled() bool {
	return p.debug
}

// Info 打印信息
func (p *Printer) Info(format string, args ...interface{}) {
	if p.level > LogLevelInfo {
		return
	}
	fmt.Fprintf(p.writer, "%s[INFO]%s %s\n", Blue, Reset, fmt.Sprintf(format, args...))
}

// Success 打印成功信息
func (p *Printer) Success(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[SUCCESS]%s %s\n", Green, Reset, fmt.Sprintf(format, args...))
}

// Error 打印错误信息
func (p *Printer) Error(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[ERROR]%s %s\n", Red, Reset, fmt.Sprintf(format, args...))
}

// Warning 打印警告信息
func (p *Printer) Warning(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[WARNING]%s %s\n", Yellow, Reset, fmt.Sprintf(format, args...))
}

// Prompt 打印提示
func (p *Printer) Prompt(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[?]%s %s", Cyan, Reset, fmt.Sprintf(format, args...))
}

// Debug 打印调试信息（仅当 debug 模式启用时显示）
func (p *Printer) Debug(format string, args ...interface{}) {
	if !p.debug {
		return
	}
	fmt.Fprintf(p.writer, "%s[DEBUG]%s %s\n", Magenta, Reset, fmt.Sprintf(format, args...))
}

// Verbose 打印详细调试信息（低优先级，仅 debug 模式显示）
func (p *Printer) Verbose(format string, args ...interface{}) {
	if !p.debug {
		return
	}
	fmt.Fprintf(p.writer, "%s[VERBOSE]%s %s\n", Cyan, Reset, fmt.Sprintf(format, args...))
}

// Print 打印普通信息
func (p *Printer) Print(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, format, args...)
}

// Println 打印普通信息并换行
func (p *Printer) Println(format string, args ...interface{}) {
	fmt.Fprintln(p.writer, fmt.Sprintf(format, args...))
}

// InfoWithCarriage 使用回车符打印信息（用于loading效果）
func (p *Printer) InfoWithCarriage(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "\r%s[INFO]%s %s", Blue, Reset, fmt.Sprintf(format, args...))
}

// SuccessLn 打印成功信息并换行（用于结束loading）
func (p *Printer) SuccessLn(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "\n%s[SUCCESS]%s %s\n", Green, Reset, fmt.Sprintf(format, args...))
}

// ErrorLn 打印错误信息并换行（用于结束loading）
func (p *Printer) ErrorLn(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "\n%s[ERROR]%s %s\n", Red, Reset, fmt.Sprintf(format, args...))
}
