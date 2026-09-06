package com.mamba.picme.domain.organize

import com.mamba.picme.core.common.PerceptualHash

/**
 * dedup_hash 缓存 → 重复组成员信息（纯函数）：
 * 精确组 = 同 MD5 且成员 ≥2；相似组 = pHash 汉明距离 ≤ [PerceptualHash.SIMILAR_HAMMING_THRESHOLD]
 * 并查集聚类且成员 ≥2（与去重 2.0 VISUAL 同一阈值口径）。
 * 输入仅依赖 data 层投影出的 uri/md5/phash 三元组，不依赖 Room 实体（保持 domain 纯净）。
 * 前置：[inputs] 的 uri 唯一（dedup_hash 表 PK 保证）；重复 uri 会被静默折叠。
 */
object DuplicateGrouper {

    data class HashInput(val uri: String, val md5: String?, val phash: Long?)

    data class DupInfo(
        val exactGroupSize: Int = 0,
        val similarGroupSize: Int = 0,
        /** 精确组标识（组内共享 MD5）；仅 exactGroupSize ≥ 2 时非空，供聚合侧按组去重扣 keeper。 */
        val exactGroupKey: String? = null,
    )

    fun group(inputs: List<HashInput>): Map<String, DupInfo> {
        val exactByUri = inputs
            .filter { input -> input.md5 != null }
            .groupBy { input -> input.md5!! }
            .filterValues { group -> group.size >= 2 }
            .flatMap { (md5, group) -> group.map { input -> input.uri to (group.size to md5) } }
            .toMap()

        val phashUris = inputs.mapNotNull { input ->
            input.phash?.let { phash -> input.uri to phash }
        }
        val similarSizeByUri = mutableMapOf<String, Int>()
        if (phashUris.size >= 2) {
            // clusterByHamming 返回成员下标簇（仅保留 size≥2 的组），按下标回映 uri
            val clusters = PerceptualHash.clusterByHamming(
                hashes = phashUris.map { pair -> pair.second },
                threshold = PerceptualHash.SIMILAR_HAMMING_THRESHOLD,
            )
            clusters.forEach { cluster ->
                cluster.forEach { index ->
                    similarSizeByUri[phashUris[index].first] = cluster.size
                }
            }
        }

        return inputs.associate { input ->
            val exact = exactByUri[input.uri]
            input.uri to DupInfo(
                exactGroupSize = exact?.first ?: 0,
                similarGroupSize = similarSizeByUri[input.uri] ?: 0,
                exactGroupKey = exact?.second,
            )
        }
    }
}
