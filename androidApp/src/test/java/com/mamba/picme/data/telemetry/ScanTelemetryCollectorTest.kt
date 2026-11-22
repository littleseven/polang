package com.mamba.picme.data.telemetry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.data.remote.picme.TelemetrySender
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * [ScanTelemetryCollector] 单测：扫描会话边界检测（转化漏斗「扫描关」）。
 * 只对会话边界（RUNNING 首帧 / 终态帧）出事件，中间进度帧不出。
 */
@RunWith(RobolectricTestRunner::class)
class ScanTelemetryCollectorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private inner class CollectorFixture(vararg frames: TagScanSessionProgress) {
        private val frames: Array<out TagScanSessionProgress> = frames
        val bufferFile = File(
            context.cacheDir, "test-scan-telemetry/${System.nanoTime()}/pending.jsonl",
        )
        val reporter = TelemetryReporter(
            appContext = context,
            deviceIdProvider = { "dev-test" },
            appVersion = "0.9.52",
            channel = "debug",
            client = object : TelemetrySender {
                override suspend fun send(eventLines: List<String>, deviceId: String) =
                    Result.failure<Unit>(RuntimeException("offline"))
            },
            buffer = TelemetryEventBuffer(bufferFile),
            scope = CoroutineScope(Dispatchers.IO),
            networkAvailableProvider = { false },
        )

        fun runFrames() {
            ScanTelemetryCollector(
                reporter = reporter,
                scope = CoroutineScope(Dispatchers.Unconfined),
                progressFlow = flowOf(*frames),
            ).start()
        }

        fun events(): List<JSONObject> =
            TelemetryEventBuffer(bufferFile).readAll().map { JSONObject(it) }
    }

    private fun progress(
        session: String,
        state: ScanSessionState,
        processed: Int = 0,
        total: Int = 500,
        failed: Int = 0,
    ) = TagScanSessionProgress(
        sessionId = session, state = state, processed = processed, total = total, failed = failed,
    )

    @Test
    fun `running then completed emits started and terminal events`() = runBlocking {
        val f = CollectorFixture(
            progress("s1", ScanSessionState.RUNNING),
            progress("s1", ScanSessionState.RUNNING, processed = 250),
            progress("s1", ScanSessionState.COMPLETED, processed = 500, failed = 3),
        )
        f.runFrames()

        val events = f.events()
        assertEquals(2, events.size)
        assertEquals("started", events[0].getJSONObject("payload").getString("phase"))
        assertEquals(500, events[0].getJSONObject("payload").getInt("total"))
        val terminal = events[1].getJSONObject("payload")
        assertEquals("completed", terminal.getString("phase"))
        assertEquals(500, terminal.getInt("processed"))
        assertEquals(3, terminal.getInt("failed"))
    }

    @Test
    fun `repeated running frames emit only one started event`() {
        val f = CollectorFixture(
            progress("s1", ScanSessionState.RUNNING),
            progress("s1", ScanSessionState.RUNNING, processed = 10),
            progress("s1", ScanSessionState.RUNNING, processed = 20),
        )
        f.runFrames()
        assertEquals(1, f.events().size)
    }

    @Test
    fun `terminal frame without matching session is ignored`() {
        val f = CollectorFixture(progress("s1", ScanSessionState.COMPLETED, processed = 500))
        f.runFrames()
        assertEquals(0, f.events().size)
    }

    @Test
    fun `cancelled session emits cancelled terminal event`() {
        val f = CollectorFixture(
            progress("s1", ScanSessionState.RUNNING),
            progress("s1", ScanSessionState.CANCELLED, processed = 120),
        )
        f.runFrames()
        val events = f.events()
        assertEquals(2, events.size)
        assertEquals("cancelled", events[1].getJSONObject("payload").getString("phase"))
    }
}
