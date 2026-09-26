# IM 远程控制技术规格

> **版本**: 1.1  
> **状态**: 重新激活（多通道）  
> **最后更新**: 2026-08-03（重复 §13 编号修正为 §14；§14.5 失效文件清单标注）  
> **上一更新**: 2026-07-27（从「已冻结」重新激活，新增 Telegram + 单通道选择 + release 凭据隔离）  
> **维护者**: RD Agent  

> ℹ️ 本线于 2026-07-16 冻结、2026-07-27 重新激活以承载多通道能力。端侧直连（飞书 WebSocket / Telegram 长轮询）与服务端中转方案并行存在，互不替代。
>
> **历史变更**：
> - 2026-06-17：新增产品线，通过飞书等 IM + LLM 实现 App 远程控制
> - 2026-06-17：架构从 SCF Relay Server 变更为设备端直连飞书 WebSocket
> - 2026-07-03：从 P0 降级为 P2 实验线
> - 2026-07-16：正式冻结，资源投入以 `PRODUCT.md` 为准，服务端方案优先
> - 2026-07-27：重新激活；抽象 RemoteChannel 接口 + RemoteChannelManager 单通道激活，新增 Telegram（Pengrad 长轮询 + chatId 白名单 fail-closed），release 不再打包飞书凭据，通道配置迁出独立设置页
> - 2026-08-03：重复的第二个「## 13.」（ReAct Agent 工具调用实现指引）改为「## 14.」；§14.5 引用的 InApp*/ToolRegistry 等文件已不存在，整节标注失效

---

## 0. 多通道扩展（2026-07-27 重新激活）

> 详细设计稿与实现计划均已随落地清理（git 历史可查），本节即现行事实源。

- **`RemoteChannel` 接口**（`domain/agent/remote/RemoteChannel.kt`）：统一 `channelId / isConnected / onMessageReceived / sendMessage(text, replyToken) / sendImage`。`replyToken` 通道不透明（飞书=messageId，Telegram=chatId）。
- **`RemoteChannelManager`**：单通道管理器，按 `selected_remote_channel`（FEISHU/TELEGRAM/NONE）激活；先断旧再连新；重连 = 重新 activate；发送/回调委托激活通道。
- **`TelegramChannelHandler`**：Pengrad `java-telegram-bot-api:5.5.0` 长轮询（getUpdates），出站、无需公网 IP；仅处理 `allowedChatId` 白名单内消息（fail-closed，未配置则拒绝全部）。
- **`FeishuChannelHandler`**：实现 `RemoteChannel`，行为不变（出站 WebSocket + OAPI HTTP）。
- **调度器去飞书化**：`RemoteCommandDispatcher` 持 `RemoteChannel`，会话 ID 动态取 `channel.channelId`；`AgentOrchestrator.processFeishuInput` 改名 `processRemoteImInput`。
- **release 凭据隔离**：`FEISHU_APP_ID/SECRET` 的 `buildConfigField` 仅在 `buildTypes.debug` 注入；`defaultConfig` 空串 → release 不打包开发者凭据，用户在「设置 → 通信通道」页自行配置。
- **配置页**：设置主页网格一级入口「通信通道」→ 独立二级页（通道选择 chips + 飞书/Telegram 凭据 + 连接状态）。

---

## 1. 架构总览

### 1.1 核心拓扑

![飞书远程控制架构](assets/diagrams/im-feishu-architecture.png)

### 1.2 关键变化：去除 SCF Relay Server

| 之前（SCF 方案） | 之后（直连方案） |
|----------------|----------------|
| 飞书 Webhook → SCF → 设备 WebSocket | 设备直连飞书 WebSocket（出站连接） |
| 图片经 SCF 临时存储中转 | 设备直接上传到飞书 OAPI |
| SCF 管理设备在线状态 | 飞书 SDK 内置 WS 连接管理 |
| 需维护 SCF/Workers 云函数 | 零云端基础设施 |

### 1.3 消息流转路径

全链路：用户 → 飞书消息 → 飞书开放平台 → WebSocket 推送 → PoLang 设备端 → `FeishuChannelHandler` → `RemoteCommandDispatcher` → LLM 解析意图 → `CapabilityRegistry.dispatch()` → 结果经飞书 OAPI HTTP 回复 → 用户。

