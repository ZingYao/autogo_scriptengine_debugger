package com.autogo.jetbrains;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动发现 AG、ADB 和 Go 可执行文件，并返回可直接持久化的绝对路径。
 */
public final class AutoGoToolPathResolver {
    private AutoGoToolPathResolver() {
        // 工具类禁止实例化。
    }

    /** 自动发现 AG；未找到时返回空字符串。 */
    public static String findAg(Map<String, String> environment) {
        // 环境变量优先于 PATH 和历史默认安装目录。
        List<String> candidates = new ArrayList<>();
        candidates.add(environment.getOrDefault("AUTOGO_AG_PATH", ""));
        candidates.addAll(pathCandidates(isWindows() ? "ag.exe" : "ag", environment));
        if (isWindows()) {
            candidates.add("C:\\Users\\Public\\ag.exe");
        } else if (isMac()) {
            candidates.add("/Users/Shared/ag");
            candidates.add("/opt/homebrew/bin/ag");
            candidates.add("/usr/local/bin/ag");
        } else {
            candidates.add(Path.of(environment.getOrDefault("HOME", ""), ".autogo", "ag").toString());
            candidates.add("/usr/local/bin/ag");
        }
        return firstExecutable(candidates);
    }

    /** 自动发现 ADB；未找到时返回空字符串。 */
    public static String findAdb(Map<String, String> environment) {
        // PATH 之外补充 Android SDK 环境变量和常见安装位置。
        String executable = isWindows() ? "adb.exe" : "adb";
        List<String> candidates = new ArrayList<>(pathCandidates(executable, environment));
        for (String sdkVariable : List.of("ANDROID_SDK_ROOT", "ANDROID_HOME")) {
            String sdk = environment.getOrDefault(sdkVariable, "");
            if (!sdk.isBlank()) {
                candidates.add(Path.of(sdk, "platform-tools", executable).toString());
            }
        }
        String home = environment.getOrDefault("HOME", System.getProperty("user.home", ""));
        candidates.add(Path.of(home, "Library", "Android", "sdk", "platform-tools", executable).toString());
        candidates.add("/opt/homebrew/bin/adb");
        candidates.add("/usr/local/bin/adb");
        return firstExecutable(candidates);
    }

    /** 自动发现 Go；未找到时返回空字符串。 */
    public static String findGo(Map<String, String> environment) {
        // PATH 之外补充 GOROOT 和常见包管理器目录。
        String executable = isWindows() ? "go.exe" : "go";
        List<String> candidates = new ArrayList<>(pathCandidates(executable, environment));
        String goRoot = environment.getOrDefault("GOROOT", "");
        if (!goRoot.isBlank()) {
            candidates.add(Path.of(goRoot, "bin", executable).toString());
        }
        candidates.add("/opt/homebrew/bin/go");
        candidates.add("/usr/local/bin/go");
        candidates.add("/usr/local/go/bin/go");
        return firstExecutable(candidates);
    }

    /** 自动发现 glua 运行时。 */
    public static String findGlua(Map<String, String> environment) {
        // GLua 工具遵循显式环境变量、PATH 和常见安装目录顺序。
        return findGluaTool("GLUA_EXECUTABLE", isWindows() ? "glua.exe" : "glua", environment);
    }

    /** 自动发现 gluac 编译器。 */
    public static String findGluac(Map<String, String> environment) {
        // 编译动作在未配置时调用此入口，并把发现结果写回设置。
        return findGluaTool("GLUAC_EXECUTABLE", isWindows() ? "gluac.exe" : "gluac", environment);
    }

    /** 自动发现 gluals 语言服务器。 */
    public static String findGluals(Map<String, String> environment) {
        // 语言服务器与运行时独立发现，允许分别升级版本。
        return findGluaTool("GLUALS_EXECUTABLE", isWindows() ? "gluals.exe" : "gluals", environment);
    }

    private static String findGluaTool(String environmentKey, String executable, Map<String, String> environment) {
        // 自定义环境变量优先，然后保持用户 PATH 顺序并补充常见包管理器目录。
        List<String> candidates = new ArrayList<>();
        candidates.add(environment.getOrDefault(environmentKey, ""));
        candidates.addAll(pathCandidates(executable, environment));
        candidates.add(Path.of("/opt/homebrew/bin", executable).toString());
        candidates.add(Path.of("/usr/local/bin", executable).toString());
        return firstExecutable(candidates);
    }

    private static List<String> pathCandidates(String executable, Map<String, String> environment) {
        // 按 PATH 原始顺序生成候选，保持与终端命令解析一致。
        List<String> candidates = new ArrayList<>();
        String path = environment.getOrDefault("PATH", "");
        for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!directory.isBlank()) {
                candidates.add(Path.of(directory, executable).toString());
            }
        }
        return candidates;
    }

    private static String firstExecutable(List<String> candidates) {
        // 去重后返回首个存在且可执行的普通文件。
        Set<String> unique = new LinkedHashSet<>(candidates);
        for (String candidate : unique) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Path path;
            try {
                path = Path.of(candidate).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                // 非法路径不影响后续候选。
                continue;
            }
            if (Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path))) {
                return path.toString();
            }
        }
        return "";
    }

    private static boolean isWindows() {
        // Windows 使用 .exe 文件名且不依赖 POSIX executable 位。
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static boolean isMac() {
        // macOS 需要额外检查 Homebrew 和共享安装目录。
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }
}
