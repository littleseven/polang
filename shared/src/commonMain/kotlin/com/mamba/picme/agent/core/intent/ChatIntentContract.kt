package com.mamba.picme.agent.core.intent

/**
 * chat 意图契约表（Intent Contract，路由 SSOT）——spec《意图路由契约与意图路由器》§3.1。
 *
 * 核心决策（D2）：**LLM 管语义理解（输出意图），代码管路由策略（查表执行）**。
 * 本表是「意图 → 工具白名单/黑名单/规则文案/UI 契约」的声明式纯数据，与
 * `CAPABILITY_REGISTRY.md`（命令路由 SSOT）同构，形成代码+文档双层 SSOT。
 *
 * 不变式由 commonTest `ChatIntentContractTest` 机器校验：
 * - allowedTools ∩ forbiddenTools = ∅（互斥在设计上不可能，L41/L62 类矛盾无法再发生）；
 * - 意图 id 与 UiArtifact 闭集完备；
 * - 组合型意图（compositional=true）的 allowedTools 必须覆盖其原料工具集。
 *
 * 混合意图原则：按「最终产出物（deliverable）」分类，不按动作清单分类；
 * 产出物 >2 或置信度不足 → OPEN_QA 走完整 agent（路由器永不拦截能力）。
 */

/** 意图闭集（路由器输出的 deliverable 只能取这些值）。 */
enum class IntentId {
    /** 看/找照片、图片（交付物 = 照片横滑卡片）。 */
    VIEW_PHOTOS,

    /** 在上一轮搜索结果上加条件窄化（"只要4月的""换成夜景"）。 */
    REFINE_RESULTS,

    /** 统计/盘点/数量类问题（交付物 = 文字结论）。 */
    ANALYZE_STATS,

    /** 明确要求画图/图表。 */
    DRAW_CHART,

    /** 修图/美颜/调色/优化图片。 */
    EDIT_IMAGE,

    /** 明确要求 HTML 卡片/可交互组件/报告页（组合型：执行器组装多原料）。 */
    RENDER_RICH_HTML,

    /** 记住/忘掉/回忆事实或人物关系。 */
    MEMORY,

    /** 打开/跳转页面（相机/相册/设置等）。 */
    NAVIGATE,

    /** 改设置（主题/语言/开关/模型）。 */
    SETTINGS,

    /** 其它一切（闲聊/知识问答/不确定）——兜底，走完整 agent loop。 */
    OPEN_QA,
}

/** 意图的 UI 产出物契约（回合终态护栏据此校验「应产而未产」）。 */
enum class UiArtifact {
    MEDIA_RESULTS_CARD,
    CHART,
    EDITED_IMAGE,
    RICH_HTML_CARD,
    NAV_EFFECT,
    TEXT_ONLY,
}

/**
 * 单条意图契约。
 *
 * @property compositional 组合型意图（执行器职责即组装多原料，allowedTools 相应更宽）。
 * @property allowedTools 该意图分支的工具白名单（M3 分支化时裁剪工具面的依据）。
 * @property forbiddenTools 显式黑名单（如 VIEW_PHOTOS 禁 run_gallery_script——脚本路径
 *   历史上诱导「拿到 ids 谎称已展示」的事故链）。
 * @property ruleText 该意图的 prompt 规则文案要点（规则段的语义锚点，供审查/派生）。
 */
data class IntentDef(
    val id: IntentId,
    val uiContract: UiArtifact,
    val compositional: Boolean = false,
    val allowedTools: List<String>,
    val forbiddenTools: List<String> = emptyList(),
    val ruleText: String,
)

/** 意图契约表：路由策略层（ChatRoutingPolicy）与 CI 一致性测试的唯一事实源。 */
object ChatIntentContract {

