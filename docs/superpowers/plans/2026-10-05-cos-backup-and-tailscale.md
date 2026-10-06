# 三机 COS 加密备份 + Tailscale 组网 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** HK 编排每周拉取三台机（HK prod / 北京 xuxing / KimiClaw）的配置+状态，openssl 加密后上传 COS；随后三机 Tailscale 组网打通北京↔KimiClaw 直连。

**Architecture:** 备份采用「HK 单点编排」——cron 只挂在 HK，凭据只从 `/etc/picme/server.env` 运行时读取，KimiClaw/北京机不落任何 COS 凭据、不装任何新工具（openssl 三台都有）。远端机器通过既有 SSH 通道（KimiClaw 走 chisel 隧道 `kimi-worker` 别名，xuxing 新配 HK→xuxing 免密钥）把 `tar|openssl` 流吐给 HK，HK 统一上传 COS 并清理过期对象。网络层用 Tailscale 全互联（控制面三台都验证可达），失败兜底是 autossh 反向隧道。

**Tech Stack:** bash + openssl enc (aes-256-cbc/pbkdf2) + COSCLI（仅 HK 装）+ cron + Tailscale（apt 官方源）

---

## 侦察结论（已验证事实）

| | HK prod | 北京 xuxing | KimiClaw |
|---|---|---|---|
| host / IP | VM-0-14-ubuntu / 43.161.201.142 | VM-0-13-ubuntu / 82.157.166.5（ssh 别名 `xuxing`） | VM-76-154-ubuntu / 无公网，经 HK 隧道 `ssh kimi-worker`（root） |
| 用户 | ubuntu（sudo -n 可用） | ubuntu | root |
| 磁盘空闲 | 18G | 48G | 18G |
| COS 凭据 | `/etc/picme/server.env` 有 COS_BUCKET/COS_REGION=ap-hongkong/COS_SECRET_ID/KEY | 无 | 无 |
| Tailscale 控制面 | login.tailscale.com → 302 可达 | 302 可达 | 302 可达 |
| 自研单元 | picme-api、chisel-server | browser-vnc-* ×8 | gateway、chisel-client |

**备份范围**（大体积可再生目录一律排除；用户已明确 KimiClaw 的 `/root/polang` 无备份价值）：

- **HK**：`/etc/picme/`、`/var/lib/picme/picme.db`、`/etc/nginx/`、`/etc/systemd/system/{picme-api,chisel-server}.service`、`/home/ubuntu/.openclaw/`（排除 `npm/`、`browser-existing-session/`、`cache/`）、`/home/ubuntu/.ssh/`、`/home/ubuntu/deploy-switch.sh`、`/etc/ssh/ssh_host_*`、crontab
- **xuxing**：`/home/ubuntu/.openclaw/`（同上排除）、`/home/ubuntu/.ssh/`、`/etc/nginx/`、`browser-vnc` 单元+`/opt/browser-vnc` 配置脚本（排除二进制，执行时核实）、`/var/www/ota/*.json`（排除 .apk）、`/var/www/xuxingzhiyuan/`、`/var/www/html/`、`/etc/ssh/ssh_host_*`、crontab
- **KimiClaw**：`/root/claude-tunnel.env`、`/root/.claude/`（31M）、`/root/.ssh/`、`/etc/ssh/ssh_host_*`、`/etc/systemd/system/*.service`、`/root/.openclaw/`（排除 `extensions/`(1.1G)、`workspace/`(600M)、`media/`(328M)、`browser/`、`logs/`）、`/root/Documents/`(508K)、crontab。排除项（media/workspace）先不加，需要时用户说一声

**产物路径：** 脚本 SSOT 入仓 `scripts/cos-backup/orchestrator.sh` + 本计划文档；部署副本在 HK `/home/ubuntu/cos-backup/orchestrator.sh`。

---

### Task 1: HK 侧准备（passphrase / sudo / coscli / 时区）

**Files:** 无仓内文件，仅 HK 机器状态

- [ ] **Step 1.1: 确认 sudo NOPASSWD、时区、bucket 名形态**

```bash
ssh ubuntu@43.161.201.142 '
sudo -n -l 2>/dev/null | tail -3          # 预期 (ALL) NOPASSWD: ALL
timedatectl | grep "Time zone"            # 记录时区，决定 cron 小时
sudo -n grep -c "^COS_BUCKET=" /etc/picme/server.env   # 预期 1（值形态在 1.3 内部用，不打印）
date +%F\ %T'
```

- [ ] **Step 1.2: 生成备份口令并妥善存放**

```bash
ssh ubuntu@43.161.201.142 '
sudo -n bash -c "umask 077; openssl rand -base64 32 > /etc/picme/backup.pass"
sudo -n ls -l /etc/picme/backup.pass      # 预期 -rw------- root root
sudo -n cat /etc/picme/backup.pass'       # 打印一次给用户离线保存（对话里展示）
```

