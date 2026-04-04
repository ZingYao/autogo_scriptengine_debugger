package config

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

// Config 配置结构
type Config struct {
	CodeStyle        string `json:"codeStyle"`
	DeviceServiceURL string `json:"deviceServiceUrl"` // Android 客户端服务地址
	DeviceID         string `json:"deviceId"`
	DeviceIP         string `json:"deviceIp"`
	DevicePort       string `json:"devicePort"`
	ProjectPath      string `json:"projectPath"`
	HTTPProxy        string `json:"httpProxy"`      // HTTP 代理地址
	AGPath           string `json:"agPath"`         // AG 可执行文件路径
	ADBPath          string `json:"adbPath"`        // ADB 可执行文件路径
	SelectedScript   string `json:"selectedScript"` // 选中的脚本文件名
}

// Manager 配置管理器
type Manager struct {
	configPath string
	printer    *printer.Printer
}

// NewManager 创建配置管理器
func NewManager(p *printer.Printer) *Manager {
	// 获取当前工作目录
	wd, err := os.Getwd()
	if err != nil {
		// 如果获取失败，使用用户主目录作为后备
		homeDir, _ := os.UserHomeDir()
		wd = homeDir
	}

	// 配置目录路径
	configDir := filepath.Join(wd, ".autogo_scriptengine_debugger")
	// 确保目录存在
	if err := os.MkdirAll(configDir, 0755); err != nil {
		p.Warning("创建配置目录失败: %v", err)
	}

	configPath := filepath.Join(configDir, "config.json")
	return &Manager{
		configPath: configPath,
		printer:    p,
	}
}

// Load 加载配置
func (m *Manager) Load() (*Config, error) {
	config := &Config{
		CodeStyle:  "autogo",
		DevicePort: "8080",
	}

	if _, err := os.Stat(m.configPath); os.IsNotExist(err) {
		return config, nil
	}

	content, err := os.ReadFile(m.configPath)
	if err != nil {
		return config, err
	}

	// 移除注释行（以 // 开头的行）
	lines := strings.Split(string(content), "\n")
	var jsonLines []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if !strings.HasPrefix(trimmed, "//") {
			jsonLines = append(jsonLines, line)
		}
	}
	jsonContent := strings.Join(jsonLines, "\n")

	// 兼容旧字段名：serverUrl -> deviceServiceUrl
	jsonContent = strings.Replace(jsonContent, `"serverUrl"`, `"deviceServiceUrl"`, -1)

	// 尝试解析 JSON
	if err := json.Unmarshal([]byte(jsonContent), config); err != nil {
		// 如果JSON解析失败，尝试旧格式
		m.printer.Debug("JSON 解析失败，尝试旧格式: %v", err)
		file, err := os.Open(m.configPath)
		if err != nil {
			return config, err
		}
		defer file.Close()

		scanner := bufio.NewScanner(file)
		for scanner.Scan() {
			line := scanner.Text()
			var key, value string
			if _, err := fmt.Sscanf(line, "CONFIG_%s=%q", &key, &value); err == nil {
				switch key {
				case "CODE_STYLE":
					config.CodeStyle = value
				case "SERVER_URL":
					config.DeviceServiceURL = value
				case "DEVICE_ID":
					config.DeviceID = value
				case "DEVICE_IP":
					config.DeviceIP = value
				case "DEVICE_PORT":
					config.DevicePort = value
				case "PROJECT_PATH":
					config.ProjectPath = value
				}
			}
		}
	}

	return config, nil
}

// Save 保存配置
func (m *Manager) Save(config *Config) error {
	file, err := os.Create(m.configPath)
	if err != nil {
		return err
	}
	defer file.Close()

	// 写入JSON格式
	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(config); err != nil {
		return err
	}

	// 添加时间注释
	content, _ := os.ReadFile(m.configPath)
	finalContent := fmt.Sprintf("// AutoGo Script Runner 配置文件\n// 最后更新: %s\n\n%s",
		time.Now().Format("2006-01-02 15:04:05"), string(content))

	return os.WriteFile(m.configPath, []byte(finalContent), 0644)
}

// Clear 清除配置
func (m *Manager) Clear() error {
	if _, err := os.Stat(m.configPath); os.IsNotExist(err) {
		m.printer.Info("配置文件不存在")
		return nil
	}
	return os.Remove(m.configPath)
}
