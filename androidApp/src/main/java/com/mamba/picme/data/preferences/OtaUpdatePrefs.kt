package com.mamba.picme.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    private companion object {
        val KEY_INSTALLED_REMOTE = stringPreferencesKey("installed_remote_key")
    }
}
