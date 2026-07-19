package com.glua.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;

import java.nio.file.Path;

/** 保存当前 AutoGo 调试 manifest 的本地与设备源码路径映射。 */
public final class GluaRemotePathMapper {
    private static final Key<String> MANIFEST = Key.create("autogo.glua.remote.manifest");

    private GluaRemotePathMapper() {
        // 路径映射绑定 Project user data，不持有全局项目引用。
    }

    /** 配置当前调试会话使用的远端 manifest。 */
    public static void configure(Project project, String manifestID) {
        // 空 manifest 清除映射，正常调试使用内容哈希 ID。
        project.putUserData(MANIFEST, manifestID == null || manifestID.isBlank() ? null : manifestID);
    }

    /** 为本地断点生成设备端版本目录路径。 */
    static String toRemote(Project project, String localPath) {
        // 仅项目内文件能够映射到设备版本目录。
        String basePath = project.getBasePath();
        String manifest = project.getUserData(MANIFEST);
        return toRemote(basePath, manifest, localPath);
    }

    static String toRemote(String basePath, String manifest, String localPath) {
        // 纯字符串入口便于不启动 IDEA fixture 的路径映射回归测试。
        if (basePath == null || manifest == null) {
            return "";
        }
        try {
            Path root = Path.of(basePath).toAbsolutePath().normalize();
            Path local = Path.of(localPath).toAbsolutePath().normalize();
            if (!local.startsWith(root)) {
                return "";
            }
            String relative = root.relativize(local).toString().replace('\\', '/');
            return ".autogo/remote/releases/" + manifest + "/" + relative;
        } catch (RuntimeException ignored) {
            // 非本机路径格式不能参与 Path 运算。
            return "";
        }
    }

    /** 把设备端暂停位置还原为 IDEA 项目内源码绝对路径。 */
    static String toLocal(Project project, String remotePath) {
        // 只处理当前 manifest 前缀，旧版本暂停事件不得跳转到错误源码。
        String basePath = project.getBasePath();
        String manifest = project.getUserData(MANIFEST);
        return toLocal(basePath, manifest, remotePath);
    }

    static String toLocal(String basePath, String manifest, String remotePath) {
        // 纯函数只信任当前 manifest 前缀并重复校验根目录逃逸。
        if (basePath == null || manifest == null || remotePath == null) {
            return remotePath == null ? "" : remotePath;
        }
        String normalized = remotePath.replace('\\', '/');
        String prefix = ".autogo/remote/releases/" + manifest + "/";
        int index = normalized.indexOf(prefix);
        if (index < 0) {
            return remotePath;
        }
        String relative = normalized.substring(index + prefix.length());
        Path target = Path.of(basePath).toAbsolutePath().normalize().resolve(relative).normalize();
        return target.startsWith(Path.of(basePath).toAbsolutePath().normalize())
                ? target.toString() : remotePath;
    }
}
