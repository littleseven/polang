# OTA 后台下载 + 完成自动安装 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** debug 包 OTA 更新从「前台弹窗阻塞下载」改为「系统 DownloadManager 后台下载，完成即自动拉安装器」，进程被杀后冷启动可恢复。

**Architecture:** 状态机收敛为 `Idle/Available/ReadyToInstall/Failed`（移除 Downloading，进度由系统通知承担）。分支逻辑集中到纯函数 `OtaRecoveryPolicy`（JVM 可测）；`OtaDownloadManager` 包装系统 DownloadManager；`AppUpdateController` 编排 enqueue/完成广播/前后台感知/冷启动恢复。

**Tech Stack:** 系统 DownloadManager（零新依赖）、FileProvider（现成通路）、NotificationCompat（androidx.core，已在依赖树）、DataStore prefs。

**Spec:** `docs/superpowers/specs/2026-10-05-ota-background-download-design.md`

---

### Task 1: OtaRecoveryPolicy（纯函数，TDD）

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/domain/update/OtaRecoveryPolicy.kt`
- Test: `androidApp/src/test/java/com/mamba/picme/domain/update/OtaRecoveryPolicyTest.kt`

- [x] **Step 1: 写失败测试**

```kotlin
package com.mamba.picme.domain.update

import org.junit.Assert.assertEquals
import org.junit.Test

class OtaRecoveryPolicyTest {

    private val pending = OtaPendingDownload(downloadId = 42L, versionCode = 10047L, updatedAt = "2026-10-05 12:00:00")

    @Test
    fun `无 pending 走常规检查`() {
        assertEquals(
            OtaRecoveryAction.ProceedCheck,
            OtaRecoveryPolicy.decide(installedVersionCode = 10046L, pending = null, dmStatus = null),
        )
    }

    @Test
    fun `pending 存在但 DM 查询失败走常规检查`() {
        assertEquals(
            OtaRecoveryAction.ProceedCheck,
            OtaRecoveryPolicy.decide(10046L, pending, dmStatus = null),
        )
    }

    @Test
    fun `下载中且是新版本则静默`() {
        assertEquals(
            OtaRecoveryAction.StaySilent,
            OtaRecoveryPolicy.decide(10046L, pending, OtaDmStatus.Running(0.5f)),
        )
    }

    @Test
    fun `下载完成且是新版本则提示安装`() {
        assertEquals(
            OtaRecoveryAction.OfferInstall(10047L),
            OtaRecoveryPolicy.decide(10046L, pending, OtaDmStatus.Successful),
        )
    }

    @Test
    fun `下载失败且是新版本则提示重试`() {
        assertEquals(
            OtaRecoveryAction.OfferRetry("reason=1000"),
            OtaRecoveryPolicy.decide(10046L, pending, OtaDmStatus.Failed("reason=1000")),
        )
    }

