package tui

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"

	"github.com/ZingYao/autogo_scriptengine_debugger/agmanager"
	"github.com/ZingYao/autogo_scriptengine_debugger/config"
	"github.com/ZingYao/autogo_scriptengine_debugger/device"
	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
	"github.com/ZingYao/autogo_scriptengine_debugger/project"
	"github.com/ZingYao/autogo_scriptengine_debugger/script"
)

// writerToTUI 将输出重定向到 TUI 日志区域的 writer
type writerToTUI struct {
	tui *TUI
}

// Write 实现 io.Writer 接口
func (w *writerToTUI) Write(p []byte) (n int, err error) {
	// 直接写入到 logView
	w.tui.app.QueueUpdateDraw(func() {
		fmt.Fprint(w.tui.logView, string(p))
		w.tui.logView.ScrollToEnd()
	})

	return len(p), nil
}

// writerToProjectLog 将输出重定向到项目日志区域的 writer
type writerToProjectLog struct {
	tui *TUI
}

// Write 实现 io.Writer 接口
func (w *writerToProjectLog) Write(p []byte) (n int, err error) {
	w.tui.projectLog("%s", string(p))
	return len(p), nil
}

// 确保实现了 io.Writer 接口
var _ io.Writer = (*writerToTUI)(nil)
var _ io.Writer = (*writerToProjectLog)(nil)

// TUI 终端用户界面
type TUI struct {
	app            *tview.Application
	flex           *tview.Flex
	menu           *tview.List     // 主菜单
	logView        *tview.TextView // 调试器日志
	projectLogView *tview.TextView // 项目运行日志
	logFlex        *tview.Flex     // 日志容器
	statusBar      *tview.TextView

	config     *config.Config
	configMgr  *config.Manager
	deviceMgr  *device.Manager
	projectMgr *project.Manager
	printer    *printer.Printer
	agManager  *agmanager.AGManager

	logMutex sync.Mutex
	ready    bool
	initOnce sync.Once

	// 当前模式："" 正常模式, "ag" AG管理模式, "device" 设备管理模式, "runmgmt" 运行管理模式, "log" 日志浏览模式
	currentMode  string
	previousMode string // 记录进入日志前的模式

	// 项目日志是否显示
	showProjectLog bool

	// 鼠标模式是否启用
	mouseEnabled bool

	// 脚本运行状态
	scriptStatus   string // "idle", "running", "paused"
	runningScript  string // 当前运行的脚本路径
	selectedScript string // 选择的脚本文件名
}

// NewTUI 创建 TUI 界面
func NewTUI(cfg *config.Config, configMgr *config.Manager, deviceMgr *device.Manager, projectMgr *project.Manager, p *printer.Printer) *TUI {
	tui := &TUI{
		app:            tview.NewApplication(),
		config:         cfg,
		configMgr:      configMgr,
		deviceMgr:      deviceMgr,
		projectMgr:     projectMgr,
		printer:        p,
		agManager:      agmanager.NewAGManager(p, cfg.AGPath, cfg.HTTPProxy),
		ready:          false,
		selectedScript: cfg.SelectedScript, // 从配置加载选中的脚本
		mouseEnabled:   true,               // 默认启用鼠标
	}

	tui.setupUI()

	// 设置 printer 的输出目标为 TUI 日志区域
	p.SetWriter(&writerToTUI{tui: tui})

	// 设置项目日志输出目标
	projectMgr.SetProjectLogWriter(&writerToProjectLog{tui: tui})

	// 设置 AG 命令日志输出目标
	projectMgr.SetAGLogWriter(&writerToTUI{tui: tui})

	return tui
}

// setupUI 设置界面
func (t *TUI) setupUI() {
	// 创建主菜单
	t.menu = tview.NewList().
		AddItem("运行管理", "", '1', nil).
		AddItem("设备管理", "", '2', nil).
		AddItem("AG 更新", "", '3', nil).
		AddItem("配置管理", "", '4', nil).
		AddItem("项目初始化", "", 'i', nil).
		AddItem("帮助", "", 'h', nil).
		AddItem("退出", "", 'q', nil)
	t.menu.ShowSecondaryText(false).
		SetSelectedTextColor(tcell.ColorYellow).
		SetSelectedBackgroundColor(tcell.ColorDarkCyan).
		SetMainTextColor(tcell.ColorWhite).
		SetBorder(true).
		SetTitle(" 主菜单 ").
		SetTitleAlign(tview.AlignLeft).
		SetBorderColor(tcell.ColorYellow) // 初始焦点边框颜色

	// 焦点变化时改变边框样式
	t.menu.SetFocusFunc(func() {
		t.menu.SetBorderColor(tcell.ColorYellow)
		t.menu.SetBorderAttributes(tcell.AttrBold) // 聚焦时黄色双线
	})
	t.menu.SetBlurFunc(func() {
		t.menu.SetBorderColor(tcell.ColorWhite)
		t.menu.SetBorderAttributes(0) // 失焦时白色单线
	})

	// 为菜单容器添加边框和标题
	menuFrame := tview.NewFrame(t.menu).
		SetBorders(0, 0, 0, 0, 0, 0).
		AddText(" AutoGo ScriptEngine 调试器 ", true, tview.AlignLeft, tcell.ColorYellow)

	// 创建调试器日志输出区域
	t.logView = tview.NewTextView().
		SetDynamicColors(true).
		SetRegions(true).
		SetWordWrap(true).
		SetChangedFunc(func() {
			t.app.Draw()
		})

	t.logView.SetBorder(true).
		SetTitle(" 调试器日志 [[yellow]D[white]] ").
		SetTitleAlign(tview.AlignLeft).
		SetBorderColor(tcell.ColorWhite)

	// 焦点变化时改变边框颜色和样式
	t.logView.SetFocusFunc(func() {
		t.logView.SetBorderColor(tcell.ColorYellow)
		t.logView.SetBorderAttributes(tcell.AttrBold)
	})
	t.logView.SetBlurFunc(func() {
		t.logView.SetBorderColor(tcell.ColorWhite)
		t.logView.SetBorderAttributes(0)
	})

	// 为调试器日志添加输入捕获（滚动支持）
	t.logView.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		switch event.Key() {
		case tcell.KeyEscape:
			// ESC 返回之前的菜单
			t.restorePreviousMode()
			return nil
		case tcell.KeyTab:
			// Tab 切换到项目日志
			t.app.SetFocus(t.projectLogView)
			t.updateStatusBar("projectLog")
			return nil
		case tcell.KeyUp:
			// 向上滚动
			row, col := t.logView.GetScrollOffset()
			t.logView.ScrollTo(row-1, col)
			return nil
		case tcell.KeyDown:
			// 向下滚动
			row, col := t.logView.GetScrollOffset()
			t.logView.ScrollTo(row+1, col)
			return nil
		case tcell.KeyPgUp:
			// 向上翻页
			row, col := t.logView.GetScrollOffset()
			_, _, _, height := t.logView.GetInnerRect()
			t.logView.ScrollTo(row-height, col)
			return nil
		case tcell.KeyPgDn:
			// 向下翻页
			row, col := t.logView.GetScrollOffset()
			_, _, _, height := t.logView.GetInnerRect()
			t.logView.ScrollTo(row+height, col)
			return nil
		case tcell.KeyHome:
			// 滚动到开头
			t.logView.ScrollToBeginning()
			return nil
		case tcell.KeyEnd:
			// 滚动到结尾
			t.logView.ScrollToEnd()
			return nil
		}

		// 'q' 键返回菜单
		if event.Rune() == 'q' || event.Rune() == 'Q' {
			t.restorePreviousMode()
			return nil
		}

		// 'c' 键清空调试器日志
		if event.Rune() == 'c' || event.Rune() == 'C' {
			t.logView.SetText("")
			fmt.Fprintf(t.logView, "[green]调试器日志已清空[white]\n")
			return nil
		}

		return event
	})

	// 创建项目运行日志输出区域
	t.projectLogView = tview.NewTextView().
		SetDynamicColors(true).
		SetRegions(true).
		SetWordWrap(true).
		SetChangedFunc(func() {
			t.app.Draw()
		})

	t.projectLogView.SetBorder(true).
		SetTitle(" 项目运行日志 [[yellow]L[white]] ").
		SetTitleAlign(tview.AlignLeft).
		SetBorderColor(tcell.ColorWhite)

	// 焦点变化时改变边框颜色
	t.projectLogView.SetFocusFunc(func() {
		t.projectLogView.SetBorderColor(tcell.ColorYellow)
		t.projectLogView.SetBorderAttributes(tcell.AttrBold)
	})
	t.projectLogView.SetBlurFunc(func() {
		t.projectLogView.SetBorderColor(tcell.ColorWhite)
		t.projectLogView.SetBorderAttributes(0)
	})

	// 为项目日志添加输入捕获（滚动支持）
	t.projectLogView.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		switch event.Key() {
		case tcell.KeyEscape:
			// ESC 返回之前的菜单
			t.restorePreviousMode()
			return nil
		case tcell.KeyTab:
			// Tab 切换到调试器日志
			t.app.SetFocus(t.logView)
			t.updateStatusBar("debugLog")
			return nil
		case tcell.KeyUp:
			// 向上滚动
			row, col := t.projectLogView.GetScrollOffset()
			t.projectLogView.ScrollTo(row-1, col)
			return nil
		case tcell.KeyDown:
			// 向下滚动
			row, col := t.projectLogView.GetScrollOffset()
			t.projectLogView.ScrollTo(row+1, col)
			return nil
		case tcell.KeyPgUp:
			// 向上翻页
			row, col := t.projectLogView.GetScrollOffset()
			_, _, _, height := t.projectLogView.GetInnerRect()
			t.projectLogView.ScrollTo(row-height, col)
			return nil
		case tcell.KeyPgDn:
			// 向下翻页
			row, col := t.projectLogView.GetScrollOffset()
			_, _, _, height := t.projectLogView.GetInnerRect()
			t.projectLogView.ScrollTo(row+height, col)
			return nil
		case tcell.KeyHome:
			// 滚动到开头
			t.projectLogView.ScrollToBeginning()
			return nil
		case tcell.KeyEnd:
			// 滚动到结尾
			t.projectLogView.ScrollToEnd()
			return nil
		}

		// 'q' 键返回菜单
		if event.Rune() == 'q' || event.Rune() == 'Q' {
			t.restorePreviousMode()
			return nil
		}

		// 'c' 键清空项目日志
		if event.Rune() == 'c' || event.Rune() == 'C' {
			t.projectLogView.SetText("")
			fmt.Fprintf(t.projectLogView, "[green]项目运行日志已清空[white]\n")
			return nil
		}

		return event
	})

	// 创建日志容器（左右分栏）
	t.showProjectLog = true // 默认显示项目日志
	t.logFlex = tview.NewFlex().
		SetDirection(tview.FlexColumn).
		AddItem(t.logView, 0, 1, false).       // 调试器日志
		AddItem(t.projectLogView, 0, 1, false) // 项目运行日志

	// 创建状态栏
	t.statusBar = tview.NewTextView().
		SetDynamicColors(true).
		SetTextAlign(tview.AlignCenter)

	t.updateStatusBar("menu") // 设置初始菜单提示

	// 创建 Flex 布局
	t.flex = tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(menuFrame, 12, 0, true).  // 菜单固定 12 行
		AddItem(t.logFlex, 0, 1, false).  // 日志占剩余空间
		AddItem(t.statusBar, 1, 0, false) // 状态栏固定高度

	// 设置菜单选择处理
	t.menu.SetSelectedFunc(func(index int, name string, secondary string, shortcut rune) {
		switch shortcut {
		case '1':
			t.runManagementMenu()
		case '2':
			t.deviceMenu()
		case '3':
			t.showAGManager()
		case '4':
			t.showConfigManager()
		case 'i':
			t.initProject()
		case 'h':
			t.showHelp()
		case 'q':
			t.exitApp()
		}
	})

	// 设置初始焦点
	t.app.SetFocus(t.menu)

	// 设置全局快捷键
	t.app.SetInputCapture(t.handleGlobalInput)

	// 设置鼠标监听
	t.app.SetMouseCapture(t.handleMouse)
}

