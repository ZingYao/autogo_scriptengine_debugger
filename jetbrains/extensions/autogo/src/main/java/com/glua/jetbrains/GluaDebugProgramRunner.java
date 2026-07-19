package com.glua.jetbrains;

import com.autogo.jetbrains.AutoGoRemoteEngineService;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.GenericProgramRunner;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.xdebugger.XDebugProcessStarter;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;

public final class GluaDebugProgramRunner extends GenericProgramRunner<RunnerSettings> {
    public GluaDebugProgramRunner() {
        super();
    }

    @Override
    public @NotNull String getRunnerId() {
        return "GLuaDebugProgramRunner";
    }

    @Override
    public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
        return DefaultDebugExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof GluaDapRunConfiguration;
    }

    @Override
    protected @Nullable RunContentDescriptor doExecute(@NotNull RunProfileState state,
                                                       @NotNull ExecutionEnvironment environment) throws ExecutionException {
        RunProfile profile = environment.getRunProfile();
        if (!(profile instanceof GluaDapRunConfiguration configuration)) {
            throw new ExecutionException(GluaUiText.text("GLua DAP configuration is required.", "需要 GLua DAP 调试配置。"));
        }
        if (configuration.autoGoManaged()) {
            // 首次连接消费 AutoGo 一次性令牌；原生“重启”没有令牌，必须重新执行完整同步和引擎准备。
            AutoGoRemoteEngineService service = environment.getProject().getService(AutoGoRemoteEngineService.class);
            if (!configuration.consumePreparedAutoGoLaunch()) {
                FileDocumentManager.getInstance().saveAllDocuments();
                try {
                    service.syncAndRun(Path.of(configuration.program()), true);
                } catch (RuntimeException invalidProgram) {
                    // 损坏的持久化配置必须在原生 Debug 控制台明确失败，不得回退连接旧 DAP 端口。
                    throw new ExecutionException("AutoGo 调试入口无效：" + invalidProgram.getMessage(), invalidProgram);
                }
                return null;
            }
        }
        GluaDapLaunchProcessHandler localHandler = configuration.useRemoteDap()
            ? null
            : GluaDapLaunchProcessHandler.create(environment.getProject(), configuration.gluaExecutable(), configuration.program());
        GluaDapRemoteProcessHandler remoteHandler = configuration.useRemoteDap()
            ? new GluaDapRemoteProcessHandler(environment.getProject(), configuration.host(), configuration.port(), configuration.program())
            : null;
        XDebuggerManager.getInstance(environment.getProject()).startSessionAndShowTab(profile.getName(), new XDebugProcessStarter() {
            @Override
            public @NotNull GluaDebugProcess start(@NotNull XDebugSession session) {
                return localHandler != null
                    ? new GluaDebugProcess(session, localHandler)
                    : new GluaDebugProcess(session, remoteHandler);
            }
        }, environment);
        return null;
    }
}
