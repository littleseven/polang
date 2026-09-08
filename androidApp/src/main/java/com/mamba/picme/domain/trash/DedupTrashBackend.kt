package com.mamba.picme.domain.trash

import com.mamba.picme.domain.dedup.DedupTrashManager

/**
 * TrashBackend 生产实现：委托 DedupTrashManager（IntentSender 作为 token 透传）。
 * [silentTrashEnabled] 由组合根注入（读用户偏好「删除不再询问」开关），
 * 开关开 + 持 MANAGE_MEDIA 时 [trySilentTrash] 直写 IS_TRASHED，零系统弹框。
 */
class DedupTrashBackend(
    private val trashManager: DedupTrashManager,
    private val silentTrashEnabled: suspend () -> Boolean = { false },
) : TrashBackend {
    override val isSupported: Boolean get() = trashManager.isSupported
    override fun buildTrashToken(uris: List<String>): Any = trashManager.buildTrashIntent(uris)
    override fun buildRestoreToken(uris: List<String>): Any = trashManager.buildRestoreIntent(uris)
    override fun queryExisting(uris: List<String>): List<String> = trashManager.queryExisting(uris)

    override suspend fun trySilentTrash(uris: List<String>): List<String>? {
        if (!silentTrashEnabled() || !trashManager.canManageMedia()) return null
        return trashManager.silentTrash(uris)
    }
}