### 1.4 组件职责

| 组件 | 位置 | 职责 | 技术选型 |
|------|------|------|----------|
| **FeishuChannelHandler** | Android 端 (`domain/agent/remote/`) | 飞书 WebSocket 连接、消息接收/回复、图片上传 | 飞书 OAPI SDK (`com.larksuite.oapi:oapi-sdk:2.5.3`) |
| **RemoteCommandDispatcher** | Android 端 (`domain/agent/remote/`) | 命令解析、LLM 意图理解、Capability 分派 | Kotlin + 现有 LLM 链路 |
| **RemoteControlCapability** | Android 端 (`domain/agent/capability/`) | 设备绑定状态管理、操作审计 | Kotlin + Capability 接口 |
| **RemoteChatEngine / RemoteReActAgent** | Android 端（复用现有） | 复杂意图的 LLM 解析 | 现有远程推理链路（经 AgentOrchestrator） |

---

## 2. 飞书 SDK 集成

### 2.1 依赖引入

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.larksuite.oapi:oapi-sdk:2.5.3")
}

// packaging 配置（避免 META-INF 冲突）
packaging {
    resources {
        excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
        )
    }
}
```

### 2.2 FeishuChannelHandler

```kotlin
class FeishuChannelHandler(
    private val scope: CoroutineScope,
    private var appId: String,
    private var appSecret: String,
) {
    // 飞书 OAPI HTTP 客户端（用于回复消息/上传图片）
    private var apiClient: com.lark.oapi.Client? = null
    // 飞书 WebSocket 客户端（接收消息事件）
    private var wsClient: com.lark.oapi.ws.Client? = null

    // 事件分发器
    private val eventHandler: EventDispatcher by lazy {
        EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1 { event ->
                handleMessageEvent(event)
            }
            .build()
    }

    fun init() {
        apiClient = com.lark.oapi.Client.newBuilder(appId, appSecret).build()
        wsClient = com.lark.oapi.ws.Client.Builder(appId, appSecret)
            .eventHandler(eventHandler)
            .build()
        scope.launch { wsClient?.start() }
    }

    // 回复文本消息（经飞书 OAPI HTTP）
    fun sendMessage(content: String, messageId: String) {
        scope.launch {
            apiClient?.im()?.message()?.reply(
                ReplyMessageReq.newBuilder()
                    .messageId(messageId)
                    .replyMessageReqBody(/* ... */)
                    .build()
            )
        }
    }

    // 回复图片（直接上传到飞书 OAPI）
    fun sendImage(imageBytes: ByteArray, messageId: String) { /* ... */ }

    fun disconnect() { wsClient?.let { /* 关闭连接 */ } }
}
```

**机制说明**：
- **WebSocket 接收**：设备通过飞书 SDK 的 `ws.Client` 与飞书平台建立长连接，接收 `P2MessageReceiveV1` 事件
- **OAPI HTTP 回复**：通过飞书 REST API 回复消息、上传图片
- **连接方向**：出站连接（设备 → 飞书），无需公网 IP，不受 NAT 限制

### 2.3 飞书 Bot 配置

| 配置项 | 说明 |
|-------|------|
| **App ID / App Secret** | 飞书开放平台自建应用 -> 凭证与基础信息 |
| **事件订阅** | 无需配置 Webhook URL（使用 WebSocket 模式） |
| **事件类型** | 订阅 `im.message.receive_v1` |
| **Bot 权限** | `im:message`、`im:resource`（发送/接收消息、上传/下载图片） |

---

## 3. 设备端组件设计

### 3.1 组件架构

![飞书远程控制架构（Android 端组件）](assets/diagrams/im-feishu-architecture.png)

### 3.2 RemoteCommandDispatcher

```kotlin
class RemoteCommandDispatcher(
    private val channelHandler: FeishuChannelHandler,
    private val capabilityRegistry: CapabilityRegistry,
    private val orchestrator: AgentOrchestrator,  // 复用远程推理链路（RemoteChatEngine/RemoteReActAgent）
) {
    /**
     * 接收飞书消息并分派执行
     */
    suspend fun dispatch(message: FeishuMessage)

    /**
     * 执行流程：
     * 1. 解析消息意图（直接映射 / LLM 解析）
     * 2. 逐条调用 Capability 执行
     * 3. 收集结果 → 通过 FeishuChannelHandler 回复
     */
}
```

**命令解析策略**：

| 命令类型 | 解析方式 | 示例 |
|----------|----------|------|
| **明确动作** | 直接映射 | `action: "browse_recent"` → GalleryCapability |
| **自然语言** | LLM 解析 | `"帮我优化这张照片"` → LLM → `edit_image / ai_optimize` |
| **组合命令** | LLM 分解 | `"裁剪成1:1再调亮"` → LLM → `[crop(1:1), brightness(+20)]` |

### 3.3 RemoteControlCapability

与现有实现一致（见 [RemoteControlCapability.kt](../../androidApp/src/main/java/com/mamba/picme/domain/agent/capability/RemoteControlCapability.kt)），管理设备绑定状态、自动确认模式，不通过 AgentCommand 密封类分发。

### 3.4 图片回传流程

```
命令执行完成 → 生成结果图片 → 直接通过飞书 OAPI uploadImage()
    → 获取 imageKey → ReplyMessage with msgType="image" → 用户可见
