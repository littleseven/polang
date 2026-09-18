# PoLang 服务端实现方案（Ktor）

> **文档状态**：已上线（v0.6.3），服务端已在 `api.polang.net` 运行。文档与代码已对齐，编码时以代码为事实来源。
> **最后更新**：2026-08-03（AI 工程师与问题上报路由对齐：`/v1/claude-chat`、`/v1/claude-tool-result`、`/v1/claude-deliver`、`/v1/claude-engineer/available`、`/v1/report-issue`；管理后台设置页白名单/问题诊断页；migrations 001~010，含 010_anonymous_device_platform.sql）
> **P0 阻断项**：✅ 已修复并本地端到端验证（2026-07-12）——WAL/busy_timeout/poolSize=1、seed 幂等加载（补 `rule(scene,locale,version)` 唯一索引让 `INSERT OR IGNORE` 真正生效）、StatusPages（Ktor 3 `(call,cause)` 双参数 handler）、`newSuspendedTransaction(Dispatchers.IO)`、systemd `JAVA_OPTS=-Xmx256m`。
> **维护者**：RD Agent
> **关联**：`PRODUCT.md`、`OVERSEAS_SERVER_DEPLOYMENT.md`、`AI_OPTIMIZATION.md`

---

## 1. 目标与范围

**目标**：在已购的 HK 腾讯轻量服务器上部署一个 **Ktor 后端**，挂 `api.polang.net`，支撑 PoLang App 的「推荐拍照 + 图片优化」服务端能力。

**现状资源（均已就位，不再采购）**：
- **服务器**：腾讯轻量 · 香港·三区 · **锐驰型** · 2C2G / 40G SSD / **200Mbps 峰值 · 无限流量**；公网 IP `43.161.201.142`；实例 `lhins-5u0t1f9f`；到期 2027-01-11。
- **域名**：`polang.net`（Cloudflare 注册，**DNS-only 灰云**，A 记录 → `43.161.201.142`）。
- **前置 Web**：Nginx 1.24（Ubuntu apt 装，**非宝塔**），已在 `polang.net` 托管项目官网 + 隐私声明（过审用），TLS 由 certbot 管。
- **对象存储**：腾讯 COS **100GB 标准存储包**（HK，`ap-hongkong`）。
- **同机另一租户**：OpenClaw（龙虾 AI 助手）——与后端共享 2G 内存，后端须设 `MemoryMax`。

**本轮交付（MVP + v0.5.0 扩展）**：
- 3 个 P0 路由：`/healthz`（✅）、`/recommend`（✅）、`/telemetry`（✅）
- 2 个 P0 路由（v0.5.0 新增）：`/v1/chat/completions`（✅ LLM 代理）、`/auth/email/{send,verify}`（✅ 邮箱认证）
- 1 个 P1 能力（v0.5.0 新增）：`/admin/**`（✅ 管理后台 SSR）
- 2 个 P1 路由：`/assets`（🚧）、`/agent/config`（🚧，供应商适配参数下发）
- 管理后台 v0.6.4 扩展：`/admin/devices`（未注册设备列表 + id 复制 + 单条删除，对应 `anonymous_device` 表）
- 管理后台 v0.6.4 扩展（额度/概览/渠道）：`/admin/settings`（全局额度默认值，持久化 `server_setting` 表 + `SettingsService` 内存快照）；`/admin/users/{id}/{reset-quota,limit}` 与 `/admin/devices/{id}/reset-quota`（清零计数、保留历史；单用户改上限）；概览页累计指标（用户/设备/Token/调用/成本）；`/admin/channels` 增消耗聚合 + 上游余额缓存（`llm_channel.balance_*` 列，`/admin/channels/{id}/refresh-balance`）
- AI 工程师与问题上报（2026-08 上线）：`POST /v1/claude-chat`（SSE 流式反代云主机 Claude Code）、`POST /v1/claude-tool-result`（App 工具结果回传）、`POST /v1/claude-deliver`（代码交付，白名单限制）、`GET /v1/claude-engineer/available`（返回 `{available, canDeliver}`）、`POST /v1/report-issue`（用户问题上报 → 脱敏后自动建 GitHub issue，`IssueReportService` + `GitHubIssueClient`）
- 管理后台 2026-08 扩展：`/admin/settings#whitelist`（AI 工程师白名单配置）、`/admin/diagnosis`（用户上报问题页）；原 `/admin/ai-engineer-whitelist` 301 重定向至 settings 页白名单区块
- 账号与配额路由：`GET /auth/quota`（额度查询）、`DELETE /auth/account`（账号软注销）、`DELETE /guest/device`（游客设备注销）
- SQLite（规则/元数据/遥测/计数/账号/LLM 日志）
- 腾讯 COS 预签名下发
- systemd + Nginx 反代 + certbot 上线

