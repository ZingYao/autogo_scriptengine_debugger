package js

import (
	"fmt"
	"math/rand"
	"regexp"
	"strings"
	"time"
	"unicode"

	jsobfuscator "github.com/ZingYao/autogo_scriptengine_debugger/obfuscator/js/v8obfuscator"
)

// Obfuscator JavaScript 代码混淆器
type Obfuscator struct {
	rand        *rand.Rand
	v8obfuscator *jsobfuscator.Obfuscator
}

// ObfuscatorOptions 混淆选项
type ObfuscatorOptions struct {
	// 基础选项
	RenameVariables  bool // 重命名变量
	EncodeStrings    bool // 编码字符串
	RemoveComments   bool // 移除注释
	RemoveWhitespace bool // 移除空白

	// 高级选项
	AddJunkCode        bool // 添加垃圾代码
	ControlFlowFlatten bool // 控制流扁平化

	// 字符串加密选项
	StringArray        bool // 字符串数组提取
	StringArrayEncrypt bool // 字符串数组加密（强制加密所有字符串）
	StringArrayRotate  bool // 字符串数组旋转
	StringArrayShuffle bool // 字符串数组打乱
	SplitStrings       bool // 分割字符串

	// 其他高级选项
	NumbersToExpressions bool // 数字转表达式
	DeadCodeInjection    bool // 死代码注入
	SelfDefending        bool // 自我保护
	DisableConsoleOutput bool // 禁用控制台输出
	
	// 模块兼容选项
	ModuleFriendly bool // 模块友好模式，保留 module/exports/require
}

// DefaultObfuscatorOptions 默认混淆选项
func DefaultObfuscatorOptions() ObfuscatorOptions {
	return ObfuscatorOptions{
		RenameVariables:      true,
		EncodeStrings:        true,
		RemoveComments:       true,
		RemoveWhitespace:     true,
		AddJunkCode:          false,
		ControlFlowFlatten:   true,
		StringArray:          true,
		StringArrayEncrypt:   true, // 强制加密所有字符串
		StringArrayRotate:    true,
		StringArrayShuffle:   true,
		SplitStrings:         true,
		NumbersToExpressions: true,
		DeadCodeInjection:    false,
		SelfDefending:        false,
		DisableConsoleOutput: false,
		ModuleFriendly:       true, // 默认启用模块友好模式
	}
}

// HighSecurityOptions 高安全级别混淆选项
func HighSecurityOptions() ObfuscatorOptions {
	return ObfuscatorOptions{
		RenameVariables:      true,
		EncodeStrings:        true,
		RemoveComments:       true,
		RemoveWhitespace:     true,
		AddJunkCode:          true,
		ControlFlowFlatten:   true,
		StringArray:          true,
		StringArrayEncrypt:   true,
		StringArrayRotate:    true,
		StringArrayShuffle:   true,
		SplitStrings:         true,
		NumbersToExpressions: true,
		DeadCodeInjection:    true,
		SelfDefending:        true,
		DisableConsoleOutput: true,
		ModuleFriendly:       true, // 高安全模式也启用模块友好
	}
}

// NewObfuscator 创建新的 JS 混淆器
func NewObfuscator() *Obfuscator {
	// 初始化 V8 混淆器
	v8obf, err := jsobfuscator.NewObfuscator()
	if err != nil {
		// 如果 V8 初始化失败，仍然返回一个可用的混淆器（使用内置实现）
		return &Obfuscator{
			rand: rand.New(rand.NewSource(time.Now().UnixNano())),
		}
	}

	return &Obfuscator{
		rand:         rand.New(rand.NewSource(time.Now().UnixNano())),
		v8obfuscator: v8obf,
	}
}

// Close 关闭混淆器
func (o *Obfuscator) Close() {
	// V8 混淆器不需要显式关闭
}

// Obfuscate 混淆 JavaScript 代码
func (o *Obfuscator) Obfuscate(source string, options ObfuscatorOptions) (string, error) {
	// 如果 V8 混淆器可用，使用它进行高级混淆
	if o.v8obfuscator != nil {
		return o.obfuscateWithV8(source, options)
	}

	// 否则使用内置的简单混淆
	return o.obfuscateSimple(source, options)
}

