package config

import (
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"gopkg.in/yaml.v3"

	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

// DefaultDevicePort 默认设备端口
const DefaultDevicePort = "8080"

// GlobalConfig 全局配置（存放在用户目录）
type GlobalConfig struct {
	// HTTP 代理地址，例如: http://127.0.0.1:7890
	HTTPProxy string `yaml:"httpProxy"`
	// AG 可执行文件路径，留空则自动检测
	AGPath string `yaml:"agPath"`
	// ADB 可执行文件路径，留空则自动检测
	ADBPath string `yaml:"adbPath"`
}

// SecurityConfig 安全配置（混淆和加密相关）
type SecurityConfig struct {
	// 构建环境配置（用于生成最终构建代码）
	BuildObfuscate bool `yaml:"buildObfuscate"` // 构建时是否混淆代码
	BuildBytecode  bool `yaml:"buildBytecode"`  // 构建时是否编译字节码（仅 Lua）
	BuildEncrypt   bool `yaml:"buildEncrypt"`   // 构建时是否 AES 加密

	// 调试环境配置（用于调试时处理脚本）
	DebugObfuscate bool `yaml:"debugObfuscate"` // 调试时是否混淆代码
	DebugBytecode  bool `yaml:"debugBytecode"`  // 调试时是否编译字节码（仅 Lua）
	DebugEncrypt   bool `yaml:"debugEncrypt"`   // 调试时是否 AES 加密
	DebugAutoCleanup bool `yaml:"debugAutoCleanup"` // 调试时是否自动清理脚本目录

	// AES 加密密钥（Base64 编码）
	// 如果为空，将在首次加密时自动生成
	EncryptionKey string `yaml:"encryptionKey"`
}

// ScriptLoadMode 脚本加载模式
type ScriptLoadMode string

const (
	LoadModeHTTP   ScriptLoadMode = "http"   // HTTP 加载模式
	LoadModeSDCard ScriptLoadMode = "sdcard" // SDCard 路径加载模式
	LoadModeEmbed  ScriptLoadMode = "embed"  // Embed 打包模式
)

// BuildConfig 构建配置
type BuildConfig struct {
	// 脚本加载模式: http, sdcard, embed
	LoadMode ScriptLoadMode `yaml:"loadMode"`

	// HTTP 加载配置
	HTTPScriptURL string `yaml:"httpScriptUrl"` // 脚本下载 URL

	// SDCard 加载配置
	SDCardScriptPath string `yaml:"sdcardScriptPath"` // SDCard 上的脚本路径

	// Embed 配置
	EmbedMainScript string `yaml:"embedMainScript"` // 主脚本文件名

	// 清理配置
	AutoCleanup bool `yaml:"autoCleanup"` // 脚本执行完成后是否自动清理临时目录

	// 版本控制
	Version string `yaml:"version"` // 当前构建版本号

	// 历史版本号列表（用于重复检测）
	VersionHistory []string `yaml:"versionHistory"`

	// 资源包下载配置
	ResourceURL string `yaml:"resourceUrl"` // 资源包下载URL
}

// ProjectConfig 项目配置（存放在项目目录）
type ProjectConfig struct {
	// 代码风格: autogo, lrappsoft, nodejs
	CodeStyle string `yaml:"codeStyle"`
	// 设备 ID，选择设备后自动保存
	DeviceID string `yaml:"deviceId"`
	// 项目路径
	ProjectPath string `yaml:"projectPath"`
	// 设备服务端口，默认 8080
	DevicePort string `yaml:"devicePort"`
	// 选中的脚本文件名
	SelectedScript string `yaml:"selectedScript"`
	// Go Module 名称（用于ZIP打包命名）
	ModuleName string `yaml:"moduleName"`

	// 安全配置
	Security SecurityConfig `yaml:"security"`

	// 构建配置
	Build BuildConfig `yaml:"build"`
}

// RuntimeState 运行时状态（不持久化，每次运行时检测）
type RuntimeState struct {
	// 设备 IP（运行时检测）
	DeviceIP string
	// 设备服务地址（运行时生成：IP:Port）
	DeviceServiceURL string
}

// CombinedConfig 组合配置（包含全局配置、项目配置和运行时状态）
type CombinedConfig struct {
	Global  *GlobalConfig
	Project *ProjectConfig
	Runtime *RuntimeState
}

// Manager 配置管理器
type Manager struct {
	globalConfigPath  string // 全局配置路径
	projectConfigPath string // 项目配置路径
	printer           *printer.Printer
}

// NewManager 创建配置管理器
func NewManager(p *printer.Printer) *Manager {
	// 全局配置目录：用户主目录
	homeDir, err := os.UserHomeDir()
	if err != nil {
		homeDir = "."
	}
	globalConfigDir := filepath.Join(homeDir, ".autogo_scriptengine_debugger")
	if err := os.MkdirAll(globalConfigDir, 0755); err != nil {
		p.Warning("创建全局配置目录失败: %v", err)
	}
	globalConfigPath := filepath.Join(globalConfigDir, "global.yaml")

	// 项目配置目录：当前工作目录
	wd, err := os.Getwd()
	if err != nil {
		wd = "."
	}
	projectConfigDir := filepath.Join(wd, ".autogo_scriptengine_debugger")
	if err := os.MkdirAll(projectConfigDir, 0755); err != nil {
		p.Warning("创建项目配置目录失败: %v", err)
	}
	projectConfigPath := filepath.Join(projectConfigDir, "project.yaml")

	mgr := &Manager{
		globalConfigPath:  globalConfigPath,
		projectConfigPath: projectConfigPath,
		printer:           p,
	}

	// 尝试迁移旧配置
	mgr.MigrateFromJSON()

	// 初始化配置文件（如果不存在）
	mgr.initGlobalConfigIfNeeded()
	mgr.initProjectConfigIfNeeded()

	return mgr
}

// initGlobalConfigIfNeeded 如果全局配置文件不存在，则初始化并写入默认值
func (m *Manager) initGlobalConfigIfNeeded() {
	if _, err := os.Stat(m.globalConfigPath); os.IsNotExist(err) {
		m.printer.Info("初始化全局配置文件...")

		config := &GlobalConfig{}

		// 自动检测系统代理
		if proxy := m.detectSystemProxy(); proxy != "" {
			config.HTTPProxy = proxy
			m.printer.Info("检测到系统代理: %s", proxy)
		}

		// 自动检测 AG 路径
		if agPath := m.detectAGPath(); agPath != "" {
			config.AGPath = agPath
			m.printer.Info("检测到 AG 路径: %s", agPath)
		}

		// 自动检测 ADB 路径
		if adbPath := m.detectADBPath(); adbPath != "" {
			config.ADBPath = adbPath
			m.printer.Info("检测到 ADB 路径: %s", adbPath)
		}

		// 保存配置
		if err := m.SaveGlobal(config); err != nil {
			m.printer.Warning("保存全局配置失败: %v", err)
		} else {
			m.printer.Success("全局配置文件已创建: %s", m.globalConfigPath)
		}
	}
}

// initProjectConfigIfNeeded 如果项目配置文件不存在，则初始化并写入默认值
func (m *Manager) initProjectConfigIfNeeded() {
	if _, err := os.Stat(m.projectConfigPath); os.IsNotExist(err) {
		m.printer.Info("初始化项目配置文件...")

		// 获取当前工作目录作为项目路径
		wd, _ := os.Getwd()

		config := &ProjectConfig{
			CodeStyle:      "autogo",
			DevicePort:     DefaultDevicePort,
			ProjectPath:    wd,
			SelectedScript: "",
			Security: SecurityConfig{
				BuildObfuscate: false,
				BuildBytecode:  false,
				BuildEncrypt:   false,
				DebugObfuscate: false,
				DebugBytecode:  false,
				DebugEncrypt:   false,
			},
			Build: BuildConfig{
				LoadMode:         LoadModeEmbed,
				HTTPScriptURL:    "",
				SDCardScriptPath: "",
				EmbedMainScript:  "main.lua",
				AutoCleanup:      true, // 默认启用自动清理
			},
		}

		// 保存配置
		if err := m.SaveProject(config); err != nil {
			m.printer.Warning("保存项目配置失败: %v", err)
		} else {
			m.printer.Success("项目配置文件已创建: %s", m.projectConfigPath)
		}
	}
}

// detectSystemProxy 检测系统代理设置
func (m *Manager) detectSystemProxy() string {
	switch runtime.GOOS {
	case "darwin":
		return m.detectMacOSProxy()
	case "windows":
		return m.detectWindowsProxy()
	case "linux":
		return m.detectLinuxProxy()
	default:
		return ""
	}
}

// detectMacOSProxy 检测 macOS 系统代理
func (m *Manager) detectMacOSProxy() string {
	// 使用 networksetup 命令获取代理设置
	// 首先获取所有网络服务
	cmd := exec.Command("networksetup", "-listallnetworkservices")
	output, err := cmd.Output()
	if err != nil {
		return ""
	}

	// 解析网络服务列表
	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		// 跳过第一行（标题）和空行
		if line == "" || strings.Contains(line, "Network Services") {
			continue
		}

		// 检查 HTTP 代理
		cmd = exec.Command("networksetup", "-getwebproxy", line)
		proxyOutput, err := cmd.Output()
		if err != nil {
			continue
		}

		// 解析代理设置
		enabled := false
		server := ""
		port := ""

		for _, proxyLine := range strings.Split(string(proxyOutput), "\n") {
			proxyLine = strings.TrimSpace(proxyLine)
			if strings.HasPrefix(proxyLine, "Enabled: Yes") {
				enabled = true
			} else if strings.HasPrefix(proxyLine, "Server:") {
				parts := strings.Fields(proxyLine)
				if len(parts) >= 2 {
					server = parts[1]
				}
			} else if strings.HasPrefix(proxyLine, "Port:") {
				parts := strings.Fields(proxyLine)
				if len(parts) >= 2 {
					port = parts[1]
				}
			}
		}

		if enabled && server != "" && port != "" {
			proxy := fmt.Sprintf("http://%s:%s", server, port)
			m.printer.Verbose("检测到 macOS HTTP 代理: %s (服务: %s)", proxy, line)
			return proxy
		}
	}

	return ""
}

