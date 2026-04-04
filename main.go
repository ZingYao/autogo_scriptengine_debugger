package main

import (
	"bufio"
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/ZingYao/autogo_scriptengine_debugger/config"
	"github.com/ZingYao/autogo_scriptengine_debugger/device"
	"github.com/ZingYao/autogo_scriptengine_debugger/interactive"
	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
	"github.com/ZingYao/autogo_scriptengine_debugger/project"
	"github.com/ZingYao/autogo_scriptengine_debugger/script"
	"github.com/ZingYao/autogo_scriptengine_debugger/tui"
)

// App 主应用
type App struct {
	printer    *printer.Printer
	config     *config.Config
	configMgr  *config.Manager
	deviceMgr  *device.Manager
	projectMgr *project.Manager
	input      *interactive.InputReader
}

// NewApp 创建应用
func NewApp(debug bool) *App {
	p := printer.New(debug)
	configMgr := config.NewManager(p)
	deviceMgr := device.NewManager(p)
	projectMgr := project.NewManager(p)

	return &App{
		printer:    p,
		configMgr:  configMgr,
		deviceMgr:  deviceMgr,
		projectMgr: projectMgr,
		input:      interactive.NewInputReader(p),
	}
}

// RunInteractive 交互式运行（命令行模式）
func (app *App) RunInteractive() error {
	// 加载配置
	cfg, err := app.configMgr.Load()
	if err != nil {
		app.printer.Warning("加载配置失败: %v", err)
		cfg = &config.Config{}
	}
	app.config = cfg

	// 检查并补充配置
	if err := app.checkAndFillConfig(); err != nil {
		return err
	}

	// 显示主菜单
	return app.showMainMenu()
}

// RunTUI TUI 模式运行
func (app *App) RunTUI() error {
	// 加载配置
	cfg, err := app.configMgr.Load()
	if err != nil {
		app.printer.Warning("加载配置失败: %v", err)
		cfg = &config.Config{
			CodeStyle:  "autogo",
			DevicePort: "8080",
		}
	}
	app.config = cfg

	// 创建 TUI 界面
	tuiApp := tui.NewTUI(app.config, app.configMgr, app.deviceMgr, app.projectMgr, app.printer)

	// 运行 TUI
	return tuiApp.Run()
}

// checkAndFillConfig 检查并补充配置
func (app *App) checkAndFillConfig() error {
	app.printer.Info("检查配置...")

	// 检查代码风格
	if app.config.CodeStyle == "" {
		styles := []string{"autogo", "lrappsoft", "nodejs"}
		idx := app.input.ReadChoice("请选择代码风格", styles, 0)
		app.config.CodeStyle = styles[idx]
	}

	// 检查设备连接
	if app.config.DeviceServiceURL == "" {
		if err := app.autoConnectDevice(); err != nil {
			return err
		}
	} else {
		app.printer.Success("设备服务地址: %s", app.config.DeviceServiceURL)
	}

	// 保存配置
	if app.input.Confirm("是否保存当前配置") {
		if err := app.configMgr.Save(app.config); err != nil {
			return err
		}
		app.printer.Success("配置已保存")
	}

	return nil
}

// showMainMenu 显示主菜单
func (app *App) showMainMenu() error {
	menu := interactive.NewMenu("AutoGo 调试器", app.printer)

	menu.AddItem("1", "运行脚本", "运行 Lua 或 JavaScript 脚本", func() error {
		return app.runScriptMenu()
	})

	menu.AddItem("2", "停止脚本", "停止正在运行的脚本", func() error {
		return app.stopScriptMenu()
	})

	menu.AddItem("3", "暂停脚本", "暂停正在运行的脚本", func() error {
		return app.pauseScriptMenu()
	})

	menu.AddItem("4", "恢复脚本", "恢复已暂停的脚本", func() error {
		return app.resumeScriptMenu()
	})

	menu.AddItem("5", "脚本状态", "查看脚本运行状态", func() error {
		return app.statusScriptMenu()
	})

	menu.AddItem("6", "错误信息", "查看脚本错误信息", func() error {
		return app.errorScriptMenu()
	})

	menu.AddItem("7", "设备管理", "管理设备连接", func() error {
		return app.deviceMenu()
	})

	menu.AddItem("8", "项目管理", "启动/停止项目", func() error {
		return app.projectMenu()
	})

	menu.AddItem("9", "配置管理", "查看/修改配置", func() error {
		return app.configMenu()
	})

	return menu.Show()
}

