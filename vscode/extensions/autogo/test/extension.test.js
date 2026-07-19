const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const {
  buildAgArgs,
  analyzeLuaDependencies,
  buildProxyUrl,
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
  parseLoopbackListeningPorts,
  normalizeRemoteLogEntry,
  versionMatches,
  validateEndpoint,
} = require("../autogo-core");

assert.deepStrictEqual(buildAgArgs("version"), ["version"]);
assert.deepStrictEqual(buildAgArgs("stop", { device: "device-1" }), ["stop", "-s", "device-1"]);
assert.deepStrictEqual(buildAgArgs("run", { device: "device-1", debug: true }), ["run", "-s", "device-1", "-d"]);
assert.deepStrictEqual(buildAgArgs("build", { target: "arm64-v8a", embed: true }), ["build", "-t", "arm64-v8a", "-e"]);
assert.deepStrictEqual(buildAgArgs("connect", { address: "127.0.0.1:5555" }), ["connect", "-s", "127.0.0.1:5555"]);
assert.throws(() => buildAgArgs("init"), /target/);

const fakeExists = (candidate) => ["/configured/adb", "/env/ag", "/bin/go"].includes(candidate);
assert.strictEqual(discoverExecutable("adb", "/configured/adb", { platform: "linux", env: {}, exists: fakeExists }), "/configured/adb");
assert.strictEqual(discoverExecutable("ag", "", { platform: "linux", env: { AUTOGO_AG_PATH: "/env/ag" }, exists: fakeExists }), "/env/ag");
assert.strictEqual(discoverExecutable("go", "", { platform: "linux", env: { PATH: "/bin" }, exists: fakeExists }), "/bin/go");

assert.deepStrictEqual(parseAdbDevices("List of devices attached\nserial-1 device product:test\nserial-2 offline\nserial-3 unauthorized\n"), ["serial-1"]);
assert.strictEqual(classifyDeviceAvailability("", []), "none");
assert.strictEqual(classifyDeviceAvailability("saved-device", ["online-device"]), "offline");
assert.strictEqual(classifyDeviceAvailability("online-device", ["online-device"]), "online");
assert.strictEqual(isChildProcessRunning(undefined), false);
assert.strictEqual(isChildProcessRunning({ exitCode: null, signalCode: null, killed: false }), true);
assert.strictEqual(isChildProcessRunning({ exitCode: 0, signalCode: null, killed: false }), false);
assert.strictEqual(isChildProcessRunning({ exitCode: null, signalCode: "SIGKILL", killed: true }), false);
assert.strictEqual(remoteProbeFailureDelay(1), 30_000);
assert.strictEqual(remoteProbeFailureDelay(2), 60_000);
assert.strictEqual(remoteProbeFailureDelay(20), 60_000);
assert.strictEqual(parseRemoteControlPort("2026/07/17 23:00:00 remote control listening on 127.0.0.1:49198"), 49198);
assert.strictEqual(parseRemoteControlPort("remote control listening on [::1]:38696"), 38696);
assert.strictEqual(parseRemoteControlPort("unrelated output"), 0);
assert.deepStrictEqual(deduplicateAdbDevices([
  { serial: "usb-1", physical: "device-a" },
  { serial: "adb-device-a._adb-tls-connect._tcp", physical: "device-a" },
  { serial: "192.168.1.8:5555", physical: "device-a" },
  { serial: "usb-2", physical: "" },
]), ["192.168.1.8:5555", "usb-1", "usb-2"]);
assert.strictEqual(buildProxyUrl({ enabled: true, type: "socks5", host: "127.0.0.1", port: 7890 }), "socks5://127.0.0.1:7890");
assert.strictEqual(buildProxyUrl({ enabled: true, type: "http", host: "proxy", port: 8080, auth: true, username: "u@x", password: "p:x" }), "http://u%40x:p%3Ax@proxy:8080");
assert.throws(() => buildProxyUrl({ enabled: true, type: "http", host: "", port: 0 }), /无效/);
assert.strictEqual(environmentWithProxy({}, { enabled: true, type: "http", host: "proxy", port: 8080 }).HTTPS_PROXY, "http://proxy:8080");

assert.strictEqual(validateEndpoint("http://127.0.0.1:38696").hostname, "127.0.0.1");
assert.strictEqual(validateEndpoint("https://device.example.test/api/").pathname, "/api");
assert.throws(() => validateEndpoint("http://device.example.test"), /HTTPS/);