// detectWindowsProxy 检测 Windows 系统代理
func (m *Manager) detectWindowsProxy() string {
	// Windows 通过注册表或环境变量获取代理设置
	// 首先检查环境变量
	if proxy := os.Getenv("HTTP_PROXY"); proxy != "" {
		return proxy
	}
	if proxy := os.Getenv("http_proxy"); proxy != "" {
		return proxy
	}

	// 使用 netsh 命令获取代理设置
	cmd := exec.Command("netsh", "winhttp", "show", "proxy")
	output, err := cmd.Output()
	if err != nil {
		return ""
	}

	// 解析输出
	for _, line := range strings.Split(string(output), "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "Proxy Server(s) :") {
			// 格式: Proxy Server(s) : 127.0.0.1:7890
			parts := strings.Split(line, ":")
			if len(parts) >= 2 {
				proxy := strings.TrimSpace(strings.Join(parts[1:], ":"))
				if proxy != "" && proxy != "Direct access (no proxy server)." {
					return "http://" + proxy
				}
			}
		}
	}

	return ""
}

// detectLinuxProxy 检测 Linux 系统代理
func (m *Manager) detectLinuxProxy() string {
	// Linux 通过环境变量获取代理设置
	if proxy := os.Getenv("HTTP_PROXY"); proxy != "" {
		return proxy
	}
	if proxy := os.Getenv("http_proxy"); proxy != "" {
		return proxy
	}

	// 检查 gsettings (GNOME)
	cmd := exec.Command("gsettings", "get", "org.gnome.system.proxy.http", "host")
	hostOutput, err := cmd.Output()
	if err == nil {
		host := strings.Trim(strings.TrimSpace(string(hostOutput)), "'")
		if host != "" && host != "''" {
			cmd = exec.Command("gsettings", "get", "org.gnome.system.proxy.http", "port")
			portOutput, err := cmd.Output()
			if err == nil {
				port := strings.TrimSpace(string(portOutput))
				if port != "" && port != "0" {
					return fmt.Sprintf("http://%s:%s", host, port)
				}
			}
		}
	}

	return ""
}

