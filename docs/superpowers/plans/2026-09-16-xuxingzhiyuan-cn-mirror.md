# xuxingzhiyuan.cn 官网国内镜像 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 polang 官网（docs-site/）镜像部署到腾讯云国内主机 xuxing（82.157.166.5），备案期间以 8100 端口 + basic auth 预览，并完成 SSH 加固。

**Architecture:** 单源双部署——`docs-site/` 仍是唯一源，新增 `scripts/deploy-docs-site-cn.sh` 与 HK 部署脚本并列；服务器侧 nginx 监听 8100 服务 `/var/www/xuxingzhiyuan/docs-site`；备案通过后再切 80/443（本期不做）。

**Tech Stack:** bash + rsync + nginx (Ubuntu 24.04) + openssl htpasswd。规格文档：`docs/superpowers/specs/2026-09-16-xuxingzhiyuan-cn-mirror-design.md`。

**服务器既定事实（已核实）：** ssh 别名 `xuxing` = `ubuntu@82.157.166.5`；sudo 免密；服务器已有进程 node(127.0.0.1:26972)、chrome(127.0.0.1:9222)，**不得触碰**；安全组当前仅开 22。

---

### Task 1: 服务器初始化（nginx + 站点目录）

**Files:** 无仓库文件，全部服务器侧操作。

- [ ] **Step 1: apt 更新并安装 nginx**

```bash
ssh xuxing 'sudo apt-get update -qq && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nginx'
```

预期输出末尾含 `Setting up nginx ...`，无报错。

- [ ] **Step 2: 创建站点目录并禁用默认站**

```bash
ssh xuxing 'sudo mkdir -p /var/www/xuxingzhiyuan/docs-site && sudo chown -R ubuntu:ubuntu /var/www/xuxingzhiyuan && sudo rm -f /etc/nginx/sites-enabled/default && sudo nginx -t && sudo systemctl reload nginx && echo "<h1>xuxingzhiyuan placeholder</h1>" | sudo tee /var/www/xuxingzhiyuan/docs-site/index.html'
```

预期：`syntax is ok` / `test is successful`。

- [ ] **Step 3: 验证 nginx 运行且 80 不对外（安全组兜底）**

```bash
ssh xuxing 'systemctl is-active nginx && curl -s -o /dev/null -w "%{http_code}\n" http://127.0.0.1:80/'
nc -z -G 3 82.157.166.5 80 && echo "80 对外开了(异常!)" || echo "80 对外关闭(正确)"
```

预期：`active`、`200`（本机回环）、`80 对外关闭(正确)`。

---

### Task 2: 8100 预览配置（repo 配置文件 + basic auth + 服务器启用）

**Files:**
- Create: `infra/xuxing/nginx-preview.conf`（仓库内 nginx 配置源）
- 服务器侧: `/etc/nginx/sites-available/xuxingzhiyuan-preview`、`/etc/nginx/.htpasswd-xuxing`
- 本机: `~/.config/xuxing-preview-auth`（凭据，chmod 600，**不入仓库**）

- [ ] **Step 1: 写 repo 配置文件 `infra/xuxing/nginx-preview.conf`**

```nginx
# xuxingzhiyuan.cn 备案期预览站：非标端口 8100 + basic auth。
# 备案通过后按 specs/2026-09-16-xuxingzhiyuan-cn-mirror-design.md §6 切换 80/443 并移除本配置。
server {
    listen 8100;
    listen [::]:8100;
    server_name xuxingzhiyuan.cn www.xuxingzhiyuan.cn _;

    root /var/www/xuxingzhiyuan/docs-site;
    index index.html;
    charset utf-8;

    auth_basic "xuxingzhiyuan preview";
    auth_basic_user_file /etc/nginx/.htpasswd-xuxing;

    location / {
        try_files $uri $uri/ =404;
    }

    location /assets/ {
        expires 7d;
        add_header Cache-Control "public";
    }

    gzip on;
    gzip_types text/css application/javascript image/svg+xml application/json;
    gzip_min_length 1024;
}
```

- [ ] **Step 2: 生成本机凭据文件**

```bash
PREVIEW_PASS=$(openssl rand -base64 12)
mkdir -p ~/.config
printf 'guoshuai:%s\n' "$PREVIEW_PASS" > ~/.config/xuxing-preview-auth
chmod 600 ~/.config/xuxing-preview-auth
echo "预览凭据: $(cat ~/.config/xuxing-preview-auth)"
```

