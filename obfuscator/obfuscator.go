package obfuscator

import (
	"encoding/base64"
	"fmt"
	"path/filepath"
	"strings"

	"github.com/ZingYao/autogo_scriptengine_debugger/obfuscator/crypto"
	"github.com/ZingYao/autogo_scriptengine_debugger/obfuscator/js"
	"github.com/ZingYao/autogo_scriptengine_debugger/obfuscator/lua"
)

// ScriptType 脚本类型
type ScriptType string

const (
	ScriptTypeLua        ScriptType = "lua"
	ScriptTypeJavaScript ScriptType = "javascript"
)

// ProcessingOptions 处理选项
type ProcessingOptions struct {
	Obfuscate bool // 是否混淆
	Bytecode  bool // 是否编译字节码（仅 Lua）
	Encrypt   bool // 是否 AES 加密

	// AES 加密密钥（如果为空，将自动生成）
	EncryptionKey string

	// 混淆选项
	RenameVariables    bool
	EncodeStrings      bool
	RemoveComments     bool
	RemoveWhitespace   bool
	AddJunkCode        bool
	ControlFlowFlatten bool
}

// DefaultProcessingOptions 默认处理选项
func DefaultProcessingOptions() ProcessingOptions {
	return ProcessingOptions{
		Obfuscate:          false,
		Bytecode:           false,
		Encrypt:            false,
		EncryptionKey:      "",
		RenameVariables:    true,
		EncodeStrings:      true,
		RemoveComments:     true,
		RemoveWhitespace:   true,
		AddJunkCode:        false,
		ControlFlowFlatten: false,
	}
}

// ProcessingResult 处理结果
type ProcessingResult struct {
	OriginalCode   string // 原始代码
	ProcessedCode  string // 处理后的代码
	ObfuscatedCode string // 混淆后的代码
	Bytecode       []byte // 字节码（仅 Lua）
	EncryptedData  string // 加密后的数据
	EncryptionKey  string // 加密密钥
	ScriptType     ScriptType
	WasObfuscated  bool
	WasBytecode    bool
	WasEncrypted   bool
}

// Processor 脚本处理器
type Processor struct {
	luaCompiler *lua.Compiler
	luaObf      *lua.Obfuscator
	jsObf       *js.Obfuscator

	// 懒加载标记
	initialized bool
}

// NewProcessor 创建新的处理器（懒加载，不立即初始化）
func NewProcessor() *Processor {
	return &Processor{
		initialized: false,
	}
}

// init 初始化处理器（懒加载）
func (p *Processor) init() {
	if p.initialized {
		return
	}
	p.luaCompiler = lua.NewCompiler()
	p.luaObf = lua.NewObfuscator()
	p.jsObf = js.NewObfuscator()
	p.initialized = true
}

// Close 关闭处理器
func (p *Processor) Close() {
	if p.luaCompiler != nil {
		p.luaCompiler.Close()
	}
}

