package com.mamba.picme.domain.person

import com.mamba.picme.data.local.entity.PersonEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PersonQueryResolver] 单测（纯 JVM，PersonRepository 用 MockK 替身）。
 *
 * 覆盖：称谓→谓词命中、已命名人物 contains 命中、双人共现判定、
 * 歧义并集、零命中回退、"我和X"合拍计入 SELF、第一人称单独出现不计入。
 */
class PersonQueryResolverTest {

    private val repository = mockk<PersonRepository>()
    private val resolver = PersonQueryResolver(repository)

    private fun person(id: Long, name: String?, isSelf: Boolean = false) =
        PersonEntity(personId = id, name = name, isSelf = isSelf)

    private fun stubDefaults(
        named: List<PersonEntity> = emptyList(),
        kinship: Map<String, List<PersonEntity>> = emptyMap(),
        customLabels: List<CustomLabelHit> = emptyList(),
        self: PersonEntity? = null
    ) {
        coEvery { repository.getNamedPersons() } returns named
        coEvery { repository.resolveByKinship(any()) } answers {
            kinship[firstArg<String>()].orEmpty()
        }
        coEvery { repository.resolveByCustomLabels(any()) } returns customLabels
        coEvery { repository.getSelfPerson() } returns self
    }

    @Test
    fun `kinship term resolves to related persons`() = runTest {
        stubDefaults(kinship = mapOf("女儿" to listOf(person(1, "小宝"))))

        val result = resolver.resolve("我女儿的照片")

        assertEquals(setOf(1L), result.personIds)
        assertFalse(result.isAmbiguous)
        assertTrue(result.descriptions.single().contains("女儿"))
    }

    @Test
    fun `named person is matched by query contains`() = runTest {
        stubDefaults(named = listOf(person(1, "小宝"), person(2, "阿珍")))

        val result = resolver.resolve("小宝去海边的照片")

        assertEquals(setOf(1L), result.personIds)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun `self join pattern with another person yields two personIds for cooccurrence`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝")),
            self = person(9, "我", isSelf = true)
        )

        val result = resolver.resolve("我和小宝的合照")

