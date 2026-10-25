package com.mamba.picme.server.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool

/**
 * render_ui 黄金示例（P1 few-shot：LLM 模仿示例结构的可靠性远高于遵守散文规则）。
 * 纯 JSON 数组（无前后散文），以便 `RenderUiValidatorTest` 直接喂校验器钉住
 * 「示例本身过校验」——示例与校验器永不脱节。
 * 占位符形态即合法形态（sf-xxxxxxxx / scan/ab12cd34），防止 LLM 学到非法占位符。
 */
internal const val RENDER_UI_EXAMPLE = """[
 {"version":"v0.9","createSurface":{"surfaceId":"sf-a1b2c3d4","catalogId":"https://polang.net/a2ui/catalog/v1.json"}},
 {"version":"v0.9","updateComponents":{"surfaceId":"sf-a1b2c3d4","components":[
  {"id":"root","component":"Column","children":["title","chart"]},
  {"id":"title","component":"Text","text":"今年照片统计","variant":"h3"},
  {"id":"chart","component":"BarChart","buckets":{"path":"/buckets"},"selected":{"path":"/selected"}}
 ]}},
 {"version":"v0.9","updateDataModel":{"surfaceId":"sf-a1b2c3d4","path":"/buckets","value":"local://scan/ab12cd34#histogram"}},
 {"version":"v0.9","updateDataModel":{"surfaceId":"sf-a1b2c3d4","path":"/selected","value":"2026-10"}}
]"""

internal const val RENDER_UI_RULES = """向用户下发一张 A2UI 原生渲染卡（图表/统计可视化首选，优于 render_html）。
约束（必须全部满足——服务端会逐条校验，不通过则本次不下发并返回错误清单，修正后重新调用）：
1. messagesJson 是 A2UI v0.9 信封 JSON 数组，每个信封含 "version": "v0.9" 与
   createSurface / updateComponents / updateDataModel 之一；首个信封必须是 createSurface；
2. createSurface 的 catalogId 必须是 "https://polang.net/a2ui/catalog/v1.json"；
3. updateDataModel 一信封只写一个绑定：必须含 "path"（如 "/buckets"）与 "value" 两键，
   禁止使用 "data" 批量形式（端上解析器不识别该键，数据会被静默丢弃）；
4. 组件只用以下 19 种：
   布局：Column/Row（"children":["子组件id",...]，可选 justify=start/center/end/spaceBetween/spaceAround/spaceEvenly/stretch、
   align=start/center/end/stretch）；List（children + direction=vertical/horizontal）；Card（"child":"子组件id"）；
   Divider（axis=horizontal/vertical）；Tabs（"tabs":[{"title":"…","child":"组件id"}]，至少一个）；
   Modal（trigger=触发按钮组件id，content=内容组件id）。
   展示：Text（text，variant 仅 h1/h2/h3/h4/h5/caption/body）；
   Icon（name 只用预定义名：accountCircle/add/arrowBack/arrowForward/attachFile/calendarToday/call/camera/check/close/
   delete/download/edit/error/event/fastForward/favorite/favoriteOff/folder/help/home/info/locationOn/lock/lockOpen/mail/
   menu/moreHoriz/moreVert/notifications/notificationsOff/pause/payment/person/phone/photo/play/print/refresh/rewind/search/
   send/settings/share/shoppingCart/skipNext/skipPrevious/star/starHalf/starOff/stop/upload/visibility/visibilityOff/
   volumeDown/volumeMute/volumeOff/volumeUp/warning，禁臆造、禁 svgPath）；
   Image（url 必须 https:// 公网图片地址，禁本地路径与相册 uri——相册照片永不出设备；可选 fit=contain/cover/fill/none/
   scaleDown，variant=icon/avatar/smallFeature/mediumFeature/largeFeature/header）；
   Video（url 必须 https:// 公网视频地址，同 Image 隐私口径，卡内不自动播、用户点播放）；
   AudioPlayer（url 必须 https:// 公网音频地址 + 可选 description 说明文字）。
   交互：Button（"child" 指向一个 Text 组件作按钮文字（禁 "label" 属性）+ action，
   action 格式 {"event": {"name": "<事件名>"}}（"event" 层必填，禁止平写 "name"），variant 仅 default/primary/borderless）；
   TextField（label，variant=shortText/longText/number/obscured）；CheckBox（label + value 布尔）；
   ChoicePicker（"options":[{"label":"…","value":"…"}]，value=选中值数组，variant=mutuallyExclusive/multipleSelection，可选 displayStyle=checkbox/chips）；
   Slider（value + max，可选 min/label）；DateTimeInput（value ISO 日期串，可选 enableDate/enableTime）。
   图表：BarChart（title=标题字符串，buckets 必须写数据绑定 {"path":"/buckets"}
   （[{label,count}] 数据经 updateDataModel 下发，禁裸写数据）；
   "selected" 必须写数据绑定 {"path":"/selected"}——端侧点选高亮回写该路径，
   禁裸字符串字面量（裸值不订阅数据模型，点选高亮会静默失效））；
   注意：交互组件的用户操作只在端侧生效、不会回传服务器——卡片只做展示与端侧轻交互，
   不要承诺「点了之后我会帮你…」类需要服务器感知的后续动作；
5. 数据不进 JSON：凡涉及相册数据的值，updateDataModel 的字符串值写绑定
   "local://scan/<handleId>#histogram"，handleId 来自 search_media/refine_media_search
   结果尾部的「handle: scan/<id>」。禁止臆造 handleId，禁止把路径/uri/清单写进任何字段，
   handle id 只在绑定值里引用，回复正文禁止向用户展示 handle id；
6. fallbackSummary 写投影级摘要（如「327 张，数据在原设备」），端上数据缺失时降级展示；
7. 样式只用语义令牌（禁具体数值）：
   Text 增可选 "role" 属性：HEADLINE|SUBHEAD|BODY|CAPTION（默认 BODY）；
   颜色增可选 "colorRole"：PRIMARY|SURFACE|ERROR|ON_SURFACE（默认 ON_SURFACE）；
   间距增可选 "spacing"：COMPACT|STANDARD|COMFORTABLE（默认 STANDARD）；
   BarChart 的 title 增可选 "titleRole"：HEADLINE|SUBHEAD|BODY|CAPTION（默认 SUBHEAD）；
   禁止输出 fontSize/padding/color 等具体数值字段，端侧会忽略或拒绝渲染；
8. 真实性硬约束：只有本轮实际调用了本工具（或 render_html）且未被校验拒绝，才能在回复里说
   「卡片已下发/已生成」；没调用工具就用文字假装发卡属于欺骗，服务端会检测并强制你纠正。"""

