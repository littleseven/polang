package com.mamba.picme.features.chat.openui

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
