package com.mamba.picme.domain.search

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.model.StructuredFilter
import com.mamba.picme.domain.model.TimeRange
import com.mamba.picme.domain.person.PersonQueryResolver
import com.mamba.picme.domain.person.PersonRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：MediaSearchEngine.executeFilter 必须按"维度间交集、关键词内并集"语义召回。
 *
 * 历史 bug：各维度结果累积到同一 map，时间约束与关键词约束变成并集，
 * 导致 2003 年/2023 年等非半年内结果只要命中关键词就被召回。
 */
class MediaSearchEngineFilterTest {

    private val mediaDao: MediaDao = mockk(relaxed = true)

    private val engine = MediaSearchEngine(mediaDao = mediaDao)

    @Test
    fun `time range and keyword intersection returns only ids in both sets`() = runTest {
        // 时间范围返回 {1,2}，关键词返回 {2,3}，结果只能是 {2}
        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        val filter = StructuredFilter(
            timeRange = timeRange,
            keywords = listOf("小孩"),
            hasFaces = true
        )

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(1L, 2L)
        coEvery { mediaDao.getHasFaceIds() } returns listOf(1L, 2L, 3L)

        // 内容关键词在显式候选集 {1,2} 内搜索，仅 2 命中
        coEvery { mediaDao.searchLabelsInIds(listOf(1L, 2L), any()) } answers {
            val keyword = it.invocation.args[1] as String
            if (keyword == "小孩" || keyword == "child") listOf(mediaEntity(2L)) else emptyList()
        }
        coEvery { mediaDao.searchMlKitLabelsInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchMlKitLabelsZhInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchFileNameInIds(listOf(1L, 2L), any()) } returns emptyList()

        coEvery { mediaDao.getMediaByIds(listOf(2L)) } returns listOf(mediaEntity(2L))

        val result = engine.search(filter)

        assertEquals(listOf(2L), result.media.map { it.id })
    }

    @Test
    fun `keyword outside time range is excluded`() = runTest {
        // 旧照片 id=3 在关键词结果里但不在时间范围内，必须被过滤掉
        val timeRange = TimeRange(startMs = 100, endMs = 200)
        val filter = StructuredFilter(
            timeRange = timeRange,
            keywords = listOf("小孩")
        )

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(1L, 2L)
        coEvery { mediaDao.searchByLabel(any()) } returns listOf(mediaEntity(3L))
        coEvery { mediaDao.searchByMlKitLabel(any()) } returns emptyList()
        coEvery { mediaDao.searchByMlKitLabelZh(any()) } returns emptyList()
        coEvery { mediaDao.searchByFileName(any()) } returns emptyList()

        // 期望：因为 timeRange 存在，关键词搜索走候选集内查询；候选集 {1,2} 里没有 3
        coEvery { mediaDao.searchLabelsInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchMlKitLabelsInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchMlKitLabelsZhInIds(listOf(1L, 2L), any()) } returns emptyList()
        coEvery { mediaDao.searchFileNameInIds(listOf(1L, 2L), any()) } returns emptyList()

        val result = engine.search(filter)

        assertEquals(emptyList<Long>(), result.media.map { it.id })
    }

    @Test
    fun `multiple keywords in same dimension use union`() = runTest {
        val filter = StructuredFilter(
            keywords = listOf("小孩", "猫")
        )

        coEvery { mediaDao.searchByLabel("小孩") } returns listOf(mediaEntity(1L))
        coEvery { mediaDao.searchByLabel("猫") } returns listOf(mediaEntity(2L))
        coEvery { mediaDao.searchByMlKitLabel(any()) } returns emptyList()
        coEvery { mediaDao.searchByMlKitLabelZh(any()) } returns emptyList()
        coEvery { mediaDao.searchByFileName(any()) } returns emptyList()
        coEvery { mediaDao.getMediaByIds(listOf(1L, 2L)) } returns listOf(mediaEntity(1L), mediaEntity(2L))

        val result = engine.search(filter)

        assertEquals(setOf(1L, 2L), result.media.map { it.id }.toSet())
    }

    @Test
    fun `face filter intersects with time range`() = runTest {
        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        val filter = StructuredFilter(
            timeRange = timeRange,
            hasFaces = true
        )

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(1L, 2L)
        coEvery { mediaDao.getHasFaceIds() } returns listOf(2L, 3L)
        coEvery { mediaDao.getMediaByIds(listOf(2L)) } returns listOf(mediaEntity(2L))

        val result = engine.search(filter)

        assertEquals(listOf(2L), result.media.map { it.id })
    }

    @Test
    fun `person name filter returns media linked to matched person`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val engineWithPerson = MediaSearchEngine(mediaDao = mediaDao, personDao = personDao)

        val filter = StructuredFilter(personName = "古力娜扎")
        val person = PersonEntity(personId = 19L, name = "古力娜扎")

        coEvery { personDao.findPersonByName("古力娜扎") } returns person
        coEvery { personDao.getMediaByPerson(19L) } returns listOf(mediaEntity(100L), mediaEntity(101L))
        coEvery { mediaDao.getMediaByIds(listOf(100L, 101L)) } returns listOf(mediaEntity(100L), mediaEntity(101L))

        val result = engineWithPerson.search(filter)