```

无需经过任何云端中转，图片直接从设备上传到飞书平台。

---

## 4. 设备绑定流程

1. App 内生成绑定码 → 在飞书 Bot 中输入绑定码（或扫码）
2. `FeishuChannelHandler` 收到绑定消息 → `RemoteControlCapability.updateBinding()`
3. `RemoteControlCapability` 记录绑定状态
4. 通过飞书回复「设备 Xiaomi 15 Ultra 已绑定成功 ✅」

**首次绑定的安全性**：
- 绑定码在 App 内生成，有效期内可配对
- 解绑操作需设备端确认（自动确认模式关闭时）
- 支持远程解绑（双重确认）

---

## 5. 飞书 Bot 设计

### 5.1 Bot 能力

| 能力 | 说明 |
|------|------|
| **消息接收** | 通过 WebSocket 接收文本/图片消息 |
| **消息发送** | 通过 OAPI HTTP 发送文本/图片/卡片消息 |
| **交互式卡片** | 按钮点击、下拉选择、滑块（卡片回调通过 WS 接收） |
| **文件上传** | 设备端直接上传图片到飞书（不走云端中转） |
| **事件订阅** | 消息事件通过 WebSocket 实时推送 |

### 5.2 交互式卡片设计

与之前方案一致（见飞书卡片设计部分），通过 OAPI 发送 `interactive` 消息卡片，回调事件经 WebSocket 返回设备处理。

### 5.3 命令确认流程

![危险命令确认流](assets/diagrams/im-confirm-timeout.png)

**确认策略矩阵**：

| 操作类型 | 确认要求 | 实现 |
|----------|----------|------|
| 浏览/搜索 | 不要求 | 直接执行 |
| 图片编辑（非人像）| 不要求 | 直接执行 |
| 图片编辑（人像）| 确认 | 发送交互式卡片 |
| 批量操作 | 确认 | 发送交互式卡片 |
| 删除操作 | 确认 | 发送交互式卡片 |
| 设备解绑 | 双重确认 | App 内确认 + 飞书确认 |
| 系统设置修改 | 确认 | 发送交互式卡片 |

---

## 6. 离线与异常处理

### 6.1 设备离线场景

![设备离线处理](assets/diagrams/im-offline-reconnect.png)

- 飞书 SDK 内置重连机制（指数退避）
- 无离线命令队列（相比 SCF 方案，这是唯一的能力损失：设备离线期间的命令不会缓冲）
- 可通过本地通知提醒用户打开 App

### 6.2 命令超时

- 简单命令（浏览/搜索）：超时 15s
- 编辑命令（单张）：超时 60s
- 批量编辑命令：超时 5min + 每张加 30s
- 超时后回复 "处理超时，请稍后重试"

### 6.3 图片大小限制

| 阶段 | 限制 | 处理 |
|------|------|------|
| 飞书接收 | 20MB | 飞书平台限制 |
| 设备回复 | 20MB | 设备端自动压缩（> 10MB 压缩到 80% 质量） |

---

## 7. 隐私与安全

### 7.1 数据生命周期

```
图片 → 飞书平台 → 设备下载(经飞书) → 设备端处理 → 结果直接上传飞书
                                                        ↓
                                               飞书平台管理存储生命周期
