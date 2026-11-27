package com.mamba.picme.features.chat.openui

import com.mamba.picme.data.local.HandleLookup
import com.mamba.picme.domain.chat.openui.OpenUiContract
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.domain.chat.openui.ScanPhotoEntry
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * 照片浏览卡「查看全部」（@Navigate("scan_photos", handleId)）的全量命中集解析支撑件。
 *
 * scan 句柄 payload 在 Room 存完整搜索结果（60 cap 只在 LLM 投影层，与
 * OpenUiCardHost 全量旁路同口径——300 张结果也能全量翻看），本件取其全量
 * 媒体 id 经端侧 resolver 解析 uri（[PRIVACY] uri 不出端），供 chat 内全屏
 * 翻页预览展示。句柄失效/payload 畸形/结果为空 → null（宿主 toast 降级，
 * 不穿透不崩溃）。
 */
internal fun parseScanPhotoIds(payload: String?): List<Long>? {
    if (payload == null) return null
    val entries = runCatching { scanPhotosJson.decodeFromString<List<ScanPhotoEntry>>(payload) }
        .getOrElse { error ->
            if (error is CancellationException) throw error // 协程取消穿透
            return null
        }
    return entries.map { entry -> entry.id }.takeIf { ids -> ids.isNotEmpty() }
}

/** 句柄 id → 全量命中集 uri 清单（保持搜索相关度原序）；任一环节失败/为空 → null。 */
internal suspend fun resolveScanPhotoUris(
    handleLookup: HandleLookup,
    photoResolver: OpenUiPhotoResolver,
    handleId: String,
): List<String>? {
    val ids = parseScanPhotoIds(handleLookup.payloadOf(OpenUiContract.DOMAIN_SCAN, handleId))
        ?: return null
    return photoResolver.resolveAllUrisInOrder(ids).takeIf { uris -> uris.isNotEmpty() }
}

private val scanPhotoHandleRegex = Regex("local://scan/([0-9a-f]+)#photos")

/** 卡 program 文本中照片清单 handle（`local://scan/<id>#photos` 绑定）的 id 段全量提取。 */
internal fun extractScanPhotoHandleIds(programText: String): List<String> =
    scanPhotoHandleRegex.findAll(programText).map { match -> match.groupValues[1] }.toList()

/** session.state 投影（[{id, date}] 形态对象数组，cap 60）的照片 id 提取，作 handle 全量集的回退。 */
internal fun extractProjectedPhotoIds(stateValues: Collection<OpenUiValue>): List<Long> =
    stateValues.flatMap { value ->
        (value as? OpenUiValue.Arr)?.v?.mapNotNull { item ->
            ((item as? OpenUiValue.Obj)?.v?.get("id") as? OpenUiValue.Num)?.v?.toLong()
        } ?: emptyList()
    }.distinct()

/**
 * 卡级全量照片 uri 清单（OpenUiCardHost 预解析与照片点击时按需解析共用同一取数口径）：
 * handle payload 原始 entries（完整搜索结果，60 cap 只在投影层）优先 + 投影 id 回退，
 * 保序去重后批量解析；解析失败（已删除/未索引）跳过。任一环节失败 → 空列表（调用方兜底）。
 */
internal suspend fun resolveCardFullPhotoUris(
    handleLookup: HandleLookup,
    photoResolver: OpenUiPhotoResolver,
    programText: String,
    projectedIds: List<Long>,
): List<String> {
    val fullIds = extractScanPhotoHandleIds(programText).flatMap { handleId ->
        val payload = runCatching { handleLookup.payloadOf(OpenUiContract.DOMAIN_SCAN, handleId) }
            .getOrNull()
        parseScanPhotoIds(payload) ?: emptyList()
    }
    val allIds = (fullIds + projectedIds).distinct()
    return photoResolver.resolveAllUrisInOrder(allIds)
}

private val scanPhotosJson = Json { ignoreUnknownKeys = true }
