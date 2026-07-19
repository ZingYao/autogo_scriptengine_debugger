package com.autogo.jetbrains;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 验证项目初始化生成器的模块策略、原子产物和用户文件边界。 */
final class AutoGoProjectGeneratorTest {
    @TempDir
    Path projectRoot;

    /** 验证模块名单去重、注释和顺序语义。 */
    @Test
    void normalizesModuleEntries() {
        // 重复模块只保留第一次，注释和空行不进入生成配置。
        assertEquals(List.of("app", "device", "motion"),
                AutoGoProjectGenerator.normalizeModules("app\n# disabled\ndevice\napp\n\nmotion"));
    }

    /** 验证非法模块名在写文件前被拒绝。 */
    @Test
    void rejectsUnsafeModuleName() {
        // 路径穿越不能进入模块注册表或生成源码。
        assertThrows(IllegalArgumentException.class,
                () -> AutoGoProjectGenerator.normalizeModules("app\n../outside"));
    }

    /** 验证白名单配置、Go 宿主和 manifest 一次生成。 */
    @Test
    void generatesSharedEngineArtifacts() throws Exception {
        // 设置模拟用户在 IDEA 配置页选择白名单和自定义初始化代码。
        Path customInitializer = Files.writeString(projectRoot.resolve("custom_init.go"), """
                package main

                import "github.com/ZingYao/autogo_scriptengine/lua_engine"

                func customInitialize(engine *lua_engine.LuaEngine) error { return nil }
                """);
        AutoGoSettings settings = new AutoGoSettings();
        settings.setModulePolicy("ALLOWLIST");
        settings.setModuleEntries("app\ndevice\napp");
        settings.setCustomInitializerPath(customInitializer.toString());

        AutoGoProjectGenerator.generate(projectRoot, settings, "android");

        String config = Files.readString(projectRoot.resolve(".autogo/engine.json"));
        String source = Files.readString(projectRoot.resolve("main.go"));
        String manifest = Files.readString(projectRoot.resolve(".autogo/generated/manifest.json"));
        assertTrue(config.contains("\"modulePolicy\": \"ALLOWLIST\""));
        assertTrue(config.contains("\"modules\": [\"app\", \"device\"]"));
        assertTrue(config.contains("\"customInitializer\": \"custom_init.go\""));
        assertTrue(source.contains("ModulePolicy = \"ALLOWLIST\""));
        assertTrue(source.contains("package main"));
        assertTrue(source.contains("func main()"));
        assertTrue(Files.notExists(projectRoot.resolve(".autogo/generated/engine_init.go")));
        assertTrue(source.contains("config.WhiteList"));
        assertTrue(source.contains("customInitialize(engine)"));
        assertTrue(source.contains("runtimedap.NewServer"));
        assertTrue(source.contains("runtimedap.NewServer(\"127.0.0.1:0\")"));
        assertTrue(source.contains("service\": \"autogo-script-engine"));
        assertTrue(source.contains("/v1/events"));
        assertTrue(source.contains("config.DebugObserver = observer"));
        assertTrue(source.contains("/v1/engine/restart"));
        assertTrue(source.contains("/v1/files/upload"));
        assertTrue(source.contains("AUTOGO_CONTROL_TOKEN"));
        assertTrue(source.contains("c.state, c.lastError = \"running\", \"\""));
        assertTrue(source.contains("run completed: "));
        assertTrue(source.contains("lua lifecycle: [Info] 开始执行："));
        assertTrue(source.contains("gruntime.ErrorObject(err)"));
        assertTrue(source.contains("gruntime.Traceback(message, runtimeErr.TracebackFrames)"));
        assertTrue(source.contains("/?.glua;"));
        assertTrue(source.contains("return dofile(%s)"));
        assertTrue(source.contains("config.AllowProcess = true"));
        assertTrue(source.contains("root, err := filepath.Abs(root)"));
        assertTrue(source.contains("os.Chdir(directoryPath)"));
        assertTrue(source.contains("os.Chdir(previousDirectory)"));
        assertTrue(source.contains("执行完成：%s，耗时 %s"));
        assertTrue(source.contains("c.resolveEntryLocked(c.manifestID, defaultEntry)"));
        assertTrue(source.contains("go c.executeLua(luaEngine, defaultEntry)"));
        assertTrue(manifest.contains("\"../../main.go\""));
        assertEquals("console.info(\"AutoGo Script Engine started\")\n",
                Files.readString(projectRoot.resolve("scripts/main.glua")));
        assertTrue(config.contains("\"entry\": \"scripts/main.glua\""));
        // 生成器只引用用户文件，不得修改或删除其内容。
        assertTrue(Files.readString(customInitializer).contains("func customInitialize"));
    }

