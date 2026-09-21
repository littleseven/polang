# Chat 二级页模块技术实现规范

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**: 主页面 Pager 页 2，相册内的 AI 助手能力入口，从相册悬浮底部 Tab「聊天」或全屏横滑进入；仅远程模型（端侧文本 LLM 已移除），支持对话持久化

**主要维护者**: 项目开发者

**阅读对象**: RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[PRIVACY] 隐私优先保护**: 敏感数据（人脸/对话/图片）优先本地处理，非敏感复杂推理可在用户授权后使用云端模型
- **[PERF] 响应延迟**: 远程模型首字 < 1.5s
- **[I18N] 多语言文案**: 输入框占位符、快捷入口标签必须提取到 strings.xml
- **[PERSISTENCE] 对话持久化**: 所有消息自动保存到 Room 数据库，应用重启后自动恢复

## 2. 页面架构 (Page Architecture)

### 2.1 ChatScreen 布局

```
┌─────────────────────────────┐
│  TopBar: 返回 + 设置 + 清空  │
├─────────────────────────────┤
│                             │
│      MessageList (LazyColumn)│
│      - 文本消息              │
│      - 图片消息              │
│      - 命令执行卡片          │
│                             │
├─────────────────────────────┤
│  ChatInputArea               │
│  [功能胶囊] [输入框] [发送]  │
└─────────────────────────────┘
```

### 2.2 核心组件

| 组件 | 文件 | 职责 |
|------|------|------|
| **ChatScreen** | `features/chat/ChatScreen.kt` | 二级页容器，组合各子组件 |
| **ChatViewModel** | `features/chat/ChatViewModel.kt` | 对话状态管理、消息发送 |
| **MessageList** | `features/chat/ChatScreen.kt`（内联 LazyColumn） | 消息列表渲染，支持多种消息类型 |
| **ModelSelector** | `features/chat/components/ModelSelector.kt` | 模型切换组件；2026-08-22 起输入区不再展示模型胶囊（仅远程模型） |
| **ChatInputArea** | `features/chat/ChatScreen.kt`（私有 Composable） | 输入框 + 功能胶囊 + 发送；语音入口为默认关闭的实验能力（见 §8） |
| **MessageRepository** | `data/repository/MessageRepository.kt` | Room 数据库读写，对话持久化 |

> 注：Chat 页暂不提供底部快捷入口或右下角展开菜单，相机/模型中心等能力统一从相册首页进入。

## 3. 模型切换实现 (Model Switching)

> **2026-08 更新**：端侧文本 LLM 已移除，chat 页仅保留远程模型（DeepSeek），
> `ChatModelOption`（定义于 `features/chat/ChatScreen.kt`）只剩 `Remote` 单选项；
> 2026-08-22 起输入区模型切换胶囊已移除，模型固定 Remote。下文切换逻辑为历史记录。

### 3.1 ModelSelector 组件

**位置**: 原输入框左侧（2026-08-22 起输入区不再展示）

**状态标识**:
- 远程模型（DeepSeek）：蓝色圆点 `#2196F3` + 文字 "远程"

**下拉选项**:
```kotlin
sealed class ChatModelOption(val labelRes: Int, val indicatorColor: Color) {
    data object Remote : ChatModelOption(R.string.chat_model_remote, Color(0xFF2196F3))
}
```

**切换逻辑**（chat 仅远程，简化为单选项）:
```kotlin
fun switchModel(target: ChatModelOption) {
    // 仅 Remote：同步远程配置到 AgentOrchestrator
    currentModel = remoteModel
    showToast("已切换至远程模型（DeepSeek）")
    // 保留当前对话上下文
    memorySession.preserveContext()
}
```

### 3.2 默认策略

```kotlin
fun getDefaultModel(): ChatModelOption {
    // chat 页仅远程：固定 Remote
    return ChatModelOption.Remote
}
```

## 4. 对话持久化 (Message Persistence)

### 4.1 数据库 Schema

