# AutoGo 远程脚本引擎协议

## 目标与边界

IDEA 与 VSCode 使用同一套远程控制语义。扩展只依赖本协议，不直接猜测设备进程、端口或文件布局。Android 首选通过 ADB 建立端口转发；普通远程设备可以直接连接用户配置的 HTTPS/WSS 地址。

Android 设备连接优先级为：已经验证的无线 ADB、Android 11+ 无线调试配对、USB 引导的 `adb tcpip`、纯 USB。USB 转无线必须先读取设备当前 Wi-Fi 地址，依次执行 tcpip、connect 和 `adb devices` 状态验证；只有无线序列号进入 `device` 状态后才能更新默认设备，任一步失败都保留 USB 回退。

`remote.mode` 支持 `auto/direct/adb`：`auto` 在配置 `remote.endpoint` 时先尝试直接远程连接，失败后回退 ADB；`direct` 只允许连接指定端点，不得误部署到默认 ADB 设备；`adb` 忽略直接端点。非回环直接连接必须使用 HTTPS，bearer token 由 IDE PasswordSafe 保存，不写入 `.autogo/engine.json`。

设备发现使用 `ro.serialno` 识别物理设备；同一设备同时存在 tcpip、TLS mDNS 和 USB 连接时只展示一个端点，优先级为固定无线 IP、TLS mDNS、其他网络端点、USB。身份读取失败时不得按型号猜测或合并。移动端控制服务与 DAP 均绑定 loopback 随机端口。扩展优先读取 `<remoteRoot>/engine.pid.json`，依次验证 PID、控制端口和 `instanceId` 后复用兼容实例；PID 协议不可用时才扫描旧版 loopback listener，仍未发现才执行 `ag run`。每个打开的 IDE 项目仍使用独立本地转发端口，切换设备或关闭项目只清理自己的映射。

设备菜单选择写入当前项目的 `remote.deviceSerial`；应用级“默认设备”只作为尚未选择设备的项目回退值。因此多个同时打开的项目可以绑定不同设备，选择项目 A 的设备不会覆盖项目 B 已保存的设备。

协议分为控制面、文件面与调试面：

- 控制面负责健康检查、能力协商、启动、重启、停止、运行脚本和读取日志。
- 文件面负责按项目相对路径查询哈希、增量上传、删除和原子切换版本。
- 调试面使用 DAP，扩展根据控制面返回的地址连接，不另造调试协议。
- `/v1/events` 使用 WebSocket 推送状态并接收 `stop-debug`、`stop-engine`、`ping` 控制消息；`stop-debug` 只结束调试等待并保留常驻脚本引擎，只有 `stop-engine` 才停止引擎。断线时 HTTP 健康检查和日志游标轮询仍可降级工作。WebSocket 不承载 DAP 数据。

## PID 元数据与启动互斥

- 控制服务监听成功后原子写入 `<remoteRoot>/engine.pid.json`，字段包含 `pid`、`controlPort`、`dapPort`、`instanceId` 与毫秒级 `startedAt`。
- 启动前使用 `<remoteRoot>/engine.start.lock` 原子目录互斥；锁内记录所有者 PID。PID 仍活跃时第二个进程立即退出，避免两个 `ag run` 同时启动控制服务。
- 所有者 PID 已失效时，新进程清理陈旧 PID 文件和锁目录后重试一次；正常退出时同时移除 PID 文件和锁目录。
- IDE 连接顺序固定为：读取 PID 文件 → 校验 PID 活跃 → 转发 `controlPort` → 请求 `/v1/health` → 校验 `instanceId`。任一步失败才回退旧版监听端口发现或启动新服务。

## 版本与能力协商

扩展首次连接必须请求 `GET /v1/capabilities`。响应至少包含：

```json
{
  "protocolVersion": "1.0",
  "engineVersion": "0.1.0",
  "runtimeVersion": "1.0.0",
  "state": "stopped",
  "features": ["lua", "glua", "gluac", "dap", "incremental-sync"],
  "dap": {"transport": "tcp", "host": "127.0.0.1", "port": 0},
  "limits": {"maxFileBytes": 16777216, "maxBatchBytes": 67108864}
}
```

