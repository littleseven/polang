package com.mamba.picme.domain.update

/**
 * OTA 远程构建标识：versionCode + 上传时间（COS lastModified）。
 * 同 versionCode 重发是遛狗主路径（不 bump 版本号直接重打），
 * 所以构建身份 = versionCode@updatedAt，而非仅 versionCode。
 */
data class RemoteBuild(
    val versionCode: Long,
    val updatedAt: String,
) {
    val key: String get() = "$versionCode@$updatedAt"
}

object AppUpdateChecker {

    const val PLAY_STORE_INSTALLER = "com.android.vending"

    /**
     * 更新可用判定：
     * - versionCode 更高 → 有新版；
     * - versionCode 相等 → 仅当本机 OTA 装过旧构建（installedRemoteKey 非空）且远程是重发的新构建；
     *   从未 OTA 安装过（null）时不提示，避免 adb 基线包被同版本号远程包打扰；
     * - versionCode 更低 → 无更新。
     */
    fun isUpdateAvailable(
        remote: RemoteBuild,
        currentVersionCode: Long,
        installedRemoteKey: String?,
    ): Boolean = when {
        remote.versionCode > currentVersionCode -> true
        remote.versionCode < currentVersionCode -> false
        else -> installedRemoteKey != null && remote.key != installedRemoteKey
    }

    /**
     * 自更新渠道判定：Play 商店安装的包（com.android.vending）禁止自更新
     * （Google Play 政策：不得绕过 Play 更新机制）；adb（null）/系统安装器/厂商安装器均放行。
     */
    fun isSelfUpdateAllowed(installerPackageName: String?): Boolean =
        installerPackageName != PLAY_STORE_INSTALLER
}