const root = fs.mkdtempSync(path.join(os.tmpdir(), "autogo-vscode-"));
const defaultScript = ensureDefaultScript(root);
assert.strictEqual(path.relative(root, defaultScript), path.join("scripts", "main.glua"));
assert.strictEqual(fs.readFileSync(defaultScript, "utf8"), 'console.info("AutoGo Script Engine started")\n');
fs.writeFileSync(defaultScript, "print('keep')\n");
ensureDefaultScript(root);
assert.strictEqual(fs.readFileSync(defaultScript, "utf8"), "print('keep')\n");
assert.strictEqual(inspectInitializedProject(root).initialized, false);
fs.mkdirSync(path.join(root, ".autogo", "deps", "autogo_scriptengine"), { recursive: true });
fs.writeFileSync(path.join(root, ".autogo", "engine.json"), JSON.stringify({ entry: "scripts/main.glua" }));
fs.writeFileSync(path.join(root, ".autogo", "deps", "autogo_scriptengine", "go.mod"), "module example/engine\n");
fs.writeFileSync(path.join(root, "go.mod"), "module example/app\n");
fs.writeFileSync(path.join(root, "main.go"), "// Code generated by AutoGo Script Engine Console. DO NOT EDIT.\npackage main\n");
assert.deepStrictEqual(inspectInitializedProject(root).missing, []);
assert.strictEqual(inspectInitializedProject(root).initialized, true);
fs.mkdirSync(path.join(root, "lib"));
fs.writeFileSync(path.join(root, "main.lua"), "local helper = require('lib.helper')\nreturn helper\n");
fs.writeFileSync(path.join(root, "lib", "helper.lua"), "return { value = 42 }\n");
const closure = collectLuaDependencyClosure(path.join(root, "main.lua"), root);
assert.deepStrictEqual(closure.map((file) => path.relative(root, file).split(path.sep).join("/")), ["main.lua", "lib/helper.lua"]);
fs.writeFileSync(path.join(root, "main.glua"), "local helper = require('lib.glua_helper')\nreturn helper\n");
fs.writeFileSync(path.join(root, "lib", "glua_helper.glua"), "return { value = 84 }\n");
const gluaClosure = collectLuaDependencyClosure(path.join(root, "main.glua"), root);
assert.deepStrictEqual(gluaClosure.map((file) => path.relative(root, file).split(path.sep).join("/")), ["main.glua", "lib/glua_helper.glua"]);
assert.deepStrictEqual(analyzeLuaDependencies(path.join(root, "main.glua"), root).dynamicRequires, []);
const manifest = createManifest(closure, root);
assert.strictEqual(manifest.files.length, 2);
assert.match(manifest.id, /^[a-f0-9]{24}$/);
const outside = fs.mkdtempSync(path.join(os.tmpdir(), "autogo-vscode-outside-"));
fs.writeFileSync(path.join(outside, "secret.lua"), "return true\n");
const escapedLink = path.join(root, "escaped.lua");
fs.symlinkSync(path.join(outside, "secret.lua"), escapedLink);
assert.throws(() => createManifest([escapedLink], root), /符号链接逃逸/);
fs.writeFileSync(path.join(root, "dynamic.lua"), "local name = 'lib.helper'\nreturn require(name)\n");
const dependencyAnalysis = analyzeLuaDependencies(path.join(root, "dynamic.lua"), root);
assert.deepStrictEqual(dependencyAnalysis.files.map((file) => path.basename(file)), ["dynamic.lua"]);
assert.deepStrictEqual(dependencyAnalysis.dynamicRequires, ["dynamic.lua:2"]);

