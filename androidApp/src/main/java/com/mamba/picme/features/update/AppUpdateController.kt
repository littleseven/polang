package com.mamba.picme.features.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mamba.picme.BuildConfig
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.preferences.OtaUpdatePrefs
import com.mamba.picme.data.remote.picme.AppLatestInfo
import com.mamba.picme.data.remote.picme.AppUpdateClient
import com.mamba.picme.domain.update.AppUpdateChecker
import com.mamba.picme.domain.update.RemoteBuild
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** OTA 自更新状态机（枚举所有合法状态，UI 穷举渲染）。 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    /** 检测到新版本，等待用户决策。 */
    data class Available(val info: AppLatestInfo) : AppUpdateState

    /** 下载中（progress 0..1；sizeBytes 未知时 progress 为 -1 表示不确定进度）。 */
    data class Downloading(val info: AppLatestInfo, val progress: Float) : AppUpdateState

    /** 下载完成待安装（用户可能在系统安装器取消，可重试安装）。 */
    data class ReadyToInstall(val info: AppLatestInfo, val apkFile: File) : AppUpdateState

    data class Failed(val info: AppLatestInfo, val reason: String) : AppUpdateState
}

/**
 * OTA 自更新编排：启动静默检查 → 弹窗 → 下载（带进度）→ FileProvider 安装。
 *
 * - Play 商店渠道（installer == com.android.vending）整体禁用（Google Play 政策）；
 * - 检查/下载全部失败静默降级，不影响主流程；
 * - 渠道（debug/release）取自 BuildConfig.BUILD_TYPE，与服务端双轨一一对应。
 */
class AppUpdateController(
    private val context: Context,
    private val client: AppUpdateClient,
    private val prefs: OtaUpdatePrefs,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 冷启动静默检查：Play 渠道直接短路；网络失败静默。 */
    fun checkForUpdate() {
        if (!AppUpdateChecker.isSelfUpdateAllowed(installerPackageName())) {
            Logger.d(TAG, "self-update disabled: installed from Play Store")
            return
        }
        if (_state.value !is AppUpdateState.Idle) return
        scope.launch {
            val info = client.fetchLatest(BuildConfig.BUILD_TYPE).getOrNull() ?: return@launch
            if (!info.available || info.url.isBlank()) return@launch
            val remote = RemoteBuild(info.versionCode, info.updatedAt)
            val installedKey = prefs.installedRemoteKey()
            if (AppUpdateChecker.isUpdateAvailable(remote, BuildConfig.VERSION_CODE.toLong(), installedKey)) {
                Logger.d(TAG, "update available: ${info.versionName}(${info.versionCode}) @ ${info.updatedAt}")
                _state.value = AppUpdateState.Available(info)
            }
        }
    }

    fun dismiss() {
        _state.value = AppUpdateState.Idle
    }

    /** 从 Available/Failed 态开始（或重试）下载。 */
    fun startDownload() {
        val info = when (val current = _state.value) {
            is AppUpdateState.Available -> current.info
            is AppUpdateState.Failed -> current.info
            else -> return
        }
        scope.launch {
            _state.value = AppUpdateState.Downloading(info, 0f)
            try {
                val file = downloadApk(info)
                prefs.markInstalledRemoteKey(RemoteBuild(info.versionCode, info.updatedAt).key)
                _state.value = AppUpdateState.ReadyToInstall(info, file)
                launchInstall(file)
            } catch (e: Exception) {
                Logger.w(TAG, "download failed: ${e.message}")
                _state.value = AppUpdateState.Failed(info, e.message ?: "unknown")
            }
        }
    }

    /** ReadyToInstall 态重发安装意图（用户在系统安装器点了取消）。 */
    fun installNow() {
        val current = _state.value as? AppUpdateState.ReadyToInstall ?: return
        launchInstall(current.apkFile)
    }

    private suspend fun downloadApk(info: AppLatestInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "ota").apply { mkdirs() }
        dir.listFiles()?.forEach { stale -> stale.delete() }
        val target = File(dir, "polang-${info.versionCode}.apk")
        val req = Request.Builder().url(info.url).get().build()
        downloadClient.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code}" }
            val body = resp.body ?: error("empty body")
            val total = info.sizeBytes.takeIf { it > 0 } ?: body.contentLength()
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = AppUpdateState.Downloading(info, downloaded.toFloat() / total)
                            }
                        }
                    }
                }
            }
        }
        target
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
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.ota.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
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
    }
}
