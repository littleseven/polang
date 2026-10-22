package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.processor.A2uiActionInterceptor
import androidx.a2ui.model.protocol.A2uiEventAction
import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.A2uiSurfaceDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamba.picme.R
import com.mamba.picme.domain.chat.MessagePart

/**
 * data_ui 卡的渲染宿主：一颗 processor 一张卡（surface 粒度隔离，互不重放串台）。
 *
 * §3.4 不变式落点：不挂 outboundEvents 收集器（无任何上行通道）；用户交互经
 * A2uiActionInterceptor 本地消费（返 null），只触发 onViewStateChange 回调写本地交互态。
 *
 * T7：入参改为整颗 part + HandleResolver。resolution 三态：
 * 解析中 → LoadingIndicator；Degraded → degradedContent(fallbackSummary)（US-6）；
 * Ready → T6 接线渲染，viewState `selected=<label>` 作为初始填槽追加 updateDataModel（US-7）。
 */
@Composable
fun A2uiCardHost(
    part: MessagePart.A2uiCard,
    resolver: HandleResolver,
    catalog: A2uiCatalog,
    onViewStateChange: (viewState: String) -> Unit,
    degradedContent: @Composable (summary: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolution by produceState<A2uiResolution?>(initialValue = null, part.partId) {
        value = resolver.resolve(part.surfaceId, part.messagesJson)
        // viewState 初始填槽（US-7 确定性重建）：selected=<label> → 追加 updateDataModel
        val ready = value as? A2uiResolution.Ready
        val selected = part.viewState
            ?.removePrefix("selected=")
            ?.takeIf { it != part.viewState && it.isNotEmpty() }
        if (ready != null && selected != null) {
            value = ready.copy(
                messages = ready.messages + A2uiUpdateDataModelMessage(
                    surfaceId = part.surfaceId,
                    path = "/selected",
                    value = selected,
                )
            )
        }
    }
    when (val current = resolution) {
        null -> A2uiSurfaceDefaults.LoadingIndicator()
        is A2uiResolution.Degraded -> degradedContent(part.fallbackSummary)
        is A2uiResolution.Ready -> key(part.partId) {
            val processor = remember(part.surfaceId) {
                A2uiMessageProcessor(
                    catalogs = listOf(catalog),
                    interceptors = listOf(
                        A2uiActionInterceptor { action ->
                            if (action is A2uiEventAction &&
                                action.eventName == PolangBarChartComponent.EVENT_SELECT_BUCKET
                            ) {
                                (action.context["bucket"] as? String)?.let { bucket ->
                                    onViewStateChange("selected=$bucket")
                                }
                                null // 消费：不上行
                            } else {
                                null // M2 一切动作本地消费，不出端
                            }
                        }
                    ),
                )
            }
            // processMessage 只入队：须挂 collectMessages 处理循环，消息才会真正落到 surface
            LaunchedEffect(processor) { processor.collectMessages() }
            remember(current.messages) {
                current.messages.forEach { message -> processor.processMessage(message) }
                true
            }
            val surfaces by processor.activeSurfaces.collectAsStateWithLifecycle()
            val surface = surfaces.firstOrNull { surfaceModel -> surfaceModel.id == part.surfaceId }
            if (surface != null) {
                A2uiSurface(surfaceModel = surface, modifier = modifier)
            }
        }
    }
}

/**
 * 降级占位卡（US-6）：handle 缺失/不可解析时顶替整张卡。
 * 两条静态 label 走五语资源；fallbackSummary 是云端动态内容（LLM 写的句子），[I18N] 豁免。
 */
@Composable
fun A2uiCardDegraded(summary: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.chat_a2ui_card_unavailable),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.chat_a2ui_card_data_elsewhere, summary),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
