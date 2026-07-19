package com.autogo.jetbrains;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 验证 JetBrains 工具栏的移动端引擎状态文案。 */
final class AutoGoToolWindowFactoryTest {
    /** 状态标签必须展示实时状态，并为缺失状态提供明确兜底。 */
    @Test
    void formatsRemoteEngineState() {
        // 先验证正常运行状态，再验证尚未完成首次探测时的空状态。
        JLabel label = new JLabel();
        AutoGoToolWindowFactory.updateEngineState(label, "running");
        assertEquals("移动端引擎：running", label.getText());
        assertEquals("移动端引擎状态：running", label.getToolTipText());

        AutoGoToolWindowFactory.updateEngineState(label, " ");
        assertEquals("移动端引擎：未知", label.getText());
        assertEquals("移动端引擎状态：未知", label.getToolTipText());
    }
}
