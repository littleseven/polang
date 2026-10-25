package com.mamba.picme.features.chat.a2ui

import androidx.a2ui.compose.runtime.A2uiComponentReference
import androidx.a2ui.compose.runtime.A2uiComponentScope
import androidx.a2ui.compose.ui.catalog.A2uiBasicCatalogV1
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * 纵向 List 有界封装（2026-10-25 行程 Tab 崩溃事故）。
 *
 * 官方默认 List 渲染器纵向走 LazyColumn；聊天卡挂在聊天消息流（LazyColumn item）
 * 里，item 按无限高约束测量 → 嵌套 LazyColumn 拿到 infinity max height →
 * IllegalStateException 闪退。本组件继承接口默认实现（name/description/properties/
 * isReady/Content 全部沿用，Content 默认实现虚调用 TypedContent），只覆写
 * TypedContent：纵向时给 modifier 封顶 [maxHeight]，内部自滚；横向 LazyRow
 * 父容器宽度恒有界，不动。
 */
class BoundedVerticalA2uiList(
    private val maxHeight: Dp,
    private val inner: A2uiBasicCatalogV1.List = MaterialA2uiBasicCatalogV1Defaults.list,
) : A2uiBasicCatalogV1.List {

    @Composable
    override fun A2uiComponentScope.TypedContent(
        children: List<A2uiComponentReference>,
        direction: A2uiBasicCatalogV1.List.Direction,
        align: A2uiBasicCatalogV1.List.Align,
        accessibility: A2uiBasicCatalogV1.AccessibilityAttributes?,
        modifier: Modifier,
    ) {
        val bounded =
            if (direction == A2uiBasicCatalogV1.List.Direction.Vertical) {
                modifier.heightIn(max = maxHeight)
            } else {
                modifier
            }
        with(inner) {
            this@TypedContent.TypedContent(children, direction, align, accessibility, bounded)
        }
    }
}
