package com.mamba.picme.agent.core.model.command

import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.plan.ExecutionPlan
import com.mamba.picme.beauty.api.BeautySettings
import com.mamba.picme.beauty.api.FilterType
import com.mamba.picme.beauty.api.StyleFilter

/**
 * 反馈目标指代。
 *
 * @property Ordinal 按展示序号指代，如“第三张”
 * @property Description 按内容描述指代，如“海边的”
 * @property MediaId 精确媒体 ID
 * @property LastShown 最近展示的第一张，如“这张”
 */
sealed interface FeedbackTarget {
    data class Ordinal(val index: Int) : FeedbackTarget
    data class Description(val text: String) : FeedbackTarget
    data class MediaId(val id: String) : FeedbackTarget
    data object LastShown : FeedbackTarget
}

/**
 * Agent 命令 V2 —— 精简 JSON 风格
 *
 * 每个命令携带唯一 commandId（32位自增整型），支持请求-响应关联。
 * 扩展版本，支持：
 * - 相机控制（原有）
 * - Gallery 操作（新增）
 * - 设置控制（新增）
 * - 页面导航（新增）
 * - 照片编辑（新增）
 */
sealed class AgentCommand {

    /**
     * 命令唯一标识（32位自增整型）
     * 用于请求-响应关联和全链路追踪。
     */
    abstract val commandId: Int

    // ==================== 相机命令 ====================

    /**
     * 调整美颜参数
     */
    data class AdjustBeauty(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val settings: BeautySettings
    ) : AgentCommand()

    /**
     * 切换滤镜
     */
    data class SwitchFilter(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val filterType: FilterType
    ) : AgentCommand()

    /**
     * 切换风格特效
     */
    data class SwitchStyle(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val styleFilter: StyleFilter
    ) : AgentCommand()

    /**
     * 切换场景模式
     */
    data class SwitchScene(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val sceneName: String
    ) : AgentCommand()

    /**
     * 切换画幅比例
     */
    data class SwitchRatio(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val ratio: String
    ) : AgentCommand()

    /**
     * 调整曝光
     */
    data class AdjustExposure(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val exposure: Int
    ) : AgentCommand()

    /**
     * 调整变焦
     */
    data class AdjustZoom(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val zoomRatio: Float
    ) : AgentCommand()

    /**
     * 翻转摄像头
     */
    data class FlipCamera(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    /**
     * 拍摄照片
     */
    data class CapturePhoto(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    /**
     * 开始/停止录像
     */
    data class ToggleRecording(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    /**
     * 延迟等待（通用原语）
     *
     * 按指定毫秒数等待，可与其他命令组合实现延迟执行效果。
     * 例如：BatchExecute([Delay(3000), CapturePhoto]) 实现 3 秒后拍照。
     *
     * @property delayMs 延迟毫秒数（1~300000，即最多 5 分钟）
     */
    data class Delay(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val delayMs: Long
    ) : AgentCommand()

    /**
     * 切换拍摄模式
     */
    data class SwitchMode(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mode: MediaType
    ) : AgentCommand()

    // ==================== Gallery 命令 ====================

    /**
     * 查看指定媒体
     */
    data class ViewMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaId: String? = null
    ) : AgentCommand()

    /**
     * 删除媒体（可指定 ID 列表，空列表表示删除当前选中）
     */
    data class DeleteMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaIds: List<String> = emptyList()
    ) : AgentCommand()

    /**
     * 分享媒体
     */
    data class ShareMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaIds: List<String> = emptyList()
    ) : AgentCommand()

