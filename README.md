# AutoGo ScriptEngine Debugger

[![Go Version](https://img.shields.io/badge/Go-1.21+-00ADD8?style=flat&logo=go)](https://golang.org)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/ZingYao/autogo_scriptengine_debugger?include_prereleases)](https://github.com/ZingYao/autogo_scriptengine_debugger/releases)

一个现代化的 AutoGo 脚本开发调试工具，提供友好的 TUI 界面和强大的调试功能。

## ✨ 功能特性

### 🎨 现代化 TUI 界面
- 直观的终端用户界面
- 实时日志输出显示
- 支持鼠标操作
- 彩色语法高亮

### 🚀 核心功能
- **项目管理**: 初始化、编译、部署 AutoGo 项目
- **设备管理**: 自动检测设备、连接设备、获取设备信息
- **脚本运行**: 支持 Lua 和 JavaScript 脚本
- **实时调试**: 日志输出、暂停、恢复、停止脚本
- **AG 管理**: 自动下载和更新 AutoGo 工具

### 🔧 开发工具
- 代码风格支持: AutoGo、LrAppSoft、NodeJS
- 自动生成项目模板
- 配置文件管理
- 多设备支持

### 🌍 跨平台支持
- ✅ Windows (AMD64)
- ✅ macOS (ARM64 - M1/M2/M3)
- ✅ macOS (AMD64 - Intel)
- ✅ Linux

## 📥 安装

### 从 Release 下载

访问 [Releases](https://github.com/ZingYao/autogo_scriptengine_debugger/releases) 页面下载最新版本：

| 平台 | 文件名 | 说明 |
|------|--------|------|
| Windows | `AutoGoScriptEngineDebugger-Windows.zip` | 包含 `.exe` 可执行文件 |
| macOS ARM | `AutoGoScriptEngineDebugger-macOS-ARM.tar.gz` | 适用于 M1/M2/M3 芯片 |
| macOS AMD | `AutoGoScriptEngineDebugger-macOS-AMD.tar.gz` | 适用于 Intel 芯片 |

#### 安装步骤

**Windows:**
```bash
# 1. 解压缩
unzip AutoGoScriptEngineDebugger-Windows.zip

# 2. 运行
.\AutoGoScriptEngineDebugger.exe
```

**macOS:**
```bash
# 1. 解压缩
tar -xzf AutoGoScriptEngineDebugger-macOS-*.tar.gz

# 2. 添加执行权限
chmod +x AutoGoScriptEngineDebugger*

# 3. 运行
./AutoGoScriptEngineDebuggerArm  # 或 AutoGoScriptEngineDebuggerAmd
```

### 从源码构建

**前置要求:**
- Go 1.21 或更高版本
- Git

**构建步骤:**
```bash
# 克隆仓库
git clone https://github.com/ZingYao/autogo_scriptengine_debugger.git
cd autogo_scriptengine_debugger

# 编译
go build -o AutoGoScriptEngineDebugger .

# 运行
./AutoGoScriptEngineDebugger
```

## 🎯 快速开始

### 1. 启动程序

```bash
# TUI 模式（推荐）
./AutoGoScriptEngineDebugger

# CLI 模式
./AutoGoScriptEngineDebugger --cli

# 直接运行脚本
./AutoGoScriptEngineDebugger script.lua
```

### 2. 初始化项目

1. 启动程序后，按 `i` 键选择"项目初始化"
2. 输入 Module 名称（如：`example.com/myproject`）
3. 选择目标平台（android/ios）
4. 等待初始化完成

### 3. 连接设备

1. 确保设备通过 USB 连接或网络连接
2. 按 `2` 键进入"设备管理"
3. 选择"查看已连接设备"
4. 选择要使用的设备

### 4. 运行脚本

1. 将脚本文件放到项目目录的 `scripts/` 文件夹
2. 按 `1` 键进入"运行管理"
3. 选择"选择脚本文件"
4. 选择要运行的脚本
5. 选择"运行脚本"

## ⌨️ 快捷键

### 主菜单
| 按键 | 功能 |
|------|------|
| `1-4` | 快速选择菜单项 |
| `i` | 项目初始化 |
| `h` | 帮助 |
| `q` | 退出 |
| `l` | 查看调试器日志 |
| `d` | 查看项目运行日志 |
| `r` | 刷新页面 |
| `F9` | 切换鼠标模式 |
| `Ctrl+Q` | 直接退出 |
| `Ctrl+1-4` | 快速执行菜单项 |

### 日志浏览
| 按键 | 功能 |
|------|------|
| `↑/↓` | 滚动 |
| `PgUp/PgDn` | 翻页 |
| `Home/End` | 跳转首尾 |
| `Tab` | 切换日志 |
| `r` | 刷新页面 |
| `c` | 清空日志 |
| `ESC` | 返回菜单 |

## 📁 项目结构

```
autogo_scriptengine_debugger/
├── main.go              # 程序入口
├── agmanager/           # AG 工具管理
│   ├── agmanager.go     # 下载、安装 AG
│   └── version_list.go  # 版本列表
├── config/              # 配置管理
│   └── config.go        # 加载、保存配置
├── device/              # 设备管理
│   └── device.go        # ADB 设备操作
├── interactive/         # 交互式输入
│   └── interactive.go   # CLI 模式输入
├── printer/             # 彩色打印
│   └── printer.go       # 日志输出
├── project/             # 项目管理
│   ├── project.go       # 项目操作
│   ├── embed_files.go   # 嵌入模板文件
│   ├── scripts/         # 示例脚本模板
│   └── main.go.code     # main.go 模板
├── script/              # 脚本操作
│   └── script.go        # 部署脚本
├── tui/                 # TUI 界面
│   └── tui.go           # 终端界面
└── .github/
    └── workflows/
        └── release.yml  # 自动发布配置
```

## 🔨 开发

### 构建

```bash
# 本地构建
go build -o AutoGoScriptEngineDebugger .

# 跨平台编译
# Windows
GOOS=windows GOARCH=amd64 go build -o AutoGoScriptEngineDebugger.exe .

# macOS ARM
GOOS=darwin GOARCH=arm64 go build -o AutoGoScriptEngineDebuggerArm .

# macOS AMD
GOOS=darwin GOARCH=amd64 go build -o AutoGoScriptEngineDebuggerAmd .
```

### 发布新版本

1. 更新代码并提交
2. 创建版本标签：
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```
3. GitHub Actions 会自动构建并创建 Release

## ⚙️ 配置

配置文件保存在 `当前执行目录/.autogo_scriptengine_debugger/config.json`

**配置项：**
- `codeStyle`: 代码风格（autogo/lrappsoft/nodejs）
- `deviceServiceUrl`: 设备服务地址
- `deviceId`: 设备 ID
- `deviceIp`: 设备 IP
- `devicePort`: 设备端口
- `projectPath`: 项目路径
- `httpProxy`: HTTP 代理地址
- `agPath`: AG 可执行文件路径
- `selectedScript`: 选中的脚本文件名

## 🐛 故障排除

### 页面显示错乱
- 按 `r` 键强制刷新页面

### 设备连接失败
1. 检查 USB 连接或网络连接
2. 确认 ADB 已安装并在 PATH 中
3. 在"设备管理"中重新连接设备

### AG 未找到
1. 在主菜单选择 `[3] AG 更新`
2. 选择 `检查更新` 自动下载安装
3. 或手动下载 AG 并设置环境变量 `AUTOGO_AG_PATH`

## 📝 更新日志

查看 [Releases](https://github.com/ZingYao/autogo_scriptengine_debugger/releases) 了解版本更新内容。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

## 📄 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

## 🙏 致谢

- [AutoGo](https://github.com/Dasongzi1366/AutoGo) - AutoGo 脚本引擎
- [tview](https://github.com/rivo/tview) - 终端 UI 库
- [tcell](https://github.com/gdamore/tcell) - 终端处理库
