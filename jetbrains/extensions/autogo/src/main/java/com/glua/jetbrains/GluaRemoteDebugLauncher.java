package com.glua.jetbrains;

import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationType;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.openapi.project.Project;

/** 为 AutoGo 已同步脚本创建 IDEA 原生 XDebugger 远程 DAP 会话。 */
public final class GluaRemoteDebugLauncher {
    private GluaRemoteDebugLauncher() {
        // 调试启动器只提供无状态静态入口。
    }

    /** 连接调用方提供的项目专属 DAP 转发端点。 */
    public static void launch(Project project, String localProgram, String host, int port) {
        // 每个项目使用独立本地端口，避免多项目、多设备调试会话串线。
        launchConfiguration(project, localProgram, host, port);
    }

    /** 兼容用户手工创建的默认远程配置。 */
    public static void launch(Project project, String localProgram) {
        // 默认入口仍使用参考 go-lua-vm 的内部 DAP 地址。
        launchConfiguration(project, localProgram, "127.0.0.1", 38697);
    }

    private static void launchConfiguration(Project project, String localProgram, String host, int port) {
        // 从扩展点取得已注册类型，避免直接构造与平台注册表脱节的配置。
        GluaDapRunConfigurationType type = null;
        for (ConfigurationType candidate : ConfigurationType.CONFIGURATION_TYPE_EP.getExtensionList()) {
            if (candidate instanceof GluaDapRunConfigurationType gluaType) {
                type = gluaType;
                break;
            }
        }
        if (type == null) {
            throw new IllegalStateException("GLua DAP Run Configuration 未注册");
        }
        GluaDapRunConfigurationFactory factory =
                (GluaDapRunConfigurationFactory) type.getConfigurationFactories()[0];
        GluaDapRunConfiguration configuration = new GluaDapRunConfiguration(
                project, factory, "AutoGo Debug " + java.nio.file.Path.of(localProgram).getFileName());
        configuration.setProgram(localProgram.replace('\\', '/'));
        configuration.setUseRemoteDap(true);
        configuration.setDapHost(host);
        configuration.setDapPort(port);
        configuration.setAutoGoManaged(true);
        configuration.prepareAutoGoLaunch();
        configuration.setAllowRunningInParallel(true);
        RunnerAndConfigurationSettings settings = RunManager.getInstance(project)
                .createConfiguration(configuration, factory);
        RunManager.getInstance(project).addConfiguration(settings);
        RunManager.getInstance(project).setSelectedConfiguration(settings);
        ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance());
    }
}