    /**
     * 选择/取消选择媒体
     */
    data class SelectMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaId: String,
        val selected: Boolean
    ) : AgentCommand()

    /**
     * 搜索媒体
     *
     * @property query 原始查询文本，必填；用于展示与语义召回兜底。
     * @property intent 可选的标准化搜索意图。当 LLM 能可靠拆出时间/关键词/地点/人物时填充，
     *                  下游可直接用结构化过滤执行精确 Room 查询；为 null 时退回到字符串解析。
     */
    data class SearchMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val query: String,
        val intent: SearchIntent? = null
    ) : AgentCommand()

    /**
     * 细化上一轮相册搜索结果（in-set 过滤）。
     * 由 Agent 在识别到用户对上一轮结果收窄时发出；命中 id 集合由 ChatViewModel 按 session 持有。
     *
     * @property constraint 原始细化条件文本，必填。
     * @property intent 可选的标准化搜索意图；与 [SearchMedia.intent] 语义一致。
     */
    data class RefineMediaSearch(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val constraint: String,
        val intent: SearchIntent? = null
    ) : AgentCommand()

    /**
     * 记录用户对搜索结果的反馈（喜欢/不喜欢）。
     *
     * 由 LLM 在识别到自然语言反馈时发出，如“第三张不错”“不喜欢有人物的”。
     */
    data class RecordMediaFeedback(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val target: FeedbackTarget,
        val action: FeedbackAction,
        val queryHint: String? = null
    ) : AgentCommand()

    /**
     * 基于指定图片推荐更多相似照片。
     *
     * 由 LLM 在识别到“再来点这种”/“类似的”时发出。
     */
    data class MoreLikeThis(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val target: FeedbackTarget,
        val queryHint: String? = null
    ) : AgentCommand()

    /**
     * 在后续搜索中排除某类约束。
     *
     * 由 LLM 在识别到“排除夜景”/“不要室内的”时发出。
     */
    data class ExcludeConstraint(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val constraint: String
    ) : AgentCommand()

    /**
     * 切换视图模式
     */
    data class SwitchViewMode(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mode: String
    ) : AgentCommand()

    /**
     * 收藏/取消收藏
     */
    data class FavoriteMedia(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val mediaId: String,
        val favorite: Boolean
    ) : AgentCommand()

    // ==================== 设置命令 ====================

    /**
     * 切换主题
     */
    data class ChangeTheme(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val theme: String
    ) : AgentCommand()

    /**
     * 切换语言
     */
    data class ChangeLanguage(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val language: String
    ) : AgentCommand()

    /**
     * 下载模型
     */
    data class DownloadModel(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val modelId: String
    ) : AgentCommand()

    /**
     * 切换人脸检测引擎
     */
    data class SwitchFaceEngine(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val engine: String
    ) : AgentCommand()

    /**
     * 切换开关设置
     */
    data class ToggleSetting(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val settingKey: String,
        val enabled: Boolean
    ) : AgentCommand()

    // ==================== 导航命令 ====================

    /**
     * 导航到指定页面
     */
    data class NavigateTo(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val destination: String
    ) : AgentCommand()

    /**
     * 返回上一页
     */
    data class GoBack(
        override val commandId: Int = AgentIdGenerator.nextId()
    ) : AgentCommand()

    // ==================== 编辑命令 ====================

    /**
     * AI 一键优化图片
     *
     * @property imageUri 待优化图片的本地 URI
     * @property explanation 执行后生成的用户说明（Capability 回写）
     * @property resultRecipe 执行后生成的编辑配方 JSON（Capability 回写，UI 据此进入编辑器）
     */
    data class AiOptimize(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val imageUri: String,
        val explanation: String? = null,
        val resultRecipe: String? = null
    ) : AgentCommand()

    /**
     * 对话式图片编辑
     *
     * @property params 结构化编辑意图（美颜/滤镜/调色 delta）
     * @property imageUri 待编辑图片 URI；为空时使用会话最近一张用户图片
     * @property explanation 给用户的一句话说明
     */
    data class EditImage(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val params: EditParams,
        val imageUri: String = "",
        val explanation: String? = null
    ) : AgentCommand()

    /**
     * 获取本地相册摘要
     *
     * @property includeDetails 是否返回包含剩余任务数的完整摘要
     */
    data class GetGallerySummary(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val includeDetails: Boolean = false
    ) : AgentCommand()

    /**
     * 启动/控制/查询 TAG 扫描任务
     *
     * @property action 动作：start, pause, resume, cancel, query
     * @property taskType 扫描类别：face, scene, activity, objects, tags, summary, mlkit, auto
     * @property mode 扫描模式：full, incremental（仅 start 有效）
     */
    data class StartTagScan(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val action: String,
        val taskType: String? = null,
        val mode: String? = null
    ) : AgentCommand()

    // ==================== 系统/外部 App 命令 ====================

    /**
     * 启动其他应用或本应用指定 Activity
     *
     * @property packageName 目标应用包名（优先）
     * @property appName 应用名称（如"微信"），用于自然语言映射
     * @property activityClass 目标 Activity 全限定名（可选）
     * @property extras 启动 Intent 附加参数（可选）
     */
    data class LaunchApp(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val packageName: String? = null,
        val appName: String? = null,
        val activityClass: String? = null,
        val extras: Map<String, String> = emptyMap()
    ) : AgentCommand()

    /**
     * 打开系统设置项
     *
     * @property setting 设置项标识，如 wifi / bluetooth / accessibility / display / location
     */
    data class OpenSystemSettings(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val setting: String
    ) : AgentCommand()

    // ==================== 远程模式专用命令 ====================

    /**
     * 批量执行命令（L2 Batch Function Calling）
     *
     * 数组中的命令按顺序执行，每个子命令独立返回响应，最终汇总为响应数组。
     *
     * @property commands 子命令列表
     * @property atomic 是否原子模式（true 时任一失败触发全部回滚）
     */
    data class BatchExecute(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val commands: List<AgentCommand>,
        val atomic: Boolean = false
    ) : AgentCommand()

    /**
     * 执行计划（L3 Plan-and-Execute）
     *
     * 仅远程模式支持，包含条件判断和多步骤编排。
     */
    data class ExecutePlan(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val plan: ExecutionPlan
    ) : AgentCommand()

    /**
     * 执行一段 JavaScript（端侧沙箱；code 由远程 LLM 生成）。
     *
     * 用于相册盘点/统计等需组合计算的场景：JS 在 QuickJS 沙箱内执行，经 JSBridge
     * 调只读原生能力（如 gallery.summary），结构化结果回传 LLM 做自然语言总结。
     */
    data class ExecuteScript(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val code: String
    ) : AgentCommand()

    /**
     * 画一张图表（端侧渲染成真实图片插入聊天）。
     *
     * 远程 LLM 把已得到的统计数据经此命令交给端侧 Chart 生成器（bar/line/pie），
     * 渲染结果作为 CHART 消息显示；summary 回传 LLM 做文字总结。
     * 这是给用户展示图表的唯一途径（不要用文字/表格画图）。
     */
    data class DrawChart(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val type: String,
        val title: String,
        val labels: List<String>,
        val values: List<Double>,
        val unit: String? = null
    ) : AgentCommand()

    /**
     * 渲染一张 HTML 组件卡片（端侧离线 WebView 渲染，插入聊天）。
     *
     * 远程 LLM 把自包含 HTML（内联 CSS/JS，禁外链/网络资源）经此命令交给端侧，
     * 渲染结果作为 HTML_CARD 消息显示；summary 回传 LLM 做文字总结。
     * 与 [DrawChart] 分工：统计图走 DrawChart，仅当用户明确要求更丰富展示/
     * 交互组件时才用本命令。端侧渲染层全量断网 + 零 JS 桥接（不可信内容）。
     *
     * [display] 为 LLM 声明的展示形态（"inline"/"fullpage"，null = inline）：
     * fullpage 卡在聊天流内外显为固定高预览，点击进全屏查看器；
     * 端侧另有测高兜底（超 1.0 屏强制预览形态），终判见 `HtmlCardDisplay`（androidApp）。
     */
    data class RenderHtml(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val html: String,
        val summary: String? = null,
        val display: String? = null
    ) : AgentCommand()

    // ==================== 记忆命令（人物关系 + 事实记忆） ====================

    /**
     * 声明人物关系："X 是我的 Y"（如"小宝是我女儿"）。
     *
     * @property name 已命名人物的名字（须已在相册人物分组命名）
     * @property relation 关系谓词：RelationPredicate 枚举名（如 CHILD）
     *                    或中文称谓（如"女儿"，由 Capability 经 KinshipLexicon 归一）；
     *                    都不匹配时原话作为自定义称呼存入 customLabel、谓词记 OTHER
     */
    data class RememberPersonRelation(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val name: String,
        val relation: String
    ) : AgentCommand()

    /**
     * 遗忘某人物与"我"之间的全部关系。
     */
    data class ForgetPersonRelation(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val name: String
    ) : AgentCommand()

    /**
     * 查询人物关系（chat 主动读通路）。
     *
     * [name] 指定人物名时只返回该人物与「我」的关系（声明幂等，至多 1 条）；
     * null/空返回全部指向「我」的关系。实时同步读 DB，不依赖被动注入的 Flow 快照，
     * 用于"看一下我的人物关系""小宝和我什么关系"等查询（规避 snapshot 更新延迟）。
     */
    data class QueryPersonRelation(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val name: String?
    ) : AgentCommand()

    /**
     * 记住一条事实（"帮我记住…"）。
     *
     * @property source 声明来源：CHAT_TOOL（聊天工具直调）/ JS_DISPATCH（JS 沙盒写通路）
     */
    data class RememberFact(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val content: String,
        val category: String? = null,
        val source: String = "CHAT_TOOL"
    ) : AgentCommand()

    /**
     * 遗忘一条事实：[factId] 精确删除优先；否则按 [query] 唯一匹配删除
     * （多条命中不删，返回候选由用户选择）。
     */
    data class ForgetFact(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val factId: Long? = null,
        val query: String? = null
    ) : AgentCommand()

    /**
     * 检索事实记忆（LIKE 模糊召回，返回含 factId 的列表供后续 forget）。
     */
    data class RecallMemory(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val query: String
    ) : AgentCommand()

    // ==================== 通用命令 ====================

    /**
     * 文本回复（聊天模式）
     */
    data class TextReply(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val message: String
    ) : AgentCommand()

    /**
     * 未知命令（LLM 输出无法解析时）
     */
    data class Unknown(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val raw: String
    ) : AgentCommand()

    /**
     * 执行错误
     */
    data class Error(
        override val commandId: Int = AgentIdGenerator.nextId(),
        val reason: String
    ) : AgentCommand()

    companion object {
        /**
         * 获取命令的 method 名称（用于 JSON 序列化）
         */
        fun getMethodName(command: AgentCommand): String = when (command) {
            is AdjustBeauty -> "adjust_beauty"
            is SwitchFilter -> "switch_filter"
            is SwitchStyle -> "switch_style"
            is SwitchScene -> "switch_scene"
            is SwitchRatio -> "switch_ratio"
            is AdjustExposure -> "adjust_exposure"
            is AdjustZoom -> "adjust_zoom"
            is FlipCamera -> "flip_camera"
            is CapturePhoto -> "capture"
            is ToggleRecording -> "toggle_recording"
            is Delay -> "delay"
            is SwitchMode -> "switch_mode"
            is ViewMedia -> "view_media"
            is DeleteMedia -> "delete_media"
            is ShareMedia -> "share_media"
            is SelectMedia -> "select_media"
            is SearchMedia -> "search_media"
            is RefineMediaSearch -> "refine_media_search"
            is RecordMediaFeedback -> "feedback"
            is MoreLikeThis -> "more"
            is ExcludeConstraint -> "exclude"
            is SwitchViewMode -> "switch_view_mode"
            is FavoriteMedia -> "favorite_media"
            is ChangeTheme -> "change_theme"
            is ChangeLanguage -> "change_language"
            is DownloadModel -> "download_model"
            is SwitchFaceEngine -> "switch_face_engine"
            is ToggleSetting -> "toggle_setting"
            is NavigateTo -> "navigate_to"
            is GoBack -> "go_back"
            is AiOptimize -> "ai_optimize"
            is EditImage -> "edit_image"
            is GetGallerySummary -> "get_gallery_summary"
            is StartTagScan -> "start_tag_scan"
            is ExecuteScript -> "run_gallery_script"
            is DrawChart -> "draw_chart"
            is RenderHtml -> "render_html"
            is RememberPersonRelation -> "remember_person_relation"
            is ForgetPersonRelation -> "forget_person_relation"
            is QueryPersonRelation -> "query_person_relation"
            is RememberFact -> "remember_fact"
            is ForgetFact -> "forget_fact"
            is RecallMemory -> "recall_memory"
            is LaunchApp -> "launch_app"
            is OpenSystemSettings -> "open_system_settings"
            is BatchExecute -> "batch_execute"
            is ExecutePlan -> "execute_plan"
            is TextReply -> "text_reply"
            is Unknown -> "unknown"
            is Error -> "error"
        }

        /**
         * 获取命令的 commandId
         */
        fun getCommandId(command: AgentCommand): Int = command.commandId
    }
}

