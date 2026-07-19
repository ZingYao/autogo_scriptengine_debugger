# AutoGo Script Engine Console for VSCode

VSCode 版本与 IDEA 扩展共享 `.autogo/engine.json`、移动端控制协议、manifest 增量同步和 GLua DAP 语义。扩展不注册编辑器右键菜单；`AutoGo Script Engine Console` 位于底部 Panel，与 Debug Console、Terminal 等标签处于同一行，主要操作在视图标题栏横向排列，完整入口同时出现在命令面板。

## 主要能力

- 自动发现并写回 AG、ADB、Go 路径；GLuac 优先使用用户配置或系统路径，否则按操作系统与 CPU 架构选择扩展内置版本。所有路径和自定义初始化文件均可通过系统文件选择框重新选择。
- 从 `adb devices` 刷新设备并通过 Quick Pick 选择，支持 USB 设备切换 Wi-Fi 和 Android 无线调试配对。
- 快速调试当前 Lua/GLua：解析静态 `require` 闭包、合并 `sync.extraFiles`、提示动态 require、增量同步、重启隔离引擎、启动移动端 DAP，并双向映射本地/设备源码路径。
- 运行、停止、四种构建、移动端引擎启动/重启、资源同步、系统文件选择推送、节点助手和内置官方文档。
- GLuac 编译要求目标运行时版本，按版本隔离产物并写入 SHA-256/debug sidecar；支持远程运行和远程调试入口。
- `ag init` 在展示绝对路径后进行模态二次确认；成功后生成根目录 `main.go`，Clone `autogo_scriptengine`，并强制校验 `require` 与本地 `replace` 同时存在。
- 模块策略为全部/白名单/黑名单，模块通过“选择模块黑白名单”命令多选，不要求手工输入。
- 完整 GLua 高亮、诊断、补全、跳转、格式化、API catalog、内置文档、LSP 与 DAP，资源来自 go-lua-vm VSCode 扩展。
- 远程引擎优先使用 `.autogo/engine.json` 中的 HTTPS 直连地址，`auto` 模式失败后回退 ADB forward；Bearer Token、代理密码通过 VSCode SecretStorage 保存。
- 首次连接校验协议主版本和 feature 列表；设备日志按游标跟随到 Output Channel，扩展关闭时清理自身创建的 ADB forward 和 DAP 代理。
- HTTP/HTTPS/SOCKS5 代理应用到 AG、Git Clone、Go 和其他扩展子进程；默认代理测试地址为 Google `generate_204`。
- 检查更新采用 256 MiB 下载上限、下载后版本执行校验、旧版本备份和同目录原子替换；应用模块配置会保留未知项目字段并最多保留五份根入口备份。

官方文档：<https://zingyao.github.io/autogo_scriptengine/>

## 开发验证

```bash
npm install
npm test
npm run build
```

`npm run build` 会从 `GO_LUA_VM_ROOT`、常见本地目录或固定提交的临时 Clone 中定位 `go-lua-vm`，并交叉编译 macOS/Windows 的 AMD64、ARM64 `gluals` 与 `gluac` 后打入 VSIX。可通过 `GO_LUA_VM_ROOT` 和 `GO_LUA_VM_REF` 覆盖源码目录与 Clone 版本。

测试覆盖 AutoGo 命令、工具发现、设备解析、代理、依赖图、Lua require 闭包、manifest、远程安全边界、DAP 路径映射，以及 go-lua-vm 的语法、内置 API、诊断、补全、跳转、格式化与调试适配。
