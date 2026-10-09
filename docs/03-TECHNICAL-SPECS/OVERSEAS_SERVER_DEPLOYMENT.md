# 海外服务端部署与运维（香港节点）

> **文档类型**：部署运维规格
> **针对场景**：PoLang 出海 Google Play，面向海外用户的「推荐拍照 + 图片优化 + LLM 代理」服务端
> **部署形态**：个人开发者 · 单机单体 · 香港机房 · Nginx 反代 + 腾讯 COS
> **最后更新**：2026-08-03
> **维护者**：RD Agent（技术实现）
> **关联文档**：`PRODUCT.md`、`docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`、`server/README.md`

---

## 1. 背景与目标

PoLang 是「AI Agent + 相册/图像编辑」技术探索实验场。产品重心已迁移到相册与图片编辑（见 `PRODUCT.md`），其中「AI 一键优化」「智能场景模板」「对话式编辑」需要服务端能力支撑：可更新的推荐规则、模型/素材分发、远程 LLM 网关、匿名遥测、账号体系、管理后台。

面向**海外市场（Google Play）**的服务端方案目标：

- **个人开发者可承担**：6 个月含域名预算 ≤ 500 元人民币。
- **免中国大陆备案**：使用香港 region，绕开 ICP 备案与生成式 AI 服务备案等个人开发者资质门槛。
- **隐私优先不变**：敏感数据（人脸/图像/对话原文）端侧处理；服务端只接收匿名标签与参数；重算子默认不上云。
- **可直连海外 LLM**：香港可访问 OpenAI / Gemini / Anthropic（大陆机房不可），这是选择香港而非大陆的硬性原因。
- **稳定可用**：单机部署 + Nginx 反代，承担 TLS 终止与静态托管。

> 非目标：高可用多活、GPU 云端图像处理（成本不允许，且与隐私优先冲突）、商业化计费系统、Cloudflare 橙云代理（当前 DNS-only）。

---

## 2. 设计原则

| 原则 | 含义 |
|------|------|
| **端侧优先** | 人脸检测、图像增强、OCR 全在端侧；服务端不持有原图与人脸生物特征 |
| **上行最小化** | App 仅上送匿名场景标签与必要参数，不送图像/位置/设备指纹可逆信息 |
| **免备案** | 香港机房 + 海外域名，不进入中国大陆备案体系 |
| **单一职责单体** | MVP 阶段一个进程承载所有路由，SQLite 存储，避免微服务复杂度 |
| **成本可预测** | 免费层优先，按量服务设阈值告警，防止流量/LLM 费用失控 |
| **可演进** | 单机起步，预留向「多 region、双市场、拆分服务」的升级路径 |

---

## 3. 总体架构（实际落地）

```
                 ┌──────────────────────────────────────────────────────┐
   海外用户(App) │                   DNS（Cloudflare DNS-only）          │
   Google Play  │  域名解析：polang.net / api.polang.net                  │
       │        │  A 记录 → 43.161.201.142（灰云，非代理）                │
       └───────▶│                                                      │
                 └────────────────────┬─────────────────────────────────┘
                                      │ HTTPS（Nginx 1.24 + certbot TLS）
                                      ▼
                 ┌──────────────────────────────────────────────────────┐
                 │        香港轻量服务器 2C2G（腾讯云 Lighthouse）          │
                 │  Nginx 1.24（反代 + TLS + 静态托管）                    │
                 │  ├─ polang.net          → 项目官网 / 隐私声明            │
                 │  ├─ api.polang.net      → 127.0.0.1:8080（Ktor 后端）  │
                 │  └─ certbot 自动续期 Let's Encrypt 证书                │
                 │  单体后端（Kotlin/Ktor）：                              │
                 │     /healthz     存活探活                              │
                 │     /recommend   场景标签 → 参数包（规则引擎）          │
                 │     /telemetry   匿名指标 append                       │
                 │     /v1/chat/completions  LLM 代理（流式 SSE）        │
                 │     /auth/email/{send,verify}  邮箱注册认证             │
                 │     /v1/claude-chat 等  AI 工程师（SSE 反代 / tool-result / deliver / available）│
                 │     /v1/report-issue  用户问题上报（→ GitHub issue）    │
                 │     /admin/**    管理后台（SSR HTML，含 settings 白名单 / diagnosis）│
                 │  SQLite(WAL) — 规则 / 元数据 / 遥测 / 账号 / LLM 日志   │
                 └──────────┬──────────────────────────┬──────────────────┘
                            │ 签名 URL（下发）          │ 代理（出站）
                            ▼                         ▼
                   腾讯 COS（ap-hongkong）      Cloudflare AI Gateway / TokenHub
                ┌──────────────────┐        （香港可直连；大陆机房被墙）
                │ models/ filters/ │
                │   presets/       │
                └──────────────────┘
```