- [ ] **Step 1.3: 安装 COSCLI 到 HK**

从官方发布页下载 linux-amd64 单文件到 `/usr/local/bin/coscli`（优先腾讯官方镜像，GitHub 兜底），`chmod +x`，`coscli --version` 有输出即可。若官方源不可达：兜底方案 `pip3 install coscmd`（脚本内上传命令换成 coscmd 语法）。

- [ ] **Step 1.4: 生成临时 coscli 配置并验证凭据可用（凭据不落盘、不回显）**

```bash
ssh ubuntu@43.161.201.142 '
set -a; . <(sudo -n cat /etc/picme/server.env); set +a
printf "cos:\n  base_url: .cos.%s.myqcloud.com\n  bucket: %s\n  region: %s\n  secret_id: %s\n  secret_key: %s\n  session_token: \"\"\n  max_thread: 5\n  part_size: 1\n  schema: https\n" \
  "$COS_REGION" "$COS_BUCKET" "$COS_REGION" "$COS_SECRET_ID" "$COS_SECRET_KEY" > /tmp/coscli-test.yaml
/usr/local/bin/coscli -c /tmp/coscli-test.yaml ls cos:// -a 2>&1 | head -5
rm -f /tmp/coscli-test.yaml'
```

预期：能列桶内对象（含 `apk/polang-release.apk`）。若 bucket 名不带 appid 后缀导致 404，从 server.env 的 COS_BUCKET 实际值判断（执行时允许打印 bucket 名——它出现在公开预签名 URL 里，非机密），必要时补 `-<appid>`。

---

### Task 2: 编写 orchestrator.sh（SSOT 入仓）

**Files:**
- Create: `scripts/cos-backup/orchestrator.sh`

- [ ] **Step 2.1: 写入完整脚本**（内容见下，核心交付物）

```bash
#!/usr/bin/env bash
# 三机周备份编排器 — 部署于 HK /home/ubuntu/cos-backup/，cron 每周调用
# 拉取 HK/xuxing/kimiclab 三台机的 tar|openssl 加密流，上传 COS，清理过期对象
# SSOT: polang 仓 scripts/cos-backup/orchestrator.sh（改完重新 scp 到 HK）
set -uo pipefail

BASE=/home/ubuntu/cos-backup
PASS_FILE=/etc/picme/backup.pass
COSCLI=/usr/local/bin/coscli
KEEP=8                      # 每台机保留份数
PREFIX=backups
LOG=$BASE/backup.log
STAMP=$(date +%Y%m%d-%H%M)
FAIL=0

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }

# --- COS 凭据：运行时从 server.env 取，临时配置用完即删 ---
cosconf() {
  eval "$(sudo -n grep -E '^(COS_BUCKET|COS_REGION|COS_SECRET_ID|COS_SECRET_KEY)=' /etc/picme/server.env)"
  C=$(mktemp /tmp/coscli.XXXXXX)
  printf "cos:\n  base_url: .cos.%s.myqcloud.com\n  bucket: %s\n  region: %s\n  secret_id: %s\n  secret_key: %s\n  session_token: \"\"\n  max_thread: 5\n  part_size: 1\n  schema: https\n" \
    "$COS_REGION" "$COS_BUCKET" "$COS_REGION" "$COS_SECRET_ID" "$COS_SECRET_KEY" > "$C"
  echo "$C"
}

upload() {  # $1=host $2=本地加密文件
  local C; C=$(cosconf)
  "$COSCLI" -c "$C" cp "$2" "cos://$PREFIX/$1/$2" >/dev/null 2>&1 || { log "FAIL upload $1"; FAIL=1; }
  shred -u "$C" 2>/dev/null || rm -f "$C"
}

prune() {   # $1=host 保留最近 KEEP 份
  local C; C=$(cosconf)
  "$COSCLI" -c "$C" ls "cos://$PREFIX/$1/" 2>/dev/null | grep -o "$PREFIX/$1/[^ ]*\.enc" | sort | head -n -"$KEEP" \
    | while read -r obj; do "$COSCLI" -c "$C" rm "cos://$obj" >/dev/null 2>&1; done
  shred -u "$C" 2>/dev/null || rm -f "$C"
}

ENC="openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt"

do_host() {  # $1=标签 $2=收集命令(输出加密流到 stdout)
  local tag=$1 cmd=$2 F
  F="$BASE/$tag-$STAMP.tar.gz.enc"
  log "START $tag"
  if bash -c "$cmd" > "$F" 2>>"$LOG" && [ -s "$F" ]; then
    upload "$tag" "$F"; rm -f "$F"; prune "$tag"
    log "OK $tag $(du -h "$F" 2>/dev/null | cut -f1)"
  else
    log "FAIL collect $tag"; rm -f "$F"; FAIL=1
  fi
}
```

