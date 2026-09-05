package com.mamba.picme.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.mamba.picme.domain.memories.MemoryHiddenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [MemoryHiddenStore] 的 DataStore 生产实现：复用 user_preferences 存储，
 * key = `memory_hidden_ids` stringSet（条目为 Memory.id）。
 */
class DataStoreMemoryHiddenStore(private val context: Context) : MemoryHiddenStore {

    private val key = stringSetPreferencesKey("memory_hidden_ids")

    override val ids: Flow<Set<String>> =
        context.dataStore.data.map { prefs -> prefs[key] ?: emptySet() }

    override suspend fun hide(id: String) {
        context.dataStore.edit { prefs -> prefs[key] = (prefs[key] ?: emptySet()) + id }
    }
}
