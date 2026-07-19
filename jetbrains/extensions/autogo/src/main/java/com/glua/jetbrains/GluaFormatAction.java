package com.glua.jetbrains;

import com.autogo.jetbrains.AutoGoActionFiles;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

public final class GluaFormatAction extends AnAction {
    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (file == null && project != null && AutoGoActionFiles.currentFile(event) != null) {
            file = PsiManager.getInstance(project).findFile(AutoGoActionFiles.currentFile(event));
        }
        event.getPresentation().setEnabledAndVisible(file != null && file.getFileType() == GluaFileType.INSTANCE);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Document document = AutoGoActionFiles.currentEditor(event) == null
                ? null : AutoGoActionFiles.currentEditor(event).getDocument();
        PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (file == null && project != null && AutoGoActionFiles.currentFile(event) != null) {
            file = PsiManager.getInstance(project).findFile(AutoGoActionFiles.currentFile(event));
        }
        if (project == null || document == null || file == null || file.getFileType() != GluaFileType.INSTANCE) {
            return;
        }
        String formatted = GluaFormatter.format(document.getText());
        WriteCommandAction.runWriteCommandAction(project, "Format GLua File", null, () ->
            document.replaceString(0, document.getTextLength(), formatted)
        );
    }
}
