package com.mamba.picme.domain.trash

/**
 * 静默删除引导触发前置判定（纯函数，JVM 可测）。
 * MANAGE_MEDIA 检查口 MediaStore.canManageMedia 为 API 31+ 新增，低于 S 不引导（与设置页开关显示口径一致）。
 */
object SilentTrashGuidancePolicy {
    const val MIN_API_LEVEL = 31

    fun shouldAsk(
        apiLevel: Int,
        alreadyAsked: Boolean,
        silentTrashEnabled: Boolean,
        canManageMedia: Boolean,
    ): Boolean =
        apiLevel >= MIN_API_LEVEL &&
            !alreadyAsked &&
            !(silentTrashEnabled && canManageMedia)
}