    @Test
    fun `版本已追平的 pending 是陈旧残留需清理`() {
        // 已安装 10047（含本 pending 对应版本），无论 DM 状态如何都应清理后走常规检查
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(10047L, pending, OtaDmStatus.Successful),
        )
    }

    @Test
    fun `DM 查无此下载视为陈旧残留需清理`() {
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(10046L, pending, OtaDmStatus.Missing),
        )
    }

    @Test
    fun `版本严格小于已安装也是陈旧残留`() {
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(10048L, pending, OtaDmStatus.Successful),
        )
    }

    @Test
    fun `Running 但版本已追平仍优先判陈旧`() {
        assertEquals(
            OtaRecoveryAction.DiscardStale,
            OtaRecoveryPolicy.decide(10047L, pending, OtaDmStatus.Running(0.5f)),
        )
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.update.OtaRecoveryPolicyTest"`
Expected: 编译失败（OtaPendingDownload/OtaRecoveryPolicy 未定义）

- [x] **Step 3: 最小实现**

```kotlin
package com.mamba.picme.domain.update

/** DataStore 侧记录的 pending 下载（enqueue 时写入，安装/清理时移除）。 */
data class OtaPendingDownload(
    val downloadId: Long,
    val versionCode: Long,
    val updatedAt: String,
)

/** 系统 DownloadManager 侧的下载状态快照。 */
sealed interface OtaDmStatus {
    data class Running(val progress: Float) : OtaDmStatus
    data object Successful : OtaDmStatus
    data class Failed(val reason: String) : OtaDmStatus
    data object Missing : OtaDmStatus
}

/** 冷启动恢复决策。 */
sealed interface OtaRecoveryAction {
    /** 无 pending 或无需恢复 → 继续常规 fetchLatest 检查。 */
    data object ProceedCheck : OtaRecoveryAction
    /** pending 陈旧（已安装该版本 / DM 记录丢失）→ 清理 pending 后走常规检查。 */
    data object DiscardStale : OtaRecoveryAction
    /** 下载进行中 → 静默（系统通知已在展示进度）。 */
    data object StaySilent : OtaRecoveryAction
    /** 下载完成且未安装 → 弹「已下载完成」对话框（不自动拉安装器）。 */
    data class OfferInstall(val versionCode: Long) : OtaRecoveryAction
    /** 下载失败 → 弹失败对话框可重试。 */
    data class OfferRetry(val reason: String) : OtaRecoveryAction
}

object OtaRecoveryPolicy {
    fun decide(
        installedVersionCode: Long,
        pending: OtaPendingDownload?,
        dmStatus: OtaDmStatus?,
    ): OtaRecoveryAction {
        if (pending != null && pending.versionCode <= installedVersionCode) {
            return OtaRecoveryAction.DiscardStale
        }
        if (pending == null || dmStatus == null) return OtaRecoveryAction.ProceedCheck
        return when (dmStatus) {
            is OtaDmStatus.Running -> OtaRecoveryAction.StaySilent
            is OtaDmStatus.Successful -> OtaRecoveryAction.OfferInstall(pending.versionCode)
            is OtaDmStatus.Failed -> OtaRecoveryAction.OfferRetry(dmStatus.reason)
            OtaDmStatus.Missing -> OtaRecoveryAction.DiscardStale
        }
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `./gradlew :androidApp:testDebugUnitTest --tests "com.mamba.picme.domain.update.OtaRecoveryPolicyTest"`
Expected: 9 tests PASS（卫语句 + subject when 保 sealed 穷举，无死 else）

- [x] **Step 5: Commit**（最终 c30cc90b9，经质量审查 amend：卫语句穷举+边界测试 9/9）

```bash
git add androidApp/src/main/java/com/mamba/picme/domain/update/OtaRecoveryPolicy.kt androidApp/src/test/java/com/mamba/picme/domain/update/OtaRecoveryPolicyTest.kt
git commit -m "feat(ota): 冷启动恢复判定 OtaRecoveryPolicy——pending×DM状态纯函数决策"
```

---

### Task 2: OtaUpdatePrefs 增加 pending 键

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/data/preferences/OtaUpdatePrefs.kt`

薄 DataStore 包装（该文件既有模式无测试，保持一致）。

- [x] **Step 1: 追加 pending 读写 API**

在 `markInstalledRemoteKey` 后追加（新增 import `com.mamba.picme.domain.update.OtaPendingDownload`、`longPreferencesKey`）：

```kotlin
    /** pending 后台下载记录（enqueue 时写入；安装完成/清理时移除）。 */
    suspend fun pendingDownload(): OtaPendingDownload? {
        val prefs = context.otaUpdateDataStore.data.first()
        val id = prefs[KEY_PENDING_ID] ?: return null
        val vc = prefs[KEY_PENDING_VC] ?: return null
        val updatedAt = prefs[KEY_PENDING_UPDATED_AT] ?: return null
        return OtaPendingDownload(id, vc, updatedAt)
    }

    suspend fun markPendingDownload(pending: OtaPendingDownload) {
        context.otaUpdateDataStore.edit { prefs ->
            prefs[KEY_PENDING_ID] = pending.downloadId
            prefs[KEY_PENDING_VC] = pending.versionCode
            prefs[KEY_PENDING_UPDATED_AT] = pending.updatedAt
        }
    }

    suspend fun clearPendingDownload() {
        context.otaUpdateDataStore.edit { prefs ->
            prefs.remove(KEY_PENDING_ID)
            prefs.remove(KEY_PENDING_VC)
            prefs.remove(KEY_PENDING_UPDATED_AT)
        }
    }
```

companion 追加：

```kotlin
        val KEY_PENDING_ID = longPreferencesKey("pending_download_id")
        val KEY_PENDING_VC = longPreferencesKey("pending_download_version_code")
        val KEY_PENDING_UPDATED_AT = stringPreferencesKey("pending_download_updated_at")
```

- [x] **Step 2: 编译确认**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 3: Commit**（4a782c23f，spec+质量双审通过）

```bash
git add androidApp/src/main/java/com/mamba/picme/data/preferences/OtaUpdatePrefs.kt
git commit -m "feat(ota): prefs 增加 pending 下载三元组(id/versionCode/updatedAt)"
```

---

### Task 3: OtaDownloadManager + FileProvider 路径

**Files:**
- Create: `androidApp/src/main/java/com/mamba/picme/data/update/OtaDownloadManager.kt`
- Modify: `androidApp/src/main/res/xml/ota_paths.xml`

系统 API 薄包装，无单测（恢复分支已由 Task 1 覆盖）。

- [x] **Step 1: 写 OtaDownloadManager**

```kotlin
package com.mamba.picme.data.update

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.mamba.picme.domain.update.OtaDmStatus
import java.io.File

/**
 * 系统 DownloadManager 包装：OTA APK 后台下载。
 * 落盘：getExternalFilesDir(DIRECTORY_DOWNLOADS)/ota/polang-<versionCode>.apk
 * （app 外部私有目录，FileProvider external-files-path 直接可装，零拷贝）。
 */
class OtaDownloadManager(private val context: Context) {

    private val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** 入队下载（成功后才清旧包，防 enqueue 失败丢旧包）。@throws IllegalStateException 外部存储不可用；@throws IllegalArgumentException 非 http(s) URI。 */
    fun enqueue(url: String, versionCode: Long, title: String): Long {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setTitle(title)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "ota/polang-$versionCode.apk")
        }
        val id = dm.enqueue(request)
        clearOldFiles(exceptVersionCode = versionCode)
        return id
    }

    fun status(downloadId: Long): OtaDmStatus {
        return dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
            if (!c.moveToFirst()) return OtaDmStatus.Missing
            when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> OtaDmStatus.Successful
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val soFar = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    OtaDmStatus.Running(if (total > 0) soFar.toFloat() / total else -1f)
                }
                DownloadManager.STATUS_FAILED -> OtaDmStatus.Failed(
                    c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) ?: "unknown",
                )
                else -> OtaDmStatus.Running(-1f)
            }
        }
    }

    fun localFileFor(versionCode: Long): File =
        File(File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ota"), "polang-$versionCode.apk")

    /** 删除下载记录与已落盘文件（公开 SDK 无 markDeleted；DiscardStale 清理场景正需要连文件一起删）。 */
    fun delete(downloadId: Long) {
        dm.remove(downloadId)
    }

    /** 清旧版本 APK 文件（保留 exceptVersionCode 指定版本）。 */
    fun clearOldFiles(exceptVersionCode: Long) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "ota")
        dir.listFiles()?.forEach { f ->
            val vc = Regex("polang-(\\d+)\\.apk").find(f.name)?.groupValues?.get(1)?.toLongOrNull()
            if (vc != null && vc != exceptVersionCode) f.delete()
        }
    }
}
```

- [x] **Step 2: ota_paths.xml 增加外部路径**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="ota" path="ota/" />
    <external-files-path name="ota_external" path="Download/ota/" />
</paths>
```

- [x] **Step 3: 编译确认**（markDeleted 非公开 API→dm.remove()；质量审查三项改进已 fold：clearOldFiles 后移/未知态→Running(-1f)/KDoc 契约）

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [x] **Step 4: Commit**（最终 46c6a835，spec+质量双审通过）

```bash
git add androidApp/src/main/java/com/mamba/picme/data/update/OtaDownloadManager.kt androidApp/src/main/res/xml/ota_paths.xml
git commit -m "feat(ota): 系统 DownloadManager 包装 + FileProvider 外部私有目录路径"
```

---

### Task 4: 五语 strings

**Files:**
- Modify: `androidApp/src/main/res/values/strings.xml`（1738-1748 区域）
- Modify: `androidApp/src/main/res/values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml`、`values-es/strings.xml`、`values-fr/strings.xml`（各自 ota_ 区域）

- [x] **Step 1: 全部语言同步改动**（五语 15 键齐平，逐字节 spec 核验 + 繁简零混用）

EN（values/strings.xml）：删除 `ota_downloading`；`ota_ready_to_install` 去掉占位符；新增 5 键：

```xml
    <string name="ota_ready_to_install">Update package downloaded. Install now?</string>
    <string name="ota_background_download_toast">Downloading in background. It will install when ready.</string>
    <string name="ota_dm_notification_title">PoLang Update Package</string>
    <string name="ota_complete_notification_title">Update ready to install</string>
    <string name="ota_complete_notification_text">Download finished. Tap to install.</string>
    <string name="ota_notification_channel_name">App updates</string>
```

zh-rCN：

```xml
    <string name="ota_ready_to_install">更新包已下载完成，现在安装吗？</string>
    <string name="ota_background_download_toast">已转入后台下载，完成后将自动安装</string>
    <string name="ota_dm_notification_title">PoLang 更新包</string>
    <string name="ota_complete_notification_title">更新已就绪</string>
    <string name="ota_complete_notification_text">下载完成，点击安装</string>
    <string name="ota_notification_channel_name">应用更新</string>
```

zh-rTW：

```xml
    <string name="ota_ready_to_install">更新包已下載完成，現在安裝嗎？</string>
    <string name="ota_background_download_toast">已轉入後台下載，完成後將自動安裝</string>
    <string name="ota_dm_notification_title">PoLang 更新包</string>
    <string name="ota_complete_notification_title">更新已就緒</string>
    <string name="ota_complete_notification_text">下載完成，點擊安裝</string>
    <string name="ota_notification_channel_name">應用更新</string>
```

es：

```xml
    <string name="ota_ready_to_install">Paquete de actualización descargado. ¿Instalar ahora?</string>
    <string name="ota_background_download_toast">Descargando en segundo plano. Se instalará al terminar.</string>
    <string name="ota_dm_notification_title">Paquete de actualización de PoLang</string>
    <string name="ota_complete_notification_title">Actualización lista para instalar</string>
    <string name="ota_complete_notification_text">Descarga completada. Toca para instalar.</string>
    <string name="ota_notification_channel_name">Actualizaciones de la app</string>
```

fr：

```xml
    <string name="ota_ready_to_install">Mise à jour téléchargée. Installer maintenant ?</string>
    <string name="ota_background_download_toast">Téléchargement en arrière-plan. Installation à la fin.</string>
    <string name="ota_dm_notification_title">Mise à jour de PoLang</string>
    <string name="ota_complete_notification_title">Mise à jour prête à installer</string>
    <string name="ota_complete_notification_text">Téléchargement terminé. Touchez pour installer.</string>
    <string name="ota_notification_channel_name">Mises à jour de l\'application</string>
```

（五语均删除 `ota_downloading`。）

- [x] **Step 2: Commit**（b9ffa8129，spec+质量双审通过；中间态编译红属预期，禁单独 cherry-pick）

```bash
git add androidApp/src/main/res/values*/strings.xml
git commit -m "i18n(ota): 后台下载五语文案——toast/完成通知/DM标题/渠道名，ready_to_install 去版本占位"
```

---

### Task 5: AppUpdateController 重构（状态机收敛 + enqueue + 广播 + 恢复）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/update/AppUpdateController.kt`（整文件重写）

- [x] **Step 1: 重写 controller**（经两轮审查修复：RECEIVER_EXPORTED/记账挪 launchInstall 发动点/policy 4 参 isStale/防双入队/IO 下放——终版见 commit 585d52ae3，此处代码块为初稿存档）

```kotlin
package com.mamba.picme.features.update

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
import kotlinx.coroutines.launch
import java.io.File

/** OTA 自更新状态机（UI 穷举渲染；下载进度由系统下载通知承担，无 Downloading 态）。 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState

    /** 检测到新版本，等待用户决策。 */
    data class Available(val info: AppLatestInfo) : AppUpdateState

    /** 下载完成待安装（AppLatestInfo 不可得——冷启动恢复路径无网络依赖）。 */
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
            if (intent?.action != android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            scope.launch { handleDownloadComplete(id) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            downloadCompleteReceiver,
            IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
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
                    false
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
```

注意：顶部补 `kotlinx.coroutines.flow.MutableStateFlow/StateFlow/asStateFlow` import（原文件已有，保留）。

- [x] **Step 2: 编译确认**

Run: `./gradlew :androidApp:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（此时 AppUpdateDialog/MainActivity 仍引用旧 API，会报错——本 Task 与 Task 6 同一原子编译单元；若报错属预期，直接进 Task 6 后一并验证）

- [x] **Step 3: Commit（与 Task 6 合并提交亦可，保持编译绿优先）**（eacdfc3f4；审查修复并入 585d52ae3）

```bash
git add androidApp/src/main/java/com/mamba/picme/features/update/AppUpdateController.kt
git commit -m "feat(ota): controller 重构——DM 后台下载/完成广播/冷启动恢复/前台拉装"
```

---

### Task 6: AppUpdateDialog 精简 + MainActivity 接线

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/features/update/AppUpdateDialog.kt`
- Modify: `androidApp/src/main/java/com/mamba/picme/MainActivity.kt:229-241`（OTA 区域）

> 质量审查台账：除 :96 的 `ota_downloading` 引用外，:118 的 `stringResource(R.string.ota_ready_to_install, state.info.versionName)` 死参数一并清除（下方重写代码天然覆盖）。

- [x] **Step 1: Dialog 移除 Downloading 分支与简化态渲染**（含 :96 ota_downloading 与 :118 死参数清理）

`AppUpdateDialog` when 分支删除 `Downloading`；删除 `DownloadingDialog` 与 `LinearProgressIndicator`/`Column`（若 AvailableDialog 仍用 Column/Spacer 则保留相应 import）`Downloading` 相关 import；`ReadyToInstallDialog` 文案改用无占位符 `ota_ready_to_install`：

```kotlin
        is AppUpdateState.ReadyToInstall -> ReadyToInstallDialog(
            state = current,
            onInstall = { controller.installNow() },
            onLater = { controller.dismiss() },
        )
        is AppUpdateState.Failed -> FailedDialog(
            reason = current.reason,
            onRetry = { controller.startDownload() },
            onCancel = { controller.dismiss() },
        )
```

```kotlin
@Composable
private fun ReadyToInstallDialog(
    state: AppUpdateState.ReadyToInstall,
    onInstall: () -> Unit,
    onLater: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.ota_update_title)) },
        text = { Text(stringResource(R.string.ota_ready_to_install)) },
        confirmButton = {
            TextButton(onClick = onInstall) { Text(stringResource(R.string.ota_install)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.ota_update_later)) }
        },
    )
}

@Composable
private fun FailedDialog(
    reason: String,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.ota_update_title)) },
        text = { Text(stringResource(R.string.ota_download_failed, reason)) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.ota_retry)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.ota_cancel)) }
        },
    )
}
```

- [x] **Step 2: MainActivity OTA 区域替换**（DisposableEffect 生命周期接线，LocalLifecycleOwner 用 androidx.lifecycle.compose 变体循项目惯例）

```kotlin
                    // OTA 自更新：冷启动恢复 pending 下载 → 静默检查（仅非 Play 渠道）；
                    // 后台下载由系统 DownloadManager 承担，完成后前台自动拉安装器
                    val appUpdateController = remember {
                        AppUpdateController(
                            applicationContext,
                            app.container.appUpdateClient,
                            app.container.otaUpdatePrefs,
                            scope,
                        )
                    }
                    val updateLifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(appUpdateController) {
                        val observer = LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_START -> appUpdateController.onAppForegroundChanged(true)
                                Lifecycle.Event.ON_STOP -> appUpdateController.onAppForegroundChanged(false)
                                else -> Unit
                            }
                        }
                        updateLifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            updateLifecycleOwner.lifecycle.removeObserver(observer)
                            appUpdateController.shutdown()
                        }
                    }
                    LaunchedEffect(Unit) { appUpdateController.checkForUpdate() }
                    AppUpdateDialog(appUpdateController)
```

（新增 import：`androidx.compose.runtime.DisposableEffect`、`androidx.lifecycle.Lifecycle`、`androidx.lifecycle.LifecycleEventObserver`、`androidx.compose.ui.platform.LocalLifecycleOwner`——按 MainActivity 既有 import 风格去重。）

- [x] **Step 3: 编译 + 全量单测**（最终 1558 tests / 0 失败，OtaRecoveryPolicyTest 11 用例）

Run: `./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest`
Expected: BUILD SUCCESSFUL，OtaRecoveryPolicyTest 11 用例（两轮审查修复后终态）+ 既有用例全绿

- [x] **Step 4: Commit**（c8fc07b65；Task5+6 经 spec ✅ + 质量 needs-fixes→修复两轮→approve ✅）

```bash
git add androidApp/src/main/java/com/mamba/picme/features/update/AppUpdateDialog.kt androidApp/src/main/java/com/mamba/picme/MainActivity.kt
git commit -m "feat(ota): 弹窗移除阻塞下载态 + 前后台感知接线——下载全量转后台"
```

---

### Task 7: 收尾验证

- [x] **Step 1: assembleDebug 完整构建**（BUILD SUCCESSFUL in 45s，2026-10-05）

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

- [x] **Step 2: 五语资源一致性人工核对**（五文件各 15 键齐平）

Run: `grep -c "ota_" androidApp/src/main/res/values*/strings.xml`
Expected: 五个文件 ota_ 键数一致（原 11 - ota_downloading + 5 新增 = 15）

- [x] **Step 3: 真机验收（下一个 debug 版本发布后，三场景）**（2026-10-06 经 1.0.47→1.0.48 两跳验收：①弹窗秒关+toast+系统通知进度 ②前台完成安装器自动弹 ③后台完成通知点击装，全过；1.0.48 装后下次冷启动静默确认记账闭环）

```bash
./scripts/ota-publish.sh --notes "后台下载+自动安装上线"
```

场景：① 前台等完成 → 系统安装器自动弹出；② 点更新后切出 App → 完成通知 → 点击安装；③ 下载中杀进程 → 冷启动静默恢复（下载中）或弹「已下载完成」。

- [ ] **Step 4: 最终 commit + push**

```bash
git push
```

---

## Self-Review 结论

- Spec 覆盖：enqueue/完成分发/冷启动恢复/清理/i18n/测试/通知兜底全部有对应 Task
- 无占位符；类型一致（OtaPendingDownload 三元组、OtaDmStatus 四态、OtaRecoveryAction 五动作在 Task 1/5 一致）
- 已知边界：Task 5 与 Task 6 是同一编译单元，中间编译红属预期，以 Task 6 Step 3 的全绿为准

## 审查延期决策记录（2026-10-05 终审归档）

| 项 | 内容 | 处置 |
|---|---|---|
| I1 | POST_NOTIFICATIONS 运行时申请（A13+ 后台完成通知可见性） | 延期：DM 进度通知为系统侧不受限；未授权最坏情形=后台完成后下次打开重弹 Available 重下；release 轨上前补 |
| M1 | 旋转重建双 receiver 重叠窗口 | 接受：后果良性（NEW_TASK 去重/同 ID 通知互替） |
| M3 | collectAsState vs collectAsStateWithLifecycle | 接受：对话框场景无害 |
| M5 | 会话内 DM 失败即时反馈 | 延期：DM 系统「下载不成功」通知兜底 + 冷启动恢复弹窗 |
| M6 | controller 单测（fake downloader 覆盖状态组合） | 延期：真机三场景验收（Task 7 Step 3）优先，验收后视回归情况补 |

## 终审结论（2026-10-05）

代码可合入、无阻断缺陷。spec 过期三处已修订（RECEIVER_EXPORTED/完成分发记账时点/policy 4 参签名）；P2 代码清理（dialog 未用参数、controller MagicNumber）随收尾 commit 处理。
