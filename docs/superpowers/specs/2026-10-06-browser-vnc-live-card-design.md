# Browser-VNC 云端浏览器直播卡 · 设计稿

> **日期**：2026-10-06
> **状态**：已定稿待实施
> **范围**：M1 = bridge + 服务端网关 + App 工具闭环 + INLINE 直播卡（Android 首发，iOS 走 ios-follow）
> **上位约束**：ADR-016（Chat parts 宪法）、`2026-09-28-chat-type-taxonomy-design.md`（type 分类法）、根 AGENTS.md 全局红线

## 1. 背景与目标

xuxing（北京，`VM-0-13`）已有 `browser-vnc-*` ×8 systemd 单元（headed Chrome 池，`/opt/browser-vnc`）。本设计让 App 端 Koog Agent 获得云端浏览器能力：Agent 执行需要浏览网页的任务时调用云端浏览器，**用户在 Chat 里看到「Agent 每操作一步、卡片画面更新一步」的直播卡**（Operator/Manus 风格 live view）。

执行面采用 Muse 同款路线：**bridge 驱动 headless Chromium、纯 CDP DOM 级驱动**（Playwright 单例 browser + 每会话临时 BrowserContext，借鉴 openmuse），AI 干活不渲染像素（省 Xvfb/桌面/VNC 编码），画面只在用户要看时按需生成（2026-10-06 设计修订；既有 VNC headed 池保留作人工调试与反爬回退）。

已确认的四个选型（2026-10-06 brainstorming）：

| 决策点 | 结论 |
|--------|------|
| 核心场景 | Agent 云端浏览器直播（非用户手动远控、非纯无 UI 工具） |
| 画面形态 | 按需抓帧回传原生渲染（`wantFrame` 标志，非 noVNC/WebRTC/独立推流） |
| 会话驱动 | App Agent 驱动（Koog 工具闭环经 picme-server 转发 xuxing） |
| 平台范围 | Android 先行，iOS 走 ios-follow 管线对等 |

## 2. 整体架构

```
┌─ App (Android 首发) ──────────────────────────────┐
│ Koog Agent                                         │
│  └─ BrowserToolService（新，仿 ChatToolService）     │
│       └─ @Tool: browser_open / navigate / click /  │
│                type / extract / screenshot / close │
│  └─ BrowserSessionCapability（新，进 CapabilityRegistry）│
│       └─ BrowserSessionClient（HTTP，commonMain）     │
└──────────────┬─────────────────────────────────────┘
               │ HTTPS + X-App-Token（现有认证）
┌──────────────▼────────── picme-server (HK) ─────────┐
│ BrowserRoute（新）：POST /v1/browser/{action}        │
│  └─ BrowserPoolProxy：鉴权 → 配额检查 → 转发 → 回传   │
└──────────────┬─────────────────────────────────────┘
               │ 内网（tailscale；无则 HK→xuxing 直连 + IP 白名单）
┌──────────────▼──── xuxing (北京) ───────────────────┐
│ browser-agent-bridge（新增组件，单进程）              │
│  ├─ 会话管理：单例 headless Chromium + 每会话临时      │
│  │   BrowserContext（fresh storage，结束销毁；          │
│  │   不借用 VNC 池单元，无 Xvfb/桌面/VNC 编码开销）    │
│  ├─ CDP 执行：navigate/click/type/extract            │
│  │   （DOM 级读写，不渲染像素）                       │
│  └─ 按需抓帧：仅当请求带 wantFrame=true 时            │
│      page.screenshot → JPEG（720p 质量 60）           │
│ （既有 browser-vnc ×8 池保持原样：人工调试通道 +       │
│  反爬顽固站点回退用 headed 实例）                     │
└──────────────────────────────────────────────────────┘
```

### 2.1 组件边界

| 组件 | 位置 | 职责 | 依赖 |
|------|------|------|------|
| `BrowserToolService` | shared commonMain | Koog @Tool 表面，7 个原子工具，薄封装派发（仿 `ChatToolService`：@Tool 为 dispatchCommand 薄封装、参数不用 Kotlin 默认值、suspend） | CapabilityRegistry |
| `BrowserSessionCapability` | shared commonMain | 命令 → HTTP 调用，结构化错误映射 | BrowserSessionClient |
| `BrowserSessionClient` + 协议模型 | shared commonMain | 请求/响应 DTO（kotlinx.serialization），纯逻辑可测；HttpClient 经 `KoogHttpClientFactoryProvider` 分流 | expect HttpClient |
| `BrowserRoute` + `BrowserPoolProxy` | server/ | 鉴权（X-App-Token）、配额、转发、30s 网关超时 | AppConfig 新增 xuxing 地址 + X-Bridge-Token |
| `browser-agent-bridge` | `infra/browser-bridge/` 入仓，部署 xuxing | Playwright 动作执行 + 按需抓帧 + 会话生命周期（单例 headless Chromium + 每会话临时 BrowserContext） | Playwright / CDP |
| Chat 直播卡渲染 | androidApp | 新 part `tool_browser` 的 UI | ADR-016 状态机 |

