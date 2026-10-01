package com.mamba.picme.features.common.trash

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mamba.picme.R
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.trash.SilentTrashGuidancePolicy
import com.mamba.picme.domain.trash.TrashGuidanceDecision
import com.mamba.picme.domain.trash.TrashGuidanceGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「删除不再询问」删除现场一次性引导（[TrashGuidanceGate] 生产实现，AppContainer 单例）：
 * [com.mamba.picme.domain.trash.TrashSessionController] 静默快路径不可用且未引导过时挂起征询，
 * 弹窗 UI 由 [SilentTrashGuidanceDialog] 在 MainActivity 根渲染——全宿主（预览上滑删除 /
 * 滑动整理 / 整理中心）共享同一单例，弹一次、裁决一次（DataStore `media_manage_silent_trash_guided`）。
 */
class SilentTrashGuidanceController(
    private val appContext: Context,
    private val settingsRepository: UserSettingsRepository,
    private val scope: CoroutineScope,
    private val apiLevel: Int = Build.VERSION.SDK_INT,
    private val canManageMedia: () -> Boolean = {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MediaStore.canManageMedia(appContext)
    },
) : TrashGuidanceGate {

    sealed interface GuidanceState {
        data object Idle : GuidanceState
        data object Asking : GuidanceState
        data object AwaitingGrant : GuidanceState
    }

    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    private var pendingDecision: CompletableDeferred<TrashGuidanceDecision>? = null

    override suspend fun ask(): TrashGuidanceDecision {
        val alreadyAsked = settingsRepository.mediaManageSilentTrashGuidedFlow.first()
        val toggleOn = settingsRepository.mediaManageSilentTrashFlow.first()
        if (!SilentTrashGuidancePolicy.shouldAsk(apiLevel, alreadyAsked, toggleOn, canManageMedia())) {
            return TrashGuidanceDecision.KeepSystemConfirm
        }
        // 并发互斥：跨 VM 的多个 TrashSessionController 实例共享本单例，第二个 ask 直接回落 token 通路
        if (pendingDecision != null) return TrashGuidanceDecision.KeepSystemConfirm
        val deferred = CompletableDeferred<TrashGuidanceDecision>()
        pendingDecision = deferred
        _state.value = GuidanceState.Asking
        try {
            return deferred.await()
        } finally {
            // 调用方协程取消（宿主解绑/VM 销毁）时收起弹窗，不留孤儿 UI
            pendingDecision = null
            _state.value = GuidanceState.Idle
        }
    }

    /** 「开启」：写开关（授权晚到也自然生效）→ 已持权立即生效；未持权跳系统授权页等待返回复查。 */
    fun onEnable() {
        val deferred = pendingDecision ?: return
        scope.launch {
            runCatching {
                settingsRepository.updateMediaManageSilentTrash(true)
                settingsRepository.updateMediaManageSilentTrashGuided(true)
            }
            if (canManageMedia()) {
                deferred.complete(TrashGuidanceDecision.Enabled)
            } else {
                _state.value = GuidanceState.AwaitingGrant
                launchGrantRequest(deferred)
            }
        }
    }

    /** 「保持确认」/ 关闭弹窗：标记已引导（一次性），本次回落系统确认。 */
    fun onDecline() {
        val deferred = pendingDecision ?: return
        scope.launch {
            runCatching { settingsRepository.updateMediaManageSilentTrashGuided(true) }
            deferred.complete(TrashGuidanceDecision.KeepSystemConfirm)
        }
    }

    /** 从系统授权页返回（ON_RESUME 复查授权）：到位即静默生效，否则本次回落系统确认。 */
    fun onGrantScreenResult(granted: Boolean) {
        if (_state.value != GuidanceState.AwaitingGrant) return
        pendingDecision?.complete(
            if (granted) TrashGuidanceDecision.Enabled else TrashGuidanceDecision.KeepSystemConfirm
        )
    }

    private fun launchGrantRequest(deferred: CompletableDeferred<TrashGuidanceDecision>) {
        val intent = Intent(
            Settings.ACTION_REQUEST_MANAGE_MEDIA,
            Uri.parse("package:${appContext.packageName}"),
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val launched = runCatching { appContext.startActivity(intent) }.isSuccess
        if (!launched) {
            Log.w(TAG, "ACTION_REQUEST_MANAGE_MEDIA launch failed")
            deferred.complete(TrashGuidanceDecision.KeepSystemConfirm)
        }
    }

    private companion object {
        const val TAG = "PoLang:SilentTrash"
    }
}

/**
 * 全局一次性引导弹窗，挂 MainActivity 内容根（覆盖所有删除宿主）。
 * AwaitingGrant 态按钮置灰等待系统授权页返回复查。
 */
@Composable
fun SilentTrashGuidanceDialog(controller: SilentTrashGuidanceController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 用户从系统媒体管理授权页返回时复查授权（ON_RESUME）
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                controller.state.value == SilentTrashGuidanceController.GuidanceState.AwaitingGrant
            ) {
                val granted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    MediaStore.canManageMedia(context)
                controller.onGrantScreenResult(granted)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val asking = state == SilentTrashGuidanceController.GuidanceState.Asking
    when (state) {
        SilentTrashGuidanceController.GuidanceState.Idle -> Unit
        SilentTrashGuidanceController.GuidanceState.Asking,
        SilentTrashGuidanceController.GuidanceState.AwaitingGrant,
        -> AlertDialog(
            onDismissRequest = { if (asking) controller.onDecline() },
            title = { Text(stringResource(R.string.silent_trash_guide_title)) },
            text = { Text(stringResource(R.string.silent_trash_guide_message)) },
            confirmButton = {
                TextButton(
                    onClick = controller::onEnable,
                    enabled = asking,
                ) { Text(stringResource(R.string.silent_trash_guide_enable)) }
            },
            dismissButton = {
                TextButton(
                    onClick = controller::onDecline,
                    enabled = asking,
                ) { Text(stringResource(R.string.silent_trash_guide_keep)) }
            },
        )
    }
}
