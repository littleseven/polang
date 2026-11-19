package com.mamba.picme.core.common

/** 字节格式化（B/KB/MB/GB，一位小数）。dedup UI 内有同名 internal 副本；新跨场景代码统一用本实现。 */
object FormatBytes {

    private const val KB = 1024.0
    private const val MB = KB * KB
    private const val GB = MB * KB

    fun format(bytes: Long): String = when {
        bytes >= GB -> "%.1f GB".format(bytes / GB)
        bytes >= MB -> "%.1f MB".format(bytes / MB)
        bytes >= KB -> "%.1f KB".format(bytes / KB)
        else -> "$bytes B"
    }
}
