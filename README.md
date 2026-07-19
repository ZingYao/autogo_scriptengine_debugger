# AutoGo IDE Extensions

本仓库提供 AutoGo 的两个 IDE 扩展基础框架：

- `vscode/extensions/autogo`：VSCode 扩展。
- `jetbrains/extensions/autogo`：IntelliJ Platform 扩展，可用于 IDEA、GoLand 等 JetBrains IDE。

两个扩展围绕同一组 `ag` 命令、共享 `.autogo/engine.json` 和移动端远程引擎协议提供一致能力。当前版本已接入 Lua/GLua/JavaScript 文件的依赖闭包增量同步、IDE 原生 DAP 调试、GLuac 编译/远程运行、模块策略代码生成、设备与资源管理、节点助手、官方文档，以及 go-lua-vm 的 GLua 语言支持。

初始化项目时，扩展会明确提示 `ag init` 将清空项目根目录，并在用户二次确认后执行。初始化完成后会 Clone `autogo_scriptengine`，在根 `go.mod` 中同时写入 `require` 和本地 `replace`，再生成必须位于项目根目录的 `main.go`。

相关文档：

- [AG 下载与命令使用](docs/AG_GUIDE.md)
- [扩展架构](docs/EXTENSION_ARCHITECTURE.md)
- [移动端远程引擎协议](docs/REMOTE_ENGINE_PROTOCOL.md)
- [IDEA 与 VSCode 验收记录](docs/ACCEPTANCE.md)

统一验证：

```bash
./scripts/check-editor-extensions.sh
```
