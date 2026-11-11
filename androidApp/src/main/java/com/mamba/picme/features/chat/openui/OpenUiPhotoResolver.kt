package com.mamba.picme.features.chat.openui

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * OpenUI Image 组件渲染期解析到的照片（spec《openui-image-component-design》D4）。
 * uri 只在端侧存在——不进 program、不进 handle payload、不回灌 LLM（[PRIVACY]）。
 */
data class OpenUiPhoto(
    val uri: String,
    val faceFocusY: Float?,
    /** 照片真实宽高比（宽/高，MediaStore 元数据；null=取不到 → 渲染层回落 4:3）。
     *  aspect prop 缺省时渲染层按它定框（宽 sanity 界 [0.3, 3.0]），竖屏图不再被横框拦腰。 */
    val aspectRatio: Float? = null,
)

/**
 * OpenUI Image 组件 photoId → 端侧媒体解析接缝（DI 显式注入，Agent First §2.1）。
 * 实现 = AppContainer 内 `AndroidMediaRepository.getMediaById` 双通道：Room 正 id 直查
 * DAO；未索引系统媒体的负 synthetic id 经 allMedia 合并流反查（2026-10-25 修复，此前
 * 直查 DAO 致负 id 恒 null）。解析不到（已删除/未索引）→ null，渲染层落占位（半信任
 * 输入防御，不炸整卡）。
 */
fun interface OpenUiPhotoResolver {
    suspend fun resolve(photoId: Long): OpenUiPhoto?
}

/** [resolveAllUrisInOrder] 并发上限：会话全量照片实测可达 ~300 张，裸 launch 扇出会瞬间打满解析通道；8 在批量吞吐与 DAO 压力间取平衡。 */
private const val SESSION_PHOTO_RESOLVE_PARALLELISM = 8

/**
 * 批量解析照片 id → uri 列表（有界并行，2026-11-11 性能收口）：
 * `resolveAllProgressively` 并行模式 + Semaphore 限界的整批形态——结果顺序与 [ids]
 * 对齐（索引槽位），解析失败（null，已删除/未索引）跳过，mapNotNull 语义不变。
 * 替代 OpenUiCardHost 会话照片的串行 `mapNotNull { resolve(it)?.uri }`：~300 张
 * 串行在扫描负载下秒级白付，用户不点照片也全量执行（PhotoGrid 串行灰格同病灶）。
 */
internal suspend fun OpenUiPhotoResolver.resolveAllUrisInOrder(ids: List<Long>): List<String> = coroutineScope {
    val slots = arrayOfNulls<OpenUiPhoto?>(ids.size)
    val gate = Semaphore(SESSION_PHOTO_RESOLVE_PARALLELISM)
    ids.forEachIndexed { index, photoId ->
        launch {
            gate.withPermit { slots[index] = resolve(photoId) }
        }
    }
    slots.filterNotNull().map { photo -> photo.uri }
}
