package com.autogo.jetbrains;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/**
 * 创建 IDEA 内部的 AutoGo Node Assistant JCEF 工具窗口。
 */
public final class AutoGoNodeAssistantToolWindowFactory implements ToolWindowFactory, DumbAware {
    /** 创建内嵌浏览器；JCEF 不可用时展示降级说明。 */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 根据当前 IDE 运行时能力选择浏览器或提示组件。
        JComponent component;
        if (JBCefApp.isSupported()) {
            JBCefBrowser browser = new JBCefBrowser();
            project.getService(AutoGoNodeAssistantService.class).bindBrowser(browser);
            component = browser.getComponent();
        } else {
            // 无 JCEF 的精简 IDE 仍可由动作层打开系统浏览器。
            component = new JBLabel("当前 IDE 运行时不支持 JCEF，节点助手将使用系统浏览器打开。");
        }
        Content content = ContentFactory.getInstance().createContent(component, "", false);
        toolWindow.getContentManager().addContent(content);
    }
}
