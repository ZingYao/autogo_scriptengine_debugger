# AutoGo ScriptEngine Debugger

[!\[Go Version\](https://img.shields.io/badge/Go-1.21+-00ADD8?style=flat\&logo=go null)](https://golang.org)
[!\[License\](https://img.shields.io/badge/license-MIT-blue.svg null)](LICENSE)
[!\[Release\](https://img.shields.io/github/v/release/ZingYao/autogo\_scriptengine\_debugger?include\_prereleases null)](https://github.com/ZingYao/autogo_scriptengine_debugger/releases)

一个现代化的 AutoGo ScriptEngine 开发调试工具，提供友好的 TUI 界面和强大的调试功能。

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

## 📥 安装

### 从 Release 下载

访问 [Releases](https://github.com/ZingYao/autogo_scriptengine_debugger/releases) 页面下载最新版本：

| 平台        | 文件名                                           | 说明              |
| --------- | --------------------------------------------- | --------------- |
| Windows   | `AutoGoScriptEngineDebugger-Windows.zip`      | 包含 `.exe` 可执行文件 |
| macOS ARM | `AutoGoScriptEngineDebugger-macOS-ARM.tar.gz` | 适用于 M1/M2/M3 芯片 |
| macOS AMD | `AutoGoScriptEngineDebugger-macOS-AMD.tar.gz` | 适用于 Intel 芯片    |

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

## 🔧 环境变量配置

配置环境变量后，可以在任意目录下直接运行 `AutoGoScriptEngineDebugger` 命令。

### Windows 配置

#### 方法一：通过系统设置（推荐）

1. 右键点击"此电脑"或"我的电脑"，选择"属性"
2. 点击"高级系统设置"
3. 点击"环境变量"按钮
4. 在"系统变量"或"用户变量"中找到 `Path` 变量，点击"编辑"
5. 点击"新建"，添加程序所在目录的完整路径，例如：
   ```
   C:\Tools\AutoGoScriptEngineDebugger
   ```
6. 点击"确定"保存所有窗口
7. **重新打开命令提示符或 PowerShell** 使配置生效

#### 方法二：通过命令行（PowerShell）

```powershell
# 为当前用户添加环境变量（推荐）
[Environment]::SetEnvironmentVariable("Path", $env:Path + ";C:\Tools\AutoGoScriptEngineDebugger", "User")

# 或者临时添加（仅当前会话有效）
$env:Path += ";C:\Tools\AutoGoScriptEngineDebugger"
```

#### 验证配置

```powershell
# 重新打开终端后执行
AutoGoScriptEngineDebugger.exe --help
```

### macOS 配置

首先，将程序移动到合适的目录（例如 `/usr/local/bin` 或自定义目录）：

```bash
# 创建目录（如果不存在）
sudo mkdir -p /usr/local/bin

# 移动程序（根据你的芯片选择对应的文件）
# M1/M2/M3 芯片
sudo mv AutoGoScriptEngineDebuggerArm /usr/local/bin/AutoGoScriptEngineDebugger

# Intel 芯片
sudo mv AutoGoScriptEngineDebuggerAmd /usr/local/bin/AutoGoScriptEngineDebugger

# 添加执行权限
sudo chmod +x /usr/local/bin/AutoGoScriptEngineDebugger
```

#### 方法一：添加到 /usr/local/bin（推荐）

如果按照上面的步骤将程序移动到 `/usr/local/bin`，通常无需额外配置，因为该目录已在 PATH 中。

验证：
```bash
AutoGoScriptEngineDebugger --help
```

#### 方法二：自定义目录 + 配置 PATH

如果程序放在自定义目录（如 `~/tools/AutoGoScriptEngineDebugger`），需要配置 shell：

**Zsh（macOS 默认，Catalina 及以后版本）：**

```bash
# 编辑配置文件
nano ~/.zshrc

# 在文件末尾添加以下内容（替换为你的实际路径）
export PATH="$HOME/tools/AutoGoScriptEngineDebugger:$PATH"

# 保存后重新加载配置
source ~/.zshrc
```

**Bash（旧版 macOS 默认）：**

```bash
# 编辑配置文件
nano ~/.bash_profile

# 在文件末尾添加以下内容（替换为你的实际路径）
export PATH="$HOME/tools/AutoGoScriptEngineDebugger:$PATH"

# 保存后重新加载配置
source ~/.bash_profile
```

#### 验证配置

```bash
# 检查是否配置成功
which AutoGoScriptEngineDebugger

# 运行程序
AutoGoScriptEngineDebugger --help
```

### 常见问题

**Q: 配置后命令仍然找不到？**

- Windows: 确保重新打开了终端窗口
- macOS: 确保执行了 `source` 命令或重新打开了终端

**Q: macOS 提示"无法打开，因为无法验证开发者"？**

```bash
# 移除隔离属性
sudo xattr -r -d com.apple.quarantine /usr/local/bin/AutoGoScriptEngineDebugger
```

**Q: 如何查看当前的 PATH 环境变量？**

```bash
# Windows (PowerShell)
$env:Path -split ';'

# macOS / Linux
echo $PATH | tr ':' '\n'
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

| 按键         | 功能       |
| ---------- | -------- |
| `1-4`      | 快速选择菜单项  |
| `i`        | 项目初始化    |
| `h`        | 帮助       |
| `q`        | 退出       |
| `l`        | 查看调试器日志  |
| `d`        | 查看项目运行日志 |
| `r`        | 刷新页面     |
| `F9`       | 切换鼠标模式   |
| `Ctrl+Q`   | 直接退出     |
| `Ctrl+1-4` | 快速执行菜单项  |

### 日志浏览

| 按键          | 功能   |
| ----------- | ---- |
| `↑/↓`       | 滚动   |
| `PgUp/PgDn` | 翻页   |
| `Home/End`  | 跳转首尾 |
| `Tab`       | 切换日志 |
| `r`         | 刷新页面 |
| `c`         | 清空日志 |
| `ESC`       | 返回菜单 |

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
│   └── debugger.go.code # debugger.go 模板
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

