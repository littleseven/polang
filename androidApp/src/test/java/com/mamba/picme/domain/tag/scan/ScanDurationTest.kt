package com.mamba.picme.domain.tag.scan

import com.mamba.picme.data.local.entity.TagScanPass
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDurationTest {

    @Test
    fun `秒级 ETA 渲染为纯秒`() {
        assertEquals("45s", formatDuration(45_000L))
    }

    @Test
    fun `零与负值不崩溃渲染 0s`() {
        assertEquals("0s", formatDuration(0L))
        assertEquals("0s", formatDuration(-100L))
    }

    @Test
    fun `分钟级 ETA 带余秒`() {
        assertEquals("1m 30s", formatDuration(90_000L))
        assertEquals("59m 59s", formatDuration(3_599_000L))
    }

    @Test
    fun `小时级 ETA 带余分钟`() {
        assertEquals("2h 0m", formatDuration(7_200_000L))
    }

    @Test
    fun `恰 24h 按天展示为 1d 0h 而非 24h 0m`() {
        // 回归：ETA 上限移除后超 24h 合法估值必须按天展示（原 FormatDurationTest 用例随函数迁移）
        assertEquals("1d 0h", formatDuration(24 * 3_600_000L))
        assertEquals("1d 1h", formatDuration(25 * 3_600_000L))
    }

    @Test
    fun `天级 ETA 带余小时`() {
        assertEquals("1d 2h", formatDuration(26 * 3_600_000L))
    }

    @Test
    fun `阶段映射 TagScanPass 到 ScanStage 供通知与卡片共用`() {
        assertEquals(ScanStage.FACE, scanStageOf(TagScanPass.FACE_DETECTION))
        assertEquals(ScanStage.CLUSTER, scanStageOf(TagScanPass.DBSCAN))
        assertEquals(ScanStage.CONTENT, scanStageOf(TagScanPass.IMAGE_TAGGING))
        assertEquals(ScanStage.SEMANTIC, scanStageOf(TagScanPass.MOBILE_CLIP_ENCODING))
        assertEquals(ScanStage.PREPARING, scanStageOf(null))
    }
}
