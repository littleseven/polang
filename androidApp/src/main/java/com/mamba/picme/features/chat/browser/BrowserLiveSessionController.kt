package com.mamba.picme.features.chat.browser

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.mamba.picme.R
import com.mamba.picme.agent.core.capability.BrowserSessionCapability
import com.mamba.picme.agent.core.capability.BrowserSessionDelegate
import com.mamba.picme.data.local.ChatMessageDao
import com.mamba.picme.data.local.ChatMessageEntity
import com.mamba.picme.data.local.insertMessageWithParts
import com.mamba.picme.data.remote.picme.BrowserSessionClient
import com.mamba.picme.data.remote.picme.BrowserWebSocketClient
import com.mamba.picme.domain.browser.BrowserStatus
import com.mamba.picme.domain.chat.BrowserActionEntry
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import com.mamba.picme.domain.chat.streaming.TurnPartsReducer
import com.mamba.picme.features.chat.formatBrowserAction
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject

/** browser-vnc 直播卡 live 态 overlay 节流（与任务卡同 500ms 节拍；帧高频不直接驱动全列表重组）。 */
private const val BROWSER_LIVE_OVERLAY_THROTTLE_MS = 500L

/** turn 结束兜底 close 宽限期（M2.7 竞态修复）：给用户「事后进直播卡接管」的机会窗；
 * 须 < bridge idleTimeout 120s，保证宽限 close 先于 idle reap 触发（卡片拿得到 lastGoodFrame）。 */
private const val BROWSER_SETTLE_GRACE_MS = 90_000L

private const val TAG = "ChatViewModel"

/**
 * 浏览器直播卡会话控制器 —— ChatViewModel 拆分第一接缝（架构健康审计 P1-3b，browser-vnc spec §4/§6）。
 *
 * 自 ChatViewModel 整体迁出（纯移动重构，行为等价）：云端浏览器会话的 live 态管理、
 * WS 推流/HTTP 轮询双通道、takeover 动作串行通道、turn 结束宽限兜底 close（M2.7 竞态修复）、
 * 定格卡落库。帧/动作流水只走本控制器与 UI 通道，不回灌 LLM。
 *
 * 与宿主 ViewModel 的边界全部经构造函数显式注入（Agent First：构造函数即文档）：
 * - [capabilityProvider]：组合根注册的 BrowserSessionCapability 单例（未注册 → 整体静默降级）
 * - [isTurnInFlight]/[stopGeneration]：turn 生命周期接缝（unwatch 即时 close 判定、接管即暂停）
 * - [feedToolOutput]/[feedToolError]：turn 装配器回填（占位原位填充/失败闭环）
 *
 * 线程模型：RMW 一律 StateFlow.update 原子 CAS（delegate 回调线程与轮询 IO 并发）；
 * settleJobs/actionJobs 仅主线程读写（scope = viewModelScope）；
 * userControlledSessions/watchedSessions 跨线程读写，用同步集合。
 */
