package agmanager

import (
	"bufio"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

const (
	changelogURL = "https://autogo-1257133387.cos.ap-shanghai.myqcloud.com/changelog.md"
	sdkBaseURL   = "http://168.138.164.80:7001/files/AutoGo/sdk/"
)

// VersionInfo 版本信息
type VersionInfo struct {
	Version    string
	Date       string
	Changes    []string
	Downloaded bool
}

// AGManager AG 管理器
type AGManager struct {
	printer    *printer.Printer
	installDir string // 安装目录
	agPath     string // ag 可执行文件路径
	httpProxy  string // HTTP 代理地址
}

// NewAGManager 创建 AG 管理器
func NewAGManager(p *printer.Printer, installDir string, httpProxy string) *AGManager {
	if installDir == "" {
		// 使用系统默认路径
		switch runtime.GOOS {
		case "windows":
			installDir = "C:\\Users\\Public"
			// Windows 使用 ag.exe
			return &AGManager{
				printer:    p,
				installDir: installDir,
				agPath:     filepath.Join(installDir, "ag.exe"),
				httpProxy:  httpProxy,
			}
		case "darwin":
			installDir = "/Users/Shared"
		default:
			homeDir, _ := os.UserHomeDir()
			installDir = filepath.Join(homeDir, ".autogo")
		}
	}

	// 确定可执行文件名
	agName := "ag"
	if runtime.GOOS == "windows" {
		agName = "ag.exe"
	}

	return &AGManager{
		printer:    p,
		installDir: installDir,
		agPath:     filepath.Join(installDir, agName),
		httpProxy:  httpProxy,
	}
}

// SetHTTPProxy 设置 HTTP 代理
func (m *AGManager) SetHTTPProxy(proxy string) {
	m.httpProxy = proxy
}

// GetAGPath 获取 AG 路径
func (m *AGManager) GetAGPath() string {
	return m.agPath
}

// IsInstalled 检查是否已安装
func (m *AGManager) IsInstalled() bool {
	if _, err := os.Stat(m.agPath); err != nil {
		return false
	}
	return true
}

// GetCurrentVersion 获取当前版本
func (m *AGManager) GetCurrentVersion() string {
	// 优先读取版本文件
	versionFile := m.agPath + ".version"
	if data, err := os.ReadFile(versionFile); err == nil {
		version := strings.TrimSpace(string(data))
		if version != "" {
			return version
		}
	}

	// 如果版本文件不存在，尝试执行 ag version 命令
	if _, err := os.Stat(m.agPath); err == nil {
		// AG 文件存在，尝试获取版本号
		cmd := exec.Command(m.agPath, "version")
		output, err := cmd.Output()
		if err == nil {
			// 解析版本号（假设输出格式为 "v1.11.05" 或 "1.11.05"）
			version := strings.TrimSpace(string(output))
			version = strings.TrimPrefix(version, "v")
			version = strings.TrimPrefix(version, "V")

			// 提取版本号（通常是第一行）
			if idx := strings.Index(version, "\n"); idx > 0 {
				version = version[:idx]
			}

			version = strings.TrimSpace(version)
			if version != "" && version != "unknown" {
				// 保存版本号到文件
				_ = os.WriteFile(versionFile, []byte(version), 0644)
				return version
			}
		}
	}

	return "unknown"
}

// FetchChangelog 获取更新日志
func (m *AGManager) FetchChangelog() ([]VersionInfo, error) {
	client := &http.Client{Timeout: 10 * time.Second}

	// 配置代理
	if m.httpProxy != "" {
		m.printer.Info("使用代理: %s", m.httpProxy)
		proxyURL, err := url.Parse(m.httpProxy)
		if err != nil {
			return nil, fmt.Errorf("代理地址无效: %v", err)
		}
		client.Transport = &http.Transport{
			Proxy: http.ProxyURL(proxyURL),
		}
	} else {
		m.printer.Info("未配置代理，直接连接")
	}

	resp, err := client.Get(changelogURL)
	if err != nil {
		return nil, fmt.Errorf("获取更新日志失败: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("获取更新日志失败: HTTP %d", resp.StatusCode)
	}

	// 解析 changelog
	var versions []VersionInfo
	scanner := bufio.NewScanner(resp.Body)
	var currentVersion *VersionInfo

	for scanner.Scan() {
		line := scanner.Text()

		// 解析版本标题 (## [1.11.05] - 2026-04-03)
		if strings.HasPrefix(line, "## [") {
			if currentVersion != nil {
				versions = append(versions, *currentVersion)
			}

			// 解析版本号和日期
			// 格式: ## [1.11.05] - 2026-04-03
			line = strings.TrimPrefix(line, "## [")
			parts := strings.Split(line, "] - ")
			version := parts[0]
			date := ""
			if len(parts) > 1 {
				date = strings.TrimSpace(parts[1])
			}

			currentVersion = &VersionInfo{
				Version: version,
				Date:    date,
				Changes: []string{},
			}
		} else if currentVersion != nil && strings.HasPrefix(line, "- ") {
			// 解析变更记录
			change := strings.TrimPrefix(line, "- ")
			currentVersion.Changes = append(currentVersion.Changes, change)
		}
	}

	if currentVersion != nil {
		versions = append(versions, *currentVersion)
	}

	m.printer.Success("获取到 %d 个版本", len(versions))
	return versions, nil
}

// GetPlatformFile 获取当前平台的文件名
func (m *AGManager) GetPlatformFile(version string) string {
	var platform string
	switch runtime.GOOS {
	case "darwin":
		if runtime.GOARCH == "arm64" {
			platform = "mac_arm"
		} else {
			platform = "mac_amd"
		}
	case "windows":
		platform = "win_x64"
	case "linux":
		platform = "linux_x64"
	default:
		platform = "mac_arm"
	}

	return fmt.Sprintf("%s_%s", platform, version)
}

// DownloadWithProgress 带进度条的下载
func (m *AGManager) DownloadWithProgress(downloadURL, filepath string, progressChan chan<- int) error {
	m.printer.Info("开始下载: %s", downloadURL)

	// 创建 HTTP 客户端
	client := &http.Client{
		Timeout: 30 * time.Minute, // 30 分钟超时
	}

	// 如果配置了代理，设置代理
	if m.httpProxy != "" {
		m.printer.Info("使用代理: %s", m.httpProxy)
		proxyURL, err := url.Parse(m.httpProxy)
		if err != nil {
			return fmt.Errorf("代理地址无效: %v", err)
		}
		client.Transport = &http.Transport{
			Proxy: http.ProxyURL(proxyURL),
		}
	}

	resp, err := client.Get(downloadURL)
	if err != nil {
		return fmt.Errorf("下载失败: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("下载失败: HTTP %d", resp.StatusCode)
	}

	// 创建临时文件
	tmpFile := filepath + ".tmp"
	out, err := os.Create(tmpFile)
	if err != nil {
		return fmt.Errorf("创建文件失败: %v", err)
	}
	defer out.Close()

	// 获取文件大小
	totalSize := resp.ContentLength
	downloaded := int64(0)

	// 下载并更新进度
	buf := make([]byte, 32*1024) // 32KB buffer
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, writeErr := out.Write(buf[:n]); writeErr != nil {
				return fmt.Errorf("写入文件失败: %v", writeErr)
			}
			downloaded += int64(n)

			// 发送进度
			if totalSize > 0 && progressChan != nil {
				progress := int(float64(downloaded) / float64(totalSize) * 100)
				progressChan <- progress
			}
		}
		if err != nil {
			if err == io.EOF {
				break
			}
			return fmt.Errorf("下载失败: %v", err)
		}
	}

	// 重命名临时文件
	if err := os.Rename(tmpFile, filepath); err != nil {
		return fmt.Errorf("重命名文件失败: %v", err)
	}

	m.printer.Success("下载完成: %s", filepath)
	return nil
}

// Install 安装或更新 AG
func (m *AGManager) Install(version string, progressChan chan<- int) error {
	// 创建安装目录
	if err := os.MkdirAll(m.installDir, 0755); err != nil {
		return fmt.Errorf("创建目录失败: %v", err)
	}

	// 备份旧版本
	if m.IsInstalled() {
		oldVersion := m.GetCurrentVersion()
		if oldVersion != "unknown" {
			backupPath := filepath.Join(m.installDir, fmt.Sprintf("ag_%s", oldVersion))
			m.printer.Info("备份旧版本: %s -> %s", m.agPath, backupPath)
			if err := os.Rename(m.agPath, backupPath); err != nil {
				m.printer.Warning("备份失败: %v", err)
			}
		}
	}

	// 下载新版本
	filename := m.GetPlatformFile(version)
	downloadURL := sdkBaseURL + filename
	tmpFile := filepath.Join(m.installDir, filename)

	if err := m.DownloadWithProgress(downloadURL, tmpFile, progressChan); err != nil {
		return err
	}

	// 设置可执行权限
	if err := os.Chmod(tmpFile, 0755); err != nil {
		return fmt.Errorf("设置权限失败: %v", err)
	}

	// 重命名为 ag
	if err := os.Rename(tmpFile, m.agPath); err != nil {
		return fmt.Errorf("安装失败: %v", err)
	}

	// 保存版本号
	versionFile := m.agPath + ".version"
	if err := os.WriteFile(versionFile, []byte(version), 0644); err != nil {
		m.printer.Warning("保存版本号失败: %v", err)
	}

	m.printer.Success("安装完成: %s (v%s)", m.agPath, version)
	return nil
}

// AutoInstallIfNotExists 如果不存在则自动安装
func (m *AGManager) AutoInstallIfNotExists(progressChan chan<- int) error {
	if m.IsInstalled() {
		return nil
	}

	m.printer.Info("未检测到 AG，开始自动安装...")

	// 获取最新版本
	versions, err := m.FetchChangelog()
	if err != nil {
		return err
	}

	if len(versions) == 0 {
		return fmt.Errorf("未找到可用版本")
	}

	latestVersion := versions[0].Version
	return m.Install(latestVersion, progressChan)
}
