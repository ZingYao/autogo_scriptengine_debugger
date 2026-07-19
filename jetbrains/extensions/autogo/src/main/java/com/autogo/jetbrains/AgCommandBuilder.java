package com.autogo.jetbrains;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 构建 AG 命令参数，确保不同入口使用一致的参数顺序和可选项语义。
 */
public final class AgCommandBuilder {
    private AgCommandBuilder() {
        // 工具类禁止实例化。
    }

    /**
     * 根据动作和选项构建参数；缺少必填选项时抛出 IllegalArgumentException。
     *
     * @param action AG 动作
     * @param options target、device、address、debug、embed 等选项
     * @return 不包含可执行文件的参数列表
     */
    public static List<String> build(String action, Map<String, String> options) {
        // 按 AG CLI 约定逐类构建参数，避免把空值传给子进程。
        List<String> args = new ArrayList<>();
        args.add(action);
        switch (action) {
            case "version", "stop" -> {
                // 无附加参数。
            }
            case "init" -> {
                // 初始化必须声明目标平台。
                args.add("-t");
                args.add(required(options, "target"));
            }
            case "run" -> {
                // 设备和调试模式均为可选参数。
                appendValue(args, "-s", options.get("device"));
                if (Boolean.parseBoolean(options.get("debug"))) {
                    // 调试开关只在明确启用时追加。
                    args.add("-d");
                }
            }
            case "build" -> {
                // 编译必须声明目标平台。
                args.add("-t");
                args.add(required(options, "target"));
                if (Boolean.parseBoolean(options.get("embed"))) {
                    // 内嵌构建只在明确启用时追加。
                    args.add("-e");
                }
            }
            case "deploy" -> {
                // 部署允许省略设备并交由 AG 自行选择。
                appendValue(args, "-s", options.get("device"));
            }
            case "connect" -> {
                // 连接动作必须提供设备地址。
                args.add("-s");
                args.add(required(options, "address"));
            }
            default -> throw new IllegalArgumentException("不支持的 AG 操作: " + action);
        }
        return List.copyOf(args);
    }

    private static String required(Map<String, String> options, String name) {
        // 缺少必填值时提前退出，禁止启动无效命令。
        String value = options.getOrDefault(name, "").trim();
        if (value.isEmpty()) {
            // 明确指出缺少的选项，便于 UI 层提示用户修正。
            throw new IllegalArgumentException("缺少 AG 参数: " + name);
        }
        return value;
    }

    private static void appendValue(List<String> args, String flag, String value) {
        // 空选项不进入命令行，保留 AG 默认行为。
        if (value != null && !value.isBlank()) {
            args.add(flag);
            args.add(value.trim());
        }
    }
}
