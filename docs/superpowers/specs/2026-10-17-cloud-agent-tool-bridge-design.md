# 云端 Agent M2：端侧工具桥 + BYOK 授权 + 最小云端聊天页 — 设计 Spec

> **日期**：2026-10-17
> **状态**：已定稿待评审
> **上位约束**：ADR-017（推理后端域）/ ADR-018（云端 Agent 会话状态模型：决策 2 工具桥、决策 3 独立会话域、决策 4 BYOK 透传）
> **前置交付**：云端 Agent M1（`server/agent/` 包 + 事件溯源 + `/agent/**` REST/SSE，`feat/cloud-agent-m1`）
> **对齐记录**：2026-10-17 会话内逐项确认——含最小聊天页（调试入口）；桥传输 = SSE 下行 + POST 上行；Room 镜像推迟 M3；实验页豁免 PARITY；M2 工具面只读三件套

---

## 1. 目标与非目标

**目标**：让云端 Agent 能调用端侧相册工具（只读），BYOK 用户显式授权后可用云端通道，并提供最小可用的云端会话聊天页完成端云全链路遛狗。

**非目标（M2 明确不做）**：

- 端侧 Room 渲染镜像（ADR-018 决策 3 的镜像层推迟到 M3；M2 历史经 `GET /agent/sessions/{id}/events` 回放）
- 写类端侧工具（trash/delete 等，等决策 1a 逆操作执行链 + 确认流，M3）
- 搜索结果横滑卡片跨域渲染（云端会话中 search_media 降级为文本总结，M3 课题）
- WS 常驻桥、端离线时的工具调用排队
- 云端浏览器工具接入云端 Agent 工具面
- 云端聊天页的双端 PARITY 固化（调试入口实验页，转正时再入 `08-UI-SPECS`）
- iOS 跟随（M2 全部 Android-only，转正后按 /ios-follow 管线追）

## 2. 架构总览

```
┌─ App ────────────────────────────────────────────────┐
│ CloudAgentChatScreen（调试入口实验页）                 │
│   └─ CloudAgentChatViewModel（内存态消息列表）         │
│        ├─ CloudAgentClient（OkHttp：REST + SSE）       │
│        └─ DeviceToolExecutor ──→ ChatToolService       │
│            （tool 名映射 + 截断脱敏）   （现有 handler）│
└───────┬───────────────────────▲──────────────────────┘
        │ POST messages（SSE）  │ POST tool-results
┌───────▼───────────────────────┴──────────────────────┐
│ picme-server                                          │
│   AgentRoutes（+tool-results 端点）                    │
│   AgentRunner ── DeviceTool（Koog Tool 适配器）         │
│        └─ AgentBridgeRegistry（requestId → Deferred）  │
│   agent_session / agent_event（M1 已有，零 schema 变更）│
└───────────────────────────────────────────────────────┘
```

关键性质：**一轮对话只开一条 SSE**——工具请求帧与助手文本帧同流下行；上行结果走独立 POST（claude-chat 已验证的双通道形态）。Koog 的 EventHandler 照常落 Action/Observation，pending 不变式不被桥破坏。

## 3. 桥协议

### 3.1 下行：SSE `tool_request` 帧

在 `POST /agent/sessions/{id}/messages` 的事件流中新增一种帧（与 M1 `event`/`done`/`error` 并列）：

```
data: {"kind":"tool_request","request_id":"tr_<uuid8>","tool":"search_media","args":{"query":"猫","person":"","fromMs":"","toMs":"","hasFace":""},"timeout_ms":120000}
```

`args` 为工具参数 JSON 对象（非字符串转义）。该帧**不落事件流**——它是传输层信号；Action 事件已由 `onToolCallStarting` 落流，二者经事件 id 无关联需求（`request_id` 只活在桥层）。

### 3.2 上行：`POST /agent/sessions/{id}/tool-results`

请求：

```json
{"request_id":"tr_abc","ok":true,"result":"找到 3 张…"}
```

- `ok=false` 时 `result` 为错误描述文本，云端以 `error: <result>` 作为工具返回值
- 鉴权：全局拦截器（X-App-Token）；会话归属校验（他人会话 404）
- 响应：`200 {"accepted":true}`；request_id 未知或已完成 → `409 {"error":"tool_request_not_pending"}`

### 3.3 服务端执行语义

`DeviceTool.execute(args)`（Koog Tool 适配器，经 reflect 注册进当轮 ToolRegistry）：

1. 生成 `request_id`，向 `AgentBridgeRegistry` 注册 `CompletableDeferred`
2. 经 runner 的 `onToolRequest` 回调向 SSE writer 写 §3.1 帧（**写帧失败 = 端已断线 → 立即以 `error: device_offline` 返回**，不挂起）
3. `withTimeout(120s)` 挂起等上行结果
4. 超时 → 返回 `"error: device_tool_timeout"` 文本（Observation 照常落流，pending 闭环）
5. 结果到达 → 返回结果文本

