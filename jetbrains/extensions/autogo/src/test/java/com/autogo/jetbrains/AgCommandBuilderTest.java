package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 AG 命令参数在 JetBrains 入口中的稳定顺序。 */
final class AgCommandBuilderTest {
    /** 验证运行命令可以组合设备和调试参数。 */
    @Test
    void buildsRunArguments() {
        // 输入设备和调试开关后应生成与 AG CLI 一致的参数。
        assertEquals(List.of("run", "-s", "device-1", "-d"),
                AgCommandBuilder.build("run", Map.of("device", "device-1", "debug", "true")));
    }

    /** 验证编译命令可以组合目标和内嵌参数。 */
    @Test
    void buildsBuildArguments() {
        // 编译目标必须位于可选内嵌开关之前。
        assertEquals(List.of("build", "-t", "android", "-e"),
                AgCommandBuilder.build("build", Map.of("target", "android", "embed", "true")));
    }

    /** 验证必填参数缺失时拒绝生成无效命令。 */
    @Test
    void rejectsMissingAddress() {
        // connect 缺少地址必须提前失败，不应启动子进程。
        assertThrows(IllegalArgumentException.class,
                () -> AgCommandBuilder.build("connect", Map.of()));
    }

    /** 验证设备 ABI 与项目资源目录命名保持一致。 */
    @Test
    void normalizesDeviceAbi() {
        // x86-64 需要转换为初始化项目实际生成的 x86_64 目录。
        assertEquals("x86_64", AutoGoSyncResourcesAction.normalizeAbi("x86-64"));
        assertEquals("arm64-v8a", AutoGoSyncResourcesAction.normalizeAbi("arm64-v8a"));
    }

    /** 验证远端 stat 输出可以按文件名和大小索引。 */
    @Test
    void parsesRemoteLibrarySizes() {
        // 解析结果不保留远端绝对路径，便于与本地目录逐文件比较。
        assertEquals(Map.of("libgoeval.so", 128L), AutoGoSyncResourcesAction.parseRemoteSizes(
                "/data/local/tmp/libgoeval.so:128\ninvalid"));
    }

    /** 验证当前平台下载文件名包含目标版本。 */
    @Test
    void buildsAgPlatformFileName() {
        // 平台前缀由运行测试的系统决定，但版本后缀必须稳定。
        org.junit.jupiter.api.Assertions.assertTrue(
                AutoGoUpdateAction.platformFile("1.16.1").endsWith("_1.16.1"));
    }

    /** 验证 AG 下载版本只接受完整版本边界。 */
    @Test
    void validatesDownloadedAgVersionOutput() {
        // 常见 CLI 输出允许 v 前缀，但不能把目标版本匹配为更长版本的一部分。
        org.junit.jupiter.api.Assertions.assertTrue(
                AutoGoUpdateAction.versionMatches("AG - AutoGo CLI v1.16.1", "1.16.1"));
        org.junit.jupiter.api.Assertions.assertFalse(
                AutoGoUpdateAction.versionMatches("AG - AutoGo CLI v1.16.10", "1.16.1"));
        org.junit.jupiter.api.Assertions.assertFalse(
                AutoGoUpdateAction.versionMatches("download error", "1.16.1"));
    }

    /** 验证代理端口只接受标准有效范围。 */
    @Test
    void validatesProxyPort() {
        // 非数字、零和超范围端口都应归零。
        assertEquals(7890, AutoGoSettingsConfigurable.parsePort("7890"));
        assertEquals(0, AutoGoSettingsConfigurable.parsePort("invalid"));
        assertEquals(0, AutoGoSettingsConfigurable.parsePort("65536"));
    }

    /** 验证 AG 自动发现优先使用 PATH 中的可执行文件。 */
    @Test
    void discoversAgFromPath() throws Exception {
        // 临时目录模拟用户 PATH，避免依赖测试机器的固定安装位置。
        Path directory = Files.createTempDirectory("autogo-tool-path");
        String fileName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "ag.exe" : "ag";
        Path executable = Files.writeString(directory.resolve(fileName), "test");
        executable.toFile().setExecutable(true);
        try {
            assertEquals(executable.toAbsolutePath().normalize().toString(),
                    AutoGoToolPathResolver.findAg(Map.of("PATH", directory.toString())));
        } finally {
            // 测试结束后删除临时文件和目录。
            Files.deleteIfExists(executable);
            Files.deleteIfExists(directory);
        }
    }

    /** 验证无线切换优先选择默认路由的设备 IPv4。 */
    @Test
    void parsesWirelessDeviceAddress() {
        // VPN 路由可能先出现，默认 Wi-Fi 路由必须拥有更高优先级。
        String routes = "10.0.0.0/8 dev tun0 src 10.1.2.3\n"
                + "default via 192.168.1.1 dev wlan0 proto dhcp src 192.168.1.28 metric 303\n";
        assertEquals("192.168.1.28", AutoGoDeviceSupport.parseRouteIpv4(routes));
        assertEquals("", AutoGoDeviceSupport.parseRouteIpv4("default dev wlan0 src 999.1.1.1"));
    }

    /** 验证无线调试配对和连接端点格式。 */
    @Test
    void validatesWirelessHostPort() {
        // 配对端口由 Android 动态生成，必须完整填写且位于标准端口范围。
        org.junit.jupiter.api.Assertions.assertTrue(AutoGoDeviceSupport.isHostPort("192.168.1.28:37123"));
        org.junit.jupiter.api.Assertions.assertTrue(AutoGoDeviceSupport.isHostPort("pixel.local:5555"));
        org.junit.jupiter.api.Assertions.assertFalse(AutoGoDeviceSupport.isHostPort("192.168.1.28"));
        org.junit.jupiter.api.Assertions.assertFalse(AutoGoDeviceSupport.isHostPort("192.168.1.28:70000"));
    }

    /** 验证同一物理设备同时连接时优先显示固定无线 IP。 */
    @Test
    void deduplicatesPhysicalDeviceAndPrefersRemoteEndpoint() {
        // USB、mDNS 和 tcpip 三个连接均对应同一 ro.serialno。
        List<String> selected = AutoGoDeviceSupport.preferRemoteEndpoints(List.of(
                        "bc29432a",
                        "adb-bc29432a-random._adb-tls-connect._tcp",
                        "192.168.31.4:5555"),
                Map.of(
                        "bc29432a", "bc29432a",
                        "adb-bc29432a-random._adb-tls-connect._tcp", "bc29432a",
                        "192.168.31.4:5555", "bc29432a"));
        assertEquals(List.of("192.168.31.4:5555"), selected);
    }

    /** 验证两台同型号但物理序列号不同的设备不会被误合并。 */
    @Test
    void keepsDifferentPhysicalDevices() {
        // 去重只依赖真实 ro.serialno，不使用 product/model。
        List<String> selected = AutoGoDeviceSupport.preferRemoteEndpoints(
                List.of("192.168.31.4:5555", "192.168.31.5:5555"),
                Map.of("192.168.31.4:5555", "device-a", "192.168.31.5:5555", "device-b"));
        assertEquals(List.of("192.168.31.4:5555", "192.168.31.5:5555"), selected);
    }

    /** 验证身份查询失败时保留全部连接，不进行猜测合并。 */
    @Test
    void keepsUnknownIdentityConnections() {
        // 空身份分别以连接序列号作为唯一键。
        List<String> selected = AutoGoDeviceSupport.preferRemoteEndpoints(
                List.of("usb-one", "usb-two"), Map.of());
        assertEquals(List.of("usb-one", "usb-two"), selected);
    }
}
