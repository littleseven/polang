package com.mamba.picme.domain.organize

import org.junit.Assert.assertEquals
import org.junit.Test

class OrganizeCategorizerTest {

    private val now = 1_800_000_000_000L

    @Suppress("LongParameterList")
    private fun item(
        uri: String,
        isVideo: Boolean = false,
        captureDate: Long = now,
        sizeBytes: Long = 1_000_000,
        relativePath: String? = "DCIM/Camera/",
        ocrText: String? = null,
        pixelArea: Long? = 12_000_000,
        labels: String? = null,
        hasFace: Boolean = false,
        aestheticScore: Float? = null,
        faceQualityScore: Float? = null,
        blurScore: Float? = null,
        exposureScore: Float? = null,
        lastViewedAt: Long? = null,
        isFavorite: Boolean = false,
        personPhotoCount: Int? = null,
        exactDupGroupSize: Int = 0,
        similarDupGroupSize: Int = 0,
        exactDupGroupKey: String? = null,
    ) = OrganizeItem(
        uri = uri, isVideo = isVideo, captureDate = captureDate, sizeBytes = sizeBytes,
        relativePath = relativePath, ocrText = ocrText, pixelArea = pixelArea,
        labels = labels, hasFace = hasFace, aestheticScore = aestheticScore,
        faceQualityScore = faceQualityScore, blurScore = blurScore,
        exposureScore = exposureScore, lastViewedAt = lastViewedAt, isFavorite = isFavorite,
        personPhotoCount = personPhotoCount, exactDupGroupSize = exactDupGroupSize,
        similarDupGroupSize = similarDupGroupSize, exactDupGroupKey = exactDupGroupKey,
    )

    private fun cardOf(board: OrganizeBoard, category: OrganizeCategory): CategoryBoard =
        board.categories.single { card -> card.category == category }

    @Test
    fun `mutex - screenshot with low scores lands only in SCREEN_CONTENT`() {
        val classified = OrganizeCategorizer.classifyAll(
            listOf(
                item(
                    "shot", relativePath = "Pictures/Screenshots/",
                    aestheticScore = 1.0f, blurScore = 1.0f, ocrText = "x".repeat(600),
                )
            ),
            now = now,
        )
        assertEquals(1, classified.size)
        assertEquals(OrganizeCategory.SCREEN_CONTENT, classified[0].category)
    }

    @Test
    fun `hero reclaim is HIGH non-protected union - no double count regression AC-F1-1`() {
        // 同一张图在 v1 会被截图+模糊+文档计 3 次；v2 互斥后 Hero 只计 1 次
        val board = OrganizeCategorizer.board(
            listOf(
                item(
                    "shot", relativePath = "Pictures/Screenshots/", sizeBytes = 100,
                    blurScore = 1.0f, ocrText = "x".repeat(600),
                ),
                item("big", isVideo = true, sizeBytes = 250L * 1024 * 1024),
                item("normal"),
            ),
            now = now,
        )
        assertEquals(100L + 250L * 1024 * 1024, board.heroReclaimBytes)
    }

    @Test
    fun `protected items excluded from hero and never high-counted`() {
        val board = OrganizeCategorizer.board(
            listOf(
                // 6 年前的模糊老照片：HIGH 置信但 protected → 不进 Hero、不进 highCount
                item(
                    "old", captureDate = now - 6 * OrganizeThresholds.YEAR_MILLIS,
                    blurScore = 10.0f, sizeBytes = 500,
                ),
            ),
            now = now,
        )
        assertEquals(0L, board.heroReclaimBytes)
        val card = cardOf(board, OrganizeCategory.LOW_QUALITY_PHOTOS)
        assertEquals(1, card.totalCount)
        assertEquals(0, card.highCount)
        assertEquals(1, card.protectedCount)
    }

    @Test
    fun `categories sorted by high confidence bytes descending`() {
        // 区分点：排序键是 highBytes 而非 totalBytes——
        // SCREEN_CONTENT 两截图全 HIGH（highBytes=150=totalBytes）；
        // LOW_QUALITY_PHOTOS highBytes=100（HIGH 项 100B）但 totalBytes=5100（含 MEDIUM 项 5000B）。
        // 按 highBytes 排序 → [SCREEN_CONTENT, LOW_QUALITY_PHOTOS]；若误用 totalBytes 则顺序相反。
        val board = OrganizeCategorizer.board(
            listOf(
                item("s1", relativePath = "Pictures/Screenshots/", sizeBytes = 100),
                item("s2", relativePath = "Pictures/Screenshots/", sizeBytes = 50),
                item("blurHigh", blurScore = 10.0f, sizeBytes = 100),   // HIGH
                item("blurMid", blurScore = 80.0f, sizeBytes = 5000),   // MEDIUM
            ),
            now = now,
        )
        assertEquals(
            listOf(OrganizeCategory.SCREEN_CONTENT, OrganizeCategory.LOW_QUALITY_PHOTOS),
            board.categories.take(2).map { card -> card.category },
        )
        // 锁定两键语义，防止排序键被误换
        val screenCard = cardOf(board, OrganizeCategory.SCREEN_CONTENT)
        assertEquals(150L, screenCard.highBytes)
        assertEquals(150L, screenCard.totalBytes)
        val photosCard = cardOf(board, OrganizeCategory.LOW_QUALITY_PHOTOS)
        assertEquals(100L, photosCard.highBytes)
        assertEquals(5100L, photosCard.totalBytes)
    }

