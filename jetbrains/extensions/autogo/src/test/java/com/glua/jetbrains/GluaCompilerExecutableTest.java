package com.glua.jetbrains;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 IDEA 扩展内置 GLuac 的平台覆盖和提取行为。 */
final class GluaCompilerExecutableTest {
    /** Windows 与 macOS 常见架构必须全部包含在插件产物中。 */
    @Test
    void bundledPlatformsMatchReleaseTargets() {
        // 当前发布范围为 Windows/macOS 的 amd64 与 arm64。
        assertTrue(GluaCompilerExecutable.isBundled("darwin", "amd64"));
        assertTrue(GluaCompilerExecutable.isBundled("darwin", "arm64"));
        assertTrue(GluaCompilerExecutable.isBundled("windows", "amd64"));
        assertTrue(GluaCompilerExecutable.isBundled("windows", "arm64"));
        assertFalse(GluaCompilerExecutable.isBundled("linux", "amd64"));
        assertFalse(GluaCompilerExecutable.isBundled("windows", "386"));
    }

    /** 当前平台应能从插件资源提取出真实可执行文件。 */
    @Test
    void resolvesBundledCompilerForCurrentPlatform() throws Exception {
        // CI 在受支持平台运行时应得到非空且可执行的临时文件。
        String os = GluaLanguageServerExecutable.normalizedOs(System.getProperty("os.name", ""));
        String arch = GluaLanguageServerExecutable.normalizedArch(System.getProperty("os.arch", ""));
        if (!GluaCompilerExecutable.isBundled(os, arch)) {
            return;
        }
        Path executable = GluaCompilerExecutable.resolve("");
        assertTrue(Files.isRegularFile(executable));
        assertTrue(os.equals("windows") || Files.isExecutable(executable));
    }
}
