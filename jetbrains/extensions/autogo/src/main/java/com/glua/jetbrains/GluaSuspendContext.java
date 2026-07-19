package com.glua.jetbrains;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.XExpression;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator;
import com.intellij.xdebugger.frame.XCompositeNode;
import com.intellij.xdebugger.frame.XExecutionStack;
import com.intellij.xdebugger.frame.XNamedValue;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.frame.XSuspendContext;
import com.intellij.xdebugger.frame.XValueChildrenList;
import com.intellij.xdebugger.frame.XValueModifier;
import com.intellij.xdebugger.frame.XValueNode;
import com.intellij.xdebugger.frame.XValuePlace;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GluaSuspendContext extends XSuspendContext {
    private static final Pattern HOVER_EXPRESSION = Pattern.compile(
            "[\\p{L}_][\\p{L}\\p{N}_]*(?:\\.[\\p{L}_][\\p{L}\\p{N}_]*)*");
    private final GluaExecutionStack executionStack;

    public GluaSuspendContext(@NotNull Project project,
                              @NotNull GluaDapStackFrame frame,
                              @Nullable GluaDapClient dapHandler) {
        this.executionStack = new GluaExecutionStack(project, frame, dapHandler);
    }

    @Override
    public @NotNull XExecutionStack getActiveExecutionStack() {
        return executionStack;
    }

    @Override
    public XExecutionStack @NotNull [] getExecutionStacks() {
        return new XExecutionStack[]{executionStack};
    }

    static @Nullable TextRange expressionRangeAtOffset(@NotNull CharSequence text, int offset) {
        // 使用完整词法形状匹配，避免从当前位置向两侧扫描时把 `left..right` 合并为非法表达式。
        if (text.isEmpty()) {
            return null;
        }
        int safeOffset = Math.max(0, Math.min(offset, text.length() - 1));
        if (!isExpressionChar(text.charAt(safeOffset)) && safeOffset > 0 && isExpressionChar(text.charAt(safeOffset - 1))) {
            safeOffset--;
        }
        if (!isExpressionChar(text.charAt(safeOffset))) {
            return null;
        }
        Matcher matcher = HOVER_EXPRESSION.matcher(text);
        while (matcher.find()) {
            if (safeOffset >= matcher.start() && safeOffset < matcher.end()) {
                return TextRange.create(matcher.start(), matcher.end());
            }
        }
        return null;
    }

    private static boolean isExpressionChar(char ch) {
        // 当前服务端支持标识符和点访问；不扩大到函数调用，避免悬停产生副作用。
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '.';
    }

    static void refreshModifiedVariables(@NotNull GluaDapClient dapHandler, int variablesReference) {
        // 重新请求被修改变量的父作用域；响应处理器会替换快照并重建 IDEA 调试视图。
        dapHandler.requestVariables(variablesReference, ignored -> {
            // 数据更新和视图刷新由 DAP 响应处理器统一完成，此处只触发请求。
        });
    }

    private static final class GluaExecutionStack extends XExecutionStack {
        private final GluaStackFrame topFrame;

        private GluaExecutionStack(@NotNull Project project,
                                   @NotNull GluaDapStackFrame frame,
                                   @Nullable GluaDapClient dapHandler) {
            super("GLua main");
            this.topFrame = new GluaStackFrame(project, frame, dapHandler);
        }

        @Override
        public @NotNull XStackFrame getTopFrame() {
            return topFrame;
        }

        @Override
        public void computeStackFrames(int firstFrameIndex, @NotNull XStackFrameContainer container) {
            if (firstFrameIndex == 0) {
                container.addStackFrames(java.util.List.of(topFrame), true);
                return;
            }
            container.addStackFrames(java.util.List.of(), true);
        }
    }

    private static final class GluaStackFrame extends XStackFrame {
        private final GluaDapStackFrame frame;
        private final GluaDapClient dapHandler;
        private final XSourcePosition position;
        private final XDebuggerEvaluator evaluator;

        private GluaStackFrame(@NotNull Project project,
                               @NotNull GluaDapStackFrame frame,
                               @Nullable GluaDapClient dapHandler) {
            this.frame = frame;
            this.dapHandler = dapHandler;
            this.position = sourcePosition(project, frame);
            this.evaluator = new GluaVariableEvaluator(dapHandler);
        }

        @Override
        public @Nullable XSourcePosition getSourcePosition() {
            return position;
        }

        @Override
        public @Nullable XDebuggerEvaluator getEvaluator() {
            return evaluator;
        }

        @Override
        public void computeChildren(@NotNull XCompositeNode node) {
            List<GluaDapVariable> variables = dapHandler == null
                ? List.of()
                : dapHandler.currentVariables();
            if (variables.isEmpty()) {
                node.addChildren(XValueChildrenList.EMPTY, true);
                return;
            }
            XValueChildrenList children = new XValueChildrenList(variables.size());
            for (GluaDapVariable variable : variables) {
                children.add(new GluaVariableValue(variable, 1, dapHandler));
            }
            node.addChildren(children, true);
        }

        private static @Nullable XSourcePosition sourcePosition(@NotNull Project project, @NotNull GluaDapStackFrame frame) {
            if (frame.source().isBlank() || frame.line() <= 0) {
                return null;
            }
            for (Path path : sourcePathCandidates(project, frame.source())) {
                VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path);
                if (file == null) {
                    file = LocalFileSystem.getInstance().findFileByNioFile(path);
                }
                if (file != null) {
                    return XDebuggerUtil.getInstance().createPosition(file, frame.line() - 1);
                }
            }
            return null;
        }

        private static @NotNull List<Path> sourcePathCandidates(@NotNull Project project, @NotNull String source) {
            List<Path> paths = new ArrayList<>();
            try {
                Path path = Path.of(source);
                if (!path.isAbsolute() && project.getBasePath() != null) {
                    // DAP 可能返回相对路径；按项目根目录解析后才能让 IDE 自动跳转到其它文件。
                    path = Path.of(project.getBasePath()).resolve(path).normalize();
                }
                paths.add(path);
                String normalized = path.toString();
                if (normalized.endsWith(".lua")) {
                    paths.add(Path.of(normalized.substring(0, normalized.length() - ".lua".length()) + ".glua"));
                } else if (normalized.endsWith(".glua")) {
                    paths.add(Path.of(normalized.substring(0, normalized.length() - ".glua".length()) + ".lua"));
                }
            } catch (RuntimeException ignored) {
                // 远程或非本地路径无法转成本机 Path 时返回空候选。
            }
            return paths;
        }
    }

    private static final class GluaVariableValue extends XNamedValue {
        private final GluaDapVariable variable;
        private final int parentVariablesReference;
        private final GluaDapClient dapHandler;

        private GluaVariableValue(@NotNull GluaDapVariable variable,
                                  int parentVariablesReference,
                                  @Nullable GluaDapClient dapHandler) {
            super(variable.name());
            this.variable = variable;
            this.parentVariablesReference = parentVariablesReference;
            this.dapHandler = dapHandler;
        }

        @Override
        public void computePresentation(@NotNull XValueNode node, @NotNull XValuePlace place) {
            String type = variable.type().isBlank() ? null : variable.type();
            node.setPresentation(null, type, variable.value(), variable.variablesReference() > 0);
        }

        @Override
        public void computeChildren(@NotNull XCompositeNode node) {
            if (dapHandler == null || variable.variablesReference() <= 0) {
                node.addChildren(XValueChildrenList.EMPTY, true);
                return;
            }
            dapHandler.requestVariables(variable.variablesReference(), variables -> {
                if (variables.isEmpty()) {
                    node.addChildren(XValueChildrenList.EMPTY, true);
                    return;
                }
                XValueChildrenList children = new XValueChildrenList(variables.size());
                for (GluaDapVariable child : variables) {
                    children.add(new GluaVariableValue(child, variable.variablesReference(), dapHandler));
                }
                node.addChildren(children, true);
            });
        }

        @Override
        public @Nullable XValueModifier getModifier() {
            if (dapHandler == null) {
                return null;
            }
            return new XValueModifier() {
                @Override
                public void setValue(@NotNull XExpression expression, @NotNull XModificationCallback callback) {
                    dapHandler.setVariable(parentVariablesReference, variable.name(), expression.getExpression(), result -> {
                        ApplicationManager.getApplication().invokeLater(() -> {
                            if (result.success()) {
                                // 写回成功后重新读取父作用域，避免变量面板继续展示修改前的快照。
                                callback.valueModified();
                                refreshModifiedVariables(dapHandler, parentVariablesReference);
                                return;
                            }
                            callback.errorOccurred(result.error());
                        });
                    });
                }
            };
        }
    }

    private static final class GluaVariableEvaluator extends XDebuggerEvaluator {
        private final GluaDapClient dapHandler;

        private GluaVariableEvaluator(@Nullable GluaDapClient dapHandler) {
            this.dapHandler = dapHandler;
        }

        @Override
        public void evaluate(@NotNull String expression,
                             @NotNull XEvaluationCallback callback,
                             @Nullable XSourcePosition expressionPosition) {
            String name = expression.trim();
            if (name.isEmpty() || dapHandler == null) {
                callback.errorOccurred(GluaUiText.text("No GLua variable is selected.", "未选择 GLua 变量。"));
                return;
            }
            // 委托移动端 DAP 使用真实暂停栈帧求值，支持局部变量及 table.field 表达式。
            dapHandler.evaluate(name, result -> ApplicationManager.getApplication().invokeLater(() -> {
                if (!result.success()) {
                    callback.errorOccurred(result.error());
                    return;
                }
                GluaDapVariable variable = result.variable();
                GluaDapVariable named = new GluaDapVariable(name, variable.value(), variable.type(), variable.variablesReference());
                callback.evaluated(new GluaVariableValue(named, 1, dapHandler));
            }));
        }

        @Override
        public @Nullable TextRange getExpressionRangeAtOffset(@NotNull Project project,
                                                              @NotNull Document document,
                                                              int offset,
                                                              boolean sideEffectsAllowed) {
            // 只提取标识符与单点成员链；Lua 拼接符 `..` 必须成为表达式边界。
            return expressionRangeAtOffset(document.getCharsSequence(), offset);
        }

    }
}
