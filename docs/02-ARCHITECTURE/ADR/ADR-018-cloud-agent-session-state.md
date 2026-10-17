# ADR-018: 云端 Agent 会话状态模型——事件溯源 + 端云工具桥 + BYOK 透传语义

**状态**: 生效中（2026-10-14 用户评审通过）
**日期**: 2026-10-14
**决策**: 用户（评审认可）
**依赖**: ADR-005（远程协议）、ADR-008（隐私红线）、ADR-012（统一会话记忆）、ADR-016（Chat parts 模型）、ADR-017（推理后端域，云端 Agent 框架已定向 Koog JVM）
**借鉴**: OpenHands 事件溯源架构（事件即状态 / source·role·parent 三维分离 / pending action 不变式）

---

## 1. 背景

ADR-017 已定向：云端 Agent 运行时用 Koog JVM，复用 `:shared` 编排层。框架选型不回答的真正难题是**会话状态**——Agent loop 上云后，状态从端侧内存/Room 挪到服务端，必须钉死三件事：

1. **状态怎么建模**：端侧现状是 Room `chat_sessions`/`chat_messages`（渲染数据源），服务端现状只有无状态代理 + `llm_call_log`（计费日志），**没有任何 Agent 会话状态载体**；`:shared` 未使用 Koog persistence/checkpoint 能力（2026-10-14 代码确认），服务端会话持久化是全新地带。
2. **端云怎么分工**：云端 Agent 需要调端侧工具（相册/媒体/设备动作），媒体又不能上云（ADR-008）。
3. **BYOK 怎么活**：ADR-017 承诺 BYOK 通道「server 零接触」，云端 Agent 要求 LLM 调用发生在服务端——承诺必须细化而不是悄悄稀释。

## 2. 决策

### 决策 1: 会话状态 = 事件溯源（Event Sourcing），事件流是唯一真相

借鉴 OpenHands 的成熟设计，服务端新增两张表：

- `agent_session`：会话元数据 + 生命周期状态机（`running / paused / waiting_confirmation / finished / error`）；
- `agent_event`：**不可变、类型化、只追加**的事件流。事件类型最小集：`Message`（用户/系统）、`Action`（模型决定调工具）、`Observation`（工具结果）、`Condensation`（上下文压缩动作本身也是事件，历史不篡改只投影）、`Finish`。

每条事件带**三维正交标注**：`source`（user/agent/environment，真实来源）、`role`（投影给模型时的 system/user/assistant/tool）、`parent_id`（会话树分支锚点）。「写给模型看的」与「实际发生的」在数据模型上分离——审计与回放不依赖 LLM role 反推。

**Pending action 不变式**：没有匹配 Observation 的 Action 视为 pending；Agent loop 恢复时优先执行 pending action，再生成新动作。崩溃恢复语义由此一句话钉死。

**Agent 无状态、Session 有状态**：Agent = `step(事件历史) → Action` 纯函数式循环；Session 承载生命周期、配额、密钥、持久化。Koog 侧只用其 strategy/agent loop 编排能力，**不启用 Koog Persistence checkpoint**。理由不是存储格式锁定（Koog 的 `PersistenceStorageProvider` 接口仅三个 suspend 方法、无强加格式，可自行实现 SQL provider）——而是：① checkpoint 是**快照模型**（每节点存全量消息历史），长会话在 SQLite 上存储膨胀明显，与事件流构成双状态源；② 事件流本身已覆盖恢复语义（pending action 不变式 + 尾部重放），且同时充当审计与 UI 渲染数据源，一物三用；③ 事件级粒度（每个 Action/Observation 留痕）优于快照间细节丢失。（事实依据：Koog Persistence 官方文档，2026-10-14 核实。）

### 决策 1a: 工具副作用可逆注册表（借 Koog RollbackToolRegistry 思想）

Koog Persistence 最有价值的设计不是快照，而是 `RollbackToolRegistry`——注册**成对的正向/逆向工具**，rollback 时逆序调用逆向工具（如 `createUser` ↔ `removeUser`）。本决策借其思想、自建于事件流之上：

- 工具注册时可声明**可选的逆操作**（revert counterpart），要求逆操作接受与原工具相同的参数（与 Koog 同约束）；
- 会话取消/失败回滚时，沿事件流**逆序**对已执行的可逆 Action 逐条补发逆操作，每次逆操作也作为事件落流（`Observation` source=environment 标注 revert）；
- 幂等性归工具实现方负责；不可逆工具（如已发出的 IM 消息）在注册时显式标记 `reversible = false`，回滚时跳过并留痕。

polang 端侧工具天然有可逆土壤（删除照片 = 移入回收站，本身可逆），该机制让云端 Agent 的「放手执行」有安全网。

### 决策 2: 端云分工 = 云端编排 + 双域工具桥

云端 Agent 的工具面分两域：