**P2（待验证后实施）**：
- `/assets` 完整实现 — COS 预签名 + 素材清单
- `/agent/config` 供应商适配参数下发

> **架构定位**：Server 是**配置中心 + 分发管道 + 遥测收集**，不做 Agent 编排。ReAct 循环、tool 执行、ChatMemory 永远在客户端。历史消息不存服务端（客户端 Room + DataStore 已有持久化）。

**本轮不做（Out of scope）**：GPU 云端图像处理、多 region、CI/CD 流水线、端云共享 Kotlin 模块、正式 WAF/监控仪表盘、飞书服务端接入（保持客户端直连）。

> **架构演进**：账号体系（v0.4.0）和管理后台（v0.5.0）已从 "Out of scope" 升级为已上线功能。

---

## 2. 代码管理：Monorepo（已定）

**决策**：后端放进本仓 `polang/server/`（**Monorepo**）——AI 全栈协作友好，端云同仓便于跨端检索与契约演进。

**落地点与构建边界**：
- 目录：`polang/server/`（自洽的 Ktor Gradle 工程）。
- 构建：`server/` 用**独立的 `settings.gradle.kts`**，**不纳入安卓的 settings.gradle.kts** → `./gradlew -p server installDist` 只编译后端，安卓构建完全不依赖 `:server`、也不被拖慢。（`server/` 无独立 `gradlew`，须用根目录 wrapper 加 `-p server`。）
- CI（后期）：用 path filter 让 `server/**` 改动才触发后端 build/deploy，不污染安卓流水线。
- 密钥：`server/.env` 不入 git；GitHub Actions（若启用）用独立 secrets。
- **端云共享 Kotlin（红利）**：将来在仓内加 `shared/` 模块，App 与 Server 共同引用——这是 monorepo 相对独立仓的最大优势，DTO/推荐规则可端云同源。

---

## 3. 技术栈

| 项 | 选型 | 备注 |
|----|------|------|
| 语言/框架 | **Kotlin 2.0.21 + Ktor 3.0.3** | **JDK 17**（代码 `JvmTarget.JVM_17`） |
| 服务引擎 | **CIO**（纯 Kotlin 协程，省内存） | 2G 盒子已选定 CIO |
| 构建 | Gradle 8.x Kotlin DSL | |
| HTTP 客户端 | Ktor HttpClient（CIO） | 用于 LLM 流式代理（P2） |
| 序列化 | kotlinx.serialization | JSON DTO |
| DB | **SQLite + Exposed 0.55.0 + HikariCP** | ⚠️ HikariCP 对 SQLite 价值低（单写者模型），考虑降 `maximumPoolSize=1` 或去 pool |
| 对象存储 | 腾讯 COS Java SDK（`cos-java-sdk`） | 生成预签名 URL |
| LLM 协议 | **DeepSeek（OpenAI Chat Completions 兼容）** | 默认接 DeepSeek，经 TokenHub 或直连 |
| 日志 | Logback | `logback.xml` + 滚动 |
| 进程 | systemd | `picme-api.service` |
| 反代/TLS | Nginx 1.24（已存在）+ certbot | `api.polang.net` |

