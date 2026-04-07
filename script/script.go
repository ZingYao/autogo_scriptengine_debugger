package script

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	"github.com/ZingYao/autogo_scriptengine_debugger/config"
	"github.com/ZingYao/autogo_scriptengine_debugger/obfuscator"
	"github.com/ZingYao/autogo_scriptengine_debugger/printer"
)

// OperationType 操作类型
type OperationType string

const (
	OpRun      OperationType = "run"
	OpStop     OperationType = "stop"
	OpPause    OperationType = "pause"
	OpResume   OperationType = "resume"
	OpStatus   OperationType = "status"
	OpGetError OperationType = "get_error"
)

// RequestMessage 请求消息
type RequestMessage struct {
	ScriptType string                 `json:"script_type"`
	CodeStyle  string                 `json:"code_style"`
	Operation  OperationType          `json:"operation"`
	ScriptPath string                 `json:"script_path"`
	WorkDir    string                 `json:"work_dir"` // 工作目录,用于 require 搜索路径
	ScriptID   string                 `json:"script_id"`
	Parameters map[string]interface{} `json:"parameters"`

	// 调试模式
	Debug bool `json:"debug"` // 是否启用调试模式（打印详细日志）

	// 调试模式安全选项（通过 Socket 传输到 Android 客户端）
	DebugObfuscate bool `json:"debug_obfuscate"` // 调试时是否混淆代码
	DebugBytecode  bool `json:"debug_bytecode"`  // 调试时是否编译字节码（仅 Lua）
	DebugEncrypt   bool `json:"debug_encrypt"`   // 调试时是否 AES 加密

	// 加密密钥（如果启用加密，需要传输密钥）
	EncryptionKey string `json:"encryption_key,omitempty"` // AES 加密密钥（Base64 编码）

	// 清理目录（脚本执行完成后需要删除的目录）
	CleanupDir string `json:"cleanup_dir,omitempty"` // 脚本执行完成后需要删除的目录路径

	// 清理配置
	AutoCleanup bool `json:"auto_cleanup"` // 是否启用自动清理
}

// ResponseMessage 响应消息
type ResponseMessage struct {
	Success   bool                   `json:"success"`
	Status    string                 `json:"status"`
	Error     string                 `json:"error"`
	Message   string                 `json:"message"`
	Data      map[string]interface{} `json:"data"`
	ScriptID  string                 `json:"script_id"`
	Timestamp int64                  `json:"timestamp"`
}

// Runner 脚本运行器
type Runner struct {
	serverAddr string // TCP 服务器地址（IP:Port）
	printer    *printer.Printer
	deviceID   string
	adbPath    string
	debug      bool                                     // 是否启用调试模式
	debugLog   func(format string, args ...interface{}) // 调试日志输出函数

	// 安全配置
	securityConfig *config.SecurityConfig
	processor      *obfuscator.Processor
}

// NewRunner 创建脚本运行器
func NewRunner(serverAddr string, p *printer.Printer) *Runner {
	return &Runner{
		serverAddr: serverAddr,
		printer:    p,
		debug:      p.IsDebugEnabled(), // 从 printer 获取 debug 状态
		processor:  obfuscator.NewProcessor(),
	}
}

// SetSecurityConfig 设置安全配置
func (r *Runner) SetSecurityConfig(cfg *config.SecurityConfig) {
	r.securityConfig = cfg
}

// Close 关闭运行器
func (r *Runner) Close() {
	if r.processor != nil {
		r.processor.Close()
	}
}

// SetDebugLog 设置调试日志输出函数
func (r *Runner) SetDebugLog(fn func(format string, args ...interface{})) {
	r.debugLog = fn
}

// SetDevice 设置设备信息
func (r *Runner) SetDevice(deviceID, adbPath string) {
	r.deviceID = deviceID
	r.adbPath = adbPath
}

