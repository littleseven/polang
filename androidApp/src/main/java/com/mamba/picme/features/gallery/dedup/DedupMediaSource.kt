package com.mamba.picme.features.gallery.dedup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.dedup.DedupScanner
import com.mamba.picme.domain.dedup.detectContentType
import com.mamba.picme.domain.repository.AndroidMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

/**
 * 扫描输入源抽象（Agent First：显式注入接缝，ViewModel 单测用 lambda fake）。
 *
 * 生产实现为 [MediaStoreDedupMediaSource]。
 */
fun interface DedupMediaSource {
    suspend fun photoScanItems(): List<DedupScanner.ScanItem>
}

/**
 * 生产取数：相册库照片（[AndroidMediaRepository.allMedia]，仅元数据，[LAZY_LOAD]）
 * + MediaStore 一次性批量查询补 sizeBytes/mime/modifiedAt（DATE_MODIFIED 秒→毫秒）。
 * 元数据缺失（如文件已不可读）的照片跳过，不参与去重。
 *
 * 注意 join key 必须用 content uri：`MediaAsset.id` 是 [MediaRepositoryImpl] 的
 * syntheticMediaId 负值编码（区分系统/DB 来源），与 MediaStore `_ID` 不相等。
 */
class MediaStoreDedupMediaSource(
    private val context: Context,
    private val repository: AndroidMediaRepository,
) : DedupMediaSource {

    override suspend fun photoScanItems(): List<DedupScanner.ScanItem> = withContext(Dispatchers.IO) {
        val photos = repository.allMedia.firstOrNull().orEmpty()
            .filter { asset -> asset.type == MediaType.PHOTO }
        if (photos.isEmpty()) return@withContext emptyList()
        val metaByUri = queryImageMeta(context.contentResolver)
        photos.mapNotNull { asset ->
            val meta = metaByUri[asset.uri] ?: return@mapNotNull null
            DedupScanner.ScanItem(
                uri = asset.uri,
                sizeBytes = meta.sizeBytes,
                mime = meta.mime,
                captureDate = asset.captureDate,
                modifiedAt = meta.modifiedAtMs,
                aestheticScore = asset.aestheticScore,
                contentType = detectContentType(
                    path = meta.path,
                    ocrText = asset.ocrText,
                    pixelArea = meta.pixelArea,
                    labels = asset.labels,
                    hasFace = asset.hasFace,
                    faceQualityScore = asset.faceQualityScore,
                ),
                faceQualityScore = asset.faceQualityScore,
            )
        }
    }

    private data class ImageMeta(
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val mime: String,
        /** 截图目录判定路径：RELATIVE_PATH（API 29+），缺失时 DATA 列兜底（全版本可查）。 */
        val path: String?,
        /** 像素面积（WIDTH×HEIGHT，API 16+ 即有该列；列缺失或脏值 ≤0 为 null，OCR 判定退回绝对阈值）。 */
        val pixelArea: Long?,
    )

    /** key = content uri 字符串（与 `MediaAsset.uri` 同源：withAppendedId(EXTERNAL_CONTENT_URI, _ID)） */
    @Suppress("DEPRECATION") // DATA 列 API 29 起废弃，但查询仍返回路径，作 API<29 截图识别兜底
    private fun queryImageMeta(resolver: ContentResolver): Map<String, ImageMeta> {
        val contentUri = MediaStore.Images.Media.getContentUri("external")
        val hasQColumns = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        // WIDTH/HEIGHT 自 API 16 可用（非 Q-only），全版本入 projection 让 API 24-28 也享受密度归一；
        // RELATIVE_PATH 才是 Q-only 列，低版本入 projection 会抛 IllegalArgumentException
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
        ).apply {
            if (hasQColumns) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            }
        }.toTypedArray()
        val result = HashMap<String, ImageMeta>()
        runCatching {
            resolver.query(
                contentUri,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                // 可选列防御性取 -1（OEM 裁剪/测试 fake 可能缺列），不让单列缺失拖垮整次查询
                val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                val pathCol = if (hasQColumns) {
                    cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val widthCol = cursor.getColumnIndex(MediaStore.MediaColumns.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
                while (cursor.moveToNext()) {
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0) continue
                    val uri = ContentUris.withAppendedId(contentUri, cursor.getLong(idCol)).toString()
                    val relativePath = if (pathCol >= 0) cursor.getString(pathCol) else null
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val width = if (widthCol >= 0) cursor.getLong(widthCol) else 0L
                    val height = if (heightCol >= 0) cursor.getLong(heightCol) else 0L
                    result[uri] = ImageMeta(
                        sizeBytes = size,
                        modifiedAtMs = cursor.getLong(modifiedCol) * 1_000L,
                        mime = cursor.getString(mimeCol) ?: "image/*",
                        // DATA 仅作 API<29 兜底（29+ 正常走 RELATIVE_PATH，个别行缺失才回退 DATA）
                        path = relativePath ?: dataPath,
                        pixelArea = if (width > 0 && height > 0) width * height else null,
                    )
                }
            }
        }.onFailure { error -> Logger.w(TAG, "query image meta failed", error) }
        return result
    }

    private companion object {
        const val TAG = "PoLang:Dedup"
    }
}