// obfuscateWithV8 使用 V8 引擎进行高级混淆
func (o *Obfuscator) obfuscateWithV8(source string, options ObfuscatorOptions) (string, error) {
	// 根据选项选择混淆配置
	var v8opts jsobfuscator.Options
	if options.ModuleFriendly {
		// 使用模块友好的选项
		v8opts = jsobfuscator.ModuleFriendlyOptions()
	} else {
		// 使用默认选项
		v8opts = jsobfuscator.DefaultOptions()
	}

	// 如果是模块友好模式，保护 module.exports 语句
	var moduleExportsStmt string
	var protectedSource string
	if options.ModuleFriendly {
		// 提取 module.exports = ... 语句（匹配整行，包括分号）
		// 支持多种格式：
		// - module.exports = xxx;
		// - module.exports = { ... };
		// - module.exports = function() { ... };
		moduleExportsPattern := regexp.MustCompile(`(?s)module\.exports\s*=\s*[^;]+;?\s*$`)
		match := moduleExportsPattern.FindString(source)
		if match != "" {
			// 保存 module.exports 语句
			moduleExportsStmt = strings.TrimSpace(match)
			// 从源码中移除 module.exports 语句
			protectedSource = strings.TrimSpace(moduleExportsPattern.ReplaceAllString(source, ""))
		} else {
			protectedSource = source
		}
	} else {
		protectedSource = source
	}

	result, err := o.v8obfuscator.ObfuscateWithOptions(protectedSource, v8opts)
	if err != nil {
		// 如果 V8 混淆失败，回退到简单混淆
		return o.obfuscateSimple(source, options)
	}

	// 注意：不再额外进行字符串加密，因为 V8 混淆器已经处理了字符串
	// 额外的字符串加密可能导致与 goja 引擎不兼容的代码

	// 如果是模块友好模式，恢复 module.exports 语句
	if options.ModuleFriendly && moduleExportsStmt != "" {
		result = result + "\n" + moduleExportsStmt
	}

	return result, nil
}

// encryptRemainingStrings 加密剩余的可见字符串
func (o *Obfuscator) encryptRemainingStrings(source string) string {
	// 匹配所有字符串常量（包括反引号字符串）
	// 修复：使用 [^`\\\\] 代替 [^`\\]，确保在正则表达式中正确匹配"不是反引号或反斜杠的字符"
	stringPattern := regexp.MustCompile("`([^`\\\\]|\\\\.)*`|\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'")

	result := stringPattern.ReplaceAllStringFunc(source, func(match string) string {
		// 跳过已经混淆的字符串（通常包含 _0x 前缀的变量名）
		if strings.Contains(match, "_0x") || strings.Contains(match, "\\x") {
			return match
		}

		// 提取字符串内容
		var content string
		if strings.HasPrefix(match, "`") {
			content = match[1 : len(match)-1]
		} else if strings.HasPrefix(match, "\"") {
			content = match[1 : len(match)-1]
		} else {
			content = match[1 : len(match)-1]
		}

		// 如果内容太短，不加密
		if len(content) < 2 {
			return match
		}

		// 使用 XOR 加密
		encoded := o.encodeStringWithXOR(content)
		return fmt.Sprintf(`(function(){var t=[%s];return t.join('');})()`, encoded)
	})

	return result
}

// encodeStringWithXOR 使用 XOR 加密字符串
func (o *Obfuscator) encodeStringWithXOR(s string) string {
	var parts []string
	for _, c := range s {
		// 使用 XOR 加密
		key := o.rand.Intn(256)
		encrypted := int(c) ^ key
		parts = append(parts, fmt.Sprintf("String.fromCharCode(%d^%d)", encrypted, key))
	}
	return strings.Join(parts, ",")
}

// obfuscateSimple 使用内置实现进行简单混淆
func (o *Obfuscator) obfuscateSimple(source string, options ObfuscatorOptions) (string, error) {
	result := source

	// 1. 移除注释
	if options.RemoveComments {
		result = o.removeComments(result)
	}

	// 2. 重命名变量
	if options.RenameVariables {
		result = o.renameVariables(result)
	}

	// 3. 编码字符串
	if options.EncodeStrings {
		result = o.encodeStrings(result)
	}

	// 4. 数字转表达式
	if options.NumbersToExpressions {
		result = o.encodeNumbers(result)
	}

	// 5. 控制流混淆
	if options.ControlFlowFlatten {
		result = o.controlFlowObfuscate(result)
	}

	// 6. 移除空白
	if options.RemoveWhitespace {
		result = o.removeWhitespace(result)
	}

	// 7. 添加垃圾代码
	if options.AddJunkCode {
		result = o.addJunkCode(result)
	}

	// 8. 死代码注入
	if options.DeadCodeInjection {
		result = o.injectDeadCode(result)
	}

	return result, nil
}

// removeComments 移除 JavaScript 注释
func (o *Obfuscator) removeComments(source string) string {
	// 移除单行注释
	singleLineComment := regexp.MustCompile(`//[^\n]*`)
	result := singleLineComment.ReplaceAllString(source, "")

	// 移除多行注释
	multiLineComment := regexp.MustCompile(`/\*.*?\*/`)
	result = multiLineComment.ReplaceAllString(result, "")

	return result
}

// generateRandomName 生成随机变量名
func (o *Obfuscator) generateRandomName() string {
	chars := "Il1O0"
	length := 8 + o.rand.Intn(8)
	name := make([]byte, length)
	for i := range name {
		name[i] = chars[o.rand.Intn(len(chars))]
	}
	return "_" + string(name)
}

