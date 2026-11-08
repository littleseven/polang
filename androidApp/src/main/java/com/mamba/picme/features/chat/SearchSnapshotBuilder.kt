package com.mamba.picme.features.chat

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.ResultItem
import com.mamba.picme.agent.core.model.context.SearchResultSnapshot
import com.mamba.picme.core.common.Logger
import org.json.JSONException
import org.json.JSONObject

/**
 * 将聊天中的搜索结果构建为 [SearchResultSnapshot]，供 AgentContext 携带给 LLM。
 *
 * 从 [MediaAsset.labels] JSON 中提取 `tags` 数组，取前 [MAX_TAGS_PER_ITEM] 个作为可指代标签。
 */
internal object SearchSnapshotBuilder {

    /** 保留最近几轮搜索快照用于多轮对话上下文。 */
    internal const val MAX_ROUNDS = 3

    /** 快照项上限（横滑卡退役后承接原卡片展示上限语义：快照服务 LLM 指代与出卡取数，前 20 项足够）。 */
    internal const val MAX_ITEMS = 20

    /** 每张图片取前几个标签用于自然语言指代。 */
    internal const val MAX_TAGS_PER_ITEM = 3

    fun build(
        results: List<MediaAsset>,
        query: String,
        totalCount: Int,
        isRefinement: Boolean
    ): SearchResultSnapshot {
        val items = results.map { asset ->
            ResultItem(
                mediaId = asset.id.toString(),
                tags = extractTags(asset.labels)
            )
        }
        return SearchResultSnapshot(
            query = query,
            results = items,
            totalCount = totalCount,
            isRefinement = isRefinement,
            timestamp = System.currentTimeMillis()
        )
    }

    private fun extractTags(labelsJson: String?): List<String> {
        if (labelsJson.isNullOrBlank()) return emptyList()
        return try {
            val obj = JSONObject(labelsJson)
            val arr = obj.optJSONArray("tags")
            if (arr != null) {
                List(arr.length()) { arr.optString(it, "") }
                    .filter { it.isNotBlank() }
                    .take(MAX_TAGS_PER_ITEM)
            } else {
                emptyList()
            }
        } catch (e: JSONException) {
            Logger.w("SearchSnapshotBuilder", "Failed to parse labels JSON", e)
            emptyList()
        }
    }
}