**三层职责**：
1. **DNS 层（Cloudflare DNS-only）**：仅做域名解析，灰云模式（不代理流量），源站 IP 直接暴露。未来如需抗 D / CDN 可切橙云，改动极小。
2. **网关层（Nginx 1.24 + certbot）**：TLS 终止、反向代理、静态网站托管（项目官网 + 隐私声明）。
3. **计算层（香港 VPS）**：唯一的有状态业务逻辑（推荐规则、LLM 代理、遥测、账号、管理后台）。位于香港，可直连海外 LLM、可被大陆开发者在合规前提下访问调试。
4. **存储层（腾讯 COS + SQLite）**：对象存储（大文件、模型）放腾讯 COS HK（100GB 标准存储包已购）；结构化小数据（规则、元数据、遥测、账号）放源站 SQLite。

---

## 4. 选型决策

### 4.1 为什么是香港（不是大陆、不是欧美）

| 维度 | 大陆机房 | **香港机房** | 新加坡/欧美 |
|------|----------|------------|------------|
| ICP 备案 | 必须（个人非经营可办，但生成式 AI 公众服务仍受限） | **免** | 免 |
| 可直连海外 LLM（OpenAI/Gemini） | ❌ 被墙 + 厂商不支持中国区 | ✅ **支持 HK 区** | ✅ |
| 对国内开发者支付/控制台 | ✅ 人民币/中文 | ✅ **人民币/中文** | ❌ USD/英文为主 |
| 到大陆延迟 | 最低 | 低（华南 ~10–30ms，过 GFW 有抖动） | 高 |
| 到海外用户延迟 | 高（出海差） | 亚洲优、欧美中 | 欧美优、亚洲中 |
| GDPR 充分性 | 否 | 否（需 SCC） | 视地区 |

**结论**：出海为主、开发者在国内、需调海外 LLM → **香港是最优折中**。

### 4.2 云厂商与配置（实际落地）

| 云 | 配置 | 价格 | 说明 |
|----|------|------|------|
| **腾讯云 Lighthouse（香港·三区·锐驰型）** | 2C2G / 40G SSD / **200Mbps 峰值 · 无限流量** | 年付约 500 元 | 已购，到期 2027-01-11 |

**同机另一租户**：OpenClaw（龙虾 AI 助手）——与后端共享 2G 内存，后端须设 `MemoryMax=450M`。

### 4.3 域名

- **注册商**：Cloudflare Registrar（`.net` 域名）。
- **DNS 模式**：**DNS-only（灰云）**，A 记录直接指向 VPS 公网 IP `43.161.201.142`。
- **子域规划**：`api.polang.net`（API 入口）、`polang.net`（项目官网 / 隐私声明）。
- **未启用**：Cloudflare 橙云代理、R2、Workers、Tunnel、Access——当前阶段不需要，未来可按需启用。

### 4.4 LLM 选型（实际使用）

| 选项 | 说明 | 成本 |
|------|------|------|
| **DeepSeek** | 通过 Cloudflare AI Gateway 或腾讯 TokenHub 代理 | 按 token，极低 |
| **Gemini** | 备选，多模态强 | 免费层 + 超用便宜 |

