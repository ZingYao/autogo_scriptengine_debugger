const fs = require("fs");
const path = require("path");
const vscode = require("vscode");
const WebSocket = require("ws");
const gluaExtension = require("./glua-extension");
const { resolveGluacExecutable } = require("./gluals-resolver");
const { startDapPathProxy } = require("./dap-path-proxy");
const {
  buildAgArgs,
  analyzeJavaScriptDependencies,
  analyzeLuaDependencies,
  collectLuaDependencyClosure,
  classifyDeviceAvailability,
  createManifest,
  deduplicateAdbDevices,
  discoverExecutable,
  environmentWithProxy,
  ensureDefaultScript,
  hasRequireAndReplace,
  inspectInitializedProject,
  isChildProcessRunning,
  remoteProbeFailureDelay,
  parseRemoteControlPort,
  agPlatformFile,
  latestVersionFromChangelog,
  latestSemanticTag,
  parseAdbDevices,
  parseModulePath,
  normalizeRemoteLogEntry,
  requestJson,
  runProcess,
  sha256,
  versionMatches,
} = require("./autogo-core");

const ENGINE_REPOSITORY = "https://github.com/ZingYao/autogo_scriptengine.git";
const ENGINE_DIRECTORY = ".autogo/deps/autogo_scriptengine";
const CONTROL_PORT = 38696;
const DAP_PORT = 38697;
const CHANGELOG_URL = "https://autogo-1257133387.cos.ap-shanghai.myqcloud.com/changelog.md";
const SDK_BASE_URL = "http://168.138.164.80:7001/files/AutoGo/sdk/";
const MAX_AG_DOWNLOAD_BYTES = 256 * 1024 * 1024;
const nativeOutput = vscode.window.createOutputChannel("AutoGo Script Engine Console");
let consoleViewProvider;
const CONSOLE_CHANNELS = ["ag", "go", "lua", "extension"];
const pendingConsoleText = { ag: "", go: "", lua: "", extension: "" };