// Run 运行 TUI
func (t *TUI) Run() error {
	// 确保终端使用 UTF-8 编码
	if os.Getenv("LANG") == "" {
		os.Setenv("LANG", "en_US.UTF-8")
	}

	// 启用鼠标支持
	t.app.EnableMouse(true)

	// 设置应用
	t.app.SetRoot(t.flex, true)

	// 启动初始化（异步）
	go t.initialize()

	return t.app.Run()
}

// restorePreviousMode 从日志浏览模式恢复到之前的菜单
func (t *TUI) restorePreviousMode() {
	switch t.previousMode {
	case "runmgmt":
		t.currentMode = "runmgmt"
		t.runManagementMenu()
	case "device":
		t.currentMode = "device"
		t.deviceMenu()
	case "ag":
		t.currentMode = "ag"
		t.showAGManager()
	case "config":
		t.currentMode = "config"
		t.showConfigManager()
	default:
		// 默认返回主菜单
		t.currentMode = ""
		t.app.SetRoot(t.flex, true)
		t.app.SetFocus(t.menu)
		t.updateStatusBar("menu")
	}
}

// restoreFocus 恢复主界面焦点和光标显示
func (t *TUI) restoreFocus() {
	t.currentMode = ""  // 返回主界面
	t.previousMode = "" // 清除之前的模式记录
	t.app.SetRoot(t.flex, true)
	t.app.SetFocus(t.menu)
	t.updateStatusBar("menu")
}

// updateStatusBar 更新状态栏提示
func (t *TUI) updateStatusBar(mode string) {
	switch mode {
	case "menu":
		t.statusBar.SetText("[yellow]快捷键:[white] 1-4/h/q 选择菜单 | L/D 查看日志 | Tab 切换日志 | Ctrl+数字 快速执行 | R 刷新 | F9 鼠标 | Ctrl+Q 退出")
	case "debugLog":
		t.statusBar.SetText("[yellow]操作提示:[white] ↑↓滚动 | PgUp/PgDn翻页 | Home/End 跳转首尾 | Tab 切换日志 | R 刷新 | C 清空 | ESC 返回菜单")
	case "projectLog":
		t.statusBar.SetText("[yellow]操作提示:[white] ↑↓滚动 | PgUp/PgDn翻页 | Home/End 跳转首尾 | Tab 切换日志 | R 刷新 | C 清空 | ESC 返回菜单")
	case "submenu":
		t.statusBar.SetText("[yellow]操作提示:[white] ↑↓选择 | Enter 确认 | ESC 返回主菜单")
	default:
		t.statusBar.SetText("[yellow]快捷键:[white] 1-4/h/q 选择菜单 | L/D 查看日志 | Tab 切换日志 | Ctrl+数字 快速执行 | R 刷新 | F9 鼠标 | Ctrl+Q 退出")
	}
}

// handleGlobalInput 处理全局输入（快捷键）
func (t *TUI) handleGlobalInput(event *tcell.EventKey) *tcell.EventKey {
	// 如果当前不在主界面（包括日志浏览模式），则跳过菜单快捷键
	// 这样可以让二级界面（如输入框、日志视图）正常接收键盘输入
	if t.currentMode != "" && t.currentMode != "log" {
		return event
	}

	// 如果在日志浏览模式，仅允许 Ctrl+Q、R 和 ESC
	if t.currentMode == "log" {
		// Ctrl+Q 直接退出
		if event.Modifiers() == tcell.ModCtrl && (event.Rune() == 'q' || event.Rune() == 'Q') {
			t.exitApp()
			return nil
		}
		// R 刷新页面
		if event.Rune() == 'r' || event.Rune() == 'R' {
			t.refreshScreen()
			return nil
		}
		// ESC 返回菜单（日志视图自己处理）
		return event
	}

	// Ctrl+Q 直接退出
	if event.Modifiers() == tcell.ModCtrl && (event.Rune() == 'q' || event.Rune() == 'Q') {
		t.exitApp()
		return nil
	}

	// Ctrl+Shift+L 切换项目日志显示
	if event.Modifiers() == tcell.ModCtrl|tcell.ModShift && (event.Rune() == 'l' || event.Rune() == 'L') {
		t.toggleProjectLog()
		return nil
	}

	// Ctrl+Shift+M 切换鼠标模式
	if event.Modifiers() == tcell.ModCtrl|tcell.ModShift && (event.Rune() == 'm' || event.Rune() == 'M') {
		t.toggleMouse()
		return nil
	}

	// F9 键切换鼠标模式（备用）
	if event.Key() == tcell.KeyF9 {
		t.toggleMouse()
		return nil
	}

	// Ctrl+数字 (1-4)：快速执行（跳转并执行）
	if event.Modifiers() == tcell.ModCtrl {
		switch event.Rune() {
		case '1':
			t.runManagementMenu()
			return nil
		case '2':
			t.deviceMenu()
			return nil
		case '3':
			t.showAGManager()
			return nil
		case '4':
			t.showConfigManager()
			return nil
		}
	}

	// 数字键和字母：跳转到对应菜单项，如果已在当前项则执行选中
	switch event.Rune() {
	case '1':
		if t.menu.GetCurrentItem() == 0 {
			t.runManagementMenu()
		} else {
			t.menu.SetCurrentItem(0)
		}
		return nil
	case '2':
		if t.menu.GetCurrentItem() == 1 {
			t.deviceMenu()
		} else {
			t.menu.SetCurrentItem(1)
		}
		return nil
	case '3':
		if t.menu.GetCurrentItem() == 2 {
			t.showAGManager()
		} else {
			t.menu.SetCurrentItem(2)
		}
		return nil
	case '4':
		if t.menu.GetCurrentItem() == 3 {
			t.showConfigManager()
		} else {
			t.menu.SetCurrentItem(3)
		}
		return nil
	case 'i':
		if t.menu.GetCurrentItem() == 4 {
			t.initProject()
		} else {
			t.menu.SetCurrentItem(4)
		}
		return nil
	case 'h':
		if t.menu.GetCurrentItem() == 5 {
			t.showHelp()
		} else {
			t.menu.SetCurrentItem(5)
		}
		return nil
	case 'q':
		if t.menu.GetCurrentItem() == 6 {
			t.exitApp()
		} else {
			t.menu.SetCurrentItem(6)
		}
		return nil
	case 'l', 'L':
		// 切换焦点到项目日志
		if t.showProjectLog {
			t.currentMode = "log"
			t.app.SetFocus(t.projectLogView)
			t.updateStatusBar("projectLog")
		} else {
			t.log("[yellow]项目日志已隐藏，按 Ctrl+Shift+L 显示[white]")
		}
		return nil
	case 'd', 'D':
		// 切换焦点到调试器日志
		t.currentMode = "log"
		t.app.SetFocus(t.logView)
		t.updateStatusBar("debugLog")
		return nil
	case 'r', 'R':
		// 强制刷新页面
		t.refreshScreen()
		return nil
	}

	return event
}

// handleMouse 处理鼠标事件
func (t *TUI) handleMouse(event *tcell.EventMouse, action tview.MouseAction) (*tcell.EventMouse, tview.MouseAction) {
	// 如果鼠标模式被禁用，返回 nil 让终端原生处理（允许选择复制）
	if !t.mouseEnabled {
		return nil, action
	}

	// 只处理点击和滚动事件
	switch action {
	case tview.MouseLeftClick:
		return t.handleMouseClick(event)
	case tview.MouseScrollUp, tview.MouseScrollDown:
		return t.handleMouseScroll(event, action)
	}
	return event, action
}

// handleMouseClick 处理鼠标点击
func (t *TUI) handleMouseClick(event *tcell.EventMouse) (*tcell.EventMouse, tview.MouseAction) {
	x, y := event.Position()

	// 如果当前在日志浏览模式，点击日志区域切换焦点
	if t.currentMode == "log" {
		// 检查是否点击了调试器日志区域
		if t.isPointInRect(x, y, t.logView) {
			t.app.SetFocus(t.logView)
			t.updateStatusBar("debugLog")
			return nil, tview.MouseLeftClick
		}
		// 检查是否点击了项目日志区域
		if t.showProjectLog && t.isPointInRect(x, y, t.projectLogView) {
			t.app.SetFocus(t.projectLogView)
			t.updateStatusBar("projectLog")
			return nil, tview.MouseLeftClick
		}
	}

	// 对于主菜单（仅在主界面时），进行精确的点击范围检测
	if t.currentMode == "" && t.isPointInRect(x, y, t.menu) {
		if t.isClickOnMenuText(x, y, t.menu) {
			return event, tview.MouseLeftClick
		}
		return nil, tview.MouseLeftClick
	}

	// 对于其他区域（包括二级菜单），让 tview 默认处理
	return event, tview.MouseLeftClick
}

// isClickOnMenuText 检查点击是否在菜单文本区域
func (t *TUI) isClickOnMenuText(x, y int, menu *tview.List) bool {
	px, py, pw, _ := menu.GetRect()

	// 计算点击的是哪一行（考虑边框）
	// tview.List 有 1 像素的上边框
	row := y - py - 1
	if row < 0 {
		return false
	}

	// 获取菜单项数量
	itemCount := menu.GetItemCount()
	if row >= itemCount {
		return false
	}

	// 使用固定的文本区域宽度
	// 菜单文本通常在左侧，快捷键提示在右侧
	// 设置一个合理的宽度限制（约 20 个中文字符 = 40 个英文字符宽度）
	maxTextWidth := 40
	if maxTextWidth > pw {
		maxTextWidth = pw
	}

	// 检查点击是否在文本区域内
	clickOffset := x - px
	return clickOffset <= maxTextWidth
}

// handleMouseScroll 处理鼠标滚动
func (t *TUI) handleMouseScroll(event *tcell.EventMouse, action tview.MouseAction) (*tcell.EventMouse, tview.MouseAction) {
	x, y := event.Position()

	// 检查是否在调试器日志区域滚动
	if t.isPointInRect(x, y, t.logView) {
		row, col := t.logView.GetScrollOffset()
		if action == tview.MouseScrollUp {
			t.logView.ScrollTo(row-1, col)
		} else {
			t.logView.ScrollTo(row+1, col)
		}
		return nil, action
	}

	// 检查是否在项目日志区域滚动
	if t.showProjectLog && t.isPointInRect(x, y, t.projectLogView) {
		row, col := t.projectLogView.GetScrollOffset()
		if action == tview.MouseScrollUp {
			t.projectLogView.ScrollTo(row-1, col)
		} else {
			t.projectLogView.ScrollTo(row+1, col)
		}
		return nil, action
	}

	return event, action
}

