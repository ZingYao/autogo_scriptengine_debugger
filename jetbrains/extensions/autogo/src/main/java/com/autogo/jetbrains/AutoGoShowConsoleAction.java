package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.jetbrains.annotations.NotNull;

/** 显示 AutoGo Script Engine Console，供动作搜索和菜单直接调用。 */
public final class AutoGoShowConsoleAction extends DumbAwareAction {
    private static final String TOOL_WINDOW_ID = "AutoGo Script Engine Console";

    /** 创建带统一名称和图标的 Console 动作。 */
    public AutoGoShowConsoleAction() {
        // 动作名称可通过 IDEA 的“查找操作”检索。
        super("显示 AutoGo Script Engine Console", "打开 AutoGo 底部工具窗口", AutoGoIcons.LOGO);
    }

    /** 显示并激活项目级 AutoGo 工具窗口。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // 没有打开项目时不存在项目级工具窗口，直接结束。
        Project project = event.getProject();
        if (project == null || project.isDisposed()) {
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            // 注册异常需要在用户可见的扩展日志中保留诊断。
            project.getService(AutoGoConsoleService.class).error("未找到 AutoGo Script Engine Console 工具窗口。");
            return;
        }
        toolWindow.show();
    }
}
