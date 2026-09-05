package com.mamba.picme.domain.trash

import com.mamba.picme.domain.dedup.DedupTrashManager

/** TrashBackend 生产实现：委托 DedupTrashManager（IntentSender 作为 token 透传）。 */
class DedupTrashBackend(private val trashManager: DedupTrashManager) : TrashBackend {
    override val isSupported: Boolean get() = trashManager.isSupported
    override fun buildTrashToken(uris: List<String>): Any = trashManager.buildTrashIntent(uris)
    override fun buildRestoreToken(uris: List<String>): Any = trashManager.buildRestoreIntent(uris)
    override fun queryExisting(uris: List<String>): List<String> = trashManager.queryExisting(uris)
}