---

## 4. 工程结构（monorepo 子目录 `server/`，自洽 Gradle build）

`server/` 寄居在 `polang/` 下，**用独立 `settings.gradle.kts`、不纳入安卓 `settings.gradle.kts`**——安卓 7 模块的构建完全不依赖它、也不被拖慢：

```
server/   # = polang/server/（rootProject.name = "picme-server"）
├── build.gradle.kts                # 版本号硬编码于此（无 libs.versions.toml）
├── settings.gradle.kts
├── src/main/
│   ├── kotlin/com/mamba/picme/server/
│   │   ├── Application.kt          # ✅ 入口 + 插件装配
│   │   ├── config/AppConfig.kt     # ✅ 读环境变量
│   │   ├── config/SettingsService.kt # ✅ 全局设置（server_setting 表 + 内存快照）
│   │   ├── routes/
│   │   │   ├── HealthzRoute.kt     # ✅ P0
│   │   │   ├── RecommendRoute.kt   # ✅ P0
│   │   │   ├── TelemetryRoute.kt   # ✅ P0
│   │   │   ├── AuthRoute.kt        # ✅ P0（邮箱认证 + /auth/quota + DELETE /auth/account + DELETE /guest/device）
│   │   │   ├── LlmRoute.kt         # ✅ P0（LLM 代理）
│   │   │   ├── DownloadRoute.kt    # ✅ P0
│   │   │   ├── ClaudeChatRoute.kt  # ✅ AI 工程师（/v1/claude-chat、/v1/claude-deliver、/v1/claude-engineer/available）
│   │   │   ├── ClaudeToolResultRoute.kt # ✅ /v1/claude-tool-result（App 工具结果回传）
│   │   │   ├── IssueReportRoute.kt # ✅ /v1/report-issue（用户上报 → GitHub issue）
│   │   │   ├── AssetsRoute.kt      # 🚧 P1 待实现
│   │   │   └── AgentConfigRoute.kt # 🚧 P1 待实现
│   │   ├── auth/
│   │   │   ├── AccountService.kt   # ✅ 账号 CRUD
│   │   │   ├── AppTokenAuth.kt     # ✅ X-App-Token 认证
│   │   │   └── EmailService.kt     # ✅ 验证码发送
│   │   ├── admin/
│   │   │   ├── AdminRoutes.kt      # ✅ 管理后台路由
│   │   │   ├── AdminAuth.kt        # ✅ ADMIN_TOKEN 认证
│   │   │   ├── AdminViews.kt       # ✅ SSR HTML 页面
│   │   │   └── AdminQueries.kt     # ✅ 运营数据查询
│   │   ├── llm/
│   │   │   ├── ChannelConfig.kt    # ✅ 供应商配置
│   │   │   ├── ChannelRegistry.kt  # ✅ 多供应商注册表
│   │   │   ├── ChannelRepository.kt# ✅ 配置持久化
│   │   │   ├── LlmProxy.kt         # ✅ 流式代理
│   │   │   └── LlmRoute.kt         # ✅ /v1/chat/completions
│   │   ├── recommend/RuleEngine.kt # ✅ 规则查询
│   │   ├── cos/CosService.kt       # ✅ COS 预签名
│   │   ├── ratelimit/RateLimiter.kt# ✅ 内存令牌桶
│   │   ├── analytics/
│   │   │   ├── UsageRecorder.kt    # ✅ LLM 调用日志
│   │   │   └── TokenUsage.kt       # ✅ Token 用量解析
│   │   ├── issue/
│   │   │   ├── IssueReportService.kt # ✅ 问题上报（脱敏 → GitHub issue）
│   │   │   └── GitHubIssueClient.kt  # ✅ GitHub Issues API 客户端
│   │   └── db/{Db,Tables,Migrations}.kt  # ✅
│   └── resources/logback.xml       # ✅
├── migrations/                     # 001_init ~ 009_drop_diag_jobs（含 006_account_soft_delete、007_server_setting、008_llm_channel_balance、009_drop_diag_jobs）
├── .env.example
├── deploy.sh · deploy-switch.sh · run-local.sh
├── picme-api.service
└── README.md · OPENCLAW_DEPLOY.md
```

