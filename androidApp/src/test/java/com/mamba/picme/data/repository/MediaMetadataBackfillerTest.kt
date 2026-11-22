package com.mamba.picme.data.repository

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MediaMetadataBackfiller 核心循环测试（Room v29 media_assets.relativePath/sizeBytes
 * 存量回填，Agent 清理 M1）：三函数注入纯 JVM 可测，语义不变式——只补 null 行、
 * provider 缺失（MediaStore 已删）不写、返回实际补写行数。
 */
class MediaMetadataBackfillerTest {

    @Test
    fun `runUntilDone fills only rows missing metadata and stops when no candidates`() = runTest {
        val writes = mutableListOf<Pair<String, Pair<String?, Long>>>()
        val backfiller = MediaMetadataBackfiller(
            candidateUris = { limit -> listOf("uri://a", "uri://b") },
            metaProvider = { uris -> uris.associateWith { u -> "DCIM/Camera" to 1024L } },
            writer = { uri, meta -> writes.add(uri to meta) },
        )
        assertEquals(2, backfiller.runUntilDone(batchLimit = 500))
        assertEquals(listOf("uri://a", "uri://b"), writes.map { w -> w.first })
    }

    @Test
    fun `meta missing from provider leaves row untouched but counts as attempted`() = runTest {
        val writes = mutableListOf<Pair<String, Pair<String?, Long>>>()
        val backfiller = MediaMetadataBackfiller(
            candidateUris = { listOf("uri://gone") },
            metaProvider = { emptyMap() },
            writer = { uri, meta -> writes.add(uri to meta) },
        )
        assertEquals(0, backfiller.runUntilDone())
        assertTrue(writes.isEmpty())
    }
}
