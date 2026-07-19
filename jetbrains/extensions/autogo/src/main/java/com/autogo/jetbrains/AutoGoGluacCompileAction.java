package com.autogo.jetbrains;

import com.glua.jetbrains.GluaSettings;
import com.glua.jetbrains.GluaCompilerExecutable;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** 把当前 Lua/GLua 文件编译为带目标版本隔离的 GLuac 产物。 */
public class AutoGoGluacCompileAction extends DumbAwareAction {
    private static final String VERSION_PATTERN = "[A-Za-z0-9][A-Za-z0-9._+\\-]*";
    private final boolean remoteRun;
    private final boolean remoteDebug;

    /** 创建 GLuac 编译动作。 */
    public AutoGoGluacCompileAction() {
        // 产物默认保留调试信息，供后续远程执行与 DAP 调试复用。
        this("编译当前文件为 GLuac", false, false);
    }

    /** 创建可复用的编译、远程运行或远程调试动作。 */
    protected AutoGoGluacCompileAction(String text, boolean remoteRun, boolean remoteDebug) {
        // 三个入口共享完全一致的版本校验和编译产物规则。
        super(text, "编译当前 Lua/GLua 文件并保留调试信息", AutoGoIcons.GLUA);
        this.remoteRun = remoteRun;
        this.remoteDebug = remoteDebug;
    }

    /** 仅在当前编辑器是 Lua 或 GLua 文件时启用。 */
    @Override
    public void update(@NotNull AnActionEvent event) {
        // 扩展名判断避免对普通文本文件展示不可执行入口。
        VirtualFile file = AutoGoActionFiles.currentFile(event);
        String extension = file == null ? null : file.getExtension();
        boolean enabled = extension != null
                && ("lua".equalsIgnoreCase(extension) || "glua".equalsIgnoreCase(extension));
        event.getPresentation().setEnabledAndVisible(enabled);
    }

    /** 收集必填版本、保存当前文档并启动 gluac。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // 编译必须有项目、当前文件和合法目标版本。
        Project project = event.getProject();
        VirtualFile file = AutoGoActionFiles.currentFile(event);
        if (project == null || project.getBasePath() == null || file == null) {
            return;
        }
        String version = Messages.showInputDialog(project,
                "请输入目标 GLua 运行时版本号。不同版本的字节码会分别保存：",
                "编译当前文件为 GLuac", Messages.getQuestionIcon());
        if (version == null) {
            // 用户取消时不创建构建目录或启动进程。
            return;
        }
        version = version.trim();
        if (!version.matches(VERSION_PATTERN)) {
            // 版本同时进入目录名，必须拒绝路径分隔符和空白。
            Messages.showErrorDialog(project,
                    "版本号只能包含字母、数字、点、下划线、加号和连字符。",
                    "编译当前文件为 GLuac");
            return;
        }
        if (AutoGoActionFiles.currentEditor(event) != null) {
            // 先保存编辑器未落盘内容，确保编译产物与用户当前看到的源码一致。
            FileDocumentManager.getInstance().saveDocument(AutoGoActionFiles.currentEditor(event).getDocument());
        }

        GluaSettings gluaSettings = ApplicationManager.getApplication().getService(GluaSettings.class);
        String executable;
        try {
            // 未配置外部工具时直接使用随插件分发的当前平台 GLuac。
            executable = GluaCompilerExecutable.resolve(gluaSettings.gluacExecutable()).toString();
            if (gluaSettings.gluacExecutable().isBlank()) {
                // 把实际解析路径写回设置，使设置页能够清晰回显当前使用的工具。
                gluaSettings.setGluacExecutable(executable);
            }
        } catch (IOException error) {
            // 解析失败时引导用户选择可执行文件，并展示具体原因。
            Messages.showErrorDialog(project,
                    "未找到可用的 gluac，请在 AutoGo 设置的 GLua 页面选择文件。\n" + error.getMessage(),
                    "编译当前文件为 GLuac");
            return;
        }

        Path source = Path.of(file.getPath()).toAbsolutePath().normalize();
        Path outputDir = Path.of(project.getBasePath(), ".autogo", "build", "gluac", version);
        String baseName = source.getFileName().toString().replaceFirst("\\.(?i:glua|lua)$", "");
        Path output = outputDir.resolve(baseName + ".luac");
        try {
            Files.createDirectories(outputDir);
        } catch (IOException error) {
            // 构建目录创建失败时不启动编译器。
            project.getService(AutoGoConsoleService.class).error("无法创建 GLuac 构建目录：" + error.getMessage());
            return;
        }
        String finalVersion = version;
        String finalExecutable = executable;
        project.getService(AutoGoProcessService.class).run(finalExecutable,
                List.of("-o", output.toString(), source.toString()), true, exitCode -> {
                    if (exitCode != 0) {
                        // gluac 已把语法或写入错误输出到统一 Console。
                        project.getService(AutoGoConsoleService.class).error("GLuac 编译失败，未生成可用产物。");
                        return;
                    }
                    try {
                        writeMetadata(output, source, finalVersion);
                        project.getService(AutoGoConsoleService.class)
                                .info("GLuac 编译完成：" + output + "（保留调试信息）");
                        if (remoteRun) {
                            // 仅在字节码与 sidecar 都成功生成后进入远程同步，禁止运行旧产物。
                            project.getService(AutoGoRemoteEngineService.class)
                                    .syncAndRunArtifact(output, source, finalVersion, remoteDebug);
                        }
                    } catch (IOException error) {
                        // 元数据失败不删除有效字节码，但必须提示版本追踪不完整。
                        project.getService(AutoGoConsoleService.class)
                                .error("GLuac 已生成，但写入版本元数据失败：" + error.getMessage());
                    }
                });
    }

    /** 原子写入产物版本、源码和哈希元数据。 */
    private static void writeMetadata(Path output, Path source, String version) throws IOException {
        // sidecar 与字节码同名，后续远程运行先检查版本和完整性。
        String metadata = "{\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"runtimeVersion\": \"" + jsonEscape(version) + "\",\n"
                + "  \"source\": \"" + jsonEscape(source.toString().replace('\\', '/')) + "\",\n"
                + "  \"artifactSha256\": \"" + sha256(Files.readAllBytes(output)) + "\",\n"
                + "  \"debugInfo\": true\n"
                + "}\n";
        Path target = output.resolveSibling(output.getFileName() + ".json");
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, metadata, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // 写入或移动失败时不残留临时 sidecar。
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(byte[] content) {
        // GLuac 远程上传前使用同一 SHA-256 校验产物。
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder value = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                value.append(String.format("%02x", item));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException error) {
            // 标准 Java 运行时必须支持 SHA-256。
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String jsonEscape(String value) {
        // sidecar 仅需要标准字符串转义。
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
