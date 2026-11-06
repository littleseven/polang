package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * OpenUiContract 的 local:// handle 契约测试（自 a2ui/A2uiCardContractTest 迁移改写，
 * 2026-11-05 A2UI 退役；formatLocalHandle 无幸存调用方未迁移，roundtrip 用例改写为
 * parseLocalHandle 直断）。
 */
class OpenUiContractTest {

    @Test
    fun handleToken_isOpaqueWithoutScheme() {
        val token = OpenUiContract.formatHandleToken(OpenUiContract.DOMAIN_SCAN, "abc123")
        assertEquals("handle: scan/abc123", token)
        // 投影闸 FORBIDDEN 含 "local://" 字面量：opaque token 绝不含 scheme
        assertFalse(token.contains(OpenUiContract.HANDLE_SCHEME))
    }

    @Test
    fun parseLocalHandle_parsesWireFormatWithFragment() {
        assertEquals(
            LocalHandle(domain = "scan", id = "7f3a", fragment = "photos"),
            OpenUiContract.parseLocalHandle("local://scan/7f3a#photos"),
        )
    }

    @Test
    fun parseLocalHandle_parsesWireFormatWithoutFragment() {
        assertEquals(
            LocalHandle(domain = "scan", id = "7f3a", fragment = null),
            OpenUiContract.parseLocalHandle("local://scan/7f3a"),
        )
    }

    @Test
    fun parseLocalHandle_nestedHashAndSlashBoundaries() {
        // 首 `#` 切分：后续 `#` 归 fragment（fragment 允许任意非空片段）
        assertEquals(
            LocalHandle(domain = "scan", id = "7f3a", fragment = "foo#bar"),
            OpenUiContract.parseLocalHandle("local://scan/7f3a#foo#bar"),
        )
        // 首 `/` 切 domain：id 允许含 `/`
        assertEquals(
            LocalHandle(domain = "scan", id = "a/b", fragment = null),
            OpenUiContract.parseLocalHandle("local://scan/a/b"),
        )
    }

    @Test
    fun parseLocalHandle_rejectsMalformed() {
        assertNull(OpenUiContract.parseLocalHandle("https://scan/7f3a"))
        assertNull(OpenUiContract.parseLocalHandle("local://scan"))
        assertNull(OpenUiContract.parseLocalHandle("local:///7f3a"))
        assertNull(OpenUiContract.parseLocalHandle(""))
    }

    @Test
    fun parseLocalHandle_emptyIdRejected_emptyFragmentNormalizedToNull() {
        assertNull(OpenUiContract.parseLocalHandle("local://scan/"))
        // 空 fragment 不拒绝，归一为 null
        assertEquals(
            LocalHandle(domain = "scan", id = "7f3a", fragment = null),
            OpenUiContract.parseLocalHandle("local://scan/7f3a#"),
        )
    }
}
