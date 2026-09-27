# Chat Agent 分层记忆系统设计（Layered Agent Memory）

> **日期**：2026-09-27
> **状态**：M1 + M2 已实施（feat/chat-memory-m2 分支）；M3 待排期
> **来源**：记忆管理专项调研会话（参考系：ChatGPT Memory、Claude Code auto-compact、Vercel AI SDK parts）
> **关联**：ADR-012（对话记忆三分边界）、ADR-016（chat 消息模型宪法，互不侵入）、ADR-008（[PRIVACY]）、`docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`
>
> **M1 实施记录（2026-09-27）**：`KoogMessageMemory` 新增 `estimateTokens`/`estimateMessageTokens`/`ageToolResults`/`trimToTokenBudget`/`assembleForPersistence`（19 个 JVM 单测）；双端 store save 统一改走 `assembleForPersistence`。预算默认 8000 token 常量——接真实模型窗口的配置化留到 M2 一并做。实证发现：项目 Koog 已升 1.3.0，`MessagePart.Tool.Result` 内容形态变为 `parts: List<ContentPart>`（读全文 `output`、老化改写 `copy(parts=…)`）、`Call` 参数字段为 `args`。
>
> **M2 实施记录（2026-09-27）**：滚动摘要（compaction）落地——
> - **数据层**：`SessionCompaction`（version/slots/compactedUpToTurnId/createdAt，kotlinx-serializable）+ `CompactionSlots` 四槽位（intent/decisions/todos/entities），`parseSlots` 解析失败整体回退自由文本进 intent（决策 9）；`merge` 增量合并（标量新覆盖旧、列表追加去重保序）。
> - **候选选择**：`KoogMessageMemory.selectCompactionCandidates`——组装超预算才触发，从最旧块取、跳过近 `PROTECTED_RECENT_TURNS` 实轮保护区、最新块永不压、tool 块原子（复用 `groupIntoBlocks`）。
> - **执行器**：`SessionCompactor`（`maybeCompact`）+ `SummaryGenerator` 函数接口；生产实现 `SummaryGeneratorViaExecutor` 经 Koog executor 单发（temperature=0/maxTokens=512，与 `IntentRouter.callRouterLlm` 同源），llm_call_log source=`chat-session-compaction`。**不改写历史消息**——compaction 是叠加式摘要，历史仍按 M1 预算组装。
> - **存储**：`ChatMemoryStore` 加 `loadSummary/saveSummary/clearSummary`（摘要键 `koog_summary_` 独立于历史键 `koog_memory_`；`clear` 会话销毁才一并清）；双端 actual（Android DataStore / iOS NSUserDefaults）同步落地，失败降级 null/静默。
> - **注入**：`composeChatSystemPrompt`（top-level 纯函数）把摘要段拼在记忆快照后（`【会话摘要】` 标记）；`KoogChatAgent` 的 agent 重建键 = 记忆快照 + 摘要 version（压缩后 version 变 → 重建 → 新摘要烘焙进 system prompt）；每轮 `runChat` 开头 `maybeCompact` + `loadSummary` 新鲜渲染。
> - **测试**：`SessionCompactionTest`（21 例：槽位解析/合并/渲染/序列化/候选选择/prompt 构造）+ `ChatMemoryStoreSummaryTest`（6 例契约）+ `SessionCompactorTest`（9 例触发/合并/失败跳过）+ `ComposeChatSystemPromptTest`（5 例注入语义）。

---

## 1. 问题陈述

现状：chat 会话上下文 = `KoogMessageMemory.trimToMaxMessages` 截最近 **10 条**消息（System 占 1 个预算位，tool 块原子裁剪，经 `ChatMemoryStore` 落盘，Android=DataStore / iOS=NSUserDefaults）。

三个问题：

- **P1 硬遗忘**：第 11 条起的信息对模型完全不可见。用户在长会话中段说过的偏好、做出的决定、提到的实体，后续轮次全部"失忆"。
- **P2 预算错配**：窗口按**消息条数**而非 token 预算。单条相册查询 tool result 可达数千 token，10 条窗口实际只够 2~3 个有效对话轮；旧 tool result 整段滞留，挤占对话本身。
- **P3 恢复退化 + 无长期记忆**：会话恢复 = 窗口内消息全量重放，早期语境丢失；跨会话零记忆——L3 只有静态的 `MemoryContextProvider` 事实快照（ADR-012），没有写入/更新管线。