三台机的收集命令（脚本尾部依次调用）：

```bash
# --- HK 自身（sudo tar 读特权路径；crontab 先落地）---
crontab -l > /tmp/cron-$STAMP.txt 2>/dev/null || true
do_host VM-0-14 "sudo -n tar czf - \
  /etc/picme /var/lib/picme/picme.db /etc/nginx \
  /etc/systemd/system/picme-api.service /etc/systemd/system/chisel-server.service \
  /etc/ssh/ssh_host_ed25519_key /etc/ssh/ssh_host_ed25519_key.pub /etc/ssh/ssh_host_rsa_key /etc/ssh/ssh_host_rsa_key.pub \
  /home/ubuntu/.openclaw /home/ubuntu/.ssh /home/ubuntu/deploy-switch.sh /tmp/cron-$STAMP.txt \
  --exclude=.openclaw/npm --exclude=.openclaw/browser-existing-session --exclude=.openclaw/cache \
  | $ENC -pass file:$PASS_FILE && rm -f /tmp/cron-$STAMP.txt"

# --- 北京 xuxing（HK→xuxing 免密钥，Task 3 建）---
do_host VM-0-13 "ssh -o ConnectTimeout=15 -o BatchMode=yes xuxing \"
  crontab -l > /tmp/cron.txt 2>/dev/null; \
  tar czf - /home/ubuntu/.openclaw /home/ubuntu/.ssh /etc/nginx /var/www/ota/*.json \
    /var/www/xuxingzhiyuan /var/www/html /tmp/cron.txt /etc/ssh/ssh_host_* \
    /etc/systemd/system/browser-vnc-*.service /opt/browser-vnc \
    --exclude=.openclaw/npm --exclude=.openclaw/browser-existing-session --exclude=.openclaw/cache \
    --exclude=opt/browser-vnc/chrome --exclude='*.apk' --exclude='*.so' --exclude=chrome-* \
  | $ENC -pass file:/home/ubuntu/.backup.pass\""

# --- KimiClaw（经 chisel 隧道，root）---
do_host VM-76-154 "ssh -o ConnectTimeout=15 -o BatchMode=yes kimi-worker \"
  crontab -l > /tmp/cron.txt 2>/dev/null; \
  tar czf - /root/claude-tunnel.env /root/.claude /root/.ssh /root/Documents \
    /etc/ssh/ssh_host_* /etc/systemd/system /tmp/cron.txt /root/.openclaw \
    --exclude=.openclaw/extensions --exclude=.openclaw/workspace --exclude=.openclaw/media \
    --exclude=.openclaw/browser --exclude=.openclaw/logs --exclude=.openclaw/npm \
  | $ENC -pass file:/root/.backup.pass\""

log "DONE fail=$FAIL"
exit $FAIL
```

注：`/etc/systemd/system` 整目录在 KimiClaw 仅 136K，全量打包（单元即配置）；HK 只挑自研单元。`prune` 依赖对象名日期排序，`sort | head -n -8` 删旧留新。

- [ ] **Step 2.2: 语法验证**：`bash -n scripts/cos-backup/orchestrator.sh` 无输出

- [ ] **Step 2.3: 部署到 HK**：`scp` 到 `/home/ubuntu/cos-backup/orchestrator.sh`，`chmod 700`

---

### Task 3: 三机接入 + 端到端恢复演练（= 本方案的"测试"）

**Files:** HK/xuxing/kimiclab 机器状态

- [ ] **Step 3.1: 建 HK→xuxing 免密钥**

```bash
ssh ubuntu@43.161.201.142 'ssh-keygen -t ed25519 -N "" -f ~/.ssh/id_ed25519 <<< y 2>/dev/null; cat ~/.ssh/id_ed25519.pub' \
  && ssh xuxing 'mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys'
# 验证：ssh ubuntu@43.161.201.142 'ssh -o BatchMode=yes xuxing hostname'  → VM-0-13-ubuntu
```

- [ ] **Step 3.2: 分发口令到两台远端机**

```bash
# KimiClaw（经 HK 跳）：/etc/picme/backup.pass → /root/.backup.pass (600)
ssh ubuntu@43.161.201.142 'sudo -n cat /etc/picme/backup.pass | ssh kimi-worker "cat > /root/.backup.pass && chmod 600 /root/.backup.pass"'
# xuxing：同文件 → /home/ubuntu/.backup.pass (600)，走 HK 中转
```

- [ ] **Step 3.3: 首跑 orchestrator（手动）**

```bash
ssh ubuntu@43.161.201.142 '/home/ubuntu/cos-backup/orchestrator.sh; tail -8 /home/ubuntu/cos-backup/backup.log'
```