工具结果进 Koog 后即走 M1 既有路径（`onToolCallCompleted` 落 Observation，source=ENVIRONMENT，role=TOOL）。

### 3.4 错误矩阵

| 场景 | 行为 |
|------|------|
| LLM 上游失败 | M1 语义不变：Finish(error) + 会话 ERROR + 网关配额回退 |
| 端断线（写帧失败） | 工具返回 `error: device_offline`，LLM 拿到错误继续或收尾 |
| 工具超时（120s） | 工具返回 `error: device_tool_timeout` |
| 迟到/伪造 tool-results | 409，不影响运行中会话 |
| 重复 tool-results | 409（deferred 已完成即视为非 pending） |
| 端执行异常 | `ok=false` 上行，云端工具返回 `error: <描述>` |
| 服务重启 | 内存挂起表清空，在等工具的一轮随进程终止；会话行残留 RUNNING（无心跳机制，M2 不引入），下一条 message 触发 M1 pending 重放——孤儿 Action 因设备工具不在服务端注册表 → error Observation 闭环后正常继续 |

## 4. 服务端组件 [agent-task:cloud-m2-server]

- **Scope**：`server/src/main/kotlin/com/mamba/picme/server/agent/`
- **新增**：
  1. `AgentBridgeRegistry.kt`——`register(requestId): CompletableDeferred<String>` / `complete(requestId, result): Boolean` / `failAll(sessionId, reason)`（连接断开时清理）；内存 ConcurrentHashMap，单进程语义
  2. `DeviceToolService.kt`——`@Tool` 反射工具集（`get_gallery_summary` / `search_media` / `refine_media_search`，描述对齐端侧同名工具）+ 转 Koog `ToolRegistry`；每个工具的 execute 走 §3.3 语义
  3. `AgentRoutes.kt` 扩展——`POST /agent/sessions/{id}/tool-results` 端点
  4. `AgentRunner.runTurn` 扩展——新增 `onToolRequest: suspend (ToolRequestFrame) -> Unit` 回调 + `bridge: AgentBridgeRegistry` + 设备工具集注入（DeviceTool 经回调写帧）
- **约束**：`AgentBridgeRegistry` 的挂起条目必须设超时兜底且随 SSE 连接关闭清理（防泄漏）；帧写出与事件帧写出共用同一 writer（保序）
- **Acceptance**：AC-M2-S1（工具全链路）、AC-M2-S2（超时/断线/409）

## 5. Android 组件 [agent-task:cloud-m2-android]

- **Scope**：`androidApp/src/main/java/com/mamba/picme/features/cloudagent/`（新包）+ `core/agenttools/`
- **新增**：
  1. `CloudAgentClient.kt`——OkHttp；`createSession/listSessions/getEvents(after,limit)/finish` + `postMessage(sessionId, content): Flow<CloudFrame>`（SSE 逐帧）+ `postToolResult(sessionId, requestId, ok, result)`；BYOK 会话的 `postMessage` 自动带 `X-User-Api-Key`（M1 语义：缺头 400；从当前选中 BYOK 配置取）
  2. `DeviceToolExecutor.kt`——`suspend fun execute(tool: String, args: JsonObject): String`；映射 `get_gallery_summary`/`search_media`/`refine_media_search` → `ChatToolService.getInstance()` 对应 handler；结果 ≤32KB 截断（AppToolExecutor 范式）；未知工具返回 `error: unknown_tool`
  3. `CloudAgentChatViewModel.kt`——状态：`Idle / Loading / Streaming(messages) / Error`；消息为内存态 data class 列表；遇 `tool_request` 帧 → DeviceToolExecutor 执行 → 上行 → 继续收帧
  4. `CloudAgentChatScreen.kt`——最小聊天页：消息气泡列表（复用现有文本/Markdown 渲染组件）+ 输入框 + 发送/停止；顶部通道标记（网关/BYOK）
  5. 调试入口：SettingsScreen 调试区加「云端 Agent（实验）」入口（创建或复用会话后进入聊天页）
- **BYOK 授权（[agent-task:cloud-m2-byok-ui]）**：
  1. SettingsScreen 远程模型区（`RemoteModelsListSection` 旁）加开关「允许云端 Agent 使用我的 API Key」+ 副文案「Key 仅在会话期间随请求发送至服务器内存处理，服务器不保存、不写日志」；DataStore 持久化 `cloud_agent_byok_consent`，默认关
  2. 建会话时：当前选中 BYOK 供应商且开关开 → `channel=byok`；BYOK 供应商但开关关 → 弹窗明示「云端 Agent 需要授权 Key 透传，或切换到网关通道」+ [去设置] [使用网关]（ADR-018 决策 4 兜底语义，不静默回退端侧编排冒充云端）
  3. 五语同步（EN/zh-CN/zh-TW/ES/FR），[I18N] 红线
