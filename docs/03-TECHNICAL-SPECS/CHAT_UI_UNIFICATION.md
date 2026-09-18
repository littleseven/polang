# Chat UI 统一化改造

> **版本**: 1.0  
> **状态**: 生效中  
> **最后更新**: 2026-08-03  
> **维护者**: RD Agent  


## 背景

PoLang 项目中存在两套 Chat UI 实现：
1. **Camera 页面** (`features/camera/agent/`) - 完整功能，设计精美
2. **Gallery 页面** (`AiChatPanel.kt`) - 基础功能，设计简单

为了提供一致的用户体验，本次改造将 Gallery 页面的 Chat UI 升级为与 Camera 页面相同的统一设计。

## 改动内容

### 1. 新增统一组件库

**位置**: `androidApp/src/main/java/com/mamba/picme/features/common/chat/`

#### 文件列表：
- **AiChatScreen.kt** - 主聊天界面组件
- **AgentMessage.kt** - 消息类型定义
- **AgentChatComponents.kt** - 气泡/消息渲染组件
- **AGENTS.md** - 模块使用文档

#### 核心功能：
```kotlin
// 统一的聊天界面组件
@Composable
fun AiChatScreen(
    visible: Boolean,              // 可见性控制
    messages: List<AgentMessage>,  // 消息列表
    isProcessing: Boolean,         // 处理中状态
    onVisibleChange: (Boolean) -> Unit,   // 可见性变化回调
    onSendMessage: (String) -> Unit,      // 发送消息回调
    onCommand: (AiAgentCommand) -> Unit,  // 执行命令回调
    onPlanConfirm: () -> Unit,            // 确认计划回调
    onPlanCancel: () -> Unit,             // 取消计划回调
    modifier: Modifier = Modifier
)
```

### 2. 消息类型定义

支持 6 种消息类型：

```kotlin
sealed class AgentMessage {
    data class UserText(val content: String) : AgentMessage()
    data class AgentText(val content: String) : AgentMessage()
    data class CommandExecution(
        val commandName: String,
        val commandIcon: ImageVector? = null,
        val status: Status = Status.PENDING,  // PENDING/RUNNING/SUCCESS/FAILED
        val detail: String = "",
        val index: Int = 0,
        val total: Int = 1
    ) : AgentMessage()  // 单命令执行状态展示（支持多命令批量执行过程可视化）
    data class PlanPreview(val content: String, val plan: ExecutionPlan? = null) : AgentMessage()
    data class PlanProgress(val content: String) : AgentMessage()
    data class PlanResult(val content: String) : AgentMessage()
}
```

### 3. Gallery 页面迁移

**旧代码** (`AiChatPanel.kt`):
```kotlin
Dialog(onDismissRequest = onDismiss) {
    Surface(...) {
        Column(...) {
            // 简单的 Dialog 布局
        }
    }
}
```

**新代码**:
```kotlin
AiChatScreen(
    visible = isVisible,
    messages = messages,
    isProcessing = isProcessing,
    onVisibleChange = { isVisible = it },
    onSendMessage = { input -> /* handle */ },
    onCommand = { command -> /* execute */ }
)
```

## 特性对比

| 特性 | 旧版 Gallery | Camera | 新版 Gallery |
|------|-------------|--------|-------------|
| ModalBottomSheet | ❌ | ✅ | ✅ |
| 折叠/展开 | ❌ | ✅ | ✅ |
| 拖拽把手 | ❌ | ✅ | ✅ |
| 滑入滑出动画 | ❌ | ✅ | ✅ |
| 语音输入 | ❌ | ✅ | ✅ |
| 文字 + 语音切换 | ❌ | ✅ | ✅ |
| PlanPreview 按钮 | ❌ | ✅ | ✅ |
| PlanProgress 样式 | ❌ | ✅ | ✅ |
| PlanResult 样式 | ❌ | ✅ | ✅ |
| Material3 主题适配 | ⚠️ 部分 | ✅ | ✅ |

## 视觉改进

### 气泡样式
- **用户消息**: 主题色半透明背景，右侧对齐
- **AI 回复**: 深灰色半透明背景，左侧对齐
- **计划预览**: 带确认/取消按钮的交互卡片
- **进度提示**: 浅背景次要颜色文字
- **结果反馈**: 中等背景第三颜色文字

