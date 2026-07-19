package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 验证引擎 RegisterMethod 到 IDE builtin catalog 的确定性转换。 */
final class AutoGoApiCatalogGeneratorTest {
    @TempDir
    Path root;

    /** 生成名称、描述、签名和源码位置。 */
    @Test
    void generatesCatalogFromGoRegistration() throws Exception {
        // 同名后出现的具体实现描述覆盖 fallback。
        Path engine = Files.createDirectories(root.resolve("engine/lua_engine/model/app"));
        Files.writeString(engine.resolve("app.go"), """
                package app
                func inject(engine Engine) {
                    engine.RegisterMethod("app.version", "获取版本", version, true)
                    engine.RegisterMethod("app.name", "获取\\\"名称\\\"", name, true)
                }
                """);
        Path project = Files.createDirectories(root.resolve("project"));
        Path output = AutoGoApiCatalogGenerator.generate(project, root.resolve("engine"));
        String json = Files.readString(output);
        assertTrue(json.contains("\"app.version\""));
        assertTrue(json.contains("\"signature\": \"version(...)\""));
        assertTrue(json.contains("获取版本"));
        assertTrue(json.contains("model/app/app.go"));
        assertTrue(json.contains("获取\\\"名称\\\""));
        assertTrue(json.contains("\"console.info\""));
        assertTrue(json.contains("console.info(...values)"));
        assertTrue(json.contains("青绿色显示"));
    }

    /** 使用用户提供的真实引擎源码验证大规模 catalog 扫描。 */
    @Test
    void generatesCatalogFromLocalEngine() throws Exception {
        // CI 没有相邻源码仓库时跳过，本机开发必须覆盖真实 1 万级注册调用。
        Path engine = Path.of("/Users/zing/Documents/SelfProject/GolangProject/autogo_scriptengine");
        assumeTrue(Files.isDirectory(engine.resolve("lua_engine")));
        Path project = Files.createDirectories(root.resolve("real-project"));
        String json = Files.readString(AutoGoApiCatalogGenerator.generate(project, engine));
        assertTrue(json.length() > 100_000, "real API catalog should contain the generated engine surface");
        assertTrue(json.contains("\"device."));
        assertTrue(json.contains("\"imgui."));
    }
}
