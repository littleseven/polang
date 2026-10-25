# :server 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `server/` 模块的实现细节。
> - 产品目标与验收口径以 `PRODUCT.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 部署与运维细节见 `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md`。
> - API 契约与实现方案见 `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`。

**模块定位**：`server/` 是 PoLang 的 **Ktor 后端单体服务**，独立 Gradle 工程，与 Android 客户端通过 Monorepo 管理。提供 AI 网关、账号体系、管理后台、推荐引擎、遥测收集、COS 对象存储等能力。

**主要维护者**：项目开发者

**阅读对象**：RD、CR、AI Agent

**版本**：0.9.6

**最后更新**：2026-10-25

**状态**：生效中 / 已上线

---

## 1. 模块概述

`server/` 是独立的 Ktor Gradle 工程（`rootProject.name = "picme-server"`），**不纳入 Android `settings.gradle.kts`**。通过 `./gradlew -p server` 独立构建和运行。

**核心职责**：
- **AI 网关**：`LlmProxy` + `ChannelRegistry` — 按模型自动路由到 Cloudflare AI Gateway 或腾讯 TokenHub；`stream=true` 请求 SSE 逐 chunk 透传（原样转发 stream/stream_options），usage 从流尾帧解析计费（含 `prompt_tokens_details.cached_tokens` 前缀缓存命中采集，`llm_call_log.cached_tokens`，2026-10-17）；非流式请求强制 `stream=false` 走完整响应体解析
- **账号体系**：`EmailService` + `AccountService` + `AppTokenAuth` — 邮箱注册、动态 Token、SHA-256 校验
- **管理后台**：`AdminRoutes` + `AdminViews` + `AdminQueries` — SSR HTML 运营后台（概览/用户/流量）
- **推荐引擎**：`RuleEngine` — 纯规则型场景推荐（规避算法备案）
- **遥测收集**：`TelemetryRoute` — 批量匿名事件写入 SQLite
- **COS 存储**：`CosService` — 腾讯 COS 上传/元数据/预签名 URL；APK 下载 URL 渠道路由（`apkPublicUrl(channel)`：debug 轨可经 `APK_DEBUG_PUBLIC_BASE` 指向北京轻量国内镜像，release 轨恒走 `cos.polang.net`）
- **限流**：`RateLimiter` — per-IP 令牌桶 + 日预算熔断
- **云端浏览器网关**：`BrowserRoute` + `browser/`（BrowserBridgeClient/BrowserConcurrencyRegistry/BrowserSessionStats）— `/v1/browser/*` 鉴权（X-App-Token）→ 每日配额 peek（open 成功才 allow 计费）→ per-user 并发=1 → 透传 xuxing browser-agent-bridge（30s 网关超时；bridge 不可达统一 503 `browser_unavailable` 且不计额度，域名结果 JSON `status` 原样透传）；M2（2026-10-09）增 `WS /v1/browser/ws` 帧流双向代理（App↔bridge 文本/二进制帧透传，鉴权/owner 校验同 HTTP 路由，失败以 WS close code 表达）；M2.5（2026-10-11）busy 自愈——open 撞占用时经 bridge `GET /session/{id}/status` 探测，仅 `session_expired` 判死后条件释放重试（`reaped_heal`），探测失败/其它状态保守 BUSY；M2.7（2026-10-11）WS 链路修复——代理应答 bridge Ping（回 Pong，OkHttp pingInterval 无 pong 判死）+ bridge→app 帧字节闸门（90KB/s 令牌窗口、窗口首帧无条件放行防全黑、容量 1 DROP_OLDEST 不排队，防 HK↔国内窄带积压堵死 pong/文本帧）
- **云端 Agent 会话域**：`agent/` 包（ADR-017/018，Koog JVM + 事件溯源 `agent_session`/`agent_event` 两表，`/agent/**` REST + SSE；BYOK X-User-Api-Key 请求级透传零留存）——M2（2026-10-17）工具桥双通道：SSE `tool_request` 帧下行 + `POST /agent/sessions/{id}/tool-results` 上行（`AgentBridgeRegistry` 挂起表 + `DeviceToolService` 端侧只读三件套适配，409 `tool_request_not_pending`）；M2.6（2026-10-17）per-user 跑轮并发=1（`AgentRoundGate` 内存租约，占用回 409 `round_in_flight`，占位在配额扣减之前）；M1（2026-10-21，混合架构投影通路）端侧结果出端过 shared CloudToolProjectionGate（fail-closed），LLM 可见描述只承诺投影（计数/摘要，不含路径/媒体清单）；render_html H5 卡通道（2026-10-22）：服务端工具 `render_html(html, summary)`，LLM 只得 summary 回执（HTML 不入上下文），HTML 本体经第六型事件载荷 `AgentEventPayload.HtmlCard`（wire `html_card`/`environment`/`tool`）落 `agent_event` 随 SSE `kind=event` 下行，128_000 字符上限超限回 `error: html_too_large`，pending 重放经 `ServerToolDispatcher.withHtmlCardSink` 重出卡；render_ui A2UI 卡通道校验闭环（2026-10-24，sf-012a0/sf-a2u1c0de 事故修复）：`RenderUiValidator` 发射点门控（`AgentRunner.actionGate` 挂在 onToolCallStarting——Action 先于工具体落流，工具体内校验拦不住坏负载到端，故校验必须在发射点；非法负载不落 Action、不 SSE 下行、不进 pending 重放）+ 工具体同源校验回 `error: …` 逐条违规清单（Observation 回灌，LLM 同轮修正重试）；工具描述内嵌黄金示例 few-shot（`RENDER_UI_EXAMPLE` 纯 JSON 常量，测试钉住示例自身过校验、与校验器零脱节）；组件白名单 6→17（同日真机穷举后扩齐：catalog 19 扣 Video/AudioPlayer 空盒占位未实装；Image.url 限 https:// 公网地址 [PRIVACY]，Icon.name 限 59 枚枚举禁 svgPath）；假发卡守卫 `CardClaimGuard`（同日：LLM 不调卡工具就声称「卡片已下发」→ cloudAgentStrategy correctFakeCardClaim 节点追加 user 纠正消息就地重发 LLM 请求，上限 1 次）；GenUI 线格式分流（2026-10-24，openui-genui 并行接入）：请求头 `X-GenUI-Format: openui` 时 `render_ui` 工具互斥换注册 `render_ui_openui`（`RenderOpenUiToolService`，OpenUI Lang 1.0 程序整帧下发，同一回合只产一种 GenUI 卡），openui 线同口径过 `RenderOpenUiValidator` 结构级门控 + 错误回灌（gate 按 toolName 分发）；OpenUI 线组件 6→7（+Image 相册照片图文混排，photo 仅许句柄投影 id（`local://scan/<id>#photos` → [{id,date}]），2026-10-24）；OpenUI 组件库 M2（2026-10-24，spec《openui-component-library-m2-design》）：`RenderOpenUiValidator` 白名单 7→14 组件（+Icon/Divider/List/Tabs/CheckBox/ChoicePicker/Slider——spec 标题「十三」系计数笔误，以 §2 枚举为准）+ Icon name 59 枚白名单正则级校验（`ICON_NAMES`，与 `RenderUiValidator.ICON_NAMES` 副本源 ↔ shared `OpenUiLibrary.iconNames` ↔ 工具描述 `ICON_NAMES_TEXT` 四处字面量副本，测试钉住同源）+ `RenderOpenUiToolService` 描述加 7 组件签名/state 写入约定段（`$变量` 首行声明、交互组件绑定写法、裸字面量只读）/交互黄金示例 `GOLDEN_INTERACTIVE_PROGRAM`（companion const 测试同源钉住）；OpenUI 组件库 M3（2026-10-24，spec《openui-component-library-m3-design》）：白名单 14→21 组件（+Table/PieChart/LineChart/Callout/Markdown/Accordion/Switch，无新正则——本批无 Icon 名字面量类新攻击面）+ 描述加 7 组件签名/规则 6 交互组件清单补 Switch/图文报表黄金示例 `GOLDEN_REPORT_PROGRAM`（Table+PieChart+Callout 组合，companion const 测试同源钉住）；OpenUI 卡内设计语言第一刀（2026-10-25，`docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md`）：Text role 增 DISPLAY（stat tile 32sp 大数字，shared `OpenUiLibrary.textRoles` 同源）+ Icon 省略 colorRole 自动弱色引导 + BarChart >12 桶先聚合引导 + stat tile 黄金示例 `GOLDEN_STAT_TILE_PROGRAM`（companion const 测试同源钉住）；构图软警告 lint（2026-10-25）：`RenderOpenUiValidator.lintComposition`（DISPLAY >4 / Button >3 正则级计数，不拒卡）附在工具体「UI 已下发」回执后供 LLM 下次发卡改进（明示无需重发防双卡），gate 发射点只走硬校验不受影响（测试钉住）；Image 真实比例+点击大图（2026-10-25）：工具描述 aspect 改「省略时端侧按 MediaStore 元数据真实比例渲染（竖屏图不再被横框拦腰，推荐省略），强制造型才显式传」，端侧 resolver 回填 aspectRatio（API<29 BitmapFactory 边界解码兜底；元数据宽 sanity 界 [0.3, 3.0] 不套 LLM 窄 clamp）、点击经宿主动作 OpenPhoto 落 PhotoEditor；OpenUI 官方组件库全量补全（2026-10-25，spec《openui-full-library-design》）：白名单 21→89（官方 82 适配 81 + 本仓既有 8，官方 Col 列式刻意不收）+ Image src https 硬校验/photo·src 二缺一（src 限任意组件 `src=`/`{src:}` 形态同规）+ 工具描述全量 89 签名（表单 name 键控 @Set($name) 直写约定段、元素形状组件不独立使用注明）+ 白名单 89 项字面量测试同源钉住
- **用户问题上报**：`IssueReportRoute` — 脱敏后入库并自动同步 GitHub issue

---

## 2. 工程结构

```
server/
├── build.gradle.kts              # Kotlin 2.3.10（随 Koog 1.3.0 元数据升级）+ Ktor 3.0.3 + Exposed 0.55.0 + SQLite
├── settings.gradle.kts           # rootProject.name = "picme-server"
├── src/main/kotlin/com/mamba/picme/server/
│   ├── Application.kt            # Ktor 入口：插件装配（Routing/ContentNegotiation/Auth/StatusPages/CallLogging）
│   ├── config/
│   │   ├── AppConfig.kt          # 环境变量读取（非 HOCON，无 application.conf）
│   │   └── SettingsService.kt    # server_setting 内存快照（额度默认值/白名单，热路径零 DB 读）
│   ├── routes/
│   │   ├── HealthzRoute.kt       # GET /healthz — 存活探活
│   │   ├── RecommendRoute.kt     # POST /recommend — 场景推荐
│   │   ├── TelemetryRoute.kt     # POST /telemetry — 遥测收集
│   │   ├── AuthRoute.kt          # POST /auth/email/{send,verify}、GET /auth/quota、DELETE /auth/account、DELETE /guest/device
│   │   ├── ClaudeChatRoute.kt    # POST /v1/claude-chat、/v1/claude-deliver、GET /v1/claude-engineer/available
│   │   ├── ClaudeToolResultRoute.kt # POST /v1/claude-tool-result — App tool 结果回传
│   │   ├── IssueReportRoute.kt   # POST /v1/report-issue — 用户问题上报
│   │   ├── BrowserRoute.kt       # /v1/browser/{open,action,close} + GET /v1/browser/frame + WS /v1/browser/ws — 云端浏览器网关
│   │   └── DownloadRoute.kt      # GET /download — 资源下载 + iOS 安装（/download/ios、manifest.plist、udid 注册）
│   ├── browser/
│   │   ├── BrowserBridgeClient.kt    # xuxing bridge HTTP/WS 客户端（X-Bridge-Token，available 探测 + wsUrl 拼 WS 地址）
│   │   ├── BrowserConcurrencyRegistry.kt # per-user 并发=1 内存租约（默认 15min，不变式：> bridge hardCap 10min 防活跃会话被 sweep 双活；重启即清，条件释放防误删新租约）
│   │   └── BrowserSessionStats.kt    # 会话统计（管理后台概览；写故障隔离不破坏响应契约）
│   ├── auth/
│   │   ├── AccountService.kt     # 账号 CRUD + token 生成/校验
│   │   ├── AppTokenAuth.kt       # X-App-Token 认证插件
│   │   ├── EmailService.kt       # 验证码发送（Resend API，按 lang 渲染五语模板，缺省中英双语）
│   │   ├── EmailLanguage.kt      # 邮件语言枚举（EN/zh-CN/zh-TW/ES/FR + 双语缺省）+ BCP-47 标签归一化
│   │   ├── GuestService.kt       # 访客设备（anonymous_device）额度管理
│   │   └── AiEngineerWhitelistService.kt # AI 工程师交付白名单
│   ├── admin/
│   │   ├── AdminRoutes.kt        # /admin/** 路由注册（含 /admin/diagnosis 问题诊断页）
│   │   ├── AdminAuth.kt          # ADMIN_TOKEN + cookie 认证
│   │   ├── AdminViews.kt         # kotlinx.html SSR 页面
│   │   └── AdminQueries.kt       # 运营数据查询
│   ├── issue/
│   │   ├── IssueReportService.kt # 问题上报存库 + GitHub 同步
│   │   ├── IssueSanitizer.kt     # 隐私脱敏
│   │   └── GitHubIssueClient.kt  # GitHub REST API 客户端
│   ├── llm/
│   │   ├── ChannelConfig.kt      # 供应商配置（baseUrl/apiKey/model/routing）
│   │   ├── ChannelRegistry.kt    # 多供应商注册表
│   │   ├── ChannelRepository.kt  # 配置持久化/加载
│   │   ├── ChannelBalanceService.kt # 渠道上游余额查询/缓存
│   │   ├── LlmProxy.kt           # 流式代理 + 上游路由
│   │   └── LlmRoute.kt           # /v1/chat/completions 端点
│   ├── agent/                    # 云端 Agent 会话域（ADR-017/018，Koog JVM + 事件溯源）
│   │   ├── AgentEvent.kt         # 事件模型：六型载荷（2026-10-22 +HtmlCard，wire html_card）+ source/role/parentId 三维 + payload 编解码
│   │   ├── AgentTables.kt        # agent_session / agent_event 表（事件只追加，id 即事件序）
│   │   ├── AgentSessionRepository.kt # 会话生命周期状态机（running/…/finished/error）
│   │   ├── AgentEventRepository.kt   # 追加/回放/pending action 不变式查询
│   │   ├── AgentProjection.kt    # 事件流 → LLM 视图投影（Condenser 最小实现，策略可替换）
│   │   ├── AgentExecutorFactory.kt # Koog 执行器组装（OpenAI 兼容；CF_AIG 改写 cf-aig-authorization）
│   │   ├── AgentLlmTarget.kt     # 运行目标解析（网关渠道 modelMap 映射 / BYOK 透传）
│   │   ├── ServerTools.kt        # 服务端工具（server_time + render_html H5 卡通道，summary-only 回执，2026-10-22）+ pending 重放派器
│   │   ├── RenderUiToolService.kt # render_ui A2UI 发卡工具（CLOUD 侧空执行，负载经 action 事件下行）+ 黄金示例 few-shot
│   │   ├── RenderUiValidator.kt  # render_ui 负载校验器：发射点门控（AgentRunner.actionGate）+ 工具体错误回灌，同源规则零漂移；组件白名单 17 + Image https 限 + Icon 枚举
│   │   ├── CardClaimGuard.kt     # 假发卡守卫：终结消息声称发卡但当轮无卡工具 Action 过门控 → 策略层 correctFakeCardClaim 节点纠正（上限 1 次）
│   │   ├── AgentBridgeRegistry.kt # 工具桥挂起表（requestId→Deferred，断线 failAll 清理）
│   │   ├── AgentRoundGate.kt     # per-user 跑轮并发=1 内存租约（M2.6；故意无 TTL，一轮最长 20 迭代×(LLM+120s 工具)，重启即清）
│   │   ├── DeviceToolService.kt  # 端侧只读三件套 Koog 适配（tool_request 帧 + 120s 挂起；M1 起 @LLMDescription 只承诺投影面，对齐 shared CloudAgentToolCatalog.projectionSchema；search_media 增 display 参数（2026-10-24）：'true'=端侧透出横滑图集卡（用户明确想浏览照片时），取数/统计一律 'false'）
│   │   ├── CloudAgentStrategy.kt # 单跑 ReAct 策略（自 :shared poLangSingleRunStrategy 移植）
│   │   ├── AgentRunner.kt        # 一轮执行：投影→落流→pending 重放→Koog loop→Finish
│   │   └── AgentRoutes.kt        # /agent/** REST + SSE（BYOK X-User-Api-Key 零留存）
│   ├── recommend/RuleEngine.kt   # 规则查询 + 参数组装
│   ├── cos/CosService.kt         # COS 预签名 URL
│   ├── ratelimit/RateLimiter.kt  # 内存令牌桶 + 日预算
│   ├── analytics/
│   │   ├── UsageRecorder.kt      # LLM 调用日志记录
│   │   └── TokenUsage.kt         # Token 用量解析
│   └── db/
│       ├── Db.kt                 # HikariCP + Exposed 数据库连接
│       ├── Tables.kt             # Exposed Table 定义（含 ReportedIssues）
│       └── Migrations.kt         # Schema 创建 + seed 幂等加载
├── migrations/                   # 迁移文件以 server/migrations/ 目录为准
│   ├── 001_init.sql
│   ├── 002_llm_call_log.sql
│   ├── 003_llm_channel.sql
│   ├── 004_llm_channel_default_model.sql
│   ├── 005_account_token_plain.sql
│   ├── 006_account_soft_delete.sql
│   ├── 006_llm_call_log_platform.sql
│   ├── 007_server_setting.sql
│   ├── 008_llm_channel_balance.sql
│   ├── 009_drop_diag_jobs.sql
│   ├── 010_anonymous_device_platform.sql
│   ├── 011_browser_session.sql
│   ├── 012_agent_session.sql     # 云端 Agent 会话域（ADR-018）
│   ├── 013_llm_call_log_cached_tokens.sql  # prompt 前缀缓存命中列（2026-10-17）
│   └── seed_rules.sql            # 初始推荐规则
├── src/test/kotlin/              # 测试基建（下列为示意，实际 40 个 *Test.kt + 2 个辅助类）
│   ├── ChannelRepositoryTest.kt
│   ├── AdminRoutesTest.kt
│   └── TokenUsageTest.kt
├── .env.example                  # 环境变量模板
├── deploy.sh                     # 构建 + rsync 部署
├── deploy-switch.sh              # 服务器端蓝绿切换 + 自动回滚
├── run-local.sh                  # 本地开发便捷脚本
├── picme-api.service             # systemd unit
├── OPENCLAW_DEPLOY.md            # OpenClaw 一键发布指南
└── README.md                     # 模块级 README（精简，指向详细文档）
```

---

## 3. 路由清单

| 方法 | 路径 | 优先级 | 状态 | Auth | 说明 |
|------|------|--------|------|------|------|
| GET | `/healthz` | P0 | ✅ | 无 | 存活探活 |
| POST | `/recommend` | P0 | ✅ | X-App-Token | 场景标签 → 参数包 |
| POST | `/telemetry` | P0 | ✅ | X-App-Token | 批量匿名遥测 |
| POST | `/v1/chat/completions` | P0 | ✅ | X-App-Token | LLM 代理（流式 SSE） |
| POST | `/chat/completions` | P0 | ✅ | X-App-Token | 旧路径兼容 |
| POST | `/agent/sessions` | P1 | ✅ | X-App-Token | 云端 Agent 建会话（channel=gateway\|byok，ADR-018） |
| GET | `/agent/sessions` | P1 | ✅ | X-App-Token | 我的云端会话列表 |
| GET | `/agent/sessions/{id}/events` | P1 | ✅ | X-App-Token | 事件流分页回放（after/limit） |
| POST | `/agent/sessions/{id}/messages` | P1 | ✅ | X-App-Token | 跑一轮（SSE 事件帧；BYOK 经 X-User-Api-Key 透传，零留存；per-user 并发=1，占用回 409 `round_in_flight` 不耗配额） |
| POST | `/agent/sessions/{id}/tool-results` | P1 | ✅ | X-App-Token | 端侧工具结果上行（409 tool_request_not_pending；ADR-018 工具桥） |
| POST | `/agent/sessions/{id}/finish` | P1 | ✅ | X-App-Token | 关闭会话（终态） |
| POST | `/auth/email/send` | P0 | ✅ | 无 | 发送验证码 |
| POST | `/auth/email/verify` | P0 | ✅ | 无 | 校验码换 token |
| GET | `/admin/**` | P1 | ✅ | ADMIN_TOKEN | 管理后台 SSR |
| GET | `/admin/devices` | P1 | ✅ | ADMIN_TOKEN | 未注册设备列表（anonymous_device） |
| GET | `/admin/devices/{id}/raw` | P1 | ✅ | ADMIN_TOKEN | 设备 id 复制（返回完整 device_id） |
| POST | `/admin/devices/{id}/delete` | P1 | ✅ | ADMIN_TOKEN | 删除单条设备访客记录 |
| GET | `/admin/settings` | P1 | ✅ | ADMIN_TOKEN | 全局额度默认值（free/guest）表单 + AI 工程师白名单区块（`#whitelist`） |
| POST | `/admin/settings` | P1 | ✅ | ADMIN_TOKEN | 更新全局额度默认值 |
| POST | `/admin/settings/whitelist` | P1 | ✅ | ADMIN_TOKEN | 添加 AI 工程师白名单邮箱 |
| POST | `/admin/settings/whitelist/revoke` | P1 | ✅ | ADMIN_TOKEN | 移除 AI 工程师白名单邮箱 |
| GET | `/admin/diagnosis` | P1 | ✅ | ADMIN_TOKEN | 用户上报问题列表（状态流转 / 手动同步 GitHub） |
| POST | `/admin/users/{id}/reset-quota` | P1 | ✅ | ADMIN_TOKEN | 清零单账号已用额度（保留 llm_call_log 历史） |
| POST | `/admin/users/{id}/limit` | P1 | ✅ | ADMIN_TOKEN | 改单账号调用上限（0=禁用但保留 token） |
| POST | `/admin/devices/{id}/reset-quota` | P1 | ✅ | ADMIN_TOKEN | 清零访客设备已用额度 |
| POST | `/admin/channels/{id}/refresh-balance` | P1 | ✅ | ADMIN_TOKEN | 刷新渠道上游余额缓存（DeepSeek 等） |
| POST | `/v1/report-issue` | P1 | ✅ | X-App-Token | 用户问题上报（脱敏 + 自动 GitHub issue） |
| POST | `/v1/claude-chat` | P1 | ✅ | X-App-Token | AI 工程师模式 SSE 对话（只读诊断） |
| POST | `/v1/claude-tool-result` | P1 | ✅ | X-App-Token | AI 工程师 App tool 结果回传 |
| POST | `/v1/claude-deliver` | P1 | ✅ | X-App-Token + 白名单 | AI 工程师代码交付 |
| GET | `/v1/claude-engineer/available` | P1 | ✅ | X-App-Token | 返回 {available, canDeliver} |
| POST | `/v1/browser/open` | P1 | ✅ | X-App-Token | 云端浏览器开会话：每日配额 peek（成功才计费，超额 429 `quota_exceeded`）+ per-user 并发=1（占用回 `pool_exhausted`）→ 透传 bridge |
| POST | `/v1/browser/action` | P1 | ✅ | X-App-Token | 浏览器动作透传（navigate/click/type/extract/screenshot；域名结果 JSON `status` 原样回） |
| GET | `/v1/browser/frame` | P1 | ✅ | X-App-Token | watch 模式取帧（`?sessionId=`，bridge 一次性 page.screenshot） |
| POST | `/v1/browser/close` | P1 | ✅ | X-App-Token | 关会话 + 释放并发租约（条件释放防误删新租约） |
| WS | `/v1/browser/ws` | P1 | ✅ | X-App-Token | browser 帧流双向代理（M2，`?sessionId=`；owner 校验沿用 ownerTokenHash，无身份 close VIOLATED_POLICY / 缺 sessionId close CANNOT_ACCEPT / bridge 不可用 close SERVICE_RESTART；App↔bridge 文本/二进制帧透传） |
| GET | `/auth/quota` | P1 | ✅ | X-App-Token | 查询账号剩余额度 |
| DELETE | `/auth/account` | P1 | ✅ | X-App-Token | 注销账号（软删除） |
| DELETE | `/guest/device` | P1 | ✅ | X-App-Token | 清除访客设备记录（X-Device-Id 定位） |
| GET | `/download/ios` | P1 | ✅ | 无 | iOS 安装下载页 |
| GET | `/api/app/latest` | P1 | ✅ | 无 | Android OTA 版本信息（`?channel=debug\|release` 双轨，COS metadata 透传 versionCode/changelog） |
| POST | `/admin/apk/upload` | P1 | ✅ | ADMIN_TOKEN | APK 上传双轨（channel/versionCode/changelog 字段；`X-Admin-Token` header 直连返回 JSON，供 ota-publish.sh 消费） |
| GET | `/download/ios/manifest.plist` | P1 | ✅ | 无 | iOS OTA 安装 manifest（itms-services） |
| POST | `/download/ios/udid` | P1 | ✅ | 无 | iOS UDID 注册（mobileconfig 回传表单） |
| GET | `/admin/users/{id}/token` | P1 | ✅ | ADMIN_TOKEN | 查看账号 token |
| GET | `/admin/channels` | P1 | ✅ | ADMIN_TOKEN | 渠道管理列表页 |
| GET | `/admin/channels/new` | P1 | ✅ | ADMIN_TOKEN | 新建渠道页 |
| GET | `/admin/channels/{id}/edit` | P1 | ✅ | ADMIN_TOKEN | 编辑渠道页 |
| GET | `/admin/channels/{id}/token` | P1 | ✅ | ADMIN_TOKEN | 查看渠道上游 token |
| GET | `/admin/release` | P1 | ✅ | ADMIN_TOKEN | 发布页（APK/IPA 上传管理） |
| GET | `/assets/{manifest,url}` | P1 | 🚧 | X-App-Token | COS 预签名 — 待实现 |
| GET | `/agent/config` | P1 | 🚧 | X-App-Token | 供应商适配参数下发 — 待实现 |

> **browser 路由口径（2026-10-06 核定）**：owner 身份 = `ownerTokenHash()`——只认 `X-App-Token`（全局拦截器写 TokenHashKey，兜底 `validateToken`），与 claude 系同口径；**429 仅出自 `/v1/browser/open` 的每日配额 peek**（`peek` 不扣量、成功才 `allow` 计费），action/frame/close 不查配额；`BROWSER_BRIDGE_URL` 未配/bridge 不可达 → 503 `browser_unavailable` 不计额度。**超时分级层叠（守卫测试钉住，改超时须保层叠）**：bridge navTimeout 15s < App 端 registry 内层 25s = dispatch 外层 25s（同源常量）< 网关 30s——慢页面被内层必杀，外层永不触发。WS `/v1/browser/ws`（M2，2026-10-09）不查配额，鉴权 = 同一全局拦截器 + `ownerTokenHash()`，失败以 close code 表达（VIOLATED_POLICY/CANNOT_ACCEPT/SERVICE_RESTART），与 bridge 间文本/二进制帧双向透传。**busy 自愈（M2.5，2026-10-11）**：open 撞 `pool_exhausted` 前经 bridge `GET /session/{id}/status` 探测占租会话，仅 `session_expired` 判死（条件释放 + `recordClose("reaped_heal")` + 重试），null/解析失败/其它 status 一律保守 BUSY——覆盖 App 进程死亡未 close 导致的租约泄漏；并发租约默认 15min，不变式为 > bridge hardCap 10min，快速解锁由自愈探测承担而非短租约。

---

## 4. 认证体系

### 4.1 客户端认证（邮箱注册动态 Token）

```
App → POST /auth/email/send (email, lang?) → 服务端按 lang 发送对应语言验证码邮件
App → POST /auth/email/verify (email, code) → 返回 picme_at_* token
App → 后续请求带 X-App-Token: <picme_at_*>
服务端 → SHA-256(token) 匹配 account.token_hash
```

- `/healthz`、`/auth/email/send`、`/auth/email/verify` 免鉴权
- `lang` 为可选 BCP-47 标签（客户端上报 App 界面语言）；缺省或无法识别时发中英双语邮件（向后兼容旧客户端），zh-Hant/HK/MO 归一为 zh-TW，其余 zh* 为 zh-CN（`EmailLanguage.resolve`）
- 注册用户请求亦带 `X-Device-Id`,用于管理后台 device 维度展示（访客用 X-Device-Id 记设备级试用额度）
- 每账户有 `FREE_LLM_QUOTA` 免费试用额度（默认 100 次），用尽返回 403
- 额度默认值持久化于 `server_setting` 表（`free_llm_quota` / `guest_llm_quota`）；env 仅在首次启动播种，之后由 `/admin/settings` 管理，运行时经 `SettingsService` 内存快照下发（热路径零 DB 读）。单账号上限可在「用户详情」页单独覆盖（`account.llm_calls_limit`，0=禁用）
- **debug 包额度豁免**（2026-10-21）：`DEBUG_QUOTA_BYPASS=true` + 请求头 `X-App-Channel: debug` 双满足时跳过三处额度扣减（`/v1/chat/completions` 账号+访客、`/agent/sessions/{id}/messages` 网关回合、`/v1/browser/open` 每日配额 peek/计费），用量照记 `llm_call_log`；Android debug 包由 `data/remote/AppChannel.kt` 自动带头（BuildConfig.DEBUG 派生，release 发 "release"），iOS 暂不上报。头可伪造——env（默认关）是总闸。全局拦截器打标 `DebugQuotaBypassKey`，路由经 `call.isDebugQuotaBypass()` 判定；豁免请求的错误回滚（revertQuota）同步跳过，保证 increment/revert 严格配对
- **debug 包免登录（云端 Agent 调试页访客身份，2026-10-22）**：复用 DEBUG_QUOTA_BYPASS 双闸——env 开 + `X-App-Channel: debug` + `X-Device-Id` 三满足且无有效账号 token 时，全局拦截器合成稳定访客身份 sha256("debug-guest:"+deviceId)（64 hex，与真实账号 token_hash 同形可落 VARCHAR(64) 列）写入 `TokenHashKey` + `DeviceIdKey`，`/agent/**` 由此免注册可达（会话/并发闸/审计按设备维度收敛）；输入域隔离（真实 token 为 picme_at_*）保证零碰撞，绝不落 account 表。客户端侧 CloudAgentChatViewModel DEBUG 构建放宽 not_logged_in 闸（空 token 继续）+ CloudAgentClient 补发 X-Device-Id 头（deviceIdProvider 注入）。env 默认关 = 生产全封
- Token 持久化在 `account` 表，`token_hash` 字段 SHA-256 存储

### 4.2 管理后台认证

- `/admin/**` 不走 X-App-Token，用固定 `ADMIN_TOKEN` + cookie（`picme_admin = sha256(ADMIN_TOKEN)`）；CLI/脚本可改用 `X-Admin-Token: <ADMIN_TOKEN>` header 直连（如 `scripts/ota-publish.sh` 发包），header 鉴权的请求响应 JSON 而非浏览器跳转
- `ADMIN_TOKEN` 为空 → 后台禁用（全部 503）
- **强烈建议** nginx 对 `/admin` 加 IP 白名单

---

## 5. 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Monorepo | `server/` 寄居在 `polang/` 下 | 端云同仓，AI 全栈协作友好，DTO/规则可端云同源 |
| 独立构建 | 不纳入 Android `settings.gradle.kts` | 安卓构建不依赖、不被拖慢 |
| 服务引擎 | Ktor CIO（纯 Kotlin 协程） | 省内存，2G 盒子友好 |
| DB | SQLite + Exposed + HikariCP | 单机单体，规则/遥测/用量结构化存储 |
| 对象存储 | 腾讯 COS（HK，`ap-hongkong`） | 100GB 标准存储包已购，预签名 URL 下发 |
| LLM 路由 | Cloudflare AI Gateway + TokenHub 双后端 | 按模型自动路由，密钥只在服务端 |
| 管理后台 | kotlinx.html SSR（零前端构建） | 同二进制部署，运营者友好 |

---

## 6. 构建与部署

```bash
# 本地开发
./server/run-local.sh start        # 后台启动 + 等就绪 + 打印 curl 命令
./gradlew -p server run            # 裸启动（前台，127.0.0.1:8080）

# 构建
./gradlew -p server installDist    # → build/install/picme-server/bin/picme-server

# 部署
deploy.sh                          # 开发机构建 → rsync → ssh 触发切换
deploy-switch.sh                   # 服务器端蓝绿：备份 → 切换 → restart → healthz → 失败回滚
```

systemd `picme-api.service`：`JAVA_OPTS=-Xmx256m` + `MemoryMax=450M`，与 OpenClaw 共享 2G 盒子。

nginx picme 反代段落仓库 SSOT：`server/deploy/nginx/api.polang.net.conf`（2026-10-17 入册；不变式 `proxy_read_timeout 300s` > 工具桥挂起窗 120s，详见文件头注释与 `OVERSEAS_SERVER_DEPLOYMENT.md` §5.2）。

---

## 7. 编译验证

```bash
./gradlew -p server build          # 编译 + 测试
./gradlew -p server installDist    # 构建分发包
```

---

## 8. 相关文档

- `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md` — 实现方案与 API 契约
- `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md` — 部署与运维
- `server/README.md` — 模块级快速参考
- `server/OPENCLAW_DEPLOY.md` — OpenClaw 一键发布指南

---

> **维护者**：项目开发者
> **最后更新**：2026-10-25
> **状态**：生效中
