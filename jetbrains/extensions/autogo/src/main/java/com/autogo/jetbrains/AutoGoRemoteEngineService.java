package com.autogo.jetbrains;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.glua.jetbrains.GluaDependencyGraph;
import com.glua.jetbrains.GluaDebugProcess;
import com.glua.jetbrains.GluaRemoteDebugLauncher;
import com.glua.jetbrains.GluaRemotePathMapper;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 管理 IDEA 到 Android AutoGo 控制面和 DAP 端口的 ADB 转发与状态缓存。 */
@Service(Service.Level.PROJECT)
public final class AutoGoRemoteEngineService implements Disposable {
    private static final int REMOTE_DAP_PORT = 38697;
    private static final Pattern STATE = Pattern.compile("\\\"state\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ERROR_MESSAGE = Pattern.compile("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final int SUPPORTED_PROTOCOL_MAJOR = 1;
    private final Project project;
    private final AutoGoConsoleService console;
    private final AutoGoPortAllocator.PortPair localPorts;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private volatile String cachedState = "unreachable";
    private volatile long lastRefreshNanos;
    private volatile String forwardedSerial = "";
    private volatile URI activeControlBase;
    private volatile String activeDapHost = "127.0.0.1";
    private volatile int activeDapPort;
    private volatile boolean directConnection;
    private final AtomicBoolean refreshInProgress = new AtomicBoolean();
    private final AtomicLong remoteLogCursor = new AtomicLong();
    private final AtomicBoolean remoteDebugActive = new AtomicBoolean();
    private volatile ScheduledFuture<?> logPollingTask;
    private final ScheduledFuture<?> statusPollingTask;
    private volatile WebSocket eventSocket;

    /** 创建项目级远程引擎服务。 */
    public AutoGoRemoteEngineService(Project project) {
        // 每个 IDEA 项目维护独立状态，避免多项目设备状态串扰。
        this.project = project;
        this.console = project.getService(AutoGoConsoleService.class);
        this.localPorts = AutoGoPortAllocator.allocate();
        this.activeControlBase = URI.create("http://127.0.0.1:" + localPorts.control());
        this.activeDapPort = localPorts.dap();
        this.statusPollingTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                this::pollRemoteStatus, 5, 3, TimeUnit.SECONDS);
    }

    private void pollRemoteStatus() {
        // 定时任务持续检查状态；同一时刻只允许一个扫描，避免与动作 update 或 F7 并发执行 ADB。
        if (project.getService(AutoGoProcessService.class).isRunning() && forwardedSerial.isBlank()) {
            // ag run 正在启动且尚未报告端口时，不并发执行 adb shell 探测争用设备连接。
            return;
        }
        if (!refreshInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            cachedState = queryState();
            lastRefreshNanos = System.nanoTime();
        } finally {
            refreshInProgress.set(false);
        }
    }

    /** 返回供动作 update 使用的无阻塞状态快照。 */
    public String getCachedState() {
        // 展示层不得在 EDT 内发起 ADB 或 HTTP 请求。
        return cachedState;
    }

