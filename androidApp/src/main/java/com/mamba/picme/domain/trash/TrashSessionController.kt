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

    /**
     * 静默回收快路径（MANAGE_MEDIA 持权 + 用户开关开）：直写 IS_TRASHED 零系统弹框。
     * 返回 null 表示快路径不可用（开关关 / 无权限），调用方回落系统授权框；
     * 非 null 为实际回收成功集（空集 ≠ null：快路径已生效但全部写失败）。
     */
    suspend fun trySilentTrash(uris: List<String>): List<String>?
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
 * API<30（!isSupported）不拉起授权：errorEvent 置位（UI snackbar）+ [TrashOutcome.Unsupported]
 * 入流（VM 编排层据此回滚在途提交状态，2026-09-05 F2 审查修复——此前仅 errorEvent，VM 侧
 * commitInFlight/finishing 永久悬挂死锁）。
 * 静默快路径（2026-09-08，MANAGE_MEDIA）：trash 请求在 token 构建前先试
 * [TrashBackend.trySilentTrash]——用户开关「删除不再询问」开 + 持 MANAGE_MEDIA（API 31+）时
 * 直写 IS_TRASHED 零弹框，Trashed 直接入流、不产生 pendingRequest；返回 null 回落系统授权框通路。
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

    /** 授权结果流（含 Cancelled / Unsupported）；buffer 8 防连点丢事件。 */
    private val _outcomes = MutableSharedFlow<TrashOutcome>(extraBufferCapacity = 8)
    val outcomes: SharedFlow<TrashOutcome> = _outcomes

    val isSupported: Boolean get() = backend.isSupported

    /**
     * token 构建在途标志（2026-09-08 审查修复单槽竞态）：request 同步置位，
     * 构建协程落定（成功置 pending / 失败 / 被取消）时清位；在途期间后续 request 一律拒绝，
     * 杜绝「同步查 pending 为空 → 异步构建窗口内第二次 request 覆盖第一个 pending」的错配。
     */
    private var requestInFlight = false
    private var requestInFlightTag: String? = null
    private var requestInFlightCancelled = false

    fun requestTrash(uris: List<String>, tag: String? = null) =
        request(uris, isRestore = false, tag = tag)

    fun requestRestore(uris: List<String>) =
        request(uris, isRestore = true, tag = null)

    private fun request(uris: List<String>, isRestore: Boolean, tag: String?) {
        if (uris.isEmpty() || _pendingRequest.value != null || requestInFlight) return
        if (!backend.isSupported) {
            // API<30：errorEvent 供 UI snackbar；Unsupported outcome 供 VM 编排层回滚在途提交状态
            _errorEvent.value = true
            _outcomes.tryEmit(TrashOutcome.Unsupported)
            return
        }
        requestInFlight = true
        requestInFlightTag = tag
        requestInFlightCancelled = false
        scope.launch {
            // 静默快路径（仅 trash）：MANAGE_MEDIA 持权 + 用户开关开 → 直写 IS_TRASHED，
            // 完全绕过 pendingRequest/系统授权框；返回 null（开关关/无权限/执行异常）回落既有 token 通路
            if (!isRestore) {
                val silentTrashed = withContext(ioDispatcher) {
                    runCatching { backend.trySilentTrash(uris) }.getOrNull()
                }
                if (silentTrashed != null) {
                    val silentCancelled = requestInFlightCancelled
                    requestInFlight = false
                    requestInFlightTag = null
                    requestInFlightCancelled = false
                    if (silentCancelled) {
                        // 宿主在静默执行期间解绑：已回收不回滚（回收站 30 天可恢复），
                        // 按 Cancelled 结算供编排层复位在途状态（与 token 在途取消同语义）
                        _outcomes.tryEmit(TrashOutcome.Cancelled)
                    } else {
                        // 部分/全部写失败沿用 partialNotice 语义（对齐授权回流残留复查口径）
                        if (silentTrashed.size < uris.size) _partialNotice.value = true
                        _outcomes.emit(TrashOutcome.Trashed(silentTrashed, tag))
                    }
                    return@launch
                }
            }
            val token = withContext(ioDispatcher) {
                runCatching {
                    if (isRestore) backend.buildRestoreToken(uris) else backend.buildTrashToken(uris)
                }.getOrNull()
            }
            val cancelled = requestInFlightCancelled
            requestInFlight = false
            requestInFlightTag = null
            requestInFlightCancelled = false
            when {
                // 宿主在 token 构建期间解绑取消：不落 pending，按 Cancelled 结算（防单槽悬挂）
                cancelled -> _outcomes.tryEmit(TrashOutcome.Cancelled)
                token == null -> _errorEvent.value = true
                else -> _pendingRequest.value = PendingTrashRequest(uris, token, isRestore, tag)
            }
        }
    }

    /**
     * tag 宿主解绑清理（2026-09-08 审查修复悬挂）：仅清空匹配 [tag] 的 pending；
     * token 仍在构建则标记取消（构建完成后不落 pending）。两种情况均按 Cancelled 入流——
     * 用户未响应即视为取消，调用方编排层据此回滚在途状态。
     */
    fun cancelPendingRequest(tag: String) {
        val pending = _pendingRequest.value
        if (pending != null && pending.tag == tag) {
            _pendingRequest.value = null
            _outcomes.tryEmit(TrashOutcome.Cancelled)
        }
        if (requestInFlight && requestInFlightTag == tag) {
            requestInFlightCancelled = true
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
