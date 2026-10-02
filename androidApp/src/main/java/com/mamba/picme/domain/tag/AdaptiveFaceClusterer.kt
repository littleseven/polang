package com.mamba.picme.domain.tag

import android.util.Log
import kotlin.math.sqrt

/**
 * 人脸聚类方案 B：基于 k-NN 图的密度自适应聚类。
 *
 * 相比方案 A（DBSCAN 单一全局 eps），本方案的核心优势：
 * - 不需要为全部样本指定同一个密度阈值；
 * - 通过每个样本的局部 k 近邻结构自然适应密集簇与稀疏簇；
 * - 对 embedding 质量波动（同一人不同照片相似度差异大）更鲁棒。
 *
 * 算法流程：
 * 1. 为每个样本选取 k 个最近邻；
 * 2. 仅保留相似度 ≥ minSimilarity 的邻居边；
 * 3. 在得到的无向图中求连通分量；
 * 4. 大小 ≥ minClusterSize 的连通分量作为人物簇，其余标记为噪声。
 *
 * 该方法等价于「相似度阈值约束的 k-NN 图连通分量聚类」，
 * 是 DBSCAN / HDBSCAN 家族在 face embedding 场景下的实用简化变体。
 */
object AdaptiveFaceClusterer {

    private const val TAG = "AdaptiveCluster"

    /**
     * 执行密度自适应聚类。
     *
     * @param embeddingsMap mediaId -> 该媒体下各人脸 embedding 列表
     * @param flatIndex 待聚类的 (mediaId, faceIndex) 索引列表
     * @param k 每个节点的最近邻数量
     * @param minSimilarity 建边最小余弦相似度（= 1 - eps）
     * @param minClusterSize 最小簇大小，小于此值的连通分量视为噪声
     * @return 聚类结果：clusterKey -> 成员列表。key = -1 表示噪声。
     */
    fun cluster(
        embeddingsMap: Map<Long, List<FloatArray>>,
        flatIndex: List<Pair<Long, Int>>,
        k: Int = ClusteringConfig.KNN_K,
        minSimilarity: Float = ClusteringConfig.KNN_MIN_SIMILARITY,
        minClusterSize: Int = ClusteringConfig.KNN_MIN_CLUSTER_SIZE
    ): Map<Int, List<Pair<Long, Int>>> {
        val n = flatIndex.size
        if (n == 0) return emptyMap()
        if (n == 1) {
            return if (minClusterSize <= 1) mapOf(0 to flatIndex) else mapOf(-1 to flatIndex)
        }

        // 1. 提取展平 embedding 列表
        val embeddings = flatIndex.map { (mediaId, faceIdx) ->
            embeddingsMap[mediaId]?.getOrNull(faceIdx)
                ?: throw IllegalArgumentException("Missing embedding for $mediaId/$faceIdx")
        }

        // 2. 计算两两余弦相似度，并构建 k-NN 邻接表
        val adjacency = buildKnnGraph(embeddings, k, minSimilarity)

        // 3. 连通分量（迭代 DFS）
        val visited = BooleanArray(n)
        val labels = IntArray(n) { -1 }
        var nextLabel = 0

        for (start in 0 until n) {
            if (visited[start]) continue
            val component = mutableListOf<Int>()
            val stack = mutableListOf(start)
            visited[start] = true
            while (stack.isNotEmpty()) {
                val node = stack.removeAt(stack.lastIndex)
                component.add(node)
                for (neighbor in adjacency[node]) {
                    if (!visited[neighbor]) {
                        visited[neighbor] = true
                        stack.add(neighbor)
                    }
                }
            }
            if (component.size >= minClusterSize) {
                for (node in component) {
                    labels[node] = nextLabel
                }
                nextLabel++
            }
        }

        // 4. 映射回 (mediaId, faceIndex)
        val result = mutableMapOf<Int, MutableList<Pair<Long, Int>>>()
        for (i in labels.indices) {
            val key = labels[i]
            result.getOrPut(key) { mutableListOf() }.add(flatIndex[i])
        }
        val noiseCount = result[-1]?.size ?: 0
        val clusterCount = result.keys.count { it != -1 }
        Log.i(TAG, "Adaptive k-NN clustering done: $clusterCount clusters, $noiseCount/${n} noise")
        return result
    }

    /**
     * 构建 k-NN 无向图。
     * 对每个节点保留与其最相似的 k 个邻居，且相似度 ≥ minSimilarity 才建边。
     *
     * 2026-10-02 OOM 修复：全原生数组实现——原 `(0 until n).filter{}.sortedByDescending{}`
     * 每节点物化 ~n 个装箱 Integer（n=万级时亿级分配churn 直接打爆 256MB 堆），
     * 现改为单条 FloatArray 相似度 + 长度 k 的插入选择 + 两遍 IntArray 邻接表。
     */
    private fun buildKnnGraph(
        embeddings: List<FloatArray>,
        k: Int,
        minSimilarity: Float
    ): Array<IntArray> {
        val n = embeddings.size
        val effectiveK = k.coerceIn(1, n - 1)

        // 有向 top-k 槽位：节点 i 的邻居存于 [i*effectiveK, (i+1)*effectiveK)
        val slots = IntArray(n * effectiveK) { -1 }
        val similarities = FloatArray(n)
        val kIdx = IntArray(effectiveK) { -1 }
        val kSim = FloatArray(effectiveK) { -2f }

        for (i in 0 until n) {
            for (j in 0 until n) {
                similarities[j] = if (i == j) -1f else cosineSimilarity(embeddings[i], embeddings[j])
            }
            // 插入排序维护降序 top-k（k 为个位数常数，代价可忽略；零装箱）
            java.util.Arrays.fill(kIdx, -1)
            java.util.Arrays.fill(kSim, -2f)
            var m = 0
            for (j in 0 until n) {
                val s = similarities[j]
                if (s < minSimilarity) continue
                if (m < effectiveK) {
                    var p = m++
                    while (p > 0 && kSim[p - 1] < s) {
                        kSim[p] = kSim[p - 1]
                        kIdx[p] = kIdx[p - 1]
                        p--
                    }
                    kSim[p] = s
                    kIdx[p] = j
                } else if (s > kSim[effectiveK - 1]) {
                    var p = effectiveK - 1
                    while (p > 0 && kSim[p - 1] < s) {
                        kSim[p] = kSim[p - 1]
                        kIdx[p] = kIdx[p - 1]
                        p--
                    }
                    kSim[p] = s
                    kIdx[p] = j
                }
            }
            for (t in 0 until effectiveK) {
                slots[i * effectiveK + t] = kIdx[t]
            }
        }

        // 两遍建无向邻接（正向 + 反向边；重复边由 DFS visited 去重，语义同旧实现）
        val degree = IntArray(n)
        for (i in 0 until n) {
            for (t in 0 until effectiveK) {
                if (slots[i * effectiveK + t] >= 0) {
                    degree[i]++
                    degree[slots[i * effectiveK + t]]++
                }
            }
        }
        val adjacency = Array(n) { IntArray(degree[it]) }
        val cursor = IntArray(n)
        for (i in 0 until n) {
            for (t in 0 until effectiveK) {
                val j = slots[i * effectiveK + t]
                if (j >= 0) {
                    adjacency[i][cursor[i]++] = j
                    adjacency[j][cursor[j]++] = i
                }
            }
        }
        return adjacency
    }

    /**
     * 余弦相似度 [0, 1]
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }
}
