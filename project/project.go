package project

import (
	"bufio"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

// Manager 项目管理器
type Manager struct {
	printer          *printer.Printer
	agPath           string
	cmd              *exec.Cmd  // 当前运行的 ag 进程
	logOutput        []string   // 日志输出缓存
	logMutex         sync.Mutex // 日志锁
	projectLogWriter io.Writer  // 项目日志输出目标
	agLogWriter      io.Writer  // AG 命令日志输出目标
}

// NewManager 创建项目管理器
func NewManager(p *printer.Printer) *Manager {
	return &Manager{
		printer:   p,
		logOutput: make([]string, 0),
	}
}

// SetProjectLogWriter 设置项目日志输出目标
func (m *Manager) SetProjectLogWriter(w io.Writer) {
	m.projectLogWriter = w
}

// SetAGLogWriter 设置 AG 命令日志输出目标
func (m *Manager) SetAGLogWriter(w io.Writer) {
	m.agLogWriter = w
}

// DetectAG 检测 ag 命令路径
func (m *Manager) DetectAG() error {
	m.printer.Verbose("正在检测 AG 路径...")

	// 1. 检查环境变量
	if agPath := os.Getenv("AUTOGO_AG_PATH"); agPath != "" {
		if _, err := exec.LookPath(agPath); err == nil {
			m.printer.Verbose("使用环境变量中的 AG 路径: %s", agPath)
			m.agPath = agPath
			return nil
		}
		m.printer.Warning("环境变量 AUTOGO_AG_PATH 指定的路径无效: %s", agPath)
	}

	// 2. 检查系统PATH
	if agPath, err := exec.LookPath("ag"); err == nil {
		m.printer.Verbose("检测到系统 AG: %s", agPath)
		m.agPath = agPath
		return nil
	}

	// 3. 检查系统默认路径
	var defaultPaths []string
	switch runtime.GOOS {
	case "windows":
		defaultPaths = []string{
			"C:\\Users\\Public\\ag.exe",
		}
	case "darwin":
		defaultPaths = []string{
			"/Users/Shared/ag",
			"/usr/local/bin/ag",
			"/usr/bin/ag",
		}
	default:
		homeDir, _ := os.UserHomeDir()
		defaultPaths = []string{
			filepath.Join(homeDir, ".autogo", "ag"),
			"/usr/local/bin/ag",
			"/usr/bin/ag",
		}
	}

	for _, path := range defaultPaths {
		if _, err := os.Stat(path); err == nil {
			m.printer.Verbose("找到 AG: %s", path)
			m.agPath = path
			return nil
		}
	}

	return fmt.Errorf("未找到 AG 命令，请在 AG 更新菜单中安装")
}

// Run 启动项目（阻塞，等待完成）
func (m *Manager) Run(projectPath, device string, debug bool) error {
	m.printer.Info("启动项目...")

	cmd, err := m.buildCommand("run", projectPath, device, debug)
	if err != nil {
		return err
	}

	m.printer.Info("执行命令: %s", strings.Join(cmd.Args, " "))

	// 创建输出捕获器
	cmd.Stdout = &writerToPrinter{printer: m.printer, prefix: "[AG]"}
	cmd.Stderr = &writerToPrinter{printer: m.printer, prefix: "[AG-ERR]"}
	cmd.Stdin = os.Stdin

	return cmd.Run()
}

// RunAsync 非阻塞启动项目
func (m *Manager) RunAsync(projectPath, device string, debug bool) error {
	m.printer.Verbose("非阻塞启动项目...")

	cmd, err := m.buildCommand("run", projectPath, device, debug)
	if err != nil {
		return err
	}

	m.printer.Verbose("执行命令: %s", strings.Join(cmd.Args, " "))

	// 创建管道读取输出
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return fmt.Errorf("创建 stdout 管道失败: %v", err)
	}

	stderr, err := cmd.StderrPipe()
	if err != nil {
		return fmt.Errorf("创建 stderr 管道失败: %v", err)
	}

	// 启动进程
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("启动项目失败: %v", err)
	}

	// 保存进程引用
	m.cmd = cmd

	// 清空日志缓存
	m.logMutex.Lock()
	m.logOutput = make([]string, 0)
	m.logMutex.Unlock()

	// 异步读取输出，重定向到项目日志
	go m.readOutputToProjectLog(stdout, "stdout")
	go m.readOutputToProjectLog(stderr, "stderr")

	m.printer.Success("项目启动中 (PID: %d)", cmd.Process.Pid)
	return nil
}

