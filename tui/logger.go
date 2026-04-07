package tui

import (
	"fmt"
	"sync"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
)

type LogLevel int

const (
	LogLevelDebug LogLevel = iota
	LogLevelMSG
	LogLevelInfo
	LogLevelWarn
	LogLevelError
	LogLevelHelp
)

type Logger struct {
	app            *tview.Application
	debugLogView   *tview.TextView
	projectLogView *tview.TextView
	logMutex       sync.Mutex
}

func NewLogger(app *tview.Application, debugLogView, projectLogView *tview.TextView) *Logger {
	return &Logger{
		app:            app,
		debugLogView:   debugLogView,
		projectLogView: projectLogView,
	}
}

func (l *Logger) logToView(view *tview.TextView, level LogLevel, format string, args ...interface{}) {
	l.logMutex.Lock()
	msg := fmt.Sprintf(format, args...)
	l.logMutex.Unlock()

	var prefix string
	var colorTag string

	switch level {
	case LogLevelDebug:
		prefix = "DEBUG"
		colorTag = "[gray]"
	case LogLevelMSG:
		prefix = "MSG"
		colorTag = "[white]"
	case LogLevelInfo:
		prefix = "INFO"
		colorTag = "[green]"
	case LogLevelWarn:
		prefix = "WARN"
		colorTag = "[yellow]"
	case LogLevelError:
		prefix = "ERROR"
		colorTag = "[red]"
	case LogLevelHelp:
		prefix = "HELP"
		colorTag = "[cyan]"
	default:
		prefix = "LOG"
		colorTag = "[white]"
	}

	var formattedMsg string
	if level == LogLevelHelp {
		formattedMsg = fmt.Sprintf("%s[%s[][white] %s", colorTag, prefix, msg)
	} else {
		timestamp := time.Now().Format("15:04:05")
		formattedMsg = fmt.Sprintf("[white][%s[] %s[%s[][white] %s%s[white]\n",
			timestamp, colorTag, prefix, colorTag, msg)
	}

	go func() {
		l.app.QueueUpdateDraw(func() {
			fmt.Fprint(view, formattedMsg)
			view.ScrollToEnd()
		})
	}()
}

func (l *Logger) Debug(format string, args ...interface{}) {
	l.logToView(l.debugLogView, LogLevelDebug, format, args...)
}

func (l *Logger) MSG(format string, args ...interface{}) {
	l.logToView(l.debugLogView, LogLevelMSG, format, args...)
}

func (l *Logger) Info(format string, args ...interface{}) {
	l.logToView(l.debugLogView, LogLevelInfo, format, args...)
}

func (l *Logger) Warn(format string, args ...interface{}) {
	l.logToView(l.debugLogView, LogLevelWarn, format, args...)
}

func (l *Logger) Error(format string, args ...interface{}) {
	l.logToView(l.debugLogView, LogLevelError, format, args...)
}

func (l *Logger) Help(format string, args ...interface{}) {
	l.logToView(l.debugLogView, LogLevelHelp, format, args...)
}

func (l *Logger) ProjectDebug(format string, args ...interface{}) {
	l.logToView(l.projectLogView, LogLevelDebug, format, args...)
}

func (l *Logger) ProjectMSG(format string, args ...interface{}) {
	l.logToView(l.projectLogView, LogLevelMSG, format, args...)
}

func (l *Logger) ProjectInfo(format string, args ...interface{}) {
	l.logToView(l.projectLogView, LogLevelInfo, format, args...)
}

func (l *Logger) ProjectWarn(format string, args ...interface{}) {
	l.logToView(l.projectLogView, LogLevelWarn, format, args...)
}

func (l *Logger) ProjectError(format string, args ...interface{}) {
	l.logToView(l.projectLogView, LogLevelError, format, args...)
}

func (l *Logger) ProjectHelp(format string, args ...interface{}) {
	l.logToView(l.projectLogView, LogLevelHelp, format, args...)
}

func (l *Logger) ClearDebugLog() {
	go func() {
		l.app.QueueUpdateDraw(func() {
			l.debugLogView.SetText("")
			l.logToView(l.debugLogView, LogLevelInfo, "调试器日志已清空")
		})
	}()
}

func (l *Logger) ClearProjectLog() {
	go func() {
		l.app.QueueUpdateDraw(func() {
			l.projectLogView.SetText("")
			l.logToView(l.projectLogView, LogLevelInfo, "项目运行日志已清空")
		})
	}()
}

func GetLogColor(level LogLevel) tcell.Color {
	switch level {
	case LogLevelDebug:
		return tcell.ColorGray
	case LogLevelMSG:
		return tcell.ColorWhite
	case LogLevelInfo:
		return tcell.ColorDarkCyan
	case LogLevelWarn:
		return tcell.ColorOrange
	case LogLevelError:
		return tcell.ColorRed
	case LogLevelHelp:
		return tcell.ColorDarkCyan
	default:
		return tcell.ColorWhite
	}
}