预期：输出形如 `guoshuai:Kx3m...` 的凭据（仅展示给用户，勿写入任何仓库文件）。

- [ ] **Step 3: htpasswd 上服务器 + 配置文件安装启用**

```bash
PREVIEW_PASS=$(cut -d: -f2 ~/.config/xuxing-preview-auth)
HASH=$(ssh xuxing "openssl passwd -apr1 '$PREVIEW_PASS'")
printf 'guoshuai:%s\n' "$HASH" | ssh xuxing 'sudo tee /etc/nginx/.htpasswd-xuxing >/dev/null'
scp infra/xuxing/nginx-preview.conf xuxing:/tmp/nginx-preview.conf
ssh xuxing 'sudo mv /tmp/nginx-preview.conf /etc/nginx/sites-available/xuxingzhiyuan-preview && sudo ln -sf /etc/nginx/sites-available/xuxingzhiyuan-preview /etc/nginx/sites-enabled/ && sudo chown root:www-data /etc/nginx/.htpasswd-xuxing && sudo chmod 640 /etc/nginx/.htpasswd-xuxing && sudo nginx -t && sudo systemctl reload nginx'
```

预期：`syntax is ok` / `test is successful`。

- [ ] **Step 4: 验证 basic auth 生效（401 无凭据 / 200 有凭据，此时是 placeholder 页）**

```bash
ssh xuxing 'curl -s -o /dev/null -w "无凭据:%{http_code}\n" http://127.0.0.1:8100/ && curl -s -u "$(cat ~/.config/xuxing-preview-auth)" -o /dev/null -w "有凭据:%{http_code}\n" http://127.0.0.1:8100/'
```

注意：`cat ~/.config/...` 在本机展开后作为 `-u` 参数传给远端 curl —— 实际执行用本地变量拼接：
```bash
CREDS=$(cat ~/.config/xuxing-preview-auth)
ssh xuxing "curl -s -o /dev/null -w 'noauth:%{http_code}\n' http://127.0.0.1:8100/; curl -s -u '$CREDS' -o /dev/null -w 'authed:%{http_code}\n' http://127.0.0.1:8100/"
```

预期：`noauth:401`、`authed:200`。

- [ ] **Step 5: Commit**

```bash
git add infra/xuxing/nginx-preview.conf
git commit -m "feat(infra): xuxingzhiyuan.cn 备案期 8100 预览 nginx 配置"
```

---

### Task 3: 部署脚本 `scripts/deploy-docs-site-cn.sh`

**Files:**
- Create: `scripts/deploy-docs-site-cn.sh`

- [ ] **Step 1: 写脚本（与 `deploy-docs-site.sh` 同构，差异：HOST=xuxing、目录、8100 本地校验）**

```bash
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
```

- [ ] **Step 2: 语法检查**

```bash
bash -n scripts/deploy-docs-site-cn.sh && chmod +x scripts/deploy-docs-site-cn.sh && echo OK
```

预期：`OK`。

- [ ] **Step 3: Commit**

```bash
git add scripts/deploy-docs-site-cn.sh
git commit -m "feat(scripts): 官网国内镜像一键部署脚本(xuxing, 备案期 8100 校验)"
```

---

### Task 4: 首次部署 + 全量验证 + 公网预览开通

**Files:** 无新文件。

- [ ] **Step 1: 执行首次部署**

```bash
./scripts/deploy-docs-site-cn.sh
```

预期：4 步全绿，末尾 `✅ 部署成功`。（备份目录首次为空目录 `cp -r` 也可成功；若报错 `cp: cannot stat` 属首次无旧版，可忽略并重跑前先 `ssh xuxing 'mkdir -p /var/www/xuxingzhiyuan/docs-site'` 保证目录存在。）

- [ ] **Step 2: 本机直连 8100 全量验证**

```bash
CREDS=$(cat ~/.config/xuxing-preview-auth)
nc -z -G 3 82.157.166.5 8100 && echo "8100 可达" || echo "8100 不可达(需先开安全组)"
curl -s -o /dev/null -w "无凭据:%{http_code}\n" -m 5 http://82.157.166.5:8100/
curl -s -u "$CREDS" -m 5 http://82.157.166.5:8100/ | grep -o '<title>[^<]*</title>'
curl -s -u "$CREDS" -o /dev/null -w "截图资源:%{http_code} %{size_download}B\n" -m 10 http://82.157.166.5:8100/assets/shots/zh-CN/01-gallery.jpg
```