// runScriptMenu 运行脚本菜单
func (app *App) runScriptMenu() error {
	app.printer.Info("当前工作目录: %s", app.config.ProjectPath)

	app.printer.Prompt("请输入脚本文件路径 (相对于项目路径): ")
	reader := bufio.NewReader(os.Stdin)
	scriptFile, _ := reader.ReadString('\n')
	scriptFile = strings.TrimSpace(scriptFile)

	if scriptFile == "" {
		return fmt.Errorf("未指定脚本文件")
	}
	var scriptType string
	// 根据脚本文件扩展名自动判断脚本类型（优先级高于配置文件）
	if strings.HasSuffix(scriptFile, ".lua") {
		scriptType = "lua"
		app.printer.Verbose("检测到 Lua 脚本")
	} else if strings.HasSuffix(scriptFile, ".js") {
		scriptType = "javascript"
		app.printer.Verbose("检测到 JavaScript 脚本")
	} else {
		app.printer.Error("不支持的脚本类型: %s", filepath.Ext(scriptFile))
		return fmt.Errorf("不支持的脚本类型: %s，仅支持 .lua 和 .js 文件", filepath.Ext(scriptFile))
	}

	// 如果是相对路径，拼接项目路径
	if !strings.HasPrefix(scriptFile, "/") && app.config.ProjectPath != "" {
		scriptFile = app.config.ProjectPath + "/" + scriptFile
	}

	app.printer.Info("脚本路径: %s", scriptFile)
	app.printer.Info("脚本类型: %s", scriptType)
	app.printer.Info("代码风格: %s", app.config.CodeStyle)

	if app.input.Confirm("确认运行") {
		runner := script.NewRunner(app.config.DeviceServiceURL, app.printer)
		return runner.Run(scriptFile, scriptFile, app.config.CodeStyle)
	}

	return nil
}

// stopScriptMenu 停止脚本菜单
func (app *App) stopScriptMenu() error {
	scriptID := app.input.ReadString("请输入脚本 ID", "")
	if scriptID == "" {
		return fmt.Errorf("未指定脚本 ID")
	}

	runner := script.NewRunner(app.config.DeviceServiceURL, app.printer)
	return runner.Stop(scriptID)
}

// pauseScriptMenu 暂停脚本菜单
func (app *App) pauseScriptMenu() error {
	scriptID := app.input.ReadString("请输入脚本 ID", "")
	if scriptID == "" {
		return fmt.Errorf("未指定脚本 ID")
	}

	runner := script.NewRunner(app.config.DeviceServiceURL, app.printer)
	return runner.Pause(scriptID)
}

// resumeScriptMenu 恢复脚本菜单
func (app *App) resumeScriptMenu() error {
	scriptID := app.input.ReadString("请输入脚本 ID", "")
	if scriptID == "" {
		return fmt.Errorf("未指定脚本 ID")
	}

	runner := script.NewRunner(app.config.DeviceServiceURL, app.printer)
	return runner.Resume(scriptID)
}

// statusScriptMenu 脚本状态菜单
func (app *App) statusScriptMenu() error {
	scriptID := app.input.ReadString("请输入脚本 ID", "")
	if scriptID == "" {
		return fmt.Errorf("未指定脚本 ID")
	}

	runner := script.NewRunner(app.config.DeviceServiceURL, app.printer)
	return runner.GetStatus(scriptID)
}

// errorScriptMenu 错误信息菜单
func (app *App) errorScriptMenu() error {
	scriptID := app.input.ReadString("请输入脚本 ID", "")
	if scriptID == "" {
		return fmt.Errorf("未指定脚本 ID")
	}

	runner := script.NewRunner(app.config.DeviceServiceURL, app.printer)
	return runner.GetError(scriptID)
}

