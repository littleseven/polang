@file:Suppress("TooGenericExceptionCaught") // runCatching 兜底解析失败
package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.ChatMessage
import com.mamba.picme.domain.chat.ClaudeAgentState
import com.mamba.picme.domain.chat.ClaudeStepStatus
import com.mamba.picme.domain.chat.ClaudeStepUi
import com.mamba.picme.domain.chat.EngineerTaskResolution
import com.mamba.picme.domain.chat.EngineerTaskState
import com.mamba.picme.domain.chat.EngineerTaskStatus
import com.mamba.picme.domain.chat.OptimizeCandidateGroup
import com.mamba.picme.domain.chat.HtmlCardDisplayMode
import com.mamba.picme.domain.chat.HtmlCardMeta
import org.json.JSONArray
import org.json.JSONObject

/**
 * commonMain Chat 模型（[com.mamba.picme.domain.chat]）的 androidApp 兼容壳。
 *
 * - `ChatMessageUi` typealias：现有代码大量引用此名，typealias 让其零改动指向 commonMain
 *   [ChatMessage]（同包 features.chat 自动可见）。
 * - 其余子类型（[ClaudeAgentState] / [OptimizeCandidateGroup] 等）由消费者直接 import
 *   commonMain（typealias 对 Companion 扩展 / 嵌套类型解析有边界，故子类型不走 typealias）。
 * - org.json 序列化（toJson/fromJson）是 Android Room metadata 边界关注点，在此以扩展函数
 *   提供（commonMain 纯数据不含 org.json）；消费者同包自动可见这些扩展。
 */

typealias ChatMessageUi = ChatMessage

// MARK: - ClaudeAgentState org.json 序列化（Room metadata）

/** 序列化为 Room metadata JSON（气泡跨重载/重启保留）。 */
fun ClaudeAgentState.toJson(): JSONObject {
    val arr = JSONArray()
    for (s in steps) {
        arr.put(
            JSONObject()
                .put("tool", s.tool)
                .put("status", s.status.name)
                .put("detail", s.detail),
        )
    }
    return JSONObject()
        .put("text", text)
        .put("steps", arr)
        .put("hasFileChange", hasFileChange)
        .put("truncatedReason", truncatedReason ?: JSONObject.NULL)
}

/** 解析失败/缺字段返回默认态（不崩溃，对齐原 fromJson 容错）。 */
fun ClaudeAgentState.Companion.fromJson(obj: JSONObject): ClaudeAgentState {
    val arr = obj.optJSONArray("steps")
    val steps = mutableListOf<ClaudeStepUi>()
    for (i in 0 until (arr?.length() ?: 0)) {
        val s = arr!!.getJSONObject(i)
        steps += ClaudeStepUi(
            tool = s.optString("tool"),
            status = runCatching { ClaudeStepStatus.valueOf(s.optString("status")) }
                .getOrDefault(ClaudeStepStatus.RUNNING),
            detail = s.optString("detail"),
        )
    }
    return ClaudeAgentState(
        text = obj.optString("text"),
        steps = steps,
        hasFileChange = obj.optBoolean("hasFileChange", false),
        truncatedReason = if (obj.isNull("truncatedReason")) null else obj.optString("truncatedReason"),
    )
}

// MARK: - OptimizeCandidateGroup org.json 序列化（Room metadata）

fun OptimizeCandidateGroup.toJson(): String {
    val arr = JSONArray()
    candidates.forEach { c ->
        val score = c.nimaScore // 本地 val 规避跨模块 smart cast
        arr.put(JSONObject().apply {
            put("direction", c.direction)
            put("thumbPath", c.thumbPath)
            if (score != null) put("nimaScore", score.toDouble())
            put("rejected", c.rejected)
        })
    }
    return JSONObject().apply {
        put("sourceImageUri", sourceImageUri)
        put("scene", scene)
        put("recommendedIndex", recommendedIndex)
        put("drawIndex", drawIndex)
        put("candidates", arr)
        put("usedFingerprints", JSONArray(usedFingerprints))
    }.toString()
}

/** 解析失败 / 必需字段缺失返回 null（调用方按无负载处理，不崩溃）。 */
fun OptimizeCandidateGroup.Companion.fromJson(json: String?): OptimizeCandidateGroup? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("candidates")
        val candidates = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            OptimizeCandidateGroup.Candidate(
                direction = c.optString("direction"),
                thumbPath = c.optString("thumbPath"),
                nimaScore = if (c.has("nimaScore")) {
                    runCatching { c.getDouble("nimaScore").toFloat() }.getOrNull()
                } else {
                    null
                },
                rejected = c.optBoolean("rejected", false)
            )
        }
        val fps = obj.optJSONArray("usedFingerprints")
        OptimizeCandidateGroup(
            sourceImageUri = obj.getString("sourceImageUri"),
            scene = obj.optString("scene"),
            recommendedIndex = obj.optInt("recommendedIndex", -1),
            candidates = candidates,
            usedFingerprints = if (fps == null) emptyList() else (0 until fps.length()).map { fps.optString(it) },
            drawIndex = obj.optInt("drawIndex", 1)
        )
    }.getOrNull()
}