```

- **不再需要**：额外的云端临时存储、24h TTL 清理逻辑
- **所有图片**直接经飞书平台流转，不经过任何第三方服务器
- 人脸数据强制设备端执行，不上传

### 7.2 安全红线

| 红线 | 实现方式 |
|------|----------|
| **人脸数据不离开设备** | 人脸检测/美颜强制设备端执行 |
| **传输加密** | 飞书平台全程 HTTPS + TLS |
| **身份认证** | App ID + App Secret，飞书平台标准鉴权 |
| **设备授权** | 首次绑定需 App 内配对码确认 |
| **操作审计** | 所有远程命令记录本地日志 |

---

## 8. 集成与扩展

### 8.1 多 IM 平台适配层（规划中）

```
┌──────────────────────────────────────────┐
│           IM Platform Adapter           │
│  ├── FeishuAdapter (飞书) ✅ 首批       │
│  ├── WecomAdapter (企业微信 - 规划中)    │
│  └── DingtalkAdapter (钉钉 - 规划中)    │
│                                          │
│  统一接口：                                │
│  interface ImPlatform {                  │
│    fun connect(appId, appSecret)         │
│    fun sendMessage(chatId, content)      │
│    fun sendCard(chatId, card)            │
│    fun uploadMedia(file)                 │
│    fun disconnect()                      │
│  }                                       │
└──────────────────────────────────────────┘
```

### 8.2 与现有远程推理链路的协同

链路：飞书消息 → `FeishuChannelHandler` → `RemoteCommandDispatcher` → LLM 解析意图（复用 Koog OpenAILLMClient）→ `CapabilityRegistry.dispatch()` → 结果经 `sendMessage`/`sendImage` 回复。

- IM 远程的 LLM 调用与 App 内共享同一 Koog `OpenAILLMClient`
- 使用独立 System Prompt（IM 场景上下文不同）
- Capability 执行层完全复用

---

## 9. 任务拆分 [agent-task]

### Phase 1: 飞书集成与基础能力 (RD)

- [ ] `agent-task:im-remote-001` 引入飞书 OAPI SDK 依赖，配置 packaging exclude
- [ ] `agent-task:im-remote-002` 实现 FeishuChannelHandler（WebSocket 连接 + OAPI 客户端）
- [ ] `agent-task:im-remote-003` 实现飞书消息接收与文本/图片回复
- [ ] `agent-task:im-remote-004` 实现 RemoteCommandDispatcher（命令接收/LLM解析/分派）
- [ ] `agent-task:im-remote-005` 实现设备绑定流程（配对码 + 状态管理）

### Phase 2: 核心命令执行 (RD)

- [ ] `agent-task:im-remote-006` 实现远程相册浏览/搜索命令
- [ ] `agent-task:im-remote-007` 实现远程图片编辑命令（单张）
- [ ] `agent-task:im-remote-008` 实现结果图片直接上传飞书并回复
- [ ] `agent-task:im-remote-009` 实现远程相册管理命令（创建/移动/删除）

### Phase 3: 进阶功能 (RD)

- [ ] `agent-task:im-remote-010` 实现交互式卡片模板与确认流程
- [ ] `agent-task:im-remote-011` 实现多设备管理与@指定
- [ ] `agent-task:im-remote-012` 实现批量编辑命令
- [ ] `agent-task:im-remote-013` 实现操作审计日志

### Phase 4: 集成与测试 (QA)

- [ ] `agent-task:im-remote-014` 端到端测试：飞书消息 → 设备执行 → 结果回传
- [ ] `agent-task:im-remote-015` 性能基准：命令响应延迟、图片处理耗时
- [ ] `agent-task:im-remote-016` 异常场景测试：设备离线、命令超时、网络断开

---

## 10. 验收标准 (AC)

| ID | 验收项 | 优先级 |
|----|--------|--------|
| AC-IM-1 | 飞书发送"最近有什么照片" → 返回设备相册结果卡片 | P0 |
| AC-IM-2 | 飞书发送图片 + "帮我优化" → 设备端执行 AI 优化 → 返回处理后图片 | P0 |
| AC-IM-3 | 设备离线时返回友好提示，不阻塞后续命令 | P0 |
| AC-IM-4 | 飞书发送"黑白滤镜" → 设备端执行滤镜 → 返回效果图 | P1 |
| AC-IM-5 | 多设备绑定后，支持@指定设备执行命令 | P1 |
| AC-IM-6 | 编辑参数交互式卡片支持增减调节 | P2 |
| AC-IM-7 | 删除操作需用户确认后执行 | P1 |
| AC-IM-8 | 操作审计日志正确记录所有远程命令 | P2 |
| AC-IM-9 | 飞书命令响应 < 3s（含 LLM 推理 + 设备执行） | P0 |
| AC-IM-10 | 图片处理 < 5s @1080p | P1 |
| AC-IM-11 | **零云端基础设施**：无需部署任何云函数/Relay Server | P0 |

---

## 11. 与 ApkClaw 方案对比

| 维度 | ApkClaw | PoLang 方案 |
|-------|---------|-----------|
| **消息接收** | 飞书 WS SDK | 飞书 WS SDK（相同） |
| **消息回复** | 飞书 OAPI HTTP | 飞书 OAPI HTTP（相同） |
| **命令执行** | AccessibilityService + ToolRegistry | **Capability 系统**（复用现有架构） |
| **LLM 集成** | LangChain4j (OpenAI/Anthropic) | **Koog OpenAILLMClient**（复用现有链路） |
| **目标** | 通用 Android 自动化 | **专注相册+图片编辑**（核心能力更深） |

**核心差异**：PoLang 不需要 AccessibilityService 来做通用 UI 自动化。我们的 Capability 系统直接操作相册/编辑内核，稳定性和响应速度优于无障碍节点遍历。

---

## 12. 远程推理与本地推理协议隔离

> **核心设计原则（2026-06-18）**：远程推理原生支持 OpenAI tool_calls 格式，与本地 LLM 的 method/params 格式完全隔离，不产生任何耦合。

> ⚠️ **历史记录（2026-08-02 更新）**：端侧**文本** LLM 链路已移除，下文「本地推理链路（端侧 LLM）」/ `LocalCommandParser` / method/params 协议仅为隔离设计的历史参考。命令解析曾走远程 ~~`ToolCallCommandParser`~~（标准 OpenAI tool_calls，已随 Phase 5 删除），tool_calls 现由 Koog agent 循环内直接 `CapabilityRegistry.dispatch` 执行。

### 12.1 协议分层

```
┌─────────────────────────────────────────────────────────────┐
│                     用户输入层                               │
│         飞书消息 / 语音 / 本地语音唤醒                        │
└─────────────────────────────────────────────────────────────┘
                              │
        ┌─────────────────────┴─────────────────────┐
        ▼                                           ▼
