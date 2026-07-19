package com.autogo.jetbrains;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 验证 IDEA 设置页和设备菜单共享同一轮异步 ADB 扫描。 */
final class AutoGoDeviceSupportTest {
    /** 并发强制刷新必须复用正在执行的扫描任务并更新缓存。 */
    @Test
    void coalescesConcurrentDeviceRefreshes() throws Exception {
        // Windows 测试环境不提供 POSIX shell，平台行为由 Java 编译与其它解析测试覆盖。
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"));
        Path fakeAdb = Files.createTempFile("autogo-fake-adb", ".sh");
        Files.writeString(fakeAdb, "#!/bin/sh\nsleep 1\nprintf 'List of devices attached\\nusb-device device product:test\\n'\n");
        fakeAdb.toFile().setExecutable(true);
        try {
            CompletableFuture<List<String>> first = AutoGoDeviceSupport.refreshDevicesAsync(fakeAdb.toString(), true);
            CompletableFuture<List<String>> second = AutoGoDeviceSupport.refreshDevicesAsync(fakeAdb.toString(), true);

            assertSame(first, second);
            assertEquals(List.of("usb-device"), first.get());
            assertEquals(List.of("usb-device"), AutoGoDeviceSupport.cachedDevices());
        } finally {
            // 测试完成后删除临时 ADB 脚本。
            Files.deleteIfExists(fakeAdb);
        }
    }
}