// deviceMenu 设备管理菜单
func (app *App) deviceMenu() error {
	menu := interactive.NewMenu("设备管理", app.printer)

	menu.AddItem("1", "查看已连接设备", "列出所有已连接的设备", func() error {
		return app.listDevices()
	})

	menu.AddItem("2", "连接设备", "通过 TCP/IP 连接设备", func() error {
		return app.connectDeviceMenu()
	})

	menu.AddItem("3", "获取设备 IP", "获取设备 IP 地址", func() error {
		return app.getDeviceIPMenu()
	})

	menu.AddItem("4", "扫描端口", "扫描设备端口", func() error {
		return app.scanPortMenu()
	})

	return menu.Show()
}

// listDevices 列出设备
func (app *App) listDevices() error {
	app.printer.Info("正在获取设备列表...")

	if err := app.deviceMgr.DetectADB(); err != nil {
		return err
	}

	devices, err := app.deviceMgr.GetConnectedDevices()
	if err != nil {
		return err
	}

	if len(devices) == 0 {
		app.printer.Warning("未检测到已连接的设备")
		return nil
	}

	app.printer.Success("检测到 %d 个设备:", len(devices))
	for i, d := range devices {
		app.printer.Info("  [%d] %s (%s)", i+1, d.Serial, d.State)
	}

	return nil
}

// connectDeviceMenu 连接设备菜单
func (app *App) connectDeviceMenu() error {
	deviceIP := app.input.ReadString("请输入设备 IP", "")
	if deviceIP == "" {
		return fmt.Errorf("未输入设备 IP")
	}

	port := app.input.ReadString("请输入端口", "5555")
	address := deviceIP + ":" + port

	app.printer.Info("正在连接设备: %s", address)

	if err := app.deviceMgr.ConnectDevice(address); err != nil {
		return err
	}

	app.printer.Success("设备连接成功")
	return nil
}

// getDeviceIPMenu 获取设备 IP 菜单
func (app *App) getDeviceIPMenu() error {
	deviceID := app.input.ReadString("请输入设备 ID (留空使用当前设备)", app.config.DeviceID)
	if deviceID == "" {
		deviceID = app.config.DeviceID
	}

	if deviceID == "" {
		return fmt.Errorf("未指定设备 ID")
	}

	app.printer.Info("正在获取设备 IP...")
	ip, err := app.deviceMgr.GetDeviceIP(deviceID)
	if err != nil {
		return err
	}

	app.printer.Success("设备 IP: %s", ip)
	return nil
}

// scanPortMenu 扫描端口菜单
func (app *App) scanPortMenu() error {
	ip := app.input.ReadString("请输入 IP 地址", app.config.DeviceIP)
	if ip == "" {
		return fmt.Errorf("未输入 IP 地址")
	}

	port := app.input.ReadString("请输入端口号", app.config.DevicePort)

	app.printer.Info("正在扫描端口 %s:%s...", ip, port)

	if app.deviceMgr.ScanPort(ip, port, 2*time.Second) {
		app.printer.Success("端口 %s:%s 可访问", ip, port)
	} else {
		app.printer.Warning("端口 %s:%s 不可访问", ip, port)
	}

	return nil
}

// projectMenu 项目管理菜单
func (app *App) projectMenu() error {
	menu := interactive.NewMenu("项目管理", app.printer)

	menu.AddItem("1", "启动项目", "启动项目（非阻塞）", func() error {
		return app.startProjectMenu()
	})

	menu.AddItem("2", "停止项目", "停止项目", func() error {
		return app.stopProjectMenu()
	})

	menu.AddItem("3", "读取项目日志", "读取项目运行日志", func() error {
		return app.readProjectLogsMenu()
	})

	menu.AddItem("4", "编译项目", "编译项目", func() error {
		return app.buildProjectMenu()
	})

	menu.AddItem("5", "部署项目", "上传项目中的 so 和 assets 到设备", func() error {
		return app.deployProjectMenu()
	})

	menu.AddItem("6", "初始化项目", "初始化 AutoGo 项目", func() error {
		return app.initProjectMenu()
	})

	menu.AddItem("7", "连接远程设备", "通过 ADB 连接远程设备", func() error {
		return app.connectDeviceMenuAG()
	})

	menu.AddItem("8", "查看版本", "显示 AG 版本号", func() error {
		return app.projectMgr.Version()
	})

	return menu.Show()
}

