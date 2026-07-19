package com.glua.jetbrains;

import org.jetbrains.annotations.NotNull;

/** 描述 DAP evaluate 请求的结果，用于 IDEA 悬停求值和表达式查看。 */
public record GluaDapEvaluateResult(boolean success,
                                    @NotNull GluaDapVariable variable,
                                    @NotNull String error) {
    /** 创建成功结果。 */
    public static @NotNull GluaDapEvaluateResult ok(@NotNull GluaDapVariable variable) {
        // 成功结果保留值、类型和子变量引用，便于 IDE 展开表结构。
        return new GluaDapEvaluateResult(true, variable, "");
    }

    /** 创建失败结果。 */
    public static @NotNull GluaDapEvaluateResult failure(@NotNull String error) {
        // 失败时返回稳定的空变量，调用方只展示错误文本。
        return new GluaDapEvaluateResult(false, new GluaDapVariable("", "", "", 0), error);
    }
}
