package com.autogo.jetbrains;

import com.glua.jetbrains.GluaBuiltinCatalog;
import com.glua.jetbrains.GluaSettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 在 ag 初始化后 Clone AutoGo Script Engine 并写入项目 Go module replace。 */
public final class AutoGoScriptEngineDependencyService {
    static final String REPOSITORY_URL = "https://github.com/ZingYao/autogo_scriptengine.git";
    static final String RELATIVE_DIRECTORY = ".autogo/deps/autogo_scriptengine";
    static final String LOCAL_REQUIRE_VERSION = "v0.0.0";
    private static final Pattern MODULE_LINE = Pattern.compile("(?m)^\\s*module\\s+([^\\s]+)\\s*$");

    private AutoGoScriptEngineDependencyService() {
        // 初始化服务只提供可测试的静态流水线。
    }

    /** Clone 或快进更新依赖，然后依次执行 go mod edit 与 go mod tidy。 */
    public static void initialize(Project project) {
        // 项目根目录和 go.mod 是 replace 的必要前置条件。
        String basePath = project.getBasePath();
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        if (basePath == null) {
            // 无根目录时不得把 clone 写到 IDE 工作目录。
            console.error("无法初始化 autogo_scriptengine：当前项目没有根目录。");
            return;
        }
        Path root = Path.of(basePath).toAbsolutePath().normalize();
        Path projectGoMod = root.resolve("go.mod");
        if (!Files.isRegularFile(projectGoMod)) {
            // ag init 预期生成根 go.mod，缺失时 replace 没有目标。
            console.error("无法初始化 autogo_scriptengine：项目根目录缺少 go.mod。");
            return;
        }
        Path dependency = root.resolve(RELATIVE_DIRECTORY).normalize();
        AutoGoProcessService process = project.getService(AutoGoProcessService.class);
        List<String> gitArguments;
        if (Files.isDirectory(dependency.resolve(".git"))) {
            // 已存在受管仓库时只允许 fast-forward，避免覆盖用户或异常本地改动。
            gitArguments = List.of("-C", dependency.toString(), "pull", "--ff-only");
            console.info("正在更新本地 autogo_scriptengine 依赖……");
        } else if (Files.exists(dependency)) {
            // 非 Git 目录可能包含用户文件，禁止自动删除或覆盖。
            console.error("依赖目录已存在但不是 Git 仓库，请处理后重试：" + dependency);
            return;
        } else {
            // clone 目标固定在 .autogo/deps 下，便于 IDEA 与 VSCode 共享。
            try {
                Files.createDirectories(dependency.getParent());
            } catch (IOException error) {
                // 父目录创建失败时不启动 Git。
                console.error("无法创建依赖目录：" + error.getMessage());
                return;
            }
            gitArguments = List.of("clone", "--origin", "origin", REPOSITORY_URL, dependency.toString());
            console.info("正在 Clone autogo_scriptengine 到 " + dependency + "……");
        }
        process.run("git", gitArguments, true, gitExit -> {
            if (gitExit != 0) {
                // Git 的网络、认证和代理错误已输出到统一 Console。
                console.error("autogo_scriptengine Clone/更新失败，未修改 go.mod。");
                return;
            }
            applyReplace(project, dependency);
        });
    }

