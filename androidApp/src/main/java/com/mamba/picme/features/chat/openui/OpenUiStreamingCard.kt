package com.mamba.picme.features.chat.openui

import androidx.compose.material3.a2ui.A2uiSurfaceDefaults
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
 *   未到位引用走 dropped 哨兵在 children/数组处剔除——卡片随流渐进生长，引擎零改动；
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
        val rendered = withContext(Dispatchers.Default) {
            runCatching {
                val session = OpenUiSession(
                    program = program,
                    tools = OpenUiToolResolver { _, _ -> OpenUiValue.Null },
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
        A2uiSurfaceDefaults.LoadingIndicator()
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
