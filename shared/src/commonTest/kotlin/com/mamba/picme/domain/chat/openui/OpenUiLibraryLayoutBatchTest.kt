package com.mamba.picme.domain.chat.openui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OpenUI 布局可控性批次（2026-10-31，spec《2026-10-31-openui-layout-controllability》）：
 * +CompareRow/Timeline/ChartGrid schema 钉住——97 → 100 注册项。
 */
class OpenUiLibraryLayoutBatchTest {

    @Test
    fun `layout batch components registered with contracts`() {
        val registry = OpenUiLibrary.components

        val compare = registry.getValue("CompareRow")
        assertEquals(
            listOf("beforePhoto", "afterPhoto", "beforeLabel", "afterLabel", "beforeSrc", "afterSrc", "aspect"),
            compare.props.map { prop -> prop.name },
        )
        assertTrue(compare.props.first { prop -> prop.name == "beforePhoto" }.required)
        assertTrue(compare.props.first { prop -> prop.name == "afterPhoto" }.required)

        val timeline = registry.getValue("Timeline")
        val items = timeline.props.single()
        assertTrue(items.required)
        assertEquals(OpenUiPropType.OBJECT_ARRAY, items.type)
        assertEquals(
            listOf("title", "subtitle", "time", "photo"),
            items.objectFields?.map { field -> field.name },
        )

        val chartGrid = registry.getValue("ChartGrid")
        val children = chartGrid.props.single()
        assertTrue(children.required)
        assertEquals(OpenUiPropType.COMPONENT_LIST, children.type)
    }
}