// isPointInRect 检查点是否在组件范围内
func (t *TUI) isPointInRect(x, y int, primitive tview.Primitive) bool {
	// 获取组件在屏幕上的位置
	px, py, pw, ph := primitive.GetRect()
	return x >= px && x < px+pw && y >= py && y < py+ph
}

// initialize 初始化
func (t *TUI) initialize() {
	t.initOnce.Do(func() {
		t.printer.Verbose("正在初始化...")

		// 停止设备上的项目
		t.printer.Verbose("停止设备上的项目...")
		t.projectMgr.AGStop()

		// 检测 ADB
		t.printer.Verbose("检测 ADB...")
		if err := t.deviceMgr.DetectADB(); err != nil {
			t.log("[red]错误: %s[white]", err)
			return
		}
		t.printer.Verbose("ADB 已就绪")

		// 检测 AG
		t.printer.Verbose("检测 AG...")
		if err := t.projectMgr.DetectAG(); err != nil {
			t.log("")
			t.log("[red]未检测到 AG！[white]")
			t.log("")
			t.log("[yellow]AG 是 AutoGo 项目的核心工具，用于编译和部署项目到设备。[white]")
			t.log("")
			t.log("[cyan]安装方法：[white]")
			t.log("[white]  1. 在主菜单选择 [3] AG 更新[white]")
			t.log("[white]  2. 选择 [检查更新] 自动下载并安装[white]")
			t.log("")
			t.log("[yellow]或手动安装：[white]")
			t.log("[white]  - 下载地址: https://github.com/Dasongzi1366/AutoGo/releases[white]")
			t.log("[white]  - 将 ag 可执行文件放到 PATH 目录或设置环境变量 AUTOGO_AG_PATH[white]")
			t.log("")
			t.updateStatus("提示: 请选择 [3] AG 更新 安装 AG")
		} else {
			// 获取版本
			version := t.agManager.GetCurrentVersion()
			t.printer.Verbose("AG 已就绪 (v%s)", version)
		}

		// 检查设备连接
		if t.config.DeviceID == "" {
			t.printer.Verbose("未配置设备")
			t.updateStatus("提示: 请选择 [2] 设备管理 连接设备")
		} else {
			// 已配置设备，自动检测并更新IP
			t.printer.Verbose("检测已配置设备: %s", t.config.DeviceID)

			// 检查设备是否在线
			devices, err := t.deviceMgr.GetConnectedDevices()
			if err != nil {
				t.printer.Verbose("获取设备列表失败: %s", err)
			} else {
				deviceOnline := false
				for _, d := range devices {
					if d.Serial == t.config.DeviceID {
						deviceOnline = true
						break
					}
				}

				if !deviceOnline {
					t.log("[yellow]设备 %s 未连接[white]", t.config.DeviceID)
					t.updateStatus("提示: 设备未连接，请选择 [2] 设备管理")
				} else {
					// 设备在线，自动获取IP
					t.printer.Verbose("正在获取设备 IP 地址...")
					ip, err := t.deviceMgr.GetDeviceIP(t.config.DeviceID)
					if err != nil {
						t.printer.Verbose("获取设备 IP 失败: %s", err)
						t.updateStatus("提示: 请检查设备是否已连接 WiFi")
					} else {
						// 检查IP是否变化
						if t.config.DeviceIP != ip {
							t.printer.Verbose("设备 IP 已更新: %s", ip)
							t.config.DeviceIP = ip
							// 保存到配置文件
							if err := t.configMgr.Save(t.config); err != nil {
								t.printer.Verbose("保存配置失败: %s", err)
							} else {
								t.printer.Verbose("配置已保存")
							}
						} else {
							t.printer.Verbose("设备 IP: %s", ip)
						}
						t.printer.Verbose("设备已就绪: %s (%s)", t.config.DeviceID, t.config.DeviceIP)

						// 检查服务端口是否可访问（检测设备IP，因为服务运行在设备上）
						t.printer.Verbose("检查服务端口 %s:%s...", t.config.DeviceIP, t.config.DevicePort)
						if t.deviceMgr.ScanPort(t.config.DeviceIP, t.config.DevicePort, 2*time.Second) {
							t.config.DeviceServiceURL = fmt.Sprintf("%s:%s", t.config.DeviceIP, t.config.DevicePort)
							t.printer.Verbose("服务已运行: %s", t.config.DeviceServiceURL)
							t.updateStatus("服务运行中 | 可以直接运行脚本")
						} else {
							t.printer.Verbose("服务未运行")
							t.updateStatus("服务未运行")
						}
					}
				}
			}
		}

		t.ready = true
		t.printer.Verbose("初始化完成，准备就绪！")
	})
}

// refreshScreen 强制刷新页面显示
func (t *TUI) refreshScreen() {
	t.log("[cyan]正在刷新页面...[white]")

	// 重新设置根视图
	t.app.SetRoot(t.flex, true)

	// 根据当前模式设置焦点
	switch t.currentMode {
	case "":
		// 主界面
		t.app.SetFocus(t.menu)
		t.updateStatusBar("menu")
	case "log":
		// 日志浏览模式，保持当前焦点
		// 不需要改变焦点
	default:
		// 其他模式，返回主界面
		t.restoreFocus()
	}

	// 强制重绘
	t.app.Draw()

	t.log("[green]页面刷新完成[white]")
}

// runManagementMenu 运行管理菜单
func (t *TUI) runManagementMenu() {
	t.currentMode = "runmgmt"
	t.log("\n[cyan]=== 运行管理 ===[white]")

	// 显示二级菜单
	menu := tview.NewList()
	menu.ShowSecondaryText(false).
		SetSelectedTextColor(tcell.ColorYellow).
		SetSelectedBackgroundColor(tcell.ColorDarkCyan).
		SetMainTextColor(tcell.ColorWhite).
		SetBorder(true).
		SetTitle(" 运行管理 ").
		SetBorderColor(tcell.ColorYellow) // 初始边框颜色

	// 焦点变化时改变边框样式
	menu.SetFocusFunc(func() {
		menu.SetBorderColor(tcell.ColorYellow)
		menu.SetBorderAttributes(tcell.AttrBold) // 聚焦时黄色双线
	})
	menu.SetBlurFunc(func() {
		menu.SetBorderColor(tcell.ColorWhite)
		menu.SetBorderAttributes(0) // 失焦时白色单线
	})

	t.updateStatusBar("submenu")

	menu.AddItem("运行脚本", "", '1', nil)
	menu.AddItem("暂停脚本", "", '2', nil)
	menu.AddItem("恢复脚本", "", '3', nil)
	menu.AddItem("停止脚本", "", '4', nil)
	menu.AddItem("启动项目", "", '5', nil)
	menu.AddItem("停止项目", "", '6', nil)
	menu.AddItem("选择脚本", "", 's', nil)
	menu.AddItem("返回", "", 'q', nil)

	// 显示当前选择的脚本
	if t.selectedScript != "" {
		t.log("[cyan]当前选择的脚本: %s[white]", t.selectedScript)
	} else {
		t.log("[yellow]未选择脚本，请先选择脚本[white]")
	}

	menu.SetSelectedFunc(func(index int, name string, secondary string, shortcut rune) {
		switch shortcut {
		case '1':
			t.runScript()
		case '2':
			t.pauseScript()
		case '3':
			t.resumeScript()
		case '4':
			t.stopScript()
		case '5':
			t.startProject()
		case '6':
			t.stopProject()
		case 's':
			t.selectScript()
		case 'q':
			t.restoreFocus()
		}
	})

	// 添加返回快捷键
	menu.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyEscape || event.Rune() == 'q' {
			t.restoreFocus()
			return nil
		}
		// L/D 快捷键切换到日志
		if event.Rune() == 'l' || event.Rune() == 'L' {
			if t.showProjectLog {
				t.previousMode = t.currentMode // 保存当前模式
				t.currentMode = "log"
				t.app.SetFocus(t.projectLogView)
				t.updateStatusBar("projectLog")
			}
			return nil
		}
		if event.Rune() == 'd' || event.Rune() == 'D' {
			t.previousMode = t.currentMode // 保存当前模式
			t.currentMode = "log"
			t.app.SetFocus(t.logView)
			t.updateStatusBar("debugLog")
			return nil
		}
		return event
	})

	// 创建运行管理布局（上菜单，中日志，下状态栏）
	runMgmtFlex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(menu, 12, 0, true).       // 菜单固定 12 行
		AddItem(t.logFlex, 0, 1, false).  // 日志占剩余空间
		AddItem(t.statusBar, 1, 0, false) // 状态栏

	t.app.SetRoot(runMgmtFlex, true)
}

// selectScript 选择脚本
func (t *TUI) selectScript() {
	t.log("\n[cyan]=== 选择脚本 ===[white]")

	// 扫描 scripts 目录
	scriptsDir := filepath.Join(t.config.ProjectPath, "scripts")
	var scriptFiles []string

	if files, err := os.ReadDir(scriptsDir); err == nil {
		for _, file := range files {
			if file.IsDir() {
				continue
			}
			name := file.Name()
			ext := filepath.Ext(name)
			if ext == ".lua" || ext == ".js" {
				scriptFiles = append(scriptFiles, name)
			}
		}
		sort.Strings(scriptFiles)
	}

	if len(scriptFiles) == 0 {
		t.log("[yellow]未找到脚本文件 (scripts/*.lua 或 scripts/*.js)[white]")
		return
	}

	// 显示选择表单
	form := tview.NewForm()
	form.SetBorder(true).SetTitle(" 选择脚本 ")

	// 查找当前选中脚本的索引
	currentIndex := 0
	for i, f := range scriptFiles {
		if f == t.selectedScript {
			currentIndex = i
			break
		}
	}

	var selectedScript string
	form.AddDropDown("脚本文件:", scriptFiles, currentIndex, func(option string, index int) {
		selectedScript = option
	}).
		AddButton("确认", func() {
			if selectedScript == "" {
				t.log("[red]错误: 未选择脚本文件[white]")
				t.runManagementMenu() // 返回二级菜单而不是主菜单
				return
			}

			t.selectedScript = selectedScript
			t.config.SelectedScript = selectedScript

			// 根据文件扩展名自动设置脚本类型
			ext := filepath.Ext(selectedScript)
			var scriptType string
			switch ext {
			case ".lua":
				scriptType = "lua"
			case ".js":
				scriptType = "javascript"
			}

			// 保存到配置文件
			if err := t.configMgr.Save(t.config); err != nil {
				t.log("[red]保存配置失败: %s[white]", err)
			} else {
				t.printer.Verbose("配置已保存")
				t.log("[green]已选择脚本: %s (类型: %s)[white]", selectedScript, scriptType)
			}
			t.runManagementMenu() // 返回二级菜单
		}).
		AddButton("取消", func() {
			t.runManagementMenu() // 返回二级菜单
		})

	t.app.SetRoot(form, true)
}

