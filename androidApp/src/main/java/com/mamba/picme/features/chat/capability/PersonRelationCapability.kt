package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.capability.BaseCapability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.runtime.state.SceneManager
import com.mamba.picme.core.common.Logger
import com.mamba.picme.data.repository.PersonRepository
import com.mamba.picme.domain.person.KinshipLexicon
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.domain.person.RelationSource

/**
 * 人物关系声明 Capability（CHAT 场景）。
 *
 * 聊天通路："记住小宝是我女儿" → remember_person_relation；"忘掉小宝的关系" → forget_person_relation。
 * 声明幂等覆盖（重复声明 = 纠错）；人名未解析返回引导性错误（先去人物分组命名）。
 * 数据操作全部收口到 [PersonRepository]（构造注入，显式依赖）。
 */
class PersonRelationCapability(
    private val personRepository: PersonRepository
) : BaseCapability() {

    private val tag = "PoLang:PersonRelationCapability"

    override val name: String = "person_relation"
    override val description: String = "在聊天中声明/遗忘/查询人物与「我」的关系（如「小宝是我女儿」「看一下我的人物关系」），声明后可用称谓搜合照"

    override fun activeScenes(): List<SceneManager.Scene> = listOf(SceneManager.Scene.CHAT)

    override fun supportedCommands(): List<String> = listOf(
        "remember_person_relation",
        "forget_person_relation",
        "query_person_relation"
    )

    override fun getCommandDescription(command: String): String = when (command) {
        "remember_person_relation" -> "声明人物关系，参数: name (已命名人物名), relation (谓词枚举名/中文称谓/任意自定义称呼)"
        "forget_person_relation" -> "遗忘与某人物的全部关系，参数: name (人物名)"
        "query_person_relation" -> "查询人物关系，参数: name (可选，指定人物名则只查该人物；留空查全部)"
        else -> "未知命令"
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        return try {
            when (command) {
                is AgentCommand.RememberPersonRelation -> rememberRelation(command)
                is AgentCommand.ForgetPersonRelation -> forgetRelation(command)
                is AgentCommand.QueryPersonRelation -> queryRelation(command)
                else -> Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                        message = "PersonRelationCapability 不支持此命令"
                    )
                )
            }
        } catch (e: Exception) {
            Logger.e(tag, "execute failed", e)
            Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "操作失败：${e.message ?: "未知错误"}"
                )
            )
        }
    }

    private suspend fun rememberRelation(
        command: AgentCommand.RememberPersonRelation
    ): Result<AgentAction> {
        // 关系归一：枚举名 → 中文称谓词表 → 都不匹配则原话存 customLabel、谓词记 OTHER（不再报错）
        val rawRelation = command.relation.trim()
        val normalizedPredicate = RelationPredicate.fromStored(rawRelation.uppercase())
            ?: KinshipLexicon.predicateFor(rawRelation)
        val predicate = normalizedPredicate ?: RelationPredicate.OTHER
        // 仅在归一失败时携带 customLabel（称谓已归一到谓词的，查询走词表即可）
        val customLabel = if (normalizedPredicate == null) rawRelation.ifEmpty { null } else null

        val person = personRepository.resolveByName(command.name.trim())
            ?: return error(
                command,
                "还没有叫「${command.name}」的人物，请先在相册人物分组里给 TA 命名，然后再告诉我"
            )

        return when (val result = personRepository.declareRelation(
            subjectPersonId = person.personId,
            predicate = predicate,
            source = RelationSource.CHAT_DECLARATION,
            customLabel = customLabel
        )) {
            is PersonRepository.DeclareRelationResult.Declared -> {
                val personName = person.name ?: command.name
                // 确认文本用用户原话（"已记住：大宝是你的发小"）
                val displayLabel = customLabel ?: predicate.labelZh
                Logger.i(tag, "relation declared: $personName is user's ${predicate.name} customLabel=$customLabel")
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = "已记住：${personName}是你的${displayLabel}"
                    )
                )
            }
            PersonRepository.DeclareRelationResult.SelfNotDeclared -> error(
                command,
                "还没有标记哪个人物是你本人，请先在相册人物分组编辑你自己的分组，打开「这是我」后再声明关系"
            )
            PersonRepository.DeclareRelationResult.SubjectNotFound -> error(
                command,
                "还没有叫「${command.name}」的人物，请先在相册人物分组里给 TA 命名"
            )
        }
    }

    private suspend fun forgetRelation(
        command: AgentCommand.ForgetPersonRelation
    ): Result<AgentAction> {
        val person = personRepository.resolveByName(command.name.trim())
            ?: return error(command, "没有找到叫「${command.name}」的人物")

        val removed = personRepository.removeAllRelationsOf(person.personId)
        val personName = person.name ?: command.name
        val message = if (removed > 0) {
            "已忘记你与「$personName」的关系"
        } else {
            "「$personName」本来就没有已记住的关系"
        }
        Logger.i(tag, "forget relation: $personName removed=$removed")
        return Result.success(AgentAction.TextReply(commandId = command.commandId, message = message))
    }

    private suspend fun queryRelation(
        command: AgentCommand.QueryPersonRelation
    ): Result<AgentAction> {
        // 主动现查 DB（绕开 MemoryContextProvider 的 Flow 快照，规避声明后 snapshot 更新延迟）
        val relations = personRepository.listRelationsToSelf(command.name)
        val message = if (relations.isEmpty()) {
            if (command.name != null) {
                "还没有记住「${command.name}」与你的关系"
            } else {
                "还没有记住任何人物关系。可以在相册人物分组里给人物命名并声明，或直接告诉我「小宝是我女儿」"
            }
        } else {
            val lines = relations.mapIndexed { index, item ->
                val label = item.customLabel ?: item.predicate.labelZh
                "${index + 1}. ${item.subjectName}（$label）"
            }
            val scope = if (command.name != null) "「${command.name}」的" else ""
            "已记住 ${relations.size} 条${scope}人物关系：\n${lines.joinToString("\n")}"
        }
        Logger.i(tag, "query relation: name=${command.name} hits=${relations.size}")
        return Result.success(AgentAction.TextReply(commandId = command.commandId, message = message))
    }

    private fun error(command: AgentCommand, message: String): Result<AgentAction> =
        Result.success(
            AgentAction.Error(
                commandId = command.commandId,
                errorCode = AgentErrorCode.INVALID_PARAMS,
                message = message
            )
        )
}
