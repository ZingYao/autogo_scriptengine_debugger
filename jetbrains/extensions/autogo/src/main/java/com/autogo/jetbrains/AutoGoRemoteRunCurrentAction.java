package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/** 增量同步并远程运行当前 Lua/GLua/JavaScript 文件。 */
public final class AutoGoRemoteRunCurrentAction extends DumbAwareAction {
    /** 创建当前脚本远程运行动作。 */
    public AutoGoRemoteRunCurrentAction() {
        // 与快速调试区分：此入口不创建 DAP 会话。
        super("远程运行当前脚本", "同步当前脚本 require 闭包并在移动端运行", AutoGoIcons.RUN);
    }

    /** 保存当前文件并启动增量同步。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // update 已限制文件类型，此处仍检查项目边界避免空事件。
        Project project = event.getProject();
        VirtualFile file = AutoGoActionFiles.currentFile(event);
        if (project == null || file == null) {
            return;
        }
        FileDocumentManager.getInstance().saveAllDocuments();
        project.getService(AutoGoRemoteEngineService.class).syncAndRun(Path.of(file.getPath()), false);
    }

    /** 仅对当前 Lua/GLua 文件显示。 */
    @Override
    public void update(@NotNull AnActionEvent event) {
        // 目录、GLuac 产物和普通文件不属于源码增量同步入口。
        VirtualFile file = AutoGoActionFiles.currentFile(event);
        String extension = file == null ? null : file.getExtension();
        boolean enabled = event.getProject() != null && extension != null
                && ("lua".equalsIgnoreCase(extension) || "glua".equalsIgnoreCase(extension));
        event.getPresentation().setEnabledAndVisible(enabled);
    }
}
