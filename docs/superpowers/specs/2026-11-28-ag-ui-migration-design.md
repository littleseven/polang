# 云端 Agent 链路 AG-UI 迁移设计

> **日期**：2026-11-28
> **状态**：在途（M1/M2/M3 已实施；M4 HITL/STATE 评估 + v1 下线待启动）
> **上游**：ADR-016（parts 文档模型）、ADR-018（云端 Agent 事件溯源）、[AG-UI 协议](https://docs.ag-ui.com/concepts/events)
> **下游同步**：`CHAT_PROTOCOL_TECH_SPEC.md` §2.9、`server/AGENTS.md` 路由清单（交付时同原子提交）

---

## 1. 决策摘要

云端回合（picme-server `/agent/**` ↔ 双端）的自定义 SSE（五 kind 帧）迁移为 **AG-UI 标准事件流**。

- **只做传输层**：存储宪法不动——server 事件溯源（agent_event append-only，ADR-018）与端侧 Room parts（ADR-016）保持原样；AG-UI 是事件流的新出站投影。
- **本地 Koog 回合不迁移**：进程内无网络边界，保持 `ChatStreamEvent` Flow。
- **选型结论**：AG-UI（对比 Vercel DSP 后选定；DSP 在 parts 层已连续，AG-UI 补 run 边界/HITL/状态同步语义与生态面。映射层吸收差异，存储层与传输层解耦）。
- **兼容策略**：v1 端点逐字节保留，v2 新端点并行；server 先行，客户端回落 v1；v1 下线走「占比观测 → 促升 → 硬门槛」三步（详见 §7）。

## 2. 里程碑

| 期 | 内容 | 状态 |
|---|---|---|
| **M1** | server `TurnFrameSink` 重构 + `AgUiFrameSink` + v2 端点 + 映射表测试；shared/commonMain `AgUiSseParser` + `AgUiTurnAdapter` + commonTest | ✅（2026-11-28） |
| **M2** | Android 主聊天切 v2（DataStore 回滚闸 `cloud_ag_ui_enabled` + 404/406 无 error 体回落 v1 + 粘性标记）；`CloudSseParser` 保留至 v1 下线（回落路径依赖，删除并入 M4） | ✅（2026-11-28） |
| **M3** | iOS 直接只实现 v2（shared `AgUiSseParser` 经 SharedKit 导出为单一解码源 + `AgUiCloudTurnTranslator` iOS 子集；v1 kind 帧模型物理删除） | ✅（2026-11-28） |
| M4 | interrupt HITL / STATE_DELTA / REASONING 迁移评估；v1 端点下线 | 待 |

## 3. 架构

```
AgentRunner → agent_event 落库（不变）
    │  onEvent / onOpenUiProgramDelta / DeviceToolService 三信号通道（不变）
    ▼
TurnFrameSink（新增接口，路由层按端点注入）
    ├─ LegacyKindFrameSink — v1：kind 帧，逐字节现行行为
    └─ AgUiFrameSink — v2：AG-UI 事件流（threadId/runId 包装 + 差分映射）
                                          │
POST /agent/sessions/{id}/messages (v1) ──┘
POST /agent/v2/sessions/{id}/messages (v2，鉴权/会话/并发闸/配额/目标解析与 v1 同一实现)
```

**端侧**（M2 起消费）：

```
AG-UI SSE → shared AgUiSseParser（帧解析，未知 type 容错跳过）
         → AgUiTurnAdapter → TurnStreamEvent（既有三段式）
         → TurnPartsReducer / CloudAgentTurnRunner（零改动）
```

## 4. 事件映射表（SSOT）

约定：`threadId` = sessionId；`runId` = `run-<uuid>`（路由层每回合生成）；messageId/toolCallId 锚定规则见各行。未列的 AG-UI 事件（STATE_*/REASONING_*/STEP_*/Subagent/Activity）M1 不产出。

| 源信号 | AG-UI 事件 | 字段与规则 |
|---|---|---|
| 回合受理 | `RUN_STARTED` | threadId/runId；**v1 无对应物，v2 新增 run 边界** |
| `onOpenUiProgramDelta(prefix)`（累计快照，节流后） | `TEXT_MESSAGE_START/CONTENT/END` | **差分映射**（复刻 `ChatStreamTurnAdapter` 启发式）：首轮 → START(role=assistant, messageId=`stream-{runSeq}`)+CONTENT(全量前缀)；前缀扩展 → CONTENT(diff)；非扩展（轮重置）→ END 旧块 + START 新块 + CONTENT |
| AgentEvent MESSAGE, role=USER | TEXT_MESSAGE 三段式 | role=user，messageId=`evt-{eventId}`，CONTENT=整段 |
| AgentEvent MESSAGE, role=ASSISTANT（终结消息） | TEXT_MESSAGE_END 或三段式 | **去重**：当前开放流式块累计 == content → 仅 END 该块；不等（ensureValidProgram normalize/兜底改写过）→ END 旧块 + 以 `evt-{eventId}` 发完整三段式；无开放块（非流式回退路径）→ 完整三段式 |
| AgentEvent ACTION | `TOOL_CALL_START/ARGS/END` | toolCallId=`act-{eventId}`；ARGS 单 chunk 全量（Koog 调用开始即给全量 args，合法）；START 前闭合开放文本块（与端侧 adapter 同口径） |
| AgentEvent OBSERVATION | `TOOL_CALL_RESULT` | toolCallId=`act-{actionEventId}` 配对锚；messageId=`act-{actionEventId}-result`；content=observation 文本 |
| AgentEvent CONDENSATION / HTML_CARD | —（不下行） | 内部事件 / 已退役型，UI 无消费面 |
| AgentEvent FINISH | —（不单独映射） | 由路由层 done 分支统一发 RUN_FINISHED |
| `ToolRequestFrame`（设备工具桥下行） | `CUSTOM` | name=`device_tool_request`，value={request_id, tool, args, timeout_ms}；上行仍走既有 `POST /agent/sessions/{id}/tool-results`（传输无关） |
| 回合成功收尾 | `RUN_FINISHED` | threadId/runId + metadata={session_status, elapsed_ms, prompt_tokens, completion_tokens, openui_lang_version}（v1 done 帧数据全量进 metadata；AG-UI metadata 开放键约定） |
| 超时 / 异常 | `RUN_ERROR` | message 与 v1 error 帧逐字一致（round_timeout / e.message）；code 分别 round_timeout / internal_error |

**帧序**：全部经同一 writer 顺序写出，与事件序一致（v1 同口径）。

## 5. server 实现要点

- **`TurnFrameSink` 接口**（`server/agent/TurnFrameSink.kt`）：`onAgentEvent / onProgramDelta / onToolRequest / onDone / onError` 五方法；`respondTextWriter` 块内按端点注入实现。
- **路由重构**：`post("/sessions/{id}/messages")` 主体抽为私有共享函数（鉴权/会话校验/roundGate/配额/target 解析/超时安全网/桥清理全部单份），v1 v2 仅 sink 不同。v1 行为逐字节不变（守卫测试钉住）。
- **`AgUiFrameSink`**：有状态（开放文本块 messageId、差分基准、累计文本、流式块序号）；终结消息去重比较用「累计 == content」字符串全等。
- **BYOK/配额/并发闸语义**：与 v1 完全一致（同一代码路径）。

## 6. shared 解码器（commonMain）

- **`AgUiFrame.kt`**（`domain/chat/streaming/`）：帧密封类（RunStarted/TextMessageStart/Content/End/ToolCallStart/Args/End/ToolCallResult/Custom/RunFinished/RunError）。
- **`AgUiSseParser`**：SSE block → AgUiFrame?；无法识别/未知 type 一律 null 容错（`CloudSseParser` 先例），kotlinx.serialization JsonObject 解析。
- **`AgUiTurnAdapter`**：AgUiFrame → List\<TurnStreamEvent\>；映射：TextMessageStart/Content/End → TextStart/TextDelta/TextEnd（partId=messageId，role=user 跳过——端上用户消息本地已落）；ToolCallStart → ToolInputStart；ToolCallArgs 累计 + ToolCallEnd → ToolInputAvailable；ToolCallResult/Custom/RunFinished/RunError 不进 TurnStreamEvent（由调用方分流：设备工具桥 / 性能 metadata / 错误收尾）。

## 7. 兼容与 v1 下线

- 兼容矩阵与发布顺序（server 先行双协议、客户端 404/406 回落 v1）按 2026-11-27 会话共识执行；
- v1 下线三步：① server 按端点统计活跃 deviceId 占比（X-Device-Id 既有链路）；② 非 Play 渠道 OTA 促升 + Play 应用内更新提示；③ v1 占比 <1% 周活后端点回「请升级」错误帧（五语），观察一个版本周期后物理删除。
- iOS 无线上存量，M3 直接只实现 v2。

## 8. 不做清单（M1）

- 不迁 interrupt HITL / STATE_SNAPSHOT·DELTA / REASONING_* / STEP_*（M4 评估）；
- 不改 Room、渲染层、回灌投影、`CloudToolProjectionGate`、IM 通道；
- 不动 AgentRunner / DeviceToolService / 事件溯源三表；
- Vercel DSP 双出口不做（选型已定 AG-UI）。

## 9. 验收

- M1：映射表单测全绿（覆盖 §4 全表 + 差分三态 + 终结去重三分支）；v1 守卫测试零改动通过（逐字节回归）；shared commonTest 全绿（解析容错 + adapter 映射）；本地 run-local 起服后 curl v2 端点人工核验帧序。
