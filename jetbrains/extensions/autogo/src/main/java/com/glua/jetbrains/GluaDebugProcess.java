package com.glua.jetbrains;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.breakpoints.XBreakpointHandler;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider;
import com.intellij.xdebugger.frame.XSuspendContext;
import org.jetbrains.annotations.NotNull;

public final class GluaDebugProcess extends XDebugProcess {
    private final ProcessHandler processHandler;
    private final GluaDapClient dapHandler;
    private final XDebuggerEditorsProvider editorsProvider = new GluaDebuggerEditorsProvider();

    public GluaDebugProcess(@NotNull XDebugSession session, @NotNull GluaDapRemoteProcessHandler processHandler) {
        super(session);
        this.dapHandler = processHandler;
        this.processHandler = processHandler;
        processHandler.setDebugProcess(this);
        processHandler.setBreakpointsMuted(session.areBreakpointsMuted());
    }

    public GluaDebugProcess(@NotNull XDebugSession session, @NotNull GluaDapLaunchProcessHandler processHandler) {
        super(session);
        this.dapHandler = processHandler;
        this.processHandler = processHandler;
        processHandler.setDebugProcess(this);
        processHandler.setBreakpointsMuted(session.areBreakpointsMuted());
    }

    @Override
    public @NotNull XDebuggerEditorsProvider getEditorsProvider() {
        return editorsProvider;
    }

    @Override
    public XBreakpointHandler<?> @NotNull [] getBreakpointHandlers() {
        if (dapHandler == null) {
            return XBreakpointHandler.EMPTY_ARRAY;
        }
        return new XBreakpointHandler<?>[]{new GluaBreakpointHandler(dapHandler)};
    }

    @Override
    protected @NotNull ProcessHandler doGetProcessHandler() {
        return processHandler;
    }

    @Override
    public @NotNull ExecutionConsole createConsole() {
        ConsoleView console = TextConsoleBuilderFactory.getInstance()
            .createBuilder(getSession().getProject())
            .getConsole();
        console.attachToProcess(processHandler);
        return console;
    }

    @Override
    public void stop() {
        processHandler.destroyProcess();
    }

    @Override
    public void resume(@NotNull XSuspendContext context) {
        sendDebugCommand("continue");
    }

    @Override
    public void startStepOver(@NotNull XSuspendContext context) {
        sendDebugCommand("next");
    }

    @Override
    public void startStepInto(@NotNull XSuspendContext context) {
        sendDebugCommand("stepIn");
    }

    @Override
    public void startStepOut(@NotNull XSuspendContext context) {
        sendDebugCommand("stepOut");
    }

    @Override
    public void startPausing() {
        sendDebugCommand("pause");
    }

    void onStopped(@NotNull GluaDapStackFrame frame) {
        getSession().positionReached(new GluaSuspendContext(getSession().getProject(), frame, dapHandler));
    }

    void refreshVariables() {
        getSession().rebuildViews();
    }

    /** 将移动端 Lua 输出写入当前 JetBrains 原生 Debug 控制台。 */
    public void publishScriptOutput(@NotNull String text, boolean error) {
        // ProcessHandler 已绑定 createConsole 创建的 ConsoleView，统一通过平台输出事件更新原生控制台。
        String line = text.endsWith("\n") ? text : text + "\n";
        ApplicationManager.getApplication().invokeLater(() -> processHandler.notifyTextAvailable(
                line,
                error ? ProcessOutputTypes.STDERR : ProcessOutputTypes.STDOUT));
    }

    private void sendDebugCommand(@NotNull String command) {
        if (dapHandler != null) {
            if (!"pause".equals(command)) {
                dapHandler.setBreakpointsMuted(getSession().areBreakpointsMuted());
            }
            dapHandler.sendControlCommand(command);
            if (!"pause".equals(command)) {
                getSession().sessionResumed();
            }
        }
    }

    private static final class GluaBreakpointHandler extends XBreakpointHandler<XLineBreakpoint<GluaBreakpointProperties>> {
        private final GluaDapClient dapHandler;

        private GluaBreakpointHandler(@NotNull GluaDapClient dapHandler) {
            super(GluaLineBreakpointType.class);
            this.dapHandler = dapHandler;
        }

        @Override
        public void registerBreakpoint(@NotNull XLineBreakpoint<GluaBreakpointProperties> breakpoint) {
            dapHandler.syncBreakpointsAsync();
        }

        @Override
        public void unregisterBreakpoint(@NotNull XLineBreakpoint<GluaBreakpointProperties> breakpoint, boolean temporary) {
            dapHandler.syncBreakpointsAsync();
        }
    }
}
