package com.mamba.picme.domain.person

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [PersonRepository] 单测（Robolectric + Room 内存库，真实 DAO）。
 *
 * 覆盖：declareRelation 幂等覆盖、self 未声明/人物不存在的失败分支、
 * resolveByName、removeRelation 幂等、setSelf 全局唯一。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PersonRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: PersonRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PersonRepository(db.personDao(), db.personRelationDao(), db.mediaDao())
    }

    @After
    fun teardown() {
        db.close()
    }

    private suspend fun insertPerson(name: String?): Long =
        db.personDao().insertPerson(PersonEntity(name = name))

    @Test
    fun `declareRelation is idempotent and re-declaration overwrites predicate`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("小宝")
        repository.setSelf(selfId)

        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)
        repository.declareRelation(childId, RelationPredicate.FRIEND, RelationSource.RENAME_DIALOG)

        val relations = db.personRelationDao().getAll()
        assertEquals("同一对人物只保留一条关系", 1, relations.size)
        assertEquals(RelationPredicate.FRIEND.name, relations[0].predicate)
        assertEquals("覆盖后来源同步更新", RelationSource.RENAME_DIALOG.name, relations[0].source)
    }

    @Test
    fun `declareRelation without self returns SelfNotDeclared`() = runTest {
        val childId = insertPerson("小宝")

        val result = repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)

        assertTrue(result is PersonRepository.DeclareRelationResult.SelfNotDeclared)
        assertTrue(db.personRelationDao().getAll().isEmpty())
    }

    @Test
    fun `declareRelation with missing subject returns SubjectNotFound`() = runTest {
        val selfId = insertPerson("我")
        repository.setSelf(selfId)

        val result = repository.declareRelation(999L, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)

        assertTrue(result is PersonRepository.DeclareRelationResult.SubjectNotFound)
    }

    @Test
    fun `resolveByName finds named person and ignores unnamed`() = runTest {
        insertPerson(null)
        val childId = insertPerson("小宝")

        assertEquals(childId, repository.resolveByName("小宝")?.personId)
        assertEquals("LIKE 模糊命中", childId, repository.resolveByName("小")?.personId)
        assertNull(repository.resolveByName("不存在"))
    }

    @Test
    fun `removeRelation deletes only matching predicate and is idempotent`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("小宝")
        repository.setSelf(selfId)
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)

        assertEquals(0, repository.removeRelation(childId, RelationPredicate.SPOUSE))
        assertEquals(1, repository.removeRelation(childId, RelationPredicate.CHILD))
        assertEquals("重复删除幂等", 0, repository.removeRelation(childId, RelationPredicate.CHILD))
        assertNull(repository.getRelationToSelf(childId))
    }

    @Test
    fun `setSelf is globally unique and clearSelf removes flag`() = runTest {
        val firstId = insertPerson("甲")
        val secondId = insertPerson("乙")

        repository.setSelf(firstId)
        repository.setSelf(secondId)

        val self = repository.getSelfPerson()
        assertNotNull(self)
        assertEquals(secondId, self!!.personId)
        assertTrue(db.personDao().getAllPersons().count { it.isSelf } == 1)

        repository.clearSelf()
        assertNull(repository.getSelfPerson())
    }

    @Test
    fun `observeSelfAvatar emits null when self not marked`() = runTest {
        insertPerson("张三")
        assertNull(repository.observeSelfAvatar().first())
    }

    @Test
    fun `observeSelfAvatar emits null when self has no cover`() = runTest {
        val selfId = insertPerson("我")
        repository.setSelf(selfId)
        assertNull("未设置封面时回退 null", repository.observeSelfAvatar().first())
    }

    @Test
    fun `observeSelfAvatar resolves cover uri and faceFocusY for marked self`() = runTest {
        val selfId = insertPerson("我")
        val mediaId = db.mediaDao().insertMedia(
            MediaEntity(
                uri = "content://media/selfie",
                type = MediaType.PHOTO,
                captureDate = 1L,
                fileName = "selfie.jpg",
                faceFocusY = 0.3f
            )
        )
        db.personDao().updateCoverMedia(selfId, mediaId)
        repository.setSelf(selfId)

        val avatar = repository.observeSelfAvatar().first()
        assertNotNull(avatar)
        assertEquals("content://media/selfie", avatar!!.coverUri)
        assertEquals(0.3f, avatar.faceFocusY)
    }

    @Test
    fun `observeSelfAvatar emits null when cover media deleted`() = runTest {
        val selfId = insertPerson("我")
        val mediaId = db.mediaDao().insertMedia(
            MediaEntity(
                uri = "content://media/selfie",
                type = MediaType.PHOTO,
                captureDate = 1L,
                fileName = "selfie.jpg"
            )
        )
        db.personDao().updateCoverMedia(selfId, mediaId)
        repository.setSelf(selfId)
        db.mediaDao().deleteMediaByIds(listOf(mediaId))

        assertNull("封面媒体已删时回退 null", repository.observeSelfAvatar().first())
    }

    /**
     * 活性测试：本功能的核心承诺是「标记/取消后头像实时切换」，锁定 Room 失效重发行为。
     * 发射序列必须是 null（未标记）→ SelfAvatar（标记）→ null（取消）。
     * Room InvalidationTracker 走真实执行器，故用 runBlocking + 真实时延而非 runTest 虚拟时间。
     */
    @Test
    fun `observeSelfAvatar re-emits on setSelf and clearSelf`() = runBlocking {
        val selfId = insertPerson("我")
        val mediaId = db.mediaDao().insertMedia(
            MediaEntity(
                uri = "content://media/selfie",
                type = MediaType.PHOTO,
                captureDate = 1L,
                fileName = "selfie.jpg",
                faceFocusY = 0.3f
            )
        )
        db.personDao().updateCoverMedia(selfId, mediaId)

        val emissions = mutableListOf<SelfAvatar?>()
        val job = launch(Dispatchers.IO) {
            repository.observeSelfAvatar().take(3).toList(emissions)
        }
        withTimeout(5_000) { while (emissions.isEmpty()) delay(50) }
        repository.setSelf(selfId)
        withTimeout(5_000) { while (emissions.size < 2) delay(50) }
        repository.clearSelf()
        withTimeout(5_000) { job.join() }

        assertEquals("发射序列：未标记 → 标记 → 取消", 3, emissions.size)
        assertNull(emissions[0])
        assertEquals("content://media/selfie", emissions[1]?.coverUri)
        assertEquals(0.3f, emissions[1]?.faceFocusY)
        assertNull(emissions[2])
    }

    @Test
    fun `resolveByKinship specific term hits specific value plus unspecified bucket`() = runTest {
        val selfId = insertPerson("我")
        val daughterId = insertPerson("小宝")
        val childId = insertPerson("豆豆")
        val sonId = insertPerson("石头")
        repository.setSelf(selfId)
        repository.declareRelation(daughterId, RelationPredicate.DAUGHTER, RelationSource.CHAT_DECLARATION)
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)
        repository.declareRelation(sonId, RelationPredicate.SON, RelationSource.CHAT_DECLARATION)

        val daughters = repository.resolveByKinship("女儿")
        assertEquals(
            "女儿 → {DAUGHTER, CHILD}：具体值 + 未指定桶，不含儿子",
            setOf(daughterId, childId),
            daughters.map { it.personId }.toSet()
        )
        assertTrue("非受控称谓返回空", repository.resolveByKinship("表妹").isEmpty())
    }

    @Test
    fun `resolveByKinship general term covers whole family`() = runTest {
        val selfId = insertPerson("我")
        val daughterId = insertPerson("小宝")
        val childId = insertPerson("豆豆")
        val sonId = insertPerson("石头")
        val fatherId = insertPerson("老头")
        repository.setSelf(selfId)
        repository.declareRelation(daughterId, RelationPredicate.DAUGHTER, RelationSource.CHAT_DECLARATION)
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)
        repository.declareRelation(sonId, RelationPredicate.SON, RelationSource.CHAT_DECLARATION)
        repository.declareRelation(fatherId, RelationPredicate.FATHER, RelationSource.CHAT_DECLARATION)

        val children = repository.resolveByKinship("孩子")
        assertEquals(
            "孩子 → 整族 {SON, DAUGHTER, CHILD}",
            setOf(daughterId, childId, sonId),
            children.map { it.personId }.toSet()
        )

        val parents = repository.resolveByKinship("父母")
        assertEquals(
            "父母 → 整族 {FATHER, MOTHER, PARENT}",
            setOf(fatherId),
            parents.map { it.personId }.toSet()
        )
    }

    @Test
    fun `observeRelationsToSelf emits display items joined with person names`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("大宝")
        repository.setSelf(selfId)
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)

        val items = repository.observeRelationsToSelf().first()

        assertEquals(1, items.size)
        assertEquals("大宝", items[0].subjectName)
        assertEquals(RelationPredicate.CHILD, items[0].predicate)
        assertEquals(childId, items[0].subjectPersonId)
    }

    @Test
    fun `observeRelationsToSelf skips relations not pointing to self and unnamed fallback`() = runTest {
        val selfId = insertPerson("我")
        val unnamedId = insertPerson(null)
        val otherId = insertPerson("路人")
        repository.setSelf(selfId)
        repository.declareRelation(unnamedId, RelationPredicate.FRIEND, RelationSource.CHAT_DECLARATION)
        // 非指向"我"的关系不展示（schema 预留，UI 不开放）
        db.personRelationDao().upsert(
            com.mamba.picme.data.local.entity.PersonRelationEntity(
                subjectPersonId = otherId,
                objectPersonId = unnamedId,
                predicate = RelationPredicate.COLLEAGUE.name,
                source = RelationSource.CHAT_DECLARATION.name
            )
        )

        val items = repository.observeRelationsToSelf().first()

        assertEquals(1, items.size)
        assertEquals("未命名人物退化为 #personId", "#$unnamedId", items[0].subjectName)
    }

    @Test
    fun `removeRelationById deletes row and is idempotent`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("小宝")
        repository.setSelf(selfId)
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)
        val relationId = db.personRelationDao().getAll().single().relationId

        assertTrue(repository.removeRelationById(relationId))
        assertTrue(db.personRelationDao().getAll().isEmpty())
        assertFalse("重复删除幂等", repository.removeRelationById(relationId))
    }

    @Test
    fun `declareRelation stores customLabel and re-declaration overwrites it`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("二宝")
        repository.setSelf(selfId)

        repository.declareRelation(
            childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION,
            customLabel = " 二儿子 "
        )
        var relation = db.personRelationDao().getAll().single()
        assertEquals("customLabel 归一（trim）后落库", "二儿子", relation.customLabel)

        // 重复声明覆盖：谓词与 customLabel 同步更新
        repository.declareRelation(
            childId, RelationPredicate.OTHER, RelationSource.RENAME_DIALOG,
            customLabel = "发小"
        )
        relation = db.personRelationDao().getAll().single()
        assertEquals(RelationPredicate.OTHER.name, relation.predicate)
        assertEquals("发小", relation.customLabel)

        // 空白 customLabel 归一为 null（清除旧称呼）
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)
        relation = db.personRelationDao().getAll().single()
        assertNull(relation.customLabel)
    }

    @Test
    fun `resolveByCustomLabels matches query contains and requires self`() = runTest {
        val selfId = insertPerson("我")
        val firstId = insertPerson("大宝")
        val secondId = insertPerson("二宝")
        repository.setSelf(selfId)
        repository.declareRelation(
            firstId, RelationPredicate.OTHER, RelationSource.CHAT_DECLARATION,
            customLabel = "发小"
        )
        repository.declareRelation(
            secondId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION,
            customLabel = "二儿子"
        )

        val hits = repository.resolveByCustomLabels("我和二儿子的合照")
        assertEquals(1, hits.size)
        assertEquals("二儿子", hits[0].label)
        assertEquals(secondId, hits[0].person.personId)

        assertTrue("未出现的称呼不命中", repository.resolveByCustomLabels("猫咪的照片").isEmpty())

        repository.clearSelf()
        assertTrue("未标记我本人时返回空", repository.resolveByCustomLabels("二儿子").isEmpty())
    }

    @Test
    fun `updateRelation updates predicate and customLabel but keeps source`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("二宝")
        repository.setSelf(selfId)
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION)
        val before = db.personRelationDao().getAll().single()

        val updated = repository.updateRelation(
            relationId = before.relationId,
            predicate = RelationPredicate.OTHER,
            customLabel = "发小"
        )

        assertTrue(updated)
        val after = db.personRelationDao().getAll().single()
        assertEquals(RelationPredicate.OTHER.name, after.predicate)
        assertEquals("发小", after.customLabel)
        assertEquals("source 保留", RelationSource.CHAT_DECLARATION.name, after.source)
        assertEquals("createdAt 保留", before.createdAt, after.createdAt)
        assertTrue("updatedAt 刷新", after.updatedAt >= before.updatedAt)

        assertFalse("不存在的 relationId 返回 false", repository.updateRelation(999L, RelationPredicate.FRIEND, null))
    }

    @Test
    fun `observeRelationsToSelf carries customLabel`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("二宝")
        repository.setSelf(selfId)
        repository.declareRelation(
            childId, RelationPredicate.CHILD, RelationSource.CHAT_DECLARATION,
            customLabel = "二儿子"
        )

        val items = repository.observeRelationsToSelf().first()

        assertEquals(1, items.size)
        assertEquals("二儿子", items[0].customLabel)
        assertEquals(RelationPredicate.CHILD, items[0].predicate)
    }

    @Test
    fun `updateCover changes coverMediaId`() = runTest {
        val personId = insertPerson("小宝")
        db.mediaDao().insertMedia(
            MediaEntity(
                id = 100L,
                uri = "content://test/100",
                type = com.mamba.picme.agent.core.model.context.MediaType.PHOTO,
                captureDate = 1L,
                fileName = "test.jpg"
            )
        )

        repository.updateCover(personId, 100L)

        val person = db.personDao().getPerson(personId)
        assertNotNull(person)
        assertEquals(100L, person!!.coverMediaId)
    }

    @Test
    fun `applyPersonEdit without self returns SelfNotDeclared and writes no relation`() = runTest {
        val childId = insertPerson("小宝")

        val result = repository.applyPersonEdit(
            personId = childId,
            name = "小宝",
            relation = RelationPredicate.CHILD,
            customLabel = "",
            isSelf = false
        )

        assertTrue("结果必须透传给调用方，不允许静默吞掉", result is PersonRepository.DeclareRelationResult.SelfNotDeclared)
        assertTrue("被拒的声明不落库", db.personRelationDao().getAll().isEmpty())
    }

    @Test
    fun `applyPersonEdit with self returns Declared and persists relation`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("小宝")
        repository.setSelf(selfId)

        val result = repository.applyPersonEdit(
            personId = childId,
            name = "小宝",
            relation = RelationPredicate.CHILD,
            customLabel = "二儿子",
            isSelf = false
        )

        assertTrue(result is PersonRepository.DeclareRelationResult.Declared)
        val relation = db.personRelationDao().getAll().single()
        assertEquals(RelationPredicate.CHILD.name, relation.predicate)
        assertEquals("二儿子", relation.customLabel)
    }

    @Test
    fun `applyPersonEdit with null relation returns null and clears relations`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("小宝")
        repository.setSelf(selfId)
        repository.declareRelation(childId, RelationPredicate.CHILD, RelationSource.RENAME_DIALOG)

        val result = repository.applyPersonEdit(
            personId = childId,
            name = "小宝",
            relation = null,
            customLabel = "",
            isSelf = false
        )

        assertNull("清除路径无声明动作，返回 null", result)
        assertTrue(db.personRelationDao().getAll().isEmpty())
    }

    @Test
    fun `applyPersonEdit renames in the same write as relation declaration`() = runTest {
        val selfId = insertPerson("我")
        val childId = insertPerson("旧名")
        repository.setSelf(selfId)

        val result = repository.applyPersonEdit(
            personId = childId,
            name = "新名",
            relation = RelationPredicate.FRIEND,
            customLabel = "",
            isSelf = false
        )

        assertTrue(result is PersonRepository.DeclareRelationResult.Declared)
        assertEquals("新名单次写入即生效", "新名", db.personDao().getPerson(childId)?.name)
    }
}