/**
 * 将一轮 Agent 命令转换为写入对话记忆的自然语言摘要。
 *
 * streamChat 回写 MemoryManager 时使用：媒体/反馈命令生成可读摘要，避免把原始 JSON
 * 当作 assistant 历史喂回端侧小模型——小模型从 `[{"method":"search_media",...}]` 里
 * 读不出"上一轮搜了/筛选了什么"，导致多轮找图无法收敛。
 *
 * - 含 [AgentCommand.TextReply] → 优先用其 message（保留既有行为）
 * - 否则含媒体/反馈命令 → 拼接各命令摘要
 * - 都不是（如纯导航/相机命令）→ 返回 null，由调用方兜底原始响应
 */
fun summarizeCommandsForMemory(commands: List<AgentCommand>): String? {
    if (commands.isEmpty()) return null
    val textReplies = commands.filterIsInstance<AgentCommand.TextReply>()
    if (textReplies.isNotEmpty()) {
        return textReplies.joinToString(" ") { reply -> reply.message }
    }
    // 媒体/反馈命令不生成摘要——回退 streamResult.fullResponse（原始 JSON）。
    // 历史 assistant 保持标准 JSON 格式，LLM 模仿输出 JSON（parser 可解析）；
    // 此前用「[method] X」摘要会 100% 诱导 LLM 照搬该非标准格式导致解析失败。
    // 远程模型能读懂 JSON 历史，无需自然语言摘要。
    return null
}