> **构建命令**：`server/` 无独立 `gradlew`，须用根目录 wrapper：`./gradlew -p server run`（开发）或 `./gradlew -p server installDist`（构建）。

---

## 5. API 契约

所有路由前缀经 Nginx 反代到 `https://api.polang.net/`。

| 方法 | 路径 | 优先级 | 状态 | 请求 | 响应 | 缓存/限流 |
|------|------|--------|------|------|------|----------|
| GET | `/healthz` | P0 | ✅ | — | `{status:"ok", version, time}` | 不缓存 |
| POST | `/recommend` | P0 | ✅ | `{scene, locale, clientVersion?}` | `{params:{...}, ruleVersion}` | 可缓存；不限流 |
| POST | `/telemetry` | P0 | ✅ | `{events:[{type, payload}]}` | `{accepted:n}` | 不缓存；批量 |
| POST | `/v1/chat/completions` | P0 | ✅ | OpenAI 兼容 `{messages, model?, stream?}` | OpenAI 兼容响应 / **SSE** 流 | 限流 + 计费（Channel 路由） |
| POST | `/auth/email/send` | P0 | ✅ | `{email}` | `{ok}` | 限流 |
| POST | `/auth/email/verify` | P0 | ✅ | `{email, code}` | `{token}` | — |
| GET | `/auth/quota` | P0 | ✅ | —（X-App-Token） | `{used, limit, ...}` | 不缓存 |
| DELETE | `/auth/account` | P0 | ✅ | —（X-App-Token） | 200/404 | 账号软注销 |
| DELETE | `/guest/device` | P0 | ✅ | —（X-Device-Id） | 200/400 | 游客设备数据删除 |
| POST | `/v1/claude-chat` | P1 | ✅ | `{message, claudeSid?, ...}` | **SSE**（流式反代云主机 Claude Code，含 `app_tool_request` 下行） | 限流；已认证账号可用（只读诊断） |
| POST | `/v1/claude-tool-result` | P1 | ✅ | `{claudeSid, toolUseId, result}` | `{ok}` | App 工具执行结果回传 |
| POST | `/v1/claude-deliver` | P1 | ✅ | `{claudeSid, instruction}` | **SSE** | **白名单限制**（`ai_engineer_whitelist`，代码交付） |
| GET | `/v1/claude-engineer/available` | P1 | ✅ | — | `{available, canDeliver}` | 不缓存 |
| POST | `/v1/report-issue` | P1 | ✅ | `{title, description, ...}` | `{issueUrl}` | 每账号每天 10 条；脱敏后建 GitHub issue |
| GET | `/assets/manifest` | P1 | 🚧 | `?since=<ver>` | `{models:[{key,kind,version,size,md5}]}` | 可缓存 |
| GET | `/assets/url` | P1 | 🚧 | `?key=<modelKey>` | `{url, expiresAt}` | COS 预签名；短缓存 |
| GET | `/agent/config` | P1 | 🚧 | `?clientVersion?&locale?` | `{systemPrompt, model, providerAdapters, maxIterations}` | 可缓存（客户端启动时拉取） |
| POST | `/llm/chat` | P2 | 🚧 | `{messages:[{role,content}], model?, stream:true}` | **SSE**（`data: {token}` 流） | **限流 + 日预算；仅 Keyless 用户** |

