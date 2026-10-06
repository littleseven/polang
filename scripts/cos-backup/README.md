# 三机 COS 加密周备份

**部署位置**：HK prod（43.161.201.142）`/home/ubuntu/cos-backup/orchestrator.sh`，cron `17 3 * * 0`（周日 03:17 CST，仅此一处）。

**架构**：HK 单点编排拉取三台机（HK / 北京xuxing / KimiClaw）的 `tar | openssl` 加密流，统一上传 COS，保留每机 8 份。COS 凭据只存在于 HK（`/root/.cos.yaml`，来自 `/etc/picme/server.env`），另两台零凭据、零新增工具。

**备份范围**（2026-10-05 首版）：

| 机器 | tag | 内容（~实际体积） | 主要排除 |
|------|-----|------|------|
| HK prod | VM-0-14 | /etc/picme、picme.db(sqlite快照)、/etc/nginx、/etc/ssh、systemd 单元、.openclaw 配置态、ssh/cron（2.2M） | .openclaw 的 npm/browser-existing-session/cache |
| 北京 xuxing | VM-0-13 | .openclaw 配置态、/opt/browser-vnc、/var/www/ota 的 json、xuxingzhiyuan/docs-site、nginx/ssh/cron（35M） | 同上 + ota 的 *.apk、docs-site-backups、*.mp4 |
| KimiClaw | VM-76-154 | claude-tunnel.env、/root/.claude、ssh(host keys)、systemd、.openclaw 配置态、/root/Documents（41M） | .openclaw 的 extensions/workspace/media/browser/logs；/root/polang（GitHub 有，用户明确不备） |

**口令文件**（openssl aes-256-cbc + pbkdf2）：HK=`/etc/picme/backup.pass`（主本）、kimiclab=`/root/.backup.pass`、xuxing=`/home/ubuntu/.backup.pass`、本地 Mac=`~/.config/picme/backup.pass`（600）+ macOS 钥匙串（服务名 `picme-cos-backup`，取回：`security find-generic-password -s picme-cos-backup -w`）。

**恢复流程**（任意有 COS 凭据的机器）：

```bash
# 1. 列出并取回
coscli --disable-log ls cos://<bucket>/backups/<tag>/ -r
coscli --disable-log cp cos://<bucket>/backups/<tag>/<obj> /tmp/r.enc
# 2. 解密列出内容确认
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:/path/to/backup.pass -in /tmp/r.enc | tar -tzf - | less
# 3. 解密解包到目标目录（-C / 按需调整，先在临时目录核对）
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass file:/path/to/backup.pass -in /tmp/r.enc | tar -xzf - -C /restore/point
```

**改脚本**：改本仓 `scripts/cos-backup/orchestrator.sh` 后 `scp` 到 HK 同路径即生效（无需重启任何服务）。

**已踩过的坑**（改动前必读）：
1. `tar czf`（老式无横线）后面的 `--exclude` 会被当成文件名——必须新式 `tar -czf`；
2. tar 选项必须全部放在路径操作数之前（BSD/POSIX getopt 不置换，GNU 会——统一前置保平安）；
3. coscli 的 `cos://` 首段必须是完整桶名（含 appid），`backups/` 只是 key 前缀；
4. coscli 需要 `sudo`（配置在 /root）+ `--disable-log`（否则往二进制旁写日志被拒）；
5. coscmd/coscli 老版本（v0.x-beta）配置 schema 与 v1.0.9 不兼容；
6. pkill -f 的 pattern 会匹配到自己的 ssh 命令行（远程 shell cmdline 含同串），用 `[o]rchestrator` 括号技巧或干脆别 pkill。

**监控**：HK `/home/ubuntu/cos-backup/backup.log` + `journalctl -t cos-backup`。失败特征行：`FAIL collect/upload <tag>`；退出码非 0（cron 侧可见）。
