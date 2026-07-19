const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const https = require("https");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");

const MAX_REMOTE_RESPONSE_BYTES = 16 * 1024 * 1024;
const TOOL_CANDIDATES = {
  darwin: {
    ag: ["/Users/Shared/ag", "/opt/homebrew/bin/ag", "/usr/local/bin/ag", "/usr/bin/ag"],
    adb: ["/opt/homebrew/bin/adb", "/usr/local/bin/adb", "/usr/bin/adb"],
    go: ["/opt/homebrew/bin/go", "/usr/local/bin/go", "/usr/bin/go"],
    gluac: ["/opt/homebrew/bin/gluac", "/usr/local/bin/gluac", "/usr/bin/gluac"],
  },
  linux: {
    ag: [path.join(os.homedir(), ".autogo", "ag"), "/usr/local/bin/ag", "/usr/bin/ag"],
    adb: [path.join(os.homedir(), "Android", "Sdk", "platform-tools", "adb"), "/usr/local/bin/adb", "/usr/bin/adb"],
    go: ["/usr/local/go/bin/go", "/usr/local/bin/go", "/usr/bin/go"],
    gluac: ["/usr/local/bin/gluac", "/usr/bin/gluac"],
  },
  win32: {
    ag: ["C:\\Users\\Public\\ag.exe"],
    adb: [path.join(process.env.LOCALAPPDATA || "", "Android", "Sdk", "platform-tools", "adb.exe")],
    go: ["C:\\Program Files\\Go\\bin\\go.exe"],
    gluac: [],
  },
};

function executableName(tool, platform = process.platform) {
  return platform === "win32" ? `${tool}.exe` : tool;
}

function pathEntries(env = process.env, platform = process.platform) {
  return String(env.PATH || "").split(path.delimiter).filter(Boolean).map((directory) => directory.trim())
    .map((directory) => path.join(directory, executableName("", platform)).slice(0, -executableName("", platform).length));
}

function discoverExecutable(tool, configured = "", options = {}) {
  const platform = options.platform || process.platform;
  const env = options.env || process.env;
  const exists = options.exists || fs.existsSync;
  const explicit = String(configured || "").trim();
  if (explicit && exists(explicit)) return explicit;
  const envName = `AUTOGO_${tool.toUpperCase()}_PATH`;
  const environmentValue = String(env[envName] || "").trim();
  if (environmentValue && exists(environmentValue)) return environmentValue;
  const fileName = executableName(tool, platform);
  const candidates = [...(TOOL_CANDIDATES[platform]?.[tool] || [])];
  for (const directory of String(env.PATH || "").split(path.delimiter).filter(Boolean)) {
    candidates.push(path.join(directory, fileName));
  }
  return candidates.find((candidate) => candidate && exists(candidate)) || "";
}

