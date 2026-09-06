package com.mamba.picme.domain.memories

import java.time.MonthDay

/** 回忆类型：那年今日 / 近期高光 / 人物合集 / 地点足迹。 */
enum class MemoryType { ON_THIS_DAY, RECENT_HIGHLIGHTS, PERSON, CITY }

/** 生成器输入：单条媒体的最小投影（仅照片参与回忆）。 */
data class MemoryInput(
    val uri: String,
    val captureDate: Long,
    val aestheticScore: Float?,
    val city: String?,
    val personId: String?,
)

/** 已命名人物投影。 */
data class NamedPerson(
    val personId: String,
    val name: String,
    val isSelf: Boolean,
)

/**
 * 一条回忆：稳定 id + 类型化展示参数 + 精选内容（封面在首）。
 * 文案不在此层拼接（I18N 红线）：UI 按 [type] + 参数经 stringResource 还原本地化 title/subtitle。
 */
data class Memory(
    val id: String,
    val type: MemoryType,
    /** 人物名（PERSON）/城市名（CITY）；ON_THIS_DAY、RECENT_HIGHLIGHTS 为 null。 */
    val label: String?,
    /** 命中总张数（PERSON/CITY 副行「N photos」）；精选 [itemUris] 截 [MemoriesGenerator.DETAIL_LIMIT] 后可能小于该值。 */
    val hitCount: Int,
    /** ON_THIS_DAY：命中照片中的最大年份；其余类型为 null。 */
    val latestYear: Int?,
    /** ON_THIS_DAY：与 now 同月同日；其余类型为 null。展示由 UI 按 Locale 本地化。 */
    val monthDay: MonthDay?,
    val coverUri: String,
    /** 精选 URI（美学分降序截 [MemoriesGenerator.DETAIL_LIMIT]），封面在首。 */
    val itemUris: List<String>,
    /** 全部命中 URI（拍摄时间降序，不截断；详情页「全部」开关与分享全集用）。 */
    val allItemUris: List<String>,
)
