@file:Suppress("TooGenericExceptionCaught") // runCatching 兜底解析失败
package com.mamba.picme.features.chat

import com.mamba.picme.domain.chat.ChatMessage
import com.mamba.picme.domain.chat.ClaudeAgentState
import com.mamba.picme.domain.chat.ClaudeStepStatus
import com.mamba.picme.domain.chat.ClaudeStepUi
import org.json.JSONArray
import org.json.JSONObject

/**
 * commonMain Chat 模型（[com.mamba.picme.domain.chat]）的 androidApp 兼容壳。
 *
 * - `ChatMessageUi` typealias：现有代码大量引用此名，typealias 让其零改动指向 commonMain
 *   [ChatMessage]（同包 features.chat 自动可见）。
 * - 其余子类型（[ClaudeAgentState] 等）由消费者直接 import
 *   commonMain（typealias 对 Companion 扩展 / 嵌套类型解析有边界，故子类型不走 typealias）。
 * - 2026-11-08 parts 单一权威后：消息内容一律经 partsJson（kotlinx codec）落库，EngineerTaskState /
 *   OptimizeCandidateGroup / HtmlCardMeta 的 org.json metadata serde 随之删除；仅剩
 *   [ClaudeAgentState] serde——metadata 白名单键 `claude_agent_state` 的消息级住户。
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

// MARK: - （已删除）OptimizeCandidateGroup / EngineerTaskState / HtmlCardMeta org.json serde
// 2026-11-08 parts 单一权威：三类负载改由 MessagePart（kotlinx codec）承载，org.json 形式不再落库。