// Stop 停止项目
func (m *Manager) Stop() error {
	if m.cmd == nil || m.cmd.Process == nil {
		m.printer.Warning("没有运行中的项目")
		return nil
	}

	m.printer.Info("正在停止项目 (PID: %d)...", m.cmd.Process.Pid)

	// 尝试优雅终止
	if err := m.cmd.Process.Signal(os.Interrupt); err != nil {
		m.printer.Warning("发送中断信号失败: %v，尝试强制终止", err)
		if err := m.cmd.Process.Kill(); err != nil {
			return fmt.Errorf("终止进程失败: %v", err)
		}
	}

	// 等待进程结束
	done := make(chan error, 1)
	go func() {
		done <- m.cmd.Wait()
	}()

	select {
	case err := <-done:
		if err != nil {
			m.printer.Debug("进程已结束: %v", err)
		}
	case <-time.After(5 * time.Second):
		m.printer.Warning("进程未响应，强制终止")
		m.cmd.Process.Kill()
	}

	m.cmd = nil
	m.printer.Success("项目已停止")
	return nil
}

// ReadLogs 读取项目日志
func (m *Manager) ReadLogs(limit int) []string {
	m.logMutex.Lock()
	defer m.logMutex.Unlock()

	if limit <= 0 || limit > len(m.logOutput) {
		limit = len(m.logOutput)
	}

	start := len(m.logOutput) - limit
	if start < 0 {
		start = 0
	}

	result := make([]string, limit)
	copy(result, m.logOutput[start:])
	return result
}

// IsRunning 检查项目是否正在运行
func (m *Manager) IsRunning() bool {
	if m.cmd == nil || m.cmd.Process == nil {
		m.printer.Debug("没有运行中的项目")
		return false
	}

	// 检查进程是否已经退出
	// ProcessState 在进程结束后才会有值
	if m.cmd.ProcessState != nil && m.cmd.ProcessState.Exited() {
		m.printer.Debug("进程已结束")
		m.cmd = nil
		return false
	}

	return true
}

// WaitForReady 等待项目就绪（通过轮询检测端口）
func (m *Manager) WaitForReady(ip, port string, maxWait int) bool {
	waitInterval := 2
	elapsed := 0

	for elapsed < maxWait {
		time.Sleep(time.Duration(waitInterval) * time.Second)
		elapsed += waitInterval

		// 检测端口是否可访问
		if m.checkPort(ip, port, 2*time.Second) {
			return true
		}

		// 检查进程是否还在运行
		if !m.IsRunning() {
			return false
		}
	}

	return false
}

// checkPort 检测端口是否可访问
func (m *Manager) checkPort(ip, port string, timeout time.Duration) bool {
	address := fmt.Sprintf("%s:%s", ip, port)
	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}

// buildCommand 构建 ag 命令
func (m *Manager) buildCommand(action, projectPath, device string, debug bool) (*exec.Cmd, error) {
	if m.agPath == "" {
		if err := m.DetectAG(); err != nil {
			return nil, err
		}
	}

	args := []string{action}

	// 添加设备参数
	if device != "" {
		args = append(args, "-s", device)
	}

	// 添加调试参数
	if debug {
		args = append(args, "-d")
	}

	cmd := exec.Command(m.agPath, args...)

	// 设置工作目录
	if projectPath != "" {
		cmd.Dir = projectPath
	}

	return cmd, nil
}

// readOutput 读取输出
func (m *Manager) readOutput(reader io.Reader, source string) {
	scanner := bufio.NewScanner(reader)
	for scanner.Scan() {
		line := scanner.Text()

		// 保存到日志缓存
		m.logMutex.Lock()
		m.logOutput = append(m.logOutput, line)
		// 只保留最近 1000 行
		if len(m.logOutput) > 1000 {
			m.logOutput = m.logOutput[len(m.logOutput)-1000:]
		}
		m.logMutex.Unlock()

		// 输出到项目日志（原始输出）
		if m.projectLogWriter != nil {
			fmt.Fprintln(m.projectLogWriter, line)
		}

		// 输出到调试器日志
		m.printer.Info("[AG] %s", line)
	}

	if err := scanner.Err(); err != nil {
		m.printer.Debug("读取 %s 失败: %v", source, err)
	}
}

