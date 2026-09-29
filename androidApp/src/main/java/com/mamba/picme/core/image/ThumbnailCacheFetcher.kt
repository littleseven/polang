package com.mamba.picme.core.image

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.fetch.FetchResult
import coil.request.Options
import coil.size.Dimension
import coil.size.isOriginal

/**
 * Coil [Fetcher]：拦截 content:// 缩略图请求，
 * 优先从 [ThumbnailCache] 返回结果，绕过 Coil 解码管线和请求队列。
 *
 * 在 [ComponentRegistry] 中按注册顺序优先于 ContentUriFetcher。
 * - 缓存命中：直接返回 [DrawableResult]（Coil 跳过 decode 步骤）
 * - 缓存未命中：返回 null，Coil 自动 fallback 到 ContentUriFetcher 正常流程
 *
 * 拦截条件（在 [Factory.create] 中判断）：
 *   1. URI scheme 为 "content"
 *   2. size 为固定像素且宽度 <= [ThumbnailCache.THUMBNAIL_SIZE_PX]（与缓存分辨率匹配）
 *   3. 不拦截 Size.ORIGINAL（原图请求，如 MediaPager）
 *
 * 阈值必须与缓存实际分辨率一致：缓存位图只有 360px，若拦截更大请求
 * （如人物页封面 ~486px），会返回小图放大显示导致模糊。
 *
 * 放大倍率闸门（[ThumbnailCachePolicy]）：系统 loadThumbnail 按 aspect-fit
 * 生成缓存，长截屏（5:1）仅缓存 72×360；命中后若 fill 放大倍率超阈值，
 * fetch() 同样返回 null 回落 Coil 正常解码（长图解码 360×1800 再 Crop，清晰）。
 */
class ThumbnailCacheFetcher(
    private val uri: Uri,
    private val requestedWidthPx: Int,
    private val requestedHeightPx: Int,
    private val cache: ThumbnailCache
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val cached = cache.get(uri.toString())
        if (cached != null && !cached.isRecycled) {
            // 放大倍率闸门：缓存图相对请求尺寸放大超阈值（长图 aspect-fit 缓存过小），
            // 直接返回会模糊，回落 Coil 正常解码管线
            if (!ThumbnailCachePolicy.isCacheAdequate(
                    cached.width, cached.height, requestedWidthPx, requestedHeightPx
                )
            ) {
                return null
            }
            // 创建 Bitmap 副本，避免 ThumbnailCache LRU 驱逐或 evict() 回收后
            // Compose 绘制时出现 "Canvas: trying to use a recycled bitmap" 崩溃
            val copiedBitmap = cached.copy(cached.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
            return DrawableResult(
                drawable = BitmapDrawable(copiedBitmap),
                isSampled = true,
                dataSource = DataSource.MEMORY_CACHE
            )
        }
        // 缓存未命中 → 返回 null，Coil 自动 fallback 到 ContentUriFetcher
        return null
    }

    /**
     * 工厂：根据请求参数决定是否创建 [ThumbnailCacheFetcher]。
     * 返回 null 表示不拦截，Coil 走默认 Fetcher 链。
     */
    class Factory(private val cache: ThumbnailCache) : Fetcher.Factory<Uri> {

        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader
        ): Fetcher? {
            // 仅处理 content:// URI
            if (data.scheme != "content") return null

            // 不拦截原图请求（MediaPager 全屏查看等）
            if (options.size.isOriginal) return null

            // 仅拦截与缓存分辨率匹配的请求；更大请求（如人物页封面 ~486px）
            // 若返回 360px 缓存图会被放大模糊，必须走 Coil 正常解码
            val width = options.size.width
            if (width is Dimension.Pixels && width.px <= ThumbnailCache.THUMBNAIL_SIZE_PX) {
                // 高度未指定时（如 .size(360) 只给宽）按宽度计，Crop 语义下保守
                val heightPx = (options.size.height as? Dimension.Pixels)?.px ?: width.px
                return ThumbnailCacheFetcher(data, width.px, heightPx, cache)
            }

            return null
        }
    }
}