> PoLang 远程推理已用 OpenAI Chat Completions 协议（见 `PRODUCT.md` ADR-005），切换 `baseUrl + apiKey` 即可指向不同供应商，**零代码改动**。

---

## 5. Nginx 与 TLS 配置

### 5.1 Nginx 角色

Nginx 1.24（Ubuntu apt 安装，**非宝塔**）承担：
- **TLS 终止**：certbot 自动申请/续期 Let's Encrypt 证书
- **反向代理**：`api.polang.net` → `127.0.0.1:8080`（Ktor 后端）
- **静态托管**：`polang.net` → 项目官网 + 隐私声明（过审用）
- **WebSocket 支持**：`/v1/chat/completions` 流式 SSE 透传；`/v1/browser/ws` WS 帧流代理（browser-vnc M2，`location /` 需 Upgrade/Connection 头透传）

### 5.2 关键配置片段

```nginx
# api.polang.net — 反代 Ktor 后端
server {
    listen 443 ssl http2;
    server_name api.polang.net;
    ssl_certificate /etc/letsencrypt/live/api.polang.net/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.polang.net/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # WebSocket 透传（browser-vnc M2 帧流代理 /v1/browser/ws，2026-10-10 补；
        # 缺这两行 WS upgrade 在 nginx 层被剥成普通 GET，Ktor 回 400）
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        # SSE 流式透传
        proxy_buffering off;
        proxy_cache off;
    }
}

# polang.net — 静态官网
server {
    listen 443 ssl http2;
    server_name polang.net;
    ssl_certificate /etc/letsencrypt/live/polang.net/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/polang.net/privkey.pem;
    root /var/www/polang;
    index index.html;
}
```

### 5.3 certbot 自动续期

```bash
# 首次申请
certbot --nginx -d api.polang.net -d polang.net

# 自动续期（systemd timer 已配置）
systemctl status certbot.timer
```

---

## 6. 数据流（关键路径）

### 6.1 推荐拍照（F1）

```
App(端侧识别场景标签：夜景/人像/逆光/美食...)
   │ POST /recommend  { scene:"night", locale:"zh" }   (不含图像)
   ▼
Nginx ──▶ HK 源站 /recommend：查 SQLite 规则表 → 组装参数包(EV/美颜档位/滤镜/比例)
   │
   ▼
App 应用参数，展示推荐；用户采纳/丢弃 → POST /telemetry(匿名)
```

### 6.2 模型 / 素材下载（F2）

```
App ──GET /assets/manifest──▶ Nginx ──▶ HK 源站(返回清单 JSON, 含 COS 对象 key)
App ──GET COS 预签名URL─────▶ 腾讯 COS(ap-hongkong) ──▶ 就近吐出模型文件
```

### 6.3 LLM 对话代理（F3）

```
App ──POST /v1/chat/completions (SSE/流式)──▶ Nginx(proxy_buffering off)
   │
   ▼
HK 源站 /v1/chat/completions：ChannelRegistry 按模型路由 → 限流 + 用量采集
   │  出站(香港可直连)
   ▼
Cloudflare AI Gateway / TokenHub ──流式 token──▶ HK 源站 ──透传──▶ Nginx ──▶ App
```

### 6.4 遥测（F4）

```
App(匿名化：采纳率/崩溃/性能，无设备可逆指纹)
   │ POST /telemetry (批量, 低频)
   ▼
Nginx ──▶ HK 源站 append SQLite(缓冲) ──离线聚合──▶ 报表
```

### 6.5 账号认证（F5）

```
App ──POST /auth/email/send──▶ Nginx ──▶ HK 源站(发送验证码到邮箱)
App ──POST /auth/email/verify──▶ Nginx ──▶ HK 源站(校验码 → 生成 picme_at_* token)
App ──后续请求带 X-App-Token: <picme_at_*> ──▶ Nginx ──▶ HK 源站(SHA-256 校验)
```

