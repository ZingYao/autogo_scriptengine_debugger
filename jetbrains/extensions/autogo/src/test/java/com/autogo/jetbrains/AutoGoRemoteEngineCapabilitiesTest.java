package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 IDEA 在控制操作前严格执行 AutoGo 远程协议能力协商。 */
final class AutoGoRemoteEngineCapabilitiesTest {
    /** 验证同主版本且能力完整的移动端可以连接。 */
    @Test
    void acceptsCompatibleMinorVersionAndUnknownFeatures() {
        // 次版本与新增能力向前兼容，IDEA 只依赖已声明的必要能力。
        String response = """
                {"protocolVersion":"1.7","features":["lua","glua","gluac","dap","incremental-sync","future"]}
                """;
        assertDoesNotThrow(() -> AutoGoRemoteEngineService.validateCapabilities(response,
                Set.of("lua", "glua", "gluac", "dap", "incremental-sync")));
    }

    /** 验证未知主版本被拒绝，避免对不兼容端点执行修改操作。 */
    @Test
    void rejectsUnknownProtocolMajor() {
        // 主版本变化代表破坏性协议变更。
        String response = """
                {"protocolVersion":"2.0","features":["lua","glua","gluac","dap","incremental-sync"]}
                """;
        assertThrows(IOException.class, () -> AutoGoRemoteEngineService.validateCapabilities(response,
                Set.of("dap")));
    }

    /** 验证缺少增量同步或调试能力时不会降级成不完整流程。 */
    @Test
    void rejectsMissingRequiredFeature() {
        // 快速调试必须同时具备 DAP 与增量同步，不能只连接健康检查。
        String response = """
                {"protocolVersion":"1.0","features":["lua","glua"]}
                """;
        assertThrows(IOException.class, () -> AutoGoRemoteEngineService.validateCapabilities(response,
                Set.of("dap", "incremental-sync")));
    }

    /** 验证损坏响应不会被当作兼容服务。 */
    @Test
    void rejectsMalformedCapabilities() {
        // 非 JSON 响应可能来自错误端口上的其他 HTTP 服务。
        assertThrows(IOException.class, () -> AutoGoRemoteEngineService.validateCapabilities("not-json",
                Set.of("lua")));
    }
}
