package com.mamba.picme.agent.core.model.context

import kotlinx.serialization.Serializable

/**
 * 媒体资源基础模型
 *
 * `@Serializable`：供搜索/投影链路（`local://scan/<id>` 句柄、OpenUI 卡数据绑定）经
 * kotlinx JSON 传输；不改变任何既有读写路径。
 */
@Serializable
data class MediaAsset(
    val id: Long = 0,
    val uri: String,
    val type: MediaType,
    val captureDate: Long,
    val fileName: String,
    val duration: Long? = null,
    val hasFace: Boolean = false,
    val faceId: String? = null,
    val source: String? = null,
    // 元数据索引字段（自然语言搜索）
    val labels: String? = null,
    val ocrText: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null,
    val city: String? = null,
    val indexedAt: Long? = null,
    /** 人脸纵向聚焦点（归一化 0~1，null=无人脸/未回填）。列表缩略图纵向对齐用。 */
    val faceFocusY: Float? = null,
    /** NIMA 美学评分（1.0~10.0；null=未评分，照片信息不显示）。 */
    val aestheticScore: Float? = null,
    /** eDifFIQA 人脸质量评分（~0~1；null=未评分，照片信息不显示）。 */
    val faceQualityScore: Float? = null
)

enum class MediaType {
    PHOTO,
    VIDEO,
    DOCUMENT
}
