# AutoGo IDE 扩展验收记录

验收日期：2026-07-18

## IDEA 扩展

- 顶部 AutoGo 菜单和 Console 横向工具栏包含快速调试、运行、停止、移动端引擎、资源同步、节点助手、构建、GLua 工具、设备、初始化、推送、文档、配置和更新入口；没有注册编辑器右键菜单。
- `ag init` 显示项目绝对路径、不可撤销说明和模态二次确认；成功后生成根 `main.go`，Clone `autogo_scriptengine`，并验证最终 `go.mod` 同时存在 `require` 与本地 `replace`。
- 模块策略支持全部、白名单和黑名单，名单来自模块目录选择；自定义初始化文件使用文件选择器，并校验项目信任、`package main` 和规定函数签名。
- 远程控制优先使用安全直连端点，失败后回退项目级 ADB forward；支持能力协商、引擎健康状态、启动/重启、增量同步、动态 require 告警、额外同步文件、日志游标和端口清理。
- IDEA XDebugger 已在独立沙盒中验证真实 Android DAP：当前 `main.glua` 自动下发 `require` 的 `module.glua`，断点可停在 `module.glua:8`，Locals 正确显示 `name = string("zing")`，继续执行后调试会话立即结束且移动端引擎保持运行。
- IDEA 运行验收确认设备执行目录为 release 的 `scripts` 目录，`io.popen`、`require`、依赖文件、Lua 输出分区和执行耗时均正常；远程日志从最新 cursor 开始，不再重放历史输出。
- go-lua-vm 的 GLua 文件类型、词法高亮、诊断、补全、内置 API、require 跳转、格式化和 DAP 已集成；AutoGo API catalog 从本地引擎源码生成。
- `./gradlew clean test buildPlugin` 与仓库统一检查通过，打包产物为 `jetbrains/extensions/autogo/build/distributions/autogo-jetbrains-0.1.0.zip`。

## VSCode 扩展

- Activity Bar AutoGo 视图使用横向 view title 按钮，没有编辑器右键菜单；移动端引擎按钮依据健康状态在“启动”和“重启”之间切换。
- IDEA 的工具发现、系统文件选择器、设备去重与优先级、无线切换、模块策略、项目生成、配置迁移/备份、依赖图、代理、更新、资源和远程协议能力均已同步。
- 快速调试使用 DAP 路径代理双向映射工作区与设备 release 路径；GLua LSP 和六个平台的 `gluals` 随 VSIX 打包。
- `npm test` 覆盖 AutoGo 核心、远程 JSON、DAP 路径、语言服务、语法、内置 API、诊断、跳转、补全、格式化和调试；`npm run build` 与 VSIX 打包通过。
- 使用独立 `--user-data-dir` 和 `--extensions-dir` 安装最终 VSIX 后，扩展宿主成功激活 `Zing.autogo`；横向工具栏、四日志分区、当前文件运行、依赖同步、Lua 输出和 DAP 跨文件调试已通过验收。
- 最终 VSIX 通过 USB 真机重复验收：F7 自动同步 `main.glua` 与 `require` 的 `module.glua`，输出 `module.add 5`、`hello,zing` 和执行耗时；F6 在 `module.glua:8` 暂停，Locals 显示 `name = string("zing")`，继续后调试会话立即结束且移动端引擎保持 `running`。
- 最终包验证了 GLua 格式化命令可实际修改文档；扩展会自动将旧版 `local.glua-lsp` formatter 配置迁移到 `Zing.autogo`，避免升级后显示“格式化器不可用”。
- 打包产物为 `vscode/extensions/autogo/autogo-0.2.0.vsix`。

## 真实 Android 协议验收

设备：`192.168.31.4:5555`，物理序列号 `bc29432a`。

- `/v1/capabilities`：协议 `1.0`，包含 `lua`、`glua`、`gluac`、`dap`、`incremental-sync`、`set-variable`。
- 单文件上限 16 MiB，批次上限 64 MiB。
- `setBreakpoints` 返回 verified；运行后收到 `stopped: breakpoint`。
- 顶层栈帧为 `/data/local/tmp/.autogo/remote/releases/acceptance-003/main.lua:1`。
- `scopes` 返回 `Upvalues` 和 `Globals`，`continue` 成功。
- 验收结束后清理临时 ADB forward 并停止测试进程。

## 已知环境约束

本机没有提供 gopls MCP，因此移动端 Go 改动无法按仓库规则执行 gopls 语义诊断。替代验证为 `gofmt`、目标包测试、宿主构建和真实 Android DAP；主机全量 `go test ./lua_engine` 仍受仓库既有 Android CGo 头文件 `android/log.h` 限制。

2026-07-18 重复设备复测期间，无线 ADB 一度在 `adb push` 阶段挂起并最终变为 offline；Android 设备重启后 USB 链路恢复，同一 9 MB 文件以 39.1 MB/s 成功推送，并在最终 VSIX 中完成上述运行与跨文件 DAP 验收。该现象属于无线设备链路，不是扩展命令超时（AG 命令按产品要求不设置超时）。
