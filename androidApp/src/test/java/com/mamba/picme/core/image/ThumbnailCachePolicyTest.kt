package com.mamba.picme.core.image

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailCachePolicyTest {

    @Test
    fun `normal photo cache within upscale threshold is adequate`() {
        // 4:3 照片缓存 360×270，请求 360×360 → fill 1.33x
        assertTrue(ThumbnailCachePolicy.isCacheAdequate(360, 270, 360, 360))
    }

    @Test
    fun `long screenshot cache is rejected`() {
        // 5:1 长截屏（1080×5400）aspect-fit 缓存仅 72×360，请求 360×360 → fill 5x
        assertFalse(ThumbnailCachePolicy.isCacheAdequate(72, 360, 360, 360))
    }

    @Test
    fun `wide panorama cache is rejected`() {
        // 横向全景同理：缓存 360×72 → fill 5x
        assertFalse(ThumbnailCachePolicy.isCacheAdequate(360, 72, 360, 360))
    }

    @Test
    fun `exactly at threshold is adequate`() {
        // 240×360 @ 360×360 → fill 恰为 1.5x
        assertTrue(ThumbnailCachePolicy.isCacheAdequate(240, 360, 360, 360))
    }

    @Test
    fun `just above threshold is rejected`() {
        // 239×360 @ 360×360 → fill ~1.506x
        assertFalse(ThumbnailCachePolicy.isCacheAdequate(239, 360, 360, 360))
    }

    @Test
    fun `downscale is always adequate`() {
        // 缓存大于请求（缩小时）始终清晰
        assertTrue(ThumbnailCachePolicy.isCacheAdequate(720, 540, 360, 360))
    }

    @Test
    fun `invalid cached dimensions are inadequate`() {
        assertFalse(ThumbnailCachePolicy.isCacheAdequate(0, 0, 360, 360))
        assertFalse(ThumbnailCachePolicy.isCacheAdequate(-1, 360, 360, 360))
    }
}