function buildProxyUrl(proxy = {}) {
  if (!proxy.enabled) return "";
  const type = String(proxy.type || "http").toLowerCase();
  if (!["http", "https", "socks5"].includes(type)) throw new Error(`不支持的代理类型：${type}`);
  const host = String(proxy.host || "").trim();
  const port = Number(proxy.port);
  if (!host || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error("代理 IP/主机和端口无效");
  const auth = proxy.auth ? `${encodeURIComponent(proxy.username || "")}:${encodeURIComponent(proxy.password || "")}@` : "";
  return `${type}://${auth}${host}:${port}`;
}

function environmentWithProxy(base = process.env, proxy = {}) {
  const env = { ...base };
  const value = buildProxyUrl(proxy);
  if (!value) return env;
  env.HTTP_PROXY = value;
  env.HTTPS_PROXY = value;
  env.ALL_PROXY = value;
  env.http_proxy = value;
  env.https_proxy = value;
  env.all_proxy = value;
  return env;
}

function parseAdbDevices(output) {
  return String(output || "").split(/\r?\n/).slice(1).map((line) => line.trim()).filter(Boolean)
    .map((line) => line.split(/\s+/)).filter((parts) => parts[1] === "device").map((parts) => parts[0]);
}

function deduplicateAdbDevices(identified) {
  const priority = (serial) => {
    if (/^\d{1,3}(?:\.\d{1,3}){3}:\d+$/.test(serial)) return 0;
    if (/_adb-tls-connect\._tcp$/i.test(serial)) return 1;
    if (serial.includes(":")) return 2;
    return 3;
  };
  const selected = new Map();
  for (const item of identified || []) {
    if (!item || typeof item.serial !== "string" || !item.serial) continue;
    // 同一物理设备的 USB 与无线连接是两个有意义的目标，需要同时展示；仅合并 IP/mDNS 等重复网络端点。
    const networkEndpoint = /^\d{1,3}(?:\.\d{1,3}){3}:\d+$/.test(item.serial)
      || /_adb-tls-connect\._tcp$/i.test(item.serial) || item.serial.includes(":");
    const key = item.physical
      ? `physical:${item.physical}:${networkEndpoint ? "network" : "usb"}`
      : `endpoint:${item.serial}`;
    const current = selected.get(key);
    if (!current || priority(item.serial) < priority(current.serial)) selected.set(key, item);
  }
  return [...selected.values()].map((item) => item.serial)
    .sort((left, right) => priority(left) - priority(right) || left.localeCompare(right));
}

function inspectInitializedProject(root) {
  const missing = [];
  const configFile = path.join(root, ".autogo", "engine.json");
  const mainFile = path.join(root, "main.go");
  const goMod = path.join(root, "go.mod");
  const dependencyGoMod = path.join(root, ".autogo", "deps", "autogo_scriptengine", "go.mod");
  let config;
  if (!fs.existsSync(configFile)) missing.push(".autogo/engine.json");
  else {
    try { config = JSON.parse(fs.readFileSync(configFile, "utf8")); }
    catch (_) { missing.push("可解析的 .autogo/engine.json"); }
  }
  if (!fs.existsSync(mainFile)) missing.push("main.go");
  else if (!fs.readFileSync(mainFile, "utf8").startsWith("// Code generated by AutoGo Script Engine Console.")) {
    missing.push("由扩展生成的 main.go");
  }
  if (!fs.existsSync(goMod)) missing.push("go.mod");
  if (!fs.existsSync(dependencyGoMod)) missing.push(".autogo/deps/autogo_scriptengine/go.mod");
  if (config) {
    const entry = String(config.entry || "scripts/main.glua").trim();
    const resolvedEntry = entry && !path.isAbsolute(entry) ? path.resolve(root, entry) : "";
    if (!resolvedEntry || !resolvedEntry.startsWith(path.resolve(root) + path.sep)
      || !/\.(?:lua|glua|js)$/i.test(resolvedEntry) || !fs.existsSync(resolvedEntry) || !fs.statSync(resolvedEntry).isFile()) {
      missing.push(entry || "scripts/main.glua");
    }
  }
  return { initialized: missing.length === 0, missing, config };
}

function buildAgArgs(action, options = {}) {
  switch (action) {
    case "version": return [action];
    case "stop": return ["stop", ...(options.device ? ["-s", options.device] : [])];
    case "init": case "build": {
      if (!options.target) throw new Error(`缺少 AG 参数: target`);
      return [action, "-t", options.target, ...(action === "build" && options.embed ? ["-e"] : [])];
    }
    case "run": return ["run", ...(options.device ? ["-s", options.device] : []), ...(options.debug ? ["-d"] : [])];
    case "deploy": return ["deploy", ...(options.device ? ["-s", options.device] : [])];
    case "connect": {
      if (!options.address) throw new Error("缺少 AG 参数: address");
      return ["connect", "-s", options.address];
    }
    default: throw new Error(`不支持的 AG 操作: ${action}`);
  }
}

function runProcess(executable, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      cwd: options.cwd,
      env: options.env || process.env,
      windowsHide: true,
      stdio: ["ignore", "pipe", "pipe"],
    });
    let stdout = "";
    let stderr = "";
    let outputBytes = 0;
    let settled = false;
    const maximumOutputBytes = options.maxOutputBytes || 16 * 1024 * 1024;
    const timeout = options.timeoutMs > 0 ? setTimeout(() => {
      if (settled) return;
      child.kill("SIGKILL");
      settled = true;
      reject(new Error(`${path.basename(executable)} 执行超时`));
    }, options.timeoutMs) : undefined;
    const append = (kind, chunk) => {
      outputBytes += chunk.length;
      if (outputBytes > maximumOutputBytes) {
        child.kill("SIGKILL");
        if (!settled) {
          settled = true;
          if (timeout) clearTimeout(timeout);
          reject(new Error(`${path.basename(executable)} 输出超过 ${maximumOutputBytes} bytes 上限`));
        }
        return;
      }
      const text = chunk.toString();
      if (kind === "stdout") stdout += text; else stderr += text;
      options.onOutput?.(text, kind);
    };
    child.stdout.on("data", (chunk) => append("stdout", chunk));
    child.stderr.on("data", (chunk) => append("stderr", chunk));
    child.on("error", (error) => {
      if (settled) return;
      settled = true;
      if (timeout) clearTimeout(timeout);
      reject(error);
    });
    child.on("close", (code, signal) => {
      if (settled) return;
      settled = true;
      if (timeout) clearTimeout(timeout);
      resolve({ code: code ?? -1, signal, stdout, stderr, child });
    });
    if (options.onSpawn) options.onSpawn(child);
  });
}