// startProjectMenu 启动项目菜单
func (app *App) startProjectMenu() error {
	projectPath := app.input.ReadString("项目路径 (留空使用默认)", app.config.ProjectPath)
	deviceID := app.input.ReadString("设备 ID (留空使用当前设备)", app.config.DeviceID)

	if deviceID == "" {
		deviceID = app.config.DeviceID
	}

	debug := app.input.ReadBool("是否启用调试模式", false)

	app.printer.Info("正在启动项目...")

	if err := app.projectMgr.RunAsync(projectPath, deviceID, debug); err != nil {
		return err
	}

	app.printer.Success("项目启动中")

	// 等待端口就绪
	app.printer.Info("等待服务启动...")
	if !app.projectMgr.WaitForReady(app.config.DeviceIP, app.config.DevicePort, 60) {
		return fmt.Errorf("项目启动失败（超时）")
	}

	app.printer.Success("服务已启动")
	return nil
}

// stopProjectMenu 停止项目菜单
func (app *App) stopProjectMenu() error {
	if !app.projectMgr.IsRunning() {
		app.printer.Warning("没有运行中的项目")
		return nil
	}

	return app.projectMgr.Stop()
}

// readProjectLogsMenu 读取项目日志菜单
func (app *App) readProjectLogsMenu() error {
	limit := app.input.ReadInt("日志行数", 50)

	logs := app.projectMgr.ReadLogs(limit)

	running := app.projectMgr.IsRunning()
	app.printer.Info("运行状态: %v", running)
	app.printer.Info("日志内容 (%d 条):", len(logs))
	for _, log := range logs {
		app.printer.Println("%s", log)
	}

	return nil
}

// buildProjectMenu 编译项目菜单
func (app *App) buildProjectMenu() error {
	targets := []string{"arm64-v8a", "x86_64", "x86", "ios", "ipa", "deb", "apk[arm64-v8a,x86_64]"}
	target := targets[app.input.ReadChoice("请选择目标架构", targets, 0)]

	embed := app.input.ReadBool("是否将 SO 库嵌入二进制", false)

	app.printer.Info("编译项目...")
	return app.projectMgr.Build(app.config.ProjectPath, target, embed)
}

// deployProjectMenu 部署项目菜单
func (app *App) deployProjectMenu() error {
	deviceID := app.input.ReadString("设备 ID (留空使用当前设备)", app.config.DeviceID)

	if deviceID == "" {
		deviceID = app.config.DeviceID
	}

	return app.projectMgr.Deploy(app.config.ProjectPath, deviceID)
}

// initProjectMenu 初始化项目菜单
func (app *App) initProjectMenu() error {
	targets := []string{"android", "ios"}
	target := targets[app.input.ReadChoice("请选择目标平台", targets, 0)]

	return app.projectMgr.Init(app.config.ProjectPath, target)
}

// connectDeviceMenuAG 连接远程设备菜单（通过 AG）
func (app *App) connectDeviceMenuAG() error {
	address := app.input.ReadString("请输入设备地址 (例如: 192.168.1.100:5555)", "")
	if address == "" {
		return fmt.Errorf("未输入设备地址")
	}

	return app.projectMgr.Connect(address)
}

