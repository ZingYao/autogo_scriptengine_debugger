package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import org.jetbrains.annotations.NotNull;

/** 根据当前编辑文件控制 GLua 工具菜单状态。 */
public final class AutoGoGluaToolsGroup extends DefaultActionGroup {
    /** 保持工具组入口可用，具体子动作再根据当前文件控制状态。 */
    @Override
    public void update(@NotNull AnActionEvent event) {
        // Console 工具栏的数据上下文不包含编辑器文件；禁用父组会导致所有子入口均无法访问。
        event.getPresentation().setEnabledAndVisible(event.getProject() != null);
    }
}