### 2.2 关键架构决策

1. **工具粒度 = 7 个原子动作**（open/navigate/click/type/extract/screenshot/close）而非单个宏工具——LLM 自己规划步骤，每一步都有画面更新（方案 A 直播感来源），与现有工具风格一致。
2. **bridge 用 Playwright 驱动 headless Chromium，单例 browser 进程 + 每会话临时 BrowserContext**——AI 干活不需要像素管线：省掉 Xvfb、桌面、VNC 编码整套开销。借鉴 openmuse 验证过的形态（见决策 7）：单例 headless browser 常驻、每开一个会话建一个临时 BrowserContext（fresh storage = fresh profile 语义，关会话即毁，比每会话一个 browser 进程轻得多），`npx playwright install chromium` 安装浏览器。既有 `browser-vnc` ×8 headed 池**不借用、保持原样**，定位转为：人工调试通道 + 反爬顽固站点的回退（headless 被识别时由 bridge 切到 headed 实例执行，M1 只做手动开关，自动检测回退属 M2+）。
3. **画面按需生成（`wantFrame` 请求标志 + watch 模式）**——帧不是每个动作的固定产物，两级按需：
   - **动作级（默认）**：App 侧策略决定何时要帧（M1 默认 = 直播卡可见期间的改状态动作；extract/close 不带帧）。LLM 不参与该决策，响应中帧字段可选。
   - **watch 模式（用户正盯着看）**：App 在卡片可见时低频轮询 `GET /v1/browser/frame?sessionId`（~1-2fps），bridge 每次轮询做一次性 `page.screenshot` 抓帧；停止轮询超时即不再截图。一次性截图在 1-2fps 轮询下与 CDP `Page.startScreencast` 体验等价、无 screencast ack 状态机（openmuse 的 live console 同为截图轮询，佐证此口径）；若后续要 >5fps 再升级 screencast。仍是纯 HTTP 请求/响应，不新增长连接。用户不看时零截图开销零流量。
4. **协议模型放 shared commonMain**——端云 DTO 同源（Monorepo 既定决策），iOS 跟随期零成本复用。
5. **bridge 代码入仓 `infra/browser-bridge/`**——与 `infra/cloudflare/`、`infra/tencentscf/` 同级，systemd unit + 部署脚本随仓。
6. **画面复用工具结果通道，零新增长连接**——不引入 WS/SSE 新通道；若后续实测过程感不足，动作通道不变、只加推流通道即平滑升级为推流方案，无返工。
7. **借鉴 openmuse（CopilotKit/openmuse，MIT）的已验证工程形态**——2026-10-06 调研其 `apps/worker`（Playwright 浏览器子代理）与 live console 后落位：
   - **借鉴**：Playwright 单例 browser + 每会话临时 BrowserContext（决策 2）；SSRF 校验升级到其 `network.ts` 级别（仅 80/443 端口、禁 userinfo、禁 `.localhost/.local/.internal/.home/.lan` 后缀、IPv4 增补 CGNAT 100.64/10 与文档/保留段、IPv6 仅 2000::/3 许可名单、DNS 5s 超时）；导航后重定向落地复查（goto 后 re-validate `page.url()`）；子请求级 route 拦截 + WebSocket 全禁 + `--disable-quic` + WebRTC 防护；per-session 串行动作队列（注意 act 内抓帧须走无锁内部版，公开入口才过串行包装，否则自死锁）；extract 返回正文 + 交互元素清单 `{index,tag,text,href,type}`，click/type 三模式定位（index > targetText > selector——纯文本 LLM 猜不出盲 selector）；服务加固（token 时序安全比较、request/headers 超时、`no-store`/`nosniff`、body 64KB 上限）。
   - **不借鉴**：持久 profile/storageState（与本设计 fresh-context 相悖）、PDF 下载、egress 代理（列为可选加固项）、Docker 部署（沿用 systemd）、坐标输入/人工接管（M2 范畴）。

## 3. 数据流

以「帮我查 XX 手机最新价格」为例：

