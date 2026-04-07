package lua

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"math"

	lua "github.com/yuin/gopher-lua"
)

// CompileResult 编译结果
type CompileResult struct {
	Bytecode    []byte // 字节码数据
	BytecodeStr string // Base64 编码的字节码字符串
	SourceSize  int    // 源码大小
	ResultSize  int    // 结果大小
}

// Compiler Lua 字节码编译器
type Compiler struct {
	state *lua.LState
}

// NewCompiler 创建新的 Lua 编译器
func NewCompiler() *Compiler {
	// 创建 LState（使用默认配置）
	state := lua.NewState()

	// 重写 print 函数，避免输出到 stdout 干扰 TUI 显示
	state.SetGlobal("print", state.NewFunction(func(L *lua.LState) int {
		// 空实现：丢弃所有 print 输出
		return 0
	}))

	return &Compiler{
		state: state,
	}
}

// Close 关闭编译器
func (c *Compiler) Close() {
	if c.state != nil {
		c.state.Close()
	}
}

// CompileString 将 Lua 源码字符串编译为字节码
func (c *Compiler) CompileString(source string, name string) (*CompileResult, error) {
	return c.Compile([]byte(source), name)
}

// CompileFile 将 Lua 源码文件编译为字节码
func (c *Compiler) CompileFile(filepath string) (*CompileResult, error) {
	// 使用 gopher-lua 的 LoadFile 方法
	fn, err := c.state.LoadFile(filepath)
	if err != nil {
		return nil, fmt.Errorf("编译 Lua 文件失败: %w", err)
	}

	// 获取函数原型
	proto := fn.Proto
	if proto == nil {
		return nil, fmt.Errorf("无法获取函数原型")
	}

	// 序列化字节码
	bytecode, err := serializeProto(proto)
	if err != nil {
		return nil, fmt.Errorf("序列化字节码失败: %w", err)
	}

	return &CompileResult{
		Bytecode:    bytecode,
		BytecodeStr: base64.StdEncoding.EncodeToString(bytecode),
		ResultSize:  len(bytecode),
	}, nil
}

// Compile 将 Lua 源码编译为字节码
func (c *Compiler) Compile(source []byte, name string) (*CompileResult, error) {
	// 使用 gopher-lua 的 Load 方法
	fn, err := c.state.Load(bytes.NewReader(source), name)
	if err != nil {
		return nil, fmt.Errorf("编译 Lua 源码失败: %w", err)
	}

	// 获取函数原型
	proto := fn.Proto
	if proto == nil {
		return nil, fmt.Errorf("无法获取函数原型")
	}

	// 序列化字节码
	bytecode, err := serializeProto(proto)
	if err != nil {
		return nil, fmt.Errorf("序列化字节码失败: %w", err)
	}

	return &CompileResult{
		Bytecode:    bytecode,
		BytecodeStr: base64.StdEncoding.EncodeToString(bytecode),
		SourceSize:  len(source),
		ResultSize:  len(bytecode),
	}, nil
}

// serializeProto 将 FunctionProto 序列化为字节码
// 注意：这是一个简化的实现，实际字节码格式可能需要更复杂的处理
func serializeProto(proto *lua.FunctionProto) ([]byte, error) {
	var buf bytes.Buffer

	// 写入 gopher-lua 字节码头部
	// 魔数: \x1bLua (标准 Lua 签名)
	buf.WriteString("\x1bLua")

	// 版本号: Lua 5.1 = 0x51
	buf.WriteByte(0x51)

	// 格式版本: 0x00 (官方格式)
	buf.WriteByte(0x00)

	// 写入源码名
	sourceName := proto.SourceName
	if sourceName == "" {
		sourceName = "=(bytecode)"
	}
	writeLengthPrefixedString(&buf, sourceName)

	// 写入行定义
	writeUint32(&buf, uint32(proto.LineDefined))
	writeUint32(&buf, uint32(proto.LastLineDefined))

	// 写入 upvalue 数量
	buf.WriteByte(byte(proto.NumUpvalues))

	// 写入参数数量
	buf.WriteByte(byte(proto.NumParameters))

	// 写入是否可变参数
	if proto.IsVarArg != 0 {
		buf.WriteByte(1)
	} else {
		buf.WriteByte(0)
	}

	// 写入寄存器数量
	buf.WriteByte(byte(proto.NumUsedRegisters))

	// 写入代码
	writeUint32(&buf, uint32(len(proto.Code)))
	for _, instr := range proto.Code {
		writeUint32(&buf, uint32(instr))
	}

	// 写入常量
	writeUint32(&buf, uint32(len(proto.Constants)))
	for _, constant := range proto.Constants {
		if err := writeConstant(&buf, constant); err != nil {
			return nil, err
		}
	}

	// 写入函数原型
	writeUint32(&buf, uint32(len(proto.FunctionPrototypes)))
	for _, fn := range proto.FunctionPrototypes {
		protoData, err := serializeProto(fn)
		if err != nil {
			return nil, err
		}
		writeUint32(&buf, uint32(len(protoData)))
		buf.Write(protoData)
	}

	return buf.Bytes(), nil
}

