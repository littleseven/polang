package com.mamba.picme.features.chat.openui

import com.mamba.picme.domain.chat.openui.OpenUiResult
import com.mamba.picme.domain.chat.openui.OpenUiSession
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.domain.chat.openui.OpenUiValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式预览骨架契约钉住（2026-11-12 前向引用骨架流式）：OpenUiStreamingCard 逐帧
 * session 以 forwardRefSkeletons = true 构建——partial 前缀的前向引用在 children 槽
 * 产 Skeleton 占位（OpenUiSkeletonPlaceholder 渲染），正式卡路径（OpenUiCardHost，
 * 默认模式）零 Skeleton。引擎层语义已由 commonTest OpenUiStreamingPreviewTest 覆盖，
 * 本测试钉 Android 流式链路依赖的契约形态（骨架槽位名 + 消失时序）。
 */
class OpenUiSkeletonStreamingTest {

    private val emptyResolver = OpenUiToolResolver { _, _ -> OpenUiValue.Null }

    private suspend fun previewChildren(program: String): List<OpenUiValue> {
        val session = OpenUiSession(program, emptyResolver, forwardRefSkeletons = true)
        val result = session.prepare()
        return ((result as OpenUiResult.Rendered).root.props["children"] as OpenUiValue.Arr).v
    }

    @Test
    fun `streaming frame keeps forward ref skeleton slots in arrival order`() = runTest {
        val children = previewChildren("root = Card([header, chart, footer])")
        assertEquals(
            listOf("header", "chart", "footer"),
            children.filterIsInstance<OpenUiValue.Skeleton>().map { it.refName },
        )
    }

    @Test
    fun `skeleton slot swaps to real component once definition arrives`() = runTest {
        val children = previewChildren(
            "root = Card([header, footer])\nfooter = Text(\"共 3 张\", \"CAPTION\")",
        )
        assertEquals(2, children.size)
        // 未到定义 → 骨架占位；已到定义 → 组件原位替换（top-down reveal）
        assertEquals("header", (children[0] as OpenUiValue.Skeleton).refName)
        val arrived = children[1] as OpenUiValue.ComponentVal
        assertEquals("Text", arrived.type)
        assertEquals("共 3 张", (arrived.props["text"] as OpenUiValue.Str).v)
    }

    @Test
    fun `terminal card path default mode yields no skeleton`() = runTest {
        // OpenUiCardHost 终态路径不传开关：同一 partial 前缀下 children 照旧剔除（dropped），
        // Skeleton 永不进正式卡/持久化（partsJson 零污染的链路级保证）
        val session = OpenUiSession("root = Card([header, body])", emptyResolver)
        val result = session.prepare()
        assertTrue(result is OpenUiResult.Rendered)
        assertTrue((result as OpenUiResult.Rendered).root.let { root ->
            (root.props["children"] as OpenUiValue.Arr).v.isEmpty()
        })
    }
}
