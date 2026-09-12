package com.mamba.picme.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.MatrixCursor
import android.provider.MediaStore
import com.mamba.picme.data.local.MediaDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
 * 回归测试：HyperOS/Android 16 的 MediaStore 普通查询不过滤回收站行（AOSP 才默认过滤），
 * 上滑删除（IS_TRASHED=1）后相册网格仍显示该照片。修复双管齐下：
 * 1. 普查查询侧（queryImages/queryVideos）加 IS_TRASHED=0 selection（API 29+）；
 * 2. stale 探活侧（isSystemMediaLive）读 IS_TRASHED 列，trashed 按「不存在」处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaRepositoryImplTest {

    private val imageColumns = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.LATITUDE,
        MediaStore.Images.Media.LONGITUDE,
    )

    private val videoColumns = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DURATION,
    )

    // ---------- isSystemMediaLive（stale 探活判定） ----------

    private fun resolverReturning(cursor: MatrixCursor?): ContentResolver {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), null, null, null) } returns cursor
        return resolver
    }

    private fun probeUri(id: Long) =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    @Test
    fun `isSystemMediaLive returns false for trashed row`() {
        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED))
            .apply { addRow(arrayOf(1L, 1)) }

        assertFalse(MediaRepositoryImpl.isSystemMediaLive(resolverReturning(cursor), probeUri(1L)))
    }

    @Test
    fun `isSystemMediaLive returns true for live row`() {
        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED))
            .apply { addRow(arrayOf(1L, 0)) }

        assertTrue(MediaRepositoryImpl.isSystemMediaLive(resolverReturning(cursor), probeUri(1L)))
    }

    @Test
    fun `isSystemMediaLive returns false when row does not exist`() {
        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED))

        assertFalse(MediaRepositoryImpl.isSystemMediaLive(resolverReturning(cursor), probeUri(1L)))
    }

    @Test
    fun `isSystemMediaLive returns false when query returns null`() {
        assertFalse(MediaRepositoryImpl.isSystemMediaLive(resolverReturning(null), probeUri(1L)))
    }

    @Test
    fun `isSystemMediaLive treats row as live when IS_TRASHED column is missing`() {
        // 列缺失（ROM 行为差异）保守视为真存在，不误删 Room 行（判法同 DedupTrashManager.queryExisting）
        val cursor = MatrixCursor(arrayOf(MediaStore.MediaColumns._ID))
            .apply { addRow(arrayOf(1L)) }

        assertTrue(MediaRepositoryImpl.isSystemMediaLive(resolverReturning(cursor), probeUri(1L)))
    }

    // ---------- 普查查询侧 selection 过滤 ----------

    private fun repositoryWith(resolver: ContentResolver): MediaRepositoryImpl {
        val mediaDao = mockk<MediaDao>()
        every { mediaDao.getAllMedia() } returns flowOf(emptyList())
        coEvery { mediaDao.getAllMediaNow() } returns emptyList()
        coEvery { mediaDao.insertMedia(any()) } returns 1L
        val context = mockk<Context>()
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        every { context.checkPermission(any(), any(), any()) } returns PackageManager.PERMISSION_GRANTED
        return MediaRepositoryImpl(mediaDao, context)
    }

    private fun resolverRecordingSelections(
        imageSelections: MutableList<String?>,
        videoSelections: MutableList<String?>,
    ): ContentResolver {
        val imageCursor = MatrixCursor(imageColumns).apply {
            addRow(arrayOf<Any?>(101L, "a.jpg", 1_700_000_000_000L, 1_700_000_000L, 31.2, 121.5))
        }
        val videoCursor = MatrixCursor(videoColumns).apply {
            addRow(arrayOf<Any?>(202L, "v.mp4", 1_700_000_000_000L, 1_700_000_000L, 5_000L))
        }
        val resolver = mockk<ContentResolver>()
        every {
            resolver.query(
                eq(MediaStore.Images.Media.EXTERNAL_CONTENT_URI), any(), any(), isNull(), any(),
            )
        } answers {
            imageSelections += arg<String?>(2)
            imageCursor
        }
        every {
            resolver.query(
                eq(MediaStore.Video.Media.EXTERNAL_CONTENT_URI), any(), any(), isNull(), any(),
            )
        } answers {
            videoSelections += arg<String?>(2)
            videoCursor
        }
        return resolver
    }

    @Test
    fun `refresh queries exclude trashed rows via IS_TRASHED selection on API 29+`() = runTest {
        val imageSelections = mutableListOf<String?>()
        val videoSelections = mutableListOf<String?>()
        val repository = repositoryWith(resolverRecordingSelections(imageSelections, videoSelections))

        repository.refreshMediaLibrary()

        assertEquals(listOf("is_trashed = 0"), imageSelections)
        assertEquals(listOf("is_trashed = 0"), videoSelections)
    }

    @Test
    @Config(sdk = [28])
    fun `refresh queries keep null selection below API 29`() = runTest {
        // IS_TRASHED 为 API 29+ 列，低版本保持无过滤（避免查询未知列）
        val imageSelections = mutableListOf<String?>()
        val videoSelections = mutableListOf<String?>()
        val repository = repositoryWith(resolverRecordingSelections(imageSelections, videoSelections))

        repository.refreshMediaLibrary()

        assertEquals(listOf<String?>(null), imageSelections)
        assertEquals(listOf<String?>(null), videoSelections)
    }
}
