package com.autogo.jetbrains;

import com.glua.jetbrains.GluaDebugProcess;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JTabbedPane;
import java.util.EnumMap;
import java.util.Map;

/**
 * 维护项目唯一的 AutoGo Console，集中承载命令、输出、状态和错误。
 */
@Service(Service.Level.PROJECT)
public final class AutoGoConsoleService implements Disposable {
    /** 与 VSCode Console 对齐的日志分区。 */
    public enum Channel {
        AG("AG 命令输出"),
        GO("Go 运行输出"),
        LUA("Lua 运行输出"),
        EXTENSION("扩展日志");

        private final String title;

        Channel(String title) {
            // 标题直接用于 IDEA 底部标签，保持两端文案一致。
            this.title = title;
        }
    }

    private final Project project;
    private final Map<Channel, ConsoleView> consoles = new EnumMap<>(Channel.class);
    private final JTabbedPane tabs = new JTabbedPane();
    private boolean tabsInitialized;
    private ToolWindow toolWindow;

    /** 创建项目级控制台。 */
    public AutoGoConsoleService(Project project) {
        // 每个项目只创建一个 ConsoleView，避免命令产生大量 Run 标签。
        this.project = project;
        for (Channel channel : Channel.values()) {
            // Console 允许在项目后台服务初始化；Swing 组件延迟到 EDT 首次展示时创建。
            ConsoleView console = TextConsoleBuilderFactory.getInstance().createBuilder(project).getConsole();
            consoles.put(channel, console);
        }
    }

    /** 绑定工具窗口，后续输出时可自动显示。 */
    public void bindToolWindow(@NotNull ToolWindow toolWindow) {
        // 工具窗口由平台创建后再注入，避免服务初始化顺序耦合。
        this.toolWindow = toolWindow;
    }

    /** 获取可嵌入工具窗口的控制台组件。 */
    public ConsoleView getConsole() {
        // 兼容既有调用方，默认返回扩展日志控制台。
        return consoles.get(Channel.EXTENSION);
    }

    /** 获取包含四个日志分区的控制台组件。 */
    public JComponent getComponent() {
        // 工具窗口只嵌入一次标签容器；后台调用时同步切到 EDT 完成 Swing 初始化。
        if (ApplicationManager.getApplication().isDispatchThread()) {
            ensureTabsInitialized();
        } else {
            ApplicationManager.getApplication().invokeAndWait(this::ensureTabsInitialized);
        }
        return tabs;
    }

    private void ensureTabsInitialized() {
        // 重复获取工具窗口组件时不得重复添加标签。
        if (tabsInitialized) {
            return;
        }
        for (Channel channel : Channel.values()) {
            tabs.addTab(channel.title, consoles.get(channel).getComponent());
        }
        tabsInitialized = true;
    }

    /** 输出普通信息。 */
    public void info(String text) {
        // 普通状态使用系统输出样式。
        print(Channel.EXTENSION, text, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /** 输出命令标准输出。 */
    public void stdout(String text) {
        // 标准输出保持普通控制台样式。
        print(Channel.EXTENSION, text, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /** 输出错误信息。 */
    public void error(String text) {
        // 错误使用醒目的错误输出样式。
        print(Channel.EXTENSION, text, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /** 输出指定分区的状态信息。 */
    public void info(Channel channel, String text) {
        // 命令和脚本生命周期使用系统输出样式。
        print(channel, text, ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    /** 静默输出指定分区的状态信息。 */
    public void infoWithoutShowing(Channel channel, String text) {
        // Debug 准备信息需要留档，但原生 Debug 控制台接管界面后不得再切回 AutoGo 工具窗口。
        print(channel, text, ConsoleViewContentType.SYSTEM_OUTPUT, false);
    }

    /** 输出指定分区的标准输出。 */
    public void stdout(Channel channel, String text) {
        // 普通执行结果保持白色控制台样式。
        print(channel, text, ConsoleViewContentType.NORMAL_OUTPUT);
    }

    /** 静默输出指定分区的标准输出。 */
    public void stdoutWithoutShowing(Channel channel, String text) {
        // 调试期间保留 AutoGo Lua 历史，但不得把用户从原生 Debug 控制台抢回工具窗口。
        print(channel, text, ConsoleViewContentType.NORMAL_OUTPUT, false);
    }

    /** 输出指定分区的错误。 */
    public void error(Channel channel, String text) {
        // 错误在所属分区内显示，避免用户跨分区寻找原因。
        print(channel, text, ConsoleViewContentType.ERROR_OUTPUT);
    }

    /** 静默输出指定分区的错误。 */
    public void errorWithoutShowing(Channel channel, String text) {
        // 错误仍写入所属日志分区，只抑制工具窗口自动激活。
        print(channel, text, ConsoleViewContentType.ERROR_OUTPUT, false);
    }

    /** 切换到指定日志分区。 */
    public void activate(Channel channel) {
        // UI 切换必须进入 EDT；运行和调试触发后立即展示 Lua 输出。
        ApplicationManager.getApplication().invokeLater(() -> tabs.setSelectedIndex(channel.ordinal()));
    }

    /** 清空当前控制台。 */
    public void clear() {
        // 用户主动清理时只移除文本，不影响运行中的进程。
        int selectedIndex = tabsInitialized ? tabs.getSelectedIndex() : Channel.EXTENSION.ordinal();
        ConsoleView selected = consoles.get(Channel.values()[Math.max(0, selectedIndex)]);
        if (selected != null) {
            selected.clear();
        }
    }

    /** 清空指定日志分区。 */
    public void clear(Channel channel) {
        // 运行前仅清理 Lua 历史输出，不影响 AG、Go 和扩展诊断记录。
        ConsoleView console = consoles.get(channel);
        if (console != null) {
            console.clear();
        }
    }

    /** 清空全部日志分区。 */
    public void clearAll() {
        // 逐个清理文本，不改变正在运行的进程与当前选中标签。
        consoles.values().forEach(ConsoleView::clear);
    }

    /** 显示 AutoGo 工具窗口。 */
    public void show() {
        // ToolWindow.show 必须在 EDT 执行；进程输出监听通常来自后台线程。
        ApplicationManager.getApplication().invokeLater(() -> {
            // 项目关闭或工具窗口尚未初始化时静默跳过，避免访问已释放 UI。
            if (project.isDisposed() || toolWindow == null) {
                return;
            }
            XDebugSession debugSession = XDebuggerManager.getInstance(project).getCurrentSession();
            if (debugSession != null && debugSession.getDebugProcess() instanceof GluaDebugProcess) {
                // 原生 GLua Debug 会话拥有前台焦点；AG、ADB、Lua 和扩展日志只能后台追加。
                return;
            }
            toolWindow.show();
        });
    }

    private void print(Channel channel, String text, ConsoleViewContentType type) {
        // 所有输出统一补齐换行，避免不同子进程内容粘连。
        print(channel, text, type, true);
    }

    private void print(Channel channel, String text, ConsoleViewContentType type, boolean revealToolWindow) {
        // 调试桥接输出允许只更新缓冲区，避免覆盖用户当前选中的原生 Debug 工具窗口。
        if (revealToolWindow) {
            show();
        }
        ConsoleView console = consoles.get(channel);
        if (console != null) {
            console.print(text.endsWith("\n") ? text : text + "\n", type);
        }
    }

    /** 项目关闭时释放控制台资源。 */
    @Override
    public void dispose() {
        // ConsoleView 持有编辑器和文档资源，必须随项目释放。
        consoles.values().forEach(ConsoleView::dispose);
    }
}
