package com.mamba.picme.domain.repository

import android.content.IntentSender

/**
 * Android 侧 [MediaRepository] 扩展接口。
 *
 * 单条恢复性删除的 IntentSender 通路是 Android 10 (API 29)
 * RecoverableSecurityException 专有机制，无法进入 shared commonMain 接口，
 * 由本接口承载；实现类为 data 层的 MediaRepositoryImpl。
 */
interface AndroidMediaRepository : MediaRepository {

    /**
     * 获取 Android 10 (API 29) 的单条恢复性删除 IntentSender
     */
    fun getPendingRecoverableIntentSender(): IntentSender?

    /**
     * 清除 Android 10 的恢复性删除状态
     */
    fun clearPendingRecoverable()

    /** 记录媒体在查看器被打开（整理中心 v2 价值保护「用户互动」信号）。 */
    suspend fun markMediaViewed(uri: String)
}
