# browser-agent-bridge

xuxing 侧云端浏览器桥：单例 headless Chromium + 每会话临时 BrowserContext（Playwright），
纯 CDP DOM 级驱动，为 picme-server `/v1/browser/*` 提供会话执行、按需抓帧与 WS screencast
推流（M2，2026-10-09）。
设计 SSOT：`docs/superpowers/specs/2026-10-06-browser-vnc-live-card-design.md`。
安全边界借鉴 [CopilotKit/openmuse](https://github.com/CopilotKit/openmuse)（MIT）`apps/worker`：
SSRF 全球单播许可名单（仅 80/443）+ 重定向落地复查 + 子请求级拦截 + WebSocket 全禁 +
token 时序安全比较 + worker 固定评估代码（不接受任意 JS 注入）。

## 端点契约

- HTTP 端点：`POST /session`（open）、`POST /session/{id}/action`、`GET /session/{id}/frame`、
  `POST /session/{id}/close`、`GET /healthz`；全部要求 `X-Bridge-Token` 头。
- WS 端点（M2 推流通道）：`GET /ws?sessionId=..&token=..`——token 与 HTTP `X-Bridge-Token`
  同源（SHA-256 时序安全比较，不符 401 断连）；连接即启动 CDP `Page.startScreencast`
  （幂等；jpeg quality 60 / maxWidth·maxHeight 1280·720 / everyNthFrame 2，config.js
  `screencast*` 四键可调）；客户端发 `{"type":"watch_stop"}` 或最后一个听众断开时自动停
  screencast（无消费者不空抓）。Server→Client：帧 = binary JPEG 字节 + text
  `{"type":"frame_meta",...}`、动作结果 = text `{"type":"action_result",...}`、异常 = text
  `{"type":"error",...}`（code：bad_request/session_expired/screencast_failed）；
  Client→Server：`{"type":"action","actionId","action":{...}}`（action 体与 HTTP
  `/session/{id}/action` 同构，参数平铺顶层）或 `{"type":"watch_stop"}`。
- 域名结果一律 HTTP 200 + JSON `status`（ok/action_failed/pool_exhausted/session_expired）。
- 请求体超限（>64KB）时 `req.destroy()` 先于响应发出，客户端可能观察到连接重置而非 413——
  应将 body 发送过程中的 reset 视为 413 等价。
- 动作清单：navigate/click/type/extract/screenshot（HTTP 契约）+ clickAt/typeText/scroll/drag
  （M2 接管动作，仅 WS 通道使用，坐标为 CSS 页面坐标：clickAt 鼠标点 (x,y)、typeText 全局
  键盘输入 text、scroll 滚轮增量 (dx,dy)、drag (fromX,fromY)→(toX,toY) 连续拖拽）。
- click/type 定位三模式：index（extract 返回的元素序号，LLM 首选）/ targetText / selector。

## 已知接受风险（M1，2026-10-06 审查记录）

- **DNS rebinding TOCTOU**：SSRF 校验（DNS 解析 + 单播许可名单）与真实连接之间存在解析竞态——
  校验后攻击者改 DNS 应答可绕过复查。M1 接受；缓解：单播许可名单已收窄目标面 +
  bridge 仅内网部署（token + ufw/tailscale 网卡绑定），M2+ 可评连接期 IP 钉扎。
- **sessionId 即能力凭证**：无 owner 绑定校验——同进程内持有 sessionId 即可操作会话
  （网关侧 per-user 并发=1 + App token 鉴权是唯一边界）。M1 接受（单用户串行语义下
  他方拿不到 sessionId）；M2+ 多并发或跨用户面扩大时需补 owner 绑定。
- **413 表现为连接重置**：机制与客户端处理口径见上方「端点契约」节（发送期 reset 按 413 等价）。

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
