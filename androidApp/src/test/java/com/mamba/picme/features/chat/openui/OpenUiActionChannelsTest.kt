package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动作三通道宿主裁定层钉住（2026-11-01 官方对齐批次）：
 * @Navigate 目的地是闭集线名（LLM 可写空间枚举化，未知名 → null 告警降级）；
 * Mutation 写工具白名单与 server 工具描述字面量副本同源。
 */
class OpenUiActionChannelsTest {

    @Test
    fun `navigate destinations are a closed wire-name set`() {
        assertEquals(OpenUiNavigateTarget.GALLERY, OpenUiNavigateTarget.ofWireName("gallery"))
        assertEquals(OpenUiNavigateTarget.GALLERY_SEARCH, OpenUiNavigateTarget.ofWireName("gallery_search"))
        assertEquals(OpenUiNavigateTarget.ORGANIZE, OpenUiNavigateTarget.ofWireName("organize"))
        assertEquals(OpenUiNavigateTarget.PEOPLE, OpenUiNavigateTarget.ofWireName("people"))
        assertEquals(OpenUiNavigateTarget.MEMORY, OpenUiNavigateTarget.ofWireName("memory"))
        assertEquals(OpenUiNavigateTarget.TASK_CENTER, OpenUiNavigateTarget.ofWireName("task_center"))
        assertEquals(OpenUiNavigateTarget.SETTINGS, OpenUiNavigateTarget.ofWireName("settings"))
    }

    @Test
    fun `unknown navigate wire names degrade to null`() {
        assertNull(OpenUiNavigateTarget.ofWireName("camera")) // 相机仅头像拍摄/Agent 指令进入，不开放
        assertNull(OpenUiNavigateTarget.ofWireName("explode"))
        assertNull(OpenUiNavigateTarget.ofWireName(""))
        // 大小写敏感：线名是契约字面量，不做模糊匹配（枚举优于条件）
        assertNull(OpenUiNavigateTarget.ofWireName("Gallery"))
    }

    @Test
    fun `only param-taking destinations declare so`() {
        assertTrue(OpenUiNavigateTarget.GALLERY_SEARCH.takesParam)
        assertTrue(OpenUiNavigateTarget.ORGANIZE.takesParam)
        OpenUiNavigateTarget.entries
            .filter { target -> target != OpenUiNavigateTarget.GALLERY_SEARCH && target != OpenUiNavigateTarget.ORGANIZE }
            .forEach { target -> assertTrue("${target.wireName} 不收参数", !target.takesParam) }
    }

    @Test
    fun `navigate event resolves destination and optional param`() {
        assertEquals(
            OpenUiNavigateTarget.GALLERY_SEARCH to "7月的照片",
            resolveOpenUiNavigate(
                listOf(OpenUiValue.Str("gallery_search"), OpenUiValue.Str("7月的照片")),
            ),
        )
        assertEquals(
            OpenUiNavigateTarget.TASK_CENTER to null,
            resolveOpenUiNavigate(listOf(OpenUiValue.Str("task_center"))),
        )
        // 未知目的地 / 缺首参 / 首参非 Str → null（宿主告警降级）
        assertNull(resolveOpenUiNavigate(listOf(OpenUiValue.Str("explode"))))
        assertNull(resolveOpenUiNavigate(emptyList()))
        assertNull(resolveOpenUiNavigate(listOf(OpenUiValue.Num(1.0))))
    }

    @Test
    fun `write tool whitelist holds low-risk media writes only`() {
        assertEquals(
            setOf(OpenUiWriteTools.FAVORITE_MEDIA, OpenUiWriteTools.UNFAVORITE_MEDIA),
            OpenUiWriteTools.ALL,
        )
        // 高风险操作（删除等需系统授权的）不得进入白名单——卡内点击无确认链路
        assertTrue(!OpenUiWriteTools.ALL.contains("delete_media"))
    }
}
