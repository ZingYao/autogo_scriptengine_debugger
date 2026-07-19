package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证远程同步显式 extraFiles 的路径和 glob 安全边界。 */
final class AutoGoRemoteEngineServiceTest {
    /** 验证只有可复用状态绕过启动确认。 */
    @Test
    void classifiesRunningEngineStates() {
        // running 与 paused 可继续执行，其余状态必须提示启动或报告不可达。
        assertTrue(AutoGoRemoteEngineService.isRunningState("running"));
        assertTrue(AutoGoRemoteEngineService.isRunningState("paused"));
        assertFalse(AutoGoRemoteEngineService.isRunningState("stopped"));
        assertFalse(AutoGoRemoteEngineService.isRunningState("unreachable"));
    }
    @TempDir
    Path root;

    /** 文件、目录和 glob 按稳定顺序去重收集。 */
    @Test
    void collectsExtraFiles() throws Exception {
        // 目录包含两类资源，glob 只追加 JSON 且不会重复。
        Path resources = Files.createDirectories(root.resolve("resources"));
        Files.createDirectories(resources.resolve("nested"));
        Path json = Files.writeString(resources.resolve("config.json"), "{}");
        Path image = Files.writeString(resources.resolve("nested/icon.txt"), "icon");
        Set<Path> output = new LinkedHashSet<>();
        AutoGoRemoteEngineService.collectExtra(root, "resources", output);
        AutoGoRemoteEngineService.collectExtra(root, "resources/**/*.json", output);
        assertEquals(Set.of(json.toAbsolutePath().normalize(), image.toAbsolutePath().normalize()), output);
    }

    /** 项目外路径被拒绝。 */
    @Test
    void rejectsEscapingExtraPath() {
        // 父目录逃逸不能进入远端上传 manifest。
        assertThrows(Exception.class,
                () -> AutoGoRemoteEngineService.collectExtra(root, "../secret", new LinkedHashSet<>()));
    }

    /** 验证直接远程地址强制使用 HTTPS，仅本机调试允许 HTTP。 */
    @Test
    void validatesSecureDirectEndpoint() throws Exception {
        // 公网 HTTPS 和 loopback HTTP 可用，局域网明文连接会泄漏 bearer token，必须拒绝。
        assertEquals(URI.create("https://engine.example.test:38696"),
                AutoGoRemoteEngineService.validateDirectEndpoint("https://engine.example.test:38696/"));
        assertEquals(URI.create("http://127.0.0.1:38696"),
                AutoGoRemoteEngineService.validateDirectEndpoint("http://127.0.0.1:38696"));
        assertThrows(Exception.class,
                () -> AutoGoRemoteEngineService.validateDirectEndpoint("http://192.168.1.20:38696"));
        assertThrows(Exception.class,
                () -> AutoGoRemoteEngineService.validateDirectEndpoint("https://user:secret@example.test"));
    }

    /** 验证 DAP 端口只接受协议范围内的数字。 */
    @Test
    void parsesRemoteDapPort() {
        // 合法端口用于直连；零、超范围和损坏 JSON 回退到调用方默认端口。
        assertEquals(40123, AutoGoRemoteEngineService.parseDapPort("{\"dap\":{\"port\":40123}}", 38697));
        assertEquals(38697, AutoGoRemoteEngineService.parseDapPort("{\"dap\":{\"port\":0}}", 38697));
        assertEquals(38697, AutoGoRemoteEngineService.parseDapPort("invalid", 38697));
    }

    /** 验证远程 diff 响应使用严格 JSON 和稳定路径语义。 */
    @Test
    void validatesUploadPathResponse() throws Exception {
        // 合法转义由 JSON 解析器处理，缺失数组、错误类型和重复路径都必须拒绝。
        assertEquals(java.util.List.of("scripts/main.lua", "模块.lua"),
                AutoGoRemoteEngineService.parseUploadPaths(
                        "{\"upload\":[\"scripts/main.lua\",\"\\u6a21\\u5757.lua\"]}"));
        assertThrows(Exception.class, () -> AutoGoRemoteEngineService.parseUploadPaths("{}"));
        assertThrows(Exception.class,
                () -> AutoGoRemoteEngineService.parseUploadPaths("{\"upload\":[1]}"));
        assertThrows(Exception.class,
                () -> AutoGoRemoteEngineService.parseUploadPaths("{\"upload\":[\"a\",\"a\"]}"));
    }
}
