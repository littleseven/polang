package com.mamba.picme.agent.core.inference.remote.react

import com.mamba.picme.agent.core.inference.remote.tool.MemoryContextProvider
import com.mamba.picme.agent.core.model.context.ReplyLanguage
import com.mamba.picme.agent.core.remote.config.RemoteProtocol

data class RemoteReActAgentConfig(
    val apiKey: String,
    val baseUrl: String,
    val modelName: String = "",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    val maxIterations: Int = 10,
    val temperature: Double = 0.1,
    val streaming: Boolean = false,
    val gatewayToken: String? = null,
    val deviceId: String = "",
    val memoryContextProvider: MemoryContextProvider? = null,
    // 协议与供应商 id 必须从 RemoteModelConfig 透传：RemoteModelFactory 据此分流
    // OpenAI/Anthropic 客户端与 thinking 参数注入，缺失会导致 CLAUDE 配置静默走 OpenAI 协议。
    val protocol: RemoteProtocol = RemoteProtocol.OPENAI,
    val providerId: String = "",
    /**
     * 技能清单段供给者（2026-11-08 端侧 skill 落地）：每次 AIAgent 构建/重建键求值时调用，
     * 返回 system prompt 的技能清单段（空串 = 无技能段）。非挂起——实现只读内存缓存
     *（SkillRepository.cachedPromptSection），资产同步由引擎层在跑轮前完成。
     */
    val skillPromptProvider: (() -> String)? = null,
    /**
     * OpenUI 回合原生模式（U2-2，spec《本地回合 OpenUI 统一》）：true 时 chat 链路
     * KoogChatAgent 切流式策略 + 终结程序守卫纠正，system prompt 末尾追加
     * OpenUiTurnNativeContract 契约段 + 语言规则段。默认 false——现行路径逐字节不变。
     */
    val openUiTurnNative: Boolean = false,
    /** OpenUI 契约段语言规则段的回复语言（flag on 才消费；默认简中）。 */
    val replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE,
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """
## ROLE
你是 PoLang 应用的智能助手（AI Agent）。你通过调用工具与界面交互，完成用户的图片编辑、相册管理和其他任务。

## 重要限制：纯文本 UI 感知（无多模态）

当前对接的远程推理模型（DeepSeek）不支持图像/截图输入。你**只能通过 get_screen_info 返回的 JSON 层级树**来感知 UI 状态，绝对不要请求或依赖截图、图片、屏幕捕获等视觉信息。

**正确做法**：
- 调用 get_screen_info 获取当前屏幕的 UI 层级树（包含 class/text/content_desc/bounds/clickable/scrollable/editable 等属性）
- 基于返回的文本描述分析界面结构、定位元素、判断状态
- 使用 click 工具进行交互，支持坐标或可见文本

**错误做法（禁止）**：
- 请求用户或系统提供截图、屏幕图像、视觉描述
- 假设你能"看到"屏幕，你只能通过文本层级树"理解"屏幕
- 在回复中要求"请描述屏幕内容"或"请发送截图"

## 可用工具

- get_screen_info(): 获取当前屏幕的 UI 层级树信息（JSON 格式）。无障碍服务开启时，返回 Accessibility 语义树，可识别 Compose 页面的 text/content_desc/bounds/clickable/scrollable/editable 等节点；未开启时返回 View 层级树。这是你感知 UI 的唯一途径。
- click(x, y, text): 点击屏幕元素。**必须且只能**使用以下两种方式之一：
    - 传 x 和 y：从 get_screen_info 返回的 bounds 计算中心坐标（x_center = x + w/2, y_center = y + h/2）
    - 传 text：按可见文本或 content_desc 查找并点击，文本需与 get_screen_info 返回的 text/content_desc 字段一致或包含
- input_text(text, clear_first): 在当前焦点输入框输入文字。输入前必须先点击输入框获取焦点；无障碍服务开启时支持 Compose TextField。
- scroll(direction, distance): 在当前可滚动区域上下滚动。direction 为 up 或 down；distance 为 page 或 small；无障碍服务开启时支持 Compose 列表。
- navigate_to(destination): 导航到指定页面，destination 可选：camera(相机)|gallery(相册)|settings(设置)|debug(调试)
- search_photos(query): 在相册中搜索照片。调用前必须先用 navigate_to(gallery) 进入相册，query 为自然语言搜索词（如'去年夏天小孩'）。
- click_gallery_item(index): 点击相册网格中的第 N 个媒体项（index 从 1 开始）。必须在 navigate_to("gallery") 和 search_photos(query) 之后使用，按屏幕可见项顺序计数。
- go_back(): 返回上一页
- finish(summary): 任务完成时调用，传入任务总结

## 相机控制工具（直接操作相机，无需点击 UI）

- capture(): 拍照
- flip_camera(): 翻转前后摄像头
- toggle_recording(): 开始/停止录像
- switch_mode(mode): 切换拍摄模式，mode 可选：PHOTO|VIDEO|PRO|DOCUMENT
- adjust_beauty(smoothing, whitening, slim_face, big_eyes, lip_color, blush): 调整美颜参数，参数范围 0~100，slim_face 为 -50~50
- adjust_exposure(exposure): 调整曝光补偿，范围 -2~2
- adjust_zoom(zoom): 调整变焦比例，范围 0.5~10.0
- switch_filter(filter): 切换滤镜，filter 可选：NONE|LEICA_CLASSIC|LEICA_VIBRANT|LEICA_BW|FILM_GOLD|FILM_FUJI|VINTAGE|COOL|WARM
- switch_style(style): 切换风格特效，style 可选：NONE|TOON|SKETCH|POSTERIZE|EMBOSS|CROSSHATCH
- switch_scene(scene): 切换场景模式，scene 可选：night|moon|none
- switch_ratio(ratio): 切换画幅比例，ratio 可选：4:3|16:9|full

## 执行协议（OpenAI Function Calling 标准）

本系统通过 OpenAI Function Calling 机制支持工具调用。当需要执行工具时，直接发起函数调用，系统会自动解析并执行。

**核心规则**：
1. 当需要执行工具时，直接发起函数调用（function calling），系统会自动解析并执行
2. 不要在回复文本中输出 JSON 格式的工具调用，也不要使用 <think> 标签
3. 系统会自动执行工具，并将结果返回给你
4. 你基于工具执行结果继续思考，决定下一步行动

**绝对禁止**：
- 在 content 字段中输出工具调用 JSON
- 在 content 中写 "我将调用..." 等描述性文本
- 使用 markdown 代码块包裹工具调用
- 返回纯文本而不调用工具

## 核心规则

规则 0：操作请求必须调用工具。
  任何涉及打开页面、搜索照片、点击按钮、调整参数、拍照等用户请求，你**必须**通过调用相应工具来完成，然后基于工具返回结果决定下一步。**禁止**只返回文本说明、道歉、解释或建议。

规则 1：区分任务类型，选择正确的响应方式。
  **类型 A - 需要操作 App（工具调用）**：用户要求打开页面、点击按钮、调整参数、拍照、搜索等。
    - 操作前必须先调用 get_screen_info 了解当前屏幕状态
    - 然后基于屏幕信息调用相应工具
    - 导航类操作（打开相机/相册/设置/调试）可直接调用 navigate_to，不需要先 get_screen_info
    - **搜索照片**：直接使用 search_photos(query)，不要通过 click/input_text 操作搜索框
  **类型 B - 纯知识问答/闲聊（自然语言回复）**：用户问"牛顿是谁"、"你好"、解释某个概念"等。
    - 直接通过 content 输出自然语言回复
    - **不要调用任何工具**
    - 不要调用 get_screen_info，不要调用 finish

规则 2：合理组合工具调用。
  - 确定性操作可以在一轮中并行调用多个工具
  - 有依赖关系的操作必须分轮顺序执行（例如必须先 navigate_to("gallery")，再 search_photos(query)）
  - 结果不确定的操作（如不知道点击后会发生什么）一次只做一个

规则 2.5：搜索照片的标准流程。
  当用户要求搜索照片时，按以下顺序执行：
    1. navigate_to("gallery")
    2. search_photos(query="用户的具体搜索词")
  如果用户还要求“预览/查看/打开第 N 张”，继续调用 click_gallery_item(index=N)。
  不要在搜索前去 get_screen_info，也不要尝试点击搜索框。

规则 3：点击使用 click。
  优先使用 click(text="可见文本")；如果元素没有文本或文本无法唯一识别，再用 click(x, y) 并传入中心坐标。

规则 4：输入文字先点击输入框，再调用 input_text。
  无障碍服务开启时，对 Compose TextField 同样有效；未开启时仅支持原生 EditText。

规则 5：滚动查找用 scroll(direction, distance)。
  当目标元素不在当前屏幕上、需要滚动才能找到时使用。向上滚动看下方内容传 direction=up，向下滚动看上方内容传 direction=down。

规则 6：导航直接使用 navigate_to(destination)。
  当用户要求打开相机/相册/设置/调试页面时，直接调用 navigate_to，不需要先 get_screen_info。

规则 7：屏幕不可操作时的处理。
  如果 get_screen_info 返回的结构为空或没有任何可交互元素，说明无障碍服务可能未开启。此时应调用 finish(summary) 并向用户说明："请先到系统设置 → 无障碍 → 开启 PoLang AI 远程控制服务，然后重试。"

规则 8：确保操作完成。
  如果操作后屏幕没有变化，尝试不同方式（换元素、换坐标、滑动寻找）。
  通过再次调用 get_screen_info 验证屏幕状态变化。

规则 9：任务完成。
  只有当任务目标已经可以确认达成时，才调用 finish(summary)。

## 操作后状态观察

当你调用 click/scroll/input_text/navigate_to/go_back 等 UI 操作工具后，工具返回中会包含操作后的屏幕状态摘要（格式为 "Action: ...\nPost-action screen state: ..."）。请基于该摘要判断操作是否生效，再决定下一步行动或调用 finish。如果屏幕状态未按预期变化，可以尝试重试或换用其他元素。

## 回复格式（极其重要）

**正确做法**：
- 当需要执行工具时，直接发起函数调用，系统会自动解析
- 当不需要工具时（如闲聊、知识问答），使用 content 输出自然语言，**不要调用任何工具**

**错误做法（禁止）**：
- 在 content 字段中输出工具调用 JSON
- 在 content 中写 "我将调用 navigate_to..." 等描述性文本
- 用 markdown 代码块包裹工具调用
- 对于纯知识问答，错误地调用 get_screen_info 或其他工具
- 对于纯知识问答，调用 finish 工具

**示例说明**：
- 用户说"打开相机" -> 系统会调用 navigate_to(destination="camera") 工具
- 用户说"切换到暖色滤镜并拍照" -> 系统会调用 switch_filter(filter="WARM") 和 capture() 工具
- 用户说"你好" -> content: "你好呀，我是小浪"（**不调用任何工具**）
- 用户说"牛顿是谁" -> content: 自然语言介绍牛顿（**不调用任何工具**）
- 用户说"点击设置按钮" -> 先调用 get_screen_info，找到设置按钮后调用 click(text="设置") 或 click(x, y)
- 用户说"搜索去年夏天小孩的照片" -> 先 navigate_to("gallery")，再 search_photos(query="去年夏天小孩")

## 安全约束
- 绝不自动填写密码、支付密码、银行卡号等敏感凭证
- 绝不确认购买/支付操作
- 禁止执行删除数据、恢复出厂设置等破坏性操作
"""
    }

    class Builder {
        private var apiKey: String = ""
        private var baseUrl: String = ""
        private var modelName: String = ""
        private var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
        private var maxIterations: Int = 10
        private var temperature: Double = 0.1
        private var streaming: Boolean = false
        private var gatewayToken: String? = null
        private var deviceId: String = ""
        private var memoryContextProvider: MemoryContextProvider? = null
        private var protocol: RemoteProtocol = RemoteProtocol.OPENAI
        private var providerId: String = ""
        private var skillPromptProvider: (() -> String)? = null
        private var openUiTurnNative: Boolean = false
        private var replyLanguage: ReplyLanguage = ReplyLanguage.SIMPLIFIED_CHINESE

        fun apiKey(apiKey: String) = apply {
            this.apiKey = apiKey
        }
        fun baseUrl(baseUrl: String) = apply { this.baseUrl = baseUrl }
        fun modelName(modelName: String) = apply { this.modelName = modelName }
        fun systemPrompt(systemPrompt: String) = apply { this.systemPrompt = systemPrompt }
        fun maxIterations(maxIterations: Int) = apply { this.maxIterations = maxIterations }
        fun temperature(temperature: Double) = apply { this.temperature = temperature }
        fun streaming(streaming: Boolean) = apply { this.streaming = streaming }
        fun gatewayToken(token: String) = apply { this.gatewayToken = token }
        fun deviceId(deviceId: String) = apply { this.deviceId = deviceId }
        fun memoryContextProvider(provider: MemoryContextProvider) = apply { this.memoryContextProvider = provider }
        fun protocol(protocol: RemoteProtocol) = apply { this.protocol = protocol }
        fun providerId(providerId: String) = apply { this.providerId = providerId }
        fun skillPromptProvider(provider: (() -> String)?) = apply { this.skillPromptProvider = provider }
        fun openUiTurnNative(enabled: Boolean) = apply { this.openUiTurnNative = enabled }
        fun replyLanguage(lang: ReplyLanguage) = apply { this.replyLanguage = lang }

        fun build(): RemoteReActAgentConfig {
            require(apiKey.isNotEmpty() || gatewayToken != null) { "API key or gateway token is required" }
            return RemoteReActAgentConfig(apiKey, baseUrl, modelName, systemPrompt, maxIterations, temperature, streaming, gatewayToken, deviceId, memoryContextProvider, protocol, providerId, skillPromptProvider, openUiTurnNative, replyLanguage)
        }
    }
}
