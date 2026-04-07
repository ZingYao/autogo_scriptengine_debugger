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
	Gray    = "[gray]"
	Reset   = "[white]"
)

// LogLevel 日志级别
type LogLevel int

const (
	LogLevelDebug LogLevel = iota
	LogLevelInfo
	LogLevelWarning
	LogLevelError
	LogLevelHelp
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
	fmt.Fprintf(p.writer, "%s[INFO]%s %s%s%s\n", Green, Reset, Green, fmt.Sprintf(format, args...), Reset)
}

// Success 打印成功信息
func (p *Printer) Success(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[SUCCESS]%s %s%s%s\n", Green, Reset, Green, fmt.Sprintf(format, args...), Reset)
}

// Error 打印错误信息
func (p *Printer) Error(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[ERROR]%s %s%s%s\n", Red, Reset, Red, fmt.Sprintf(format, args...), Reset)
}

// Warning 打印警告信息
func (p *Printer) Warning(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[WARN]%s %s%s%s\n", Yellow, Reset, Yellow, fmt.Sprintf(format, args...), Reset)
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
	fmt.Fprintf(p.writer, "%s[DEBUG]%s %s%s%s\n", Gray, Reset, Gray, fmt.Sprintf(format, args...), Reset)
}

// Verbose 打印详细调试信息（低优先级，仅 debug 模式显示）
func (p *Printer) Verbose(format string, args ...interface{}) {
	if !p.debug {
		return
	}
	fmt.Fprintf(p.writer, "%s[DEBUG]%s %s%s%s\n", Gray, Reset, Gray, fmt.Sprintf(format, args...), Reset)
}

// Print 打印普通信息
func (p *Printer) Print(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, format, args...)
}

// Println 打印普通信息并换行（空行不添加前缀）
func (p *Printer) Println(format string, args ...interface{}) {
	msg := fmt.Sprintf(format, args...)
	if msg == "" {
		fmt.Fprintln(p.writer, "")
	} else {
		fmt.Fprintf(p.writer, "%s[MSG]%s %s%s%s\n", Reset, Reset, Reset, msg, Reset)
	}
}

// InfoWithCarriage 使用回车符打印信息（用于loading效果）
func (p *Printer) InfoWithCarriage(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "\r%s[INFO]%s %s%s%s", Green, Reset, Green, fmt.Sprintf(format, args...), Reset)
}

// SuccessLn 打印成功信息并换行（用于结束loading）
func (p *Printer) SuccessLn(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "\n%s[SUCCESS]%s %s%s%s\n", Green, Reset, Green, fmt.Sprintf(format, args...), Reset)
}

// ErrorLn 打印错误信息并换行（用于结束loading）
func (p *Printer) ErrorLn(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "\n%s[ERROR]%s %s%s%s\n", Red, Reset, Red, fmt.Sprintf(format, args...), Reset)
}

// Help 打印帮助信息（不受日志级别限制，总是显示）
func (p *Printer) Help(format string, args ...interface{}) {
	fmt.Fprintf(p.writer, "%s[HELP]%s %s%s%s\n", Cyan, Reset, Cyan, fmt.Sprintf(format, args...), Reset)
}
