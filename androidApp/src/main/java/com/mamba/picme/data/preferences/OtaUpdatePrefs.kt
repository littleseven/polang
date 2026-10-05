package com.mamba.picme.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mamba.picme.domain.update.OtaPendingDownload
import kotlinx.coroutines.flow.first

private val Context.otaUpdateDataStore by preferencesDataStore(name = "ota_update")

/**
 * OTA 自更新内部状态：本机已安装的远程构建标识（versionCode@updatedAt）。
 * 支撑同 versionCode 重发包的更新判定（见 domain/update/AppUpdateChecker）。
 */
class OtaUpdatePrefs(private val context: Context) {

    suspend fun installedRemoteKey(): String? =
        context.otaUpdateDataStore.data.first()[KEY_INSTALLED_REMOTE]

    suspend fun markInstalledRemoteKey(key: String) {
        context.otaUpdateDataStore.edit { prefs -> prefs[KEY_INSTALLED_REMOTE] = key }
    }

    /** pending 后台下载记录（enqueue 时写入；安装完成/清理时移除）。updatedAt 为 COS lastModified 字符串。 */
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

    private companion object {
        val KEY_INSTALLED_REMOTE = stringPreferencesKey("installed_remote_key")
        val KEY_PENDING_ID = longPreferencesKey("pending_download_id")
        val KEY_PENDING_VC = longPreferencesKey("pending_download_version_code")
        val KEY_PENDING_UPDATED_AT = stringPreferencesKey("pending_download_updated_at")
    }
}
