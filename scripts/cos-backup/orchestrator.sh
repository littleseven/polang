#!/usr/bin/env bash
# 三机周备份编排器 — 部署于 HK prod /home/ubuntu/cos-backup/orchestrator.sh（cron 周日 03:17 CST）
# 作用：拉取 HK / 北京xuxing / KimiClaw 三台机的 tar|openssl 加密流 → 上传 COS → 清理过期对象
# 设计要点：
#   * COS 凭据只存在于 HK 的 /root/.cos.yaml（coscli config 已配好），不出 HK
#   * 口令文件：HK=/etc/picme/backup.pass(root) xuxing=/home/ubuntu/.backup.pass kimiclab=/root/.backup.pass
#   * ⚠️ tar 陷阱（2026-10-05 踩过）：老式 `tar czf` 后跟的 --exclude 会被当文件名；且选项必须全部
#     放在路径操作数之前（GNU 会置换解析，BSD/POSIX 不会）。因此统一用 `--exclude=X -czf - -C / 路径...`。
#   * ⚠️ coscli 陷阱：cos:// 首段必须是桶名（polang-<appid>），backups 只是 key 前缀
#   * SSOT 本仓 scripts/cos-backup/orchestrator.sh；改完 scp 到 HK 同路径
set -uo pipefail

BASE=/home/ubuntu/cos-backup
PREFIX=backups
KEEP=8
STAMP=$(date +%Y%m%d-%H%M)
LOG=$BASE/backup.log
COSCLI="sudo -n /usr/local/bin/coscli --disable-log"
COS_BUCKET=$(sudo -n grep -oP '(?<=^COS_BUCKET=).*' /etc/picme/server.env)
ENC_ARGS="-aes-256-cbc -pbkdf2 -iter 200000 -salt"
FAIL=0

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; logger -t cos-backup "$*" 2>/dev/null || true; }

upload() {  # $1=tag $2=本地加密文件
  if $COSCLI cp "$2" "cos://$COS_BUCKET/$PREFIX/$1/$(basename "$2")" >/dev/null 2>&1; then
    log "OK upload $1"
  else
    log "FAIL upload $1"; FAIL=1
  fi
}

prune() {  # $1=tag，保留最近 KEEP 份
  $COSCLI ls "cos://$COS_BUCKET/$PREFIX/$1/" -r 2>/dev/null | awk -F'|' 'NF>1 {gsub(/ /,"",$1); print $1}' \
    | grep "^$PREFIX/" | sort | head -n -"$KEEP" | while read -r obj; do
        $COSCLI rm "cos://$COS_BUCKET/$obj" >/dev/null 2>&1 && log "PRUNE $obj"
      done
}

do_host() {  # $1=tag，$2...=收集函数（stdout 吐加密流）
  local tag=$1; shift
  local F="$BASE/$tag-$STAMP.tar.gz.enc"
  log "START $tag"
  if "$@" > "$F" 2>>"$LOG" && [ -s "$F" ]; then
    local SZ; SZ=$(du -h "$F" | cut -f1)
    upload "$tag" "$F"; prune "$tag"
    rm -f "$F"
    log "OK $tag size=$SZ"
  else
    log "FAIL collect $tag"; rm -f "$F"; FAIL=1
  fi
}

collect_hk() {
  # picme.db 为在线 sqlite：优先用 sqlite3 .backup 取一致快照，失败则直接打包（可接受轻微撕裂）
  local dbarg=var/lib/picme/picme.db
  sudo -n bash -c 'command -v sqlite3 >/dev/null 2>&1 && sqlite3 /var/lib/picme/picme.db ".backup /tmp/picme-consistent.db"' 2>/dev/null \
    && sudo -n test -s /tmp/picme-consistent.db && dbarg=tmp/picme-consistent.db
  crontab -l > /tmp/cron-ubuntu.bak 2>/dev/null || true
  sudo -n crontab -l > /tmp/cron-root.bak 2>/dev/null || true
  sudo -n bash -c "tar \
      --exclude=home/ubuntu/.openclaw/npm \
      --exclude=home/ubuntu/.openclaw/browser-existing-session \
      --exclude=home/ubuntu/.openclaw/cache \
      -czf - -C / \
      etc/picme \
      $dbarg \
      etc/nginx \
      etc/ssh \
      etc/systemd/system \
      home/ubuntu/.openclaw \
      home/ubuntu/.ssh \
      home/ubuntu/deploy-switch.sh \
      /tmp/cron-ubuntu.bak /tmp/cron-root.bak \
      2>/dev/null | openssl enc $ENC_ARGS -pass file:/etc/picme/backup.pass"
  local rc=$?
  sudo -n rm -f /tmp/picme-consistent.db 2>/dev/null
  rm -f /tmp/cron-ubuntu.bak /tmp/cron-root.bak
  return $rc
}

collect_xuxing() {
  ssh -o ConnectTimeout=20 -o BatchMode=yes -o StrictHostKeyChecking=accept-new xuxing "
    crontab -l > /tmp/cron.bak 2>/dev/null
    sudo -n bash -c 'tar \
      --exclude=home/ubuntu/.openclaw/npm \
      --exclude=home/ubuntu/.openclaw/browser-existing-session \
      --exclude=home/ubuntu/.openclaw/cache \
      --exclude=var/www/ota/*.apk \
      --exclude=var/www/xuxingzhiyuan/docs-site-backups \
      --exclude=*.mp4 \
      -czf - -C / \
      home/ubuntu/.openclaw \
      home/ubuntu/.ssh \
      etc/nginx \
      etc/ssh \
      etc/systemd/system \
      opt/browser-vnc \
      var/www/ota \
      var/www/html \
      var/www/xuxingzhiyuan \
      /tmp/cron.bak \
      2>/dev/null | openssl enc $ENC_ARGS -pass file:/home/ubuntu/.backup.pass'"
}

collect_kimiclab() {
  ssh -o ConnectTimeout=20 -o BatchMode=yes -o StrictHostKeyChecking=accept-new kimi-worker "
    crontab -l > /tmp/cron.bak 2>/dev/null
    tar --exclude=root/.openclaw/extensions \
        --exclude=root/.openclaw/workspace \
        --exclude=root/.openclaw/media \
        --exclude=root/.openclaw/browser \
        --exclude=root/.openclaw/logs \
        --exclude=root/.openclaw/npm \
        -czf - -C / \
        root/claude-tunnel.env \
        root/.claude \
        root/.ssh \
        root/Documents \
        etc/ssh \
        etc/systemd/system \
        root/.openclaw \
        /tmp/cron.bak \
        2>/dev/null | openssl enc $ENC_ARGS -pass file:/root/.backup.pass"
}

do_host VM-0-14   collect_hk
do_host VM-0-13   collect_xuxing
do_host VM-76-154 collect_kimiclab

log "DONE fail=$FAIL"
exit $FAIL
