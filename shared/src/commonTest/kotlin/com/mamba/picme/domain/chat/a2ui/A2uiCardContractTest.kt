package com.mamba.picme.domain.chat.a2ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class A2uiCardContractTest {

    @Test
    fun handleToken_isOpaqueWithoutScheme() {
        val token = A2uiCardContract.formatHandleToken(A2uiCardContract.DOMAIN_SCAN, "abc123")
        assertEquals("handle: scan/abc123", token)
        // 投影闸 FORBIDDEN 含 "local://" 字面量：opaque token 绝不含 scheme
        assertFalse(token.contains(A2uiCardContract.HANDLE_SCHEME))
    }

    @Test
    fun localHandle_roundtripWithFragment() {
        val handle = LocalHandle(domain = "scan", id = "7f3a", fragment = "histogram")
        val wire = A2uiCardContract.formatLocalHandle(handle)
        assertEquals("local://scan/7f3a#histogram", wire)
        assertEquals(handle, A2uiCardContract.parseLocalHandle(wire))
    }

    @Test
    fun localHandle_roundtripWithoutFragment() {
        val handle = LocalHandle(domain = "scan", id = "7f3a", fragment = null)
        assertEquals(handle, A2uiCardContract.parseLocalHandle("local://scan/7f3a"))
    }

    @Test
    fun parseLocalHandle_rejectsMalformed() {
        assertNull(A2uiCardContract.parseLocalHandle("https://scan/7f3a"))
        assertNull(A2uiCardContract.parseLocalHandle("local://scan"))
        assertNull(A2uiCardContract.parseLocalHandle("local:///7f3a"))
        assertNull(A2uiCardContract.parseLocalHandle(""))
    }

    @Test
    fun parseLocalHandle_emptyIdRejected_emptyFragmentNormalizedToNull() {
        assertNull(A2uiCardContract.parseLocalHandle("local://scan/"))
        // 空 fragment 不拒绝，归一为 null
        assertEquals(
            LocalHandle(domain = "scan", id = "7f3a", fragment = null),
            A2uiCardContract.parseLocalHandle("local://scan/7f3a#"),
        )
    }
}
