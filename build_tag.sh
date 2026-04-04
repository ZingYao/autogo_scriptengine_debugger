#!/bin/bash

# 构建并推送 Git Tag 的脚本
# 用法: ./build_tag.sh <版本号>
# 示例: ./build_tag.sh v1.0.0

# 检查是否提供了版本号
if [ -z "$1" ]; then
    echo "错误: 请提供版本号"
    echo "用法: $0 <版本号>"
    echo "示例: $0 v1.0.0"
    exit 1
fi

VERSION=$1

# 验证版本号格式 (以 v 开头，后跟数字和点)
if [[ ! $VERSION =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "错误: 版本号格式不正确"
    echo "正确格式: vX.Y.Z (例如: v1.0.0)"
    exit 1
fi

# 检查是否有未提交的更改
if [[ -n $(git status --porcelain) ]]; then
    echo "警告: 存在未提交的更改"
    git status --short
    echo ""
    read -p "是否继续? (y/n): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "已取消"
        exit 1
    fi
fi

# 检查 tag 是否已存在
if git rev-parse "$VERSION" >/dev/null 2>&1; then
    echo "错误: Tag $VERSION 已存在"
    echo "现有的 tags:"
    git tag -l | tail -5
    exit 1
fi

echo "=========================================="
echo "准备创建并推送 Tag: $VERSION"
echo "=========================================="

# 构建
echo ""
echo "[1/4] 构建项目..."
go build ./...
if [ $? -ne 0 ]; then
    echo "错误: 构建失败"
    exit 1
fi
echo "构建成功"

# 创建 tag
echo ""
echo "[2/4] 创建 Tag $VERSION..."
git tag -a "$VERSION" -m "Release $VERSION"
if [ $? -ne 0 ]; then
    echo "错误: 创建 Tag 失败"
    exit 1
fi
echo "Tag 创建成功"

# 推送代码
echo ""
echo "[3/4] 推送代码到远程仓库..."
git push
if [ $? -ne 0 ]; then
    echo "警告: 推送代码失败，但 Tag 已创建"
    echo "你可以手动执行: git push && git push origin $VERSION"
    exit 1
fi

# 推送 tag
echo ""
echo "[4/4] 推送 Tag 到远程仓库..."
git push origin "$VERSION"
if [ $? -ne 0 ]; then
    echo "错误: 推送 Tag 失败"
    echo "你可以手动执行: git push origin $VERSION"
    exit 1
fi

echo ""
echo "=========================================="
echo "完成! Tag $VERSION 已创建并推送"
echo "=========================================="
