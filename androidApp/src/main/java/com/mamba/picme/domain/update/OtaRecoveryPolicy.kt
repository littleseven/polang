package com.mamba.picme.domain.update

/** DataStore 侧记录的 pending 下载（enqueue 时写入，安装/清理时移除）。 */
data class OtaPendingDownload(
    val downloadId: Long,
    val versionCode: Long,
    /** COS lastModified 字符串，语义同 RemoteBuild.updatedAt。 */
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
    /** pending 陈旧（已装更高版本或同版本同构建 / DM 记录丢失）→ 清理 pending 后走常规检查。 */
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
        installedRemoteKey: String?,
        pending: OtaPendingDownload?,
        dmStatus: OtaDmStatus?,
    ): OtaRecoveryAction {
        if (pending != null && isStale(pending, installedVersionCode, installedRemoteKey)) {
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

    /**
     * 陈旧 = 已装版本严格更高；或同 versionCode 且就是本机已装的那个构建（key 相同）。
     * 同 vc 重发（key 不同）不算陈旧——那是遛狗主路径的在途更新。
     */
    private fun isStale(
        pending: OtaPendingDownload,
        installedVersionCode: Long,
        installedRemoteKey: String?,
    ): Boolean = pending.versionCode < installedVersionCode ||
        (pending.versionCode == installedVersionCode &&
            RemoteBuild(pending.versionCode, pending.updatedAt).key == installedRemoteKey)
}