    @Test
    fun `duplicates keeper deduction - exact group of 3 same size yields 2x highBytes`() {
        // 3 张同 MD5 精确重复各 5MB：Hero/类目卡只应计 2 张可释放（保留 1 张 keeper），
        // 与去重结果页只计非 keeper 口径一致（修复全组 15MB 虚高）
        val board = OrganizeCategorizer.board(
            listOf(
                item("d1", sizeBytes = 5_000_000, exactDupGroupSize = 3, exactDupGroupKey = "md5a"),
                item("d2", sizeBytes = 5_000_000, exactDupGroupSize = 3, exactDupGroupKey = "md5a"),
                item("d3", sizeBytes = 5_000_000, exactDupGroupSize = 3, exactDupGroupKey = "md5a"),
            ),
            now = now,
        )
        val card = cardOf(board, OrganizeCategory.DUPLICATES)
        assertEquals(3, card.highCount)
        assertEquals(3, card.totalCount)
        assertEquals(15_000_000L, card.totalBytes)
        assertEquals(10_000_000L, card.highBytes)
        assertEquals(10_000_000L, board.heroReclaimBytes)
    }

    @Test
    fun `duplicates keeper deduction scopes per group - two groups deduct independently`() {
        // 两组精确重复（2×5MB + 3×2MB）各扣 1 张：(5 + 2×2) = 9MB
        val board = OrganizeCategorizer.board(
            listOf(
                item("a1", sizeBytes = 5_000_000, exactDupGroupSize = 2, exactDupGroupKey = "md5a"),
                item("a2", sizeBytes = 5_000_000, exactDupGroupSize = 2, exactDupGroupKey = "md5a"),
                item("b1", sizeBytes = 2_000_000, exactDupGroupSize = 3, exactDupGroupKey = "md5b"),
                item("b2", sizeBytes = 2_000_000, exactDupGroupSize = 3, exactDupGroupKey = "md5b"),
                item("b3", sizeBytes = 2_000_000, exactDupGroupSize = 3, exactDupGroupKey = "md5b"),
            ),
            now = now,
        )
        assertEquals(9_000_000L, cardOf(board, OrganizeCategory.DUPLICATES).highBytes)
    }

    @Test
    fun `duplicates keeper deducted after in-library convergence - 3 hashes 2 alive becomes pair`() {
        // Task 18b：聚类输入已按库内 uri 收敛（repository 过滤 dedup_hash 残留行），
        // 「3 hash / 2 在库」到达领域层时已是 2 人组（exactDupGroupSize=2）→ 正常扣 1 张
        val board = OrganizeCategorizer.board(
            listOf(
                item("d1", sizeBytes = 5_000_000, exactDupGroupSize = 2, exactDupGroupKey = "md5a"),
                item("d2", sizeBytes = 5_000_000, exactDupGroupSize = 2, exactDupGroupKey = "md5a"),
            ),
            now = now,
        )
        assertEquals(5_000_000L, cardOf(board, OrganizeCategory.DUPLICATES).highBytes)
        assertEquals(5_000_000L, board.heroReclaimBytes)
    }

    @Test
    fun `sole surviving keeper is not suggested - group collapses below 2 after deletion`() {
        // Task 18b 根治「删光唯一副本」引导：组内其余已删、唯一幸存 keeper 经库内收敛后
        // exactDupGroupSize 塌缩为 1 → 不成组、不进 DUPLICATES（归宿类目视其他信号，
        // 此处 DCIM 普通照不命中任何类目）
        val board = OrganizeCategorizer.board(
            listOf(item("keeper", sizeBytes = 5_000_000, exactDupGroupSize = 0)),
            now = now,
        )
        val card = cardOf(board, OrganizeCategory.DUPLICATES)
        assertEquals(0, card.totalCount)
        assertEquals(0L, card.highBytes)
        assertEquals(0L, board.heroReclaimBytes)
    }

