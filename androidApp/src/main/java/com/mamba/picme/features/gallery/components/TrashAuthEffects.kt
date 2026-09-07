package com.mamba.picme.features.gallery.components

import android.app.Activity
import android.content.IntentSender
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mamba.picme.R
import com.mamba.picme.domain.trash.TrashSessionController

/** 预览页上滑删除的宿主分桶 tag：Activity 级共享 controller 时按 tag 路由授权拉起与 outcome。 */
const val TRASH_TAG_PREVIEW_GALLERY = "preview_swipe_gallery"
const val TRASH_TAG_PREVIEW_CHAT = "preview_swipe_chat"
const val TRASH_TAG_PREVIEW_MEMORY = "preview_swipe_memory"

/**
 * 回收站授权拉起 + 一次性事件提示（自 SwipeReviewScreen 抽出共享）：
 * pendingRequest → StartIntentSenderForResult（isRestore 分流）；
 * partialNotice / errorEvent → 置位即消费，有 snackbarHostState 走 snackbar，否则 Toast。
 *
 * [tag] 非 null 时只处理该 tag 的 pendingRequest——Activity 级共享 [TrashSessionController]
 * 被多个宿主同时挂载（如主页面 Pager 相邻页）时，避免同一授权被重复拉起。
 */
@Composable
fun TrashAuthEffects(
    controller: TrashSessionController,
    snackbarHostState: SnackbarHostState? = null,
    tag: String? = null,
) {
    val context = LocalContext.current
    val pendingRequest by controller.pendingRequest.collectAsState()
    val partialNotice by controller.partialNotice.collectAsState()
    val trashError by controller.errorEvent.collectAsState()

    val trashLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        controller.onTrashResult(result.resultCode == Activity.RESULT_OK)
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        controller.onRestoreResult(result.resultCode == Activity.RESULT_OK)
    }

    // 宿主解绑悬挂防护：本 tag 的 pending 无人拉起时清空（按 Cancelled 结算），
    // 防单槽被永久占用导致后续上滑删除静默 no-op；tag=null（SwipeReview 私有 controller）不清理
    DisposableEffect(tag) {
        onDispose {
            if (tag != null) controller.cancelPendingRequest(tag)
        }
    }

    // 授权拉起：token 即 IntentSender（DedupTrashBackend 生产适配），isRestore 分流 launcher
    LaunchedEffect(pendingRequest) {
        val pending = pendingRequest ?: return@LaunchedEffect
        if (tag != null && pending.tag != tag) return@LaunchedEffect
        val request = IntentSenderRequest.Builder(pending.token as IntentSender).build()
        if (pending.isRestore) {
            restoreLauncher.launch(request)
        } else {
            trashLauncher.launch(request)
        }
    }

    // 部分拒绝一次性提示（置位 → 消费 → 提示）
    val partialMessage = stringResource(R.string.org_partial_trash)
    LaunchedEffect(partialNotice) {
        if (partialNotice) {
            controller.consumePartialNotice()
            if (snackbarHostState != null) {
                snackbarHostState.showSnackbar(partialMessage)
            } else {
                Toast.makeText(context, partialMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 回收站不可用（API<30）/ token 构建失败
    val unsupportedMessage = stringResource(R.string.org_trash_unsupported)
    LaunchedEffect(trashError) {
        if (trashError) {
            controller.consumeErrorEvent()
            if (snackbarHostState != null) {
                snackbarHostState.showSnackbar(unsupportedMessage)
            } else {
                Toast.makeText(context, unsupportedMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
