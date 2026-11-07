package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import com.mamba.picme.data.repository.PersonRepository
import com.mamba.picme.data.repository.RelationDisplayItem
import com.mamba.picme.domain.person.RelationPredicate
import com.mamba.picme.domain.person.RelationSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PersonRelationCapability] 关系归一单测（PersonRepository 用 MockK 替身）。
 *
 * 覆盖归一顺序：枚举名 → KinshipLexicon 中文称谓 → 原话落 customLabel（谓词记 OTHER）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonRelationCapabilityTest {

    private val repository = mockk<PersonRepository>()
    private val capability = PersonRelationCapability(repository)
    private val context = AgentContext(scene = AgentScene.CHAT)

    private val person = PersonEntity(personId = 1L, name = "大宝")

    private fun stubDeclared() {
        coEvery { repository.resolveByName("大宝") } returns person
        coEvery {
            repository.declareRelation(any(), any(), any(), any())
        } answers {
            PersonRepository.DeclareRelationResult.Declared(
                PersonRelationEntity(
                    relationId = 1L,
                    subjectPersonId = person.personId,
                    objectPersonId = 9L,
                    predicate = secondArg<RelationPredicate>().name,
                    source = RelationSource.CHAT_DECLARATION.name,
                    customLabel = arg(3)
                )
            )
        }
    }

    @Test
    fun `enum name normalizes to predicate without customLabel`() = runBlocking {
        stubDeclared()

        val result = capability.execute(
            AgentCommand.RememberPersonRelation(name = "大宝", relation = "child"),
            context,
            null
        ).getOrNull()

        assertTrue("expected TextReply, got $result", result is AgentAction.TextReply)
        assertEquals("已记住：大宝是你的孩子", (result as AgentAction.TextReply).message)
        coVerify {
            repository.declareRelation(1L, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION, null)
        }
    }

    @Test
    fun `kinship term normalizes to specific predicate without customLabel`() = runBlocking {
        stubDeclared()

        val result = capability.execute(
            AgentCommand.RememberPersonRelation(name = "大宝", relation = "女儿"),
            context,
            null
        ).getOrNull()

        assertTrue("expected TextReply, got $result", result is AgentAction.TextReply)
        assertEquals("确认文本用具体谓词标签", "已记住：大宝是你的女儿", (result as AgentAction.TextReply).message)
        coVerify {
            repository.declareRelation(1L, RelationPredicate.DAUGHTER, RelationSource.CHAT_DECLARATION, null)
        }
    }

    @Test
    fun `unrecognized relation falls back to customLabel with OTHER predicate`() = runBlocking {
        stubDeclared()

        val result = capability.execute(
            AgentCommand.RememberPersonRelation(name = "大宝", relation = "发小"),
            context,
            null
        ).getOrNull()

        assertTrue("expected TextReply, got $result", result is AgentAction.TextReply)
        assertEquals("确认文本用用户原话", "已记住：大宝是你的发小", (result as AgentAction.TextReply).message)
        coVerify {
            repository.declareRelation(1L, RelationPredicate.OTHER, RelationSource.CHAT_DECLARATION, "发小")
        }
    }

    @Test
    fun `partner term normalizes to PARTNER predicate`() = runBlocking {
        stubDeclared()

        val result = capability.execute(
            AgentCommand.RememberPersonRelation(name = "大宝", relation = "女朋友"),
            context,
            null
        ).getOrNull()

        assertTrue("expected TextReply, got $result", result is AgentAction.TextReply)
        coVerify {
            repository.declareRelation(1L, RelationPredicate.PARTNER, RelationSource.CHAT_DECLARATION, null)
        }
    }

    @Test
    fun `query lists all relations to self`() = runBlocking {
        coEvery { repository.listRelationsToSelf(any()) } returns listOf(
            RelationDisplayItem(1L, 12L, "张天爱", RelationPredicate.OTHER, "前女友"),
            RelationDisplayItem(2L, 10L, "白冰", RelationPredicate.PARTNER),
            RelationDisplayItem(3L, 1L, "大宝", RelationPredicate.SON)
        )

        val result = capability.execute(
            AgentCommand.QueryPersonRelation(name = null),
            context,
            null
        ).getOrNull()

        assertTrue("expected TextReply, got $result", result is AgentAction.TextReply)
        val msg = (result as AgentAction.TextReply).message
        assertTrue("含总数: $msg", msg.contains("已记住 3 条人物关系"))
        assertTrue("含张天爱+前女友: $msg", msg.contains("张天爱") && msg.contains("前女友"))
        assertTrue("customLabel 空时用谓词标签: $msg", msg.contains("白冰") && msg.contains("恋人"))
        assertTrue("含大宝+儿子: $msg", msg.contains("大宝") && msg.contains("儿子"))
        coVerify { repository.listRelationsToSelf(null) }
    }

    @Test
    fun `query with no relations returns guided hint`() = runBlocking {
        coEvery { repository.listRelationsToSelf(any()) } returns emptyList()

        val result = capability.execute(
            AgentCommand.QueryPersonRelation(name = null),
            context,
            null
        ).getOrNull()

        assertTrue(result is AgentAction.TextReply)
        assertTrue("空时给引导: $result", (result as AgentAction.TextReply).message.contains("还没有记住任何人物关系"))
    }

    @Test
    fun `query by name scopes to that person`() = runBlocking {
        coEvery { repository.listRelationsToSelf("张天爱") } returns listOf(
            RelationDisplayItem(1L, 12L, "张天爱", RelationPredicate.OTHER, "前女友")
        )

        val result = capability.execute(
            AgentCommand.QueryPersonRelation(name = "张天爱"),
            context,
            null
        ).getOrNull()

        assertTrue(result is AgentAction.TextReply)
        val msg = (result as AgentAction.TextReply).message
        assertTrue("含人物名前缀: $msg", msg.contains("「张天爱」的"))
        assertTrue("含称呼: $msg", msg.contains("前女友"))
        coVerify { repository.listRelationsToSelf("张天爱") }
    }
}
