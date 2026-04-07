package lua

import (
	"fmt"
	"math/rand"
	"regexp"
	"strings"
	"time"
	"unicode"
)

// Obfuscator Lua 代码混淆器
type Obfuscator struct {
	rand *rand.Rand
}

// ObfuscatorOptions 混淆选项
type ObfuscatorOptions struct {
	// 基础选项
	RenameVariables  bool // 重命名局部变量
	EncodeStrings    bool // 编码字符串常量
	RemoveComments   bool // 移除注释
	RemoveWhitespace bool // 移除多余空白

	// 高级选项
	AddJunkCode        bool // 添加垃圾代码
	ControlFlowFlatten bool // 控制流扁平化

	// 字符串加密选项
	StringEncryption    bool // 字符串加密
	StringEncryptionKey int  // XOR 加密密钥

	// 数字编码
	NumberEncoding bool // 数字转表达式

	// 死代码注入
	DeadCodeInjection bool // 死代码注入

	// 反调试
	AntiDebugging bool // 反调试检测
}

// DefaultObfuscatorOptions 默认混淆选项
func DefaultObfuscatorOptions() ObfuscatorOptions {
	return ObfuscatorOptions{
		RenameVariables:    true,
		EncodeStrings:      true,
		RemoveComments:     true,
		RemoveWhitespace:   true,
		AddJunkCode:        false,
		ControlFlowFlatten: true,
		StringEncryption:   true,
		NumberEncoding:     true,
		DeadCodeInjection:  false,
		AntiDebugging:      false,
	}
}

// HighSecurityOptions 高安全级别混淆选项
func HighSecurityOptions() ObfuscatorOptions {
	return ObfuscatorOptions{
		RenameVariables:    true,
		EncodeStrings:      true,
		RemoveComments:     true,
		RemoveWhitespace:   true,
		AddJunkCode:        true,
		ControlFlowFlatten: true,
		StringEncryption:   true,
		NumberEncoding:     true,
		DeadCodeInjection:  true,
		AntiDebugging:      true,
	}
}

