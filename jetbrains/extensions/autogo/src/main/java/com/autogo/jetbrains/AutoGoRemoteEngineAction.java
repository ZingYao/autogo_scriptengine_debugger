package com.autogo.jetbrains;

import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

/** 根据移动端真实健康状态动态展示“启动”或“重启”脚本引擎。 */
public final class AutoGoRemoteEngineAction extends DumbAwareAction {
    /** 创建动态移动端引擎动作。 */
    public AutoGoRemoteEngineAction() {
        // 初始状态未知时使用启动文案，首次后台刷新后自动更新。
        super("启动移动端引擎", "探测并启动或重启移动端 AutoGo 脚本引擎", AutoGoIcons.ENGINE);
    }

    /** 执行真实状态探测后的启动或重启。 */
    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        // 没有项目时动作不可执行，update 已负责隐藏无效入口。
        Project project = event.getProject();
        if (project != null) {
            project.getService(AutoGoRemoteEngineService.class).startOrRestart();
        }
    }

    /** 使用缓存状态更新按钮，并异步刷新设备真实状态。 */
    @Override
    public void update(@NotNull AnActionEvent event) {
        // update 不能做 ADB/HTTP 阻塞操作，只消费服务缓存。
        Project project = event.getProject();
        event.getPresentation().setEnabled(project != null);
        if (project == null) {
            return;
        }
        AutoGoRemoteEngineService service = project.getService(AutoGoRemoteEngineService.class);
        applyText(event, service.getCachedState());
        service.refresh(ignored -> event.getPresentation().setText(
                isStarted(service.getCachedState()) ? "重启移动端引擎" : "启动移动端引擎"));
    }

    private static void applyText(AnActionEvent event, String state) {
        // running/paused 均代表已有引擎实例，按钮行为必须切换为重启。
        event.getPresentation().setText(isStarted(state) ? "重启移动端引擎" : "启动移动端引擎");
        event.getPresentation().setDescription("移动端引擎状态：" + state);
    }

    private static boolean isStarted(String state) {
        // 只有可继续工作的状态显示重启；failed/stopped/unreachable 均显示启动。
        return "running".equals(state) || "paused".equals(state) || "starting".equals(state);
    }

    /** 动作更新只读取内存快照，可安全在后台更新线程执行。 */
    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        // BGT 避免平台把潜在项目服务初始化放到 EDT。
        return ActionUpdateThread.BGT;
    }
}
