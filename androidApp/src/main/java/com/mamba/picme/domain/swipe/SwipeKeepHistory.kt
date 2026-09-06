package com.mamba.picme.domain.swipe

/**
 * KEEP 决策 30 天抑制（F2 spec）：防止「One more round 原样重喂刚 KEEP 过的整队」。
 * 条目编码 `"uri|epochMs"`；读取时顺带清理过期条目（调用方负责把裁剪结果写回存储）。
 * 纯函数，零 Android 依赖，可 JVM 单测。
 */
object SwipeKeepHistory {

    const val TTL_MS: Long = 30L * 24 * 60 * 60 * 1000
    private const val SEPARATOR = '|'

    fun encode(uri: String, keptAtMs: Long): String = "$uri$SEPARATOR$keptAtMs"

    /** 仍活跃的条目（未过期且时间戳可解析；脏条目一并清除）。 */
    fun activeEntries(entries: Set<String>, nowMs: Long): Set<String> =
        entries.filter { entry ->
            val keptAtMs = entry.substringAfterLast(SEPARATOR, "").toLongOrNull() ?: return@filter false
            nowMs - keptAtMs < TTL_MS
        }.toSet()

    /** 活跃条目对应的 uri 集（建队过滤用）。 */
    fun activeUris(entries: Set<String>): Set<String> =
        entries.mapTo(mutableSetOf()) { entry -> entry.substringBeforeLast(SEPARATOR) }
}

/**
 * KEEP 抑制历史存储（引擎无关接口；生产实现 = DataStore stringSet，见 data/preferences/）。
 * 读写均为整体集合（条目量级 = 30 天内 KEEP 数，百级内）。
 */
interface SwipeKeepHistoryStore {
    suspend fun load(): Set<String>
    suspend fun save(entries: Set<String>)
}
