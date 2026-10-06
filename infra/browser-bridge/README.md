# browser-agent-bridge

xuxing 侧云端浏览器桥：单例 headless Chromium + 每会话临时 BrowserContext（Playwright），
纯 CDP DOM 级驱动，为 picme-server `/v1/browser/*` 提供会话执行与按需抓帧。
设计 SSOT：`docs/superpowers/specs/2026-10-06-browser-vnc-live-card-design.md`。
安全边界借鉴 [CopilotKit/openmuse](https://github.com/CopilotKit/openmuse)（MIT）`apps/worker`：
SSRF 全球单播许可名单（仅 80/443）+ 重定向落地复查 + 子请求级拦截 + WebSocket 全禁 +
token 时序安全比较 + worker 固定评估代码（不接受任意 JS 注入）。

## 端点契约

- 端点：`POST /session`（open）、`POST /session/{id}/action`、`GET /session/{id}/frame`、
  `POST /session/{id}/close`、`GET /healthz`；全部要求 `X-Bridge-Token` 头。
- 域名结果一律 HTTP 200 + JSON `status`（ok/action_failed/pool_exhausted/session_expired）。
- 请求体超限（>64KB）时 `req.destroy()` 先于响应发出，客户端可能观察到连接重置而非 413——
  应将 body 发送过程中的 reset 视为 413 等价。
- click/type 定位三模式：index（extract 返回的元素序号，LLM 首选）/ targetText / selector。

## 冒烟

```bash
BRIDGE_TOKEN=test-token node src/server.js &
# 健康检查
curl -s -H "X-Bridge-Token: test-token" http://127.0.0.1:8788/healthz
# → {"ok":true,"sessions":0}
# 开会话（带首帧）
SID=$(curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com","wantFrame":true}' http://127.0.0.1:8788/session \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["sessionId"])')
# 提取文本与可交互元素
curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"action":"extract"}' "http://127.0.0.1:8788/session/$SID/action"
# → {"status":"ok",...,"textExtract":"...","elements":[...]}
# 关会话
curl -s -X POST -H "X-Bridge-Token: test-token" "http://127.0.0.1:8788/session/$SID/close"
# → {"status":"ok","closed":true,...}
# SSRF 负例（open 即拒）
curl -s -X POST -H "X-Bridge-Token: test-token" -H 'Content-Type: application/json' \
  -d '{"url":"http://192.168.1.1/"}' http://127.0.0.1:8788/session
# → {"status":"action_failed","errorCode":"blocked_url",...}
kill %1
```

## 部署

- `./deploy.sh`（rsync → npm install --omit=dev → systemctl restart → healthz）。
- **防火墙**：token 仅为传输层防线，生产必须配合 ufw / tailscale 限制 8788 仅内网可达；
  `BRIDGE_BIND` 生产设为 tailscale 网卡 IP（缺省 0.0.0.0 仅开发用）。

## 测试

- `npm test`（单测，fake browser provider）；真实浏览器冒烟见上方 curl 段。