function sha256(content) {
  return crypto.createHash("sha256").update(content).digest("hex");
}

function normalizeRelative(root, file) {
  const relative = path.relative(path.resolve(root), path.resolve(file)).split(path.sep).join("/");
  if (!relative || relative === ".." || relative.startsWith("../")) throw new Error(`文件不在工作区内：${file}`);
  return relative;
}

function analyzeLuaDependencies(entry, root, read = fs.readFileSync, exists = fs.existsSync) {
  const result = [];
  const dynamicRequires = [];
  const visited = new Set();
  function visit(file) {
    const absolute = path.resolve(file);
    if (visited.has(absolute)) return;
    if (!absolute.startsWith(path.resolve(root) + path.sep) && absolute !== path.resolve(root)) throw new Error(`require 路径逃逸工作区：${absolute}`);
    if (!exists(absolute)) throw new Error(`Lua 依赖不存在：${absolute}`);
    visited.add(absolute);
    result.push(absolute);
    const source = read(absolute, "utf8");
    for (const match of source.matchAll(/\brequire\b([^\r\n]*)/g)) {
      const tail = match[1].trim();
      const argument = (tail.startsWith("(") ? tail.slice(1) : tail).trim();
      if (argument && !/^["']/.test(argument)) {
        dynamicRequires.push(`${normalizeRelative(root, absolute)}:${source.slice(0, match.index).split(/\r?\n/).length}`);
      }
    }
    const regex = /\brequire\s*(?:\(\s*)?["']([A-Za-z0-9_./-]+)["']\s*\)?/g;
    for (const match of source.matchAll(regex)) {
      const modulePath = match[1].replace(/\./g, "/");
      const candidates = [
        path.join(path.dirname(absolute), `${modulePath}.glua`),
        path.join(path.dirname(absolute), `${modulePath}.lua`),
        path.join(root, `${modulePath}.glua`),
        path.join(root, `${modulePath}.lua`),
        path.join(root, modulePath, "init.glua"),
        path.join(root, modulePath, "init.lua"),
      ];
      const resolved = candidates.find((candidate) => exists(candidate));
      if (resolved) visit(resolved);
    }
  }
  visit(entry);
  return { files: result, dynamicRequires: [...new Set(dynamicRequires)] };
}

function collectLuaDependencyClosure(entry, root, read = fs.readFileSync, exists = fs.existsSync) {
  return analyzeLuaDependencies(entry, root, read, exists).files;
}

function resolveJavaScriptDependency(fromFile, root, specifier, exists = fs.existsSync) {
  const value = String(specifier || "").trim();
  if (!value) return undefined;
  const base = value.startsWith(".") || value.startsWith("/")
    ? path.resolve(path.dirname(fromFile), value)
    : path.resolve(root, value);
  const candidates = [
    base,
    `${base}.js`,
    `${base}.json`,
    path.join(base, "index.js"),
    path.join(base, "index.json"),
  ];
  return candidates.find((candidate) => exists(candidate));
}

function analyzeJavaScriptDependencies(entry, root, read = fs.readFileSync, exists = fs.existsSync) {
  const result = [];
  const dynamicRequires = [];
  const visited = new Set();
  function lineFor(source, index) {
    return source.slice(0, index).split(/\r?\n/).length;
  }
  function visit(file) {
    const absolute = path.resolve(file);
    if (visited.has(absolute)) return;
    if (!absolute.startsWith(path.resolve(root) + path.sep) && absolute !== path.resolve(root)) throw new Error(`JavaScript 依赖路径逃逸工作区：${absolute}`);
    if (!exists(absolute)) throw new Error(`JavaScript 依赖不存在：${absolute}`);
    visited.add(absolute);
    result.push(absolute);
    if (!/\.(?:js|json)$/i.test(absolute)) return;
    const source = read(absolute, "utf8");
    const callRegex = /\b(?:require|importModule|import)\s*\(\s*([^)]*)\)/g;
    for (const match of source.matchAll(callRegex)) {
      const argument = String(match[1] || "").trim();
      const literal = argument.match(/^["']([^"']+)["']\s*$/);
      if (!literal) {
        dynamicRequires.push(`${normalizeRelative(root, absolute)}:${lineFor(source, match.index)}`);
        continue;
      }
      const resolved = resolveJavaScriptDependency(absolute, root, literal[1], exists);
      if (resolved) visit(resolved);
    }
    const importRegex = /\bimport\s+(?:[^"'()]+?\s+from\s*)?["']([^"']+)["']/g;
    for (const match of source.matchAll(importRegex)) {
      const resolved = resolveJavaScriptDependency(absolute, root, match[1], exists);
      if (resolved) visit(resolved);
    }
  }
  visit(entry);
  return { files: result, dynamicRequires: [...new Set(dynamicRequires)] };
}

function collectJavaScriptDependencyClosure(entry, root, read = fs.readFileSync, exists = fs.existsSync) {
  return analyzeJavaScriptDependencies(entry, root, read, exists).files;
}

function createManifest(files, root) {
  const realRoot = fs.realpathSync(root);
  const caseInsensitivePaths = new Map();
  const items = [...new Set(files.map((file) => path.resolve(file)))].map((file) => {
    const realFile = fs.realpathSync(file);
    if (!realFile.startsWith(realRoot + path.sep)) throw new Error(`同步文件通过符号链接逃逸工作区：${file}`);
    const relative = normalizeRelative(root, file);
    const folded = relative.toLocaleLowerCase("en-US");
    if (caseInsensitivePaths.has(folded) && caseInsensitivePaths.get(folded) !== relative) {
      throw new Error(`同步路径存在大小写冲突：${caseInsensitivePaths.get(folded)} / ${relative}`);
    }
    caseInsensitivePaths.set(folded, relative);
    const content = fs.readFileSync(realFile);
    return { absolute: file, path: relative, sha256: sha256(content), size: content.length, content };
  }).sort((left, right) => left.path.localeCompare(right.path));
  const id = sha256(Buffer.from(JSON.stringify(items.map(({ path: itemPath, sha256: hash, size }) => ({ path: itemPath, sha256: hash, size }))))).slice(0, 24);
  return { id, files: items };
}

function validateEndpoint(value, allowInsecureLoopback = true) {
  const endpoint = new URL(String(value || "").trim());
  const loopback = ["127.0.0.1", "localhost", "::1"].includes(endpoint.hostname);
  if (endpoint.protocol !== "https:" && !(allowInsecureLoopback && loopback && endpoint.protocol === "http:")) {
    throw new Error("非回环远程引擎必须使用 HTTPS");
  }
  endpoint.pathname = endpoint.pathname.replace(/\/$/, "");
  return endpoint;
}

function requestJson(endpoint, method, route, body, options = {}) {
  const base = validateEndpoint(endpoint);
  const target = new URL(base.toString());
  const queryIndex = String(route).indexOf("?");
  const routePath = queryIndex < 0 ? String(route) : String(route).slice(0, queryIndex);
  const routeQuery = queryIndex < 0 ? "" : String(route).slice(queryIndex + 1);
  target.pathname = `${base.pathname}${routePath}`.replace(/\/+/g, "/");
  target.search = routeQuery ? `?${routeQuery}` : "";
  const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
  return new Promise((resolve, reject) => {
    const transport = target.protocol === "https:" ? https : http;
    const request = transport.request(target, {
      method,
      timeout: options.timeoutMs || 10000,
      headers: {
        Accept: "application/json",
        ...(payload ? { "Content-Type": "application/json", "Content-Length": payload.length } : {}),
        ...(options.token ? { Authorization: `Bearer ${options.token}` } : {}),
      },
    }, (response) => {
      const chunks = [];
      let size = 0;
      response.on("data", (chunk) => {
        size += chunk.length;
        if (size > MAX_REMOTE_RESPONSE_BYTES) {
          request.destroy(new Error("远程响应超过大小限制"));
          return;
        }
        chunks.push(chunk);
      });
      response.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        let parsed = {};
        try { parsed = text ? JSON.parse(text) : {}; } catch (error) { reject(new Error(`远程引擎返回无效 JSON：${error.message}`)); return; }
        if ((response.statusCode || 500) >= 300) {
          const remoteError = parsed?.error;
          const message = remoteError?.message || `远程引擎 HTTP ${response.statusCode}`;
          const diagnostics = [remoteError?.code, remoteError?.detail].filter(Boolean).join("：");
          reject(new Error(diagnostics ? `${message}（${diagnostics}）` : message));
          return;
        }
        resolve(parsed);
      });
    });
    request.on("timeout", () => request.destroy(new Error("远程引擎请求超时")));
    request.on("error", reject);
    if (payload) request.write(payload);
    request.end();
  });
}

function parseModulePath(goMod) {
  const match = String(goMod || "").match(/^\s*module\s+(\S+)\s*$/m);
  if (!match) throw new Error("依赖 go.mod 缺少 module 指令");
  return match[1];
}

function hasRequireAndReplace(goMod, modulePath, relative = ".autogo/deps/autogo_scriptengine") {
  const escaped = modulePath.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const requirePattern = new RegExp(`^[ \\t]*(?:require[ \\t]+)?${escaped}[ \\t]+v\\S+(?:[ \\t]+//[ \\t]*indirect)?[ \\t]*$`, "m");
  const replacePattern = new RegExp(`^[ \\t]*(?:replace[ \\t]+)?${escaped}(?:[ \\t]+v\\S+)?[ \\t]*=>[ \\t]*\\./${relative.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}[ \\t]*$`, "m");
  return requirePattern.test(goMod) && replacePattern.test(goMod);
}

function latestVersionFromChangelog(content) {
  const match = String(content || "").match(/^## \[([^\]]+)]/m);
  if (!match || !match[1].trim()) throw new Error("AG 更新日志中未找到可用版本");
  return match[1].trim();
}

function latestSemanticTag(tags) {
  const parsed = (Array.isArray(tags) ? tags : String(tags || "").split(/\r?\n/))
    .map((tag) => String(tag).trim())
    .filter(Boolean)
    .map((tag) => {
      const match = tag.match(/^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$/);
      return match ? { tag, version: match.slice(1, 4).map(Number), prerelease: match[4] || "" } : undefined;
    })
    .filter(Boolean);
  parsed.sort((left, right) => {
    for (let index = 0; index < 3; index++) {
      if (left.version[index] !== right.version[index]) return right.version[index] - left.version[index];
    }
    if (!left.prerelease && right.prerelease) return -1;
    if (left.prerelease && !right.prerelease) return 1;
    return right.prerelease.localeCompare(left.prerelease, undefined, { numeric: true });
  });
  if (!parsed.length) throw new Error("autogo_scriptengine 仓库没有可用的语义版本 Tag");
  return parsed[0].tag;
}

function versionMatches(output, expectedVersion) {
  if (!expectedVersion) return false;
  const escaped = String(expectedVersion).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`(^|[^0-9A-Za-z])v?${escaped}($|[^0-9A-Za-z])`, "i").test(String(output || ""));
}

function agPlatformFile(version, platform = process.platform, arch = process.arch) {
  if (platform === "darwin") return `${arch === "arm64" ? "mac_arm_" : "mac_amd_"}${version}`;
  if (platform === "win32") return `win_x64_${version}`;
  return `linux_x64_${version}`;
}

function parseLoopbackListeningPorts(text) {
  const ports = new Set();
  for (const line of String(text || "").split(/\r?\n/)) {
    for (const match of line.matchAll(/(?:127\.0\.0\.1|\[?::1\]?):(\d{1,5})\b/g)) {
      const port = Number(match[1]);
      if (port >= 1 && port <= 65535) ports.add(port);
    }
  }
  return [...ports].sort((left, right) => left - right);
}

function normalizeRemoteLogEntry(entry) {
  return String(entry || "")
    .replace(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})\s+/, "")
    .replace(/^\d{4}\/\d{2}\/\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?\s+/, "");
}

