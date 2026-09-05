package com.mamba.picme.domain.dedup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内容类型识别纯函数单测（spec §10.2 / AC-7 退化原则）：
 * SCREENSHOT 路径 > DOCUMENT 文字密度/标签 > PORTRAIT 人脸信号 > GENERAL 兜底。
 */
class DedupContentTypeDetectTest {

    private fun detect(
        path: String? = null,
        ocrText: String? = null,
        pixelArea: Long? = null,
        labels: String? = null,
        hasFace: Boolean = false,
        faceQualityScore: Float? = null,
    ) = detectContentType(
        path = path,
        ocrText = ocrText,
        pixelArea = pixelArea,
        labels = labels,
        hasFace = hasFace,
        faceQualityScore = faceQualityScore,
    )

    @Test
    fun `screenshots dir in path detects SCREENSHOT case-insensitively`() {
        assertEquals(DedupContentType.SCREENSHOT, detect(path = "DCIM/Screenshots/"))
        assertEquals(DedupContentType.SCREENSHOT, detect(path = "Pictures/screenshots/"))
        // API<29 走 DATA 列绝对路径兜底，同一 contains 判定
        assertEquals(DedupContentType.SCREENSHOT, detect(path = "/storage/emulated/0/Pictures/Screenshots/a.png"))
        assertEquals(DedupContentType.GENERAL, detect(path = "DCIM/Camera/"))
    }

    @Test
    fun `ocr text density over threshold detects DOCUMENT`() {
        // 密度判定：字符数/像素面积 > 20 字符/MP
        // 201 字符在 12MP 照片上密度 ~16.8/MP，归一后不再误判
        assertEquals(
            DedupContentType.GENERAL,
            detect(ocrText = "字".repeat(201), pixelArea = 12_000_000L),
        )
        // 同字符数在 0.5MP 小图上密度 402/MP，密集文字仍判文档
        assertEquals(
            DedupContentType.DOCUMENT,
            detect(ocrText = "字".repeat(201), pixelArea = 500_000L),
        )
        // 尺寸未知（列缺失或脏值）退回绝对字符数兜底
        val dense = "字".repeat(DOCUMENT_OCR_CHAR_THRESHOLD + 1)
        assertEquals(DedupContentType.DOCUMENT, detect(ocrText = dense))
        val sparse = "字".repeat(DOCUMENT_OCR_CHAR_THRESHOLD)
        assertEquals(DedupContentType.GENERAL, detect(ocrText = sparse))
    }

    @Test
    fun `document-like labels detect DOCUMENT`() {
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "室内,document,纸张"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "receipt photo"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "证件照"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "截图文字"))
        assertEquals(DedupContentType.GENERAL, detect(labels = "风景,山脉"))
    }

    @Test
    fun `english label keywords match whole tokens only`() {
        // context/texture/textile 不得被裸子串 "text" 误伤
        assertEquals(DedupContentType.GENERAL, detect(labels = "context"))
        assertEquals(DedupContentType.GENERAL, detect(labels = "texture,textile"))
        // 整词命中仍生效（含大小写不敏感与混合分隔）
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "text"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "Screenshot_Text,室内"))
    }

    @Test
    fun `english label keywords tolerate plural forms`() {
        // 复数漏检会把 DOCUMENT 误归 GENERAL → VISUAL 组自动预选进批量删除，方向不保守
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "documents, desk"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "receipts"))
        assertEquals(DedupContentType.DOCUMENT, detect(labels = "texts"))
        // 含关键词子串的非复数词仍不命中
        assertEquals(DedupContentType.GENERAL, detect(labels = "contexts"))
    }

    @Test
    fun `face signals detect PORTRAIT`() {
        assertEquals(DedupContentType.PORTRAIT, detect(hasFace = true))
        assertEquals(DedupContentType.PORTRAIT, detect(faceQualityScore = 0.8f))
    }

    @Test
    fun `priority is SCREENSHOT over DOCUMENT over PORTRAIT`() {
        // 截图目录 + 长 OCR + 人脸信号同时命中：截图语义最强
        assertEquals(
            DedupContentType.SCREENSHOT,
            detect(
                path = "DCIM/Screenshots/",
                ocrText = "x".repeat(DOCUMENT_OCR_CHAR_THRESHOLD + 1),
                hasFace = true,
            ),
        )
        // 文档保守性优先于人像美观
        assertEquals(
            DedupContentType.DOCUMENT,
            detect(labels = "receipt", hasFace = true, faceQualityScore = 0.9f),
        )
    }

    @Test
    fun `TAG-uncovered photos fall back to GENERAL`() {
        // 退化原则：信号全空（含 TAG 未覆盖存量照片）一律 GENERAL，行为与 v1.0 一致
        assertEquals(DedupContentType.GENERAL, detect())
        assertEquals(DedupContentType.GENERAL, detect(path = null, ocrText = "", labels = ""))
    }
}