┌───────────────┐                         ┌───────────────────┐
│  远程推理链路  │                         │   本地推理链路     │
│  (云端 LLM)   │                         │  (端侧 LLM)       │
├───────────────┤                         ├───────────────────┤
│ OpenAI        │                         │ 自定义 JSON 数组   │
│ tool_calls    │                         │ method/params     │
│ protocol      │                         │ protocol          │
│               │                         │                   │
│ ToolExecution │                         │ LocalCommand      │
│ Request       │                         │ Parser            │
│ (name +       │                         │ (method + params) │
│ arguments)    │                         │                   │
└───────┬───────┘                         └─────────┬─────────┘
        │                                           │
        ▼                                           ▼
┌─────────────────────────────────────────────────────────────┐
│              ToolCallCommandParser                          │
│         直接解析为 AgentCommand（共用命令模型）              │
│              ↓ 与 LocalCommandParser 完全隔离                │
├─────────────────────────────────────────────────────────────┤
│              CapabilityRegistry.dispatch()                   │
│                    统一执行层                                │
└─────────────────────────────────────────────────────────────┘
```

### 12.2 关键隔离点

| 维度 | 远程推理 | 本地推理 | 隔离方式 |
|------|----------|----------|----------|
| **输入格式** | `{"tool_calls":[{"function":{"name":"...","arguments":"..."}}]}` | `[{"method":"...","params":{}}]` | 不同 Parser |
| **解析器** | `ToolCallCommandParser` | `LocalCommandParser` | 独立文件，无互相调用 |
| **命令模型** | `AgentCommand` (sealed class) | `AgentCommand` (sealed class) | 共用 |
| **执行层** | `CapabilityRegistry.dispatch()` | `CapabilityRegistry.dispatch()` | 共用 |
| **Prompt 格式** | `name` + `arguments` | `method` + `params` | 不同 Builder |
| **LLM 输出** | 原生 function calling | 文本 JSON 数组 | 不同协议 |

### 12.3 禁止的耦合模式

以下模式已被彻底移除，远程推理链路不再使用：

| 反模式 | 说明 | 状态 |
|--------|------|------|
| `parseAgentCommand()` | 将 method/params 转换为 AgentCommand | 已删除 |
| `mergeParamsIntoRoot()` | 合并 params 到根对象 | 已删除 |
| 在 Prompt 中混合 `method`/`params` | L3 Plan 使用 `command` 字段 | 已更新 |
| `CameraToolHelper` 调用 `LocalCommandParser` | 直接构建 AgentCommand | 已重构 |
| `parseToolCalls` 回退到 method/params | ~~直接使用 `ToolCallCommandParser`~~（已随 Phase 5 删除；tool_calls 由 Koog agent 循环内直接 `CapabilityRegistry.dispatch`） | 已重构 |

### 12.4 实现文件

| 文件 | 职责 | 协议 |
|------|------|------|
| ~~`ToolCallCommandParser.kt`~~ | ~~远程 tool_calls 解析~~（已随 Phase 5 删除；tool_calls 由 Koog agent 循环内直接 `CapabilityRegistry.dispatch`） | — |
| ~~`LocalCommandParser.kt`~~ | ~~本地 method/params 解析~~（已随端侧文本 LLM 移除） | — |
| ~~`RemoteOrchestrator.kt`~~ | ~~远程编排器~~（类不存在；现由 `RemoteChatEngine`/`AgentConfigurator` 承担） | — |
| `RemotePromptBuilder.kt` | 远程 Prompt 构建 | `name` + `arguments` 格式 |
| `CameraToolHelper.kt` | 相机命令辅助 | 直接构建 `AgentCommand` |
| ~~`InAppAgentConfig.kt`~~ | ~~本地 System Prompt~~（已移除，见 §13 失效提示） | — |

---

## 13. 相关文档

| 文档 | 说明 |
|------|------|
| `../../docs/01-PRODUCT/FEATURES.md#5-im-远程控制实验性融合入口---p2` | 产品交互规范 |
| `../../PRODUCT.md` | 产品路线图与里程碑 |
| `../../docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | Agent 架构详细设计 |
| `../../docs/02-ARCHITECTURE/AGENT_ARCHITECTURE.md` | 远程推理架构（LLM 复用层） |
| `../../app/.../domain/agent/remote/FeishuChannelHandler.kt` | 飞书通道实现（待实现） |
| `../../app/.../domain/agent/capability/RemoteControlCapability.kt` | 远程控制管理 Capability |

---

> **维护者**：RD Agent
> **最后更新**：2026-06-19
> **方案变更**：~~SCF Relay Server~~ → 设备端直连飞书 WebSocket（参考 ApkClaw 方案）
> **协议隔离**：远程 tool_calls 与本地 method/params 已彻底解耦
> **DeepSeek 适配**：
> - Prompt 移除具体 tool_calls JSON 示例，避免模型输出到 content 字段
> - API 请求自动禁用 thinking 模式（DeepSeek V4 系列）
> - ToolSpec 自动添加 `additionalProperties: false` 以兼容 strict 模式
> - 参考文档：https://api-docs.deepseek.com/zh-cn/guides/tool_calls
> **状态**：Phase 1 实现中 · ReAct Agent 工具调用已验证

---

## 14. ReAct Agent 工具调用实现指引

> **来源**：2026-06-18 飞书远程控制"打开相机"导航失败问题复盘
>
> 本章节总结 ToolSpec 实现过程中的关键陷阱与最佳实践，供后续批量补充工具时参考。
>
> ⚠️ **失效提示（2026-08-03 更新）**：本节为历史复盘记录。其中引用的 `InAppLlmClient` / `InAppAgentConfig` / `InAppAgentService` / `LangChain4jToolBridge` / `ToolRegistry` / `BaseUiTool` / `NavigateToTool` 等类已在 Agent 架构重构中移除；当前工具面为 `CameraToolService` / `ChatToolService`（@Tool 方法）→ Koog agent 循环内直接 `CapabilityRegistry.dispatch`（~~`ToolCallCommandParser`~~ 已随 Phase 5 删除）。本节的方法论（Prompt 设计、空字符串处理、content 回退解析）仍有参考价值，文件路径不再有效。

### 14.1 问题复盘

**现象**：用户通过飞书发送"打开相机"，LLM 输出了正确的 `navigate_to` 指令 JSON，但手机端未执行导航，而是把 JSON 文本直接回复给了飞书。

**日志特征**：
```
content: {"tool_calls":[{"id":"call_1",...}]}  ← 工具调用 JSON 出现在 content 中
task complete (no tool calls)                    ← 走了无工具调用路径
```

### 14.2 根因分析（三层陷阱）

| 层级 | 问题 | 影响 | 修复文件 |
|------|------|------|----------|
| **Prompt 误导** | System Prompt 提供了完整的 `{"tool_calls":[...]}` JSON 示例，模型把这个 JSON 当作应该在 `content` 中输出的文本 | LLM 把 tool_calls 输出到 content 字段而非原生 function calling | `InAppAgentConfig.kt` |
| **空字符串陷阱** | API 返回的 `content` 为空字符串 `""`（而非 `null`），`isNullOrEmpty()` 判断失效 | 空字符串被序列化到消息历史，污染后续推理；API 看到 content 存在可能忽略 tool_calls | `InAppLlmClient.kt`, `InAppAgentService.kt` |
| **解析缺失** | `parseResponse` 只检查原生 `tool_calls` 字段，没有处理嵌入 content 的情况 | 即使 content 中有正确 JSON，也走"无工具调用"路径直接返回文本 | `InAppLlmClient.kt` |

### 14.3 修复方案

#### 14.3.1 Prompt 设计原则（DeepSeek 适配）

**核心认知**：tool_calls 是 `message` 对象的独立字段，与 `content` 互斥。标准响应格式为：
```
choices[0].message: {
  "role": "assistant",
  "content": null,
  "tool_calls": [...]
}
```

**禁止**：在 Prompt 中提供具体的 tool_calls JSON 格式示例
```
❌ 错误示例（会导致模型模仿输出到 content）：
正确格式：
```json
{"tool_calls":[{"id":"call_1","type":"function",...}]}
```
```

**正确**：描述 function calling 机制，让模型使用原生 API
```
✅ 正确示例：
本系统支持 OpenAI 格式的函数调用（function calling）。
当你需要执行工具时，直接发起函数调用，系统会自动解析并执行。
不要在回复文本中输出 JSON 格式的 tool_calls。
```

**DeepSeek 特殊要求**：
- 使用 DeepSeek V4 系列模型时，API 请求必须禁用 thinking 模式（`thinking: {"type": "disabled"}`）
- 参考文档：https://api-docs.deepseek.com/zh-cn/guides/tool_calls
- 禁用 thinking 可避免模型在 reasoning 中分析工具调用但最终不输出 tool_calls 字段的问题

#### 14.3.2 空字符串处理规范

所有涉及 `content` 字段解析/序列化的位置必须使用 `isNotBlank()`：

| 位置 | 方法 | 修复前 | 修复后 |
|------|------|--------|--------|
| 解析响应 | `parseResponse` | `optString("content")` | `optString("content", "").isNotBlank()` |
| 序列化消息 | `convertMessage` | `!isNullOrEmpty()` | `!isNullOrBlank()` |
| 推送思考 | `runAgentLoop` | `!isNullOrEmpty()` | `!isNullOrBlank()` |

**关键区别**：
- `isNullOrEmpty()`：`null` → true, `""` → true, `" "` → false ❌
- `isNullOrBlank()`：`null` → true, `""` → true, `" "` → true ✅

#### 14.3.3 Content 回退解析机制（DeepSeek 兼容）

当 API 未返回原生 `tool_calls` 但 `content` 中包含 `{"tool_calls":[...]}` 时，使用正则表达式提取并解析。

**DeepSeek 文档提示**：某些情况下模型会将工具调用信息放入 content 字段，客户端需实现 fallback 解析。

实现代码：

```kotlin
private fun extractToolCallsFromContent(content: String): List<ToolExecutionRequest> {
    val result = mutableListOf<ToolExecutionRequest>()
    try {
        val toolCallsRegex = Regex("""\{\s*"tool_calls"\s*:\s*(\[.*?\])\s*\}""", RegexOption.DOT_MATCHES_ALL)
        val match = toolCallsRegex.find(content)
        if (match != null) {
            val toolCallsJson = match.groupValues[1]
            val array = JSONArray(toolCallsJson)
            for (i in 0 until array.length()) {
                val tc = array.getJSONObject(i)
                val func = tc.getJSONObject("function")
                val request = ToolExecutionRequest.builder()
                    .id(tc.optString("id", "call_$i"))
                    .name(func.getString("name"))
                    .arguments(func.optString("arguments", "{}"))
                    .build()
                result.add(request)
            }
        }
    } catch (e: Exception) {
        // 解析失败，忽略
    }
    return result
}
```

在 `parseResponse` 中调用：
```kotlin
// 回退机制：如果 API 没有返回 tool_calls 但 content 中包含 tool_calls JSON，尝试解析
if (toolCalls.isEmpty() && text != null) {
    val extracted = extractToolCallsFromContent(text)
    if (extracted.isNotEmpty()) {
        toolCalls.addAll(extracted)
    }
}
```

### 14.4 新增 ToolSpec 的 checklist（含 DeepSeek strict 模式要求）

每实现一个新工具时，按以下清单检查：

- [ ] **工具名称唯一**：在 `ToolRegistry` 中检查无重名
- [ ] **参数描述清晰**：`description` 说明参数类型、取值范围、示例
- [ ] **参数类型正确**：`string`/`integer`/`number`/`boolean`，与 `execute()` 中解析一致
- [ ] **必填参数标记**：`isRequired = true` 的参数在 `execute()` 中校验
- [ ] **destination 枚举一致**：导航类工具 `validDestinations` 与 `NavigationCapability` 路由表一致
- [ ] **错误处理完整**：参数缺失/非法值返回 `ToolResult.error()` 而非抛异常
- [ ] **Capability 注册**：新页面导航需在 `NavigationCapability.navigateTo()` 添加路由分支
- [ ] **测试覆盖**：验证工具在 ReAct 循环中能被正确调用和执行
- [ ] **DeepSeek strict 模式兼容**：`parameters` 中设置 `additionalProperties: false`（已由 Koog `RemoteModelFactory` 内部自动处理）
- [ ] **DeepSeek thinking 禁用**：使用 DeepSeek V4 时 API 请求自动附加 `thinking: {"type": "disabled"}`（已由 Koog `RemoteModelFactory` 内部自动处理）

### 14.5 相关文件

> ⚠️ **本节文件清单已失效（2026-08-03）**：下表除 `NavigationCapability.kt` 外的文件均已在 Agent 架构重构中移除或重命名（Grep 无匹配），仅保留作为历史复盘上下文。当前等价物见表后说明。

| 文件（历史，已不存在） | 原职责 |
|------|------|
| ~~`InAppAgentConfig.kt`~~ | System Prompt 定义，工具描述 |
| ~~`InAppLlmClient.kt`~~ | API 请求/响应解析，content 回退解析 |
| ~~`InAppAgentService.kt`~~ | ReAct 主循环，消息历史管理 |
| ~~`LangChain4jToolBridge.kt`~~ | ToolSpec ↔ LangChain4j 转换，工具执行分发 |
| ~~`ToolRegistry.kt`~~ | 工具注册中心 |
| ~~`BaseUiTool.kt`~~ | 工具基类，参数辅助方法 |
| ~~`NavigateToTool.kt`~~ | 导航工具示例（参考实现） |
| `NavigationCapability.kt` | 页面路由 Capability（仍存在） |

**当前等价实现**：工具定义在 `CameraToolService` / `ChatToolService` 的 @Tool 方法；解析经 Koog agent 循环；分发经 `CapabilityRegistry.dispatch`；底层 chat 模型为 Koog 的 `OpenAILLMClient`（DeepSeek 适配已内置：禁用 thinking、`additionalProperties: false`、`tool_choice: REQUIRED` 映射）。

---
