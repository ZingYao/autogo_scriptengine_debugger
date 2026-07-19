package com.autogo.jetbrains;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.jcef.JBCefApp;
import com.intellij.ui.jcef.JBCefBrowser;

/**
 * 管理项目级节点助手内嵌浏览器，并支持切换设备后重新加载 URL。
 */
@Service(Service.Level.PROJECT)
public final class AutoGoNodeAssistantService implements Disposable {
    static final String TOOL_WINDOW_ID = "AutoGo Node Assistant";
    private final Project project;
    private JBCefBrowser browser;
    private String pendingUrl = "http://127.0.0.1:8801/";

    /** 创建节点助手服务。 */
    public AutoGoNodeAssistantService(Project project) {
        // 保存项目用于定位静态注册的工具窗口。
        this.project = project;
    }

    /** 判断当前 IDE 运行时是否支持 JCEF。 */
    public boolean isBrowserSupported() {
        // JCEF 不可用时动作层降级到系统浏览器。
        return JBCefApp.isSupported();
    }

    /** 绑定工具窗口中创建的 JCEF 浏览器。 */
    public void bindBrowser(JBCefBrowser browser) {
        // 工具窗口首次初始化后立即加载最近请求的设备 URL。
        this.browser = browser;
        browser.loadURL(pendingUrl);
    }

    /** 在 IDEA 内部打开节点助手 URL。 */
    public void open(String url) {
        // 所有工具窗口和浏览器操作都切换到 EDT。
        pendingUrl = url;
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                // 项目已关闭时不再访问 UI。
                return;
            }
            ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
            if (toolWindow == null) {
                // 插件注册异常时由 Console 提示，不抛出 UI 异常。
                project.getService(AutoGoConsoleService.class).error("未找到节点助手工具窗口。");
                return;
            }
            toolWindow.show(() -> {
                // 工具窗口初始化完成后加载带设备参数的页面。
                if (browser != null) {
                    browser.loadURL(pendingUrl);
                }
            });
        });
    }

    /** 项目关闭时释放 JCEF 资源。 */
    @Override
    public void dispose() {
        // JBCefBrowser 持有原生 Chromium 资源，必须显式释放。
        if (browser != null) {
            browser.dispose();
            browser = null;
        }
    }
}