未知主版本必须拒绝连接；未知次版本允许在能力列表范围内降级。扩展不得仅通过进程名判断“已启动”，按钮状态以健康检查和能力响应为准。

## 控制面

| 方法 | 路径 | 语义 |
| --- | --- | --- |
| `GET` | `/v1/health` | 返回 `starting/running/stopped/failed`、最近错误和会话 ID |
| `POST` | `/v1/engine/start` | 仅停止状态允许启动；重复请求返回当前状态 |
| `POST` | `/v1/engine/restart` | 原子重启并返回新会话 ID |
| `POST` | `/v1/engine/stop` | 停止当前引擎和活动脚本 |
| `POST` | `/v1/run` | 运行 Lua、GLua 或 GLuac 入口文件 |
| `POST` | `/v1/debug` | 创建调试会话并返回 DAP 连接信息 |
| `GET` | `/v1/logs` | 按游标增量读取结构化日志 |

所有失败响应采用统一结构：

```json
{
  "error": {
    "code": "ENGINE_START_FAILED",
    "message": "面向用户的失败原因",
    "detail": "可写入 Console 的诊断信息",
    "retryable": false
  }
}
```

## 文件面与依赖同步

调试前，扩展解析当前 Lua/GLua 文件的静态 `require` 闭包。动态 require 不猜测目标，必须警告用户并合并项目配置中的额外同步项。

同步使用项目相对路径和 SHA-256：

1. `POST /v1/files/diff` 提交路径、大小和哈希 manifest。
2. 引擎返回缺失、变化和远端多余文件。
3. `POST /v1/files/upload` 分批上传变化文件。
4. `POST /v1/files/commit` 原子切换到新 manifest。
5. 调试请求携带 manifest ID，保证运行的代码与断点源码一致。

路径必须规范化为 `/`，拒绝绝对路径、`..`、符号链接逃逸和大小写冲突。默认不自动删除设备端多余文件；用户确认或项目配置开启清理后才允许删除。

## DAP 与路径映射

调试请求返回 `sessionId`、DAP 地址、远端项目根目录及本地到远端的路径映射。运行时至少支持：

- `initialize`、`launch/attach`、`configurationDone`
- `setBreakpoints`、`threads`、`stackTrace`
- `scopes`、`variables`、`setVariable`、`evaluate`
- `continue`、`pause`、`next`、`stepIn`、`stepOut`
- `disconnect/terminate`

未使用 `gluac -s` 的产物保留源码行信息，可以调试；strip 产物只能运行。源码路径和版本不匹配时，扩展必须拒绝启用断点并提示重新同步。

## 认证与安全

- ADB 转发模式默认仅监听 `127.0.0.1`。
- 直接远程连接优先使用 HTTPS/WSS，并支持短期 token 或双向 TLS。
- token、密码和私钥必须保存在 IDE 密钥存储，不写入项目配置。
- 自定义初始化代码首次执行必须经过工作区信任确认。
- 上传、启动、停止和调试均记录设备、会话、manifest 与结果，但日志不得包含密钥。

## 项目配置

跨 IDE 项目配置文件固定为 `.autogo/engine.json`，包含配置版本、入口文件、模块策略、额外同步项、远端连接和路径映射。模块策略只允许：

- `ALL`：加载全部可用模块。
- `ALLOWLIST`：仅加载名单内模块。
- `DENYLIST`：加载除名单外的全部模块。

`ag run` 的唯一宿主入口固定写入项目根目录 `main.go`，并且必须声明 `package main` 与
`func main()`；放在 `.autogo` 或其他子目录中的入口不会生效。生成清单和 API catalog 写入
`.autogo/generated`，构建产物写入 `.autogo/build`。用户自定义初始化代码只被引用，扩展永不覆盖其内容。

## 失败恢复

- 每次生成和同步都保存 manifest、生成器版本及内容哈希。
- 生成失败不得留下半写文件，使用临时目录完成后原子替换。
- 远端 commit 失败继续使用上一 manifest。
- IDE 或设备断线后允许按 session ID 恢复日志；不可恢复的调试会话必须明确结束并清理端口转发。