### 输入栏
- **文字模式**: 输入框 + 发送按钮 + 语音切换按钮
- **语音模式**: 键盘切换按钮 + "按住说话"大按钮
- **动态反馈**: 录音中变色，移出区域变红取消

### 头部设计
- **标题**: "AI Agent" + 折叠/展开图标 + 关闭按钮
- **拖拽把手**: 可点击折叠，提升交互直觉性
- **动画**: 箭头旋转 180 度表示展开/收起状态

## 使用指南

### 基本用法

```kotlin
@Composable
fun MyFeatureScreen() {
    val chatState = remember { mutableStateOf(listOf<AgentMessage>()) }
    
    // Your screen content
    
    AiChatScreen(
        visible = chatState.value.isNotEmpty(),
        messages = chatState.value,
        isProcessing = false,
        onVisibleChange = { /* handle */ },
        onSendMessage = { input ->
            // Add user message
            chatState.value = chatState.value + AgentMessage.UserText(input)
            
            // Simulate AI response
            CoroutineScope(Dispatchers.Main).launch {
                delay(1000)
                chatState.value = chatState.value + 
                    AgentMessage.AgentText("Received: $input")
            }
        },
        onCommand = { command ->
            // Execute the command
        }
    )
}
```

### 带计划执行的用法

```kotlin
AiChatScreen(
    visible = visible,
    messages = messages,
    isProcessing = false,
    onVisibleChange = { visible = it },
    onSendMessage = { input ->
        // Send message and get plan
        val plan = executePlan(input)
        messages.add(AgentMessage.PlanPreview(
            content = "📋 执行计划（${plan.steps.size}步）",
            plan = plan
        ))
    },
    onPlanConfirm = {
        // Execute the confirmed plan
        executePlanConfirmation()
    },
    onPlanCancel = {
        // Cancel the plan
        cancelPlan()
    }
)
```

## 维护说明

### 添加新消息类型

1. 在 `AgentMessage.kt` 中添加新的 sealed class 分支
2. 在 `AgentChatComponents.kt` 的 when 表达式中添加对应处理
3. 在 `AGENTS.md` 中更新文档

### 自定义样式

所有颜色使用 `MaterialTheme.colorScheme`，无需硬编码：

```kotlin
// ✅ Good
background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
color = MaterialTheme.colorScheme.onSurface

// ❌ Bad
background(Color.Blue)
color = Color.White
```

### 多语言支持

所有文案必须提取到 `strings.xml`:

```kotlin
// ✅ Good
text = stringResource(R.string.ai_agent_input_hint)

// ❌ Bad
text = "Type a message..."
```

## 测试建议

### UI 测试
```kotlin
@Test
fun aiChatScreen_showsMessages() {
    composeTestRule.setContent {
        AiChatScreen(
            visible = true,
            messages = listOf(
                AgentMessage.UserText("Hello"),
                AgentMessage.AgentText("Hi there!")
            ),
            isProcessing = false,
            onVisibleChange = {},
            onSendMessage = {},
            onCommand = {}
        )
    }
    
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Hello").assertIsDisplayed()
    composeTestRule.onNodeWithText("Hi there!").assertIsDisplayed()
}
```

### 集成测试
- 测试折叠/展开功能
- 测试语音/文字切换
- 测试 PlanPreview 确认/取消
- 测试键盘弹出时的布局调整

## 已知问题

无。

## 后续优化

1. **消息持久化**: 将历史消息保存到本地数据库
2. **快捷指令**: 添加常用命令的快捷按钮
3. **消息搜索**: 支持在聊天记录中搜索
4. **导出功能**: 支持导出聊天记录
5. **主题扩展**: 支持更多 Material 3 色彩方案

## 参考资源

- [Material Design 3 - Sheets](https://m3.material.io/components/sheets/overview)
- [Jetpack Compose - Layouts](https://developer.android.com/jetpack/compose/layouts)
- [Camera Chat UI Implementation](../../androidApp/src/main/java/com/mamba/picme/features/camera/CameraScreen.kt)

---

**创建日期**: 2026-05-30  
**修改日期**: 2026-05-30  
**负责人**: RD Team  
**状态**: ✅ 完成