// runScript 运行脚本（运行之前选择的脚本）
func (t *TUI) runScript() {
	t.log("\n[cyan]=== 运行脚本 ===[white]")

	// 检查是否选择了脚本
	if t.selectedScript == "" {
		t.log("[red]错误: 请先选择脚本[white]")
		return
	}

	var scriptType string
	if strings.HasSuffix(t.selectedScript, ".lua") {
		scriptType = "lua"
	} else if strings.HasSuffix(t.selectedScript, ".js") {
		scriptType = "javascript"
	} else {
		t.log("[red]错误: 脚本类型不支持[white]")
		return
	}

	// 检查脚本状态
	if t.scriptStatus == "running" {
		t.log("[red]错误: 脚本正在运行中，请先停止当前脚本[white]")
		return
	}
	if t.scriptStatus == "paused" {
		t.log("[yellow]提示: 有暂停的脚本，建议先恢复或停止[white]")
	}

	// 检查设备服务地址
	if t.config.DeviceServiceURL == "" {
		t.log("[red]错误: 未配置设备服务地址，请先启动项目[white]")
		return
	}

	// 检查设备 IP 和端口
	if t.config.DeviceIP == "" || t.config.DevicePort == "" {
		t.log("[red]错误: 未配置设备 IP 或端口[white]")
		return
	}

	// 检测设备端口连通性
	t.printer.Verbose("检测设备服务: %s:%s", t.config.DeviceIP, t.config.DevicePort)
	if !t.deviceMgr.CheckScriptEngineService(t.config.DeviceIP, t.config.DevicePort, 3*time.Second) {
		t.log("[red]错误: 设备服务 %s:%s 不可访问或未就绪[white]", t.config.DeviceIP, t.config.DevicePort)
		t.log("[yellow]请确保项目已在设备上启动[white]")
		return
	}
	t.printer.Verbose("设备服务检测通过")

	// 使用选择的脚本
	scriptsDir := filepath.Join(t.config.ProjectPath, "scripts")
	scriptPath := filepath.Join(scriptsDir, t.selectedScript)
	t.log("[yellow]运行脚本: %s[white]", t.selectedScript)
	t.log("[cyan]脚本类型: %s | 代码风格: %s[white]", scriptType, t.config.CodeStyle)

	runner := script.NewRunner(t.config.DeviceServiceURL, t.printer)
	runner.SetDevice(t.config.DeviceID, os.Getenv("AUTOGO_ADB_PATH"))

	// 更新状态
	t.scriptStatus = "running"
	t.runningScript = scriptPath

	go func() {
		err := runner.Run(scriptPath, scriptType, t.config.CodeStyle)
		t.scriptStatus = "idle"
		t.runningScript = ""

		if err != nil {
			t.log("[red]运行失败: %s[white]", err)
		} else {
			t.log("[green]脚本运行完成[white]")
		}
	}()
}

// pauseScript 暂停脚本
func (t *TUI) pauseScript() {
	t.log("\n[cyan]=== 暂停脚本 ===[white]")

	// 检查状态
	if t.scriptStatus == "idle" {
		t.log("[red]错误: 没有运行中的脚本[white]")
		return
	}
	if t.scriptStatus == "paused" {
		t.log("[yellow]提示: 脚本已经暂停[white]")
		return
	}

	runner := script.NewRunner(t.config.DeviceServiceURL, t.printer)
	runner.SetDebugLog(t.log) // 设置调试日志输出到调试器日志
	if err := runner.Pause(""); err != nil {
		t.log("[red]暂停失败: %s[white]", err)
	} else {
		t.scriptStatus = "paused"
		t.log("[green]脚本已暂停[white]")
	}
}

// resumeScript 恢复脚本
func (t *TUI) resumeScript() {
	t.log("\n[cyan]=== 恢复脚本 ===[white]")

	// 检查状态
	if t.scriptStatus == "idle" {
		t.log("[red]错误: 没有暂停的脚本[white]")
		return
	}
	if t.scriptStatus == "running" {
		t.log("[yellow]提示: 脚本正在运行中[white]")
		return
	}

	runner := script.NewRunner(t.config.DeviceServiceURL, t.printer)
	runner.SetDebugLog(t.log) // 设置调试日志输出到调试器日志
	if err := runner.Resume(""); err != nil {
		t.log("[red]恢复失败: %s[white]", err)
	} else {
		t.scriptStatus = "running"
		t.log("[green]脚本已恢复[white]")
	}
}

// stopScript 停止脚本
func (t *TUI) stopScript() {
	t.log("\n[cyan]=== 停止脚本 ===[white]")

	// 检查状态
	if t.scriptStatus == "idle" {
		t.log("[yellow]提示: 没有运行中的脚本[white]")
		return
	}

	runner := script.NewRunner(t.config.DeviceServiceURL, t.printer)
	runner.SetDebugLog(t.log) // 设置调试日志输出到调试器日志
	if err := runner.Stop(""); err != nil {
		t.log("[red]停止失败: %s[white]", err)
	} else {
		t.scriptStatus = "idle"
		t.runningScript = ""
		t.log("[green]脚本已停止[white]")
	}
}

// startProject 启动项目
func (t *TUI) startProject() {
	t.log("\n[cyan]=== 启动项目 ===[white]")

	if !t.ready {
		t.log("[red]错误: 系统未初始化完成，请稍候...[white]")
		return
	}

	if t.projectMgr.IsRunning() {
		t.log("[yellow]项目已在运行中[white]")
		return
	}

	// 检查设备是否已连接
	if t.config.DeviceID == "" || t.config.DeviceIP == "" {
		t.log("[red]错误: 未连接设备，请先在设备管理中连接设备[white]")
		t.updateStatus("请先连接设备")
		return
	}

	t.log("[yellow]设备: %s (%s)[white]", t.config.DeviceID, t.config.DeviceIP)
	t.log("[yellow]正在启动项目...[white]")
	t.updateStatus("正在启动项目，请稍候...")

	go func() {
		if err := t.projectMgr.RunAsync(t.config.ProjectPath, t.config.DeviceID, false); err != nil {
			t.log("[red]启动失败: %s[white]", err)
			t.updateStatus("启动失败")
			return
		}

		t.log("[green]项目进程已启动，等待服务就绪...[white]")

		// 等待端口就绪（检测设备IP端口，因为AG命令在设备上启动服务）
		if !t.projectMgr.WaitForReady(t.config.DeviceIP, t.config.DevicePort, 60) {
			// 检查进程是否还在运行
			if !t.projectMgr.IsRunning() {
				t.log("[red]项目进程异常终止，请检查项目配置[white]")
			} else {
				t.log("[red]服务启动超时，端口 %s 不可访问[white]", t.config.DevicePort)
			}
			t.updateStatus("启动失败")
			return
		}

		// DeviceServiceURL 使用设备IP地址，因为服务运行在设备上
		t.config.DeviceServiceURL = fmt.Sprintf("%s:%s", t.config.DeviceIP, t.config.DevicePort)
		t.log("[green]服务已启动: %s[white]", t.config.DeviceServiceURL)
		t.updateStatus("服务运行中 | [6] 停止项目")

		// 保存配置
		if err := t.configMgr.Save(t.config); err != nil {
			t.log("[red]保存配置失败: %s[white]", err)
		} else {
			t.printer.Verbose("配置已保存")
		}
	}()
}

// stopProject 停止项目
func (t *TUI) stopProject() {
	t.log("\n[cyan]=== 停止项目 ===[white]")

	if !t.projectMgr.IsRunning() {
		t.log("[yellow]项目未在运行[white]")
		return
	}

	if err := t.projectMgr.Stop(); err != nil {
		t.log("[red]停止失败: %s[white]", err)
	} else {
		t.log("[green]项目已停止[white]")
		t.updateStatus("按数字键或方向键选择菜单 | 回车确认 | q 退出")
	}
}

// deviceMenu 设备管理菜单
func (t *TUI) deviceMenu() {
	t.currentMode = "device" // 进入设备管理模式

	// 创建设备管理菜单
	deviceMenu := tview.NewList().
		AddItem("查看设备", "", '1', nil).
		AddItem("连接设备", "", '2', nil).
		AddItem("选择设备", "", '3', nil).
		AddItem("返回", "", 'q', nil)
	deviceMenu.ShowSecondaryText(false)
	deviceMenu.SetBorder(true)
	deviceMenu.SetTitle(" 设备管理 ")
	deviceMenu.SetMainTextColor(tcell.ColorWhite)
	deviceMenu.SetSelectedTextColor(tcell.ColorYellow)
	deviceMenu.SetSelectedBackgroundColor(tcell.ColorDarkCyan)
	deviceMenu.SetBorderColor(tcell.ColorYellow) // 初始边框颜色

	// 焦点变化时改变边框样式
	deviceMenu.SetFocusFunc(func() {
		deviceMenu.SetBorderColor(tcell.ColorYellow)
		deviceMenu.SetBorderAttributes(tcell.AttrBold) // 聚焦时黄色双线
	})
	deviceMenu.SetBlurFunc(func() {
		deviceMenu.SetBorderColor(tcell.ColorWhite)
		deviceMenu.SetBorderAttributes(0) // 失焦时白色单线
	})

	t.updateStatusBar("submenu")

	// 创建设备日志输出区域
	deviceLogView := tview.NewTextView().
		SetDynamicColors(true).
		SetRegions(true).
		SetWordWrap(true).
		SetChangedFunc(func() {
			t.app.Draw()
		})
	deviceLogView.SetBorder(true).SetTitle(" 日志输出 ")

	// 创建设备管理布局（上菜单，中日志，下状态栏）
	deviceFlex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(deviceMenu, 8, 0, true).     // 菜单固定 8 行
		AddItem(deviceLogView, 0, 1, false). // 日志占剩余空间
		AddItem(t.statusBar, 1, 0, false)    // 状态栏

	// 设置菜单选择处理
	deviceMenu.SetSelectedFunc(func(index int, name string, secondary string, shortcut rune) {
		switch shortcut {
		case '1':
			t.deviceLog(deviceLogView, "\n[cyan]=== 查看设备 ===[white]")
			t.listDevicesWithLog(deviceLogView)
		case '2':
			t.connectDeviceWithLog(deviceLogView)
		case '3':
			t.selectDeviceMenuWithLog(deviceLogView)
		case 'q':
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
		}
	})

	// 处理 ESC 和 Backspace 键
	deviceMenu.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyESC || event.Key() == tcell.KeyBackspace || event.Key() == tcell.KeyBackspace2 {
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
			return nil
		}
		return event
	})

	t.app.SetRoot(deviceFlex, true)
}

// listDevices 列出设备（不返回主菜单）
func (t *TUI) listDevices() {
	t.log("\n[yellow]正在获取设备列表...[white]")

	go func() {
		devices, err := t.deviceMgr.GetConnectedDevices()
		if err != nil {
			t.log("[red]错误: %s[white]", err)
			return
		}

		if len(devices) == 0 {
			t.log("[yellow]未检测到已连接的设备[white]")
			t.log("[yellow]提示: 可以通过 TCP/IP 连接设备[white]")
			return
		}

		t.log("[green]检测到 %d 个设备:[white]", len(devices))
		for i, d := range devices {
			// 注意：%d 不要用方括号包裹，否则会被解析为颜色标签
			t.log("[white]  %d. %s (%s)", i+1, d.Serial, d.State)
		}

		// 如果只有一个设备，提示已自动选择
		if len(devices) == 1 {
			t.log("[green]当前设备: %s[white]", devices[0].Serial)
			t.selectDevice(devices[0].Serial)
		} else {
			t.log("[yellow]提示: 可以选择 (3) 选择设备 来选择要使用的设备[white]")
		}
	}()
}

// deviceLog 在设备日志区域输出日志（线程安全）
func (t *TUI) deviceLog(logView *tview.TextView, format string, args ...interface{}) {
	t.logMutex.Lock()
	msg := fmt.Sprintf(format, args...)
	t.logMutex.Unlock()

	// 使用 goroutine 异步更新，避免在 UI 线程中死锁
	go func() {
		t.app.QueueUpdateDraw(func() {
			fmt.Fprintf(logView, "%s\n", msg)
			logView.ScrollToEnd()
		})
	}()
}

