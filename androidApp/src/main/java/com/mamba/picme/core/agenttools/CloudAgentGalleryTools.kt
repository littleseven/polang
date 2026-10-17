package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.inference.remote.tool.GallerySearchIntents
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.features.chat.ChatGallerySearch
import com.mamba.picme.features.chat.capability.GALLERY_SUMMARY_UNAVAILABLE_HINT
import com.mamba.picme.features.chat.capability.formatGallerySummaryForReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 云端 Agent 相册工具的无头执行器（ADR-018 M2 Android 修复）。
 *
 * 背景：旧链路 `DeviceToolExecutor` 默认委托 `ChatToolService` @Tool 方法 →
 * `CapabilityRegistry.dispatch(scene=CHAT)`，而 chat 相册能力的 Delegate 只在 ChatScreen
 * 存活期绑定、云端 Agent 页又不切 CHAT 场景——双重条件使命令被注册表**入队**并立即回
 * 「正在为您切换到对应页面执行操作...」，永不真正执行（云端相册调用失败根因）。
 *
 * 本类对齐 iOS `IosCloudAgentToolBridge` 的「绕过场景注册表」路线：直注搜索引擎/摘要用例，
 * 不走场景注册表、不渲染 UI 卡片，结果纯文本回传（`error:` 前缀契约由 [DeviceToolExecutor] /
 * 服务端识别）。意图组装（[GallerySearchIntents]）与意图→过滤转换（[ChatGallerySearch]）
 * 均与 chat 链路同源。
 *
 * 依赖以函数注入（[DeviceToolExecutor]/[AppToolExecutor] 范式），纯 JVM 可测；
 * 生产接线在 AppContainer（mediaSearchEngine / getGallerySummaryUseCase）。
 */
class CloudAgentGalleryTools(
    private val searchByQuery: suspend (query: String, limitToIds: Set<Long>?) -> List<MediaAsset>,
    private val searchByFilter: suspend (filter: StructuredFilter, limitToIds: Set<Long>?) -> List<MediaAsset>,
    private val gallerySummary: suspend (includeDetails: Boolean) -> GallerySummary?,
) {

    private val tag = "CloudAgentGalleryTools"

    /** refine_media_search 的搜索基数（chat 链路 lastResultAssets 的无头等价物，单槽位即可——云端会话串行）。 */
    private val refineBaseMutex = Mutex()
    private var refineBase: List<MediaAsset> = emptyList()

    suspend fun getGallerySummary(): String {
        val summary = try {
            gallerySummary(false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "getGallerySummary failed", e)
            return "error: ${e.message ?: "gallery summary failed"}"
        }
        return summary?.let { formatGallerySummaryForReply(it) } ?: GALLERY_SUMMARY_UNAVAILABLE_HINT
    }

    suspend fun searchMedia(
        query: String,
        person: String,
        fromMs: String,
        toMs: String,
        hasFace: String,
    ): String {
        val intent = GallerySearchIntents.structuredSearchIntent(query, person, fromMs, toMs, hasFace)
        val photos = try {
            if (intent != null) {
                searchByFilter(ChatGallerySearch.structuredFilterOf(intent, query), null)
            } else {
                searchByQuery(query, null)
            }.filter { it.type == MediaType.PHOTO }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "searchMedia failed for '$query'", e)
            return "error: ${e.message ?: "search failed"}"
        }
        refineBaseMutex.withLock { refineBase = photos }
        Logger.i(tag, "searchMedia query='$query' intent=$intent total=${photos.size}")
        return "找到 ${photos.size} 张「$query」的照片"
    }

    suspend fun refineMediaSearch(constraint: String, fromMs: String, toMs: String): String {
        val prior = refineBaseMutex.withLock { refineBase }
        // 与 chat 链路同一回合护栏（spec §3.5-c）：无搜索基数时返回明确错误引导模型改用 search_media，
        // 不静默退化为全局重搜。
        if (prior.isEmpty()) {
            return "error: 没有上一轮搜索结果可细化：请改用 search_media 重新全局搜索" +
                "（人物/时间用 person/fromMs/toMs 结构化参数）"
        }
        val priorIds = prior.map { asset -> asset.id }.toSet()
        val intent = GallerySearchIntents.refineTimeIntent(fromMs, toMs)
        val refined = try {
            if (intent != null) {
                searchByFilter(ChatGallerySearch.structuredFilterOf(intent, constraint), priorIds)
            } else {
                val cleaned = ChatGallerySearch.cleanConstraint(constraint)
                val hits = searchByQuery(cleaned, priorIds)
                ChatGallerySearch.resolveRefine(prior, hits, cleaned)
            }.filter { it.type == MediaType.PHOTO }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "refineMediaSearch failed for '$constraint'", e)
            return "error: ${e.message ?: "refine failed"}"
        }
        // in-set 空 → 保留上一轮结果集不变（与 chat 链路同口径，不覆盖基数）
        if (refined.isEmpty()) {
            return "在上一轮 ${prior.size} 张结果内没有找到「$constraint」的照片"
        }
        refineBaseMutex.withLock { refineBase = refined }
        Logger.i(tag, "refineMediaSearch constraint='$constraint' prior=${prior.size} refined=${refined.size}")
        return "在上一轮结果内找到 ${refined.size} 张「$constraint」的照片"
    }
}
