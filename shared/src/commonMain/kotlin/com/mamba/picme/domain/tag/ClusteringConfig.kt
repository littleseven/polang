package com.mamba.picme.domain.tag

/**
 * 人脸聚类统一配置常量
 *
 * 所有人脸聚类相关的阈值、参数均定义在此处，
 * [FaceClusterEngine]、[TagGenerationScheduler]
 * 等使用者统一引用此配置，确保参数一致性。
 *
 * ─── 参数说明 ─────────────────────────────────────────
 * COSINE_THRESHOLD : 流式匹配的余弦相似度下限（越大越严格）
 * DBSCAN_EPS       : DBSCAN 余弦距离上限（= 1 - 相似度，越小越严格）
 * CLUSTER_COHESION_MIN : 簇内平均相似度下限（低于此值则分裂）
 *
 * 当前值: 相似度 ≥ 0.65 / 距离 ≤ 0.35，适用于 Glint360K R100 512 维 embedding
 * ──────────────────────────────────────────────────────
 */
object ClusteringConfig {

    /** 聚类策略切换：true 使用方案 B（密度自适应 MST/HDBSCAN），false 使用方案 A（DBSCAN） */
    const val USE_ADAPTIVE_CLUSTERING = true

    /** 余弦相似度阈值：高于此值归入已有簇（越接近 1.0 越严格）
     *  0.65：保守阈值，优先保证「不同人不并成一组」，再靠 [MERGE_SMALL_CLUSTER_SIMILARITY_THRESHOLD]
     *  把小簇碎片合并回来。 */
    const val COSINE_THRESHOLD = 0.65f

    /** 跨簇合并 pass 的质心相似度阈值：高于此值才把两个 person 合并（不限簇大小）。
     *  与 COSINE_THRESHOLD 同口径（系统「同一人」判定）。 */
    const val MERGE_SIMILARITY_THRESHOLD = 0.65f

    /**
     * 小簇碎片合并：两个簇的 embedding 数均 ≤ [MERGE_SMALL_CLUSTER_MAX_SIZE] 时，
     * 使用更宽松的 [MERGE_SMALL_CLUSTER_SIMILARITY_THRESHOLD] 进行合并。
     *
     * 目的：修复「同一人因表情/角度/光线导致 embedding 距离略远，被拆成多个 1~4 张的小分组」
     *（如用户反馈的 #1647 / #1628）。只对小簇放宽，避免把两个大簇（如「大宝」和「老郭」）误合并。
     */
    const val MERGE_SMALL_CLUSTER_SIMILARITY_THRESHOLD = 0.50f
    const val MERGE_SMALL_CLUSTER_MAX_SIZE = 4

    /** 拆分 pass：把疑似「两个人被并成一组」的簇切成两个 person 的判定阈值。
     *  簇内用最远两点做种子分两半，仅当「两半各自内聚 ≥ SPLIT_INTRA_MIN 且互相交叉 ≤ SPLIT_CROSS_MAX」才拆。 */
    /** 两半互相交叉相似度上限（≤ 此值才视为两个不同的人）。0.45：高于不同人交叉上限(~0.42)。 */
    const val SPLIT_CROSS_MAX = 0.45f
    /** 每半内部平均相似度下限（≥ 此值才视为内聚子团，避免拆出噪声/误裁剪）。 */
    const val SPLIT_INTRA_MIN = 0.55f
    /** 仅对 embedding 数 ≥ 此值的簇尝试拆分（保证两半各 ≥2）。 */
    const val SPLIT_MIN_CLUSTER_SIZE = 4

    /** sink(链式垃圾簇) 判定：簇内 median 两两相似度 < 此值视为 k-NN 链式并出的垃圾簇，解散重分。
     *  0.40：同人 median 通常 ≥0.5；<0.40 基本是随机不相关脸（链式 sink）。 */
    const val SINK_COHESION_MAX = 0.40f

    /** 仅对 embedding 数 ≥ 此值的簇判定 sink（小簇不判，避免误伤）。 */
    const val SINK_MIN_SIZE = 8

    /** sink 判定时为控制开销，对大簇最多采样多少 embedding 算 median。 */
    const val SINK_SAMPLE_CAP = 30

    /** DBSCAN: 余弦距离阈值（= 1 - 相似度，越小越严格）
     *  0.35：与 COSINE_THRESHOLD=0.65 对齐，相似度 ≥ 0.65 才成簇 */
    const val DBSCAN_EPS = 0.35f

    /** DBSCAN: 最小邻居数（≥2 形成核心点）
     *  降为 2：让照片较少的明星/低频人物也能成簇 */
    const val DBSCAN_MIN_PTS = 2

    /** 簇内部平均相似度下限（< 此值则递归分裂）
     *  0.35：对松散大簇进行二次分裂 */
    const val CLUSTER_COHESION_MIN = 0.35f

    /** 增量积累达到此数量后触发全量 DBSCAN 重聚 */
    const val RE_CLUSTER_THRESHOLD = 100

    /** Pass1 流式攒批聚类：每累计多少张「含人脸图」触发一次增量归类。
     *  20：大相册里人物在远小于「整轮 Pass1」的时间内即可出现。 */
    const val STREAMING_CLUSTER_BATCH = 20

    // ═══════════════════════════════════════════════════
    //  方案 B：密度自适应 k-NN 图连通分量聚类
    // ═══════════════════════════════════════════════════

    /** k-NN 邻居数 k。
     *  越小簇越紧凑（可能漏召），越大越连通（可能混组）。
     *  与 [KNN_MIN_SIMILARITY] 配合使用：阈值收紧后，k 过大反而把弱相关样本拉进簇。
     *  当前经验值 2：在保持召回的同时抑制跨组桥接。 */
    const val KNN_K = 4

    /** k-NN 建边最小余弦相似度（= 1 - eps）。
     *  与 [COSINE_THRESHOLD] / [MERGE_SIMILARITY_THRESHOLD] 对齐（0.65）。 */
    const val KNN_MIN_SIMILARITY = 0.65f

    /** 方案 B 最小簇大小，小于此值的连通分量视为噪声。
     *  与 DBSCAN_MIN_PTS 保持一致的语义：≥2 张人脸才成人物簇。 */
    const val KNN_MIN_CLUSTER_SIZE = 2

    /**
     * 单次聚类分块上限（2026-10-02 OOM 修复）：未分配 embedding 积压超过此值时
     * 按 flatIndex 顺序分块聚类，块间以全人物质心快照续接（同人簇跨块复用既有 person）。
     * 4000 ≈ 单块图构建 O(n²) 相似度 ~8GFLOP + 内存 ~30MB，256MB 堆安全；
     * 配合 buildKnnGraph 去装箱化（原 filter/sortedByDescending 每节点 n 个装箱 Integer）。
     */
    const val KNN_CHUNK_SIZE = 4000

    /** 全量重聚类时，新簇与旧命名人物质心的最小余弦相似度。
     *  高于此值则认为新旧簇为同一人，复用 personId 与 name。 */
    const val NAME_PRESERVE_MIN_SIMILARITY = 0.65f
}