// ---- 工程师任务卡（TASK_CARD）Room metadata serde ----

fun EngineerTaskState.toJson(): JSONObject = JSONObject().apply {
    put("taskId", taskId)
    put("sourceText", sourceText)
    put("sid", sid)
    put("status", status.name)
    put("stage", stage)
    put("recentStages", JSONArray(recentStages))
    put("turns", turns)
    if (costCents != null) put("costCents", costCents)
    put("startedAtMs", startedAtMs)
    put("updatedAtMs", updatedAtMs)
    put("fileChangeCount", fileChangeCount)
    put("truncatedReason", truncatedReason)
    put("errorSummary", errorSummary)
    put("resultSummary", resultSummary)
    put("resolution", resolution?.name)
    put("deliverBranch", deliverBranch)
}

/**
 * 从 metadata.engineer_task 还原任务卡状态。taskId 缺失或整体结构损坏返回 null（UI 回落无卡形态）；
 * 其余字段缺失静默回退默认值，不丢卡（对齐 OptimizeCandidateGroup.fromJson 先例）。
 * 未知 status 回退 FAILED（安全终态，不参与在场门控），避免旧版本读到未来新枚举值时卡片被静默复活成活跃态。
 */
fun parseEngineerTaskState(metadata: String?): EngineerTaskState? {
    if (metadata.isNullOrBlank()) return null
    return runCatching {
        val root = JSONObject(metadata).optJSONObject("engineer_task") ?: return null
        EngineerTaskState(
            taskId = root.getString("taskId"),
            sourceText = root.optString("sourceText"),
            sid = root.optString("sid").ifBlank { null },
            status = runCatching { EngineerTaskStatus.valueOf(root.optString("status")) }
                .getOrDefault(EngineerTaskStatus.FAILED),
            stage = root.optString("stage").ifBlank { null },
            recentStages = root.optJSONArray("recentStages")?.let { arr ->
                (0 until arr.length()).map { idx -> arr.getString(idx) }
            } ?: emptyList(),
            turns = root.optInt("turns"),
            costCents = if (root.has("costCents")) root.getInt("costCents") else null,
            startedAtMs = root.optLong("startedAtMs"),
            updatedAtMs = root.optLong("updatedAtMs"),
            fileChangeCount = root.optInt("fileChangeCount"),
            truncatedReason = root.optString("truncatedReason").ifBlank { null },
            errorSummary = root.optString("errorSummary").ifBlank { null },
            resultSummary = root.optString("resultSummary").ifBlank { null },
            resolution = root.optString("resolution").ifBlank { null }
                ?.let { name -> runCatching { EngineerTaskResolution.valueOf(name) }.getOrNull() },
            deliverBranch = root.optString("deliverBranch").ifBlank { null },
        )
    }.getOrNull()
}

// ---- HTML 卡双形态（HTML_CARD）Room metadata serde ----

/** 序列化为 metadata `html_card` 子对象（缺省字段不写，保持 JSON 紧凑）。 */
fun HtmlCardMeta.toJson(): JSONObject = JSONObject().apply {
    val declaredDisplay = display
    val decidedMode = displayMode
    val measured = measuredHeightPx
    val caption = summary
    if (declaredDisplay != null) put("display", declaredDisplay)
    if (decidedMode != null) put("displayMode", decidedMode.name)
    if (measured != null) put("measuredHeightPx", measured)
    if (caption != null) put("summary", caption)
}

/**
 * 从 metadata.html_card 还原双形态元数据。整体结构损坏返回 null（按无元数据处理 = 未声明 inline 待测高）；
 * 未知 displayMode 枚举值静默回退 null（待重新判定），避免旧版本读到未来值时形态错乱。
 */
fun parseHtmlCardMeta(metadata: String?): HtmlCardMeta? {
    if (metadata.isNullOrBlank()) return null
    return runCatching {
        val root = JSONObject(metadata).optJSONObject("html_card") ?: return null
        HtmlCardMeta(
            display = root.optString("display").ifBlank { null },
            displayMode = root.optString("displayMode").ifBlank { null }
                ?.let { name -> runCatching { HtmlCardDisplayMode.valueOf(name) }.getOrNull() },
            measuredHeightPx = if (root.has("measuredHeightPx")) root.getInt("measuredHeightPx") else null,
            summary = root.optString("summary").ifBlank { null },
        )
    }.getOrNull()
}
