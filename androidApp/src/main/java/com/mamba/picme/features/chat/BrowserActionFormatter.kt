package com.mamba.picme.features.chat

import android.content.Context
import androidx.annotation.StringRes
import com.mamba.picme.R

/** 动作流水条目的资源规格（纯 Kotlin 可测；Context 格式化收口在 [formatBrowserAction]）。 */
data class BrowserActionSpec(
    @StringRes val templateRes: Int,
    val arg: String?,
)

/** browser_* 动作 → 文案规格；arg 截断 24 字符防流水行过长。 */
fun browserActionSpec(action: String, selector: String?, payload: String?): BrowserActionSpec {
    fun cut(s: String?): String? = s?.let { if (it.length > 24) it.take(21) + "..." else it }
    return when (action) {
        "open" -> BrowserActionSpec(R.string.browser_action_open, cut(payload))
        "navigate" -> BrowserActionSpec(R.string.browser_action_navigate, cut(payload))
        "click" -> BrowserActionSpec(R.string.browser_action_click, cut(selector))
        "type" -> BrowserActionSpec(R.string.browser_action_type, cut(payload))
        "extract" -> BrowserActionSpec(R.string.browser_action_extract, null)
        "screenshot" -> BrowserActionSpec(R.string.browser_action_screenshot, null)
        else -> BrowserActionSpec(R.string.browser_action_navigate, null)
    }
}

/** ChatViewModel 侧入口：本地化动作流水描述。 */
fun formatBrowserAction(context: Context, action: String, selector: String?, payload: String?): String {
    val spec = browserActionSpec(action, selector, payload)
    return if (spec.arg != null) context.getString(spec.templateRes, spec.arg) else context.getString(spec.templateRes)
}