// 将既有命令输出同时写入诊断 OutputChannel 和可视化 Console，避免业务命令感知 UI 实现。
const output = {
  append(text, channel = "extension") {
    const value = String(text ?? "");
    pendingConsoleText[channel] += value.replace(/\u001b\[[0-?]*[ -\/]*[@-~]/g, "");
    const lines = pendingConsoleText[channel].split(/\r?\n/);
    pendingConsoleText[channel] = lines.pop() || "";
    for (const line of lines) {
      nativeOutput.appendLine(`[${channel.toUpperCase()}] ${line}`);
      consoleViewProvider?.appendLine(line, channel);
    }
  },
  appendLine(text = "", channel = "extension") {
    const value = `${pendingConsoleText[channel]}${String(text ?? "")}`.replace(/\u001b\[[0-?]*[ -\/]*[@-~]/g, "");
    pendingConsoleText[channel] = "";
    nativeOutput.appendLine(`[${channel.toUpperCase()}] ${String(text ?? "")}`);
    consoleViewProvider?.appendLine(value, channel);
  },
  show() {
    void vscode.commands.executeCommand("autogo.console.focus");
  },
  dispose() { nativeOutput.dispose(); },
};
const channelOutput = (channel) => ({
  append: (text) => output.append(text, channel),
  appendLine: (text = "") => output.appendLine(text, channel),
  show: () => {
    consoleViewProvider?.activateChannel(channel);
    output.show(true);
  },
});
const agOutput = channelOutput("ag");
const goOutput = channelOutput("go");
const luaOutput = channelOutput("lua");
const extensionOutput = channelOutput("extension");

function createAgRunOutput() {
  let pending = "";
  const routeLine = (line) => {
    // 脚本输出统一由远程日志协议接收；ag run stdout 只作为宿主诊断，避免运行/调试双份输出。
    if (/\b(?:lua|script) output:\s?/i.test(line)) return;
    else agOutput.appendLine(line);
  };
  return {
    show: () => agOutput.show(true),
    appendLine: (text = "") => routeLine(String(text)),
    append: (text) => {
      pending += String(text ?? "").replace(/\u001b\[[0-?]*[ -\/]*[@-~]/g, "");
      const lines = pending.split(/\r?\n/);
      pending = lines.pop() || "";
      for (const line of lines) routeLine(line);
    },
  };
}
let activeProcess;
let currentRemote;
let extensionContext;
let proxyPassword = "";
let remoteLogTimer;
let remoteLogCursor = 0;
const dapProxies = new Set();
let mobileHostProcess;
let mobileHostReadyPromise;
let mobileHostCompletionPromise;
let mobileEngineOperationPromise;
let remoteEventSocket;
let remoteStatusTimer;
let remoteStatusScanInFlight = false;
let remoteStatusScanPending = false;
let remoteStatusFailureCount = 0;
let lastRemoteStatusError = "";
let remoteConnectionPromise;
let extensionDeactivating = false;
const mobileEngineStates = new Map();

async function migrateLegacyGluaFormatter() {
  // 旧版扩展曾使用 local.glua-lsp；升级后将各配置层迁移到当前扩展 ID，避免格式化器显示为不可用。
  const formatterConfig = vscode.workspace.getConfiguration("[glua]");
  const inspected = formatterConfig.inspect("editor.defaultFormatter");
  if (!inspected) return;
  const legacyFormatter = "local.glua-lsp";
  const currentFormatter = "Zing.autogo";
  const migrationTargets = [
    [inspected.globalValue, vscode.ConfigurationTarget.Global],
    [inspected.workspaceValue, vscode.ConfigurationTarget.Workspace],
    [inspected.workspaceFolderValue, vscode.ConfigurationTarget.WorkspaceFolder],
    [inspected.globalLanguageValue, vscode.ConfigurationTarget.Global],
    [inspected.workspaceLanguageValue, vscode.ConfigurationTarget.Workspace],
    [inspected.workspaceFolderLanguageValue, vscode.ConfigurationTarget.WorkspaceFolder],
  ];
  for (const [configuredFormatter, target] of migrationTargets) {
    if (configuredFormatter !== legacyFormatter) continue;
    await formatterConfig.update("editor.defaultFormatter", currentFormatter, target, true);
    extensionOutput.appendLine(`已迁移旧版 GLua 格式化器配置：${legacyFormatter} → ${currentFormatter}`);
  }
}
const adbDeviceIdentityCache = new Map();

const REMOTE_HEALTH_INTERVAL_MS = 10_000;
const REMOTE_IDLE_INTERVAL_MS = 30_000;

function setMobileEngineState(device, state, detail = "") {
  if (device) mobileEngineStates.set(device, { state, detail, updatedAt: Date.now() });
  const labels = { stopped: "未启动", starting: "正在启动…", running: "running", failed: "启动失败" };
  const suffix = detail ? ` · ${String(detail).split("\n")[0]}` : "";
  consoleViewProvider?.setEngineStatus(`移动端引擎：${labels[state] || state}${suffix}`);
}

function mobileEngineState(device) {
  return mobileEngineStates.get(device) || { state: "stopped", detail: "" };
}

function workspaceRoot() {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (!root) throw new Error("请先打开 AutoGo 项目目录");
  return root;
}

// 返回随扩展发布的资源路径；打包后 __dirname 位于 dist，不能据此直接拼接 resources。
function bundledResourcePath(...segments) {
  const installRoot = extensionContext?.extensionPath
    || (path.basename(__dirname) === "dist" ? path.dirname(__dirname) : __dirname);
  return path.join(installRoot, ...segments);
}

function configuration() {
  return vscode.workspace.getConfiguration("autogo");
}

function proxyConfiguration() {
  const config = configuration();
  return {
    enabled: config.get("proxy.enabled", false),
    type: config.get("proxy.type", "http"),
    host: config.get("proxy.host", ""),
    port: config.get("proxy.port", 0),
    auth: config.get("proxy.auth", false),
    username: config.get("proxy.username", ""),
    password: proxyPassword,
  };
}

function toolPath(name) {
  const configured = configuration().get(`${name}Path`, "");
  const discovered = discoverExecutable(name, configured);
  if (name === "gluac" && !discovered) {
    return resolveGluacExecutable(extensionContext.extensionPath, "").path;
  }
  if (!discovered) throw new Error(`未找到 ${name} 可执行文件，请在 AutoGo 设置中选择文件`);
  return discovered;
}

async function discoverAndPersistTools() {
  const config = configuration();
  const discovered = {};
  for (const name of ["ag", "adb", "go", "gluac"]) {
    const current = config.get(`${name}Path`, "");
    let value = discoverExecutable(name, current);
    let bundled = false;
    if (name === "gluac" && !value) {
      try { value = resolveGluacExecutable(extensionContext.extensionPath, "").path; bundled = true; }
      catch (_) { value = ""; }
    }
    discovered[name] = value;
    // 扩展安装目录会随版本变化；内置工具按运行时解析，不能把临时安装路径固化到用户设置。
    if (!current && value && !bundled) await config.update(`${name}Path`, value, vscode.ConfigurationTarget.Global);
  }
  output.appendLine(`工具发现：${JSON.stringify(discovered)}`);
  return discovered;
}

// settingsGluacPath 返回设置页应展示的 GLuac 路径；未手工配置时回显当前平台的内置工具。
function settingsGluacPath() {
  const configured = configuration().get("gluacPath", "");
  const discovered = discoverExecutable("gluac", configured);
  if (discovered) return discovered;
  try { return resolveGluacExecutable(extensionContext.extensionPath, "").path; }
  catch (_) { return ""; }
}

// persistedGluacPath 避免把随扩展版本变化的内置安装路径固化到用户配置。
function persistedGluacPath(value) {
  const selected = String(value || "").trim();
  if (!selected) return "";
  try {
    const bundled = resolveGluacExecutable(extensionContext.extensionPath, "");
    if (path.resolve(selected) === path.resolve(bundled.path)) return "";
  } catch (_) {}
  return selected;
}

async function executeProcess(executable, args, options = {}) {
  const exclusive = options.exclusive !== false;
  if (exclusive && activeProcess && !isChildProcessRunning(activeProcess)) activeProcess = undefined;
  if (exclusive && activeProcess) throw new Error("已有 AutoGo 任务正在运行，请先停止或等待完成");
  const cwd = options.cwd || workspaceRoot();
  const executableName = path.basename(executable).toLowerCase().replace(/\.exe$/, "");
  // ADB 属于扩展自身的设备控制链路，命令与结果必须固定写入扩展日志，禁止调用方误传到 Go/AG/Lua 分区。
  const processOutput = options.silent ? { show() {}, append() {}, appendLine() {} }
    : (executableName === "adb"
      ? extensionOutput
      : options.output || (executableName === "ag" ? agOutput : executableName === "go" ? goOutput : extensionOutput));
  if (!options.silent) {
    // ADB 结果归档在扩展日志，但设备转发是运行/调试的内部步骤，不能抢占用户当前日志分区。
    if (executableName !== "adb" && options.reveal !== false) processOutput.show(true);
    processOutput.appendLine(`> ${executable} ${args.join(" ")}`);
    processOutput.appendLine(`cwd: ${cwd}`);
  }
  let ownedProcess;
  let result;
  try {
    result = await runProcess(executable, args, {
      cwd,
      env: environmentWithProxy(process.env, proxyConfiguration()),
      timeoutMs: options.timeoutMs,
      maxOutputBytes: options.maxOutputBytes,
      onSpawn: (child) => {
        ownedProcess = child;
        if (exclusive) activeProcess = child;
      },
      onOutput: (text) => processOutput.append(text),
    });
  } finally {
    if (exclusive && activeProcess === ownedProcess) activeProcess = undefined;
  }
  if (result.code !== 0) throw new Error(`${path.basename(executable)} 失败（退出码 ${result.code}）\n${result.stderr || result.stdout}`);
  return result;
}

async function executeAg(action, options = {}) {
  return executeProcess(toolPath("ag"), buildAgArgs(action, options), { output: action === "run" ? createAgRunOutput() : agOutput });
}

async function guarded(handler) {
  try { return await handler(); }
  catch (error) {
    activeProcess = undefined;
    output.appendLine(`[错误] ${String(error.message || error).trim()}`);
    consoleViewProvider?.activateChannel("extension");
    vscode.window.showErrorMessage(`AutoGo: ${String(error.message || error).split("\n")[0]}`);
    return undefined;
  }
}

async function refreshDevices(select = true, options = {}) {
  const adb = toolPath("adb");
  const silent = options.silent ?? !select;
  // 设备列表查询应快速返回；ADB 卡死时及时结束本轮刷新，避免设置页长期停留在刷新状态。
  const result = await executeProcess(adb, ["devices", "-l"], {
    exclusive: false,
    silent,
    timeoutMs: 5_000,
  });
  const endpoints = parseAdbDevices(result.stdout);
  const identified = await Promise.all(endpoints.map(async (serial) => {
    const cached = adbDeviceIdentityCache.get(serial);
    if (cached && cached.expiresAt > Date.now()) return { serial, physical: cached.physical };
    try {
      // 物理序列号只用于合并同一设备的 USB、无线与 mDNS 端点，不应阻塞整个设备列表。
      const identity = await executeProcess(adb, ["-s", serial, "shell", "getprop", "ro.serialno"], {
        exclusive: false,
        silent,
        timeoutMs: 2_000,
      });
      const physical = identity.stdout.trim();
      adbDeviceIdentityCache.set(serial, { physical, expiresAt: Date.now() + 5 * 60_000 });
      return { serial, physical };
    } catch (error) {
      // 短期缓存失败结果，避免不可响应端点在连续刷新时反复拖慢列表。
      adbDeviceIdentityCache.set(serial, { physical: "", expiresAt: Date.now() + 15_000 });
      extensionOutput.appendLine(`[Debug] 无法快速读取设备 ${serial} 的物理序列号，暂时保留该端点。`);
      return { serial, physical: "" };
    }
  }));
  const devices = deduplicateAdbDevices(identified);
  await vscode.commands.executeCommand("setContext", "autogo.hasDevices", devices.length > 0);
  if (!select) return devices;
  if (!devices.length) throw new Error("adb devices 未发现处于 device 状态的设备");
  const selected = await vscode.window.showQuickPick(devices, { placeHolder: "选择 AutoGo 默认设备" });
  if (selected) await persistSelectedDevice(selected);
  return selected;
}

async function persistSelectedDevice(serial) {
  await configuration().update("defaultDevice", serial, vscode.ConfigurationTarget.Global);
  await configuration().update("defaultDevice", serial, vscode.ConfigurationTarget.Workspace);
  const root = workspaceRoot();
  const configFile = path.join(root, ".autogo", "engine.json");
  if (!fs.existsSync(configFile)) return;
  const projectConfig = loadProjectConfig(root, true);
  projectConfig.remote.deviceSerial = serial;
  writeAtomic(configFile, `${JSON.stringify(projectConfig, null, 2)}\n`);
}

async function requireDevice() {
  const configured = configuration().get("defaultDevice", "");
  const devices = await refreshDevices(false);
  if (configured && devices.includes(configured)) return configured;
  if (classifyDeviceAvailability(configured, devices) === "none") {
    throw new Error("未检测到在线 Android 设备。请连接设备、完成 ADB 授权后点击“刷新设备”。");
  }
  if (classifyDeviceAvailability(configured, devices) === "offline") {
    extensionOutput.appendLine(`[Warn] 已配置设备 ${configured} 当前离线，请重新选择在线设备。`);
  }
  const selected = await vscode.window.showQuickPick(devices, { placeHolder: "选择 Android 设备" });
  if (!selected) throw new Error("未选择设备");
  await persistSelectedDevice(selected);
  return selected;
}

async function requireOnlineDevice(preferred = "") {
  const devices = await refreshDevices(false);
  if (preferred && devices.includes(preferred)) return preferred;
  if (!devices.length) {
    throw new Error("未检测到在线 Android 设备。请连接设备、完成 ADB 授权后点击“刷新设备”。");
  }
  if (preferred) {
    extensionOutput.appendLine(`[Warn] 已配置设备 ${preferred} 当前离线，正在选择在线设备。`);
  }
  if (devices.length === 1) {
    await persistSelectedDevice(devices[0]);
    extensionOutput.appendLine(`[Info] 已自动切换到唯一在线设备：${devices[0]}`);
    return devices[0];
  }
  const selected = await vscode.window.showQuickPick(devices, { placeHolder: "选择 Android 设备" });
  if (!selected) throw new Error("存在多台在线设备，但尚未选择默认设备");
  await persistSelectedDevice(selected);
  return selected;
}

async function confirmRunPrerequisites(actionLabel) {
  const root = workspaceRoot();
  const configFile = path.join(root, ".autogo", "engine.json");
  const projectConfig = fs.existsSync(configFile) ? loadProjectConfig(root, true) : {};
  const remote = projectConfig.remote || {};
  if (remote.mode === "direct") {
    if (!remote.endpoint) throw new Error("远程直连模式缺少 remote.endpoint，无法继续执行。");
    try {
      const token = await extensionContext.secrets.get("autogo.remoteToken") || "";
      const health = await requestJson(remote.endpoint, "GET", "/v1/health", undefined, { token, timeoutMs: 1000 });
      if (["running", "paused"].includes(health.state)) return true;
    } catch (error) {
      throw new Error(`远程移动端服务不可达，无法${actionLabel}。请检查 remote.endpoint 和服务状态：${error.message}`);
    }
    const selected = await vscode.window.showWarningMessage(
      `移动端脚本引擎尚未启动，是否启动后继续${actionLabel}？`,
      { modal: true }, "启动并继续");
    return selected === "启动并继续";
  }

  const device = remote.deviceSerial || await requireDevice();
  const devices = await refreshDevices(false);
  if (classifyDeviceAvailability(device, devices) !== "online") {
    throw new Error(`设备 ${device} 当前离线。请重新连接、完成 ADB 授权后刷新设备列表。`);
  }
  const cachedState = mobileEngineState(device);
  // 状态栏与运行前校验必须使用同一会话状态；控制连接失效会在真正同步时自动重建。
  if (cachedState.state === "running") return true;
  const detail = cachedState.state === "failed"
    ? `本次会话中的移动端引擎启动失败：${cachedState.detail || "未知原因"}`
    : "本次 VSCode 会话尚未启动移动端脚本引擎";
  extensionOutput.appendLine(`[Warn] ${detail}，等待用户确认是否继续${actionLabel}。`);
  consoleViewProvider?.activateChannel("extension");
  const selected = await vscode.window.showWarningMessage(
    `${detail}。是否启动移动端引擎并继续${actionLabel}？`,
    { modal: true }, "启动并继续");
  return selected === "启动并继续";
}

function selectedProjectDevice() {
  const root = workspaceRoot();
  const configFile = path.join(root, ".autogo", "engine.json");
  if (fs.existsSync(configFile)) {
    const projectDevice = loadProjectConfig(root, true).remote?.deviceSerial;
    if (projectDevice) return projectDevice;
  }
  return configuration().get("defaultDevice", "");
}

async function initializeProject(target) {
  const root = workspaceRoot();
  const confirmation = await vscode.window.showWarningMessage(
    `ag init 将清理整个目录，操作不可撤销：\n${root}\n\n确认初始化 ${target} 项目吗？`,
    { modal: true }, "清空并继续");
  if (confirmation !== "清空并继续") return;
  await executeAg("init", { target });
  await generateProjectHost(root, target);
  await initializeEngineDependency(root);
  const entry = ensureDefaultScript(root);
  const document = await vscode.workspace.openTextDocument(entry);
  await vscode.window.showTextDocument(document, { preview: false });
  vscode.window.showInformationMessage("AutoGo 项目、GLua 宿主和本地脚本引擎依赖初始化完成");
}

function normalizedModules(config) {
  return [...new Set((config.get("modules", []) || []).map((value) => String(value).trim()).filter((value) => /^[A-Za-z0-9_.-]+$/.test(value)))].sort();
}

function writeAtomic(target, content) {
  fs.mkdirSync(path.dirname(target), { recursive: true });
  const temporary = `${target}.${process.pid}.${Date.now()}.tmp`;
  try {
    fs.writeFileSync(temporary, content);
    fs.renameSync(temporary, target);
  } finally {
    if (fs.existsSync(temporary)) fs.rmSync(temporary, { force: true });
  }
}

function migrateProjectConfig(document, target) {
  const config = document && typeof document === "object" && !Array.isArray(document) ? { ...document } : {};
  const version = config.configVersion === undefined ? 0 : config.configVersion;
  if (!Number.isInteger(version) || version < 0) throw new Error("configVersion 必须是非负整数");
  if (version > 1) throw new Error(`配置版本 ${version} 高于当前支持的 1，请升级 AutoGo 扩展`);
  if (config.remote !== undefined && (!config.remote || typeof config.remote !== "object" || Array.isArray(config.remote))) throw new Error("配置字段 remote 必须是 JSON 对象");
  if (config.sync !== undefined && (!config.sync || typeof config.sync !== "object" || Array.isArray(config.sync))) throw new Error("配置字段 sync 必须是 JSON 对象");
  if (config.debug !== undefined && (!config.debug || typeof config.debug !== "object" || Array.isArray(config.debug))) throw new Error("配置字段 debug 必须是 JSON 对象");
  config.configVersion = 1;
  config.target = target || config.target;
  if (config.target !== undefined && !["android", "ios"].includes(config.target)) throw new Error(`不支持的项目目标平台：${config.target}`);
  config.entry ??= "scripts/main.glua";
  if (typeof config.entry !== "string") throw new Error("配置字段 entry 必须是项目相对路径字符串");
  config.remote = { mode: "auto", endpoint: "", deviceSerial: configuration().get("defaultDevice", ""), ...(config.remote || {}) };
  config.sync = { include: ["**/*.lua", "**/*.glua", "**/*.luac", "**/*.js", "**/*.json"], extraFiles: [], deleteRemoteExtras: false, ...(config.sync || {}) };
  config.debug = { enabled: true, stripGluaBytecode: false, ...(config.debug || {}) };
  return config;
}

function loadProjectConfig(root, required = false) {
  const configFile = path.join(root, ".autogo", "engine.json");
  if (!fs.existsSync(configFile)) {
    if (required) throw new Error("项目尚未初始化：缺少 .autogo/engine.json");
    return {};
  }
  let original;
  try { original = JSON.parse(fs.readFileSync(configFile, "utf8")); }
  catch (error) { throw new Error(`无法解析 ${configFile}：${error.message}`); }
  const migrated = migrateProjectConfig(original);
  if (JSON.stringify(original) !== JSON.stringify(migrated)) writeAtomic(configFile, `${JSON.stringify(migrated, null, 2)}\n`);
  return migrated;
}

function backupExistingMain(root) {
  const main = path.join(root, "main.go");
  if (!fs.existsSync(main)) return undefined;
  const directory = path.join(root, ".autogo", "backups");
  fs.mkdirSync(directory, { recursive: true });
  const backup = path.join(directory, `main.go.${Date.now()}.bak`);
  fs.copyFileSync(main, backup);
  const backups = fs.readdirSync(directory).filter((name) => /^main\.go\.\d+\.bak$/.test(name)).sort().reverse();
  for (const stale of backups.slice(5)) fs.rmSync(path.join(directory, stale), { force: true });
  return backup;
}

async function generateProjectHost(root, target, options = {}) {
  const config = configuration();
  const policy = config.get("modulePolicy", "ALL");
  const modules = normalizedModules(config);
  const customPath = String(config.get("customInitializer", "") || "").trim();
  let customCall = "";
  const managedCustom = path.join(root, "autogo_custom_init.go");
  if (customPath) {
    if (!vscode.workspace.isTrusted) throw new Error("当前工作区尚未信任，不能引入或执行自定义初始化代码");
    const source = path.resolve(customPath);
    if (!fs.existsSync(source) || !fs.statSync(source).isFile()) throw new Error(`自定义初始化文件不存在：${source}`);
    if (source === path.resolve(root, "main.go")) throw new Error("自定义初始化文件不能使用项目根 main.go");
    const customSource = fs.readFileSync(source, "utf8");
    if (!/^\s*package\s+main\b/m.test(customSource)) throw new Error("自定义初始化文件必须声明 package main");
    if (!/func\s+customInitialize\s*\(\s*engine\s+\*lua_engine\.LuaEngine\s*\)\s+error\b/.test(customSource)) {
      throw new Error("自定义初始化文件必须实现 customInitialize(engine *lua_engine.LuaEngine) error");
    }
    writeAtomic(managedCustom, `// AutoGo managed custom initializer copy. Source: ${source.replace(/\r?\n/g, " ")}\n${customSource}`);
    customCall = "\tif err := customInitialize(engine); err != nil { return nil, err }";
  } else if (fs.existsSync(managedCustom)) {
    const existing = fs.readFileSync(managedCustom, "utf8");
    if (existing.startsWith("// AutoGo managed custom initializer copy.")) fs.rmSync(managedCustom, { force: true });
  }
  const luaModelsImport = target === "ios"
    ? "github.com/ZingYao/autogo_scriptengine/lua_engine/define/ios/autogo/all_models"
    : "github.com/ZingYao/autogo_scriptengine/lua_engine/define/android/autogo/all_models";
  const jsModelsImport = target === "ios"
    ? "github.com/ZingYao/autogo_scriptengine/js_engine/define/ios/autogo/all_models"
    : "github.com/ZingYao/autogo_scriptengine/js_engine/define/autogo/all_models";
  const moduleValues = modules.map((name) => `\t${JSON.stringify(name)},`).join("\n");
  const template = fs.readFileSync(bundledResourcePath("resources", "templates", "autogo-main.go.tmpl"), "utf8");
  const source = template.replaceAll("{{LUA_MODELS_IMPORT}}", luaModelsImport)
    .replaceAll("{{JS_MODELS_IMPORT}}", jsModelsImport)
    .replaceAll("{{MODULE_POLICY}}", policy).replaceAll("{{MODULE_VALUES}}", moduleValues)
    .replaceAll("{{CUSTOM_INITIALIZER}}", customCall);
  const autogo = path.join(root, ".autogo");
  fs.mkdirSync(path.join(autogo, "generated"), { recursive: true });
  const configFile = path.join(autogo, "engine.json");
  let existing = {};
  if (options.preserveConfig && fs.existsSync(configFile)) existing = loadProjectConfig(root, true);
  const projectConfig = migrateProjectConfig(existing, target);
  projectConfig.modulePolicy = policy;
  projectConfig.modules = modules;
  projectConfig.customInitializer = customPath;
  const backup = options.backup ? backupExistingMain(root) : undefined;
  const configSource = `${JSON.stringify(projectConfig, null, 2)}\n`;
  writeAtomic(path.join(root, "main.go"), source);
  writeAtomic(configFile, configSource);
  writeAtomic(path.join(autogo, "generated", "manifest.json"), `${JSON.stringify({
    generator: "autogo-vscode", configVersion: 1,
    files: { "../engine.json": sha256(Buffer.from(configSource)), "../../main.go": sha256(Buffer.from(source)) },
  }, null, 2)}\n`);
  return backup;
}

async function initializeEngineDependency(root) {
  const dependency = path.join(root, ENGINE_DIRECTORY);
  fs.mkdirSync(path.dirname(dependency), { recursive: true });
  if (fs.existsSync(path.join(dependency, ".git"))) {
    const status = await executeProcess("git", ["-C", dependency, "status", "--porcelain"], { exclusive: false });
    if (status.stdout.trim()) throw new Error(`autogo_scriptengine 依赖目录存在本地改动，请先处理后再更新：${dependency}`);
    await executeProcess("git", ["-C", dependency, "fetch", "--tags", "--force", "--prune", "origin"]);
  } else if (fs.existsSync(dependency)) {
    throw new Error(`依赖目录已存在但不是 Git 仓库：${dependency}`);
  } else {
    await executeProcess("git", ["clone", "--origin", "origin", ENGINE_REPOSITORY, dependency]);
  }
  const tags = await executeProcess("git", ["-C", dependency, "tag", "--list"], { exclusive: false });
  const selectedTag = latestSemanticTag(tags.stdout);
  await executeProcess("git", ["-C", dependency, "checkout", "--detach", selectedTag]);
  const commit = await executeProcess("git", ["-C", dependency, "rev-parse", "HEAD"], { exclusive: false });
  const selectedCommit = commit.stdout.trim();
  const projectConfig = loadProjectConfig(root, true);
  projectConfig.dependencies = {
    ...(projectConfig.dependencies || {}),
    autogoScriptEngine: { tag: selectedTag, commit: selectedCommit },
  };
  writeAtomic(path.join(root, ".autogo", "engine.json"), `${JSON.stringify(projectConfig, null, 2)}\n`);
  output.appendLine(`autogo_scriptengine 已锁定最新 Tag：${selectedTag} (${selectedCommit.slice(0, 12)})`);
  const modulePath = parseModulePath(fs.readFileSync(path.join(dependency, "go.mod"), "utf8"));
  const go = toolPath("go");
  await executeProcess(go, ["mod", "edit", `-require=${modulePath}@v0.0.0`]);
  await executeProcess(go, ["mod", "edit", `-replace=${modulePath}=./${ENGINE_DIRECTORY}`]);
  await executeProcess(go, ["mod", "tidy"]);
  let goMod = fs.readFileSync(path.join(root, "go.mod"), "utf8");
  if (!hasRequireAndReplace(goMod, modulePath, ENGINE_DIRECTORY)) {
    await executeProcess(go, ["mod", "edit", `-require=${modulePath}@v0.0.0`]);
    await executeProcess(go, ["mod", "edit", `-replace=${modulePath}=./${ENGINE_DIRECTORY}`]);
    goMod = fs.readFileSync(path.join(root, "go.mod"), "utf8");
    if (!hasRequireAndReplace(goMod, modulePath, ENGINE_DIRECTORY)) throw new Error("go.mod 未同时写入 autogo_scriptengine require 与 replace");
  }
  await generateApiCatalog(root, dependency);
}

async function generateApiCatalog(root, dependency) {
  const functions = {};
  const stack = [path.join(dependency, "lua_engine")];
  while (stack.length) {
    const directory = stack.pop();
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) stack.push(target);
      else if (entry.isFile() && entry.name.endsWith(".go")) {
        const source = fs.readFileSync(target, "utf8");
        const pattern = /RegisterMethod\(\s*["`]([^"`]+)["`]\s*,\s*["`]([^"`]*)["`]/g;
        for (const match of source.matchAll(pattern)) {
          functions[match[1]] = {
            signature: { en: `${match[1]}(...)`, "zh-CN": `${match[1]}(...)` },
            returns: { en: "Returns values defined by the AutoGo API.", "zh-CN": "返回值由 AutoGo API 定义。" },
            params: { en: ["...: AutoGo API arguments"], "zh-CN": ["...：AutoGo API 参数"] },
            description: { en: match[2] || `AutoGo API ${match[1]}`, "zh-CN": match[2] || `AutoGo API ${match[1]}` },
            example: { en: `${match[1]}(...)`, "zh-CN": `${match[1]}(...)` },
          };
        }
      }
    }
  }
  const consoleDocs = {
    "console.log": ["输出普通脚本消息。", "console.log(...values)", "console.log(\"任务完成\")"],
    "console.info": ["输出 Info 级别脚本消息，在 Lua 日志区使用青绿色显示。", "console.info(...values)", "console.info(\"开始处理\")"],
    "console.debug": ["输出 Debug 级别脚本消息，在 Lua 日志区使用灰色显示。", "console.debug(...values)", "console.debug(\"变量\", value)"],
    "console.warn": ["输出 Warn 级别脚本消息，在 Lua 日志区使用亮黄色显示。", "console.warn(...values)", "console.warn(\"配置缺失\")"],
    "console.error": ["输出 Error 级别脚本消息，在 Lua 日志区使用暗红色显示。", "console.error(...values)", "console.error(\"执行失败\")"],
  };
  for (const [name, [description, signature, example]] of Object.entries(consoleDocs)) {
    functions[name] = {
      signature: { en: signature, "zh-CN": signature },
      returns: { en: "No return value.", "zh-CN": "无返回值。" },
      params: { en: ["...values: values to print"], "zh-CN": ["...values：需要输出的一个或多个值"] },
      description: { en: description, "zh-CN": description },
      example: { en: example, "zh-CN": example },
    };
  }
  if (!Object.keys(functions).length) throw new Error("未从 autogo_scriptengine 发现 RegisterMethod API");
  const target = path.join(root, ".autogo", "generated", "autogo-api-catalog.json");
  fs.mkdirSync(path.dirname(target), { recursive: true });
  writeAtomic(target, `${JSON.stringify({ functions }, null, 2)}\n`);
  const glua = vscode.workspace.getConfiguration("glua");
  const docs = glua.get("builtinDocs", []);
  const relative = ".autogo/generated/autogo-api-catalog.json";
  if (!docs.includes(relative)) await glua.update("builtinDocs", [...docs, relative], vscode.ConfigurationTarget.Workspace);
  output.appendLine(`已生成 AutoGo API catalog：${target}`);
}

async function adbForward(device, remotePort, silent = false) {
  const result = await executeProcess(toolPath("adb"), ["-s", device, "forward", "tcp:0", `tcp:${remotePort}`], { exclusive: false, silent });
  const port = Number(result.stdout.trim());
  if (!Number.isInteger(port) || port < 1) throw new Error(`ADB forward 未返回有效端口：${result.stdout}`);
  return port;
}

async function removeAdbForward(device, localPort, silent = false) {
  try { await executeProcess(toolPath("adb"), ["-s", device, "forward", "--remove", `tcp:${localPort}`], { exclusive: false, silent }); }
  catch (_) { /* 临时探测映射可能已由 ADB 清理。 */ }
}

function invalidateRemoteDiscovery(device) {
  if (device) {
    mobileEngineStates.delete(device);
  } else {
    mobileEngineStates.clear();
  }
}

function migrateGeneratedHostLifecycle(root) {
  const mainFile = path.join(root, "main.go");
  if (!fs.existsSync(mainFile)) return;
  let source = fs.readFileSync(mainFile, "utf8");
  if (!source.startsWith("// Code generated by AutoGo Script Engine Console.")) return;
  let changed = false;
  const legacyAutostart = 'if os.Getenv("AUTOGO_AUTOSTART") != "0" {';
  if (source.includes(legacyAutostart)) {
    source = source.replace(legacyAutostart, 'if os.Getenv("AUTOGO_AUTOSTART") == "1" {');
    changed = true;
  }
  const legacyDebugStop = '\t\t\tc.mu.Lock(); matches := command.SessionID == "" || command.SessionID == c.sessionID; c.mu.Unlock()\n\t\t\tif matches { c.closeEngine() }';
  if (source.includes(legacyDebugStop)) {
    source = source.replace(legacyDebugStop, '\t\t\tc.mu.Lock()\n\t\t\tif command.SessionID == "" || command.SessionID == c.sessionID { c.debugPending = false; c.recordLocked("debug session stopped") }\n\t\t\tc.mu.Unlock()');
    changed = true;
  }
  const legacyProcessLock = 'if processAlive(pid) { return nil, fmt.Errorf("AutoGo 移动端控制服务已运行，pid=%d", pid) }';
  if (source.includes(legacyProcessLock) && !source.includes("func runningService(root string, pid int) bool")) {
    source = source.replace(legacyProcessLock, 'if runningService(root, pid) { return nil, fmt.Errorf("AutoGo 移动端控制服务已运行，pid=%d", pid) }');
    const processAlive = 'func processAlive(pid int) bool { if pid <= 0 { return false }; err := syscall.Kill(pid, 0); return err == nil || errors.Is(err, syscall.EPERM) }';
    const runningService = `func runningService(root string, pid int) bool {
\tif !processAlive(pid) { return false }
\tcontent, err := os.ReadFile(filepath.Join(root, "engine.pid.json"))
\tif err != nil { return false }
\tvar metadata runtimePID
\tif err = json.Unmarshal(content, &metadata); err != nil || metadata.PID != pid || metadata.ControlPort <= 0 || metadata.InstanceID == "" { return false }
\tclient := &http.Client{Timeout: 500 * time.Millisecond}
\tresponse, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/v1/health", metadata.ControlPort))
\tif err != nil { return false }
\tdefer response.Body.Close()
\tif response.StatusCode != http.StatusOK { return false }
\tvar health struct { Service string \`json:"service"\`; InstanceID string \`json:"instanceId"\` }
\treturn json.NewDecoder(response.Body).Decode(&health) == nil && health.Service == "autogo-script-engine" && health.InstanceID == metadata.InstanceID
}`;
    source = source.replace(processAlive, `${processAlive}\n\n${runningService}`);
    changed = true;
  }
  if (!changed) return;
  writeAtomic(mainFile, source);
  extensionOutput.appendLine("[Info] 已迁移移动端宿主生命周期：脚本显式运行，调试结束后引擎保持常驻。");
}

function startMobileHost(device) {
  if (mobileHostProcess && !mobileHostProcess.killed && mobileHostReadyPromise) return mobileHostReadyPromise;
  migrateGeneratedHostLifecycle(workspaceRoot());
  const ag = toolPath("ag");
  const args = buildAgArgs("run", { device });
  const processOutput = createAgRunOutput();
  const cwd = workspaceRoot();
  agOutput.show();
  agOutput.appendLine(`> ${ag} ${args.join(" ")}`);
  agOutput.appendLine(`cwd: ${cwd}`);
  let startupOutput = "";
  let settled = false;
  let ownedProcess;
  let readyPromise;
  readyPromise = new Promise((resolve, reject) => {
    const finish = (handler, value) => {
      if (settled) return;
      settled = true;
      handler(value);
    };
    const completion = runProcess(ag, args, {
      // 宿主进程只负责启动控制面；脚本必须在扩展完成 manifest 同步后通过 /v1/run 显式执行。
      cwd,
      env: {
        ...environmentWithProxy(process.env, proxyConfiguration()),
        AUTOGO_AUTOSTART: "0",
      },
      onSpawn: (child) => { ownedProcess = child; mobileHostProcess = child; },
      onOutput: (text) => {
        processOutput.append(text);
        startupOutput = `${startupOutput}${String(text).replace(/\u001b\[[0-?]*[ -\/]*[@-~]/g, "")}`.slice(-64 * 1024);
        const port = parseRemoteControlPort(startupOutput);
        if (port) finish(resolve, port);
      },
    });
    mobileHostCompletionPromise = completion;
    void completion.then(async (result) => {
      if (settled) return;
      if (result.code !== 0) {
        const detail = String(result.stderr || result.stdout || `退出码 ${result.code}`).trim();
        agOutput.appendLine(`[Error] ag run 退出：${detail}`);
        finish(reject, new Error(`ag run 启动失败：${detail}`));
      } else {
        // 新版 AG 在复用已有宿主时只输出 PID；此时端口以设备上的 PID 元数据为准。
        for (let retry = 0; retry < 20; retry++) {
          const metadata = await readRemotePidMetadata(device);
          if (metadata) {
            agOutput.appendLine(`[Info] ag run 已复用移动端控制服务：pid=${metadata.pid}，port=${metadata.controlPort}`);
            finish(resolve, metadata.controlPort);
            return;
          }
          await new Promise((resolveDelay) => setTimeout(resolveDelay, 250));
        }
        finish(reject, new Error("ag run 已退出，但 PID 文件未提供可用的移动端控制端口"));
      }
    }).catch((error) => {
      agOutput.appendLine(`[Error] ag run 启动失败：${error.message}`);
      finish(reject, error);
    }).finally(() => {
      if (mobileHostProcess === ownedProcess) mobileHostProcess = undefined;
      if (mobileHostReadyPromise === readyPromise) mobileHostReadyPromise = undefined;
      if (mobileHostCompletionPromise === completion) mobileHostCompletionPromise = undefined;
    });
  });
  mobileHostReadyPromise = readyPromise;
  return readyPromise;
}

async function stopLocalMobileHost() {
  const process = mobileHostProcess;
  const completion = mobileHostCompletionPromise;
  if (process && !process.killed) process.kill("SIGTERM");
  if (completion) {
    await Promise.race([
      completion.catch(() => undefined),
      new Promise((resolve) => setTimeout(resolve, 2_000)),
    ]);
  }
  if (mobileHostProcess === process) mobileHostProcess = undefined;
  mobileHostReadyPromise = undefined;
  if (mobileHostCompletionPromise === completion) mobileHostCompletionPromise = undefined;
}

async function connectStartedAdbRemoteService(device, remotePort) {
  const localPort = await adbForward(device, remotePort);
  const endpoint = `http://127.0.0.1:${localPort}`;
  try {
    let health;
    for (let retry = 0; retry < 20; retry++) {
      try {
        health = await requestJson(endpoint, "GET", "/v1/health", undefined, { timeoutMs: 1_000 });
        if (health?.service === "autogo-script-engine" && health?.instanceId) break;
      } catch (_) {
        await new Promise((resolve) => setTimeout(resolve, 250));
      }
    }
    if (health?.service !== "autogo-script-engine" || !health?.instanceId) throw new Error("移动端控制端口已输出，但健康检查未就绪");
    const capabilities = validateCapabilities(await requestJson(endpoint, "GET", "/v1/capabilities", undefined, { timeoutMs: 2_000 }), ["incremental-sync"]);
    const discovered = { endpoint, controlPort: localPort, remoteControlPort: remotePort, capabilities, health };
    return discovered;
  } catch (error) {
    await removeAdbForward(device, localPort);
    throw error;
  }
}

async function readRemotePidMetadata(device) {
  const remoteTempDir = configuration().get("remoteTempDir", "/data/local/tmp").replace(/\/$/, "");
  const pidFile = `${remoteTempDir}/.autogo/remote/engine.pid.json`;
  try {
    const result = await executeProcess(toolPath("adb"), ["-s", device, "shell", "cat", pidFile], { exclusive: false, silent: true });
    const metadata = JSON.parse(result.stdout.trim());
    const pid = Number(metadata?.pid);
    const controlPort = Number(metadata?.controlPort);
    if (!Number.isInteger(pid) || pid < 1 || !Number.isInteger(controlPort) || controlPort < 1 || controlPort > 65535) return undefined;
    return { ...metadata, pid, controlPort };
  } catch (_) {
    return undefined;
  }
}

async function discoverPidRemoteService(device) {
  const metadata = await readRemotePidMetadata(device);
  if (!metadata) return undefined;
  const { pid, controlPort } = metadata;
  try {
    const discovered = await connectStartedAdbRemoteService(device, controlPort);
    if (metadata.instanceId && discovered.health?.instanceId !== metadata.instanceId) {
      await removeAdbForward(device, discovered.controlPort, true);
      return undefined;
    }
    extensionOutput.appendLine(`[Info] 已通过 PID 文件复用移动端控制服务：pid=${pid}，port=${controlPort}`);
    return discovered;
  } catch (_) {
    return undefined;
  }
}

async function discoverOrStartAdbRemoteService(device) {
  try {
    await cleanupAdbForwards();
    setMobileEngineState(device, "starting");
    const reusable = await discoverPidRemoteService(device);
    if (reusable) {
      setMobileEngineState(device, "running");
      return reusable;
    }
    try {
      await executeAg("stop", { device });
    } catch (error) {
      agOutput.appendLine(`[Warn] ag stop 未能确认旧进程状态，继续重新启动：${error.message}`);
    }
    await stopLocalMobileHost();
    const remotePort = await startMobileHost(device);
    invalidateRemoteDiscovery(device);
    const discovered = await connectStartedAdbRemoteService(device, remotePort);
    setMobileEngineState(device, "running");
    return discovered;
  } catch (error) {
    setMobileEngineState(device, "failed", error.message || error);
    throw error;
  }
}

function validateCapabilities(capabilities, required = []) {
  const major = Number(String(capabilities?.protocolVersion || "").split(".")[0]);
  if (major !== 1) throw new Error(`不支持的远程协议版本：${capabilities?.protocolVersion || "missing"}`);
  if (!Array.isArray(capabilities.features)) throw new Error("远程 capabilities.features 响应无效");
  const missing = required.filter((feature) => !capabilities.features.includes(feature));
  if (missing.length) throw new Error(`移动端引擎缺少必要能力：${missing.join("、")}`);
  return capabilities;
}

async function cleanupAdbForwards() {
  if (remoteEventSocket) { remoteEventSocket.close(); remoteEventSocket = undefined; }
  const disconnectedDevice = currentRemote?.device;
  if (disconnectedDevice) invalidateRemoteDiscovery(disconnectedDevice);
  if (!currentRemote?.device || !currentRemote.forwardedPorts?.length) {
    currentRemote = undefined;
    return;
  }
  const adb = toolPath("adb");
  for (const port of currentRemote.forwardedPorts) {
    try { await executeProcess(adb, ["-s", currentRemote.device, "forward", "--remove", `tcp:${port}`], { exclusive: false }); }
    catch (error) { output.appendLine(`清理 ADB forward tcp:${port} 失败：${error.message}`); }
  }
  currentRemote = undefined;
}

function connectRemoteEvents(remote) {
  if (remoteEventSocket?.readyState === WebSocket.OPEN) return remoteEventSocket;
  if (remoteEventSocket) remoteEventSocket.close();
  const target = new URL("/v1/events", remote.endpoint);
  target.protocol = target.protocol === "https:" ? "wss:" : "ws:";
  const socket = new WebSocket(target, { headers: remote.token ? { Authorization: `Bearer ${remote.token}` } : {} });
  remoteEventSocket = socket;
  socket.on("message", (payload) => {
    try {
      JSON.parse(String(payload));
      void consoleViewProvider?.refreshEngineStatus();
    } catch (_) { /* 非 JSON 事件交由 HTTP 降级状态刷新处理。 */ }
  });
  socket.on("error", (error) => extensionOutput.appendLine(`[Debug] WebSocket 控制通道不可用，保留 HTTP 降级：${error.message}`));
  socket.on("close", () => { if (remoteEventSocket === socket) remoteEventSocket = undefined; });
  return socket;
}

function sendRemoteEvent(command) {
  if (remoteEventSocket?.readyState !== WebSocket.OPEN) return false;
  remoteEventSocket.send(JSON.stringify(command));
  return true;
}

async function createRemoteConnection() {
  const root = workspaceRoot();
  const engineConfigPath = path.join(root, ".autogo", "engine.json");
  const projectConfig = fs.existsSync(engineConfigPath) ? loadProjectConfig(root, true) : {};
  const remote = projectConfig.remote || {};
  const remoteMode = remote.mode || "auto";
  if (!["auto", "direct", "adb"].includes(remoteMode)) throw new Error(`不支持的 remote.mode：${remoteMode}`);
  if (remoteMode === "direct" && !remote.endpoint) throw new Error("remote.mode=direct 时必须配置 remote.endpoint");
  const token = await extensionContext.secrets.get("autogo.remoteToken") || "";
  const configuredDevice = remote.deviceSerial || configuration().get("defaultDevice", "");
  const reusable = currentRemote && ((currentRemote.direct && remote.endpoint === currentRemote.endpoint)
    || (currentRemote.device && (!configuredDevice || configuredDevice === currentRemote.device)));
  if (reusable) {
    try {
      currentRemote.capabilities = validateCapabilities(await requestJson(currentRemote.endpoint, "GET", "/v1/capabilities", undefined, currentRemote), ["incremental-sync"]);
      connectRemoteEvents(currentRemote);
      return currentRemote;
    } catch (error) {
      output.appendLine(`现有远程连接不可复用，正在重建：${error.message}`);
    }
  }
  await cleanupAdbForwards();
  if (["direct", "auto"].includes(remoteMode) && remote.endpoint) {
    try {
      const capabilities = validateCapabilities(await requestJson(remote.endpoint, "GET", "/v1/capabilities", undefined, { token }), ["incremental-sync"]);
      const endpointHost = new URL(remote.endpoint).hostname;
      const dapHost = ["127.0.0.1", "localhost", "::1"].includes(capabilities.dap?.host) ? endpointHost : (capabilities.dap?.host || endpointHost);
      currentRemote = { endpoint: remote.endpoint, token, dapHost, dapPort: Number(capabilities.dap?.port || projectConfig.remote?.dapPort || DAP_PORT), capabilities, direct: true };
      connectRemoteEvents(currentRemote);
      return currentRemote;
    } catch (error) {
      if (remoteMode === "direct") throw error;
      output.appendLine(`直连失败，回退 ADB：${error.message}`);
    }
  }
  const device = remote.deviceSerial || await requireDevice();
  const discovered = await discoverOrStartAdbRemoteService(device);
  return activateDiscoveredAdbService(device, discovered, token);
}

async function activateDiscoveredAdbService(device, discovered, token = "") {
  const remoteDapPort = Number(discovered.capabilities?.dap?.port || 0);
  if (!Number.isInteger(remoteDapPort) || remoteDapPort < 0 || remoteDapPort > 65535) {
    await removeAdbForward(device, discovered.controlPort);
    throw new Error("移动端服务未返回有效 DAP 端口");
  }
  const dap = remoteDapPort > 0 ? await adbForward(device, remoteDapPort) : 0;
  currentRemote = { endpoint: discovered.endpoint, token, dapHost: "127.0.0.1", dapPort: dap, device,
    remoteDapPort, instanceId: discovered.health.instanceId, forwardedPorts: [discovered.controlPort, ...(dap ? [dap] : [])], capabilities: discovered.capabilities };
  try {
    currentRemote.capabilities = validateCapabilities(await requestJson(currentRemote.endpoint, "GET", "/v1/capabilities", undefined, { token }), ["incremental-sync"]);
    connectRemoteEvents(currentRemote);
  } catch (error) {
    await cleanupAdbForwards();
    throw error;
  }
  return currentRemote;
}

function remoteConnection() {
  if (remoteConnectionPromise) return remoteConnectionPromise;
  remoteConnectionPromise = createRemoteConnection().finally(() => { remoteConnectionPromise = undefined; });
  return remoteConnectionPromise;
}

function scheduleRemoteServiceStatus(delayMs) {
  if (extensionDeactivating) return;
  if (remoteStatusTimer) clearTimeout(remoteStatusTimer);
  remoteStatusTimer = setTimeout(() => {
    remoteStatusTimer = undefined;
    void scanRemoteServiceStatus();
  }, Math.max(0, delayMs));
}

function requestRemoteServiceStatusScan() {
  if (remoteStatusTimer) {
    clearTimeout(remoteStatusTimer);
    remoteStatusTimer = undefined;
  }
  if (remoteStatusScanInFlight) {
    remoteStatusScanPending = true;
    return;
  }
  if (remoteConnectionPromise) {
    scheduleRemoteServiceStatus(1_000);
    return;
  }
  void scanRemoteServiceStatus();
}

function markRemoteProbeRecovered() {
  if (lastRemoteStatusError) extensionOutput.appendLine("[Info] 后台移动端探测已恢复。");
  lastRemoteStatusError = "";
  remoteStatusFailureCount = 0;
}

async function scanRemoteServiceStatus() {
  if (remoteStatusScanInFlight) {
    remoteStatusScanPending = true;
    return;
  }
  if (remoteConnectionPromise) {
    scheduleRemoteServiceStatus(3_000);
    return;
  }
  remoteStatusScanInFlight = true;
  let nextDelay = REMOTE_IDLE_INTERVAL_MS;
  try {
    if (currentRemote) {
      try {
        const state = await requestJson(currentRemote.endpoint, "GET", "/v1/health", undefined, { ...currentRemote, timeoutMs: 800 });
        markRemoteProbeRecovered();
        nextDelay = REMOTE_HEALTH_INTERVAL_MS;
        const running = ["running", "paused"].includes(state.state);
        if (currentRemote.device) setMobileEngineState(currentRemote.device, running ? "running" : "stopped", state.lastError || "");
        await vscode.commands.executeCommand("setContext", "autogo.engineRunning", running);
        await consoleViewProvider?.refreshEngineStatus();
        return;
      } catch (_) {
        const disconnectedDevice = currentRemote.device;
        await cleanupAdbForwards();
        if (disconnectedDevice) setMobileEngineState(disconnectedDevice, "failed", "控制连接已断开");
      }
    }
    const root = workspaceRoot();
    const configPath = path.join(root, ".autogo", "engine.json");
    const projectConfig = fs.existsSync(configPath) ? loadProjectConfig(root, true) : {};
    if (projectConfig.remote?.endpoint) {
      await remoteConnection();
      markRemoteProbeRecovered();
      nextDelay = REMOTE_HEALTH_INTERVAL_MS;
      await consoleViewProvider?.refreshEngineStatus();
      return;
    }
    const device = projectConfig.remote?.deviceSerial || configuration().get("defaultDevice", "");
    if (!device) {
      markRemoteProbeRecovered();
      nextDelay = REMOTE_IDLE_INTERVAL_MS;
      return;
    }
    const discovered = await discoverPidRemoteService(device);
    if (discovered) {
      const token = await extensionContext.secrets.get("autogo.remoteToken") || "";
      await activateDiscoveredAdbService(device, discovered, token);
      const running = ["running", "paused"].includes(discovered.health?.state);
      setMobileEngineState(device, running ? "running" : "stopped", discovered.health?.lastError || "");
      await vscode.commands.executeCommand("setContext", "autogo.engineRunning", running);
      markRemoteProbeRecovered();
      nextDelay = REMOTE_HEALTH_INTERVAL_MS;
      await consoleViewProvider?.refreshEngineStatus();
      return;
    }
    markRemoteProbeRecovered();
    setMobileEngineState(device, "stopped");
    await vscode.commands.executeCommand("setContext", "autogo.engineRunning", false);
  } catch (error) {
    remoteStatusFailureCount += 1;
    nextDelay = remoteProbeFailureDelay(remoteStatusFailureCount);
    const message = String(error.message || error).split("\n")[0];
    if (message !== lastRemoteStatusError) extensionOutput.appendLine(`[Warn] 后台移动端探测失败：${message}；将在 ${nextDelay / 1000} 秒后重试。`);
    lastRemoteStatusError = message;
    await vscode.commands.executeCommand("setContext", "autogo.engineRunning", false);
    consoleViewProvider?.setEngineStatus(`移动端引擎：探测失败 · ${message}`);
  } finally {
    remoteStatusScanInFlight = false;
    if (remoteStatusScanPending) {
      remoteStatusScanPending = false;
      scheduleRemoteServiceStatus(0);
    } else {
      scheduleRemoteServiceStatus(nextDelay);
    }
  }
}

async function mergeConfiguredExtraFiles(files) {
  const root = workspaceRoot();
  const configFile = path.join(root, ".autogo", "engine.json");
  if (!fs.existsSync(configFile)) return files;
  const projectConfig = loadProjectConfig(root, true);
  if (!Array.isArray(projectConfig.sync.extraFiles)) throw new Error("sync.extraFiles 必须是数组");
  const merged = new Map(files.map((file) => [path.resolve(file), path.resolve(file)]));
  for (const configured of projectConfig.sync.extraFiles || []) {
    if (typeof configured !== "string" || !configured.trim() || path.isAbsolute(configured) || configured.replace(/\\/g, "/").split("/").includes("..")) {
      throw new Error(`非法 sync.extraFiles 路径：${configured}`);
    }
    if (/[*?\[\]{}]/.test(configured)) {
      const matches = await vscode.workspace.findFiles(new vscode.RelativePattern(root, configured), "{.git,.autogo/deps}/**");
      for (const uri of matches) merged.set(path.resolve(uri.fsPath), path.resolve(uri.fsPath));
      continue;
    }
    const target = path.resolve(root, configured);
    if (!target.startsWith(path.resolve(root) + path.sep) || !fs.existsSync(target)) throw new Error(`sync.extraFiles 不存在或超出项目目录：${configured}`);
    if (fs.statSync(target).isFile()) merged.set(target, target);
    else {
      const stack = [target];
      while (stack.length) {
        const directory = stack.pop();
        for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
          const child = path.join(directory, entry.name);
          if (entry.isDirectory()) stack.push(child); else if (entry.isFile()) merged.set(path.resolve(child), path.resolve(child));
        }
      }
    }
  }
  const realRoot = fs.realpathSync(root);
  for (const file of merged.values()) {
    const real = fs.realpathSync(file);
    if (!real.startsWith(realRoot + path.sep)) throw new Error(`同步文件通过符号链接逃逸工作区：${file}`);
  }
  return [...merged.values()].sort();
}

function requireRemoteFeatures(remote, features) {
  validateCapabilities(remote.capabilities, features);
}

function routeRemoteLogEntry(entry) {
  // 设备日志自带 RFC3339 时间；Webview 已统一显示接收时间，避免一行出现两个时间戳。
  const value = normalizeRemoteLogEntry(entry);
  const lifecycle = value.match(/^lua lifecycle:\s?(.*)$/is);
  if (lifecycle) { luaOutput.appendLine(lifecycle[1]); return; }
  const scriptOutput = value.match(/^(?:lua|script) output:\s?(.*)$/is);
  if (scriptOutput) {
    luaOutput.appendLine(scriptOutput[1]);
    return;
  }
  if (/\b(engine (?:started|stopped)|remote control listening|manifest committed|run (?:started|completed|failed))\b/i.test(value)) {
    const level = /run failed/i.test(value) ? "Error" : (/manifest committed|run (?:started|completed)/i.test(value) ? "Debug" : "Info");
    goOutput.appendLine(`[${level}] ${value}`);
    if (/\brun failed:\s/i.test(value)) void consoleViewProvider?.refreshEngineStatus();
    return;
  }
  luaOutput.appendLine(value);
}

async function startRemoteLogPolling(remote, onRunTerminated) {
  if (remoteLogTimer) clearInterval(remoteLogTimer);
  try {
    const latest = await requestJson(remote.endpoint, "GET", "/v1/logs?cursor=0", undefined, remote);
    remoteLogCursor = Number.isInteger(latest.cursor) ? latest.cursor : 0;
    extensionOutput.appendLine(`[Debug] 远程日志从最新 cursor=${remoteLogCursor} 开始`);
  } catch (_) {
    remoteLogCursor = 0;
  }
  let remaining = 480;
  let polling = false;
  let terminationHandled = false;
  remoteLogTimer = setInterval(async () => {
    if (polling) return;
    if (--remaining < 0) { clearInterval(remoteLogTimer); remoteLogTimer = undefined; return; }
    polling = true;
    try {
      const logs = await requestJson(remote.endpoint, "GET", `/v1/logs?cursor=${remoteLogCursor}`, undefined, remote);
      if (!Array.isArray(logs.entries) || !Number.isInteger(logs.cursor)) throw new Error("日志响应格式无效");
      for (const entry of logs.entries) {
        if (typeof entry !== "string") continue;
        routeRemoteLogEntry(entry);
        if (!terminationHandled && /\brun (?:completed|failed):\s/i.test(entry)) {
          terminationHandled = true;
          await onRunTerminated?.({ failed: /\brun failed:\s/i.test(entry), entry });
        }
      }
      remoteLogCursor = logs.cursor;
    } catch (error) { /* 短暂断线由下一轮恢复，避免每秒刷屏。 */ }
    finally { polling = false; }
  }, 250);
}

async function restartRemoteEngine(remote) {
  const state = await requestJson(remote.endpoint, "POST", "/v1/engine/restart", {}, remote);
  if (!state || !["running", "paused"].includes(state.state)) throw new Error(`移动端引擎重启失败：${state?.lastError || state?.state || "响应无效"}`);
  await refreshRemoteDapForward(remote);
  output.appendLine(`移动端引擎已重启：session=${state.sessionId || "unknown"}`);
  await vscode.commands.executeCommand("setContext", "autogo.engineRunning", true);
  void consoleViewProvider?.refreshEngineStatus();
}

async function ensureRemoteEngineRunning(remote) {
  const state = await requestJson(remote.endpoint, "GET", "/v1/health", undefined, remote);
  if (["running", "paused"].includes(state.state)) return state;
  const started = await requestJson(remote.endpoint, "POST", "/v1/engine/start", {}, remote);
  if (!["running", "paused"].includes(started.state)) throw new Error(`移动端引擎启动失败：${started.lastError || started.state}`);
  await refreshRemoteDapForward(remote);
  return started;
}

async function refreshRemoteDapForward(remote) {
  const capabilities = validateCapabilities(await requestJson(remote.endpoint, "GET", "/v1/capabilities", undefined, remote), ["dap"]);
  const remotePort = Number(capabilities.dap?.port || 0);
  if (!Number.isInteger(remotePort) || remotePort < 1) throw new Error("移动端服务未返回有效 DAP 端口");
  remote.capabilities = capabilities;
  if (remote.direct) { remote.dapPort = remotePort; return; }
  if (remote.remoteDapPort === remotePort && remote.dapPort) return;
  const oldLocalPort = remote.dapPort;
  const localPort = await adbForward(remote.device, remotePort);
  remote.dapPort = localPort;
  remote.remoteDapPort = remotePort;
  remote.forwardedPorts = (remote.forwardedPorts || []).filter((port) => port !== oldLocalPort).concat(localPort);
  if (oldLocalPort) await removeAdbForward(remote.device, oldLocalPort);
}

async function applyDebugDapEndpoint(remote, debugState) {
  const dap = debugState?.dap || {};
  const remotePort = Number(dap.port || 0);
  if (!Number.isInteger(remotePort) || remotePort < 1 || remotePort > 65535) throw new Error("移动端调试响应未返回有效 DAP 端口");
  const endpointHost = new URL(remote.endpoint).hostname;
  const dapHost = ["127.0.0.1", "localhost", "::1"].includes(dap.host) ? endpointHost : (dap.host || endpointHost);
  if (remote.direct) {
    remote.dapHost = dapHost;
    remote.dapPort = remotePort;
    remote.remoteDapPort = remotePort;
    return;
  }
  if (remote.remoteDapPort === remotePort && remote.dapPort) return;
  const oldLocalPort = remote.dapPort;
  const localPort = await adbForward(remote.device, remotePort);
  remote.dapHost = "127.0.0.1";
  remote.dapPort = localPort;
  remote.remoteDapPort = remotePort;
  remote.forwardedPorts = (remote.forwardedPorts || []).filter((port) => port !== oldLocalPort).concat(localPort);
  if (oldLocalPort) await removeAdbForward(remote.device, oldLocalPort);
}

async function operateRemoteEngineOnce(forceRestart) {
  const root = workspaceRoot();
  const configPath = path.join(root, ".autogo", "engine.json");
  const projectConfig = fs.existsSync(configPath) ? loadProjectConfig(root, true) : {};
  if (projectConfig.remote?.mode !== "direct") {
    const device = await requireOnlineDevice(projectConfig.remote?.deviceSerial || configuration().get("defaultDevice", ""));
    const discovered = await discoverOrStartAdbRemoteService(device);
    const token = await extensionContext.secrets.get("autogo.remoteToken") || "";
    const remote = await activateDiscoveredAdbService(device, discovered, token);
    await ensureRemoteEngineRunning(remote);
    setMobileEngineState(device, "running");
    await vscode.commands.executeCommand("setContext", "autogo.engineRunning", true);
    await consoleViewProvider?.refreshEngineStatus();
    return remote;
  }
  const remote = await remoteConnection();
  const state = await requestJson(remote.endpoint, "GET", "/v1/health", undefined, remote);
  const running = ["running", "paused"].includes(state.state);
  const endpoint = forceRestart || running ? "/v1/engine/restart" : "/v1/engine/start";
  const result = await requestJson(remote.endpoint, "POST", endpoint, {}, remote);
  const nowRunning = ["running", "paused"].includes(result.state);
  await vscode.commands.executeCommand("setContext", "autogo.engineRunning", nowRunning);
  if (nowRunning) await refreshRemoteDapForward(remote);
  output.appendLine(`${endpoint.endsWith("restart") ? "移动端引擎已重启" : "移动端引擎已启动"}：${result.state}`);
  await consoleViewProvider?.refreshEngineStatus();
}

function operateRemoteEngine(forceRestart) {
  // 激活自动启动与用户手动点击共享同一任务，避免并发执行两组 ag stop/run。
  if (mobileEngineOperationPromise) return mobileEngineOperationPromise;
  mobileEngineOperationPromise = operateRemoteEngineOnce(forceRestart)
    .finally(() => { mobileEngineOperationPromise = undefined; });
  return mobileEngineOperationPromise;
}

async function autoStartMobileEngine() {
  const root = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  if (!root) return;
  const initialization = inspectInitializedProject(root);
  if (!initialization.initialized) {
    const detail = initialization.missing.join("、");
    consoleViewProvider?.setEngineStatus("移动端引擎：项目未初始化");
    extensionOutput.appendLine(`[Info] 当前项目未初始化或初始化不完整，跳过移动端引擎自动启动。缺少：${detail}`);
    return;
  }
  extensionOutput.appendLine("[Info] 扩展已激活，正在自动启动移动端引擎……");
  try {
    await operateRemoteEngine(false);
    extensionOutput.appendLine("[Info] 移动端引擎自动启动完成。");
  } catch (error) {
    const message = String(error.message || error).split("\n")[0];
    const device = selectedProjectDevice();
    if (device) setMobileEngineState(device, "failed", message);
    extensionOutput.appendLine(`[Warn] 移动端引擎自动启动失败：${message}`);
  } finally {
    requestRemoteServiceStatusScan();
  }
}

async function stopManagedMobileEngine() {
  const device = selectedProjectDevice() || await requireDevice();
  await executeAg("stop", { device });
  await stopLocalMobileHost();
  await cleanupAdbForwards();
  setMobileEngineState(device, "stopped");
  await vscode.commands.executeCommand("setContext", "autogo.engineRunning", false);
}

async function syncFiles(files) {
  const root = workspaceRoot();
  const remote = await remoteConnection();
  const manifest = createManifest(files, root);
  const maximumFileBytes = Number(remote.capabilities?.limits?.maxFileBytes || 16 * 1024 * 1024);
  const maximumBatchBytes = Number(remote.capabilities?.limits?.maxBatchBytes || 64 * 1024 * 1024);
  if (!Number.isSafeInteger(maximumFileBytes) || maximumFileBytes < 1 || !Number.isSafeInteger(maximumBatchBytes) || maximumBatchBytes < 1) {
    throw new Error("远程 capabilities.limits 响应无效");
  }
  const oversized = manifest.files.find((file) => file.size > maximumFileBytes);
  if (oversized) throw new Error(`同步文件超过移动端单文件上限：${oversized.path} (${oversized.size} > ${maximumFileBytes})`);
  const totalBytes = manifest.files.reduce((total, file) => total + file.size, 0);
  if (totalBytes > maximumBatchBytes) throw new Error(`同步批次超过移动端上限：${totalBytes} > ${maximumBatchBytes}`);
  const descriptors = manifest.files.map(({ path: itemPath, sha256: hash, size }) => ({ path: itemPath, sha256: hash, size }));
  const diff = await requestJson(remote.endpoint, "POST", "/v1/files/diff", { manifestId: manifest.id, files: descriptors }, remote);
  if (!Array.isArray(diff.upload) || diff.upload.some((value) => typeof value !== "string")) throw new Error("远程 diff.upload 响应无效");
  const required = new Set(diff.upload);
  for (const file of manifest.files.filter((item) => required.has(item.path))) {
    await requestJson(remote.endpoint, "POST", "/v1/files/upload", {
      manifestId: manifest.id, path: file.path, sha256: file.sha256, contentBase64: file.content.toString("base64"),
    }, remote);
    output.appendLine(`已同步：${file.path}`);
  }
  await requestJson(remote.endpoint, "POST", "/v1/files/commit", { manifestId: manifest.id }, remote);
  return { remote, manifest };
}

function scriptLanguageForFile(file) {
  if (/\.(lua|glua)$/i.test(file)) return "lua";
  if (/\.js$/i.test(file)) return "javascript";
  return "";
}

function activeScriptFile() {
  const file = vscode.window.activeTextEditor?.document.uri.fsPath;
  if (!file || !scriptLanguageForFile(file)) throw new Error("当前文件必须是 .lua、.glua 或 .js");
  return file;
}

function activeLuaFile() {
  const file = vscode.window.activeTextEditor?.document.uri.fsPath;
  if (!file || !/\.(lua|glua)$/i.test(file)) throw new Error("当前文件必须是 .lua 或 .glua");
  return file;
}

function analyzeScriptDependencies(entry, root) {
  const language = scriptLanguageForFile(entry);
  if (language === "javascript") return analyzeJavaScriptDependencies(entry, root);
  return analyzeLuaDependencies(entry, root);
}

function requiredRuntimeFeatures(language, debug = false) {
  return language === "javascript"
    ? ["javascript", "js", "incremental-sync", ...(debug ? ["dap"] : [])]
    : ["lua", "glua", "incremental-sync", ...(debug ? ["dap"] : [])];
}

function scriptLanguageLabel(language) {
  return language === "javascript" ? "JavaScript" : "Lua/GLua";
}

async function quickDebug() {
  consoleViewProvider?.clearChannel("lua");
  consoleViewProvider?.activateChannel("lua");
  if (!await confirmRunPrerequisites("调试")) return;
  // 引擎按需启动可能临时展示 AG 输出；准备完成后运行态始终回到 Lua 分区。
  consoleViewProvider?.activateChannel("lua");
  const entry = activeScriptFile();
  const language = scriptLanguageForFile(entry);
  await vscode.window.activeTextEditor.document.save();
  const root = workspaceRoot();
  const graph = analyzeScriptDependencies(entry, root);
  if (graph.dynamicRequires.length) extensionOutput.appendLine(`[Warn] 发现动态脚本依赖：${graph.dynamicRequires.join("、")}；请通过 sync.extraFiles 补充依赖。`);
  const files = await mergeConfiguredExtraFiles(graph.files);
  const { remote, manifest } = await syncFiles(files);
  requireRemoteFeatures(remote, requiredRuntimeFeatures(language, true));
  await restartRemoteEngine(remote);
  consoleViewProvider?.activateChannel("lua");
  const relativeEntry = path.relative(root, entry).split(path.sep).join("/");
  const debugState = await requestJson(remote.endpoint, "POST", "/v1/debug", { language, entry: relativeEntry, manifestId: manifest.id }, remote);
  await applyDebugDapEndpoint(remote, debugState);
  const remoteTempDir = configuration().get("remoteTempDir", "/data/local/tmp").replace(/\/$/, "");
  const remoteRoot = `${remoteTempDir}/.autogo/remote/releases/${manifest.id}`;
  extensionOutput.appendLine(`[Debug] 远程脚本目录：${remoteRoot}`);
  const proxy = await startDapPathProxy(remote.dapHost, remote.dapPort, root, remoteRoot, {
    suppressOutput: true,
    onClientMessage: (message) => {
      if (message?.type === "request" && message.command === "setBreakpoints") {
        const lines = (message.arguments?.breakpoints || []).map((item) => item.line).filter(Boolean);
        extensionOutput.appendLine(`[Debug] 设置断点：${message.arguments?.source?.path || "unknown"}${lines.length ? `，行 ${lines.join("、")}` : ""}`);
      }
    },
    onServerMessage: (message) => {
      if (message?.type === "event" && message.event === "output") {
        // 脚本 stdout 由远程日志流统一输出并保留 console 等级；DAP output 仅用于协议兼容，避免双份日志。
      } else if (message?.type === "event" && message.event === "stopped") {
        extensionOutput.appendLine(`[Debug] 已暂停：${message.body?.reason || "breakpoint"}`);
      } else if (message?.type === "event" && ["terminated", "exited"].includes(message.event)) {
        extensionOutput.appendLine(`[Debug] 会话已结束${message.body?.exitCode === undefined ? "" : `，退出码 ${message.body.exitCode}`}`);
      } else if (message?.type === "response" && message.command === "setBreakpoints") {
        const verified = (message.body?.breakpoints || []).filter((item) => item.verified).length;
        const total = (message.body?.breakpoints || []).length;
        extensionOutput.appendLine(`[Debug] 断点验证：${verified}/${total}`);
      }
    },
  });
  dapProxies.add(proxy);
  const started = await vscode.debug.startDebugging(undefined, {
    type: "glua", request: "attach", name: `AutoGo ${scriptLanguageLabel(language)} Remote Debug`,
    host: proxy.host, port: proxy.port, internalConsoleOptions: "neverOpen",
  });
  if (!started) { proxy.close(); dapProxies.delete(proxy); throw new Error(`VSCode 未能启动 ${scriptLanguageLabel(language)} DAP 会话`); }
  extensionOutput.appendLine(`[Debug] DAP 已连接：${proxy.host}:${proxy.port}`);
  await startRemoteLogPolling(remote, async () => {
    const session = vscode.debug.activeDebugSession;
    if (session?.type === "glua") await vscode.debug.stopDebugging(session);
  });
  await requestJson(remote.endpoint, "POST", "/v1/run", { entry: relativeEntry, manifestId: manifest.id, language }, remote);
  extensionOutput.appendLine(`[Debug] 已启动 ${scriptLanguageLabel(language)} 调试脚本：${relativeEntry}`);
}

async function remoteRunCurrent(debug = false) {
  if (debug) return quickDebug();
  consoleViewProvider?.clearChannel("lua");
  consoleViewProvider?.activateChannel("lua");
  if (!await confirmRunPrerequisites("运行脚本")) return;
  consoleViewProvider?.activateChannel("lua");
  const entry = activeScriptFile();
  const language = scriptLanguageForFile(entry);
  await vscode.window.activeTextEditor.document.save();
  const root = workspaceRoot();
  const graph = analyzeScriptDependencies(entry, root);
  if (graph.dynamicRequires.length) extensionOutput.appendLine(`[Warn] 发现动态脚本依赖：${graph.dynamicRequires.join("、")}；请通过 sync.extraFiles 补充依赖。`);
  const { remote, manifest } = await syncFiles(await mergeConfiguredExtraFiles(graph.files));
  requireRemoteFeatures(remote, requiredRuntimeFeatures(language, false));
  await ensureRemoteEngineRunning(remote);
  consoleViewProvider?.activateChannel("lua");
  await startRemoteLogPolling(remote);
  const relativeEntry = path.relative(root, entry).split(path.sep).join("/");
  await requestJson(remote.endpoint, "POST", "/v1/run", { entry: relativeEntry, manifestId: manifest.id, language }, remote);
  extensionOutput.appendLine(`[Info] ${scriptLanguageLabel(language)} 任务已启动：${relativeEntry}`);
}

async function compileGluac(remoteRun, remoteDebug) {
  if (remoteRun || remoteDebug) consoleViewProvider?.activateChannel("lua");
  if ((remoteRun || remoteDebug) && !await confirmRunPrerequisites(remoteDebug ? "调试 GLuac" : "运行 GLuac")) return;
  const source = activeLuaFile();
  const version = await vscode.window.showInputBox({ prompt: "目标 GLua 运行时版本号（必填）", validateInput: (value) => /^[A-Za-z0-9][A-Za-z0-9._+-]*$/.test(value) ? undefined : "只能包含字母、数字、点、下划线、加号和连字符" });
  if (!version) return;
  const root = workspaceRoot();
  const directory = path.join(root, ".autogo", "build", "gluac", version);
  fs.mkdirSync(directory, { recursive: true });
  const outputFile = path.join(directory, `${path.basename(source).replace(/\.(lua|glua)$/i, "")}.luac`);
  await executeProcess(toolPath("gluac"), ["-o", outputFile, source], { output: extensionOutput });
  const metadata = { formatVersion: 1, runtimeVersion: version, source, artifactSha256: sha256(fs.readFileSync(outputFile)), debugInfo: true };
  fs.writeFileSync(`${outputFile}.json`, `${JSON.stringify(metadata, null, 2)}\n`);
  if (remoteRun) {
    const graph = analyzeLuaDependencies(source, root);
    if (graph.dynamicRequires.length) extensionOutput.appendLine(`[Warn] GLuac 源码包含动态 require：${graph.dynamicRequires.join("、")}；请通过 sync.extraFiles 补充依赖。`);
    const { remote, manifest } = await syncFiles(await mergeConfiguredExtraFiles([...graph.files, outputFile, `${outputFile}.json`]));
    requireRemoteFeatures(remote, ["gluac", "incremental-sync", ...(remoteDebug ? ["dap"] : [])]);
    await restartRemoteEngine(remote);
    if (remoteDebug) {
      await requestJson(remote.endpoint, "POST", "/v1/debug", {}, remote);
      const started = await vscode.debug.startDebugging(undefined, {
        type: "glua", request: "attach", name: "AutoGo GLuac Remote Debug", host: remote.dapHost, port: remote.dapPort, internalConsoleOptions: "neverOpen",
      });
      if (!started) throw new Error("VSCode 未能启动 GLuac DAP 会话");
    }
    await startRemoteLogPolling(remote);
    await requestJson(remote.endpoint, "POST", "/v1/run", { entry: path.relative(root, outputFile).split(path.sep).join("/"), manifestId: manifest.id }, remote);
  }
}

async function syncResources() {
  const device = await requireDevice();
  const adb = toolPath("adb");
  const abiResult = await executeProcess(adb, ["-s", device, "shell", "getprop", "ro.product.cpu.abi"], { exclusive: false });
  const abi = abiResult.stdout.trim().replace("x86-64", "x86_64");
  const local = path.join(workspaceRoot(), "resources", "libs", abi);
  if (!fs.existsSync(local)) throw new Error(`未找到设备架构资源目录：${local}`);
  const remoteDir = configuration().get("remoteTempDir", "/data/local/tmp");
  const libraries = fs.readdirSync(local).filter((name) => name.endsWith(".so")).sort();
  const escaped = remoteDir.replace(/'/g, "'\\''");
  const scan = await executeProcess(adb, ["-s", device, "shell", "sh", "-c",
    `mkdir -p '${escaped}'; for f in '${escaped}'/*.so; do [ -f \"$f\" ] && stat -c '%n:%s' \"$f\"; done`], { exclusive: false });
  const remoteSizes = new Map();
  for (const line of scan.stdout.split(/\r?\n/)) {
    const index = line.lastIndexOf(":");
    if (index > 0 && /^\d+$/.test(line.slice(index + 1).trim())) remoteSizes.set(path.posix.basename(line.slice(0, index)), Number(line.slice(index + 1).trim()));
  }
  let updated = 0;
  let skipped = 0;
  for (const name of libraries) {
    const source = path.join(local, name);
    if (remoteSizes.get(name) === fs.statSync(source).size) { skipped++; output.appendLine(`已存在：${name}`); continue; }
    await executeProcess(adb, ["-s", device, "push", source, `${remoteDir}/${name}`], { exclusive: false });
    updated++;
  }
  output.appendLine(`资源同步完成：架构=${abi}，更新=${updated}，已存在=${skipped}`);
}

async function pushFile() {
  const selected = await vscode.window.showOpenDialog({ canSelectMany: true, canSelectFiles: true, canSelectFolders: false });
  if (!selected?.length) return;
  const device = await requireDevice();
  const remoteDir = configuration().get("remoteTempDir", "/data/local/tmp");
  for (const uri of selected) await executeProcess(toolPath("adb"), ["-s", device, "push", uri.fsPath, `${remoteDir}/${path.basename(uri.fsPath)}`], { exclusive: false });
}

async function nodeAssistant() {
  const device = await requireDevice();
  await executeProcess(toolPath("ag"), ["nodeserve", "-s", device], { exclusive: false });
  await vscode.commands.executeCommand("simpleBrowser.show", `http://127.0.0.1:8801/index.html?device=${encodeURIComponent(device)}`);
}

async function testProxy() {
  const url = configuration().get("proxy.testUrl", "https://www.google.com/generate_204");
  const curl = discoverExecutable("curl") || "curl";
  await executeProcess(curl, ["--fail", "--silent", "--show-error", "--max-time", "10", url], { exclusive: false });
  vscode.window.showInformationMessage("AutoGo 网络代理测试成功");
}

async function selectExecutablePath(name, label) {
  const selected = await vscode.window.showOpenDialog({
    title: `选择 ${label} 可执行文件`, canSelectMany: false, canSelectFiles: true, canSelectFolders: false,
    defaultUri: configuration().get(`${name}Path`, "") ? vscode.Uri.file(configuration().get(`${name}Path`)) : undefined,
  });
  if (!selected?.length) return;
  const executable = selected[0].fsPath;
  if (!fs.statSync(executable).isFile()) throw new Error(`所选 ${label} 路径不是文件：${executable}`);
  await configuration().update(`${name}Path`, executable, vscode.ConfigurationTarget.Global);
  output.appendLine(`已设置 ${label} 路径：${executable}`);
}

async function selectCustomInitializer() {
  const selected = await vscode.window.showOpenDialog({
    title: "选择自定义 GLua 引擎初始化 Go 文件", canSelectMany: false, canSelectFiles: true, canSelectFolders: false,
    filters: { "Go 源文件": ["go"] },
  });
  if (!selected?.length) return;
  await configuration().update("customInitializer", selected[0].fsPath, vscode.ConfigurationTarget.Workspace);
}

async function downloadWithLimit(address, target, maximumBytes) {
  const curl = discoverExecutable("curl") || "curl";
  await executeProcess(curl, ["--location", "--fail", "--silent", "--show-error", "--connect-timeout", "10",
    "--max-time", "1800", "--max-filesize", String(maximumBytes), "--output", target, address], { exclusive: false });
  const size = fs.statSync(target).size;
  if (size <= 0 || size > maximumBytes) throw new Error(`下载内容大小无效：${size} bytes`);
}

async function checkAgUpdate() {
  const ag = toolPath("ag");
  if (!path.isAbsolute(ag)) throw new Error("检查更新需要先选择 AG 的绝对路径");
  const temporaryDirectory = fs.mkdtempSync(path.join(require("os").tmpdir(), "autogo-update-"));
  const changelogFile = path.join(temporaryDirectory, "changelog.md");
  try {
    await downloadWithLimit(CHANGELOG_URL, changelogFile, 4 * 1024 * 1024);
    const latest = latestVersionFromChangelog(fs.readFileSync(changelogFile, "utf8"));
    const currentResult = await executeProcess(ag, ["version"], { exclusive: false });
    const currentOutput = `${currentResult.stdout}\n${currentResult.stderr}`.trim();
    output.appendLine(`当前 AG：${currentOutput || "unknown"}；最新版本：${latest}`);
    if (versionMatches(currentOutput, latest)) {
      vscode.window.showInformationMessage(`AG 已是最新版本 ${latest}`);
      return;
    }
    const answer = await vscode.window.showInformationMessage(`发现 AG ${latest}，是否下载并更新？`, { modal: true }, "更新");
    if (answer !== "更新") return;
    const downloaded = path.join(path.dirname(ag), `.${path.basename(ag)}.${process.pid}.download`);
    const currentLabel = (currentOutput.split(/\s+/).find((item) => /\d/.test(item)) || "unknown").replace(/[^A-Za-z0-9._-]/g, "_");
    const backup = `${ag}_${currentLabel}`;
    try {
      await downloadWithLimit(`${SDK_BASE_URL}${agPlatformFile(latest)}`, downloaded, MAX_AG_DOWNLOAD_BYTES);
      if (process.platform !== "win32") fs.chmodSync(downloaded, 0o755);
      const validation = await runProcess(downloaded, ["version"], {
        cwd: path.dirname(ag), env: process.env, maxOutputBytes: 1024 * 1024,
      });
      if (validation.code !== 0 || !versionMatches(`${validation.stdout}\n${validation.stderr}`, latest)) {
        throw new Error(`下载的 AG 无法验证为 ${latest}：${validation.stdout || validation.stderr}`);
      }
      if (fs.existsSync(ag)) fs.copyFileSync(ag, backup);
      fs.renameSync(downloaded, ag);
      writeAtomic(`${ag}.version`, `${latest}\n`);
      vscode.window.showInformationMessage(`AG 已更新到 ${latest}`);
      output.appendLine(`AG 更新完成：${ag}；备份：${backup}`);
    } finally {
      if (fs.existsSync(downloaded)) fs.rmSync(downloaded, { force: true });
    }
  } finally {
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
}

function availableModules() {
  const root = workspaceRoot();
  const engineRoot = path.join(root, ENGINE_DIRECTORY, "lua_engine");
  if (!fs.existsSync(engineRoot)) return [];
  const names = new Set();
  const stack = [engineRoot];
  while (stack.length) {
    const directory = stack.pop();
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const target = path.join(directory, entry.name);
      if (entry.isDirectory()) stack.push(target);
      else if (entry.isFile() && entry.name.endsWith(".go")) {
        const source = fs.readFileSync(target, "utf8");
        for (const match of source.matchAll(/func\s*\([^)]*\)\s*Name\(\)\s*string\s*\{\s*return\s*["`]([^"`]+)["`]/g)) names.add(match[1]);
      }
    }
  }
  return [...names].sort();
}

async function selectModules() {
  const config = configuration();
  const policy = await vscode.window.showQuickPick([
    { label: "全部模块", value: "ALL" }, { label: "仅白名单", value: "ALLOWLIST" }, { label: "排除黑名单", value: "DENYLIST" },
  ], { placeHolder: "选择 AutoGo 模块策略" });
  if (!policy) return;
  await config.update("modulePolicy", policy.value, vscode.ConfigurationTarget.Workspace);
  if (policy.value === "ALL") {
    await config.update("modules", [], vscode.ConfigurationTarget.Workspace);
    return;
  }
  const current = new Set(config.get("modules", []));
  const choices = availableModules().map((name) => ({ label: name, picked: current.has(name) }));
  if (!choices.length) throw new Error("未发现模块目录，请先初始化 autogo_scriptengine 依赖");
  const selected = await vscode.window.showQuickPick(choices, { canPickMany: true, placeHolder: policy.value === "ALLOWLIST" ? "选择允许加载的模块" : "选择禁止加载的模块" });
  if (selected) await config.update("modules", selected.map((item) => item.label), vscode.ConfigurationTarget.Workspace);
}

async function switchToWireless() {
  const device = await requireDevice();
  const adb = toolPath("adb");
  await executeProcess(adb, ["-s", device, "tcpip", "5555"], { exclusive: false });
  const route = await executeProcess(adb, ["-s", device, "shell", "ip", "route"], { exclusive: false });
  const ip = route.stdout.match(/\bsrc\s+(\d{1,3}(?:\.\d{1,3}){3})\b/)?.[1];
  if (!ip) throw new Error("无法从设备路由信息读取 Wi-Fi IP");
  const endpoint = `${ip}:5555`;
  await executeProcess(adb, ["connect", endpoint], { exclusive: false });
  const online = await refreshDevices(false);
  if (!online.includes(endpoint)) throw new Error(`无线端点未进入 device 状态，仍保留原设备：${endpoint}`);
  await persistSelectedDevice(endpoint);
  vscode.window.showInformationMessage(`设备已切换为无线连接：${endpoint}`);
}

async function pairWireless() {
  const endpoint = await vscode.window.showInputBox({ prompt: "ADB 无线调试配对地址，例如 192.168.1.2:37123" });
  if (!endpoint) return;
  const code = await vscode.window.showInputBox({ prompt: "一次性配对码", password: true });
  if (!code) return;
  await executeProcess(toolPath("adb"), ["pair", endpoint, code], { exclusive: false });
}

class AutoGoConsoleViewProvider {
  constructor() {
    this.view = undefined;
    this.entries = [];
    this.statusTimer = undefined;
    this.statusRefreshing = false;
  }

  resolveWebviewView(view) {
    this.view = view;
    view.webview.options = {
      enableScripts: true,
      localResourceRoots: [vscode.Uri.joinPath(extensionContext.extensionUri, "resources", "icons")],
    };
    view.webview.html = this.html();
    view.webview.onDidReceiveMessage(async (message) => {
      if (message?.type === "command" && typeof message.command === "string") {
        void vscode.commands.executeCommand(message.command);
      } else if (message?.type === "openSettings") {
        this.showSettings();
      } else if (message?.type === "saveSettings") {
        await guarded(async () => {
          await this.saveSettings(message.settings || {});
          this.post({ type: "settingsSaved" });
          this.appendLine("设置已保存");
        });
      } else if (message?.type === "selectPath") {
        await guarded(() => this.selectPath(message.key));
      } else if (message?.type === "refreshSettingsDevices") {
        await guarded(async () => this.post({
          type: "devices",
          devices: await refreshDevices(false, { silent: false }),
          selectedDevice: selectedProjectDevice(),
        }));
      } else if (message?.type === "testProxy") {
        await guarded(async () => {
          await this.saveSettings(message.settings || {});
          await testProxy();
          this.post({ type: "proxyResult", ok: true, text: "代理测试成功" });
        });
      } else if (message?.type === "clear") {
        const channel = CONSOLE_CHANNELS.includes(message.channel) ? message.channel : "extension";
        pendingConsoleText[channel] = "";
        this.entries = this.entries.filter((entry) => entry.channel !== channel);
        this.post({ type: "clear", channel });
      } else if (message?.type === "clearAll") {
        for (const channel of CONSOLE_CHANNELS) pendingConsoleText[channel] = "";
        this.entries = [];
        this.post({ type: "clearAll" });
      }
    });
    for (const entry of this.entries) this.post({ type: "log", entry });
    void this.refreshEngineStatus();
  }

  dispose() {
    // 状态扫描由扩展级单一定时任务管理，视图销毁无需维护额外轮询器。
  }

  appendLine(text, channel = "extension") {
    const rawMessage = String(text ?? "");
    const explicitLevel = rawMessage.match(/^\[(Info|Msg|Debug|Warn|Error)\]/i)?.[1]?.toLowerCase();
    // 级别用于颜色表达，不在正文重复显示标签。
    const message = rawMessage.replace(/^\[(Info|Msg|Debug|Warn|Error)\]\s*/i, "");
    const level = explicitLevel
      || (/\[错误\]|失败|error|exception/i.test(message)
        ? "error"
        : /\[警告\]|警告|warning|未找到|未安装|不可达/i.test(message)
          ? "warn"
          : /\[glua-(?:lsp|dap)\]|\[调试\]/i.test(message)
            ? "debug"
            : /完成|成功|已启动|已重启|已同步|版本|已就绪|工具发现/.test(message)
              ? "info"
              : "msg");
    const entry = {
      time: new Date().toLocaleTimeString("zh-CN", {
        hour12: false,
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        fractionalSecondDigits: 3,
      }),
      level,
      channel,
      message,
    };
    this.entries.push(entry);
    if (this.entries.length > 1500) this.entries.splice(0, this.entries.length - 1500);
    this.post({ type: "log", entry });
  }

  setEngineStatus(text) {
    this.post({ type: "status", running: false, text });
  }

  post(message) {
    void this.view?.webview.postMessage(message);
  }

  activateChannel(channel) {
    if (CONSOLE_CHANNELS.includes(channel)) this.post({ type: "activateChannel", channel });
  }

  clearChannel(channel) {
    if (!CONSOLE_CHANNELS.includes(channel)) return;
    pendingConsoleText[channel] = "";
    this.entries = this.entries.filter((entry) => entry.channel !== channel);
    this.post({ type: "clear", channel });
  }

  showSettings() {
    output.show(true);
    if (this.settingsPanel) {
      this.settingsPanel.reveal(vscode.ViewColumn.One, true);
      this.settingsPanel.webview.postMessage({ type: "settings", settings: this.settingsPayload() });
      return;
    }
    const panel = vscode.window.createWebviewPanel(
      "autogo.settings",
      "AutoGo 设置",
      { viewColumn: vscode.ViewColumn.One, preserveFocus: false },
      { enableScripts: true, retainContextWhenHidden: true },
    );
    this.settingsPanel = panel;
    panel.webview.html = this.settingsHtml();
    panel.onDidDispose(() => { this.settingsPanel = undefined; });
    panel.webview.onDidReceiveMessage(async (message) => {
      if (message?.type === "ready") {
        panel.webview.postMessage({ type: "settings", settings: this.settingsPayload() });
      } else if (message?.type === "saveSettings") {
        await guarded(async () => {
          const beforePolicy = configuration().get("modulePolicy", "ALL");
          const beforeModules = [...configuration().get("modules", [])].sort();
          await this.saveSettings(message.settings || {});
          panel.webview.postMessage({ type: "settingsSaved" });
          this.appendLine("设置已保存");
          const changed = beforePolicy !== configuration().get("modulePolicy", "ALL")
            || JSON.stringify(beforeModules) !== JSON.stringify([...configuration().get("modules", [])].sort());
          if (changed) await this.handleModuleConfigurationChanged(panel);
        });
      } else if (message?.type === "selectPath") {
        await guarded(async () => {
          const value = await this.choosePath(message.key);
          if (value) panel.webview.postMessage({ type: "selectedPath", key: message.key, value });
        });
      } else if (message?.type === "refreshSettingsDevices") {
        await guarded(async () => panel.webview.postMessage({
          type: "devices",
          devices: await refreshDevices(false, { silent: false }),
          selectedDevice: selectedProjectDevice(),
        }));
      } else if (message?.type === "discoverTools") {
        await guarded(async () => {
          await discoverAndPersistTools();
          panel.webview.postMessage({ type: "settings", settings: this.settingsPayload() });
        });
      } else if (message?.type === "testProxy") {
        await guarded(async () => {
          await this.saveSettings(message.settings || {});
          await testProxy();
          panel.webview.postMessage({ type: "proxyResult", ok: true, text: "代理测试成功" });
        });
      } else if (message?.type === "moduleRegenerationDecision") {
        await guarded(async () => {
          if (message.remember) {
            await configuration().update("moduleRegenerationPreference", message.regenerate ? "ALWAYS" : "NEVER", vscode.ConfigurationTarget.Workspace);
          }
          if (message.regenerate) await vscode.commands.executeCommand("autogo.applyEngineConfig");
          panel.webview.postMessage({ type: "modulePreference", value: configuration().get("moduleRegenerationPreference", "ASK") });
        });
      } else if (message?.type === "clearModulePreference") {
        await configuration().update("moduleRegenerationPreference", "ASK", vscode.ConfigurationTarget.Workspace);
        panel.webview.postMessage({ type: "modulePreference", value: "ASK" });
      }
    });
  }

  async handleModuleConfigurationChanged(panel) {
    const preference = configuration().get("moduleRegenerationPreference", "ASK");
    if (preference === "ALWAYS") {
      await vscode.commands.executeCommand("autogo.applyEngineConfig");
    } else if (preference === "ASK") {
      panel.webview.postMessage({ type: "confirmModuleRegeneration" });
    } else {
      this.appendLine("AutoGo 模块配置已变化；按记住的选项跳过模块引入代码生成");
    }
  }

  settingsPayload() {
    const config = configuration();
    let modules = [];
    try { modules = availableModules(); } catch (_) { modules = []; }
    return {
      agPath: config.get("agPath", ""), adbPath: config.get("adbPath", ""), goPath: config.get("goPath", ""), gluacPath: settingsGluacPath(),
      defaultDevice: selectedProjectDevice(), remoteTempDir: config.get("remoteTempDir", "/data/local/tmp"),
      modulePolicy: config.get("modulePolicy", "ALL"), selectedModules: config.get("modules", []), availableModules: modules,
      moduleRegenerationPreference: config.get("moduleRegenerationPreference", "ASK"),
      customInitializer: config.get("customInitializer", ""), proxyEnabled: config.get("proxy.enabled", false), proxyType: config.get("proxy.type", "http"),
      proxyHost: config.get("proxy.host", ""), proxyPort: config.get("proxy.port", 7890), proxyAuth: config.get("proxy.auth", false),
      proxyUsername: config.get("proxy.username", ""), proxyTestUrl: config.get("proxy.testUrl", "https://www.google.com/generate_204"),
    };
  }

  async saveSettings(settings) {
    const config = configuration();
    const previousDevice = config.get("defaultDevice", "");
    const selectedDevice = String(settings.defaultDevice || "").trim();
    const globalValues = {
      agPath: settings.agPath, adbPath: settings.adbPath, goPath: settings.goPath, gluacPath: persistedGluacPath(settings.gluacPath),
      "proxy.enabled": Boolean(settings.proxyEnabled), "proxy.type": settings.proxyType,
      "proxy.host": settings.proxyHost, "proxy.port": Number(settings.proxyPort || 0), "proxy.auth": Boolean(settings.proxyAuth),
      "proxy.username": settings.proxyUsername, "proxy.testUrl": settings.proxyTestUrl,
    };
    const workspaceValues = {
      remoteTempDir: settings.remoteTempDir, modulePolicy: settings.modulePolicy,
      modules: Array.isArray(settings.selectedModules) ? settings.selectedModules : [], customInitializer: settings.customInitializer,
    };
    if (globalValues["proxy.enabled"] && (!String(globalValues["proxy.host"] || "").trim() || globalValues["proxy.port"] < 1 || globalValues["proxy.port"] > 65535)) {
      throw new Error("启用代理时必须填写有效的 IP/主机和 1-65535 端口");
    }
    for (const [key, value] of Object.entries(globalValues)) await config.update(key, value ?? "", vscode.ConfigurationTarget.Global);
    for (const [key, value] of Object.entries(workspaceValues)) await config.update(key, value ?? "", vscode.ConfigurationTarget.Workspace);
    await persistSelectedDevice(selectedDevice);
    invalidateRemoteDiscovery(previousDevice);
    invalidateRemoteDiscovery(selectedDevice);
    if (selectedDevice) setMobileEngineState(selectedDevice, "stopped");
    else this.setEngineStatus("移动端引擎：未选择设备");
    requestRemoteServiceStatusScan();
    if (typeof settings.proxyPassword === "string" && settings.proxyPassword) {
      proxyPassword = settings.proxyPassword;
      await extensionContext.secrets.store("autogo.proxyPassword", proxyPassword);
    }
  }

  async selectPath(key) {
    const value = await this.choosePath(key);
    if (value) this.post({ type: "selectedPath", key, value });
  }

  async choosePath(key) {
    const definitions = {
      agPath: ["选择 AG 可执行文件", undefined], adbPath: ["选择 ADB 可执行文件", undefined],
      goPath: ["选择 Go 可执行文件", undefined], gluacPath: ["选择 GLuac 可执行文件", undefined],
      customInitializer: ["选择自定义 GLua 引擎初始化 Go 文件", { "Go 源文件": ["go"] }],
    };
    if (!definitions[key]) throw new Error(`不支持的文件配置：${key}`);
    const selected = await vscode.window.showOpenDialog({
      title: definitions[key][0], canSelectMany: false, canSelectFiles: true, canSelectFolders: false, filters: definitions[key][1],
    });
    return selected?.[0]?.fsPath || "";
  }

  settingsHtml() {
    return `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
      :root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;padding:0;color:var(--vscode-foreground);background:var(--vscode-editor-background);font-family:var(--vscode-font-family);font-size:13px}.layout{display:grid;grid-template-columns:260px minmax(420px,900px);gap:26px;max-width:1240px;margin:0 auto;padding:34px}.sidebar,.content{border:1px solid var(--vscode-panel-border);border-radius:10px;background:var(--vscode-editorWidget-background)}.sidebar{align-self:start;padding:20px}.brand h1{font-size:20px;margin:0 0 4px}.brand p{margin:0 0 22px;color:var(--vscode-descriptionForeground)}.nav{display:flex;flex-direction:column;gap:6px}.nav button{height:40px;padding:0 13px;text-align:left;border:0;border-radius:6px;color:var(--vscode-foreground);background:transparent;cursor:pointer}.nav button:hover{background:var(--vscode-list-hoverBackground)}.nav button.active{color:var(--vscode-list-activeSelectionForeground);background:var(--vscode-list-activeSelectionBackground)}.content{min-height:560px;padding:26px 30px}.page{display:none}.page.active{display:block}.page h2{font-size:22px;margin:0 0 6px}.subtitle{margin:0 0 24px;color:var(--vscode-descriptionForeground)}.section{padding:20px 0;border-top:1px solid var(--vscode-panel-border)}.section:first-of-type{border-top:0}.grid{display:grid;grid-template-columns:repeat(2,minmax(240px,1fr));gap:15px 18px}.field{display:flex;flex-direction:column;gap:6px}.field.wide{grid-column:1/-1}.field label{font-weight:600}.input-row{display:flex;gap:8px}input,select{width:100%;min-width:0;height:34px;padding:5px 9px;color:var(--vscode-input-foreground);background:var(--vscode-input-background);border:1px solid var(--vscode-input-border,transparent);outline:none}input:focus,select:focus{border-color:var(--vscode-focusBorder)}select[multiple]{height:230px;padding:5px}.check{display:flex;align-items:center;gap:9px;min-height:34px}.check input{width:auto;height:auto}.hint{color:var(--vscode-descriptionForeground);font-size:12px}.actions{display:flex;align-items:center;justify-content:flex-end;gap:10px;margin-top:28px;padding-top:20px;border-top:1px solid var(--vscode-panel-border)}#message{margin-right:auto}.button{height:34px;padding:0 15px;border:1px solid transparent;border-radius:3px;color:var(--vscode-button-foreground);background:var(--vscode-button-background);cursor:pointer;white-space:nowrap}.button:hover{background:var(--vscode-button-hoverBackground)}.button.secondary{color:var(--vscode-button-secondaryForeground);background:var(--vscode-button-secondaryBackground)}.button.secondary:hover{background:var(--vscode-button-secondaryHoverBackground)}@media(max-width:620px){.layout{grid-template-columns:1fr;padding:18px}.nav{display:grid;grid-template-columns:repeat(2,1fr)}.grid{grid-template-columns:1fr}.field.wide{grid-column:auto}}
      .confirm-overlay{position:fixed;inset:0;z-index:30;display:none;align-items:center;justify-content:center;background:rgba(0,0,0,.58)}.confirm-overlay.open{display:flex}.confirm{width:min(460px,90vw);padding:24px;border:1px solid var(--vscode-widget-border);border-radius:8px;background:var(--vscode-editorWidget-background);box-shadow:0 14px 44px rgba(0,0,0,.45)}.confirm h3{font-size:17px;margin:0 0 10px}.confirm p{color:var(--vscode-descriptionForeground);line-height:1.55}.confirm-actions{display:flex;justify-content:flex-end;gap:9px;margin-top:20px}
    </style></head><body><main class="layout"><aside class="sidebar"><div class="brand"><h1>AutoGo 设置</h1><p>脚本引擎与开发环境</p></div><nav class="nav"><button class="active" data-page="tools">工具与设备</button><button data-page="modules">AutoGo 模块管理</button><button data-page="engine">引擎与项目</button><button data-page="proxy">网络代理</button></nav></aside><section class="content">
      <div class="page active" id="page-tools"><h2>工具与设备</h2><p class="subtitle">配置 AutoGo 所需的本地工具和默认 Android 设备。</p><div class="section grid">
        <div class="field wide"><label>AG 可执行文件</label><div class="input-row"><input data-setting="agPath"><button class="button secondary" data-select="agPath">浏览</button></div></div><div class="field wide"><label>ADB 可执行文件</label><div class="input-row"><input data-setting="adbPath"><button class="button secondary" data-select="adbPath">浏览</button></div></div><div class="field wide"><label>Go 可执行文件</label><div class="input-row"><input data-setting="goPath"><button class="button secondary" data-select="goPath">浏览</button></div></div><div class="field wide"><label>GLuac 可执行文件</label><div class="input-row"><input data-setting="gluacPath"><button class="button secondary" data-select="gluacPath">浏览</button></div></div><div class="field"><label>默认 Android 设备</label><div class="input-row"><select data-setting="defaultDevice"></select><button id="refreshDevices" class="button secondary">刷新</button></div></div><div class="field"><label>设备临时目录</label><input data-setting="remoteTempDir"></div><div class="field wide"><button id="discoverTools" class="button secondary">重新自动发现工具路径</button></div>
      </div></div>
      <div class="page" id="page-modules"><h2>AutoGo 模块管理</h2><p class="subtitle">通过可视化选择维护 AutoGo 模块白名单或黑名单。</p><div class="section grid"><div class="field"><label>模块策略</label><select data-setting="modulePolicy"><option value="ALL">全部模块</option><option value="ALLOWLIST">仅白名单</option><option value="DENYLIST">排除黑名单</option></select></div><div class="field wide"><label>模块选择</label><select data-setting="selectedModules" multiple></select><span class="hint">按住 Cmd/Ctrl 可选择多个模块；“全部模块”策略下此列表不会限制模块。</span></div><div class="field wide"><label>模块引入代码生成偏好</label><div class="input-row"><input id="modulePreference" readonly><button id="clearModulePreference" class="button secondary">清除记住选项</button></div><span class="hint">清除后，下次模块发生变化时会重新询问。</span></div></div></div>
      <div class="page" id="page-engine"><h2>引擎与项目</h2><p class="subtitle">设置设备目录和可选的自定义初始化入口。</p><div class="section grid"><div class="field wide"><label>自定义初始化 Go 文件</label><div class="input-row"><input data-setting="customInitializer"><button class="button secondary" data-select="customInitializer">浏览</button></div><span class="hint">留空时由扩展根据模块策略生成包含完整 Debug 能力的初始化代码。</span></div><div class="field"><label>设备临时目录</label><input data-setting="remoteTempDir"></div></div></div>
      <div class="page" id="page-proxy"><h2>网络代理</h2><p class="subtitle">代理同时应用于 AG 下载、依赖下载和后续代码 Clone。</p><div class="section grid"><label class="check"><input type="checkbox" data-setting="proxyEnabled">启用网络代理</label><div></div><div class="field"><label>代理类型</label><select data-setting="proxyType"><option value="http">HTTP</option><option value="https">HTTPS</option><option value="socks5">SOCKS5</option></select></div><div class="field"><label>IP / 主机</label><input data-setting="proxyHost"></div><div class="field"><label>端口</label><input type="number" min="1" max="65535" data-setting="proxyPort"></div><label class="check"><input type="checkbox" data-setting="proxyAuth">需要认证</label><div class="field"><label>用户名</label><input data-setting="proxyUsername"></div><div class="field"><label>密码</label><input type="password" data-setting="proxyPassword" placeholder="留空表示不修改"></div><div class="field wide"><label>代理测试地址</label><div class="input-row"><input data-setting="proxyTestUrl"><button id="testProxy" class="button secondary">测试代理</button></div></div></div></div>
      <div class="actions"><span id="message"></span><button id="reload" class="button secondary">恢复已保存值</button><button id="save" class="button">保存设置</button></div>
    </section></main><div id="moduleConfirm" class="confirm-overlay"><div class="confirm" role="dialog" aria-modal="true"><h3>重新生成模块引入代码？</h3><p>检测到 AutoGo 模块策略或模块选择发生变化。是否立即重新生成模块引入代码？生成过程会保留原入口备份，并包含完整 Debug 能力。</p><label class="check"><input id="rememberModuleDecision" type="checkbox">记住此选择</label><div class="confirm-actions"><button class="button secondary" data-regenerate="false">暂不生成</button><button class="button" data-regenerate="true">重新生成</button></div></div></div><script>
      const vscode=acquireVsCodeApi(),message=document.getElementById('message'),input=key=>document.querySelector('[data-setting="'+key+'"]');
      document.querySelectorAll('[data-page]').forEach(button=>button.addEventListener('click',()=>{document.querySelectorAll('[data-page]').forEach(item=>item.classList.toggle('active',item===button));document.querySelectorAll('.page').forEach(page=>page.classList.toggle('active',page.id==='page-'+button.dataset.page))}));
      document.querySelectorAll('[data-select]').forEach(button=>button.addEventListener('click',()=>vscode.postMessage({type:'selectPath',key:button.dataset.select})));
      const read=()=>{const value={};document.querySelectorAll('[data-setting]').forEach(element=>{value[element.dataset.setting]=element.type==='checkbox'?element.checked:element.multiple?Array.from(element.selectedOptions).map(option=>option.value):element.value});return value};
      const devices=(items,selected)=>{const element=input('defaultDevice');element.textContent='';const empty=document.createElement('option');empty.value='';empty.textContent='未选择设备';element.appendChild(empty);(items||[]).forEach(name=>{const option=document.createElement('option');option.value=name;option.textContent=name;option.selected=name===selected;element.appendChild(option)});if(selected&&!(items||[]).includes(selected)){const option=document.createElement('option');option.value=selected;option.textContent=selected+'（当前配置）';option.selected=true;element.appendChild(option)}};
      const preferenceText=value=>value==='ALWAYS'?'始终重新生成':value==='NEVER'?'始终不生成':'每次询问';const fill=settings=>{document.querySelectorAll('[data-setting]').forEach(element=>{const value=settings[element.dataset.setting];if(element.type==='checkbox')element.checked=Boolean(value);else if(!element.multiple&&value!==undefined)element.value=value});const list=input('selectedModules');list.textContent='';(settings.availableModules||[]).forEach(name=>{const option=document.createElement('option');option.value=name;option.textContent=name;option.selected=(settings.selectedModules||[]).includes(name);list.appendChild(option)});document.getElementById('modulePreference').value=preferenceText(settings.moduleRegenerationPreference);devices([],settings.defaultDevice);vscode.postMessage({type:'refreshSettingsDevices'})};
      document.getElementById('refreshDevices').addEventListener('click',()=>{message.textContent='正在刷新设备…';vscode.postMessage({type:'refreshSettingsDevices'})});document.getElementById('discoverTools').addEventListener('click',()=>{message.textContent='正在发现工具…';vscode.postMessage({type:'discoverTools'})});document.getElementById('testProxy').addEventListener('click',()=>{message.textContent='正在测试代理…';vscode.postMessage({type:'testProxy',settings:read()})});document.getElementById('save').addEventListener('click',()=>{message.textContent='正在保存…';vscode.postMessage({type:'saveSettings',settings:read()})});document.getElementById('reload').addEventListener('click',()=>vscode.postMessage({type:'ready'}));document.getElementById('clearModulePreference').addEventListener('click',()=>vscode.postMessage({type:'clearModulePreference'}));document.querySelectorAll('[data-regenerate]').forEach(button=>button.addEventListener('click',()=>{document.getElementById('moduleConfirm').classList.remove('open');vscode.postMessage({type:'moduleRegenerationDecision',regenerate:button.dataset.regenerate==='true',remember:document.getElementById('rememberModuleDecision').checked})}));
      window.addEventListener('message',({data})=>{if(data.type==='settings'){fill(data.settings);message.textContent='';return}if(data.type==='devices'){devices(data.devices||[],data.selectedDevice??input('defaultDevice').value);message.textContent='设备列表已刷新';return}if(data.type==='selectedPath'){input(data.key).value=data.value;return}if(data.type==='settingsSaved'){message.textContent='设置已保存';return}if(data.type==='proxyResult'){message.textContent=data.text;return}if(data.type==='confirmModuleRegeneration'){document.getElementById('rememberModuleDecision').checked=false;document.getElementById('moduleConfirm').classList.add('open');return}if(data.type==='modulePreference'){document.getElementById('modulePreference').value=preferenceText(data.value);message.textContent='模块生成偏好已更新';}});vscode.postMessage({type:'ready'});
    </script></body></html>`;
  }

  async refreshEngineStatus() {
    if (this.statusRefreshing) return;
    this.statusRefreshing = true;
    try {
      const root = workspaceRoot();
      const engineConfigPath = path.join(root, ".autogo", "engine.json");
      const projectConfig = fs.existsSync(engineConfigPath) ? loadProjectConfig(root, true) : {};
      const remote = projectConfig.remote || {};
      const configuredDevice = remote.deviceSerial || configuration().get("defaultDevice", "");
      if (!remote.endpoint && !configuredDevice) {
        await vscode.commands.executeCommand("setContext", "autogo.engineRunning", false);
        this.post({ type: "status", running: false, text: "移动端引擎：未选择设备" });
        return;
      }
      const connection = currentRemote;
      if (!connection) {
        await vscode.commands.executeCommand("setContext", "autogo.engineRunning", false);
        const cached = mobileEngineState(configuredDevice);
        const labels = { stopped: "未启动", starting: "正在启动…", running: "running", failed: "启动失败" };
        const reason = cached.detail ? ` · ${String(cached.detail).split("\n")[0]}` : "";
        this.post({ type: "status", running: false, text: `移动端引擎：${labels[cached.state] || cached.state}${reason}` });
        return;
      }
      const health = await requestJson(connection.endpoint, "GET", "/v1/health", undefined, connection);
      const running = ["running", "paused"].includes(health.state);
      await vscode.commands.executeCommand("setContext", "autogo.engineRunning", running);
      const reason = health.lastError ? ` · ${String(health.lastError).split("\n")[0]}` : "";
      this.post({ type: "status", running, text: `移动端引擎：${health.state || "unknown"}${reason}` });
    } catch (error) {
      await vscode.commands.executeCommand("setContext", "autogo.engineRunning", false);
      this.post({ type: "status", running: false, text: `移动端引擎：不可达 · ${String(error.message || error).split("\n")[0]}` });
    }
    finally { this.statusRefreshing = false; }
  }

  html() {
    const iconUri = (name) => this.view.webview.asWebviewUri(vscode.Uri.joinPath(extensionContext.extensionUri, "resources", "icons", `${name}.svg`));
    const button = (command, title, icon) => `<button class="tool" data-command="${command}" data-tooltip="${title}" aria-label="${title}"><img src="${iconUri(icon)}" alt=""></button>`;
    const menuButton = (menu, title, icon) => `<button class="tool" data-menu="${menu}" data-tooltip="${title}" aria-label="${title}"><img src="${iconUri(icon)}" alt=""><span class="chevron">▾</span></button>`;
    const toolbar = [
      button("autogo.quickDebug", "快速调试当前 Lua/GLua 文件（F6）", "debug"), button("autogo.run", "运行当前 Lua/GLua 文件（F7）", "run"), button("autogo.stop", "停止运行（F8）", "stop"),
      button("autogo.startEngine", "启动或重启移动端脚本引擎", "engine"), button("autogo.syncResources", "同步当前设备架构的运行库资源（F10）", "sync"), button("autogo.nodeAssistant", "启动节点助手并在内置浏览器打开", "node"),
      menuButton("build", "编译项目", "build"), menuButton("glua", "GLua 工具", "glua"), menuButton("device", "设备管理与无线连接", "device"), menuButton("init", "初始化项目", "init"),
      button("autogo.pushFile", "选择本地文件并推送到设备", "push"), button("autogo.officialDocs", "在内置浏览器打开官方文档", "docs"), button("autogo.applyEngineConfig", "应用模块策略并重新生成引擎入口", "apply"),
      button("autogo.openSettings", "打开 AutoGo 图形化设置", "settings"), button("autogo.checkUpdate", "检查并更新 AG 命令", "update"),
    ].join("");
    const menuPanels = `<div class="tool-menu" data-menu-panel="build"><button data-command="autogo.buildArm64">编译 arm64-v8a</button><button data-command="autogo.buildX8664">编译 x86_64</button><button data-command="autogo.buildX86">编译 x86</button><button data-command="autogo.buildApk">编译 APK</button></div><div class="tool-menu" data-menu-panel="glua"><button data-command="autogo.remoteRunCurrent">远程运行当前脚本</button><button data-command="autogo.compileGluac">编译当前文件为 GLuac</button><button data-command="autogo.compileRunGluac">编译并远程运行 GLuac</button><button data-command="autogo.compileDebugGluac">编译并远程调试 GLuac</button><button data-command="editor.action.formatDocument">格式化当前文件</button></div><div class="tool-menu" data-menu-panel="device"><button data-command="autogo.refreshDevices">刷新并选择设备</button><button data-command="autogo.switchToWireless">切换为无线连接</button><button data-command="autogo.pairWireless">无线调试配对</button></div><div class="tool-menu" data-menu-panel="init"><button data-command="autogo.initAndroid">初始化 Android 项目</button><button data-command="autogo.initIos">初始化 iOS 项目</button></div>`;
    const clearIcon = iconUri("clear");
    return `<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>
      :root{color-scheme:light dark}*{box-sizing:border-box}body{padding:0;margin:0;color:var(--vscode-foreground);background:var(--vscode-panel-background);font-family:var(--vscode-font-family);font-size:var(--vscode-font-size)}
      .toolbar{height:48px;display:flex;align-items:center;gap:5px;padding:6px 10px;border-bottom:1px solid var(--vscode-panel-border);overflow-x:auto}.tool{position:relative;flex:0 0 34px;width:34px;height:32px;border:0;border-radius:4px;background:transparent;display:grid;place-items:center;cursor:pointer;touch-action:none}.tool img,.tool .chevron{pointer-events:none}.tool img{width:18px;height:18px}.tool:hover{background:var(--vscode-toolbar-hoverBackground)}.tool:focus-visible{outline:1px solid var(--vscode-focusBorder);outline-offset:-1px}.chevron{position:absolute;right:0;bottom:1px;color:var(--vscode-descriptionForeground);font-size:9px}.tool-menu{position:fixed;z-index:60;display:none;min-width:210px;padding:5px;border:1px solid var(--vscode-menu-border);border-radius:5px;background:var(--vscode-menu-background);box-shadow:0 8px 24px rgba(0,0,0,.42)}.tool-menu.open{display:block}.tool-menu button{display:block;width:100%;height:30px;padding:0 10px;border:0;border-radius:3px;text-align:left;color:var(--vscode-menu-foreground);background:transparent;cursor:pointer}.tool-menu button:hover{color:var(--vscode-menu-selectionForeground);background:var(--vscode-menu-selectionBackground)}
      .divider{height:24px;width:1px;background:var(--vscode-panel-border);margin:0 3px}.spacer{flex:1}.status{font-size:12px;white-space:nowrap;color:var(--vscode-descriptionForeground);max-width:38%;overflow:hidden;text-overflow:ellipsis}.toolbar-tooltip{position:fixed;z-index:50;display:none;max-width:320px;padding:6px 9px;border:1px solid var(--vscode-widget-border);border-radius:4px;color:var(--vscode-editorHoverWidget-foreground);background:var(--vscode-editorHoverWidget-background);box-shadow:0 4px 14px rgba(0,0,0,.35);font-size:12px;pointer-events:none}.toolbar-tooltip.show{display:block}
      .log-tabs{height:36px;display:flex;align-items:stretch;padding:0 10px;border-bottom:1px solid var(--vscode-panel-border);gap:4px}.log-tab{position:relative;padding:0 13px;border:0;border-bottom:2px solid transparent;color:var(--vscode-descriptionForeground);background:transparent;cursor:pointer}.log-tab:hover{color:var(--vscode-foreground);background:var(--vscode-toolbar-hoverBackground)}.log-tab.active{color:var(--vscode-foreground);border-bottom-color:var(--vscode-focusBorder)}.count{display:inline-block;min-width:18px;margin-left:5px;padding:0 5px;border-radius:8px;color:var(--vscode-badge-foreground);background:var(--vscode-badge-background);font-size:10px;line-height:16px}
      .log-search{height:36px;display:none;align-items:center;gap:7px;padding:4px 10px;border-bottom:1px solid var(--vscode-panel-border);background:var(--vscode-editor-background)}.log-search.open{display:flex}.log-search input{height:27px;flex:1;min-width:100px;padding:3px 7px;color:var(--vscode-input-foreground);background:var(--vscode-input-background);border:1px solid var(--vscode-input-border,transparent);outline:none}.log-search input:focus{border-color:var(--vscode-focusBorder)}.search-result{min-width:62px;text-align:center;color:var(--vscode-descriptionForeground);font-size:11px}.search-action{width:26px;height:26px;border:0;border-radius:3px;color:var(--vscode-foreground);background:transparent;cursor:pointer}.search-action:hover{background:var(--vscode-toolbar-hoverBackground)}
      #logs{height:calc(100vh - 84px);font-family:var(--vscode-editor-font-family);font-size:var(--vscode-editor-font-size);line-height:1.55}body.search-open #logs{height:calc(100vh - 120px)}.log-pane{display:none;height:100%;overflow:auto;padding:10px 12px 24px}.log-pane.active{display:block}.line{display:flex;white-space:pre-wrap;overflow-wrap:anywhere}.line.search-hidden{display:none}.line.search-current{outline:1px solid var(--vscode-focusBorder);background:var(--vscode-editor-findMatchHighlightBackground)}.time{flex:none;color:var(--vscode-descriptionForeground);margin-right:10px}.prefix{flex:none;width:12ch;min-width:12ch;white-space:nowrap;font-weight:600}.info{color:#35c9a5}.msg{color:var(--vscode-foreground)}.debug{color:var(--vscode-descriptionForeground)}.warn{color:#ffd84d}.error{color:#b94a52}.empty{color:var(--vscode-descriptionForeground)}
      .overlay{position:fixed;inset:0;z-index:20;background:rgba(0,0,0,.56);display:none;align-items:center;justify-content:center;padding:24px}.overlay.open{display:flex}.dialog{width:min(920px,96vw);max-height:90vh;display:flex;flex-direction:column;background:var(--vscode-editorWidget-background);border:1px solid var(--vscode-widget-border);border-radius:8px;box-shadow:0 12px 40px rgba(0,0,0,.4)}.dialog-head,.dialog-foot{display:flex;align-items:center;padding:14px 18px}.dialog-head{border-bottom:1px solid var(--vscode-panel-border)}.dialog-head h2{font-size:16px;margin:0}.dialog-body{overflow:auto;padding:16px 18px}.dialog-foot{justify-content:flex-end;gap:8px;border-top:1px solid var(--vscode-panel-border)}
      .section{margin-bottom:20px}.section h3{font-size:13px;margin:0 0 10px;color:var(--vscode-foreground)}.grid{display:grid;grid-template-columns:repeat(2,minmax(260px,1fr));gap:10px 16px}.field{display:flex;flex-direction:column;gap:5px}.field.wide{grid-column:1/-1}.field label{font-size:12px;color:var(--vscode-descriptionForeground)}.input-row{display:flex;gap:6px}input,select{min-width:0;width:100%;height:30px;padding:4px 8px;color:var(--vscode-input-foreground);background:var(--vscode-input-background);border:1px solid var(--vscode-input-border,transparent);outline:none}input:focus,select:focus{border-color:var(--vscode-focusBorder)}select[multiple]{height:112px}.check{display:flex;align-items:center;gap:8px;height:30px}.check input{width:auto;height:auto}.button{height:30px;padding:0 13px;border:1px solid transparent;border-radius:2px;color:var(--vscode-button-foreground);background:var(--vscode-button-background);cursor:pointer;white-space:nowrap}.button:hover{background:var(--vscode-button-hoverBackground)}.button.secondary{color:var(--vscode-button-secondaryForeground);background:var(--vscode-button-secondaryBackground)}.button.secondary:hover{background:var(--vscode-button-secondaryHoverBackground)}.close{margin-left:auto}.hint{font-size:11px;color:var(--vscode-descriptionForeground)}#formMessage{margin-right:auto;font-size:12px}.hidden{display:none!important}@media(max-width:720px){.grid{grid-template-columns:1fr}.field.wide{grid-column:auto}.status{display:none}}
    </style></head><body><div class="toolbar">${toolbar}<span class="divider"></span><span id="status" class="status">移动端引擎：检测中…</span><span class="spacer"></span><button id="clear" class="tool" data-tooltip="单击清空当前日志；长按清空全部日志" aria-label="单击清空当前日志；长按清空全部日志"><img src="${clearIcon}" alt=""></button></div>${menuPanels}<div id="toolbarTooltip" class="toolbar-tooltip"></div><div class="log-tabs"><button class="log-tab" data-log-tab="ag">AG 命令输出<span class="count" data-count="ag">0</span></button><button class="log-tab" data-log-tab="go">Go 运行输出<span class="count" data-count="go">0</span></button><button class="log-tab" data-log-tab="lua">Lua 运行输出<span class="count" data-count="lua">0</span></button><button class="log-tab active" data-log-tab="extension">扩展日志<span class="count" data-count="extension">0</span></button></div><div id="logSearch" class="log-search"><input id="logSearchInput" type="text" placeholder="搜索当前日志分区" aria-label="搜索当前日志分区"><span id="searchResult" class="search-result">0 个结果</span><button id="searchPrevious" class="search-action" title="上一个结果（Shift+Enter）">↑</button><button id="searchNext" class="search-action" title="下一个结果（Enter）">↓</button><button id="closeSearch" class="search-action" title="关闭搜索（Esc）">×</button></div><div id="logs"><div class="log-pane" data-log-pane="ag"><div class="empty">暂无 AG 命令输出</div></div><div class="log-pane" data-log-pane="go"><div class="empty">暂无 Go 运行输出</div></div><div class="log-pane" data-log-pane="lua"><div class="empty">暂无 Lua 运行输出</div></div><div class="log-pane active" data-log-pane="extension"><div class="empty">AutoGo Script Engine Console 已就绪。</div></div></div>
    <div id="settingsOverlay" class="overlay" role="dialog" aria-modal="true" aria-labelledby="settingsTitle"><div class="dialog"><div class="dialog-head"><h2 id="settingsTitle">AutoGo Script Engine Console 设置</h2><button id="closeSettings" class="tool close" title="关闭设置" aria-label="关闭设置">×</button></div><div class="dialog-body">
      <div class="section"><h3>工具与设备</h3><div class="grid">
        <div class="field wide"><label>AG 可执行文件</label><div class="input-row"><input data-setting="agPath"><button class="button secondary" data-select="agPath">选择文件</button></div></div>
        <div class="field wide"><label>ADB 可执行文件</label><div class="input-row"><input data-setting="adbPath"><button class="button secondary" data-select="adbPath">选择文件</button></div></div>
        <div class="field wide"><label>Go 可执行文件</label><div class="input-row"><input data-setting="goPath"><button class="button secondary" data-select="goPath">选择文件</button></div></div>
        <div class="field wide"><label>GLuac 可执行文件</label><div class="input-row"><input data-setting="gluacPath"><button class="button secondary" data-select="gluacPath">选择文件</button></div></div>
        <div class="field"><label>默认 Android 设备</label><div class="input-row"><select data-setting="defaultDevice"></select><button id="refreshSettingsDevices" class="button secondary">刷新</button></div></div><div class="field"><label>设备临时目录</label><input data-setting="remoteTempDir"></div>
      </div></div>
      <div class="section"><h3>AutoGo 模块管理与初始化</h3><div class="grid"><div class="field"><label>模块策略</label><select data-setting="modulePolicy"><option value="ALL">全部模块</option><option value="ALLOWLIST">仅白名单</option><option value="DENYLIST">排除黑名单</option></select></div><div class="field"><label>模块选择</label><select data-setting="selectedModules" multiple></select><span class="hint">按住 Cmd/Ctrl 可选择多个模块</span></div><div class="field wide"><label>自定义初始化 Go 文件</label><div class="input-row"><input data-setting="customInitializer"><button class="button secondary" data-select="customInitializer">选择文件</button></div></div></div></div>
      <div class="section"><h3>网络代理</h3><div class="grid"><label class="check"><input type="checkbox" data-setting="proxyEnabled">启用网络代理（同时用于下载与代码 Clone）</label><div></div><div class="field"><label>代理类型</label><select data-setting="proxyType"><option value="http">HTTP</option><option value="https">HTTPS</option><option value="socks5">SOCKS5</option></select></div><div class="field"><label>IP / 主机</label><input data-setting="proxyHost"></div><div class="field"><label>端口</label><input type="number" min="1" max="65535" data-setting="proxyPort"></div><label class="check"><input type="checkbox" data-setting="proxyAuth">需要认证</label><div class="field"><label>用户名</label><input data-setting="proxyUsername"></div><div class="field"><label>密码</label><input type="password" data-setting="proxyPassword" placeholder="留空表示不修改"></div><div class="field wide"><label>代理测试地址</label><div class="input-row"><input data-setting="proxyTestUrl"><button id="testProxy" class="button secondary">测试代理</button></div></div></div></div>
    </div><div class="dialog-foot"><span id="formMessage"></span><button id="cancelSettings" class="button secondary">取消</button><button id="saveSettings" class="button">保存设置</button></div></div></div><script>
      const vscode=acquireVsCodeApi(),logs=document.getElementById('logs'),channels=['ag','go','lua','extension'],counts={ag:0,go:0,lua:0,extension:0};let activeChannel='extension';
      let searchMatches=[],searchIndex=-1;const searchBar=document.getElementById('logSearch'),searchInput=document.getElementById('logSearchInput'),searchResult=document.getElementById('searchResult');const runSearch=()=>{document.querySelectorAll('.line.search-hidden,.line.search-current').forEach(row=>row.classList.remove('search-hidden','search-current'));const query=searchInput.value.trim().toLocaleLowerCase();const rows=Array.from(document.querySelectorAll('[data-log-pane="'+activeChannel+'"] .line'));searchMatches=query?rows.filter(row=>row.textContent.toLocaleLowerCase().includes(query)):[];if(query)rows.forEach(row=>row.classList.toggle('search-hidden',!searchMatches.includes(row)));searchIndex=searchMatches.length?0:-1;if(searchIndex>=0)searchMatches[searchIndex].classList.add('search-current');searchResult.textContent=query?(searchMatches.length?(searchIndex+1)+' / '+searchMatches.length:'无结果'):'0 个结果'};const moveSearch=step=>{if(!searchMatches.length)return;if(searchIndex>=0)searchMatches[searchIndex].classList.remove('search-current');searchIndex=(searchIndex+step+searchMatches.length)%searchMatches.length;searchMatches[searchIndex].classList.add('search-current');searchMatches[searchIndex].scrollIntoView({block:'center'});searchResult.textContent=(searchIndex+1)+' / '+searchMatches.length};const openSearch=()=>{searchBar.classList.add('open');document.body.classList.add('search-open');searchInput.focus();searchInput.select();runSearch()};const closeSearch=()=>{searchBar.classList.remove('open');document.body.classList.remove('search-open');searchInput.value='';runSearch()};const activateLogChannel=channel=>{activeChannel=channel;document.querySelectorAll('[data-log-tab]').forEach(tab=>tab.classList.toggle('active',tab.dataset.logTab===channel));document.querySelectorAll('[data-log-pane]').forEach(pane=>pane.classList.toggle('active',pane.dataset.logPane===channel));if(searchBar.classList.contains('open'))runSearch()};document.querySelectorAll('[data-log-tab]').forEach(tab=>tab.addEventListener('click',()=>activateLogChannel(tab.dataset.logTab)));searchInput.addEventListener('input',runSearch);searchInput.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();moveSearch(event.shiftKey?-1:1)}else if(event.key==='Escape'){event.preventDefault();closeSearch()}});document.getElementById('searchPrevious').addEventListener('click',()=>moveSearch(-1));document.getElementById('searchNext').addEventListener('click',()=>moveSearch(1));document.getElementById('closeSearch').addEventListener('click',closeSearch);document.addEventListener('keydown',event=>{if((event.metaKey||event.ctrlKey)&&event.key.toLocaleLowerCase()==='f'){event.preventDefault();openSearch()}else if(event.key==='Escape'&&searchBar.classList.contains('open'))closeSearch()});
      document.querySelectorAll('[data-command]').forEach(button=>button.addEventListener('click',()=>vscode.postMessage({type:'command',command:button.dataset.command})));
      const closeMenus=()=>document.querySelectorAll('[data-menu-panel]').forEach(menu=>menu.classList.remove('open'));document.querySelectorAll('[data-menu]').forEach(button=>button.addEventListener('click',event=>{event.stopPropagation();const menu=document.querySelector('[data-menu-panel="'+button.dataset.menu+'"]'),wasOpen=menu.classList.contains('open');closeMenus();if(!wasOpen){const rect=button.getBoundingClientRect();menu.style.left=Math.max(6,Math.min(rect.left,window.innerWidth-menu.offsetWidth-6))+'px';menu.style.top=(rect.bottom+4)+'px';menu.classList.add('open')}}));document.querySelectorAll('.tool-menu [data-command]').forEach(button=>button.addEventListener('click',closeMenus));document.addEventListener('click',closeMenus);
      const toolbarTooltip=document.getElementById('toolbarTooltip');document.querySelectorAll('[data-tooltip]').forEach(button=>{button.addEventListener('mouseenter',()=>{const rect=button.getBoundingClientRect();toolbarTooltip.textContent=button.dataset.tooltip;toolbarTooltip.style.left=Math.max(8,Math.min(rect.left,window.innerWidth-toolbarTooltip.offsetWidth-8))+'px';toolbarTooltip.style.top=(rect.bottom+6)+'px';toolbarTooltip.classList.add('show')});button.addEventListener('mouseleave',()=>toolbarTooltip.classList.remove('show'));button.addEventListener('focus',()=>button.dispatchEvent(new Event('mouseenter')));button.addEventListener('blur',()=>toolbarTooltip.classList.remove('show'))});
      const clearButton=document.getElementById('clear');let clearTimer,clearLongPressed=false;const cancelClearTimer=()=>{if(clearTimer){clearTimeout(clearTimer);clearTimer=undefined}};clearButton.addEventListener('pointerdown',event=>{if(event.button!==0)return;event.preventDefault();clearLongPressed=false;clearButton.setPointerCapture?.(event.pointerId);clearTimer=setTimeout(()=>{clearTimer=undefined;clearLongPressed=true;vscode.postMessage({type:'clearAll'})},800)});clearButton.addEventListener('pointerup',event=>{cancelClearTimer();clearButton.releasePointerCapture?.(event.pointerId);if(clearLongPressed){clearLongPressed=false;return}vscode.postMessage({type:'clear',channel:activeChannel})});clearButton.addEventListener('pointercancel',cancelClearTimer);
      const overlay=document.getElementById('settingsOverlay'),formMessage=document.getElementById('formMessage');
      const closeSettings=()=>{overlay.classList.remove('open');formMessage.textContent=''};
      document.getElementById('closeSettings').addEventListener('click',closeSettings);document.getElementById('cancelSettings').addEventListener('click',closeSettings);overlay.addEventListener('click',event=>{if(event.target===overlay)closeSettings()});
      document.querySelectorAll('[data-select]').forEach(button=>button.addEventListener('click',()=>vscode.postMessage({type:'selectPath',key:button.dataset.select})));
      document.getElementById('refreshSettingsDevices').addEventListener('click',()=>vscode.postMessage({type:'refreshSettingsDevices'}));
      const input=(key)=>document.querySelector('[data-setting="'+key+'"]');
      const readSettings=()=>{const value={};document.querySelectorAll('[data-setting]').forEach(element=>{value[element.dataset.setting]=element.type==='checkbox'?element.checked:element.multiple?Array.from(element.selectedOptions).map(option=>option.value):element.value});return value};
      const fillDevices=(devices,selected)=>{const element=input('defaultDevice');element.textContent='';const emptyOption=document.createElement('option');emptyOption.value='';emptyOption.textContent='未选择设备';element.appendChild(emptyOption);(devices||[]).forEach(device=>{const option=document.createElement('option');option.value=device;option.textContent=device;option.selected=device===selected;element.appendChild(option)});if(selected&&!devices.includes(selected)){const option=document.createElement('option');option.value=selected;option.textContent=selected+'（当前配置）';option.selected=true;element.appendChild(option)}};
      const fillSettings=(settings)=>{document.querySelectorAll('[data-setting]').forEach(element=>{const value=settings[element.dataset.setting];if(element.type==='checkbox')element.checked=Boolean(value);else if(!element.multiple&&value!==undefined)element.value=value});const modules=input('selectedModules');modules.textContent='';(settings.availableModules||[]).forEach(name=>{const option=document.createElement('option');option.value=name;option.textContent=name;option.selected=(settings.selectedModules||[]).includes(name);modules.appendChild(option)});fillDevices([],settings.defaultDevice);overlay.classList.add('open');vscode.postMessage({type:'refreshSettingsDevices'})};
      document.getElementById('saveSettings').addEventListener('click',()=>{formMessage.textContent='正在保存…';vscode.postMessage({type:'saveSettings',settings:readSettings()})});document.getElementById('testProxy').addEventListener('click',()=>{formMessage.textContent='正在测试代理…';vscode.postMessage({type:'testProxy',settings:readSettings()})});
      const emptyLabel=channel=>channel==='ag'?'暂无 AG 命令输出':channel==='go'?'暂无 Go 运行输出':channel==='lua'?'暂无 Lua 运行输出':'暂无扩展日志';const clearPane=channel=>{const pane=document.querySelector('[data-log-pane="'+channel+'"]');pane.innerHTML='<div class="empty">'+emptyLabel(channel)+'</div>';counts[channel]=0;document.querySelector('[data-count="'+channel+'"]').textContent='0'};window.addEventListener('message',({data})=>{if(data.type==='activateChannel'){if(channels.includes(data.channel))activateLogChannel(data.channel);return}if(data.type==='clear'){clearPane(data.channel);return}if(data.type==='clearAll'){channels.forEach(clearPane);return}if(data.type==='status'){document.getElementById('status').textContent=data.text;return}if(data.type==='settings'){fillSettings(data.settings);return}if(data.type==='settingsSaved'){formMessage.textContent='设置已保存';setTimeout(closeSettings,500);return}if(data.type==='devices'){fillDevices(data.devices||[],data.selectedDevice??input('defaultDevice').value);formMessage.textContent='设备列表已刷新';return}if(data.type==='selectedPath'){input(data.key).value=data.value;return}if(data.type==='proxyResult'){formMessage.textContent=data.text;return}if(data.type==='log'){const channel=channels.includes(data.entry.channel)?data.entry.channel:'extension',pane=document.querySelector('[data-log-pane="'+channel+'"]');pane.querySelector('.empty')?.remove();const row=document.createElement('div');row.className='line '+data.entry.level;const time=document.createElement('span');time.className='time';time.textContent='['+data.entry.time+']';const prefix=document.createElement('span');prefix.className='prefix';prefix.textContent='['+channel.toUpperCase()+']';const value=document.createElement('span');value.textContent=data.entry.message;row.append(time,prefix,value);pane.appendChild(row);pane.scrollTop=pane.scrollHeight;counts[channel]++;document.querySelector('[data-count="'+channel+'"]').textContent=String(counts[channel]);if(channel===activeChannel)pane.scrollTop=pane.scrollHeight;}});
    </script></body></html>`;
  }
}

async function activate(context) {
  extensionDeactivating = false;
  extensionContext = context;
  proxyPassword = await context.secrets.get("autogo.proxyPassword") || "";
  // 在语言服务启动前生成并配置 AutoGo API 目录，确保首次打开项目即可补全、Hover 和跳转。
  const activeRoot = vscode.workspace.workspaceFolders?.[0]?.uri.fsPath;
  await guarded(migrateLegacyGluaFormatter);
  const activeDependency = activeRoot ? path.join(activeRoot, ENGINE_DIRECTORY) : "";
  if (activeRoot && fs.existsSync(path.join(activeDependency, "go.mod"))) {
    await guarded(() => generateApiCatalog(activeRoot, activeDependency));
  }
  await gluaExtension.activate(context, extensionOutput);
  consoleViewProvider = new AutoGoConsoleViewProvider();
  context.subscriptions.push(vscode.window.registerWebviewViewProvider("autogo.console", consoleViewProvider, { webviewOptions: { retainContextWhenHidden: true } }));
  context.subscriptions.push(consoleViewProvider);
  await guarded(discoverAndPersistTools);
  await vscode.commands.executeCommand("setContext", "autogo.engineRunning", false);
  context.subscriptions.push(vscode.debug.onDidTerminateDebugSession((session) => {
    if (session.type !== "glua") return;
    if (!sendRemoteEvent({ type: "stop-debug" })) {
      extensionOutput.appendLine("[Debug] 调试控制通道已断开；仅清理本地 DAP，不停止移动端引擎。");
    }
    for (const proxy of dapProxies) proxy.close();
    dapProxies.clear();
    const device = currentRemote?.device;
    if (device) setMobileEngineState(device, "running");
    void vscode.commands.executeCommand("setContext", "autogo.engineRunning", true);
  }));
  const commands = {
    "autogo.quickDebug": quickDebug,
    "autogo.run": () => remoteRunCurrent(false),
    "autogo.stop": stopManagedMobileEngine,
    "autogo.remoteEngine": () => operateRemoteEngine(false),
    "autogo.startEngine": () => operateRemoteEngine(false),
    "autogo.restartEngine": () => operateRemoteEngine(true),
    "autogo.syncResources": syncResources,
    "autogo.nodeAssistant": nodeAssistant,
    "autogo.buildArm64": () => executeAg("build", { target: "arm64-v8a" }),
    "autogo.buildX8664": () => executeAg("build", { target: "x86_64" }),
    "autogo.buildX86": () => executeAg("build", { target: "x86" }),
    "autogo.buildApk": () => executeAg("build", { target: "apk" }),
    "autogo.remoteRunCurrent": () => remoteRunCurrent(false),
    "autogo.compileGluac": () => compileGluac(false, false),
    "autogo.compileRunGluac": () => compileGluac(true, false),
    "autogo.compileDebugGluac": () => compileGluac(true, true),
    "autogo.initAndroid": () => initializeProject("android"),
    "autogo.initIos": () => initializeProject("ios"),
    "autogo.refreshDevices": async () => {
      invalidateRemoteDiscovery();
      const selected = await refreshDevices(true);
      requestRemoteServiceStatusScan();
      return selected;
    },
    "autogo.pushFile": pushFile,
    "autogo.officialDocs": () => vscode.commands.executeCommand("simpleBrowser.show", "https://zingyao.github.io/autogo_scriptengine/"),
    "autogo.openSettings": () => consoleViewProvider.showSettings(),
    "autogo.applyEngineConfig": async () => {
      const root = workspaceRoot();
      const configFile = path.join(root, ".autogo", "engine.json");
      if (!fs.existsSync(configFile)) throw new Error("项目尚未初始化：缺少 .autogo/engine.json");
      const target = loadProjectConfig(root, true).target;
      const backup = await generateProjectHost(root, target, { preserveConfig: true, backup: true });
      output.appendLine(`已应用引擎配置。${backup ? `旧入口备份：${backup}` : "原项目没有 main.go。"}`);
    },
    "autogo.checkUpdate": checkAgUpdate,
    "autogo.discoverTools": discoverAndPersistTools,
    "autogo.testProxy": testProxy,
    "autogo.selectModules": selectModules,
    "autogo.switchToWireless": switchToWireless,
    "autogo.pairWireless": pairWireless,
    "autogo.setProxyPassword": async () => {
      const value = await vscode.window.showInputBox({ prompt: "网络代理密码", password: true, ignoreFocusOut: true });
      if (value === undefined) return;
      proxyPassword = value;
      if (value) await context.secrets.store("autogo.proxyPassword", value);
      else await context.secrets.delete("autogo.proxyPassword");
    },
    "autogo.selectAgPath": () => selectExecutablePath("ag", "AG"),
    "autogo.selectAdbPath": () => selectExecutablePath("adb", "ADB"),
    "autogo.selectGoPath": () => selectExecutablePath("go", "Go"),
    "autogo.selectGluacPath": () => selectExecutablePath("gluac", "GLuac"),
    "autogo.selectCustomInitializer": selectCustomInitializer,
    "autogo.setRemoteToken": async () => {
      const token = await vscode.window.showInputBox({ prompt: "远程引擎 Bearer Token", password: true, ignoreFocusOut: true });
      if (token === undefined) return;
      if (token) await context.secrets.store("autogo.remoteToken", token);
      else await context.secrets.delete("autogo.remoteToken");
    },
    "autogo.showConsole": () => output.show(true),
  };
  for (const [name, handler] of Object.entries(commands)) context.subscriptions.push(vscode.commands.registerCommand(name, () => guarded(handler)));
  context.subscriptions.push(output);
  void autoStartMobileEngine();
}

async function deactivate() {
  extensionDeactivating = true;
  if (activeProcess) activeProcess.kill();
  if (remoteLogTimer) clearInterval(remoteLogTimer);
  if (remoteStatusTimer) clearTimeout(remoteStatusTimer);
  await cleanupAdbForwards();
  for (const proxy of dapProxies) proxy.close();
  dapProxies.clear();
  return gluaExtension.deactivate();
}

module.exports = { activate, deactivate, _test: { generateProjectHost, normalizedModules }, ...require("./autogo-core") };
