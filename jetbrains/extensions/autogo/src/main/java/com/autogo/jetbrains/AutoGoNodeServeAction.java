package com.autogo.jetbrains;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 为当前设备启动 ag nodeserve，并在服务就绪后打开 IDEA 内嵌节点助手。
 */
public final class AutoGoNodeServeAction extends DumbAwareAction {
    private static final int NODE_AID_PORT = 8801;

    /** 创建带节点助手图标的动作。 */
    public AutoGoNodeServeAction() {
        super("节点助手", "在 IDEA 内部打开当前设备的节点助手", AutoGoIcons.NODE);
    }

    /** 启动节点助手；没有选择设备时拒绝执行。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // 节点助手 URL 必须包含用户选择的设备序列号。
        Project project = event.getProject();
        String device = AutoGoMenuActions.requireDevice(project);
        if (project == null || project.getBasePath() == null || device == null) {
            return;
        }
        new Task.Backgroundable(project, "启动 AutoGo 节点助手", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 后台启动 nodeaid 并等待本地 HTTP 服务就绪。
                startAndOpen(project, device, indicator);
            }
        }.queue();
    }

    private static void startAndOpen(Project project, String device, ProgressIndicator indicator) {
        // AG 路径遵循设置和自动发现结果。
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        AutoGoSettings settings = AutoGoMenuActions.settings();
        String ag = AgExecutableResolver.resolve(settings.getAgPath(), System.getenv());
        indicator.setText("启动 ag nodeserve");
        console.info("> " + ag + " nodeserve -s " + device);
        try {
            Process process = new ProcessBuilder(ag, "nodeserve", "-s", device)
                    .directory(new java.io.File(project.getBasePath()))
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                // 启动器超时必须先终止，再读取当前输出，避免 readAllBytes 无限阻塞。
                process.destroyForcibly();
                console.error("节点助手启动超时。");
                return;
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 && !isPortReady()) {
                // 已有 nodeaid 监听时允许复用，否则非零退出视为启动失败。
                console.error("节点助手启动失败：" + output);
                return;
            }
            if (!output.isBlank()) {
                // 保留 AG 返回的访问地址，便于排查版本差异。
                console.info(output);
            }
        } catch (IOException | InterruptedException error) {
            // 启动错误或任务取消均停止后续浏览器操作。
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            console.error("节点助手启动失败：" + error.getMessage());
            return;
        }
        indicator.setText("等待节点助手服务就绪");
        if (!waitForPort(indicator)) {
            // 服务未在预算内监听时给出明确诊断。
            console.error("节点助手未在 10 秒内监听 127.0.0.1:" + NODE_AID_PORT);
            return;
        }
        String encodedDevice = URLEncoder.encode(device, StandardCharsets.UTF_8);
        String url = "http://127.0.0.1:" + NODE_AID_PORT + "/index.html?device=" + encodedDevice;
        AutoGoNodeAssistantService assistant = project.getService(AutoGoNodeAssistantService.class);
        if (assistant.isBrowserSupported()) {
            // JCEF 可用时始终在 IDEA 内部打开。
            assistant.open(url);
        } else {
            // 精简运行时没有 JCEF 时降级到系统浏览器。
            console.info("当前 IDEA 不支持 JCEF，已使用系统浏览器打开节点助手。");
            BrowserUtil.browse(url);
        }
    }

    private static boolean waitForPort(ProgressIndicator indicator) {
        // 以短连接轮询本地监听端口，最多等待 10 秒并支持取消。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && !indicator.isCanceled()) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", NODE_AID_PORT), 300);
                return true;
            } catch (IOException ignored) {
                // 服务尚未就绪，短暂等待后重试。
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static boolean isPortReady() {
        // 单次短连接用于判断已有 nodeaid 是否可复用。
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", NODE_AID_PORT), 300);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}