- **服务端工具**：云端浏览器（BrowserRoute 已有）、web 搜索等纯云能力，服务端本地执行；
- **端侧工具**：相册查询/媒体操作/设备动作，经**工具桥**下发：server → App（WS/轮询通道）→ 端侧执行 → Observation 回传。复用 AI 工程师模式已验证的「云端编排 + 端侧执行」隧道骨架（`ClaudeChatRoute`/`ClaudeToolResultRoute`），方向泛化为通用工具桥。

ADR-008 红线不变：媒体文件不上云；端侧工具回传的 Observation 仅文本/元数据/摘要。守卫测试覆盖范围扩展至云端工具桥回传路径。

### 决策 3: 与端侧 Room 的关系 = 独立会话域 + 端侧只读镜像

云端会话是**独立会话域**（`agent_session` 为 SSOT），不混入本地 `chat_sessions`。端侧 Room 仅保留**渲染镜像**（离线查看用），不是状态源——ADR-012「每条链路一套记忆」按「链路 = 会话域」解读：云端链路的唯一记忆在服务端事件流，端侧镜像不反向写入。会话在 App 端通过 SSE 实时呈现 + 历史分页拉取。

### 决策 4: BYOK 透传语义 = 请求级透传（零留存）+ 端侧编排兜底

- **A 主路径（零留存）**：用户在设置中显式授权「云端 Agent 模式下 API Key 随会话发送至服务端内存处理」后，key 经请求 header 携带，仅存活在会话内存中，**不落库、不进任何日志**（access log / `llm_call_log` / 异常堆栈显式脱敏），服务端加守卫测试钉住「key 不得持久化」不变式；
- **C 兜底**：未授权的 BYOK 用户回落端侧编排（现状），云端 Agent 能力不可用并在 UI 明示原因。

由此 ADR-017 决策 2 的隐私承诺细化为分档表述：**BYOK 直连 = 零接触；BYOK + 云端 Agent（显式授权）= 零留存**。网关通道语义不变。

## 3. 为什么不选替代方案

- **不直接复用 Room 消息表模型上云**：消息表是渲染数据源，缺 source/role/parent 三维与 pending 语义，无法支撑崩溃恢复与审计；OpenHands 已证明该坑（用 role 反推来源会审计失真）。
- **不启用 Koog Persistence checkpoint 作为状态源**：`:shared` 现未使用该能力；其快照模型（每节点全量消息历史）与事件溯源构成双状态源、长会话存储膨胀，恢复语义已由 pending 不变式覆盖。仅借鉴其 `RollbackToolRegistry` 思想（决策 1a）。
- **不做 key 托管（服务端加密保管）**：把 BYOK 变成「另一种网关托管」会模糊双通道的隐私分野，且显著扩大服务端安全责任面。
- **不做云端编排 + 端侧执行 LLM 调用（每跳回端）**：延迟翻倍、SSE 穿两层、端必须在线，性价比过低（评估记录见 2026-10-14 会话）。

## 4. 本期非目标

- 多设备同时编辑同一会话、事件流分支回放（time-travel）——`parent_id` 仅预留锚点；
- Condenser 压缩策略细化——先用简单截断/摘要，策略可替换（OpenHands 同构）；
- 多实例水平扩展——2G VPS 单进程，会话亲和问题不存在；
- 事件流跨用户共享/协作。

## 5. 与既有 ADR 的关系

- **ADR-008**：红线不变，守卫范围扩至工具桥回传路径；
- **ADR-012**：按「链路 = 会话域」解读，不冲突；
- **ADR-016**：parts 模型管端侧渲染协议，事件流管云端状态，两者在 SSE 边界做一层映射（事件 → parts chunk）；
- **ADR-017**：本文档是其演进方向第 0 条的展开。

## 6. 状态

| 阶段 | 状态 | 日期 |
|------|------|------|
| 架构决策（本文档） | ✅ 生效中（用户评审通过） | 2026-10-14 |
| 服务端实现 M1（`server/agent/` 包 + `agent_session`/`agent_event` 两表 + `/agent/**` REST/SSE + BYOK 透传零留存守卫测试；事件溯源/pending 不变式/投影/决策 1a 元数据落地） | ✅ 完成（299 测试全绿） | 2026-10-17 |
| 端侧工具桥 + BYOK 授权 UI（+ 最小聊天页，调试入口实验页） | ✅ 完成 | 2026-10-17 |

> M1 已录边界（详见 `server/AGENTS.md` §2 agent/ 包注释与 AgentRoutes KDoc）：SSE 帧为持久化事件级（无 token delta）；投影仅 Message 直映射 + 最新 Condensation 作 system；服务端工具仅 server_time（闭环验证用，可逆集合为空）；~~同会话并发轮无互斥~~（M2.6 已由 `AgentRoundGate` per-user 并发=1 消除，占用回 409 `round_in_flight`）；Agent 轮次不写 llm_call_log（审计即事件流）。