为什么值得解决：任务范式（PRODUCT.md v3.1 §6.6）要求 agent 承担多轮、跨会话的相册整理任务，记忆是任务连续性的前提。

## 2. 方案概述：三层记忆

| 层 | 内容 | 生命周期 | 注入方式 |
|---|---|---|---|
| **L1 工作记忆** | 近 K 轮原文 + 当前工具状态 | 当前会话 | 现有窗口（保留，改预算制） |
| **L2 情节记忆** | 旧轮滚动摘要（compaction） | 会话内持久 | 1 条 system 摘要段，每轮新鲜组装 |
| **L3 语义记忆** | 跨会话事实库（偏好/决定/实体） | 永久（可治理） | `MemoryContextProvider` top-k 注入 |

核心转变：**条数窗口 → 优先级 + token 预算制组装**；**无中段记忆 → 滚动摘要**；**静态事实快照 → 事实管线**。

## 3. 接缝（深模块思维）

| 接缝 | 角色 | 现状 |
|---|---|---|
| **S1 `KoogMessageMemory`** | 纯函数层：预算裁剪、tool result 老化 | 已有（三不变式），M1 全部落这里，JVM 单测靶心已存在 |
| **S2 `ChatMemoryStore`** | 持久化协议：新增会话摘要存取 | 已有接口，双端 actual，只加方法不动现有签名 |
| **S3 `MemoryContextProvider`** | L3 事实注入 | **现成接缝**（ADR-012 已定其职责=事实快照被动注入），补写入管线而非另起炉灶 |
| **S4 server AI 网关** | 摘要/抽取的 LLM 通道 | 已有（文本远程推理，[PRIVACY] 合规） |

理想边界：整个变更只动 S1（纯函数）+ S2（接口扩展），S3/S4 复用不动。

## 4. 用户故事

### M1：预算制组装 + 工具瘦身（独立可交付）

- **US-1.1 token 预算制**：组装输入 `(messages, budgetTokens)`，超预算按优先级裁剪；预算默认取模型窗口 70%，可配。
- **US-1.2 tool result 老化**：非最近 2 个对话轮的 `Tool.Result` part 替换为单行占位（如 `[tool:gallery_search → 23 项：海边/2025-07]`），Call/Result 配对保持完整。
- **US-1.3 不变式保持**：老化与裁剪后仍满足三不变式（①System 不落盘 ②tool 块原子 ③双向配对），现有 `KoogMessageMemoryTest` 全绿。
- **US-1.4 裁剪顺序**：从最旧块向新丢，但近 K 轮（默认 3）对话轮最后才丢；同预算下优先保对话、牺牲旧工具输出。

### M2：滚动摘要（compaction，已实施 2026-09-27）

- **US-2.1 触发**：组装超预算且存在可压缩轮 → 最旧 M 轮送摘要请求（经 S4 网关，模型默认取 chat 模型廉价档）。✅ `SessionCompactor.maybeCompact` + `KoogMessageMemory.selectCompactionCandidates`
- **US-2.2 槽位化摘要**：输出四槽位 JSON——`intent`（用户意图）/ `decisions`（已决定）/ `todos`（待办）/ `entities`（关键实体）；schema 校验，解析失败降级自由文本。✅ `CompactionSlots.parseSlots`
- **US-2.3 摘要持久化**：`ChatMemoryStore` 新增 `sessionSummary`（带版本号），会话恢复时自动重放，无需重读全史。✅ `loadSummary/saveSummary/clearSummary` 双端 actual
- **US-2.4 注入**：摘要作为 system 段拼在 `chatSystemPrompt` 之后，每轮新鲜组装、**不落盘**（与不变式①语义一致）。✅ `composeChatSystemPrompt` + agent 重建键含摘要 version
- **US-2.5 增量合并**：新一次 compaction 输入 = 旧摘要 + 新被压轮 → 输出 v(n+1)。✅ `CompactionSlots.merge`
- **US-2.6 语义保护**：media id、日期、数字、否定语义（"不要时间线"）在摘要 prompt 中明确要求保留。✅ `CompactionPrompt.build`