预期：三行 `OK VM-0-14 / VM-0-13 / VM-76-154`，`DONE fail=0`

- [ ] **Step 3.4: 恢复演练（每台机取回→解密→tar -t 验证关键文件在内）**

```bash
ssh ubuntu@43.161.201.142 '
set -a; . <(sudo -n cat /etc/picme/server.env); set +a
# 取回 kimiclab 最新份
/usr/local/bin/coscli … ls cos://backups/VM-76-154/ | tail -1   # 拿对象名
/usr/local/bin/coscli … cp cos://backups/VM-76-154/<obj> /tmp/drill.enc
sudo -n openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:/etc/picme/backup.pass -in /tmp/drill.enc | tar -tzf - | grep -E "claude-tunnel.env|openclaw.json|ssh_host_ed25519_key" | head -3
rm -f /tmp/drill.enc'
```

预期：能列出 `/root/claude-tunnel.env`、`/root/.openclaw/openclaw.json`、ssh host key。HK 与 xuxing 同法各验一次（grep 关键词分别为 `server.env|picme.db` 和 `openclaw.json|nginx`）。**解密成功 = 测试通过**。

- [ ] **Step 3.5: 失败路径验证**：临时改一个错误口令路径跑单机段，确认日志出现 `FAIL collect` 且其余主机不受影响、退出码非 0。

---

### Task 4: cron 固化

- [ ] **Step 4.1: 按 Task 1.1 实测时区写 crontab**（目标 = 北京时间周日 03:17；若 HK 是 UTC 则写 `17 19 * * 6`）

```bash
ssh ubuntu@43.161.201.142 '(crontab -l 2>/dev/null; echo "17 3 * * 0 /home/ubuntu/cos-backup/orchestrator.sh") | crontab -'
```

- [ ] **Step 4.2: 验证**：`crontab -l | tail -1` 含 orchestrator

---

### Task 5: Tailscale 三机安装（网络层）

**Files:** 三台机各装 tailscale 包

- [ ] **Step 5.1: 三台安装（Ubuntu 24.04 官方源，xuxing/kimiclab 走国内网络验证可达后再装）**

```bash
install_ts() {  # 在目标机执行
  curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/noble.noarmor.gpg | sudo tee /usr/share/keyrings/tailscale-archive-keyring.gpg >/dev/null
  curl -fsSL https://pkgs.tailscale.com/stable/ubuntu/noble.tailscale-keyring.list | sudo tee /etc/apt/sources.list.d/tailscale.list >/dev/null
  sudo apt-get update -qq && sudo apt-get install -y tailscale
  sudo systemctl enable --now tailscaled
}
```

xuxing 与 kimiclab 若拉 pkgs.tailscale.com 超时：兜底 = 从 HK `curl -O` 静态包再 scp 过去（HK 国际带宽好）。

- [ ] **Step 5.2: 验证三台 `tailscale version` 有输出**

---

### Task 6: 入网（需用户一次性动作）

- [ ] **Step 6.1: 向用户要 auth key**（https://login.tailscale.com/admin/settings/keys 生成，Reusable、Expiration 1 天、3 台够用）或用户自己点 `tailscale up` 打印的 3 个登录 URL —— **AskUserQuestion 二选一**

- [ ] **Step 6.2: 三台 `sudo tailscale up --authkey=<key> --hostname=<polang-hk|polang-bj|kimiclaw>`**（kimiclab 用 `--accept-routes` 不需要，默认即可）

- [ ] **Step 6.3: mesh 验证**：
  - `tailscale status` 三台齐、全 direct 或可用 DERP
  - `tailscale ping` 三向通
  - **核心验收**：在 xuxing 上 `ssh root@<kimiclab-tailnet-ip> hostname` → `VM-76-154-ubuntu`（北京↔KimiClaw 不再绕港，延迟对比 ssh 经 HK 隧道）

---

### Task 7: 收尾

- [ ] **Step 7.1:** 口令已展示给用户离线保存（Task 1.2）；把「备份范围/恢复命令」写进 `scripts/cos-backup/README.md`
- [ ] **Step 7.2:** memory 落盘：备份体系要点 + Tailscale 组网状态
- [ ] **Step 7.3:** 仓内新文件（orchestrator.sh / README / 本计划）暂不入当前 OTA 分支提交，问用户去向

## Self-Review

- 覆盖：备份（Task 1-4）✓ Tailscale（Task 5-6）✓ 用户撤下的 APK 桶同步未含 ✓
- 占位符：coscli 具体下载 URL 留到 1.3 执行时从官方文档取（防链接失效），其余命令完整
- 一致性：口令路径 `/etc/picme/backup.pass`（HK）、`/root/.backup.pass`（kimiclab）、`/home/ubuntu/.backup.pass`（xuxing）三处统一；加密参数三机同一字符串
