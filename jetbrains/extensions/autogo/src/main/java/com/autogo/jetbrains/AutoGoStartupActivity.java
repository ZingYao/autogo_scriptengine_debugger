package com.autogo.jetbrains;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Files;
import java.nio.file.Path;

/** 在已完成初始化的项目中自动尝试启动移动端脚本引擎。 */
public final class AutoGoStartupActivity implements StartupActivity.Background {
    /** 项目打开后检查初始化标志并按需启动引擎。 */
    @Override
    public void runActivity(@NotNull Project project) {
        // 未初始化项目不得执行 ADB 或 AG，避免普通 Go 项目打开时产生副作用。
        String basePath = project.getBasePath();
        if (basePath == null || project.isDisposed()) {
            return;
        }
        Path root = Path.of(basePath);
        Path config = root.resolve(".autogo/engine.json");
        Path host = root.resolve("main.go");
        Path scripts = root.resolve("scripts");
        if (!Files.isRegularFile(config) || !Files.isRegularFile(host) || !Files.isDirectory(scripts)) {
            project.getService(AutoGoConsoleService.class)
                    .info("当前项目未初始化或初始化不完整，跳过移动端引擎自动启动。");
            return;
        }
        // 与 VSCode 激活行为一致：初始化完整后自动复用或启动移动端控制服务。
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(() -> {
            // 项目服务可能创建 Swing Console，必须从 EDT 进入初始化链路。
            if (!project.isDisposed()) {
                project.getService(AutoGoConsoleService.class).info("扩展已激活，正在自动启动移动端引擎……");
                project.getService(AutoGoRemoteEngineService.class).startOrRestart();
            }
        });
    }
}
