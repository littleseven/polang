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

**版本**：0.9.3

**最后更新**：2026-09-18

**状态**：生效中 / 已上线

---

## 1. 模块概述

`server/` 是独立的 Ktor Gradle 工程（`rootProject.name = "picme-server"`），**不纳入 Android `settings.gradle.kts`**。通过 `./gradlew -p server` 独立构建和运行。

**核心职责**：
- **AI 网关**：`LlmProxy` + `ChannelRegistry` — 按模型自动路由到 Cloudflare AI Gateway 或腾讯 TokenHub；`stream=true` 请求 SSE 逐 chunk 透传（原样转发 stream/stream_options），usage 从流尾帧解析计费；非流式请求强制 `stream=false` 走完整响应体解析
- **账号体系**：`EmailService` + `AccountService` + `AppTokenAuth` — 邮箱注册、动态 Token、SHA-256 校验
- **管理后台**：`AdminRoutes` + `AdminViews` + `AdminQueries` — SSR HTML 运营后台（概览/用户/流量）
- **推荐引擎**：`RuleEngine` — 纯规则型场景推荐（规避算法备案）
- **遥测收集**：`TelemetryRoute` — 批量匿名事件写入 SQLite
- **COS 存储**：`CosService` — 腾讯 COS 预签名 URL 生成
- **限流**：`RateLimiter` — per-IP 令牌桶 + 日预算熔断
- **用户问题上报**：`IssueReportRoute` — 脱敏后入库并自动同步 GitHub issue

---

## 2. 工程结构

```
server/
├── build.gradle.kts              # Kotlin 2.0.21 + Ktor 3.0.3 + Exposed 0.55.0 + SQLite
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
│   │   └── DownloadRoute.kt      # GET /download — 资源下载 + iOS 安装（/download/ios、manifest.plist、udid 注册）
│   ├── auth/
│   │   ├── AccountService.kt     # 账号 CRUD + token 生成/校验
│   │   ├── AppTokenAuth.kt       # X-App-Token 认证插件
│   │   ├── EmailService.kt       # 验证码发送（SMTP）
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
| GET | `/auth/quota` | P1 | ✅ | X-App-Token | 查询账号剩余额度 |
| DELETE | `/auth/account` | P1 | ✅ | X-App-Token | 注销账号（软删除） |
| DELETE | `/guest/device` | P1 | ✅ | X-App-Token | 清除访客设备记录（X-Device-Id 定位） |
| GET | `/download/ios` | P1 | ✅ | 无 | iOS 安装下载页 |
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

---

## 4. 认证体系

### 4.1 客户端认证（邮箱注册动态 Token）

```
App → POST /auth/email/send (email) → 服务端发送验证码
App → POST /auth/email/verify (email, code) → 返回 picme_at_* token
App → 后续请求带 X-App-Token: <picme_at_*>
服务端 → SHA-256(token) 匹配 account.token_hash
```

- `/healthz`、`/auth/email/send`、`/auth/email/verify` 免鉴权
- 注册用户请求亦带 `X-Device-Id`,用于管理后台 device 维度展示（访客用 X-Device-Id 记设备级试用额度）
- 每账户有 `FREE_LLM_QUOTA` 免费试用额度（默认 100 次），用尽返回 403
- 额度默认值持久化于 `server_setting` 表（`free_llm_quota` / `guest_llm_quota`）；env 仅在首次启动播种，之后由 `/admin/settings` 管理，运行时经 `SettingsService` 内存快照下发（热路径零 DB 读）。单账号上限可在「用户详情」页单独覆盖（`account.llm_calls_limit`，0=禁用）
- Token 持久化在 `account` 表，`token_hash` 字段 SHA-256 存储

### 4.2 管理后台认证

- `/admin/**` 不走 X-App-Token，用固定 `ADMIN_TOKEN` + cookie（`picme_admin = sha256(ADMIN_TOKEN)`）
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
> **最后更新**：2026-09-18
> **状态**：生效中
