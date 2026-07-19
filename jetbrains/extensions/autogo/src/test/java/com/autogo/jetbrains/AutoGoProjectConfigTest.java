package com.autogo.jetbrains;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证共享项目配置迁移保留用户数据并拒绝不兼容版本。 */
final class AutoGoProjectConfigTest {
    @TempDir
    Path temporaryDirectory;

    /** 验证无版本旧配置补齐 v1 节点并保留未知用户字段。 */
    @Test
    void migratesLegacyConfigWithoutDiscardingUserValues() throws Exception {
        // legacyFlag 模拟未来由另一 IDE 写入但当前扩展不解释的字段。
        Path config = Files.writeString(temporaryDirectory.resolve("engine.json"), """
                {"entry":"scripts/start.glua","legacyFlag":{"keep":true}}
                """);
        JsonObject migrated = AutoGoProjectConfig.loadAndMigrate(config);
        assertEquals(1, migrated.get("configVersion").getAsInt());
        assertEquals("scripts/start.glua", migrated.get("entry").getAsString());
        assertTrue(migrated.getAsJsonObject("legacyFlag").get("keep").getAsBoolean());
        assertTrue(migrated.has("remote"));
        assertTrue(migrated.has("sync"));
        assertTrue(migrated.has("debug"));
        // 写回后再次加载必须幂等，不继续改变配置。
        String firstMigration = Files.readString(config);
        AutoGoProjectConfig.loadAndMigrate(config);
        assertEquals(firstMigration, Files.readString(config));
    }

    /** 验证未来版本被拒绝且文件内容完全不变。 */
    @Test
    void rejectsFutureVersionWithoutRewritingFile() throws Exception {
        // 高版本语义未知时不能猜测降级。
        Path config = Files.writeString(temporaryDirectory.resolve("future.json"),
                "{\"configVersion\":9,\"entry\":\"main.lua\"}\n");
        String original = Files.readString(config);
        assertThrows(IOException.class, () -> AutoGoProjectConfig.loadAndMigrate(config));
        assertEquals(original, Files.readString(config));
    }

    /** 验证错误字段类型不会被默认值静默覆盖。 */
    @Test
    void rejectsInvalidObjectType() throws Exception {
        // sync 数组无法安全解释为同步策略对象。
        Path config = Files.writeString(temporaryDirectory.resolve("invalid.json"),
                "{\"configVersion\":1,\"entry\":\"main.lua\",\"sync\":[]}");
        assertThrows(IOException.class, () -> AutoGoProjectConfig.loadAndMigrate(config));
    }

    /** 验证每个项目可以覆盖应用级默认设备且保留未知字段。 */
    @Test
    void persistsProjectSpecificDeviceSelection() throws Exception {
        // 两个项目共享全局默认时，项目覆盖只写 remote.deviceSerial。
        Path config = Files.writeString(temporaryDirectory.resolve("device.json"), """
                {"configVersion":1,"entry":"main.lua","remote":{"mode":"auto","endpoint":"",
                "future":{"keep":true}},"sync":{},"debug":{}}
                """);
        assertEquals("global-device", AutoGoProjectConfig.selectedDevice(config, "global-device"));
        AutoGoProjectConfig.setSelectedDevice(config, "project-device:5555");
        assertEquals("project-device:5555", AutoGoProjectConfig.selectedDevice(config, "global-device"));
        JsonObject saved = AutoGoProjectConfig.loadAndMigrate(config);
        assertTrue(saved.getAsJsonObject("remote").getAsJsonObject("future").get("keep").getAsBoolean());
    }
}
