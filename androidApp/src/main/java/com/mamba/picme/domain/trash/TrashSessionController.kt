package com.mamba.picme.domain.trash

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** MediaStore 回收站 IPC 抽象；生产实现包装 DedupTrashManager（IntentSender 即 token）。 */
interface TrashBackend {
    val isSupported: Boolean
    fun buildTrashToken(uris: List<String>): Any
    fun buildRestoreToken(uris: List<String>): Any
    fun queryExisting(uris: List<String>): List<String>
}

data class PendingTrashRequest(
    val uris: List<String>,
    val token: Any,
    val isRestore: Boolean,
    val tag: String?,
)

sealed interface TrashOutcome {
    data class Trashed(val trashedUris: List<String>, val tag: String?) : TrashOutcome
    data class Restored(val restoredUris: List<String>) : TrashOutcome
    data object Cancelled : TrashOutcome
    data object Unsupported : TrashOutcome
}

/**
 * 通用回收站删除/恢复编排（从 DedupViewModel 提炼，供整理中心/手势整理复用）。
 * 线程：build 走 ioDispatcher；pendingRequest 由 UI 层以 StartIntentSenderForResult 拉起；
 * queryExisting 复查在授权回调线程同步执行（纯 MediaStore 单 uri 查询，耗时微秒级）。
 */
class TrashSessionController(
    private val backend: TrashBackend,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val _pendingRequest = MutableStateFlow<PendingTrashRequest?>(null)
    val pendingRequest: StateFlow<PendingTrashRequest?> = _pendingRequest

    private val _partialNotice = MutableStateFlow(false)
    val partialNotice: StateFlow<Boolean> = _partialNotice

    private var errorEvent = false

    val isSupported: Boolean get() = backend.isSupported

    fun requestTrash(uris: List<String>, tag: String? = null) =
        request(uris, isRestore = false, tag = tag)

    fun requestRestore(uris: List<String>) =
        request(uris, isRestore = true, tag = null)

    private fun request(uris: List<String>, isRestore: Boolean, tag: String?) {
        if (uris.isEmpty() || _pendingRequest.value != null) return
        if (!backend.isSupported) {
            errorEvent = true
            return
        }
        scope.launch {
            val token = withContext(ioDispatcher) {
                runCatching {
                    if (isRestore) backend.buildRestoreToken(uris) else backend.buildTrashToken(uris)
                }.getOrNull()
            }
            if (token == null) {
                errorEvent = true
            } else {
                _pendingRequest.value = PendingTrashRequest(uris, token, isRestore, tag)
            }
        }
    }

    /** UI 授权回调。ok=false → Cancelled；ok=true → 复查 IS_TRASHED 残留。 */
    fun onTrashResult(ok: Boolean): TrashOutcome {
        val pending = _pendingRequest.value ?: return TrashOutcome.Cancelled
        _pendingRequest.value = null
        if (!ok) return TrashOutcome.Cancelled
        val remaining = backend.queryExisting(pending.uris)
        val trashed = pending.uris - remaining.toSet()
        if (remaining.isNotEmpty()) _partialNotice.value = true
        return TrashOutcome.Trashed(trashed, pending.tag)
    }

    fun onRestoreResult(ok: Boolean): TrashOutcome {
        val pending = _pendingRequest.value ?: return TrashOutcome.Cancelled
        _pendingRequest.value = null
        if (!ok) return TrashOutcome.Cancelled
        // queryExisting 返回「已回到库中（未 trash）」的 uri，即恢复成功项
        return TrashOutcome.Restored(backend.queryExisting(pending.uris))
    }

    fun consumePartialNotice() {
        _partialNotice.value = false
    }

    fun consumeErrorEvent(): Boolean = errorEvent.also { errorEvent = false }
}