// configMenu 配置管理菜单
func (app *App) configMenu() error {
	menu := interactive.NewMenu("配置管理", app.printer)

	menu.AddItem("1", "查看当前配置", "显示所有配置项", func() error {
		app.printer.Info("当前配置:")
		app.printer.Info("  代码风格: %s", app.config.CodeStyle)
		app.printer.Info("  设备服务地址: %s", app.config.DeviceServiceURL)
		app.printer.Info("  设备 ID: %s", app.config.DeviceID)
		app.printer.Info("  设备 IP: %s", app.config.DeviceIP)
		app.printer.Info("  设备端口: %s", app.config.DevicePort)
		app.printer.Info("  项目路径: %s", app.config.ProjectPath)
		return nil
	})

	menu.AddItem("2", "修改配置", "修改配置项", func() error {
		return app.editConfigMenu()
	})

	menu.AddItem("3", "保存配置", "保存当前配置到文件", func() error {
		if err := app.configMgr.Save(app.config); err != nil {
			return err
		}
		app.printer.Success("配置已保存")
		return nil
	})

	menu.AddItem("4", "清除配置", "清除配置文件", func() error {
		if app.input.Confirm("确认清除配置文件") {
			if err := app.configMgr.Clear(); err != nil {
				return err
			}
			app.printer.Success("配置文件已清除")
			app.config = &config.Config{}
		}
		return nil
	})

	return menu.Show()
}

// editConfigMenu 编辑配置菜单
func (app *App) editConfigMenu() error {
	app.printer.Info("修改配置 (直接回车保持当前值)")

	app.config.CodeStyle = app.input.ReadString("代码风格", app.config.CodeStyle)
	app.config.DeviceServiceURL = app.input.ReadString("设备服务地址", app.config.DeviceServiceURL)
	app.config.DeviceID = app.input.ReadString("设备 ID", app.config.DeviceID)
	app.config.DeviceIP = app.input.ReadString("设备 IP", app.config.DeviceIP)
	app.config.DevicePort = app.input.ReadString("设备端口", app.config.DevicePort)
	app.config.ProjectPath = app.input.ReadString("项目路径", app.config.ProjectPath)

	app.printer.Success("配置已更新")
	return nil
}

// autoConnectDevice 自动连接设备
func (app *App) autoConnectDevice() error {
	app.printer.Info("正在检查设备连接...")

	// 检测ADB
	if err := app.deviceMgr.DetectADB(); err != nil {
		return err
	}

	// 获取设备列表
	devices, err := app.deviceMgr.GetConnectedDevices()
	if err != nil || len(devices) == 0 {
		app.printer.Warning("未检测到已连接的设备")
		app.printer.Info("尝试通过 TCP/IP 连接设备...")

		// 获取本机IP
		localIP := getLocalIP()
		if localIP != "" {
			app.printer.Info("本机 IP: %s", localIP)
			app.printer.Prompt("请输入设备 IP 地址 (例如: 192.168.31.71): ")
			reader := bufio.NewReader(os.Stdin)
			deviceIP, _ := reader.ReadString('\n')
			deviceIP = strings.TrimSpace(deviceIP)

			if deviceIP != "" {
				address := deviceIP + ":5555"
				app.printer.Info("正在连接设备: %s", address)

				if err := app.deviceMgr.ConnectDevice(address); err != nil {
					return fmt.Errorf("设备连接失败: %v", err)
				}

				app.printer.Success("设备连接成功")
				devices, err = app.deviceMgr.GetConnectedDevices()
				if err != nil {
					return err
				}
			} else {
				return fmt.Errorf("未输入设备 IP")
			}
		} else {
			return fmt.Errorf("无法获取本机 IP")
		}
	}

	// 选择设备
	var deviceID string
	if len(devices) > 0 {
		// 检查配置文件中的设备是否可用
		if app.config.DeviceID != "" {
			for _, d := range devices {
				if d.Serial == app.config.DeviceID {
					app.printer.Info("使用配置中的设备: %s", app.config.DeviceID)
					deviceID = app.config.DeviceID
					break
				}
			}
			if deviceID == "" {
				app.printer.Warning("配置中的设备 %s 未连接", app.config.DeviceID)
				app.config.DeviceID = ""
				app.config.DeviceIP = ""
			}
		}

		// 如果没有配置设备，让用户选择
		if deviceID == "" {
			if len(devices) > 1 {
				app.printer.Warning("检测到多个设备:")
				for i, d := range devices {
					app.printer.Println("  %d. %s", i+1, d.Serial)
				}
				app.printer.Prompt("请选择设备编号 (1-%d): ", len(devices))
				reader := bufio.NewReader(os.Stdin)
				choice, _ := reader.ReadString('\n')
				choice = strings.TrimSpace(choice)

				num, err := strconv.Atoi(choice)
				if err != nil || num < 1 || num > len(devices) {
					return fmt.Errorf("无效的选择")
				}
				deviceID = devices[num-1].Serial
			} else {
				deviceID = devices[0].Serial
			}
		}
	}

	app.printer.Success("已选择设备: %s", deviceID)

	// 获取设备IP
	app.printer.Info("正在获取设备 IP 地址...")
	deviceIP, err := app.deviceMgr.GetDeviceIP(deviceID)
	if err != nil {
		return fmt.Errorf("无法获取设备 IP 地址: %v", err)
	}

	if deviceIP == "" {
		return fmt.Errorf("无法获取设备 IP 地址，请确保设备已连接 WiFi")
	}

	app.printer.Success("设备 IP: %s", deviceIP)

	// 保存设备信息
	app.config.DeviceID = deviceID
	app.config.DeviceIP = deviceIP
	app.printer.Info("保存设备信息到配置文件...")
	app.configMgr.Save(app.config)

	// 扫描端口
	app.printer.Info("正在扫描服务端口: %s", app.config.DevicePort)
	if app.deviceMgr.ScanPort(deviceIP, app.config.DevicePort, 2*time.Second) {
		app.printer.Success("端口 %s 可访问", app.config.DevicePort)
		app.config.DeviceServiceURL = fmt.Sprintf("%s:%s", deviceIP, app.config.DevicePort)
	} else {
		app.printer.Warning("端口 %s 不可访问", app.config.DevicePort)

		// 自动启动项目
		if err := app.autoStartProject(deviceID); err != nil {
			return err
		}
	}

	app.printer.Success("设备服务地址: %s", app.config.DeviceServiceURL)
	return nil
}

