package com.mamba.picme.features.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主页面 Pager 页序守卫：相册(0) / 相册整理(1) / 聊天(2) / 人物(3) / 回忆(4)。
 *
 * 页序是跨文件契约（MainActivity 切页、返回键回相册、相册页左滑进相册整理），
 * 相机已路由化（Screen.Camera）不在 Pager 内——此测试防页序被无意改回。
 */
class MainPagerPageOrderTest {

    @Test
    fun pagerOrderIsGalleryDedupChatPeople() {
        assertEquals(0, MAIN_PAGE_GALLERY)
        assertEquals(1, MAIN_PAGE_DEDUP)
        assertEquals(2, MAIN_PAGE_CHAT)
        assertEquals(3, MAIN_PAGE_PEOPLE)
        assertEquals(4, MAIN_PAGE_MEMORY)
        assertEquals(5, MAIN_PAGE_COUNT)
    }

    @Test
    fun pageIndexesAreDistinctAndContiguous() {
        val indexes = listOf(
            MAIN_PAGE_GALLERY,
            MAIN_PAGE_DEDUP,
            MAIN_PAGE_CHAT,
            MAIN_PAGE_PEOPLE,
            MAIN_PAGE_MEMORY,
        )
        assertEquals(indexes.size, indexes.distinct().size)
        assertTrue(indexes.all { index -> index in 0 until MAIN_PAGE_COUNT })
    }
}