// pushScripts 推送脚本目录到设备
// 返回值: 设备上的实际脚本目录路径（包含随机文件夹名）, 错误
func (r *Runner) pushScripts(localScriptsDir string) (string, error) {
	if r.deviceID == "" {
		return "", fmt.Errorf("未设置设备ID")
	}
	if r.adbPath == "" {
		r.adbPath = "adb"
	}

	deviceDir := "/sdcard/autogo_scriptengine_debugger"
	deviceScriptsDir := deviceDir + "/scripts"

	// 1. 在设备上创建目录
	r.debugPrint("创建设备目录: %s", deviceScriptsDir)
	mkdirCmd := exec.Command(r.adbPath, "-s", r.deviceID, "shell", "mkdir", "-p", deviceScriptsDir)
	if output, err := mkdirCmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("创建目录失败: %v\n%s", err, output)
	}

	// 获取本地目录的文件夹名称（如 autogo_scripts_3207304448）
	localDirName := filepath.Base(localScriptsDir)
	r.debugPrint("本地脚本目录名称: %s", localDirName)

	// 2. 推送整个脚本目录到设备
	// 推送后设备路径为: /sdcard/autogo_scriptengine_debugger/scripts/autogo_scripts_xxx/
	r.debugPrint("推送脚本目录到设备: %s -> %s", localScriptsDir, deviceScriptsDir)
	pushCmd := exec.Command(r.adbPath, "-s", r.deviceID, "push", localScriptsDir, deviceScriptsDir+"/")
	if output, err := pushCmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("推送脚本失败: %v\n%s", err, output)
	}

	// 3. 构建设备上的实际脚本目录路径（包含随机文件夹名）
	actualDeviceScriptsDir := deviceScriptsDir + "/" + localDirName
	r.debugPrint("设备上的实际脚本目录: %s", actualDeviceScriptsDir)

	r.debugSuccess("脚本推送完成")
	return actualDeviceScriptsDir, nil
}

// Run 运行脚本
// autoCleanup: 是否在脚本执行完成后自动清理临时目录
func (r *Runner) Run(scriptFile, scriptType, codeStyle string, autoCleanup bool) error {
	if scriptFile == "" {
		return fmt.Errorf("未指定脚本文件")
	}

	absPath, err := filepath.Abs(scriptFile)
	if err != nil {
		return err
	}

	if info, err := os.Stat(absPath); err != nil {
		r.debugPrint("脚本文件不存在: %s", absPath)
		r.debugPrint("原始路径: %s", scriptFile)
		r.debugPrint("当前工作目录: %s", mustGetwd())
		return fmt.Errorf("脚本文件不存在")
	} else if info.IsDir() {
		return fmt.Errorf("脚本路径是一个目录: %s", absPath)
	}

	// 确定脚本类型
	scriptTypeEnum := obfuscator.ScriptTypeLua
	if strings.ToLower(filepath.Ext(absPath)) == ".js" {
		scriptTypeEnum = obfuscator.ScriptTypeJavaScript
	}

	// 处理脚本（根据安全配置）
	mainScriptName := filepath.Base(absPath)
	processedScriptsDir, err := r.processScripts(filepath.Dir(absPath), scriptTypeEnum, mainScriptName)
	if err != nil {
		return fmt.Errorf("处理脚本失败: %v", err)
	}

	// 推送处理后的脚本到设备
	deviceScriptsDir, err := r.pushScripts(processedScriptsDir)
	if err != nil {
		return fmt.Errorf("推送脚本失败: %v", err)
	}

	// 使用设备上的脚本路径
	baseName := filepath.Base(absPath)
	r.debugPrint("filepath.Base(absPath): '%s'", baseName)
	if baseName == "" {
		return fmt.Errorf("无法获取脚本文件名: %s", absPath)
	}
	deviceScriptPath := deviceScriptsDir + "/" + baseName

	r.debugPrint("脚本类型: %s", scriptType)
	r.debugPrint("代码风格: %s", codeStyle)
	r.debugPrint("本地路径: %s", absPath)
	r.debugPrint("设备路径: %s", deviceScriptPath)
	r.debugPrint("工作目录: %s", deviceScriptsDir)

	// 准备请求消息，处理安全配置可能为 nil 的情况
	var debugObfuscate, debugBytecode, debugEncrypt bool
	var encryptionKey string
	if r.securityConfig != nil {
		debugObfuscate = r.securityConfig.DebugObfuscate
		debugBytecode = r.securityConfig.DebugBytecode
		debugEncrypt = r.securityConfig.DebugEncrypt
		encryptionKey = r.securityConfig.EncryptionKey
	}

	// 构建清理目录路径（只有启用自动清理时才设置）
	var cleanupDir string
	if autoCleanup {
		cleanupDir = deviceScriptsDir
	}

	request := RequestMessage{
		ScriptType: scriptType,
		CodeStyle:  codeStyle,
		Operation:  OpRun,
		ScriptPath: deviceScriptPath,
		WorkDir:    deviceScriptsDir, // 设置工作目录，用于 require 搜索

		// 调试模式安全选项（通过 Socket 传输到 Android 客户端）
		DebugObfuscate: debugObfuscate,
		DebugBytecode:  debugBytecode,
		DebugEncrypt:   debugEncrypt,
		EncryptionKey:  encryptionKey,

		// 设置清理目录（脚本执行完成后需要删除的随机文件夹）
		CleanupDir: cleanupDir,

		// 清理配置
		AutoCleanup: autoCleanup,
	}

	// 记录调试模式选项
	r.debugPrint("调试模式选项: 混淆=%v, 字节码=%v, 加密=%v",
		debugObfuscate, debugBytecode, debugEncrypt)

	response, err := r.sendRequest(request)
	if err != nil {
		return fmt.Errorf("发送请求失败: %w", err)
	}

	r.printJSON(response)

	if response.Success {
		r.debugSuccess("脚本启动成功")
	} else {
		// 提供详细的错误信息
		errMsg := "脚本启动失败"
		if response.Error != "" {
			errMsg = fmt.Sprintf("脚本启动失败: %s", response.Error)
		}
		if response.Message != "" {
			errMsg = fmt.Sprintf("%s (%s)", errMsg, response.Message)
		}
		r.debugError(errMsg)
		return fmt.Errorf("%s", errMsg)
	}

	return nil
}

