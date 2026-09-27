package com.mamba.picme.domain.tag

import android.graphics.RectF

/**
 * 单个人脸的 ROI + RetinaFace 5 点 landmarks（用于人脸 embedding 对齐）
 *
 * @param roi 人脸 ROI 区域（像素坐标）
 * @param landmarks5 5 点原图像素坐标（FloatArray，长度 10）。
 *                   顺序：[左眼 x,y, 右眼 x,y, 鼻尖 x,y, 左嘴角 x,y, 右嘴角 x,y]。
 *                   null 表示 ROI 检测器未提供 landmarks（兼容回退路径）。
 */
data class FaceRoi(
    val roi: RectF,
    val landmarks5: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceRoi
        return roi == other.roi &&
            ((landmarks5 == null && other.landmarks5 == null) ||
                (landmarks5 != null && other.landmarks5 != null &&
                    landmarks5.contentEquals(other.landmarks5)))
    }

    override fun hashCode(): Int {
        var result = roi.hashCode()
        result = 31 * result + (landmarks5?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Stage 1 产出：人脸 ROI 检测结果（含可选 5 点 landmarks）
 */
data class Stage1Result(
    val hasFace: Boolean,
    val faceCount: Int = 0,
    val faces: List<FaceRoi> = emptyList()
) {
    /**
     * 兼容旧代码：仅返回 ROI 矩形列表
     */
    val roiRects: List<RectF> get() = faces.map { it.roi }

    val isSelfie: Boolean get() = faceCount == 1

    /**
     * 合影判定策略：
     * - 有效人脸数 >= 2 即识别为合影
     * - 有效人脸定义：已通过 detectFacesOnly 过滤掉面积 < 1.5% 图片总面积的小脸/误检
     *
     * 此逻辑与 FaceDetectorManager.detectFacesOnly 中的过滤策略一致。
     */
    val isGroupPhoto: Boolean get() = faceCount >= 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Stage1Result
        return hasFace == other.hasFace &&
                faceCount == other.faceCount &&
                faces == other.faces
    }

    override fun hashCode(): Int {
        var result = hasFace.hashCode()
        result = 31 * result + faceCount
        result = 31 * result + faces.hashCode()
        return result
    }
}

/**
 * Stage 2 产出：每张人脸的嵌入结果
 */
data class FaceEmbeddingOutput(
    val mediaId: Long,
    val embedding: FloatArray,
    val personId: Long?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FaceEmbeddingOutput
        return mediaId == other.mediaId &&
                embedding.contentEquals(other.embedding) &&
                personId == other.personId
    }

    override fun hashCode(): Int {
        var result = mediaId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        return result
    }
}

/**
 * Stage 2 聚类结果：所有检测到的人脸及其归属
 */
data class Stage2Result(
    val faceEmbeddings: List<FaceEmbeddingOutput>,
    val personIds: List<Long>
)

/**
 * Stage 3 Qwen 输出的原始标签（反序列化用）
 */
data class QwenTags(
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = ""
)

/**
 * 规范化后的标签（后处理后写入数据库）
 */
data class QwenTagsNormalized(
    val scene: String,
    val activity: String,
    val objects: List<String>,
    val tags: List<String>,
    val summary: String,
    val nonStandard: List<String> = emptyList(),
    /** JSON 是否被成功解析（与受控词表匹配无关） */
    val jsonParsed: Boolean = true
)

/**
 * 最终写入 MediaAsset.labels 的 JSON 结构
 */
data class UnifiedTagResult(
    val face: FaceTagInfo = FaceTagInfo(),
    val scene: String = "",
    val activity: String = "",
    val objects: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val summary: String = ""
)

data class FaceTagInfo(
    val count: Int = 0,
    val selfie: Boolean = false,
    val groupPhoto: Boolean = false,
    val personIds: List<Long> = emptyList()
)

/**
 * 管道处理阶段
 */
