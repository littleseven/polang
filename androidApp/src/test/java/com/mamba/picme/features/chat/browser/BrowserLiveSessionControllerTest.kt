package com.mamba.picme.features.chat.browser

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.R
import com.mamba.picme.agent.core.capability.BrowserSessionCapability
import com.mamba.picme.agent.core.capability.BrowserTransport
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.decodePartsOrLegacy
import com.mamba.picme.domain.browser.BrowserActionRequest
import com.mamba.picme.domain.browser.BrowserActionResult
import com.mamba.picme.domain.browser.BrowserFrameResult
import com.mamba.picme.domain.browser.BrowserStatus
import com.mamba.picme.domain.chat.MessagePart
import com.mamba.picme.domain.chat.ToolPartState
import com.mamba.picme.domain.chat.streaming.TurnPartsReducer
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [BrowserLiveSessionController] 单测（GLM review 🟡 跟进；Robolectric + Room 内存库真实 DAO
 * + kotlinx-coroutines-test 虚拟时间驱动 90s 宽限期）。
 *
 * 覆盖四类时序用例：
 * 1. turn 结束 settle → 90s 宽限到点才 close 定格落库；
 * 2. 宽限期内 watch 豁免（远超宽限期也不 close）；
 * 3. 新回合 flush 立即判定（不推进虚拟时间即 close）；
 * 4. actionCount 口径换算 maxOf(本地累计, bridge N + open 1 步)。
 *
 * 补充：onBrowserSessionFailed 转 OUTPUT_ERROR + feedToolError、终态会话不进 settle 调度。
 *
 * 时序要点（两个坑，写给后来者）：
 * - controller 内部 withContext(Dispatchers.IO) 在真实 IO 线程执行，continuation 落回
 *   TestScope 调度器才可见——断言用 [pumpUntil] 轮询（runCurrent 泵送 + 真实短等）。
 * - runTest 的后台 worker 会在测试体挂起时把调度器 advanceUntilIdle：宽限 delay 在途期间
 *   任何挂起点（含 Room suspend 查询）都会把虚拟时间烧到 90s——「宽限期内」断言必须全
 *   非挂起（不落库由「未调 close」+落库只发生在 close 之后共同保证，不再直查 DB）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowserLiveSessionControllerTest {

    /** 记录调用的假传输层（close/settle 在 IO 线程执行，须线程安全）。 */
    private class FakeBrowserTransport(var closeResult: BrowserActionResult) : BrowserTransport {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        override suspend fun open(url: String, wantFrame: Boolean): BrowserActionResult {
            calls += "open:$url"
            return BrowserActionResult(status = BrowserStatus.OK, sessionId = "unused")
        }
        override suspend fun action(request: BrowserActionRequest): BrowserActionResult {
            calls += "action:${request.sessionId}:${request.action}"
            return BrowserActionResult(status = BrowserStatus.OK, sessionId = request.sessionId)
        }
        override suspend fun frame(sessionId: String): BrowserFrameResult {
            calls += "frame:$sessionId"
            // 无帧返回：watch 回退轮询只登记调用、不更新 live 态
            return BrowserFrameResult(status = BrowserStatus.OK, sessionId = sessionId)
        }
        override suspend fun close(sessionId: String): BrowserActionResult {
            calls += "close:$sessionId"
            return closeResult
        }
    }

    private companion object {
        const val SESSION_ID = "chat-1"
        const val JUST_BEFORE_GRACE_MS = 89_999L
        const val WELL_PAST_GRACE_MS = 180_000L
        const val PUMP_TIMEOUT_MS = 5_000L
        const val PUMP_INTERVAL_MS = 25L
        const val REAL_WAIT_MS = 200L
    }

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val toolOutputs = Collections.synchronizedList(mutableListOf<String>())
    private val toolErrors = Collections.synchronizedList(mutableListOf<Pair<String, String>>())

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 预热：Robolectric 首次 Room 查询触发 SQLite 初始化（秒级），移出计时敏感段
        runBlocking { db.chatMessageDao().getMessageCount("warmup") }
    }

    @After
    fun teardown() {
        db.close()
    }

    private fun TestScope.newController(transport: FakeBrowserTransport): BrowserLiveSessionController =
        BrowserLiveSessionController(
            scope = backgroundScope,
            browserWebSocketClient = null, // watch 走 HTTP 轮询回退（单测场景）
            capabilityProvider = { BrowserSessionCapability(transport) },
            chatMessageDao = db.chatMessageDao(),
            currentSessionId = { SESSION_ID },
            stringContext = { context },
            isTurnInFlight = { false },
            stopGeneration = { },
            feedToolOutput = { toolName, _ -> toolOutputs += toolName },
            feedToolError = { toolName, errorText -> toolErrors += toolName to errorText },
        )

    /** 造一个 running 态 live 会话（open 后本地 actionCount=1，state=INPUT_AVAILABLE）。 */
    private fun startLiveSession(controller: BrowserLiveSessionController, sessionId: String = "s1") {
        controller.onBrowserSessionStarted(
            sessionId = sessionId,
            url = "https://example.com",
            title = "Example",
            frameJpegBase64 = "frame-open",
        )
    }

    /** 本地累计造到 3 步（open + click + type），供 actionCount 口径断言。 */
    private fun accumulateThreeLocalActions(controller: BrowserLiveSessionController) {
        startLiveSession(controller)
        controller.onBrowserSessionAction(
            sessionId = "s1", action = "click", selector = null, text = null,
            url = "https://example.com/a", title = "A", frameJpegBase64 = "f1",
        )
        controller.onBrowserSessionAction(
            sessionId = "s1", action = "type", selector = null, text = "hi",
            url = "https://example.com/b", title = "B", frameJpegBase64 = "f2",
        )
    }

    /**
     * IO/Room 真线程完成后的 continuation 才回排 TestScope 调度器：轮询断言条件，
     * 间隙 runCurrent 泵送调度器 + 真实 delay 让 IO 落地（虚拟 delay 对真线程无效）。
     */
    private suspend fun TestScope.pumpUntil(
        timeoutMs: Long = PUMP_TIMEOUT_MS,
        condition: suspend () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "pumpUntil 条件在 ${timeoutMs}ms 内未满足" }
            testScheduler.runCurrent()
            withContext(Dispatchers.Default) { delay(PUMP_INTERVAL_MS) }
        }
        testScheduler.runCurrent()
    }

    private suspend fun persistedBrowserCard(): MessagePart.BrowserLive =
        db.chatMessageDao().getRecentMessages(SESSION_ID, limit = 1)
            .single()
            .decodePartsOrLegacy()
            .filterIsInstance<MessagePart.BrowserLive>()
            .single()

    @Test
    fun `settle close fires at grace deadline and persists frozen card`() = runTest {
        val transport = FakeBrowserTransport(
            closeResult = BrowserActionResult(status = BrowserStatus.OK, actionCount = 0, lastGoodFrame = "final-frame"),
        )
        val controller = newController(transport)
        startLiveSession(controller)

        controller.settleBrowserSessionsAtTurnEnd()
        testScheduler.runCurrent() // settle 调度启动，delay(90s) 起算
        assertEquals("open 占位回填进流式轨", listOf(TurnPartsReducer.TOOL_BROWSER_OPEN), toolOutputs)

        // 宽限期内断言全部非挂起：挂起点会让 runTest worker 自动把虚拟时间烧到 90s（见类注释）
        testScheduler.advanceTimeBy(JUST_BEFORE_GRACE_MS)
        testScheduler.runCurrent()
        assertTrue("宽限期内不 close", transport.calls.none { call -> call.startsWith("close") })
        assertTrue("宽限期内 live 态保留", controller.liveSessions.value.containsKey("s1"))

        testScheduler.advanceTimeBy(1) // 到点
        pumpUntil { transport.calls.any { call -> call.startsWith("close") } }
        pumpUntil { db.chatMessageDao().getMessageCount(SESSION_ID) == 1 }
        assertFalse("定格后 live 态移除", controller.liveSessions.value.containsKey("s1"))

        val part = persistedBrowserCard()
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, part.state)
        assertEquals("final-frame", part.frameJpegBase64)
        assertEquals("close 回执 0 步 + open 1 步（纯 open 不显示 0 步）", 1, part.actionCount)
    }

    @Test
    fun `watching during grace period exempts session from settle close`() = runTest {
        val transport = FakeBrowserTransport(closeResult = BrowserActionResult(status = BrowserStatus.OK))
        val controller = newController(transport)
        startLiveSession(controller)

        controller.settleBrowserSessionsAtTurnEnd()
        testScheduler.runCurrent()
        controller.watchBrowserSession("s1") // 宽限期内进卡观看 → 豁免（取消宽限任务 + watched 集合）

        testScheduler.advanceTimeBy(WELL_PAST_GRACE_MS)
        testScheduler.runCurrent()
        withContext(Dispatchers.Default) { delay(REAL_WAIT_MS) } // 给漏网 IO 一点真实时间
        testScheduler.runCurrent()

        assertTrue("观看豁免：远超宽限期也不 close", transport.calls.none { call -> call.startsWith("close") })
        assertTrue("会话保留在 live 态供继续观看", controller.liveSessions.value.containsKey("s1"))
        assertEquals("豁免期不落库", 0, db.chatMessageDao().getMessageCount(SESSION_ID))
    }

    @Test
    fun `flushBrowserSettleCloses closes immediately without waiting for grace period`() = runTest {
        val transport = FakeBrowserTransport(
            closeResult = BrowserActionResult(status = BrowserStatus.OK, actionCount = 0, lastGoodFrame = "flush-frame"),
        )
        val controller = newController(transport)
        startLiveSession(controller)

        controller.settleBrowserSessionsAtTurnEnd()
        testScheduler.runCurrent() // 宽限任务在 delay 中挂起
        controller.flushBrowserSettleCloses()

        pumpUntil { transport.calls.any { call -> call.startsWith("close") } }
        pumpUntil { db.chatMessageDao().getMessageCount(SESSION_ID) == 1 }
        assertEquals("虚拟时间未推进即完成 close（不等 90s 宽限）", 0L, testScheduler.currentTime)
        assertFalse("定格后 live 态移除", controller.liveSessions.value.containsKey("s1"))

        val part = persistedBrowserCard()
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, part.state)
        assertEquals("flush-frame", part.frameJpegBase64)
    }

    @Test
    fun `frozen actionCount keeps larger local tally when bridge count is lower`() = runTest {
        val transport = FakeBrowserTransport(closeResult = BrowserActionResult(status = BrowserStatus.OK))
        val controller = newController(transport)
        accumulateThreeLocalActions(controller) // 本地累计 3（open=1 起）

        controller.onBrowserSessionClosed(sessionId = "s1", finalFrameJpegBase64 = "final-frame", actionCount = 1)

        pumpUntil { db.chatMessageDao().getMessageCount(SESSION_ID) == 1 }
        val part = persistedBrowserCard()
        assertEquals("本地累计 3 > bridge 1 + open 1 步", 3, part.actionCount)
        assertEquals(context.getString(R.string.browser_live_done_summary, 3), part.resultSummary)
        assertEquals(ToolPartState.OUTPUT_AVAILABLE, part.state)
    }

    @Test
    fun `frozen actionCount takes bridge count plus open when larger`() = runTest {
        val transport = FakeBrowserTransport(closeResult = BrowserActionResult(status = BrowserStatus.OK))
        val controller = newController(transport)
        accumulateThreeLocalActions(controller) // 本地累计 3（open=1 起）

        controller.onBrowserSessionClosed(sessionId = "s1", finalFrameJpegBase64 = "final-frame", actionCount = 5)

        pumpUntil { db.chatMessageDao().getMessageCount(SESSION_ID) == 1 }
        val part = persistedBrowserCard()
        assertEquals("bridge 5 + open 1 步 > 本地累计 3", 6, part.actionCount)
        assertEquals(context.getString(R.string.browser_live_done_summary, 6), part.resultSummary)
    }

    @Test
    fun `onBrowserSessionFailed marks session error state and feeds tool error`() = runTest {
        val transport = FakeBrowserTransport(closeResult = BrowserActionResult(status = BrowserStatus.OK))
        val controller = newController(transport)
        startLiveSession(controller)

        controller.onBrowserSessionFailed(sessionId = "s1", reason = "bridge lost")
        runCurrent()

        val live = controller.liveSessions.value["s1"]
        assertEquals(ToolPartState.OUTPUT_ERROR, live?.state)
        assertEquals("bridge lost", live?.errorReason)
        assertEquals("失败进流式轨（browser_open 占位闭环）", listOf(TurnPartsReducer.TOOL_BROWSER_OPEN to "bridge lost"), toolErrors)
    }

    @Test
    fun `settle skips sessions already in terminal error state`() = runTest {
        val transport = FakeBrowserTransport(closeResult = BrowserActionResult(status = BrowserStatus.OK))
        val controller = newController(transport)
        startLiveSession(controller)
        controller.onBrowserSessionFailed(sessionId = "s1", reason = "boom") // → OUTPUT_ERROR 终态

        controller.settleBrowserSessionsAtTurnEnd()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(WELL_PAST_GRACE_MS)
        testScheduler.runCurrent()
        withContext(Dispatchers.Default) { delay(REAL_WAIT_MS) }
        testScheduler.runCurrent()

        assertTrue("终态会话不调度兜底 close", transport.calls.none { call -> call.startsWith("close") })
        assertEquals("终态会话不经 settle 落库（定格落库走 close 通路）", 0, db.chatMessageDao().getMessageCount(SESSION_ID))
    }
}
