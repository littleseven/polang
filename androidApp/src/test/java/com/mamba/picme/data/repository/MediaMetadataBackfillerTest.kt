package com.mamba.picme.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaMetadataBackfiller 核心循环测试（Room v29 media_assets.relativePath/sizeBytes
 * 存量回填，Agent 清理 M1）：三函数注入纯 JVM 可测，语义不变式——只补 null 行、
 * provider 缺失（MediaStore 已删）不写、每批攒齐单次批量写、返回实际补写行数。
 */
class MediaMetadataBackfillerTest {

    @Test
    fun `runUntilDone fills only rows missing metadata and stops when no candidates`() = runTest {
        val writes = mutableListOf<List<Pair<String, Pair<String?, Long>>>>()
        val backfiller = MediaMetadataBackfiller(
            candidateUris = { limit -> listOf("uri://a", "uri://b") },
            metaProvider = { uris -> uris.associateWith { u -> "DCIM/Camera" to 1024L } },
            writer = { batch -> writes.add(batch) },
        )
        assertEquals(2, backfiller.runUntilDone(batchLimit = 500))
        // 单批一次批量写（生产 = MediaDao 单事务），行集语义与逐行写一致
        assertEquals(listOf("uri://a", "uri://b"), writes.single().map { w -> w.first })
    }

    @Test
    fun `meta missing from provider leaves row untouched but counts as attempted`() = runTest {
        val writes = mutableListOf<List<Pair<String, Pair<String?, Long>>>>()
        val backfiller = MediaMetadataBackfiller(
            candidateUris = { listOf("uri://gone") },
            metaProvider = { emptyMap() },
            writer = { batch -> writes.add(batch) },
        )
        assertEquals(0, backfiller.runUntilDone())
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `runUntilDone continues with new uris across batches`() = runTest {
        val allUris = listOf("uri://1", "uri://2", "uri://3")
        var served = 0
        val writes = mutableListOf<List<Pair<String, Pair<String?, Long>>>>()
        val backfiller = MediaMetadataBackfiller(
            candidateUris = { limit ->
                val chunk = allUris.drop(served).take(limit)
                served += chunk.size
                chunk
            },
            metaProvider = { uris -> uris.associateWith { u -> "DCIM/Camera" to 1024L } },
            writer = { batch -> writes.add(batch) },
        )

        assertEquals(3, backfiller.runUntilDone(batchLimit = 2))
        // batchLimit=2：两批各一次批量写（[1,2] / [3]），第三次候选为空即停
        assertEquals(listOf(listOf("uri://1", "uri://2"), listOf("uri://3")), writes.map { batch -> batch.map { w -> w.first } })
    }
}