// detectAGPath 检测 AG 可执行文件路径
func (m *Manager) detectAGPath() string {
	// 1. 检查环境变量
	if agPath := os.Getenv("AUTOGO_AG_PATH"); agPath != "" {
		if _, err := exec.LookPath(agPath); err == nil {
			return agPath
		}
	}

	// 2. 检查系统 PATH
	if agPath, err := exec.LookPath("ag"); err == nil {
		return agPath
	}

	// 3. 检查系统默认路径（与 project.go 中的 DetectAG 保持一致）
	homeDir, _ := os.UserHomeDir()
	var commonPaths []string

	switch runtime.GOOS {
	case "windows":
		commonPaths = []string{
			"C:\\Users\\Public\\ag.exe",
			filepath.Join(os.Getenv("LOCALAPPDATA"), "ag.exe"),
			filepath.Join(os.Getenv("APPDATA"), "ag.exe"),
		}
	case "darwin":
		commonPaths = []string{
			"/opt/homebrew/bin/ag",           // Apple Silicon Homebrew
			"/usr/local/bin/ag",              // Intel Mac Homebrew
			"/Users/Shared/ag",               // 共享目录
			filepath.Join(homeDir, "go/bin/ag"),
			filepath.Join(homeDir, ".local/bin/ag"),
			"/usr/bin/ag",
		}
	default:
		// Linux 和其他 Unix 系统
		commonPaths = []string{
			filepath.Join(homeDir, ".autogo/ag"),
			filepath.Join(homeDir, "go/bin/ag"),
			filepath.Join(homeDir, ".local/bin/ag"),
			"/usr/local/bin/ag",
			"/usr/bin/ag",
			"/snap/bin/ag",
		}
	}

	for _, path := range commonPaths {
		if _, err := exec.LookPath(path); err == nil {
			m.printer.Verbose("找到 AG (常见路径): %s", path)
			return path
		}
	}

	return ""
}