    /** 验证默认脚本重复初始化时不会覆盖用户代码。 */
    @Test
    void preservesExistingDefaultScript() throws Exception {
        // 用户脚本一旦存在，生成器只返回路径而不改写内容。
        Path entry = projectRoot.resolve("scripts/main.glua");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "print('keep')\n");
        assertEquals(entry, AutoGoProjectGenerator.ensureDefaultScript(projectRoot));
        assertEquals("print('keep')\n", Files.readString(entry));
    }

    /** 验证重新应用模块策略保留项目远程配置并备份旧入口。 */
    @Test
    void regeneratesExistingProjectWithoutDiscardingSharedConfig() throws Exception {
        // 先生成标准项目，再模拟用户修改跨 IDE 同步与远程字段。
        AutoGoSettings settings = new AutoGoSettings();
        AutoGoProjectGenerator.generate(projectRoot, settings, "android");
        Path config = projectRoot.resolve(".autogo/engine.json");
        String customized = Files.readString(config)
                .replace("\"endpoint\": \"\"", "\"endpoint\": \"https://device.example.test\"")
                .replace("\"extraFiles\": []", "\"extraFiles\": [\"shared/**\"]")
                .replace("\n}", ",\n  \"unknownFutureField\": {\"keep\": true}\n}");
        Files.writeString(config, customized);
        Files.writeString(projectRoot.resolve("main.go"), "// user-edited-root-entry\npackage main\n");
        settings.setModulePolicy("ALLOWLIST");
        settings.setModuleEntries("app\ndevice");

        Path backup = AutoGoProjectGenerator.regenerate(projectRoot, settings);

        assertTrue(backup != null && Files.readString(backup).contains("user-edited-root-entry"));
        String regeneratedConfig = Files.readString(config);
        assertTrue(regeneratedConfig.contains("https://device.example.test"));
        assertTrue(regeneratedConfig.contains("shared/**"));
        assertTrue(regeneratedConfig.contains("unknownFutureField"));
        assertTrue(regeneratedConfig.contains("\"modulePolicy\": \"ALLOWLIST\""));
        assertTrue(Files.readString(projectRoot.resolve("main.go")).contains("config.WhiteList"));
    }

    /** 验证生成的根 main.go 能通过 gofmt 语法解析。 */
    @Test
    void generatedRootMainIsValidGoSyntax() throws Exception {
        // gofmt 非零退出表示生成模板存在 Go 语法错误，不能交给 ag run。
        String source = AutoGoProjectGenerator.renderEngineSource(
                "android", "DENYLIST", List.of("opencv", "yolo"), false);
        Path mainFile = Files.writeString(projectRoot.resolve("main.go"), source);
        Process process = new ProcessBuilder("gofmt", "-w", mainFile.toString())
                .redirectErrorStream(true)
                .start();
        boolean completed = process.waitFor(10, TimeUnit.SECONDS);
        assertTrue(completed, "gofmt should complete");
        String gofmtOutput = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), "gofmt failed: " + gofmtOutput);
        String formatted = Files.readString(mainFile);
        assertTrue(formatted.contains("package main"));
        assertTrue(formatted.contains("func main()"));
    }

    @Test
    void generatedHostContainsPidMetadataAndStartupLock() throws Exception {
        // 新建项目必须携带与 VSCode 相同的 PID 元数据和设备端原子启动锁协议。
        AutoGoSettings settings = new AutoGoSettings();
        AutoGoProjectGenerator.generate(projectRoot, settings, "android");
        String generatedMain = Files.readString(projectRoot.resolve("main.go"));
        assertTrue(generatedMain.contains("engine.pid.json"));
        assertTrue(generatedMain.contains("engine.start.lock"));
        assertTrue(generatedMain.contains("syscall.Kill(pid, 0)"));
        assertTrue(generatedMain.contains("writeRuntimePID"));
    }

    /** 使用用户提供的本地 autogo_scriptengine 验证生成入口能够真实 Go 编译。 */
    @Test
    void generatedRootMainCompilesAgainstLocalEngine() throws Exception {
        // 本地参考仓库不存在的 CI 环境跳过；当前开发机必须执行真实依赖编译。
        assumeTrue("1".equals(System.getenv("AUTOGO_RUN_MOBILE_HOST_BUILD_TESTS")),
                "mobile host build test requires Android/iOS build dependencies");
        Path engine = Path.of("/Users/zing/Documents/SelfProject/GolangProject/autogo_scriptengine");
        assumeTrue(Files.isRegularFile(engine.resolve("go.mod")), "local autogo_scriptengine is unavailable");
        String source = AutoGoProjectGenerator.renderEngineSource("android", "ALL", List.of(), false);
        Files.writeString(projectRoot.resolve("main.go"), source);
        Path goja = Path.of("/Users/zing/Documents/goja-debug");
        assumeTrue(Files.isRegularFile(goja.resolve("go.mod")), "local goja debug fork is unavailable");
        Files.writeString(projectRoot.resolve("go.mod"), """
                module autogo-generated-entry-test

                go 1.26

                require github.com/ZingYao/autogo_scriptengine v0.0.0

                replace github.com/ZingYao/autogo_scriptengine => %s
                replace github.com/dop251/goja => %s
                """.formatted(engine, goja));
        ProcessBuilder tidyBuilder = new ProcessBuilder("go", "mod", "tidy")
                .directory(projectRoot.toFile()).redirectErrorStream(true);
        tidyBuilder.environment().put("CGO_ENABLED", "0");
        Process tidy = tidyBuilder.start();
        assertTrue(tidy.waitFor(60, TimeUnit.SECONDS), "go mod tidy should complete");
        String tidyOutput = new String(tidy.getInputStream().readAllBytes());
        assertEquals(0, tidy.exitValue(), "go mod tidy failed: " + tidyOutput);
        ProcessBuilder buildBuilder = new ProcessBuilder("go", "build", ".")
                .directory(projectRoot.toFile()).redirectErrorStream(true);
        buildBuilder.environment().put("CGO_ENABLED", "0");
        Process build = buildBuilder.start();
        assertTrue(build.waitFor(60, TimeUnit.SECONDS), "go build should complete");
        String buildOutput = new String(build.getInputStream().readAllBytes());
        assertEquals(0, build.exitValue(), "generated root main.go failed to build: " + buildOutput);
    }

    /** 启动真实生成宿主，验证能力协商、令牌保护、原子 manifest 与日志游标。 */
    @Test
    void generatedRootMainServesRemoteControlProtocol() throws Exception {
        // 本地引擎缺失时只跳过开发机专属集成测试，常规模板测试仍会执行。
        assumeTrue("1".equals(System.getenv("AUTOGO_RUN_MOBILE_HOST_BUILD_TESTS")),
                "mobile host protocol test requires Android/iOS build dependencies");
        Path engine = Path.of("/Users/zing/Documents/SelfProject/GolangProject/autogo_scriptengine");
        assumeTrue(Files.isRegularFile(engine.resolve("go.mod")), "local autogo_scriptengine is unavailable");
        Files.writeString(projectRoot.resolve("main.go"),
                AutoGoProjectGenerator.renderEngineSource("android", "ALL", List.of(), false));
        Path goja = Path.of("/Users/zing/Documents/goja-debug");
        assumeTrue(Files.isRegularFile(goja.resolve("go.mod")), "local goja debug fork is unavailable");
        Files.writeString(projectRoot.resolve("go.mod"), """
                module autogo-generated-protocol-test

                go 1.26

                require github.com/ZingYao/autogo_scriptengine v0.0.0

                replace github.com/ZingYao/autogo_scriptengine => %s
                replace github.com/dop251/goja => %s
                """.formatted(engine, goja));
        ProcessBuilder tidyBuilder = new ProcessBuilder("go", "mod", "tidy")
                .directory(projectRoot.toFile()).redirectErrorStream(true);
        tidyBuilder.environment().put("CGO_ENABLED", "0");
        Process tidy = tidyBuilder.start();
        assertTrue(tidy.waitFor(60, TimeUnit.SECONDS), "protocol host tidy should complete");
        String tidyOutput = new String(tidy.getInputStream().readAllBytes());
        assertEquals(0, tidy.exitValue(), "protocol host tidy failed: " + tidyOutput);
        Path executable = projectRoot.resolve("autogo-protocol-host");
        ProcessBuilder buildBuilder = new ProcessBuilder("go", "build", "-o", executable.toString(), ".")
                .directory(projectRoot.toFile()).redirectErrorStream(true);
        buildBuilder.environment().put("CGO_ENABLED", "0");
        Process build = buildBuilder.start();
        assertTrue(build.waitFor(60, TimeUnit.SECONDS), "protocol host build should complete");
        String buildOutput = new String(build.getInputStream().readAllBytes());
        assertEquals(0, build.exitValue(), "protocol host build failed: " + buildOutput);

        int controlPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            // 释放测试专用端口后立即启动宿主，避免占用扩展固定的 38696。
            controlPort = socket.getLocalPort();
        }
        Process host = null;
        try {
            // 环境在启动前设置，避免进程短暂监听扩展固定的 38696 端口。
            ProcessBuilder hostBuilder = new ProcessBuilder(executable.toString())
                    .directory(projectRoot.toFile()).redirectErrorStream(true);
            hostBuilder.environment().put("AUTOGO_AUTOSTART", "0");
            hostBuilder.environment().put("AUTOGO_CONTROL_LISTEN", "127.0.0.1:" + controlPort);
            hostBuilder.environment().put("AUTOGO_CONTROL_TOKEN", "test-token");
            hostBuilder.environment().put("AUTOGO_REMOTE_ROOT", projectRoot.resolve("remote").toString());
            host = hostBuilder.start();

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            URI base = URI.create("http://127.0.0.1:" + controlPort);
            HttpResponse<String> capabilities = waitForHttp(client, base.resolve("/v1/capabilities"));
            assertEquals(200, capabilities.statusCode());
            AutoGoRemoteEngineService.validateCapabilities(capabilities.body(),
                    java.util.Set.of("lua", "glua", "gluac", "javascript", "js", "dap", "incremental-sync"));
            JsonObject pidMetadata = JsonParser.parseString(
                    Files.readString(projectRoot.resolve("remote/engine.pid.json"))).getAsJsonObject();
            assertEquals(host.pid(), pidMetadata.get("pid").getAsLong());
            assertEquals(controlPort, pidMetadata.get("controlPort").getAsInt());
            assertTrue(pidMetadata.get("instanceId").getAsString().startsWith("autogo-"));

            // 相同 remoteRoot 的第二个宿主必须被设备端原子锁立即拒绝。
            Process duplicate = hostBuilder.start();
            assertTrue(duplicate.waitFor(5, TimeUnit.SECONDS), "duplicate host should fail fast");
            String duplicateOutput = new String(duplicate.getInputStream().readAllBytes());
            assertTrue(duplicate.exitValue() != 0, "duplicate host must not start");
            assertTrue(duplicateOutput.contains("已运行"), "duplicate host error should explain lock owner");

            HttpRequest unauthorized = HttpRequest.newBuilder(base.resolve("/v1/files/diff"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"manifestId\":\"m1\",\"files\":[]}"))
                    .build();
            assertEquals(401, client.send(unauthorized, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, postAuthorized(client, base.resolve("/v1/files/diff"),
                    "{\"manifestId\":\"m1\",\"files\":[]}").statusCode());
            assertEquals(200, postAuthorized(client, base.resolve("/v1/files/commit"),
                    "{\"manifestId\":\"m1\"}").statusCode());

            HttpResponse<String> firstLogs = getAuthorized(client, base.resolve("/v1/logs?cursor=0"));
            JsonObject firstLogDocument = JsonParser.parseString(firstLogs.body()).getAsJsonObject();
            assertTrue(firstLogDocument.getAsJsonArray("entries").size() > 0);
            long cursor = firstLogDocument.get("cursor").getAsLong();
            HttpResponse<String> secondLogs = getAuthorized(client,
                    base.resolve("/v1/logs?cursor=" + cursor));
            assertEquals(0, JsonParser.parseString(secondLogs.body()).getAsJsonObject()
                    .getAsJsonArray("entries").size());
        } finally {
            // 测试无论断言结果如何都终止本地宿主，避免污染后续沙盒端口。
            if (host != null) {
                host.destroyForcibly();
                host.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static HttpResponse<String> waitForHttp(HttpClient client, URI endpoint) throws Exception {
        // Go 进程启动存在短暂延迟，最多等待五秒并保留最后一次连接异常。
        Exception lastError = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                return client.send(HttpRequest.newBuilder(endpoint).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            } catch (Exception error) {
                lastError = error;
                Thread.sleep(100);
            }
        }
        throw lastError == null ? new IllegalStateException("control endpoint unavailable") : lastError;
    }

    private static HttpResponse<String> postAuthorized(HttpClient client, URI endpoint, String body)
            throws Exception {
        // 修改端点必须携带 bearer token。
        return client.send(HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer test-token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> getAuthorized(HttpClient client, URI endpoint) throws Exception {
        // 日志也可能包含运行信息，因此使用与修改端点相同的令牌保护。
        return client.send(HttpRequest.newBuilder(endpoint)
                .header("Authorization", "Bearer test-token").GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** 验证模块选择目录来自 autogo_scriptengine 的 AutoGo 注册表。 */
    @Test
    void exposesAutoGoInjectableModules() {
        // Lua 标准库不属于 EngineConfig 白黑名单，AutoGo 模块才允许用户选择。
        assertTrue(AutoGoModuleCatalog.defaultModules().contains("app"));
        assertTrue(AutoGoModuleCatalog.defaultModules().contains("device"));
        assertTrue(AutoGoModuleCatalog.defaultModules().contains("opencv"));
        assertTrue(!AutoGoModuleCatalog.defaultModules().contains("_G"));
        assertTrue(!AutoGoModuleCatalog.defaultModules().contains("glua.json"));
    }
}
