package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/** 解析编辑器动作当前文件，兼容 Console 工具栏的数据上下文。 */
public final class AutoGoActionFiles {
    private AutoGoActionFiles() {
        // 纯工具类不允许实例化。
    }

    /** 返回事件文件；工具窗口触发时回退到主编辑器当前文件。 */
    public static @Nullable VirtualFile currentFile(AnActionEvent event) {
        // 编辑器菜单和快捷键优先使用事件携带的精确文件。
        VirtualFile file = event.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file != null) {
            return file;
        }
        Project project = event.getProject();
        if (project == null) {
            // 无项目事件不能解析编辑文件。
            return null;
        }
        VirtualFile[] selectedFiles = FileEditorManager.getInstance(project).getSelectedFiles();
        return selectedFiles.length == 0 ? null : selectedFiles[0];
    }

    /** 返回事件编辑器；工具窗口触发时回退到主编辑器。 */
    public static @Nullable Editor currentEditor(AnActionEvent event) {
        // 工具栏事件缺少 EDITOR，因此必须通过项目文件管理器读取。
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (editor != null) {
            return editor;
        }
        Project project = event.getProject();
        return project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
    }
}
