package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.processor.A2uiActionInterceptor
import androidx.a2ui.model.protocol.A2uiEventAction
import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamba.picme.BuildConfig
import com.mamba.picme.R
import com.mamba.picme.core.designsystem.AppShapes
import com.mamba.picme.core.designsystem.Spacing
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract

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
    // 键粒度收到整颗 part（data class 可比）：viewState 回流产生新 part 即重解析、
    // 按最新 viewState 重填 /selected（终审 m3——恢复语义，/selected 在恢复路径最后追加，时序正确）
    val resolution by produceState<A2uiResolution?>(initialValue = null, part) {
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
                    path = A2uiCardContract.DATA_PATH_SELECTED,
                    value = selected,
                )
            )
        }
    }
    when (val current = resolution) {
        null -> A2uiSurfaceDefaults.LoadingIndicator()
        is A2uiResolution.Degraded -> degradedContent(part.fallbackSummary)
        is A2uiResolution.Ready -> key(part.partId) {
            // 点选即时高亮（终审 M2）：拦截器除持久化外，经 processorRef 就地回写 /selected。
            // also 赋值在 remember 块内：键失效重建时 ref 同步指向新 processor，不留悬空引用。
            // 类型全限定：compose.ui.A2uiMessageProcessor 是工厂函数（同名），类本体在 model.processor
            var processorRef: androidx.a2ui.model.processor.A2uiMessageProcessor? = null
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
                                    // processMessage 仅入队，拦截器挂起回调内调用安全
                                    processorRef?.processMessage(
                                        A2uiUpdateDataModelMessage(
                                            surfaceId = part.surfaceId,
                                            path = A2uiCardContract.DATA_PATH_SELECTED,
                                            value = bucket,
                                        )
                                    )
                                }
                                null // 消费：不上行
                            } else {
                                null // M2 一切动作本地消费，不出端
                            }
                        }
                    ),
                ).also { created -> processorRef = created }
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
                if (BuildConfig.DEBUG) {
                    // debug 徽标 overlay：显性标注此卡由 A2UI 协议驱动，点击开蓝图查看器
                    Box(modifier = modifier) {
                        A2uiSurface(surfaceModel = surface)
                        A2uiDebugBadge(
                            surfaceId = part.surfaceId,
                            messagesJson = part.messagesJson,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                } else {
                    A2uiSurface(surfaceModel = surface, modifier = modifier)
                }
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
    Card(modifier = modifier.fillMaxWidth(), shape = AppShapes.card) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
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
