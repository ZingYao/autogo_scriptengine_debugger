# AG 下载与命令使用指南

本文只描述 AutoGo SDK 命令行工具 `ag` 的下载、安装、路径检测和项目实际使用方式。

官方文档：<https://zingyao.github.io/autogo_scriptengine/>

## 下载与安装

版本信息来源：

```text
https://autogo-1257133387.cos.ap-shanghai.myqcloud.com/changelog.md
```

SDK 下载地址规则：

```text
http://168.138.164.80:7001/files/AutoGo/sdk/{平台}_{版本号}
```

支持的平台文件名：

| 系统 | 文件名 |
| --- | --- |
| Apple Silicon macOS | `mac_arm_{version}` |
| Intel macOS | `mac_amd_{version}` |
| Windows x64 | `win_x64_{version}` |
| Linux x64 | `linux_x64_{version}` |

默认安装位置：

| 系统 | 路径 |
| --- | --- |
| macOS | `/Users/Shared/ag` |
| Windows | `C:\Users\Public\ag.exe` |
| Linux | `~/.autogo/ag` |

安装或更新过程：

1. 从更新日志读取版本列表并选择目标版本。
2. 已安装旧版本时，将其备份为 `ag_{旧版本}`。
3. 将平台文件下载到临时文件。
4. 在 Unix 系统设置 `0755` 可执行权限。
5. 将下载文件重命名为 `ag`，Windows 使用 `ag.exe`。
6. 将版本号写入相邻的 `.version` 文件。

历史桌面程序通过主菜单 `[3] AG 更新` 进入下载管理。旧资料中的 `[8] AG 更新` 已过期。

## 路径检测

IDE 扩展和原项目按以下优先级定位 `ag`：

1. 用户在 IDE 设置中配置的路径。
2. 环境变量 `AUTOGO_AG_PATH`。
3. 系统 `PATH` 中的 `ag` 或 `ag.exe`。
4. 操作系统常见安装路径。

手动指定示例：

```bash
export AUTOGO_AG_PATH=/Users/Shared/ag
```

## 命令

`ag run` 必须在项目根目录执行，并且只使用项目根目录的 `main.go`。该文件必须声明
`package main` 并包含 `func main()`；放在子目录或 `.autogo` 目录中的入口文件不会生效。

```bash
# 查看版本
ag version

# 初始化项目
ag init -t <target>

# 运行项目
ag run
ag run -s <device>
ag run -d
ag run -s <device> -d

# 编译项目
ag build -t <target>
ag build -t <target> -e

# 部署项目
ag deploy
ag deploy -s <device>

# 连接远程设备
ag connect -s <address>

# 停止设备上的项目
ag stop

# 启动节点助手服务
ag nodeserve -s <device>
```

节点助手启动后访问：

```text
http://127.0.0.1:8801/index.html?device=<设备序列号>
```

IDEA 扩展会等待本地服务就绪，并优先使用右侧 `AutoGo Node Assistant` JCEF 工具窗口打开；当前 IDE 不支持 JCEF 时才降级到系统浏览器。

命令应以 AutoGo 项目根目录作为工作目录。标准输出和错误输出由 IDE 扩展转发到对应控制台。

完整构建目标：

```text
arm64-v8a
x86_64
x86
ios
ipa
deb
apk[arm64-v8a,x86_64,x86]
```

## IDEA 扩展中的设备与资源操作

设备来自：

```bash
adb devices -l
```

初始化 Android 项目后，动态库位于：

```text
resources/libs/arm64-v8a/
resources/libs/x86_64/
resources/libs/x86/
```

“同步资源”先通过 `adb shell getprop ro.product.cpu.abi` 获取设备架构，再扫描设备 `/data/local/tmp` 中的 `.so`。只有远端缺失或文件大小不同的动态库才会执行 `adb push`。

“推送文件”使用系统文件选择器选择单个文件，然后推送到插件设置的设备临时目录，默认 `/data/local/tmp`。

## 检查更新

IDEA 与 VSCode 扩展都使用本文开头的版本信息和 SDK 下载地址检查 AG 更新。发现新版本时需要用户确认；下载体限制为 256 MiB，并在替换前实际执行临时文件的 `ag version` 校验目标版本。验证成功后才备份旧 AG、原子替换可执行文件并保存版本号；验证失败时保留当前版本。

插件中的“网络代理”是通用配置，同时用于：

- AG 版本列表和二进制下载；
- 后续代码 Clone；
- 插件启动的 AG、Go 和 Git 子进程。

代理不会写入系统环境或 Git 全局配置。

两个扩展都会自动发现 AG、ADB、Go 和 GLuac：仅当对应配置为空时扫描环境变量、系统 `PATH`、Homebrew、Android SDK 和常见安装目录；找到后立即保存绝对路径，未找到则保持为空。IDEA 设置页直接提供单文件选择器，VSCode 通过 `AutoGo: 选择 … 可执行文件` 命令打开系统文件选择框。

默认设备通过 `adb devices -l` 扫描并以下拉框选择。只有状态为 `device` 的设备视为在线；已保存但当前不可用的设备会标记为离线。

代理设置包含启用开关、HTTP/SOCKS5 类型、IP/主机、端口、认证开关、用户名、密码和测试地址。密码存储于 JetBrains PasswordSafe，默认测试地址为：

```text
https://www.google.com/generate_204
```

## 常见问题

如果提示找不到 AG：

1. 检查 IDE 中配置的 AG 路径。
2. 执行 `ag version` 验证系统 `PATH`。
3. 检查 `AUTOGO_AG_PATH` 是否指向真实的可执行文件。
4. 检查文件是否具备执行权限。