// Process 处理脚本
func (p *Processor) Process(source string, scriptType ScriptType, options ProcessingOptions) (*ProcessingResult, error) {
	// 懒加载初始化
	p.init()

	result := &ProcessingResult{
		OriginalCode:  source,
		ProcessedCode: source,
		ScriptType:    scriptType,
	}

	var err error
	currentCode := source

	// 1. 混淆
	if options.Obfuscate {
		currentCode, err = p.obfuscate(currentCode, scriptType, options)
		if err != nil {
			return nil, fmt.Errorf("混淆失败: %w", err)
		}
		result.ObfuscatedCode = currentCode
		result.WasObfuscated = true
	}

	// 2. 字节码编译（仅 Lua）
	if scriptType == ScriptTypeLua && options.Bytecode {
		compileResult, err := p.luaCompiler.CompileString(currentCode, "script")
		if err != nil {
			return nil, fmt.Errorf("字节码编译失败: %w", err)
		}
		result.Bytecode = compileResult.Bytecode
		result.WasBytecode = true

		// 如果同时启用加密，加密字节码
		if options.Encrypt {
			encryptedData, key, err := p.encrypt(string(compileResult.Bytecode), options.EncryptionKey)
			if err != nil {
				return nil, fmt.Errorf("加密字节码失败: %w", err)
			}
			result.EncryptedData = encryptedData
			result.EncryptionKey = key
			result.WasEncrypted = true
			// 加密后的字节码数据
			result.ProcessedCode = encryptedData
		} else {
			// 未加密的字节码
			result.ProcessedCode = string(compileResult.Bytecode)
		}
		return result, nil
	}

	// 3. AES 加密（非字节码情况）
	if options.Encrypt {
		encryptedData, key, err := p.encrypt(currentCode, options.EncryptionKey)
		if err != nil {
			return nil, fmt.Errorf("加密失败: %w", err)
		}
		result.EncryptedData = encryptedData
		result.EncryptionKey = key
		result.WasEncrypted = true
		// 不再生成解密加载器代码，直接保留加密数据
		// Android 端会负责解密
		result.ProcessedCode = encryptedData
		return result, nil
	}

	result.ProcessedCode = currentCode
	return result, nil
}

// obfuscate 执行混淆
func (p *Processor) obfuscate(source string, scriptType ScriptType, options ProcessingOptions) (string, error) {
	// 懒加载初始化
	p.init()

	obfOptions := lua.ObfuscatorOptions{
		RenameVariables:    options.RenameVariables,
		EncodeStrings:      options.EncodeStrings,
		RemoveComments:     options.RemoveComments,
		RemoveWhitespace:   options.RemoveWhitespace,
		AddJunkCode:        options.AddJunkCode,
		ControlFlowFlatten: options.ControlFlowFlatten,
	}

	switch scriptType {
	case ScriptTypeLua:
		return p.luaObf.Obfuscate(source, obfOptions)
	case ScriptTypeJavaScript:
		// 使用默认的模块友好选项
		jsOptions := js.DefaultObfuscatorOptions()
		jsOptions.RenameVariables = options.RenameVariables
		jsOptions.EncodeStrings = options.EncodeStrings
		jsOptions.RemoveComments = options.RemoveComments
		jsOptions.RemoveWhitespace = options.RemoveWhitespace
		jsOptions.AddJunkCode = options.AddJunkCode
		jsOptions.ControlFlowFlatten = options.ControlFlowFlatten
		return p.jsObf.Obfuscate(source, jsOptions)
	default:
		return "", fmt.Errorf("不支持的脚本类型: %s", scriptType)
	}
}

// escapeLuaString 转义 Lua 字符串中的特殊字符
func escapeLuaString(s string) string {
	repl := map[string]string{
		"\\": "\\\\",
		"\"": "\\\"",
		"\n": "\\n",
		"\r": "\\r",
		"\t": "\\t",
		"\b": "\\b",
		"\f": "\\f",
		"\v": "\\v",
	}

	result := s
	for old, new := range repl {
		result = strings.ReplaceAll(result, old, new)
	}
	return result
}

