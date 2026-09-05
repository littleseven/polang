package com.mamba.picme.domain.trash

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
 * 授权回调 [onTrashResult]/[onRestoreResult] 只同步清 pending（授权回调在主线程），
 * queryExisting 残留复查（逐 uri ContentResolver binder IPC，批量可达成百上千次）
 * 移入 ioDispatcher 执行（[PERF] 红线，2026-09-05 审查修复），结果经 [outcomes] 流抛出。
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

    /** 一次性错误事件（token 构建失败 / API<30 不支持）：置位 → UI 消费 → [consumeErrorEvent]。 */
    private val _errorEvent = MutableStateFlow(false)
    val errorEvent: StateFlow<Boolean> = _errorEvent

    /** 授权结果流（含 Cancelled）；buffer 8 防连点丢事件。 */
    private val _outcomes = MutableSharedFlow<TrashOutcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<TrashOutcome> = _outcomes

    val isSupported: Boolean get() = backend.isSupported

    fun requestTrash(uris: List<String>, tag: String? = null) =
        request(uris, isRestore = false, tag = tag)

    fun requestRestore(uris: List<String>) =
        request(uris, isRestore = true, tag = null)

    private fun request(uris: List<String>, isRestore: Boolean, tag: String?) {
        if (uris.isEmpty() || _pendingRequest.value != null) return
        if (!backend.isSupported) {
            _errorEvent.value = true
            return
        }
        scope.launch {
            val token = withContext(ioDispatcher) {
                runCatching {
                    if (isRestore) backend.buildRestoreToken(uris) else backend.buildTrashToken(uris)
                }.getOrNull()
            }
            if (token == null) {
                _errorEvent.value = true
            } else {
                _pendingRequest.value = PendingTrashRequest(uris, token, isRestore, tag)
            }
        }
    }

    /** UI 授权回调：同步清 pending；ok=false → Cancelled 直接入流；ok=true → ioDispatcher 复查残留后入流。 */
    fun onTrashResult(ok: Boolean) {
        val pending = _pendingRequest.value ?: return
        _pendingRequest.value = null
        if (!ok) {
            _outcomes.tryEmit(TrashOutcome.Cancelled)
            return
        }
        scope.launch {
            val remaining = withContext(ioDispatcher) { backend.queryExisting(pending.uris) }
            val trashed = pending.uris - remaining.toSet()
            if (remaining.isNotEmpty()) _partialNotice.value = true
            _outcomes.emit(TrashOutcome.Trashed(trashed, pending.tag))
        }
    }

    fun onRestoreResult(ok: Boolean) {
        val pending = _pendingRequest.value ?: return
        _pendingRequest.value = null
        if (!ok) {
            _outcomes.tryEmit(TrashOutcome.Cancelled)
            return
        }
        scope.launch {
            // queryExisting 返回「已回到库中（未 trash）」的 uri，即恢复成功项
            val restored = withContext(ioDispatcher) { backend.queryExisting(pending.uris) }
            _outcomes.emit(TrashOutcome.Restored(restored))
        }
    }

    fun consumePartialNotice() {
        _partialNotice.value = false
    }

    fun consumeErrorEvent() {
        _errorEvent.value = false
    }
}
