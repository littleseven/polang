# ADR-017: 后端推理体系架构——推理后端独立成域与双通道一等公民

**状态**: 生效中（2026-10-14 用户评审通过；架构定位决策，无代码变更）
**日期**: 2026-10-14
**决策**: 用户（评审认可）
**依赖**: ADR-005（远程推理协议）、ADR-008（隐私红线）、ADR-013（KMP 契约）
**实现 SSOT**: 服务端 `server/AGENTS.md`；客户端通道抽象 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/remote/config/RemoteModelConfig.kt`

---

## 1. 背景

「端云结合推理」经澄清特指 **Agent 框架推理通道**：两种通道都使用云端厂商 LLM，与端侧媒体推理（VLM 打标/人脸/ASR，见 `ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`）无关。根本上，本项目作为技术实验要建设**完整的后端推理框架和体系**——BYOK 双通道只是其客户端接入形态。

现状两端均已具雏形但缺乏体系化架构契约：

- **服务端**（`server/`）：`LlmProxy` + `ChannelRegistry`（`llm_channel` 表持久化、单活跃渠道 + modelMap 模型映射）、SSE 逐 chunk 透传 + 流尾 usage 解析计费、账号/访客配额 + per-IP 限流、`llm_call_log` 调用日志 + 管理后台渠道 CRUD/用量统计。但定位叙述一直是「AI 网关」附属能力，无独立架构域地位；无 failover/重试/多渠道加权。
- **客户端**（`:shared` commonMain）：`RemoteModelConfig` 已支持 5 个 BYOK 供应商（TokenHub/Kimi/DeepSeek/OpenAI/Anthropic）+ 网关缺省（`PICME_SERVER_DEFAULT`），`AgentOrchestrator` 回退链 `getUserRemoteConfig() ?: PICME_SERVER_DEFAULT`。BYOK 设置 UI 已上线。但「网关通道与 BYOK 通道是什么关系」在架构层从未定义。

## 2. 决策

### 决策 1: 推理后端独立成域

「后端推理体系」（Backend Inference System）升格为与账号体系、管理后台、云端浏览器网关**平级的独立架构域**，以 LLM 推理为核心。职责边界：

| 子域 | 职责 | 现役载体 |
|------|------|----------|
| 接入鉴权 | X-App-Token / X-Device-Id 访客、per-IP 限流、账号/访客配额 | `auth/` + `ratelimit/` + `config/SettingsService` |
| 渠道路由 | 模型 → 渠道映射、单活跃渠道切换 | `llm/ChannelRegistry` + `ChannelRepository` |
| 密钥托管 | 供应商密钥服务端持有、后台脱敏管理 | `llm_channel` 表 + `/admin/channels` |
| 流式代理 | SSE 逐 chunk 透传、max_tokens 封顶、非流式强制整包 | `llm/LlmProxy` + `LlmRoute` |
| 计费用量 | 流尾 usage 解析、成本估算、配额扣减/回滚 | `analytics/TokenUsage` + `UsageRecorder` |
| 可观测 | 调用日志（延迟/平台/渠道/模型维度）、后台统计 | `llm_call_log` + `AdminQueries` |

「AI 网关」从此仅指其中**渠道路由 + 流式代理**两子域，不再是整个推理后端的代称。

### 决策 2: 双通道一等公民（GATEWAY / DIRECT）

客户端存在两条**平等**的推理通道，无主次之分：

| 维度 | 网关通道（GATEWAY） | BYOK 直连通道（DIRECT） |
|------|---------------------|--------------------------|
| 链路 | 端 → api.polang.net → 渠道 → 厂商 | 端 → 厂商 API |
| 密钥 | 服务端托管，端零接触 | 端侧自持（用户自己的 API Key） |
| 会话内容 | 流经 polang server（不落原文，仅 usage 元数据） | polang server **零接触** |
| 配额/计费 | 受 FREE/GUEST 配额约束 | 不受约束（用户与厂商自行结算） |
| 配置形态 | `RemoteModelConfig(gatewayToken/baseUrl=api.polang.net)` | `RemoteModelConfig(apiKey/baseUrl=厂商)` |

两通道共用 `RemoteModelConfig` 抽象与 Koog 编排层，协议同为 ADR-005 决策 1 的标准 OpenAI Chat Completions（Anthropic 走 Koog CLAUDE 协议适配）——**通道选择是纯配置，不是协议分叉**。

**推理通道与服务器能力正交**：账号体系、OTA 自更新、云端浏览器、AI 工程师模式等服务器能力按账号绑定，与推理走哪条通道无关——BYOK 用户可全量使用服务器能力，网关用户也不依赖任何端侧模型。

### 决策 3: 推理域的隐私边界

后端推理体系仅承载**文本/元数据/工具调用流**；用户图片/视频推理 100% 端侧（MNN/ONNX 栈），ADR-008 红线不变、守卫测试不变。云端浏览器属**工具执行通道**（页面数据流经 server 是用户显式发起的动作），不计入推理域、不改变本边界。

### 决策 4: 本期非目标

- **云端多模态推理服务**（VLM/embedding/ASR 上云）：ADR-008 媒体红线 + 香港 VPS 无 GPU 双重不成立；若未来重启须先修订 ADR-008 并单列决策。
- **多渠道加权路由 / failover / 重试**：当前单活跃渠道 + modelMap 够用，列入演进方向。
- **BYOK 密钥端侧加密存储**：当前明文手写 JSON 序列化，列演进方向。

## 3. 为什么不选替代方案

- **不把 BYOK 定位为网关的 fallback**：两者隐私语义根本不同（零接触 vs 托管经转），用户的选择权是一等产品属性而非容灾手段；fallback 定位会让「密钥发给谁」这个事实在 UI 上无法诚实表达。
- **不把推理后端继续叫「AI 网关」**：网关只是路由+代理的实现层，计费/配额/可观测/客户端双通道同属推理体系；名词过小会导致后续演进（failover、预算熔断、能力下发）无处安放。
- **不在本期做云端多模态推理**：红线与硬件现实双重否决，见决策 4。

## 4. 与既有 ADR 的关系

- **ADR-005**：演进不推翻。决策 1（远程推理 = 标准 OpenAI Chat Completions）继续 govern 两条通道；ADR-017 补全其缺失的**通道维度**（网关/直连）与**后端体系视角**。
- **ADR-008**：不变。决策 3 显式重申媒体不出端。
- **ADR-013**：双通道抽象驻留 `:shared` commonMain（`remote/config/`），符合 KMP 契约；iOS 经 ChatAgentBridge 自动同构。
- **ADR-015**：意图路由（LLM 闭集分类）与推理通道正交——路由器的 LLM 调用走当前活跃通道，行为不变。

## 5. 演进方向（非承诺，触发时各开 ADR/spec）

0. **云端 Agent 运行时（框架已定：Koog JVM，2026-10-14 定向；会话状态模型 → ADR-018）**：服务端跑 Koog agent loop，复用 `:shared` 编排层；选型依据——Junie 血统、Ktor/协程原生、单 JVM 进程零新增运维面（Python 系 harness 会撕碎单语言 monorepo）；JVM/Kotlin 侧无成熟开源 harness 可抄（OpenHands 等全在 Py/TS/Rust），harness 层注定自建，可跨语言借鉴 OpenHands 事件溯源三点（事件即状态 / source·role·parent 三维分离 / pending action 不变式）；
1. 渠道 failover / 指数退避重试 / 多渠道加权路由；
2. BYOK 密钥端侧加密存储（Keystore / KMP 安全存储）；
3. `/agent/config` 供应商适配参数下发（现路由表留 🚧 位）；
4. 成本预算熔断（日预算 → 渠道级 → 用户级分级）；
5. 设备分级路由（低端机默认网关通道 + 轻模型）。

## 6. 状态

| 阶段 | 状态 | 日期 |
|------|------|------|
| 架构决策（本文档） | ✅ 生效中（用户评审通过） | 2026-10-14 |
| 代码变更 | 无（现状即符合决策，仅名词与定位升格） | — |
| 云端 Agent 运行时（Koog JVM） | 🎯 框架已定向，会话状态/BYOK 透传待另开 ADR | 2026-10-14 |

## 7. 相关文档

- `server/AGENTS.md` — 服务端实现 SSOT（渠道/配额/后台口径）
- `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md` — API 契约
- `docs/03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md` — 端侧媒体推理全景（与本域的边界）
- `shared/AGENTS.md` — Koog 编排层与远程协议接入坑位
