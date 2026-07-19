package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 创建底部 AutoGo Script Engine Console 工具窗口和顶部横向快捷工具栏。
 */
public final class AutoGoToolWindowFactory implements ToolWindowFactory, DumbAware {
    private static final int CLEAR_ALL_HOLD_MILLIS = 600;

    /** 创建工具窗口内容并输出当前 SDK 版本。 */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 将项目唯一控制台嵌入工具窗口，避免重复创建运行标签。
        AutoGoConsoleService service = project.getService(AutoGoConsoleService.class);
        service.bindToolWindow(toolWindow);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(createToolbar(project), BorderLayout.NORTH);
        panel.add(service.getComponent(), BorderLayout.CENTER);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        service.info("AutoGo Script Engine Console 已就绪，官方文档：https://zingyao.github.io/autogo_scriptengine/");
        project.getService(AutoGoProcessService.class).runAg(List.of("version"));
    }

    private static JComponent createToolbar(Project project) {
        // 工具栏直接复用顶部菜单注册的完整动作集，保证能力数量和行为一致。
        DefaultActionGroup group = new DefaultActionGroup();
        addRegistered(group, "AutoGo.QuickDebug");
        addRegistered(group, "AutoGo.Run");
        addRegistered(group, "AutoGo.Stop");
        addRegistered(group, "AutoGo.RemoteEngine");
        addRegistered(group, "AutoGo.SyncResources");
        addRegistered(group, "AutoGo.NodeServe");
        group.addSeparator();
        addRegistered(group, "AutoGo.BuildMenu");
        addRegistered(group, "AutoGo.GluaToolsMenu");
        addRegistered(group, "AutoGo.DevicesMenu");
        addRegistered(group, "AutoGo.SwitchToWireless");
        addRegistered(group, "AutoGo.PairWireless");
        addRegistered(group, "AutoGo.InitMenu");
        addRegistered(group, "AutoGo.PushFile");
        group.addSeparator();
        addRegistered(group, "AutoGo.OfficialDocs");
        addRegistered(group, "AutoGo.ApplyEngineConfig");
        addRegistered(group, "AutoGo.Settings");
        addRegistered(group, "AutoGo.CheckUpdate");
        group.addSeparator();
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true);
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        toolbar.setTargetComponent(console.getComponent());

        // 独立尾部区域持续展示引擎状态，并为清空按钮提供短按与长按两种手势。
        JPanel row = new JPanel(new BorderLayout());
        row.add(toolbar.getComponent(), BorderLayout.CENTER);
        JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JLabel engineState = new JLabel();
        updateEngineState(engineState, project.getService(AutoGoRemoteEngineService.class).getCachedState());
        trailing.add(engineState);
        trailing.add(createClearLogsButton(console));
        row.add(trailing, BorderLayout.EAST);

        // 状态探测由项目服务负责；Swing 定时器只读取内存快照，不在 EDT 执行 ADB 或网络操作。
        Timer stateTimer = new Timer(1_000, ignored -> updateEngineState(
                engineState,
                project.getService(AutoGoRemoteEngineService.class).getCachedState()));
        stateTimer.start();
        Disposer.register(project, stateTimer::stop);
        return row;
    }

    private static JButton createClearLogsButton(AutoGoConsoleService console) {
        // 短按清空当前分区，长按清空全部分区，交互与 VSCode 保持一致。
        JButton button = new JButton(AutoGoIcons.CLEAR);
        button.setToolTipText("清空当前日志；长按清空全部日志");
        button.setFocusable(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.addMouseListener(new MouseAdapter() {
            private final Timer holdTimer = createHoldTimer();
            private boolean clearedAll;

            private Timer createHoldTimer() {
                // 单次计时达到阈值后立即清空全部日志，释放鼠标时不再重复执行短按行为。
                Timer timer = new Timer(CLEAR_ALL_HOLD_MILLIS, ignored -> {
                    clearedAll = true;
                    console.clearAll();
                });
                timer.setRepeats(false);
                return timer;
            }

            @Override
            public void mousePressed(MouseEvent event) {
                // 只响应主鼠标键，避免右键菜单或触控板辅助点击误清日志。
                if (!ApplicationManager.getApplication().isUnitTestMode()
                        && event.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                clearedAll = false;
                holdTimer.restart();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                // 未达到长按阈值时执行当前分区清理；长按完成后仅结束本次手势。
                if (!holdTimer.isRunning() && !clearedAll) {
                    return;
                }
                holdTimer.stop();
                if (!clearedAll) {
                    console.clear();
                }
            }
        });
        return button;
    }

    static void updateEngineState(JLabel label, String state) {
        // 空状态使用“未知”，其余状态原样展示，便于与扩展日志和 VSCode 状态快速对照。
        String visibleState = state == null || state.isBlank() ? "未知" : state;
        label.setText("移动端引擎：" + visibleState);
        label.setToolTipText("移动端引擎状态：" + visibleState);
    }

    private static void addRegistered(DefaultActionGroup group, String actionId) {
        // plugin.xml 注册异常时跳过缺失项，避免整个工具窗口初始化失败。
        AnAction action = ActionManager.getInstance().getAction(actionId);
        if (action != null) {
            group.add(action);
        }
    }
}