        assertEquals("≥2 personId 供共现查询", setOf(1L, 9L), result.personIds)
        assertTrue(result.descriptions.contains("我"))
    }

    @Test
    fun `kinship term hitting multiple relations unions and marks ambiguous`() = runTest {
        stubDefaults(
            kinship = mapOf("女儿" to listOf(person(1, "大宝"), person(2, "二宝")))
        )

        val result = resolver.resolve("我女儿的单人照")

        assertEquals(setOf(1L, 2L), result.personIds)
        assertTrue(result.isAmbiguous)
        assertTrue(result.descriptions.single().contains("并集"))
    }

    @Test
    fun `zero hit returns empty result`() = runTest {
        stubDefaults(named = listOf(person(1, "小宝")))

        val result = resolver.resolve("猫咪的照片")

        assertTrue(result.personIds.isEmpty())
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun `first person pronoun alone does not include self`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝")),
            self = person(9, "我", isSelf = true)
        )

        // 无合拍 Pattern：不计入 SELF（"我"只是第一人称叙述）
        val plain = resolver.resolve("我想看小宝的照片")
        assertEquals(setOf(1L), plain.personIds)

        // 有合拍 Pattern 但没有其他人物命中：也不计入 SELF
        val noOther = resolver.resolve("我和猫咪的合照")
        assertTrue(noOther.personIds.isEmpty())
    }

    @Test
    fun `same name hitting multiple persons unions and marks ambiguous`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝"), person(2, "小宝"))
        )

        val result = resolver.resolve("小宝的照片")

        assertEquals(setOf(1L, 2L), result.personIds)
        assertTrue(result.isAmbiguous)
    }

    @Test
    fun `person hit by both name and kinship is deduplicated`() = runTest {
        stubDefaults(
            named = listOf(person(1, "小宝")),
            kinship = mapOf("女儿" to listOf(person(1, "小宝")))
        )

        val result = resolver.resolve("我女儿小宝的照片")

        assertEquals(setOf(1L), result.personIds)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun `custom label hit resolves exactly and suppresses covered kinship term`() = runTest {
        // 三个儿子都是 CHILD；"二儿子"是自定义称呼，应精确命中二宝，
        // 且"儿子"不再走亲属称谓并集
        stubDefaults(
            named = listOf(person(1, "大宝"), person(2, "二宝"), person(3, "小宝")),
            kinship = mapOf("儿子" to listOf(person(1, "大宝"), person(2, "二宝"), person(3, "小宝"))),
            customLabels = listOf(CustomLabelHit(label = "二儿子", person = person(2, "二宝"))),
            self = person(9, "我", isSelf = true)
        )

        val result = resolver.resolve("我和二儿子的合照")

        assertEquals("自定义称呼精确命中单个簇 + 合拍计入本人", setOf(2L, 9L), result.personIds)
        assertFalse("未被称谓并集稀释，无歧义", result.isAmbiguous)
        assertTrue(result.descriptions.any { it.contains("二儿子") })
    }

    @Test
    fun `custom label miss still falls through to kinship`() = runTest {
        stubDefaults(
            kinship = mapOf("儿子" to listOf(person(1, "大宝"), person(2, "二宝"))),
            customLabels = emptyList()
        )

        val result = resolver.resolve("我儿子的照片")

        assertEquals(setOf(1L, 2L), result.personIds)
        assertTrue(result.isAmbiguous)
    }

    @Test
    fun `multiple distinct custom labels can hit together`() = runTest {
        stubDefaults(
            customLabels = listOf(
                CustomLabelHit(label = "发小", person = person(1, "大宝")),
                CustomLabelHit(label = "二儿子", person = person(2, "二宝"))
            )
        )

        val result = resolver.resolve("发小和二儿子的合照")

        assertEquals(setOf(1L, 2L), result.personIds)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun `longer kinship term suppresses contained shorter term`() = runTest {
        // 新词表含单字称谓（爸/妈/哥/姐）：长称谓命中后短称谓不重复解析
        stubDefaults(
            kinship = mapOf(
                "爸爸" to listOf(person(1, "老头")),
                "爸" to listOf(person(1, "老头"))
            )
        )

        val result = resolver.resolve("我和爸爸的合照")

        assertEquals(setOf(1L), result.personIds)
        assertEquals("只解析一次，无「爸」重复描述", 1, result.descriptions.size)
        assertTrue(result.descriptions.single().startsWith("爸爸"))
    }

    @Test
    fun `kinship hit records matchedTerm for caller consumption`() = runTest {
        stubDefaults(kinship = mapOf("儿子" to listOf(person(42, null))))

        val result = resolver.resolve("我儿子的照片")

        assertEquals(setOf(42L), result.personIds)
        assertTrue("命中的称谓词应回传给调用方，供其从内容关键词中消费", "儿子" in result.matchedTerms)
    }

    @Test
    fun `custom label and name hits record matchedTerms`() = runTest {
        stubDefaults(customLabels = listOf(CustomLabelHit(label = "宝贝", person = person(5, "大宝"))))

        val labelHit = resolver.resolve("宝贝的照片")
        assertEquals(setOf(5L), labelHit.personIds)
        assertEquals(setOf("宝贝"), labelHit.matchedTerms)

        stubDefaults(named = listOf(person(5, "大宝")))
        val nameHit = resolver.resolve("大宝的照片")
        assertEquals(setOf(5L), nameHit.personIds)
        assertEquals("人名命中同样要回传 matchedTerms", setOf("大宝"), nameHit.matchedTerms)
    }

    @Test
    fun `kinship term without declared relation falls back to named person whose name contains term`() = runTest {
        // 用户未声明 SON 关系，但把人物簇命名为"大儿子"：称谓解析空手而归时
        // 应回退到"簇名包含称谓"的命名簇，而不是返回空
        stubDefaults(named = listOf(person(7, "大儿子")))

        val result = resolver.resolve("我儿子的照片")

        assertEquals(setOf(7L), result.personIds)
        assertTrue("儿子" in result.matchedTerms)
    }
}
