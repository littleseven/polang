#!/usr/bin/env bash
set -euo pipefail
# 注意：非 tty ssh 下的 sudo systemctl restart 需要 NOPASSWD 配置；
# 首次部署请手动在 xuxing 上执行本脚本内的 npm install / systemctl / curl 步骤。
cd "$(dirname "$0")"
rsync -av --delete --exclude node_modules --exclude .env ./ xuxing:/opt/browser-bridge/
ssh xuxing bash -s <<'EOF'
set -euo pipefail
cd /opt/browser-bridge
npm install --omit=dev
sudo systemctl restart browser-bridge
sleep 1
curl --fail-with-body -s \
  -H "X-Bridge-Token: $(grep -m1 '^BRIDGE_TOKEN=' .env | cut -d= -f2- | tr -d '\r\n')" \
  http://127.0.0.1:8788/healthz
EOF
