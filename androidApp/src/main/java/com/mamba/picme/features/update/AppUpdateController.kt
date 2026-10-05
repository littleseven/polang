package com.mamba.picme.features.update

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mamba.picme.BuildConfig
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.preferences.OtaUpdatePrefs
import com.mamba.picme.data.remote.picme.AppLatestInfo
import com.mamba.picme.data.remote.picme.AppUpdateClient
import com.mamba.picme.data.update.OtaDownloadManager
import com.mamba.picme.domain.update.AppUpdateChecker
import com.mamba.picme.domain.update.OtaDmStatus
import com.mamba.picme.domain.update.OtaPendingDownload
import com.mamba.picme.domain.update.OtaRecoveryAction
import com.mamba.picme.domain.update.OtaRecoveryPolicy
import com.mamba.picme.domain.update.RemoteBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** OTA 自更新状态机（UI 穷举渲染；下载进度由系统下载通知承担，无 Downloading 态）。 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    /** 检测到新版本，等待用户决策。 */
    data class Available(val info: AppLatestInfo) : AppUpdateState

    /** 下载完成待安装（冷启动恢复路径无网络依赖，不携带 AppLatestInfo）。 */
    data class ReadyToInstall(val apkFile: File) : AppUpdateState

    data class Failed(val reason: String) : AppUpdateState
}

/**
 * OTA 自更新编排：启动恢复/检查 → 弹窗决策 → 系统 DownloadManager 后台下载
 * → 完成即安装（前台直接拉系统安装器，后台完成通知点击安装）。
 *
 * - Play 渠道整体禁用；检查/恢复全部失败静默，不影响主流程；
 * - 进程被杀由 DownloadManager 继续下载，冷启动经 OtaRecoveryPolicy 恢复。
 */
