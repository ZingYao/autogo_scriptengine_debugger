package com.autogo.jetbrains;

import java.util.List;

/** 提供离线可用的 go-lua-vm 与 AutoGo 模块目录。 */
public final class AutoGoModuleCatalog {
    private static final List<String> DEFAULT_MODULES = List.of(
            "app",
            "device",
            "console",
            "hud",
            "vdisplay",
            "coroutine",
            "dotocr",
            "files",
            "http",
            "images",
            "ime",
            "imgui",
            "media",
            "motion",
            "opencv",
            "plugin",
            "ppocr",
            "rhino",
            "storages",
            "system",
            "uiacc",
            "utils",
            "websocket",
            "yolo"
    );

    private AutoGoModuleCatalog() {
        // 模块目录通过静态方法访问。
    }

    /** 返回按运行时注册顺序排列的默认模块。 */
    public static List<String> defaultModules() {
        // 不暴露可变集合，远程 catalog 后续在服务层合并。
        return DEFAULT_MODULES;
    }
}
