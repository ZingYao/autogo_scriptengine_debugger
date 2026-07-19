package com.glua.jetbrains;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputType;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GluaDapLaunchProcessHandler extends OSProcessHandler implements GluaDapClient {
    static final String READY_PREFIX = "GLua DAP server listening on ";
    private static final Pattern CONTENT_LENGTH_PATTERN = Pattern.compile("(?i)^Content-Length:\\s*(\\d+)\\s*$");
    private final String commandText;
    private final String workDirectory;
    private final String listenAddress;
    private final Project project;
    private final String program;
    private final CountDownLatch readyOrExit = new CountDownLatch(1);
    private final AtomicBoolean clientStarted = new AtomicBoolean(false);
    private final StringBuilder stdoutTail = new StringBuilder();
    private final StringBuilder stderrTail = new StringBuilder();
    private volatile ReadyTarget readyTarget;
    private volatile Integer exitCode;
    private volatile Socket dapSocket;
    private volatile BufferedWriter dapWriter;
    private volatile GluaDebugProcess debugProcess;
    private volatile boolean breakpointsMuted;
    private volatile List<GluaDapVariable> latestVariables = List.of();
    private final Map<Integer, List<GluaDapVariable>> variableCache = new LinkedHashMap<>();
    private final Map<Integer, List<Consumer<List<GluaDapVariable>>>> variableCallbacks = new LinkedHashMap<>();
    private final Map<Integer, Integer> variablesRequestReferences = new LinkedHashMap<>();
    private final Map<Integer, Consumer<GluaDapSetVariableResult>> setVariableCallbacks = new LinkedHashMap<>();
    private final Map<Integer, Consumer<GluaDapEvaluateResult>> evaluateCallbacks = new LinkedHashMap<>();
    private final Set<String> syncedBreakpointSources = new LinkedHashSet<>();
    private int nextClientSeq = 1;

    private GluaDapLaunchProcessHandler(@NotNull Project project,
                                        @NotNull String program,
                                        @NotNull GeneralCommandLine commandLine,
                                        @NotNull String listenAddress) throws ExecutionException {
        super(commandLine);
        this.project = project;
        this.program = program;
        this.commandText = commandLine.getCommandLineString();
        this.workDirectory = commandLine.getWorkDirectory() == null ? "" : commandLine.getWorkDirectory().getAbsolutePath();
        this.listenAddress = listenAddress;
        addProcessListener(new ProcessAdapter() {
            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                exitCode = event.getExitCode();
                closeDapSocket();
                readyOrExit.countDown();
            }
        });
    }

    public void setDebugProcess(@NotNull GluaDebugProcess debugProcess) {
        this.debugProcess = debugProcess;
    }

    public void sendControlCommand(@NotNull String command) {
        BufferedWriter writer = dapWriter;
        if (writer == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                if (!"pause".equals(command)) {
                    sendBreakpoints(writer);
                }
                sendRequest(writer, command, "{}");
            } catch (IOException error) {
                notifyTextAvailable("GLua Debug command failed: " + readableError(error) + "\n", ProcessOutputTypes.STDERR);
            }
        }, "glua-dap-" + command);
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void setBreakpointsMuted(boolean muted) {
        breakpointsMuted = muted;
    }

    public void syncBreakpointsAsync() {
        BufferedWriter writer = dapWriter;
        if (writer == null) {
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                sendBreakpoints(writer);
            } catch (IOException error) {
                notifyTextAvailable("GLua breakpoint sync failed: " + readableError(error) + "\n", ProcessOutputTypes.STDERR);
            }
        }, "glua-dap-breakpoint-sync");
        worker.setDaemon(true);
        worker.start();
    }

    public @NotNull List<GluaDapVariable> currentVariables() {
        return latestVariables;
    }

    @Override
    public void requestVariables(int variablesReference, @NotNull Consumer<List<GluaDapVariable>> callback) {
        if (variablesReference <= 0) {
            callback.accept(List.of());
            return;
        }
        List<GluaDapVariable> cached;
        synchronized (variableCache) {
            cached = variableCache.get(variablesReference);
            if (cached != null) {
                callback.accept(cached);
                return;
            }
        }
        BufferedWriter writer = dapWriter;
        if (writer == null) {
            callback.accept(List.of());
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                sendVariablesRequest(writer, variablesReference, callback);
            } catch (IOException error) {
                notifyTextAvailable("GLua variables request failed: " + readableError(error) + "\n", ProcessOutputTypes.STDERR);
                callback.accept(List.of());
            }
        }, "glua-dap-variables-" + variablesReference);
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void setVariable(int variablesReference,
                            @NotNull String name,
                            @NotNull String value,
                            @NotNull Consumer<GluaDapSetVariableResult> callback) {
        BufferedWriter writer = dapWriter;
        if (writer == null) {
            callback.accept(GluaDapSetVariableResult.failure("GLua DAP connection is not ready."));
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                sendSetVariableRequest(writer, variablesReference, name, value, callback);
            } catch (IOException error) {
                notifyTextAvailable("GLua setVariable request failed: " + readableError(error) + "\n", ProcessOutputTypes.STDERR);
                callback.accept(GluaDapSetVariableResult.failure(readableError(error)));
            }
        }, "glua-dap-set-variable");
        worker.setDaemon(true);
        worker.start();
    }

    /** 请求本地 DAP 在暂停栈帧中计算悬停表达式。 */
    @Override
    public void evaluate(@NotNull String expression, @NotNull Consumer<GluaDapEvaluateResult> callback) {
        // 本地 DAP 尚未完成握手时立即失败，避免 IDE 悬停请求挂起。
        BufferedWriter writer = dapWriter;
        if (writer == null) {
            callback.accept(GluaDapEvaluateResult.failure("GLua DAP connection is not ready."));
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                sendEvaluateRequest(writer, expression, callback);
            } catch (IOException error) {
                notifyTextAvailable("GLua evaluate request failed: " + readableError(error) + "\n", ProcessOutputTypes.STDERR);
                callback.accept(GluaDapEvaluateResult.failure(readableError(error)));
            }
        }, "glua-dap-evaluate");
        worker.setDaemon(true);
        worker.start();
    }

    public static @NotNull GluaDapLaunchProcessHandler create(@NotNull Project project,
                                                              @NotNull String gluaExecutable,
                                                              @NotNull String program) throws ExecutionException {
        String executable = gluaExecutable.isBlank() ? "glua" : gluaExecutable;
        String listen = GluaDapRunConfiguration.INTERNAL_DAP_HOST + ":0";
        GeneralCommandLine commandLine = new GeneralCommandLine(executable, "--glua-dap-listen=" + listen, program);
        VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(Path.of(program));
        if (file != null && file.getParent() != null) {
            commandLine.withWorkDirectory(file.getParent().getPath());
        }
        return new GluaDapLaunchProcessHandler(project, program, commandLine, listen);
    }

    @Override
    public void notifyTextAvailable(@NotNull String text, @NotNull Key outputType) {
        captureOutput(text, outputType);
        if (isQuietDapStatus(text, outputType)) {
            return;
        }
        super.notifyTextAvailable(text, outputType);
    }

    public @NotNull ReadyTarget awaitReady(@NotNull Duration timeout) throws ExecutionException {
        ReadyTarget current = readyTarget;
        if (current != null) {
            return current;
        }
        try {
            if (!readyOrExit.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new ExecutionException(failureMessage(GluaUiText.text(
                    "timeout waiting for GLua DAP ready marker",
                    "等待 GLua DAP 启动标记超时"
                )));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ExecutionException(failureMessage(GluaUiText.text(
                "interrupted while waiting for GLua DAP ready marker",
                "等待 GLua DAP 启动标记时被中断"
            )), error);
        }
        current = readyTarget;
        if (current != null) {
            return current;
        }
        throw new ExecutionException(failureMessage(GluaUiText.text(
            "glua exited before GLua DAP ready marker",
            "glua 在输出 GLua DAP 启动标记前退出"
        )));
    }

    public @NotNull String failureMessage(@NotNull String reason) {
        StringBuilder builder = new StringBuilder();
        builder.append(GluaUiText.text("GLua Debug launch failed: ", "GLua Debug 启动失败：")).append(reason)
            .append(GluaUiText.text(" | command=", " | 命令=")).append(commandText)
            .append(GluaUiText.text(" | cwd=", " | 工作目录=")).append(workDirectory)
            .append(GluaUiText.text(" | listen=", " | 监听=")).append(listenAddress);
        if (exitCode != null) {
            builder.append(GluaUiText.text(" | exit=", " | 退出码=")).append(exitCode);
        }
        String stderr = stderrTail.toString().trim();
        if (!stderr.isBlank()) {
            builder.append(" | stderr=").append(stderr);
        }
        String stdout = stdoutTail.toString().trim();
        if (!stdout.isBlank()) {
            builder.append(" | stdout=").append(stdout);
        }
        return builder.toString();
    }

    private void captureOutput(@NotNull String text, @NotNull Key outputType) {
        if (ProcessOutputType.isStderr(outputType) || outputType == ProcessOutputTypes.STDERR) {
            appendTail(stderrTail, text);
        } else {
            appendTail(stdoutTail, text);
        }
        ReadyTarget parsed = parseReadyTarget(stdoutTail + "\n" + stderrTail);
        if (parsed != null) {
            readyTarget = parsed;
            readyOrExit.countDown();
            startDapClient(parsed);
        }
    }

    private void startDapClient(@NotNull ReadyTarget target) {
        if (!clientStarted.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(() -> runDapClient(target), "glua-idea-dap-client");
        worker.setDaemon(true);
        worker.start();
    }

    private void runDapClient(@NotNull ReadyTarget target) {
        try {
            Socket socket = new Socket(target.host(), target.port());
            dapSocket = socket;
            InputStream reader = new BufferedInputStream(socket.getInputStream());
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            dapWriter = writer;
            Thread readerThread = new Thread(() -> drainDapMessages(reader), "glua-idea-dap-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            sendRequest(writer, "initialize", "{\"adapterID\":\"glua\",\"clientID\":\"intellij\",\"clientName\":\"IntelliJ IDEA\",\"linesStartAt1\":true,\"columnsStartAt1\":true,\"pathFormat\":\"path\"}");
            sendBreakpoints(writer);
            sendRequest(writer, "launch", "{\"program\":" + quoteJson(program) + ",\"noDebug\":false}");
            sendRequest(writer, "configurationDone", "{}");
        } catch (IOException error) {
            notifyTextAvailable("GLua Debug client failed: " + readableError(error) + "\n", ProcessOutputTypes.STDERR);
        }
    }

    private void sendBreakpoints(@NotNull BufferedWriter writer) throws IOException {
        Map<String, List<Integer>> breakpointsByFile = collectBreakpoints();
        Set<String> sources = breakpointSourcesToSync(breakpointsByFile);
        if (sources.isEmpty()) {
            return;
        }
        for (String source : sources) {
            List<Integer> lines = breakpointsByFile.getOrDefault(source, List.of());
            StringBuilder breakpointsJson = new StringBuilder("[");
            for (int index = 0; index < lines.size(); index++) {
                if (index > 0) {
                    breakpointsJson.append(',');
                }
                breakpointsJson.append("{\"line\":").append(lines.get(index)).append('}');
            }
            breakpointsJson.append(']');
            String arguments = "{\"source\":{\"path\":" + quoteJson(source) + "},\"breakpoints\":" + breakpointsJson + "}";
            sendRequest(writer, "setBreakpoints", arguments);
        }
        rememberSyncedBreakpointSources(breakpointsByFile.keySet());
    }

    private @NotNull Set<String> breakpointSourcesToSync(@NotNull Map<String, List<Integer>> breakpointsByFile) {
        Set<String> sources = new LinkedHashSet<>();
        synchronized (syncedBreakpointSources) {
            sources.addAll(syncedBreakpointSources);
        }
        sources.addAll(breakpointsByFile.keySet());
        return sources;
    }

    private void rememberSyncedBreakpointSources(@NotNull Set<String> currentSources) {
        synchronized (syncedBreakpointSources) {
            syncedBreakpointSources.clear();
            syncedBreakpointSources.addAll(currentSources);
        }
    }

    private @NotNull Map<String, List<Integer>> collectBreakpoints() {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        var breakpointManager = XDebuggerManager.getInstance(project).getBreakpointManager();
        if (breakpointsMuted) {
            return result;
        }
        for (XBreakpoint<?> breakpoint : breakpointManager.getAllBreakpoints()) {
            if (!(breakpoint instanceof XLineBreakpoint<?> lineBreakpoint)) {
                continue;
            }
            if (!(lineBreakpoint.getType() instanceof GluaLineBreakpointType) || !lineBreakpoint.isEnabled()) {
                continue;
            }
            Set<String> paths = breakpointPathAliases(normalizeFileUrl(lineBreakpoint.getFileUrl()));
            if (paths.isEmpty()) {
                continue;
            }
            for (String path : paths) {
                result.computeIfAbsent(path, ignored -> new ArrayList<>()).add(lineBreakpoint.getLine() + 1);
            }
        }
        return result;
    }

    private void drainDapMessages(@NotNull InputStream reader) {
        try {
            while (true) {
                String message = readDapMessage(reader);
                if (message == null) {
                    return;
                }
                handleDapMessage(message);
                logDapTraffic("<=", message);
            }
        } catch (IOException error) {
            if (!isProcessTerminated()) {
                notifyTextAvailable("GLua Debug client read failed: " + readableError(error) + "\n", ProcessOutputTypes.STDERR);
            }
        }
    }

    private void handleDapMessage(@NotNull String message) {
        JsonObject object;
        try {
            object = JsonParser.parseString(message).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }
        String type = stringMember(object, "type");
        if ("event".equals(type) && "stopped".equals(stringMember(object, "event"))) {
            latestVariables = List.of();
            clearVariableCaches();
            sendControlCommand("stackTrace");
            return;
        }
        if ("response".equals(type) && "variables".equals(stringMember(object, "command"))) {
            handleVariablesResponse(object);
            return;
        }
        if ("response".equals(type) && "setVariable".equals(stringMember(object, "command"))) {
            handleSetVariableResponse(object);
            return;
        }
        if ("response".equals(type) && "evaluate".equals(stringMember(object, "command"))) {
            handleEvaluateResponse(object);
            return;
        }
        if ("response".equals(type) && "stackTrace".equals(stringMember(object, "command"))) {
            GluaDapStackFrame frame = firstGluaDapStackFrame(object);
            GluaDebugProcess process = debugProcess;
            if (frame != null && process != null) {
                process.onStopped(frame);
                requestVariables(1, variables -> {
                    latestVariables = variables;
                    process.refreshVariables();
                });
            }
        }
    }

    private synchronized int sendRequest(@NotNull BufferedWriter writer,
                                         @NotNull String command,
                                         @NotNull String argumentsJson) throws IOException {
        int seq = nextClientSeq++;
        String payload = "{\"seq\":" + seq
            + ",\"type\":\"request\""
            + ",\"command\":" + quoteJson(command)
            + ",\"arguments\":" + argumentsJson
            + "}";
        writer.write("Content-Length: " + payload.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n");
        writer.write(payload);
        writer.flush();
        logDapTraffic("=>", payload);
        return seq;
    }

    private synchronized int sendVariablesRequest(@NotNull BufferedWriter writer,
                                                  int variablesReference,
                                                  @NotNull Consumer<List<GluaDapVariable>> callback) throws IOException {
        int seq = nextClientSeq++;
        synchronized (variableCache) {
            variablesRequestReferences.put(seq, variablesReference);
            variableCallbacks.computeIfAbsent(variablesReference, ignored -> new ArrayList<>()).add(callback);
        }
        String payload = "{\"seq\":" + seq
            + ",\"type\":\"request\""
            + ",\"command\":\"variables\""
            + ",\"arguments\":{\"variablesReference\":" + variablesReference + "}"
            + "}";
        writer.write("Content-Length: " + payload.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n");
        writer.write(payload);
        writer.flush();
        logDapTraffic("=>", payload);
        return seq;
    }

    private synchronized int sendSetVariableRequest(@NotNull BufferedWriter writer,
                                                    int variablesReference,
                                                    @NotNull String name,
                                                    @NotNull String value,
                                                    @NotNull Consumer<GluaDapSetVariableResult> callback) throws IOException {
        int seq = nextClientSeq++;
        synchronized (variableCache) {
            setVariableCallbacks.put(seq, callback);
        }
        String payload = "{\"seq\":" + seq
            + ",\"type\":\"request\""
            + ",\"command\":\"setVariable\""
            + ",\"arguments\":{\"variablesReference\":" + variablesReference
            + ",\"name\":" + quoteJson(name)
            + ",\"value\":" + quoteJson(value)
            + "}"
            + "}";
        writer.write("Content-Length: " + payload.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n");
        writer.write(payload);
        writer.flush();
        logDapTraffic("=>", payload);
        return seq;
    }

    private synchronized int sendEvaluateRequest(@NotNull BufferedWriter writer,
                                                  @NotNull String expression,
                                                  @NotNull Consumer<GluaDapEvaluateResult> callback) throws IOException {
        // hover 上下文仅允许服务端支持的无副作用变量与点访问表达式。
        int seq = nextClientSeq++;
        synchronized (variableCache) {
            evaluateCallbacks.put(seq, callback);
        }
        String payload = "{\"seq\":" + seq
            + ",\"type\":\"request\""
            + ",\"command\":\"evaluate\""
            + ",\"arguments\":{\"expression\":" + quoteJson(expression)
            + ",\"context\":\"hover\"}"
            + "}";
        writer.write("Content-Length: " + payload.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n");
        writer.write(payload);
        writer.flush();
        return seq;
    }

    private void logDapTraffic(@NotNull String direction, @NotNull String payload) {
        if (!dapDebugLogEnabled()) {
            return;
        }
        String line = "GLua DAP " + direction + " " + payload + "\n";
        ApplicationManager.getApplication().invokeLater(() -> notifyTextAvailable(line, ProcessOutputTypes.STDOUT));
    }

    private static boolean dapDebugLogEnabled() {
        return ApplicationManager.getApplication().getService(GluaSettings.class).dapDebugLog();
    }

    private void handleVariablesResponse(@NotNull JsonObject object) {
        int requestSeq = intMember(object, "request_seq");
        List<GluaDapVariable> variables = parseVariables(object);
        List<Consumer<List<GluaDapVariable>>> callbacks;
        int reference;
        synchronized (variableCache) {
            reference = variablesRequestReferences.remove(requestSeq);
            if (reference <= 0) {
                reference = 1;
            }
            variableCache.put(reference, variables);
            callbacks = variableCallbacks.remove(reference);
        }
        if (reference == 1) {
            latestVariables = variables;
            GluaDebugProcess process = debugProcess;
            if (process != null) {
                process.refreshVariables();
            }
        }
        if (callbacks != null) {
            for (Consumer<List<GluaDapVariable>> callback : callbacks) {
                callback.accept(variables);
            }
        }
    }

    private void handleSetVariableResponse(@NotNull JsonObject object) {
        int requestSeq = intMember(object, "request_seq");
        Consumer<GluaDapSetVariableResult> callback;
        synchronized (variableCache) {
            callback = setVariableCallbacks.remove(requestSeq);
            variableCache.clear();
        }
        if (callback == null) {
            return;
        }
        JsonObject body = objectMember(object, "body");
        String error = body == null ? "" : stringMember(body, "error");
        if (!booleanMember(object, "success")) {
            if (error.isBlank()) {
                error = stringMember(object, "message");
            }
            callback.accept(GluaDapSetVariableResult.failure(error.isBlank() ? "GLua setVariable failed." : error));
            return;
        }
        if (!error.isBlank()) {
            callback.accept(GluaDapSetVariableResult.failure(error));
            return;
        }
        callback.accept(GluaDapSetVariableResult.ok());
    }

    private void handleEvaluateResponse(@NotNull JsonObject object) {
        // request_seq 将响应分发给对应的 IDEA 悬停请求。
        int requestSeq = intMember(object, "request_seq");
        Consumer<GluaDapEvaluateResult> callback;
        synchronized (variableCache) {
            callback = evaluateCallbacks.remove(requestSeq);
        }
        if (callback == null) {
            return;
        }
        JsonObject body = objectMember(object, "body");
        if (!booleanMember(object, "success") || body == null) {
            String error = stringMember(object, "message");
            callback.accept(GluaDapEvaluateResult.failure(error.isBlank() ? "GLua evaluate failed." : error));
            return;
        }
        callback.accept(GluaDapEvaluateResult.ok(new GluaDapVariable(
            "",
            stringMember(body, "result"),
            stringMember(body, "type"),
            intMember(body, "variablesReference")
        )));
    }

    private void clearVariableCaches() {
        synchronized (variableCache) {
            variableCache.clear();
            variableCallbacks.clear();
            variablesRequestReferences.clear();
            setVariableCallbacks.clear();
            evaluateCallbacks.clear();
        }
    }

    private static String readDapMessage(@NotNull InputStream reader) throws IOException {
        int contentLength = -1;
        while (true) {
            String line = readAsciiHeaderLine(reader);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                break;
            }
            Matcher matcher = CONTENT_LENGTH_PATTERN.matcher(line);
            if (matcher.matches()) {
                contentLength = Integer.parseInt(matcher.group(1));
            }
        }
        if (contentLength < 0) {
            return null;
        }
        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = reader.read(body, offset, contentLength - offset);
            if (read < 0) {
                return null;
            }
            offset += read;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static String readAsciiHeaderLine(@NotNull InputStream reader) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = reader.read();
            if (value < 0) {
                return line.size() == 0 ? null : line.toString(StandardCharsets.US_ASCII);
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.write(value);
            }
        }
        return line.toString(StandardCharsets.US_ASCII);
    }

    private void closeDapSocket() {
        dapWriter = null;
        Socket socket = dapSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 进程结束时关闭调试 socket，失败不影响进程退出。
            }
        }
    }

    private static String quoteJson(@NotNull String text) {
        StringBuilder builder = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            switch (ch) {
                case '\\' -> builder.append("\\\\");
                case '"' -> builder.append("\\\"");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> builder.append(ch);
            }
        }
        return builder.append('"').toString();
    }

    private static String readableError(@NotNull Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
            ? error.getClass().getSimpleName()
            : error.getMessage();
    }

    private static boolean isQuietDapStatus(@NotNull String text, @NotNull Key outputType) {
        String trimmed = text.trim();
        return trimmed.startsWith("GLua DAP server listening on ")
            || trimmed.equals("GLua DAP waiting for client configuration...")
            || trimmed.equals("GLua DAP client configured; starting script.")
            || (!dapDebugLogEnabled() && trimmed.startsWith("GLua DAP => "))
            || (!dapDebugLogEnabled() && trimmed.startsWith("GLua DAP <= "))
            || trimmed.startsWith("GLua IDEA DAP client ");
    }

    private static @NotNull String normalizeFileUrl(@NotNull String fileUrl) {
        if (fileUrl.startsWith("file://")) {
            return fileUrl.substring("file://".length());
        }
        return fileUrl;
    }

    private @NotNull Set<String> breakpointPathAliases(@NotNull String path) {
        Set<String> aliases = new LinkedHashSet<>();
        if (path.isBlank()) {
            return aliases;
        }
        addPathWithExtensionAliases(aliases, path);
        try {
            Path absolute = Paths.get(path).toAbsolutePath().normalize();
            addPathWithExtensionAliases(aliases, absolute.toString());
            if (!workDirectory.isBlank()) {
                Path workDir = Paths.get(workDirectory).toAbsolutePath().normalize();
                if (absolute.startsWith(workDir)) {
                    addPathWithExtensionAliases(aliases, workDir.relativize(absolute).toString());
                }
            }
        } catch (RuntimeException ignored) {
            // 非本地文件 URL 不能转 Path 时保留原始 URL 即可。
        }
        aliases.remove("");
        return aliases;
    }

    private static void addPathWithExtensionAliases(@NotNull Set<String> aliases, @NotNull String path) {
        String normalized = path.replace('\\', '/');
        aliases.add(normalized);
        if (normalized.endsWith(".lua")) {
            aliases.add(normalized.substring(0, normalized.length() - ".lua".length()) + ".glua");
        } else if (normalized.endsWith(".glua")) {
            aliases.add(normalized.substring(0, normalized.length() - ".glua".length()) + ".lua");
        }
    }

    private static @NotNull String stringMember(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static GluaDapStackFrame firstGluaDapStackFrame(@NotNull JsonObject response) {
        JsonObject body = objectMember(response, "body");
        if (body == null) {
            return null;
        }
        JsonArray frames = arrayMember(body, "stackFrames");
        if (frames == null || frames.isEmpty() || !frames.get(0).isJsonObject()) {
            return null;
        }
        JsonObject frame = frames.get(0).getAsJsonObject();
        JsonObject source = objectMember(frame, "source");
        String path = source == null ? "" : stringMember(source, "path");
        int line = intMember(frame, "line");
        return new GluaDapStackFrame(path, line);
    }

    private static @NotNull List<GluaDapVariable> parseVariables(@NotNull JsonObject response) {
        JsonObject body = objectMember(response, "body");
        if (body == null) {
            return List.of();
        }
        JsonArray variables = arrayMember(body, "variables");
        if (variables == null || variables.isEmpty()) {
            return List.of();
        }
        List<GluaDapVariable> result = new ArrayList<>();
        for (JsonElement element : variables) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject variable = element.getAsJsonObject();
            String name = stringMember(variable, "name");
            if (name.isBlank()) {
                continue;
            }
            result.add(new GluaDapVariable(name, stringMember(variable, "value"), stringMember(variable, "type"), intMember(variable, "variablesReference")));
        }
        return Collections.unmodifiableList(result);
    }

    private static JsonObject objectMember(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonObject() ? null : element.getAsJsonObject();
    }

    private static JsonArray arrayMember(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonArray() ? null : element.getAsJsonArray();
    }

    private static int intMember(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? 0 : element.getAsInt();
    }

    private static boolean booleanMember(@NotNull JsonObject object, @NotNull String name) {
        JsonElement element = object.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsBoolean();
    }

    private static void appendTail(StringBuilder builder, String text) {
        builder.append(text);
        int extra = builder.length() - 4000;
        if (extra > 0) {
            builder.delete(0, extra);
        }
    }

    static ReadyTarget parseReadyTarget(@NotNull String text) {
        String[] lines = text.split("\\R");
        for (String line : lines) {
            int index = line.indexOf(READY_PREFIX);
            if (index < 0) {
                continue;
            }
            String address = line.substring(index + READY_PREFIX.length()).trim();
            int colon = address.lastIndexOf(':');
            if (colon <= 0 || colon == address.length() - 1) {
                continue;
            }
            try {
                int port = Integer.parseInt(address.substring(colon + 1));
                if (port >= 1 && port <= 65535) {
                    return new ReadyTarget(address.substring(0, colon), port);
                }
            } catch (NumberFormatException ignored) {
                // 非法端口不是 ready 标记，继续检查后续行。
            }
        }
        return null;
    }

    public record ReadyTarget(@NotNull String host, int port) {
    }
}
