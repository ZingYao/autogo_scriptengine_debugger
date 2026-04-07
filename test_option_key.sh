#!/bin/bash

# macOS Terminal Option 键配置测试脚本
# 用于验证 Option 键是否被正确配置为 Meta 键

echo "================================"
echo "macOS Terminal Option 键配置测试"
echo "================================"
echo ""
echo "请按照以下步骤测试："
echo ""
echo "1. 按 Option+L 键"
echo "   - 如果显示: ^[l 或 �[l → ✅ 配置成功"
echo "   - 如果显示: ¬ → ❌ 配置失败（Option 键仍在输入特殊字符）"
echo ""
echo "2. 按 Option+D 键"
echo "   - 如果显示: ^[d 或 �[d → ✅ 配置成功"
echo "   - 如果显示: ∂ → ❌ 配置失败（Option 键仍在输入特殊字符）"
echo ""
echo "3. 按 Ctrl+Shift+L 键"
echo "   - 这个组合键应该始终工作（不需要配置）"
echo ""
echo "4. 按 Ctrl+C 退出测试"
echo ""
echo "================================"
echo "开始测试（cat -v 模式）："
echo "================================"
echo ""

# 使用 cat -v 显示控制字符
cat -v