// readOutputToProjectLog 读取输出并仅重定向到项目运行日志（不输出到调试器日志）
func (m *Manager) readOutputToProjectLog(reader io.Reader, source string) {
	scanner := bufio.NewScanner(reader)
	for scanner.Scan() {
		line := scanner.Text()

		// 保存到日志缓存
		m.logMutex.Lock()
		m.logOutput = append(m.logOutput, line)
		// 只保留最近 1000 行
		if len(m.logOutput) > 1000 {
			m.logOutput = m.logOutput[len(m.logOutput)-1000:]
		}
		m.logMutex.Unlock()

		// 仅输出到项目日志（原始输出）
		if m.projectLogWriter != nil {
			fmt.Fprintln(m.projectLogWriter, line)
		}

		// 使用 Verbose 级别，仅在 debug 模式下显示在调试器日志
		m.printer.Verbose("[AG-%s] %s", source, line)
	}

	if err := scanner.Err(); err != nil {
		m.printer.Debug("读取 %s 失败: %v", source, err)
	}
}

// Build 编译项目
func (m *Manager) Build(projectPath, target string, embed bool) error {
	m.printer.Info("编译项目 (目标: %s)...", target)

	if m.agPath == "" {
		if err := m.DetectAG(); err != nil {
			return err
		}
	}

	args := []string{"build", "-t", target}
	if embed {
		args = append(args, "-e")
	}

	cmd := exec.Command(m.agPath, args...)
	if projectPath != "" {
		cmd.Dir = projectPath
	}

	cmd.Stdout = &writerToPrinter{printer: m.printer, prefix: "[AG]"}
	cmd.Stderr = &writerToPrinter{printer: m.printer, prefix: "[AG-ERR]"}

	return cmd.Run()
}

// Deploy 部署项目
func (m *Manager) Deploy(projectPath, device string) error {
	m.printer.Info("部署项目...")

	if m.agPath == "" {
		if err := m.DetectAG(); err != nil {
			return err
		}
	}

	args := []string{"deploy"}
	if device != "" {
		args = append(args, "-s", device)
	}

	cmd := exec.Command(m.agPath, args...)
	if projectPath != "" {
		cmd.Dir = projectPath
	}

	cmd.Stdout = &writerToPrinter{printer: m.printer, prefix: "[AG]"}
	cmd.Stderr = &writerToPrinter{printer: m.printer, prefix: "[AG-ERR]"}

	return cmd.Run()
}

// Init 初始化项目
func (m *Manager) Init(projectPath, target string) error {
	m.printer.Info("初始化项目 (目标: %s)...", target)

	if m.agPath == "" {
		if err := m.DetectAG(); err != nil {
			return err
		}
	}

	args := []string{"init", "-t", target}

	cmd := exec.Command(m.agPath, args...)
	if projectPath != "" {
		cmd.Dir = projectPath
	}

	// 使用 agLogWriter 或默认的 writerToPrinter
	var stdout, stderr io.Writer
	if m.agLogWriter != nil {
		stdout = m.agLogWriter
		stderr = m.agLogWriter
	} else {
		stdout = &writerToPrinter{printer: m.printer, prefix: "[AG]"}
		stderr = &writerToPrinter{printer: m.printer, prefix: "[AG-ERR]"}
	}
	cmd.Stdout = stdout
	cmd.Stderr = stderr

	if err := cmd.Run(); err != nil {
		return err
	}

	// 释放模板文件到项目目录
	if err := m.releaseTemplateFiles(projectPath); err != nil {
		m.printer.Warning("释放模板文件失败: %v", err)
		// 不返回错误，因为初始化已经成功
	}

	return nil
}