    private static void applyReplace(Project project, Path dependency) {
        // replace 的左侧必须来自实际 clone 的 go.mod，避免仓库改名后写入错误模块。
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        String modulePath;
        try {
            modulePath = readModulePath(dependency.resolve("go.mod"));
            verifyRequiredEngineCapabilities(dependency);
        } catch (IOException error) {
            // Clone 内容不完整或版本过旧时保持项目 go.mod 不变，避免生成必然无法编译的入口。
            console.error("autogo_scriptengine 依赖不可用：" + error.getMessage());
            return;
        }
        AutoGoSettings settings = ApplicationManager.getApplication().getService(AutoGoSettings.class);
        String goExecutable = settings.getGoPath().isBlank() ? "go" : settings.getGoPath();
        String require = modulePath + "@" + LOCAL_REQUIRE_VERSION;
        String replace = modulePath + "=./" + RELATIVE_DIRECTORY;
        AutoGoProcessService process = project.getService(AutoGoProcessService.class);
        process.run(goExecutable, List.of("mod", "edit", "-require=" + require), true, requireExit -> {
            if (requireExit != 0) {
                // 根 main.go 直接导入脚本引擎，因此 require 是 replace 生效前的硬依赖。
                console.error("写入 autogo_scriptengine require 失败。");
                return;
            }
            console.info("已写入 Go require：" + require);
            process.run(goExecutable, List.of("mod", "edit", "-replace=" + replace), true, replaceExit -> {
                if (replaceExit != 0) {
                    // require 已写入但 replace 失败时不执行 tidy，避免转而下载远端伪版本。
                    console.error("require 已写入，但 autogo_scriptengine replace 写入失败。");
                    return;
                }
                console.info("已写入 Go replace：" + replace);
                process.run(goExecutable, List.of("mod", "tidy"), true, tidyExit -> {
                    if (tidyExit != 0) {
                        // require 与 replace 已成功但 tidy 失败时明确区分状态。
                        console.error("require/replace 已写入，但 go mod tidy 失败；请检查 Go 版本和依赖日志。");
                        return;
                    }
                    ensureDependencyGraph(project, dependency, modulePath, goExecutable, require, replace);
                });
            });
        });
    }

    private static void ensureDependencyGraph(Project project, Path dependency, String modulePath,
                                              String goExecutable, String require, String replace) {
        // tidy 可能在入口源码尚未被 Go 识别时移除 require；初始化成功前必须验证最终依赖图。
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        Path goMod = Path.of(project.getBasePath()).toAbsolutePath().normalize().resolve("go.mod");
        try {
            if (hasRequireAndReplace(goMod, modulePath, RELATIVE_DIRECTORY)) {
                // 完整依赖图可以直接进入 catalog 生成阶段。
                finishInitialization(project, dependency);
                return;
            }
        } catch (IOException error) {
            // 无法读取最终 go.mod 时禁止把初始化报告为成功。
            console.error("无法校验 Go 依赖图：" + error.getMessage());
            return;
        }
        console.info("go mod tidy 后依赖图不完整，正在补写 autogo_scriptengine require/replace……");
        AutoGoProcessService process = project.getService(AutoGoProcessService.class);
        process.run(goExecutable, List.of("mod", "edit", "-require=" + require), true, requireExit -> {
            if (requireExit != 0) {
                // require 补写失败后不能继续生成与运行宿主。
                console.error("补写 autogo_scriptengine require 失败，初始化未完成。");
                return;
            }
            process.run(goExecutable, List.of("mod", "edit", "-replace=" + replace), true, replaceExit -> {
                if (replaceExit != 0) {
                    // replace 补写失败时保留 require，方便用户从日志继续修复。
                    console.error("补写 autogo_scriptengine replace 失败，初始化未完成。");
                    return;
                }
                try {
                    if (!hasRequireAndReplace(goMod, modulePath, RELATIVE_DIRECTORY)) {
                        // Go 命令成功但文件内容不满足约束时给出确定性失败，而不是假成功。
                        console.error("autogo_scriptengine 依赖图仍不完整：必须同时包含 require 与 replace。");
                        return;
                    }
                } catch (IOException error) {
                    // 最终文件读取失败同样不进入成功路径。
                    console.error("无法复核 Go 依赖图：" + error.getMessage());
                    return;
                }
                finishInitialization(project, dependency);
            });
        });
    }

