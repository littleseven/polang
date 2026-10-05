package com.mamba.picme.service.tag

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mamba.picme.MainActivity
import com.mamba.picme.R
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tag.TagScanProgress
import com.mamba.picme.domain.tag.scan.LibraryCompletion
import com.mamba.picme.domain.tag.scan.ScanQueuePolicy
import com.mamba.picme.domain.tag.scan.ScanStage
import com.mamba.picme.domain.tag.scan.formatDuration
import com.mamba.picme.domain.tag.scan.scanStageOf
import com.mamba.picme.PoLangApplication
import com.mamba.picme.domain.tag.scan.ScanSessionState
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * TAG 生成前台 Service —— Android Style.
 *
 * ## 对外接口
 * - **触发操作**：通过标准 Android Intent 驱动，见伴生对象的 Intent 构建方法
 * - **观察状态**：通过伴生对象的 StateFlow 暴露
 * - **Service 内部细节**（编排器、协程、单线程执行器）完全隐藏
 *
 * ## 使用示例
 * ```kotlin
 * // 启动全量扫描
 * startService(TagGenerationService.intentScanAll(context))
 *
 * // 观察扫描状态
 * TagGenerationService.isScanning.collect { ... }
 * TagGenerationService.sessionProgress.collect { ... }
 * ```
 *
 * ## 后台保活(重要)
 * 本 Service 虽为 dataSync 前台服务 + PARTIAL_WAKE_LOCK,在原生 AOSP 足以保活;但
 * HyperOS / MIUI 等 ROM 对退后台 + 息屏的 app 有独立冻结策略,会冻结整个进程导致
 * 扫描暂停(亮屏打开 app 解冻即续跑)。要让扫描在后台持续运行,用户需:
 * 1. 加入电池优化白名单(见 BackgroundScanGuard / BatteryOptimizationUtils)
 * 2. 允许 app 通知(前台服务依赖通知,部分 ROM 对通知禁用的 FGS 限制更狠)
 * 3. (MIUI/HyperOS)允许自启动
 * 扫描启动入口已通过 BackgroundScanGuard 引导用户完成上述配置。
 *
 * 另:Android 14+ 对 dataSync 类型 FGS 有运行时长上限,超时走 [onTimeout],
 * 由 [TagScanRescheduleReceiver] 闹钟兜底续跑(前提:已加电池白名单)。
 */
class TagGenerationService : Service() {

    // ═══════════════════════════════════════════════════════════
    //  对外 API：Intent 动作 + 状态流
    // ═══════════════════════════════════════════════════════════