// detectADBPath 检测 ADB 可执行文件路径
func (m *Manager) detectADBPath() string {
	// 1. 检查环境变量
	if adbPath := os.Getenv("AUTOGO_ADB_PATH"); adbPath != "" {
		if _, err := exec.LookPath(adbPath); err == nil {
			return adbPath
		}
	}

	// 2. 检查系统 PATH
	if adbPath, err := exec.LookPath("adb"); err == nil {
		return adbPath
	}

	// 3. 检查系统默认路径
	homeDir, _ := os.UserHomeDir()
	var commonPaths []string

	switch runtime.GOOS {
	case "windows":
		commonPaths = []string{
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "Sdk", "platform-tools", "adb.exe"),
			filepath.Join(os.Getenv("LOCALAPPDATA"), "Android", "android-sdk", "platform-tools", "adb.exe"),
			"C:\\Android\\sdk\\platform-tools\\adb.exe",
		}
	case "darwin":
		commonPaths = []string{
			"/opt/homebrew/bin/adb",                                    // Apple Silicon Homebrew
			"/usr/local/bin/adb",                                       // Intel Mac Homebrew
			filepath.Join(homeDir, "Library", "Android", "sdk", "platform-tools", "adb"),
			filepath.Join(homeDir, "Android", "Sdk", "platform-tools", "adb"),
			"/usr/bin/adb",
		}
	default:
		// Linux 和其他 Unix 系统
		commonPaths = []string{
			filepath.Join(homeDir, "Android", "Sdk", "platform-tools", "adb"),
			filepath.Join(homeDir, ".android", "platform-tools", "adb"),
			"/usr/local/bin/adb",
			"/usr/bin/adb",
			"/snap/bin/adb",
		}
	}

	for _, path := range commonPaths {
		if _, err := exec.LookPath(path); err == nil {
			m.printer.Verbose("找到 ADB (常见路径): %s", path)
			return path
		}
	}

	return ""
}

