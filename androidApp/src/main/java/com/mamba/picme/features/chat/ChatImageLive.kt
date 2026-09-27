package com.mamba.picme.features.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 判定 chat 内一张结果图当前是否可展示（未过期）。
 *
 * - content://（已保存到相册）：恒为存活，免疫 LRU；
 * - file:// 或裸路径（私有缓存）：取决于文件是否仍存在；
 * - null/空：不存活。
 *
 * 判定基于 [File.exists]（stat），不依赖 Coil 内存缓存，确定性强。
 */
fun chatImageIsLive(uri: String?): Boolean {
    if (uri.isNullOrBlank()) return false
    if (uri.startsWith("content://")) return true
    val path = uri.removePrefix("file://")
    return File(path).exists()
}

/**
 * [chatImageIsLive] 的组合安全版本（ADR-016 M4 spec §8）：File.exists 是磁盘 IO，
 * 不允许在组合期同步调用（流式重组会反复触发）。produceState 挂 IO 调度器异步判定，
 * 乐观初值 true（判定期先按存活渲染，过期图随后换占位——结果图 LRU 清理是低频事件，
 * 短暂误显优于每次重组阻塞主线程）。
 */
@Composable
fun rememberChatImageIsLive(uri: String?): Boolean {
    val isLive by produceState(initialValue = true, key1 = uri) {
        value = withContext(Dispatchers.IO) { chatImageIsLive(uri) }
    }
    return isLive
}