- **约束**：BYOK key 端侧只在现有 DataStore 供应商配置一处存储，云端链路新增任何日志/埋点不得包含 key（守卫测试）；实验页豁免 PARITY 固化（转正时再入 `08-UI-SPECS`）
- **Acceptance**：AC-M2-A1（端侧全链路）、AC-M2-A2（授权门）

## 6. 通道选择规则（建会话时一次定型）

| 当前选中远程模型 | BYOK 授权开关 | 云端会话通道 |
|------------------|---------------|--------------|
| 网关（picme-server） | 无关 | `gateway`（M1 配额语义） |
| BYOK 供应商 | 开 | `byok`（model/baseUrl 取供应商配置；key 逐请求透传） |
| BYOK 供应商 | 关 | 弹窗二选一：去授权 / 改用网关 |

会话的 `channel`/`model`/`byok_base_url` 建会话时固化（M1 schema 已支持），中途换供应商不影响进行中的会话。

## 7. 数据流（一轮含端侧工具的时序）

```
App: POST /agent/sessions/{id}/messages {content:"我上周拍的猫"}
  → Server: 落 user Message 事件 → SSE 帧 event
  → Koog LLM 轮 → 决定调 search_media（DeviceTool）
  → Server: 落 Action 事件 → SSE 帧 event + 帧 tool_request → 挂起
  → App: 收 tool_request → DeviceToolExecutor → ChatToolService.searchMedia → 文本结果
  → App: POST tool-results {request_id, ok:true, result}
  → Server: deferred complete → Koog 继续 → 落 Observation → LLM 二轮 → 答复
  → Server: 落 assistant Message + Finish → SSE 帧 event×2 + done
```

## 8. 测试策略

**Server（JUnit + MockEngine + testApplication，沿用 M1 基建）**：

- `AgentBridgeRegistryTest`：complete 正常返回 / 超时抛 TimeoutCancellationException / 重复 complete 返回 false / failAll 清理
- `AgentRoutesBridgeTest`（集成）：
  - AC-M2-S1：MockEngine 第一轮返回 `tool_calls[search_media]` → SSE 出现 tool_request 帧 → 测试客户端 POST tool-results → MockEngine 第二轮返回最终文本 → SSE 收到 Observation 事件帧 + assistant + done；事件流含完整 Action→Observation 闭环
  - AC-M2-S2：tool-results 伪造 request_id → 409；DeviceTool 超时（缩短超时注入）→ Observation 内容为 timeout 错误
- 回归：M1 既有 42 测试全绿

**Android（JUnit + MockWebServer/假 executor）**：

- `CloudAgentClientTest`：SSE 帧解析（event/tool_request/done/error 四型）、tool-results 上行编码
- `DeviceToolExecutorTest`：工具名映射、未知工具 error、>32KB 截断
- `CloudAgentChatViewModelTest`：状态机（发送→流式→工具请求→上行→完成）、错误帧 → Error
- BYOK 守卫：授权开关关 + BYOK 供应商 → 建会话弹窗分支（不发出请求）；授权开 → 请求带 X-User-Api-Key 且 key 不出现在任何日志/埋点参数（静态断言 + 单测）

## 9. 里程碑与验收

**M2 一次性交付**（服务端与端侧同分支，服务端先行可独立测）：

| AC | 描述 | 验证 |
|----|------|------|
| AC-M2-S1 | 端侧只读工具全链路闭环（Action→tool_request→上行→Observation→答复） | 服务端集成测试 + 真机遛狗 |
| AC-M2-S2 | 超时/断线/伪造结果的错误语义 | 服务端测试 |
| AC-M2-A1 | 调试入口进入云端聊天页，网关通道完整对话（含一次工具调用） | 真机遛狗 |
| AC-M2-A2 | BYOK 授权门：未授权弹窗、授权后 key 透传且双端零留存 | 单测 + 真机抓包 |
| AC-M2-DOC | server/AGENTS.md 路由清单与包结构同步；androidApp AGENTS.md 新包登记；ADR-018 §6 状态更新 | 文档检查 |

## 10. 交付沉淀管线（DoD 提醒）

合 main 前：桥协议 → `docs/03-TECHNICAL-SPECS/`（CHAT_PROTOCOL 或新 TECH_SPEC）；路由/包结构 → `server/AGENTS.md`；端侧实现约束 → `androidApp/AGENTS.md`；ADR-018 §6 状态表更新；本 spec `git rm`（git 历史即归档），README §6 白名单摘除。
