package com.mamba.picme.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.local.DedupHashDao
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.OrganizeRow
import com.mamba.picme.domain.organize.OrganizeItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** dedup_hash 批量查询分批大小（SQLite IN 参数上限 999，留余量取 500，与 DedupScanner 一致）。 */
private const val DEDUP_HASH_BATCH_SIZE = 500

/**
 * 整理中心（F1）类目数据源：Room `media_assets` 轻量投影 + MediaStore 批量 meta
 * （path/sizeBytes/尺寸）按 **content uri 字符串** 合并为 [OrganizeItem] 快照。
 *
 * join key 不能用媒体 id：`MediaAsset.id` 是 MediaRepositoryImpl 的负值合成编码，
 * 与 MediaStore `_ID` 不相等（与 MediaStoreDedupMediaSource 同一约束）。
 */
class OrganizeRepository(
    context: Context,
    private val mediaDao: MediaDao,
    private val dedupHashDao: DedupHashDao,
    private val ioDispatcher: CoroutineDispatcher,
) {

    private val appContext = context.applicationContext

    /** hub 统计流：Room 行 + MediaStore meta 按 uri 合并；媒体库任何写触发重算。 */
    fun observeItems(): Flow<List<OrganizeItem>> =
        mediaDao.observeOrganizeRows().map { rows -> mergeRows(rows) }

    /** 类目详情全量（一次性）。 */
    suspend fun loadItems(): List<OrganizeItem> =
        mergeRows(mediaDao.observeOrganizeRows().first())

    private suspend fun mergeRows(rows: List<OrganizeRow>): List<OrganizeItem> = withContext(ioDispatcher) {
        if (rows.isEmpty()) return@withContext emptyList()
        val metaByUri = queryMediaStoreMeta(appContext.contentResolver)
        val pixelAreaByUri = queryCachedPixelAreas(rows.map { row -> row.uri })
        rows.map { row ->
            val meta = metaByUri[row.uri]
            OrganizeItem(
                uri = row.uri,
                // isVideo 以 Room type 为准（MediaStore collection 来源仅作 meta 补充）
                isVideo = row.type == MediaType.VIDEO.name,
                captureDate = row.captureDate,
                sizeBytes = meta?.sizeBytes ?: 0L,
                relativePath = meta?.path,
                ocrText = row.ocrText,
                pixelArea = pixelAreaByUri[row.uri] ?: meta?.pixelArea,
                labels = row.labels,
                hasFace = row.hasFace,
                aestheticScore = row.aestheticScore,
                faceQualityScore = row.faceQualityScore,
            )
        }
    }

    /** dedup_hash 缓存的像素面积（>0 有效），分批 500 防 SQLite IN 上限。 */
    private suspend fun queryCachedPixelAreas(uris: List<String>): Map<String, Long> =
        uris.chunked(DEDUP_HASH_BATCH_SIZE)
            .flatMap { chunk -> dedupHashDao.getByUris(chunk) }
            .mapNotNull { entity ->
                if (entity.pixelArea > 0) entity.uri to entity.pixelArea.toLong() else null
            }
            .toMap()

    private data class MediaMeta(
        val sizeBytes: Long,
        /** 截图目录判定路径：RELATIVE_PATH（API 29+），缺失时 DATA 列兜底。 */
        val path: String?,
        /** 像素面积（WIDTH×HEIGHT；列缺失或脏值 ≤0 为 null，OCR 判定退回绝对阈值）。 */
        val pixelArea: Long?,
    )

    /** Images + Video 两个 collection 各查一次，key = content uri 字符串（withAppendedId 重建）。 */
    private fun queryMediaStoreMeta(resolver: ContentResolver): Map<String, MediaMeta> {
        val result = HashMap<String, MediaMeta>()
        queryCollectionMeta(resolver, MediaStore.Images.Media.getContentUri("external"), result)
        queryCollectionMeta(resolver, MediaStore.Video.Media.getContentUri("external"), result)
        return result
    }

    @Suppress("DEPRECATION") // DATA 列 API 29 起废弃，但查询仍返回路径，作 API<29 截图识别兜底
    private fun queryCollectionMeta(resolver: ContentResolver, collection: Uri, out: MutableMap<String, MediaMeta>) {
        val hasQColumns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        // RELATIVE_PATH 是 Q-only 列，低版本入 projection 会抛 IllegalArgumentException
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.DATA,
        ).apply {
            if (hasQColumns) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        runCatching {
            resolver.query(collection, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                // 可选列防御性取 -1（OEM 裁剪可能缺列），不让单列缺失拖垮整次查询
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val pathCol = if (hasQColumns) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)).toString()
                    val relativePath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val width = if (widthCol >= 0) cursor.getLong(widthCol) else 0L
                    val height = if (heightCol >= 0) cursor.getLong(heightCol) else 0L
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                    out[uri] = MediaMeta(
                        sizeBytes = if (size > 0) size else 0L,
                        path = relativePath ?: dataPath,
                        pixelArea = if (width > 0 && height > 0) width * height else null,
                    )
                }
            }
        }.onFailure { error -> Logger.w(TAG, "query mediastore meta failed: $collection", error) }
    }

    private companion object {
        const val TAG = "PoLang:Organize"
    }
}
