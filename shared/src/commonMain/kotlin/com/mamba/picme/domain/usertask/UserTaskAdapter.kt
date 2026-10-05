package com.mamba.picme.domain.usertask

/** 体系与协议之间的唯一接缝（spec §6）：订阅体系状态 → 翻译进注册表；统一动词 → 体系原生 API。 */
interface UserTaskAdapter {
    val kind: UserTaskKind

    /** 开始订阅体系状态流（含启动对账）。由注册表在 registerAdapter 时调用一次。 */
    fun start()

    /** 统一动词分发。失败不乐观更新——状态以体系为准，体系流会纠正注册表。 */
    suspend fun perform(taskId: String, action: UserTaskAction)
}
