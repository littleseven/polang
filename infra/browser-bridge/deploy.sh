#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
rsync -av --delete --exclude node_modules --exclude .env ./ xuxing:/opt/browser-bridge/
ssh xuxing 'cd /opt/browser-bridge && npm install --omit=dev && sudo systemctl restart browser-bridge && sleep 1 && curl -s -H "X-Bridge-Token: $(grep ^BRIDGE_TOKEN .env | cut -d= -f2)" http://127.0.0.1:8788/healthz'