// NewObfuscator 创建新的 Lua 混淆器
func NewObfuscator() *Obfuscator {
	return &Obfuscator{
		rand: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

// Obfuscate 混淆 Lua 代码
func (o *Obfuscator) Obfuscate(source string, options ObfuscatorOptions) (string, error) {
	result := source

	// 1. 移除注释
	if options.RemoveComments {
		result = o.removeComments(result)
	}

	// 2. 重命名局部变量
	if options.RenameVariables {
		result = o.renameVariables(result)
	}

	// 3. 字符串加密
	if options.StringEncryption {
		result = o.encryptStrings(result, options.StringEncryptionKey)
	} else if options.EncodeStrings {
		result = o.encodeStrings(result)
	}

	// 4. 数字编码
	if options.NumberEncoding {
		result = o.encodeNumbers(result)
	}

	// 5. 控制流混淆
	if options.ControlFlowFlatten {
		result = o.controlFlowFlatten(result)
	}

	// 6. 死代码注入
	if options.DeadCodeInjection {
		result = o.injectDeadCode(result)
	}

	// 7. 添加垃圾代码
	if options.AddJunkCode {
		result = o.addJunkCode(result)
	}

	// 8. 反调试
	if options.AntiDebugging {
		result = o.addAntiDebugging(result)
	}

	// 9. 移除多余空白
	if options.RemoveWhitespace {
		result = o.removeWhitespace(result)
	}

	return result, nil
}

// removeComments 移除 Lua 注释
func (o *Obfuscator) removeComments(source string) string {
	// 移除单行注释
	singleLineComment := regexp.MustCompile(`--[^\n]*`)
	result := singleLineComment.ReplaceAllString(source, "")

	// 移除多行注释 --[[ ... ]]
	multiLineComment := regexp.MustCompile(`--\[\[.*?\]\]`)
	result = multiLineComment.ReplaceAllString(result, "")

	// 移除多行注释 --[=[ ... ]=] 等嵌套形式
	nestedComment := regexp.MustCompile(`--\[=+\[.*?\]=+\]`)
	result = nestedComment.ReplaceAllString(result, "")

	return result
}

// generateRandomName 生成随机变量名
func (o *Obfuscator) generateRandomName() string {
	// 使用混合字符生成难以阅读的变量名
	chars := "Il1O0"
	length := 8 + o.rand.Intn(8)
	name := make([]byte, length)
	for i := range name {
		name[i] = chars[o.rand.Intn(len(chars))]
	}
	return "_" + string(name)
}

// renameVariables 重命名局部变量
func (o *Obfuscator) renameVariables(source string) string {
	// 匹配局部变量声明
	localVarPattern := regexp.MustCompile(`local\s+([a-zA-Z_][a-zA-Z0-9_]*)`)

	// 收集所有局部变量名
	variables := make(map[string]string)
	matches := localVarPattern.FindAllStringSubmatch(source, -1)

	for _, match := range matches {
		if len(match) > 1 {
			varName := match[1]
			// 跳过保留字
			if o.isReservedWord(varName) {
				continue
			}
			// 生成新的变量名
			if _, exists := variables[varName]; !exists {
				variables[varName] = o.generateRandomName()
			}
		}
	}

	// 匹配函数声明
	funcPattern := regexp.MustCompile(`function\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(`)
	funcMatches := funcPattern.FindAllStringSubmatch(source, -1)

	for _, match := range funcMatches {
		if len(match) > 1 {
			funcName := match[1]
			if o.isReservedWord(funcName) {
				continue
			}
			if _, exists := variables[funcName]; !exists {
				variables[funcName] = o.generateRandomName()
			}
		}
	}

	// 替换变量名
	result := source
	for oldName, newName := range variables {
		// 使用单词边界匹配，避免替换变量名的一部分
		pattern := regexp.MustCompile(`\b` + regexp.QuoteMeta(oldName) + `\b`)
		result = pattern.ReplaceAllString(result, newName)
	}

	return result
}

// isReservedWord 检查是否是 Lua 保留字
func (o *Obfuscator) isReservedWord(word string) bool {
	reserved := map[string]bool{
		"and": true, "break": true, "do": true, "else": true,
		"elseif": true, "end": true, "false": true, "for": true,
		"function": true, "if": true, "in": true, "local": true,
		"nil": true, "not": true, "or": true, "repeat": true,
		"return": true, "then": true, "true": true, "until": true,
		"while": true, "require": true, "print": true,
		// 常用全局变量和函数
		"pairs": true, "ipairs": true, "next": true, "type": true,
		"tostring": true, "tonumber": true, "pcall": true, "xpcall": true,
		"error": true, "assert": true, "collectgarbage": true,
		"select": true, "unpack": true, "pack": true, "rawget": true,
		"rawset": true, "setmetatable": true, "getmetatable": true,
		"string": true, "table": true, "math": true, "os": true,
		"io": true, "debug": true, "coroutine": true,
	}
	return reserved[word]
}

// encryptStrings 加密字符串常量（使用 XOR 加密）
func (o *Obfuscator) encryptStrings(source string, key int) string {
	if key == 0 {
		key = o.rand.Intn(255) + 1
	}

	// 匹配字符串常量
	stringPattern := regexp.MustCompile(`"([^"\\]|\\.)*"|'([^'\\]|\\.)*'`)

	// 生成解密函数
	decryptFunc := fmt.Sprintf(`
local _d = function(s, k)
    local r = {}
    for i = 1, #s do
        r[i] = string.char(string.byte(s, i) ~ k)
    end
    return table.concat(r)
end
`)

	result := stringPattern.ReplaceAllStringFunc(source, func(match string) string {
		// 提取字符串内容
		content := match[1 : len(match)-1]

		// XOR 加密
		encrypted := o.xorEncrypt(content, key)

		// 生成解密调用
		return fmt.Sprintf(`_d("%s", %d)`, encrypted, key)
	})

	// 在代码开头添加解密函数
	return decryptFunc + result
}

// xorEncrypt XOR 加密字符串
func (o *Obfuscator) xorEncrypt(s string, key int) string {
	var result strings.Builder
	for _, c := range s {
		encrypted := int(c) ^ key
		// 转义特殊字符
		switch encrypted {
		case '\n':
			result.WriteString("\\n")
		case '\r':
			result.WriteString("\\r")
		case '\t':
			result.WriteString("\\t")
		case '\\':
			result.WriteString("\\\\")
		case '"':
			result.WriteString("\\\"")
		default:
			if encrypted >= 32 && encrypted <= 126 {
				result.WriteByte(byte(encrypted))
			} else {
				result.WriteString(fmt.Sprintf("\\%d", encrypted))
			}
		}
	}
	return result.String()
}

// encodeStrings 编码字符串常量（转换为字符数组）
func (o *Obfuscator) encodeStrings(source string) string {
	// 匹配字符串常量
	stringPattern := regexp.MustCompile(`"([^"\\]|\\.)*"|'([^'\\]|\\.)*'`)

	result := stringPattern.ReplaceAllStringFunc(source, func(match string) string {
		// 提取字符串内容
		content := match[1 : len(match)-1]

		// 生成编码后的字符串
		encoded := o.encodeString(content)
		return fmt.Sprintf(`(function() local t={%s} return table.concat(t) end)()`, encoded)
	})

	return result
}

// encodeString 将字符串编码为字符数组
func (o *Obfuscator) encodeString(s string) string {
	var parts []string
	for _, c := range s {
		// 使用字符编码
		parts = append(parts, fmt.Sprintf("string.char(%d)", c))
	}
	return strings.Join(parts, ",")
}

// encodeNumbers 将数字编码为表达式
func (o *Obfuscator) encodeNumbers(source string) string {
	// 匹配数字（不包括小数点和科学计数法）
	numberPattern := regexp.MustCompile(`\b(\d+)\b`)

	result := numberPattern.ReplaceAllStringFunc(source, func(match string) string {
		var num int
		fmt.Sscanf(match, "%d", &num)
		return o.numberToExpression(num)
	})

	return result
}

// numberToExpression 将数字转换为复杂表达式
func (o *Obfuscator) numberToExpression(n int) string {
	// 随机选择一种转换方式
	switch o.rand.Intn(3) {
	case 0:
		// 加法表达式
		a := o.rand.Intn(1000) + 1
		b := n + a
		return fmt.Sprintf("(%d-%d)", b, a)
	case 1:
		// 乘法表达式
		a := o.rand.Intn(10) + 2
		b := n*a + o.rand.Intn(a)
		c := b / a
		d := b % a
		if d == 0 {
			return fmt.Sprintf("(%d/%d)", b, a)
		}
		return fmt.Sprintf("(%d/%d)", c*a, a)
	default:
		// 位运算表达式
		a := o.rand.Intn(8)
		shifted := n << a
		return fmt.Sprintf("(%d>>%d)", shifted, a)
	}
}

// controlFlowFlatten 控制流扁平化
func (o *Obfuscator) controlFlowFlatten(source string) string {
	// 生成状态机变量
	stateVar := o.generateRandomName()
	counterVar := o.generateRandomName()

	// 添加控制流混淆头部
	header := fmt.Sprintf(`
local %s = 1
local %s = 0
while %s do
    %s = %s + 1
    if %s > 10000 then break end
`, stateVar, counterVar, stateVar, counterVar, counterVar, counterVar)

	// 添加尾部
	footer := `
    break
end
`

	return header + source + footer
}

// injectDeadCode 注入死代码
func (o *Obfuscator) injectDeadCode(source string) string {
	// 生成永远不会执行的代码块
	deadCode := fmt.Sprintf(`
if false then
    local %s = function() return nil end
    local %s = math.random()
    local %s = "dead code injection"
end
`, o.generateRandomName(), o.generateRandomName(), o.generateRandomName())

	return deadCode + source
}

// addJunkCode 添加垃圾代码
func (o *Obfuscator) addJunkCode(source string) string {
	// 生成一些无意义的代码
	junkFunctions := []string{
		"local %s = function() return nil end",
		"local %s = math.random()",
		"local %s = string.len('')",
		"local %s = table.concat({})",
	}

	var junkLines []string
	for i := 0; i < 5; i++ {
		template := junkFunctions[o.rand.Intn(len(junkFunctions))]
		junkLines = append(junkLines, fmt.Sprintf(template, o.generateRandomName()))
	}

	// 在代码开头插入垃圾代码
	return strings.Join(junkLines, "; ") + "; " + source
}

// addAntiDebugging 添加反调试检测
func (o *Obfuscator) addAntiDebugging(source string) string {
	antiDebug := `
-- Anti-debugging checks
local _ad = function()
    -- 检测调试库
    if debug and debug.getinfo then
        local info = debug.getinfo(2)
        if info and info.what == "C" then
            return true
        end
    end
    -- 检测执行时间
    local start = os.clock()
    for i = 1, 1000 do end
    if os.clock() - start > 0.1 then
        return true
    end
    return false
end
if _ad() then return end
`
	return antiDebug + source
}

// removeWhitespace 移除多余空白
func (o *Obfuscator) removeWhitespace(source string) string {
	// 移除行首行尾空白
	lines := strings.Split(source, "\n")
	var result []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" {
			result = append(result, trimmed)
		}
	}

	// 合并为单行
	return strings.Join(result, " ")
}

