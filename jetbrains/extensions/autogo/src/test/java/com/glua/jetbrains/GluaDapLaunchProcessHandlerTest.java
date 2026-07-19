package com.glua.jetbrains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 验证本地 GLua DAP 启动器从运行时输出解析真实监听地址。 */
final class GluaDapLaunchProcessHandlerTest {
    /** 验证标准 ready 标记能够解析主机与动态端口。 */
    @Test
    void parseReadyTargetReadsHostAndPort() {
        // 本地启动允许运行时选择空闲端口，IDE 必须使用输出中的最终值。
        GluaDapLaunchProcessHandler.ReadyTarget target = GluaDapLaunchProcessHandler.parseReadyTarget(
                "GLua DAP server listening on 127.0.0.1:65019\n");
        assertNotNull(target);
        assertEquals("127.0.0.1", target.host());
        assertEquals(65019, target.port());
    }

    /** 验证普通 stderr 不会被误判为 DAP 已就绪。 */
    @Test
    void parseReadyTargetRejectsMissingMarker() {
        // 缺少稳定标记时继续等待或报告启动失败。
        assertNull(GluaDapLaunchProcessHandler.parseReadyTarget("plain stderr"));
    }
}
