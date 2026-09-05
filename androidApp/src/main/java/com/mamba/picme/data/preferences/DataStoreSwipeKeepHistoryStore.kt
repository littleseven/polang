package com.mamba.picme.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mamba.picme.domain.swipe.SwipeKeepHistoryStore
import kotlinx.coroutines.flow.first

/**
 * [SwipeKeepHistoryStore] 的 DataStore 生产实现：复用 user_preferences 存储，
 * key = `swipe_keep_history` stringSet（条目 `"uri|epochMs"`，30 天 TTL 裁剪由
 * 领域层 [com.mamba.picme.domain.swipe.SwipeKeepHistory] 纯函数负责）。
 */
class DataStoreSwipeKeepHistoryStore(private val context: Context) : SwipeKeepHistoryStore {

    private val key = stringSetPreferencesKey("swipe_keep_history")

    override suspend fun load(): Set<String> =
        context.dataStore.data.first()[key] ?: emptySet()

    override suspend fun save(entries: Set<String>) {
        context.dataStore.edit { prefs -> prefs[key] = entries }
    }
}