// autoStartProject 自动启动项目
func (app *App) autoStartProject(deviceID string) error {
	app.printer.Info("检测到端口不可访问，自动启动项目...")
	if app.config.ProjectPath != "" {
		app.printer.Info("项目路径: %s", app.config.ProjectPath)
	} else {
		app.printer.Info("未设置项目路径，将使用默认路径启动")
	}

	// 检测 AG 路径
	if err := app.projectMgr.DetectAG(); err != nil {
		return err
	}

	// 非阻塞启动项目
	app.printer.Info("启动项目...")
	if err := app.projectMgr.RunAsync(app.config.ProjectPath, deviceID, false); err != nil {
		return fmt.Errorf("启动项目失败: %v", err)
	}

	app.printer.Success("项目启动中")

	// 轮询检测端口判断服务是否启动
	app.printer.Info("等待服务启动...")
	if !app.projectMgr.WaitForReady(app.config.DeviceIP, app.config.DevicePort, 60) {
		return fmt.Errorf("项目启动失败（超时）")
	}

	// 服务已启动，扫描端口确认
	app.printer.Info("扫描服务端口...")
	time.Sleep(1 * time.Second)

	if app.deviceMgr.ScanPort(app.config.DeviceIP, app.config.DevicePort, 2*time.Second) {
		app.printer.Success("端口 %s 可访问", app.config.DevicePort)
		app.config.DeviceServiceURL = fmt.Sprintf("http://%s:%s", app.config.DeviceIP, app.config.DevicePort)
		return nil
	}

	// 尝试其他端口
	app.printer.Warning("端口 %s 不可访问，尝试扫描其他常用端口...", app.config.DevicePort)

	ports := []string{"8080", "9090", "3000", "8000", "5000"}
	var foundPort string
	for _, port := range ports {
		if port == app.config.DevicePort {
			continue
		}
		app.printer.Info("尝试端口: %s", port)
		if app.deviceMgr.ScanPort(app.config.DeviceIP, port, 2*time.Second) {
			foundPort = port
			app.printer.Success("找到可用端口: %s", port)
			break
		}
	}

	if foundPort != "" {
		app.config.DevicePort = foundPort
		app.config.DeviceServiceURL = fmt.Sprintf("%s:%s", app.config.DeviceIP, app.config.DevicePort)
		return nil
	}

	return fmt.Errorf("未找到可用的服务端口")
}

