package com.mamba.picme.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.MediaDao

/**
 * media_assets.relativePath/sizeBytes 存量回填器（Room v29，Agent 清理 M1 数据基础）。
 *
 * 仿 [com.mamba.picme.data.indexing.geo.LocationIndexer]：三函数注入（候选查询 /
 * MediaStore meta 批查 / 回写）让核心循环 JVM 纯可测。语义不变式：
 * 1. 只补 `relativePath IS NULL` 的行（新行由 MediaRepositoryImpl 同步插入直接带列）；
 * 2. provider 缺失（MediaStore 已删/不可见）的行本轮不写但计入 attempted——下一批
 *    不再重复处理，候选集永不清空也不会死循环；
 * 3. 返回实际补写行数。
 */
class MediaMetadataBackfiller(
    private val candidateUris: suspend (limit: Int) -> List<String>,
    private val metaProvider: suspend (uris: List<String>) -> Map<String, Pair<String?, Long>>,
    private val writer: suspend (uri: String, meta: Pair<String?, Long>) -> Unit,
) {

    /** 批量回填直到候选耗尽（空批 / 全部已 attempted），返回累计补写行数。 */
    suspend fun runUntilDone(batchLimit: Int = DEFAULT_BATCH_LIMIT): Int {
        var written = 0
        val attempted = HashSet<String>()
        while (true) {
            val candidates = candidateUris(batchLimit)
            val fresh = candidates.filterNot { uri -> uri in attempted }
            if (fresh.isEmpty()) break
            fresh.forEach { uri -> attempted.add(uri) }
            val metaByUri = metaProvider(fresh)
            for (uri in fresh) {
                val meta = metaByUri[uri] ?: continue
                writer(uri, meta)
                written++
            }
        }
        return written
    }

    companion object {
        private const val TAG = "PoLang:MediaMeta"

        /** 单批候选上限（与 DEDUP_HASH_BATCH_SIZE / OrganizeRepositoryImpl 分批口径一致）。 */
        const val DEFAULT_BATCH_LIMIT = 500

        /** 生产装配：Room 候选/回写 + MediaStore 逐 uri 单行直查（item 主键命中，等价批查）。 */
        fun create(context: Context, mediaDao: MediaDao): MediaMetadataBackfiller {
            val appContext = context.applicationContext
            return MediaMetadataBackfiller(
                candidateUris = { limit -> mediaDao.getMediaNeedingMetaBackfill(limit) },
                metaProvider = { uris -> queryMediaStoreMeta(appContext.contentResolver, uris) },
                writer = { uri, meta -> mediaDao.updateMediaMeta(uri, meta.first, meta.second) },
            )
        }

        /**
         * 逐 uri item 查询 SIZE + RELATIVE_PATH（API<29 回退 DATA 绝对路径）；行不存在
         * （已删/回收站）或查询失败的 uri 不进 map——留给上层按「provider 缺失」跳过。
         */
        @Suppress("DEPRECATION") // DATA 列 API 29 起废弃，低版本作路径兜底（判法同 OrganizeRepositoryImpl）
        private fun queryMediaStoreMeta(
            resolver: ContentResolver,
            uris: List<String>,
        ): Map<String, Pair<String?, Long>> {
            val hasQColumns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            val pathColumn = if (hasQColumns) {
                MediaStore.MediaColumns.RELATIVE_PATH
            } else {
                MediaStore.MediaColumns.DATA
            }
            val projection = arrayOf(MediaStore.MediaColumns.SIZE, pathColumn)
            val result = HashMap<String, Pair<String?, Long>>(uris.size)
            for (uriString in uris) {
                val meta = runCatching {
                    resolver.query(Uri.parse(uriString), projection, null, null, null)?.use { cursor ->
                        if (!cursor.moveToNext()) return@use null
                        // 可选列防御性取 -1（OEM 裁剪可能缺列），缺失不拖垮整批
                        val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                        val pathCol = cursor.getColumnIndex(pathColumn)
                        val size = if (sizeCol >= 0 && !cursor.isNull(sizeCol)) cursor.getLong(sizeCol) else 0L
                        val path = if (pathCol >= 0) cursor.getString(pathCol) else null
                        path to size
                    }
                }.onFailure { error ->
                    Logger.w(TAG, "query mediastore meta failed: $uriString", error)
                }.getOrNull()
                if (meta != null) result[uriString] = meta
            }
            return result
        }
    }
}
