package com.autogo.jetbrains;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.jcef.JBCefBrowser;

/** 管理项目级 AutoGo 官方文档内嵌浏览器。 */
@Service(Service.Level.PROJECT)
public final class AutoGoDocumentationService implements Disposable {
    static final String TOOL_WINDOW_ID = "AutoGo Documentation";
    private final Project project;
    private JBCefBrowser browser;
    private String pendingUrl = "https://zingyao.github.io/autogo_scriptengine/";

    /** 创建官方文档服务。 */
    public AutoGoDocumentationService(Project project) {
        // 保存项目用于访问静态注册的工具窗口。
        this.project = project;
    }

    /** 绑定工具窗口创建的浏览器。 */
    public void bindBrowser(JBCefBrowser browser) {
        // 首次显示时直接加载最近请求地址。
        this.browser = browser;
        browser.loadURL(pendingUrl);
    }

    /** 在 IDEA 内打开官方文档。 */
    public void open(String url) {
        // JCEF 与 ToolWindow 操作必须运行在 EDT。
        pendingUrl = url;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                // 项目关闭后忽略迟到的打开请求。
                return;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow == null) {
                // 注册异常写入插件控制台，避免静默失败。
                project.getService(AutoGoConsoleService.class).error("未找到官方文档工具窗口。");
                return;
            }
            toolWindow.show(() -> {
                // 工具窗口初始化完成后加载最新地址。
                if (browser != null) {
                    browser.loadURL(pendingUrl);
                }
            });
        });
    }

    /** 项目关闭时释放 Chromium 原生资源。 */
    @Override
    public void dispose() {
        // 浏览器实例不可跨项目复用。
        if (browser != null) {
            browser.dispose();
            browser = null;
        }
    }
}
