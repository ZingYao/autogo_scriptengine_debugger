package builder

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"text/template"

	"github.com/ZingYao/autogo_scriptengine_debugger/config"
	"github.com/ZingYao/autogo_scriptengine_debugger/obfuscator"
)

// BuildOptions 构建选项
type BuildOptions struct {
	CodeStyle       string
	ScriptType      string
	LoadMode        config.ScriptLoadMode
	EnableObfuscate bool
	EnableBytecode  bool
	EnableEncrypt   bool
	EncryptionKey   string

	// 加载模式配置
	HTTPScriptURL    string
	SDCardScriptPath string
	EmbedMainScript  string

	// 路径配置
	ProjectPath string
	ScriptsDir  string
	OutputPath  string

	// 模板内容（如果为空则从文件系统读取）
	TemplateContent string
}

// Builder 构建代码生成器
type Builder struct {
	processor *obfuscator.Processor
}

// NewBuilder 创建构建器
func NewBuilder() *Builder {
	return &Builder{
		processor: obfuscator.NewProcessor(),
	}
}

// Close 关闭构建器
func (b *Builder) Close() {
	if b.processor != nil {
		b.processor.Close()
	}
}

// GenerateBuildCode 生成构建代码
func (b *Builder) GenerateBuildCode(options BuildOptions) error {
	// 读取模板文件（优先使用传入的模板内容，否则从文件系统读取）
	var templateContent []byte
	var err error
	if options.TemplateContent != "" {
		templateContent = []byte(options.TemplateContent)
	} else {
		templatePath := filepath.Join(options.ProjectPath, "project", "build.go.code")
		templateContent, err = os.ReadFile(templatePath)
		if err != nil {
			return fmt.Errorf("读取模板文件失败: %w", err)
		}
	}

	// 准备模板数据
	data := map[string]interface{}{
		"CodeStyle":        options.CodeStyle,
		"ScriptType":       options.ScriptType,
		"LoadMode":         string(options.LoadMode),
		"EnableObfuscate":  options.EnableObfuscate,
		"EnableBytecode":   options.EnableBytecode,
		"EnableEncrypt":    options.EnableEncrypt,
		"EncryptionKey":    options.EncryptionKey,
		"HTTPScriptURL":    options.HTTPScriptURL,
		"SDCardScriptPath": options.SDCardScriptPath,
		"EmbedMainScript":  options.EmbedMainScript,
	}

	// 解析模板
	tmpl, err := template.New("build").Parse(string(templateContent))
	if err != nil {
		return fmt.Errorf("解析模板失败: %w", err)
	}

	// 执行模板
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		return fmt.Errorf("执行模板失败: %w", err)
	}

	// 后处理：移除未使用的加载模式代码
	result := b.postProcess(buf.String(), options)

	// 写入输出文件
	outputPath := options.OutputPath
	if outputPath == "" {
		outputPath = filepath.Join(options.ProjectPath, "build.go")
	}

	if err := os.WriteFile(outputPath, []byte(result), 0644); err != nil {
		return fmt.Errorf("写入输出文件失败: %w", err)
	}

	return nil
}

// postProcess 后处理生成的代码
func (b *Builder) postProcess(code string, options BuildOptions) string {
	result := code

	// 根据加载模式移除未使用的代码块
	switch options.LoadMode {
	case config.LoadModeHTTP:
		// 保留 HTTP 加载代码，移除 SDCard 和 Embed
		result = b.removeCodeBlock(result, "sdcard")
		result = b.removeCodeBlock(result, "embed")
	case config.LoadModeSDCard:
		// 保留 SDCard 加载代码，移除 HTTP 和 Embed
		result = b.removeCodeBlock(result, "http")
		result = b.removeCodeBlock(result, "embed")
	case config.LoadModeEmbed:
		// 保留 Embed 加载代码，移除 HTTP 和 SDCard
		result = b.removeCodeBlock(result, "http")
		result = b.removeCodeBlock(result, "sdcard")
	}

	// 如果未启用加密，移除解密相关代码
	if !options.EnableEncrypt {
		result = b.removeCodeBlock(result, "encrypt")
	}

	return result
}

// removeCodeBlock 移除标记的代码块
func (b *Builder) removeCodeBlock(code string, tag string) string {
	// 使用简单的标记方式移除代码块
	// 标记格式: // {{if eq .LoadMode "xxx"}} ... // {{end}}
	startMarker := fmt.Sprintf("// {{if eq .LoadMode \"%s\"}}", tag)
	endMarker := "// {{end}}"

	// 查找并移除代码块
	startIdx := strings.Index(code, startMarker)
	for startIdx != -1 {
		endIdx := strings.Index(code[startIdx:], endMarker)
		if endIdx == -1 {
			break
		}
		endIdx += startIdx + len(endMarker)
		code = code[:startIdx] + code[endIdx:]
		startIdx = strings.Index(code, startMarker)
	}

	return code
}

