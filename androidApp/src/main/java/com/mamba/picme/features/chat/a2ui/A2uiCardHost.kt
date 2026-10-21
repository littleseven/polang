package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.processor.A2uiActionInterceptor
import androidx.a2ui.model.processor.A2uiJsonMessageParser
import androidx.a2ui.model.protocol.A2uiEventAction
import androidx.a2ui.model.protocol.A2uiServerToClientMessage
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * data_ui 卡的渲染宿主：一颗 processor 一张卡（surface 粒度隔离，互不重放串台）。
 *
 * §3.4 不变式落点：不挂 outboundEvents 收集器（无任何上行通道）；用户交互经
 * A2uiActionInterceptor 本地消费（返 null），只触发 onBucketSelected 回调写本地交互态。
 */
@Composable
fun A2uiCardHost(
    surfaceId: String,
    resolvedMessages: List<A2uiServerToClientMessage>,
    catalog: A2uiCatalog,
    onBucketSelected: (bucketLabel: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val processor = remember(surfaceId) {
        A2uiMessageProcessor(
            catalogs = listOf(catalog),
            interceptors = listOf(
                A2uiActionInterceptor { action ->
                    if (action is A2uiEventAction &&
                        action.eventName == PolangBarChartComponent.EVENT_SELECT_BUCKET
                    ) {
                        (action.context["bucket"] as? String)?.let(onBucketSelected)
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
    remember(resolvedMessages) {
        resolvedMessages.forEach { message -> processor.processMessage(message) }
        true
    }
    val surfaces by processor.activeSurfaces.collectAsStateWithLifecycle()
    val surface = surfaces.firstOrNull { surfaceModel -> surfaceModel.id == surfaceId }
    if (surface != null) {
        A2uiSurface(surfaceModel = surface, modifier = modifier)
    }
}

/**
 * messagesJson（信封 JSON 数组）逐封解析——A2uiJsonMessageParser 单信封一 parse。
 *
 * 错误契约：畸形输入（非 JSON 数组 / 元素非对象 / 信封缺字段）抛
 * `org.json.JSONException` 或 parser 校验异常，调用方负责兜底降级（占位卡）。
 */
fun parseA2uiEnvelopes(messagesJson: String): List<A2uiServerToClientMessage> {
    val parser = A2uiJsonMessageParser { input -> AndroidA2uiJsonReader(input) }
    val array = org.json.JSONArray(messagesJson)
    return (0 until array.length()).map { index -> parser.parse(array.getJSONObject(index).toString()) }
}