// listDevicesWithLog 列出设备（带日志输出）
func (t *TUI) listDevicesWithLog(logView *tview.TextView) {
	t.deviceLog(logView, "[yellow]正在获取设备列表...[white]")

	go func() {
		devices, err := t.deviceMgr.GetConnectedDevices()
		if err != nil {
			t.deviceLog(logView, "[red]错误: %s[white]", err)
			return
		}

		if len(devices) == 0 {
			t.deviceLog(logView, "[yellow]未检测到已连接的设备[white]")
			t.deviceLog(logView, "[yellow]提示: 可以通过 TCP/IP 连接设备[white]")
			return
		}

		t.deviceLog(logView, "[green]检测到 %d 个设备:[white]", len(devices))
		for i, d := range devices {
			t.deviceLog(logView, "[white]  %d. %s (%s)", i+1, d.Serial, d.State)
		}

		// 如果只有一个设备，提示已自动选择
		if len(devices) == 1 {
			t.deviceLog(logView, "[green]当前设备: %s[white]", devices[0].Serial)
			t.selectDeviceWithLog(devices[0].Serial, logView)
		} else {
			t.deviceLog(logView, "[yellow]提示: 可以选择 (3) 选择设备 来选择要使用的设备[white]")
		}
	}()
}

// selectDeviceMenu 选择设备菜单
func (t *TUI) selectDeviceMenu() {
	t.log("\n[yellow]正在获取设备列表...[white]")

	go func() {
		devices, err := t.deviceMgr.GetConnectedDevices()
		if err != nil {
			t.log("[red]错误: %s[white]", err)
			return
		}

		if len(devices) == 0 {
			t.log("[yellow]未检测到已连接的设备[white]")
			return
		}

		// 创建设备选择列表
		deviceList := tview.NewList()
		deviceList.ShowSecondaryText(false)
		deviceList.SetBorder(false)
		deviceList.SetMainTextColor(tcell.ColorWhite)
		deviceList.SetSelectedTextColor(tcell.ColorYellow)
		deviceList.SetSelectedBackgroundColor(tcell.ColorDarkCyan)

		for i, d := range devices {
			device := d // 捕获变量
			deviceList.AddItem(
				fmt.Sprintf("%s", device.Serial),
				"",
				rune('1'+i),
				func() {
					t.selectDevice(device.Serial)
					t.restoreFocus()
				},
			)
		}

		deviceList.AddItem("返回", "", 'q', func() {
			t.deviceMenu()
		})

		// 处理 ESC 和 Backspace 键
		deviceList.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
			if event.Key() == tcell.KeyESC || event.Key() == tcell.KeyBackspace || event.Key() == tcell.KeyBackspace2 {
				t.deviceMenu()
				return nil
			}
			return event
		})

		t.app.QueueUpdateDraw(func() {
			t.app.SetRoot(deviceList, true)
		})
	}()
}

// connectDevice 连接设备
func (t *TUI) connectDevice() {
	form := tview.NewForm()
	form.SetBorder(true).SetTitle(" 连接设备 ")

	var deviceIP string
	var port string

	form.AddInputField("设备 IP:", "", 20, nil, func(text string) {
		deviceIP = text
	}).
		AddInputField("端口:", "5555", 10, nil, func(text string) {
			port = text
		}).
		AddButton("连接", func() {
			if deviceIP == "" {
				t.log("[red]错误: 未输入设备 IP[white]")
				t.restoreFocus()
				return
			}

			address := fmt.Sprintf("%s:%s", deviceIP, port)
			t.log("[yellow]连接设备: %s[white]", address)

			go func() {
				if err := t.deviceMgr.ConnectDevice(address); err != nil {
					t.log("[red]连接失败: %s[white]", err)
				} else {
					t.log("[green]连接成功[white]")

					// 获取设备列表
					devices, _ := t.deviceMgr.GetConnectedDevices()
					if len(devices) > 0 {
						t.selectDevice(devices[0].Serial)
					}
				}
			}()

			t.restoreFocus()
		}).
		AddButton("取消", func() {
			t.restoreFocus()
		})

	t.app.SetRoot(form, true)
}

// selectDevice 选择设备
func (t *TUI) selectDevice(deviceID string) {
	t.log("[yellow]正在选择设备: %s[white]", deviceID)
	t.log("[cyan]正在获取设备 IP 地址...[white]")

	go func() {
		// 获取设备 IP
		ip, err := t.deviceMgr.GetDeviceIP(deviceID)
		if err != nil {
			t.log("[red]获取设备 IP 失败: %s[white]", err)
			t.log("[yellow]提示: 请确保设备已连接 WiFi[white]")
			return
		}

		// 检查IP是否变化
		ipChanged := t.config.DeviceIP != ip
		t.config.DeviceID = deviceID
		t.config.DeviceIP = ip

		if ipChanged && t.config.DeviceIP != "" {
			t.log("[green]设备 IP 已更新: %s[white]", ip)
		} else {
			t.log("[green]设备 IP: %s[white]", ip)
		}

		t.log("[green]设备已配置: %s (%s)[white]", deviceID, ip)

		// 保存配置
		if err := t.configMgr.Save(t.config); err != nil {
			t.log("[red]保存配置失败: %s[white]", err)
		} else {
			t.printer.Verbose("配置已保存到文件")
		}
	}()
}

// selectDeviceWithLog 选择设备（带日志输出）
func (t *TUI) selectDeviceWithLog(deviceID string, logView *tview.TextView) {
	t.deviceLog(logView, "[yellow]正在选择设备: %s[white]", deviceID)
	t.deviceLog(logView, "[cyan]正在获取设备 IP 地址...[white]")

	go func() {
		// 获取设备 IP
		ip, err := t.deviceMgr.GetDeviceIP(deviceID)
		if err != nil {
			t.deviceLog(logView, "[red]获取设备 IP 失败: %s[white]", err)
			t.deviceLog(logView, "[yellow]提示: 请确保设备已连接 WiFi[white]")
			return
		}

		t.config.DeviceID = deviceID
		t.config.DeviceIP = ip

		t.deviceLog(logView, "[green]设备 IP: %s[white]", ip)
		t.deviceLog(logView, "[green]设备已配置: %s (%s)[white]", deviceID, ip)

		// 保存配置
		if err := t.configMgr.Save(t.config); err != nil {
			t.deviceLog(logView, "[red]保存配置失败: %s[white]", err)
		} else {
			t.printer.Verbose("配置已保存到文件")
		}
	}()
}

// connectDeviceWithLog 连接设备（带日志输出）
func (t *TUI) connectDeviceWithLog(logView *tview.TextView) {
	t.deviceLog(logView, "\n[cyan]=== 连接设备 ===[white]")

	// 创建表单
	form := tview.NewForm()
	form.AddInputField("设备 IP:", "", 20, nil, nil)
	form.AddInputField("端口:", "5555", 10, nil, nil)
	form.AddButton("连接", func() {
		deviceIP := form.GetFormItem(0).(*tview.InputField).GetText()
		port := form.GetFormItem(1).(*tview.InputField).GetText()

		if deviceIP == "" {
			t.deviceLog(logView, "[red]错误: 未输入设备 IP[white]")
			return
		}

		address := fmt.Sprintf("%s:%s", deviceIP, port)
		t.deviceLog(logView, "[yellow]连接设备: %s[white]", address)

		go func() {
			if err := t.deviceMgr.ConnectDevice(address); err != nil {
				t.deviceLog(logView, "[red]连接失败: %s[white]", err)
			} else {
				t.deviceLog(logView, "[green]设备连接成功[white]")

				// 获取设备列表
				t.deviceLog(logView, "[cyan]正在获取设备信息...[white]")
				devices, _ := t.deviceMgr.GetConnectedDevices()
				if len(devices) > 0 {
					t.deviceLog(logView, "[green]检测到设备: %s[white]", devices[0].Serial)
					t.selectDeviceWithLog(devices[0].Serial, logView)
				} else {
					t.deviceLog(logView, "[red]错误: 未检测到设备[white]")
				}
			}
		}()
	})
	form.AddButton("取消", func() {
		t.deviceLog(logView, "[yellow]已取消连接[white]")
	})
	form.SetBorder(true).SetTitle(" 连接设备 ")

	// 创建布局：表单在上，日志在中，状态栏在下
	flex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(form, 7, 0, true).
		AddItem(logView, 0, 1, false).
		AddItem(t.statusBar, 1, 0, false)

	// 处理 ESC 键
	flex.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyESC {
			t.deviceLog(logView, "[yellow]已取消连接[white]")
			t.deviceMenu() // 返回设备管理
			return nil
		}
		return event
	})

	t.app.SetRoot(flex, true)
}

// selectDeviceMenuWithLog 选择设备菜单（带日志输出）
func (t *TUI) selectDeviceMenuWithLog(logView *tview.TextView) {
	t.deviceLog(logView, "\n[yellow]正在获取设备列表...[white]")

	go func() {
		devices, err := t.deviceMgr.GetConnectedDevices()
		if err != nil {
			t.deviceLog(logView, "[red]错误: %s[white]", err)
			return
		}

		if len(devices) == 0 {
			t.deviceLog(logView, "[yellow]未检测到已连接的设备[white]")
			return
		}

		// 创建设备选择列表
		deviceList := tview.NewList()
		deviceList.ShowSecondaryText(false)
		deviceList.SetBorder(true)
		deviceList.SetTitle(" 选择设备 ")
		deviceList.SetMainTextColor(tcell.ColorWhite)
		deviceList.SetSelectedTextColor(tcell.ColorYellow)
		deviceList.SetSelectedBackgroundColor(tcell.ColorDarkCyan)

		for i, d := range devices {
			device := d // 捕获变量
			deviceList.AddItem(
				fmt.Sprintf("%s", device.Serial),
				"",
				rune('1'+i),
				func() {
					t.selectDeviceWithLog(device.Serial, logView)
					t.deviceMenu() // 返回设备管理
				},
			)
		}

		deviceList.AddItem("返回", "", 'q', func() {
			t.deviceMenu()
		})

		// 处理 ESC 和 Backspace 键
		deviceList.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
			if event.Key() == tcell.KeyESC || event.Key() == tcell.KeyBackspace || event.Key() == tcell.KeyBackspace2 {
				t.deviceMenu()
				return nil
			}
			return event
		})

		t.app.QueueUpdateDraw(func() {
			t.app.SetRoot(deviceList, true)
		})
	}()
}

// showConfig 显示配置
func (t *TUI) showConfig() {
	t.log("\n[cyan]=== 当前配置 ===[white]")
	t.log("代码风格: %s", t.config.CodeStyle)
	t.log("设备服务地址: %s", t.config.DeviceServiceURL)
	t.log("设备 ID: %s", t.config.DeviceID)
	t.log("设备 IP: %s", t.config.DeviceIP)
	t.log("设备端口: %s", t.config.DevicePort)
	t.log("项目路径: %s", t.config.ProjectPath)
	t.log("AG 版本: %s", t.agManager.GetCurrentVersion())
}

