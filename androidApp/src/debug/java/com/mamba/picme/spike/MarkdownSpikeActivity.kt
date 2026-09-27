package com.mamba.picme.spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.mamba.picme.core.designsystem.PoLangTheme
import com.mamba.picme.features.chat.spike.MarkdownSpikeScreen

/**
 * M3 spike 临时入口（仅 debug 构建，验证后整体可删）：
 * `adb shell am start -n com.mamba.picme/.spike.MarkdownSpikeActivity` 直达 spike 屏幕，
 * 不走 instrument 通道。屏幕内置流式模拟（6 字/40ms 步进追加，循环重放）。
 */
class MarkdownSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PoLangTheme {
                MarkdownSpikeScreen()
            }
        }
    }
}