// ObfuscateWithKey 使用密钥混淆代码
func (o *Obfuscator) ObfuscateWithKey(source string, key string, options ObfuscatorOptions) (string, error) {
	result, err := o.Obfuscate(source, options)
	if err != nil {
		return "", err
	}

	// 添加密钥相关的混淆层
	keyCheck := fmt.Sprintf(`
local _k = "%s"
local _v = function() return true end
`, key)

	return keyCheck + result, nil
}

// StringToHexTable 将字符串转换为十六进制表
func StringToHexTable(s string) string {
	var parts []string
	for _, c := range s {
		parts = append(parts, fmt.Sprintf("0x%02x", c))
	}
	return "{" + strings.Join(parts, ", ") + "}"
}

// StringToCharCodes 将字符串转换为字符编码
func StringToCharCodes(s string) string {
	var parts []string
	for _, c := range s {
		parts = append(parts, fmt.Sprintf("%d", c))
	}
	return strings.Join(parts, ",")
}

// GenerateStringDecoder 生成字符串解码函数
func GenerateStringDecoder() string {
	return `
local _decode = function(t)
    local r = {}
    for i, v in ipairs(t) do
        r[i] = string.char(v)
    end
    return table.concat(r)
end
`
}

// IsIdentifier 检查字符串是否是有效的 Lua 标识符
func IsIdentifier(s string) bool {
	if len(s) == 0 {
		return false
	}
	if !unicode.IsLetter(rune(s[0])) && s[0] != '_' {
		return false
	}
	for _, c := range s[1:] {
		if !unicode.IsLetter(c) && !unicode.IsDigit(c) && c != '_' {
			return false
		}
	}
	return true
}
