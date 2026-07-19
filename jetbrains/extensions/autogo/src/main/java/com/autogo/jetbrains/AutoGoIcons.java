package com.autogo.jetbrains;

import com.intellij.openapi.util.IconLoader;

import javax.swing.Icon;

/**
 * 集中加载 AutoGo 自有 SVG 图标，供菜单、工具栏和工具窗口复用。
 */
public final class AutoGoIcons {
    public static final Icon LOGO = icon("logo");
    public static final Icon DEBUG = icon("debug");
    public static final Icon RUN = icon("run");
    public static final Icon STOP = icon("stop");
    public static final Icon SYNC = icon("sync");
    public static final Icon NODE = icon("node");
    public static final Icon BUILD = icon("build");
    public static final Icon GLUA = icon("glua");
    public static final Icon ENGINE = icon("engine");
    public static final Icon DEVICE = icon("device");
    public static final Icon INIT = icon("init");
    public static final Icon PUSH = icon("push");
    public static final Icon DOCS = icon("docs");
    public static final Icon SETTINGS = icon("settings");
    public static final Icon APPLY = icon("apply");
    public static final Icon UPDATE = icon("update");
    public static final Icon CLEAR = icon("clear");

    private AutoGoIcons() {
        // 图标容器禁止实例化。
    }

    private static Icon icon(String name) {
        // 所有图标位于插件 resources/icons 目录。
        return IconLoader.getIcon("/icons/" + name + ".svg", AutoGoIcons.class);
    }
}
