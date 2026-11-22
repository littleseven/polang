package com.mamba.picme.service.dedup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mamba.picme.MainActivity
import com.mamba.picme.PoLangApplication
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.dedup.DedupLevel
import com.mamba.picme.domain.dedup.DedupScanConfig
import com.mamba.picme.domain.dedup.DedupScanProgress
import com.mamba.picme.domain.dedup.DedupScanState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 去重扫描前台 Service——仿 TagGenerationService（service/tag）精简版。
 *
 * ## 对外接口
 * - **触发操作**：标准 Intent（[intentStart]/[intentPause]/[intentResume]/[intentCancel]）；
 * - **观察状态**：伴生 [sessionProgress]（进程级单例，Service 未运行时为 null）。
 *
 * ## 职责边界（M1 服务化决策）
 * - Service 持有 [DedupScanSessionManager] 执行扫描（取数 + 哈希 + 聚类，100% 端侧 [PRIVACY]）；
 * - Results/Cleaned 留在 VM 本地——会话只产出扫描进度与结果组，不持久化（进程死亡由
 *   dedup_hash 缓存保证重扫增量）。
 *
 * ## 生命周期
 * - 终态（COMPLETED/CANCELLED）后延迟 [STOP_DELAY_MS] 自灭；延迟窗口内新会话重启则放弃收摊；
 * - onStartCommand 分发后无活跃会话（复活型 pause/cancel intent / START_STICKY 空重启）即刻 stopSelf；
 * - onDestroy 清空 [sessionProgress]，防服务死亡后残留 RUNNING 让 VM 卡在扫描态。
 */
class DedupScanService : Service() {

    companion object {
        private const val TAG = "PoLang:DedupScan"
        private const val CHANNEL_ID = "picme_dedup_scan"
        private const val NOTIFICATION_ID = 10044

        /** 终态后延迟自灭窗口：让观察者先消费终态，且给快速重启留缓冲。 */
        private const val STOP_DELAY_MS = 1_000L

        private const val WAKE_LOCK_TAG = "PoLang:DedupScanWakeLock"

        // ── Intent Action 常量 ──────────────────────────────
        const val ACTION_START = "com.mamba.picme.dedup.START"
        const val ACTION_PAUSE = "com.mamba.picme.dedup.PAUSE"
        const val ACTION_RESUME = "com.mamba.picme.dedup.RESUME"
        const val ACTION_CANCEL = "com.mamba.picme.dedup.CANCEL"

        // Intent extras（DedupScanConfig 序列化）
        private const val EXTRA_LEVELS = "levels"
        private const val EXTRA_VISUAL_THRESHOLD = "visual_threshold"
        private const val EXTRA_SCREENSHOT_VISUAL_THRESHOLD = "screenshot_visual_threshold"
        private const val EXTRA_SCENE_THRESHOLD = "scene_threshold"
        private const val EXTRA_SCENE_TIME_WINDOW_MS = "scene_time_window_ms"

        /** 去重扫描会话进度（进程级单例：VM/适配器观察入口，Service 未运行时为 null）。 */
        val sessionProgress = MutableStateFlow<DedupScanProgress?>(null)

        @Volatile
        private var managerRef: DedupScanSessionManager? = null

        private fun intent(context: Context, action: String): Intent =
            Intent(context, DedupScanService::class.java).setAction(action)

        fun intentStart(context: Context, config: DedupScanConfig): Intent =
            intent(context, ACTION_START)
                .putStringArrayListExtra(
                    EXTRA_LEVELS,
                    ArrayList(config.levels.map { level -> level.name }),
                )
                .putExtra(EXTRA_VISUAL_THRESHOLD, config.visualThreshold)
                .putExtra(EXTRA_SCREENSHOT_VISUAL_THRESHOLD, config.screenshotVisualThreshold)
                .putExtra(EXTRA_SCENE_THRESHOLD, config.sceneThreshold)
                .putExtra(EXTRA_SCENE_TIME_WINDOW_MS, config.sceneTimeWindowMs)

        fun intentPause(context: Context): Intent = intent(context, ACTION_PAUSE)
        fun intentResume(context: Context): Intent = intent(context, ACTION_RESUME)
        fun intentCancel(context: Context): Intent = intent(context, ACTION_CANCEL)

        private fun configFrom(intent: Intent): DedupScanConfig {
            val defaults = DedupScanConfig()
            val levels = intent.getStringArrayListExtra(EXTRA_LEVELS).orEmpty()
                .mapNotNull { name -> runCatching { DedupLevel.valueOf(name) }.getOrNull() }
                .toSet()
            return DedupScanConfig(
                levels = levels.ifEmpty { defaults.levels },
                visualThreshold = intent.getIntExtra(EXTRA_VISUAL_THRESHOLD, defaults.visualThreshold),
                screenshotVisualThreshold = intent.getIntExtra(
                    EXTRA_SCREENSHOT_VISUAL_THRESHOLD,
                    defaults.screenshotVisualThreshold,
                ),
                sceneThreshold = intent.getIntExtra(EXTRA_SCENE_THRESHOLD, defaults.sceneThreshold),
                sceneTimeWindowMs = intent.getLongExtra(EXTRA_SCENE_TIME_WINDOW_MS, defaults.sceneTimeWindowMs),
            )
        }
    }

