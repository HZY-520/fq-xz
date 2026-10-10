#!/usr/bin/env bash
# =============================================================================
# 番茄小说下载器前端 — 一键构建 + 部署
#
# 用法：
#   bash deploy.sh                     # 使用下方默认路径
#   WEB_ROOT=/your/path bash deploy.sh # 覆盖目标目录
#
# 做的事：
#   1. pnpm install（依赖缺失时）
#   2. pnpm build
#   3. 把 dist/ 同步到 $WEB_ROOT（存放 index.html 的目录）
#   4. 修正属主为 web 用户
#
# 注意：$WEB_ROOT 必须是 Nginx 的 root 所指目录，静态资源会放在 $WEB_ROOT/assets/
# =============================================================================
set -euo pipefail

# ★ 按需修改 ---------------------------------------------------------------
SRC_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_ROOT="${WEB_ROOT:-/www/wwwroot/fq.huziyang.top/web}"
WEB_USER="${WEB_USER:-www}"
PNPM_BIN="${PNPM_BIN:-pnpm}"
# --------------------------------------------------------------------------

cd "$SRC_DIR"

if ! command -v "$PNPM_BIN" >/dev/null 2>&1; then
  echo "!! 未找到 $PNPM_BIN，请先安装 pnpm（npm i -g pnpm）或设置 PNPM_BIN" >&2
  exit 1
fi

echo "==> [1/4] 安装依赖（node_modules 已存在则跳过）"
if [ ! -d node_modules ]; then
  "$PNPM_BIN" install
else
  echo "    已存在，跳过"
fi

echo "==> [2/4] 构建"
"$PNPM_BIN" build

echo "==> [3/4] 部署到 $WEB_ROOT"
mkdir -p "$WEB_ROOT" "$WEB_ROOT/assets"
cp -f dist/index.html "$WEB_ROOT/index.html"
# 清理旧 hash 资源后整体同步，避免残留堆积
rm -rf "$WEB_ROOT/assets"
mkdir -p "$WEB_ROOT/assets"
cp -rf dist/assets/. "$WEB_ROOT/assets/"

echo "==> [4/4] 修正属主"
if id "$WEB_USER" >/dev/null 2>&1; then
  chown -R "$WEB_USER:$WEB_USER" "$WEB_ROOT"
else
  echo "    用户 $WEB_USER 不存在，跳过 chown"
fi

echo
echo "部署完成："
echo "  docroot : $WEB_ROOT"
echo "  资源    : $(ls -1 "$WEB_ROOT/assets" | wc -l) 个文件"
echo
echo "提示：改了 Nginx 配置后记得 nginx -t && nginx -s reload"
