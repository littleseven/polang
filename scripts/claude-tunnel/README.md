# claude-tunnel

chisel wss 反向隧道 + Claude 流式网关。让外部经 api.polang.net 实时、流式、多轮驱动 KimiClaw 上的 Claude Code（GLM 后端）。设计 spec 与实现总结已随交付清理，git 历史可查。

## 拓扑
```
app → api.polang.net(nginx /v1/claude-chat，Phase 2) → 127.0.0.1:3001(chisel 隧道口)
    → [chisel wss 隧道] → KimiClaw 127.0.0.1:3000(网关) → claude --resume(GLM)
```
Phase 1 验收不含 app/server：直接 `curl 127.0.0.1:3001/chat`。

## 部署
1. **PoLang 服务器**：`deploy/install-chisel.sh` → 配 `/etc/picme/tunnel.env`(`CHISEL_PSK`) → 装 `chisel-server.service` → nginx 加 `/tunnel`(见 `deploy/nginx-tunnel.conf`)。见 plan Task 5。
2. **KimiClaw**：落 `gateway/` + venv(`pip install -r gateway/requirements.txt`) → 配 `tunnel.env`(同 PSK) → 装 `gateway.service` + `chisel-client.service`。见 plan Task 6。claude 权限白名单 = `gateway/claude-settings.json`（`server.py` 用 `--settings` 指定该文件，随 `gateway/` 落盘即生效；`CT_SETTINGS` 可覆盖路径）。
   - **重置/重启后一键恢复**：腾讯云 console 跑 `bash deploy/bootstrap-kimiclaw.sh` —— git pull + `gh auth setup-git` + 起 gateway + 起 chisel（含反向 SSH `R:3022`）+ 注入 prod 公钥 + 健康验证。跑完后**日常不再需要登 console**：从 prod `ssh -p 3022 root@127.0.0.1` 直达 KimiClaw 远程运维。前提：repo 已在 `/root/polang`、chisel binary 已装（重启级重置适用；重装系统需先手动 clone + 装 chisel/gh）。
3. **验证**：`curl http://127.0.0.1:3001/healthz`(服务器上) → `ok`。

## 三层鉴权（spec §9）
- chisel PSK（client→server 建隧道）
- 端口只绑 127.0.0.1（3001/3000/8090）
- （Phase 2 补 Ktor X-App-Token）

## 网关本地开发
```bash
cd gateway && python3 -m pytest -v          # 翻译 + session 单测
bash smoke/run-smoke.sh                      # mock claude 端到端
```

## 交付三档（push / pr / auto）

`POST /deliver` 支持 `mode` 参数：

| 模式 | 行为 | 环境要求 |
|---|---|---|
| `push` | commit + push `claude-chat/<sid>` | git credential 可用即可 |
| `pr` | push 分支 + `gh pr create` 建 GitHub PR | KimiClaw 已登录 `gh` + 有写权限 `GITHUB_TOKEN` |
| `auto` | push 分支 + `./gradlew -p server test` + ff-merge 进 `main` + push | 同上，且 server 单测通过 |

- 环境变量 `CT_BASE_BRANCH` 指定 auto/pr 的目标分支，默认 `main`。
- 环境变量 `CT_DELIVER_TIMEOUT` 控制交付整体超时（含测试），默认 120s；旧 `CT_PUSH_TIMEOUT` 仍兼容。
- pr/auto 若 `gh` 未登录/token 无权限，会返回明确错误；auto 测试失败或 ff 冲突会降级为仅 push 分支。

## 已知限制（Phase 1）
- 网关以 root + `IS_SANDBOX=1` 跑；claude 权限走 `gateway/claude-settings.json` 的 `permissions.allow` 白名单（`--settings` 指定，**非** `--dangerously-skip-permissions`/bypass——claude CLI 硬禁 root+bypass，见 commit `df354bec`）。spec §10 root 风险，接受。
- 无并发隔离（多 session 共享 KimiClaw 资源，Phase 1 单用户够用）。
- claude 以 root 运行需 `IS_SANDBOX=1`（网关 `server.py` 已设；手动实测也要带）。

## MCP app 工具（2026-08-01，AI 工程师模式）

`app_tools_mcp.py` 是 gateway 同目录的 MCP stdio server，向 Claude Code 暴露 5 个 `app_*` 工具（日志/崩溃/聊天历史/运行时状态/相册摘要）。tool call 经 SSE 下行 `app_tool_request` 到 App，App 采集后经 `POST /v1/claude-tool-result` 回传，gateway 再返回给 MCP。

部署说明：
- `app_tools_mcp.py` 随 `gateway/` 一起部署，无额外步骤。
- gateway 启动时自动生成 `app-tools.mcp.json`（MCP 配置，含**绝对路径**，属运行时生成物，已在 `gateway/.gitignore` 中忽略，勿提交）。
- 新环境变量 `CT_APP_TOOL_TIMEOUT`：单次 app tool 调用超时秒数，默认 `60`，可在 `tunnel.env` 覆盖。
- 新环境变量 `CT_STDOUT_LIMIT`：claude stream-json 单行读取上限（字节），默认 16MiB（asyncio 默认 64KiB 会被大 tool_result/读大文件触发 `chunk is longer than limit`，2026-08-04 修复）。
- 发版流程不变：`ssh kimi-worker 'cd /root/polang && git pull && systemctl restart gateway'`（重启 gateway 即生效）。
