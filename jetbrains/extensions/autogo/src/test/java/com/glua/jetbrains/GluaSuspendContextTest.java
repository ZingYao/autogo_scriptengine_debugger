package com.glua.jetbrains;

import com.intellij.openapi.util.TextRange;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证 IDEA 调试悬停只向 DAP 提交合法且无副作用的 GLua 表达式。 */
final class GluaSuspendContextTest {
    /** 普通函数参数在声明和使用位置都必须解析为同一个变量名。 */
    @Test
    void resolvesPlainVariableAtDeclarationAndUsage() {
        // 两种位置分别覆盖截图中的可用与不可用场景。
        String source = "tools.hello = function(name)\n  print('hello,'..name)\nend";
        assertEquals("name", expressionAt(source, source.indexOf("name")));
        assertEquals("name", expressionAt(source, source.lastIndexOf("name")));
    }

    /** Lua 拼接符不能与相邻变量合并为 DAP 求值表达式。 */
    @Test
    void stopsAtLuaConcatenationOperator() {
        // 左右两侧分别悬停时只返回当前标识符。
        String source = "left..right";
        assertEquals("left", expressionAt(source, source.indexOf("left") + 2));
        assertEquals("right", expressionAt(source, source.indexOf("right") + 2));
        assertNull(GluaSuspendContext.expressionRangeAtOffset(source, source.indexOf("..")));
    }

    /** 单点成员访问仍应作为完整表达式交给 DAP。 */
    @Test
    void keepsMemberAccessExpression() {
        // table.field 属于无副作用的受支持表达式。
        String source = "console.log(tools.current.name)";
        assertEquals("tools.current.name", expressionAt(source, source.indexOf("current")));
    }

    /** 修改变量成功后必须重新请求对应父作用域。 */
    @Test
    void refreshesParentScopeAfterVariableModification() {
        // 使用轻量 DAP 客户端记录请求，确保 IDEA 不会继续展示旧变量快照。
        AtomicInteger requestedReference = new AtomicInteger();
        GluaDapClient client = new RecordingDapClient(requestedReference);

        GluaSuspendContext.refreshModifiedVariables(client, 17);

        assertEquals(17, requestedReference.get());
    }

    private static String expressionAt(String source, int offset) {
        // 测试辅助方法按返回范围截取实际提交给 DAP 的表达式。
        TextRange range = GluaSuspendContext.expressionRangeAtOffset(source, offset);
        return range == null ? null : range.substring(source);
    }

    /** 仅记录变量刷新请求的 DAP 测试客户端。 */
    private static final class RecordingDapClient implements GluaDapClient {
        private final AtomicInteger requestedReference;

        private RecordingDapClient(AtomicInteger requestedReference) {
            // 保存断言使用的请求引用记录器。
            this.requestedReference = requestedReference;
        }

        @Override
        public void setDebugProcess(GluaDebugProcess debugProcess) {
            // 当前测试不创建调试进程。
        }

        @Override
        public void sendControlCommand(String command) {
            // 当前测试不发送运行控制命令。
        }

        @Override
        public void setBreakpointsMuted(boolean muted) {
            // 当前测试不管理断点。
        }

        @Override
        public void syncBreakpointsAsync() {
            // 当前测试不触发断点同步。
        }

        @Override
        public List<GluaDapVariable> currentVariables() {
            // 当前测试没有变量快照。
            return List.of();
        }

        @Override
        public void requestVariables(int variablesReference, Consumer<List<GluaDapVariable>> callback) {
            // 记录父作用域并立即返回空结果。
            requestedReference.set(variablesReference);
            callback.accept(List.of());
        }

        @Override
        public void evaluate(String expression, Consumer<GluaDapEvaluateResult> callback) {
            // 当前测试不执行表达式求值。
        }

        @Override
        public void setVariable(int variablesReference, String name, String value,
                                Consumer<GluaDapSetVariableResult> callback) {
            // 当前测试不再次修改变量。
        }
    }
}