预期（安全组未开时）：`8100 不可达`——先做 Step 3 再回来重跑。
预期（安全组已开）：`无凭据:401`、`<title>PoLang 破浪相册 · 隐私优先的智能相册</title>`、`截图资源:200 ...B`（>100KB）。

- [ ] **Step 3: 【用户操作】腾讯云控制台放行 8100**

控制台 → 云服务器 → 安全组 → 入站规则 → 添加规则：协议 TCP、端口 8100、源 0.0.0.0/0。完成后告知执行者，重跑 Step 2 须全绿。

- [ ] **Step 4: 与 polang.net 内容一致性抽查**

```bash
curl -s -u "$(cat ~/.config/xuxing-preview-auth)" http://82.157.166.5:8100/en/ -o /tmp/cn-en.html
curl -s https://polang.net/en/ -o /tmp/hk-en.html
diff <(grep -oE '<(h1|h2)[^>]*>[^<]*' /tmp/cn-en.html) <(grep -oE '<(h1|h2)[^>]*>[^<]*' /tmp/hk-en.html) && echo "EN 页标题一致"
```

预期：`EN 页标题一致`（以仓库源为准；若 HK 线上落后于仓库 HEAD，个别文案差异属正常，以仓库为准）。

---

### Task 5: SSH 加固（禁用密码登录）

**Files:** 服务器侧 `/etc/ssh/sshd_config.d/99-disable-password.conf`。

- [ ] **Step 1: 写 sshd drop-in 并校验**

```bash
ssh xuxing 'printf "PasswordAuthentication no\nKbdInteractiveAuthentication no\n" | sudo tee /etc/ssh/sshd_config.d/99-disable-password.conf && sudo sshd -t && echo CONF_OK'
```

预期：`CONF_OK`。注意 Ubuntu 24.04 的 `/etc/ssh/sshd_config` 默认含 `Include /etc/ssh/sshd_config.d/*.conf` 置顶，drop-in 优先生效；若 `sudo sshd -T | grep -i passwordauthentication` 不为 `no`，则改为直接编辑主配置文件。

- [ ] **Step 2: 重载 sshd（不断连接）**

```bash
ssh xuxing 'sudo systemctl reload ssh && echo RELOADED'
```

预期：`RELOADED`。

- [ ] **Step 3: 双向验证——密钥仍通、密码路径已死**

```bash
ssh -o BatchMode=yes xuxing 'echo KEY_OK'
ssh -o BatchMode=yes -o PubkeyAuthentication=no -o PreferredAuthentications=password xuxing exit 2>&1 | tail -1
```

预期：第一行 `KEY_OK`；第二行 `Permission denied (publickey)`（密码方式已无 —— 注意报错里不再出现 `password` 字样即成功）。

**兜底说明（写给用户）**：若极端情况下密钥丢失，腾讯云控制台「实例 → 登录 → VNC」仍可进入系统修复，不会锁死。

---

### Task 6: 收尾——更新 spec 验收清单

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-xuxingzhiyuan-cn-mirror-design.md`（§7 勾选已达成的验收项）

- [ ] **Step 1: 勾选验收项**

将 §7 中以下三项改为 `- [x]`：
- `scripts/deploy-docs-site-cn.sh` 一键部署：备份 + rsync + 远端校验全绿
- `http://82.157.166.5:8100/` basic auth 后可访问，内容与 polang.net 一致（首页标记「零图片上传隐私安全」、zh/en/tw 三语、docsify 文档站）
- 服务器 SSH 密码登录已禁用（`PasswordAuthentication no` 生效）
（第 1 项 `ssh xuxing` 免密登录已在撰写时达成，一并勾选。）

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/specs/2026-09-16-xuxingzhiyuan-cn-mirror-design.md
git commit -m "docs(design): xuxingzhiyuan.cn 镜像本期验收项勾选完成"
```

---

## 交付后状态

- 预览地址：`http://82.157.166.5:8100/`（basic auth，凭据在本机 `~/.config/xuxing-preview-auth`）
- 等待用户完成企业备案（材料清单见 spec §5）
- 备案通过后执行 spec §6（切 80/443 + HTTPS + 页脚备案号，关闭 8100）
