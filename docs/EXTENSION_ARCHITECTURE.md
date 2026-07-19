# AutoGo IDE 扩展架构

## 目标

先完成并验收 JetBrains IDEA 扩展，再把全部能力同步到 VSCode。两端共享配置、远程引擎、文件同步和 DAP 语义；平台代码只负责 IDE API 适配。

## 目录边界

- `vscode/extensions/autogo` 只包含 VSCode API 适配和 Node 测试。
- `jetbrains/extensions/autogo` 只包含 IntelliJ Platform API 适配和 JUnit 测试。
- `docs` 记录共享契约，不在两个扩展之间复制运行时代码。

## 共享配置模型

| 语义 | VSCode | JetBrains |
| --- | --- | --- |
| AG 路径 | `autogo.agPath` | `AutoGoSettings.agPath` |
| ADB 路径 | `autogo.adbPath` | `AutoGoSettings.adbPath` |
| Go 路径 | `autogo.goPath` | `AutoGoSettings.goPath` |
| GLuac 路径 | `autogo.gluacPath` | `GluaSettings.gluacExecutable` |
| 网络代理 | `autogo.proxy.*` | `AutoGoSettings` 代理字段 |
| 工作目录 | 当前工作区 | 当前项目根目录 |
| 设备 | `adb devices` 多选框/项目覆盖 | `adb devices` 下拉框/项目覆盖 |
| 调试模式 | `run -d` | `run -d` |
| 模块策略 | `ALL/ALLOWLIST/DENYLIST` | `ALL/ALLOWLIST/DENYLIST` |
| 自定义初始化文件 | 项目配置路径 | 项目配置路径 |
| 远程引擎 | `.autogo/engine.json` | `.autogo/engine.json` |

AG 路径解析顺序统一为 IDE 设置、`AUTOGO_AG_PATH`、系统 `PATH`。

AG、ADB、Go、GLuac 首次加载都会自动发现；只有没有发现时才保持为空。IDEA 设置页使用文件选择器，VSCode 通过 `AutoGo: 选择 … 可执行文件` 命令打开系统文件选择框。自定义初始化 Go 文件同样只能通过文件选择器选取。

JetBrains 第一阶段使用顶部独立菜单和底部 `AutoGo Script Engine Console` 工具窗口，不注册编辑器右键菜单。Console 在每个项目中保持单例，项目关闭时终止由插件创建的子进程。

VSCode 使用 AutoGo Activity Bar 容器和视图标题横向按钮，不注册编辑器右键菜单；详细过程统一写入 `AutoGo Script Engine Console` Output Channel。

`.autogo/engine.json` 使用显式 `configVersion`。缺少版本号的旧配置按 v0 原子迁移到 v1，保留未知字段和用户已有值；高于扩展支持版本或字段类型损坏时拒绝运行，不允许静默覆盖。用户修改模块策略或自定义初始化文件后，通过“应用引擎配置”非破坏性重生成项目根 `main.go`，原入口保存到 `.autogo/backups`，最多保留最近五份；远程、同步、调试和未知配置保持不变。

网络代理属于插件通用执行配置，应用于 AG 下载和后续代码 Clone，并通过代理环境变量传递给插件启动的子进程；插件不得修改系统环境或 Git 全局配置。

代理支持 HTTP、HTTPS、SOCKS5、可选认证和测试地址，默认测试 Google `generate_204`。密码与远程 Bearer Token 只写入 IDE 密钥存储，不进入项目配置或日志。

## 命令模型

第一阶段提供 `version`、`init`、`run`、`build`、`deploy`、`connect` 和 `stop`。扩展只负责参数编排，不解释 AG 的业务输出。

命令失败时必须展示可执行文件、参数、工作目录、退出码和输出。用户停止命令时，扩展终止由自身创建的子进程。

## 调试边界

快速调试只针对当前 `.lua` 或 `.glua` 文件。扩展先解析静态 require 闭包并完成增量同步，再通过远程引擎创建 DAP 会话。完整协议见 [REMOTE_ENGINE_PROTOCOL.md](REMOTE_ENGINE_PROTOCOL.md)。

直连端点优先于 ADB forward；非回环远端必须使用 HTTPS。IDEA 和 VSCode 都将本地工作区路径映射为设备 release 路径，因此断点、调用栈、作用域、变量修改、继续与单步使用 IDE 原生调试界面。

GLuac 编译必须要求用户提供目标运行时版本。默认保留调试信息；只有用户明确选择 strip 时才生成不可源码调试的产物。

## 破坏性初始化

`ag init` 会清空整个目标目录。IDE 扩展必须在显示目标绝对路径和不可撤销说明后，获得用户二次确认，才能启动命令。取消操作不得创建文件或启动子进程。

`ag init` 成功后，扩展将 `https://github.com/ZingYao/autogo_scriptengine.git` Clone 到 `.autogo/deps/autogo_scriptengine`。扩展必须从克隆仓库的 `go.mod` 读取实际 module path，然后在项目根模块依次执行 `go mod edit -require=<module>@v0.0.0`、`go mod edit -replace=<module>=./.autogo/deps/autogo_scriptengine` 和 `go mod tidy`。`replace` 只改变指定模块的来源，不能代替 `require` 将模块加入依赖图，因此两条指令缺一不可。Git 与 Go 子进程继承插件统一代理，但不得修改用户 Git 或 Go 全局配置。

`go mod tidy` 后必须重新校验最终依赖图；若入口尚未被 Go 识别而导致 `require` 被移除，扩展补写 `require` 和 `replace` 并再次校验。只有两者都存在时初始化才成功。生成入口的包名和方法名均为 `main`，文件固定为项目根目录 `main.go`。

## 更新与恢复

“检查更新”读取官方 changelog，按当前平台下载 AG，限制下载大小为 256 MiB，并在覆盖前执行新二进制的 `version` 验证。旧 AG 先备份，同目录临时文件验证成功后再原子替换；失败时保持当前安装不变。

“应用引擎配置”保留 `remote`、`sync`、`debug` 和未知字段，重生成前备份根 `main.go`，仅保留最近五份受管备份。配置损坏或来自未来版本时拒绝覆盖。

## 构建与验证

- VSCode：Node 20+，执行 `npm test` 和 `npm run build`。
- JetBrains：JDK 21，执行 `./gradlew test` 和 `./gradlew buildPlugin`。
- JetBrains 插件必须实际打包 macOS、Linux、Windows 的 amd64/arm64 `gluals`；测试需验证每个声明支持的平台资源存在，当前平台还需执行一次可执行文件冒烟检查。
- 仓库根目录执行 `./scripts/check-editor-extensions.sh` 运行统一检查。
- VSCode 使用独立 `--user-data-dir` 和 `--extensions-dir` 安装打包后的 VSIX，确认扩展激活、AutoGo 视图注册和 GLua Language Server 启动。
