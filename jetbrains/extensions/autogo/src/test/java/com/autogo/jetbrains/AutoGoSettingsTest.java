package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 AutoGo 设置枚举的兼容回退与持久化语义。 */
final class AutoGoSettingsTest {
    /** 模块代码生成偏好只接受稳定枚举，非法输入恢复为询问。 */
    @Test
    void normalizesModuleRegenerationPreference() {
        // 新设置默认询问，随后分别验证两个记住选项及非法值回退。
        AutoGoSettings settings = new AutoGoSettings();
        assertEquals("ASK", settings.getModuleRegenerationPreference());

        settings.setModuleRegenerationPreference("always");
        assertEquals("ALWAYS", settings.getModuleRegenerationPreference());

        settings.setModuleRegenerationPreference("NEVER");
        assertEquals("NEVER", settings.getModuleRegenerationPreference());

        settings.setModuleRegenerationPreference("unexpected");
        assertEquals("ASK", settings.getModuleRegenerationPreference());
    }
}
