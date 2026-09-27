# ADR-012: 解决双套对话记忆系统

**状态**: 波次1 数据正确性修复已完成（2026-08-03 代码核实，详见下方状态更新）
**日期**: 2026-07-28
**更新日期**: 2026-08-03（核实三项实施任务现状）
**决策**: 用户
**依赖**: review §0.3-D5、P1-3（原前置 ADR-010「链路隔离」已于 2026-08-23 随 ADR 整理删除，历史见 git）

---

> ## ⛔ 状态更新（2026-08-03）：波次1 修复已落地，「相机专用」前提已变化
>
> 经代码核实，本 ADR 波次1 的两个数据正确性 bug 均已修复：`MemoryManager.appendConversation` 已改为 DataStore `edit{}` 内原子 read-modify-write（`MemoryManager.kt:144`）；`DataStoreChatMemory.clear()` 已同步清内存 `cache`（`RemoteReActAgent.kt:467`，注释明确记录旧 bug）。
> 原 `streamChat` 对 `MemoryManager` 的死写回已随链路重构消除（`AgentOrchestrator` 已无 `streamChat`）；现存两处写回为有意保留：相机会话回写 `saveCameraConversation`（`processCameraInput` 后，`AgentOrchestrator.kt:415`）与 chat 图片分析结果回写（`ChatViewModel.kt:2302`）。
> §3 中「`MemoryManager` 限定相机」的前提已不成立：2026-08-02 本地链路整体删除，相机链路同样改走远程 tool_calls，`MemoryManager` 现状服务相机会话与 chat 图片上下文两类写回。⚠️ 遗留观察（2026-08-23 复核仍未变）：`buildContextMessages` 无调用方，`MemoryManager` 写回的消费者链路待后续梳理。✅ 2026-09-27 复核：`buildContextMessages` 已不存在于 `MemoryManager`（随演进删除，全仓仅文档提及），该遗留观察关闭。

## 1. 背景

当前存在两套互不同步的对话记忆：

- `MemoryManager`（DataStore Preferences JSON，本地路径用，`buildContextMessages`）。
- `DataStoreChatMemory`（langchain4j ChatMemory，远程 ReAct 用，`RemoteReActAgent`）。

问题（review P1-3）：

- `streamChat` 把本轮 `[user, 摘要 reply]` 回写 `MemoryManager`，但 chat ReAct 只读 `DataStoreChatMemory` → **该回写对 chat 是死写**（无消费者），且原注释声称"ReAct 经 buildContextMessages 读历史"与实现不符。
- `MemoryManager.appendConversation` 是 load→改→save 非原子，配合 fire-and-forget `backgroundScope.launch` → 并发追加**丢更新**。
- `RemoteReActAgent.resetSession()` 只清 DataStore、不清内存 `cache` → 重置后仍返回旧历史。

## 2. 决策

**每条隔离链路有且仅有一套对话记忆；消除死写与割裂。** 边界：对话记忆 ≠ 事实记忆 ≠ 人物关系（三者职责写清，避免再次混淆）。

## 3. 实现要点（波次1）

- **chat 记忆单一来源**：统一用 `DataStoreChatMemory`（langchain4j ChatMemory）作为 chat 唯一对话记忆；**删除 `streamChat` 对 `MemoryManager.appendConversation` 的死写回**（`AgentOrchestrator.kt:463-468`）。
- **`MemoryManager` 限定相机**：成为相机本地路径专用；或抽象统一 `ConversationMemory` 接口、两实现各服务一条隔离链路。⚠️ **已过时（2026-08-03）**：本地链路整体删除后相机同样改走远程 tool_calls；`MemoryManager` 现状服务相机会话与 chat 图片上下文两类写回，「限定相机」前提不再成立。
- **修数据正确性 bug**：
  - `MemoryManager.appendConversation` 改为 DataStore `edit{}` 内原子 read-modify-write（解决并发丢更新）。
  - `RemoteReActAgent.resetSession` 同步清内存 `cache`（`RemoteReActAgent.kt:313-317,413`）。
- **划清边界**：`MemoryContextProvider`（事实/人物关系快照）仅供 system prompt 被动注入，不是对话记忆；三者职责在 ADR 中固化。

## 4. 后果

- ✅ 消除死写与"两套记忆不一致"的认知负担；多轮对话上下文来源唯一、可预测。
- ✅ 修复两个数据正确性 bug（并发丢更新、重置残留）。
- ✅ 随链路隔离重构自然落地（各链路各持自己的记忆实现）。
- ⚠️ 若选择统一 `ConversationMemory` 接口，需小幅重构两实现的共性。

## 5. 状态

| 项 | 状态 |
|---|---|
| 决策与 ADR | ✅ 2026-07-28 |
| 删 chat 对 MemoryManager 死写回 | ✅ 已消除（原 `streamChat` 死写回随链路重构移除；现存相机会话与 chat 图片上下文两处为有意写回） |
| appendConversation 原子化 / resetSession 清 cache | ✅ 已完成（2026-08-03 代码核实：`edit{}` 原子写、`clear()` 同步清 cache） |
| 记忆边界文档化 | ⏳ 波次1 |

## 6. 相关

- `docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md`（链路隔离后的现役架构 SSOT；原 ADR-009/010 已删除）
- review §0.3-D5、P1-3
