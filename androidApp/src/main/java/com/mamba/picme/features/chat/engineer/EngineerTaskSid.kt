package com.mamba.picme.features.chat.engineer

/**
 * 工程师任务动作（resume/deliver）的网关 sid 选择纯逻辑（测试决策接缝，对齐 EngineerTaskReducer 先例）。
 *
 * 背景：reducer 对 Session 事件 last-wins 无过滤，claude init 的 UUID session 事件会覆盖卡片 sid；
 * VM 级 claudeSid 归属单一 chat 会话，跨会话动作（任务中心）不得串用。
 */
object EngineerTaskSid {

    /** 网关 sid 形态：12 位 hex（workdir/deliver key，uuid4().hex[:12]）。 */
    val GATEWAY_PATTERN = Regex("[0-9a-f]{12}")

    fun isGatewaySid(sid: String): Boolean = sid.matches(GATEWAY_PATTERN)

    /**
     * 动作 sid 选择优先级：卡片 sid 合规 → 直接用（跨会话安全，sid 即 workdir key）；
     * 否则 VM 级 sid 仅当归属目标会话才回落；都不满足返回 null（调用方报错上卡或按新回合处理）。
     */
    fun chooseActionSid(
        taskSid: String?,
        vmSid: String?,
        vmSidOwner: String?,
        targetSessionId: String,
    ): String? =
        taskSid?.takeIf { sid -> isGatewaySid(sid) }
            ?: vmSid?.takeIf { vmSidOwner == targetSessionId }
}
