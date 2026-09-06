package com.mamba.picme.domain.memories

import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.time.ZoneId

/**
 * Memory 页「回忆」生成器（纯函数，零 Android 依赖、零推理，可 JVM 单测）。
 *
 * 输入契约：所有 [MemoryInput] 视为照片——视频由上游（数据源查询）过滤，生成器不再判定类型。
 * 确定性：`now` 与 `zoneId` 由调用方注入，测试可固定；生产默认 [ZoneId.systemDefault]。
 *
 * 输出顺序：ON_THIS_DAY > RECENT_HIGHLIGHTS > PERSON（媒体数降序）> CITY（媒体数降序），
 * 总量截 [maxCarousel]（默认 [MAX_CAROUSEL]；当前各类型上限合计 7 条，该上限为未来类型扩展预留）。
 */
object MemoriesGenerator {

    /** carousel 最大条数。 */
    const val MAX_CAROUSEL = 10

    /** 单条回忆精选张数上限。 */
    const val DETAIL_LIMIT = 12

    /** 那年今日最少命中张数。 */
    const val MIN_ON_THIS_DAY = 4

    /** 人物/城市分组最少命中张数；近期高光同阈值。 */
    const val MIN_GROUP = 6

    /** 人物回忆最大条数。 */
    const val MAX_PERSON = 3

    /** 城市回忆最大条数。 */
    const val MAX_CITY = 2

    /** 近期高光窗口：30 天。 */
    const val RECENT_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun generate(
        inputs: List<MemoryInput>,
        persons: List<NamedPerson>,
        now: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        maxCarousel: Int = MAX_CAROUSEL,
    ): List<Memory> {
        val nowDate = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val memories = mutableListOf<Memory>()
        onThisDay(inputs, nowDate, zoneId)?.let { memory -> memories += memory }
        recentHighlights(inputs, now, nowDate)?.let { memory -> memories += memory }
        memories += personMemories(inputs, persons)
        memories += cityMemories(inputs)
        return memories.take(maxCarousel)
    }

    /** 那年今日：与 now 同月同日的往年（year < now）照片跨年聚合，未来年份（时钟异常/EXIF 未来）不混入。 */
    private fun onThisDay(inputs: List<MemoryInput>, nowDate: LocalDate, zoneId: ZoneId): Memory? {
        val hits = inputs.filter { input ->
            val date = input.localDate(zoneId)
            date.monthValue == nowDate.monthValue &&
                date.dayOfMonth == nowDate.dayOfMonth &&
                date.year < nowDate.year
        }
        if (hits.size < MIN_ON_THIS_DAY) return null
        return buildMemory(
            id = "on_this_day:${twoDigit(nowDate.monthValue)}-${twoDigit(nowDate.dayOfMonth)}",
            type = MemoryType.ON_THIS_DAY,
            hits = hits,
            latestYear = hits.maxOf { input -> input.localDate(zoneId).year },
            monthDay = MonthDay.from(nowDate),
        )
    }

    /** 近期高光：近 30 天内已评分照片按分降序。 */
    private fun recentHighlights(inputs: List<MemoryInput>, now: Long, nowDate: LocalDate): Memory? {
        val hits = inputs.filter { input ->
            input.aestheticScore != null && input.captureDate in (now - RECENT_WINDOW_MS)..now
        }
        if (hits.size < MIN_GROUP) return null
        return buildMemory(
            id = "recent:${nowDate.year}-${twoDigit(nowDate.monthValue)}",
            type = MemoryType.RECENT_HIGHLIGHTS,
            hits = hits,
        )
    }

    /** 人物合集：已命名（非本人）人物按命中媒体数降序取前 [MAX_PERSON]。 */
    private fun personMemories(inputs: List<MemoryInput>, persons: List<NamedPerson>): List<Memory> {
        val byPerson = inputs.groupBy { input -> input.personId }
        return persons
            .filter { person -> !person.isSelf }
            .mapNotNull { person ->
                val hits = byPerson[person.personId].orEmpty()
                if (hits.size < MIN_GROUP) null else person to hits
            }
            .sortedByDescending { pair -> pair.second.size }
            .take(MAX_PERSON)
            .map { pair ->
                buildMemory(
                    id = "person:${pair.first.personId}",
                    type = MemoryType.PERSON,
                    hits = pair.second,
                    label = pair.first.name,
                )
            }
    }

    /** 地点足迹：同城市照片达阈值的城市按张数降序取前 [MAX_CITY]。 */
    private fun cityMemories(inputs: List<MemoryInput>): List<Memory> =
        inputs
            .filter { input -> !input.city.isNullOrBlank() }
            .groupBy { input -> input.city }
            .mapNotNull { entry ->
                val city = entry.key ?: return@mapNotNull null
                if (entry.value.size < MIN_GROUP) null else city to entry.value
            }
            .sortedByDescending { pair -> pair.second.size }
            .take(MAX_CITY)
            .map { pair ->
                buildMemory(
                    id = "city:${pair.first}",
                    type = MemoryType.CITY,
                    hits = pair.second,
                    label = pair.first,
                    earliestCaptureDate = pair.second.minOf { input -> input.captureDate },
                    latestCaptureDate = pair.second.maxOf { input -> input.captureDate },
                )
            }

    /** 精选排序：美学分降序（null 排最后），同分拍摄时间新的在前；截 [DETAIL_LIMIT]，封面取首张。 */
    @Suppress("LongParameterList") // 各类型可选参数平铺（label/latestYear/monthDay/日期范围），默认 null；抽配置类收益低于可读性损耗
    private fun buildMemory(
        id: String,
        type: MemoryType,
        hits: List<MemoryInput>,
        label: String? = null,
        latestYear: Int? = null,
        monthDay: MonthDay? = null,
        earliestCaptureDate: Long? = null,
        latestCaptureDate: Long? = null,
    ): Memory {
        val selected = hits
            .sortedWith(
                compareByDescending<MemoryInput> { input -> input.aestheticScore ?: Float.NEGATIVE_INFINITY }
                    .thenByDescending { input -> input.captureDate },
            )
            .take(DETAIL_LIMIT)
        return Memory(
            id = id,
            type = type,
            label = label,
            hitCount = hits.size,
            latestYear = latestYear,
            monthDay = monthDay,
            coverUri = selected.first().uri,
            itemUris = selected.map { input -> input.uri },
            allItemUris = hits
                .sortedByDescending { input -> input.captureDate }
                .map { input -> input.uri },
            earliestCaptureDate = earliestCaptureDate,
            latestCaptureDate = latestCaptureDate,
        )
    }

    private fun MemoryInput.localDate(zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(captureDate).atZone(zoneId).toLocalDate()

    private fun twoDigit(value: Int): String = value.toString().padStart(2, '0')
}