```kotlin
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String = "default", // 后续支持多会话
    val type: String, // "user_text", "agent_text", "image", "command"
    val content: String, // 文本内容或图片路径
    val timestamp: Long = System.currentTimeMillis(),
    val modelUsed: String? = null, // 生成该消息的模型标识
    val metadata: String? = null // JSON 扩展字段
)
```

### 4.2 存储策略

- **自动保存**: 每条消息发送/接收后立即插入数据库
- **加载恢复**: ChatScreen 进入时异步加载最近 100 条消息
- **清理策略**: 单会话超过 1000 条时，自动删除最早 100 条
- **手动清空**: 顶部栏菜单提供"清空对话"选项，清空后保留空会话

### 4.3 消息类型映射

| UI 消息类型 | 数据库 type | 内容格式 |
|-------------|-------------|----------|
| UserText | `user_text` | 纯文本 |
| AgentText | `agent_text` | 纯文本 |
| UserImage | `user_image` | 图片文件路径 |
| AgentImage | `agent_image` | 图片文件路径 |
| CommandExecution | `command` | JSON: `{command, status, detail}` |
| PlanPreview | `plan_preview` | JSON: `{content, plan}` |

## 5. 快捷入口实现 (QuickActionBar)

> **⚠️ 设计稿，未落地**：Chat 页暂不提供底部快捷入口（见 §2 注）。以下伪码仅为方案示例；其中 `Screen.Editor`/`Screen.Gallery` 路由不存在（编辑器实际为 `Screen.PhotoEditor`，相册为主页面 Pager 页 0 非路由），落地时需按现行路由表修正。

### 5.1 入口定义

```kotlin
sealed class QuickAction(val icon: ImageVector, val label: String, val route: String) {
    data object Camera : QuickAction(Icons.Rounded.Camera, "相机", Screen.Camera.route)
    data object Gallery : QuickAction(Icons.Rounded.PhotoLibrary, "相册", Screen.Gallery.route)
    data object Editor : QuickAction(Icons.Rounded.Edit, "编辑", Screen.Editor.route)
}
```

### 5.2 跳转与返回流程

```kotlin
fun onQuickActionClick(action: QuickAction) {
    // 1. 保存当前对话状态
    viewModel.saveDraft()
    
    // 2. 跳转目标页面
    navController.navigate(action.route)
    
    // 3. 目标页面完成后返回（通过回调或结果回调）
    // 4. 将结果图片作为图片消息插入对话
    viewModel.sendImageMessage(resultImageUri)
    
    // 5. 可选：触发 AI 自动分析
    viewModel.requestImageAnalysis(resultImageUri)
}
```

### 5.3 返回行为

| 入口 | 跳转页面 | 返回触发条件 | 返回后行为 |
|------|----------|--------------|------------|
| 相机 | CameraScreen | 拍照完成/取消 | 照片作为图片消息插入；AI 自动分析（可选） |
| 相册 | GalleryScreen | 选择照片/取消 | 照片作为图片消息插入；AI 自动分析（可选） |
| 编辑 | EditorScreen | 保存/取消 | 编辑后图片作为图片消息插入；AI 给出编辑建议（可选） |

## 6. 与旧版对比

### 旧版（浮动面板）
```kotlin
// ❌ 依附于 Camera/Gallery 页面，非独立页面
AiChatScreen(
    visible = isVisible, // 需要外部控制可见性
    messages = messages,
    ...
)
```

### 新版（独立二级页）
```kotlin
// ✅ 独立 ChatScreen，作为相册内的 AI 助手二级页
ChatScreen(
    viewModel = chatViewModel,
    onNavigateToCamera = { navController.navigate(Screen.Camera.route) },
    onNavigateToGallery = { navController.navigate(Screen.Gallery.route) },
    onNavigateToEditor = { navController.navigate(Screen.Editor.route) }
)
```

**改进点**:
1. 独立二级页，从相册首页进入，不再依附于相机/编辑页内
2. 仅远程模型，无本地切换
3. 对话持久化，Room 数据库存储
4. 消息类型扩展，支持图片消息
5. 全屏对话体验，非浮动面板

