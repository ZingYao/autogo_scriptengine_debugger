package agmanager

import (
	"fmt"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"

	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

// VersionListView 版本列表视图
type VersionListView struct {
	*tview.Flex
	app           *tview.Application
	manager       *AGManager
	printer       *printer.Printer
	list          *tview.List
	versions      []VersionInfo
	onBack        func()
	onToggleMouse func()
	onExit        func()
}

// NewVersionListView 创建版本列表视图
func NewVersionListView(app *tview.Application, manager *AGManager, p *printer.Printer) *VersionListView {
	v := &VersionListView{
		Flex:    tview.NewFlex(),
		app:     app,
		manager: manager,
		printer: p,
	}

	v.setupUI()
	return v
}

// setupUI 设置界面
func (v *VersionListView) setupUI() {
	v.SetDirection(tview.FlexRow)

	// 创建版本列表
	v.list = tview.NewList()
	v.list.SetBorder(true).SetTitle(" AG 版本列表 (按 u 更新到最新版本) ")
	v.list.ShowSecondaryText(false)

	// 添加返回按钮
	v.list.AddItem("返回主菜单", "", 'q', nil)

	// 设置键盘事件
	v.list.SetInputCapture(func(event *tcell.EventKey) *tcell.EventKey {
		// F9 键切换鼠标模式 - 全局快捷键
		if event.Key() == tcell.KeyF9 {
			if v.onToggleMouse != nil {
				v.onToggleMouse()
			}
			return nil
		}

		// Ctrl+Q 直接退出 - 全局快捷键
		if event.Modifiers() == tcell.ModCtrl && (event.Rune() == 'q' || event.Rune() == 'Q') {
			if v.onExit != nil {
				v.onExit()
			}
			return nil
		}

		if event.Rune() == 'u' || event.Rune() == 'U' {
			v.updateToLatest()
			return nil
		}
		return event
	})

	// 添加到布局
	v.AddItem(v.list, 0, 1, true)

	// 加载版本信息
	go v.loadVersions()
}

// loadVersions 加载版本信息
func (v *VersionListView) loadVersions() {
	versions, err := v.manager.FetchChangelog()
	if err != nil {
		v.printer.Error("加载版本信息失败: %s", err)
		return
	}

	v.versions = versions

	// 更新 UI
	v.app.QueueUpdateDraw(func() {
		currentVersion := v.manager.GetCurrentVersion()

		for i, ver := range versions {
			title := fmt.Sprintf("v%s (%s)", ver.Version, ver.Date)
			if ver.Version == currentVersion {
				title = "[green]v%s (%s) [当前版本][white]"
				title = fmt.Sprintf(title, ver.Version, ver.Date)
			}

			// 添加版本项
			shortcut := rune('a' + i)
			if shortcut > 'z' {
				shortcut = 0
			}

			v.list.InsertItem(i, title, "", shortcut, nil)
		}
	})
}

// updateToLatest 更新到最新版本
func (v *VersionListView) updateToLatest() {
	if len(v.versions) == 0 {
		v.printer.Warning("未获取到版本信息")
		return
	}

	latest := v.versions[0]
	current := v.manager.GetCurrentVersion()

	if latest.Version == current {
		v.printer.Success("已经是最新版本: v%s", current)
		return
	}

	// 创建进度条
	progressBar := tview.NewTextView().
		SetDynamicColors(true).
		SetTextAlign(tview.AlignCenter)
	progressBar.SetBorder(true).SetTitle(" 下载进度 ")

	// 创建模态框
	modal := tview.NewFlex().
		SetDirection(tview.FlexRow).
		AddItem(nil, 0, 1, false).
		AddItem(progressBar, 3, 0, true).
		AddItem(nil, 0, 1, false)

	// 设置模态框
	flex := tview.NewFlex().
		SetDirection(tview.FlexColumn).
		AddItem(nil, 0, 1, false).
		AddItem(modal, 0, 1, true).
		AddItem(nil, 0, 1, false)

	v.app.SetRoot(flex, true)

	// 下载进度通道
	progressChan := make(chan int, 100)

	// 启动进度更新协程
	go func() {
		for progress := range progressChan {
			v.app.QueueUpdateDraw(func() {
				bar := ""
				for i := 0; i < 50; i++ {
					if i < progress/2 {
						bar += "█"
					} else {
						bar += "░"
					}
				}
				progressBar.SetText(fmt.Sprintf("[yellow]%s[white] %d%%", bar, progress))
			})
		}
	}()

	// 安装
	go func() {
		err := v.manager.Install(latest.Version, progressChan)
		close(progressChan)

		v.app.QueueUpdateDraw(func() {
			if err != nil {
				v.printer.Error("更新失败: %s", err)
			} else {
				v.printer.Success("更新成功: v%s", latest.Version)
			}

			// 返回版本列表
			if v.onBack != nil {
				v.onBack()
			}
		})
	}()
}

// SetOnBack 设置返回回调
func (v *VersionListView) SetOnBack(callback func()) {
	v.onBack = callback
}

// SetOnToggleMouse 设置切换鼠标模式回调
func (v *VersionListView) SetOnToggleMouse(callback func()) {
	v.onToggleMouse = callback
}

// SetOnExit 设置退出回调
func (v *VersionListView) SetOnExit(callback func()) {
	v.onExit = callback
}
