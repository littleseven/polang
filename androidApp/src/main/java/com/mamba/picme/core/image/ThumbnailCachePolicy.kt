package com.mamba.picme.core.image

/**
 * 缩略图缓存可用性判定（纯函数，JVM 可测）。
 *
 * 背景：系统 [android.content.ContentResolver.loadThumbnail] 按 aspect-fit 生成
 * 缓存图——5:1 长截屏（1080×5400）在 360px 盒子里只缓存到 72×360。网格格子
 * 以 ContentScale.Crop 填满 ~302px 方形，放大 ~4.2 倍 → 长图缩略图模糊。
 * 正常 4:3 照片缓存 360×270，填充放大仅 ~1.33x，无感。
 *
 * 此闸门在读路径（[ThumbnailCacheFetcher.fetch]）拦截：fill 放大倍率超阈值
 * 即弃用缓存返回 null，Coil 回落正常解码管线（长图解码 360×1800 再裁剪，
 * 宽度 360 ≥ 格子 302px，清晰）。生成策略与磁盘缓存不受影响。
 */
object ThumbnailCachePolicy {

    /**
     * 最大允许 fill 放大倍率。
     * 正常 4:3 照片 1.33x 通过；5:1 长图 ~5x 被拒；取 1.5 留有余量。
     */
    const val MAX_UPSCALE_FACTOR = 1.5f

    /**
     * 判断缓存位图是否足以服务目标请求尺寸（ContentScale.Crop 语义）。
     *
     * fill 缩放因子 = max(请求宽/缓存宽, 请求高/缓存高)——Crop 填满目标盒子
     * 所需的放大倍率；≤ 1 表示缩小（始终清晰）。
     *
     * @return true = 缓存可用；false = 应回落 Coil 正常解码
     */
    fun isCacheAdequate(
        cachedWidth: Int,
        cachedHeight: Int,
        requestedWidth: Int,
        requestedHeight: Int
    ): Boolean {
        if (cachedWidth <= 0 || cachedHeight <= 0) return false
        if (requestedWidth <= 0 || requestedHeight <= 0) return true
        val scaleX = requestedWidth.toFloat() / cachedWidth
        val scaleY = requestedHeight.toFloat() / cachedHeight
        return maxOf(scaleX, scaleY) <= MAX_UPSCALE_FACTOR
    }
}