// showConfigWithLog 显示配置（带日志输出）
func (t *TUI) showConfigWithLog(logView *tview.TextView) {
	t.agLog(logView, "\n[cyan]=== 当前配置 ===[white]")
	t.agLog(logView, "[white]代码风格: %s", t.config.CodeStyle)
	t.agLog(logView, "[white]设备服务地址: %s", t.config.DeviceServiceURL)
	t.agLog(logView, "[white]设备 ID: %s", t.config.DeviceID)
	t.agLog(logView, "[white]设备 IP: %s", t.config.DeviceIP)
	t.agLog(logView, "[white]设备端口: %s", t.config.DevicePort)
	t.agLog(logView, "[white]项目路径: %s", t.config.ProjectPath)
	t.agLog(logView, "[white]AG 版本: %s", t.agManager.GetCurrentVersion())
	t.agLog(logView, "[white]HTTP 代理: %s", t.config.HTTPProxy)
	t.agLog(logView, "[white]AG 路径: %s", t.agManager.GetAGPath())
}

// showConfigManager 显示配置管理
func (t *TUI) showConfigManager() {
	t.currentMode = "config" // 进入配置管理模式

	// 创建配置管理菜单
	configMenu := tview.NewList().
		AddItem("脚本类型", "", '1', nil).
		AddItem("代码风格", "", '2', nil).
		AddItem("设备服务地址", "", '3', nil).
		AddItem("设备 ID", "", '4', nil).
		AddItem("设备 IP", "", '5', nil).
		AddItem("设备端口", "", '6', nil).
		AddItem("项目路径", "", '7', nil).
		AddItem("HTTP 代理", "", '8', nil).
		AddItem("AG 路径", "", '9', nil).
		AddItem("返回", "", 'q', nil)
	configMenu.ShowSecondaryText(false)
	configMenu.SetBorder(true)
	configMenu.SetTitle(" 配置管理 ")
	configMenu.SetMainTextColor(tcell.ColorWhite)
	configMenu.SetSelectedTextColor(tcell.ColorYellow)
	configMenu.SetSelectedBackgroundColor(tcell.ColorDarkCyan)
	configMenu.SetBorderColor(tcell.ColorYellow) // 初始边框颜色

	// 焦点变化时改变边框样式
	configMenu.SetFocusFunc(func() {
		configMenu.SetBorderColor(tcell.ColorYellow)
		configMenu.SetBorderAttributes(tcell.AttrBold) // 聚焦时黄色双线
	})
	configMenu.SetBlurFunc(func() {
		configMenu.SetBorderColor(tcell.ColorWhite)
		configMenu.SetBorderAttributes(0) // 失焦时白色单线
	})

	t.updateStatusBar("submenu")

	// 创建配置日志输出区域
	configLogView := tview.NewTextView().
		SetDynamicColors(true).
		SetRegions(true).
		SetWordWrap(true).
		SetChangedFunc(func() {
			t.app.Draw()
		})
	configLogView.SetBorder(true).SetTitle(" 日志输出 ")

	// 创建配置管理布局（上菜单，中日志，下状态栏）
	configFlex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(configMenu, 12, 0, true).    // 菜单固定 12 行
		AddItem(configLogView, 0, 1, false). // 日志占剩余空间
		AddItem(t.statusBar, 1, 0, false)    // 状态栏

	// 显示当前配置
	t.configLog(configLogView, "\n[cyan]=== 当前配置 ===[white]")
	t.configLog(configLogView, "[white]代码风格: %s", t.config.CodeStyle)
	t.configLog(configLogView, "[white]设备服务地址: %s", t.config.DeviceServiceURL)
	t.configLog(configLogView, "[white]设备 ID: %s", t.config.DeviceID)
	t.configLog(configLogView, "[white]设备 IP: %s", t.config.DeviceIP)
	t.configLog(configLogView, "[white]设备端口: %s", t.config.DevicePort)
	t.configLog(configLogView, "[white]项目路径: %s", t.config.ProjectPath)
	t.configLog(configLogView, "[white]HTTP 代理: %s", t.config.HTTPProxy)
	t.configLog(configLogView, "[white]AG 路径: %s", t.config.AGPath)

	// 设置菜单选择处理
	configMenu.SetSelectedFunc(func(index int, name string, secondary string, shortcut rune) {
		switch shortcut {
		case '2':
			t.editConfigItem("代码风格", "codeStyle", t.config.CodeStyle, configLogView)
		case '3':
			t.editConfigItem("设备服务地址", "serverUrl", t.config.DeviceServiceURL, configLogView)
		case '4':
			t.editConfigItem("设备 ID", "deviceId", t.config.DeviceID, configLogView)
		case '5':
			t.editConfigItem("设备 IP", "deviceIp", t.config.DeviceIP, configLogView)
		case '6':
			t.editConfigItem("设备端口", "devicePort", t.config.DevicePort, configLogView)
		case '7':
			t.editConfigItem("项目路径", "projectPath", t.config.ProjectPath, configLogView)
		case '8':
			t.editConfigItem("HTTP 代理", "httpProxy", t.config.HTTPProxy, configLogView)
		case '9':
			t.editConfigItem("AG 路径", "agPath", t.config.AGPath, configLogView)
		case 'q':
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
		}
	})

	// 处理 ESC 和 Backspace 键
	configMenu.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyESC || event.Key() == tcell.KeyBackspace || event.Key() == tcell.KeyBackspace2 {
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
			return nil
		}
		return event
	})

	t.app.SetRoot(configFlex, true)
}

// configLog 在配置日志区域输出日志（线程安全）
func (t *TUI) configLog(logView *tview.TextView, format string, args ...interface{}) {
	t.logMutex.Lock()
	msg := fmt.Sprintf(format, args...)
	t.logMutex.Unlock()

	// 使用 goroutine 异步更新，避免在 UI 线程中死锁
	go func() {
		t.app.QueueUpdateDraw(func() {
			fmt.Fprintf(logView, "%s\n", msg)
			logView.ScrollToEnd()
		})
	}()
}

// editConfigItem 编辑配置项
func (t *TUI) editConfigItem(name, key, currentValue string, logView *tview.TextView) {
	t.configLog(logView, "\n[yellow]编辑配置项: %s[white]", name)
	t.configLog(logView, "[white]当前值: %s", currentValue)

	// 创建表单
	form := tview.NewForm()
	form.AddInputField("新值:", currentValue, 50, nil, nil)
	form.AddButton("保存", func() {
		newValue := form.GetFormItem(0).(*tview.InputField).GetText()

		// 更新配置
		switch key {
		case "codeStyle":
			t.config.CodeStyle = newValue
		case "serverUrl":
			t.config.DeviceServiceURL = newValue
		case "deviceId":
			t.config.DeviceID = newValue
		case "deviceIp":
			t.config.DeviceIP = newValue
		case "devicePort":
			t.config.DevicePort = newValue
		case "projectPath":
			t.config.ProjectPath = newValue
		case "httpProxy":
			t.config.HTTPProxy = newValue
			t.agManager.SetHTTPProxy(newValue)
		case "agPath":
			t.config.AGPath = newValue
		}

		// 保存配置
		if err := t.configMgr.Save(t.config); err != nil {
			t.configLog(logView, "[red]保存失败: %s[white]", err)
		} else {
			t.configLog(logView, "[green]配置已保存: %s = %s[white]", name, newValue)
		}

		// 返回配置管理
		t.showConfigManager()
	})
	form.AddButton("取消", func() {
		t.configLog(logView, "[yellow]已取消修改[white]")
		// 返回配置管理
		t.showConfigManager()
	})
	form.SetBorder(true).SetTitle(fmt.Sprintf(" 编辑: %s ", name))

	// 创建布局：表单在上，日志在中，状态栏在下
	flex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(form, 7, 0, true).
		AddItem(logView, 0, 1, false).
		AddItem(t.statusBar, 1, 0, false)

	// 处理 ESC 键
	flex.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyESC {
			t.configLog(logView, "[yellow]已取消修改[white]")
			t.showConfigManager()
			return nil
		}
		return event
	})

	t.app.SetRoot(flex, true)
}

// showAGManager 显示 AG 管理
func (t *TUI) showAGManager() {
	// 设置当前模式
	t.currentMode = "ag"

	// 创建 AG 管理菜单
	agMenu := tview.NewList().
		AddItem("检查更新", "", 'c', nil).
		AddItem("查看当前版本", "", 'v', nil).
		AddItem("查看配置", "", 's', nil).
		AddItem("配置代理", "", 'p', nil).
		AddItem("返回", "", 'q', nil)
	agMenu.ShowSecondaryText(false)
	agMenu.SetBorder(true)
	agMenu.SetTitle(" AG 管理 ")
	agMenu.SetMainTextColor(tcell.ColorWhite)
	agMenu.SetSelectedTextColor(tcell.ColorYellow)
	agMenu.SetSelectedBackgroundColor(tcell.ColorDarkCyan)
	agMenu.SetBorderColor(tcell.ColorYellow) // 初始边框颜色

	// 焦点变化时改变边框样式
	agMenu.SetFocusFunc(func() {
		agMenu.SetBorderColor(tcell.ColorYellow)
		agMenu.SetBorderAttributes(tcell.AttrBold) // 聚焦时黄色双线
	})
	agMenu.SetBlurFunc(func() {
		agMenu.SetBorderColor(tcell.ColorWhite)
		agMenu.SetBorderAttributes(0) // 失焦时白色单线
	})

	t.updateStatusBar("submenu")

	// 创建 AG 日志输出区域
	agLogView := tview.NewTextView().
		SetDynamicColors(true).
		SetRegions(true).
		SetWordWrap(true).
		SetChangedFunc(func() {
			t.app.Draw()
		})
	agLogView.SetBorder(true).SetTitle(" 日志输出 ")

	// 创建 AG 管理布局（上菜单，中日志，下状态栏）
	agFlex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(agMenu, 8, 0, true).      // 菜单固定 8 行
		AddItem(agLogView, 0, 1, false).  // 日志占剩余空间
		AddItem(t.statusBar, 1, 0, false) // 状态栏

	// 设置菜单选择处理
	agMenu.SetSelectedFunc(func(index int, name string, secondary string, shortcut rune) {
		switch shortcut {
		case 'c':
			t.agLog(agLogView, "\n[yellow]正在检查更新...[white]")
			go t.checkForUpdatesWithLog(agLogView)
		case 'v':
			t.agLog(agLogView, "\n[green]当前 AG 版本: %s[white]", t.agManager.GetCurrentVersion())
			t.agLog(agLogView, "[cyan]AG 安装路径: %s[white]", t.agManager.GetAGPath())
		case 's':
			t.showConfigWithLog(agLogView)
		case 'p':
			t.configureProxyWithLog(agLogView)
		case 'q':
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
		}
	})

	// 处理 ESC 和 Backspace
	agMenu.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyESC || event.Key() == tcell.KeyBackspace || event.Key() == tcell.KeyBackspace2 {
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
			return nil
		}
		return event
	})

	t.app.SetRoot(agFlex, true)
}

// agLog 在 AG 日志区域输出日志（线程安全）
func (t *TUI) agLog(logView *tview.TextView, format string, args ...interface{}) {
	t.logMutex.Lock()
	msg := fmt.Sprintf(format, args...)
	t.logMutex.Unlock()

	// 使用 goroutine 异步更新，避免在 UI 线程中死锁
	go func() {
		t.app.QueueUpdateDraw(func() {
			fmt.Fprintf(logView, "%s\n", msg)
			logView.ScrollToEnd()
		})
	}()
}

