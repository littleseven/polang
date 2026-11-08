package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [IosChatGallerySearch] 纯逻辑测试（契约 §9.4，逐字对齐 Android ChatGallerySearch 语义）。
 */
class IosChatGallerySearchTest {

    private fun asset(
        id: Long,
        fileName: String = "IMG_$id.jpg",
        labels: String? = null,
        ocrText: String? = null,
        locationName: String? = null,
        hasFace: Boolean = false
    ) = MediaAsset(
        id = id, uri = "L-$id", type = MediaType.PHOTO, captureDate = id * 1000,
        fileName = fileName, hasFace = hasFace, labels = labels,
        ocrText = ocrText, locationName = locationName
    )

    // ── filterInSet（契约 §9.4）──────────────────────────────────────────────

    @Test
    fun filterInSetBlankConstraintReturnsOriginalSet() {
        val assets = listOf(asset(1), asset(2))
        assertEquals(assets, IosChatGallerySearch.filterInSet(assets, "   "))
    }

    @Test
    fun filterInSetMatchesLabelsOcrLocationFileNameIgnoreCase() {
        val byLabels = asset(1, labels = """{"tags":["夜景","城市"]}""")
        val byOcr = asset(2, ocrText = "发票号码 12345")
        val byLocation = asset(3, locationName = "上海外滩")
        val byFileName = asset(4, fileName = "beach_photo.jpg")
        val miss = asset(5)
        val assets = listOf(byLabels, byOcr, byLocation, byFileName, miss)

        assertEquals(listOf(byLabels), IosChatGallerySearch.filterInSet(assets, "夜景"))
        assertEquals(listOf(byOcr), IosChatGallerySearch.filterInSet(assets, "发票"))
        assertEquals(listOf(byLocation), IosChatGallerySearch.filterInSet(assets, "外滩"))
        // ignoreCase：BEACH 命中 beach_photo.jpg
        assertEquals(listOf(byFileName), IosChatGallerySearch.filterInSet(assets, "BEACH"))
        assertEquals(emptyList(), IosChatGallerySearch.filterInSet(assets, "不存在"))
    }

    @Test
    fun filterInSetFaceIntentUsesHasFaceField() {
        val faced = asset(1, hasFace = true)
        val noFace = asset(2)
        val assets = listOf(faced, noFace)
        assertEquals(listOf(faced), IosChatGallerySearch.filterInSet(assets, "有人脸的"))
        assertEquals(listOf(faced), IosChatGallerySearch.filterInSet(assets, "face"))
    }

    // ── resolveRefine（契约 §9.4）─────────────────────────────────────────────

    @Test
    fun resolveRefinePrefersFilterInSet() {
        val keep = asset(1, labels = """{"tags":["夜景"]}""")
        val drop = asset(2)
        val prior = listOf(keep, drop)
        // searchHits 全量（引擎语义召回可能更宽），filterInSet 非空时优先精准子集
        val hits = listOf(keep, drop)
        assertEquals(listOf(keep), IosChatGallerySearch.resolveRefine(prior, hits, "夜景"))
    }

    @Test
    fun resolveRefineFallsBackToHitsIntersectPriorById() {
        val a = asset(1)
        val b = asset(2)
        val c = asset(3)
        val prior = listOf(a, b)
        // 「女性」类词 filterInSet 字面不命中 → 回退 hits ∩ prior
        val hits = listOf(b, c)
        assertEquals(listOf(b), IosChatGallerySearch.resolveRefine(prior, hits, "xylophone"))
    }

    // ── cleanConstraint（契约 §9.4）───────────────────────────────────────────

    @Test
    fun cleanConstraintStripsPrefixSuffix() {
        assertEquals("夜景", IosChatGallerySearch.cleanConstraint("只保留夜景"))
        assertEquals("海边", IosChatGallerySearch.cleanConstraint("只要海边的照片"))
        assertEquals("女", IosChatGallerySearch.cleanConstraint("其中的女性"))
        assertEquals("猫", IosChatGallerySearch.cleanConstraint("不要猫的图片"))
        assertEquals("狗", IosChatGallerySearch.cleanConstraint("排除狗"))
    }

    @Test
    fun cleanConstraintNormalizesGenderToSingleChar() {
        assertEquals("女", IosChatGallerySearch.cleanConstraint("女性"))
        assertEquals("女", IosChatGallerySearch.cleanConstraint("女孩"))
        assertEquals("男", IosChatGallerySearch.cleanConstraint("男人"))
        assertEquals("男", IosChatGallerySearch.cleanConstraint("男生"))
        // 非性别词不改动
        assertEquals("夜景", IosChatGallerySearch.cleanConstraint("夜景"))
    }
}
