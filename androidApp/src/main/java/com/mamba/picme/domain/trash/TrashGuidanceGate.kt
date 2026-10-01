package com.mamba.picme.domain.trash

/** 引导裁决结果。 */
sealed interface TrashGuidanceDecision {
    /** 用户开启免确认且静默路径就绪（开关 + MANAGE_MEDIA 授权）：调用方重试静默回收。 */
    data object Enabled : TrashGuidanceDecision

    /** 保持系统确认（用户拒绝 / 关闭弹窗 / 引导不适用 / 并发引导占用）：调用方回落 token 通路。 */
    data object KeepSystemConfirm : TrashGuidanceDecision
}

/**
 * 静默删除一次性引导门（UI 协作钩子）：静默快路径不可用（开关关 / 无 MANAGE_MEDIA）且未引导过时，
 * [TrashSessionController] 在回落系统授权框前挂起征询用户「是否开启免确认删除」。
 * 实现方负责一次性标志（asked）的读写与弹窗生命周期；并发 ask 互斥（直接返回 KeepSystemConfirm）。
 */
interface TrashGuidanceGate {
    /** 挂起直至用户裁决；实现保证不适用场景（已引导 / API 不足 / 静默已生效）立即返回 KeepSystemConfirm。 */
    suspend fun ask(): TrashGuidanceDecision
}
