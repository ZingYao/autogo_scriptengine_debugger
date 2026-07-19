package com.autogo.jetbrains;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 读取并原子迁移 IDEA 与 VSCode 共享的 .autogo/engine.json。 */
final class AutoGoProjectConfig {
    static final int CURRENT_VERSION = 1;
    private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();

    private AutoGoProjectConfig() {
        // 配置加载器只提供无状态静态能力。
    }

    /** 加载配置，迁移旧版本并返回完整 JSON 对象。 */
    static JsonObject loadAndMigrate(Path configFile) throws IOException {
        // 读取失败、损坏 JSON 和未来版本均不得降级为默认配置。
        JsonObject document;
        try {
            document = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException malformed) {
            throw new IOException("无法解析 " + configFile + "：" + malformed.getMessage(), malformed);
        }
        int version = readVersion(document);
        if (version > CURRENT_VERSION) {
            // 新版本可能改变安全或同步语义，旧扩展必须拒绝解释。
            throw new IOException("配置版本 " + version + " 高于当前支持的 " + CURRENT_VERSION
                    + "，请升级 AutoGo 扩展");
        }
        boolean changed = version < CURRENT_VERSION;
        changed |= ensureObject(document, "remote", defaultRemote());
        changed |= ensureObject(document, "sync", defaultSync());
        changed |= ensureObject(document, "debug", defaultDebug());
        if (!document.has("entry")) {
            // v0 未声明入口时沿用跨 IDE 约定的 main.lua。
            document.addProperty("entry", "main.lua");
            changed = true;
        } else if (!document.get("entry").isJsonPrimitive()
                || !document.getAsJsonPrimitive("entry").isString()) {
            throw new IOException("配置字段 entry 必须是项目相对路径字符串");
        }
        if (changed) {
            // 所有迁移完成后才提升版本并写回，避免半迁移配置被误识别为 v1。
            document.addProperty("configVersion", CURRENT_VERSION);
            writeAtomic(configFile, PRETTY_JSON.toJson(document) + "\n");
        }
        return document;
    }

    /** 读取项目设备覆盖；空值表示使用应用级默认设备。 */
    static String selectedDevice(Path configFile, String fallback) throws IOException {
        // 复用版本化加载器，损坏配置不得被静默忽略。
        if (!Files.isRegularFile(configFile)) {
            return fallback == null ? "" : fallback.trim();
        }
        JsonObject document = loadAndMigrate(configFile);
        JsonObject remote = document.getAsJsonObject("remote");
        if (!remote.has("deviceSerial")) {
            return fallback == null ? "" : fallback.trim();
        }
        if (!remote.get("deviceSerial").isJsonPrimitive()
                || !remote.getAsJsonPrimitive("deviceSerial").isString()) {
            // 设备序列号类型损坏时拒绝执行 ADB 命令。
            throw new IOException("配置字段 remote.deviceSerial 必须是字符串");
        }
        String selected = remote.get("deviceSerial").getAsString().trim();
        return selected.isEmpty() ? (fallback == null ? "" : fallback.trim()) : selected;
    }

    /** 原子保存当前项目的设备覆盖，同时保留未知配置字段。 */
    static void setSelectedDevice(Path configFile, String serial) throws IOException {
        // 项目必须已经存在有效引擎配置，避免设备选择创建不完整配置。
        JsonObject document = loadAndMigrate(configFile);
        document.getAsJsonObject("remote").addProperty("deviceSerial",
                serial == null ? "" : serial.trim());
        writeAtomic(configFile, PRETTY_JSON.toJson(document) + "\n");
    }

    private static int readVersion(JsonObject document) throws IOException {
        // 缺少版本号属于早期 v0 配置；其他非整数类型直接拒绝。
        if (!document.has("configVersion")) {
            return 0;
        }
        try {
            if (!document.get("configVersion").isJsonPrimitive()
                    || !document.getAsJsonPrimitive("configVersion").isNumber()
                    || !document.get("configVersion").getAsString().matches("\\d+")) {
                throw new IllegalArgumentException("not an integer");
            }
            int version = document.get("configVersion").getAsInt();
            if (version < 0) {
                throw new IOException("configVersion 不能为负数");
            }
            return version;
        } catch (RuntimeException invalid) {
            throw new IOException("configVersion 必须是非负整数", invalid);
        }
    }

    private static boolean ensureObject(JsonObject document, String field, JsonObject defaultValue)
            throws IOException {
        // 缺失节点补默认值；存在但类型错误时保留原文件并报告。
        if (!document.has(field)) {
            document.add(field, defaultValue);
            return true;
        }
        if (!document.get(field).isJsonObject()) {
            throw new IOException("配置字段 " + field + " 必须是 JSON 对象");
        }
        return false;
    }

    private static JsonObject defaultRemote() {
        // auto 在配置 endpoint 时优先直连，否则按无线 ADB、USB 顺序连接。
        JsonObject remote = new JsonObject();
        remote.addProperty("mode", "auto");
        remote.addProperty("endpoint", "");
        remote.addProperty("deviceSerial", "");
        return remote;
    }

    private static JsonObject defaultSync() {
        // 默认只上传依赖闭包，不删除设备端旧版本文件。
        JsonObject sync = new JsonObject();
        sync.add("extraFiles", new JsonArray());
        sync.addProperty("deleteRemoteExtras", false);
        return sync;
    }

    private static JsonObject defaultDebug() {
        // 调试默认启用并保留 GLuac 行号信息。
        JsonObject debug = new JsonObject();
        debug.addProperty("enabled", true);
        debug.addProperty("stripGluaBytecode", false);
        return debug;
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        // 同目录临时文件保证 rename 不跨文件系统，异常时删除临时文件。
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".migration");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // 文件系统不支持原子移动时退化为同目录替换。
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
