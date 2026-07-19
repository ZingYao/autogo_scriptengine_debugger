package com.glua.jetbrains;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证插件选择与发布资产一致的 GLua Language Server。 */
final class GluaLanguageServerExecutableTest {
    /** 验证只声明实际随插件打包的操作系统和架构组合。 */
    @Test
    void bundlesOnlyRequestedTargets() {
        // 六个桌面目标受支持，32 位与未发布平台必须回退到用户配置。
        assertTrue(GluaLanguageServerExecutable.isBundled("darwin", "amd64"));
        assertTrue(GluaLanguageServerExecutable.isBundled("darwin", "arm64"));
        assertTrue(GluaLanguageServerExecutable.isBundled("linux", "amd64"));
        assertTrue(GluaLanguageServerExecutable.isBundled("linux", "arm64"));
        assertTrue(GluaLanguageServerExecutable.isBundled("windows", "amd64"));
        assertTrue(GluaLanguageServerExecutable.isBundled("windows", "arm64"));
        assertFalse(GluaLanguageServerExecutable.isBundled("linux", "386"));
        assertFalse(GluaLanguageServerExecutable.isBundled("freebsd", "amd64"));
    }

    /** 验证所有声明支持的平台都真实包含可提取的 gluals 资源。 */
    @Test
    void bundledTargetsHavePackagedExecutables() {
        // 代码声明与插件包资产必须一一对应，避免运行时才报告 missing resource。
        for (String os : new String[]{"darwin", "linux", "windows"}) {
            for (String arch : new String[]{"amd64", "arm64"}) {
                String executable = "windows".equals(os) ? "gluals.exe" : "gluals";
                String resource = "/gluals/" + os + "-" + arch + "/" + executable;
                assertNotNull(GluaLanguageServerExecutable.class.getResource(resource),
                        "missing packaged executable " + resource);
            }
        }
    }

    /** 验证 JVM 平台名称规范化为发布资产使用的名称。 */
    @Test
    void normalizesJvmPlatformNames() {
        // Apple Silicon 与 x86_64 的 JVM 别名必须映射到 Go 风格架构名。
        assertTrue(GluaLanguageServerExecutable.normalizedOs("Mac OS X").equals("darwin"));
        assertTrue(GluaLanguageServerExecutable.normalizedArch("aarch64").equals("arm64"));
        assertTrue(GluaLanguageServerExecutable.normalizedArch("x86_64").equals("amd64"));
    }

    /** 验证当前平台内置 gluals 能完成真实 LSP initialize 握手。 */
    @Test
    void bundledCurrentPlatformSpeaksLsp() throws Exception {
        // 只检查协议握手，不依赖编辑器 UI 或网络服务。
        Path executable = GluaLanguageServerExecutable.resolve("");
        Path catalog = GluaLanguageServerExecutable.resolveBuiltinCatalog();
        Process process = new ProcessBuilder(executable.toString(), "--gluals-syntax", "extended",
                "--gluals-builtin-docs", catalog.toString()).redirectErrorStream(true).start();
        try {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"processId\":null,\"rootUri\":\"file:///tmp/autogo-lsp-test\","
                    + "\"capabilities\":{}}}";
            byte[] payload = request.getBytes(StandardCharsets.UTF_8);
            process.getOutputStream().write(("Content-Length: " + payload.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            process.getOutputStream().write(payload);
            process.getOutputStream().flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                // available 避免测试线程永久阻塞在长驻语言服务器 stdout。
                int available = process.getInputStream().available();
                if (available > 0) {
                    response.write(process.getInputStream().readNBytes(available));
                    String text = response.toString(StandardCharsets.UTF_8);
                    if (text.contains("\"id\":1") && text.contains("\"capabilities\"")) {
                        return;
                    }
                }
                Thread.sleep(20);
            }
            throw new AssertionError("gluals initialize response missing: "
                    + response.toString(StandardCharsets.UTF_8));
        } finally {
            // LSP 是长驻进程，测试完成后必须强制回收。
            process.destroyForcibly();
            process.waitFor(2, TimeUnit.SECONDS);
        }
    }
}
