package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.catalog.functions.A2uiMessageFormatter
import androidx.a2ui.model.catalog.functions.A2uiUrlOpener
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.a2ui.catalog.A2uiAudioPlayerRenderer
import androidx.compose.material3.a2ui.catalog.A2uiImageRenderer
import androidx.compose.material3.a2ui.catalog.A2uiVideoRenderer
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import coil.compose.AsyncImage
import com.mamba.picme.core.designsystem.A2uiCardTokens
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import com.mamba.picme.features.chat.media.ChatAudioPlayer
import com.mamba.picme.features.chat.media.ChatVideoPlayer

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
        // Video/AudioPlayer 真渲染（Media3，共用件 features/chat/media/），卡内不自动播
        video = MaterialA2uiBasicCatalogV1Defaults.video(
            A2uiVideoRenderer { url, modifier, onError ->
                ChatVideoPlayer(
                    url = url,
                    modifier = modifier.fillMaxWidth().height(A2uiCardTokens.videoHeight),
                    onError = onError,
                )
            }
        ),
        audioPlayer = MaterialA2uiBasicCatalogV1Defaults.audioPlayer(
            A2uiAudioPlayerRenderer { url, description, modifier, onError ->
                ChatAudioPlayer(
                    url = url,
                    description = description,
                    modifier = modifier.fillMaxWidth().height(A2uiCardTokens.audioPlayerHeight),
                    onError = onError,
                )
            }
        ),
        // 纵向 List 封顶（防聊天流无限高测量崩溃，见 BoundedVerticalA2uiList 文件头）
        // 纵向 List 封顶（防聊天流无限高测量崩溃，见 BoundedVerticalA2uiList 文件头）
        list = BoundedVerticalA2uiList(maxHeight = A2uiCardTokens.listMaxHeight),
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
