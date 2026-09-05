package com.mamba.picme.features.gallery.dedup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.dedup.DedupScanner
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

/** 尺寸未知（WIDTH/HEIGHT 列缺失或脏值 ≤0）时 OCR 判文档的绝对字符数兜底阈值。 */
internal const val DOCUMENT_OCR_CHAR_THRESHOLD = 200

/**
 * DOCUMENT 文字密度阈值（字符数 / 百万像素，spec §10.2 面积归一）。
 * 依据：旧绝对阈值 200 字符在典型 12MP 照片上等价约 17 字符/MP，取整 20；
 * 归一后大图低文字密度（海报/路牌/漫画）不再误判文档，小图密集文字仍可命中。
 */
internal const val DOCUMENT_OCR_DENSITY_PER_MEGAPIXEL = 20

/** MediaStore 截图目录约定（路径 contains，大小写不敏感）。 */
private const val SCREENSHOT_DIR_KEYWORD = "screenshots"

/**
 * DOCUMENT 标签关键词启发式：labels 为 TAG Pass 3 产出的自由文本（中英混合）。
 * 英文按整词（token）匹配并容忍可选复数后缀（documents/receipts/texts），
 * 避免 `context`/`texture`/`textile` 被子串 "text" 误伤；
 * 中文无词边界，按子串匹配（"截图文字" 等复合词由 "文字" 覆盖，不单独列死条目）。
 * 误伤代价仅是 VISUAL 组不预选；漏检更危险（DOCUMENT 误归 GENERAL 会被自动预选）。
 */
private val DOCUMENT_LABEL_KEYWORDS_EN = listOf("document", "receipt", "text", "screenshot_text")
private val DOCUMENT_LABEL_KEYWORDS_ZH = listOf("文档", "证件", "票据", "文字")

private val LABEL_TOKEN_REGEX = Regex("[a-z0-9_]+")

/** labels 命中文档关键词：中文子串 + 英文整词双通道。 */
private fun labelsIndicateDocument(labels: String): Boolean {
    val lower = labels.lowercase()
    if (DOCUMENT_LABEL_KEYWORDS_ZH.any { keyword -> lower.contains(keyword) }) return true
    val tokens = LABEL_TOKEN_REGEX.findAll(lower).map { match -> match.value }.toHashSet()
    return DOCUMENT_LABEL_KEYWORDS_EN.any { keyword ->
        tokens.any { token -> token == keyword || token == keyword + "s" }
    }
}

/**
 * OCR 文字密度判定（spec §10.2 面积归一）：[pixelArea] 可用时按字符数/图面积，
 * 否则（尺寸列缺失或脏值）退回绝对字符数兜底。
 */
private fun isDocumentText(ocrText: String?, pixelArea: Long?): Boolean {
    val chars = ocrText?.length ?: 0
    if (chars == 0) return false
    return if (pixelArea != null && pixelArea > 0) {
        chars.toLong() * 1_000_000L > pixelArea * DOCUMENT_OCR_DENSITY_PER_MEGAPIXEL
    } else {
        chars > DOCUMENT_OCR_CHAR_THRESHOLD
    }
}

/**
 * 内容类型识别纯函数（spec §10.2，零额外推理，可 JVM 单测）。
 * 优先级 SCREENSHOT > DOCUMENT > PORTRAIT > GENERAL；TAG 未覆盖（信号全空）一律 GENERAL。
 *
 * @param path RELATIVE_PATH（API 29+）或 DATA 列兜底路径，用于截图目录判定。
 * @param pixelArea 图片像素面积（WIDTH×HEIGHT），未知时传 null。
 *
 * public：整理中心（domain/organize）DOCUMENT 类目判定复用同一实现。
 */
fun detectContentType(
    path: String?,
    ocrText: String?,
    pixelArea: Long?,
    labels: String?,
    hasFace: Boolean,
    faceQualityScore: Float?,
): DedupContentType = when {
    path?.contains(SCREENSHOT_DIR_KEYWORD, ignoreCase = true) == true ->
        DedupContentType.SCREENSHOT
    isDocumentText(ocrText, pixelArea) || labels?.let(::labelsIndicateDocument) == true ->
        DedupContentType.DOCUMENT
    hasFace || faceQualityScore != null -> DedupContentType.PORTRAIT
    else -> DedupContentType.GENERAL
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
