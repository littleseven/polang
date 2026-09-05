package com.mamba.picme.features.gallery.dedup

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.dedup.DedupContentType
import com.mamba.picme.domain.dedup.DOCUMENT_OCR_CHAR_THRESHOLD
import com.mamba.picme.domain.repository.AndroidMediaRepository
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归测试：生产取数必须以 content uri 关联 MediaStore 元数据。
 *
 * `MediaAsset.id` 是 MediaRepositoryImpl 的 syntheticMediaId 负值编码，
 * 与 MediaStore `_ID` 不相等；按 id join 会全部 miss（扫描 0 项秒完）。
 */
@RunWith(RobolectricTestRunner::class)
// RELATIVE_PATH 需 API 29+（Q-only 列）：类级钉 33 使完整列路径生效（Robolectric 默认 SDK 低于 29）；
// WIDTH/HEIGHT 自 API 16 可用、全版本入 projection；API<29 的 DATA 兜底路径由单独的 @Config(sdk = [28]) 用例覆盖。
@Config(sdk = [33])
class MediaStoreDedupMediaSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** API 29+ 完整列（row 序）：_ID, SIZE, DATE_MODIFIED, MIME_TYPE, RELATIVE_PATH, DATA, WIDTH, HEIGHT */
    private val qColumns = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
    )

    /** API<29 列（row 序）：_ID, SIZE, DATE_MODIFIED, MIME_TYPE, DATA, WIDTH, HEIGHT（无 RELATIVE_PATH） */
    private val legacyColumns = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
    )

    private fun fakeResolver(
        columns: Array<String>,
        rows: List<Array<Any>>,
        projectionSlot: CapturingSlot<Array<String>>? = null,
    ): ContentResolver {
        val resolver = mockk<ContentResolver>()
        val cursor = MatrixCursor(columns).apply { rows.forEach { row -> addRow(row) } }
        if (projectionSlot != null) {
            every { resolver.query(any(), capture(projectionSlot), null, null, null) } returns cursor
        } else {
            every { resolver.query(any(), any(), null, null, null) } returns cursor
        }
        return resolver
    }

    private fun mockContextWith(resolver: ContentResolver): Context {
        val mockContext = mockk<Context>()
        every { mockContext.contentResolver } returns resolver
        return mockContext
    }

    private fun sourceWith(
        resolver: ContentResolver,
        photos: List<MediaAsset>,
    ): MediaStoreDedupMediaSource {
        val repository = mockk<AndroidMediaRepository>()
        every { repository.allMedia } returns flowOf(photos)
        return MediaStoreDedupMediaSource(mockContextWith(resolver), repository)
    }

    @Test
    fun `photoScanItems joins MediaStore meta by content uri, not synthetic asset id`() = runTest {
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uriA = ContentUris.withAppendedId(contentUri, 101L).toString()
        val uriB = ContentUris.withAppendedId(contentUri, 202L).toString()
        val projectionSlot = slot<Array<String>>()
        val resolver = fakeResolver(
            qColumns,
            listOf(
                arrayOf(101L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/", "/sdcard/DCIM/Camera/a.jpg", 4000L, 3000L),
                arrayOf(202L, 3_000L, 1_700_001L, "image/png", "DCIM/Camera/", "/sdcard/DCIM/Camera/b.png", 4000L, 3000L),
            ),
            projectionSlot,
        )
        val photos = listOf(
            // syntheticMediaId 负值编码：与 MediaStore _ID 101/202 不相等
            MediaAsset(id = -1011L, uri = uriA, type = MediaType.PHOTO, captureDate = 1L, fileName = "a.jpg"),
            MediaAsset(id = -2021L, uri = uriB, type = MediaType.PHOTO, captureDate = 2L, fileName = "b.png"),
            MediaAsset(id = -9L, uri = "content://media/external/video/media/9", type = MediaType.VIDEO, captureDate = 3L, fileName = "v.mp4"),
        )

        val items = sourceWith(resolver, photos).photoScanItems()

        assertEquals(2, items.size) // 修复前为 0：id join 全 miss
        val byUri = items.associateBy { item -> item.uri }
        assertEquals(2_000L, byUri.getValue(uriA).sizeBytes)
        assertEquals(1_700_000_000L, byUri.getValue(uriA).modifiedAt)
        assertEquals("image/png", byUri.getValue(uriB).mime)
        // projection 锁死：API 29+ 含 RELATIVE_PATH 与全版本 WIDTH/HEIGHT
        assertTrue(MediaStore.MediaColumns.RELATIVE_PATH in projectionSlot.captured)
        assertTrue(MediaStore.MediaColumns.WIDTH in projectionSlot.captured)
        assertTrue(MediaStore.MediaColumns.HEIGHT in projectionSlot.captured)
    }

    @Test
    fun `photoScanItems detects contentType from RELATIVE_PATH and TAG signals`() = runTest {
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uriShot = ContentUris.withAppendedId(contentUri, 301L).toString()
        val uriDoc = ContentUris.withAppendedId(contentUri, 302L).toString()
        val uriPortrait = ContentUris.withAppendedId(contentUri, 303L).toString()
        val uriGeneral = ContentUris.withAppendedId(contentUri, 304L).toString()
        val resolver = fakeResolver(
            qColumns,
            listOf(
                arrayOf(301L, 2_000L, 1_700_000L, "image/png", "DCIM/Screenshots/", "/sdcard/DCIM/Screenshots/s.png", 1080L, 2400L),
                // 尺寸列为 0：pixelArea=null，OCR 判定退回绝对字符数兜底
                arrayOf(302L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/", "/sdcard/DCIM/Camera/d.jpg", 0L, 0L),
                arrayOf(303L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/", "/sdcard/DCIM/Camera/p.jpg", 4000L, 3000L),
                arrayOf(304L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/", "/sdcard/DCIM/Camera/g.jpg", 4000L, 3000L),
            ),
        )
        val photos = listOf(
            MediaAsset(id = -301L, uri = uriShot, type = MediaType.PHOTO, captureDate = 1L, fileName = "s.png"),
            MediaAsset(
                id = -302L, uri = uriDoc, type = MediaType.PHOTO, captureDate = 2L, fileName = "d.jpg",
                ocrText = "字".repeat(DOCUMENT_OCR_CHAR_THRESHOLD + 1),
            ),
            MediaAsset(
                id = -303L, uri = uriPortrait, type = MediaType.PHOTO, captureDate = 3L, fileName = "p.jpg",
                hasFace = true, faceQualityScore = 0.8f,
            ),
            MediaAsset(id = -304L, uri = uriGeneral, type = MediaType.PHOTO, captureDate = 4L, fileName = "g.jpg"),
        )

        val items = sourceWith(resolver, photos).photoScanItems()

        val byUri = items.associateBy { item -> item.uri }
        assertEquals(DedupContentType.SCREENSHOT, byUri.getValue(uriShot).contentType)
        assertEquals(DedupContentType.DOCUMENT, byUri.getValue(uriDoc).contentType)
        assertEquals(DedupContentType.PORTRAIT, byUri.getValue(uriPortrait).contentType)
        assertEquals(0.8f, byUri.getValue(uriPortrait).faceQualityScore)
        assertEquals(DedupContentType.GENERAL, byUri.getValue(uriGeneral).contentType)
    }

    @Test
    fun `photoScanItems normalizes ocr density by pixel area`() = runTest {
        // spec §10.2 面积归一：同为 250 字符，大图（48MP 海报）密度低判 GENERAL，小图密度高判 DOCUMENT
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uriPoster = ContentUris.withAppendedId(contentUri, 401L).toString()
        val uriDoc = ContentUris.withAppendedId(contentUri, 402L).toString()
        val resolver = fakeResolver(
            qColumns,
            listOf(
                arrayOf(401L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/", "/sdcard/DCIM/Camera/poster.jpg", 8000L, 6000L),
                arrayOf(402L, 2_000L, 1_700_000L, "image/jpeg", "DCIM/Camera/", "/sdcard/DCIM/Camera/doc.jpg", 640L, 480L),
            ),
        )
        val photos = listOf(
            MediaAsset(id = -401L, uri = uriPoster, type = MediaType.PHOTO, captureDate = 1L, fileName = "poster.jpg", ocrText = "字".repeat(250)),
            MediaAsset(id = -402L, uri = uriDoc, type = MediaType.PHOTO, captureDate = 2L, fileName = "doc.jpg", ocrText = "字".repeat(250)),
        )

        val items = sourceWith(resolver, photos).photoScanItems()

        val byUri = items.associateBy { item -> item.uri }
        assertEquals(DedupContentType.GENERAL, byUri.getValue(uriPoster).contentType)
        assertEquals(DedupContentType.DOCUMENT, byUri.getValue(uriDoc).contentType)
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 falls back to DATA column for screenshot detection`() = runTest {
        // API 24-28 无 RELATIVE_PATH 列：截图识别走 DATA 兜底；WIDTH/HEIGHT 全版本可查，
        // OCR 判定仍走密度归一，contentType 判定不崩溃且走剩余信号
        val contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uriShot = ContentUris.withAppendedId(contentUri, 501L).toString()
        val uriDoc = ContentUris.withAppendedId(contentUri, 502L).toString()
        val uriPortrait = ContentUris.withAppendedId(contentUri, 503L).toString()
        val uriGeneral = ContentUris.withAppendedId(contentUri, 504L).toString()
        val projectionSlot = slot<Array<String>>()
        val resolver = fakeResolver(
            legacyColumns,
            listOf(
                arrayOf(501L, 2_000L, 1_700_000L, "image/png", "/storage/emulated/0/DCIM/Screenshots/s.png", 1080L, 2400L),
                // 小图密集文字：201 字符 / 0.3MP ≈ 654 字符/MP，密度归一命中 DOCUMENT
                arrayOf(502L, 2_000L, 1_700_000L, "image/jpeg", "/storage/emulated/0/DCIM/Camera/d.jpg", 640L, 480L),
                arrayOf(503L, 2_000L, 1_700_000L, "image/jpeg", "/storage/emulated/0/DCIM/Camera/p.jpg", 4000L, 3000L),
                arrayOf(504L, 2_000L, 1_700_000L, "image/jpeg", "/storage/emulated/0/DCIM/Camera/g.jpg", 4000L, 3000L),
            ),
            projectionSlot,
        )
        val photos = listOf(
            MediaAsset(id = -501L, uri = uriShot, type = MediaType.PHOTO, captureDate = 1L, fileName = "s.png"),
            MediaAsset(
                id = -502L, uri = uriDoc, type = MediaType.PHOTO, captureDate = 2L, fileName = "d.jpg",
                ocrText = "字".repeat(DOCUMENT_OCR_CHAR_THRESHOLD + 1),
            ),
            MediaAsset(id = -503L, uri = uriPortrait, type = MediaType.PHOTO, captureDate = 3L, fileName = "p.jpg", hasFace = true),
            MediaAsset(id = -504L, uri = uriGeneral, type = MediaType.PHOTO, captureDate = 4L, fileName = "g.jpg"),
        )

        val items = sourceWith(resolver, photos).photoScanItems()

        assertEquals(4, items.size)
        val byUri = items.associateBy { item -> item.uri }
        assertEquals(DedupContentType.SCREENSHOT, byUri.getValue(uriShot).contentType)
        assertEquals(DedupContentType.DOCUMENT, byUri.getValue(uriDoc).contentType)
        assertEquals(DedupContentType.PORTRAIT, byUri.getValue(uriPortrait).contentType)
        assertEquals(DedupContentType.GENERAL, byUri.getValue(uriGeneral).contentType)
        // projection 锁死：API<29 不含 Q-only 的 RELATIVE_PATH（防低版本查询抛 IllegalArgumentException），
        // WIDTH/HEIGHT 自 API 16 可用，全版本保留
        assertFalse(MediaStore.MediaColumns.RELATIVE_PATH in projectionSlot.captured)
        assertTrue(MediaStore.MediaColumns.WIDTH in projectionSlot.captured)
        assertTrue(MediaStore.MediaColumns.HEIGHT in projectionSlot.captured)
    }

    @Test
    fun `photoScanItems returns empty when repository has no photos`() = runTest {
        val repository = mockk<AndroidMediaRepository>()
        every { repository.allMedia } returns flowOf(emptyList())

        val items = MediaStoreDedupMediaSource(context, repository).photoScanItems()

        assertEquals(0, items.size)
    }
}