// renameVariables 重命名变量
func (o *Obfuscator) renameVariables(source string) string {
	varPattern := regexp.MustCompile(`\b(var|let|const)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)`)

	variables := make(map[string]string)
	matches := varPattern.FindAllStringSubmatch(source, -1)

	for _, match := range matches {
		if len(match) > 2 {
			varName := match[2]
			if o.isReservedWord(varName) {
				continue
			}
			if _, exists := variables[varName]; !exists {
				variables[varName] = o.generateRandomName()
			}
		}
	}

	funcPattern := regexp.MustCompile(`\bfunction\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*\(`)
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

	result := source
	for oldName, newName := range variables {
		pattern := regexp.MustCompile(`\b` + regexp.QuoteMeta(oldName) + `\b`)
		result = pattern.ReplaceAllString(result, newName)
	}

	return result
}

// isReservedWord 检查是否是 JavaScript 保留字
func (o *Obfuscator) isReservedWord(word string) bool {
	reserved := map[string]bool{
		"break": true, "case": true, "catch": true, "continue": true,
		"debugger": true, "default": true, "delete": true, "do": true,
		"else": true, "finally": true, "for": true, "function": true,
		"if": true, "in": true, "instanceof": true, "new": true,
		"return": true, "switch": true, "this": true, "throw": true,
		"try": true, "typeof": true, "var": true, "void": true,
		"while": true, "with": true, "let": true, "const": true,
		"class": true, "extends": true, "export": true, "import": true,
		"super": true, "implements": true, "interface": true, "package": true,
		"private": true, "protected": true, "public": true, "static": true,
		"yield": true, "await": true,
		"console": true, "window": true, "document": true,
		"Object": true, "Array": true, "String": true, "Number": true,
		"Boolean": true, "Function": true, "Symbol": true,
		"Math": true, "Date": true, "RegExp": true, "Error": true,
		"JSON": true, "Promise": true, "Map": true, "Set": true,
		"require": true, "module": true, "exports": true,
		"undefined": true, "null": true, "true": true, "false": true,
		"NaN": true, "Infinity": true,
	}
	return reserved[word]
}

// encodeStrings 编码字符串常量
func (o *Obfuscator) encodeStrings(source string) string {
	stringPattern := regexp.MustCompile(`"([^"\\]|\\.)*"|'([^'\\]|\\.)*'`)

	result := stringPattern.ReplaceAllStringFunc(source, func(match string) string {
		content := match[1 : len(match)-1]
		encoded := o.encodeString(content)
		return fmt.Sprintf(`(function(){var t=[%s];return t.join('');})()`, encoded)
	})

	return result
}

// encodeString 将字符串编码为字符数组
func (o *Obfuscator) encodeString(s string) string {
	var parts []string
	for _, c := range s {
		// 使用 XOR 加密
		key := o.rand.Intn(256)
		encrypted := int(c) ^ key
		parts = append(parts, fmt.Sprintf("String.fromCharCode(%d^%d)", encrypted, key))
	}
	return strings.Join(parts, ",")
}

// encodeNumbers 将数字编码为表达式
func (o *Obfuscator) encodeNumbers(source string) string {
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
	a := o.rand.Intn(1000) + 1
	b := n + a
	return fmt.Sprintf("(%d-%d)", b, a)
}

// controlFlowObfuscate 控制流混淆
func (o *Obfuscator) controlFlowObfuscate(source string) string {
	// 简单的控制流混淆：添加不透明谓词
	predicate := fmt.Sprintf("/* %s */", o.generateRandomName())
	return predicate + source
}

// removeWhitespace 移除多余空白
func (o *Obfuscator) removeWhitespace(source string) string {
	lines := strings.Split(source, "\n")
	var result []string
	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return strings.Join(result, " ")
}

// addJunkCode 添加垃圾代码
func (o *Obfuscator) addJunkCode(source string) string {
	junkFunctions := []string{
		"var %s = function(){return undefined;};",
		"var %s = Math.random();",
		"var %s = ''.length;",
	}

	var junkLines []string
	for i := 0; i < 5; i++ {
		template := junkFunctions[o.rand.Intn(len(junkFunctions))]
		junkLines = append(junkLines, fmt.Sprintf(template, o.generateRandomName()))
	}

	return strings.Join(junkLines, " ") + " " + source
}

// injectDeadCode 注入死代码
func (o *Obfuscator) injectDeadCode(source string) string {
	deadCode := fmt.Sprintf(`
if (false) {
	var %s = function() { return "dead code"; };
	%s();
}
`, o.generateRandomName(), o.generateRandomName())

	return deadCode + source
}

// ObfuscateWithKey 使用密钥混淆代码
func (o *Obfuscator) ObfuscateWithKey(source string, key string, options ObfuscatorOptions) (string, error) {
	result, err := o.Obfuscate(source, options)
	if err != nil {
		return "", err
	}

	keyCheck := fmt.Sprintf(`
(function(){
	var _k = "%s";
	var _v = function(){return true;};
})();
`, key)

	return keyCheck + result, nil
}

// IsIdentifier 检查字符串是否是有效的 JavaScript 标识符
func IsIdentifier(s string) bool {
	if len(s) == 0 {
		return false
	}
	if !unicode.IsLetter(rune(s[0])) && s[0] != '_' && s[0] != '$' {
		return false
	}
	for _, c := range s[1:] {
		if !unicode.IsLetter(c) && !unicode.IsDigit(c) && c != '_' && c != '$' {
			return false
		}
	}
	return true
}
