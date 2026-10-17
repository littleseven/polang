package com.mamba.picme.agent.core.inference.remote.tool

import com.mamba.picme.agent.core.model.context.SearchIntent
import com.mamba.picme.agent.core.model.context.TimeRange

/**
 * search_media / refine_media_search 的意图组装纯函数（端云各链路同源 SSOT）。
 *
 * 自 [ChatToolService] 抽出为 public object：端侧 chat 链路（[ChatToolService] @Tool 方法）、
 * 云端 Agent 工具桥（iOS `IosCloudAgentToolBridge` / Android `CloudAgentGalleryTools`，
 * 均绕过场景注册表直执）共用同一份组装逻辑，避免漂移。
 */
object GallerySearchIntents {

    /**
     * 把 search_media 的 person/fromMs/toMs/hasFace 组装成 [SearchIntent]（M1 透传，spec §3.4-1；
     * 2026-09-29 增补 hasFace——此前人脸约束只写进 query 时被结构化路径整句丢弃）。
     * 四者全空 → null（走字符串搜索路径）；任一非空 → 结构化过滤精确执行。
     * person 槽位原样透传（称谓→人物名的消歧由引擎层 PersonQueryResolver / 调用方先做）。
     */
    fun structuredSearchIntent(
        query: String,
        person: String,
        fromMs: String,
        toMs: String,
        hasFace: String
    ): SearchIntent? {
        val start = fromMs.trim().toLongOrNull()
        val end = toMs.trim().toLongOrNull()
        val personName = person.trim().ifBlank { null }
        val wantsFace = hasFace.trim().equals("true", ignoreCase = true)
        if (personName == null && start == null && end == null && !wantsFace) return null
        return SearchIntent(
            query = query,
            timeRange = if (start != null || end != null) {
                TimeRange(startMs = start ?: 0L, endMs = end ?: Long.MAX_VALUE)
            } else {
                null
            },
            personName = personName,
            hasFaces = if (wantsFace) true else null,
        )
    }

    /**
     * 把 refine_media_search 的 fromMs/toMs 解析成 [SearchIntent]（timeRange）。
     * 二者都空 → null（走字符串细化路径）；任一非空 → 结构化时间，走精确交集。
     */
    fun refineTimeIntent(fromMs: String, toMs: String): SearchIntent? {
        val start = fromMs.trim().toLongOrNull()
        val end = toMs.trim().toLongOrNull()
        if (start == null && end == null) return null
        return SearchIntent(
            query = "",
            timeRange = TimeRange(startMs = start ?: 0L, endMs = end ?: Long.MAX_VALUE),
        )
    }
}
