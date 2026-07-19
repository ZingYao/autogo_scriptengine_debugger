package com.autogo.jetbrains;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 解析 AG 可执行文件路径，优先使用 IDE 配置和环境变量。
 */
public final class AgExecutableResolver {
    private AgExecutableResolver() {
        // 工具类禁止实例化。
    }

    /**
     * 解析 AG 路径；找不到常见文件时返回命令名，由系统 PATH 完成最终解析。
     *
     * @param configuredPath IDE 中配置的路径，可为空
     * @param environment 当前进程环境变量
     * @return 可执行文件路径或 ag 命令名
     */
    public static String resolve(String configuredPath, Map<String, String> environment) {
        // 首先尊重用户在 IDE 中的显式配置。
        if (configuredPath != null && !configuredPath.isBlank()) {
            return configuredPath.trim();
        }
        String environmentPath = environment.getOrDefault("AUTOGO_AG_PATH", "").trim();
        if (!environmentPath.isEmpty()) {
            // 环境变量作为跨 IDE 的统一覆盖入口。
            return environmentPath;
        }
        for (String candidate : commonPaths(environment)) {
            // 返回首个实际存在的常见安装路径。
            if (Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        return isWindows() ? "ag.exe" : "ag";
    }

    private static List<String> commonPaths(Map<String, String> environment) {
        // 不同系统使用各自的历史默认安装位置。
        if (isWindows()) {
            return List.of("C:\\Users\\Public\\ag.exe");
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            return List.of("/Users/Shared/ag", "/opt/homebrew/bin/ag", "/usr/local/bin/ag", "/usr/bin/ag");
        }
        String home = environment.getOrDefault("HOME", System.getProperty("user.home", ""));
        return List.of(Path.of(home, ".autogo", "ag").toString(), "/usr/local/bin/ag", "/usr/bin/ag");
    }

    private static boolean isWindows() {
        // JVM 系统属性用于区分 Windows 的可执行文件名和路径格式。
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
