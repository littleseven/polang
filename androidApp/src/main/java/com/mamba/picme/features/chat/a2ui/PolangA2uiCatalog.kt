package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.catalog.functions.A2uiMessageFormatter
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.a2ui.catalog.A2uiAudioPlayerRenderer
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.A2uiVideoRenderer
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import coil.compose.AsyncImage
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract

/**
 * polang A2UI catalog（D6：A2UI 只作线格式，目录抽象是自己的）。
 * = 官方 material3 基础目录 + 自研 BarChart；catalogId 固定 [A2uiCardContract.CATALOG_ID]，
 * 云端 createSurface.catalogId 必须等于它（render_ui 工具描述内嵌同一字面量）。
 */
fun polangA2uiCatalog(urlOpener: A2uiUrlOpener): A2uiCatalog {
    val material = materialA2uiBasicCatalogV1(
        image = MaterialA2uiBasicCatalogV1Defaults.image(
            A2uiImageRenderer { url, contentDescription, contentScale, modifier, _ ->
                AsyncImage(
                    model = url,
                    contentDescription = contentDescription,
                    contentScale = contentScale,
                    modifier = modifier,
                )
            }
        ),
        // M2 surfaces 不用 video/audioPlayer：最小占位实现（渲染空盒），不引 Media3 依赖
        video = MaterialA2uiBasicCatalogV1Defaults.video(
            A2uiVideoRenderer { _, modifier, _ ->
                Box(modifier = modifier)
            }
        ),
        audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer(
            A2uiAudioPlayerRenderer { _, _, modifier, _ ->
                Box(modifier = modifier)
            }
        ),
        urlOpener = urlOpener,
        messageFormatter = A2uiMessageFormatter { pattern, locale, arguments ->
            android.icu.text.MessageFormat(pattern, locale).format(arguments)
        },
        localeProvider = A2uiLocaleProvider.Default,
    )
    return A2uiCatalog(
        catalogId = A2uiCardContract.CATALOG_ID,
        components = material.components + PolangBarChartComponent(),
        functions = material.functions,
        themeSchema = material.themeSchema,
    )
}
