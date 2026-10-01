package com.mamba.picme.util.permission

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger

/**
 * 后台扫描保活门控。
 *
 * 扫描跑在 `dataSync` 前台服务 + PARTIAL_WAKE_LOCK 上，原生 AOSP 足以保活；但
 * HyperOS / MIUI 等 ROM 对「退后台 + 息屏」的 app 有独立冻结策略，会冻结整个进程
 * 导致扫描暂停（亮屏解冻即续跑）。引导用户完成「电池优化白名单 + 通知 + 自启动」
 * 配置可显著降低被冻结的概率。
 *
 * MIUI/HyperOS 不暴露「自启动是否已允许」的读取 API，故只要判定为 MIUI 即把该项
 * 列为「请确认」，由用户进入自启动管理页核对。
 *
 * 另：加入电池优化白名单不仅是防冻结的根治手段，也是后续 onTimeout 兜底中
 * AlarmManager 闹钟重启前台服务的前提（Android 12+ 后台启动 FGS 需白名单豁免）。
 */
object BackgroundScanGuard {

    private const val TAG = "BackgroundScanGuard"
    private const val PREFS_NAME = "picme_bg_scan_guard"
    private const val KEY_DONT_SHOW = "dont_show_dialog"

    enum class IssueType { BATTERY_OPTIMIZATION, NOTIFICATIONS, MIUI_AUTOSTART }

    data class Issue(
        val type: IssueType,
        /** 用于在弹窗 / 提示条中展示该项名称 */
        val titleRes: Int,
        /** 目标设置页上的具体操作指引（弹窗行副标题） */
        val hintRes: Int,
        /** 点击该项后跳转的修复动作 */
        val openFix: (Context) -> Unit
    )

    /**
     * 诊断当前影响后台扫描的缺失项。
     */
    fun diagnose(context: Context): List<Issue> {
        return evaluate(
            batteryOk = BatteryOptimizationUtils.isIgnoringBatteryOptimizations(context),
            notificationsOk = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            isMiui = MiuiPermissionUtils.isMiui()
        ).map { type -> type.toIssue() }
    }

    /**
     * 纯逻辑：根据三项事实返回缺失项类型列表。抽出为纯函数便于 JVM 单测。
     *
     * 顺序固定为 BATTERY → NOTIFICATIONS → MIUI_AUTOSTART，保证 UI 展示稳定。
     */
    fun evaluate(batteryOk: Boolean, notificationsOk: Boolean, isMiui: Boolean): List<IssueType> {
        val result = mutableListOf<IssueType>()
        if (!batteryOk) result += IssueType.BATTERY_OPTIMIZATION
        if (!notificationsOk) result += IssueType.NOTIFICATIONS
        if (isMiui) result += IssueType.MIUI_AUTOSTART
        return result
    }

    /**
     * 纯逻辑：弹窗 checklist 单行是否已处理（ON_RESUME 复查打勾依据）。
     * 电池/通知按系统事实（[stillMissing] = 最新 diagnose 仍缺失）；MIUI 自启动无读取 API，
     * 按用户是否已点击进入过确认页（会话级 [visited]）。
     */
    fun isRowResolved(type: IssueType, stillMissing: Boolean, visited: Boolean): Boolean =
        if (type == IssueType.MIUI_AUTOSTART) visited else !stillMissing

    /**
     * 是否应展示引导弹窗（用户未选「不再提醒」时为 true）。
     */
    fun shouldShowDialog(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getBoolean(KEY_DONT_SHOW, false)
    }

    /**
     * 标记用户选择「不再提醒」。
     */
    fun doNotShowAgain(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DONT_SHOW, true)
            .apply()
    }

    private fun IssueType.toIssue(): Issue = when (this) {
        IssueType.BATTERY_OPTIMIZATION -> Issue(
            type = this,
            titleRes = R.string.bg_scan_guard_issue_battery,
            hintRes = R.string.bg_scan_guard_hint_battery,
            openFix = { ctx -> BatteryOptimizationUtils.requestIgnoreBatteryOptimizations(ctx) }
        )
        IssueType.NOTIFICATIONS -> Issue(
            type = this,
            titleRes = R.string.bg_scan_guard_issue_notifications,
            hintRes = R.string.bg_scan_guard_hint_notifications,
            openFix = { ctx -> openNotificationSettings(ctx) }
        )
        IssueType.MIUI_AUTOSTART -> Issue(
            type = this,
            titleRes = R.string.bg_scan_guard_issue_miui_autostart,
            hintRes = R.string.bg_scan_guard_hint_miui_autostart,
            openFix = { ctx -> MiuiPermissionUtils.openMiuiAutoStart(ctx) }
        )
    }

    private fun openNotificationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to open notification settings, fallback to app info", e)
            MiuiPermissionUtils.openAppInfo(context)
        }
    }
}