> **`/agent/config` 的作用**：将客户端 ReAct 循环中容易变更的参数（system prompt、供应商适配开关如 disableThinking/strictToolSchema、模型选择、maxIterations）从 APK 硬编码改为服务端配置下发，使大多数供应商适配问题不需发版即可解决。
>
> **`/llm/chat` 的定位**：混合模式下仅服务无 API Key 的用户（Keyless）。BYOK 用户直连 TokenHub，不经过 Server。Server 对 `/llm` 是无状态透传管道 + 计费网关，不参与 ReAct 编排。
>
> 字段细节（推荐参数 schema、LLM 消息格式）在实施时按产品约定补全，并写进 `openapi.yaml`。

---

## 6. 数据模型（SQLite）

```sql
-- 推荐规则（改库即热更）
CREATE TABLE rule (
  id INTEGER PRIMARY KEY,
  scene TEXT NOT NULL,          -- night/portrait/food/...
  locale TEXT NOT NULL,         -- zh/en/...
  condition_json TEXT,          -- 附加条件
  params_json TEXT NOT NULL,    -- 推荐参数包
  version INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1
);

-- 模型/素材清单
CREATE TABLE asset (
  key TEXT PRIMARY KEY,         -- models/landmark@v2
  kind TEXT NOT NULL,           -- model/filter/preset
  version INTEGER NOT NULL,
  size INTEGER, md5 TEXT,
  cos_bucket TEXT, cos_key TEXT,
  created_at INTEGER NOT NULL
);

-- 匿名遥测
CREATE TABLE telemetry_event (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  type TEXT NOT NULL,
  payload_json TEXT,
  created_at INTEGER NOT NULL
);

-- LLM 日预算熔断
CREATE TABLE llm_daily_counter (
  day TEXT PRIMARY KEY,         -- '2026-07-11'（时区 Asia/Hong_Kong）
  tokens INTEGER NOT NULL DEFAULT 0,
  cost_cny REAL NOT NULL DEFAULT 0,   -- 与 DeepSeek 计费一致，CNY
  blocked INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX idx_rule_scene ON rule(scene, locale, enabled);
-- seed 幂等关键：INSERT OR IGNORE 需唯一约束才会忽略重复，否则自增 id 无冲突每次重启重复插入
CREATE UNIQUE INDEX idx_rule_seed ON rule(scene, locale, version);
CREATE INDEX idx_telemetry_time ON telemetry_event(created_at);
```

---

## 7. 关键组件设计

- **RuleEngine（recommend）**：按 `(scene, locale, enabled)` 查 `rule` → 取最新 `params_json` 返回。**非个性化、纯规则**（规避算法备案，隐私友好）。用原生 SQL + `exec`（已参数化防注入），经 `newSuspendedTransaction(Dispatchers.IO)` 调用；后续可改 Exposed DSL 保持风格一致。
- **seed 幂等（Migrations）**：`seed_rules.sql` 用 `INSERT OR IGNORE`，但**必须依赖 `rule(scene, locale, version)` 唯一索引**才会忽略重复——否则自增 id 无冲突，每次重启都重复插入。已补唯一索引，重启不重复（本地验证：6 条保持稳定）。
- **CosSigner（assets，P1 待实现）**：读 `asset` 表得 `(cos_bucket, cos_key)` → 用 COS SDK 生成预签名 GET URL。
- **AgentConfig（agent/config，P1 待实现）**：从 DB 或静态配置返回供应商适配参数包，客户端启动时拉取并缓存。解决 loop 在端上带来的兼容性问题——供应商适配从 APK 硬编码变为配置驱动。
- **OpenAiProxy（llm，P2 待实现）**：`HttpClient` POST `${LLM_BASE_URL}/chat/completions`（`stream=true`）→ 逐 chunk 透传 SSE；前置限流 + 日预算检查；**密钥只在服务端**。超预算返回降级提示。混合模式下仅 Keyless 用户使用。
- **Telemetry**：已改 `newSuspendedTransaction(Dispatchers.IO)` 协程安全写库（不再阻塞 CIO 事件循环）。仍待补：`batchInsert` 批量化 + 批量上限校验（`MAX_BATCH_EVENTS`、`MAX_PAYLOAD_BYTES`）。
- **RateLimiter（P2 待实现）**：内存令牌桶（按 IP）；`/llm` 日预算读 `llm_daily_counter`。