// checkForUpdatesWithLog 检查更新（带日志输出）
func (t *TUI) checkForUpdatesWithLog(logView *tview.TextView) {
	// 创建一个结果通道
	type Result struct {
		versions []agmanager.VersionInfo
		err      error
	}
	resultChan := make(chan Result, 1)

	// 异步获取版本列表
	go func() {
		// 临时重定向 printer 输出到 AG 日志区域
		oldWriter := t.printer.GetWriter()
		t.printer.SetWriter(&logWriter{logView: logView, app: t.app})

		versions, err := t.agManager.FetchChangelog()

		// 恢复原来的 writer
		t.printer.SetWriter(oldWriter)

		resultChan <- Result{versions: versions, err: err}
	}()

	// 等待结果（不阻塞主线程）
	go func() {
		result := <-resultChan

		if result.err != nil {
			t.agLog(logView, "[red]检查更新失败: %s[white]", result.err)
			return
		}

		if len(result.versions) == 0 {
			t.agLog(logView, "[red]未找到可用版本[white]")
			return
		}

		latest := result.versions[0]
		current := t.agManager.GetCurrentVersion()

		// 比较版本号
		if current == latest.Version {
			t.agLog(logView, "[green]已经是最新版本: v%s[white]", current)
			return
		}

		// 检查是否已安装
		if !t.agManager.IsInstalled() {
			t.agLog(logView, "[yellow]未检测到 AG 安装[white]")
		} else {
			t.agLog(logView, "[cyan]发现新版本: v%s (当前: v%s)[white]", latest.Version, current)
		}

		t.agLog(logView, "[yellow]更新内容:[white]")
		for _, change := range latest.Changes {
			t.agLog(logView, "  - %s", change)
		}

		// 显示确认对话框
		t.app.QueueUpdateDraw(func() {
			t.showUpdateConfirmDialogWithLog(latest, logView)
		})
	}()
}

// logWriter 用于重定向 printer 输出到 AG 日志区域
type logWriter struct {
	logView *tview.TextView
	app     *tview.Application
}

func (w *logWriter) Write(p []byte) (n int, err error) {
	// 使用 goroutine + QueueUpdateDraw 异步更新，避免阻塞
	go func() {
		w.app.QueueUpdateDraw(func() {
			fmt.Fprintf(w.logView, "%s", string(p))
			w.logView.ScrollToEnd()
		})
	}()
	return len(p), nil
}

// showUpdateConfirmDialogWithLog 显示更新确认对话框（带日志输出）
func (t *TUI) showUpdateConfirmDialogWithLog(latest agmanager.VersionInfo, logView *tview.TextView) {
	modal := tview.NewModal().
		SetText(fmt.Sprintf("发现新版本 v%s\n是否立即更新？", latest.Version)).
		AddButtons([]string{"更新", "取消"}).
		SetDoneFunc(func(buttonIndex int, buttonLabel string) {
			if buttonLabel == "更新" {
				t.performUpdateWithLog(latest, logView)
			}
		})

	t.app.SetRoot(modal, false)
}

// performUpdateWithLog 执行更新（带日志输出）
func (t *TUI) performUpdateWithLog(latest agmanager.VersionInfo, logView *tview.TextView) {
	// 重新创建 AG 管理界面
	agMenu := tview.NewList().
		AddItem("检查更新", "", 'c', nil).
		AddItem("查看当前版本", "", 'v', nil).
		AddItem("查看配置", "", 's', nil).
		AddItem("配置代理", "", 'p', nil).
		AddItem("返回", "", 'q', nil)
	agMenu.ShowSecondaryText(false)
	agMenu.SetBorder(true)
	agMenu.SetTitle(" AG 管理 ")
	agMenu.SetMainTextColor(tcell.ColorWhite)
	agMenu.SetSelectedTextColor(tcell.ColorYellow)
	agMenu.SetSelectedBackgroundColor(tcell.ColorDarkCyan)
	agMenu.SetBorderColor(tcell.ColorYellow) // 初始边框颜色

	// 焦点变化时改变边框样式
	agMenu.SetFocusFunc(func() {
		agMenu.SetBorderColor(tcell.ColorYellow)
		agMenu.SetBorderAttributes(tcell.AttrBold) // 聚焦时黄色双线
	})
	agMenu.SetBlurFunc(func() {
		agMenu.SetBorderColor(tcell.ColorWhite)
		agMenu.SetBorderAttributes(0) // 失焦时白色单线
	})

	t.agLog(logView, "\n[yellow]开始下载更新...[white]")

	// 创建进度条视图
	progressBar := tview.NewTextView().
		SetDynamicColors(true).
		SetTextAlign(tview.AlignCenter).
		SetText("[cyan]░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░[white] [yellow]  0%[white]")
	progressBar.SetBorder(true).SetTitle(" 下载进度 ")

	// 创建布局：上菜单，中进度条，下日志，最底下状态栏
	agFlex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(agMenu, 8, 0, true).
		AddItem(progressBar, 3, 0, false).
		AddItem(logView, 0, 1, false).
		AddItem(t.statusBar, 1, 0, false)

	t.app.SetRoot(agFlex, true)

	// 下载进度通道
	progressChan := make(chan int, 100)

	// 启动进度更新（实时更新进度条）
	go func() {
		for progress := range progressChan {
			// 构建进度条（30字符宽）
			bar := ""
			for i := 0; i < 30; i++ {
				if i < progress*30/100 {
					bar += "█"
				} else {
					bar += "░"
				}
			}

			progressText := fmt.Sprintf("[cyan]%s[white] [yellow]%3d%%[white]", bar, progress)

			// 使用 QueueUpdateDraw 确保 UI 更新
			t.app.QueueUpdateDraw(func() {
				progressBar.SetText(progressText)
			})
		}

		// 下载完成
		t.app.QueueUpdateDraw(func() {
			progressBar.SetText("[green]██████████████████████████████ 100%[white]")
			progressBar.SetTitle(" 下载完成 ")
		})
		t.agLog(logView, "[green]下载完成[white]")
	}()

	// 异步安装
	go func() {
		// 临时重定向 printer 输出到 AG 日志区域
		oldWriter := t.printer.GetWriter()
		t.printer.SetWriter(&logWriter{logView: logView, app: t.app})

		err := t.agManager.Install(latest.Version, progressChan)
		close(progressChan)

		// 恢复原来的 writer
		t.printer.SetWriter(oldWriter)

		if err != nil {
			t.agLog(logView, "[red]更新失败: %s[white]", err)
		} else {
			t.agLog(logView, "[green]更新成功: v%s[white]", latest.Version)
			t.agLog(logView, "[green]AG 已安装到: %s[white]", t.agManager.GetAGPath())
		}
	}()

	// 设置返回处理
	agMenu.SetSelectedFunc(func(index int, name string, secondary string, shortcut rune) {
		if shortcut == 'q' {
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
		}
	})

	agMenu.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyESC || event.Key() == tcell.KeyBackspace || event.Key() == tcell.KeyBackspace2 {
			t.currentMode = "" // 返回主界面
			t.restoreFocus()
			return nil
		}
		return event
	})
}

// configureProxyWithLog 配置代理（带日志输出）
func (t *TUI) configureProxyWithLog(logView *tview.TextView) {
	t.agLog(logView, "\n[cyan]=== 配置代理 ===[white]")
	t.agLog(logView, "[white]当前代理: %s", t.config.HTTPProxy)

	// 创建表单
	form := tview.NewForm()
	form.AddInputField("代理地址:", t.config.HTTPProxy, 40, nil, nil)
	form.AddButton("确认", func() {
		proxyAddr := form.GetFormItem(0).(*tview.InputField).GetText()
		t.config.HTTPProxy = proxyAddr
		t.agManager.SetHTTPProxy(proxyAddr)

		if err := t.configMgr.Save(t.config); err != nil {
			t.agLog(logView, "[red]保存配置失败: %s[white]", err)
		} else {
			if proxyAddr != "" {
				t.agLog(logView, "[green]代理已配置: %s[white]", proxyAddr)
			} else {
				t.agLog(logView, "[green]代理已清除[white]")
			}
		}

		// 返回 AG 管理界面
		t.showAGManager()
	})
	form.AddButton("取消", func() {
		t.agLog(logView, "[yellow]已取消配置[white]")
		// 返回 AG 管理界面
		t.showAGManager()
	})
	form.SetBorder(true).SetTitle(" 配置代理 ")

	// 创建布局：表单在上，日志在中，状态栏在下
	flex := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(form, 7, 0, true).
		AddItem(logView, 0, 1, false).
		AddItem(t.statusBar, 1, 0, false)

	// 处理 ESC 键 - 直接取消
	flex.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		if event.Key() == tcell.KeyESC {
			t.agLog(logView, "[yellow]已取消配置[white]")
			t.showAGManager()
			return nil
		}
		return event
	})

	t.app.SetRoot(flex, true)
}

// showHelp 显示帮助
func (t *TUI) showHelp() {
	t.log("\n[cyan]=== 帮助信息 ===[white]")
	t.log("[1] 运行脚本 - 运行 Lua 或 JavaScript 脚本")
	t.log("[2] 停止脚本 - 停止正在运行的脚本")
	t.log("[3] 启动项目 - 启动 AutoGo 项目")
	t.log("[4] 停止项目 - 停止正在运行的项目")
	t.log("[5] 编译项目 - 编译项目")
	t.log("[6] 部署项目 - 上传项目到设备")
	t.log("[7] 设备管理 - 管理设备连接")
	t.log("[8] AG 更新 - 查看 AG 版本并更新")
	t.log("[h] 帮助 - 显示帮助信息")
	t.log("[q] 退出 - 退出程序")
	t.log("")
	t.log("[cyan]快捷键说明:[white]")
	t.log("数字键 1-8: 跳转到对应菜单项")
	t.log("Ctrl+数字 1-8: 快速执行对应菜单项")
	t.log("Ctrl+Q: 退出程序")
	t.log("回车: 执行当前选中的菜单项")
}

// log 记录日志（线程安全）
func (t *TUI) log(format string, args ...interface{}) {
	t.logMutex.Lock()
	msg := fmt.Sprintf(format, args...)
	t.logMutex.Unlock()

	// 使用 QueueUpdate 异步更新，不阻塞调用者
	go func() {
		t.app.QueueUpdateDraw(func() {
			fmt.Fprintf(t.logView, "%s\n", msg)
			t.logView.ScrollToEnd()
		})
	}()
}

// projectLog 记录项目日志（线程安全）
func (t *TUI) projectLog(format string, args ...interface{}) {
	t.logMutex.Lock()
	msg := fmt.Sprintf(format, args...)
	t.logMutex.Unlock()

	// 使用 QueueUpdate 异步更新，不阻塞调用者
	go func() {
		t.app.QueueUpdateDraw(func() {
			fmt.Fprintf(t.projectLogView, "%s", msg)
			t.projectLogView.ScrollToEnd()
		})
	}()
}

// updateStatus 更新状态栏（线程安全）
func (t *TUI) updateStatus(text string) {
	go func() {
		t.app.QueueUpdateDraw(func() {
			t.statusBar.SetText(text)
		})
	}()
}

// exitApp 退出应用（输出 bye 后延迟退出）
func (t *TUI) exitApp() {
	t.log("[cyan]正在退出...[white]")

	// 停止设备上的项目（TUI 用户可见的日志）
	t.printer.Verbose("停止设备上的项目...")
	t.projectMgr.AGStop()

	t.log("[green]Bye![white]")
	go func() {
		time.Sleep(500 * time.Millisecond)
		t.app.Stop()
	}()
}