    companion object {
        private const val TAG = "TagGenService"
        private const val CHANNEL_ID = "picme_tag_generation"
        private const val CHANNEL_NAME = "TAG 生成"
        private const val NOTIFICATION_ID = 10043

        /** onTimeout 后多久重新拉起扫描服务续跑(Android 14+ dataSync FGS 超时兜底) */
        const val RESUME_DELAY_MS = 15 * 60 * 1000L

        /** 电池电量阈值：低于此值暂停扫描 */
        private const val BATTERY_LOW_THRESHOLD = 15
        /** 电池电量阈值：低于此值终止扫描 */
        private const val BATTERY_CRITICAL_THRESHOLD = 5

        /** Pass3 流控：每张推理后的散热间歇（毫秒），随热状态递增（平衡档）。
         *  SEVERE 及以上由 [checkGuard] ABORT 兜底，不在此表。 */
        val PASS3_COOLDOWN_BY_THERMAL: Map<Int, Long> = mapOf(
            PowerManager.THERMAL_STATUS_NONE to 800L,
            PowerManager.THERMAL_STATUS_LIGHT to 1_500L,
            PowerManager.THERMAL_STATUS_MODERATE to 3_000L
        )
        const val PASS3_COOLDOWN_DEFAULT_MS = 800L

        // ── Intent Action 常量 ──────────────────────────────
        const val ACTION_SCAN_ALL = "com.mamba.picme.tag.SCAN_ALL"
        const val ACTION_SCAN_INCREMENTAL = "com.mamba.picme.tag.SCAN_INCREMENTAL"
        const val ACTION_SCAN_PASS_1 = "com.mamba.picme.tag.SCAN_PASS_1"
        const val ACTION_SCAN_PASS_1_FULL = "com.mamba.picme.tag.SCAN_PASS_1_FULL"
        const val ACTION_SCAN_PASS_2 = "com.mamba.picme.tag.SCAN_PASS_2"
        const val ACTION_SCAN_PASS_2_FULL = "com.mamba.picme.tag.SCAN_PASS_2_FULL"
        const val ACTION_SCAN_PASS_3 = "com.mamba.picme.tag.SCAN_PASS_3"
        const val ACTION_SCAN_PASS_3_FULL = "com.mamba.picme.tag.SCAN_PASS_3_FULL"
        /** 单独执行 MobileCLIP 语义编码（增量）。常规扫描已将该阶段内联合并到 Pass 1。 */
        const val ACTION_SCAN_PASS_4 = "com.mamba.picme.tag.SCAN_PASS_4"
        /** 单独全量重新生成 MobileCLIP 语义编码。常规扫描已将该阶段内联合并到 Pass 1。 */
        const val ACTION_SCAN_PASS_4_FULL = "com.mamba.picme.tag.SCAN_PASS_4_FULL"
        const val ACTION_REGENERATE_CATEGORIES = "com.mamba.picme.tag.REGENERATE_CATEGORIES"
        /** 美学评分（NIMA + eDifFIQA）增量补分：循环跑批排空全库积压，与扫描会话解耦。 */
        const val ACTION_SCORE_AESTHETIC = "com.mamba.picme.tag.SCORE_AESTHETIC"
        /** 美学评分全量重打：先清空已有 aestheticScore/faceQualityScore 再排空。 */
        const val ACTION_SCORE_AESTHETIC_FULL = "com.mamba.picme.tag.SCORE_AESTHETIC_FULL"
        const val ACTION_PAUSE = "com.mamba.picme.tag.PAUSE"
        const val ACTION_RESUME = "com.mamba.picme.tag.RESUME"
        const val ACTION_CANCEL = "com.mamba.picme.tag.CANCEL"
        const val ACTION_RETRY_FAILED = "com.mamba.picme.tag.RETRY_FAILED"
        /** 仅重提已有人脸的 embedding（对齐）并全量重聚类（保名）。入口在人物页顶栏。 */
        const val ACTION_REEMBED_FACES = "com.mamba.picme.tag.REEMBED_FACES"

        /** 按用户友好的类别启动 TAG 扫描 */
        const val ACTION_START_TAG_SCAN = "com.mamba.picme.tag.START_TAG_SCAN"

        // Intent extras
        private const val EXTRA_CATEGORIES = "categories"
        private const val EXTRA_START_TIME_MS = "start_time_ms"
        private const val EXTRA_FULL_MODE = "full_mode"
        private const val EXTRA_TASK_TYPE = "task_type"
        private const val EXTRA_MODE = "mode"

        /** 创建用于启动 Service 的 Intent（首次需 [startForegroundService]） */
        private fun intent(context: Context, action: String): Intent =
            Intent(context, TagGenerationService::class.java).setAction(action)

        fun intentScanAll(context: Context) = intent(context, ACTION_SCAN_ALL)
        fun intentScanIncremental(context: Context) = intent(context, ACTION_SCAN_INCREMENTAL)
        fun intentScanPass1(context: Context) = intent(context, ACTION_SCAN_PASS_1)
        fun intentScanPass1Full(context: Context) = intent(context, ACTION_SCAN_PASS_1_FULL)
        fun intentScanPass2(context: Context) = intent(context, ACTION_SCAN_PASS_2)
        fun intentScanPass2Full(context: Context) = intent(context, ACTION_SCAN_PASS_2_FULL)
        fun intentScanPass3(context: Context) = intent(context, ACTION_SCAN_PASS_3)
        fun intentScanPass3Full(context: Context) = intent(context, ACTION_SCAN_PASS_3_FULL)
        fun intentScanPass4(context: Context) = intent(context, ACTION_SCAN_PASS_4)
        fun intentScanPass4Full(context: Context) = intent(context, ACTION_SCAN_PASS_4_FULL)
        fun intentScoreAesthetic(context: Context) = intent(context, ACTION_SCORE_AESTHETIC)
        fun intentScoreAestheticFull(context: Context) = intent(context, ACTION_SCORE_AESTHETIC_FULL)

        /** 人物页「重新聚类」：仅重提已有人脸 embedding（对齐）+ 全量重聚类（保名）。 */
        fun intentReembedFaces(context: Context) = intent(context, ACTION_REEMBED_FACES)

        /**
         * 按 TAG 类别 / 时间范围重新生成
         *
         * @param categories 需要重新生成的类别名称列表，如 ["SCENE", "TAGS"]
         * @param startTimeMs 时间范围起点（毫秒），0 表示不限制
         * @param fullMode true=清空旧标签后重标，false=仅补充缺失类别
         */
        fun intentRegenerateCategories(
            context: Context,
            categories: List<String>,
            startTimeMs: Long = 0L,
            fullMode: Boolean = false
        ): Intent = intent(context, ACTION_REGENERATE_CATEGORIES)
            .putStringArrayListExtra(EXTRA_CATEGORIES, ArrayList(categories))
            .putExtra(EXTRA_START_TIME_MS, startTimeMs)
            .putExtra(EXTRA_FULL_MODE, fullMode)

        fun intentPause(context: Context) = intent(context, ACTION_PAUSE)
        fun intentResume(context: Context) = intent(context, ACTION_RESUME)
        fun intentCancel(context: Context) = intent(context, ACTION_CANCEL)
        fun intentRetryFailed(context: Context) = intent(context, ACTION_RETRY_FAILED)

        /**
         * 按类别启动 TAG 扫描。
         *
         * @param taskType 逗号分隔的类别名，或 "AUTO"
         * @param mode "full" 或 "incremental"
         */
        fun intentStartTagScan(
            context: Context,
            taskType: String,
            mode: String
        ): Intent = intent(context, ACTION_START_TAG_SCAN)
            .putExtra(EXTRA_TASK_TYPE, taskType)
            .putExtra(EXTRA_MODE, mode)

        /** 启动前台 Service（UI 进入 TAG 控制页时调用） */
        fun startForeground(context: Context) {
            context.startForegroundService(
                Intent(context, TagGenerationService::class.java)
            )
        }

        // ── 可观察状态（只读 StateFlow，不暴露调度器）─────────

        @Volatile
        private var orchestratorRef: TagScanOrchestrator? = null

        /** 是否正在扫描（Service 未运行时为 false） */
        val isScanning: MutableStateFlow<Boolean> = MutableStateFlow(false)

        /** 旧版扫描进度（兼容旧 UI，Service 未运行时为 null） */
        val progress: MutableStateFlow<TagScanProgress?> = MutableStateFlow(null)

        /** 最后一次扫描消息（Service 未运行时为 null） */
        val lastScanMessage: MutableStateFlow<String?> = MutableStateFlow(null)

        /** 新版会话级增强进度 */
        val sessionProgress: MutableStateFlow<TagScanSessionProgress?> = MutableStateFlow(null)

        /**
         * 库级 AI 打标完成率（2026-10-01 口径立法：全 app 唯一对外百分比数据源）。
         * 扫描会话活跃期间由 progressJob 节流刷新；会话终态/Service 销毁时置 null。
         */
        val libraryCompletion: MutableStateFlow<LibraryCompletion?> = MutableStateFlow(null)

        /**
         * 刷新统一数据库统计快照。
         *
         * 直接查询数据库，不依赖 Orchestrator 实例是否已创建，
         * 确保 UI 即使刚进入页面或 Service 重建时也能获取统计。
         */
        suspend fun refreshDbStats(context: Context): com.mamba.picme.domain.tag.scan.TagScanOrchestrator.TagScanDbStats {
            return com.mamba.picme.domain.tag.scan.TagScanOrchestrator.getDbStats(
                com.mamba.picme.data.local.AppDatabase.getDatabase(context)
            )
        }

        private fun TagScanSessionProgress?.toLegacyProgress(): TagScanProgress? {
            if (this == null) return null
            return TagScanProgress(
                processed = processed,
                total = total,
                currentStage = when (currentPass) {
                    com.mamba.picme.data.local.entity.TagScanPass.FACE_DETECTION ->
                        com.mamba.picme.domain.tag.PipelineStage.FACE_ROI
                    com.mamba.picme.data.local.entity.TagScanPass.DBSCAN ->
                        com.mamba.picme.domain.tag.PipelineStage.FACE_CLUSTER
                    com.mamba.picme.data.local.entity.TagScanPass.IMAGE_TAGGING ->
                        com.mamba.picme.domain.tag.PipelineStage.IMAGE_TAGGING
                    com.mamba.picme.data.local.entity.TagScanPass.MOBILE_CLIP_ENCODING ->
                        com.mamba.picme.domain.tag.PipelineStage.MOBILE_CLIP
                    null ->
                        if (state == ScanSessionState.COMPLETED) {
                            com.mamba.picme.domain.tag.PipelineStage.COMPLETE
                        } else {
                            com.mamba.picme.domain.tag.PipelineStage.FACE_ROI
                        }
                },
                currentItem = currentMediaId?.toInt() ?: 0
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  内部实现（完全对外隐藏）
    // ═══════════════════════════════════════════════════════════

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var progressJob: Job? = null

    /**
     * 在途美学打分任务（runUntilDone）句柄。
     * 美学打分与扫描会话互斥（eDifFIQA 复用 pipeline 的 RetinaFace 检测，非线程安全）：
     * 新扫描会话进入活跃态时取消本任务，扫描完成后再由 post-scan 钩子重新补分。
     */
    private var aestheticJob: Job? = null

    /**
     * 控制线程：负责状态机、DB 队列操作、进度更新。
     * 必须与任务线程分离，防止 JNI 阻塞时控制命令无法响应。
     */
    private val controlDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "tag-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * 任务线程：负责执行具体推理任务（人脸检测 / DBSCAN / 图像打标）。
     * 与控制线程解耦，即使被 JNI 阻塞也不影响暂停/取消。
     */
    private val taskExecutor =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "tag-worker").apply { isDaemon = true }
        }
    private val taskDispatcher = taskExecutor.asCoroutineDispatcher()

    private var batteryLevel: Int = 100
    private var isCharging: Boolean = false
    private var thermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    private var scheduler: TagGenerationScheduler? = null
    private var orchestrator: TagScanOrchestrator? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (scale > 0) (batteryLevel * 100.0 / scale).roundToInt() else 100
            batteryLevel = pct
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            android.util.Log.d(TAG, "Battery: $batteryLevel% charging=$isCharging")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val sched = TagGenerationScheduler(
            context = this,
            dispatcher = taskDispatcher,
            guard = { checkGuard() },
            getThrottleMs = { getAdaptiveThrottleMs() },
            getPass3CooldownMs = { getPass3CooldownMs() }
        )
        scheduler = sched

        val orch = TagScanOrchestrator(
            context = this,
            scheduler = sched,
            dispatcher = controlDispatcher,
            db = AppDatabase.getDatabase(this)
        )
        orchestrator = orch
        orchestratorRef = orch

        progressJob = serviceScope.launch {
            var scoredSession: String? = null
            var lastLibraryRefreshMs = 0L
            orch.progress.collectLatest { sp ->
                if (!coroutineContext.isActive) return@collectLatest // onDestroy 已取消；禁止残留写复活 isScanning
                sessionProgress.value = sp
                isScanning.value = sp?.state in setOf(
                    ScanSessionState.RUNNING,
                    ScanSessionState.PAUSING,
                    ScanSessionState.CANCELLING
                )
                progress.value = sp.toLegacyProgress()
                lastScanMessage.value = sp?.messages?.lastOrNull()?.text
                updateNotification(sp)
                // 口径立法：活跃会话期间节流（≥1s）刷新库级完成率，终态/空闲置 null
                val sessionActive = sp != null && sp.state != ScanSessionState.IDLE &&
                        sp.state != ScanSessionState.COMPLETED && sp.state != ScanSessionState.CANCELLED
                val nowMs = System.currentTimeMillis()
                if (sessionActive && nowMs - lastLibraryRefreshMs >= 1000L) {
                    lastLibraryRefreshMs = nowMs
                    runCatching { orch.getDbStats() }.onSuccess { stats ->
                        libraryCompletion.value = LibraryCompletion(
                            totalPhotos = stats.photoCount,
                            remainingPass1 = stats.remainingForPass1,
                            remainingPass3 = stats.remainingForPass3,
                            weights = orch.currentPassWeights(),
                        )
                    }
                } else if (!sessionActive) {
                    libraryCompletion.value = null
                    lastLibraryRefreshMs = 0L
                }
                // 扫描会话活跃 → 取消在途美学打分（互斥：eDifFIQA 复用 RetinaFace，非线程安全），
                // 同时避免打标控制页在扫描期间显示「美学评分」进度而非扫描进度。
                if (isScanning.value && aestheticJob?.isActive == true) {
                    android.util.Log.i(TAG, "scan session active; cancelling in-flight aesthetic scoring")
                    aestheticJob?.cancel()
                    aestheticJob = null
                }
                // 扫描会话完成 → 后台触发全量美学/人脸画质打分（独立、幂等；复用同一 worker 实例）。
                // fire-and-forget 到 serviceScope，不被 collectLatest 取消；按 sessionId 去重，每会话只触发一次。
                // runUntilDone 循环跑批排空积压（旧版 runOnce 每会话仅 50 张，大图库永远补不齐）。
                // 若完成态只是链式批次的中间态（紧接的下一批会话会把状态刷回 RUNNING），
                // 本任务会被上方互斥逻辑取消，待全部扫描结束后最后一次 COMPLETED 再真正排空。
                val sid = sp?.sessionId
                if (sp?.state == ScanSessionState.COMPLETED && sid != null && sid != scoredSession) {
                    scoredSession = sid
                    aestheticJob = serviceScope.launch {
                        runCatching {
                            (applicationContext as? PoLangApplication)
                                ?.container?.aestheticScoreWorker?.runUntilDone()
                        }.onFailure { android.util.Log.w(TAG, "post-scan aesthetic scoring failed", it) }
                    }
                }
            }
        }

        android.util.Log.i(TAG, "Service created with orchestrator")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == null) {
            // 首次启动，仅显示前台通知
            startForeground(NOTIFICATION_ID, buildNotification(null))
            return START_STICKY
        }

