# PoLang 系统架构 — 证据索引

> 模型快照：2026-09-19（main 分支，工作区含 docs-site 未提交改动，不影响本模型）
> 视图：`polang-system.structurizr.dsl`（system-context / containers / android-components）

## 节点证据

| 节点 | 置信度 | 证据 |
|------|--------|------|
| Android App（:androidApp） | high | `settings.gradle.kts` include；`androidApp/src/main/java/com/mamba/picme/` |
| Compose 功能界面层 | high | `androidApp/.../features/`（camera/chat/gallery/settings/person/search/tagviewer/translation/editor/backuprestore/idphoto） |
| AndroidAgentComposition 组合根 | high | `androidApp/.../agent/AndroidAgentComposition.kt`；AGENTS.md §7 |
| Agent 编排核心（:shared commonMain） | high | `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/`（capability/intent/js/model/platform/remote/runtime/tool） |
| Koog 远程推理层 | high | `shared/.../inference/remote/koog/`（KoogChatAgent.kt、KoogReActAgent.kt、KoogReActStrategy.kt 等）；AGENTS.md §7（JetBrains Koog、OpenAI/Anthropic 双协议分流） |
| QuickJS JS 沙箱 | high | `androidApp/.../features/chat/js/`（QuickJsEngine.kt、CapabilityDispatchHandler.kt）；`docs/03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md` |
| TAG 生成管线 | high | AGENTS.md §7（`androidApp/.../domain/tag/`：TagScanOrchestrator / TagGenerationScheduler / OpenClGuardian） |
| 美颜引擎（beauty-api/beauty-engine） | high | `engines/beauty-engine/src/main/{cpp,java}`；`docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md` |
| 端侧推理（mnn-core/agent-native/sentencepiece） | high | `engines/mnn-core/src/main/{java,jniLibs}`；AGENTS.md §7（libagent_native.so 经 AAR 传递、Qwen3-VL-2B 打标） |
| 本地数据层（Room/DataStore/MediaStore） | high | `androidApp/build.gradle.kts`（room.runtime/ktx/ksp）；AGENTS.md §2.4（polang_llm_log.db） |
| iOS App | high | `iosApp/PoLang/`（Features：Camera/Chat/Gallery/...；SharedBridge/KotlinBridge.swift）；AGENTS.md §7（SwiftUI、SharedKit XCFramework、Metal 4-pass 美颜） |
| PoLang Server | high | `server/src/main/kotlin/com/mamba/picme/server/`（admin/auth/cos/db/issue/llm/ratelimit/recommend/routes）；`docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md` |
| 服务端 DB（Exposed+SQLite+HikariCP） | high | `server/build.gradle.kts`（exposed 0.55.0 注释明示）；`server/.../db/{Db,Migrations,Tables}.kt` |
| OpenAI 兼容 LLM / Anthropic | high | AGENTS.md §7（OpenAILLMClient / AnthropicLLMClient 分流，RemoteModelFactory） |
| IM 远程控制（飞书/Telegram） | high | `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`；AGENTS.md §5 [PRIVACY]（ADR-008 豁免） |
| 腾讯云 COS | high | `server/.../cos/`；SERVER_IMPLEMENTATION_PLAN.md |
| GitHub issue 上报 | high | AGENTS.md §7（/v1/report-issue → littleseven/polang 建 issue）；`server/.../routes/IssueReportRoute.kt` |

## 边证据（关键关系）

| 关系 | 置信度 | 证据 |
|------|--------|------|
| androidApp → LLM 直连（OpenAI/Anthropic 协议） | high | AGENTS.md §7（RemoteModelFactory.createKoogExecutor 分流） |
| androidApp → server（/v1/*） | high | `server/.../routes/`（ClaudeChatRoute、AuthRoute、IssueReportRoute 等）；AGENTS.md §7 |
| server → LLM 网关代理 | high | `server/.../llm/`（LlmProxy.kt、ChannelRegistry.kt、ChannelBalanceService.kt） |
| iosApp → LLM 直连 | medium（图中标 inferred） | iosMain Phase 6.2 复用 shared Koog 远程链路（AGENTS.md §7），但 iOS 侧具体通道配置未见直接代码证据 |
| iosApp → server | medium（图中标 inferred） | 同上，账号/网关路径未在 iosApp 代码中逐条核实 |
| androidApp → IM 通道 | high | IM_REMOTE_CONTROL_TECH_SPEC.md；AGENTS.md §7 |
| server → COS / GitHub | high | server cos/issue 包；AGENTS.md §7 |
| Android 组件间依赖（UI→组合根→编排核心→Koog） | high | AGENTS.md §7 架构说明 + 目录结构 |
| 相机 → 美颜引擎 / TAG → mnn-core | high | AGENTS.md §7；`engines/` 目录 |

## 未知 / 待验证

1. **iOS 网络出口**：iOS Chat 的远程推理是直连 LLM 服务商还是必经 PoLang Server 网关——需读 `iosApp/PoAng/Features/Chat` + `shared/src/iosMain` 的 IosAgentComposition 确认（当前标 inferred）。
2. **iOS 组件级边界**：本次未为 iOS App 出组件图（容器内 SwiftUI 模块与 SharedKit 的细分依赖证据不足，需要时补 L3）。
3. **server → COS 的实际用途面**：cos 包存在，但哪些业务（备份？资源分发？）在用未逐路由核实。
4. **Android spike/ 目录**：`androidApp/.../spike` 为实验代码，未纳入架构图。
