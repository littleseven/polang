package com.mamba.picme.features.gallery.memories

import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import com.mamba.picme.domain.memories.MemoriesGenerator
import com.mamba.picme.domain.memories.Memory
import com.mamba.picme.domain.memories.MemoryHiddenStore
import com.mamba.picme.domain.memories.MemoryType
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemoriesViewModelTest {

    /** 用系统默认时区正午构造时间戳：月日分组与生成器（systemDefault）天然对齐，测试与机器时区无关。 */
    private val zone: ZoneId = ZoneId.systemDefault()

    private fun at(year: Int, month: Int, day: Int): Long =
        LocalDateTime.of(year, month, day, 12, 0).atZone(zone).toInstant().toEpochMilli()

    /** 固定 now：2026-09-05 12:00（本地时区）。 */
    private val now: Long = at(2026, 9, 5)

    private fun photo(
        uri: String,
        date: Long,
        score: Float? = null,
        city: String? = null,
        faceId: String? = null,
    ) = MediaEntity(
        uri = uri,
        type = MediaType.PHOTO,
        captureDate = date,
        fileName = "$uri.jpg",
        aestheticScore = score,
        city = city,
        faceId = faceId,
    )

    private fun video(uri: String, date: Long) = MediaEntity(
        uri = uri,
        type = MediaType.VIDEO,
        captureDate = date,
        fileName = "$uri.mp4",
    )

    private class FakeHiddenStore : MemoryHiddenStore {
        val hidden = MutableStateFlow<Set<String>>(emptySet())
        override val ids: Flow<Set<String>> = hidden
        override suspend fun hide(id: String) {
            hidden.value = hidden.value + id
        }
    }

    /** JVM 单测无 Room：mockk 拦截 observe 方法，answers 读 StateFlow 支持重发。 */
    private class FakeData {
        val media = MutableStateFlow<List<MediaEntity>>(emptyList())
        val persons = MutableStateFlow<List<PersonEntity>>(emptyList())
        val mediaDao: MediaDao = mockk {
            every { getAllMedia() } answers { media }
        }
        val personDao: PersonDao = mockk {
            every { observeAll() } answers { persons }
        }
    }

    private fun viewModel(
        scope: TestScope,
        data: FakeData,
        hiddenStore: FakeHiddenStore = FakeHiddenStore(),
    ): MemoriesViewModel {
        // VM 内 stateIn 是无限订阅：挂独立 TestScope（非 runTest 子作用域），
        // 避免 runTest teardown 等待永活 collector 报 UncompletedCoroutinesError
        val vmScope = TestScope(scope.testScheduler)
        return MemoriesViewModel(
            mediaDao = data.mediaDao,
            personDao = data.personDao,
            hiddenStore = hiddenStore,
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
            coroutineScope = vmScope,
            nowMs = { now },
        )
    }

    /** 订阅 memories（WhileSubscribed 需有订阅者才产出），返回快照列表（末位即最新值）。 */
    private fun TestScope.collectMemories(vm: MemoriesViewModel): MutableList<List<Memory>> {
        val values = mutableListOf<List<Memory>>()
        // 收集器挂 runTest 子作用域会拖住 teardown：与 VM 同挂独立 TestScope
        TestScope(testScheduler).launch { vm.memories.collect { list -> values += list } }
        return values
    }

    /** 近 30 天内 6 张带分照片 → RECENT_HIGHLIGHTS。 */
    private fun recentPhotos(count: Int, score: Float = 7f): List<MediaEntity> =
        (1..count).map { index -> photo("r$index", at(2026, 9, 1), score = score + index / 100f) }

    /** 2023-09-05 四张（与 now 同月同日不同年）→ ON_THIS_DAY。 */
    private fun onThisDayPhotos(count: Int = MemoriesGenerator.MIN_ON_THIS_DAY): List<MediaEntity> =
        (1..count).map { index -> photo("otd$index", at(2023, 9, 5), score = 8f) }

    @Test
    fun `recent highlights generated from scored photos only, videos excluded`() = runTest {
        val data = FakeData().apply {
            media.value = recentPhotos(6) + video("v1", at(2026, 9, 2)) + video("v2", at(2026, 9, 3))
        }
        val vm = viewModel(this, data)
        val values = collectMemories(vm)
        advanceUntilIdle()
        val memories = values.last()
        assertEquals(1, memories.size)
        assertEquals(MemoryType.RECENT_HIGHLIGHTS, memories[0].type)
        assertTrue(memories[0].itemUris.none { uri -> uri.startsWith("v") })
    }

    @Test
    fun `unscored or old photos do not produce recent highlights`() = runTest {
        val data = FakeData().apply {
            media.value = (1..8).map { index -> photo("old$index", at(2020, 3, 10)) }
        }
        val vm = viewModel(this, data)
        val values = collectMemories(vm)
        advanceUntilIdle()
        assertTrue(values.last().isEmpty())
    }

    @Test
    fun `hideMemory removes memory from carousel and persists id`() = runTest {
        val data = FakeData().apply { media.value = onThisDayPhotos() }
        val store = FakeHiddenStore()
        val vm = viewModel(this, data, store)
        val values = collectMemories(vm)
        advanceUntilIdle()
        val memory = values.last().single()
        assertEquals(MemoryType.ON_THIS_DAY, memory.type)

        vm.hideMemory(memory.id)
        advanceUntilIdle()
        assertTrue(values.last().isEmpty())
        assertTrue(store.hidden.value.contains(memory.id))
    }

    @Test
    fun `getMemory resolves hidden memory from unfiltered set`() = runTest {
        val data = FakeData().apply { media.value = onThisDayPhotos() }
        val store = FakeHiddenStore()
        val vm = viewModel(this, data, store)
        val values = collectMemories(vm)
        advanceUntilIdle()
        val memory = values.last().single()

        vm.hideMemory(memory.id)
        advanceUntilIdle()
        assertTrue(values.last().isEmpty())
        // 已隐藏条目仍可从全集复原（详情页直达/刷新场景）
        assertEquals(memory, vm.getMemory(memory.id))
        assertNull(vm.getMemory("on_this_day:01-01"))
    }

    @Test
    fun `named persons produce person memories, unnamed and self excluded`() = runTest {
        val data = FakeData().apply {
            media.value = (1..6).map { index -> photo("mom$index", at(2026, 6, 10), faceId = "11") } +
                (1..6).map { index -> photo("anon$index", at(2026, 6, 11), faceId = "22") } +
                (1..6).map { index -> photo("self$index", at(2026, 6, 12), faceId = "33") }
            persons.value = listOf(
                PersonEntity(personId = 11L, name = "Mom"),
                PersonEntity(personId = 22L, name = null),
                PersonEntity(personId = 33L, name = "Me", isSelf = true),
            )
        }
        val vm = viewModel(this, data)
        val values = collectMemories(vm)
        advanceUntilIdle()
        val memories = values.last()
        assertEquals(1, memories.size)
        assertEquals(MemoryType.PERSON, memories[0].type)
        assertEquals("Moments with Mom", memories[0].title)
        assertTrue(memories[0].itemUris.all { uri -> uri.startsWith("mom") })
        assertEquals("person:11", memories[0].id)
    }

    @Test
    fun `media updates re-generate memories reactively`() = runTest {
        val data = FakeData()
        val vm = viewModel(this, data)
        val values = collectMemories(vm)
        advanceUntilIdle()
        assertTrue(values.last().isEmpty())

        data.media.value = onThisDayPhotos()
        advanceUntilIdle()
        assertEquals(MemoryType.ON_THIS_DAY, values.last().single().type)
    }
}