        // 启动前台通知（任何首次 Intent 都需要）
        startForeground(NOTIFICATION_ID, buildNotification(null))

        val orch = orchestrator ?: return START_STICKY

        // 根据 Action 分发
        serviceScope.launch {
            when (intent.action) {
                ACTION_SCAN_ALL -> orch.scheduleAutoScan(ScanQueuePolicy())
                ACTION_SCAN_INCREMENTAL -> orch.scheduleAutoScan(ScanQueuePolicy())
                ACTION_SCAN_PASS_1 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.FACE_DETECTION,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                )
                ACTION_SCAN_PASS_1_FULL -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.FACE_DETECTION,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.FULL
                )
                ACTION_SCAN_PASS_2 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.DBSCAN,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                )
                ACTION_SCAN_PASS_2_FULL -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.DBSCAN,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.FULL
                )
                ACTION_SCAN_PASS_3 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.IMAGE_TAGGING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                )
                ACTION_SCAN_PASS_3_FULL -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.IMAGE_TAGGING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.FULL
                )
                ACTION_SCAN_PASS_4 -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.MOBILE_CLIP_ENCODING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                )
                ACTION_SCAN_PASS_4_FULL -> orch.schedulePass(
                    com.mamba.picme.data.local.entity.TagScanPass.MOBILE_CLIP_ENCODING,
                    com.mamba.picme.domain.tag.scan.TagScanQuery(),
                    com.mamba.picme.domain.tag.scan.ScanMode.FULL
                )
                ACTION_REEMBED_FACES -> {
                    // 仅重提已有人脸 embedding（对齐）+ 全量重聚类（保名）。直接调用，非会话。
                    // 必须与 orchestrator 扫描互斥，防止并发写 face_embeddings/persons 产生重复分组。
                    val hasActiveOrchestratorSession = orch.progress.value?.state in setOf(
                        ScanSessionState.RUNNING,
                        ScanSessionState.PAUSING,
                        ScanSessionState.CANCELLING
                    )
                    if (hasActiveOrchestratorSession || scheduler?.isScanning?.value == true) {
                        android.util.Log.w(
                            TAG,
                            "reembed ignored: orchestrator=${hasActiveOrchestratorSession}, scheduler=${scheduler?.isScanning?.value}"
                        )
                        return@launch
                    }
                    scheduler?.reembedFacesAndRecluster { processed, total ->
                        android.util.Log.i(TAG, "reembed progress: $processed/$total")
                    }
                    stopSelf() // 完成后释放 FGS
                }
                ACTION_REGENERATE_CATEGORIES -> {
                    val categoryNames = intent.getStringArrayListExtra(EXTRA_CATEGORIES) ?: arrayListOf()
                    val startTimeMs = intent.getLongExtra(EXTRA_START_TIME_MS, 0L)
                    val fullMode = intent.getBooleanExtra(EXTRA_FULL_MODE, false)

                    val categories = categoryNames.mapNotNull { name ->
                        runCatching { com.mamba.picme.domain.tag.TagCategory.valueOf(name) }.getOrNull()
                    }.toSet()

                    if (categories.isNotEmpty()) {
                        val query = com.mamba.picme.domain.tag.scan.TagScanQuery(
                            startTimeMs = startTimeMs.takeIf { it > 0 }
                        )
                        val mode = if (fullMode) {
                            com.mamba.picme.domain.tag.scan.ScanMode.FULL
                        } else {
                            com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                        }
                        orch.scheduleRegenerateByQuery(query, categories, mode)
                    }
                }
                ACTION_START_TAG_SCAN -> {
                    val taskType = intent.getStringExtra(EXTRA_TASK_TYPE) ?: "AUTO"
                    val modeName = intent.getStringExtra(EXTRA_MODE) ?: "incremental"
                    val mode = if (modeName.equals("full", ignoreCase = true)) {
                        com.mamba.picme.domain.tag.scan.ScanMode.FULL
                    } else {
                        com.mamba.picme.domain.tag.scan.ScanMode.INCREMENTAL
                    }

                    if (taskType.equals("AUTO", ignoreCase = true)) {
                        orch.scheduleAutoScan(com.mamba.picme.domain.tag.scan.ScanQueuePolicy())
                    } else {
                        val categoryNames = taskType.split(",").map { it.trim().uppercase() }
                        val categories = categoryNames.mapNotNull { name ->
                            runCatching { com.mamba.picme.domain.tag.TagCategory.valueOf(name) }.getOrNull()
                        }.toSet()
                        if (categories.isNotEmpty()) {
                            orch.scheduleRegenerateByQuery(
                                query = com.mamba.picme.domain.tag.scan.TagScanQuery(),
                                categories = categories,
                                mode = mode
                            )
                        }
                    }
                }
                ACTION_PAUSE -> orch.pause()
                ACTION_RESUME -> orch.resume()
                ACTION_CANCEL -> orch.cancel()
                ACTION_RETRY_FAILED -> orch.retryFailed()
                // 美学评分手动触发（打标控制页「美学评分」卡片）：循环跑批排空积压。
                // 互斥规则：eDifFIQA 复用 pipeline 的 RetinaFace 检测（detectFacesForScoring，非线程安全），
                // 扫描会话活跃时不并发执行——增量补分由会话完成后的 post-scan 钩子自动兜底；
                // 全量重打直接拒绝（避免清空后无人补分），用户待扫描结束后重试。
                ACTION_SCORE_AESTHETIC -> if (isScanning.value) {
                    android.util.Log.i(TAG, "scan session active; incremental aesthetic scoring deferred to post-scan hook")
                } else {
                    aestheticJob = serviceScope.launch {
                        runCatching {
                            (applicationContext as? PoLangApplication)
                                ?.container?.aestheticScoreWorker?.runUntilDone()
                        }.onFailure { android.util.Log.w(TAG, "manual aesthetic scoring failed", it) }
                    }
                }
                ACTION_SCORE_AESTHETIC_FULL -> if (isScanning.value) {
                    android.util.Log.w(TAG, "scan session active; full aesthetic rescoring rejected, retry after scan")
                } else {
                    aestheticJob = serviceScope.launch {
                        runCatching {
                            AppDatabase.getDatabase(applicationContext).mediaDao().clearAestheticScores()
                            (applicationContext as? PoLangApplication)
                                ?.container?.aestheticScoreWorker?.runUntilDone()
                        }.onFailure { android.util.Log.w(TAG, "full aesthetic rescoring failed", it) }
                    }
                }
            }
        }

        return START_STICKY
    }

    /**
     * Android 14+ dataSync 前台服务超时回调(API 35+,低版本系统不调用)。
     *
     * 超时后系统要求停止服务,且 START_STICKY 不保证重启。此处安排一个
     * [TagScanRescheduleReceiver] 闹钟,到点重新拉起本 Service,由 Orchestrator.init 的
     * `resetRunningToPending()` + `maybeResumeOnStartup()` 自动接续被中断的会话。
     * 前提:用户已加电池优化白名单(BackgroundScanGuard 引导),否则后台启动 FGS 受限。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w(
            TAG,
            "onTimeout: dataSync FGS timed out after long run, scheduling resume in ${RESUME_DELAY_MS}ms"
        )
        TagScanRescheduleReceiver.scheduleResume(applicationContext, RESUME_DELAY_MS)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // onDestroy 即将取消 progressJob（isScanning 唯一写者），取消后残留值无人刷新——
        // 显式归零，防服务中途死亡后标志残留 true 误导底 bar 落点（2026-09-30）
        isScanning.value = false
        // 库级完成率同理归零：残留值会让任务中心/通知拿陈旧口径渲染
        libraryCompletion.value = null
        progressJob?.cancel()
        batteryReceiver.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        runBlocking { orchestrator?.cancel() }
        // wakeLock 安全网：orchestrator 取消后 runSession 的 finally 可能因
        // dispatcher 先被取消而跳过 releaseWakeLock()，这里兜底强制释放。
        orchestrator?.releaseWakeLockIfHeld()
        // 级联释放推理引擎 native 资源（FaceDetector / 人脸嵌入 / Florence-2 /
        // OpusMT / MobileCLIP，合计数百 MB）：排到任务线程尾部执行，等可能在飞的
        // JNI 任务结束后再释放，避免并发释放 native 句柄导致崩溃。
        // 后续 taskDispatcher.cancel() 走 shutdown()，已入队的释放任务仍会执行。
        scheduler?.let { sched ->
            taskExecutor.execute {
                runCatching { sched.release() }
                    .onFailure { android.util.Log.w(TAG, "scheduler release failed: ${it.message}") }
            }
        }
        // scheduler 不再维护自己的扫描任务（旧入口已废弃），
        // 取消 orchestrator、排队引擎释放后再取消 dispatcher 即可完成清理。
        orchestrator = null
        scheduler = null
        orchestratorRef = null
        serviceScope.cancel()
        controlDispatcher.cancel()
        taskDispatcher.cancel()
        android.util.Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    /**
     * 自适应节流间隔：根据设备热状态分级
     *
     * MobileCLIP 已启用 GPU 加速且复用 Bitmap，单张处理耗时降至 ~100-300ms，
     * 原 1s 节流成为吞吐瓶颈。新分级在凉机/微热时大幅缩短间隔，
     * 严重发热时仍通过 guard 触发 ABORT/PAUSE 保护设备。
     *
     * - SEVERE:   由 checkGuard() 直接 ABORT，不使用此值
     * - MODERATE: 3s（严重发热，大幅降低推理频率）
     * - LIGHT:    300ms（轻微发热，适度降低）
     * - NONE:     50ms（正常状态，GPU 推理可承受高吞吐）
     */
    private fun getAdaptiveThrottleMs(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_MODERATE -> 3_000L
                PowerManager.THERMAL_STATUS_LIGHT -> 300L
                else -> 50L
            }
        }
        return 50L
    }

    /**
     * Pass3 每张推理后的散热间歇：随热状态递增。
     * 凉机轻间歇（控温为主），发热明显拉长；SEVERE 及以上由 [checkGuard] ABORT。
     */
    private fun getPass3CooldownMs(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            return PASS3_COOLDOWN_BY_THERMAL[pm.currentThermalStatus] ?: PASS3_COOLDOWN_DEFAULT_MS
        }
        return PASS3_COOLDOWN_DEFAULT_MS
    }

    private fun checkGuard(): TagGenerationScheduler.GuardResult {
        if (!isCharging && batteryLevel <= BATTERY_CRITICAL_THRESHOLD) {
            android.util.Log.w(TAG, "Guard: battery critical ($batteryLevel%), aborting")
            return TagGenerationScheduler.GuardResult.ABORT
        }
        if (!isCharging && batteryLevel <= BATTERY_LOW_THRESHOLD) {
            android.util.Log.w(TAG, "Guard: battery low ($batteryLevel%), extended throttle")
            return TagGenerationScheduler.GuardResult.PAUSE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = getSystemService(PowerManager::class.java)
            thermalStatus = pm.currentThermalStatus
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                android.util.Log.w(TAG, "Guard: thermal severe ($thermalStatus), aborting")
                return TagGenerationScheduler.GuardResult.ABORT
            }
            if (thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                android.util.Log.w(TAG, "Guard: thermal moderate ($thermalStatus), extended throttle")
                return TagGenerationScheduler.GuardResult.PAUSE
            }
        }
        return TagGenerationScheduler.GuardResult.ALLOW
    }

    private fun updateNotification(progress: TagScanSessionProgress?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun buildNotification(progress: TagScanSessionProgress?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 口径立法（2026-10-05 修订）：通知进度条 = 任务域加权进度（weightedFraction，
        // sweep 连续、新增照片不回退）；缺帧回退库域完成度，再缺不定态
        val percent = progress?.weightedFraction?.let { fraction -> (fraction * 100).toInt() }
            ?: libraryCompletion.value?.percentRounded()
        val titleRes = when (progress?.state) {
            ScanSessionState.PAUSED -> R.string.tag_gen_notification_paused
            ScanSessionState.COMPLETED -> R.string.tag_gen_notification_completed
            ScanSessionState.RUNNING, ScanSessionState.PAUSING, ScanSessionState.CANCELLING ->
                when (scanStageOf(progress.currentPass)) {
                    ScanStage.FACE -> R.string.tag_scan_now_face
                    ScanStage.CLUSTER -> R.string.tag_scan_now_cluster
                    ScanStage.CONTENT -> R.string.tag_scan_now_content
                    ScanStage.SEMANTIC -> R.string.tag_scan_now_semantic
                    ScanStage.PREPARING -> R.string.tag_scan_now_preparing
                }
            else -> R.string.tag_gen_notification_title
        }
        // 任务级进度只允许「第 x/y 张」叙述形态（spec §4），ETA 仅运行中有意义
        val content = if (progress != null && progress.total > 0) {
            if (progress.state == ScanSessionState.RUNNING && progress.estimatedRemainingMs != null) {
                getString(
                    R.string.tag_scan_narrative,
                    progress.processed,
                    progress.total,
                    formatDuration(progress.estimatedRemainingMs),
                )
            } else {
                getString(R.string.tag_scan_narrative_no_eta, progress.processed, progress.total)
            }
        } else {
            getString(R.string.tag_gen_notification_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(titleRes))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.tag_gen_notification_channel_desc)
        }
        manager.createNotificationChannel(channel)
    }
}