```
1. 用户消息 → Koog Agent 规划 → 决定调 browser_open(url)
2. @Tool browser_open → BrowserSessionCapability → POST /v1/browser/open（wantFrame=true）
   → bridge 建临时 BrowserContext（fresh storage）→ 导航 → 按需抓帧
   → 返回 { sessionId, status, currentUrl, pageTitle, frameJpegBase64?, actionMs }
3. 工具结果分流：llmPayload（文本摘要）回灌 LLM；
   uiPayload（帧 + 元数据）走占位 part 原位填充（M2 已有机制，draw_chart/render_html 同款）
4. Agent 继续调 browser_click / extract / …（改状态动作带 wantFrame，画面逐步更新；
   extract 等纯读取动作不带帧）
5. browser_close 或会话超时 → bridge 销毁实例与 profile，卡片定格最终帧 + 结果摘要
```

### 3.1 响应双载荷（token 保护）

工具响应分两段：**`llmPayload`**（URL/标题/页面文本摘要 + extract 的可交互元素清单（index/tag/text/href），回灌 LLM——元素清单让后续 click/type 用 index 回指，不用猜盲 selector）与 **`uiPayload`**（帧 + 元数据，只走 UI part）。帧**不进 LLM 上下文**——遵 ADR-016「data part 默认不回灌」，避免一次浏览任务烧掉几十万 token。

### 3.2 流量预算

720p 质量 60 JPEG ≈ 40-80KB/帧；一次任务按 10 个改状态动作计 ≈ 0.5-1MB。用户已确认可接受。帧按 `wantFrame` 生成，App 退后台或卡片不可见期间零帧零流量。watch 模式期间轮询 ~1-2fps ≈ 80-160KB/s，仅存在于用户实际观看时。

### 3.3 隔离与扩容模型

M1 采用**共享主机 + Chromium 自身沙箱 + fresh profile 即毁**：任务面只有「浏览网页 + 读 DOM」，无任意代码执行、无登录态，威胁面可控；per-user 并发=1 天然限制单用户资源占用。Muse 式「一人一 Secure VM（systemd-nspawn 容器）」是面向海量用户的隔离/扩容模型，列为 M2+ 扩容方向（届时横向扩容 = 加机器，bridge 无状态可圆移植）。

## 4. Chat 直播卡（新 part `tool_browser`）

type 分类法 spec（`2026-09-28-chat-type-taxonomy-design.md`）tool 类扩 1 值：8 值 → 9 值。

| PartState | 卡片表现 | 持久化 |
|-----------|----------|--------|
| `INPUT_STREAMING` / `RUNNING` | 占位卡：浏览器图标 + 当前动作描述（「正在打开 example.com…」）+ 最新帧（如有） | 不落库（瞬态，同 M2 占位 parts 口径） |
| `OUTPUT_AVAILABLE` | 直播卡：最新帧大图 + 页面标题/URL + **最近 3 步动作流水**（如 点击"价格" → 输入"iPhone 17" → 提取结果） | 会话结束落：最终帧缩略图 + 结果摘要 + 动作计数 |
| `OUTPUT_ERROR` | 错误卡：失败原因 + 已完成步骤数 | 落库（供重试/排查） |

- M1 只有 INLINE 形态；点按帧全屏查看复用现有全屏查看器模式。不做接管输入（方案 A 决策，接管属 M2+ 候选）。
- 卡片可见即触发 watch 模式（~1-2fps 轮询最新帧，页面加载动画等过程可见）；不可见/退后台即停轮询，bridge 侧轮询停止后即不再截图（一次性 `page.screenshot`，无 screencast 状态机）。
- 流式走 M2 既有管线（`TurnStreamEvent` 工具五事件 + `TurnPartsReducer` 占位原位填充），browser 工具只是新增一个类型化占位，**不动管线主干**。
- 卡片渲染须走 ui-parity-guard 闭环（spec → token → 截图），iOS 跟随期由 ios-follow 管线对等。

## 5. 会话生命周期（bridge 侧状态机）

```
Idle → Allocated(open) → Active(navigate/click/...) → Closing(close/超时) → Destroyed
              │
              └─ 池满 → PoolExhausted（结构化错误，Agent 降级纯文本回答）
```

- 空闲 2 分钟无动作 → 自动 Closing；硬上限 10 分钟强制回收。
- App 进程死亡/断网：服务端超时兜底回收，不依赖客户端 close。

## 6. 错误处理（分层，每层结构化可枚举）