// LoadGlobal 加载全局配置
func (m *Manager) LoadGlobal() *GlobalConfig {
	config := &GlobalConfig{}

	if _, err := os.Stat(m.globalConfigPath); os.IsNotExist(err) {
		return config
	}

	content, err := os.ReadFile(m.globalConfigPath)
	if err != nil {
		return config
	}

	if err := yaml.Unmarshal(content, config); err != nil {
		m.printer.Debug("解析全局配置失败: %v", err)
	}

	return config
}

// LoadProject 加载项目配置
func (m *Manager) LoadProject() *ProjectConfig {
	config := &ProjectConfig{
		CodeStyle:  "autogo",
		DevicePort: DefaultDevicePort,
		Build: BuildConfig{
			LoadMode: LoadModeEmbed,
		},
	}

	if _, err := os.Stat(m.projectConfigPath); os.IsNotExist(err) {
		return config
	}

	content, err := os.ReadFile(m.projectConfigPath)
	if err != nil {
		return config
	}

	if err := yaml.Unmarshal(content, config); err != nil {
		m.printer.Debug("解析项目配置失败: %v", err)
	}

	// 确保端口有默认值
	if config.DevicePort == "" {
		config.DevicePort = DefaultDevicePort
	}

	// 确保加载模式有默认值
	if config.Build.LoadMode == "" {
		config.Build.LoadMode = LoadModeEmbed
	}

	return config
}

// Load 加载所有配置（兼容旧接口）
func (m *Manager) Load() (*CombinedConfig, error) {
	return &CombinedConfig{
		Global:  m.LoadGlobal(),
		Project: m.LoadProject(),
		Runtime: &RuntimeState{},
	}, nil
}

// writeFileAtomic 原子性写入文件
// 先写入临时文件，然后重命名，避免写入过程中断导致文件损坏
func writeFileAtomic(filename string, data []byte, perm os.FileMode) error {
	// 创建临时文件
	dir := filepath.Dir(filename)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("创建目录失败: %w", err)
	}

	// 生成临时文件名
	tempFilename := filename + ".tmp"

	// 写入临时文件
	if err := os.WriteFile(tempFilename, data, perm); err != nil {
		return fmt.Errorf("写入临时文件失败: %w", err)
	}

	// 同步文件到磁盘
	if err := syncFile(tempFilename); err != nil {
		os.Remove(tempFilename)
		return fmt.Errorf("同步文件失败: %w", err)
	}

	// 原子性重命名
	if err := os.Rename(tempFilename, filename); err != nil {
		os.Remove(tempFilename)
		return fmt.Errorf("重命名文件失败: %w", err)
	}

	return nil
}

// syncFile 同步文件到磁盘
func syncFile(filename string) error {
	file, err := os.Open(filename)
	if err != nil {
		return err
	}
	defer file.Close()
	return file.Sync()
}

// SaveGlobal 保存全局配置
func (m *Manager) SaveGlobal(config *GlobalConfig) error {
	// 带注释的 YAML 内容
	content := `# AutoGo ScriptEngine Debugger 全局配置
# 此配置文件存放在用户主目录下，对所有项目生效

# HTTP 代理地址
# 例如: http://127.0.0.1:7890
# 留空则不使用代理
httpProxy: ` + yamlValue(config.HTTPProxy) + `

# AG 可执行文件路径
# 留空则自动检测系统 PATH 中的 ag 命令
agPath: ` + yamlValue(config.AGPath) + `

# ADB 可执行文件路径
# 留空则自动检测系统 PATH 中的 adb 命令
adbPath: ` + yamlValue(config.ADBPath) + `
`
	return writeFileAtomic(m.globalConfigPath, []byte(content), 0644)
}