// ProcessScript 处理脚本文件
func (b *Builder) ProcessScript(scriptPath string, scriptType obfuscator.ScriptType, options BuildOptions) (*obfuscator.ProcessingResult, error) {
	// 读取脚本内容
	content, err := os.ReadFile(scriptPath)
	if err != nil {
		return nil, fmt.Errorf("读取脚本文件失败: %w", err)
	}

	// 准备处理选项
	procOptions := obfuscator.ProcessingOptions{
		Obfuscate:        options.EnableObfuscate,
		Bytecode:         options.EnableBytecode && scriptType == obfuscator.ScriptTypeLua,
		Encrypt:          options.EnableEncrypt,
		EncryptionKey:    options.EncryptionKey,
		RenameVariables:  true,
		EncodeStrings:    true,
		RemoveComments:   true,
		RemoveWhitespace: true,
	}

	// 处理脚本
	result, err := b.processor.Process(string(content), scriptType, procOptions)
	if err != nil {
		return nil, fmt.Errorf("处理脚本失败: %w", err)
	}

	return result, nil
}

// GenerateFromConfig 从配置生成构建代码
func (b *Builder) GenerateFromConfig(cfg *config.ProjectConfig, projectPath string, templateContent string) error {
	// 确定脚本类型
	var scriptType string
	if cfg.SelectedScript != "" {
		ext := strings.ToLower(filepath.Ext(cfg.SelectedScript))
		switch ext {
		case ".lua":
			scriptType = "lua"
		case ".js":
			scriptType = "javascript"
		}
	}

	if scriptType == "" {
		scriptType = "lua" // 默认 Lua
	}

	options := BuildOptions{
		CodeStyle:         cfg.CodeStyle,
		ScriptType:        scriptType,
		LoadMode:          cfg.Build.LoadMode,
		EnableObfuscate:   cfg.Security.BuildObfuscate,
		EnableBytecode:    cfg.Security.BuildBytecode,
		EnableEncrypt:     cfg.Security.BuildEncrypt,
		EncryptionKey:     cfg.Security.EncryptionKey,
		HTTPScriptURL:     cfg.Build.HTTPScriptURL,
		SDCardScriptPath:  cfg.Build.SDCardScriptPath,
		EmbedMainScript:   cfg.Build.EmbedMainScript,
		ProjectPath:       projectPath,
		ScriptsDir:        filepath.Join(projectPath, "scripts"),
		TemplateContent:   templateContent,
	}

	return b.GenerateBuildCode(options)
}

// ProcessScriptsForEmbed 处理嵌入模式的脚本
func (b *Builder) ProcessScriptsForEmbed(scriptsDir string, options BuildOptions) (map[string]*obfuscator.ProcessingResult, error) {
	results := make(map[string]*obfuscator.ProcessingResult)

	// 读取脚本目录
	entries, err := os.ReadDir(scriptsDir)
	if err != nil {
		return nil, fmt.Errorf("读取脚本目录失败: %w", err)
	}

	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}

		name := entry.Name()
		ext := strings.ToLower(filepath.Ext(name))

		var scriptType obfuscator.ScriptType
		switch ext {
		case ".lua":
			scriptType = obfuscator.ScriptTypeLua
		case ".js":
			scriptType = obfuscator.ScriptTypeJavaScript
		default:
			continue
		}

		scriptPath := filepath.Join(scriptsDir, name)
		result, err := b.ProcessScript(scriptPath, scriptType, options)
		if err != nil {
			return nil, fmt.Errorf("处理脚本 %s 失败: %w", name, err)
		}

		results[name] = result
	}

	return results, nil
}

// GenerateEmbedFiles 生成嵌入文件
func (b *Builder) GenerateEmbedFiles(scriptsDir string, outputDir string, options BuildOptions) error {
	// 处理所有脚本
	results, err := b.ProcessScriptsForEmbed(scriptsDir, options)
	if err != nil {
		return err
	}

	// 确保输出目录存在
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return fmt.Errorf("创建输出目录失败: %w", err)
	}

	// 写入处理后的脚本
	for name, result := range results {
		outputPath := filepath.Join(outputDir, name)

		var content []byte
		if result.WasEncrypted {
			// 如果加密了，写入加密数据
			content = []byte(result.EncryptedData)
		} else if result.WasBytecode {
			// 如果是字节码，写入字节码
			content = result.Bytecode
		} else {
			// 否则写入处理后的代码
			content = []byte(result.ProcessedCode)
		}

		if err := os.WriteFile(outputPath, content, 0644); err != nil {
			return fmt.Errorf("写入文件 %s 失败: %w", name, err)
		}
	}

	return nil
}
