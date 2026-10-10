#!/usr/bin/env bash
# =============================================================================
# 打包脚本：生成可分发的前端发布包（zip）
#
# 用法：
#   bash package.sh
#   VERSION=2026.10.10 bash package.sh      # 自定义版本号
#
# 产物：../tomato-webui-<版本>.zip
#
# 包内容：构建产物 dist/ + 完整源码 src/ + 文档 + 部署脚本 + 删除服务源码
# 不包含：node_modules、分布式版本控制目录（体积从 52M 降到 ~300K）
# =============================================================================
set -euo pipefail

SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION="${VERSION:-$(date +%Y.%m.%d)}"
PKG_NAME="tomato-webui"
STAGE="$(mktemp -d)"
OUT_ZIP="$SRC_DIR/../${PKG_NAME}-${VERSION}.zip"

cleanup() { rm -rf "$STAGE"; }
trap cleanup EXIT

cd "$SRC_DIR"

echo "==> [1/6] 清理脚手架残留（若存在）"
rm -rf src/assets public/favicon.svg public/icons.svg xiaoli-glass.html 2>/dev/null || true

echo "==> [2/6] 构建（确保 dist 与源码一致）"
if command -v pnpm >/dev/null 2>&1; then
  [ -d node_modules ] || pnpm install
  pnpm build
else
  echo "    !! 未找到 pnpm，跳过构建，直接使用现有 dist/"
  [ -d dist ] || { echo "    !! dist/ 不存在，无法打包" >&2; exit 1; }
fi

echo "==> [3/6] 准备目录并记录构建信息"
DEST="$STAGE/$PKG_NAME"
mkdir -p "$DEST"
BUILD_INFO="$STAGE/$PKG_NAME/BUILD_INFO.txt"
{
  echo "番茄小说下载器 — Web UI 发布包"
  echo "=================================="
  echo "版本号    : $VERSION"
  echo "打包时间  : $(date '+%Y-%m-%d %H:%M:%S %Z')"
  echo "Node      : $(node --version 2>/dev/null || echo '未知')"
  if command -v pnpm >/dev/null 2>&1; then
    echo "pnpm      : $(pnpm --version 2>/dev/null)"
  fi
  echo
  echo "--- 构建产物（dist/）---"
  find dist -type f -printf '  %-42p %8s B\n' 2>/dev/null || find dist -type f -exec ls -l {} \;
  echo
  echo "--- 源码统计 ---"
  find src -type f | wc -l | xargs echo "  文件数        :"
  find src -type f -print0 2>/dev/null | xargs -0 cat 2>/dev/null | wc -l | xargs echo "  总行数        :"
  echo
  echo "--- 后端 API 文档 ---"
  echo "  见 API.md（$(grep -c '^| GET\|^| POST\|^| DELETE' API.md 2>/dev/null || echo '?') 条接口表格行）"
  echo
  echo "详细说明见 README.md 与 DEPLOY.md"
} > "$BUILD_INFO"

echo "==> [4/6] 复制文件到临时目录"
cp -r dist "$DEST/"
cp -r src "$DEST/"
cp -r manage "$DEST/"
for f in index.html vite.config.js package.json pnpm-lock.yaml README.md DEPLOY.md \
         API.md nginx-example.conf deploy.sh package.sh verify-mobile.js .gitignore; do
  [ -e "$f" ] && cp "$f" "$DEST/"
done
chmod +x "$DEST/deploy.sh" "$DEST/manage/delete_service.py"

echo "==> [5/6] 统一文件权限（避免 600 权限在他人机器上不可读）"
find "$DEST" -type d -exec chmod 755 {} +
find "$DEST" -type f -exec chmod 644 {} +
chmod 755 "$DEST/deploy.sh" "$DEST/package.sh" "$DEST/manage/delete_service.py"

echo "==> [5/6] 校验包内文件"
missing=0
for required in dist/index.html src/App.vue src/api.js package.json README.md API.md; do
  if [ ! -e "$DEST/$required" ]; then
    echo "    !! 缺少 $required" >&2
    missing=1
  fi
done
[ "$missing" -eq 0 ] || exit 1
echo "    必需文件齐全 ✓"

echo "==> [6/6] 生成 zip"
rm -f "$OUT_ZIP"
if command -v zip >/dev/null 2>&1; then
  (cd "$STAGE" && zip -qr "$OUT_ZIP" "$PKG_NAME")
else
  # 无 zip 命令时退回 Python
  python3 - "$STAGE" "$PKG_NAME" "$OUT_ZIP" <<'PY'
import os, sys, zipfile
stage, name, out = sys.argv[1], sys.argv[2], sys.argv[3]
root = os.path.join(stage, name)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _dirs, files in os.walk(root):
        for fn in files:
            p = os.path.join(base, fn)
            z.write(p, os.path.relpath(p, stage))
PY
fi

echo
echo "打包完成："
echo "  文件 : $OUT_ZIP"
echo "  大小 : $(du -h "$OUT_ZIP" | cut -f1)"
echo "  条目 : $(unzip -l "$OUT_ZIP" 2>/dev/null | tail -1 | awk '{print $2}' || echo '?')"
echo
unzip -l "$OUT_ZIP" 2>/dev/null | head -20 || true