---

## 7. 组件详细设计

### 7.1 后端单体

- **语言/框架**：Kotlin 2.0.21 + Ktor 3.0.3（CIO 引擎），与 Android 技术栈一致
- **路由**：`/healthz`、`/recommend`、`/telemetry`、`/v1/chat/completions`、`/auth/email/{send,verify}`、`/auth/quota`、`DELETE /auth/account`、`DELETE /guest/device`、`/v1/claude-chat`、`/v1/claude-tool-result`、`/v1/claude-deliver`、`/v1/claude-engineer/available`、`/v1/report-issue`、`/admin/**`、`/download`
- **进程管理**：`systemd` 守护（`picme-api.service`），崩溃自启，`JAVA_OPTS=-Xmx256m` + `MemoryMax=450M`
- **配置**：环境变量注入（`server/.env` 不入 git，`.env.example` 提供模板）

### 7.2 LLM 网关（核心、成本风险点）

| 职责 | 实现 |
|------|------|
| 密钥保管 | key 仅存源站环境变量，**App 不持有**第三方 key |
| 多供应商路由 | `ChannelRegistry` 按 `model` 字段自动路由到 Cloudflare AI Gateway / TokenHub |
| 限流 | per-IP 令牌桶（`RateLimiter`，默认 20 req/min）+ 每账户免费额度熔断 |
| 流式透传 | SSE/Chunked 透传，`proxy_buffering off` |
| 降级 | 主 LLM 超时/失败 → 返回 502 + 客户端降级到本地推理 |
| 计量 | `UsageRecorder` + `TokenUsage` 解析上游 `usage`，记录真实 token + 估算成本 |

### 7.3 推荐引擎（规则型）

- **形态**：SQLite 规则表 `(scene, locale, enabled) → param_pack(JSON)`。非个性化、不做用户画像排序——**规避算法推荐备案**。
- **更新**：服务端改库即生效，App 无需发版。
- **seed 幂等**：`INSERT OR IGNORE` + `rule(scene, locale, version)` 唯一索引，重启不重复插入。

### 7.4 模型 / 素材分发（COS）

- **存储**：腾讯 COS `ap-hongkong`，100GB 标准存储包已购。
- **清单**：`/assets/manifest` 返回版本与对象 key；App 比对本地版本，增量下载。
- **访问**：`CosService` 生成预签名 GET URL，App 直接访问 COS（不经过源站）。

### 7.5 遥测

- **匿名**：仅采纳率、延迟分桶、崩溃堆栈、版本号；**不含设备 ID 可逆映射、不含图像、不含位置**。
- **写入**：`newSuspendedTransaction(Dispatchers.IO)` 协程安全写库，不阻塞 CIO 事件循环。

### 7.6 管理后台

- **形态**：kotlinx.html SSR（零前端构建），同二进制部署。
- **访问**：`https://api.polang.net/admin`，固定 `ADMIN_TOKEN` + cookie 认证。
- **页面**：概览（今日 stat）、用户列表、用户详情、流量趋势、渠道（消耗聚合 + 上游余额）、设置（全局额度默认值 + AI 工程师白名单 `#whitelist`）、问题诊断（用户上报问题）。原 `/admin/ai-engineer-whitelist` 已 301 重定向至 `/admin/settings#whitelist`。
- **安全**：`ADMIN_TOKEN` 为空则后台禁用；建议 nginx 加 IP 白名单。

---

## 8. 部署流程

### 8.1 首次部署

```bash
# 1. 服务器准备（Ubuntu 22.04）
apt update && apt install -y openjdk-17-jre-headline nginx certbot python3-certbot-nginx

# 2. 配置 Nginx + certbot
certbot --nginx -d api.polang.net -d polang.net

# 3. 创建 systemd service
cp picme-api.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable picme-api

# 4. 配置环境变量
mkdir -p /etc/picme
cp .env.example /etc/picme/server.env
# 编辑 /etc/picme/server.env：填 LLM_API_KEY、COS_SECRET、ADMIN_TOKEN 等

# 5. 构建并部署
./gradlew -p server installDist
./server/deploy.sh  # 或手动 rsync + ssh 触发 deploy-switch.sh
```

