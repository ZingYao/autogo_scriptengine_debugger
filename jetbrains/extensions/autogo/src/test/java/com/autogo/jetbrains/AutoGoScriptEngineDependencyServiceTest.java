package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证脚本引擎依赖初始化使用实际 go.mod module，而不是硬编码猜测。 */
final class AutoGoScriptEngineDependencyServiceTest {
    @TempDir
    Path temporaryDirectory;

    /** 验证带注释和空行的标准 go.mod module 解析。 */
    @Test
    void readsModuleDirective() throws Exception {
        // require 和 replace 不得影响第一条 module 指令。
        Path goMod = Files.writeString(temporaryDirectory.resolve("go.mod"), """
                module github.com/ZingYao/autogo_scriptengine

                go 1.26

                require example.com/dependency v1.0.0
                """);
        assertEquals("github.com/ZingYao/autogo_scriptengine",
                AutoGoScriptEngineDependencyService.readModulePath(goMod));
    }

    /** 验证缺少 module 指令时拒绝生成错误 replace。 */
    @Test
    void rejectsMissingModuleDirective() throws Exception {
        // 空 go.mod 不具备可替换的模块身份。
        Path goMod = Files.writeString(temporaryDirectory.resolve("missing.mod"), "go 1.26\n");
        assertThrows(IOException.class,
                () -> AutoGoScriptEngineDependencyService.readModulePath(goMod));
    }

    /** 验证分组 require 与独立 replace 会被识别为完整本地依赖图。 */
    @Test
    void acceptsRequireAndReplaceTogether() throws Exception {
        // 伪版本与 indirect 标记都属于合法 require 表达。
        Path goMod = Files.writeString(temporaryDirectory.resolve("complete.mod"), """
                module app

                require (
                    github.com/ZingYao/autogo_scriptengine v0.0.0-00010101000000-000000000000 // indirect
                )

                replace github.com/ZingYao/autogo_scriptengine => ./.autogo/deps/autogo_scriptengine
                """);
        assertTrue(AutoGoScriptEngineDependencyService.hasRequireAndReplace(goMod,
                "github.com/ZingYao/autogo_scriptengine", ".autogo/deps/autogo_scriptengine"));
    }

    /** 验证 go mod edit 生成的单行 require 同样通过最终校验。 */
    @Test
    void acceptsStandaloneRequireAndReplace() throws Exception {
        // 单依赖项目通常不会使用 require 分组，必须兼容指令前缀。
        Path goMod = Files.writeString(temporaryDirectory.resolve("standalone.mod"), """
                module app
                require github.com/ZingYao/autogo_scriptengine v0.0.0
                replace github.com/ZingYao/autogo_scriptengine => ./.autogo/deps/autogo_scriptengine
                """);
        assertTrue(AutoGoScriptEngineDependencyService.hasRequireAndReplace(goMod,
                "github.com/ZingYao/autogo_scriptengine", ".autogo/deps/autogo_scriptengine"));
    }

    /** 验证只有 replace 不能被误判成完整依赖图。 */
    @Test
    void rejectsReplaceWithoutRequire() throws Exception {
        // 用户报告的实际缺陷正是 replace 存在但模块未加入依赖图。
        Path goMod = Files.writeString(temporaryDirectory.resolve("replace-only.mod"), """
                module app

                replace github.com/ZingYao/autogo_scriptengine => ./.autogo/deps/autogo_scriptengine
                """);
        assertFalse(AutoGoScriptEngineDependencyService.hasRequireAndReplace(goMod,
                "github.com/ZingYao/autogo_scriptengine", ".autogo/deps/autogo_scriptengine"));
    }

    /** 验证只有 require 同样不能通过最终依赖校验。 */
    @Test
    void rejectsRequireWithoutReplace() throws Exception {
        // 缺少本地 replace 会绕过已 Clone 的受管源码并访问网络版本。
        Path goMod = Files.writeString(temporaryDirectory.resolve("require-only.mod"), """
                module app

                require github.com/ZingYao/autogo_scriptengine v0.0.0
                """);
        assertFalse(AutoGoScriptEngineDependencyService.hasRequireAndReplace(goMod,
                "github.com/ZingYao/autogo_scriptengine", ".autogo/deps/autogo_scriptengine"));
    }

    /** 验证具备 DebugObserver 字段并传入 VM 的引擎版本可以进入依赖流水线。 */
    @Test
    void acceptsInstructionLevelDebuggerCapability() throws Exception {
        // 最小源码夹具同时覆盖公开配置面与 VM 注入面。
        Path luaEngine = Files.createDirectories(temporaryDirectory.resolve("supported/lua_engine"));
        Files.writeString(luaEngine.resolve("types.go"), "DebugObserver gruntime.DebugObserver\n");
        Files.writeString(luaEngine.resolve("lua_engine.go"),
                "options.DebugObserver = e.config.DebugObserver\n");
        assertDoesNotThrow(() -> AutoGoScriptEngineDependencyService.verifyRequiredEngineCapabilities(
                temporaryDirectory.resolve("supported")));
    }

    /** 验证过旧引擎不会被写入 require/replace 后留下不可编译项目。 */
    @Test
    void rejectsEngineWithoutInstructionLevelDebuggerCapability() throws Exception {
        // 旧版本即使目录完整，也没有扩展所需的 EngineConfig 字段。
        Path luaEngine = Files.createDirectories(temporaryDirectory.resolve("legacy/lua_engine"));
        Files.writeString(luaEngine.resolve("types.go"), "type EngineConfig struct {}\n");
        Files.writeString(luaEngine.resolve("lua_engine.go"), "package lua_engine\n");
        assertThrows(IOException.class,
                () -> AutoGoScriptEngineDependencyService.verifyRequiredEngineCapabilities(
                        temporaryDirectory.resolve("legacy")));
    }
}