@Suppress("TooManyFunctions") // 会话生命周期 + 双通道 + 宽限兜底为同一职责族，不再细分
class BrowserLiveSessionController(
    private val scope: CoroutineScope,
    private val browserWebSocketClient: BrowserWebSocketClient?,
    private val capabilityProvider: () -> BrowserSessionCapability?,
    private val chatMessageDao: ChatMessageDao,
    private val currentSessionId: () -> String,
    private val stringContext: () -> Context,
    private val isTurnInFlight: () -> Boolean,
    private val stopGeneration: () -> Unit,
    private val feedToolOutput: suspend (toolName: String, output: (toolCallId: String) -> MessagePart) -> Unit,
    private val feedToolError: suspend (toolName: String, errorText: String) -> Unit,
) : BrowserSessionDelegate {

    /** 浏览器直播卡内存态：sessionId → 最新 live 态（Room 为持久层，此处为会话期间的 live 覆盖）。 */
    private val _liveSessions = MutableStateFlow<Map<String, MessagePart.BrowserLive>>(emptyMap())
    val liveSessions: StateFlow<Map<String, MessagePart.BrowserLive>> = _liveSessions.asStateFlow()

    /**
     * 直播卡 live 态的节流视图：帧/动作流水高频更新不直接驱动全列表重组。
     * 首值直通 + 周期节流的语义同任务卡 throttledEngineerTasks。
     */
    @VisibleForTesting
    internal val throttledLiveSessions: Flow<Map<String, MessagePart.BrowserLive>> =
        _liveSessions.sample(BROWSER_LIVE_OVERLAY_THROTTLE_MS).onStart { emit(_liveSessions.value) }

    private val messageSeq = AtomicInteger(0)

    private val partJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** takeover 动作按会话串行保序的任务链（scroll/typeText 乱序即事故）。 */
    private val actionJobs = HashMap<String, Job>()

    /**
     * 用户接管中的浏览器会话 id（M2.6 Muse 形态）：turn 结束兜底 close 豁免——接管已停 agent，
     * 但会话须存活供用户继续操作。主线程写、[settleBrowserSessionsAtTurnEnd] IO 线程读，用同步集合。
     */
    private val userControlledSessions = Collections.synchronizedSet(mutableSetOf<String>())

    /** 宽限期 close 待决任务（main 线程读写）。 */
    private val settleJobs = HashMap<String, Job>()

    /** 正在被全屏接管页观看的会话 id（overlay watch 期间豁免兜底 close）。主线程写、IO 读，同步集合。 */
    private val watchedSessions = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * 启动接线（宿主 VM init 调用一次）：注册为会话事件出口 + WS 帧/断连事件收集。
     * 帧事件 → [_liveSessions] 原位覆写。
     */
    fun start() {
        capabilityProvider()?.setDelegate(this)
        browserWebSocketClient?.let { wsClient ->
            scope.launch {
                wsClient.frameEvents.collect { event ->
                    onBrowserSessionFrame(event.sessionId, "", "", event.frameJpegBase64)
                }
            }
            scope.launch {
                wsClient.disconnectEvents.collect { event ->
                    // 非主动断开（网络抖动/bridge 重启）：仅降级——卡片保留最后一帧，
                    // 后续 HTTP 动作帧（delegate 通道）仍会更新；不把整条会话标失败
                    // （WS 只是 watch 增强层，标失败会误伤进行中的会话并污染 reducer 终态）
                    Log.w(TAG, "browser WS disconnected: session=${event.sessionId}, reason=${event.reason}")
                }
            }
        }
    }

    /**
     * 宿主 VM onCleared 调用：delegate 反注册（capability 是组合根单例，VM 销毁后不得继续持有）+
     * WS 断开所有活跃连接。
     */
    fun shutdown() {
        capabilityProvider()?.setDelegate(null)
        browserWebSocketClient?.disconnectAll()
    }

    // ── BrowserSessionDelegate：云端浏览器直播卡 live 态（browser-vnc spec §4）─────────

    override fun onBrowserSessionStarted(sessionId: String, url: String, title: String, frameJpegBase64: String?) {
        val entry = MessagePart.BrowserLive(
            partId = "", // 以流式占位为准（overlay 归一化保留占位 partId，见 BrowserLiveOverlay）
            sessionId = sessionId,
            state = ToolPartState.INPUT_AVAILABLE,
            currentUrl = url,
            pageTitle = title,
            frameJpegBase64 = frameJpegBase64,
            actions = listOf(
                BrowserActionEntry(description = formatBrowserAction(stringContext(), "open", null, url), ok = true)
            ),
            actionCount = 1,
        )
        _liveSessions.update { it + (sessionId to entry) }
        // browser_open 占位原位填充（拿到 sessionId；无在途调用时 feedToolOutput 内部 no-op）
        scope.launch {
            feedToolOutput(TurnPartsReducer.TOOL_BROWSER_OPEN) { toolCallId -> entry.copy(partId = toolCallId) }
        }
    }

    override fun onBrowserSessionAction(sessionId: String, action: String, selector: String?, text: String?, url: String, title: String, frameJpegBase64: String?) {
        // delegate 回调来自能力执行线程，与 pollBrowserFrame(IO) 并发：RMW 必须走 update 原子 CAS，
        // 否则旧快照回退会丢更新（最严重：帧回退终态写入 → settle 误判非终态重复 close/落卡）
        _liveSessions.update { sessions ->
            val current = sessions[sessionId] ?: return@update sessions
            sessions + (sessionId to current.copy(
                currentUrl = url.ifBlank { current.currentUrl },
                pageTitle = title.ifBlank { current.pageTitle },
                frameJpegBase64 = frameJpegBase64 ?: current.frameJpegBase64,
                actions = (current.actions + BrowserActionEntry(
                    description = formatBrowserAction(stringContext(), action, selector, text ?: url)
                )).takeLast(3),
                actionCount = current.actionCount + 1,
            ))
        }
    }

    override fun onBrowserSessionFrame(sessionId: String, url: String, title: String, frameJpegBase64: String) {
        _liveSessions.update { sessions ->
            val current = sessions[sessionId] ?: return@update sessions
            sessions + (sessionId to current.copy(
                currentUrl = url.ifBlank { current.currentUrl },
                pageTitle = title.ifBlank { current.pageTitle },
                frameJpegBase64 = frameJpegBase64,
            ))
        }
    }

    override fun onBrowserSessionFailed(sessionId: String, reason: String) {
        _liveSessions.update { sessions ->
            val current = sessions[sessionId] ?: return@update sessions
            sessions + (sessionId to current.copy(state = ToolPartState.OUTPUT_ERROR, errorReason = reason))
        }
        // 有意不做门控：unknown session（live 态已被 settle 移除）时错误仍进流式轨，
        // 让失败在工具调用链上留痕（对 oldest pending 通常 no-op，无视觉副作用）。
        scope.launch { feedToolError(TurnPartsReducer.TOOL_BROWSER_OPEN, reason) }
    }

    override fun onBrowserSessionClosed(sessionId: String, finalFrameJpegBase64: String?, actionCount: Int) {
        userControlledSessions -= sessionId // 会话关闭后受控豁免失效（防泄漏）
        var finalPart: MessagePart.BrowserLive? = null
        _liveSessions.update { sessions ->
            val current = sessions[sessionId] ?: return@update sessions
            // 步数口径统一：bridge actionCount 只计 navigate/click/type/extract/screenshot（不含 open/close），
            // 本地 current.actionCount 从 open=1 起累计。定格取 max(本地累计, bridge + open 1 步)，
            // 防「流水 2 行却显示共 1 步」「纯 open 会话显示共 0 步」的口径漂移（Task 20 验收观察项 1/2）。
            val displayCount = maxOf(current.actionCount, actionCount + 1)
            val part = current.copy(
                state = if (current.state == ToolPartState.OUTPUT_ERROR) ToolPartState.OUTPUT_ERROR else ToolPartState.OUTPUT_AVAILABLE,
                frameJpegBase64 = finalFrameJpegBase64 ?: current.frameJpegBase64,
                actionCount = displayCount,
                resultSummary = if (current.errorReason == null) {
                    stringContext().getString(R.string.browser_live_done_summary, displayCount)
                } else {
                    null
                },
            )
            finalPart = part
            sessions + (sessionId to part)
        }
        // 定格后保留在 map：overlay 持续把流式占位覆写为终态，Task 13 双显跳过依赖该状态；
        // 清理统一在 turn 结束兜底（settleBrowserSessionsAtTurnEnd）
        val persisted = finalPart ?: return
        scope.launch { emitBrowserCardMessage(persisted) }
    }

    /**
     * 浏览器会话定格卡**落库**（type=tool_browser，content=BrowserLive JSON；partsJson 由
     * insertMessageWithParts 自标量列现算——MessagePartsConverter 认 tool_browser 解回 BrowserLive）。
     * 与图表/HTML 卡同理：消息列表由 DB Flow 驱动，卡片必须落库才能跨重载持久。
     * 双显跳过（流式占位卡 vs 产物行）由 ChatScreen 的 persistedBrowserSessionIds
     * 提取式自动生效（自非流式消息 parts 提取 sessionId 集合）。
     */
    private suspend fun emitBrowserCardMessage(finalPart: MessagePart.BrowserLive) {
        val persisted = finalPart.copy(partId = "p0")
        chatMessageDao.insertMessageWithParts(
            ChatMessageEntity(
                id = "browser_" + System.currentTimeMillis() + "_" + messageSeq.incrementAndGet(),
                sessionId = currentSessionId(),
                type = "tool_browser",
                role = "agent",
                content = partJson.encodeToString(MessagePart.BrowserLive.serializer(), persisted),
                timestamp = System.currentTimeMillis(),
                modelUsed = "browser"
            )
        )
    }

    /**
     * 启动 WS 推流 watch（M2）：连接 bridge WebSocket，帧经 [BrowserWebSocketClient.frameEvents]
     * 推送到 [_liveSessions]。WS 不可用时回退 HTTP 轮询（spec §6 降级）。
     */
    fun watchBrowserSession(sessionId: String) {
        watchedSessions += sessionId
        settleJobs.remove(sessionId)?.cancel() // 观看中豁免宽限 close（竞态修复 M2.7）
        val wsClient = browserWebSocketClient
        if (wsClient != null) {
            wsClient.connect(sessionId)
        } else {
            // 回退：WS 客户端未接线时走 HTTP 轮询（单测/降级场景）
            pollBrowserFrame(sessionId)
        }
    }

    /**
     * 停止 WS 推流 watch（卡片离开组合/会话终态/暂停时调用）。
     * 观看页退出且无回合在跑、未被接管：会话留着只会等 bridge idle reap 徒占并发席位——
     * 立即 close 定格（turn 仍在跑则保留，用户可从卡片重进，M2.7）。
     */
    fun unwatchBrowserSession(sessionId: String) {
        watchedSessions -= sessionId
        browserWebSocketClient?.disconnect(sessionId)
        if (!isTurnInFlight() && sessionId !in userControlledSessions) {
            scope.launch(Dispatchers.IO) { closeBrowserSessionIfIdle(sessionId) }
        }
    }

    /**
     * 发送 takeover 动作到云端浏览器（HTTP 原文透传，M2.7 起不再走 WS）。
     * action 为 bridge 动作名（clickAt / typeText / scroll / drag / key），params 为动作参数 JSON。
     *
     * 为什么不走 WS：推帧洪泛时 WS 下行被二进制帧占满，动作回执排队在帧后（真机实测 >13s 不到，
     * 经代理空载实测 0.10s）——动作看似发出实则卡死。HTTP 通道独立于帧流，响应同步携带结果。
     * 顺序保证：HTTP 无单通道顺序语义，按会话串行（scroll/typeText 乱序即事故）。
     */
    fun sendBrowserAction(sessionId: String, action: String, params: JSONObject) {
        // bridge sessions.js 从动作体顶层读参数（body.x/body.y/...），params 必须平铺进动作对象
        val payload = JSONObject().put("action", action)
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            payload.put(key, params.get(key))
        }
        val prev = actionJobs[sessionId]
        actionJobs[sessionId] = scope.launch {
            prev?.join()
            val client = capabilityProvider()?.transport as? BrowserSessionClient
            if (client == null) {
                Log.w(TAG, "browser action dropped (no http client): $action session=$sessionId")
                return@launch
            }
            try {
                val result = client.rawAction(sessionId, payload)
                if (result.status == BrowserStatus.OK) {
                    Log.d(TAG, "browser action ok: $action session=$sessionId url=${result.currentUrl} ms=${result.actionMs} params=$payload")
                } else {
                    Log.w(TAG, "browser action failed: $action session=$sessionId status=${result.status} reason=${result.reason}")
                    // 会话已被云端回收（idle reap 等）：静默吞掉会让用户对着死画面操作——
                    // 走统一失败通路，live 态转 OUTPUT_ERROR，接管页呈现「会话已结束」
                    if (result.status == BrowserStatus.SESSION_EXPIRED) {
                        onBrowserSessionFailed(sessionId, result.reason ?: "session expired")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "browser action error: $action session=$sessionId ${e.message}")
            }
        }
    }

    /**
     * Muse「接管浏览器」：登记受控豁免（[settleBrowserSessionsAtTurnEnd] 跳过该会话）后取消
     * 当前生成回合 ≈ Muse「接管即暂停 agent」。无真暂停/续跑基建：交还控制不续跑 agent。
     */
    fun takeControlBrowserSession(sessionId: String) {
        userControlledSessions += sessionId
        settleJobs.remove(sessionId)?.cancel() // 接管豁免宽限 close（竞态修复 M2.7）
        stopGeneration()
    }

    /** Muse「交还控制」：解除受控豁免（agent 不续跑；会话由 bridge 空闲回收或下轮兜底 close 善后）。 */
    fun releaseBrowserSessionControl(sessionId: String) {
        userControlledSessions -= sessionId
    }

    /**
     * Muse「停止任务」：显式 close 会话，按 [onBrowserSessionClosed] 同路径定格落库。
     * close 失败静默：服务端 2 分钟空闲回收兜底（spec §6）。
     */
    fun stopBrowserSession(sessionId: String) {
        userControlledSessions -= sessionId
        val part = _liveSessions.value[sessionId] ?: return
        scope.launch(Dispatchers.IO) {
            val result = try {
                capabilityProvider()?.transport?.close(sessionId)
            } catch (e: CancellationException) {
                // 结构化并发：VM 清理/协程取消原样上抛，不折叠为静默失败
                throw e
            } catch (e: Exception) {
                null // 失败静默：会话由服务端 2 分钟空闲回收兜底（spec §6）
            }
            // actionCount 换算同 settleBrowserSessionsAtTurnEnd（bridge 口径不含 open）
            onBrowserSessionClosed(sessionId, result?.lastGoodFrame, result?.actionCount ?: (part.actionCount - 1))
            _liveSessions.update { it - sessionId }
        }
    }

    /** HTTP 轮询回退（WS 未接线或连接失败时）。 */
    private fun pollBrowserFrame(sessionId: String) {
        scope.launch(Dispatchers.IO) {
            val transport = capabilityProvider()?.transport ?: return@launch
            runCatching { transport.frame(sessionId) }
                .onSuccess { r ->
                    val frame = r.frameJpegBase64
                    if (r.status == BrowserStatus.OK && frame != null) {
                        onBrowserSessionFrame(sessionId, r.currentUrl ?: "", r.pageTitle ?: "", frame)
                    }
                }
            // 失败静默：轮询下个节拍自愈（spec §6 不穿透）
        }
    }

    /**
     * turn 结束兜底（spec §6）：仍为非终态的会话调度宽限期 close（LLM 忘调 browser_close 时防服务器
     * 会话泄漏 + 卡片有终态）。M2.7 竞态修复：turn 结束立即 close 与「用户事后进直播卡接管」存在
     * 固有竞态——close HTTP 在途的 1-3s 窗口内卡片仍显示 running，用户进卡接管后全部动作
     * session_expired（真机实测）。改为宽限期调度：[BROWSER_SETTLE_GRACE_MS] 后仍非终态且未被
     * 接管（[userControlledSessions]）/观看（[watchedSessions]）才 close 定格；
     * 宽限期内用户进卡（watch）或接管即豁免。新回合开始立即 flush 待决
     * close（per-user 并发=1，须为下个 browser_open 腾出席位）。
     */
    fun settleBrowserSessionsAtTurnEnd() {
        val sessions = _liveSessions.value
        if (sessions.isEmpty()) return
        sessions.values
            .filter { it.state != ToolPartState.OUTPUT_AVAILABLE && it.state != ToolPartState.OUTPUT_ERROR }
            .filter { it.sessionId !in userControlledSessions }
            .forEach { part -> scheduleBrowserSettleClose(part.sessionId) }
    }

    private fun scheduleBrowserSettleClose(sessionId: String) {
        settleJobs[sessionId]?.cancel()
        settleJobs[sessionId] = scope.launch {
            delay(BROWSER_SETTLE_GRACE_MS)
            settleJobs.remove(sessionId)
            withContext(Dispatchers.IO) { closeBrowserSessionIfIdle(sessionId) }
        }
    }

    /** 新回合开始 flush 待决宽限 close（取消延迟立即判定，为下个 browser_open 腾出并发席位）。 */
    fun flushBrowserSettleCloses() {
        settleJobs.keys.toList().forEach { sessionId ->
            settleJobs.remove(sessionId)?.cancel()
            scope.launch(Dispatchers.IO) { closeBrowserSessionIfIdle(sessionId) }
        }
    }

    /**
     * 宽限期满/观看页退出/新回合 flush 的统一 close 判定：仍非终态且未被接管/观看才 close，
     * 按 [onBrowserSessionClosed] 同路径定格落库并从 live 态移除。close 失败静默：
     * 服务端 2 分钟空闲回收兜底（spec §6）。
     */
    private suspend fun closeBrowserSessionIfIdle(sessionId: String) {
        val part = _liveSessions.value[sessionId] ?: return
        if (part.state == ToolPartState.OUTPUT_AVAILABLE || part.state == ToolPartState.OUTPUT_ERROR) return
        if (sessionId in userControlledSessions || sessionId in watchedSessions) return
        val transport = capabilityProvider()?.transport
        val result = try {
            transport?.close(sessionId)
        } catch (e: CancellationException) {
            // 结构化并发：VM 清理/协程取消原样上抛，不折叠为静默失败
            throw e
        } catch (e: Exception) {
            null
        }
        // actionCount 为 bridge 语义（不含 open）；close 失败回退时把本地口径
        // （open=1 起累计）减 1 换算成 bridge 口径，经 onBrowserSessionClosed 的
        // +1 还原为本地值，避免兜底路径虚增一步。
        onBrowserSessionClosed(sessionId, result?.lastGoodFrame, result?.actionCount ?: (part.actionCount - 1))
        _liveSessions.update { current -> current - sessionId }
    }
}