        assertEquals(setOf(100L, 101L), result.media.map { it.id }.toSet())
    }

    @Test
    fun `person name filter intersects with time range`() = runTest {
        val personDao: PersonDao = mockk(relaxed = true)
        val engineWithPerson = MediaSearchEngine(mediaDao = mediaDao, personDao = personDao)

        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        val filter = StructuredFilter(
            timeRange = timeRange,
            personName = "古力娜扎"
        )
        val person = PersonEntity(personId = 19L, name = "古力娜扎")

        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(100L, 200L)
        coEvery { personDao.findPersonByName("古力娜扎") } returns person
        coEvery { personDao.getMediaByPerson(19L) } returns listOf(mediaEntity(100L), mediaEntity(300L))
        coEvery { mediaDao.getMediaByIds(listOf(100L)) } returns listOf(mediaEntity(100L))

        val result = engineWithPerson.search(filter)

        assertEquals(listOf(100L), result.media.map { it.id })
    }

    /**
     * 回归测试：人物搜索精度——"找下我儿子的照片"曾返回 470 张（27 张正确的 ∪ ~443 张标签并集污染）。
     *
     * 根因：executeFilter 把 PersonQueryResolver 的命中结果当作内容并集成员，
     * 关键词"儿子"又驱动全库标签搜索，两者取并集。正确语义：人物命中是显式收窄维度，
     * 且已被人物解析消费的关键词不得再驱动标签搜索。
     */
    @Test
    fun `resolver hit excludes keyword tag pollution`() = runTest {
        val engine = sonResolverEngine()

        // 标签污染：全库有大量被打了"儿子/child"类标签的照片（模拟 99）
        coEvery { mediaDao.searchByLabel(any()) } returns listOf(mediaEntity(99L))
        coEvery { mediaDao.searchByFileName(any()) } returns emptyList()
        coEvery { mediaDao.getMediaByIds(any()) } answers { firstArg<List<Long>>().map { id -> mediaEntity(id) } }

        val result = engine.search(
            StructuredFilter(keywords = listOf("儿子")),
            enableSemanticSearch = false
        )

        assertEquals(
            "人物命中为显式维度：只返回儿子的人物簇照片，排除标签并集污染",
            setOf(100L, 101L),
            result.media.map { it.id }.toSet()
        )
    }

    @Test
    fun `resolver hit intersects with time range without pollution`() = runTest {
        val engine = sonResolverEngine()

        val timeRange = TimeRange(startMs = 0, endMs = 1000)
        coEvery { mediaDao.getMediaIdsByTimeRange(timeRange.startMs, timeRange.endMs) } returns listOf(99L, 100L, 200L)
        // 有时间约束 → 关键词走候选集内搜索（searchLabelsInIds），污染源必须打在正确方法上
        coEvery { mediaDao.searchLabelsInIds(any(), any()) } returns listOf(mediaEntity(99L))
        coEvery { mediaDao.getMediaByIds(any()) } answers { firstArg<List<Long>>().map { id -> mediaEntity(id) } }

        val result = engine.search(
            StructuredFilter(timeRange = timeRange, keywords = listOf("儿子")),
            enableSemanticSearch = false
        )

        assertEquals(
            "时间 ∩ 人物簇，标签命中 99 不得因并集混入",
            setOf(100L),
            result.media.map { it.id }.toSet()
        )
    }

    @Test
    fun `resolver hit with unmatched extra keyword falls back to person set`() = runTest {
        val engine = sonResolverEngine()

        // "海边"命中全库标签 50（不在儿子人物簇中）：剩余关键词交集为空时，
        // 应回退人物簇全集——宁返回 27 张正确结果，不返回 0、也不返回带 50 的污染并集
        coEvery { mediaDao.searchByLabel(any()) } returns listOf(mediaEntity(50L))
        coEvery { mediaDao.getMediaByIds(any()) } answers { firstArg<List<Long>>().map { id -> mediaEntity(id) } }

        val result = engine.search(
            StructuredFilter(keywords = listOf("儿子", "海边")),
            enableSemanticSearch = false
        )

        assertEquals(
            "剩余关键词交集为空时回退人物簇，宁返回 27 张正确结果不返回 0",
            setOf(100L, 101L),
            result.media.map { it.id }.toSet()
        )
    }

    @Test
    fun `resolver hit disables semantic recall`() = runTest {
        val semanticEngine: SemanticSearchEngine = mockk(relaxed = true)
        val engine = sonResolverEngine(semantic = semanticEngine)

        coEvery { mediaDao.getMediaByIds(any()) } answers { firstArg<List<Long>>().map { id -> mediaEntity(id) } }

        val result = engine.search(
            StructuredFilter(keywords = listOf("儿子")),
            enableSemanticSearch = true
        )

        assertTrue(result.media.isNotEmpty())
        coVerify(exactly = 0) {
            "人物命中是精确约束，不得启用 MobileCLIP 语义召回引入'长得像'污染"
            semanticEngine.searchByText(any(), any(), any())
        }
    }

    /**
     * 构造带真实 PersonQueryResolver 的引擎：resolveByKinship("儿子") 命中 person 42，
     * 其人物簇含媒体 {100, 101}（对应用户真机上 27 张的抽象）。
     */
    private fun sonResolverEngine(semantic: SemanticSearchEngine? = null): MediaSearchEngine {
        val repository = mockk<PersonRepository>(relaxed = true)
        coEvery { repository.resolveByCustomLabels(any()) } returns emptyList()
        coEvery { repository.getNamedPersons() } returns emptyList()
        coEvery { repository.resolveByKinship("儿子") } returns listOf(PersonEntity(personId = 42L, name = null))
        coEvery { repository.getSelfPerson() } returns null

        val personDao: PersonDao = mockk(relaxed = true)
        coEvery { personDao.getMediaByPerson(42L) } returns listOf(mediaEntity(100L), mediaEntity(101L))

        return MediaSearchEngine(
            mediaDao = mediaDao,
            personDao = personDao,
            semanticSearchEngine = semantic,
            personQueryResolver = PersonQueryResolver(repository)
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