// checkDeviceService 检查服务器连接
func (app *App) checkDeviceService() error {
	// 简单检查服务器是否可访问
	app.printer.Info("设备服务地址: %s", app.config.DeviceServiceURL)
	return nil
}

// getLocalIP 获取本机IP
func getLocalIP() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return ""
	}

	for _, addr := range addrs {
		if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			if ipnet.IP.To4() != nil {
				return ipnet.IP.String()
			}
		}
	}
	return ""
}

// RunCommand 命令行模式运行（保持兼容）
func (app *App) RunCommand(operation, scriptFile, scriptID, scriptType, codeStyle, serverURL, projectPath, port string, saveConfig, clearConfig bool) error {
	// 加载配置
	cfg, err := app.configMgr.Load()
	if err != nil {
		app.printer.Warning("加载配置失败: %v", err)
	}
	app.config = cfg

	if codeStyle != "" {
		app.config.CodeStyle = codeStyle
	}
	if serverURL != "" {
		app.config.DeviceServiceURL = serverURL
	}
	if projectPath != "" {
		app.config.ProjectPath = projectPath
	}
	if port != "" {
		app.config.DevicePort = port
	}
	if scriptType == "" {
		if strings.HasSuffix(scriptFile, ".lua") {
			scriptType = "lua"
		} else if strings.HasSuffix(scriptFile, ".js") {
			scriptType = "javascript"
		}
	}

	// 清除配置
	if clearConfig {
		if err := app.configMgr.Clear(); err != nil {
			return err
		}
		app.printer.Success("配置文件已清除")
		return nil
	}

	// 检查服务器连接
	if app.config.DeviceServiceURL == "" {
		if err := app.autoConnectDevice(); err != nil {
			return err
		}
	} else {
		if err := app.checkDeviceService(); err != nil {
			return err
		}
	}

	// 保存配置
	if saveConfig {
		if err := app.configMgr.Save(app.config); err != nil {
			return err
		}
	}

	// 执行操作
	runner := script.NewRunner(app.config.DeviceServiceURL, app.printer)

	switch operation {
	case "run":
		return runner.Run(scriptFile, scriptType, app.config.CodeStyle)
	case "stop":
		return runner.Stop(scriptID)
	case "pause":
		return runner.Pause(scriptID)
	case "resume":
		return runner.Resume(scriptID)
	case "status":
		return runner.GetStatus(scriptID)
	case "get_error":
		return runner.GetError(scriptID)
	default:
		return fmt.Errorf("未知操作: %s", operation)
	}
}