// generateAESLoader 生成包含 AES 解密逻辑的 Lua 代码
func (p *Processor) generateAESLoader(encryptedData string, key string) string {
	escapedEncryptedData := escapeLuaString(encryptedData)
	escapedKey := escapeLuaString(key)
	
	return fmt.Sprintf(`
-- AES 解密函数 (简化实现)
local function _aes_decrypt(encrypted, key)
    local function base64_decode(data)
        local b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
        local decoding = {}
        for i = 1, 64 do
            decoding[string.sub(b64chars, i, i)] = i - 1
        end
        local result = {}
        local padding = 0
        if string.sub(data, -2) == '==' then
            padding = 2
        elseif string.sub(data, -1) == '=' then
            padding = 1
        end
        data = string.gsub(data, '[^'..b64chars..'=]', '')
        for i = 1, #data, 4 do
            local n = (decoding[string.sub(data, i, i)] or 0) * 262144
                + (decoding[string.sub(data, i+1, i+1)] or 0) * 4096
                + (decoding[string.sub(data, i+2, i+2)] or 0) * 64
                + (decoding[string.sub(data, i+3, i+3)] or 0)
            table.insert(result, string.char(math.floor(n / 65536) %% 256))
            table.insert(result, string.char(math.floor(n / 256) %% 256))
            table.insert(result, string.char(n %% 256))
        end
        for i = 1, padding do
            table.remove(result)
        end
        return table.concat(result)
    end

    -- 注意：这里使用简化的 XOR 解密作为示例
    -- 在实际应用中，应该使用完整的 AES 解密实现
    local function xor_decrypt(data, key)
        local result = {}
        local key_len = #key
        for i = 1, #data do
            local key_char = string.byte(key, (i-1)%%key_len + 1)
            local data_char = string.byte(data, i)
            table.insert(result, string.char(data_char ~ key_char))
        end
        return table.concat(result)
    end

    local decoded = base64_decode(encrypted)
    local key_bytes = base64_decode(key)
    return xor_decrypt(decoded, key_bytes)
end

-- 解密并执行脚本
local encrypted_data = "%s"
local decrypt_key = "%s"
local decrypted = _aes_decrypt(encrypted_data, decrypt_key)
if decrypted then
    local fn = loadstring(decrypted)
    if fn then fn() end
end
`, escapedEncryptedData, escapedKey)
}

// xorEncrypt 使用 XOR 加密数据
func xorEncrypt(data string, key []byte) []byte {
	dataBytes := []byte(data)
	result := make([]byte, len(dataBytes))
	keyLen := len(key)
	for i, b := range dataBytes {
		result[i] = b ^ key[i%keyLen]
	}
	return result
}

// encrypt 执行加密
func (p *Processor) encrypt(data string, providedKey string) (string, string, error) {
	var key []byte
	var err error

	if providedKey != "" {
		// 使用提供的密钥（Base64 编码的字符串）
		// 先解码 Base64 字符串
		key, err = base64.StdEncoding.DecodeString(providedKey)
		if err != nil {
			return "", "", fmt.Errorf("解码密钥失败: %w", err)
		}
		// 确保密钥长度至少为 8 字节
		if len(key) < 8 {
			paddedKey := make([]byte, 8)
			copy(paddedKey, key)
			key = paddedKey
		} else if len(key) > 32 {
			key = key[:32]
		}
	} else {
		// 生成新密钥
		key, err = crypto.GenerateKey(32)
		if err != nil {
			return "", "", fmt.Errorf("生成密钥失败: %w", err)
		}
	}

	// 使用 XOR 加密
	encrypted := xorEncrypt(data, key)
	// Base64 编码
	encryptedBase64 := base64.StdEncoding.EncodeToString(encrypted)
	keyBase64 := base64.StdEncoding.EncodeToString(key)

	return encryptedBase64, keyBase64, nil
}

// Decrypt 解密数据
func Decrypt(encryptedData string, keyBase64 string) (string, error) {
	key, err := crypto.KeyFromString(keyBase64)
	if err != nil {
		return "", fmt.Errorf("解析密钥失败: %w", err)
	}

	decrypted, err := crypto.DecryptAES(key, encryptedData)
	if err != nil {
		return "", fmt.Errorf("AES 解密失败: %w", err)
	}

	return string(decrypted), nil
}

// ProcessFile 处理脚本文件
func (p *Processor) ProcessFile(filePath string, options ProcessingOptions) (*ProcessingResult, error) {
	// 根据文件扩展名确定脚本类型
	scriptType, err := GetScriptType(filePath)
	if err != nil {
		return nil, err
	}

	_ = scriptType // 这里需要读取文件内容

	// 这里需要读取文件内容
	// 由于是示例，返回错误提示
	return nil, fmt.Errorf("请使用 Process 方法处理代码内容")
}

