package com.autogo.jetbrains;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一启动 AG、ADB、Go 和 Git 子进程，并把输出路由到 AutoGo Console。
 */
@Service(Service.Level.PROJECT)
public final class AutoGoProcessService implements Disposable {
    private static final Pattern REMOTE_CONTROL_LISTENER = Pattern.compile(
            "remote control listening on (?:127\\.0\\.0\\.1|\\[?::1\\]?):(\\d{1,5})");
    private final Project project;
    private final AutoGoConsoleService console;
    private OSProcessHandler activeHandler;
    private volatile int recentRemoteControlPort;
    private final StringBuilder recentAgOutput = new StringBuilder();

    /** 创建项目级进程服务。 */
    public AutoGoProcessService(Project project) {
        // 保存项目和唯一控制台，所有菜单动作共享运行状态。
        this.project = project;
        this.console = project.getService(AutoGoConsoleService.class);
    }

    /** 使用统一环境启动 AG 命令。 */
    public synchronized boolean runAg(List<String> args) {
        // AG 路径使用插件设置、环境变量和默认路径的统一解析规则。
        if (args.contains("run")) {
            // 新一轮宿主启动必须等待本次输出的随机控制端口，禁止复用旧进程端口。
            recentRemoteControlPort = 0;
            recentAgOutput.setLength(0);
        }
        AutoGoSettings settings = settings();
        String executable = AgExecutableResolver.resolve(settings.getAgPath(), System.getenv());
        return run(executable, args, true);
    }

    /** 使用统一环境启动 AG，并在进程退出后回调退出码。 */
    public synchronized boolean runAg(List<String> args, IntConsumer onExit) {
        // 初始化等流水线动作需要在 AG 成功后继续生成项目文件。
        if (args.contains("run")) {
            // 带回调入口同样清理旧控制端口缓存。
            recentRemoteControlPort = 0;
            recentAgOutput.setLength(0);
        }
        AutoGoSettings settings = settings();
        String executable = AgExecutableResolver.resolve(settings.getAgPath(), System.getenv());
        return run(executable, args, true, onExit);
    }

    /** 顺序执行 ag stop 与 ag run，确保缺少有效 PID 时不会与旧宿主并存。 */
    public synchronized boolean restartAgHost(List<String> stopArgs, List<String> runArgs) {
        // 启动流水线前清空旧端口；stop 结束释放独占槽位后再启动 run。
        recentRemoteControlPort = 0;
        recentAgOutput.setLength(0);
        AutoGoSettings settings = settings();
        String executable = AgExecutableResolver.resolve(settings.getAgPath(), System.getenv());
        return run(executable, stopArgs, true, exitCode -> {
            // ag stop 无论是否找到旧进程都继续 run，最终以新宿主的端口和 PID 文件为准。
            if (exitCode != 0) {
                console.info(AutoGoConsoleService.Channel.AG,
                        "ag stop 未能确认旧进程状态，继续启动移动端宿主。退出码：" + exitCode);
            }
            if (!runAg(runArgs)) {
                // 下一阶段未能进入进程槽位时必须给出明确错误，避免等待端口直到超时。
                console.error("ag stop 已结束，但 ag run 未能启动。");
            }
        });
    }

    /** 使用统一环境启动 ADB 命令。 */
    public synchronized boolean runAdb(List<String> args) {
        // ADB 留空时交由系统 PATH 解析。
        String executable = settings().getAdbPath().isBlank() ? "adb" : settings().getAdbPath();
        return run(executable, args, true);
    }

    /** 使用统一环境启动 ADB，并在进程退出后回调退出码。 */
    public synchronized boolean runAdb(List<String> args, IntConsumer onExit) {
        // 无线切换等多阶段设备操作需要串联 tcpip、connect 和验证。
        String executable = settings().getAdbPath().isBlank() ? "adb" : settings().getAdbPath();
        return run(executable, args, true, onExit);
    }

    /** 运行无线 ADB 配对并在日志中隐藏一次性配对码。 */
    public synchronized boolean runAdbPair(String endpoint, String pairingCode, IntConsumer onExit) {
        // 配对码只作为本机 adb 参数传递，Console 永远显示脱敏占位符。
        String executable = settings().getAdbPath().isBlank() ? "adb" : settings().getAdbPath();
        return run(executable, List.of("pair", endpoint, pairingCode), true, onExit,
                executable + " pair " + endpoint + " ******");
    }

    /** 启动指定工具；长任务运行期间拒绝并发启动。 */
    public synchronized boolean run(String executable, List<String> args, boolean exclusive) {
        // 普通命令不需要退出回调。
        return run(executable, args, exclusive, ignored -> { });
    }

    /** 启动指定工具并在退出后回调结果；长任务运行期间拒绝并发启动。 */
    public synchronized boolean run(String executable, List<String> args, boolean exclusive, IntConsumer onExit) {
        // 默认日志可以显示完整非敏感命令行。
        return run(executable, args, exclusive, onExit, null);
    }