// processScripts 处理脚本目录中的所有脚本
func (r *Runner) processScripts(scriptsDir string, scriptType obfuscator.ScriptType, mainScriptName string) (string, error) {
	// 如果没有安全配置，直接返回原始目录
	if r.securityConfig == nil {
		r.debugPrint("未配置安全选项，跳过脚本处理")
		return scriptsDir, nil
	}

	// 检查是否需要处理
	needProcess := r.securityConfig.DebugObfuscate ||
		r.securityConfig.DebugBytecode ||
		r.securityConfig.DebugEncrypt

	if !needProcess {
		r.debugPrint("安全选项未启用，跳过脚本处理")
		return scriptsDir, nil
	}

	r.debugPrint("开始处理脚本...")
	r.debugPrint("混淆: %v, 字节码: %v, 加密: %v",
		r.securityConfig.DebugObfuscate,
		r.securityConfig.DebugBytecode,
		r.securityConfig.DebugEncrypt)

	// 创建临时目录
	tempDir, err := os.MkdirTemp("", "autogo_scripts_*")
	if err != nil {
		return "", fmt.Errorf("创建临时目录失败: %w", err)
	}

	// 读取脚本目录
	entries, err := os.ReadDir(scriptsDir)
	if err != nil {
		os.RemoveAll(tempDir)
		return "", fmt.Errorf("读取脚本目录失败: %w", err)
	}

	// 处理每个脚本文件
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}

		name := entry.Name()
		ext := strings.ToLower(filepath.Ext(name))

		// 只处理 Lua 和 JS 文件
		if ext != ".lua" && ext != ".js" {
			// 复制非脚本文件
			srcPath := filepath.Join(scriptsDir, name)
			dstPath := filepath.Join(tempDir, name)
			data, err := os.ReadFile(srcPath)
			if err != nil {
				r.debugWarning("读取文件 %s 失败: %v", name, err)
				continue
			}
			os.WriteFile(dstPath, data, 0644)
			continue
		}

		// 确定脚本类型
		currentScriptType := obfuscator.ScriptTypeLua
		if ext == ".js" {
			currentScriptType = obfuscator.ScriptTypeJavaScript
		}

		// 读取脚本内容
		scriptPath := filepath.Join(scriptsDir, name)
		content, err := os.ReadFile(scriptPath)
		if err != nil {
			os.RemoveAll(tempDir)
			return "", fmt.Errorf("读取脚本 %s 失败: %w", name, err)
		}

		// 准备处理选项
		options := obfuscator.ProcessingOptions{
			Obfuscate:        r.securityConfig.DebugObfuscate,
			Bytecode:         r.securityConfig.DebugBytecode && currentScriptType == obfuscator.ScriptTypeLua,
			Encrypt:          r.securityConfig.DebugEncrypt,
			EncryptionKey:    r.securityConfig.EncryptionKey,
			RenameVariables:  true,
			EncodeStrings:    true,
			RemoveComments:   true,
			RemoveWhitespace: true,
		}

		// 处理脚本
		result, err := r.processor.Process(string(content), currentScriptType, options)
		if err != nil {
			os.RemoveAll(tempDir)
			return "", fmt.Errorf("处理脚本 %s 失败: %w", name, err)
		}

		// 如果加密，打印加密前的原始数据（仅用于调试，只打印主脚本）
		if result.WasEncrypted && name == mainScriptName {
			r.debugPrint("=== 加密调试信息 ===")
			r.debugPrint("文件: %s", name)
			r.debugPrint("混淆后代码前 20 字节 (hex): %x", []byte(result.ObfuscatedCode)[:20])
			r.debugPrint("加密密钥: %s", result.EncryptionKey)
			r.debugPrint("=== 加密调试信息结束 ===")
		}

		// 写入处理后的脚本
		outputPath := filepath.Join(tempDir, name)
		var outputData []byte

		if result.WasBytecode {
			// 字节码模式：写入字节码数据（可能是加密的或未加密的）
			outputData = []byte(result.ProcessedCode)
		} else if result.WasEncrypted {
			// 加密的源码
			outputData = []byte(result.ProcessedCode)
		} else {
			// 混淆或原始源码
			outputData = []byte(result.ProcessedCode)
		}

		if err := os.WriteFile(outputPath, outputData, 0644); err != nil {
			os.RemoveAll(tempDir)
			return "", fmt.Errorf("写入脚本 %s 失败: %w", name, err)
		}

		// 验证：读取写入的文件并打印前 20 字节（只打印主脚本）
		if result.WasEncrypted && name == mainScriptName {
			verifyData, err := os.ReadFile(outputPath)
			if err != nil {
				r.debugPrint("验证失败：无法读取文件 %s: %v", outputPath, err)
			} else {
				if len(verifyData) >= 20 {
					r.debugPrint("写入文件前 20 字节 (ASCII): %s", string(verifyData[:20]))
				}
			}
		}

		// 如果生成了新密钥，保存它
		if result.WasEncrypted && result.EncryptionKey != "" && r.securityConfig.EncryptionKey == "" {
			r.securityConfig.EncryptionKey = result.EncryptionKey
			r.debugPrint("已生成新的加密密钥")
		}
	}

	r.debugSuccess("脚本处理完成")
	return tempDir, nil
}

