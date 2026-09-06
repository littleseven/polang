package com.mamba.picme.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.domain.model.AppLanguage

@Entity(
    tableName = "media_assets",
    indices = [
        Index("captureDate"),
        Index("hasFace"),
        // uri 是 updateLastViewedAt/updateQualityScores 的 WHERE 键，非唯一索引消除全表扫描
        Index("uri")
    ]
)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uri: String,
    val type: MediaType,
    val captureDate: Long,
    val fileName: String,
    val duration: Long? = null,
    val hasFace: Boolean = false,
    val faceId: String? = null,
    val source: String? = null,
    // 元数据索引字段（Phase 1 自然语言搜索）
    val labels: String? = null,           // JSON 数组：["猫","户外","食物"]
    /** 打标统一规格英文 JSON（tagger 原语，默认 Florence-2；英文搜索/展示来源） */
    val labelsEn: String? = null,
    /** 打标统一规格中文 JSON（由 labelsEn 离线汉化派生：tags 词表映射 + summary MT；中文搜索/展示来源） */
    val labelsZh: String? = null,
    /** ML Kit Image Labeler 输出的英文标签（JSON 数组），与 Qwen 的 labels 字段完全独立 */
    val mlKitLabels: String? = null,      // JSON 数组：["Outdoor","Food"]
    /** ML Kit 英文标签对应的中文翻译（JSON 数组），用于中文搜索直接命中 */
    val mlKitLabelsZh: String? = null,    // JSON 数组：["户外","食物"]
    val ocrText: String? = null,          // OCR 提取的文字
    val latitude: Double? = null,         // GPS 纬度
    val longitude: Double? = null,        // GPS 经度
    val locationName: String? = null,     // 逆地理编码地名
    val city: String? = null,             // 逆地理编码城市（去范式化,供按城市分组）
    val indexedAt: Long? = null,          // 索引完成时间戳（null=未索引）
    // 人脸 ROI 检测结果 JSON（Stage 1 产出持久化，用于 Pass 1→Pass 3 断点续扫）
    val faceRoiResult: String? = null,
    /** 人脸纵向聚焦点（归一化 0~1，Pass 1 检测算出；null=无人脸/未回填）。供列表缩略图纵向对齐。 */
    val faceFocusY: Float? = null,
    /** NIMA 美学评分（1.0~10.0；null=未评分）。供人脸聚类挑最佳封面。 */
    val aestheticScore: Float? = null,
    /** eDifFIQA 人脸质量评分（~0~1；null=未评分）。人脸封面选择的主信号。 */
    val faceQualityScore: Float? = null,
    /** Laplacian 方差模糊分（越大越清晰；null=未计算）。整理中心 v2 LOW_QUALITY_PHOTOS 主信号。 */
    val blurScore: Float? = null,
    /** 平均亮度归一（0~1，0.5 附近为正常曝光；null=未计算）。 */
    val exposureScore: Float? = null,
    /** 用户在查看器最近一次打开时间戳（null=从未查看）。整理中心 v2 价值保护信号。 */
    val lastViewedAt: Long? = null,

    // MobileCLIP 语义 embedding（512 维 FloatArray 的 Base64，供语义搜索）
    val semanticEmbedding: String? = null,

    // 最近一次 TAG 扫描成功时间戳（用于增量去重与避重）
    val lastTagScanAt: Long? = null,

    // 最近一次成功扫描覆盖的 Pass 阶段 JSON，如 {"1":ts,"2":ts,"3":ts}
    val lastTagScanPasses: String? = null
) {
    /**
     * 按 UI 语言取对应标签 JSON：英文/西语/法语 UI→[labelsEn]，其余→[labelsZh]；
     * 目标字段为空（老数据未回填）时回退 [labels]。供展示/搜索读取。
     */
    fun labelsForLanguage(lang: AppLanguage): String? =
        when (lang) {
            AppLanguage.ENGLISH, AppLanguage.SPANISH, AppLanguage.FRENCH -> labelsEn ?: labels
            else -> labelsZh ?: labels
        }
}
