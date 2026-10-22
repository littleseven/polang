package com.mamba.picme.server.agent

import ai.koog.serialization.JSONObject
import ai.koog.serialization.kotlinx.toKotlinxJsonObject

/**
 * 工具参数 wire JSON（修复 Koog 1.3.0 私有 JSON 树 toString 零转义缺陷）。
 *
 * `ai.koog.serialization.JSONPrimitive.toString()` 只做原文包引号、不做 JSON 转义，
 * `JSONObject.toString()` 逐 entry 拼接——render_ui 的 messagesJson（嵌套 A2UI JSON
 * 字符串，含引号/中文）经 `ctx.toolArgs.toString()` 产出非法 JSON，端侧
 * `JSONObject(argsJson)` 解析失败静默吞帧（US-2：data_ui 不落库、A2UI 卡不渲染）。
 *
 * 统一经 kotlinx 映射拿合规转义；replayPending 回放库存 argsJson 同受其益。
 */
internal fun toolArgsWireJson(toolArgs: JSONObject): String =
    toolArgs.toKotlinxJsonObject().toString()
