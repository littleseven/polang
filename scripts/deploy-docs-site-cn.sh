#!/usr/bin/env bash
# 部署 polang 官网国内镜像(xuxingzhiyuan.cn)到腾讯云国内服务器(ssh 别名 xuxing)。
# 备案通过前官网经 8100 端口 basic-auth 预览；备案通过后按
# docs/superpowers/specs/2026-09-16-xuxingzhiyuan-cn-mirror-design.md §6 切 80/443。
# 用法: ./scripts/deploy-docs-site-cn.sh
# 流程: sync-docs → 远端备份 → rsync 镜像 → 远端 localhost:8100 校验(首页标记+关键子页)。
set -euo pipefail
export LC_ALL=C

HOST="xuxing"
REMOTE_DIR="/var/www/xuxingzhiyuan/docs-site"
BACKUP_ROOT="/var/www/xuxingzhiyuan/docs-site-backups"
LOCAL_DIR="$(cd "$(dirname "$0")/.." && pwd)/docs-site"
CREDS_FILE="${XUXING_PREVIEW_AUTH:-$HOME/.config/xuxing-preview-auth}"
TS="$(date +%Y%m%d-%H%M%S)"
MARKER="零图片上传隐私安全"
PREVIEW_PORT=8100

[ -f "$CREDS_FILE" ] || { echo "❌ 缺少预览凭据文件 $CREDS_FILE"; exit 1; }
CREDS="$(cat "$CREDS_FILE")"

echo "==> [0/3] 同步 docs/ -> docs-site/docs/ (docsify 文档站)"
bash "$(dirname "$0")/sync-docs.sh"

echo "==> [1/3] 备份远端 $REMOTE_DIR -> $BACKUP_ROOT/docs-site.bak.$TS"
ssh -o ConnectTimeout=15 "$HOST" "mkdir -p $BACKUP_ROOT && cp -r $REMOTE_DIR $BACKUP_ROOT/docs-site.bak.$TS"

echo "==> [2/3] rsync $LOCAL_DIR -> $HOST:$REMOTE_DIR (--delete 镜像)"
rsync -az --delete "$LOCAL_DIR/" "$HOST:$REMOTE_DIR/"

echo "==> [3/3] 远端校验 localhost:$PREVIEW_PORT (basic auth)"
if ssh -o ConnectTimeout=15 "$HOST" "
  ok=1
  curl -s -u '$CREDS' http://127.0.0.1:$PREVIEW_PORT/ | grep -q '$MARKER' || { echo '❌ 首页标记缺失'; ok=0; }
  for p in en/ tw/ docs/ getting-started.html privacy-policy/; do
    code=\$(curl -s -u '$CREDS' -o /dev/null -w '%{http_code}' http://127.0.0.1:$PREVIEW_PORT/\$p)
    [ \"\$code\" = 200 ] || { echo \"❌ /\$p -> \$code\"; ok=0; }
  done
  exit \$((1-ok))
"; then
  echo "✅ 部署成功: 远端预览 http://82.157.166.5:$PREVIEW_PORT/ (basic auth)"
else
  echo "❌ 校验失败。回滚命令:"
  echo "  ssh $HOST \"sudo rm -rf $REMOTE_DIR && sudo cp -r $BACKUP_ROOT/docs-site.bak.$TS $REMOTE_DIR\""
  exit 1
fi
