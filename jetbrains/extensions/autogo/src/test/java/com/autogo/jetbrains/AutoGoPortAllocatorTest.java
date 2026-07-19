package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证并行项目不会共享本地控制或 DAP 端口。 */
final class AutoGoPortAllocatorTest {
    /** 验证连续分配的两个项目获得四个不同有效端口。 */
    @Test
    void allocatesDistinctPortsForParallelProjects() {
        // 即使设备端口相同，本地 ADB forward 也必须隔离。
        AutoGoPortAllocator.PortPair first = AutoGoPortAllocator.allocate();
        AutoGoPortAllocator.PortPair second = AutoGoPortAllocator.allocate();
        try {
            assertNotEquals(first.control(), first.dap());
            assertNotEquals(second.control(), second.dap());
            assertNotEquals(first.control(), second.control());
            assertNotEquals(first.control(), second.dap());
            assertNotEquals(first.dap(), second.control());
            assertNotEquals(first.dap(), second.dap());
            assertTrue(first.control() > 0 && first.dap() > 0);
        } finally {
            // 测试释放预留，避免影响同 JVM 后续用例。
            AutoGoPortAllocator.release(first);
            AutoGoPortAllocator.release(second);
        }
    }
}