---

## 8. 配置与密钥

`server/.env`（**不入 git**，`.env.example` 提供模板）：
```
HOST=127.0.0.1
PORT=8080
DB_PATH=/var/lib/picme/picme.db

# LLM —— DeepSeek（OpenAI 兼容协议），经 TokenHub 或直连
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_API_KEY=sk-xxx
LLM_MODEL=deepseek-chat
LLM_DAILY_BUDGET_CNY=20

# 限流
RATE_LIMIT_PER_MIN=100

# 腾讯 COS（HK, 100GB）
COS_SECRET_ID=
COS_SECRET_KEY=
COS_REGION=ap-hongkong
COS_BUCKET=
COS_PRESIGN_TTL_MIN=60
```
- 配置全部走环境变量（`AppConfig.load()`），**无 `application.conf` HOCON 文件**。
- 本地 dev：`.env`（dotenv）/ `export`。
- 服务器：systemd `EnvironmentFile=/etc/picme/server.env`。
- host/port 由环境变量 `HOST`/`PORT` 控制，默认 `127.0.0.1:8080`（仅本地监听）。

---

## 9. 部署

**本地构建**：`./gradlew -p server installDist` → `build/install/picme-server/bin/picme-server`（rootProject.name=`picme-server`）。注意 `server/` 无独立 `gradlew`，须用根目录 wrapper 加 `-p server`。

**两段式发布（蓝绿 + 自动回滚）**，脚本见 `server/deploy.sh` + `server/deploy-switch.sh`（单一来源，不再在此复制全文）：
- `deploy.sh`（开发机）：`installDist` → rsync artifact 到服务器 `~/picme-server.new/`（不覆盖现网）→ ssh 触发切换。
- `deploy-switch.sh`（服务器）：备份现网 → `mv .new → picme-server` → `systemctl restart picme-api` → 轮询 `http://127.0.0.1:8080/healthz`（最长 30s）→ **失败自动回滚**到 `~/picme-server.prev` + 打 journalctl。
- OpenClaw 可直接 `bash ~/deploy-switch.sh` 实现「一句话发布」（指令见 `server/OPENCLAW_DEPLOY.md`）。
- 前提（首次部署确认）：ubuntu 用户 `sudo systemctl` 免密；生产监听 `127.0.0.1:8080`（`HEALTH_URL` 不符时用环境变量覆盖）。

**`picme-api.service`**（systemd）：
```ini
[Unit]
Description=PoLang API (Ktor)
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/picme-server
EnvironmentFile=-/etc/picme/server.env
Environment="JAVA_OPTS=-Xmx256m -Xms128m -XX:MaxMetaspaceSize=128m"
ExecStart=/home/ubuntu/picme-server/bin/picme-server
Restart=always
RestartSec=3
# 内存上限保护（2G 共享盒子，OpenClaw 占 ~1G）
MemoryMax=450M

[Install]
WantedBy=multi-user.target
```

> **JVM 堆限制（P0 修复）**：不加 `-Xmx` 时 JVM 默认堆按物理内存 1/4 算（~512M），极易撞破 MemoryMax 被 OOMKilled。`-Xmx256m` 确保堆可控，实际 RSS 峰值 ~350M。

**COS 初始化**：建私有读 bucket（HK，`ap-hongkong`）；上传模型到 `models/...`；为服务端建子账号 + 最小签名权限（只允许对该 bucket 的 GetObject 预签名）。

**Nginx + 证书**：见附录 A。

---

## 10. 安全

