package com.mamba.picme.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地回合 OpenUI 回滚闸（spec《本地回合 OpenUI 统一》U2-8）DataStore 读写契约：
 * 默认 true；写 false 后 flow 发射 false；写回 true 恢复。
 *
 * 单测试方法承载全序列：`Context.dataStore` 委托为 classloader 级单例，
 * 拆多方法会共享同一持久化文件造成跨方法串扰（默认值断言被前序写污染）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UserPreferencesRepositoryLocalTurnOpenUiTest {

    @Test
    fun localTurnOpenUiDefaultsTrueAndWritePropagates() = runTest {
        val repository = UserPreferencesRepository(ApplicationProvider.getApplicationContext<Context>())

        assertEquals(true, repository.localTurnOpenUiEnabledFlow.first())

        repository.updateLocalTurnOpenUiEnabled(false)
        assertEquals(false, repository.localTurnOpenUiEnabledFlow.first())

        repository.updateLocalTurnOpenUiEnabled(true)
        assertEquals(true, repository.localTurnOpenUiEnabledFlow.first())
    }
}