    /** 后台刷新状态，并在 EDT 回调最新值。 */
    public void refresh(@NotNull Consumer<String> callback) {
        // ADB 转发和 HTTP 健康检查均放到线程池执行。
        long now = System.nanoTime();
        if (now - lastRefreshNanos < TimeUnit.SECONDS.toNanos(2)
                || !refreshInProgress.compareAndSet(false, true)) {
            // IDEA 会高频调用 action update，两秒内复用缓存并禁止并发 adb 扫描。
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String state = queryState();
                cachedState = state;
                lastRefreshNanos = System.nanoTime();
                ApplicationManager.getApplication().invokeLater(() -> callback.accept(state));
            } finally {
                // 查询失败和异常也必须释放刷新互斥，否则按钮永久停止更新。
                refreshInProgress.set(false);
            }
        });
    }

    /** 启动未运行的引擎；已运行时执行重启。控制服务未启动则先运行根目录 ag run。 */
    public void startOrRestart() {
        // 整个探测、启动和轮询流程不能阻塞 UI。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            // 激活阶段直接使用缓存并启动宿主；禁止先执行一轮可能很慢的设备端口扫描。
            String state = cachedState;
            if ("unreachable".equals(state)) {
                if (!canBootstrapWithAdb()) {
                    // direct 模式只能由远端运维启动控制服务，IDE 不得部署到其他 ADB 设备。
                    cachedState = "unreachable";
                    console.error("直接远程控制服务不可达，请检查 remote.endpoint、TLS 和服务状态。");
                    return;
                }
                // ag run 固定在项目根目录启动生成的 main.go 控制服务。
                console.info("移动端控制服务未连接，正在通过项目根目录 ag run 启动……");
                migrateGeneratedHostLock();
                AutoGoProcessService processService = project.getService(AutoGoProcessService.class);
                boolean started = processService.restartAgHost(
                        AutoGoMenuActions.deviceArgs(project, "stop", false),
                        AutoGoMenuActions.deviceArgs(project, "run", false));
                if (!started) {
                    cachedState = "failed";
                    return;
                }
                state = waitUntilReachable();
                if ("unreachable".equals(state)) {
                    cachedState = "failed";
                    console.error("移动端控制服务启动超时，请查看 ag run 输出和设备端口监听状态。");
                    return;
                }
            }
            String endpoint = "running".equals(state) || "paused".equals(state)
                    ? "/v1/engine/restart" : "/v1/engine/start";
            HttpResult result = request("POST", endpoint);
            cachedState = result.state();
            if (result.success()) {
                // 成功响应中的状态决定按钮下次显示“启动”还是“重启”。
                console.info((endpoint.endsWith("restart") ? "移动端脚本引擎已重启：" : "移动端脚本引擎已启动：")
                        + result.state());
            } else {
                // 服务端结构化错误直接输出到 Console，保留具体启动失败原因。
                console.error("移动端脚本引擎操作失败：" + result.message());
            }
        });
    }

    /** 停止引擎但保留移动端控制服务，以便无需重新部署即可再次启动。 */
    public void stopEngine() {
        // 停止请求与普通状态请求共享 ADB 转发和错误解析。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            HttpResult result = request("POST", "/v1/engine/stop");
            cachedState = result.state();
            if (result.success()) {
                console.info("移动端脚本引擎已停止。");
            } else {
                console.error("停止移动端脚本引擎失败：" + result.message());
            }
        });
    }

    /** 调试会话结束后通知远端停止本次调试任务，但保持移动端引擎常驻。 */
    public void stopDebugSession() {
        // WebSocket 只终止本次调试任务，不关闭常驻脚本引擎。
        remoteDebugActive.set(false);
        WebSocket socket = eventSocket;
        if (socket != null && !socket.isOutputClosed()) {
            socket.sendText("{\"type\":\"stop-debug\"}", true);
            return;
        }
        // 控制通道断开时只清理本地 DAP；禁止降级调用 engine/stop 误杀常驻引擎。
        cachedState = "running";
        console.infoWithoutShowing(AutoGoConsoleService.Channel.EXTENSION,
                "调试控制通道已断开；仅清理本地 DAP，不停止移动端引擎。");
    }

    /** 判断远程 DAP handler 是否属于当前项目的 AutoGo 移动端会话。 */
    public boolean ownsDapEndpoint(String host, int port) {
        // 手工配置的其他 GLua DAP 服务不得被 AutoGo 生命周期逻辑停止。
        return activeDapPort == port && activeDapHost.equalsIgnoreCase(host == null ? "" : host.trim());
    }

    /** 同步入口文件及静态 require 闭包，并在目标 manifest 上运行或准备调试。 */
    public void syncAndRun(Path entryFile, boolean debug) {
        // 运行切换到 Lua 分区；Debug 由原生控制台接管，只在后台清空并保留 AutoGo Lua 副本。
        console.clear(AutoGoConsoleService.Channel.LUA);
        String language = scriptLanguage(entryFile);
        String preparation = (debug ? "准备调试：" : "准备运行：") + entryFile.toAbsolutePath().normalize();
        if (debug) {
            console.infoWithoutShowing(AutoGoConsoleService.Channel.LUA, preparation);
        } else {
            console.activate(AutoGoConsoleService.Channel.LUA);
            console.info(AutoGoConsoleService.Channel.LUA, preparation);
        }
        // 文件读取、哈希、ADB 和 HTTP 均在后台执行，编辑器线程只负责触发。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!confirmExecutionPrerequisites(debug ? "调试脚本" : "运行脚本")) {
                // 用户取消或设备不可用时不读取、同步任何脚本文件。
                return;
            }
            String basePath = project.getBasePath();
            if (basePath == null) {
                console.error("当前项目没有可用根目录，无法同步脚本。");
                return;
            }
            Path root = Path.of(basePath).toAbsolutePath().normalize();
            try {
                GluaDependencyGraph.Result graph = "javascript".equals(language)
                        ? resolveJavaScriptDependencies(root, entryFile)
                        : GluaDependencyGraph.resolve(root, entryFile);
                List<Path> syncFiles = mergeExtraFiles(root, graph.files());
                if (!graph.dynamicRequires().isEmpty()) {
                    // 动态 require 不能猜测，明确提示用户通过项目 extraFiles 补充。
                    console.error("发现动态脚本依赖，无法自动确定依赖："
                            + String.join("、", graph.dynamicRequires())
                            + "；请在 .autogo/engine.json 的 sync.extraFiles 中补充文件。");
                }
                syncGraph(root, entryFile.toAbsolutePath().normalize(), syncFiles, debug, language);
            } catch (IOException error) {
                // 依赖图读取失败时不上传不完整 manifest。
                console.error("解析脚本依赖图失败：" + error.getMessage());
            }
        });
    }

    /** 同步带版本元数据的 GLuac 产物、源码 require 闭包并远程执行或调试。 */
    public void syncAndRunArtifact(Path artifact, Path source, String runtimeVersion, boolean debug) {
        // 编译回调运行在后台线程，但仍统一调度，避免与其他同步流程交叉 commit。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!confirmExecutionPrerequisites(debug ? "调试 GLuac" : "运行 GLuac")) {
                // 前置状态未满足时不上传刚生成的字节码。
                return;
            }
            String basePath = project.getBasePath();
            if (basePath == null) {
                console.error("当前项目没有可用根目录，无法同步 GLuac。");
                return;
            }
            Path root = Path.of(basePath).toAbsolutePath().normalize();
            Path normalizedArtifact = artifact.toAbsolutePath().normalize();
            Path sidecar = normalizedArtifact.resolveSibling(normalizedArtifact.getFileName() + ".json");
            try {
                if (!Files.isRegularFile(normalizedArtifact) || !Files.isRegularFile(sidecar)) {
                    // 无字节码或版本 sidecar 时禁止远程运行，避免版本不可追踪。
                    throw new IOException("GLuac 产物或版本元数据不存在");
                }
                String metadata = Files.readString(sidecar, StandardCharsets.UTF_8);
                if (!metadata.contains("\"runtimeVersion\": \"" + jsonEscape(runtimeVersion) + "\"")) {
                    // 用户输入版本必须与刚生成 sidecar 完全一致。
                    throw new IOException("GLuac 目标版本与元数据不一致：" + runtimeVersion);
                }
                GluaDependencyGraph.Result graph = GluaDependencyGraph.resolve(root, source);
                List<Path> files = new ArrayList<>(mergeExtraFiles(root, graph.files()));
                files.add(normalizedArtifact);
                files.add(sidecar);
                if (!graph.dynamicRequires().isEmpty()) {
                    // 字节码执行仍可能触发源码中的动态 require，必须保留同样警告。
                    console.error("GLuac 源码包含动态 require：" + String.join("、", graph.dynamicRequires())
                            + "；请通过 sync.extraFiles 补充依赖。");
                }
                syncGraph(root, normalizedArtifact, List.copyOf(files), debug, "lua");
            } catch (IOException error) {
                // 版本、路径或依赖图不完整时禁止上传字节码。
                console.error("准备 GLuac 远程运行失败：" + error.getMessage());
            }
        });
    }

    /** 检查设备与引擎状态，用户确认后运行项目根入口。 */
    public void runProjectWithPrompt() {
        // ADB 扫描和远端健康检查必须离开 EDT；确认弹窗会切回 EDT。
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!confirmExecutionPrerequisites("运行项目")) {
                // 无设备或用户取消时不启动 AG。
                return;
            }
            AutoGoProcessService processService = project.getService(AutoGoProcessService.class);
            processService.restartAgHost(
                    AutoGoMenuActions.deviceArgs(project, "stop", false),
                    AutoGoMenuActions.deviceArgs(project, "run", false));
        });
    }

    private boolean confirmExecutionPrerequisites(String actionLabel) {
        // 用户动作只读取后台缓存，禁止在 F6/F7 前同步等待缓慢的设备端口扫描。
        String state = cachedState;
        if (isRunningState(state)) {
            // 已运行或暂停的引擎可以直接复用。
            return true;
        }
        if (!canBootstrapWithAdb() && "unreachable".equals(state)) {
            showExecutionError("远程移动端服务不可达，无法" + actionLabel
                    + "。请检查 remote.endpoint、TLS 和服务状态。");
            return false;
        }
        if (!directConnection && canBootstrapWithAdb()) {
            // ADB 模式必须同时验证“已选择”和“当前在线”，不能只信任保存的序列号。
            String serial = AutoGoMenuActions.selectedDevice(project);
            List<String> online = AutoGoDeviceSupport.listDevices(AutoGoMenuActions.settings().getAdbPath());
            if (serial.isBlank()) {
                showExecutionError("未选择 Android 设备，无法" + actionLabel
                        + "。请连接设备、完成 ADB 授权后在“连接设备”菜单选择设备。");
                return false;
            }
            if (!online.contains(serial)) {
                showExecutionError("设备 " + serial + " 当前离线，无法" + actionLabel
                        + "。请重新连接、完成 ADB 授权后刷新设备列表。");
                return false;
            }
        }
        console.info("移动端脚本引擎尚未启动，等待确认是否继续" + actionLabel + "。");
        int[] answer = new int[]{Messages.NO};
        ApplicationManager.getApplication().invokeAndWait(() -> answer[0] = Messages.showYesNoDialog(
                project,
                "移动端脚本引擎尚未启动。是否启动移动端引擎并继续" + actionLabel + "？",
                "AutoGo Script Engine Console",
                "启动并继续", "取消", Messages.getWarningIcon()));
        return answer[0] == Messages.YES;
    }

    static boolean isRunningState(String state) {
        // paused 仍表示引擎和控制通道存在，后续动作可按需要 restart。
        return "running".equals(state) || "paused".equals(state);
    }

    private static String scriptLanguage(Path entryFile) {
        String name = entryFile.getFileName() == null ? "" : entryFile.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".js") ? "javascript" : "lua";
    }

    private static String scriptLabel(String language) {
        return "javascript".equals(language) ? "JavaScript" : "GLua";
    }

    private static GluaDependencyGraph.Result resolveJavaScriptDependencies(Path projectRoot, Path entryFile)
            throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        Path entry = entryFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(entry) || !entry.startsWith(root)) {
            throw new IOException("入口文件必须位于项目根目录内：" + entry);
        }
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        List<String> dynamicRequires = new ArrayList<>();
        List<Path> pending = new ArrayList<>();
        pending.add(entry);
        Pattern callPattern = Pattern.compile("\\b(?:require|importModule|import)\\s*\\(\\s*([^)]*)\\)");
        Pattern importPattern = Pattern.compile("\\bimport\\s+(?:[^\"'()]+?\\s+from\\s*)?[\"']([^\"']+)[\"']");
        for (int cursor = 0; cursor < pending.size(); cursor++) {
            Path current = pending.get(cursor).toAbsolutePath().normalize();
            if (!current.startsWith(root) || !files.add(current)) {
                continue;
            }
            if (!current.toString().toLowerCase(Locale.ROOT).matches(".*\\.(js|json)$")) {
                continue;
            }
            String source = Files.readString(current, StandardCharsets.UTF_8);
            Matcher calls = callPattern.matcher(source);
            while (calls.find()) {
                String argument = calls.group(1) == null ? "" : calls.group(1).trim();
                Matcher literal = Pattern.compile("^[\"']([^\"']+)[\"']\\s*$").matcher(argument);
                if (!literal.matches()) {
                    dynamicRequires.add(root.relativize(current).toString().replace('\\', '/')
                            + ":" + lineAt(source, calls.start()));
                    continue;
                }
                Path resolved = resolveJavaScriptModule(root, current, literal.group(1));
                if (resolved != null) {
                    pending.add(resolved);
                }
            }
            Matcher imports = importPattern.matcher(source);
            while (imports.find()) {
                Path resolved = resolveJavaScriptModule(root, current, imports.group(1));
                if (resolved != null) {
                    pending.add(resolved);
                }
            }
        }
        return new GluaDependencyGraph.Result(List.copyOf(files), List.copyOf(new LinkedHashSet<>(dynamicRequires)));
    }

    private static Path resolveJavaScriptModule(Path root, Path current, String specifier) {
        String value = specifier == null ? "" : specifier.trim();
        if (value.isEmpty()) {
            return null;
        }
        Path base = (value.startsWith(".") || value.startsWith("/"))
                ? current.getParent().resolve(value).normalize()
                : root.resolve(value).normalize();
        for (Path candidate : List.of(
                base,
                Path.of(base.toString() + ".js"),
                Path.of(base.toString() + ".json"),
                base.resolve("index.js"),
                base.resolve("index.json"))) {
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static int lineAt(String source, int offset) {
        int line = 1;
        for (int index = 0; index < Math.min(offset, source.length()); index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private void showExecutionError(String message) {
        // Console 保留可追踪原因，同时弹窗保证用户即使停留在其他日志分区也能看到。
        console.error(message);
        ApplicationManager.getApplication().invokeLater(() ->
                Messages.showErrorDialog(project, message, "AutoGo Script Engine Console"));
    }

    private List<Path> mergeExtraFiles(Path root, List<Path> dependencyFiles) throws IOException {
        // 依赖闭包优先并保持稳定顺序，项目显式 extraFiles 随后追加并去重。
        LinkedHashSet<Path> merged = new LinkedHashSet<>(dependencyFiles);
        Path config = root.resolve(".autogo/engine.json");
        if (!Files.isRegularFile(config)) {
            return List.copyOf(merged);
        }
        // 统一经过版本化加载器，保证 IDEA 与后续 VSCode 使用同一迁移语义。
        JsonObject document = AutoGoProjectConfig.loadAndMigrate(config);
        JsonObject sync = document.has("sync") && document.get("sync").isJsonObject()
                ? document.getAsJsonObject("sync") : null;
        JsonArray extras = sync != null && sync.has("extraFiles") && sync.get("extraFiles").isJsonArray()
                ? sync.getAsJsonArray("extraFiles") : new JsonArray();
        for (JsonElement item : extras) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                // 非字符串条目属于配置错误，不能猜测转换。
                throw new IOException("sync.extraFiles 只允许项目相对路径或 glob 字符串");
            }
            collectExtra(root, item.getAsString(), merged);
        }
        return List.copyOf(merged);
    }

    static void collectExtra(Path root, String configured, Set<Path> output) throws IOException {
        // 绝对路径和父目录逃逸永远不允许进入上传 manifest。
        boolean absolute;
        try {
            absolute = configured != null && Path.of(configured).isAbsolute();
        } catch (RuntimeException invalidPath) {
            // 操作系统无法解析的路径同样视为配置错误。
            throw new IOException("非法 sync.extraFiles 路径：" + configured, invalidPath);
        }
        if (configured == null || configured.isBlank() || absolute
                || configured.replace('\\', '/').contains("../")) {
            throw new IOException("非法 sync.extraFiles 路径：" + configured);
        }
        String normalized = configured.replace('\\', '/');
        boolean glob = normalized.contains("*") || normalized.contains("?") || normalized.contains("[")
                || normalized.contains("{");
        if (glob) {
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + normalized);
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .filter(path -> !path.startsWith(root.resolve(".git")))
                        .filter(path -> matcher.matches(root.relativize(path)))
                        .sorted()
                        .forEach(path -> output.add(path.toAbsolutePath().normalize()));
            }
            return;
        }
        Path target = root.resolve(normalized).normalize();
        if (!target.startsWith(root) || !Files.exists(target)) {
            throw new IOException("sync.extraFiles 不存在或超出项目目录：" + configured);
        }
        if (Files.isRegularFile(target)) {
            output.add(target.toAbsolutePath().normalize());
            return;
        }
        try (Stream<Path> paths = Files.walk(target)) {
            paths.filter(Files::isRegularFile).sorted()
                    .forEach(path -> output.add(path.toAbsolutePath().normalize()));
        }
    }

    private void syncGraph(Path root, Path entry, List<Path> files, boolean debug, String language) throws IOException {
        // manifest 使用稳定路径顺序和内容哈希，保证相同源码得到相同 ID。
        Map<String, byte[]> contentByPath = new LinkedHashMap<>();
        StringBuilder manifestSeed = new StringBuilder();
        StringBuilder fileJson = new StringBuilder();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            byte[] content = Files.readAllBytes(file);
            String hash = sha256(content);
            contentByPath.put(relative, content);
            manifestSeed.append(relative).append('\n').append(hash).append('\n');
            if (!fileJson.isEmpty()) {
                fileJson.append(',');
            }
            fileJson.append("{\"path\":\"").append(jsonEscape(relative))
                    .append("\",\"sha256\":\"").append(hash)
                    .append("\",\"size\":").append(content.length).append('}');
        }
        String manifestID = sha256(manifestSeed.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        // 使用最近一次状态缓存；若不可达则直接进入 ag 启动并消费其端口输出。
        String state = cachedState;
        if ("unreachable".equals(state)) {
            if (!canBootstrapWithAdb()) {
                // direct 模式禁止把脚本意外部署到默认 ADB 设备。
                console.error("直接远程控制服务不可达，已取消同步。请检查 remote.endpoint。");
                return;
            }
            // 当前脚本运行也能独立启动根 main.go 控制宿主，不要求用户预先点击运行项目。
            console.info("移动端控制服务未连接，正在通过项目根目录 ag run 启动……");
            migrateGeneratedHostLock();
            AutoGoProcessService processService = project.getService(AutoGoProcessService.class);
            boolean started = processService.isRunning()
                    || processService.restartAgHost(
                            AutoGoMenuActions.deviceArgs(project, "stop", false),
                            AutoGoMenuActions.deviceArgs(project, "run", false));
            if (!started || "unreachable".equals(waitUntilReachable())) {
                console.error("无法启动移动端控制服务，已取消脚本同步。");
                return;
            }
        } else if (!ensureAdbForward()) {
            return;
        }
        HttpResult diff = request("POST", "/v1/files/diff",
                "{\"manifestId\":\"" + manifestID + "\",\"files\":[" + fileJson + "]}");
        if (!diff.success()) {
            console.error("查询远端文件差异失败：" + diff.message());
            return;
        }
        List<String> uploads = parseUploadPaths(diff.body());
        for (String relative : uploads) {
            byte[] content = contentByPath.get(relative);
            if (content == null) {
                // 服务端不得要求上传本地 manifest 之外的路径。
                console.error("远端返回了未知上传路径，已中止同步：" + relative);
                return;
            }
            String hash = sha256(content);
            String body = "{\"manifestId\":\"" + manifestID + "\",\"path\":\""
                    + jsonEscape(relative) + "\",\"sha256\":\"" + hash
                    + "\",\"contentBase64\":\"" + Base64.getEncoder().encodeToString(content) + "\"}";
            HttpResult upload = request("POST", "/v1/files/upload", body);
            if (!upload.success()) {
                // 任一文件失败都不 commit，设备继续使用上一完整版本。
                console.error("上传 " + relative + " 失败：" + upload.message());
                return;
            }
        }
        HttpResult commit = request("POST", "/v1/files/commit",
                "{\"manifestId\":\"" + manifestID + "\"}");
        if (!commit.success()) {
            console.error("提交远端脚本版本失败：" + commit.message());
            return;
        }
        console.info(scriptLabel(language) + " 增量同步完成：manifest=" + manifestID + "，上传 " + uploads.size()
                + "/" + files.size() + " 个文件。");
        // Debug 需要干净 VM；普通 F7 复用运行中的引擎，避免每次重建 DAP 和端口转发。
        HttpResult currentEngine = request("GET", "/v1/health", "{}");
        HttpResult start = debug
                ? request("POST", "/v1/engine/restart", "{}")
                : (currentEngine.success() && Set.of("running", "paused").contains(currentEngine.state())
                    ? currentEngine : request("POST", "/v1/engine/start", "{}"));
        if (!start.success()) {
            console.error("同步完成但移动端引擎启动失败：" + start.message());
            return;
        }
        if (!directConnection && debug) {
            // 引擎 restart 会分配新的 DAP 端口；调试必须丢弃健康但已过期的旧映射。
            removeForwards(AutoGoMenuActions.settings().getAdbPath().isBlank()
                    ? "adb" : AutoGoMenuActions.settings().getAdbPath(), forwardedSerial);
            forwardedSerial = "";
        }
        if (!directConnection && !ensureAdbForward()) {
            // 引擎重启会重新分配 DAP 端口，调试前必须刷新对应 ADB 映射。
            console.error("移动端引擎已重启，但无法连接新的 DAP 端口。");
            return;
        }
        String relativeEntry = root.relativize(entry).toString().replace('\\', '/');
        String runBody = "{\"entry\":\"" + jsonEscape(relativeEntry)
                + "\",\"manifestId\":\"" + manifestID
                + "\",\"language\":\"" + jsonEscape(language) + "\"}";
        primeRemoteLogCursor();
        if (debug) {
            HttpResult debugSession = request("POST", "/v1/debug", runBody);
            if (!debugSession.success()) {
                console.error("创建远程调试会话失败：" + debugSession.message());
                return;
            }
            if (!activateDebugDapEndpoint(debugSession.body())) {
                console.error("创建远程调试会话失败：无法连接语言对应的 DAP 端口");
                return;
            }
            // 先让 IDEA 原生 XDebugger 完成 initialize/attach/断点同步，再启动脚本。
            GluaRemotePathMapper.configure(project, manifestID);
            ApplicationManager.getApplication().invokeAndWait(() ->
                    GluaRemoteDebugLauncher.launch(project, entry.toString(), activeDapHost, activeDapPort));
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                // IDE 关闭或调试取消时不再启动脚本。
                Thread.currentThread().interrupt();
                console.error("远程 DAP 会话连接被中断。");
                return;
            }
            console.info("IDEA 原生 DAP 会话已连接：" + activeDapHost + ":" + activeDapPort
                    + "，manifest=" + manifestID);
            remoteDebugActive.set(true);
        }
        HttpResult run = request("POST", "/v1/run", runBody);
        if (!run.success()) {
            finishAutoGoDebugSession();
            console.error("远程执行 " + scriptLabel(language) + " 失败：" + run.message());
            return;
        }
        console.info((debug ? "远程调试脚本已启动：" : "远程脚本已启动：") + relativeEntry);
        startLogPolling();
        if (debug) {
            // AutoGo 初始化日志写完后切到原生 Debug 控制台，后续 Lua 输出不得再抢回 AutoGo 窗口。
            activateNativeDebugConsole();
        }
    }

    private synchronized void startLogPolling() {
        // 每次运行重置为新的有界轮询周期，避免多个任务重复输出同一设备日志。
        if (logPollingTask != null) {
            logPollingTask.cancel(false);
        }
        AtomicLong remainingPolls = new AtomicLong(120L);
        logPollingTask = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(() -> {
            if (project.isDisposed() || remainingPolls.getAndDecrement() <= 0L) {
                // 默认跟随两分钟；后续运行会重新开启，项目销毁时也会显式取消。
                ScheduledFuture<?> current = logPollingTask;
                if (current != null) {
                    current.cancel(false);
                }
                return;
            }
            pullRemoteLogs();
        }, 0L, 1L, TimeUnit.SECONDS);
    }

    private void primeRemoteLogCursor() {
        // 与 VSCode 一致，从运行前的最新游标开始，禁止把历史脚本日志重复输出到本轮任务。
        HttpResult latest = request("GET", "/v1/logs?cursor=0");
        if (!latest.success()) {
            return;
        }
        try {
            JsonObject document = JsonParser.parseString(latest.body()).getAsJsonObject();
            if (document.has("cursor") && document.get("cursor").isJsonPrimitive()) {
                remoteLogCursor.set(document.get("cursor").getAsLong());
            }
        } catch (RuntimeException ignored) {
            // 日志游标只是去重优化；响应损坏时仍允许本次脚本继续执行。
        }
    }

    private void pullRemoteLogs() {
        // 绝对游标允许服务端环形缓冲裁剪旧记录，同时保证客户端不重复打印。
        long cursor = remoteLogCursor.get();
        HttpResult result = request("GET", "/v1/logs?cursor=" + cursor);
        if (!result.success()) {
            // 短暂断线等待下一次轮询恢复，不用每秒刷屏。
            return;
        }
        try {
            JsonObject document = JsonParser.parseString(result.body()).getAsJsonObject();
            JsonArray entries = document.has("entries") && document.get("entries").isJsonArray()
                    ? document.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement entry : entries) {
                if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                    routeRemoteLog(entry.getAsString());
                }
            }
            if (document.has("cursor") && document.get("cursor").isJsonPrimitive()) {
                remoteLogCursor.set(document.get("cursor").getAsLong());
            }
        } catch (RuntimeException malformed) {
            // 损坏日志响应只报告一次并停止本轮任务，防止持续制造相同错误。
            if (remoteDebugActive.get()) {
                console.errorWithoutShowing(AutoGoConsoleService.Channel.EXTENSION,
                        "无法解析移动端日志响应：" + malformed.getMessage());
            } else {
                console.error("无法解析移动端日志响应：" + malformed.getMessage());
            }
            ScheduledFuture<?> current = logPollingTask;
            if (current != null) {
                current.cancel(false);
            }
        }
    }

    private void routeRemoteLog(String entry) {
        // Lua 分区只接收脚本输出和本次脚本生命周期；引擎、端口和同步过程留在扩展日志。
        String normalized = entry == null ? "" : entry.trim();
        boolean luaLog = normalized.contains("lua output:")
                || normalized.contains("lua lifecycle:")
                || normalized.contains("run started:")
                || normalized.contains("run completed:")
                || normalized.contains("run failed:");
        if (!luaLog) {
            if (remoteDebugActive.get()) {
                // 调试期间设备生命周期只静默留档，禁止后台轮询把界面从原生 Debug 切回 AutoGo。
                console.infoWithoutShowing(AutoGoConsoleService.Channel.EXTENSION, "[设备] " + normalized);
            } else {
                console.info("[设备] " + normalized);
            }
            return;
        }
        boolean terminalLog = normalized.contains("run completed:") || normalized.contains("run failed:");
        String message = normalized
                .replaceFirst("^\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[+-]\\d{2}:?\\d{2})?\\s*", "")
                .replaceFirst("^lua output:\\s*", "")
                .replaceFirst("^lua lifecycle:\\s*", "");
        boolean error = message.contains("[Error]") || message.startsWith("run failed:");
        boolean debugActive = remoteDebugActive.get();
        if (error) {
            if (debugActive) {
                console.errorWithoutShowing(AutoGoConsoleService.Channel.LUA, message);
            } else {
                console.error(AutoGoConsoleService.Channel.LUA, message);
            }
        } else {
            if (debugActive) {
                console.stdoutWithoutShowing(AutoGoConsoleService.Channel.LUA, message);
            } else {
                console.stdout(AutoGoConsoleService.Channel.LUA, message);
            }
        }
        if (debugActive) {
            // 仅 AutoGo 创建的活动调试会话接收镜像输出，普通运行不会产生原生 Debug 控制台内容。
            publishNativeDebugOutput(message, error);
        }
        if (terminalLog) {
            // 先投递最后一条生命周期或错误信息，再结束没有主动发送 terminated 的移动端会话。
            finishAutoGoDebugSession();
        }
    }

    private void publishNativeDebugOutput(String message, boolean error) {
        // 当前项目可能同时存在其它调试配置，只允许 GLua Debug Process 接收移动端脚本输出。
        XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
        if (session != null && session.getDebugProcess() instanceof GluaDebugProcess process) {
            process.publishScriptOutput(message, error);
        }
    }

    private void activateNativeDebugConsole() {
        // startSessionAndShowTab 已创建并默认选择 Console 内容，这里只需把 Debug 工具窗口带到前台。
        ApplicationManager.getApplication().invokeLater(() -> {
            ToolWindow debugWindow = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.DEBUG);
            if (debugWindow != null && !project.isDisposed()) {
                debugWindow.activate(null, true);
            }
        });
    }


    private void finishAutoGoDebugSession() {
        // 只结束本项目由 AutoGo 启动的 GLua 调试，不影响用户手工创建的其他调试配置。
        if (!remoteDebugActive.compareAndSet(true, false)) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> {
            XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
            if (session != null && session.getDebugProcess() instanceof GluaDebugProcess) {
                session.stop();
            }
        });
    }

    private String waitUntilReachable() {
        // ag run 会直接输出随机控制端口；优先消费输出，禁止每轮执行 adb shell 扫描。
        AutoGoProcessService processService = project.getService(AutoGoProcessService.class);
        for (int retry = 0; retry < 80; retry++) {
            int reportedPort = processService.getRecentRemoteControlPort();
            if (reportedPort > 0 && activateReportedControlPort(reportedPort)) {
                return requestAt(activeControlBase, "GET", "/v1/health", "{}").state();
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException interrupted) {
                // IDE 关闭或任务取消时立即停止轮询。
                Thread.currentThread().interrupt();
                return "unreachable";
            }
        }
        // 兼容旧版 ag 未输出端口的场景，超时后只做一次旧式发现。
        return queryState();
    }

    /** 迁移旧生成宿主的启动锁校验，避免 Android PID 复用导致永久误判服务已运行。 */
    private void migrateGeneratedHostLock() {
        // 只修改带生成标记且仍使用旧锁判断的根目录 main.go，用户自定义入口保持不变。
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return;
        }
        Path mainFile = Path.of(basePath).resolve("main.go");
        String legacyLock = "if processAlive(pid) { return nil, fmt.Errorf(\"AutoGo 移动端控制服务已运行，pid=%d\", pid) }";
        String processAlive = "func processAlive(pid int) bool { if pid <= 0 { return false }; err := syscall.Kill(pid, 0); return err == nil || errors.Is(err, syscall.EPERM) }";
        try {
            String source = Files.readString(mainFile, StandardCharsets.UTF_8);
            if (!source.startsWith("// Code generated by AutoGo Script Engine Console.")
                    || !source.contains(legacyLock)
                    || source.contains("func runningService(root string, pid int) bool")) {
                return;
            }
            String healthCheck = """
                    func runningService(root string, pid int) bool {
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
                    \tvar health struct { Service string `json:"service"`; InstanceID string `json:"instanceId"` }
                    \treturn json.NewDecoder(response.Body).Decode(&health) == nil && health.Service == "autogo-script-engine" && health.InstanceID == metadata.InstanceID
                    }
                    """.stripIndent().stripTrailing();
            source = source.replace(legacyLock,
                    "if runningService(root, pid) { return nil, fmt.Errorf(\"AutoGo 移动端控制服务已运行，pid=%d\", pid) }");
            source = source.replace(processAlive, processAlive + "\n\n" + healthCheck);
            Files.writeString(mainFile, source, StandardCharsets.UTF_8);
            console.info("已迁移移动端宿主启动锁：PID、控制端口与实例身份将联合校验。");
        } catch (IOException error) {
            // 迁移失败时停止启动，避免继续被陈旧锁误导；详细原因写入扩展日志。
            console.error("迁移移动端宿主启动锁失败：" + error.getMessage());
        }
    }

    private boolean activateReportedControlPort(int remoteControlPort) {
        // 使用 ag 输出建立控制转发，再从 capabilities 获取同一服务实例的 DAP 端口。
        String serial = AutoGoMenuActions.selectedDevice(project);
        if (serial.isBlank()) {
            return false;
        }
        String configuredAdb = AutoGoMenuActions.settings().getAdbPath();
        String adb = configuredAdb.isBlank() ? "adb" : configuredAdb;
        if (!forwardPort(adb, serial, localPorts.control(), remoteControlPort)) {
            return false;
        }
        URI controlBase = URI.create("http://127.0.0.1:" + localPorts.control());
        HttpResult capabilities = requestAt(controlBase, "GET", "/v1/capabilities", "{}");
        int dapPort = parseDapPort(capabilities.body(), 0);
        if (!capabilities.success()) {
            removeForwards(adb, serial);
            return false;
        }
        if (dapPort > 0 && !forwardPort(adb, serial, localPorts.dap(), dapPort)) {
            // 已报告 DAP 端口却无法转发时视为服务未就绪，防止调试连接到旧映射。
            removeForwards(adb, serial);
            return false;
        }
        forwardedSerial = serial;
        activeControlBase = controlBase;
        activeDapHost = "127.0.0.1";
        // 控制服务启动阶段允许 DAP 尚未分配；engine/start 后会重新读取并建立映射。
        activeDapPort = dapPort > 0 ? localPorts.dap() : 0;
        directConnection = false;
        connectEventSocket(controlBase);
        return true;
    }

    private String queryState() {
        // auto 模式优先尝试项目配置的直接远程地址，失败后才回退 ADB。
        RemoteConnection connection;
        try {
            connection = loadRemoteConnection();
        } catch (IOException invalidConfig) {
            console.error("远程连接配置无效：" + invalidConfig.getMessage());
            return "incompatible";
        }
        if (!"adb".equals(connection.mode()) && connection.endpoint() != null) {
            HttpResult directCapabilities = requestAt(connection.endpoint(), "GET", "/v1/capabilities", "{}");
            if (directCapabilities.success()) {
                try {
                    validateCapabilities(directCapabilities.body(),
                            Set.of("lua", "glua", "gluac", "dap", "incremental-sync"));
                    activateDirectConnection(connection.endpoint(), directCapabilities.body());
                    return requestAt(connection.endpoint(), "GET", "/v1/health", "{}").state();
                } catch (IOException incompatible) {
                    console.error("直接远程脚本引擎协议不兼容：" + incompatible.getMessage());
                    return "incompatible";
                }
            }
            if ("direct".equals(connection.mode())) {
                // direct 模式禁止静默切换设备，原始连接错误由状态展示。
                return directCapabilities.state();
            }
            console.info("直接远程控制服务不可达，正在回退 ADB：" + directCapabilities.message());
        }
        String selectedSerial = AutoGoMenuActions.selectedDevice(project);
        if (!forwardedSerial.isBlank() && forwardedSerial.equals(selectedSerial) && !directConnection) {
            // 已建立的控制转发优先使用亚秒级健康检查；只有失效才重新扫描设备 listener。
            HttpResult cachedHealth = requestAt(activeControlBase, "GET", "/v1/health", "{}");
            if (cachedHealth.success()) {
                return cachedHealth.state();
            }
        }
        // 每次 ADB 查询前重建幂等端口转发，设备切换后不会继续访问旧设备。
        if (!ensureAdbForward()) {
            return "unreachable";
        }
        HttpResult capabilities = requestAt(activeControlBase, "GET", "/v1/capabilities", "{}");
        if (!capabilities.success()) {
            // 控制服务可能存在但不是受支持的 AutoGo 协议端点。
            return capabilities.state();
        }
        try {
            validateCapabilities(capabilities.body(), Set.of("lua", "glua", "gluac", "dap", "incremental-sync"));
        } catch (IOException incompatible) {
            // 协议不兼容时拒绝继续发送启动、上传或调试请求。
            console.error("移动端脚本引擎协议不兼容：" + incompatible.getMessage());
            return "incompatible";
        }
        return requestAt(activeControlBase, "GET", "/v1/health", "{}").state();
    }

    private RemoteConnection loadRemoteConnection() throws IOException {
        // 项目级配置由 IDEA 与 VSCode 共享；缺失配置保持 ADB auto 行为。
        String basePath = project.getBasePath();
        if (basePath == null) {
            return new RemoteConnection("auto", null);
        }
        Path config = Path.of(basePath).resolve(".autogo/engine.json");
        if (!Files.isRegularFile(config)) {
            return new RemoteConnection("auto", null);
        }
        JsonObject document = AutoGoProjectConfig.loadAndMigrate(config);
        JsonObject remote = document.has("remote") && document.get("remote").isJsonObject()
                ? document.getAsJsonObject("remote") : new JsonObject();
        String mode = remote.has("mode") && remote.get("mode").isJsonPrimitive()
                ? remote.get("mode").getAsString().trim().toLowerCase() : "auto";
        if (!Set.of("auto", "direct", "adb").contains(mode)) {
            // 未知模式不能被猜测为某一种传输。
            throw new IOException("remote.mode 只允许 auto、direct 或 adb");
        }
        String endpointText = remote.has("endpoint") && remote.get("endpoint").isJsonPrimitive()
                ? remote.get("endpoint").getAsString().trim() : "";
        if (endpointText.isEmpty()) {
            if ("direct".equals(mode)) {
                // direct 没有地址时不允许隐式走 ADB。
                throw new IOException("remote.mode=direct 时必须配置 remote.endpoint");
            }
            return new RemoteConnection(mode, null);
        }
        return new RemoteConnection(mode, validateDirectEndpoint(endpointText));
    }

    private boolean canBootstrapWithAdb() {
        // 配置读取失败和 direct 模式都不允许执行破坏目标选择的 ADB 回退。
        try {
            return !"direct".equals(loadRemoteConnection().mode());
        } catch (IOException invalidConfig) {
            return false;
        }
    }

    static URI validateDirectEndpoint(String endpointText) throws IOException {
        // 直接远程控制只允许 HTTPS；明文 HTTP 仅允许本机回环测试。
        URI endpoint;
        try {
            endpoint = URI.create(endpointText).normalize();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("remote.endpoint 不是有效 URL", invalid);
        }
        String scheme = endpoint.getScheme() == null ? "" : endpoint.getScheme().toLowerCase();
        String host = endpoint.getHost() == null ? "" : endpoint.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "::1".equals(host)
                || host.startsWith("127.");
        if (!("https".equals(scheme) || ("http".equals(scheme) && loopback))) {
            // 局域网和公网 token 禁止通过明文 HTTP 传输。
            throw new IOException("非回环直接远程连接必须使用 HTTPS");
        }
        if (host.isBlank() || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null) {
            // 凭据、查询和片段不得混入基础控制地址。
            throw new IOException("remote.endpoint 必须是无凭据、查询和片段的服务基础地址");
        }
        String text = endpoint.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }

    private void activateDirectConnection(URI endpoint, String capabilitiesBody) {
        // 直连成功后清理本项目旧 ADB 映射，并采用能力响应中的 DAP 端口。
        String adb = AutoGoMenuActions.settings().getAdbPath();
        removeForwards(adb.isBlank() ? "adb" : adb, forwardedSerial);
        forwardedSerial = "";
        activeControlBase = endpoint;
        directConnection = true;
        activeDapHost = endpoint.getHost();
        activeDapPort = parseDapPort(capabilitiesBody, REMOTE_DAP_PORT);
        connectEventSocket(endpoint);
    }

    static int parseDapPort(String capabilitiesBody, int fallback) {
        // DAP 端口必须来自有效 JSON 数字且处于标准 TCP 范围。
        try {
            JsonObject document = JsonParser.parseString(capabilitiesBody).getAsJsonObject();
            JsonObject dap = document.has("dap") && document.get("dap").isJsonObject()
                    ? document.getAsJsonObject("dap") : null;
            int port = dap != null && dap.has("port") ? dap.get("port").getAsInt() : fallback;
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (RuntimeException malformed) {
            // 能力主结构已由调用方校验；损坏的可选 DAP 端口使用协议默认值。
            return fallback;
        }
    }

    private boolean activateDebugDapEndpoint(String debugBody) {
        int remoteDapPort = parseDapPort(debugBody, 0);
        if (remoteDapPort <= 0) {
            return false;
        }
        if (directConnection) {
            activeDapHost = activeControlBase.getHost();
            activeDapPort = remoteDapPort;
            return true;
        }
        String serial = AutoGoMenuActions.selectedDevice(project);
        if (serial.isBlank()) {
            return false;
        }
        String configuredAdb = AutoGoMenuActions.settings().getAdbPath();
        String adb = configuredAdb.isBlank() ? "adb" : configuredAdb;
        removeForwardPort(adb, serial, localPorts.dap());
        if (!forwardPort(adb, serial, localPorts.dap(), remoteDapPort)) {
            return false;
        }
        activeDapHost = "127.0.0.1";
        activeDapPort = localPorts.dap();
        return true;
    }

    /** 校验控制面协议主版本及扩展执行链依赖的能力集合。 */
    static void validateCapabilities(String responseBody, Set<String> requiredFeatures) throws IOException {
        // JSON 结构缺失或类型错误必须视为不兼容，不能猜测服务端行为。
        JsonObject capabilities;
        try {
            capabilities = JsonParser.parseString(responseBody).getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("capabilities 响应不是有效 JSON", malformed);
        }
        if (!capabilities.has("protocolVersion") || !capabilities.get("protocolVersion").isJsonPrimitive()) {
            throw new IOException("capabilities 缺少 protocolVersion");
        }
        String version = capabilities.get("protocolVersion").getAsString();
        String[] versionParts = version.split("\\.", -1);
        int major;
        try {
            major = Integer.parseInt(versionParts[0]);
        } catch (NumberFormatException invalidVersion) {
            throw new IOException("无法识别协议版本：" + version, invalidVersion);
        }
        if (major != SUPPORTED_PROTOCOL_MAJOR) {
            throw new IOException("不支持协议主版本 " + major + "，IDEA 仅支持 " + SUPPORTED_PROTOCOL_MAJOR + ".x");
        }
        Set<String> available = new LinkedHashSet<>();
        JsonArray features = capabilities.has("features") && capabilities.get("features").isJsonArray()
                ? capabilities.getAsJsonArray("features") : new JsonArray();
        for (JsonElement feature : features) {
            if (feature.isJsonPrimitive() && feature.getAsJsonPrimitive().isString()) {
                // 未知次版本能力允许保留，调用方只验证自己需要的交集。
                available.add(feature.getAsString());
            }
        }
        Set<String> missing = new LinkedHashSet<>(requiredFeatures);
        missing.removeAll(available);
        if (!missing.isEmpty()) {
            throw new IOException("缺少必要能力：" + String.join("、", missing));
        }
    }

    private boolean ensureAdbForward() {
        // 控制端口和 DAP 端口必须绑定同一当前设备。
        String serial = AutoGoMenuActions.selectedDevice(project);
        if (serial.isBlank()) {
            console.error("尚未选择在线设备，无法连接移动端脚本引擎。");
            return false;
        }
        if (!directConnection && serial.equals(forwardedSerial)
                && requestAt(activeControlBase, "GET", "/v1/health", "{}").success()) {
            // 当前项目转发仍健康时直接复用，禁止为每次 F6/F7 重跑 adb shell 端口扫描。
            return true;
        }
        String adb = AutoGoMenuActions.settings().getAdbPath();
        String executable = adb.isBlank() ? "adb" : adb;
        String previousSerial = forwardedSerial;
        if (!previousSerial.isBlank() && !previousSerial.equals(serial)) {
            // 切换设备前移除上一台设备的项目专属映射，避免旧连接继续占用端口。
            removeForwards(executable, previousSerial);
            forwardedSerial = "";
        }
        Integer pidControlPort = discoverPidControlPort(executable, serial);
        if (pidControlPort != null && activateDiscoveredControlPort(executable, serial)) {
            // PID、进程和控制端口三项均有效时直接复用，这是常规且最快的恢复路径。
            return true;
        }
        // 没有有效 PID 文件就判定控制服务未启动，由上层统一执行 ag stop → ag run。
        return false;
    }

    private boolean activateDiscoveredControlPort(String adb, String serial) {
        // PID/旧版发现阶段已经完成控制端口转发与 health 验证，这里补充能力和 DAP 校验后提交连接。
        URI discoveredBase = URI.create("http://127.0.0.1:" + localPorts.control());
        HttpResult capabilities = requestAt(discoveredBase, "GET", "/v1/capabilities", "{}");
        int discoveredDapPort = parseDapPort(capabilities.body(), 0);
        if (!capabilities.success() || (discoveredDapPort > 0
                && !forwardPort(adb, serial, localPorts.dap(), discoveredDapPort))) {
            // 非 AutoGo 服务或 DAP 映射失败时清理本次候选映射。
            removeForwards(adb, serial);
            return false;
        }
        forwardedSerial = serial;
        activeControlBase = discoveredBase;
        activeDapHost = "127.0.0.1";
        // stopped 控制服务允许 DAP 为 0；engine/start 后调试路径会强制刷新映射。
        activeDapPort = discoveredDapPort > 0 ? localPorts.dap() : 0;
        directConnection = false;
        connectEventSocket(discoveredBase);
        return true;
    }

    private void connectEventSocket(URI controlBase) {
        // 每个项目只保留一条控制通道；状态和日志仍有 HTTP 轮询作为降级。
        WebSocket previous = eventSocket;
        if (previous != null && !previous.isOutputClosed()) {
            previous.sendClose(WebSocket.NORMAL_CLOSURE, "reconnect");
        }
        String scheme = "https".equalsIgnoreCase(controlBase.getScheme()) ? "wss" : "ws";
        URI endpoint = URI.create(scheme + "://" + controlBase.getAuthority() + "/v1/events");
        WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(2));
        String token = AutoGoMenuActions.settings().getRemoteControlToken();
        if (!token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        builder.buildAsync(endpoint, new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                eventSocket = webSocket;
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                if (last) {
                    cachedState = capture(STATE, data.toString(), cachedState);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                if (eventSocket == webSocket) {
                    eventSocket = null;
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                if (eventSocket == webSocket) {
                    eventSocket = null;
                }
            }
        });
    }

    private Integer discoverPidControlPort(String adb, String serial) {
        // PID 文件由移动端宿主原子写入；只有 PID 活跃、端口可达且实例 ID 一致时才允许复用。
        String remoteRoot = AutoGoMenuActions.settings().getRemoteTempDir().replaceAll("/+$", "")
                + "/.autogo/remote";
        Process read = null;
        try {
            read = new ProcessBuilder(adb, "-s", serial, "shell", "cat",
                    remoteRoot + "/engine.pid.json").redirectErrorStream(true).start();
            if (!read.waitFor(3, TimeUnit.SECONDS) || read.exitValue() != 0) {
                return null;
            }
            JsonObject metadata = JsonParser.parseString(
                    new String(read.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim()).getAsJsonObject();
            int pid = metadata.has("pid") ? metadata.get("pid").getAsInt() : 0;
            int controlPort = metadata.has("controlPort") ? metadata.get("controlPort").getAsInt() : 0;
            String instanceId = metadata.has("instanceId") ? metadata.get("instanceId").getAsString() : "";
            if (pid <= 0 || controlPort <= 0 || controlPort > 65535) {
                return null;
            }
            if (!forwardPort(adb, serial, localPorts.control(), controlPort)) {
                return null;
            }
            HttpResult health = requestAt(URI.create("http://127.0.0.1:" + localPorts.control()),
                    "GET", "/v1/health", "{}");
            if (!health.success() || !health.body().contains("\"service\":\"autogo-script-engine\"")
                    || (!instanceId.isBlank() && !health.body().contains("\"instanceId\":\"" + instanceId + "\""))) {
                return null;
            }
            console.info("已通过 PID 文件复用移动端控制服务：pid=" + pid + "，port=" + controlPort);
            return controlPort;
        } catch (IOException | InterruptedException | RuntimeException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        } finally {
            if (read != null && read.isAlive()) {
                read.destroyForcibly();
            }
        }
    }

    private boolean forwardPort(String adb, String serial, int localPort, int remotePort) {
        // 单个动态映射失败只淘汰当前候选端口，不影响后续候选探测。
        Process process = null;
        try {
            process = new ProcessBuilder(adb, "-s", serial, "forward",
                    "tcp:" + localPort, "tcp:" + remotePort).redirectErrorStream(true).start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private void removeForwards(String adb, String serial) {
        // 清理只针对本项目分配的本地端口，不影响用户或其他项目的 ADB 映射。
        if (serial == null || serial.isBlank()) {
            return;
        }
        for (int port : List.of(localPorts.control(), localPorts.dap())) {
            Process cleanup = null;
            try {
                cleanup = new ProcessBuilder(adb, "-s", serial, "forward", "--remove", "tcp:" + port)
                        .redirectErrorStream(true).start();
                if (!cleanup.waitFor(2, TimeUnit.SECONDS)) {
                    // 项目切换不能被无响应的 adb 清理阻塞。
                    cleanup.destroyForcibly();
                }
            } catch (IOException | InterruptedException ignored) {
                // 清理属于尽力操作；中断时保留线程状态。
                if (ignored instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                // 异常路径回收清理进程。
                if (cleanup != null && cleanup.isAlive()) {
                    cleanup.destroyForcibly();
                }
            }
        }
    }

    private void removeForwardPort(String adb, String serial, int localPort) {
        // 切换语言 DAP 端口时只能清理调试端口，控制端口必须保持在线。
        if (serial == null || serial.isBlank() || localPort <= 0) {
            return;
        }
        Process cleanup = null;
        try {
            cleanup = new ProcessBuilder(adb, "-s", serial, "forward", "--remove", "tcp:" + localPort)
                    .redirectErrorStream(true).start();
            if (!cleanup.waitFor(2, TimeUnit.SECONDS)) {
                cleanup.destroyForcibly();
            }
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (cleanup != null && cleanup.isAlive()) {
                cleanup.destroyForcibly();
            }
        }
    }

    private HttpResult request(String method, String path) {
        // 无请求体的控制动作使用空 JSON 对象。
        return request(method, path, "{}");
    }

    private HttpResult request(String method, String path, String body) {
        // 使用最近一次能力协商选中的直连或 ADB 控制地址。
        return requestAt(activeControlBase, method, path, body);
    }

    private HttpResult requestAt(URI base, String method, String path, String body) {
        // 路径固定由插件定义，基础地址已经过 HTTPS/loopback 安全校验。
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base.toString() + path))
                .timeout(Duration.ofSeconds(3));
        String token = AutoGoMenuActions.settings().getRemoteControlToken();
        if (!token.isBlank()) {
            // bearer token 来自 PasswordSafe，任何日志都不得输出该请求头。
            builder.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method)) {
            builder.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.GET();
        }
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String state = capture(STATE, response.body(), response.statusCode() < 400 ? cachedState : "failed");
            String message = capture(ERROR_MESSAGE, response.body(), response.body());
            return new HttpResult(response.statusCode() >= 200 && response.statusCode() < 300,
                    state, message, response.body());
        } catch (IOException error) {
            // 未监听、连接重置和超时都归为不可达，由上层决定是否触发 ag run。
            return new HttpResult(false, "unreachable", error.getMessage(), "");
        } catch (InterruptedException interrupted) {
            // 保留线程中断，避免 IDE 关闭时继续进行网络操作。
            Thread.currentThread().interrupt();
            return new HttpResult(false, "unreachable", interrupted.getMessage(), "");
        }
    }

    static List<String> parseUploadPaths(String body) throws IOException {
        // 直连响应必须是严格 JSON，缺失字段不能被误判成空差异。
        try {
            JsonObject document = JsonParser.parseString(body == null ? "" : body).getAsJsonObject();
            if (!document.has("upload") || !document.get("upload").isJsonArray()) {
                // 协议要求 upload 始终存在，即使数组为空。
                throw new IOException("diff 响应缺少 upload 数组");
            }
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            JsonArray upload = document.getAsJsonArray("upload");
            for (JsonElement item : upload) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                    // 非字符串路径可能绕过本地 manifest 查找，必须拒绝。
                    throw new IOException("diff 响应 upload 只允许字符串路径");
                }
                String path = item.getAsString();
                if (!paths.add(path)) {
                    // 重复路径会导致重复上传和不稳定日志，视为协议错误。
                    throw new IOException("diff 响应包含重复上传路径：" + path);
                }
            }
            return List.copyOf(paths);
        } catch (IOException protocolError) {
            throw protocolError;
        } catch (RuntimeException malformed) {
            throw new IOException("diff 响应不是有效 JSON", malformed);
        }
    }

    private static String sha256(byte[] content) {
        // SHA-256 同时用于文件校验和稳定 manifest 标识。
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            // 标准 JDK 必须提供 SHA-256，缺失属于无法继续的运行环境损坏。
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }

    private static String jsonEscape(String value) {
        // 路径只需转义 JSON 控制字符，不改变项目相对路径语义。
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String capture(Pattern pattern, String value, String fallback) {
        // 仅提取协议中稳定的简单字符串字段，未知响应完整保留为诊断文本。
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    /** 清理当前设备的本地端口转发。 */
    @Override
    public void dispose() {
        // 转发由 adb server 持有，项目关闭时显式移除避免污染其他项目。
        ScheduledFuture<?> currentLogTask = logPollingTask;
        if (currentLogTask != null) {
            currentLogTask.cancel(false);
        }
        statusPollingTask.cancel(false);
        WebSocket socket = eventSocket;
        eventSocket = null;
        if (socket != null && !socket.isOutputClosed()) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "project disposed");
        }
        String adb = AutoGoMenuActions.settings().getAdbPath();
        String executable = adb.isBlank() ? "adb" : adb;
        removeForwards(executable, forwardedSerial);
        forwardedSerial = "";
        AutoGoPortAllocator.release(localPorts);
    }

    private record HttpResult(boolean success, String state, String message, String body) { }

    private record RemoteConnection(String mode, URI endpoint) { }
}
