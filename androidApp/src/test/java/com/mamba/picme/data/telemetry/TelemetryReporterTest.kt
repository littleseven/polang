package com.mamba.picme.data.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.data.remote.picme.TelemetrySender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [TelemetryReporter] / [TelemetryEventBuffer] 单测：转化漏斗事件采集。
 *
 * 关键口径：
 * - 失败事件必须落本地缓冲（差网络发不出去是常态，直发会让失败率系统性偏低）
 * - flush 成功才清缓冲；无网络/无 deviceId/发送失败都保留事件
 * - models_ready 每安装只报一次
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryReporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var bufferFile: File

    @Before
    fun setUp() {
        bufferFile = File(context.cacheDir, "test-telemetry/${System.nanoTime()}/pending.jsonl")
        context.getSharedPreferences("telemetry_flags", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private class FakeSender(var succeed: Boolean = true) : TelemetrySender {
        val sentBatches = mutableListOf<Pair<List<String>, String>>()
        override suspend fun send(eventLines: List<String>, deviceId: String): Result<Unit> {
            sentBatches.add(eventLines to deviceId)
            return if (succeed) Result.success(Unit) else Result.failure(RuntimeException("boom"))
        }
    }

    private fun reporter(
        sender: TelemetrySender,
        deviceId: String? = "dev-test",
        networkAvailable: Boolean = true,
    ) = TelemetryReporter(
        appContext = context,
        deviceIdProvider = { deviceId },
        appVersion = "0.9.52",
        channel = "debug",
        client = sender,
        buffer = TelemetryEventBuffer(bufferFile),
        scope = CoroutineScope(Dispatchers.IO),
        networkTypeProvider = { "wifi" },
        networkAvailableProvider = { networkAvailable },
        regionProvider = { "CN" },
        timeZoneProvider = { "Asia/Shanghai" },
    )

    // ── 缓冲 ────────────────────────────────────────────────

    @Test
    fun `buffer appends reads and removes prefix`() {
        val buffer = TelemetryEventBuffer(bufferFile)
        buffer.append("""{"n":1}""")
        buffer.append("""{"n":2}""")
        buffer.append("""{"n":3}""")
        assertEquals(3, buffer.readAll().size)
        buffer.removeFirst(2)
        assertEquals(listOf("""{"n":3}"""), buffer.readAll())
        buffer.removeFirst(99)
        assertEquals(emptyList<String>(), buffer.readAll())
        assertTrue(!bufferFile.exists())
    }

    @Test
    fun `buffer drops oldest when over capacity`() {
        val buffer = TelemetryEventBuffer(bufferFile, maxEvents = 3)
        repeat(5) { buffer.append("""{"n":$it}""") }
        val lines = buffer.readAll()
        assertEquals(3, lines.size)
        assertTrue(lines.first().contains("\"n\":2"))
        assertTrue(lines.last().contains("\"n\":4"))
    }

    // ── 事件构建 ────────────────────────────────────────────

    @Test
    fun `reportModelFailed buffers event with error class and context fields`() {
        val sender = FakeSender()
        val reporter = reporter(sender, networkAvailable = false)
        reporter.reportModelFailed("florence2_base", bytes = 278936295, durationMs = 61000, errorClass = "integrity", resumed = false)

        val lines = TelemetryEventBuffer(bufferFile).readAll()
        assertEquals(1, lines.size)
        val event = JSONObject(lines.first())
        assertEquals(TelemetryReporter.EVENT_TYPE_MODEL_DOWNLOAD, event.getString("type"))
        val payload = event.getJSONObject("payload")
        assertEquals("florence2_base", payload.getString("modelId"))
        assertEquals("failed", payload.getString("outcome"))
        assertEquals("integrity", payload.getString("errorClass"))
        assertEquals("CN", payload.getString("region"))
        assertEquals("wifi", payload.getString("networkType"))
        assertEquals("0.9.52", payload.getString("appVersion"))
        assertEquals("android", payload.getString("platform"))
    }

    @Test
    fun `reportModelsReadyOnce reports only once per install`() {
        val reporter = reporter(FakeSender(), networkAvailable = false)
        reporter.reportModelsReadyOnce(7)
        reporter.reportModelsReadyOnce(7)

        val lines = TelemetryEventBuffer(bufferFile).readAll()
        assertEquals(1, lines.size)
        assertEquals(TelemetryReporter.EVENT_TYPE_MODELS_READY, JSONObject(lines.first()).getString("type"))
    }

    // ── flush 语义 ──────────────────────────────────────────

    @Test
    fun `flush clears buffer only on success`() = runBlocking {
        val sender = FakeSender(succeed = true)
        val reporter = reporter(sender, networkAvailable = false)
        reporter.reportModelCompleted("m", bytes = 100, durationMs = 10, resumed = false)
        assertEquals(1, TelemetryEventBuffer(bufferFile).readAll().size)

        reporter.flushPending() // 无网络：保留
        assertEquals(1, TelemetryEventBuffer(bufferFile).readAll().size)
        assertEquals(0, sender.sentBatches.size)

        val onlineReporter = reporter(sender)
        onlineReporter.flushPending() // 成功：清空
        assertEquals(1, sender.sentBatches.size)
        assertEquals("dev-test", sender.sentBatches.first().second)
        assertEquals(0, TelemetryEventBuffer(bufferFile).readAll().size)
    }

    @Test
    fun `flush retains events on send failure or missing deviceId`() = runBlocking {
        val failing = FakeSender(succeed = false)
        reporter(failing).flushPending()
        assertEquals(0, failing.sentBatches.size) // 空缓冲不发

        val reporter = reporter(failing)
        reporter.reportScanStarted(total = 500)
        reporter.flushPending()
        assertEquals(1, failing.sentBatches.size) // 尝试过
        assertEquals(1, TelemetryEventBuffer(bufferFile).readAll().size) // 失败保留

        val noId = reporter(FakeSender(), deviceId = null)
        noId.flushPending()
        assertEquals(1, TelemetryEventBuffer(bufferFile).readAll().size) // 无 deviceId 不发
    }

    @Test
    fun `flush retains events when deviceId is blank`() = runBlocking {
        val sender = FakeSender()
        val reporter = reporter(sender, deviceId = "  ")
        reporter.reportScanStarted(total = 100)
        reporter.flushPending()
        assertEquals(0, sender.sentBatches.size) // 空串 deviceId 不发
        assertEquals(1, TelemetryEventBuffer(bufferFile).readAll().size) // 事件保留待下次
    }
}