// SaveProject 保存项目配置
func (m *Manager) SaveProject(config *ProjectConfig) error {
	// 确保端口有默认值
	if config.DevicePort == "" {
		config.DevicePort = DefaultDevicePort
	}

	// 确保加载模式有默认值
	if config.Build.LoadMode == "" {
		config.Build.LoadMode = LoadModeEmbed
	}

	// 带注释的 YAML 内容
	content := `# AutoGo ScriptEngine Debugger 项目配置
# 此配置文件存放在项目目录下，仅对当前项目生效

# 代码风格
# 可选值: autogo, lrappsoft, nodejs
codeStyle: "` + config.CodeStyle + `"

# 设备 ID
# 选择设备后自动保存，留空则每次启动时选择
deviceId: "` + config.DeviceID + `"

# 项目路径
projectPath: "` + config.ProjectPath + `"

# 设备服务端口
# 脚本引擎服务监听的端口，默认 8080
devicePort: "` + config.DevicePort + `"

# 选中的脚本文件名
# 在运行管理中选择脚本后自动保存
selectedScript: "` + config.SelectedScript + `"

# ========================================
# 安全配置
# ========================================
security:
  # 构建环境配置（用于生成最终构建代码）
  buildObfuscate: ` + boolToYAML(config.Security.BuildObfuscate) + ` # 构建时是否混淆代码
  buildBytecode: ` + boolToYAML(config.Security.BuildBytecode) + ` # 构建时是否编译字节码（仅 Lua）
  buildEncrypt: ` + boolToYAML(config.Security.BuildEncrypt) + ` # 构建时是否 AES 加密

  # 调试环境配置（用于调试时处理脚本）
  debugObfuscate: ` + boolToYAML(config.Security.DebugObfuscate) + ` # 调试时是否混淆代码
  debugBytecode: ` + boolToYAML(config.Security.DebugBytecode) + ` # 调试时是否编译字节码（仅 Lua）
  debugEncrypt: ` + boolToYAML(config.Security.DebugEncrypt) + ` # 调试时是否 AES 加密

  # AES 加密密钥（Base64 编码）
  # 如果为空，将在首次加密时自动生成
  encryptionKey: "` + config.Security.EncryptionKey + `"

# ========================================
# 构建配置
# ========================================
build:
  # 脚本加载模式: http, sdcard, embed
  loadMode: "` + string(config.Build.LoadMode) + `"

  # HTTP 加载配置
  httpScriptUrl: "` + config.Build.HTTPScriptURL + `" # 脚本下载 URL

  # SDCard 加载配置
  sdcardScriptPath: "` + config.Build.SDCardScriptPath + `" # SDCard 上的脚本路径

  # Embed 配置
  embedMainScript: "` + config.Build.EmbedMainScript + `" # 主脚本文件名

  # 清理配置
  autoCleanup: ` + boolToYAML(config.Build.AutoCleanup) + ` # 脚本执行完成后是否自动清理临时目录
`
	return writeFileAtomic(m.projectConfigPath, []byte(content), 0644)
}

