# AutoGo for JetBrains IDEs

安装插件后，IDE 顶部会显示独立的 `AutoGo Script Engine Console` 菜单，不注册编辑器右键菜单。

菜单提供快速调试、运行、停止、资源同步、节点助手、分架构编译、ADB 设备选择、Android/iOS 初始化、文件推送、官方文档、设置和 AG 更新。快速调试和运行支持 Lua、GLua 与 JavaScript 当前文件。

节点助手使用当前设备执行 `ag nodeserve -s <device>`，随后在 IDEA 右侧的内嵌 JCEF 浏览器中打开。设备刷新允许重复执行，并通过刷新序号防止旧扫描结果覆盖新结果。

底部 `AutoGo Script Engine Console` 工具窗口集中显示 SDK 版本、命令、stdout/stderr 和退出码，并提供调试、运行、停止及清空日志按钮。

JavaScript 调试复用 IDE 原生 DAP UI，移动端宿主会为 JavaScript 单独启动 goja DAP 服务；支持 CommonJS `require`、Promise 风格 `importModule`、断点、单步、变量查看、`evaluate` 和 `setVariable`。

Console 顶部横向工具栏提供完整功能入口：调试、运行、停止、同步资源、节点助手、编译、设备选择、初始化、文件推送、官方文档、设置、检查更新和清空日志。

插件自带适配 JetBrains 浅色和深色主题的 16×16 SVG 功能图标。

官方文档：<https://zingyao.github.io/autogo_scriptengine/>

设置页路径：

```text
Settings / Preferences -> AutoGo Script Engine Console
```

AG、ADB、Go 路径为空时自动发现并写入配置，也可以通过单文件选择框指定。默认设备由 `adb devices -l` 扫描后以下拉框选择。

统一代理支持启用开关、HTTP/SOCKS5、IP/主机、端口和可选认证，密码保存在 JetBrains PasswordSafe。设置页可直接测试代理，默认请求 Google 204 探测地址。

开发验证：

```bash
./gradlew test
./gradlew buildPlugin
```
