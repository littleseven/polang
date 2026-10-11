package com.mamba.picme.testing.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.mamba.picme.testing.accessibility.model.Bounds
import com.mamba.picme.testing.accessibility.model.UiNode
import com.mamba.picme.testing.accessibility.model.UiWindow
import org.json.JSONObject

class AccessibilityNodeSerializer(private val targetPackage: String? = null) {

    fun dump(service: AccessibilityService, maxDepth: Int = 50): JSONObject {
        val root = resolveServiceRoot(service, targetPackage)
            ?: return JSONObject().apply {
                put("window", UiWindow(title = null, width = 0, height = 0).toJson())
                put(
                    "nodes",
                    UiNode(
                        id = "0",
                        packageName = null,
                        className = null,
                        text = null,
                        contentDescription = null,
                        hint = null,
                        bounds = Bounds(0, 0, 0, 0),
                        clickable = false,
                        longClickable = false,
                        scrollable = false,
                        enabled = false,
                        checked = false,
                        selected = false,
                        focused = false,
                        children = emptyList()
                    ).toJson()
                )
            }

        val windowInfo = service.windows.firstOrNull { it.isActive }
        val metrics = service.resources.displayMetrics
        val window = UiWindow(
            title = windowInfo?.title?.toString(),
            width = metrics.widthPixels,
            height = metrics.heightPixels
        )

        val rootNode = serializeNode(root, "0", 0, maxDepth)
        root.recycle()

        return JSONObject().apply {
            put("window", window.toJson())
            put("nodes", rootNode.toJson())
        }
    }

    private fun serializeNode(
        node: AccessibilityNodeInfo,
        id: String,
        depth: Int,
        maxDepth: Int
    ): UiNode {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val children = mutableListOf<UiNode>()
        if (depth < maxDepth) {
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i) ?: continue
                if (targetPackage != null && child.packageName?.toString() != targetPackage) {
                    child.recycle()
                    continue
                }
                children.add(serializeNode(child, "$id.$i", depth + 1, maxDepth))
                child.recycle()
            }
        }

        return UiNode(
            id = id,
            packageName = node.packageName?.toString(),
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            bounds = Bounds(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom
            ),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            enabled = node.isEnabled,
            checked = node.isChecked,
            selected = node.isSelected,
            focused = node.isFocused,
            children = children
        )
    }
}

/**
 * 解析无障碍树根（debug E2E 基建，2026-10-11）：
 * `rootInActiveWindow` 在 MIUI 浮窗/服务重绑等时机偶发返回「无子节点的空根」，
 * 改为从 `service.windows` 选目标包（或活跃）的 TYPE_APPLICATION 窗口取其 root，
 * 并 `refresh()` 强制拉取最新快照；全部落空才回退 `rootInActiveWindow`。
 * 返回节点由调用方负责 recycle。
 */
internal fun resolveServiceRoot(
    service: AccessibilityService,
    packageName: String? = null,
): AccessibilityNodeInfo? {
    val appWindows = service.windows.orEmpty()
        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
    // 顺序：活跃窗口 > 包名匹配（同包多窗口时活跃优先，避免命中空浮窗/弹窗根）
    var chosen: AccessibilityWindowInfo? = appWindows.firstOrNull { it.isActive }
    if (chosen == null && packageName != null) {
        for (w in appWindows) {
            val r = w.root ?: continue
            val match = r.packageName?.toString() == packageName
            r.recycle()
            if (match) {
                chosen = w
                break
            }
        }
    }
    if (chosen == null) {
        chosen = appWindows.firstOrNull()
    }
    val root = chosen?.root ?: service.rootInActiveWindow ?: return null
    root.refresh()
    return root
}