class AppUpdateController(
    private val context: Context,
    private val client: AppUpdateClient,
    private val prefs: OtaUpdatePrefs,
    private val scope: CoroutineScope,
    private val downloader: OtaDownloadManager = OtaDownloadManager(context),
) {

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    /** MainActivity 生命周期喂养（ON_START/ON_STOP），决定完成时分发到安装器还是通知。 */
    @Volatile
    var appInForeground: Boolean = false
        private set

    /** 本会话内最近一次下载目标（Failed 重试不必重新 fetch）。 */
    private var lastInfo: AppLatestInfo? = null

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            scope.launch { handleDownloadComplete(id) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            downloadCompleteReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        createNotificationChannel()
    }

    /** MainActivity DisposableEffect onDispose 调用（receiver 与 activity 生命周期对齐）。 */
    fun shutdown() {
        context.unregisterReceiver(downloadCompleteReceiver)
    }

    fun onAppForegroundChanged(inForeground: Boolean) {
        appInForeground = inForeground
    }

    /** 冷启动：先恢复 pending 下载，未命中再走常规远程检查。 */
    fun checkForUpdate() {
        if (!AppUpdateChecker.isSelfUpdateAllowed(installerPackageName())) {
            Logger.d(TAG, "self-update disabled: installed from Play Store")
            return
        }
        if (_state.value !is AppUpdateState.Idle) return
        scope.launch {
            if (recoverPending()) return@launch
            val info = client.fetchLatest(BuildConfig.BUILD_TYPE).getOrNull() ?: return@launch
            if (!info.available || info.url.isBlank()) return@launch
            val remote = RemoteBuild(info.versionCode, info.updatedAt)
            if (AppUpdateChecker.isUpdateAvailable(remote, BuildConfig.VERSION_CODE.toLong(), prefs.installedRemoteKey())) {
                Logger.d(TAG, "update available: ${info.versionName}(${info.versionCode}) @ ${info.updatedAt}")
                _state.value = AppUpdateState.Available(info)
            }
        }
    }

    fun dismiss() {
        _state.value = AppUpdateState.Idle
    }

    /** Available/Failed 态发起（或重试）后台下载：弹窗即关，进度走系统通知。 */
    fun startDownload() {
        val info = when (val current = _state.value) {
            is AppUpdateState.Available -> current.info
            is AppUpdateState.Failed -> lastInfo ?: return
            else -> return
        }
        lastInfo = info
        scope.launch {
            try {
                val id = downloader.enqueue(
                    info.url,
                    info.versionCode,
                    context.getString(R.string.ota_dm_notification_title),
                )
                prefs.markPendingDownload(OtaPendingDownload(id, info.versionCode, info.updatedAt))
                _state.value = AppUpdateState.Idle
                Toast.makeText(
                    context,
                    context.getString(R.string.ota_background_download_toast),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Logger.w(TAG, "enqueue failed: ${e.message}")
                _state.value = AppUpdateState.Failed(e.message ?: "enqueue failed")
            }
        }
    }

    /** ReadyToInstall 态重发安装意图（用户在系统安装器点了取消）。 */
    fun installNow() {
        val current = _state.value as? AppUpdateState.ReadyToInstall ?: return
        launchInstall(current.apkFile)
    }

    /** 恢复 pending 下载；返回 true 表示已终态处理（静默/弹窗），无需常规检查。 */
    private suspend fun recoverPending(): Boolean {
        val pending = prefs.pendingDownload() ?: return false
        val dmStatus = runCatching { downloader.status(pending.downloadId) }.getOrNull()
        return when (val action = OtaRecoveryPolicy.decide(BuildConfig.VERSION_CODE.toLong(), pending, dmStatus)) {
            is OtaRecoveryAction.ProceedCheck -> false
            is OtaRecoveryAction.DiscardStale -> {
                downloader.delete(pending.downloadId)
                downloader.clearOldFiles(exceptVersionCode = -1L)
                prefs.clearPendingDownload()
                Logger.d(TAG, "stale pending discarded: vc=${pending.versionCode}")
                false
            }
            is OtaRecoveryAction.StaySilent -> true
            is OtaRecoveryAction.OfferInstall -> {
                val file = downloader.localFileFor(action.versionCode)
                if (file.exists()) {
                    Logger.d(TAG, "recovered: downloaded vc=${action.versionCode} awaiting install")
                    _state.value = AppUpdateState.ReadyToInstall(file)
                } else {
                    // DM 报成功但文件已被系统清理：按陈旧处理
                    downloader.delete(pending.downloadId)
                    prefs.clearPendingDownload()
                }
                true
            }
            is OtaRecoveryAction.OfferRetry -> {
                Logger.d(TAG, "recovered: failed download (${action.reason})")
                _state.value = AppUpdateState.Failed(action.reason)
                true
            }
        }
    }

    private suspend fun handleDownloadComplete(id: Long) {
        val pending = prefs.pendingDownload() ?: return
        if (pending.downloadId != id) return
        val status = runCatching { downloader.status(id) }.getOrNull()
        if (status !is OtaDmStatus.Successful) return // 失败留给冷启动恢复
        prefs.markInstalledRemoteKey(RemoteBuild(pending.versionCode, pending.updatedAt).key)
        prefs.clearPendingDownload()
        val file = downloader.localFileFor(pending.versionCode)
        Logger.d(TAG, "download complete: vc=${pending.versionCode} foreground=$appInForeground")
        if (appInForeground) {
            launchInstall(file)
        } else {
            showCompletionNotification(file)
        }
        _state.value = AppUpdateState.ReadyToInstall(file)
    }

    private fun launchInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }
        context.startActivity(installIntent(apkFile).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun installIntent(apkFile: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota.provider", apkFile)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun showCompletionNotification(apkFile: File) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return // 冷启动恢复兜底
        val intent = installIntent(apkFile).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.ota_complete_notification_title))
            .setContentText(context.getString(R.string.ota_complete_notification_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.ota_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            )
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun installerPackageName(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    private companion object {
        const val TAG = "PoLang:Update"
        const val CHANNEL_ID = "ota_complete"
        const val NOTIFICATION_ID = 4001
    }
}