    @Test
    fun `duplicates keeper deduction with mixed group - similar-only member stays in review`() {
        // 混合组：2 exact（同 key，HIGH）+ 1 similar-only（无 key，MEDIUM 落请确认段）同卡，
        // highBytes 仍只按 exact 组扣 1 张；similar-only 成员不进 high 不参与扣减
        val board = OrganizeCategorizer.board(
            listOf(
                item("d1", sizeBytes = 5_000_000, exactDupGroupSize = 2, exactDupGroupKey = "md5a"),
                item("d2", sizeBytes = 5_000_000, exactDupGroupSize = 2, exactDupGroupKey = "md5a"),
                item("s1", sizeBytes = 4_000_000, similarDupGroupSize = 3),
            ),
            now = now,
        )
        val card = cardOf(board, OrganizeCategory.DUPLICATES)
        assertEquals(3, card.totalCount)
        assertEquals(2, card.highCount)
        assertEquals(1, card.reviewCount)
        assertEquals(5_000_000L, card.highBytes)
    }

    @Test
    fun `duplicates keeper not deducted without group key - defensive fallback`() {
        // 防御分支：有组大小无组标识无法按组去重扣减 → 不扣（按全组字节计）。
        // 生产管线库内收敛后组恒带标识，仅直调 board 的异常输入可达
        val board = OrganizeCategorizer.board(
            listOf(
                item("d1", sizeBytes = 5_000_000, exactDupGroupSize = 2),
                item("d2", sizeBytes = 5_000_000, exactDupGroupSize = 2),
            ),
            now = now,
        )
        assertEquals(10_000_000L, cardOf(board, OrganizeCategory.DUPLICATES).highBytes)
    }

    @Test
    fun `needs-scan coverage when no library item has the signal`() {
        val board = OrganizeCategorizer.board(
            listOf(item("a")), // 全库无 blurScore/exposureScore
            now = now,
        )
        // 六类目全量产卡：零命中类目以 totalCount=0 + NEEDS_SCAN 引导态呈现，不再静默消失
        assertEquals(OrganizeCategory.entries.size, board.categories.size)
        val photosCard = cardOf(board, OrganizeCategory.LOW_QUALITY_PHOTOS)
        assertEquals(0, photosCard.totalCount)
        assertEquals(SignalCoverage.NEEDS_SCAN, photosCard.coverage)
        assertEquals(
            SignalCoverage.READY,
            cardOf(board, OrganizeCategory.SCREEN_CONTENT).coverage,
        )
        // 覆盖度亦可绕开 board 直查
        assertEquals(
            SignalCoverage.NEEDS_SCAN,
            OrganizeCategorizer.coverageOf(OrganizeCategory.LOW_QUALITY_PHOTOS, listOf(item("a"))),
        )
        assertEquals(
            SignalCoverage.READY,
            OrganizeCategorizer.coverageOf(OrganizeCategory.SCREEN_CONTENT, listOf(item("a"))),
        )
    }

    @Test
    fun `review count aggregates non-HIGH non-protected`() {
        val board = OrganizeCategorizer.board(
            listOf(
                item("borderline", blurScore = 80.0f, sizeBytes = 10),       // MEDIUM
                item("aesthetic", blurScore = 500.0f, exposureScore = 0.5f,
                    aestheticScore = 2.0f, sizeBytes = 10),                 // 不进类目（无强信号）
            ),
            now = now,
        )
        assertEquals(1, board.heroReviewCount)
        assertEquals(1, cardOf(board, OrganizeCategory.LOW_QUALITY_PHOTOS).reviewCount)
    }

    @Test
    fun `preview uris truncated to 4 in stable uri-sorted order`() {
        // 乱序输入：预览不随 rows rowid 序漂移，恒按 uri 字典序取前 4
        val board = OrganizeCategorizer.board(
            listOf(
                item("s5", relativePath = "Pictures/Screenshots/"),
                item("s2", relativePath = "Pictures/Screenshots/"),
                item("s4", relativePath = "Pictures/Screenshots/"),
                item("s1", relativePath = "Pictures/Screenshots/"),
                item("s3", relativePath = "Pictures/Screenshots/"),
            ),
            now = now,
        )
        assertEquals(
            listOf("s1", "s2", "s3", "s4"),
            cardOf(board, OrganizeCategory.SCREEN_CONTENT).previewUris,
        )
    }

    @Test
    fun `empty input yields six zero cards and zero hero`() {
        val board = OrganizeCategorizer.board(emptyList(), now = now)
        assertEquals(OrganizeCategory.entries.size, board.categories.size)
        board.categories.forEach { card ->
            assertEquals(0, card.totalCount)
            assertEquals(0, card.highCount)
            assertEquals(0, card.reviewCount)
            assertEquals(0, card.protectedCount)
            assertEquals(0L, card.totalBytes)
            assertEquals(0L, card.highBytes)
        }
        assertEquals(0L, board.heroReclaimBytes)
        assertEquals(0, board.heroReviewCount)
    }
}
