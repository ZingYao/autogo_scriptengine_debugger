package com.glua.jetbrains;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证远程 DAP 连接失败提供可操作诊断信息。 */
final class GluaDapRemoteProcessHandlerTest {
    /** 验证错误消息同时包含目标、根因和本地/远程恢复建议。 */
    @Test
    void failureMessageIncludesTargetErrorAndRecoveryHint() {
        // 用户需要区分端口设置错误、未启动服务和本地启动模式。
        String message = GluaDapRemoteProcessHandler.failureMessage(
                "127.0.0.1", 5678, new ConnectException("Connection refused"));
        assertTrue(message.contains("127.0.0.1:5678"), "message should include attach target");
        assertTrue(message.contains("Connection refused"), "message should include connection error");
        assertTrue(message.contains("No GLua DAP server is listening"),
                "message should explain missing DAP server");
        assertTrue(message.contains("glua executable"), "message should point users at local launch");
        assertTrue(message.contains("remote GLua DAP server"),
                "message should point users at remote attach");
    }
}
