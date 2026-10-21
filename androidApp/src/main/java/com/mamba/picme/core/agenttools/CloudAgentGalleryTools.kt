package com.mamba.picme.core.agenttools

import com.mamba.picme.agent.core.inference.remote.tool.GallerySearchIntents
import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.LocalHandleStore
import com.mamba.picme.domain.chat.a2ui.A2uiCardContract
import com.mamba.picme.domain.chat.a2ui.ScanPhotoEntry
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.features.chat.ChatGallerySearch
import com.mamba.picme.features.chat.capability.GALLERY_SUMMARY_UNAVAILABLE_HINT
import com.mamba.picme.features.chat.capability.formatGallerySummaryForReply
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val localHandleStore: LocalHandleStore,
) {

    private val tag = "CloudAgentGalleryTools"

    private val handleJson = Json { encodeDefaults = true }

    /**
     * 图集卡透出回调（云端 Agent 图集卡恢复，2026-10-20）：搜索/细化命中非空时回调完整的
     * PHOTO 资产列表（query、资产、总数、是否细化）。**数据不出设备**——回传服务器的仍是
     * 纯文本计数；资产列表仅经本回调给 UI 层本地渲染卡片（BrowserSessionDelegate 同款
     * delegate 先例）。由发起云端回合的 ViewModel 挂接、回合结束摘除；串行成立的前提 =
     * server per-user 跑轮并发闸 = 1（M2.6）+ 调试页/主聊天页不会同时发回合。
     */
    var mediaResultsListener: ((query: String, assets: List<MediaAsset>, totalCount: Int, isRefinement: Boolean) -> Unit)? =
        null

    /** refine_media_search 的搜索基数（chat 链路 lastResultAssets 的无头等价物，单槽位即可——云端会话串行）。 */
    private val refineBaseMutex = Mutex()
    private var refineBase: List<MediaAsset> = emptyList()

    /** 命中集注册本地句柄，返回 opaque token（无 uri——D2 结构保证）。 */
    private suspend fun registerScanHandle(photos: List<MediaAsset>): String {
        val entries = photos.map { asset -> ScanPhotoEntry(id = asset.id, captureDate = asset.captureDate) }
        val handleId = localHandleStore.put(
            A2uiCardContract.DOMAIN_SCAN,
            handleJson.encodeToString(entries),
        )
        return A2uiCardContract.formatHandleToken(A2uiCardContract.DOMAIN_SCAN, handleId)
    }

    /**
     * 前缀文本 + 句柄 token 尾缀，注册失败降级为纯前缀：
     * Room 写失败（SQLiteException/磁盘满）时搜索本身已成功，只记日志、不带 token 返回，
     * 不抛异常、不走 `error:` 协议（避免成功搜索被云端当成工具失败）。
     */
    private suspend fun appendHandleSuffix(prefix: String, photos: List<MediaAsset>): String {
        return try {
            "$prefix（数据句柄 ${registerScanHandle(photos)}）"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag, "scan handle registration failed, degrading to plain text", e)
            prefix
        }
    }

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
        // 空命中不注册句柄：空集句柄是无意义锚点 + 垃圾行，与 refine 空分支同口径
        if (photos.isEmpty()) {
            return "找到 0 张「$query」的照片"
        }
        mediaResultsListener?.invoke(query, photos, photos.size, false)
        return appendHandleSuffix("找到 ${photos.size} 张「$query」的照片", photos)
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
        mediaResultsListener?.invoke(constraint, refined, refined.size, true)
        return appendHandleSuffix("在上一轮结果内找到 ${refined.size} 张「$constraint」的照片", refined)
    }
}