- 后端**只监听 `127.0.0.1:8080`**，仅 Nginx 暴露；不直接对公网。
- `ufw`：仅放 22/80/443；SSH 改端口 + 密钥 + 禁密码登录；装 fail2ban。
- 密钥只服务端持有；**App 不持有 LLM/COS 密钥**（经自家网关）。
- `/llm`（P2）限流 + 日预算熔断；`/telemetry` 需补批量上限校验（`MAX_BATCH_EVENTS`、`MAX_PAYLOAD_BYTES`）。
- ⚠️ **StatusPages 未安装**（P0 修复项）：`build.gradle.kts` 已引 `ktor-server-status-pages` 依赖，但 `Application.kt` 未 `install(StatusPages)`。当前任意异常返回裸 500 + 堆栈泄露。须补 install + 统一错误体 `{error, message}`。
- systemd `MemoryMax=450M` + `JAVA_OPTS=-Xmx256m` 双保险，防后端把 2G 盒子（与 OpenClaw 共享）拖垮。
- 请求体上限（Nginx `client_max_body_size 20m`）。
- 隐私：图像/人脸不上行；LLM 对话文本（P2）需补用户授权流程 + 跨境数据声明（HK 服务器 → DeepSeek 回源大陆）。

---

## 11. 监控与备份（轻量）

- **存活**：UptimeRobot 免费层 ping `/healthz`，挂了邮件提醒。
- **日志**：systemd journal + logback 滚动；`journalctl -u picme-api`。
- **指标**：日志埋点 + `llm_daily_counter`（token/花费/是否熔断）。
- **备份**：每日 cron `sqlite3 picme.db '.dump' | gzip` → 本机轮转 7 天；异地零成本备份加密推 GitHub 私有仓 Release。COS 存的是模型（非用户数据），无需备份。

---

## 12. 文档更新

实施后**重写** `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md`：
- 删除旧版「CF 代理 + R2 + 通用 VPS」假设。
- 替换为现实版：**Nginx(非宝塔) + COS + DNS-only + Ktor + 三租户共存（官网/隐私 + OpenClaw + 后端）**。
- 收录本方案的 Nginx/systemd/deploy.sh/COS 配置。

---

## 13. 实施顺序

### P0：修复已有代码（上线阻断项）✅ 已完成并本地端到端验证（2026-07-12）

1. ✅ **SQLite 配置**：`Db.kt` 加 `PRAGMA journal_mode=WAL`（库级持久）+ `PRAGMA busy_timeout=5000`（连接级 init），`maximumPoolSize` 降到 1。本地验证 `journal_mode=wal`。
2. ✅ **seed_rules.sql 加载**：`Migrations.run()` 读 classpath 的 `seed_rules.sql` 执行（`INSERT OR IGNORE`），并补 `rule(scene,locale,version)` 唯一索引让幂等真正生效。本地验证 6 条规则、重启不重复。
3. ✅ **StatusPages 安装**：`Application.kt` 补 `install(StatusPages)`，统一 `{error,message}`；Ktor 3 的 exception handler 是 `suspend (call, cause) -> Unit` 双参数形式。本地验证 malformed JSON → 400。
4. ✅ **JVM 堆限制**：`picme-api.service` 加 `Environment="JAVA_OPTS=-Xmx256m -Xms128m -XX:MaxMetaspaceSize=128m"`、`MemoryMax=450M`、`EnvironmentFile=-/etc/picme/server.env`（部署时生效）。
5. ✅ **transaction 改协程安全**：`RuleEngine`/`TelemetryRoute` 的 `transaction {}` 改 `newSuspendedTransaction(Dispatchers.IO)`。本地验证 `/telemetry` 协程写库 200。

> 本地验证命令：`./gradlew -p server run`（配 `DB_PATH=build/picme.db`），curl 打 `/healthz` `/recommend` `/telemetry`。

### P0→P1：MVP 上线

6. `/assets` manifest + COS 预签名。
7. `/agent/config` 供应商适配参数下发。
8. `/telemetry` 补批量上限 + 异步落库。
9. `picme-api.service` + `deploy.sh`（已在 §9 更新）+ Nginx + certbot → 上线 healthz，`curl` 验证。
10. 补测试基建（`ktor-server-test-host` + `kotlin-test` + `mockk`）。
11. 重写 `OVERSEAS_SERVER_DEPLOYMENT.md`。

