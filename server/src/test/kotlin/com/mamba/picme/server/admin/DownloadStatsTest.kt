package com.mamba.picme.server.admin

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.TelemetryEvents
import com.mamba.picme.server.routes.TELEMETRY_TYPE_GALLERY_SCAN
import com.mamba.picme.server.routes.TELEMETRY_TYPE_MODELS_READY
import com.mamba.picme.server.routes.TELEMETRY_TYPE_MODEL_DOWNLOAD
import com.mamba.picme.server.util.TestDb
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStatsTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `parseModelDownloadEvent parses a complete payload`() {
        val ev = parseModelDownloadEvent(
            """{"modelId":"florence2_base","outcome":"failed","errorClass":"integrity",""" +
                """"bytes":278936295,"durationMs":61000,"networkType":"wifi","region":"CN",""" +
                """"appVersion":"0.9.52","channel":"release","source":"modelscope"}""",
        )
        assertNotNull(ev)
        ev!!
        assertEquals("florence2_base", ev.modelId)
        assertEquals("failed", ev.outcome)
        assertEquals("integrity", ev.errorClass)
        assertEquals(278936295L, ev.bytes)
        assertEquals(61000L, ev.durationMs)
        assertEquals("wifi", ev.networkType)
        assertEquals("CN", ev.region)
        assertEquals("modelscope", ev.source)
    }

    @Test
    fun `parseModelDownloadEvent rejects missing modelId or outcome and malformed json`() {
        assertNull(parseModelDownloadEvent(null))
        assertNull(parseModelDownloadEvent(""))
        assertNull(parseModelDownloadEvent("not json"))
        assertNull(parseModelDownloadEvent("""{"outcome":"completed"}"""))
        assertNull(parseModelDownloadEvent("""{"modelId":"x"}"""))
    }

    @Test
    fun `parseModelDownloadEvent defaults region and networkType when absent`() {
        val ev = parseModelDownloadEvent("""{"modelId":"m","outcome":"completed"}""")
        assertNotNull(ev)
        assertEquals("unknown", ev!!.region)
        assertEquals("other", ev.networkType)
        assertNull(ev.errorClass)
        assertNull(ev.source) // 旧客户端事件无 source 字段
    }

    @Test
    fun `modelDownloadStats groups by region and computes success rate and speed`() = runBlocking {
        TestDb.init(TelemetryEvents)
        // CN：2 成功（100MB/50s = 2048KB/s，200MB/100s = 2048KB/s）+ 1 完整性失败 + 1 取消
        insert(now, """{"modelId":"a","outcome":"completed","bytes":104857600,"durationMs":50000,"region":"CN"}""")
        insert(now, """{"modelId":"b","outcome":"completed","bytes":209715200,"durationMs":100000,"region":"CN"}""")
        insert(now, """{"modelId":"a","outcome":"failed","errorClass":"integrity","region":"CN"}""")
        insert(now, """{"modelId":"a","outcome":"cancelled","region":"CN"}""")
        // US：1 失败（http）
        insert(now, """{"modelId":"a","outcome":"failed","errorClass":"http","region":"US"}""")
        // 窗口外（40 天前）不参与 30 天统计
        insert(now - 40L * 86_400_000, """{"modelId":"a","outcome":"completed","region":"CN"}""")
        // 非 model_download 类型不参与
        insert(now, """{"foo":1}""", type = "other_event")

        val rows = AdminQueries.modelDownloadStats(days = 30, now = now)
        assertEquals(2, rows.size)

        val cn = rows.first { it.region == "CN" }
        assertEquals(2L, cn.completed)
        assertEquals(1L, cn.failed)
        assertEquals(1L, cn.cancelled)
        assertEquals(1L, cn.integrityFailed)
        assertEquals(2.0 / 3.0, cn.successRate, 0.0001)
        assertEquals(2048.0, cn.avgSpeedKbps, 1.0)

        val us = rows.first { it.region == "US" }
        assertEquals(0L, us.completed)
        assertEquals(1L, us.failed)
        assertEquals(0L, us.integrityFailed)
        assertEquals(0.0, us.successRate, 0.0001)
        assertEquals(0.0, us.avgSpeedKbps, 0.0001)
    }

    @Test
    fun `modelDownloadStatsByModel groups by model and region with duration`() = runBlocking {
        TestDb.init(TelemetryEvents)
        // model a × CN：2 成功（50s + 100s → 平均 75s；速度均 2048KB/s；大小取 max bytes；双源）+ 1 失败
        insert(now, """{"modelId":"a","outcome":"completed","bytes":104857600,"durationMs":50000,"region":"CN","source":"modelscope"}""")
        insert(now, """{"modelId":"a","outcome":"completed","bytes":209715200,"durationMs":100000,"region":"CN","source":"hf-mirror"}""")
        insert(now, """{"modelId":"a","outcome":"failed","errorClass":"http","region":"CN"}""")
        // model a × US：1 成功（10s）
        insert(now, """{"modelId":"a","outcome":"completed","bytes":10485760,"durationMs":10000,"region":"US","source":"modelscope"}""")
        // model b × CN：1 取消（不计入成功率分母；无 bytes/source → 大小 0、源为空）
        insert(now, """{"modelId":"b","outcome":"cancelled","region":"CN"}""")

        val rows = AdminQueries.modelDownloadStatsByModel(days = 30, now = now)
        assertEquals(3, rows.size)
        // 排序：modelId 升序，同模型按量级降序
        assertEquals(listOf("a" to "CN", "a" to "US", "b" to "CN"), rows.map { it.modelId to it.region })

        val aCn = rows[0]
        assertEquals(2L, aCn.completed)
        assertEquals(1L, aCn.failed)
        assertEquals(2.0 / 3.0, aCn.successRate, 0.0001)
        assertEquals(75000L, aCn.avgDurationMs)
        assertEquals(2048.0, aCn.avgSpeedKbps, 1.0)
        assertEquals(209715200L, aCn.sizeBytes)
        assertEquals(listOf("hf-mirror", "modelscope"), aCn.sources)

        val aUs = rows[1]
        assertEquals(10000L, aUs.avgDurationMs)
        assertEquals(1.0, aUs.successRate, 0.0001)
        assertEquals(10485760L, aUs.sizeBytes)
        assertEquals(listOf("modelscope"), aUs.sources)

        val bCn = rows[2]
        assertEquals(0L, bCn.completed)
        assertEquals(1L, bCn.cancelled)
        assertEquals(0L, bCn.avgDurationMs)
        assertEquals(0L, bCn.sizeBytes)
        assertEquals(emptyList<String>(), bCn.sources)
    }

    @Test
    fun `telemetryFunnel counts devices per stage with conversion`() = runBlocking {
        TestDb.init(TelemetryEvents)
        // dev-1：完整走通漏斗（下载 → 就绪 → 扫描启动 → 扫描完成）
        insert(now, """{"modelId":"a","outcome":"completed"}""", type = TELEMETRY_TYPE_MODEL_DOWNLOAD, deviceId = "dev-1")
        insert(now, """{"requiredCount":7}""", type = TELEMETRY_TYPE_MODELS_READY, deviceId = "dev-1")
        insert(now, """{"phase":"started","total":500}""", type = TELEMETRY_TYPE_GALLERY_SCAN, deviceId = "dev-1")
        insert(now, """{"phase":"completed","processed":500,"total":500}""", type = TELEMETRY_TYPE_GALLERY_SCAN, deviceId = "dev-1")
        // dev-2：卡在模型关（下载过但未就绪）
        insert(now, """{"modelId":"a","outcome":"failed"}""", type = TELEMETRY_TYPE_MODEL_DOWNLOAD, deviceId = "dev-2")
        // dev-3：就绪但没扫起来
        insert(now, """{"modelId":"a","outcome":"completed"}""", type = TELEMETRY_TYPE_MODEL_DOWNLOAD, deviceId = "dev-3")
        insert(now, """{"requiredCount":7}""", type = TELEMETRY_TYPE_MODELS_READY, deviceId = "dev-3")
        // 无 deviceId 的历史行不参与漏斗
        insert(now, """{"modelId":"a","outcome":"completed"}""", type = TELEMETRY_TYPE_MODEL_DOWNLOAD)

        val funnel = AdminQueries.telemetryFunnel(days = 30, now = now)
        assertEquals(4, funnel.size)
        assertEquals(3L, funnel[0].devices)   // download
        assertEquals(null, funnel[0].conversionFromPrev)
        assertEquals(2L, funnel[1].devices)   // ready
        assertEquals(2.0 / 3.0, funnel[1].conversionFromPrev!!, 0.0001)
        assertEquals(1L, funnel[2].devices)   // scan_started
        assertEquals(0.5, funnel[2].conversionFromPrev!!, 0.0001)
        assertEquals(1L, funnel[3].devices)   // scan_completed
        assertEquals(1.0, funnel[3].conversionFromPrev!!, 0.0001)
    }

    @Test
    fun `downloadStatsPage renders funnel region rows and empty state`() {
        val rows = listOf(
            DownloadRegionStatRow("CN", completed = 9, failed = 1, cancelled = 2, integrityFailed = 1, avgSpeedKbps = 2048.0),
        )
        val modelRows = listOf(
            DownloadModelStatRow("qwen3_vl_2b", "CN", completed = 8, failed = 1, cancelled = 0, avgDurationMs = 61000, avgSpeedKbps = 2048.0, sizeBytes = 209715200, sources = listOf("modelscope")),
        )
        val funnel = listOf(
            FunnelRow("download", "开始下载模型", 100, null),
            FunnelRow("ready", "必备模型就绪", 60, 0.6),
        )
        val html = AdminViews.downloadStatsPage(rows, modelRows, funnel, days = 30)
        assertTrue(html.contains("模型下载遥测"))
        assertTrue(html.contains("转化漏斗"))
        assertTrue(html.contains("开始下载模型"))
        assertTrue(html.contains("60.0%"))
        assertTrue(html.contains("CN"))
        assertTrue(html.contains("90.0%"))
        assertTrue(html.contains("2048 KB/s"))
        assertTrue(html.contains("分模型 × 分地区"))
        assertTrue(html.contains("qwen3_vl_2b"))
        assertTrue(html.contains("61.0 s"))
        assertTrue(html.contains("200.00 MB"))
        assertTrue(html.contains("modelscope"))

        val empty = AdminViews.downloadStatsPage(emptyList(), emptyList(), emptyList(), days = 30)
        assertTrue(empty.contains("暂无下载遥测数据"))
        assertTrue(empty.contains("暂无分模型数据"))
    }

    private fun insert(at: Long, payload: String, type: String = TELEMETRY_TYPE_MODEL_DOWNLOAD, deviceId: String? = null) {
        transaction(Db.instance) {
            TelemetryEvents.insert {
                it[TelemetryEvents.type] = type
                it[payloadJson] = payload
                it[TelemetryEvents.deviceId] = deviceId
                it[createdAt] = at
            }
        }
    }
}
