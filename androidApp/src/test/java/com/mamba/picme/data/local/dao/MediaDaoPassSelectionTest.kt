package com.mamba.picme.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：Pass 1（人脸检测）/ Pass 3（图像打标）的选片与计数必须排除视频。
 *
 * 背景：人脸检测/图像打标都依赖 loadBitmap 解码图片，视频会被 MIME 拦截返回 null，
 * faceRoiResult 永远写不进去。若选片/计数 SQL 不过滤 type=PHOTO，视频会被永久计入
 * “待 Pass 1” → 计数器永不归零、增量扫描无限重选同一批视频（“永远扫不完”）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MediaDaoPassSelectionTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MediaDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.mediaDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun seed(): Pair<Long, Long> {
        // 一张缺人脸检测的照片 + 一个缺人脸检测的视频，二者 faceRoiResult 均为 NULL。
        val photoId = dao.insertMedia(
            MediaEntity(uri = "content://photo/1", type = MediaType.PHOTO, captureDate = 100L, fileName = "p1.jpg")
        )
        val videoId = dao.insertMedia(
            MediaEntity(uri = "content://video/1", type = MediaType.VIDEO, captureDate = 200L, fileName = "v1.mp4")
        )
        return photoId to videoId
    }

    @Test
    fun `getMediaWithoutFaceRoiCount excludes videos`() = runTest {
        seed()

        // 只有照片计入“待 Pass 1”，视频不计入。
        assertEquals(1, dao.getMediaWithoutFaceRoiCount())
    }

    @Test
    fun `getMediaWithoutFaceRoiIds excludes videos`() = runTest {
        val (photoId, _) = seed()
        assertEquals(listOf(photoId), dao.getMediaWithoutFaceRoiIds())
    }

    @Test
    fun `incremental scan projection excludes videos`() = runTest {
        val (photoId, _) = seed()

        val candidates = dao.getMediaForIncrementalScanNewestProjection(before = Long.MAX_VALUE, limit = 10, passPattern = null)

        assertEquals(listOf(photoId), candidates.map { it.id })
    }

    @Test
    fun `decode-failure sentinel makes remaining-for-pass1 converge`() = runTest {
        val (photoId, _) = seed()

        // 模拟 executeFaceDetection 对解码失败的照片写哨兵（与 DECODE_FAILURE_ROI_JSON 同构）。
        dao.updateFaceRoiResult(photoId, """{"hasFace":false,"faceCount":0,"decodeError":true}""", false)

        // 写入哨兵后该照片不再“缺 faceRoiResult”，计数归零 —— 即死循环收敛。
        assertEquals(0, dao.getMediaWithoutFaceRoiCount())
        assertTrue(dao.getMediaWithoutFaceRoiIds().isEmpty())
    }

    @Test
    fun `video never counted even after photo is resolved`() = runTest {
        val (photoId, _) = seed()
        dao.updateFaceRoiResult(photoId, """{"hasFace":false,"faceCount":0}""", false)

        // 照片已处理，视频本就排除 → 计数 0（回归保证：不会因视频把计数卡住）。
        assertEquals(0, dao.getMediaWithoutFaceRoiCount())
    }
    // ── 2026-10-01 增量管线饿死回归（真库实锤：newest-100 窗口被已完扫 {"1","3"} 占满）──

    private suspend fun seedStarvedLibrary(): Pair<List<Long>, List<Long>> {
        // 5 张「最新但已完扫 {"1","3"}」+ 2 张「更老但从未扫描」
        val covered = (1..5).map { i ->
            dao.insertMedia(
                MediaEntity(
                    uri = "content://covered/$i", type = MediaType.PHOTO,
                    captureDate = 10_000L + i, fileName = "c$i.jpg",
                    lastTagScanPasses = "{\"1\":1,\"3\":1}", lastTagScanAt = 1L
                )
            )
        }
        val old = (1..2).map { i ->
            dao.insertMedia(
                MediaEntity(
                    uri = "content://old/$i", type = MediaType.PHOTO,
                    captureDate = 100L + i, fileName = "o$i.jpg"
                )
            )
        }
        return covered to old
    }

    @Test
    fun `null passPattern keeps legacy window behavior`() = runTest {
        val (coveredIds, _) = seedStarvedLibrary()
        // 无 pattern：newest 窗口按旧行为返回已完扫媒体（内存过滤前的原始候选）
        val candidates = dao.getMediaForIncrementalScanNewestProjection(
            before = Long.MAX_VALUE, limit = 3, passPattern = null
        )
        assertEquals(coveredIds.takeLast(3).reversed(), candidates.map { it.id })
    }

    @Test
    fun `passPattern pushes covered media out and rescues old unscanned`() = runTest {
        val (_, oldIds) = seedStarvedLibrary()
        // Pass3 相位（pattern %"3"%）：已完扫 5 张被剔除出窗口，2 张老未扫描得救
        val candidates = dao.getMediaForIncrementalScanNewestProjection(
            before = Long.MAX_VALUE, limit = 3, passPattern = "%\"3\"%"
        )
        assertEquals(oldIds.reversed(), candidates.map { it.id })
    }

    @Test
    fun `passPattern keeps recently scanned excluded`() = runTest {
        val (_, oldIds) = seedStarvedLibrary()
        // 最近扫描（lastTagScanAt=now）即使缺 pass 也被 4h 窗口挡住——防重扫节流不回退
        dao.insertMedia(
            MediaEntity(
                uri = "content://fresh/1", type = MediaType.PHOTO,
                captureDate = 99_999L, fileName = "f1.jpg", lastTagScanAt = Long.MAX_VALUE
            )
        )
        val candidates = dao.getMediaForIncrementalScanNewestProjection(
            before = Long.MAX_VALUE - 14400000, limit = 10, passPattern = "%\"1\"%"
        )
        assertEquals(oldIds.reversed(), candidates.map { it.id })
    }
}
