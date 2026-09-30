package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.TimeRange
import com.mamba.picme.domain.person.PersonQueryResolver
import com.mamba.picme.domain.person.PersonRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private fun explicitFilter(timeRange: TimeRange? = null) = ExplicitFilter(
    timeRange = timeRange,
    locationKeywords = emptyList(),
    hasFaces = null,
    personKeywords = emptyList()
)

private fun contentFilter(vararg keywords: String) = ContentFilter(
    keywords = keywords.toList(),
    ocrKeywords = emptyList(),
    semanticQuery = keywords.joinToString("")
)

/**
 * [QA] ExplicitFirstSearchPipeline 单元测试
 *
 * 验证显式约束优先搜索管道在候选集内召回内容关键词的行为，
 * 包括自定义人物分组名称的召回。
 */
class ExplicitFirstSearchPipelineTest {

    private val mediaDao: MediaDao = mockk(relaxed = true)

    @Test
    fun `search by custom person group name with time range returns intersected media`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val pipeline = ExplicitFirstSearchPipeline(
            mediaDao = mediaDao,
            personDao = personDao
        )

        val person = PersonEntity(personId = 42L, name = "大宝")

        // 显式约束：时间范围返回 {100, 200}
        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(100L, 200L)

        // 人物分组名称命中：person 42 拥有 {100, 300}
        coEvery { personDao.findPersonByName("大宝") } returns person
        coEvery { personDao.getMediaByPerson(42L) } returns listOf(mediaEntity(100L), mediaEntity(300L))

        // 标签/OCR/文件名搜索均无命中
        coEvery { mediaDao.searchLabelsAllFieldsInIds(any(), any()) } returns emptyList()
        coEvery { mediaDao.searchFileNameInIds(any(), any()) } returns emptyList()

        // 期望结果：时间范围 {100, 200} ∩ 人物 {100, 300} = {100}
        coEvery { mediaDao.getMediaByIds(listOf(100L)) } returns listOf(mediaEntity(100L))

        val result = pipeline.search(
            explicit = explicitFilter(timeRange = timeRange),
            content = contentFilter("大宝")
        )

        assertEquals(listOf(100L), result.media.map { it.id })
    }

    @Test
    fun `search by custom person group name without explicit constraint returns person media`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val pipeline = ExplicitFirstSearchPipeline(
            mediaDao = mediaDao,
            personDao = personDao
        )

        val person = PersonEntity(personId = 42L, name = "大宝")

        // 人物分组名称命中
        coEvery { personDao.findPersonByName("大宝") } returns person
        coEvery { personDao.getMediaByPerson(42L) } returns listOf(mediaEntity(100L), mediaEntity(101L))

        // 标签/OCR/文件名搜索均无命中
        coEvery { mediaDao.searchByLabelAllFields(any()) } returns emptyList()

        coEvery { mediaDao.getMediaByIds(listOf(100L, 101L)) } returns listOf(
            mediaEntity(100L),
            mediaEntity(101L)
        )

        val result = pipeline.search(
            explicit = explicitFilter(),
            content = contentFilter("大宝")
        )

        assertEquals(setOf(100L, 101L), result.media.map { it.id }.toSet())
    }

    @Test
    fun `resolver hit narrows candidates and consumes person keyword`() = runTest {
        // 缺陷③回归：resolveCandidateIds 曾静默丢弃 personKeywords —— "去年夏天儿子的照片"
        // 只走 时间∩标签搜索，"儿子"标签命中大量无关照片。人物解析器命中后：
        // 1. 人物簇成为显式维度（时间 ∩ 人物簇）
        // 2. 已消费的关键词不再驱动标签搜索
        val personDao: PersonDao = mockk(relaxed = true)
        val repository = mockk<PersonRepository>(relaxed = true)
        coEvery { repository.resolveByCustomLabels(any()) } returns emptyList()
        coEvery { repository.getNamedPersons() } returns emptyList()
        coEvery { repository.resolveByKinship("儿子") } returns listOf(PersonEntity(personId = 42L, name = null))
        coEvery { repository.getSelfPerson() } returns null
        val pipeline = ExplicitFirstSearchPipeline(
            mediaDao = mediaDao,
            personDao = personDao,
            personQueryResolver = PersonQueryResolver(repository)
        )

        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        // 显式约束：时间返回 {99, 100, 200}
        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(99L, 100L, 200L)
        // 人物簇：person 42 拥有 {100, 101}
        coEvery { personDao.getMediaByPerson(42L) } returns listOf(mediaEntity(100L), mediaEntity(101L))
        // 标签污染源：候选内 "儿子" 标签命中 99（不在人物簇中）
        coEvery { mediaDao.searchLabelsAllFieldsInIds(any(), any()) } returns listOf(mediaEntity(99L))
        coEvery { mediaDao.searchFileNameInIds(any(), any()) } returns emptyList()
        coEvery { mediaDao.getMediaByIds(any()) } answers { firstArg<List<Long>>().map { id -> mediaEntity(id) } }

        val result = pipeline.search(
            explicit = explicitFilter(timeRange = timeRange).copy(personKeywords = listOf("儿子")),
            content = contentFilter("儿子")
        )

        assertEquals(
            "时间 {99,100,200} ∩ 人物簇 {100,101} = {100}；'儿子'被消费不得经标签混入 99",
            setOf(100L),
            result.media.map { it.id }.toSet()
        )
    }

    private fun mediaEntity(id: Long): MediaEntity {
        return MediaEntity(
            id = id,
            uri = "uri_$id",
            type = MediaType.PHOTO,
            captureDate = System.currentTimeMillis(),
            fileName = "img_$id.jpg"
        )
    }
}
