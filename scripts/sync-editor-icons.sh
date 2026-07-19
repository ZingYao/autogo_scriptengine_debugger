#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$repo_root/jetbrains/extensions/autogo/src/main/resources/icons"
vscode_dir="$repo_root/vscode/extensions/autogo/resources/icons"

# IDEA resources 是两端共用的图标源；VSCode 构建前同步，避免图标语义和视觉逐渐分叉。
mkdir -p "$vscode_dir"
find "$vscode_dir" -maxdepth 1 -type f -name '*.svg' -delete
cp "$source_dir"/*.svg "$vscode_dir"/

echo "Synced shared AutoGo icons: $source_dir -> $vscode_dir"
