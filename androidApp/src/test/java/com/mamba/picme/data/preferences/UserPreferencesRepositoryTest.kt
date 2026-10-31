package com.mamba.picme.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [UserPreferencesRepository] GenUI 线格式缺省行为单测（2026-11-01 产品决定：默认线切 OpenUI）：
 * - 未设置 → "openui"
 * - 未知存量值 → 归一 "openui"
 * - 显式存量 "a2ui" → 保留不翻
 */
@RunWith(RobolectricTestRunner::class)
class UserPreferencesRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repository = UserPreferencesRepository(context)

    @Before
    fun resetDataStore() {
        // 顶层 Context.dataStore 是进程级单例，用 edit 清空保证用例间互不污染
        runBlocking { context.dataStore.edit { preferences -> preferences.clear() } }
    }

    @Test
    fun `unset genui format defaults to openui`() = runBlocking {
        assertEquals("openui", repository.genUiFormatFlow.first())
    }

    @Test
    fun `unknown genui format normalizes to openui`() = runBlocking {
        // 直写原始脏值（绕过 update 归一），验证读取路径的归一化
        context.dataStore.edit { preferences ->
            preferences[stringPreferencesKey("genui_format")] = "weird"
        }
        assertEquals("openui", repository.genUiFormatFlow.first())
    }

    @Test
    fun `explicit a2ui preference is preserved`() = runBlocking {
        repository.updateGenUiFormat("a2ui")
        assertEquals("a2ui", repository.genUiFormatFlow.first())
    }
}