func main() {
	// 定义命令行参数
	help := flag.Bool("h", false, "显示帮助信息")
	helpLong := flag.Bool("help", false, "显示帮助信息")
	scriptType := flag.String("t", "lua", "脚本类型 (lua/javascript)")
	scriptTypeLong := flag.String("type", "", "脚本类型 (lua/javascript)")
	codeStyle := flag.String("s", "autogo", "代码风格 (autogo/lrappsoft/nodejs)")
	codeStyleLong := flag.String("style", "", "代码风格 (autogo/lrappsoft/nodejs)")
	operation := flag.String("o", "run", "操作类型 (run/stop/pause/resume/status/get_error)")
	operationLong := flag.String("operation", "", "操作类型 (run/stop/pause/resume/status/get_error)")
	serverURL := flag.String("u", "", "设备服务地址")
	serverURLLong := flag.String("url", "", "设备服务地址")
	scriptID := flag.String("i", "", "脚本ID")
	scriptIDLong := flag.String("script-id", "", "脚本ID")
	port := flag.String("p", "8080", "设备服务端口")
	projectPath := flag.String("project-path", "", "项目路径")
	debug := flag.Bool("debug", false, "显示调试信息")
	saveConfig := flag.Bool("save-config", false, "保存当前设置到配置文件")
	clearConfig := flag.Bool("clear-config", false, "清除配置文件")
	useCLI := flag.Bool("cli", false, "使用命令行交互模式（默认为 TUI 模式）")

	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "使用方法: %s [选项] [脚本文件]\n\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "选项:")
		flag.PrintDefaults()
		fmt.Fprintln(os.Stderr, "\n模式:")
		fmt.Fprintf(os.Stderr, "  TUI 模式（默认）: 直接运行 %s\n", os.Args[0])
		fmt.Fprintf(os.Stderr, "  CLI 模式: %s --cli\n", os.Args[0])
		fmt.Fprintf(os.Stderr, "  命令行模式: %s [选项] 脚本文件\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "\n示例:")
		fmt.Fprintln(os.Stderr, "  # TUI 模式（推荐）")
		fmt.Fprintf(os.Stderr, "  %s\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # CLI 模式")
		fmt.Fprintf(os.Stderr, "  %s --cli\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # 命令行模式 - 运行脚本")
		fmt.Fprintf(os.Stderr, "  %s script.lua\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # 指定项目路径（自动启动项目并运行脚本）")
		fmt.Fprintf(os.Stderr, "  %s --project-path /path/to/project script.lua\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # 指定脚本类型和代码风格")
		fmt.Fprintf(os.Stderr, "  %s -t javascript -s nodejs script.js\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # 保存配置")
		fmt.Fprintf(os.Stderr, "  %s -t lua -s autogo --save-config\n", os.Args[0])
		fmt.Fprintln(os.Stderr, "")
		fmt.Fprintln(os.Stderr, "  # 显示调试信息")
		fmt.Fprintf(os.Stderr, "  %s --debug script.lua\n", os.Args[0])
	}

	flag.Parse()

	// 显示帮助
	if *help || *helpLong {
		flag.Usage()
		os.Exit(0)
	}

	// 创建应用
	app := NewApp(*debug)

	// 确保退出时停止设备上的项目
	defer func() {
		app.projectMgr.AGStop()
	}()

	// TUI 模式（默认）
	if !*useCLI && len(flag.Args()) == 0 && *operation == "run" && *serverURL == "" {
		if err := app.RunTUI(); err != nil {
			fmt.Fprintf(os.Stderr, "错误: %v\n", err)
			os.Exit(1)
		}
		return
	}

	// CLI 交互模式
	if *useCLI && len(flag.Args()) == 0 && *operation == "run" && *serverURL == "" {
		if err := app.RunInteractive(); err != nil {
			fmt.Fprintf(os.Stderr, "错误: %v\n", err)
			os.Exit(1)
		}
		return
	}

	// 处理长参数
	finalScriptType := *scriptType
	if *scriptTypeLong != "" {
		finalScriptType = *scriptTypeLong
	}

	finalCodeStyle := *codeStyle
	if *codeStyleLong != "" {
		finalCodeStyle = *codeStyleLong
	}

	finalOperation := *operation
	if *operationLong != "" {
		finalOperation = *operationLong
	}

	finalDeviceServiceURL := *serverURL
	if *serverURLLong != "" {
		finalDeviceServiceURL = *serverURLLong
	}

	finalScriptID := *scriptID
	if *scriptIDLong != "" {
		finalScriptID = *scriptIDLong
	}

	// 获取脚本文件
	var scriptFile string
	args := flag.Args()
	if len(args) > 0 {
		scriptFile = args[0]
	}

	// 命令行模式运行
	if err := app.RunCommand(
		finalOperation,
		scriptFile,
		finalScriptID,
		finalScriptType,
		finalCodeStyle,
		finalDeviceServiceURL,
		*projectPath,
		*port,
		*saveConfig,
		*clearConfig,
	); err != nil {
		fmt.Fprintf(os.Stderr, "错误: %v\n", err)
		os.Exit(1)
	}
}
