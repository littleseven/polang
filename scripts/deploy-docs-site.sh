#!/usr/bin/env bash
# 部署 polang.net 官网静态站到腾讯云 HK 服务器。
# 用法: ./scripts/deploy-docs-site.sh
# 流程: 远端备份(同目录兄弟位置)→ rsync(--delete 镜像 repo)→ curl 线上校验。
set -euo pipefail
export LC_ALL=C

HOST="ubuntu@43.161.201.142"
REMOTE_DIR="/var/www/picme/docs-site"
BACKUP_ROOT="/var/www/picme/docs-site-backups"
LOCAL_DIR="$(cd "$(dirname "$0")/.." && pwd)/docs-site"
TS="$(date +%Y%m%d-%H%M%S)"
MARKER="零图片上传隐私安全"

echo "==> [0/3] 同步 docs/ -> docs-site/docs/ (docsify 文档站)"
bash "$(dirname "$0")/sync-docs.sh"

echo "==> [1/3] 备份远端 $REMOTE_DIR -> $BACKUP_ROOT/docs-site.bak.$TS"
ssh -o ConnectTimeout=15 "$HOST" "mkdir -p $BACKUP_ROOT && cp -r $REMOTE_DIR $BACKUP_ROOT/docs-site.bak.$TS"

echo "==> [2/3] rsync $LOCAL_DIR -> $HOST:$REMOTE_DIR (--delete 镜像)"
rsync -avz --delete "$LOCAL_DIR/" "$HOST:$REMOTE_DIR/"

echo "==> [3/3] 校验线上首页标记: $MARKER"
# 注意: / 会 302 到 /en/（英文页无中文标记），须直接校验 /index.html
if curl -s --max-time 20 https://polang.net/index.html | grep -q "$MARKER"; then
  echo "✅ 部署成功: https://polang.net/"
else
  echo "❌ 校验失败:首页未检测到标记。回滚命令:"
  echo "  ssh $HOST \"rm -rf $REMOTE_DIR && cp -r $BACKUP_ROOT/docs-site.bak.$TS $REMOTE_DIR\""
  exit 1
fi
