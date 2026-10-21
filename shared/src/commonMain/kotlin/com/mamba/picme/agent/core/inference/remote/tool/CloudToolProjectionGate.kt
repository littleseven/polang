package com.mamba.picme.agent.core.inference.remote.tool

/**
 * 出端投影闸（spec §3.1 集中投影函数 + §3.4 强制不变式）——一切上云的端侧工具结果
 * 必经此闸，不散落在各工具里。
 *
 * fail-closed：命中禁出模式时原内容**不出端**，替换为 `error: projection_blocked:<tool>`
 *（契约 `error:` 前缀，server/端侧/TurnRunner 同口径识别为失败；该文本本身即是
 * 可观测信号——违反投影不变式时表现为功能失败而非静默泄漏）。
 *
 * 禁出模式 = [PRIVACY] 红线的机器可校验面：媒体引用（content://、file://、local://）
 * 与全量路径（/storage/、/sdcard/、/data/user/、/data/media/）。文本元数据/计数/摘要放行。
 */
object CloudToolProjectionGate {

    const val BLOCKED_PREFIX = "error: projection_blocked:"

    private val FORBIDDEN: List<Regex> = listOf(
        Regex("content://"),
        Regex("file://"),
        Regex("local://"),
        Regex("/storage/"),
        Regex("/sdcard/"),
        Regex("/data/user/"),
        Regex("/data/media/"),
    )

    /** 校验出端文本；安全则原样返回，违规则 fail-closed 替换。 */
    fun gate(tool: String, outbound: String): String {
        val hit = FORBIDDEN.firstOrNull { pattern -> pattern.containsMatchIn(outbound) }
        return if (hit == null) outbound else "$BLOCKED_PREFIX$tool"
    }
}
