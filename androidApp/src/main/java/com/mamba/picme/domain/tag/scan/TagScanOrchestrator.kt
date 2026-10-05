package com.mamba.picme.domain.tag.scan

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.dao.StatusCount
import com.mamba.picme.data.local.entity.TagScanPass
import com.mamba.picme.data.local.entity.TagScanTaskEntity
import com.mamba.picme.data.local.entity.TagScanTaskStatus
import com.mamba.picme.domain.tag.TagCategory
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tagscan.ScanPassWeights
import com.mamba.picme.domain.tagscan.ScanTaskProgress
import com.mamba.picme.domain.tagscan.computeScanTaskProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * TAG 扫描编排器
 *
 * 负责：
 * - 创建并持久化扫描任务队列
 * - 维护扫描会话状态机（Idle / Running / Paused / Cancelled）
 * - 轮询任务并调用 TagGenerationScheduler 执行原子任务
 * - 提供增强进度反馈
 * - 支持暂停、恢复、取消、失败重试
 */
class TagScanOrchestrator(
    private val context: Context,
    private val scheduler: TagGenerationScheduler,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val db: AppDatabase = AppDatabase.getDatabase(context)
) {

    companion object {
        private const val TAG = "TagScanOrchestrator"

        /** 轮询任务间隔 */
        private const val POLL_INTERVAL_MS = 100L

        /** 每个 Pass 的历史耗时窗口：用于估算剩余时间 */
        private const val ESTIMATE_WINDOW_SIZE = 20

        /** 无样本时的默认单任务耗时（ms），避免冷启动 ETA 跳变 */
        private val DEFAULT_PASS_DURATION_MS = mapOf(
            TagScanPass.FACE_DETECTION to 800L,
            TagScanPass.DBSCAN to 5_000L,
            TagScanPass.IMAGE_TAGGING to 7_000L,
            TagScanPass.MOBILE_CLIP_ENCODING to 1_000L
        )

        /** 清理已完成任务的最小保留时间 */
        private const val CLEANUP_RETENTION_MS = 7 * 24 * 60 * 60 * 1000L

        /** 失败重试退避基数 */
        private const val RETRY_BACKOFF_BASE_MS = 5 * 60 * 1000L

        /** 查询过滤时分批加载 MediaEntity 的批量大小，防止 Java Heap OOM */
        private const val QUERY_FILTER_BATCH_SIZE = 100

        /**
         * 判断最近一次扫描覆盖的 Pass 是否包含所有请求的 Pass
         */
        fun isPassesCovered(lastTagScanPasses: String?, requested: Set<String>): Boolean {
            if (requested.isEmpty()) return true
            if (lastTagScanPasses.isNullOrBlank()) return false
            val content = lastTagScanPasses.trim()
            if (content == "{}") return false
            // 使用简单字符串查找：pass 键以 "<number>" 形式出现在 JSON 中，值是数字时间戳，不会误命中。
            // 避免在循环中反复实例化 Regex，也避免依赖 Android 的 org.json stub。
            return requested.all { pass ->
                content.contains("\"$pass\"")
            }
        }

        /**
         * 计算用于「单媒体覆盖判定」的 Pass 编号集合（供 [scheduleAutoScan] 过滤候选）。
         *
         * **必须剔除 DBSCAN**：DBSCAN 是全局任务（[createTasks] 里 mediaId=-1），
         * [maybeUpdateMediaScanRecord] 对 DBSCAN 直接 return → 单媒体 lastTagScanPasses 永不写 "2"。
         * 若把 "2" 纳入 [isPassesCovered]，所有照片恒为「未覆盖」，4h 避重窗口一过期就被重选
         * → Pass 1 死循环；候选为空时又误判「全量完成」提前进入 deferredPasses（第二/三步）。
         */
        fun perMediaCoveragePassNumbers(passes: List<TagScanPass>): Set<String> =
            passes.filter { it != TagScanPass.DBSCAN }
                .map { it.toPassNumber() }
                .toSet()

        /**
         * 计算自动扫描的阶段切换策略。
         *
         * - [ScanQueuePolicy.deferredPasses] 非空：返回第二阶段 policy（passes=deferredPasses，
         *   deferredPasses 清空）。第二阶段再调用必返回 null，保证只会切换一次（防死循环）。
         * - [ScanQueuePolicy.deferredPasses] 为空：返回 null，表示无后续阶段。
         */
        fun nextPhasePolicy(policy: ScanQueuePolicy): ScanQueuePolicy? {
            if (policy.deferredPasses.isEmpty()) return null
            return policy.copy(
                passes = policy.deferredPasses,
                deferredPasses = emptyList()
            )
        }

        /**
         * Pass 阶段 → 数字编号（与 media_assets.lastTagScanPasses 约定一致）
         */
        fun TagScanPass.toPassNumber(): String = when (this) {
            TagScanPass.FACE_DETECTION -> "1"
            TagScanPass.DBSCAN -> "2"
            TagScanPass.IMAGE_TAGGING -> "3"
            TagScanPass.MOBILE_CLIP_ENCODING -> "4"
        }

        /**
         * 统一数据库统计快照
         *
         * 不依赖 [TagScanOrchestrator] 实例，供 UI/Service 直接使用，确保口径一致。
         * - [remainingForPass1]：尚未进行人脸检测/MobileCLIP 编码的媒体数
         * - [remainingForPass3]：尚未生成图像标签的媒体数（不强制要求已有 faceRoiResult，与 Pass 1 解耦）
         *
         * 注意：所有统计均使用 COUNT 查询并在 IO 调度器执行，避免一次性加载大量 [MediaEntity]
         * 到 Java Heap 导致 OOM（MediaEntity 包含 faceRoiResult/semanticEmbedding 等大字段）。
         */
        suspend fun getDbStats(db: AppDatabase): TagScanDbStats = withContext(Dispatchers.IO) {
            val totalMedia = db.mediaDao().getTotalCount()
            val withFace = db.mediaDao().getHasFaceCount()
            val withSemantic = db.mediaDao().getMediaWithSemanticEmbeddingCount()
            // Pass 3 剩余/已打标均为照片口径：视频永不进打标流水线（loadBitmap 被 MIME 拦截，
            // labels 写不进去），计入会永久垫底导致进度永不收敛 100%。
            val unlabeledCount = db.mediaDao().getUnlabeledPhotoCount()
            val photoCount = db.mediaDao().getPhotoCount()
            val withLabels = photoCount - unlabeledCount
            val personCount = db.personDao().getPersonCount()
            val faceEmbeddingCount = db.personDao().getAllEmbeddingCount()
            val remainingForPass1 = db.mediaDao().getMediaWithoutFaceRoiCount()
            // Pass 3 剩余独立统计：所有无 labels 的照片，不强制要求已有 faceRoiResult
            val remainingForPass3 = unlabeledCount
            val namedPersonCount = db.personDao().getNamedPersonCount()
            val aestheticScoredCount = db.mediaDao().getAestheticScoredCount()
            val cityCount = db.mediaDao().getDistinctCityCount()
            val groupPhotoCount = db.personDao().getGroupPhotoMediaIds().size
            val selfPhotoCount = db.personDao().getSelfPersonMediaIds().size

            TagScanDbStats(
                totalMedia = totalMedia,
                withFace = withFace,
                withLabels = withLabels,
                withSemantic = withSemantic,
                personCount = personCount,
                namedPersonCount = namedPersonCount,
                faceEmbeddingCount = faceEmbeddingCount,
                remainingForPass1 = remainingForPass1,
                remainingForPass3 = remainingForPass3,
                photoCount = photoCount,
                aestheticScoredCount = aestheticScoredCount,
                cityCount = cityCount,
                groupPhotoCount = groupPhotoCount,
                selfPhotoCount = selfPhotoCount
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var currentJob: Job? = null

    private val _progress = MutableStateFlow<TagScanSessionProgress?>(null)
    val progress: StateFlow<TagScanSessionProgress?> = _progress.asStateFlow()

    private val sessionMutex = Mutex()
    private var activeSessionId: String? = null

    /** 扫描期间持有 partial wake lock，防止息屏后 CPU 休眠导致任务挂起 */
    private val wakeLockLazy = lazy {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PoLang:TagScanWakeLock").apply {
            setReferenceCounted(false)
        }
    }
    private val wakeLock: PowerManager.WakeLock by wakeLockLazy

    /**
     * Service onDestroy 安全网：强制释放 wakeLock。
     *
     * 正常路径由 runSession 的 finally 释放，但 onDestroy 先取消 dispatcher 时
     * finally 所在协程可能不再被调度，导致 setReferenceCounted(false) 的 wakeLock 残留。
     */
    fun releaseWakeLockIfHeld() {
        if (!wakeLockLazy.isInitialized()) return
        releaseWakeLock()
    }

    /** 每个 Pass 最近 N 次任务耗时，用于估算剩余时间 */
    private val recentDurationsMs = mutableMapOf<TagScanPass, ArrayDeque<Long>>()

    /** 当前会话消息历史 */
    private val sessionMessages = mutableListOf<ScanMessage>()

    /** 当前会话中被标记为全量重跑的 Pass 集合，供 executeTask 读取 */
    private val fullRescanPasses = mutableSetOf<TagScanPass>()

    /** Pass1 流式攒批聚类累加器（跨批次保留；全量重扫时复位）。 */
    private val streamingAccumulator = StreamingClusterAccumulator()
    /** 自上次全量重聚类以来流式归类的 embedding 数（阶段末判定用）。 */
    private var embeddingsSinceDbscan = 0

    /**
     * 自动扫描会话的策略缓存：sessionId -> ScanQueuePolicy。
     * 用于当前批次完成后自动调度下一批；手动触发的会话不缓存，避免意外连锁。
     */
    private val sessionPolicies = mutableMapOf<String, ScanQueuePolicy>()

    // ── 任务域加权进度（2026-10-05 口径立法，公式见 shared ScanProgressCalculator）──
    // 自动 sweep 基线：sweep 启动时全库待办快照（照片口径，含未入队积压）。
    // 链式批次/阶段切换共享同一基线，分母不随 50 张一批的会话切分归零（消灭锯齿波）；
    // 扫描中途新增照片不进入本轮分母（当前剩余按基线钳制）。
    // -1 = 无 sweep 基线（手动会话 / 进程重启后回退任务表口径）。
    private var sweepBaselineFace = -1
    private var sweepBaselineTagging = -1
    /** sweep 内单调钳制：删除照片等边界不回退 */
    private var sweepLastFraction: Float? = null
    /** 媒体表剩余计数节流缓存（≥1s），避免每任务两次 COUNT 全表扫描 */
    private var sweepLastRefreshMs = 0L
    private var sweepCachedRemainingFace = 0
    private var sweepCachedRemainingTagging = 0

    private fun resetSweepBaseline() {
        sweepBaselineFace = -1
        sweepBaselineTagging = -1
        sweepLastFraction = null
        sweepLastRefreshMs = 0L
    }

    /** 当前各 Pass 单张预估耗时权重（实测中位数，冷启动默认值兜底）。 */
    fun currentPassWeights(): ScanPassWeights = ScanPassWeights(
        faceDetectionMs = estimatePassDurationMs(TagScanPass.FACE_DETECTION),
        imageTaggingMs = estimatePassDurationMs(TagScanPass.IMAGE_TAGGING),
    )

    init {
        // 启动时恢复被异常中断的 RUNNING 任务
        scope.launch {
            db.tagScanTaskDao().resetRunningToPending()
            maybeResumeOnStartup()
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  公开 API：调度扫描
    // ═══════════════════════════════════════════════════════════

    /**
     * 自动增量扫描：按策略生成任务队列并启动
     *
     * 核心去重规则：
     * 1. 跳过最近一次全量扫描在 [skipRecentlyTaggedMs] 窗口内且覆盖所有请求 Pass 的媒体
     * 2. 失败项默认 24h 后才允许自动重试
     * 3. 按 [order] 排序，默认 newest-first 优先处理新拍摄/新添加的照片
     */
    suspend fun scheduleAutoScan(policy: ScanQueuePolicy = ScanQueuePolicy()): String =
        scheduleAutoScan(policy, continueSweep = false)

    private suspend fun scheduleAutoScan(policy: ScanQueuePolicy, continueSweep: Boolean): String {
        val sessionId = newSessionId()
        logInfo(sessionId, "开始自动增量扫描: $policy")

        if (!continueSweep) {
            // 新 sweep：快照全库待办基线（照片口径，含未入队积压）。
            // 只计入本 policy 覆盖的 Pass，不覆盖的 Pass 留在分母外，进度才能收敛 100%。
            val coversFace = policy.passes.contains(TagScanPass.FACE_DETECTION) ||
                policy.deferredPasses.contains(TagScanPass.FACE_DETECTION)
            val coversTagging = policy.passes.contains(TagScanPass.IMAGE_TAGGING) ||
                policy.deferredPasses.contains(TagScanPass.IMAGE_TAGGING)
            sweepBaselineFace = if (coversFace) db.mediaDao().getMediaWithoutFaceRoiCount() else 0
            sweepBaselineTagging = if (coversTagging) db.mediaDao().getUnlabeledPhotoCount() else 0
            sweepLastFraction = null
            sweepLastRefreshMs = 0L
        }

        val before = System.currentTimeMillis() - policy.skipRecentlyTaggedMs
        // 覆盖判定只认单媒体 Pass（剔除 DBSCAN 全局任务），见 [perMediaCoveragePassNumbers]。
        val requestedPassNumbers = perMediaCoveragePassNumbers(policy.passes)

        // 按排序策略从数据库拉取轻量候选（仅 id + lastTagScanPasses），
        // 避免一次性加载 faceRoiResult/semanticEmbedding 等大字段到 Java Heap。
        // 覆盖过滤下推 SQL（单 pass 相位）：窗口先剔除已完扫再取 newest——否则窗口被
        // 已完扫媒体占满时老媒体永远进不来（饿死，见 MediaDao passPattern 注释）；
        // 多 pass 组合（手动）无单一 pattern，走下方内存 isPassesCovered 兜底。
        val sqlPassPattern = requestedPassNumbers.singleOrNull()?.let { pass -> "%\"$pass\"%" }
        val projections = when (policy.order) {
            QueueOrder.OLDEST_FIRST -> db.mediaDao().getMediaForIncrementalScanOldestProjection(before, policy.maxBatchSize * 2, sqlPassPattern)
            QueueOrder.NEWEST_FIRST -> db.mediaDao().getMediaForIncrementalScanNewestProjection(before, policy.maxBatchSize * 2, sqlPassPattern)
        }
        val filteredIds = projections
            .filter { !isPassesCovered(it.lastTagScanPasses, requestedPassNumbers) }
            .take(policy.maxBatchSize)
            .map { it.id }

        if (filteredIds.isEmpty()) {
            // 当前阶段（含 DBSCAN 的 Pass1+Pass2）全量完成：跑一次全量重聚类，纠正流式碎片/错分。
            // 必须 isFullRescan=true，否则 runDbscanClustering 只处理 unassigned embeddings，
            // 流式聚类已分配的错分无法被纠正，导致同一人被拆成多个重复分组。
            if (policy.passes.contains(TagScanPass.DBSCAN) &&
                DbscanRefinementPolicy.shouldRunRefinement(embeddingsSinceDbscan, isFinalBatch = true)
            ) {
                logInfo(sessionId, "阶段末全量重聚类")
                scheduler.executeDbscan(preserveNamedPersons = true, isFullRescan = true)
                embeddingsSinceDbscan = 0
            }
            // 第一阶段全量完成：若有延迟阶段，切换到第二阶段（递归一层，第二阶段 deferredPasses 已空 → 不会死循环）
            val nextPolicy = nextPhasePolicy(policy)
            if (nextPolicy != null) {
                logInfo(sessionId, "延迟阶段切换: ${policy.passes} 全量完成 → 进入 ${nextPolicy.passes}")
                return scheduleAutoScan(nextPolicy, continueSweep = true)
            }
            logInfo(sessionId, "没有需要增量扫描的媒体")
            resetSweepBaseline()
            _progress.value = TagScanSessionProgress(
                sessionId = sessionId,
                state = ScanSessionState.COMPLETED,
                messages = listOf(ScanMessage(level = MessageLevel.INFO, text = "没有需要扫描的媒体"))
            )
            return sessionId
        }

        // createTasks 只需 mediaId；不再加载完整 MediaEntity（含 faceRoiResult/semanticEmbedding 大字段），降低 Heap 峰值。
        createTasks(sessionId, filteredIds, TagCategory.ALL, policy.passes, policy)
        sessionPolicies[sessionId] = policy
        startSession(sessionId)
        return sessionId
    }

    /**
     * 对指定媒体重新生成/增量生成指定类别标签
     *
     * 手动触发不受 [ScanQueuePolicy.skipRecentlyTaggedMs] 时间窗口限制。
     */
    suspend fun scheduleRegenerate(
        mediaIds: List<Long>,
        categories: Set<TagCategory> = TagCategory.ALL,
        mode: ScanMode = ScanMode.FULL,
        policy: ScanQueuePolicy = ScanQueuePolicy()
    ): String {
        val sessionId = newSessionId()
        logInfo(sessionId, "scheduleRegenerate: ${mediaIds.size} 张, categories=$categories, mode=$mode")

        val passes = TagCategory.toPasses(categories)
        val entities = db.mediaDao().getMediaByIds(mediaIds)
        val filteredIds = if (mode == ScanMode.INCREMENTAL) {
            entities.filter { !hasAllCategories(it, categories) }.map { it.id }
        } else {
            entities.map { it.id }
        }

        if (filteredIds.isEmpty()) {
            _progress.value = TagScanSessionProgress(
                sessionId = sessionId,
                state = ScanSessionState.COMPLETED,
                messages = listOf(ScanMessage(level = MessageLevel.INFO, text = "没有需要处理的媒体"))
            )
            return sessionId
        }

        createTasks(sessionId, filteredIds, categories, passes, policy)
        startSession(sessionId)
        return sessionId
    }

    /**
     * 按查询条件批量生成 / 重生成
     *
     * 实现注意：为避免一次性加载全部 [MediaEntity]（含大字段 faceRoiResult/semanticEmbedding）
     * 导致 Java Heap OOM，先加载所有 ID，再分批加载实体进行过滤。
     */
    suspend fun scheduleRegenerateByQuery(
        query: TagScanQuery,
        categories: Set<TagCategory> = TagCategory.ALL,
        mode: ScanMode = ScanMode.FULL
    ): String {
        val sessionId = newSessionId()
        logInfo(sessionId, "scheduleRegenerateByQuery: $query, categories=$categories, mode=$mode")

        val allIds = db.mediaDao().getAllMediaIds()
        val filteredIds = filterMediaIdsByQuery(allIds, query)

        if (filteredIds.isEmpty()) {
            _progress.value = TagScanSessionProgress(
                sessionId = sessionId,
                state = ScanSessionState.COMPLETED,
                messages = listOf(ScanMessage(level = MessageLevel.INFO, text = "没有需要处理的媒体"))
            )
            return sessionId
        }

        return scheduleRegenerate(filteredIds, categories, mode)
    }

    /**
     * 执行单个 Pass 阶段（用于兼容旧的 Pass 1/2/3 独立控制按钮）
     *
     * ## 增量模式行为（INCREMENTAL）
     * - **Pass 1**：仅处理 `faceRoiResult IS NULL` 的媒体（未执行人脸检测），含 MobileCLIP 语义编码内联
     * - **Pass 3**：仅处理 `labels IS NULL` 的媒体（未生成标签）
     * - **MobileCLIP 语义编码**：仅处理 `semanticEmbedding IS NULL` 的媒体（单独重编码场景）
     * - **Pass 2**：始终执行全局 DBSCAN（增量 embedding 自动参与）
     *
     * ## 全量模式行为（FULL）
     * - 清空对应阶段旧数据后全量重跑
     *
     * 实现注意：为避免一次性加载全部 [MediaEntity] 导致 Java Heap OOM，先加载所有 ID，
     * 再分批加载实体进行过滤。
     */
    suspend fun schedulePass(
        pass: TagScanPass,
        query: TagScanQuery = TagScanQuery(),
        mode: ScanMode = ScanMode.INCREMENTAL,
        policy: ScanQueuePolicy = ScanQueuePolicy()
    ): String {
        val sessionId = newSessionId()
        logInfo(sessionId, "schedulePass: $pass, mode=$mode")

        if (mode == ScanMode.FULL) {
            fullRescanPasses += pass
            if (pass == TagScanPass.FACE_DETECTION) {
                streamingAccumulator.reset()
                embeddingsSinceDbscan = 0
            }
        }

        val allIds = db.mediaDao().getAllMediaIds()
        var ids = filterMediaIdsByQuery(allIds, query)

        // 人脸检测 / 图像打标 / 语义编码 只适用于照片：FULL 与 INCREMENTAL 都排除视频，
        // 否则 FULL 重跑会把视频当作可处理项（loadBitmap 对非图片返回 null → 结果列永不写入 → 永不收敛）。
        if (pass != TagScanPass.DBSCAN) {
            ids = filterPhotosOnly(ids)
        }

        if (pass == TagScanPass.FACE_DETECTION && mode == ScanMode.FULL) {
            // 全量重跑 Pass 1：清空媒体端的人脸标记。
            // 不删除 face_embeddings 与 persons，使后续 Pass 2 全量重聚类时还能基于旧 embedding
            // 计算命名人物质心，从而复用人名。
            db.mediaDao().resetAllFaceData()
        }

        if (pass == TagScanPass.DBSCAN && mode == ScanMode.FULL) {
            // 全量重跑 Pass 2：由 scheduler.executeDbscan(isFullRescan=true) 负责
            // 先保存命名人物质心快照，再清空旧人物簇及媒体上的 faceId，最后重新聚类。
            // 这里仅做标记，实际清空操作在 executeDbscan 中按快照捕获后执行。
        }

        if (pass == TagScanPass.IMAGE_TAGGING && mode == ScanMode.FULL) {
            // 全量重跑 Pass 3：清空已有标签
            db.mediaDao().resetAllLabels()
        }

        if (pass == TagScanPass.MOBILE_CLIP_ENCODING && mode == ScanMode.FULL) {
            // 单独重编码 MobileCLIP：清空语义 embedding
            db.mediaDao().resetAllSemanticEmbeddings()
        }

        // 手动 Pass 增量：按阶段特征过滤，不受时间窗口限制
        if (mode == ScanMode.INCREMENTAL && pass != TagScanPass.DBSCAN) {
            ids = ids.filter { mediaId ->
                isPassMissing(mediaId, pass)
            }
        }

        if (ids.isEmpty() && pass != TagScanPass.DBSCAN) {
            _progress.value = TagScanSessionProgress(
                sessionId = sessionId,
                state = ScanSessionState.COMPLETED,
                messages = listOf(ScanMessage(level = MessageLevel.INFO, text = "没有需要处理的媒体"))
            )
            return sessionId
        }

        createTasksForSinglePass(sessionId, ids, pass, policy)
        startSession(sessionId)
        return sessionId
    }

    /**
     * 暂停当前活跃会话
     */
    suspend fun pause(sessionId: String? = null) {
        val target = sessionId ?: activeSessionId ?: return
        val currentState = _progress.value?.state
        if (currentState in setOf(
                ScanSessionState.PAUSING,
                ScanSessionState.PAUSED,
                ScanSessionState.CANCELLING,
                ScanSessionState.CANCELLED
            )
        ) {
            return
        }
        logInfo(target, "暂停扫描")
        db.tagScanTaskDao().pauseSession(target)
        updateProgressState(target, ScanSessionState.PAUSING)
    }

    /**
     * 恢复指定会话
     */
    suspend fun resume(sessionId: String? = null) {
        val target = sessionId ?: activeSessionId ?: findFirstPausedSession() ?: return
        val currentState = _progress.value?.state
        if (currentState == ScanSessionState.CANCELLED || currentState == ScanSessionState.CANCELLING) {
            logWarning(target, "会话已取消，无法恢复")
            return
        }
        logInfo(target, "恢复扫描")
        db.tagScanTaskDao().resumeSession(target)
        startSession(target)
    }

    /**
     * 取消指定会话
     *
     * 立即把状态置为 [ScanSessionState.CANCELLED]，不再等待当前 JNI 任务返回。
     * 当前正在执行的任务可能还会继续运行（无法中断 native 推理），但其结果会被忽略。
     */
    suspend fun cancel(sessionId: String? = null) {
        val target = sessionId ?: activeSessionId ?: return
        val currentState = _progress.value?.state
        if (currentState in setOf(
                ScanSessionState.CANCELLING,
                ScanSessionState.CANCELLED
            )
        ) {
            return
        }
        logInfo(target, "取消扫描")
        db.tagScanTaskDao().cancelSession(target)
        resetSweepBaseline()
        sessionMutex.withLock { activeSessionId = null }
        currentJob?.cancel()
        // 立即反馈终态，避免 JNI 阻塞导致 UI 长时间停留在“取消中”
        updateProgressState(target, ScanSessionState.CANCELLED)
    }

    /**
     * 重试失败任务
     */
    suspend fun retryFailed(sessionId: String? = null) {
        val target = sessionId ?: activeSessionId ?: return
        logInfo(target, "重试失败任务")
        val now = System.currentTimeMillis()
        val failed = db.tagScanTaskDao().getTasksBySession(target)
            .filter { it.status == TagScanTaskStatus.FAILED }
            .map { it.copy(status = TagScanTaskStatus.PENDING, scheduledAt = now, errorMessage = null) }
        db.tagScanTaskDao().insertAll(failed)
        startSession(target)
    }

    /**
     * 清理已完成/已取消的旧任务
     */
    suspend fun cleanup() {
        val before = System.currentTimeMillis() - CLEANUP_RETENTION_MS
        db.tagScanTaskDao().cleanupOldCompleted(before)
    }

    // ═══════════════════════════════════════════════════════════
    //  内部实现
    // ═══════════════════════════════════════════════════════════

    private suspend fun createTasks(
        sessionId: String,
        mediaIds: List<Long>,
        categories: Set<TagCategory>,
        passes: List<TagScanPass>,
        policy: ScanQueuePolicy
    ) {
        val categoriesJson = if (categories == TagCategory.ALL) null
        else JSONArray(categories.map { it.name }).toString()

        val tasks = mutableListOf<TagScanTaskEntity>()

        // Pass 1: 每张媒体一个独立任务
        if (passes.contains(TagScanPass.FACE_DETECTION)) {
            tasks += mediaIds.map { mediaId ->
                TagScanTaskEntity(
                    sessionId = sessionId,
                    mediaId = mediaId,
                    pass = TagScanPass.FACE_DETECTION,
                    tagCategories = categoriesJson,
                    status = TagScanTaskStatus.PENDING,
                    priority = 0,
                    createdAt = System.currentTimeMillis()
                )
            }
        }

        // Pass 2: 不在中途入队 DBSCAN 精修任务。阶段末的全量重聚类已足够纠正流式错分，
        // 中途非全量精修无法处理已分配 embedding，反而会留下重复分组。

        // Pass 3: 每张媒体一个独立任务
        if (passes.contains(TagScanPass.IMAGE_TAGGING)) {
            tasks += mediaIds.map { mediaId ->
                TagScanTaskEntity(
                    sessionId = sessionId,
                    mediaId = mediaId,
                    pass = TagScanPass.IMAGE_TAGGING,
                    tagCategories = categoriesJson,
                    status = TagScanTaskStatus.PENDING,
                    priority = 2,
                    createdAt = System.currentTimeMillis()
                )
            }
        }

        db.tagScanTaskDao().insertAll(tasks)
        logInfo(sessionId, "创建 ${tasks.size} 个任务 (${mediaIds.size} 媒体, passes=$passes)")
    }

    private suspend fun createTasksForSinglePass(
        sessionId: String,
        mediaIds: List<Long>,
        pass: TagScanPass,
        policy: ScanQueuePolicy
    ) {
        val tasks = when (pass) {
            TagScanPass.FACE_DETECTION -> mediaIds.map { mediaId ->
                TagScanTaskEntity(
                    sessionId = sessionId,
                    mediaId = mediaId,
                    pass = TagScanPass.FACE_DETECTION,
                    status = TagScanTaskStatus.PENDING,
                    priority = 0,
                    createdAt = System.currentTimeMillis()
                )
            }
            TagScanPass.DBSCAN -> listOf(
                TagScanTaskEntity(
                    sessionId = sessionId,
                    mediaId = -1L,
                    pass = TagScanPass.DBSCAN,
                    status = TagScanTaskStatus.PENDING,
                    priority = 0,
                    createdAt = System.currentTimeMillis()
                )
            )
            TagScanPass.IMAGE_TAGGING -> mediaIds.map { mediaId ->
                TagScanTaskEntity(
                    sessionId = sessionId,
                    mediaId = mediaId,
                    pass = TagScanPass.IMAGE_TAGGING,
                    status = TagScanTaskStatus.PENDING,
                    priority = 0,
                    createdAt = System.currentTimeMillis()
                )
            }
            TagScanPass.MOBILE_CLIP_ENCODING -> mediaIds.map { mediaId ->
                TagScanTaskEntity(
                    sessionId = sessionId,
                    mediaId = mediaId,
                    pass = TagScanPass.MOBILE_CLIP_ENCODING,
                    status = TagScanTaskStatus.PENDING,
                    priority = 0,
                    createdAt = System.currentTimeMillis()
                )
            }
        }
        db.tagScanTaskDao().insertAll(tasks)
        logInfo(sessionId, "创建 ${tasks.size} 个任务 (pass=$pass)")
    }

    private suspend fun startSession(sessionId: String) {
        // 若调度器正在执行「重新聚类」（reembedFacesAndRecluster），禁止启动新扫描会话，
        // 避免并发的全局 DBSCAN 与 reembed 互相覆盖 person/face_embeddings 表导致重复分组。
        if (scheduler.isScanning.value) {
            logWarning(sessionId, "已有重新聚类/扫描任务在运行，拒绝启动新会话")
            db.tagScanTaskDao().cancelSession(sessionId)
            _progress.value = TagScanSessionProgress(
                sessionId = sessionId,
                state = ScanSessionState.COMPLETED,
                messages = listOf(
                    ScanMessage(
                        level = MessageLevel.WARNING,
                        text = "已有重新聚类/扫描任务在运行，本次请求已忽略"
                    )
                )
            )
            return
        }

        sessionMutex.withLock {
            if (activeSessionId == sessionId && currentJob?.isActive == true) {
                logInfo(sessionId, "会话已在运行中")
                return
            }
            activeSessionId = sessionId
            // fullRescanPasses 不在此清：schedulePass(FULL) 先写标记再 startSession，
            // 会话开始即清会把标记抹掉 → executeTask 永远读到 false →「全部重新处理」
            // 静默降级为增量（2026-10-02 实锤 no-op）。改为会话收尾清（runSession finally）。
        }

        currentJob?.cancel()
        currentJob = scope.launch {
            runSession(sessionId)
        }
    }

    private suspend fun runSession(sessionId: String) {
        updateProgressState(sessionId, ScanSessionState.RUNNING)
        logInfo(sessionId, "会话开始运行")
        // 清零跨会话残留的特征提取失败计数（取消/暂停分支不消费，
        // reembedFacesAndRecluster 等非会话路径也会累加），避免过期警告带入本会话
        scheduler.consumeFaceEmbeddingFailureCount()
        acquireWakeLock()


        try {
            while (currentCoroutineContext().isActive) {
                val task = db.tagScanTaskDao().pollNextPendingBySession(sessionId) ?: break

                // Pass 3 (IMAGE_TAGGING) 图像打标：scheduler.executeImageTagging 按 tagger 配置分流（Florence-2 / MNN VLM），内部 ensureModelLoaded。
                // 由 scheduler.executeImageTagging 内部负责 ensureModelLoaded() 与推理。

                // MobileCLIP 不参与 Pass3 打标（已移除 MobileClipTagClassifier.classify），无需预热。
                // MobileCLIP 语义向量在 Pass1 内联编码供语义搜索，与此处无关。

                val startMs = System.currentTimeMillis()
                db.tagScanTaskDao().markRunning(task.id)
                updateProgressState(sessionId, ScanSessionState.RUNNING, task.pass, task.mediaId)

                val success = executeTask(task)

                val durationMs = System.currentTimeMillis() - startMs
                recordDuration(task.pass, durationMs)

                if (!success) {
                    handleTaskFailure(task)
                } else {
                    db.tagScanTaskDao().markCompleted(task.id)
                    maybeUpdateMediaScanRecord(task)
                }

                delay(POLL_INTERVAL_MS)
            }

            finalizeSession(sessionId)

            // 自动扫描批次链式调度：当前批次正常完成后，继续调度下一批（同 sweep，共享进度基线）
            val policy = sessionPolicies.remove(sessionId)
            if (policy != null && _progress.value?.state == ScanSessionState.COMPLETED) {
                logInfo(sessionId, "当前批次完成，继续调度下一批")
                scheduleAutoScan(policy, continueSweep = true)
            }
        } catch (e: CancellationException) {
            logInfo(sessionId, "会话被取消")
            updateProgressState(sessionId, ScanSessionState.CANCELLED)
            sessionPolicies.remove(sessionId)
            throw e
        } catch (e: Exception) {
            logError(sessionId, "会话异常: ${e.message}")
            updateProgressState(sessionId, ScanSessionState.PAUSED)
            sessionPolicies.remove(sessionId)
        } finally {
            releaseWakeLock()
            // 会话收尾统一清全量标记（原在 startSession 清=执行前自抹，见上注）
            sessionMutex.withLock { fullRescanPasses.clear() }
        }
    }

    private suspend fun executeTask(task: TagScanTaskEntity): Boolean {
        return try {
            when (task.pass) {
                TagScanPass.FACE_DETECTION -> {
                    val hasFace = scheduler.executeFaceDetection(task.mediaId)
                    if (hasFace && streamingAccumulator.onFacePhoto()) {
                        val assigned = scheduler.runStreamingClusterBatch()
                        embeddingsSinceDbscan += assigned
                    }
                }
                TagScanPass.DBSCAN -> scheduler.executeDbscan(
                    preserveNamedPersons = true,
                    isFullRescan = task.pass in fullRescanPasses
                )
                TagScanPass.IMAGE_TAGGING -> scheduler.executeImageTagging(task.mediaId)
                TagScanPass.MOBILE_CLIP_ENCODING -> scheduler.executeMobileClipEncoding(task.mediaId)
            }
            true
        } catch (e: CancellationException) {
            // 取消异常必须向上抛，让 runSession 进入取消终态
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Task ${task.id} failed: ${e.message}")
            false
        }
    }

    /**
     * 批量执行前准备：加载 Qwen 模型
     */
    suspend fun prepareTaggerModel(): Boolean {
        return scheduler.prepareTaggerModel()
    }

    private suspend fun handleTaskFailure(task: TagScanTaskEntity) {
        val policy = ScanQueuePolicy() // 默认策略，可扩展为按会话存储策略
        val nextRetryAt = if (task.attemptCount < policy.maxRetryAttempts) {
            System.currentTimeMillis() + RETRY_BACKOFF_BASE_MS * (task.attemptCount + 1)
        } else null

        db.tagScanTaskDao().markFailed(
            task.id,
            "Attempt ${task.attemptCount + 1} failed",
            nextRetryAt
        )
        logWarning(task.sessionId, "任务 ${task.id} 失败，第 ${task.attemptCount + 1} 次尝试")
    }

    private suspend fun maybeUpdateMediaScanRecord(task: TagScanTaskEntity) {
        // DBSCAN 是全局任务（mediaId=-1 哨兵），不更新单媒体记录。
        // 注意不能用 mediaId < 0 判断：media_assets 主键全部是负数合成 ID
        // （MediaRepositoryImpl.syntheticMediaId），用符号判断会导致所有媒体的
        // lastTagScanAt/lastTagScanPasses 永不写入 → 增量扫描选片窗口失效 →
        // 每个 session 重复处理同一批最新照片（死循环）。
        if (task.pass == TagScanPass.DBSCAN) return

        // 仅当该媒体所有同会话任务都完成时更新 lastTagScanAt
        val sessionTasks = db.tagScanTaskDao().getTasksBySession(task.sessionId)
        val mediaTasks = sessionTasks.filter { it.mediaId == task.mediaId }
        val allCompleted = mediaTasks.all { it.status == TagScanTaskStatus.COMPLETED }
        if (!allCompleted) return

        val now = System.currentTimeMillis()
        val existingEntity = db.mediaDao().getMediaById(task.mediaId)
        val existingPasses = existingEntity?.lastTagScanPasses?.let { parsePassesJson(it) } ?: mutableMapOf()

        val passNumber = task.pass.toPassNumber()
        existingPasses[passNumber] = now
        val passesJson = JSONObject(existingPasses as Map<*, *>).toString()
        db.mediaDao().updateLastTagScan(task.mediaId, now, passesJson)
    }

    private suspend fun finalizeSession(sessionId: String) {
        val stats = db.tagScanTaskDao().countByStatus(sessionId)
        val pending = stats.count(TagScanTaskStatus.PENDING)
        val running = stats.count(TagScanTaskStatus.RUNNING)
        val paused = stats.count(TagScanTaskStatus.PAUSED)
        val cancelled = stats.count(TagScanTaskStatus.CANCELLED)

        when {
            cancelled > 0 && pending == 0 && running == 0 -> {
                logInfo(sessionId, "会话已取消")
                updateProgressState(sessionId, ScanSessionState.CANCELLED)
                cleanup()
            }
            paused > 0 -> {
                logInfo(sessionId, "会话已暂停")
                updateProgressState(sessionId, ScanSessionState.PAUSED)
            }
            pending == 0 && running == 0 -> {
                logInfo(sessionId, "会话完成")
                // 产出率哨兵：本会话有照片检测到人脸但 embedding 提取为空 → 人脸模型疑似损坏，
                // 转为用户可见警告（否则 persons/embeddings 统计静默归零，无任何报错）。
                val faceFailures = scheduler.consumeFaceEmbeddingFailureCount()
                if (faceFailures > 0) {
                    logWarning(sessionId, "有 $faceFailures 张照片检测到人脸但特征提取失败" +
                        "（人脸模型缺失或损坏），请到模型中心重新下载 Glint360K R100 后重新扫描")
                }
                updateProgressState(sessionId, ScanSessionState.COMPLETED)
                cleanup()
            }
            else -> {
                updateProgressState(sessionId, ScanSessionState.IDLE)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  进度与日志
    // ═══════════════════════════════════════════════════════════

    private suspend fun updateProgressState(
        sessionId: String,
        state: ScanSessionState,
        currentPass: TagScanPass? = null,
        currentMediaId: Long? = null
    ) {
        // 一旦进入终态（已取消/已完成），不再接受运行中/暂停等中间态覆盖，
        // 避免取消后当前 JNI 任务返回又把状态刷回 RUNNING。
        val current = _progress.value
        if (current != null &&
            current.sessionId == sessionId &&
            current.state in setOf(ScanSessionState.CANCELLED, ScanSessionState.COMPLETED) &&
            state !in setOf(ScanSessionState.CANCELLED, ScanSessionState.COMPLETED)
        ) {
            return
        }

        val stats = db.tagScanTaskDao().countByStatus(sessionId)
        val total = stats.sumOf { it.cnt }
        val processed = stats.count(TagScanTaskStatus.COMPLETED)
        val pending = stats.count(TagScanTaskStatus.PENDING)
        val failed = stats.count(TagScanTaskStatus.FAILED)

        val taskProgress = computeTaskProgress(sessionId)

        _progress.value = TagScanSessionProgress(
            sessionId = sessionId,
            state = state,
            currentPass = retainedCurrentPass(
                state = state,
                incoming = currentPass,
                previous = current?.takeIf { it.sessionId == sessionId }?.currentPass,
            ),
            currentMediaId = currentMediaId,
            processed = processed,
            total = total,
            pending = pending,
            failed = failed,
            estimatedRemainingMs = taskProgress.etaMs,
            weightedFraction = taskProgress.fraction,
            messages = sessionMessages.toList()
        )
    }

    /**
     * 任务域加权进度 + ETA（2026-10-05 口径立法，公式见 shared ScanProgressCalculator）。
     *
     * - 自动 sweep：媒体表口径——基线 = sweep 启动时全库待办快照（照片口径，含未入队积压），
     *   当前剩余 ≥1s 节流刷新并按基线钳制（扫描中途新增照片不进本轮分母），sweep 内单调钳制。
     * - 手动会话（regenerate / 单 Pass / 进程重启恢复）：任务表口径——基线 = 会话任务总数，
     *   剩余 = PENDING/RUNNING/PAUSED/FAILED（FAILED 视为待重试，对齐旧 ETA 语义）。
     *
     * ETA 与进度同源：etaMs = 当前剩余工作量，不再两套时间基准。
     * DBSCAN 是全局任务不进 fraction；其耗时计入 ETA。MOBILE_CLIP 兼容任务同理只进 ETA。
     */
    private suspend fun computeTaskProgress(sessionId: String): ScanTaskProgress {
        val weights = currentPassWeights()
        if (sweepBaselineFace >= 0 || sweepBaselineTagging >= 0) {
            val now = System.currentTimeMillis()
            if (now - sweepLastRefreshMs >= 1000L) {
                sweepLastRefreshMs = now
                sweepCachedRemainingFace = db.mediaDao().getMediaWithoutFaceRoiCount()
                sweepCachedRemainingTagging = db.mediaDao().getUnlabeledPhotoCount()
            }
            val result = computeScanTaskProgress(
                baselineRemainingPass1 = sweepBaselineFace.coerceAtLeast(0).toLong(),
                baselineRemainingPass3 = sweepBaselineTagging.coerceAtLeast(0).toLong(),
                remainingPass1 = sweepCachedRemainingFace.toLong(),
                remainingPass3 = sweepCachedRemainingTagging.toLong(),
                weights = weights,
            )
            val fraction = result.fraction
            val previous = sweepLastFraction
            val monotonic = when {
                fraction == null -> previous
                previous == null -> fraction
                else -> maxOf(previous, fraction)
            }
            sweepLastFraction = monotonic
            return result.copy(fraction = monotonic)
        }

        val stats = db.tagScanTaskDao().countByStatusAndPass(sessionId)
        var totalFace = 0L
        var totalTagging = 0L
        var remainingFace = 0L
        var remainingTagging = 0L
        var extraEtaMs = 0L
        for (row in stats) {
            val isRemaining = when (row.status) {
                TagScanTaskStatus.PENDING, TagScanTaskStatus.RUNNING,
                TagScanTaskStatus.PAUSED, TagScanTaskStatus.FAILED -> true
                TagScanTaskStatus.COMPLETED, TagScanTaskStatus.CANCELLED -> false
            }
            when (row.pass) {
                TagScanPass.FACE_DETECTION -> {
                    if (row.status != TagScanTaskStatus.CANCELLED) totalFace += row.cnt
                    if (isRemaining) remainingFace += row.cnt
                }
                TagScanPass.IMAGE_TAGGING -> {
                    if (row.status != TagScanTaskStatus.CANCELLED) totalTagging += row.cnt
                    if (isRemaining) remainingTagging += row.cnt
                }
                // 全局/兼容任务不进 fraction，仅按各自预估耗时计入 ETA
                TagScanPass.DBSCAN ->
                    if (isRemaining) extraEtaMs += row.cnt * estimatePassDurationMs(TagScanPass.DBSCAN)
                TagScanPass.MOBILE_CLIP_ENCODING ->
                    if (isRemaining) extraEtaMs += row.cnt * estimatePassDurationMs(TagScanPass.MOBILE_CLIP_ENCODING)
            }
        }
        val result = computeScanTaskProgress(
            baselineRemainingPass1 = totalFace,
            baselineRemainingPass3 = totalTagging,
            remainingPass1 = remainingFace,
            remainingPass3 = remainingTagging,
            weights = weights,
        )
        val baseEtaMs = result.etaMs
        val etaMs = when {
            baseEtaMs != null -> baseEtaMs + extraEtaMs
            extraEtaMs > 0 -> extraEtaMs
            else -> null
        }
        return result.copy(etaMs = etaMs)
    }

    private fun logInfo(sessionId: String, text: String) {
        Log.i(TAG, "[$sessionId] $text")
        addMessage(MessageLevel.INFO, text)
    }

    private fun logWarning(sessionId: String, text: String) {
        Log.w(TAG, "[$sessionId] $text")
        addMessage(MessageLevel.WARNING, text)
    }

    private fun logError(sessionId: String, text: String) {
        Log.e(TAG, "[$sessionId] $text")
        addMessage(MessageLevel.ERROR, text)
    }

    private fun addMessage(level: MessageLevel, text: String) {
        sessionMessages.add(ScanMessage(level = level, text = text))
        if (sessionMessages.size > 50) {
            sessionMessages.removeAt(0)
        }
    }

    private fun recordDuration(pass: TagScanPass, durationMs: Long) {
        // 过滤极端异常值：单任务耗时超过 30 分钟视为异常（多为应用被挂起/后台冻结），
        // 不计入估算，避免中位数被这种非Processing时间污染。
        if (durationMs > 30 * 60 * 1000L) {
            Log.w(TAG, "[ETA] Abnormal duration filtered: pass=$pass, duration=${durationMs}ms")
            return
        }
        val deque = recentDurationsMs.getOrPut(pass) { ArrayDeque(ESTIMATE_WINDOW_SIZE) }
        if (deque.size >= ESTIMATE_WINDOW_SIZE) {
            deque.removeFirst()
        }
        deque.addLast(durationMs)
    }

    private fun estimatePassDurationMs(pass: TagScanPass): Long {
        val deque = recentDurationsMs[pass]
        if (deque.isNullOrEmpty()) {
            return DEFAULT_PASS_DURATION_MS[pass] ?: 1_000L
        }
        return median(deque).coerceAtLeast(50L)
    }

    private fun median(values: ArrayDeque<Long>): Long {
        val sorted = values.sorted()
        val size = sorted.size
        return if (size % 2 == 1) {
            sorted[size / 2]
        } else {
            (sorted[size / 2 - 1] + sorted[size / 2]) / 2
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  辅助
    // ═══════════════════════════════════════════════════════════

    private suspend fun maybeResumeOnStartup() {
        // 自动恢复 Service 被系统杀死后遗留的待处理会话：
        // init 块已先调用 resetRunningToPending()，将异常中断的 RUNNING 任务重置为 PENDING。
        val pendingSession = db.tagScanTaskDao().findSessionsByStatus(TagScanTaskStatus.PENDING)
            .firstOrNull()
            ?: return
        logInfo(pendingSession, "Service 重建，自动恢复待处理会话")
        startSession(pendingSession)
    }

    private suspend fun findFirstPausedSession(): String? {
        return db.tagScanTaskDao().findSessionsByStatus(TagScanTaskStatus.PAUSED).firstOrNull()
    }

    private fun acquireWakeLock() {
        try {
            if (!wakeLock.isHeld) wakeLock.acquire()
        } catch (e: Exception) {
            Log.w(TAG, " acquire wake lock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (e: Exception) {
            Log.w(TAG, " release wake lock failed: ${e.message}")
        }
    }

    private fun newSessionId(): String = "tag-${UUID.randomUUID().toString().substring(0, 8)}"

    private fun parsePassesJson(json: String): MutableMap<String, Long> {
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Long>()
            obj.keys().forEach { key ->
                map[key] = obj.getLong(key)
            }
            map
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    /**
     * 分批加载 [MediaEntity] 并按 [TagScanQuery] 条件过滤，返回匹配的媒体 ID 列表。
     *
     * 避免一次性加载全部 [MediaEntity]（含 faceRoiResult/semanticEmbedding 等大字段）到 Java Heap，
     * 防止大图库下出现 OOM。
     */
    private suspend fun filterMediaIdsByQuery(
        allIds: List<Long>,
        query: TagScanQuery
    ): List<Long> {
        val result = mutableListOf<Long>()
        allIds.chunked(QUERY_FILTER_BATCH_SIZE).forEach { batchIds ->
            val batchEntities = db.mediaDao().getMediaByIds(batchIds)
            val matching = batchEntities.filter { entity ->
                query.mediaIds?.let { entity.id in it } ?: true
            }.filter { entity ->
                query.startTimeMs?.let { entity.captureDate >= it } ?: true
            }.filter { entity ->
                query.endTimeMs?.let { entity.captureDate <= it } ?: true
            }.filter { entity ->
                query.hasFace?.let { entity.hasFace == it } ?: true
            }.filter { entity ->
                if (query.missingAnyCategory.isNullOrEmpty()) true
                else !hasAllCategories(entity, query.missingAnyCategory)
            }
            result += matching.map { it.id }
        }
        return result
    }

    /**
     * 判断指定媒体的某个 Pass 是否缺失（用于手动 Pass 增量）
     */
    private suspend fun isPassMissing(mediaId: Long, pass: TagScanPass): Boolean {
        val entity = db.mediaDao().getMediaById(mediaId) ?: return true
        // 人脸检测 / 图像打标 / 语义编码 都依赖 loadBitmap 解码图片，视频会被 MIME 拦截返回 null，
        // 结果列（faceRoiResult/labels/semanticEmbedding）永远写不进去 → 视频必须视为“不缺失”，
        // 否则增量选片会永久重复选中视频（Pass 1“永远扫不完”的根因）。
        if (pass != TagScanPass.DBSCAN && entity.type != MediaType.PHOTO) return false
        return when (pass) {
            TagScanPass.FACE_DETECTION -> entity.faceRoiResult.isNullOrEmpty()
            TagScanPass.IMAGE_TAGGING -> entity.labels.isNullOrEmpty()
            TagScanPass.MOBILE_CLIP_ENCODING -> entity.semanticEmbedding.isNullOrEmpty()
            TagScanPass.DBSCAN -> false // DBSCAN 是全局任务，不针对单媒体
        }
    }

    /**
     * 过滤出 type == PHOTO 的媒体 ID（人脸检测/图像打标/语义编码只适用于照片）。
     * 分批加载实体以规避 SQLite IN 绑定变量上限，与 [filterMediaIdsByQuery] 同模式。
     */
    private suspend fun filterPhotosOnly(ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return ids
        val photoIds = mutableSetOf<Long>()
        ids.chunked(QUERY_FILTER_BATCH_SIZE).forEach { batch ->
            db.mediaDao().getMediaByIds(batch).forEach { entity ->
                if (entity.type == MediaType.PHOTO) photoIds.add(entity.id)
            }
        }
        return ids.filter { it in photoIds }
    }

    private fun hasAllCategories(entity: com.mamba.picme.data.model.MediaEntity, categories: Set<TagCategory>): Boolean {
        return categories.all { category ->
            when (category) {
                TagCategory.FACE -> hasFaceCategory(entity.labels)
                TagCategory.SCENE -> hasLabelField(entity.labels, "scene")
                TagCategory.ACTIVITY -> hasLabelField(entity.labels, "activity")
                TagCategory.OBJECTS -> hasQwenArrayField(entity.labels, "objects")
                TagCategory.TAGS -> hasQwenArrayField(entity.labels, "tags")
                TagCategory.SUMMARY -> hasLabelField(entity.labels, "summary")
            }
        }
    }

    private fun hasFaceCategory(labelsJson: String?): Boolean {
        if (labelsJson.isNullOrEmpty()) return false
        return try {
            JSONObject(labelsJson).has("face")
        } catch (e: Exception) {
            false
        }
    }

    private fun hasLabelField(labelsJson: String?, field: String): Boolean {
        if (labelsJson.isNullOrEmpty()) return false
        return try {
            JSONObject(labelsJson).optString(field).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    private fun hasQwenArrayField(labelsJson: String?, field: String): Boolean {
        if (labelsJson.isNullOrEmpty()) return false
        return try {
            JSONObject(labelsJson).optJSONArray(field)?.length()?.let { it > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  统一数据库统计
    // ═══════════════════════════════════════════════════════════

    /**
     * 统一数据库统计快照（委托到伴生对象方法，供已有 Orchestrator 持有者使用）
     */
    suspend fun getDbStats(): TagScanDbStats = getDbStats(db)

    data class TagScanDbStats(
        val totalMedia: Int,
        val withFace: Int,
        val withLabels: Int,
        val withSemantic: Int,
        val personCount: Int,
        val namedPersonCount: Int = 0,
        val faceEmbeddingCount: Int,
        val remainingForPass1: Int,
        val remainingForPass3: Int,
        /** 照片总数（type = 'PHOTO'，美学评分进度分母；视频不参与打分） */
        val photoCount: Int = 0,
        /** 已出 NIMA 美学分的照片数 */
        val aestheticScoredCount: Int = 0,
        /** 足迹城市数（DISTINCT city；成果面板） */
        val cityCount: Int = 0,
        /** 合照数（同照片聚到 ≥2 人物；成果面板） */
        val groupPhotoCount: Int = 0,
        /** 「我」本人照片数（is_self 标记人物；未标记为 0） */
        val selfPhotoCount: Int = 0
    )

    private fun List<StatusCount>.count(status: TagScanTaskStatus): Int {
        return find { it.status == status }?.cnt ?: 0
    }
}
