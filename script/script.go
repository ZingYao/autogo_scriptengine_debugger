package script

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"time"

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
}

// NewRunner 创建脚本运行器
func NewRunner(serverAddr string, p *printer.Printer) *Runner {
	return &Runner{
		serverAddr: serverAddr,
		printer:    p,
		debug:      p.IsDebugEnabled(), // 从 printer 获取 debug 状态
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
	r.debugPrint("创建设备目录: %s", deviceDir)
	mkdirCmd := exec.Command(r.adbPath, "-s", r.deviceID, "shell", "mkdir", "-p", deviceDir)
	if output, err := mkdirCmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("创建目录失败: %v\n%s", err, output)
	}

	// 2. 推送整个 scripts 目录
	r.debugPrint("推送脚本目录到设备: %s -> %s", localScriptsDir, deviceScriptsDir)
	pushCmd := exec.Command(r.adbPath, "-s", r.deviceID, "push", localScriptsDir, deviceDir+"/")
	if output, err := pushCmd.CombinedOutput(); err != nil {
		return "", fmt.Errorf("推送脚本失败: %v\n%s", err, output)
	}

	r.debugSuccess("脚本推送完成")
	return deviceScriptsDir, nil
}

// Run 运行脚本
func (r *Runner) Run(scriptFile, scriptType, codeStyle string) error {
	if scriptFile == "" {
		return fmt.Errorf("未指定脚本文件")
	}

	absPath, err := filepath.Abs(scriptFile)
	if err != nil {
		return err
	}

	if _, err := os.Stat(absPath); os.IsNotExist(err) {
		r.debugPrint("脚本文件不存在: %s", absPath)
		r.debugPrint("原始路径: %s", scriptFile)
		r.debugPrint("当前工作目录: %s", mustGetwd())
		return fmt.Errorf("脚本文件不存在")
	}

	// 推送脚本到设备
	deviceScriptsDir, err := r.pushScripts(filepath.Dir(absPath))
	if err != nil {
		return fmt.Errorf("推送脚本失败: %v", err)
	}

	// 使用设备上的脚本路径
	deviceScriptPath := deviceScriptsDir + "/" + filepath.Base(absPath)

	r.debugPrint("脚本类型: %s", scriptType)
	r.debugPrint("代码风格: %s", codeStyle)
	r.debugPrint("本地路径: %s", absPath)
	r.debugPrint("设备路径: %s", deviceScriptPath)
	r.debugPrint("工作目录: %s", deviceScriptsDir)

	request := RequestMessage{
		ScriptType: scriptType,
		CodeStyle:  codeStyle,
		Operation:  OpRun,
		ScriptPath: deviceScriptPath,
		WorkDir:    deviceScriptsDir, // 设置工作目录，用于 require 搜索
	}

	response, err := r.sendRequest(request)
	if err != nil {
		return err
	}

	r.printJSON(response)

	if response.Success {
		r.debugSuccess("脚本启动成功")
	} else {
		r.debugError("脚本启动失败")
	}

	return nil
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
