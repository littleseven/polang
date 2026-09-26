# polang Agent 架构设计

> **版本**：4.1（端侧文本 LLM 移除对齐版）  
> **状态**：已实施 / 迭代中  
> **最后更新**：2026-08-03  
> **主要维护者**：项目开发者、AI Agent  
> **历史合并说明**：本文档由 `AGENT_ARCHITECTURE.md` 与 `REMOTE_INFERENCE_ARCHITECTURE.md` 合并而成。远程推理相关的 OpenAI 协议、langchain4j 标准化、DeepSeek 适配、四层模型、性能成本与验收标准已并入“推理模式选型”与“远程推理”章节，原 `REMOTE_INFERENCE_ARCHITECTURE.md` 已删除。

> **边界声明（Boundary Statement）**
> - 本文档定义 Agent 的运行时架构、Capability 模型与推理模式选型。
> - 产品目标与验收口径以 [`../01-PRODUCT/FEATURES.md`](../01-PRODUCT/FEATURES.md) 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 [`AGENTS.md`](../../AGENTS.md) 为准。
> - **重要：原 `:agent-core` Java 基础库已删除**（2026-08 迁移至 JetBrains Koog 外部依赖），Agent 编排层（AgentOrchestrator、CapabilityRegistry、PrivacyGuard、MemoryManager、SceneManager 等）在 `:shared` KMP 模块的 `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/` 目录下（平台实现 androidMain；Android 组合根 `androidApp/src/main/java/com/mamba/picme/agent/AndroidAgentComposition.kt`）。详见 [`MODULE_ARCHITECTURE.md`](MODULE_ARCHITECTURE.md)。

**模块定位**: AI Agent 运行时架构与推理模式选型（基础库 polang + Demo 工程 PoLang）  
**阅读对象**: RD、AI Agent

---

## 目录

