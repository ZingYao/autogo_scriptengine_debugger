package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 按设备 ABI 检查并同步 resources/libs 中缺失或大小变化的动态库。
 */
public final class AutoGoSyncResourcesAction extends DumbAwareAction {
    /** 创建带 AutoGo 同步图标的动作。 */
    public AutoGoSyncResourcesAction() {
        super("同步资源", "同步当前设备架构的动态库", AutoGoIcons.SYNC);
    }

    /** 在后台执行设备扫描和增量推送。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // 同步必须绑定有效项目和已选择设备。
        Project project = event.getProject();
        String device = AutoGoMenuActions.requireDevice(project);
        if (project == null || project.getBasePath() == null || device == null) {
            return;
        }
        new Task.Backgroundable(project, "同步 AutoGo 资源", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 后台任务逐步更新进度，允许用户了解当前阶段。
                sync(project, device, indicator);
            }
        }.queue();
    }

    private static void sync(Project project, String device, ProgressIndicator indicator) {
        // 获取共享配置和控制台，所有诊断集中输出。
        AutoGoSettings settings = AutoGoMenuActions.settings();
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        String adb = settings.getAdbPath().isBlank() ? "adb" : settings.getAdbPath();
        String remoteDir = settings.getRemoteTempDir();
        indicator.setText("读取设备架构");
        CommandResult abiResult = execute(List.of(adb, "-s", device, "shell", "getprop", "ro.product.cpu.abi"), 10);
        if (abiResult.exitCode != 0) {
            // 无法识别 ABI 时禁止猜测目录，避免推送错误架构动态库。
            console.error("读取设备 ABI 失败：" + abiResult.output);
            return;
        }
        String abi = normalizeAbi(abiResult.output.trim());
        Path localDir = Path.of(project.getBasePath(), "resources", "libs", abi);
        if (!Files.isDirectory(localDir)) {
            // 初始化不完整或架构不受支持时明确指出期望目录。
            console.error("未找到设备架构资源目录：" + localDir);
            return;
        }
        List<Path> localLibraries;
        try (var stream = Files.list(localDir)) {
            // 只同步当前架构目录第一层的 .so，并按名称稳定排序。
            localLibraries = stream.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().endsWith(".so"))
                    .sorted()
                    .toList();
        } catch (IOException error) {
            // 本地扫描失败时不执行任何设备写入。
            console.error("扫描本地动态库失败：" + error.getMessage());
            return;
        }
        indicator.setText("扫描设备临时目录");
        String script = "mkdir -p '" + shellEscape(remoteDir) + "'; "
                + "for f in '" + shellEscape(remoteDir) + "'/*.so; do "
                + "[ -f \"$f\" ] && stat -c '%n:%s' \"$f\"; done";
        CommandResult remoteResult = execute(List.of(adb, "-s", device, "shell", "sh", "-c", script), 20);
        if (remoteResult.exitCode != 0) {
            // 远端扫描失败时停止同步，避免把权限问题误判为文件缺失。
            console.error("扫描设备动态库失败：" + remoteResult.output);
            return;
        }
        Map<String, Long> remoteSizes = parseRemoteSizes(remoteResult.output);
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        for (int index = 0; index < localLibraries.size(); index++) {
            // 每个文件先比较名称和大小，仅对差异文件执行 adb push。
            if (indicator.isCanceled()) {
                console.info("资源同步已取消。");
                return;
            }
            Path library = localLibraries.get(index);
            String name = library.getFileName().toString();
            long localSize;
            try {
                localSize = Files.size(library);
            } catch (IOException error) {
                // 单个文件读取失败不影响其余文件，最终汇总失败数。
                console.error("读取文件大小失败：" + library + "，" + error.getMessage());
                failed++;
                continue;
            }
            indicator.setFraction(localLibraries.isEmpty() ? 1.0 : (double) index / localLibraries.size());
            indicator.setText("同步 " + name);
            if (remoteSizes.getOrDefault(name, -1L) == localSize) {
                // 名称和大小一致时跳过设备写入。
                console.info("已存在：" + name);
                skipped++;
                continue;
            }
            CommandResult pushResult = execute(List.of(adb, "-s", device, "push",
                    library.toString(), remoteDir + "/" + name), 120);
            if (pushResult.exitCode == 0) {
                // 推送成功立即记录，方便定位同步进度。
                console.info("已更新：" + name);
                updated++;
            } else {
                // 单文件失败后继续同步其他文件，并保留错误输出。
                console.error("推送失败：" + name + "，" + pushResult.output);
                failed++;
            }
        }
        indicator.setFraction(1.0);
        console.info("资源同步完成：架构=" + abi + "，更新=" + updated
                + "，已存在=" + skipped + "，失败=" + failed);
    }

    static String normalizeAbi(String abi) {
        // AG 项目使用 x86_64 目录名，兼容界面中常见的 x86-64 写法。
        return switch (abi) {
            case "x86-64", "x86_64" -> "x86_64";
            default -> abi;
        };
    }

    static Map<String, Long> parseRemoteSizes(String output) {
        // 将 stat 的绝对路径和大小转换为按文件名查询的映射。
        Map<String, Long> sizes = new HashMap<>();
        for (String line : output.split("\\R")) {
            // 从末尾冒号切分，避免路径中的其他字符影响解析。
            int separator = line.lastIndexOf(':');
            if (separator <= 0) {
                continue;
            }
            try {
                Path path = Path.of(line.substring(0, separator));
                sizes.put(path.getFileName().toString(), Long.parseLong(line.substring(separator + 1).trim()));
            } catch (RuntimeException ignored) {
                // 无法解析的设备输出行不参与存在性判断。
            }
        }
        return sizes;
    }

    private static CommandResult execute(List<String> command, int timeoutSeconds) {
        // 同步内部命令需要读取结果后决定下一步，因此使用有超时的阻塞执行。
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // 超时立即终止，避免后台任务和 ADB 进程泄漏。
                process.destroyForcibly();
                return new CommandResult(-1, "命令超时：" + String.join(" ", command));
            }
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            return new CommandResult(process.exitValue(), output.toString().trim());
        } catch (IOException | InterruptedException error) {
            // 中断状态必须恢复，IOException 作为命令失败返回。
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(-1, error.getMessage());
        } finally {
            // 异常路径清理仍在运行的子进程。
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String shellEscape(String value) {
        // 远端目录嵌入单引号 shell 字符串时转义单引号。
        return value.replace("'", "'\\''");
    }

    private record CommandResult(int exitCode, String output) {
        // 不可变结果同时携带退出码和合并输出。
    }
}
