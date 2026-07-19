package com.autogo.jetbrains;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 从 autogo_scriptengine 的 RegisterMethod 调用生成 GLua 补全和文档 catalog。 */
public final class AutoGoApiCatalogGenerator {
    private static final Pattern REGISTER_METHOD = Pattern.compile(
            "RegisterMethod\\s*\\(\\s*\"((?:\\\\.|[^\"\\\\])+)\"\\s*,\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
            Pattern.DOTALL);

    private AutoGoApiCatalogGenerator() {
        // catalog 生成器只提供确定性的静态文件转换。
    }

    /** 扫描依赖源码并原子写入项目级 autogo-api.json。 */
    public static Path generate(Path projectRoot, Path engineRoot) throws IOException {
        // 只扫描 Lua 引擎目录，排除测试夹具、文档和无关 Go 工具。
        Path sourceRoot = engineRoot.resolve("lua_engine").toAbsolutePath().normalize();
        if (!Files.isDirectory(sourceRoot)) {
            throw new IOException("autogo_scriptengine 缺少 lua_engine 目录：" + sourceRoot);
        }
        Map<String, Entry> methods = new TreeMap<>();
        try {
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".go"))
                        .filter(path -> !path.getFileName().toString().endsWith("_test.go"))
                        .sorted()
                        .forEach(path -> collect(path, sourceRoot, methods));
            }
        } catch (CatalogReadException error) {
            // Stream lambda 不能声明 IOException，这里恢复原始文件读取错误语义。
            throw (IOException) error.getCause();
        }
        addCoreConsoleMethods(methods);
        if (methods.isEmpty()) {
            // 空 catalog 通常表示上游 API 结构变化，不能覆盖上一份有效文件。
            throw new IOException("未从 autogo_scriptengine 发现 RegisterMethod API");
        }
        Path output = projectRoot.resolve(".autogo/generated/autogo-api.json").toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        writeAtomic(output, render(methods));
        return output;
    }

    private static void collect(Path file, Path sourceRoot, Map<String, Entry> methods) {
        // 单文件读取失败会终止生成，避免产生不完整 catalog。
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = REGISTER_METHOD.matcher(source);
            while (matcher.find()) {
                String name = goString(matcher.group(1));
                String description = goString(matcher.group(2));
                if (name.isBlank() || !name.matches("[A-Za-z_][A-Za-z0-9_.:-]*")) {
                    // 非稳定字面量名称不进入 IDE catalog。
                    continue;
                }
                String relativeSource = sourceRoot.relativize(file).toString().replace('\\', '/');
                // 按排序后的文件顺序覆盖，同平台具体实现会覆盖较早的 fallback 描述。
                String shortName = name.substring(name.lastIndexOf('.') + 1);
                methods.put(name, new Entry(shortName + "(...)", description,
                        "...values：需要传递的参数", "返回值由 AutoGo API 定义。", "", relativeSource));
            }
        } catch (IOException error) {
            throw new CatalogReadException(error);
        }
    }

    private static void addCoreConsoleMethods(Map<String, Entry> methods) {
        // 核心 console 方法由 LuaEngine 直接注册，不经过 RegisterMethod，必须显式加入公开目录。
        methods.put("console.log", consoleEntry("log", "输出普通脚本消息。", "console.log(\"任务完成\")"));
        methods.put("console.info", consoleEntry("info", "输出 Info 级别脚本消息，在 Lua 日志区使用青绿色显示。", "console.info(\"开始处理\")"));
        methods.put("console.debug", consoleEntry("debug", "输出 Debug 级别脚本消息，在 Lua 日志区使用灰色显示。", "console.debug(\"变量\", value)"));
        methods.put("console.warn", consoleEntry("warn", "输出 Warn 级别脚本消息，在 Lua 日志区使用亮黄色显示。", "console.warn(\"配置缺失\")"));
        methods.put("console.error", consoleEntry("error", "输出 Error 级别脚本消息，在 Lua 日志区使用暗红色显示。", "console.error(\"执行失败\")"));
    }

    private static Entry consoleEntry(String method, String description, String example) {
        // Console 日志方法接受可变参数且不返回值，签名与移动端运行时保持一致。
        return new Entry("console." + method + "(...values)", description,
                "...values：需要输出的一个或多个值", "无返回值。", example, "lua_engine/lua_engine.go");
    }

    private static String render(Map<String, Entry> methods) {
        // 输出格式直接兼容 GluaBuiltinCatalog 的 functions schema。
        StringBuilder json = new StringBuilder("{\n  \"formatVersion\": 1,\n  \"locale\": \"zh\",\n  \"functions\": {\n");
        int index = 0;
        for (Map.Entry<String, Entry> method : methods.entrySet()) {
            if (index++ > 0) {
                json.append(",\n");
            }
            json.append("    \"").append(jsonEscape(method.getKey())).append("\": {")
                    .append("\"signature\": \"").append(jsonEscape(method.getValue().signature())).append("\", ")
                    .append("\"description\": \"").append(jsonEscape(method.getValue().description())).append("\", ")
                    .append("\"params\": [\"").append(jsonEscape(method.getValue().parameter())).append("\"], ")
                    .append("\"returns\": \"").append(jsonEscape(method.getValue().returns())).append("\", ")
                    .append("\"example\": \"").append(jsonEscape(method.getValue().example())).append("\", \"source\": \"")
                    .append(jsonEscape(method.getValue().source())).append("\"}");
        }
        return json.append("\n  }\n}\n").toString();
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        // 同目录临时文件保证扫描失败不会破坏上一版本 catalog。
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                // 不支持原子 rename 的文件系统退化为同目录替换。
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // 写入或移动失败时清理临时文件。
            Files.deleteIfExists(temporary);
        }
    }

    private static String goString(String value) {
        // RegisterMethod 使用普通 Go 字符串字面量，处理 catalog 所需的常见转义。
        return value.replace("\\n", "\n").replace("\\r", "\r")
                .replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String jsonEscape(String value) {
        // 生成严格 JSON 字符串，描述中的换行和引号不会破坏 catalog。
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private record Entry(String signature, String description, String parameter,
                         String returns, String example, String source) { }

    private static final class CatalogReadException extends RuntimeException {
        private CatalogReadException(IOException cause) {
            super(cause);
        }
    }
}
