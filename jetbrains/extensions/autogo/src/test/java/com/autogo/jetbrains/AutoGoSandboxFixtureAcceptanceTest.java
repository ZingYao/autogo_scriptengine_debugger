package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 显式升级用户指定的沙盒工程，用于真实 IDEA/设备验收；普通测试默认跳过。 */
final class AutoGoSandboxFixtureAcceptanceTest {
    /** 通过产品生成器升级沙盒工程并验证可恢复边界。 */
    @Test
    void regeneratesExplicitSandboxFixture() throws Exception {
        // 只有调用方显式传入绝对路径才允许修改外部验收工程。
        String configured = System.getProperty("autogo.fixtureProject",
                System.getenv().getOrDefault("AUTOGO_FIXTURE_PROJECT", "")).trim();
        assumeTrue(!configured.isBlank(), "autogo.fixtureProject is not configured");
        Path fixture = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(fixture.resolve(".autogo/engine.json")),
                "fixture is not an initialized AutoGo project");

        AutoGoSettings settings = new AutoGoSettings();
        settings.setModulePolicy("ALL");
        Path backup = AutoGoProjectGenerator.regenerate(fixture, settings);

        assertNotNull(backup, "existing fixture main.go must be backed up");
        assertTrue(Files.isRegularFile(backup));
        String source = Files.readString(fixture.resolve("main.go"));
        assertTrue(source.contains("package main"));
        assertTrue(source.contains("func main()"));
        assertTrue(source.contains("/v1/capabilities"));
        assertTrue(source.contains("config.DebugObserver = observer"));
        assertTrue(Files.readString(fixture.resolve(".autogo/engine.json"))
                .contains("\"configVersion\": 1"));
    }
}
