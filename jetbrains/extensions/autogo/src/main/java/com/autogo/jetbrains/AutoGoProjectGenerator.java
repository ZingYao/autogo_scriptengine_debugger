package com.autogo.jetbrains;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Stream;

/** 生成跨 IDE 共享的 AutoGo 远程引擎配置和 Go 宿主边界。 */
public final class AutoGoProjectGenerator {
    private static final int CONFIG_VERSION = 1;
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();

    private AutoGoProjectGenerator() {
        // 生成器只提供无状态静态入口。
    }

    /** 根据当前设置原子生成引擎配置、Go 初始化代码和生成清单。 */
    public static void generate(Path projectRoot, AutoGoSettings settings, String target) throws IOException {
        // 先规范化和校验全部输入，避免生成一半后才发现配置错误。
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            // ag init 成功后项目根目录必须存在。
            throw new IOException("项目根目录不存在：" + root);
        }
        String policy = settings.getModulePolicy();
        List<String> modules = normalizeModules(settings.getModuleEntries());
        Path customInitializer = normalizeCustomInitializer(root, settings.getCustomInitializerPath());

        Path autogoDir = root.resolve(".autogo");
        Path generatedDir = autogoDir.resolve("generated");
        Files.createDirectories(generatedDir);

        String config = renderConfig(target, policy, modules, customInitializer, root);
        boolean hasCustomInitializer = prepareCustomInitializer(root, customInitializer);
        String source = renderEngineSource(target, policy, modules, hasCustomInitializer);
        String manifest = renderManifest(config, source);
        writeAtomic(autogoDir.resolve("engine.json"), config);
        // ag run 只识别项目根目录的 package main 入口。
        writeAtomic(root.resolve("main.go"), source);
        ensureDefaultScript(root);
        Files.deleteIfExists(generatedDir.resolve("engine_init.go"));
        writeAtomic(generatedDir.resolve("manifest.json"), manifest);
    }

    /** 创建默认 GLua 脚本；已有文件保持原样，避免重新生成时覆盖用户代码。 */
    public static Path ensureDefaultScript(Path projectRoot) throws IOException {
        // 默认入口统一放在 scripts 目录，IDEA 与 VSCode 使用同一项目约定。
        Path entry = projectRoot.toAbsolutePath().normalize().resolve("scripts/main.glua");
        Files.createDirectories(entry.getParent());
        if (!Files.exists(entry)) {
            // 仅首次初始化写入可直接运行的示例。
            writeAtomic(entry, "console.info(\"AutoGo Script Engine started\")\n");
        }
        return entry;
    }

    /** 将设置中的模块策略应用到现有项目，同时保留跨 IDE 项目级配置。 */
    public static Path regenerate(Path projectRoot, AutoGoSettings settings) throws IOException {
        // 非破坏性重生成要求已有 v1 项目配置，不允许猜测目标平台。
        Path root = projectRoot.toAbsolutePath().normalize();
        Path configFile = root.resolve(".autogo/engine.json");
        if (!Files.isRegularFile(configFile)) {
            throw new IOException("项目尚未初始化：缺少 .autogo/engine.json");
        }
        JsonObject document = AutoGoProjectConfig.loadAndMigrate(configFile);
        String target = requiredString(document, "target").toLowerCase(Locale.ROOT);
        if (!"android".equals(target) && !"ios".equals(target)) {
            // 模型导入路径取决于平台，未知值不能默认成 Android。
            throw new IOException("不支持的项目目标平台：" + target);
        }
        String policy = settings.getModulePolicy();
        List<String> modules = normalizeModules(settings.getModuleEntries());
        Path customInitializer = normalizeCustomInitializer(root, settings.getCustomInitializerPath());
        boolean hasCustomInitializer = prepareCustomInitializer(root, customInitializer);
        document.addProperty("modulePolicy", policy);
        JsonArray moduleValues = new JsonArray();
        modules.forEach(moduleValues::add);
        document.add("modules", moduleValues);
        document.addProperty("customInitializer",
                customInitializer == null ? "" : portablePath(root, customInitializer));
        String config = PRETTY_JSON.toJson(document) + "\n";
        String source = renderEngineSource(target, policy, modules, hasCustomInitializer);

        Path backup = backupExistingMain(root);
        // 配置、根入口和生成清单分别使用原子替换，任一写入失败仍保留备份供恢复。
        writeAtomic(configFile, config);
        writeAtomic(root.resolve("main.go"), source);
        writeAtomic(root.resolve(".autogo/generated/manifest.json"), renderManifest(config, source));
        pruneBackups(root.resolve(".autogo/backups"), 5);
        return backup;
    }

    /** 解析模块名单，忽略空行、注释和重复项并保持用户顺序。 */
    static List<String> normalizeModules(String entries) {
        // LinkedHashSet 同时保证去重和稳定生成顺序。
        Set<String> modules = new LinkedHashSet<>();
        for (String line : entries.split("\\R")) {
            String module = line.trim();
            if (module.isEmpty() || module.startsWith("#")) {
                // 空行和井号注释不参与模块策略。
                continue;
            }
            if (!module.matches("[A-Za-z_][A-Za-z0-9_.-]*")) {
                // 非法模块名会造成不可预测的注册行为，直接拒绝生成。
                throw new IllegalArgumentException("非法 GLua 模块名：" + module);
            }
            modules.add(module);
        }
        return List.copyOf(modules);
    }

    private static Path normalizeCustomInitializer(Path root, String configured) throws IOException {
        // 空配置表示完全使用生成的默认初始化代码。
        if (configured == null || configured.isBlank()) {
            return null;
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) {
            // 初始化时再次校验，防止设置保存后文件被移动或删除。
            throw new IOException("自定义初始化文件不存在：" + path);
        }
        return path;
    }

    private static boolean prepareCustomInitializer(Path root, Path customInitializer) throws IOException {
        // 未选择自定义初始化文件时清理插件以前生成的受管副本。
        Path managedCopy = root.resolve("autogo_custom_init.go");
        if (customInitializer == null) {
            if (Files.isRegularFile(managedCopy)
                    && Files.readString(managedCopy, StandardCharsets.UTF_8)
                    .startsWith("// AutoGo managed custom initializer copy.")) {
                // 只删除带插件标记的副本，绝不删除用户自己创建的同名文件。
                Files.delete(managedCopy);
            }
            return false;
        }
        String content = Files.readString(customInitializer, StandardCharsets.UTF_8);
        if (!content.matches("(?s).*\\bpackage\\s+main\\b.*")) {
            // 根入口只能与 package main 中的自定义函数一起构建。
            throw new IOException("自定义初始化文件必须声明 package main：" + customInitializer);
        }
        if (!content.matches("(?s).*\\bfunc\\s+customInitialize\\s*\\(.*")) {
            // 固定函数名让生成入口无需猜测用户符号。
            throw new IOException("自定义初始化文件必须实现 customInitialize(engine *lua_engine.LuaEngine) error");
        }
        if (customInitializer.getParent() != null
                && customInitializer.getParent().toAbsolutePath().normalize().equals(root)) {
            if (customInitializer.getFileName().toString().equals("main.go")) {
                // 自定义文件不能与插件生成的根入口争用同一路径。
                throw new IOException("自定义初始化文件不能使用项目根 main.go");
            }
            // 已在项目根目录的 package main 文件会被 Go 自动编译，无需复制。
            return true;
        }
        String managedContent = "// AutoGo managed custom initializer copy. Source: "
                + customInitializer + "\n" + content;
        writeAtomic(managedCopy, managedContent);
        return true;
    }

    private static String renderConfig(String target, String policy, List<String> modules,
                                       Path customInitializer, Path root) {
        // 配置文件是 IDEA 与 VSCode 后续共同读取的稳定契约。
        StringBuilder moduleJson = new StringBuilder();
        for (int index = 0; index < modules.size(); index++) {
            if (index > 0) {
                moduleJson.append(", ");
            }
            moduleJson.append('"').append(jsonEscape(modules.get(index))).append('"');
        }
        String customPath = customInitializer == null ? "" : portablePath(root, customInitializer);
        return "{\n"
                + "  \"configVersion\": " + CONFIG_VERSION + ",\n"
                + "  \"target\": \"" + jsonEscape(target.toLowerCase(Locale.ROOT)) + "\",\n"
                + "  \"entry\": \"scripts/main.glua\",\n"
                + "  \"modulePolicy\": \"" + policy + "\",\n"
                + "  \"modules\": [" + moduleJson + "],\n"
                + "  \"customInitializer\": \"" + jsonEscape(customPath) + "\",\n"
                + "  \"remote\": {\"mode\": \"auto\", \"endpoint\": \"\", \"deviceSerial\": \"\"},\n"
                + "  \"sync\": {\"include\": [\"**/*.lua\", \"**/*.glua\", \"**/*.luac\", \"**/*.js\", \"**/*.json\"], \"extraFiles\": [], \"deleteRemoteExtras\": false},\n"
                + "  \"debug\": {\"enabled\": true, \"stripGluaBytecode\": false}\n"
                + "}\n";
    }

    private static String requiredString(JsonObject document, String field) throws IOException {
        // 必填字符串缺失或类型错误时拒绝覆盖现有入口。
        if (!document.has(field) || !document.get(field).isJsonPrimitive()
                || !document.getAsJsonPrimitive(field).isString()
                || document.get(field).getAsString().isBlank()) {
            throw new IOException("配置字段 " + field + " 必须是非空字符串");
        }
        return document.get(field).getAsString().trim();
    }

    private static Path backupExistingMain(Path root) throws IOException {
        // 重生成前始终保存现有根入口，用户手工改动可以直接恢复。
        Path main = root.resolve("main.go");
        if (!Files.isRegularFile(main)) {
            return null;
        }
        Path backupDirectory = root.resolve(".autogo/backups");
        Files.createDirectories(backupDirectory);
        Path backup = Files.createTempFile(backupDirectory, "main.go.", ".bak");
        Files.copy(main, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
    }

    private static void pruneBackups(Path backupDirectory, int retained) throws IOException {
        // 只清理插件命名的 main.go 备份，其他用户文件完全不触碰。
        if (!Files.isDirectory(backupDirectory)) {
            return;
        }
        List<Path> backups;
        try (Stream<Path> paths = Files.list(backupDirectory)) {
            backups = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("main\\.go\\.\\d+\\.bak"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), Comparator.reverseOrder()))
                    .toList();
        }
        for (int index = retained; index < backups.size(); index++) {
            // 超过五份时删除最旧的插件备份，限制长期磁盘增长。
            Files.deleteIfExists(backups.get(index));
        }
    }

    static String renderEngineSource(String target, String policy, List<String> modules,
                                     boolean hasCustomInitializer) {
        // 根 main.go 自身包含控制、文件同步和 DAP 服务，兼容 ag 按单文件构建入口。
        StringBuilder moduleValues = new StringBuilder();
        for (String module : modules) {
            moduleValues.append("\t\"").append(goEscape(module)).append("\",\n");
        }
        String luaModelsImport = "ios".equalsIgnoreCase(target)
                ? "github.com/ZingYao/autogo_scriptengine/lua_engine/define/ios/autogo/all_models"
                : "github.com/ZingYao/autogo_scriptengine/lua_engine/define/android/autogo/all_models";
        String jsModelsImport = "ios".equalsIgnoreCase(target)
                ? "github.com/ZingYao/autogo_scriptengine/js_engine/define/ios/autogo/all_models"
                : "github.com/ZingYao/autogo_scriptengine/js_engine/define/autogo/all_models";
        String customCall = hasCustomInitializer
                ? "\tif err := customInitialize(engine); err != nil { engine.Close(); return nil, fmt.Errorf(\"custom initialize: %w\", err) }"
                : "";
        String template = readTemplate("/templates/autogo-main.go.tmpl");
        return template
                .replace("{{LUA_MODELS_IMPORT}}", luaModelsImport)
                .replace("{{JS_MODELS_IMPORT}}", jsModelsImport)
                .replace("{{MODULE_POLICY}}", goEscape(policy))
                .replace("{{MODULE_VALUES}}", moduleValues.toString().stripTrailing())
                .replace("{{CUSTOM_INITIALIZER}}", customCall);
    }

    private static String readTemplate(String resourcePath) {
        // 模板随插件打包，缺失属于不可恢复的构建错误而非用户配置问题。
        try (var input = AutoGoProjectGenerator.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("缺少生成模板：" + resourcePath);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("读取生成模板失败：" + resourcePath, error);
        }
    }

    private static String renderManifest(String config, String source) {
        // manifest 用于检测生成文件被手工改动以及后续配置迁移。
        return "{\n"
                + "  \"generator\": \"autogo-idea\",\n"
                + "  \"configVersion\": " + CONFIG_VERSION + ",\n"
                + "  \"files\": {\n"
                + "    \"../engine.json\": \"" + sha256(config) + "\",\n"
                + "    \"../../main.go\": \"" + sha256(source) + "\"\n"
                + "  }\n"
                + "}\n";
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        // 同目录临时文件确保支持原子 rename，失败时不破坏上一版本。
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                // 不支持原子移动的文件系统退化为同目录替换。
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // 移动或写入失败时清理临时文件。
            Files.deleteIfExists(temporary);
        }
    }

    private static String portablePath(Path root, Path path) {
        // 项目内文件保存相对路径，项目外文件保留绝对路径并统一分隔符。
        Path value = path.startsWith(root) ? root.relativize(path) : path;
        return value.toString().replace('\\', '/');
    }

    private static String jsonEscape(String value) {
        // 只生成配置所需的 JSON 字符串转义。
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String goEscape(String value) {
        // 模块名已限制字符集，仍保留通用字符串转义以防未来放宽规则。
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256(String value) {
        // SHA-256 结果使用小写十六进制，便于两端实现一致比较。
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                output.append(String.format("%02x", item));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            // Java 运行时必须提供 SHA-256，不可用时视为环境损坏。
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
