package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * P1（spec《2026-10-26-openui-compose-alignment-design》§3.1）：syncWith 逐键增量同步契约——
 * 未变键的值实例必须保持同一引用（SnapshotStateMap 场景下不变更即不让订阅者失效）。
 */
class OpenUiStateSyncTest {

    @Test
    fun `sync adds changes and removes incrementally`() {
        val target = mutableMapOf(
            "a" to OpenUiValue.Num(1.0),
            "b" to OpenUiValue.Str("keep"),
            "gone" to OpenUiValue.Bool(true),
        )
        target.syncWith(
            mapOf(
                "a" to OpenUiValue.Num(2.0),
                "b" to OpenUiValue.Str("keep"),
                "c" to OpenUiValue.Str("new"),
            )
        )
        assertEquals(
            mapOf(
                "a" to OpenUiValue.Num(2.0),
                "b" to OpenUiValue.Str("keep"),
                "c" to OpenUiValue.Str("new"),
            ),
            target,
        )
    }

    @Test
    fun `unchanged value instance is not replaced`() {
        val shared = OpenUiValue.Str("same")
        val target = mutableMapOf<String, OpenUiValue>("k" to shared)
        target.syncWith(mapOf("k" to shared))
        assertSame("未变键不得重写（重写会让 per-key 订阅者失效）", shared, target["k"])
    }

    @Test
    fun `empty latest clears target`() {
        val target = mutableMapOf<String, OpenUiValue>("a" to OpenUiValue.Null)
        target.syncWith(emptyMap())
        assertEquals(emptyMap<String, OpenUiValue>(), target)
    }
}
