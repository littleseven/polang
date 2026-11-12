package com.mamba.picme.features.chat.openui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.domain.chat.openui.OpenUiValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenUI 流式预览卡（openui P2，spec《2026-10-26-openui-compose-alignment-design》§4 P2）：
 * SSE openui_delta 帧的 program 前缀边收边渲染，对齐官方 A2UI Compose 的流式就绪语义。
 *
 * - 每帧前缀新建 session 做前缀解析：parser 的 autoClose（§4 流末规则）容错截断输入，
 *   未到位引用走 forwardRefSkeletons 模式产出 Skeleton 骨架占位（top-down reveal，
 *   2026-11-12 前向引用骨架流式批次）——骨架槽位先立、定义流入后下一帧自然补位，
 *   children 数不再随帧膨胀收缩（dropped→resolved 布局跳变收敛为原位内容替换）；
 * - 客户端预算（2026-11-11）：累计前缀超 [PREVIEW_PARSE_BUDGET_CHARS] 停逐帧重解析、
 *   清旧帧预览落既有 indeterminate 占位，等终结 Message 落库后由 [OpenUiCardHost]
 *   正式卡路径接管；阈值内（含等于）逐帧行为不变；
 * - **空 resolver**：Query 不取数（防按帧刷 host 工具/DB），数据组件先渲 defaults 空态，
 *   正式卡帧到达后由 [OpenUiCardHost] 接管真数据与交互；
 * - 只读：action 全部忽略，不写 viewState 不持久化；
 * - 前缀解析失败 → 保留上一帧好预览（produceState 不覆写 value），永不降级闪错。
 */
@Composable
fun OpenUiStreamingCard(
    program: String,
    photoResolver: OpenUiPhotoResolver,
    modifier: Modifier = Modifier,
) {
    val preview by produceState<OpenUiPreview?>(initialValue = null, program) {
        if (!shouldContinuePreviewParsing(program.length)) {
            // 超预算：停逐帧重解析并清旧帧预览 → 既有 indeterminate 占位（OpenUiCardLoading），
            // 等终结 Message 落库后的正式卡路径（OpenUiCardHost）接管
            value = null
            return@produceState
        }
        val rendered = withContext(Dispatchers.Default) {
            runCatching {
                val session = OpenUiSession(
                    program = program,
                    tools = OpenUiToolResolver { _, _ -> OpenUiValue.Null },
                    forwardRefSkeletons = true,
                )
                (session.prepare() as? OpenUiResult.Rendered)
                    ?.let { OpenUiPreview(it.root, session.state) }
            }.getOrElse { error ->
                if (error is CancellationException) throw error // 协程取消穿透（同 engine 口径）
                null
            }
        }
        if (rendered != null) value = rendered
    }
    val current = preview
    if (current == null) {
        OpenUiCardLoading()
    } else {
        OpenUiNodeRenderer(
            node = current.root,
            state = current.state,
            photoResolver = photoResolver,
            onAction = {},
            modifier = modifier,
        )
    }
}

/** 一帧预览产物：组件树根 + state 声明默认值快照（绑定按默认值渲染）。 */
private class OpenUiPreview(
    val root: OpenUiValue.ComponentVal,
    val state: Map<String, OpenUiValue>,
)

/**
 * 流式预览逐帧重解析的客户端字符预算：server 侧 ≥48 字符/≥80ms 节流只约束发帧频率，
 * 客户端此前无预算——每帧对累计前缀全量新建 session 重解析，单帧成本随前缀长度线性
 * 增长、整流累计平方级；程序上限 128KB，低端机大程序每帧重解析开销不可控。24k 字符
 * 约为常规 OpenUI 卡程序的数十倍，超此值逐帧预览边际收益为负：停帧重解析、落既有
 * indeterminate 占位，等终结 Message 落库后由正式卡路径接管。阈值内（含等于）行为不变。
 */
internal const val PREVIEW_PARSE_BUDGET_CHARS = 24_000

/**
 * 是否继续逐帧重解析（纯函数，超阈值/未超/边界三例单测钉住，OpenUiStreamingBudgetTest）：
 * program 长度 ≤ 预算 → 继续；严格大于才停（边界等于预算仍解析）。
 */
internal fun shouldContinuePreviewParsing(
    programLength: Int,
    budgetChars: Int = PREVIEW_PARSE_BUDGET_CHARS,
): Boolean = programLength <= budgetChars