enum class PipelineStage {
    /** Pass 1: 人脸 ROI + 人脸 Embedding + MobileCLIP 语义编码（已内联合并） */
    FACE_ROI,
    /** Pass 2: 全局 DBSCAN 聚类 */
    FACE_CLUSTER,
    /** Pass 3: 图像打标（图像内容理解） */
    IMAGE_TAGGING,
    /**
     * MobileCLIP 语义编码（保留枚举值以兼容历史任务/单独重编码场景）。
     * 注意：常规扫描已将该阶段内联到 [FACE_ROI]。
     */
    MOBILE_CLIP,
    COMPLETE
}

/**
 * 管道处理阶段（3-Pass 混合模型细化阶段名）
 */
enum class PassStage {
    /** Pass 1: 人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（已内联合并） */
    FACE_DETECTION,
    /** Pass 2: 全局 DBSCAN 聚类 */
    DBSCAN_CLUSTERING,
    /** Pass 3: 图像打标（图像内容理解） */
    IMAGE_TAGGING,
    /**
     * MobileCLIP 语义编码（保留枚举值以兼容历史任务/单独重编码场景）。
     * 注意：常规扫描已将该阶段内联到 [FACE_DETECTION]。
     */
    MOBILE_CLIP_ENCODING,
    COMPLETE
}

/**
 * 扫描进度
 */
data class TagScanProgress(
    val processed: Int,
    val total: Int,
    val currentStage: PipelineStage = PipelineStage.FACE_ROI,
    val currentItem: Int = 0
)

/**
 * 3-Pass 混合模型扫描进度
 */
data class HybridScanProgress(
    val pass: PassStage = PassStage.FACE_DETECTION,
    val processed: Int = 0,
    val total: Int = 0,
    val currentStage: PipelineStage = PipelineStage.FACE_ROI
)

/**
 * Stage 1 结果持久化 JSON 的数据结构
 */
data class FaceRoiPersist(
    val hasFace: Boolean,
    val faceCount: Int,
    val isSelfie: Boolean,
    val isGroupPhoto: Boolean
)

/**
 * [Pass 1] 单张照片的人脸检测 + Embedding 提取结果
 *
 * 内联合成 MobileCLIP 语义 embedding，避免 Pass 1 和 MobileCLIP 阶段重复解码同一张图。
 */
data class Stage1WithEmbeddingsResult(
    /** faceRoi JSON（null = 解码失败；非 null = 已处理，可能无人脸） */
    val faceRoiJson: String?,
    /** 每张人脸的 512 维 embedding */
    val embeddings: List<FloatArray>,
    /** MobileCLIP 语义 embedding Base64（null = 编码失败） */
    val semanticEmbedding: String? = null,
    /** 人脸纵向聚焦点（归一化 0~1；null=无人脸/解码失败）。供列表对齐持久化。 */
    val faceFocusY: Float? = null,
    /** 检测器是否检出人脸（与 faceRoiJson 内的 hasFace 同源，免去调用方反解 JSON） */
    val faceDetected: Boolean = false
)

/**
 * 人脸 Embedding 及其关联信息（用于 Pass 1→Pass 2 桥接）
 */
data class FaceEmbeddingBatch(
    val mediaId: Long,
    val faceIdx: Int,
    val embedding: FloatArray
)

/**
 * 计算人脸纵向「聚焦点」——所有人脸 ROI 纵向并集的中心，归一化到 [0,1]（相对 bitmap 高度）。
 *
 * 用于列表缩略图在 ContentScale.Crop 下的纵向对齐：null 表示无人脸（UI 回退居中）。
 * 仅取纵向（top/bottom）并集，忽略横向；与镜头方向无关。
 */
fun computeFaceFocusY(faces: List<FaceRoi>, bitmapHeight: Int): Float? {
    if (faces.isEmpty() || bitmapHeight <= 0) return null
    val minTop = faces.minOf { it.roi.top }
    val maxBottom = faces.maxOf { it.roi.bottom }
    val center = (minTop + maxBottom) / 2f
    return (center / bitmapHeight.toFloat()).coerceIn(0f, 1f)
}