function ensureDefaultScript(root) {
  const entry = path.join(root, "scripts", "main.glua");
  fs.mkdirSync(path.dirname(entry), { recursive: true });
  if (!fs.existsSync(entry)) fs.writeFileSync(entry, 'console.info("AutoGo Script Engine started")\n');
  return entry;
}

function classifyDeviceAvailability(configured, devices) {
  const online = Array.isArray(devices) ? devices : [];
  if (!online.length) return "none";
  if (configured && !online.includes(configured)) return "offline";
  return "online";
}

function isChildProcessRunning(child) {
  return Boolean(child)
    && child.exitCode === null
    && child.signalCode === null
    && !child.killed;
}

function remoteProbeFailureDelay(failureCount) {
  return Number(failureCount) <= 1 ? 30_000 : 60_000;
}

function parseRemoteControlPort(output) {
  const match = String(output || "").match(/remote control listening on\s+(?:\[[^\]]+]|[^\s:]+):(\d{1,5})/i);
  const port = Number(match?.[1] || 0);
  return port >= 1 && port <= 65535 ? port : 0;
}

module.exports = {
  MAX_REMOTE_RESPONSE_BYTES,
  TOOL_CANDIDATES,
  buildAgArgs,
  buildProxyUrl,
  analyzeJavaScriptDependencies,
  analyzeLuaDependencies,
  collectJavaScriptDependencyClosure,
  collectLuaDependencyClosure,
  classifyDeviceAvailability,
  isChildProcessRunning,
  remoteProbeFailureDelay,
  parseRemoteControlPort,
  createManifest,
  deduplicateAdbDevices,
  inspectInitializedProject,
  discoverExecutable,
  environmentWithProxy,
  ensureDefaultScript,
  hasRequireAndReplace,
  latestVersionFromChangelog,
  latestSemanticTag,
  versionMatches,
  agPlatformFile,
  normalizeRelative,
  parseAdbDevices,
  parseModulePath,
  parseLoopbackListeningPorts,
  normalizeRemoteLogEntry,
  requestJson,
  runProcess,
  sha256,
  validateEndpoint,
};