    val intents: List<IntentDef> = listOf(
        IntentDef(
            id = IntentId.VIEW_PHOTOS,
            uiContract = UiArtifact.MEDIA_RESULTS_CARD,
            allowedTools = listOf("search_media", "refine_media_search", "list_person_relations"),
            forbiddenTools = listOf("run_gallery_script"),
            ruleText = "看/找照片一律 search_media 出横滑卡片（人物/时间走 person/fromMs/toMs " +
                "结构化参数）；禁止绕脚本路径拿 ids 假装已展示",
        ),
        IntentDef(
            id = IntentId.REFINE_RESULTS,
            uiContract = UiArtifact.MEDIA_RESULTS_CARD,
            allowedTools = listOf("refine_media_search", "search_media"),
            ruleText = "在上一轮结果内窄化用 refine_media_search 取交集；无基数时改 search_media 重搜",
        ),
        IntentDef(
            id = IntentId.ANALYZE_STATS,
            uiContract = UiArtifact.TEXT_ONLY,
            allowedTools = listOf("run_gallery_script", "get_gallery_summary"),
            ruleText = "盘点/统计默认纯文字总结，不主动画图；脚本纯统计不要 return ids（否则会触发补卡）",
        ),
        IntentDef(
            id = IntentId.DRAW_CHART,
            uiContract = UiArtifact.CHART,
            allowedTools = listOf("run_gallery_script", "draw_chart", "get_gallery_summary"),
            ruleText = "明确要求画图时：run_gallery_script 取数 1 次 → draw_chart 1 次 → 一句话总结",
        ),
        IntentDef(
            id = IntentId.EDIT_IMAGE,
            uiContract = UiArtifact.EDITED_IMAGE,
            allowedTools = listOf("adjust_image", "edit_image", "ai_optimize"),
            forbiddenTools = listOf("navigate_to"),
            ruleText = "修图/美颜在 chat 内完成（结果图发到聊天中），严禁跳转编辑页",
        ),
        IntentDef(
            id = IntentId.RENDER_RICH_HTML,
            uiContract = UiArtifact.RICH_HTML_CARD,
            compositional = true,
            allowedTools = listOf("run_gallery_script", "render_html", "draw_chart", "search_media"),
            ruleText = "明确要求 HTML 卡片/可交互组件时用 render_html；数据先经脚本取数内联",
        ),
        IntentDef(
            id = IntentId.MEMORY,
            uiContract = UiArtifact.TEXT_ONLY,
            allowedTools = listOf(
                "remember_fact", "forget_fact", "recall_memory",
                "remember_person_relation", "forget_person_relation", "list_person_relations",
            ),
            ruleText = "记忆类读写走记忆工具，人物关系查询必须 list_person_relations 不凭印象",
        ),
        IntentDef(
            id = IntentId.NAVIGATE,
            uiContract = UiArtifact.NAV_EFFECT,
            allowedTools = listOf("navigate_to", "go_back", "launch_app", "open_system_settings"),
            ruleText = "仅明确口令（去/回/打开 + 页面名）才导航，模糊表述拦截为文本提示",
        ),
        IntentDef(
            id = IntentId.SETTINGS,
            uiContract = UiArtifact.TEXT_ONLY,
            allowedTools = listOf(
                "change_theme", "change_language", "toggle_setting",
                "download_model", "switch_face_engine",
            ),
            ruleText = "设置变更直接调对应设置工具，结果如实告知",
        ),
        IntentDef(
            id = IntentId.OPEN_QA,
            uiContract = UiArtifact.TEXT_ONLY,
            allowedTools = emptyList(), // 兜底分支保留完整工具面（M3 再按使用率裁剪）
            ruleText = "开放语义走完整 agent loop 自由路由；路由器永不拦截能力",
        ),
    )

    private val byId: Map<IntentId, IntentDef> = intents.associateBy { it.id }

    fun of(id: IntentId): IntentDef = requireNotNull(byId[id]) { "契约表缺意图 $id" }

    /** 结构化校验（CI 测试调用）：返回违例描述列表，空表 = 契约自洽。 */
    fun validate(): List<String> {
        val violations = mutableListOf<String>()
        intents.forEach { def ->
            val overlap = def.allowedTools intersect def.forbiddenTools.toSet()
            if (overlap.isNotEmpty()) {
                violations += "${def.id}: allowedTools 与 forbiddenTools 互斥失败：$overlap"
            }
            if (def.compositional && def.allowedTools.size < 2) {
                violations += "${def.id}: 组合型意图的原料工具集过窄（${def.allowedTools}）"
            }
        }
        if (intents.map { it.id }.toSet().size != intents.size) {
            violations += "意图 id 重复"
        }
        return violations
    }
}