### M3：跨会话事实库

- **US-3.1 抽取**：会话结束/后台空闲时，对「摘要 + 近轮」抽取候选事实，schema：`{subject, predicate, object, confidence, sourceSessionId}`。
- **US-3.2 写入门槛**：confidence ≥ 0.7 才写入；同 subject 新事实**覆盖**旧事实（用户纠正即更新），不追加。
- **US-3.3 注入**：每轮按 BM25/关键词相关性取 top-k（k ≤ 5）经 `MemoryContextProvider` 注入。
- **US-3.4 治理**：设置页可查看全部事实、单条删除、一键清空；删除下轮即时生效。
- **US-3.5 时效衰减**：事实带 `lastConfirmedAt`，超 90 天未再确认标记 stale、不再注入。

## 5. 已定决策

1. 三层模型与分期顺序 **M1 → M2 → M3**；M1 不依赖任何新基建，先行交付。
2. 条数窗口 → **token 预算制**，默认 70% 模型窗口。
3. compaction 粒度 = **对话轮**（user↔assistant 往返），与 ADR-016 Turn 聚合方向对齐。
4. 摘要**槽位化**（四槽位）优于自由文本，抗细节丢失。
5. 检索 **BM25/关键词先行**，向量嵌入后置。
6. **复用 ADR-012 三分边界**（对话记忆 ≠ 事实记忆 ≠ 人物关系），L3 走 `MemoryContextProvider` 现有接缝，不推翻既有 ADR。
7. 摘要/抽取仅**文本**经 server 网关远程推理；图片一律 `media://` id 占位（[PRIVACY]）。
8. 摘要与 system prompt 同样**不落盘**，每轮由摘要记录新鲜渲染。
9. 失败模式：摘要请求失败 → 本轮跳过 compaction（下次再试），**绝不因记忆系统阻断对话**。

## 6. 测试决策

- **S1 纯函数 JVM 单测**：预算裁剪优先级矩阵、tool 老化占位、三不变式回归（扩展 `KoogMessageMemoryTest`）。
- **S2 契约测试**（commonTest）：摘要存取、版本递增、恢复重放；iOS `IosKoogMessageMemoryStore` 同契约（iosX64Test）。
- **S4 集成测试**（jvmTest，mock 网关）：摘要请求体/响应 schema 校验、失败降级路径。
- **验收口径（完成定义）**：
  - 记忆回放测试集：构造 20+ 轮会话（埋 3 个事实：偏好/决定/实体），末轮提问，**召回 ≥ 2/3**；
  - M1 后同场景上下文 token 下降 **≥ 30%**（tool result 占大头场景）。

## 7. 明确不做

- 向量嵌入/语义检索（避免先建索引基建）
- 跨设备记忆同步（账号体系联动后置）
- 多用户/家庭共享记忆
- 图片内容记忆（media 不远程；端侧 VLM 打标属 TAG 域既有管线，不并入）
- M3 记忆治理 UI 的 iOS 实现（走 [PARITY] spec 流程另行固化）
- 不改 Koog 框架内部（全部落在 store/组装层/prompt 装配层）
- ChatPart/UIMessage 消息模型变更（ADR-016 域，互不侵入）

## 8. 补充说明

- **数据结构草案**：
  - M2：`SessionCompaction { version, slots { intent, decisions[], todos[], entities[] }, compactedUpToTurnId, createdAt }`
  - M3：`AgentFact { id, subject, predicate, object, confidence, sourceSessionId, lastConfirmedAt }`
- **MemoryManager 遗留**（ADR-012 观察：`buildContextMessages` 无调用方）：M1 落地时顺带清理或写明保留理由，防止第三套记忆滋生。
- **摘要模型选择**：质量要求低于对话，默认复用网关廉价档；计费/观测对齐 `llm_call_log` 既有管线。
- **iOS 注意**：`ChatMemoryStore` 加方法属接口演进，双 actual（DataStore/NSUserDefaults）同步实现；摘要体积小，NSUserDefaults 容量可承受，但 M3 事实库建议直接评估 Room/GRDB 级存储（事实数会增长）。
