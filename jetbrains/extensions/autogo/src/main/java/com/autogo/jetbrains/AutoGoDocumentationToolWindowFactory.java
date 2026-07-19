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

/** 创建 IDEA 内部的 AutoGo 官方文档工具窗口。 */
public final class AutoGoDocumentationToolWindowFactory implements ToolWindowFactory, DumbAware {
    /** 创建内嵌官方文档浏览器。 */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 支持 JCEF 时嵌入网页，否则给出明确的运行时限制说明。
        JComponent component;
        if (JBCefApp.isSupported()) {
            JBCefBrowser browser = new JBCefBrowser();
            project.getService(AutoGoDocumentationService.class).bindBrowser(browser);
            component = browser.getComponent();
        } else {
            // 官方文档按产品要求不自动跳出 IDEA。
            component = new JBLabel("当前 IDE 运行时不支持 JCEF，无法在 IDEA 内显示官方文档。");
        }
        Content content = ContentFactory.getInstance().createContent(component, "官方文档", false);
        toolWindow.getContentManager().addContent(content);
    }
}