    /** 启动指定工具，并允许调用方提供脱敏后的日志命令。 */
    private synchronized boolean run(String executable, List<String> args, boolean exclusive,
                                     IntConsumer onExit, String displayCommand) {
        // 独占命令避免多个 AG/ADB 操作争用设备和控制台状态。
        if (exclusive && activeHandler != null && !activeHandler.isProcessTerminated()) {
            console.error("已有 AutoGo 任务正在运行，请先停止或等待完成。");
            return false;
        }
        String basePath = project.getBasePath();
        if (basePath == null) {
            // 无项目目录时禁止执行，避免文件操作落到 IDE 启动目录。
            console.error("当前项目没有可用的根目录。");
            return false;
        }
        GeneralCommandLine commandLine = new GeneralCommandLine(executable)
                .withParameters(args)
                .withWorkDirectory(basePath)
                .withEnvironment(environment());
        AutoGoConsoleService.Channel channel = commandChannel(executable);
        console.info(channel, "> " + (displayCommand == null ? commandLine.getCommandLineString() : displayCommand));
        console.info(channel, "cwd: " + basePath);
        try {
            OSProcessHandler handler = new OSProcessHandler(commandLine);
            activeHandler = handler;
            attach(handler, onExit, channel);
            handler.startNotify();
            return true;
        } catch (ExecutionException error) {
            // 启动失败必须展示命令与底层错误，便于定位路径和权限问题。
            activeHandler = null;
            console.error("无法启动命令：" + error.getMessage());
            return false;
        }
    }

    /** 停止插件当前启动的进程。 */
    public synchronized void stopActive() {
        // 先终止本地进程，再由动作层决定是否补发 ag stop。
        if (activeHandler != null && !activeHandler.isProcessTerminated()) {
            console.info("正在停止当前 AutoGo 任务……");
            activeHandler.destroyProcess();
        }
    }

    /** 判断当前是否存在运行中的长任务。 */
    public synchronized boolean isRunning() {
        // 菜单和工具栏使用该状态决定是否允许新任务。
        return activeHandler != null && !activeHandler.isProcessTerminated();
    }

    /** 返回最近一次 ag run 输出的移动端随机控制端口。 */
    public int getRecentRemoteControlPort() {
        // 端口由进程输出线程写入，volatile 保证引擎等待线程立即可见。
        return recentRemoteControlPort;
    }

    private void attach(OSProcessHandler handler, IntConsumer onExit, AutoGoConsoleService.Channel channel) {
        // 监听文本和退出事件，将完整生命周期写入唯一控制台。
        handler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                // stderr 与 stdout 使用不同颜色展示。
                recentAgOutput.append(event.getText());
                if (recentAgOutput.length() > 2048) {
                    recentAgOutput.delete(0, recentAgOutput.length() - 2048);
                }
                Matcher listener = REMOTE_CONTROL_LISTENER.matcher(recentAgOutput);
                if (listener.find()) {
                    int port = Integer.parseInt(listener.group(1));
                    if (port >= 1 && port <= 65535) {
                        // 直接复用 ag 明确报告的端口，避免再执行缓慢的 adb shell ss。
                        recentRemoteControlPort = port;
                    }
                }
                if (ProcessOutputType.isStderr(outputType)) {
                    console.error(channel, event.getText());
                } else {
                    console.stdout(channel, event.getText());
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                // 记录退出码并释放独占槽位。
                console.info(channel, "进程已结束，退出码：" + event.getExitCode());
                synchronized (AutoGoProcessService.this) {
                    if (activeHandler == handler) {
                        activeHandler = null;
                    }
                }
                // 回调始终在独占槽位释放后执行，允许流水线启动下一步任务。
                try {
                    onExit.accept(event.getExitCode());
                } catch (RuntimeException error) {
                    // 后处理失败必须显示在同一 Console，不能破坏进程监听线程。
                    console.error("命令后处理失败：" + error.getMessage());
                }
            }
        });
    }

    private static AutoGoConsoleService.Channel commandChannel(String executable) {
        // AG 命令单独归档；Go 构建归入 Go，其余 ADB/Git/工具过程属于扩展日志。
        String name = new File(executable == null ? "" : executable).getName().toLowerCase();
        if (name.equals("ag") || name.equals("ag.exe")) {
            return AutoGoConsoleService.Channel.AG;
        }
        if (name.equals("go") || name.equals("go.exe")) {
            return AutoGoConsoleService.Channel.GO;
        }
        return AutoGoConsoleService.Channel.EXTENSION;
    }

    private Map<String, String> environment() {
        // 从系统环境复制后注入插件代理与 Go 工具目录，不污染用户全局环境。
        Map<String, String> environment = new java.util.HashMap<>(System.getenv());
        AutoGoSettings settings = settings();
        String proxy = settings.getNetworkProxy();
        if (!proxy.isBlank()) {
            // SOCKS5 只设置 ALL_PROXY；HTTP 同时覆盖 HTTP/HTTPS 代理变量。
            if ("HTTP".equals(settings.getProxyType())) {
                environment.put("HTTP_PROXY", proxy);
                environment.put("HTTPS_PROXY", proxy);
                environment.put("http_proxy", proxy);
                environment.put("https_proxy", proxy);
            }
            environment.put("ALL_PROXY", proxy);
            environment.put("all_proxy", proxy);
        }
        String goPath = settings.getGoPath();
        if (!goPath.isBlank()) {
            // 仅将 Go 可执行文件所在目录前置到子进程 PATH。
            File parent = new File(goPath).getAbsoluteFile().getParentFile();
            if (parent != null) {
                String currentPath = environment.getOrDefault("PATH", "");
                environment.put("PATH", parent.getPath() + File.pathSeparator + currentPath);
            }
        }
        return environment;
    }

    private static AutoGoSettings settings() {
        // 所有项目读取同一份应用级工具链配置。
        return ApplicationManager.getApplication().getService(AutoGoSettings.class);
    }

    /** 项目关闭时终止插件创建的子进程。 */
    @Override
    public void dispose() {
        // 防止 AG nodeserve、run 或 ADB 长任务在项目关闭后残留。
        stopActive();
    }
}