### 8.2 日常发布

```bash
# 开发机
./server/deploy.sh

# 服务器端（OpenClaw 可一句话执行）
bash ~/deploy-switch.sh
# 蓝绿：备份现网 → 切换 → restart → healthz 校验 → 失败自动回滚
```

### 8.3 本地开发

```bash
./server/run-local.sh start        # 后台启动 + 等就绪 + 打印 curl 命令
# 或裸启动：./gradlew -p server run
```

---

## 9. 安全与运维

### 9.1 安全基线

| 项 | 状态 | 说明 |
|----|------|------|
| TLS 1.3 | ✅ | certbot + Let's Encrypt |
| 源站 IP 暴露 | ⚠️ | DNS-only 灰云，IP 直接暴露；未来可切橙云隐藏 |
| SSH | ✅ | 改端口 + 公钥登录 + fail2ban |
| 防火墙 | ✅ | ufw 仅开 443/80 + 受限 SSH |
| 管理后台 | ⚠️ | `ADMIN_TOKEN` + cookie；建议 nginx IP 白名单 |
| 密钥管理 | ✅ | 全部走 `server/.env`，不入 git |

### 9.2 监控与告警

| 项 | 方式 | 说明 |
|----|------|------|
| 进程存活 | systemd + `MemoryMax` | 崩溃/oom 自动重启 |
| 健康检查 | `curl -f https://api.polang.net/healthz` | deploy-switch.sh 自动校验 |
| 日志 | Logback 滚动 + journald | `journalctl -u picme-api -f` |
| 磁盘 | SQLite 定期清理 + 告警 | 遥测表按时间归档 |
| LLM 成本 | `llm_daily_counter` 表 | 超预算自动熔断（返回 403） |

### 9.3 备份

- **SQLite**：`picme.db` 每日 `cp` 到 `~/backups/`，保留 7 天。
- **环境变量**：`/etc/picme/server.env` 手动备份（变更少）。
- **代码**：Git 仓库本身即备份。

---

## 10. 成本估算（6 个月）

| 项 | 月成本 | 6 个月 | 说明 |
|----|--------|--------|------|
| 腾讯云 Lighthouse HK | ~42 元 | ~252 元 | 年付 510 元 85 折 |
| 域名 `.net` | ~7 元 | ~42 元 | Cloudflare Registrar |
| 腾讯 COS 100GB | 已购包年 | 0 元 | 标准存储包 |
| LLM API（DeepSeek） | 按量 | ~50 元 | 免费试用 + 低用量 |
| **合计** | **~49 元** | **~344 元** | 远低于 500 元预算 |

---

## 11. 演进路线

| 阶段 | 时间 | 动作 |
|------|------|------|
| **当前（v0.5.0）** | 2026-07 | Nginx + certbot + DNS-only + COS；账号/LLM/管理后台已上线 |
| **P1** | 2026-08 | `/assets` 完整实现（COS 预签名 + 素材清单）；`/agent/config` 供应商适配参数下发 |
| **P2** | 2026-09 | Cloudflare 橙云切换（如需抗 D / CDN）；或保持现状 |
| **P3** | 2026-10+ | 多 region 评估（新加坡/东京）；CI/CD 流水线；端云共享 Kotlin 模块 `shared/` |

---

## 12. 相关文档

- `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md` — API 契约与实现方案
- `server/README.md` — 模块级快速参考
- `server/AGENTS.md` — 模块规范
- `server/OPENCLAW_DEPLOY.md` — OpenClaw 一键发布指南
- `PRODUCT.md` — 产品需求与路线图

---

> **维护者**：RD Agent
> **最后更新**：2026-08-03
> **状态**：生效中（已上线）