| 层 | 故障 | 表现 |
|----|------|------|
| bridge/CDP | 页面加载超时（15s）、元素未找到、Chrome 崩溃 | 工具返回 `{status:"action_failed", reason, lastGoodFrame}`——带最后一张好帧，卡片定格并标注失败步骤，Agent 可重试或换策略 |
| bridge 会话 | 池满、会话已销毁 | 结构化错误码 `pool_exhausted` / `session_expired`，Agent 降级纯文本回答并告知「浏览器资源忙，稍后再试」 |
| picme-server | xuxing 不可达 | 网关 30s 超时 → 统一错误码 `browser_unavailable`，同上降级；**不计用户额度** |
| App | HTTP 失败/断网 | 复用现有远程推理失败处理（工具异常 → Koog 捕获 → Agent 致歉降级） |

原则：**browser 能力整体可降级**——任何一环失败，体验退化为「没有直播卡的普通文本回答」，绝不白屏/卡死。

## 7. 配额

headless 实例轻量（无 Xvfb/桌面/VNC 编码），但机器资源仍有限：

- per-user 并发 = 1（picme-server 内存态登记，重启即清，不落库）。
- bridge 侧全局并发上限默认 8 个活跃会话（env 可配，与 VNC 池解耦——headless 会话不占用 VNC 单元），满则 `pool_exhausted`。
- 单用户每日 20 会话（存 `server_setting` 可配，管理后台可改），超额返回 `quota_exceeded`。
- 与 LLM token 额度体系解耦（browser 成本是机器资源）；管理后台「概览」加 browser 会话统计（当日数/活跃数/失败率）。

## 8. 隐私与安全

- [PRIVACY] 红线不触碰：帧是网页内容截图，非用户相册媒体；**卡片帧不进遥测、不进问题上报附件**。
- M1 全部 fresh profile：无登录态、不持久 cookie、会话销毁即清空。登录态会话是 M2+ 的显式决策，本期不做。
- bridge 只监听内网网卡（tailscale IP），不暴露公网；picme-server → bridge 带共享密钥 `X-Bridge-Token`（server.env / bridge env 双端配置）。
- 目标 URL 校验（SSRF 防护，openmuse `network.ts` 级）：仅允许 80/443 端口的 http/https、禁 userinfo、禁 `.localhost/.local/.internal/.home/.lan` 后缀；DNS 解析（5s 超时）后走单播许可名单——IPv4 拒绝私网/回环/链路本地/CGNAT 100.64/10/文档与保留段，IPv6 仅放行 2000::/3 全球单播；导航后重定向落地复查（re-validate `page.url()`）+ 子请求级 route 拦截（WebSocket 全禁、`--disable-quic`、WebRTC 防护），防 SSRF 借浏览器打内网。

## 9. 测试策略

| 层 | 测试 | 位置 |
|----|------|------|
| 协议模型 | DTO 序列化 round-trip、错误码枚举穷尽 | shared commonTest |
| Capability | mock HttpClient 验证请求组装 + 错误映射 | shared commonTest |
| 服务端 | BrowserRoute 鉴权/配额/超时（Ktor test，bridge 用 fake） | server/src/test |
| bridge | CDP 动作执行 + 抓帧（真实 headless Chrome 集成测试，手跑） | infra/browser-bridge/tests |
| 卡片 UI | part 状态机 reducer 用例（占位→填充→定格） | shared commonTest + androidApp |
| 端到端 | 真机闭环：chat 发指令 → 直播卡逐步更新 → 结果落库 | dev-loop 脚本 |

验证门槛沿用现有口径：`JITPACK=true ./gradlew :shared:assemble` + `:shared:jvmTest`；服务端 `./gradlew -p server build`；Android 端走 android-build-debug/dev-loop 闭环。

## 10. 分期

- **M1（本 spec 全部内容）**：bridge + 服务端网关 + App 工具闭环 + INLINE 直播卡。
- **M2 候选（不在本 spec）**：全屏查看器增强、登录态会话、headless 被反爬识别时自动回退 headed 池（M1 为手动开关）、动作密度不足时升级独立推流通道、Muse 式一人一 VM 隔离扩容（systemd-nspawn 容器 + 加机器横向扩）、iOS 跟随。

## 11. 交付审计对应

- [ ] 新代码遵循 Agent First 原则（显式注入 / 枚举状态 / DTO 自描述 / 结构化错误码）
- [ ] type 分类法 spec 同步扩值（8→9）
- [ ] `server/AGENTS.md` 路由清单 + `shared/AGENTS.md` 组件表同步
- [ ] 满足 [PRIVACY]（帧不出遥测/上报）、[I18N]（卡片文案五语）、[PARITY]（卡片固化 spec 供 iOS 跟随）红线
- [ ] 闭环验证（编译/真机 dev-loop）通过
