package com.mamba.picme.domain.chat

/**
 * 浏览器直播卡 live 态挂载（spec §4）：会话期间的帧/动作流水更新走 BrowserLive part
 * **同 sessionId 原位覆写**（partId 不变，LazyColumn key 恒定），内存态不落 Room；
 * 持久化在会话结束时由调用方落最终卡（OUTPUT_AVAILABLE）。
 *
 * 形态与 [overlayLiveTaskState] 同构；纯函数：无 BrowserLive part / 无 live 态 → 原样返回。
 */
fun ChatMessage.overlayLiveBrowserState(live: Map<String, MessagePart.BrowserLive>): ChatMessage {
    if (live.isEmpty()) return this
    val index = parts.indexOfFirst { it is MessagePart.BrowserLive && it.sessionId in live }
    if (index < 0) return this
    val part = parts[index] as MessagePart.BrowserLive
    val livePart = live.getValue(part.sessionId)
    if (livePart == part) return this
    // partId 以流式轨占位为准（key 恒定），其余字段取 live 投影
    val newPart = livePart.copy(partId = part.partId)
    return copy(parts = parts.toMutableList().also { it[index] = newPart })
}
