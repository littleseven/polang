package com.mamba.picme.domain.memories

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

/** 一条回忆：稳定 id + 展示文案 + 精选内容（封面在首）。 */
data class Memory(
    val id: String,
    val type: MemoryType,
    val title: String,
    val subtitle: String,
    val coverUri: String,
    val itemUris: List<String>,
)