## 7. 跨模块复用

- ✅ **ChatScreen（二级页）**: 使用完整 Chat UI，包含持久化
- ✅ **Camera**: 保留 AiChatScreen 浮动面板（作为页面内辅助）
- ✅ **Gallery**: 保留 AiChatScreen 浮动面板（作为页面内辅助）
- **Editor**: 已独立为 `PhotoEditorScreen` 二级页（`photo_editor/{sourceUri}` 路由），不使用 AiChatScreen 浮动面板

## 8. 语音输入集成

> **2026-08-19 更新**：语音能力已降级为**默认关闭的实验能力**（未移除）。仅当设置中语音模式
> `VoiceCommandMode ≠ DISABLED` 且本地 ASR 模型已下载就绪时，输入区才显示语音入口；
> 入口隐藏时不初始化 ASR 引擎，已处于语音输入态则强制回落文字模式。

- **Camera/Gallery 浮动面板 (`AiChatScreen`)**：通过 `VoiceCommandCoordinator` 处理，识别结果以 `AgentMessage.UserText` 形式进入消息列表。
- **独立 Chat 页 (`ChatScreen`)**：`ChatInputArea` 在语音入口可见时提供文字/语音模式切换。语音模式使用 `PushToTalkEngine` 直接驱动 ASR：
  - 已配置本地 Sherpa-ONNX ASR 模型且文件就绪时，使用本地识别
  - 未配置或本地模型不可用时回退到 `SystemAsrEngine`
  - 按住按钮时请求 `RECORD_AUDIO` 运行时权限，权限拒绝时提示用户
  - 识别结果通过 `ChatViewModel.sendMessage()` 进入当前会话

## 9. Agent 执行规约 (Execution Rules)

- **消息发送**: 先插入本地数据库，再发起网络推理请求
- **图片消息**: 图片保存到应用私有目录，数据库只存储路径
- **数据库查询**: 使用 Flow 监听，自动响应数据变化
- **I18N**: 所有用户可见文案必须提取到 strings.xml
- **日志规范**: 关键操作（消息发送、数据库读写）需记录 `PoLang:Chat` 日志
- **内存管理**: 图片消息使用 Coil/Glide 加载，避免内存泄漏
- **状态恢复**: 进程被杀后重启，自动恢复最近对话
- **主题适配**: 所有颜色使用 `MaterialTheme.colorScheme`
- **消息类型扩展**: 新增消息类型时，需同时更新 `AgentMessage` sealed class 和 `ChatBubble` 的 when 表达式

## 10. 常见陷阱检查清单 (Checklist)

- [ ] 模型切换时是否保留了对话上下文？
- [ ] 消息发送失败时是否有重试机制？
- [ ] 数据库读写是否在 IO 线程执行？
- [ ] 图片消息是否使用了适当的压缩？
- [ ] 单会话消息数是否超过 1000 条限制？
- [ ] 应用重启后对话历史是否正确恢复？
- [ ] 快捷入口跳转后是否正确返回并插入图片消息？
- [ ] 语音入口是否按「语音模式开关 + ASR 模型就绪」正确显隐？（语音为默认关闭的实验能力，见 §8）

## 11. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**:
- ✅ AI 对话二级页 → 独立 ChatScreen，从相册首页进入
- ✅ 模型切换 → 输入框下拉，仅远程(DeepSeek)（本地 Qwen 文本模型已移除）
- ✅ 对话持久化 → Room 数据库，自动保存/恢复
- ✅ 响应延迟 → 远程 < 1.5s
- ✅ 隐私保护 → 敏感数据优先本地处理

**技术决策记录**:
- 选择 Room 而非 DataStore：Room 支持复杂查询和分页，适合消息列表场景
- 单会话 1000 条限制：平衡存储空间与历史完整性，后续可扩展为可配置
- 默认远程模型：确保首次安装最佳体验
- 图片存储在私有目录：避免暴露到公共相册，用户主动分享时才导出
