package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查并更新 AG 可执行文件，下载成功后备份旧版本并原子替换。
 */
public final class AutoGoUpdateAction extends DumbAwareAction {
    private static final int MAX_DOWNLOAD_BYTES = 256 * 1024 * 1024;
    private static final String CHANGELOG_URL =
            "https://autogo-1257133387.cos.ap-shanghai.myqcloud.com/changelog.md";
    private static final String SDK_BASE_URL = "http://168.138.164.80:7001/files/AutoGo/sdk/";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(?m)^## \\[([^]]+)]");

    /** 创建带更新图标的动作。 */
    public AutoGoUpdateAction() {
        super("检查更新", "检查并更新 AG 命令", AutoGoIcons.UPDATE);
    }

    /** 在后台检查版本并在用户确认后安装。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // 更新操作需要项目 Console 展示完整过程。
        Project project = event.getProject();
        if (project == null) {
            return;
        }
        new Task.Backgroundable(project, "检查 AG 更新", true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // 网络与文件操作均在后台线程执行。
                update(project, indicator);
            }
        }.queue();
    }

    private static void update(Project project, ProgressIndicator indicator) {
        // 解析当前配置和安装路径，下载失败前不改动旧文件。
        AutoGoSettings settings = AutoGoMenuActions.settings();
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        String executable = AgExecutableResolver.resolve(settings.getAgPath(), System.getenv());
        Path agPath = Path.of(executable);
        if (!agPath.isAbsolute()) {
            // PATH 中的命令无法可靠原子替换，要求用户先配置真实文件路径。
            console.error("检查更新需要在设置中配置 AG 的绝对路径。");
            return;
        }
        indicator.setText("获取 AG 版本列表");
        String changelog;
        try {
            changelog = new String(download(CHANGELOG_URL, settings), StandardCharsets.UTF_8);
        } catch (IOException | IllegalArgumentException error) {
            // 更新日志失败不触碰本地安装。
            console.error("获取 AG 更新日志失败：" + error.getMessage());
            return;
        }
        Matcher matcher = VERSION_PATTERN.matcher(changelog);
        if (!matcher.find()) {
            // 版本格式异常时停止更新，避免拼接错误下载地址。
            console.error("AG 更新日志中未找到可用版本。");
            return;
        }
        String latest = matcher.group(1).trim();
        String current = currentVersion(agPath);
        console.info("当前 AG 版本：" + current + "，最新版本：" + latest);
        if (latest.equals(current)) {
            // 已是最新版时无需用户确认和文件写入。
            console.info("AG 已是最新版本。");
            return;
        }
        int[] answer = new int[1];
        ApplicationManager.getApplication().invokeAndWait(() -> answer[0] = Messages.showYesNoDialog(
                project, "发现 AG " + latest + "，是否更新？", "AutoGo Script Engine Console",
                Messages.getQuestionIcon()));
        if (answer[0] != Messages.YES) {
            // 用户取消后保留当前版本。
            console.info("已取消 AG 更新。");
            return;
        }
        indicator.setText("下载 AG " + latest);
        Path temporary = agPath.resolveSibling(agPath.getFileName() + ".download");
        Path backup = agPath.resolveSibling(agPath.getFileName() + "_" + current);
        try {
            byte[] binary = download(SDK_BASE_URL + platformFile(latest), settings);
            Files.write(temporary, binary);
            makeExecutable(temporary);
            validateDownloadedBinary(temporary, latest);
            if (Files.exists(agPath)) {
                // 先复制备份，避免移动后替换失败导致主路径缺失。
                Files.copy(agPath, backup, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.move(temporary, agPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(Path.of(agPath + ".version"), latest, StandardCharsets.UTF_8);
            console.info("AG 更新完成：" + agPath + "（" + latest + "）");
        } catch (IOException | IllegalArgumentException error) {
            // 下载或替换失败时删除临时文件，原 AG 保持不变。
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 临时文件清理失败不覆盖主要错误。
            }
            console.error("AG 更新失败，当前版本保持不变：" + error.getMessage());
        }
    }

    private static String currentVersion(Path agPath) {
        // 优先读取版本文件，缺失时执行 ag version。
        Path versionFile = Path.of(agPath + ".version");
        try {
            if (Files.isRegularFile(versionFile)) {
                return Files.readString(versionFile).trim();
            }
            Process process = new ProcessBuilder(agPath.toString(), "version").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !output.isBlank() ? output.lines().findFirst().orElse("unknown") : "unknown";
        } catch (IOException | InterruptedException error) {
            // 版本探测失败时使用 unknown，仍允许用户覆盖安装。
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "unknown";
        }
    }

    private static byte[] download(String address, AutoGoSettings settings) throws IOException {
        // 所有插件下载统一使用设置中的网络代理。
        HttpURLConnection connection = AutoGoProxySupport.openHttpConnection(address, settings);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30 * 60_000);
        connection.setInstanceFollowRedirects(true);
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            // 非 200 响应直接失败，禁止把错误页当作可执行文件。
            throw new IOException("HTTP " + status + "：" + address);
        }
        try (var input = connection.getInputStream()) {
            byte[] content = input.readNBytes(MAX_DOWNLOAD_BYTES + 1);
            if (content.length > MAX_DOWNLOAD_BYTES) {
                // 更新日志和 AG 二进制都不允许无界占用 IDE 内存。
                throw new IOException("下载内容超过 256 MiB 上限：" + address);
            }
            return content;
        } finally {
            // 显式断开连接释放底层网络资源。
            connection.disconnect();
        }
    }

    static String platformFile(String version) {
        // 下载文件名与 AG 服务端平台命名规则保持一致。
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (os.contains("mac")) {
            return (arch.contains("aarch64") || arch.contains("arm64") ? "mac_arm_" : "mac_amd_") + version;
        }
        if (os.contains("win")) {
            return "win_x64_" + version;
        }
        return "linux_x64_" + version;
    }

    private static void validateDownloadedBinary(Path executable, String expectedVersion) throws IOException {
        // HTTP 下载结果必须能够独立执行且报告目标版本，才允许覆盖当前 AG。
        Process process = null;
        try {
            process = new ProcessBuilder(executable.toString(), "version").redirectErrorStream(true).start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                // 版本探测超时说明下载结果不可用。
                process.destroyForcibly();
                throw new IOException("下载的 AG 版本探测超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || !versionMatches(output, expectedVersion)) {
                // 拒绝错误页、损坏文件和服务端返回的错误版本。
                throw new IOException("下载的 AG 无法验证为版本 " + expectedVersion + "，输出：" + output);
            }
        } catch (InterruptedException error) {
            // IDE 关闭或任务取消时保留中断状态并停止安装。
            Thread.currentThread().interrupt();
            throw new IOException("AG 版本验证被中断", error);
        } finally {
            // 异常路径不得留下仍运行的未知下载程序。
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static boolean versionMatches(String output, String expectedVersion) {
        // 版本必须以独立数字边界出现，避免把 1.2 错认成 1.20。
        return output != null && expectedVersion != null && !expectedVersion.isBlank()
                && Pattern.compile("(?<![0-9A-Za-z])v?" + Pattern.quote(expectedVersion)
                + "(?![0-9A-Za-z])", Pattern.CASE_INSENSITIVE).matcher(output).find();
    }

    private static void makeExecutable(Path file) throws IOException {
        // Windows 不使用 POSIX 权限，其他平台设置标准可执行权限。
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE));
        }
    }
}
