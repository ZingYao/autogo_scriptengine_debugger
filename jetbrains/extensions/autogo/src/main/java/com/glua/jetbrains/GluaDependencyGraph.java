package com.glua.jetbrains;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 解析 Lua/GLua 静态 require 闭包，供运行前增量同步复用。 */
public final class GluaDependencyGraph {
    private GluaDependencyGraph() {
        // 依赖图解析器只提供纯文件系统静态方法。
    }

    /** 从当前入口递归解析静态 require，并报告无法确定的动态 require。 */
    public static Result resolve(Path projectRoot, Path entryFile) throws IOException {
        // 所有返回路径均规范化并限制在项目根目录内。
        Path root = projectRoot.toAbsolutePath().normalize();
        Path entry = entryFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(entry) || !entry.startsWith(root)) {
            // 项目外文件不能映射为安全的远端项目相对路径。
            throw new IOException("入口文件必须位于项目根目录内：" + entry);
        }
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        List<String> dynamicRequires = new ArrayList<>();
        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(entry);
        while (!pending.isEmpty()) {
            Path current = pending.removeFirst().normalize();
            if (!files.add(current)) {
                // 环形 require 已经访问过，不再重复解析。
                continue;
            }
            String source = Files.readString(current, StandardCharsets.UTF_8);
            List<GluaToken> tokens = GluaLexerUtil.scan(source);
            for (int index = 0; index < tokens.size(); index++) {
                GluaToken token = tokens.get(index);
                if (!"require".equals(token.text) || !token.isName()) {
                    // 只有词法上真实的 require 调用参与依赖图，注释和字符串会被跳过。
                    continue;
                }
                int argument = nextVisible(tokens, index);
                if (argument >= 0 && "(".equals(tokens.get(argument).text)) {
                    argument = nextVisible(tokens, argument);
                }
                if (argument < 0 || !"string".equals(tokens.get(argument).type)) {
                    // 变量、拼接或函数结果属于动态 require，扩展不能猜测目标。
                    dynamicRequires.add(root.relativize(current).toString().replace('\\', '/')
                            + ":" + lineAt(source, token.start));
                    continue;
                }
                String module = unquote(tokens.get(argument).text);
                Path resolved = resolveModule(root, current, module);
                if (resolved != null && resolved.startsWith(root)) {
                    pending.addLast(resolved);
                }
            }
        }
        return new Result(List.copyOf(files), List.copyOf(dynamicRequires));
    }

    private static Path resolveModule(Path root, Path current, String module) {
        // 与编辑器跳转保持一致：当前目录优先，再查项目根、lua 和 src。
        String relative = module.replace('.', '/');
        List<Path> roots = new ArrayList<>();
        if (current.getParent() != null) {
            roots.add(current.getParent());
        }
        roots.add(root);
        roots.add(root.resolve("lua"));
        roots.add(root.resolve("src"));
        for (Path candidateRoot : roots) {
            for (String suffix : List.of(".lua", ".glua", "/init.lua", "/init.glua")) {
                Path candidate = candidateRoot.resolve(relative + suffix).normalize();
                if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
                    // 返回第一个符合 Lua 搜索顺序的真实文件。
                    return candidate;
                }
            }
        }
        return null;
    }

    private static int nextVisible(List<GluaToken> tokens, int index) {
        // 空白和注释不改变 require 参数位置。
        for (int next = index + 1; next < tokens.size(); next++) {
            String type = tokens.get(next).type;
            if (!"space".equals(type) && !"comment".equals(type)) {
                return next;
            }
        }
        return -1;
    }

    private static int lineAt(String source, int offset) {
        // 用户提示使用一基行号。
        int line = 1;
        for (int index = 0; index < Math.min(offset, source.length()); index++) {
            if (source.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String unquote(String value) {
        // 词法器保证字符串至少包含首尾引号。
        return value.length() >= 2 ? value.substring(1, value.length() - 1) : "";
    }

    /** 静态文件闭包和需要用户补充的动态 require 位置。 */
    public record Result(List<Path> files, List<String> dynamicRequires) { }
}