// boolToYAML 将布尔值转换为 YAML 格式
func boolToYAML(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

// yamlValue 返回 YAML 格式的字符串值
// 空字符串返回空字符串，非空字符串加引号
func yamlValue(s string) string {
	if s == "" {
		return `""`
	}
	return `"` + s + `"`
}

// Save 保存配置（兼容旧接口）
func (m *Manager) Save(config *CombinedConfig) error {
	if config.Global != nil {
		if err := m.SaveGlobal(config.Global); err != nil {
			return err
		}
	}
	if config.Project != nil {
		if err := m.SaveProject(config.Project); err != nil {
			return err
		}
	}
	return nil
}

// Clear 清除所有配置
func (m *Manager) Clear() error {
	if _, err := os.Stat(m.globalConfigPath); err == nil {
		if err := os.Remove(m.globalConfigPath); err != nil {
			return err
		}
	}
	if _, err := os.Stat(m.projectConfigPath); err == nil {
		if err := os.Remove(m.projectConfigPath); err != nil {
			return err
		}
	}
	return nil
}

// GetGlobalConfigPath 获取全局配置路径
func (m *Manager) GetGlobalConfigPath() string {
	return m.globalConfigPath
}

// GetProjectConfigPath 获取项目配置路径
func (m *Manager) GetProjectConfigPath() string {
	return m.projectConfigPath
}

// UpdateAGPath 更新 AG 路径到配置文件
func (m *Manager) UpdateAGPath(agPath string) error {
	config := m.LoadGlobal()
	config.AGPath = agPath
	return m.SaveGlobal(config)
}

// UpdateADBPath 更新 ADB 路径到配置文件
func (m *Manager) UpdateADBPath(adbPath string) error {
	config := m.LoadGlobal()
	config.ADBPath = adbPath
	return m.SaveGlobal(config)
}

// UpdateHTTPProxy 更新 HTTP 代理到配置文件
func (m *Manager) UpdateHTTPProxy(proxy string) error {
	config := m.LoadGlobal()
	config.HTTPProxy = proxy
	return m.SaveGlobal(config)
}

// MigrateFromJSON 从旧的 JSON 配置迁移到 YAML
func (m *Manager) MigrateFromJSON() error {
	// 检查旧的 JSON 配置文件
	oldGlobalPath := filepath.Join(filepath.Dir(m.globalConfigPath), "global.json")
	oldProjectPath := filepath.Join(filepath.Dir(m.projectConfigPath), "project.json")

	// 迁移全局配置
	if _, err := os.Stat(oldGlobalPath); err == nil {
		content, err := os.ReadFile(oldGlobalPath)
		if err == nil {
			var config GlobalConfig
			if json.Unmarshal(content, &config) == nil {
				m.SaveGlobal(&config)
				os.Remove(oldGlobalPath)
				m.printer.Info("已迁移全局配置: %s -> %s", oldGlobalPath, m.globalConfigPath)
			}
		}
	}

	// 迁移项目配置
	if _, err := os.Stat(oldProjectPath); err == nil {
		content, err := os.ReadFile(oldProjectPath)
		if err == nil {
			var config ProjectConfig
			if json.Unmarshal(content, &config) == nil {
				m.SaveProject(&config)
				os.Remove(oldProjectPath)
				m.printer.Info("已迁移项目配置: %s -> %s", oldProjectPath, m.projectConfigPath)
			}
		}
	}

	// 检查旧的合并配置文件
	oldConfigPath := filepath.Join(filepath.Dir(m.projectConfigPath), "config.json")
	if _, err := os.Stat(oldConfigPath); err == nil {
		content, err := os.ReadFile(oldConfigPath)
		if err == nil {
			// 尝试解析为旧格式
			var oldConfig struct {
				CodeStyle        string `json:"codeStyle"`
				DeviceServiceURL string `json:"deviceServiceUrl"`
				DeviceID         string `json:"deviceId"`
				DeviceIP         string `json:"deviceIp"`
				DevicePort       string `json:"devicePort"`
				ProjectPath      string `json:"projectPath"`
				HTTPProxy        string `json:"httpProxy"`
				AGPath           string `json:"agPath"`
				ADBPath          string `json:"adbPath"`
				SelectedScript   string `json:"selectedScript"`
			}
			if json.Unmarshal(content, &oldConfig) == nil {
				// 拆分到全局和项目配置
				globalConfig := &GlobalConfig{
					HTTPProxy: oldConfig.HTTPProxy,
					AGPath:    oldConfig.AGPath,
					ADBPath:   oldConfig.ADBPath,
				}

				projectConfig := &ProjectConfig{
					CodeStyle:      oldConfig.CodeStyle,
					DeviceID:       oldConfig.DeviceID,
					ProjectPath:    oldConfig.ProjectPath,
					DevicePort:     oldConfig.DevicePort,
					SelectedScript: oldConfig.SelectedScript,
				}
				if projectConfig.CodeStyle == "" {
					projectConfig.CodeStyle = "autogo"
				}
				if projectConfig.DevicePort == "" {
					projectConfig.DevicePort = DefaultDevicePort
				}

				m.SaveGlobal(globalConfig)
				m.SaveProject(projectConfig)
				os.Remove(oldConfigPath)
				m.printer.Info("已迁移旧配置: %s", oldConfigPath)
			}
		}
	}

	return nil
}