// writeLengthPrefixedString 写入带长度前缀的字符串
func writeLengthPrefixedString(buf *bytes.Buffer, s string) {
	writeUint32(buf, uint32(len(s)))
	buf.WriteString(s)
}

// writeUint32 写入 uint32 (小端序)
func writeUint32(buf *bytes.Buffer, v uint32) {
	buf.WriteByte(byte(v))
	buf.WriteByte(byte(v >> 8))
	buf.WriteByte(byte(v >> 16))
	buf.WriteByte(byte(v >> 24))
}

// writeConstant 写入常量
func writeConstant(buf *bytes.Buffer, constant interface{}) error {
	switch v := constant.(type) {
	case lua.LNilType:
		buf.WriteByte(0)
	case lua.LBool:
		buf.WriteByte(1)
		if bool(v) {
			buf.WriteByte(1)
		} else {
			buf.WriteByte(0)
		}
	case lua.LNumber:
		buf.WriteByte(2)
		writeFloat64(buf, float64(v))
	case lua.LString:
		buf.WriteByte(3)
		writeLengthPrefixedString(buf, string(v))
	case *lua.LFunction:
		buf.WriteByte(4)
		if v.Proto != nil {
			writeLengthPrefixedString(buf, v.Proto.SourceName)
		} else {
			writeLengthPrefixedString(buf, "")
		}
	default:
		return fmt.Errorf("不支持的常量类型: %T", constant)
	}
	return nil
}

// writeFloat64 写入 float64 (小端序)
func writeFloat64(buf *bytes.Buffer, v float64) {
	bits := math.Float64bits(v)
	for i := 0; i < 8; i++ {
		buf.WriteByte(byte(bits >> (i * 8)))
	}
}

// GenerateLoaderCode 生成加载字节码的 Lua 代码
// 这个函数生成可以在 gopher-lua 中执行的代码，用于加载字节码
func GenerateLoaderCode(bytecodeBase64 string) string {
	return fmt.Sprintf(`local bytecode = "%s"
local function load_bytecode()
    local decoded = {}
    local b64chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
    local decoding = {}
    for i = 1, 64 do
        decoding[string.sub(b64chars, i, i)] = i - 1
    end
    local data = bytecode
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
        table.insert(result, string.char(math.floor(n / 65536) % 256))
        table.insert(result, string.char(math.floor(n / 256) % 256))
        table.insert(result, string.char(n % 256))
    end
    for i = 1, padding do
        table.remove(result)
    end
    return table.concat(result)
end
local code = load_bytecode()
local fn = loadstring(code)
if fn then fn() end`, bytecodeBase64)
}

// GenerateBytecodeLoader 生成简化的字节码加载器
// 返回可以直接在 gopher-lua 中执行的代码
func GenerateBytecodeLoader(bytecode []byte) string {
	var hexStr string
	for _, b := range bytecode {
		hexStr += fmt.Sprintf("\\x%02x", b)
	}
	return fmt.Sprintf(`local bytecode = "%s"
local fn = loadstring(bytecode)
if fn then fn() end`, hexStr)
}