// Stop 停止脚本
func (r *Runner) Stop(scriptID string) error {
	if scriptID == "" {
		return fmt.Errorf("未指定脚本ID")
	}

	r.debugPrint("停止脚本: %s", scriptID)

	request := RequestMessage{
		Operation: OpStop,
		ScriptID:  scriptID,
	}

	response, err := r.sendRequest(request)
	if err != nil {
		return err
	}

	r.printJSON(response)

	if response.Success {
		r.debugSuccess("脚本已停止")
	} else {
		r.debugError("停止失败")
	}

	return nil
}

// Pause 暂停脚本
func (r *Runner) Pause(scriptID string) error {
	if scriptID == "" {
		return fmt.Errorf("未指定脚本ID")
	}

	r.debugPrint("暂停脚本: %s", scriptID)

	request := RequestMessage{
		Operation: OpPause,
		ScriptID:  scriptID,
	}

	response, err := r.sendRequest(request)
	if err != nil {
		return err
	}

	r.printJSON(response)

	if response.Success {
		r.debugSuccess("脚本已暂停")
	} else {
		r.debugError("暂停失败")
	}

	return nil
}

// Resume 恢复脚本
func (r *Runner) Resume(scriptID string) error {
	if scriptID == "" {
		return fmt.Errorf("未指定脚本ID")
	}

	r.debugPrint("恢复脚本: %s", scriptID)

	request := RequestMessage{
		Operation: OpResume,
		ScriptID:  scriptID,
	}

	response, err := r.sendRequest(request)
	if err != nil {
		return err
	}

	r.printJSON(response)

	if response.Success {
		r.debugSuccess("脚本已恢复")
	} else {
		r.debugError("恢复失败")
	}

	return nil
}