    /** FGS 协程未捕获异常只记日志不杀进程（START_STICKY 重放 + 未捕获异常 = 崩溃循环）。 */
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Logger.e(TAG, "Uncaught exception in dedup scan service scope", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + serviceExceptionHandler)
    private var progressJob: Job? = null

    private val wakeLockLazy = lazy {
        val pm = getSystemService(PowerManager::class.java)
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
    }
    private val wakeLock: PowerManager.WakeLock by wakeLockLazy

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val container = (application as PoLangApplication).container
        val manager = DedupScanSessionManager(
            scanner = container.dedupScanner,
            mediaSource = container.dedupMediaSource,
            scope = serviceScope,
            ioDispatcher = Dispatchers.IO,
        )
        managerRef = manager
        progressJob = serviceScope.launch {
            manager.progress.collect { progress ->
                if (!coroutineContext.isActive) return@collect // onDestroy 已取消；禁止残留写复活进度
                sessionProgress.value = progress
                updateNotification(progress)
                if (progress?.state == DedupScanState.COMPLETED ||
                    progress?.state == DedupScanState.CANCELLED
                ) {
                    scheduleStopIfIdle()
                }
            }
        }
        Logger.i(TAG, "Dedup scan service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启动前台通知（任何 Intent 都需要，startForegroundService 5s 窗口）
        if (!enterForegroundSafely()) return START_STICKY

        val manager = managerRef
        when (intent?.action) {
            ACTION_START -> {
                acquireWakeLock()
                manager?.startScan(configFrom(intent))
            }
            ACTION_PAUSE -> manager?.pauseScan()
            ACTION_RESUME -> manager?.resumeScan()
            ACTION_CANCEL -> manager?.cancelScan()
        }
        // 无会话的空转 intent（服务被杀后残留的 pause/cancel、START_STICKY 空重启）：即刻收摊
        if (manager?.progress?.value == null) stopSelf()
        return START_STICKY
    }

    /**
     * Android 14+ dataSync FGS 超时（API 35+ 回调）：去重扫描为分钟级任务、不安排自动续跑，
     * 停服即可（onDestroy 清空 sessionProgress，VM 自动退扫描态；重扫靠 dedup_hash 增量）。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Logger.w(TAG, "onTimeout: dataSync FGS window exhausted, stopping dedup scan")
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 进度归零防残留：服务死亡（系统杀/超时/终态自灭）后 RUNNING 残值会把 VM 卡在扫描态
        sessionProgress.value = null
        progressJob?.cancel()
        releaseWakeLockIfHeld()
        managerRef = null
        serviceScope.cancel()
        Logger.i(TAG, "Dedup scan service destroyed")
        super.onDestroy()
    }

    /**
     * 进入前台（含失败降级）：窗口耗尽（Android 15+ dataSync 6h/24h）或通知权限被拒时
     * stopSelf 放弃本次（对齐 TagGenerationService 先例），不 crash。
     */
    private fun enterForegroundSafely(): Boolean {
        val entered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(null))
            }
        }.onFailure { error ->
            Logger.w(TAG, "startForeground failed, stopping dedup scan service", error)
        }.isSuccess
        if (!entered) stopSelf()
        return entered
    }

    /** 终态延迟自灭；窗口内新会话重启（progress 离开终态/空）则放弃本次收摊。 */
    private fun scheduleStopIfIdle() {
        serviceScope.launch {
            delay(STOP_DELAY_MS)
            val state = sessionProgress.value?.state
            if (state == null || state == DedupScanState.COMPLETED || state == DedupScanState.CANCELLED) {
                stopSelf()
            }
        }
    }

    /** 扫描期间持有 partial wake lock，防止息屏后 CPU 休眠挂起扫描。 */
    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire()
    }

    /** onDestroy 安全网：正常路径在终态/服务销毁时释放，此处兜底强制释放。 */
    private fun releaseWakeLockIfHeld() {
        if (!wakeLockLazy.isInitialized()) return
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun updateNotification(progress: DedupScanProgress?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    private fun buildNotification(progress: DedupScanProgress?): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val scanned = progress?.scanned ?: 0
        val total = progress?.total ?: 0
        val percent = if (total > 0) scanned * 100 / total else null
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.dedup_scan_notification_title))
            .setContentText(getString(R.string.dedup_scan_notification_text, scanned, total))
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
            getString(R.string.dedup_scan_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        manager.createNotificationChannel(channel)
    }
}
