package device

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

// Device 设备信息
type Device struct {
	Serial string `json:"serial"`
	State  string `json:"state"`
}

// Manager 设备管理器
type Manager struct {
	printer *printer.Printer
}

// NewManager 创建设备管理器
func NewManager(p *printer.Printer) *Manager {
	return &Manager{
		printer: p,
	}
}

// DetectADB 检测ADB路径
// configADBPath: 配置文件中指定的 ADB 路径（优先使用）
// 返回: 检测到的 ADB 路径，供调用者保存到配置
func (m *Manager) DetectADB(configADBPath string) (string, error) {
	m.printer.Verbose("正在检测 ADB 路径...")

	// 1. 首先检查配置文件中的 ADB 路径
	if configADBPath != "" {
		if _, err := exec.LookPath(configADBPath); err == nil {
			m.printer.Verbose("使用配置文件中的 ADB 路径: %s", configADBPath)
			os.Setenv("AUTOGO_ADB_PATH", configADBPath)
			return configADBPath, nil
		}
		m.printer.Warning("配置文件中的 ADB 路径无效: %s", configADBPath)
	}

	// 2. 检查环境变量
	if adbPath := os.Getenv("AUTOGO_ADB_PATH"); adbPath != "" {
		if _, err := exec.LookPath(adbPath); err == nil {
			m.printer.Verbose("使用环境变量中的 ADB 路径: %s", adbPath)
			os.Setenv("AUTOGO_ADB_PATH", adbPath)
			return adbPath, nil
		}
		m.printer.Warning("环境变量 AUTOGO_ADB_PATH 指定的路径无效: %s", adbPath)
	}

	// 3. 检查系统PATH
	if adbPath, err := exec.LookPath("adb"); err == nil {
		m.printer.Verbose("检测到系统 ADB: %s", adbPath)
		os.Setenv("AUTOGO_ADB_PATH", adbPath)
		return adbPath, nil
	}

	// 4. 检查常见路径
	homeDir, _ := os.UserHomeDir()
	commonPaths := []string{
		filepath.Join(homeDir, "Library/Android/sdk/platform-tools/adb"),
		filepath.Join(homeDir, "Android/Sdk/platform-tools/adb"),
		"/usr/local/bin/adb",
		"/usr/bin/adb",
	}

	for _, path := range commonPaths {
		if _, err := exec.LookPath(path); err == nil {
			m.printer.Verbose("找到 ADB: %s", path)
			os.Setenv("AUTOGO_ADB_PATH", path)
			return path, nil
		}
	}

	return "", fmt.Errorf("未找到 ADB 命令")
}

// GetConnectedDevices 获取已连接设备列表
func (m *Manager) GetConnectedDevices() ([]Device, error) {
	m.printer.Debug("使用 ADB 获取设备列表...")
	return m.getConnectedDevicesADB()
}

func (m *Manager) getConnectedDevicesADB() ([]Device, error) {
	adbPath := os.Getenv("AUTOGO_ADB_PATH")
	if adbPath == "" {
		adbPath = "adb"
	}

	cmd := exec.Command(adbPath, "devices")
	output, err := cmd.Output()
	if err != nil {
		return nil, err
	}

	var devices []Device
	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		if strings.Contains(line, "\t") && strings.Contains(line, "device") {
			parts := strings.Split(line, "\t")
			if len(parts) >= 2 {
				devices = append(devices, Device{
					Serial: strings.TrimSpace(parts[0]),
					State:  strings.TrimSpace(parts[1]),
				})
			}
		}
	}

	return devices, nil
}

// GetDeviceIP 获取设备IP地址
func (m *Manager) GetDeviceIP(deviceID string) (string, error) {
	m.printer.Debug("使用 ADB 获取设备 IP...")
	return m.getDeviceIPADB(deviceID)
}

func (m *Manager) getDeviceIPADB(deviceID string) (string, error) {
	adbPath := os.Getenv("AUTOGO_ADB_PATH")
	if adbPath == "" {
		adbPath = "adb"
	}

	methods := []string{
		"ifconfig wlan0 2>/dev/null | grep 'inet addr' | awk '{print $2}' | cut -d: -f2",
		"ip addr show wlan0 2>/dev/null | grep 'inet ' | awk '{print $2}' | cut -d/ -f1",
		"dumpsys wifi 2>/dev/null | grep 'ip_address' | awk '{print $NF}'",
	}

	for _, method := range methods {
		cmd := exec.Command(adbPath, "-s", deviceID, "shell", method)
		output, err := cmd.Output()
		if err != nil {
			continue
		}

		ip := strings.TrimSpace(string(output))
		if ip != "" && net.ParseIP(ip) != nil {
			return ip, nil
		}
	}

	return "", fmt.Errorf("无法获取设备 IP")
}

// ConnectDevice 连接设备
func (m *Manager) ConnectDevice(address string) error {
	m.printer.Debug("使用 ADB 连接设备: %s", address)
	return m.connectDeviceADB(address)
}

func (m *Manager) connectDeviceADB(address string) error {
	adbPath := os.Getenv("AUTOGO_ADB_PATH")
	if adbPath == "" {
		adbPath = "adb"
	}

	cmd := exec.Command(adbPath, "connect", address)
	return cmd.Run()
}

// ScanPort 扫描端口（仅检测 TCP 连通性）
func (m *Manager) ScanPort(ip string, port string, timeout time.Duration) bool {
	address := fmt.Sprintf("%s:%s", ip, port)
	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return false
	}

	conn.Close()
	return true
}

// CheckScriptEngineService 检查脚本引擎服务是否可用（发送测试请求）
func (m *Manager) CheckScriptEngineService(ip string, port string, timeout time.Duration) bool {
	address := fmt.Sprintf("%s:%s", ip, port)

	// 1. 检查 TCP 连通性
	conn, err := net.DialTimeout("tcp", address, timeout)
	if err != nil {
		return false
	}
	defer conn.Close()

	// 2. 设置读写超时
	conn.SetDeadline(time.Now().Add(timeout))

	// 3. 发送一个简单的状态查询请求
	// 根据协议，发送 JSON 格式的请求（以换行符结尾）
	testRequest := `{"operation":"status","script_id":"test"}` + "\n"
	_, err = conn.Write([]byte(testRequest))
	if err != nil {
		return false
	}

	// 4. 读取响应
	buf := make([]byte, 1024)
	n, err := conn.Read(buf)
	if err != nil {
		return false
	}

	// 5. 验证响应是否为有效的 JSON
	var response map[string]interface{}
	if err := json.Unmarshal(buf[:n], &response); err != nil {
		return false
	}

	// 6. 检查响应中是否包含 success 字段
	_, ok := response["success"]
	return ok
}