    private static void finishInitialization(Project project, Path dependency) {
        // 只有 require 与 replace 均已落盘后，才生成与实际引擎版本一致的 API catalog。
        AutoGoConsoleService console = project.getService(AutoGoConsoleService.class);
        try {
            Path projectRoot = Path.of(project.getBasePath()).toAbsolutePath().normalize();
            Path catalog = AutoGoApiCatalogGenerator.generate(projectRoot, dependency);
            GluaSettings gluaSettings = ApplicationManager.getApplication().getService(GluaSettings.class);
            List<String> docs = new ArrayList<>(gluaSettings.builtinDocs());
            if (!docs.contains(catalog.toString())) {
                // 只自动追加一次，保留用户自己选择的其他 catalog。
                docs.add(catalog.toString());
                gluaSettings.setBuiltinDocs(docs);
            }
            GluaBuiltinCatalog.getInstance().reload();
            console.info("已生成 AutoGo API catalog：" + catalog);
        } catch (IOException error) {
            // 依赖可用但 catalog 失败时不回滚 go.mod，明确提示补全可能不完整。
            console.error("autogo_scriptengine 已就绪，但 API catalog 生成失败：" + error.getMessage());
        }
        console.info("autogo_scriptengine 已 Clone，并完成 go mod require、replace、tidy 与 API catalog。");
    }

    /** 校验 go.mod 同时包含指定模块的 require 与本地 replace。 */
    static boolean hasRequireAndReplace(Path goMod, String modulePath, String relativeDirectory) throws IOException {
        // 使用 Go 指令行级匹配，兼容单行和分组 require/replace，避免把注释误判成依赖。
        String content = Files.readString(goMod, StandardCharsets.UTF_8);
        String quotedModule = Pattern.quote(modulePath);
        String quotedDirectory = Pattern.quote("./" + relativeDirectory);
        boolean hasRequire = Pattern.compile("(?m)^[ \\t]*(?:require[ \\t]+)?" + quotedModule
                        + "[ \\t]+v\\S+(?:[ \\t]+//[ \\t]*indirect)?[ \\t]*$")
                .matcher(content).find();
        boolean hasReplace = Pattern.compile("(?m)^[ \\t]*(?:replace[ \\t]+)?" + quotedModule
                        + "(?:[ \\t]+v\\S+)?[ \\t]*=>[ \\t]*" + quotedDirectory + "[ \\t]*$")
                .matcher(content).find();
        return hasRequire && hasReplace;
    }

    /** 从 go.mod 读取唯一 module path。 */
    static String readModulePath(Path goMod) throws IOException {
        // module 指令必须存在且非空，其他 require/replace 行不参与解析。
        String content = Files.readString(goMod, StandardCharsets.UTF_8);
        Matcher matcher = MODULE_LINE.matcher(content);
        if (!matcher.find()) {
            // 缺少 module 会导致 go mod edit 的左侧不可确定。
            throw new IOException("go.mod 缺少 module 指令：" + goMod);
        }
        return matcher.group(1).trim();
    }

    /** 校验 Clone 版本具备扩展生成入口所需的指令级调试注入能力。 */
    static void verifyRequiredEngineCapabilities(Path dependency) throws IOException {
        // 生成的根 main.go 会直接设置 EngineConfig.DebugObserver，缺少字段时 Go 编译一定失败。
        Path typesFile = dependency.resolve("lua_engine/types.go");
        Path engineFile = dependency.resolve("lua_engine/lua_engine.go");
        if (!Files.isRegularFile(typesFile) || !Files.isRegularFile(engineFile)) {
            // 不完整仓库不能进入 require/replace 流水线。
            throw new IOException("缺少 lua_engine 源码文件，请重新 Clone 依赖");
        }
        String typesSource = Files.readString(typesFile, StandardCharsets.UTF_8);
        String engineSource = Files.readString(engineFile, StandardCharsets.UTF_8);
        if (!typesSource.matches("(?s).*\\bDebugObserver\\s+gruntime\\.DebugObserver\\b.*")) {
            // 明确指出发布前置条件，避免把后续 go build 错误误判为用户项目问题。
            throw new IOException("当前版本缺少 EngineConfig.DebugObserver，请先更新到支持 IDEA 原生 DAP 的引擎版本");
        }
        if (!engineSource.matches("(?s).*options\\.DebugObserver\\s*=\\s*e\\.config\\.DebugObserver.*")) {
            // 只有配置字段但未传入 VM 时，断点与单步不会生效，也不能视为兼容。
            throw new IOException("当前版本未将 DebugObserver 注入 Lua VM，请更新 autogo_scriptengine");
        }
    }
}
