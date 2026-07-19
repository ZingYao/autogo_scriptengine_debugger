package com.glua.jetbrains;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 AutoGo manifest 目录与 IDEA 项目源码之间的双向映射。 */
public final class GluaRemotePathMapperTest {
    @TempDir
    Path root;

    /** 本地断点和远端暂停位置必须映射到同一源码。 */
    @Test
    public void roundTripsManifestPath() {
        // 临时目录模拟项目根，不依赖 IntelliJ 测试框架。
        String local = root.resolve("lua/main.lua").toString();
        String remote = GluaRemotePathMapper.toRemote(root.toString(), "abc123", local);
        assertEquals(".autogo/remote/releases/abc123/lua/main.lua", remote);
        assertEquals(Path.of(local).toAbsolutePath().normalize().toString(),
                GluaRemotePathMapper.toLocal(root.toString(), "abc123", remote));
    }
}
