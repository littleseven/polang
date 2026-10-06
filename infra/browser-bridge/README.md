# browser-agent-bridge

xuxing 侧云端浏览器桥：单例 headless Chromium + 每会话临时 BrowserContext（Playwright），
纯 CDP DOM 级驱动，为 picme-server `/v1/browser/*` 提供会话执行与按需抓帧。
设计 SSOT：`docs/superpowers/specs/2026-10-06-browser-vnc-live-card-design.md`。
安全边界借鉴 [CopilotKit/openmuse](https://github.com/CopilotKit/openmuse)（MIT）`apps/worker`：
SSRF 全球单播许可名单（仅 80/443）+ 重定向落地复查 + 子请求级拦截 + WebSocket 全禁 +
token 时序安全比较 + worker 固定评估代码（不接受任意 JS 注入）。

- 端点：`POST /session`（open）、`POST /session/{id}/action`、`GET /session/{id}/frame`、
  `POST /session/{id}/close`、`GET /healthz`；全部要求 `X-Bridge-Token` 头。
- 域名结果一律 HTTP 200 + JSON `status`（ok/action_failed/pool_exhausted/session_expired）。
- click/type 定位三模式：index（extract 返回的元素序号，LLM 首选）/ targetText / selector。
- 部署：`./deploy.sh`（rsync → npm install --omit=dev → systemctl restart → healthz）。
- 测试：`npm test`（单测）；冒烟走 README curl 段（本文件上方示例）。
