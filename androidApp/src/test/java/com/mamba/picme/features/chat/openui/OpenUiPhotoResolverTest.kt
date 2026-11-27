package com.mamba.picme.features.chat.openui

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * resolveAllUrisInOrder 回归钉住（2026-11-27 真机事故）：coroutineScope block 的聚合
 * 表达式在 block 同步完成时即求值，launch 的子协程此时仍排队在调度器上未运行——
 * 缺 joinAll 时函数恒返回空列表（「查看全部」toast 无法打开根因）。
 */
class OpenUiPhotoResolverTest {

    @Test
    fun `awaits children before aggregating`() = runTest {
        val resolver = OpenUiPhotoResolver { photoId ->
            delay(10)
            OpenUiPhoto(uri = "content://media/$photoId", faceFocusY = null)
        }
        val uris = resolver.resolveAllUrisInOrder((1L..139L).toList())
        assertEquals(139, uris.size)
    }

    @Test
    fun `keeps input order regardless of completion order and skips unresolved`() = runTest {
        val resolver = OpenUiPhotoResolver { photoId ->
            delay(if (photoId == 1L) 50 else 5)
            if (photoId == 2L) null else OpenUiPhoto(uri = "content://media/$photoId", faceFocusY = null)
        }
        val uris = resolver.resolveAllUrisInOrder(listOf(1L, 2L, 3L))
        assertEquals(listOf("content://media/1", "content://media/3"), uris)
    }
}