// toggleProjectLog 切换项目日志显示
func (t *TUI) toggleProjectLog() {
	t.showProjectLog = !t.showProjectLog

	// 清空 logFlex 并重新构建
	t.logFlex.Clear()

	if t.showProjectLog {
		// 显示两个日志区域
		t.logFlex.SetDirection(tview.FlexColumn).
			AddItem(t.logView, 0, 1, false).
			AddItem(t.projectLogView, 0, 1, false)
		t.log("[cyan]项目日志已显示[white]")
	} else {
		// 只显示调试器日志
		t.logFlex.SetDirection(tview.FlexColumn).
			AddItem(t.logView, 0, 1, false)
		t.log("[cyan]项目日志已隐藏[white]")
	}

	// 强制刷新整个屏幕
	t.app.Draw()
}

// toggleMouse 切换鼠标模式
func (t *TUI) toggleMouse() {
	t.mouseEnabled = !t.mouseEnabled
	if t.mouseEnabled {
		t.app.EnableMouse(true) // 启用 tview 鼠标捕获
		t.log("[green]鼠标模式已启用[white]")
		t.updateStatusBar("menu")
	} else {
		t.app.EnableMouse(false) // 禁用 tview 鼠标捕获，让终端原生处理
		t.log("[yellow]鼠标模式已禁用 - 可以使用鼠标选择复制内容[white]")
		t.updateStatusBar("menu")
	}
}

// initProject 初始化项目
func (t *TUI) initProject() {
	t.currentMode = "init"
	t.log("\n[cyan]=== 项目初始化 ===[white]")

	// 获取当前工作目录
	currentDir, err := os.Getwd()
	if err != nil {
		t.log("[red]获取当前目录失败: %s[white]", err)
		t.restoreFocus()
		return
	}

	t.log("[cyan]当前目录: %s[white]", currentDir)

	// 创建表单
	form := tview.NewForm()
	form.SetBorder(true).SetTitle(" 项目初始化 ")

	var moduleName string
	var targetPlatform string

	// 获取默认 module 名称（使用当前目录名）
	defaultModuleName := filepath.Base(currentDir)

	// 平台选项
	platforms := []string{"android", "ios"}
	currentPlatform := 0

	form.AddInputField("Module 名称:", defaultModuleName, 40, nil, func(text string) {
		moduleName = text
	}).
		AddDropDown("目标平台:", platforms, currentPlatform, func(option string, index int) {
			targetPlatform = option
		}).
		AddButton("初始化", func() {
			if strings.TrimSpace(moduleName) == "" {
				moduleName = defaultModuleName
			}
			if moduleName == "" {
				t.log("[red]错误: Module 名称不能为空[white]")
				return
			}

			t.log("[yellow]开始初始化项目...[white]")
			t.log("[cyan]Module: %s[white]", moduleName)
			t.log("[cyan]目标平台: %s[white]", targetPlatform)

			// 立即回到主界面以显示日志
			t.restoreFocus()

			// 异步执行初始化
			go t.doInitProject(currentDir, moduleName, targetPlatform)
		}).
		AddButton("取消", func() {
			t.log("[yellow]已取消初始化[white]")
			t.restoreFocus()
		})

	t.app.SetRoot(form, true)
}

// doInitProject 执行项目初始化
func (t *TUI) doInitProject(projectPath, moduleName, target string) {
	// 1. 检查目录是否为空或是否已有项目
	files, err := os.ReadDir(projectPath)
	if err != nil {
		t.log("[red]读取目录失败: %s[white]", err)
		t.restoreFocus()
		return
	}

	// 检查是否存在关键文件
	for _, f := range files {
		name := f.Name()
		if name == "go.mod" || name == "main.go" || name == "AutoGo" {
			t.log("[yellow]警告: 当前目录已存在项目文件[white]")
			// 不阻止，继续执行
		}
	}

	// 2. 调用 ag init 初始化
	t.log("[cyan]执行 ag init -t %s...[white]", target)
	if err := t.projectMgr.Init(projectPath, target); err != nil {
		t.log("[red]AG 初始化失败: %s[white]", err)
		t.restoreFocus()
		return
	}
	t.log("[green]AG 初始化完成[white]")

	// 3. 替换 go.mod 中的 module 名称
	goModPath := filepath.Join(projectPath, "go.mod")
	if err := t.updateGoModModule(goModPath, moduleName); err != nil {
		t.log("[red]更新 go.mod 失败: %s[white]", err)
		t.log("[yellow]请手动修改 go.mod 中的 module 名称[white]")
	} else {
		t.log("[green]go.mod module 已更新为: %s[white]", moduleName)
	}

	// 4. 设置 autogo_scriptengine
	if err := t.setupAutoGoScriptEngine(projectPath); err != nil {
		t.log("[red]设置 autogo_scriptengine 失败: %s[white]", err)
		t.log("[yellow]请手动设置 autogo_scriptengine[white]")
	} else {
		t.log("[green]autogo_scriptengine 设置完成[white]")
	}

	t.log("[green]项目初始化完成![white]")
}

// updateGoModModule 更新 go.mod 中的 module 名称
func (t *TUI) updateGoModModule(goModPath, moduleName string) error {
	// 读取 go.mod 文件
	file, err := os.Open(goModPath)
	if err != nil {
		return fmt.Errorf("打开 go.mod 失败: %w", err)
	}
	defer file.Close()

	// 读取所有行
	var lines []string
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := scanner.Text()
		// 替换 module 行
		if strings.HasPrefix(line, "module ") {
			line = "module " + moduleName
		}
		lines = append(lines, line)
	}

	if err := scanner.Err(); err != nil {
		return fmt.Errorf("读取 go.mod 失败: %w", err)
	}

	// 写回文件
	output, err := os.Create(goModPath)
	if err != nil {
		return fmt.Errorf("创建 go.mod 失败: %w", err)
	}
	defer output.Close()

	writer := bufio.NewWriter(output)
	for _, line := range lines {
		fmt.Fprintln(writer, line)
	}

	return writer.Flush()
}

// setupAutoGoScriptEngine 设置 autogo_scriptengine
func (t *TUI) setupAutoGoScriptEngine(projectPath string) error {
	enginePath := filepath.Join(projectPath, "AutoGoScriptEngine")

	// 1. 删除现有的 autogo_scriptengine 目录（如果存在）
	if _, err := os.Stat(enginePath); err == nil {
		t.log("[yellow]删除现有的 AutoGoScriptEngine 目录...[white]")
		if err := os.RemoveAll(enginePath); err != nil {
			return fmt.Errorf("删除 AutoGoScriptEngine 失败: %w", err)
		}
	}

	// 2. 克隆仓库
	t.log("[cyan]克隆 autogo_scriptengine 仓库...[white]")
	t.log("[yellow]这可能需要几分钟，请耐心等待...[white]")
	cloneCmd := exec.Command("git", "clone", "--quiet", "https://github.com/ZingYao/autogo_scriptengine.git", enginePath)
	// 静默所有输出
	cloneCmd.Stdout = nil
	cloneCmd.Stderr = nil
	if err := cloneCmd.Run(); err != nil {
		return fmt.Errorf("克隆仓库失败: %w", err)
	}
	t.log("[green]克隆完成[white]")

	// 3. 清理不需要的文件，只保留 common、js_engine、lua_engine 和 go.mod
	t.log("[cyan]清理不必要的文件...[white]")
	keepDirs := map[string]bool{
		"common":     true,
		"js_engine":  true,
		"lua_engine": true,
		"go.mod":     true,
	}

	entries, err := os.ReadDir(enginePath)
	if err != nil {
		return fmt.Errorf("读取 AutoGoScriptEngine 目录失败: %w", err)
	}

	for _, entry := range entries {
		name := entry.Name()
		if !keepDirs[name] {
			fullPath := filepath.Join(enginePath, name)
			if err := os.RemoveAll(fullPath); err != nil {
				t.log("[yellow]警告: 无法删除 %s: %s[white]", name, err)
			}
		}
	}

	// 4. 修改 AutoGoScriptEngine/go.mod，添加 replace 指令
	engineGoMod := filepath.Join(enginePath, "go.mod")
	t.log("[cyan]修改 AutoGoScriptEngine/go.mod...[white]")
	if err := t.addReplaceToGoMod(engineGoMod, "AutoGo", "../AutoGo"); err != nil {
		return fmt.Errorf("修改 AutoGoScriptEngine/go.mod 失败: %w", err)
	}

	// 5. 修改项目根目录 go.mod，添加 require 和 replace
	projectGoMod := filepath.Join(projectPath, "go.mod")
	t.log("[cyan]修改项目 go.mod...[white]")
	if err := t.addRequireAndReplace(projectGoMod, "github.com/ZingYao/autogo_scriptengine", "./AutoGoScriptEngine"); err != nil {
		return fmt.Errorf("修改项目 go.mod 失败: %w", err)
	}

	return nil
}

// addReplaceToGoMod 在 go.mod 末尾添加 replace 指令
func (t *TUI) addReplaceToGoMod(goModPath, module, replacePath string) error {
	// 读取现有内容
	content, err := os.ReadFile(goModPath)
	if err != nil {
		return err
	}

	// 检查是否已经存在该 replace 指令
	if strings.Contains(string(content), "replace "+module) {
		t.log("[yellow]replace 指令已存在，跳过[white]")
		return nil
	}

	// 添加 replace 指令
	file, err := os.OpenFile(goModPath, os.O_APPEND|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	defer file.Close()

	replaceLine := fmt.Sprintf("\nreplace %s => %s\n", module, replacePath)
	_, err = file.WriteString(replaceLine)
	return err
}

// addRequireAndReplace 在 go.mod 添加 require 和 replace
func (t *TUI) addRequireAndReplace(goModPath, module, replacePath string) error {
	// 读取现有内容
	content, err := os.ReadFile(goModPath)
	if err != nil {
		return err
	}

	lines := strings.Split(string(content), "\n")

	// 查找 require 块的位置
	var newLines []string
	requireAdded := false
	replaceAdded := false

	// 检查是否已存在
	for _, line := range lines {
		if strings.Contains(line, module) {
			requireAdded = true
		}
		if strings.Contains(line, "replace "+module) {
			replaceAdded = true
		}
	}

	// 如果都已存在，跳过
	if requireAdded && replaceAdded {
		t.log("[yellow]require 和 replace 已存在，跳过[white]")
		return nil
	}

	// 找到 go 版本行后面添加 require
	for i, line := range lines {
		newLines = append(newLines, line)
		// 在 go 版本行后添加 require（如果还没添加）
		if !requireAdded && strings.HasPrefix(line, "go ") && i+1 < len(lines) {
			// 检查下一行是否是 require
			if !strings.HasPrefix(lines[i+1], "require (") {
				newLines = append(newLines, "")
				newLines = append(newLines, "require (")
				newLines = append(newLines, fmt.Sprintf("\t%s v0.0.0", module))
				newLines = append(newLines, ")")
				requireAdded = true
			}
		}
	}

	// 如果 require 块已存在但模块不存在，需要添加到 require 块中
	if !requireAdded {
		// 在文件末尾添加 require
		newLines = append(newLines, "")
		newLines = append(newLines, "require (")
		newLines = append(newLines, fmt.Sprintf("\t%s v0.0.0", module))
		newLines = append(newLines, ")")
	}

	// 添加 replace 指令
	if !replaceAdded {
		newLines = append(newLines, "")
		newLines = append(newLines, fmt.Sprintf("replace %s => %s", module, replacePath))
	}

	// 写回文件
	output := strings.Join(newLines, "\n")
	return os.WriteFile(goModPath, []byte(output), 0644)
}
