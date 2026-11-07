package com.mamba.picme.agent.core.skill

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import kotlinx.coroutines.CancellationException

/**
 * skill 加载能力（chat `load_skill` 工具的执行体，2026-11-08 端侧落地）。
 *
 * 与云端 `LoadSkillToolService`（server 直读磁盘）同契约不同落点：工具名/描述/error 前缀
 * 逐字节一致（见 [AgentSkillContract]），端侧正文来自 [SkillRepository] 的本地缓存 +
 * 单点穿透。失败语义与 WebSearchCapability 同构——一切异常映射为 error 文本交 LLM
 * 降级处理，不穿透 ReAct 链（CancellationException 原样 rethrow）。
 */
class LoadSkillCapability(
    private val repository: SkillRepository,
) : BaseCapability() {

    override val name: String = AgentSkillContract.TOOL_LOAD_SKILL
    override val description: String = "按需加载技能（SKILL）指令包，获取「怎么做」的完整指令"

    override fun supportedCommands(): List<String> = listOf(AgentSkillContract.TOOL_LOAD_SKILL)

    override suspend fun execute(command: AgentCommand, context: AgentContext, pageContext: PageContext?): Result<AgentAction> {
        if (command !is AgentCommand.LoadSkill) {
            return Result.success(
                AgentAction.Error(command.commandId, AgentErrorCode.METHOD_NOT_FOUND, "LoadSkillCapability 不支持此命令"),
            )
        }
        val body = try {
            repository.content(command.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        val text = body ?: AgentSkillContract.unknownSkill(command.name)
        return Result.success(AgentAction.TextReply(commandId = command.commandId, message = text))
    }
}