// GetStatus 获取状态
func (r *Runner) GetStatus(scriptID string) error {
	if scriptID == "" {
		return fmt.Errorf("未指定脚本ID")
	}

	r.debugPrint("查询状态: %s", scriptID)

	request := RequestMessage{
		Operation: OpStatus,
		ScriptID:  scriptID,
	}

	response, err := r.sendRequest(request)
	if err != nil {
		return err
	}

	r.printJSON(response)
	return nil
}

// GetError 获取错误信息
func (r *Runner) GetError(scriptID string) error {
	if scriptID == "" {
		return fmt.Errorf("未指定脚本ID")
	}

	r.debugPrint("获取错误信息: %s", scriptID)

	request := RequestMessage{
		Operation: OpGetError,
		ScriptID:  scriptID,
	}

	response, err := r.sendRequest(request)
	if err != nil {
		return err
	}

	r.printJSON(response)
	return nil
}

// sendRequest 发送 TCP 请求
func (r *Runner) sendRequest(req RequestMessage) (*ResponseMessage, error) {
	// 连接到 TCP 服务器
	conn, err := net.DialTimeout("tcp", r.serverAddr, 10*time.Second)
	if err != nil {
		return nil, fmt.Errorf("连接服务器失败: %v", err)
	}
	defer conn.Close()

	// 设置读写超时
	conn.SetDeadline(time.Now().Add(10 * time.Second))

	// 序列化请求
	data, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("序列化请求失败: %v", err)
	}

	// 打印发送的原始 JSON 数据（调试用）
	if req.Operation == OpRun {
		r.debugPrint("=== 发送的原始 JSON 数据 ===")
		r.debugPrint("%s", string(data))
		r.debugPrint("=== 发送的原始 JSON 数据结束 ===")
	}

	// 发送请求（以换行符结尾）
	_, err = conn.Write(append(data, '\n'))
	if err != nil {
		return nil, fmt.Errorf("发送请求失败: %v", err)
	}

	// 读取响应（以换行符结尾）
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, fmt.Errorf("读取响应失败: %v", err)
	}

	// 去掉换行符
	responseData := buf[:n]
	if n > 0 && responseData[n-1] == '\n' {
		responseData = responseData[:n-1]
	}

	// 解析响应
	var response ResponseMessage
	if err := json.Unmarshal(responseData, &response); err != nil {
		return nil, fmt.Errorf("解析响应失败: %v", err)
	}

	return &response, nil
}

// printJSON 打印JSON响应（仅调试模式输出）
func (r *Runner) printJSON(resp *ResponseMessage) {
	if !r.debug {
		return
	}
	pretty, _ := json.MarshalIndent(resp, "", "  ")
	if r.debugLog != nil {
		r.debugLog("[cyan]%s[white]", string(pretty))
	} else {
		r.printer.Println("%s", string(pretty))
	}
}

// debugPrint 输出调试信息（仅调试模式输出）
func (r *Runner) debugPrint(format string, args ...interface{}) {
	if !r.debug {
		return
	}
	if r.debugLog != nil {
		r.debugLog("[blue][INFO][white] %s", fmt.Sprintf(format, args...))
	} else {
		r.printer.Info(format, args...)
	}
}

// debugSuccess 输出成功信息（仅调试模式输出）
func (r *Runner) debugSuccess(format string, args ...interface{}) {
	if !r.debug {
		return
	}
	if r.debugLog != nil {
		r.debugLog("[green][SUCCESS][white] %s", fmt.Sprintf(format, args...))
	} else {
		r.printer.Success(format, args...)
	}
}

// debugError 输出错误信息（始终输出）
func (r *Runner) debugError(format string, args ...interface{}) {
	if r.debugLog != nil {
		r.debugLog("[red][ERROR][white] %s", fmt.Sprintf(format, args...))
	} else {
		r.printer.Error(format, args...)
	}
}

// debugWarning 输出警告信息（仅调试模式输出）
func (r *Runner) debugWarning(format string, args ...interface{}) {
	if !r.debug {
		return
	}
	if r.debugLog != nil {
		r.debugLog("[yellow][WARNING][white] %s", fmt.Sprintf(format, args...))
	} else {
		r.printer.Warning(format, args...)
	}
}

func mustGetwd() string {
	dir, _ := os.Getwd()
	return dir
}