// releaseTemplateFiles 释放模板文件到项目目录
func (m *Manager) releaseTemplateFiles(projectPath string) error {
	m.printer.Info("正在释放模板文件...")

	// 确定目标路径
	targetDir := projectPath
	if targetDir == "" {
		// 如果未指定项目路径，使用当前工作目录
		wd, err := os.Getwd()
		if err != nil {
			return fmt.Errorf("获取当前目录失败: %w", err)
		}
		targetDir = wd
	}

	// 1. 释放 scripts 目录
	scriptsDir := filepath.Join(targetDir, "scripts")
	if err := os.MkdirAll(scriptsDir, 0755); err != nil {
		return fmt.Errorf("创建 scripts 目录失败: %w", err)
	}

	// 读取嵌入的 scripts 目录
	err := fs.WalkDir(scriptsFS, "scripts", func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}

		// 跳过根目录
		if path == "scripts" {
			return nil
		}

		// 计算相对路径
		relPath := strings.TrimPrefix(path, "scripts/")
		targetPath := filepath.Join(scriptsDir, relPath)

		if d.IsDir() {
			return os.MkdirAll(targetPath, 0755)
		}

		// 读取嵌入的文件内容
		content, err := scriptsFS.ReadFile(path)
		if err != nil {
			return fmt.Errorf("读取嵌入文件 %s 失败: %w", path, err)
		}

		// 写入目标文件
		if err := os.WriteFile(targetPath, content, 0644); err != nil {
			return fmt.Errorf("写入文件 %s 失败: %w", targetPath, err)
		}

		m.printer.Verbose("已释放: %s", targetPath)
		return nil
	})

	if err != nil {
		return fmt.Errorf("释放 scripts 目录失败: %w", err)
	}

	// 2. 释放 main.go.code 为 main.go
	mainGoPath := filepath.Join(targetDir, "main.go")
	if err := os.WriteFile(mainGoPath, []byte(mainGoCode), 0644); err != nil {
		return fmt.Errorf("写入 main.go 失败: %w", err)
	}
	m.printer.Verbose("已释放: %s", mainGoPath)

	m.printer.Info("模板文件释放完成")
	return nil
}

// Connect 连接远程设备
func (m *Manager) Connect(address string) error {
	m.printer.Info("连接远程设备: %s", address)

	if m.agPath == "" {
		if err := m.DetectAG(); err != nil {
			return err
		}
	}

	cmd := exec.Command(m.agPath, "connect", "-s", address)
	cmd.Stdout = &writerToPrinter{printer: m.printer, prefix: "[AG]"}
	cmd.Stderr = &writerToPrinter{printer: m.printer, prefix: "[AG-ERR]"}

	return cmd.Run()
}

// Version 显示版本号
func (m *Manager) Version() error {
	if m.agPath == "" {
		if err := m.DetectAG(); err != nil {
			return err
		}
	}

	cmd := exec.Command(m.agPath, "version")
	cmd.Stdout = &writerToPrinter{printer: m.printer, prefix: "[AG]"}
	cmd.Stderr = &writerToPrinter{printer: m.printer, prefix: "[AG-ERR]"}

	return cmd.Run()
}

// AGStop 执行 ag stop 停止设备上的项目
func (m *Manager) AGStop() error {
	m.printer.Verbose("停止设备上的项目...")

	if m.agPath == "" {
		if err := m.DetectAG(); err != nil {
			m.printer.Verbose("未检测到 AG，跳过停止操作")
			return nil // AG 未安装不算错误
		}
	}

	cmd := exec.Command(m.agPath, "stop")
	cmd.Stdout = &writerToPrinter{printer: m.printer, prefix: "[AG]"}
	cmd.Stderr = &writerToPrinter{printer: m.printer, prefix: "[AG-ERR]"}

	if err := cmd.Run(); err != nil {
		m.printer.Verbose("AG stop 执行失败: %v", err)
		return err
	}

	m.printer.Verbose("已停止设备上的项目")
	return nil
}

// writerToPrinter 将输出重定向到 printer
type writerToPrinter struct {
	printer *printer.Printer
	prefix  string
}

func (w *writerToPrinter) Write(p []byte) (n int, err error) {
	lines := strings.Split(string(p), "\n")
	for _, line := range lines {
		if line != "" {
			w.printer.Info("%s %s", w.prefix, line)
		}
	}
	return len(p), nil
}