### P2：待验证后实施

12. `/llm` 流式代理（混合模式：仅 Keyless 用户）+ 限流 + 日预算。
13. `RateLimiter` + `OpenAiProxy`。

---

## 14. 已决策（Review 后定稿）

> 以下决策在 PM/RD/CR/QA 四角色 Review 后已拍板，以代码现状为事实来源。

| # | 决策点 | 结论 | 依据 |
|---|--------|------|------|
| 1 | Ktor 版本 | **Ktor 3.0.3 + Kotlin 2.0.21 + JDK 17** | 代码现状 `JvmTarget.JVM_17`；JDK 17 LTS 足够，CIO 不依赖 virtual threads |
| 2 | 服务引擎 | **CIO** | 2G 盒子首选，SSE 支持完整，省内存 |
| 3 | DB 访问 | **Exposed + HikariCP（poolSize 降到 1）** | SQLite 单写者模型，多连接只增冲突。开 WAL + busy_timeout |
| 4 | 默认 LLM | **DeepSeek**（OpenAI 兼容协议） | 中文质量好、价格极低（~¥1/M tokens）、代码已落地 deepseek-chat |
| 5 | COS 预签名 TTL | **默认 60min**（`.env` 可调） | 小素材足够；大模型场景可配置上调 + 客户端断点续传 |
| 6 | 限流/日预算 | **RATE_LIMIT_PER_MIN=100、LLM_DAILY_BUDGET_CNY=20** | 以代码为准（`.env.example`）；DeepSeek 单价低，¥20 可覆盖 ~6000 会话/天 |
| 7 | MemoryMax | **450M + JAVA_OPTS=-Xmx256m** | OpenClaw 占 ~1G，Ktor 基线 ~180M，堆限 256M 后 RSS 峰值 ~350M |

### 补充决策（架构讨论，2026-07-12）

| 决策 | 结论 |
|------|------|
| Server 定位 | 配置中心 + 分发管道 + 遥测收集，不做 Agent 编排 |
| `/llm` 优先级 | **P2**——MVP 不含，混合模式下仅 Keyless 用户走 Server |
| `/agent/config` | **新增 P1 路由**——供应商适配参数下发，解决端上 loop 兼容性问题 |
| 历史消息 | **不存服务端**——客户端 Room + DataStore 已有持久化 |
| 飞书 | **保持客户端接入**——不迁移服务端 |
| ReAct 循环归属 | **永远在客户端**——tool 执行在设备上，Server 不参与编排 |
| 2C2G 容量评估 | MVP（不含 /llm）绰绰有余；加 /llm 需限并发 Semaphore(16) |

---

## 附录 A：`api.polang.net` 上线清单（可照抄）

**A.1 DNS（Cloudflare）**：加 A 记录
- Type `A`，Name `api`，IPv4 `43.161.201.142`，Proxy **DNS only（灰云）**，TTL Auto。

**A.2 Nginx server 块**：新建 `/etc/nginx/sites-available/api.polang.net`
```nginx
server {
    listen 80;
    listen [::]:80;
    server_name api.polang.net;
    client_max_body_size 20m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # LLM 流式(SSE)必需
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 300s;
    }
}
```
启用并签证书：
```bash
sudo ln -s /etc/nginx/sites-available/api.polang.net /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d api.polang.net   # 自动签 Let's Encrypt + 加 443 + 强制 HTTPS
```

**A.3 启动后端 + 验证**
```bash
sudo systemctl enable --now picme-api
curl -I https://api.polang.net/healthz     # 期望 HTTP 200
```

**A.4 排错**
- 502：后端没起 / 端口不对 → `systemctl status picme-api`、`ss -tlnp | grep 8080`。
- 证书失败：DNS 未生效 → `dig api.polang.net +short` 应返回 IP。
- 仍打到官网：Nginx 没识别 server 块 → 确认软链 + reload。