const modulePath = "github.com/ZingYao/autogo_scriptengine";
assert.strictEqual(parseModulePath(`module ${modulePath}\n\ngo 1.25\n`), modulePath);
assert.strictEqual(hasRequireAndReplace(`module app\nrequire ${modulePath} v0.0.0\nreplace ${modulePath} => ./.autogo/deps/autogo_scriptengine\n`, modulePath), true);
assert.strictEqual(hasRequireAndReplace(`module app\nreplace ${modulePath} => ./.autogo/deps/autogo_scriptengine\n`, modulePath), false);
assert.strictEqual(latestVersionFromChangelog("# changelog\n\n## [1.16.2]\n"), "1.16.2");
assert.strictEqual(latestSemanticTag(["v1.9.0", "v1.10.0", "v2.0.0-beta.1", "not-a-version"]), "v2.0.0-beta.1");
assert.strictEqual(latestSemanticTag(["v2.0.0-beta.2", "v2.0.0", "v1.99.0"]), "v2.0.0");
assert.throws(() => latestSemanticTag(["master", "release"]), /没有可用/);
assert.throws(() => latestVersionFromChangelog("# no version"), /未找到/);
assert.strictEqual(versionMatches("ag version 1.16.2", "1.16.2"), true);
assert.strictEqual(versionMatches("ag version 1.16.20", "1.16.2"), false);
assert.strictEqual(agPlatformFile("1.16.2", "darwin", "arm64"), "mac_arm_1.16.2");
assert.strictEqual(agPlatformFile("1.16.2", "darwin", "x64"), "mac_amd_1.16.2");
assert.strictEqual(agPlatformFile("1.16.2", "win32", "x64"), "win_x64_1.16.2");
assert.deepStrictEqual(parseLoopbackListeningPorts("127.0.0.1:41002\n[::1]:41001\n0.0.0.0:8080\n127.0.0.1:41002"), [41001, 41002]);
assert.strictEqual(normalizeRemoteLogEntry("2026/07/17 17:39:13 lua output: hello"), "lua output: hello");
assert.strictEqual(normalizeRemoteLogEntry("2026-07-17T17:39:13+08:00 lua lifecycle: [Info] 开始执行：main.lua"), "lua lifecycle: [Info] 开始执行：main.lua");
const hostTemplate = fs.readFileSync(path.join(__dirname, "..", "resources", "templates", "autogo-main.go.tmpl"), "utf8");
assert.match(hostTemplate, /os\.Getenv\("AUTOGO_AUTOSTART"\) == "1"/);
assert.doesNotMatch(hostTemplate, /os\.Getenv\("AUTOGO_AUTOSTART"\) != "0"/);
assert.match(hostTemplate, /engine\.pid\.json/);
assert.match(hostTemplate, /engine\.start\.lock/);
assert.match(hostTemplate, /syscall\.Kill\(pid, 0\)/);
assert.match(hostTemplate, /writeRuntimePID/);
assert.match(hostTemplate, /case "stop-debug":[\s\S]*c\.debugPending = false/);
assert.doesNotMatch(hostTemplate, /if matches \{ c\.closeEngine\(\) \}/);
assert.match(hostTemplate, /gruntime\.ErrorObject\(err\)/);
assert.match(hostTemplate, /gruntime\.Traceback\(message, runtimeErr\.TracebackFrames\)/);
assert.match(hostTemplate, /lua lifecycle: \[Error\][\s\S]*detail/);
assert.ok(hostTemplate.includes('directory + "/?.glua;" + directory + "/?/init.glua;"'));
assert.ok(hostTemplate.includes('directory + "/?.lua;" + directory + "/?/init.lua"'));
assert.match(hostTemplate, /package\.path = %s \.\. ';' \.\. package\.path/);
assert.doesNotMatch(hostTemplate, /package\.path = package\.path \.\./);
assert.match(hostTemplate, /return dofile\(%s\)/);
assert.match(hostTemplate, /config\.AllowProcess = true/);
assert.match(hostTemplate, /root, err := filepath\.Abs\(root\)/);
assert.match(hostTemplate, /os\.Chdir\(directoryPath\)/);
assert.match(hostTemplate, /os\.Chdir\(previousDirectory\)/);
assert.match(hostTemplate, /if runningService\(root, pid\)/);
assert.match(hostTemplate, /health\.Service == "autogo-script-engine" && health\.InstanceID == metadata\.InstanceID/);
const extensionSource = fs.readFileSync(path.join(__dirname, "..", "extension.js"), "utf8");
assert.match(
  extensionSource,
  /\.prefix\{[^}]*width:12ch;[^}]*min-width:12ch;[^}]*white-space:nowrap;[^}]*\}/,
  "console log prefixes should remain aligned and must not wrap"
);
assert.match(extensionSource, /gluacPath: settingsGluacPath\(\)/);
assert.match(extensionSource, /gluacPath: persistedGluacPath\(settings\.gluacPath\)/);
assert.match(extensionSource, /run \(\?:completed\|failed\)/);
assert.match(extensionSource, /onRunTerminated\?\.\(\{ failed:/);
assert.match(extensionSource, /ag run 已复用移动端控制服务：pid=\$\{metadata\.pid\}，port=\$\{metadata\.controlPort\}/);
assert.match(extensionSource, /已自动切换到唯一在线设备：\$\{devices\[0\]\}/);
assert.match(extensionSource, /const metadata = await readRemotePidMetadata\(device\)/);
assert.doesNotMatch(extensionSource, /output\.appendLine\(`\\n\[错误\]/);
assert.doesNotMatch(extensionSource, /"shell", "sh", "-c", `kill -0 \$\{pid\}`/);

fs.rmSync(root, { recursive: true, force: true });
fs.rmSync(outside, { recursive: true, force: true });
console.log("VSCode AutoGo core tests passed");