/**
 * render_ui——云端 Agent 的 A2UI 发卡工具（hybrid M2，spec §3.3 L1 卡）。
 *
 * 工身体是 CLOUD 侧空执行：A2UI 负载不经 tool-result 回环，而是经
 * AgentRunner.onToolCallStarting 落的 action 事件（tool+argsJson）随 SSE 下行，
 * 端侧 CloudAgentTurnRunner "action" 分支拦截渲染。工具只回确认文本。
 *
 * 服务端校验（2026-10-24，sf-012a0/sf-a2u1c0de 两类事故的系统性修复）：
 * 负载先过 [RenderUiValidator]——发射点门控（非法负载不落 action 事件、不下行端侧）
 * + 工具体回错误文本（Koog Observation 回灌，LLM 同轮修正重试）。
 *
 * ⚠️ @LLMDescription 与 shared `A2uiCardContract` 的字面量（CATALOG_ID/版本/handle 约定）
 * 是字面量副本关系（server 不依赖 :shared），改动须双向同步核对。
 */
class RenderUiToolService {

    @Tool(customName = "render_ui")
    @LLMDescription(
        RENDER_UI_RULES +
            "\n示例（月度照片统计完整卡；surfaceId/handleId 替换为真实值，结构原样照抄）：\n" +
            RENDER_UI_EXAMPLE,
    )
    suspend fun renderUi(
        @LLMDescription("A2UI surface 唯一 id（与 createSurface.surfaceId 一致，建议 sf-<8位hex>）")
        surfaceId: String,
        @LLMDescription("A2UI v0.9 信封 JSON 数组原文")
        messagesJson: String,
        @LLMDescription("投影级降级摘要（数据缺失时端上展示的占位文案）")
        fallbackSummary: String,
    ): String {
        RenderUiValidator.validateToError(surfaceId, messagesJson)?.let { error -> return error }
        return "UI 已下发端侧渲染（surfaceId=$surfaceId）"
    }
}