1. [核心产品逻辑](#1-核心产品逻辑-core-product-logic)
2. [架构图](#2-架构图)
3. [核心组件设计](#3-核心组件设计)
4. [推理模式选型](#4-推理模式选型)
5. [命令扩展](#5-命令扩展)
6. [数据模型](#6-数据模型)
7. [执行规约](#7-执行规约)
8. [常见陷阱检查清单](#8-常见陷阱检查清单)
9. [验收标准](#9-验收标准)
10. [远程推理任务拆分](#10-远程推理任务拆分-agent-task)
11. [架构演进路线图](#11-架构演进路线图)
12. [附录：参考文档](#12-附录参考文档)

---

## 1. 核心产品逻辑 (Core Product Logic)

### 1.1 红线约束

| 约束 | 定义 | 验证方式 |
|------|------|----------|
| **[PRIVACY]** | 敏感数据优先本地推理；确需云端处理时，必须获得用户授权且不得留存 | PrivacyGuard 拦截数据流 + 授权流程审计 |
| **[PERF]** | 交互反馈 < 100ms，LLM 推理后台完成 | 远程流式首 token 目标 < 500ms |
| **[I18N]** | System Prompt 及用户可见回复禁止硬编码中文 | 接入 string 资源 |
| **[OFFLINE]** | 本地模型未下载时提供明确引导 | 非静默失败 |
| **[TYPE_SAFE]** | AgentCommand / AgentAction 必须使用 Sealed Class | 禁止字符串魔法值 |

### 1.2 当前架构模式（2026-08-02：端侧文本 LLM 移除后）

**文本推理全远程**：chat 与相机指令统一走标准 OpenAI Chat Completions API（原生 tool_calls + 流式 + 多轮对话，ADR-005 远程协议）。  
**端侧仅保留 VLM 打标**：`LocalLlmEngine` 仅存 `imageInference` 用途（Qwen3-VL-2B，TAG Pass3 打标），不再承担任何文本推理。

- 用户输入 → AgentOrchestrator
  - chat/相册 → `streamChat` → KoogChatAgent + ChatToolService → tool_calls → Capability 执行
  - 相机指令 → `processCameraInput` → KoogReActAgent + CameraToolService → tool_calls → Capability 执行（全景见下方路由图）

> 历史：ADR-005 的「本地/远程双链路」（Qwen3.5-2B 端侧推理 + 自定义 JSON 数组协议）已于 2026-08-02 随端侧文本 LLM 一并移除，见本节「已移除组件」与 ADR-005 的「状态更新（2026-08-02）」块（历史 ADR-009/010 已于 2026-08-23 删除，见 [ADR 索引](./ADR/README.md)）。

**核心组件状态**: 

| 组件 | 职责 | 状态 |
|------|------|------|
| `AgentOrchestrator` | 统一入口：chat 走 `streamChat`，相机走 `processCameraInput`，均为远程推理 | ✅ 已落地 |
| `KoogChatAgent` / `KoogReActAgent` / `RemoteChatEngine` | 远程推理链路：OpenAI Chat Completions API（tool_calls·流式·多轮）；chat 走 `RemoteChatEngine` + `KoogChatAgent`，相机走 `KoogReActAgent` | ✅ 已落地 |
| `AgentConfigurator` / Koog `ChatMemory` | 远程推理装配与编排：`createRemoteChatModel()` 构建 Koog `OpenAILLMClient`，多轮记忆由 Koog ChatMemory feature + 组合根注入的 ChatMemoryStore 承担 | ✅ 已落地 |
| `CameraToolService` | 相机场域 @Tool 工具集（capture/adjust_beauty/switch_filter/adjust_zoom/flip_camera 等），相机指令远程 tool_calls 入口 | ✅ 已落地 |
| `LocalLlmEngine` | 仅存 `imageInference`：Qwen3-VL-2B 端侧 VLM 打标（TAG Pass3），不再承担文本推理 | ✅ 已落地（仅打标） |
| `:shared` (KMP 模块) | Kotlin Multiplatform Library，提供 Koog Agent 框架封装：ChatModel、@Tool、AIAgent、ChatMemory、OpenAI Client、流式客户端 | ✅ 已落地 |
| `CapabilityRegistry` | Capability 注册与命令分发 | ✅ 已落地 |
| `PrivacyGuard` | 输入内容隐私分级与本地优先约束 | ✅ 已落地 |
| `MemoryManager` | DataStore 持久化对话历史，按 session 隔离 | ✅ 已落地 |
| `KeywordSpotterEngine` | KWS 常驻低功耗唤醒词检测（Sherpa-ONNX，~14MB） | ✅ 已落地 |
| `SherpaOnnxAsrEngine` | ASR 按需加载语音转录（Sherpa-ONNX，~282MB） | ✅ 已落地 |
| `RemoteChannelManager`（`FeishuChannelHandler` + `TelegramChannelHandler`） | IM 远程控制入口：飞书/Telegram 多通道，消息→AgentCommand，拍照回传·设备绑定·确认 | ✅ 已落地（2026-07-27 解冻，多通道） |

**已移除组件（2026-08-02：端侧文本 LLM 移除）**：
- `LocalInferencePipeline` / `LocalCameraAgent` — 本地推理链路整体删除
- `LocalCommandParser` / `LocalPromptBuilder` — 本地 JSON 数组协议与本地 Prompt 构建器删除
- `local/llm/` langchain4j 适配层 — 随本地文本推理删除
- `IntentCache` / `L1CacheSettings` — L1 意图缓存删除
- `AiAgentMode.LOCAL`（枚举仅剩 OFF/REMOTE/FEISHU）、`AiAgentInferencePreference` 枚举
- chat 页 `ChatModelOption.Local`、qwen3_5_2b 模型下载条目与设置 UI
- 相机 AI 指令改为远程 tool_calls：`AgentOrchestrator.processCameraInput` + `CameraToolService`
- **保留**：`LocalLlmEngine`（仅存 `imageInference` VLM 打标）、`MnnLlmClient`、`llm_jni_bridge.cpp`/`libagent_native.so`、`LocalModelService`、`OpenClGuardian`、`TaggerModelSelector`

**已移除组件（2026-06 协议分离清理，ADR-005；原 ADR-006 已删）**：
- `InferenceRouter` — 拆分为 `LocalInferencePipeline` + `RemoteReActAgent` 两条独立链路（前者已于 2026-08-02 随端侧文本 LLM 移除）
- `ToolCallingChatLanguageModel` — 远程直接使用 langchain4j 原生 `OpenAiChatModel`
- `ToolCallingOutputParser` — 远程使用标准 OpenAI `tool_calls` 响应格式
- `ToolPromptBuilder` — 拆分为 `LocalPromptBuilder` + `RemotePromptBuilder`
- `AdaptiveStrategySelector` — 本地不再需要策略分级
- `ToolOrchestrator` — 编排逻辑合并入 `RemoteReActAgent`
- `SherpaMnnAsrEngine` + `com.k2fsa.sherpa.mnn.*` — 已迁移至 Sherpa-ONNX（`SherpaOnnxAsrEngine`）
- `MnnAsrClient` — 占位死代码，同步移除
- 共清理 ~2,600 行冗余代码

---

## 2. 架构图

### 2.1 系统全景架构

![Agent 运行时全景分层](assets/diagrams/agent-runtime-stack.png)

### 2.2 推理链路数据流（2026-08-02：文本推理全远程）

![AgentOrchestrator 路由](assets/diagrams/agent-orchestrator-routing.png)

### 2.3 语音交互管线（Sherpa-ONNX 双引擎）

![语音唤醒生命周期](assets/diagrams/voice-wakeup-lifecycle.png)

### 2.4 能力访问链路：tool_call 为主，JS 是 run_gallery_script 的内部实现

> **一句话心智模型**（本节是 LLM 路由的权威说明，修正 2.2 中 `RemoteOrchestrator` 等已过时表述）：
> 对外，LLM 只有一种调用方式 —— **tool_call（`@Tool`）**；`run_gallery_script` 只是其中一个"参数为 JS 源码"的特殊工具，**JS 沙箱不是与 tool_call 平级的第二条链路**，而是该工具的执行体。
> 对内，所有 tool 最终收敛到 `CapabilityRegistry.dispatch(AgentCommand)`；唯一旁路是飞书 RPA 的 UI 自动化（操作无障碍树，非语义命令）。

![tool_calls 收敛结构](assets/diagrams/toolcalls-convergence.png)

#### 2.4.1 三条 LLM 入口与收敛

| 入口 | 触发场景 | 协议 | 收敛点 |
|---|---|---|---|
| 远程 Chat ReAct（`ChatToolService`） | CHAT 场景（相册助理）；`streamChat` 固定走此 | OpenAI `tool_calls` | 每个 `@Tool` → `dispatchCommand(AgentCommand)` → `CapabilityRegistry` |
| 远程飞书 RPA（`RemoteControlToolService`） | 飞书 IM 远程控制（`processRemoteImInput`） | OpenAI `tool_calls` | 语义工具 → `CapabilityRegistry`；UI 工具（click/scroll/input）→ Accessibility（旁路） |
| 相机远程 tool_calls（`CameraToolService`） | CAMERA 场景的语音/文字指令（`processCameraInput` → `KoogReActAgent`） | OpenAI `tool_calls` | 每个 `@Tool` → `dispatchCommand(AgentCommand)` → `CapabilityRegistry`（写操作复用 CommandRisk/确认机制与 JS `capability.dispatch` 通路） |

> `streamChatLocal` / `streamChatRemote`（曾与 ReAct 并存的文本协议路径）已删除；CHAT 场景统一走远程 ReAct（ADR-005 远程协议分离）。

#### 2.4.2 两个"能力表面"及其关系

存在两套并行的能力描述面，**语义部分重叠**，需对照维护（映射表见 `docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`「JS 沙箱能力表面」）：

- **AgentCommand 表面（SSOT）**：`AgentCommand` sealed class → `CapabilityRegistry` → `Capability`。承载所有语义命令（搜索/编辑/打标/记忆/设置/导航/媒体写）。**三条 LLM 入口都汇此**。
- **JsBridge handler 表面**：`gallery.* / media.* / face.* / tag.*`（`GalleryScriptHandlers`，直连 `QueryGalleryMediaUseCase` 等，**不经注册表**）。仅 `run_gallery_script` 执行的 JS 内部可见。

`run_gallery_script` 是两表面的桥梁：JS 内 `bridge.callAsync('gallery.query')` 走 handler 表面（只读取数）；`bridge.callAsync('capability.dispatch',{method,params})` 回环到 AgentCommand 表面（写操作）。

#### 2.4.3 LLM 选路决策矩阵

| 意图 | 用哪个 tool | 底层路径 |
|---|---|---|
| 单一搜索 / 摘要 / 修图 / 打标 / 记忆 / 设置 / 导航 | 对应单一 `@Tool` | tool → AgentCommand → Capability |
| 多维组合查询 / 趋势 / 占比 / 统计 / 数学计算 | `run_gallery_script`（取数）+ `draw_chart`（画图） | tool → JS 沙箱 → `gallery.*` 读 / `capability.dispatch` 写 |
| 飞书 IM 远程操控 UI（点按 / 输入 / 滑动） | `click` / `scroll` / `input_text` | tool → Accessibility（旁路，不进注册表） |

判定边界（已固化于 chat system prompt 行为规则段 `ChatPromptRules`，由 `RemoteChatEngine.buildChatSystemPrompt` 拼装）：单一维度用独立 tool；≥2 维度组合 / 趋势 / 占比 / 计算，才用 `run_gallery_script`。**`run_gallery_script` / `draw_chart` 仅 chat 场景（`ChatToolService`）暴露**——相机工具集（`CameraToolService`）不含 JS 工具。

#### 2.4.4 意图理解：意图路由器（Intent Router）+ IntentGuard 确定性护栏

> 2026-09-26 起实施《意图路由契约与意图路由器》（spec：`docs/superpowers/specs/2026-09-25-intent-routing-contract-design.md`，决策记录 ADR-015）。核心分工：**LLM 管语义理解（输出意图），代码管路由策略（查表执行）**。

chat 入口（`RemoteChatEngine.streamChat`）在 agent loop 之前挂 **IntentRouter**（`:shared` commonMain `agent/core/intent/`），三层判定：

1. **本地信号门控**（`IntentRouterCore.shouldRoute`）：相册域关键词命中才进路由，寒暄/开放问答直通 OPEN_QA，零额外延迟；只放行不拦截（误判代价 = 多走一次全量 agent，与旧行为同）。
2. **pattern 捷径**：「看/找/搜…照片」最热句式零延迟短路；负面语素（有没有/多少/画/糊…）显式排除，防捷径劫持分析/计数/画图语义。
3. **LLM 闭集分类**：专用小 prompt（~1k token、temperature=0、JSON 强校验）输出 `RouterOutput`（deliverable 意图 + confidence + isRefinement + 槽位），1.5s 硬超时 + schema 重试 1 次 + 低置信（<0.6）降级——一切失败同路回落完整 agent loop，**路由器永不拦截能力**。

路由策略层 **ChatRoutingPolicy**（纯函数查表）：仅 VIEW_PHOTOS / REFINE_RESULTS 直执——经 `ChatToolService.dispatchCommandDetailed` 直发 `SearchMedia`/`RefineMediaSearch`（与 LLM tool_calls 同一 dispatch 链路，uiActions/observation/5s 超时同源）；dispatch 失败/业务错误回落完整 agent loop；直执不产 LLM 总结，结果以 `DirectRouteReply` 结构化回执返回、用户气泡由平台层本地化渲染（observation 模型侧文案不直达用户），该轮 user+observation 补写 Koog 会话记忆防多轮指代断裂；refine 基数缺失自动退化为 fresh SearchMedia；secondary 双产出物与其余意图一律回落完整 agent。意图→工具/UI 的声明式契约表为 **ChatIntentContract**（allowed∩forbidden=∅ 由 commonTest 机器校验，VIEW_PHOTOS 显式禁 `run_gallery_script`——消除「脚本拿 ids 谎称已展示」事故链）。

每回合路由判定（含门控直通/降级）落 `polang_llm_log.db` 的 `routing_audit_log` 表（`RoomRoutingAuditRecorder`，仅路由维度指标、不含用户 query 原文），路由器 LLM 调用落 `llm_call_log`（source=`chat-intent-router`）。

LLM 之外的确定性护栏仍收口在 `agent/core/intent/IntentGuard`（纯函数、可单测、双端可复用），只做保守的误伤修正、不替代语义理解：

- **误拒回退**：`isRefusedSearchRequest` 检测 LLM 安全对齐误拒相册搜索 → 调用方回退直搜本地相册（Android 接线：`ChatViewModel`）。
- **模糊跳转拦截**：`sanitizeNavigationCommands` 在 chat 页把非明确口令（`isExplicitNavigationRequest`）触发的 navigate_to / go_back 替换为文本提示，拦截文案由平台侧按 i18n 注入。

#### 2.4.5 写操作确认两层策略

同一写命令可由两条链路触发，**确认手段不同是刻意设计**（风险分级 SSOT：`CommandRisk`）：

- **Tier A — JS 沙箱内 `capability.dispatch`**：JS 可由 `gallery.query` 计算出大批量 id 批量删除，风险高 → dispatch 前经应用内确认（`WriteConfirmationController`，带缩略图预览，仅脚本生命周期内有效，防孤儿确认）。
- **Tier B — 顶层 `@Tool` 直调（`delete_media` 等）**：ReAct 循环对用户透明，删除另由系统 MediaStore 授权框兜底，不重复应用内确认。

两条链路最终都汇聚到 `ChatMediaWriteCapability`（CHAT 场景媒体写执行点）执行；差异仅在"确认发生在 dispatch 前（Tier A）还是依赖系统授权（Tier B）"。

---

### 2.5 Chat 双模式架构：普通助手 vs AI 工程师

Chat 页通过输入栏的 **AI 工程师** toggle 在两条完全独立的 LLM 链路间切换。两条链路的目标、LLM、上下文、工具与交付物均不同。

#### 2.5.1 普通 Chat 链路（相册助手）

![Chat 消息链路](assets/diagrams/chat-message-flow.png)

**普通 Chat 的 LLM 感知：**
- 输入：当前用户消息 + 多轮对话历史 + 被动注入的记忆快照 + 可选图片。
- 能力：通过 `@Tool` 调用端侧 Capability，操作相册、编辑图片、运行 JS 分析脚本。
- 隐私：敏感操作（人脸/OCR/图片）由 `PrivacyGuard` 强制本地；非敏感复杂推理可上云。
- 交付物：文本回复、媒体结果、编辑结果图、图表 SVG。

#### 2.5.2 AI 工程师链路（远程 coding agent）

![AI 工程师链路](assets/diagrams/engineer-claude-chain.png)

**AI 工程师的 LLM 感知：**
- 输入：当前用户消息 + 完整代码库（KimiClaw workdir）+ App 运行时数据（经 MCP 工具按需拉取）。
- 能力：Bash 跑 gradle/测试、Edit 改文件、MCP app tools 感知 App 状态。
- 隐私：不触碰用户图片/视频；日志/聊天历史/相册摘要经 `DiagSanitizer` 脱敏后回传。
- 交付物：代码改动 + git 分支 / PR / main merge 结果。

#### 2.5.3 核心差异对比

| 维度 | 普通 Chat | AI 工程师 |
|---|---|---|
| 入口 | ChatScreen 输入栏 | ChatScreen「AI 工程师」toggle |
| 目标 | 操作相册、回答问题 | 读改代码、诊断修复、推分支 |
| ViewModel 方法 | `sendMessage()` | `sendClaudeMessage()` |
| LLM | DeepSeek（远程，端侧文本 LLM 已移除） | Claude Code (GLM backend) |
| 网络路径 | App → PoLang Server → 第三方 LLM | App → PoLang Server → chisel → KimiClaw → Claude |
| 上下文 | 会话历史 + 记忆 + 相册元数据 | 完整代码库 + App 运行时状态 |
| 工具协议 | `@Tool` / OpenAI tool_calls | Claude Code 原生工具 + MCP app_tools |
| 执行位置 | 端侧 Capability | KimiClaw 云主机 workdir |
| 媒体输入 | 可发送图片（ADR-008） | 禁止发送图片 |
| 交付物 | 文本/图片/命令结果 | 代码改动 + 分支/PR |

#### 2.5.4 用户问题上报

设置页「其他」分组的「上报问题」入口（2026-09-26 自 Chat 顶部栏迁入），与 AI 工程师链路独立：

![问题上报链路](assets/diagrams/report-issue-flow.png)

- 上报内容为文本描述与脱敏后的运行信息，不触碰用户图片/视频（[PRIVACY] 红线）。
- 创建的问题在管理后台「问题诊断」页（`/admin/diagnosis`）可见，供 AI 工程师链路后续诊断与修复。

---

## 3. 核心组件设计

### 3.1 SceneManager 场景管理器

```kotlin
/**
 * 场景管理器
 * 
 * 负责：
 * 1. 跟踪当前活跃场景
 * 2. 根据场景获取对应的 Capability 集合
 * 3. 动态构建场景相关的 system prompt
 */
class SceneManager {
    
    enum class Scene {
        GALLERY,     // 相册首页（默认）
        CHAT,        // AI 对话二级页
        CAMERA,      // 相机页
        SETTINGS,    // 设置页
        EDITOR,      // 编辑页
        DEBUG        // 调试页
    }
    
    private val _currentScene = MutableStateFlow(Scene.GALLERY)
    val currentScene: StateFlow<Scene> = _currentScene.asStateFlow()
    
    fun transitionTo(scene: Scene) {
        _currentScene.value = scene
    }
    
    /**
     * 获取场景对应的 Capability 列表
     */
    fun getCapabilitiesForScene(scene: Scene): List<String> = when (scene) {
        Scene.GALLERY -> listOf("gallery", "editor", "navigation")
        Scene.CHAT -> listOf("chat", "navigation", "gallery", "editor")
        Scene.CAMERA -> listOf("camera", "navigation")
        Scene.SETTINGS -> listOf("settings", "navigation")
        Scene.EDITOR -> listOf("edit", "navigation")
        Scene.DEBUG -> listOf("navigation")
    }
}
```

### 3.2 Prompt 构建器（远程）

端侧文本 LLM 移除后（2026-08-02），`LocalPromptBuilder` 已删除，仅保留远程 Prompt 构建（ADR-005 的本地/远程双构建器划分成为历史）：

```kotlin
// 远程 Prompt — 标准 OpenAI 协议格式
// 通过 langchain4j 构建 ChatRequest，SDK 自动序列化为 OpenAI Chat Completions 请求体
class RemotePromptBuilder {
    fun buildChatRequest(
        systemPrompt: String,
        userInput: String,
        capabilities: List<Capability>
    ): ChatRequest {
        val toolSpecs = capabilities.map { cap ->
            ToolSpecification.builder()
                .name(cap.name)
                .description(cap.description)
                .build()
        }
        return ChatRequest.builder()
            .messages(
                SystemMessage(systemPrompt),
                UserMessage(userInput)
            )
            .toolSpecifications(toolSpecs)
            .build()
        // langchain4j 自动序列化为:
        // POST /v1/chat/completions
        // {"model":"...","messages":[...],"tools":[...]}
    }
}
```

#### 3.2.1 被动记忆注入（chat + 飞书，2026-07）

Koog agent（`KoogChatAgent` / `KoogReActAgent`）的 system prompt 在 agent 构建期拼接，在固定 system prompt 后追加 `MemoryContextProvider.snapshot()` 返回的【关于用户】快照（已记住的事实 + 与"我"的人物关系）。快照由 app 层 `MemoryContextProviderImpl` 用 Room Flow（`observeAllFacts` + `observeRelationsToSelf`）预热 `@Volatile` 缓存，按 ~1500 字符预算截断、超出用 `recall_memory` 兜底。chat 与飞书 agent 共用同一份设备本机记忆（设计稿已随交付清理，git 历史可查）。

### 3.3 Capability 接口扩展

```kotlin
/**
 * Capability 接口 V2
 * 
 * 新增：
 * - 场景绑定：声明该 Capability 活跃的场景
 * - 上下文感知：接收页面特定的上下文数据
 */
interface Capability {
    val name: String
    val description: String
    
    /**
     * 该 Capability 在哪些场景下可用
     */
    fun activeScenes(): List<SceneManager.Scene>
    
    fun supportedCommands(): List<String>
    
    /**
     * 执行命令
     * @param command 解析后的命令
     * @param context 当前上下文
     * @param pageContext 页面特定上下文（如 Gallery 当前选中的照片）
     */
    suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext? = null
    ): Result<AgentAction>
}

/**
 * 页面上下文（页面特定状态）
 */
sealed class PageContext {
    data class GalleryContext(
        val currentMedia: MediaAsset?,
        val selectedItems: List<MediaAsset>,
        val isSelectionMode: Boolean
    ) : PageContext()
    
    data class SettingsContext(
        val currentCategory: String?
    ) : PageContext()
    
    data class EditorContext(
        val editingMedia: MediaAsset,
        val hasUnsavedChanges: Boolean
    ) : PageContext()
    
    object None : PageContext()
}
```

### 3.4 GalleryCapability 示例

```kotlin
/**
 * 相册控制 Capability
 */
class GalleryCapability(
    private val onViewMedia: ((MediaAsset) -> Unit)? = null,
    private val onDeleteMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onShareMedia: ((List<MediaAsset>) -> Unit)? = null,
    private val onSelectMedia: ((MediaAsset, Boolean) -> Unit)? = null,
    private val onSearch: ((String) -> Unit)? = null,
    private val onSwitchViewMode: ((ViewMode) -> Unit)? = null
) : Capability {
    
    override val name = "gallery"
    override val description = "查看、删除、分享、搜索照片和视频"
    
    override fun activeScenes() = listOf(SceneManager.Scene.GALLERY, SceneManager.Scene.CHAT)
    
    override fun supportedCommands() = listOf(
        "view_media",
        "delete_media",
        "share_media",
        "select_media",
        "search_media",
        "switch_view",
        "text_reply"
    )
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        val galleryContext = pageContext as? PageContext.GalleryContext
        
        return when (command) {
            is AgentCommand.ViewMedia -> {
                val media = command.mediaId?.let { findMediaById(it) }
                    ?: galleryContext?.currentMedia
                media?.let { onViewMedia?.invoke(it) }
                Result.success(AgentAction.Success(command))
            }
            
            is AgentCommand.DeleteMedia -> {
                val items = command.mediaIds.mapNotNull { findMediaById(it) }
                    .ifEmpty { galleryContext?.selectedItems ?: emptyList() }
                onDeleteMedia?.invoke(items)
                Result.success(AgentAction.Success(command))
            }
            
            else -> Result.success(AgentAction.Error("不支持的命令"))
        }
    }
}
```

### 3.5 NavigationCapability 示例

```kotlin
/**
 * 导航 Capability
 * 
 * 在所有场景都可用，负责页面切换
 */
class NavigationCapability(
    private val onNavigate: (Screen) -> Unit,
    private val onBack: () -> Unit
) : Capability {
    
    override val name = "navigation"
    override val description = "页面导航：切换页面、返回上一页"
    
    override fun activeScenes() = SceneManager.Scene.entries.toList()
    
    override fun supportedCommands() = listOf(
        "navigate_to",
        "go_back",
        "text_reply"
    )
    
    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return when (command) {
            is AgentCommand.NavigateTo -> {
                val screen = when (command.destination.lowercase()) {
                    "camera", "相机" -> Screen.Camera
                    "gallery", "相册" -> Screen.Gallery
                    "settings", "设置" -> Screen.Settings
                    "editor", "编辑" -> Screen.Editor
                    "chat", "聊天" -> Screen.Chat
                    else -> return Result.success(AgentAction.Error("未知页面：${command.destination}"))
                }
                onNavigate(screen)
                Result.success(AgentAction.Success(command))
            }
            
            is AgentCommand.GoBack -> {
                onBack()
                Result.success(AgentAction.Success(command))
            }
            
            else -> Result.success(AgentAction.Error("不支持的导航命令"))
        }
    }
}
```

---

## 4. 推理模式选型

### 4.1 文本推理全远程，端侧仅 VLM 打标（2026-08-02 最终状态）

**最终决策**：端侧文本 LLM（Qwen3.5-2B）已完全移除。chat 与相机指令统一走远程 OpenAI Chat Completions（tool_calls），与 ADR-005 远程协议一致；相机链路为 `AgentOrchestrator.processCameraInput` → `KoogReActAgent` + `CameraToolService`（相机场域 @Tool 工具集）→ @Tool 方法直接构造 `AgentCommand` → `CapabilityRegistry.dispatch`（`ToolCallCommandParser` 已随 Phase 5 Koog 迁移删除），写操作复用 CommandRisk/确认机制与 JS `capability.dispatch` 通路。

**端侧保留**：仅 Qwen3-VL-2B VLM 打标（`LocalLlmEngine` 仅存 `imageInference`，TAG Pass3）、Florence-2 打标、人脸检测（`:engines:mnn-core`）、OPUS-MT 翻译（`:engines:sentencepiece`）——均为媒体/视觉处理，不承担文本对话与指令解析。

**演进脉络**：ADR-005（本地/远程协议分离，2026-06）→ 本地收缩与链路隔离（2026-07~08，相关 ADR-009/010 已删除，历史见 git）→ 2026-08-02 端侧文本 LLM 移除、本地链路整体删除（commit 2fb1f299e）→ 2026-08 langchain4j fork 迁移 Koog、`:agent-core` 删除（commit 1cbe9353）。

**历史对比（ADR-005 时期，本地列已删除）**：

| 维度 | 本地推理（已删除） | 远程推理（现状唯一链路） |
|------|---------|---------|
| **协议** | 自定义 JSON 数组 | 标准 OpenAI Chat Completions API |
| **Library** | 无第三方依赖 | Koog (OpenAILLMClient) |
| **Prompt** | 精简、结构化 | 自然语言 + Tool Schema |
| **输出解析** | 简单 JSON 数组解析 | 标准 JSON 反序列化（tool_calls） |
| **约束方式** | JSON 数组格式 Prompt 约束 | OpenAI 原生协议约束 |
| **聊天/闲聊** | 通过 text_reply 命令兜底 | 原生支持（流式 + 多轮） |
| **Strategy** | L1 Cache / L2 Batch | L2 Batch / L3 Plan / L4 Chat |
| **延迟** | < 600ms | 500ms-2s |
| **隐私** | 敏感数据本地处理 | 媒体文件不出端；文本/元数据可远程（[PRIVACY]） |

### 4.2 端侧推理现状：仅 VLM 打标（原 Qwen3.5-2B 选型已废止）

端侧不再运行任何文本 LLM。保留的端侧推理均为视觉/媒体处理：

| 引擎 | 用途 | 模块 |
|------|------|------|
| Qwen3-VL-2B（MNN-VLM） | TAG Pass3 图像打标（`LocalLlmEngine.imageInference`） | `:shared`（androidMain）+ `:engines:agent-native` + `:engines:mnn-core` |
| Florence-2 | 图像打标 | `:androidApp` 打标流水线 |
| MNN 人脸检测 | 人脸检测/关键点 | `:engines:beauty-engine` + `:engines:mnn-core` |
| NIMA / eDifFIQA（ONNX，NNAPI 加速） | 人物封面美学/人脸质量打分（`NimaScorer`/`EdiffiqaScorer`/`CoverSelector`） | `:androidApp` `domain/aesthetic/` |
| OPUS-MT（SentencePiece） | 翻译（与 LLM 无关） | `:engines:sentencepiece` |

**原 Qwen3.5-2B 端侧文本推理选型（历史记录，2026-06-12 验证，已随模型删除而废止）**：

- 不推荐完整 ReAct：2B 模型 COT（链式思考）能力弱，Thought 质量不稳定；AI 对话页 < 500ms 首字延迟的 `[PERF]` 红线多轮推理无法满足；端侧电池/发热敏感。
- 端侧能力边界：单条 Function Calling 已验证（准确率 > 90%）；简单 Batch FC（2-3 条）部分支持；复杂组合指令、上下文推理、开放式闲聊不支持——这些短板正是最终移除端侧文本 LLM、全面转向远程 tool_calls 的原因。

### 4.3 远程推理模式选型

远程模式下**减少 LLM 调用次数**比端侧更重要（RTT 成本主导）。

**推荐分层自适应模式**：

| 层级 | 模式 | 适用场景 | 协议 | 输出格式 | 执行位置 |
|------|------|---------|------|---------|---------|
| Layer 1 | 本地规则缓存（已随 `IntentCache` 移除，2026-08-02） | — | — | — | — |
| Layer 2 | Batch Function Calling | 简单连续动作指令（2-3 步） | OpenAI Chat Completions + tool_calls | `ToolExecutionRequest[]` → `AgentCommand[]` | 远程 |
| Layer 3 | Plan-and-Execute | 条件/依赖/多步骤 | OpenAI Chat Completions + tool_calls | `ExecutionPlan` (含 command 字段) | 远程规划 + 本地执行 |
| Layer 4 | 流式 Chat | 开放式对话、闲聊 | OpenAI Chat Completions (stream=true) | 文本流 + 可选 tool_calls | 远程 |

**远程优化策略**：
- 连接池 + Keep-Alive 复用 TCP
- 100ms 防抖窗口合并请求
- 2s 超时降级为文本提示（本地规则兜底已随本地链路移除）
- 常见意图响应缓存（LruCache）
- **隐私分级**：媒体文件（图片/视频）不出端（[PRIVACY] 红线）；文本/元数据/相册摘要允许远程推理

### 4.4 远程推理协议实现

远程推理使用标准 OpenAI Chat Completions API 格式：

**请求格式**：
```json
POST /v1/chat/completions
{
  "model": "deepseek-chat",
  "messages": [
    {"role": "system", "content": "..."},
    {"role": "user", "content": "帮我优化这张照片"}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "ai_optimize",
        "description": "AI 一键优化图片",
        "parameters": {
          "type": "object",
          "properties": {...},
          "required": [...],
          "additionalProperties": false
        }
      }
    }
  ],
  "tool_choice": "required",
  "stream": false
}
```

**响应格式（tool_calls）**：
```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_xxx",
        "type": "function",
        "function": {
          "name": "ai_optimize",
          "arguments": "{\"image_id\": \"img_123\"}"
        }
      }]
    }
  }]
}
```

**关键规则**：
- `tool_calls` 是 `message` 对象的独立字段，与 `content` 互斥
- 当存在 `tool_calls` 时，`content` 必须为 `null`
- 参数通过 `function.arguments` 传递，为标准 JSON 字符串

**Koog 标准化实现**：
```kotlin
class RemoteChatEngine(config: RemoteModelConfig) {  // 实际由 AgentConfigurator.createRemoteChatModel() 装配
    // Koog OpenAILLMClient 驱动，经 RemoteModelFactory 注入 additionalProperties
    fun chat(request: ChatRequest): ChatResponse {
        // Koog AIAgent + ChatMemory 支持 tool_calls、流式、多轮
    }
}
```

远程推理使用 Koog `OpenAILLMClient`，支持所有兼容 OpenAI API 的服务（DeepSeek、通义千问等）；通过 Koog `AIAgent` + `ChatMemory` 实现多轮对话。

**命令解析（@Tool 方法内联构造，2026-08 Phase 5 起）**：

中心化解析器 `ToolCallCommandParser`（langchain4j 期，`ToolExecutionRequest` + JSON args → `AgentCommand` 的巨型 when）已随 Phase 5 Koog 迁移删除。Koog 下 LLM 的 tool_calls 由 Koog agent 循环直接派发到 `@Tool` 方法，类型化参数在方法签名就位，每个 `@Tool` 方法一行薄封装构造 `AgentCommand`：

```kotlin
@Tool(customName = "switch_filter")
suspend fun switchFilter(filter: String): String =
    dispatchCommand(AgentCommand.SwitchFilter(filterType = parseFilterType(filter)))
```

- `@Tool` 方法 — 类型化参数 → `AgentCommand`（chat `ChatToolService` 与相机 `CameraToolService` 各自场域工具集，同一薄封装模式）

### 4.5 DeepSeek 适配

| 适配项 | 实现 | 位置 |
|--------|------|------|
| 禁用 thinking | API 请求自动附加 `thinking: {"type": "disabled"}` | `OpenAiChatModel` 内部处理 |
| strict 模式兼容 | ToolSpec 自动添加 `additionalProperties: false` | `OpenAiChatModel` 内部处理 |
| tool_choice 修复 | `REQUIRED` 正确映射为 `"required"`（非 `"auto"`） | `OpenAiChatModel` 内部处理 |
| thinking 禁用（避免 content 回退） | DeepSeek 开启 thinking 会使 content 为空、tool_calls 缺失；请求自动附加 `thinking.type=disabled` 使模型直接返回标准 tool_calls，无需 content 回退解析 | `RemoteModelFactory` |
| Prompt 规范 | 禁止在 Prompt 中提供具体 tool_calls JSON 示例，避免模型输出到 content | `RemotePromptBuilder` |

### 4.6 性能与成本考量

**Token 消耗估算**：

| 模式 | System Prompt | User Input | Output | 单次总 Token |
|------|--------------|-----------|--------|-------------|
| L1 Cache | 0 | 0 | 0 | 0 |
| L2 Batch | ~800 | ~50 | ~200 | ~1050 |
| L3 Plan | ~1000 | ~100 | ~500 | ~1600 |
| L4 Chat | ~800 | ~50 | ~300 | ~1150 |

**延迟估算**：

| 模式 | 网络 RTT | LLM 生成 | 解析 | 总延迟 |
|------|---------|---------|------|-------|
| L1 | 0 | 0 | 0 | < 10ms |
| L2 | 200-500ms | 200-500ms | 50ms | 450-1050ms |
| L3 | 200-500ms | 500-1000ms | 100ms | 800-1600ms |
| L4 (流式) | 200-500ms | 首 token 50-200ms | 50ms | 250-750ms |

**优化策略**：
1. **L1 缓存预热**：（已废止，2026-08-02 — `IntentCache` 随端侧文本 LLM 移除）
2. **L2 默认化**：80% 场景走 L2，保持简单高效
3. **L3 异步执行**：计划生成后异步执行，不阻塞 UI
4. **L4 流式**：首 token 低延迟，提升对话体验
5. **连接池 + Keep-Alive**：复用 TCP 连接

---

## 5. 命令扩展

```kotlin
/**
 * Agent 命令 V2
 * 
 * 新增 Gallery、Settings、Navigation、Edit 相关命令
 */
sealed class AgentCommand {
    // ===== 相机命令（已有）=====
    data class AdjustBeauty(val settings: BeautySettings) : AgentCommand()
    data class SwitchFilter(val filterType: FilterType) : AgentCommand()
    // ... 其他相机命令
    
    // ===== Gallery / Chat 相册搜索命令（新增）=====
    data class ViewMedia(val mediaId: String? = null) : AgentCommand()
    data class DeleteMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()
    data class ShareMedia(val mediaIds: List<String> = emptyList()) : AgentCommand()
    data class SelectMedia(val mediaId: String, val selected: Boolean) : AgentCommand()
    /**
     * 搜索媒体
     *
     * @property query 原始查询文本，必填；用于展示与语义召回兜底。
     * @property intent 可选的标准化搜索意图。当 LLM 能可靠拆出时间/关键词/地点/人物时填充，
     *                  下游可直接用结构化过滤执行精确 Room 查询；为 null 时退回到字符串解析。
     */
    data class SearchMedia(
        val query: String,
        val intent: SearchIntent? = null
    ) : AgentCommand()

    /**
     * 细化上一轮相册搜索结果（in-set 过滤）。
     */
    data class RefineMediaSearch(
        val constraint: String,
        val intent: SearchIntent? = null
    ) : AgentCommand()

    data class SwitchViewMode(val mode: ViewMode) : AgentCommand()
    
    // ===== 设置命令（新增）=====
    data class ChangeTheme(val theme: ThemeMode) : AgentCommand()
    data class ChangeLanguage(val language: AppLanguage) : AgentCommand()
    data class DownloadModel(val modelId: String) : AgentCommand()
    data class SwitchFaceEngine(val engine: FaceDetectionEngineMode) : AgentCommand()
    data class ToggleSetting(val settingKey: String, val enabled: Boolean) : AgentCommand()
    
    // ===== 导航命令（新增）=====
    data class NavigateTo(val destination: String) : AgentCommand()
    object GoBack : AgentCommand()
    
    // ===== 编辑命令（新增）=====
    data class ApplyEdit(val editType: String, val params: Map<String, Any>) : AgentCommand()
    object SaveEdit : AgentCommand()
    object UndoEdit : AgentCommand()
    
    // ===== 通用命令 =====
    data class TextReply(val message: String) : AgentCommand()
    data class Unknown(val raw: String) : AgentCommand()
    data class Error(val reason: String) : AgentCommand()
}
```

### 5.1 功能覆盖矩阵

#### 当前已接入功能（已验证）

| 功能域 | 具体功能 | 命令类型 | Capability | 状态 |
|--------|----------|----------|------------|------|
| **相机控制** | 拍照 | `CapturePhoto` | CameraCapability | 已验证 |
| | 开始/停止录像 | `ToggleRecording` | CameraCapability | 已验证 |
| | 翻转摄像头 | `FlipCamera` | CameraCapability | 已验证 |
| | 变焦调节 | `AdjustZoom` | CameraCapability | 已验证 |
| | 曝光调节 | `AdjustExposure` | CameraCapability | 已验证 |
| | 切换拍摄模式 | `SwitchMode` | CameraCapability | 已验证 |
| **美颜** | 磨皮/美白调节 | `AdjustBeauty` | CameraCapability | 已验证 |
| | 瘦脸/大眼调节 | `AdjustBeauty` | CameraCapability | 已验证 |
| | 唇色/腮红调节 | `AdjustBeauty` | CameraCapability | 已验证 |
| **滤镜/风格** | 切换滤镜 | `SwitchFilter` | CameraCapability | 已验证 |
| | 切换风格特效 | `SwitchStyle` | CameraCapability | 已验证 |
| | 切换场景模式 | `SwitchScene` | CameraCapability | 已验证 |
| | 切换画幅比例 | `SwitchRatio` | CameraCapability | 已验证 |
| **对话** | 文本回复/聊天 | `TextReply` | CameraCapability | 已验证 |
| **相册搜索** | 自然语言搜照片（含 LLM 意图标准化） | `SearchMedia` | GalleryCapability / ChatSearchCapability | 已验证 |
| **相册搜索** | 多轮结果细化（in-set 过滤） | `RefineMediaSearch` | ChatSearchCapability | 已验证 |
| **远程控制** | 飞书/Telegram 消息处理 | 多种 | RemoteChannelManager（RemoteControlCapability 存在但未注册） | ✅ 已落地 |

#### V2 新增功能（开发中）

| 功能域 | 具体功能 | 命令类型 | Capability | 优先级 |
|--------|----------|----------|------------|--------|
| **Gallery** | 查看照片 | `ViewMedia` | GalleryCapability | P0 |
| | 删除照片 | `DeleteMedia` | GalleryCapability | P0 |
| | 分享照片 | `ShareMedia` | GalleryCapability | P1 |
| **设置** | 切换主题 | `ChangeTheme` | SettingsCapability | P1 |
| | 切换语言 | `ChangeLanguage` | SettingsCapability | P1 |
| **导航** | 切换页面 | `NavigateTo` | NavigationCapability | P0 |
| | 返回上一页 | `GoBack` | NavigationCapability | P0 |
| **编辑** | 进入编辑 | 预留 | ImageEditCapability | P2 |

---

## 6. 数据模型

### 6.1 AgentCommand 密封类

见 [第 5 章](#5-命令扩展)。

新增的标准化搜索意图模型（位于 `:shared` commonMain）：

```kotlin
data class SearchIntent(
    val query: String,
    val timeRange: TimeRange? = null,
    val keywords: List<String> = emptyList(),
    val ocrKeywords: List<String> = emptyList(),
    val locationKeywords: List<String> = emptyList(),
    val personName: String? = null,
    val hasFaces: Boolean? = null
)

data class TimeRange(
    val startMs: Long,
    val endMs: Long
)
```

`SearchIntent` 由 LLM 在解析 `SearchMedia` / `RefineMediaSearch` 时生成，用于把“近半年”“去年”等相对时间词直接转换为绝对时间戳，避免规则解析遗漏或错误。

### 6.2 推理结果包装

```kotlin
sealed class InferenceResult {
    data class Local(val command: AgentCommand) : InferenceResult()
    data class Remote(val commands: List<AgentCommand>) : InferenceResult()
    data class Text(val message: String) : InferenceResult()
    data class Plan(val plan: ExecutionPlan) : InferenceResult()
    data class Error(val reason: String) : InferenceResult()
}
```

### 6.3 ExecutionPlan（L3 Plan 模式）

```json
{
  "plan_id": "uuid",
  "steps": [
    {
      "step": 1,
      "condition": "currentCamera == BACK",
      "command": {
        "name": "flip_camera",
        "arguments": "{}"
      },
      "description": "切换到前置摄像头"
    }
  ]
}
```

### 6.4 AiAgentUseCase（Facade）

```kotlin
class AiAgentUseCase(
    context: Context,
    agentMode: AiAgentMode = AiAgentMode.REMOTE, // 默认 REMOTE；端侧文本 LLM 已移除，AiAgentMode 仅剩 OFF/REMOTE/FEISHU
    privacyLevel: AiAgentPrivacyLevel = AiAgentPrivacyLevel.STRICT,
    remoteConfig: RemoteModelConfig? = null,
    forceRemote: Boolean = false
) {
    private val orchestrator = AgentOrchestrator(
        remotePipeline = RemoteChatEngine(...)   // 由 AgentConfigurator.createRemoteChatModel 装配
    )

    // chat/相册入口
    suspend fun processInput(userInput: String, context: AgentContext): InferenceResult {
        return when (configurator.getAgentMode()) {
            AiAgentMode.REMOTE -> remotePipeline.process(input)
            AiAgentMode.FEISHU -> remotePipeline.process(input) // 飞书通道复用远程链路
            AiAgentMode.OFF -> Result.failure(AgentDisabledException())
        }
    }

    // 相机入口：远程 tool_calls（KoogReActAgent + CameraToolService）
    suspend fun processCameraInput(userInput: String, context: AgentContext): InferenceResult =
        orchestrator.processCameraInput(userInput, context)
}
```

### 6.5 IM 远程控制集成

飞书/Telegram 远程控制复用同一远程推理链路（`RemoteChatEngine`/`KoogReActAgent`，经 `RemoteChannelManager` 分发）：

飞书消息 → `FeishuChannelHandler` → `RemoteCommandDispatcher` → LLM 解析意图（复用 RemoteChatEngine，独立 System Prompt）→ `CapabilityRegistry.dispatch()` → 结果经 `sendMessage`/`sendImage` 回复。

---

## 7. Agent 执行规约 (Execution Rules)

- **JSON 解析**: 必须使用 `kotlinx.serialization.json`，严禁正则提取字段
- **System Prompt**: 禁止硬编码在 `AgentOrchestrator` 内，需抽象为 `PromptBuilder` 策略接口
- **Capability 注册**: 新增 Capability 必须同步更新 `CapabilityRegistry` 的命令映射，禁止遗漏
- **Memory 持久化**: `appendConversation` 需引入内存缓存 + 批量刷盘，禁止每条消息 2 次 DataStore IO
- **ChatML 格式**:（已废止，2026-08-02 — 端侧文本 LLM 移除；原约束为 `LocalLlmEngine` 禁止硬编码 Qwen ChatML）
- **线程安全**: `AgentOrchestrator` 的 `agentMode` / `currentModelId` 需同步控制，禁止并发修改
- **模型加载**: 快速连续调用需加并发锁，避免触发多次加载
- **隐私拦截**: `PrivacyGuard` 必须接入 LLM 输入输出流和 Capability 执行链路，禁止仅做断言
- **日志规范**: 统一使用 `PoLang:[Module]` 前缀，禁止各组件标签不一致
- **协议统一**: 全链路使用 OpenAI tool_calls（chat/相机/飞书统一 Koog `@Tool` + tool_calls；原中心化解析器 `ToolCallCommandParser` 已随 Phase 5 删除，本地 method/params 协议已随端侧文本 LLM 移除）

---

## 8. 常见陷阱检查清单 (Checklist)

- [ ] JSON 解析是否使用了正则？（必须用 kotlinx.serialization，正则无法处理嵌套/转义）
- [ ] System Prompt 是否硬编码在类内？（需按场景插件化，违反 OCP）
- [ ] 新增 AgentCommand 子类后是否同步更新了 CapabilityRegistry 的映射？（易遗漏）
- [ ] MemoryManager 是否存在 IO 放大问题？（每条消息 2 次 DataStore 读写需优化）
- [ ] CameraCapability 回调是否为 null？（11 个可选回调需 Builder 模式或统一接口）
- [ ] 模型加载是否有并发控制？（快速连续调用可能触发多次加载）
- [ ] PrivacyGuard 是否实际拦截了数据流？（当前仅断言，未接入 LLM 和 Capability）
- [ ] 用户可见文案是否硬编码中文？（需接入 strings.xml 支持多语言）
- [ ] AgentAction.Success 是否携带了语义冗余？（应携带执行结果数据，而非原命令）
- [ ] 远程 Prompt 是否包含 tool_calls JSON 示例？（会导致模型输出到 content 字段）
- [ ] content 字段是否处理了空字符串陷阱？（需使用 `isNotBlank()` 而非 `isNullOrEmpty()`）
- [ ] DeepSeek 请求是否禁用了 thinking 模式？（V4 系列必须禁用）

---

## 9. 验收标准

| ID | 验收项 | 优先级 |
|----|--------|--------|
| AC-1 | 远程模式下，"磨皮 60 然后拍照" 能解析为两个 tool_calls 并依次执行 | P0 |
| AC-2 | 远程模式下，"如果是后置就切前置再拍" 能正确执行条件判断 | P0 |
| AC-3 | 相机指令经远程 tool_calls 正确解析并执行（`processCameraInput` + `CameraToolService`） | P0 |
| AC-4 | （已废止，2026-08-02：`IntentCache` L1 缓存随端侧文本 LLM 移除） | — |
| AC-5 | 远程推理平均延迟 < 1.5s | P1 |
| AC-6 | 支持对话式记忆（多轮上下文） | P2 |
| AC-7 | DeepSeek 模型 tool_calls 成功率 > 95% | P0 |
| AC-8 | 流式聊天首 token 延迟 < 500ms | P1 |

---

## 10. 远程推理任务拆分 [agent-task]

### Phase 1: 基础设施 (RD) — 已完成
- [x] `agent-task:remote-infra-001` 实现 `RemoteInferencePipeline`（标准 OpenAI 协议）
- [x] `agent-task:remote-infra-002` 引入 Koog `OpenAILLMClient` 标准化
- [x] `agent-task:remote-infra-003` 实现 `ToolCallCommandParser`（tool_calls → AgentCommand）（该解析器已随 Phase 5 Koog 迁移删除，职责内联进各 `@Tool` 方法）
- [x] `agent-task:remote-infra-004` 删除 `InferenceRouter`、`AdaptiveStrategySelector` 等冗余组件

### Phase 2: L2 Batch 模式 (RD) — 已完成
- [x] `agent-task:remote-l2-001` 实现 `RemoteOrchestrator.processBatch()`（tool_calls 解析）
- [x] `agent-task:remote-l2-002` 设计 `RemotePromptBuilder`（ToolSpecification 格式）
- [x] `agent-task:remote-l2-003` UI 层适配批量命令串行执行

### Phase 3: L3 Plan 模式 (RD) — 已完成
- [x] `agent-task:remote-l3-001` 实现 `ExecutionEngine` 执行引擎
- [x] `agent-task:remote-l3-002` 更新 Plan 格式为标准 tool_calls（command 字段）
- [x] `agent-task:remote-l3-003` 条件求值器 (`evaluateCondition`)

### Phase 4: L4 流式 Chat (RD) — 已完成
- [x] `agent-task:remote-l4-001` 实现流式聊天（StreamingChatResponseHandler）
- [x] `agent-task:remote-l4-002` ChatMemory 历史管理（DataStoreChatMemoryStore）

### Phase 5: DeepSeek 适配 (RD) — 已完成
- [x] `agent-task:remote-ds-001` 禁用 thinking 模式
- [x] `agent-task:remote-ds-002` strict 模式兼容（additionalProperties: false）
- [x] `agent-task:remote-ds-003` content 回退解析（fallback tool_calls 提取）
- [x] `agent-task:remote-ds-004` Prompt 移除 tool_calls JSON 示例

### Phase 6: 集成与测试 (QA)
- [ ] `agent-task:remote-qa-001` 端到端测试（多指令、条件、降级）
- [ ] `agent-task:remote-qa-002` 性能基准（延迟、Token 消耗）
- [ ] `agent-task:remote-qa-003` DeepSeek 工具调用成功率测试

---

## 11. 架构演进路线图

**P0（已完成）**

1. JSON 解析改用 kotlinx.serialization
2. System Prompt 提取为 LocalPromptBuilder / RemotePromptBuilder
3. MemoryManager 引入内存缓存 + 批量刷盘
4. 本地/远程协议彻底分离（ADR-005）
5. 远程推理引入 langchain4j 标准化（2026-08 已迁移 Koog）
6. DeepSeek 适配（thinking 禁用、strict 兼容）
7. Sherpa-MNN 语音栈清理，迁至 Sherpa-ONNX
8. 唤醒词引擎 Phase 1 完成（21 词 + 动态轮询 + VAD 稳定性）

**P1（进行中）**

9. KWS always-on 迁移（Sherpa-ONNX KWS，Phase 2）
10. 支持 Batch Function Calling（tool_calls 数组）
11. ChatFormat 抽象，支持多模型切换
12. Capability 命令映射改为注解驱动或属性声明
13. IM 远程控制（飞书 WebSocket）全链路打通

**P2（中期）**

14. Plan-and-Execute 规则模板引擎（端侧不用 LLM 做规划）
15. Token 预算管理与上下文压缩
16. 隐私分级路由完善：敏感强制本地，非敏感允许远程

**P3（远期）**

17. 记忆摘要（长期对话不丢失上下文）
18. 多会话管理

---

## 12. 附录：参考文档

- [AGENTS.md](../../AGENTS.md) — 顶层治理规则
- [FEATURES.md](../01-PRODUCT/FEATURES.md) — 功能交互细节
- [COMMAND_REFERENCE.md](../04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md) — 命令参考手册
- [AI_OPTIMIZATION.md](../03-TECHNICAL-SPECS/AI_OPTIMIZATION.md) — AI 一键优化
- [TAG_GENERATION.md](../03-TECHNICAL-SPECS/TAG_GENERATION.md) — TAG 生成
- [MNN_LLM_OPERATIONS.md](../03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md) — 端侧 VLM 打标引擎运维
- [VOICE_STACK.md](../03-TECHNICAL-SPECS/VOICE_STACK.md) — 语音栈
- [IM_REMOTE_CONTROL_TECH_SPEC.md](../03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md) — IM 远程控制技术规范
- `shared/src/commonMain/kotlin/com/mamba/picme/agent/core/` — 源码目录（Agent 编排层：AgentOrchestrator、CapabilityRegistry、PrivacyGuard、MemoryManager、SceneManager 等；平台实现见 `shared/src/androidMain/`）
- ~~`agent-core/src/main/java/com/mamba/`~~ — `:agent-core` 已删除（2026-08 迁移至 JetBrains Koog 外部依赖）
- `androidApp/src/main/java/com/mamba/picme/domain/usecase/AiAgentUseCase.kt` — Facade 桥接层