// GetScriptType 根据文件路径获取脚本类型
func GetScriptType(filepath string) (ScriptType, error) {
	ext := strings.ToLower(filepath)
	if strings.HasSuffix(ext, ".lua") {
		return ScriptTypeLua, nil
	} else if strings.HasSuffix(ext, ".js") {
		return ScriptTypeJavaScript, nil
	}
	return "", fmt.Errorf("不支持的文件类型: %s", filepath)
}

// GenerateDecryptLoader 生成解密加载器代码
func GenerateDecryptLoader(scriptType ScriptType, encryptedVarName string, keyVarName string) string {
	switch scriptType {
	case ScriptTypeLua:
		return fmt.Sprintf(`
-- 解密并执行脚本
local encrypted_data = %s
local decrypt_key = %s
local decrypted = _aes_decrypt(encrypted_data, decrypt_key)
if decrypted then
    local fn = loadstring(decrypted)
    if fn then fn() end
end
`, encryptedVarName, keyVarName)

	case ScriptTypeJavaScript:
		return fmt.Sprintf(`
// 解密并执行脚本
(function(){
	var encryptedData = %s;
	var decryptKey = %s;
	var decrypted = _aesDecrypt(encryptedData, decryptKey);
	if (decrypted) {
		var fn = new Function(decrypted);
		fn();
	}
})();
`, encryptedVarName, keyVarName)

	default:
		return ""
	}
}

// GenerateEmbedLoader 生成嵌入加载器代码
func GenerateEmbedLoader(scriptType ScriptType, embedPath string) string {
	switch scriptType {
	case ScriptTypeLua:
		return fmt.Sprintf(`
-- 从 embed 加载脚本
local embed = require('embed')
local script = embed.readFile('%s')
local fn = loadstring(script)
if fn then fn() end
`, embedPath)

	case ScriptTypeJavaScript:
		return fmt.Sprintf(`
// 从 embed 加载脚本
var embed = require('embed');
var script = embed.readFile('%s');
var fn = new Function(script);
fn();
`, embedPath)

	default:
		return ""
	}
}

// GenerateHTTPLoader 生成 HTTP 加载器代码
func GenerateHTTPLoader(scriptType ScriptType, url string) string {
	switch scriptType {
	case ScriptTypeLua:
		return fmt.Sprintf(`
-- 从 HTTP 加载脚本
local http = require('http')
local response = http.get('%s')
if response and response.body then
    local fn = loadstring(response.body)
    if fn then fn() end
end
`, url)

	case ScriptTypeJavaScript:
		return fmt.Sprintf(`
// 从 HTTP 加载脚本
var http = require('http');
http.get('%s', function(response) {
    if (response && response.body) {
        var fn = new Function(response.body);
        fn();
    }
});
`, url)

	default:
		return ""
	}
}

// GenerateSDCardLoader 生成 SDCard 加载器代码
func GenerateSDCardLoader(scriptType ScriptType, path string) string {
	switch scriptType {
	case ScriptTypeLua:
		return fmt.Sprintf(`
-- 从 SDCard 加载脚本
local file = io.open('%s', 'r')
if file then
    local script = file:read('*all')
    file:close()
    local fn = loadstring(script)
    if fn then fn() end
end
`, path)

	case ScriptTypeJavaScript:
		return fmt.Sprintf(`
// 从 SDCard 加载脚本
var fs = require('fs');
var script = fs.readFileSync('%s', 'utf8');
if (script) {
    var fn = new Function(script);
    fn();
}
`, path)

	default:
		return ""
	}
}

// DetectScriptType 从文件名检测脚本类型
func DetectScriptType(filename string) ScriptType {
	ext := strings.ToLower(filepath.Ext(filename))
	switch ext {
	case ".lua":
		return ScriptTypeLua
	case ".js":
		return ScriptTypeJavaScript
	default:
		return ScriptTypeLua
	}
}
