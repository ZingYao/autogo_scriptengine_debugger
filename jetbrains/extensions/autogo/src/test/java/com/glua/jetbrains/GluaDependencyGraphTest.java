package com.glua.jetbrains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证调试同步依赖图的闭包、环和动态 require 行为。 */
final class GluaDependencyGraphTest {
    @TempDir
    Path root;

    /** 静态 require 递归收集且环依赖只保留一次。 */
    @Test
    void resolvesStaticClosureAndCycle() throws Exception {
        // main -> lib/a -> lib/b -> lib/a 构成闭环。
        Path lib = Files.createDirectories(root.resolve("lib"));
        Path main = Files.writeString(root.resolve("main.lua"), "local a = require('lib.a')\n");
        Path a = Files.writeString(lib.resolve("a.lua"), "return require(\"lib.b\")\n");
        Path b = Files.writeString(lib.resolve("b.glua"), "require 'lib.a'\n");

        GluaDependencyGraph.Result result = GluaDependencyGraph.resolve(root, main);

        assertEquals(List.of(main, a, b), result.files());
        assertEquals(List.of(), result.dynamicRequires());
    }

    /** 动态 require 被报告但不猜测文件。 */
    @Test
    void reportsDynamicRequire() throws Exception {
        // 动态名称必须交由用户通过 extraFiles 补充。
        Path main = Files.writeString(root.resolve("main.lua"), "local name = 'a'\nrequire(name)\n");
        GluaDependencyGraph.Result result = GluaDependencyGraph.resolve(root, main);
        assertEquals(List.of(main), result.files());
        assertEquals(List.of("main.lua:2"), result.dynamicRequires());
    }

    /** 项目外入口会被拒绝。 */
    @Test
    void rejectsEntryOutsideProject() throws Exception {
        // 防止相对路径上传逃逸项目根目录。
        Path outside = Files.writeString(root.getParent().resolve("outside.lua"), "return true\n");
        assertThrows(Exception.class, () -> GluaDependencyGraph.resolve(root, outside));
    }
}
