package com.glua.jetbrains;

import com.intellij.openapi.application.PathManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 解析用户配置或插件内置的跨平台 GLuac 编译器。 */
public final class GluaCompilerExecutable {
    private GluaCompilerExecutable() {
    }

    /** 优先使用用户配置，否则提取与当前平台匹配的内置编译器。 */
    public static Path resolve(String configuredPath) throws IOException {
        // 显式配置具有最高优先级，路径失效时直接报告配置错误。
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(configured)) {
                // 禁止静默回退，避免用户误以为正在使用指定版本。
                throw new IOException("configured gluac executable does not exist: " + configured);
            }
            return configured;
        }

        String os = GluaLanguageServerExecutable.normalizedOs(System.getProperty("os.name", ""));
        String arch = GluaLanguageServerExecutable.normalizedArch(System.getProperty("os.arch", ""));
        if (!isBundled(os, arch)) {
            // 当前产物明确覆盖 Windows 与 macOS 的常见 CPU 架构。
            throw new IOException("gluac is not bundled for " + os + "/" + arch
                    + "; configure the gluac executable path in GLua settings");
        }
        String executableName = os.equals("windows") ? "gluac.exe" : "gluac";
        String resource = "/gluac/" + os + "-" + arch + "/" + executableName;
        Path target = Path.of(PathManager.getPluginTempPath()).resolve("glua-tools")
                .resolve(os + "-" + arch).resolve(executableName);
        Files.createDirectories(target.getParent());
        try (InputStream input = GluaCompilerExecutable.class.getResourceAsStream(resource)) {
            if (input == null) {
                // 资源缺失属于插件构建错误，必须给出可定位的资源路径。
                throw new IOException("bundled gluac executable is missing: " + resource);
            }
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!os.equals("windows") && !target.toFile().setExecutable(true, true)) {
            // Unix 平台提取后的资源必须具备当前用户执行权限。
            throw new IOException("cannot mark bundled gluac executable as executable: " + target);
        }
        return target;
    }

    /** 判断当前构建是否承诺包含指定平台的 GLuac。 */
    static boolean isBundled(String os, String arch) {
        // Windows 与 macOS 均覆盖 x64 和 arm64。
        return (os.equals("darwin") || os.equals("windows"))
                && (arch.equals("amd64") || arch.equals("arm64"));
    }
}
