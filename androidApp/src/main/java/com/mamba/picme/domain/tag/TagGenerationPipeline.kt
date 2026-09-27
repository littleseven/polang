@file:Suppress("TooGenericExceptionCaught") // 通用兜底：catch(Exception) 防崩溃，已记录日志
package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import com.mamba.picme.agent.core.inference.local.llm.LocalLlmEngine
import com.mamba.picme.domain.tag.florence2.Florence2Tagger
import com.mamba.picme.beauty.api.facedetect.FaceDetectionResult
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.tag.prompt.DefaultTagPromptProvider
import com.mamba.picme.domain.tag.prompt.TagPromptProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * 判断 MIME 是否为图片类型。
 *
 * 供 [TagGenerationPipeline.loadBitmap] 在解码前拦截非图片媒体（视频/音频等），
 * 避免对大文件（如屏录视频）做无意义且可能 OOM 的图像解码。
 * mime 为 null 时返回 false（无法判定为图片），由调用方决定是否放行。
 */
internal fun isImageMimeType(mime: String?): Boolean =
    mime != null && mime.startsWith("image/", ignoreCase = true)

/**
 * 三阶段 Tag 生成管道
 *
 * ```
 * Stage 1 (Face ROI) ─── 有人脸? ──→ YES ──→ Stage 2 (Face Cluster)
 *      │                                         │
 *      │ NO                                      │
 *      ↓                                         ↓
 * Stage 3 (Qwen without face context)    Stage 3 (Qwen with face context)
 * ```
 *
 * 依赖注入：
 * - [faceDetector]：Stage 1 使用，RetinaFace Det500M
 * - [llmEngine]：Stage 3 使用，Qwen3-VL-2B MNN
 * - [faceClusterEngine]：Stage 2 使用，Glint360K R100 + 增量聚类
 * - [normalizer]：标签后处理规范化
 * - [openClGuardian]：OpenCL 超时守卫（可选）
 * - [promptProvider]：Prompt 生成策略
 * - [mobileClipEngine]：MobileCLIP 语义编码（可选）
 * - [mobileClipTagClassifier]：MobileCLIP 零 shot 标签分类器（可选）
 */
@Suppress("LongParameterList") // 待重构：依赖容器，考虑分组
class TagGenerationPipeline(
    private val context: Context,
    private val faceDetector: FaceDetector,
    private val llmEngine: LocalLlmEngine,
    private val faceClusterEngine: FaceClusterEngine,
    private val normalizer: TagNormalizer,
    private val openClGuardian: OpenClGuardian? = null,
    private val promptProvider: TagPromptProvider = DefaultTagPromptProvider(),
    private val mobileClipEngine: MobileClipEngine? = null,
    private val mobileClipTagClassifier: MobileClipTagClassifier? = null,
    private val florence2TaggerProvider: () -> Florence2Tagger? = { null },
    private val taggerModelKeyProvider: () -> String = { TaggerModelSelector.defaultKey }
) {

    companion object {
        private const val TAG = "TagPipeline"

        /** 人脸检测前的图片最长边缩放 */
        private const val MAX_FACE_DETECT_SIZE = 640

        /** VLM 图像推理的图片最长边缩放 */
        private const val MAX_VISION_SIZE = 512

        /** Qwen Stage 3 最大输出 token 数。SmolVLM-256M 输出 JSON 需要 256 tokens 才能完整闭合。 */
        private const val QWEN_MAX_TOKENS = 256

        /**
         * EXIF 旋转角度缓存：URI -> rotationDegrees。
         * 在 Pass 1 与 Pass 3 分离执行时，避免对同一张图重复读取 EXIF。
         */
        private val exifRotationCache = LruCache<String, Int>(200)
    }

    /** 打标恒用英文（SmolVLM 原语）；中文由 LabelSinicizer 离线派生到 labelsZh，不再随 UI 语言切换。 */
    private val targetLanguage: AppLanguage
        get() = AppLanguage.ENGLISH

    private val stage3SystemPrompt: String
        get() = promptProvider.systemPrompt(targetLanguage)

    /**
     * 单张照片完整处理管道
     *
     * @param uri 照片 Content URI
     * @param lensFacing 镜头方向（CameraSelector.LENS_FACING_BACK/FRONT）
     * @param mediaId 数据库中的媒体 ID
     * @return 最终写入 labels 字段的 JSON 字符串
     */
    suspend fun processPhoto(
        uri: String,
        lensFacing: Int,
        mediaId: Long
    ): String {
        Log.d(TAG, "=== Pipeline start: mediaId=$mediaId ===")

        // 一次性加载 640px Bitmap，Stage 1 / Stage 2 / Stage 3 共用
        val faceBitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE)
        if (faceBitmap == null) {
            Log.w(TAG, "Failed to load bitmap for mediaId=$mediaId")
            return """{"face":{"count":0}}"""
        }

        val stage1Result: Stage1Result
        val stage2Result: Stage2Result?
        val stage3Result: UnifiedTagResult

        try {
            // ── Stage 1: 轻量人脸 ROI 检测（复用 faceBitmap）───
            stage1Result = stage1FaceDetection(faceBitmap, lensFacing)
            Log.d(TAG, "Stage 1 done: hasFace=${stage1Result.hasFace}, count=${stage1Result.faceCount}")

            // ── Stage 2: 人脸聚类（复用同一个 faceBitmap，不再重新解码）───
            stage2Result = if (stage1Result.hasFace) {
                stage2FaceCluster(faceBitmap, mediaId, stage1Result)
            } else {
                null
            }
            Log.d(TAG, "Stage 2 done: personIds=${stage2Result?.personIds ?: "N/A"}")

            // ── Stage 3: 统一分流（与批量同模型同提示词；按模型内部解码合适尺寸）───
            val faceRoiJson = faceRoiToJson(stage1Result)
            stage3Result = runStage3Unified(uri, faceRoiJson)
            Log.d(TAG, "Stage 3 done: scene=${stage3Result.scene}, tags=${stage3Result.tags}")
        } finally {
            faceBitmap.recycle()
        }

        // ── 组装最终结果 ───────────────────────────────────
        val faceIds = stage2Result?.personIds ?: emptyList()
        val unified = UnifiedTagResult(
            face = FaceTagInfo(
                count = stage1Result.faceCount,
                selfie = stage1Result.isSelfie,
                groupPhoto = stage1Result.isGroupPhoto,
                personIds = faceIds
            ),
            scene = stage3Result.scene,
            activity = stage3Result.activity,
            objects = stage3Result.objects,
            tags = stage3Result.tags,
            summary = stage3Result.summary
        )

        val resultJson = toJsonString(unified)
        Log.d(TAG, "=== Pipeline done: $resultJson ===")
        return resultJson
    }

    // ═══════════════════════════════════════════════════
    //  [Pass 1] 人脸检测 + Embedding 提取（供 3-Pass 混合扫描用）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 1] 单张照片的人脸检测 + Glint360K R100 Embedding 提取 + MobileCLIP 语义编码
     *
     * 结果持久化（faceRoiJson 字段）供 Pass 3 构造人脸上下文。
     * Embedding 由调度器写入 face_embeddings 表供 Pass 2 DBSCAN。
     * MobileCLIP 语义编码**不依赖是否检测到人脸**，无人脸的照片同样需要语义 embedding
     * 以支持自然语言搜索。
     *
     * @param uri 照片 Content URI
     * @param lensFacing 镜头方向
     * @param mediaId 媒体 ID
     * @return 包含 faceRoi JSON、每张人脸的 embedding 列表和 MobileCLIP 语义 embedding
     */
    suspend fun stage1WithEmbeddings(
        uri: String,
        lensFacing: Int,
        mediaId: Long
    ): Stage1WithEmbeddingsResult {
        val faceBitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE)
        if (faceBitmap == null) {
            Log.w(TAG, "[Pass 1] Failed to load bitmap for mediaId=$mediaId")
            return Stage1WithEmbeddingsResult(null, emptyList())
        }

        try {
            val stage1Result = stage1FaceDetection(faceBitmap, lensFacing)
            Log.d(TAG, "[Pass 1] Stage 1 done: hasFace=${stage1Result.hasFace}, count=${stage1Result.faceCount}")

            val faceRoiJson = faceRoiToJson(stage1Result)

            // 提取每张人脸的 512 维 embedding，过滤零向量
            val embeddings = if (stage1Result.hasFace) {
                mutableListOf<FloatArray>().also { list ->
                    for (face in stage1Result.faces) {
                        val feature = faceClusterEngine.extractFeature(
                            faceBitmap, face.roi, face.landmarks5, mediaId
                        )
                        if (!isZeroVector(feature)) {
                            list.add(feature)
                        } else {
                            Log.w(TAG, "[Pass 1] Zero vector embedding skipped for mediaId=$mediaId, roi=${face.roi}")
                        }
                    }
                }
            } else {
                emptyList()
            }

            // 复用同一张 faceBitmap 做 MobileCLIP 语义编码，避免二次解码图片。
            // 无论是否检测到人脸，都需要语义 embedding 以支持自然语言搜索。
            val semanticEmbedding = stage4MobileClipEncoding(
                uri = uri,
                mediaId = mediaId,
                reuseBitmap = faceBitmap
            )

            Log.d(TAG, "[Pass 1] Extracted ${embeddings.size} valid embeddings for mediaId=$mediaId, " +
                "semanticEmbedding=${if (semanticEmbedding != null) "ok" else "null"}")
            val faceFocusY = computeFaceFocusY(stage1Result.faces, faceBitmap.height)
            return Stage1WithEmbeddingsResult(faceRoiJson, embeddings, semanticEmbedding, faceFocusY, stage1Result.hasFace)
        } finally {
            faceBitmap.recycle()
        }
    }

    /**
     * 仅做人脸纵向聚焦点检测（轻量，不提取 embedding / 不做 MobileCLIP）。
     * 供老照片一次性回填（MediaEntity.faceFocusY）使用。null=解码失败或无人脸。
     */
    suspend fun detectFaceFocusY(uri: String): Float? {
        val bitmap = loadBitmap(uri, MAX_FACE_DETECT_SIZE) ?: return null
        try {
            val stage1 = stage1FaceDetection(bitmap, androidx.camera.core.CameraSelector.LENS_FACING_BACK)
            return computeFaceFocusY(stage1.faces, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    // ═══════════════════════════════════════════════════
    //  [Pass 3] 图像打标（可断点续扫）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 3] 图像打标（MNN VLM 分支）
     *
     * 使用 Pass 1 持久化的 faceRoiJson 恢复人脸上下文。
     * 不依赖传递性 Stage1Result 对象，天然支持断点续扫。
     *
     * @param uri 照片 Content URI
     * @param faceRoiJson Pass 1 持久化的人脸 ROI JSON（null=解码失败）
     * @return 规范化后的标签结果
     */
    suspend fun stage3QwenTagging(
        uri: String,
        faceRoiJson: String?
    ): QwenTagsNormalized {
        val bitmap = loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "[Pass 3] Failed to load bitmap, returning empty tags")
            return QwenTagsNormalized("", "", emptyList(), emptyList(), "")
        }

        return try {
            stage3QwenTagging(bitmap, faceRoiJson)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [Pass 3] 图像打标（MNN VLM 分支，Bitmap 重载）。
     *
     * 供 [processPhoto] 复用已加载/已旋转的 Bitmap，避免二次 ContentResolver 解码。
     */
    suspend fun stage3QwenTagging(
        bitmap: Bitmap,
        faceRoiJson: String?
    ): QwenTagsNormalized {
        val combined = runStage3Combined(bitmap, faceRoiJson)
        return QwenTags(
            scene = combined.scene,
            activity = combined.activity,
            objects = combined.objects,
            tags = combined.tags,
            summary = combined.summary
        ).let { normalizer.normalize(it) }
    }

    /**
     * Stage-3 统一分流：按 scheduler 解析的 taggerModelKey 决定用 Florence-2（ORT）
     * 还是 Qwen3-VL（MNN）打标。entry 1（批量 [TagGenerationScheduler.executeImageTagging]）
     * 与 entry 3（retag [processPhoto]）共用，保证「单张/批量同模型同提示词同过程」。
     *
     * 内部按所选模型解码合适尺寸 bitmap（Florence-2 → 768，Qwen → 512）。
     *
     * @param uri 照片 Content URI
     * @param faceRoiJson Pass 1 持久化的人脸上下文（Qwen 提示词提示用；可为 null）
     * @return Stage-3 产物；face 字段留空，由调用方按各自人脸上下文填充。
     *         模型不可用 / 解码失败 / 推理空 → 返回空 [UnifiedTagResult]。
     */
    suspend fun runStage3Unified(uri: String, faceRoiJson: String?): UnifiedTagResult {
        val modelKey = taggerModelKeyProvider()
        return if (modelKey == TaggerModelSelector.defaultKey) {
            // Florence-2 ORT 路径
            val tagger = florence2TaggerProvider()
            if (tagger == null || !tagger.isInit) {
                Log.w(TAG, "[Stage3] Florence-2 unavailable, returning empty")
                return UnifiedTagResult()
            }
            val bitmap = loadBitmap(uri, Florence2Tagger.IMAGE_SIZE) ?: return UnifiedTagResult()
            try {
                tagger.tag(bitmap)
            } finally {
                bitmap.recycle()
            }
        } else {
            // Qwen3-VL-2B 路径：复用既有 Stage-3（含 faceRoi 上下文提示 + normalize）
            val qwen = stage3QwenTagging(uri, faceRoiJson)
            UnifiedTagResult(
                scene = qwen.scene,
                activity = qwen.activity,
                objects = qwen.objects,
                tags = qwen.tags,
                summary = qwen.summary
            )
        }
    }

    /**
     * Stage 3 合并结果：MobileCLIP 分类 + Qwen activity/summary
     */
    private data class Stage3CombinedResult(
        val scene: String = "",
        val activity: String = "",
        val objects: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val summary: String = "",
        val fromMobileClip: Boolean = false
    )

    private suspend fun runStage3Combined(bitmap: Bitmap, faceRoiJson: String?): Stage3CombinedResult {
        if (!llmEngine.isLoaded) {
            Log.w(TAG, "[Pass 3] LLM not loaded, skipping image tagging")
        }

        // 从 JSON 恢复人脸上下文
        val faceRoi = faceRoiJson?.let { parseFaceRoi(it) }
        val faceCount = if (faceRoi?.hasFace == true) faceRoi.faceCount else 0
        val isGroupPhoto = faceRoi?.isGroupPhoto ?: false

        // MobileCLIP 不参与打标：实测 MobileClipTagClassifier qualityOk=false（全乱标），
        // 且每张白跑一次 classify 浪费算力。Pass3 直接 SmolVLM 全量输出。
        // MobileCLIP 语义向量（Pass1 semanticEmbedding）保留，仅供语义搜索。
        val qwenTags = runQwenFull(bitmap, faceCount, isGroupPhoto)
        return if (qwenTags != null) {
            Stage3CombinedResult(
                scene = qwenTags.scene,
                activity = qwenTags.activity,
                objects = qwenTags.objects,
                tags = qwenTags.tags,
                summary = qwenTags.summary,
                fromMobileClip = false
            )
        } else {
            Stage3CombinedResult()
        }
    }

    private suspend fun runQwenFull(
        bitmap: Bitmap,
        faceCount: Int,
        isGroupPhoto: Boolean
    ): QwenTags? {
        if (!llmEngine.isLoaded) return null

        val userPrompt = promptProvider.userPrompt(targetLanguage, faceCount, isGroupPhoto)
        val response = runVisionInference(bitmap, stage3SystemPrompt, userPrompt)

        if (response.isBlank()) return null
        val jsonPart = extractJson(response) ?: return null
        return parseQwenResponse(jsonPart)
    }

    /**
     * 释放 MobileCLIP 引擎资源
     */
    fun releaseMobileClip() {
        mobileClipEngine?.release()
    }

    /**
     * 级联释放 pipeline 持有的引擎 native 资源（人脸检测器 + MobileCLIP）。
     *
     * faceClusterEngine 由 TagGenerationScheduler 持有并单独释放，不在此列。
     * 仅供 Service onDestroy 级联调用，调用后本实例不可再用。
     */
    fun release() {
        faceDetector.release()
        releaseMobileClip()
    }

    /**
     * 预热 MobileCLIP 标签分类器
     *
     * 在 Pass 3 开始前调用，预计算候选标签文本 embedding。
     *
     * @return 是否成功。失败时 Stage 3 会回退到 Qwen 全量输出。
     */
    fun warmUpMobileClipClassifier(): Boolean {
        return mobileClipTagClassifier?.warmUp() ?: false
    }

    // ═══════════════════════════════════════════════════
    //  MobileCLIP 语义编码（已内联合并到 Pass 1，保留方法用于单独重编码）
    // ═══════════════════════════════════════════════════

    /**
     * MobileCLIP 语义编码
     *
     * 使用 MobileCLIP-S2 生成 512 维 L2 归一化图像 embedding，
     * 存储为 Base64 字符串供语义搜索使用。
     *
     * 说明：常规扫描已将该阶段内联合并到 Pass 1。此方法保留用于：
     * - Pass 1 内联调用
     * - 单独对某张或某批媒体重新生成语义编码
     *
     * 已优化：支持复用已加载的 Bitmap，避免重复解码。
     *
     * @param uri 照片 Content URI
     * @param mediaId 媒体 ID
     * @param reuseBitmap 复用的 Bitmap（如 Pass 1 已加载的 640px Bitmap），null 则重新加载
     * @return Base64 编码的 embedding 字符串，失败返回 null
     */
    suspend fun stage4MobileClipEncoding(
        uri: String,
        mediaId: Long,
        reuseBitmap: Bitmap? = null
    ): String? {
        val engine = mobileClipEngine ?: run {
            Log.w(TAG, "[MobileCLIP] MobileClipEngine not available")
            return null
        }

        if (!engine.isInitialized) {
            Log.w(TAG, "[MobileCLIP] MobileClipEngine not initialized, attempting init")
            if (!engine.initializeWithFallback()) {
                Log.w(TAG, "[MobileCLIP] Failed to initialize MobileClipEngine")
                return null
            }
        }

        val bitmap = reuseBitmap ?: loadBitmap(uri, MAX_VISION_SIZE)
        if (bitmap == null) {
            Log.w(TAG, "[MobileCLIP] Failed to load bitmap for mediaId=$mediaId")
            return null
        }

        return try {
            val embedding = engine.encodeImage(bitmap)
            if (embedding == null) {
                Log.w(TAG, "[MobileCLIP] encodeImage returned null for mediaId=$mediaId")
                return null
            }

            // 二次校验：确保入库前 embedding 是有效且已归一化的
            if (!isValidEmbedding(embedding)) {
                Log.w(TAG, "[MobileCLIP] Invalid embedding rejected for mediaId=$mediaId")
                return null
            }

            // 编码为 Base64 字符串存储
            val base64 = floatArrayToBase64(embedding)
            Log.d(TAG, "[MobileCLIP] Encoded embedding for mediaId=$mediaId, dim=${embedding.size}, base64_len=${base64.length}")
            base64
        } finally {
            // 仅当 bitmap 是自己加载的才回收，外部传入的不负责回收
            if (reuseBitmap == null) {
                bitmap.recycle()
            }
        }
    }

    /**
     * 校验 embedding 是否可用于入库。
     *
     * 注意：MobileClipEngine 已在返回前做强制 L2 归一化，这里做最终守门检查。
     */
    private fun isValidEmbedding(embedding: FloatArray): Boolean {
        if (embedding.size != 512) return false
        var norm = 0f
        for (v in embedding) {
            if (v.isNaN() || v.isInfinite()) return false
            norm += v * v
        }
        // L2 归一化后 norm 应接近 1.0；允许小误差，拒绝零向量
        return norm > 0.8f
    }

    /**
     * 将 FloatArray 编码为 Base64 字符串
     */
    private fun floatArrayToBase64(array: FloatArray): String {
        val bytes = ByteArray(array.size * 4)
        for (i in array.indices) {
            val bits = java.lang.Float.floatToRawIntBits(array[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    // ═══════════════════════════════════════════════════
    //  JSON 序列化/反序列化辅助
    // ═══════════════════════════════════════════════════

    /**
     * 将 Stage 1 结果序列化为 JSON（用于 DB 持久化）
     *
     * 始终返回非 null JSON，以便 caller 能区分"已处理但无人脸"和"尚未处理"。
     * hasFace 字段仍由 [hasValidFace] 控制，不会误标为 true。
     */
    private fun faceRoiToJson(result: Stage1Result): String {
        return """{"hasFace":${result.hasFace},"faceCount":${result.faceCount},"isSelfie":${result.isSelfie},"isGroupPhoto":${result.isGroupPhoto}}"""
    }

    /** 判断 embedding 是否为无效的零向量、nan 或 inf */
    private fun isZeroVector(embedding: FloatArray): Boolean {
        return embedding.all { it == 0f || it.isNaN() || it.isInfinite() }
    }

    /** 从 JSON 恢复人脸上下文 */
    private fun parseFaceRoi(json: String): FaceRoiPersist? {
        return try {
            val obj = JSONObject(json)
            FaceRoiPersist(
                hasFace = obj.optBoolean("hasFace", false),
                faceCount = obj.optInt("faceCount", 0),
                isSelfie = obj.optBoolean("isSelfie", false),
                isGroupPhoto = obj.optBoolean("isGroupPhoto", false)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse faceRoi JSON: ${e.message}")
            null
        }
    }

    // ═══════════════════════════════════════════════════
    //  Stage 1: 人脸 ROI 检测 + 2D106 关键点对齐
    // ═══════════════════════════════════════════════════

    /**
     * [方案 B] 人脸检测 — 获取 ROI + 2D106 重新计算 5 点 landmarks
     *
     * 1. 用 RetinaFace 获取所有人脸 ROI（含其自带的 5 点，作为 fallback）。
     * 2. 对每个 ROI 调用 2D106 关键点检测，得到 106 点统一关键点。
     * 3. 从 106 点中提取更稳定的 5 点（双眼中心、鼻尖、嘴角），
     *    供 Stage 2 的 ArcFace/Glint360K R100 进行仿射对齐。
     *
     * 关键点索引基于统一 106 标准（画面视角）：
     * - 左眼（画面右侧）: 58-63 外轮廓 + 75-76 内眼角
     * - 右眼（画面左侧）: 52-57 外轮廓 + 72-73 内眼角
     * - 鼻尖中心: 49
     * - 左嘴角（画面右侧）: 94
     * - 右嘴角（画面左侧）: 84
     */
    /**
     * 对外人脸检测 + 5 点 landmarks（复用扫描级检测管线，供美学/人脸画质打分等使用）。
     * @return 人脸列表（每人脸含 roi 与 landmarks5；landmarks5 个别情况可能为 null）
     */
    fun detectFacesWithLandmarks5(bitmap: Bitmap): List<FaceRoi> =
        stage1FaceDetection(bitmap, androidx.camera.core.CameraSelector.LENS_FACING_BACK).faces

    private fun stage1FaceDetection(bitmap: Bitmap, lensFacing: Int): Stage1Result {
        val detections = faceDetector.detectFacesWithLandmarks(bitmap)

        if (detections.isEmpty()) {
            return Stage1Result(false)
        }

        val faces = detections.map { detection ->
            // 优先用 2D106 重新计算 5 点；失败时回退到 RetinaFace 5 点
            val landmarks5 = detectLandmarks5From106(bitmap, lensFacing, detection.roi)
                ?: detection.landmarks5
            FaceRoi(detection.roi, landmarks5)
        }

        val fallbackCount = faces.count { it.landmarks5 == null }
        if (fallbackCount > 0) {
            Log.w(TAG, "[PlanB] $fallbackCount/${faces.size} faces fallback to RetinaFace 5-pt or no landmarks")
        }

        return Stage1Result(
            hasFace = true,
            faceCount = faces.size,
            faces = faces
        )
    }

    /**
     * 对单个 ROI 运行 2D106 关键点检测，并转换为 ArcFace 5 点像素坐标。
     *
     * @return 长度 10 的 FloatArray（5 点 x,y 像素坐标），失败返回 null
     */
    private fun detectLandmarks5From106(
        bitmap: Bitmap,
        lensFacing: Int,
        roi: RectF
    ): FloatArray? {
        val result: FaceDetectionResult = faceDetector.detectLandmarksForRoi(bitmap, lensFacing, roi)
            ?: return null

        val landmarks106 = result.landmarks106
        if (landmarks106.size < 212) {
            Log.w(TAG, "[PlanB] 106 landmarks too short: ${landmarks106.size}")
            return null
        }

        return convert106ToLandmarks5(landmarks106, bitmap.width, bitmap.height)
    }

    /**
     * 将统一 106 点归一化坐标转换为 ArcFace 5 点像素坐标。
     *
     * 输出顺序：[左眼，右眼，鼻尖，左嘴角，右嘴角]。
     *
     * **关键约定**：ArcFace 模板中的"左/右"以**画面**为参考（ aligned 图像左侧 = 画面左）。
     * 统一 106 点则以"被摄者真实面部"命名，因此存在交叉映射：
     * - 画面左眼（ArcFace 左眼）= 106 右眼区域（画面左侧）= 52-57 + 72-73
     * - 画面右眼（ArcFace 右眼）= 106 左眼区域（画面右侧）= 58-63 + 75-76
     * - 画面左嘴角（ArcFace 左嘴角）= 106 右嘴角（画面左侧）= 84
     * - 画面右嘴角（ArcFace 右嘴角）= 106 左嘴角（画面右侧）= 94
     */
    private fun convert106ToLandmarks5(
        landmarks106: FloatArray,
        bitmapWidth: Int,
        bitmapHeight: Int
    ): FloatArray {
        // ArcFace 左眼 = 画面左侧 = 106 右眼区域 (52-57 外轮廓 + 72-73 内眼角)
        val leftEyeX = averageX(landmarks106, intArrayOf(52, 53, 54, 55, 56, 57, 72, 73))
        val leftEyeY = averageY(landmarks106, intArrayOf(52, 53, 54, 55, 56, 57, 72, 73))

        // ArcFace 右眼 = 画面右侧 = 106 左眼区域 (58-63 外轮廓 + 75-76 内眼角)
        val rightEyeX = averageX(landmarks106, intArrayOf(58, 59, 60, 61, 62, 63, 75, 76))
        val rightEyeY = averageY(landmarks106, intArrayOf(58, 59, 60, 61, 62, 63, 75, 76))

        // 鼻尖中心: 49
        val noseX = landmarks106[49 * 2]
        val noseY = landmarks106[49 * 2 + 1]

        // ArcFace 左嘴角 = 画面左侧 = 106 右嘴角 (84)
        val leftMouthX = landmarks106[84 * 2]
        val leftMouthY = landmarks106[84 * 2 + 1]

        // ArcFace 右嘴角 = 画面右侧 = 106 左嘴角 (94)
        val rightMouthX = landmarks106[94 * 2]
        val rightMouthY = landmarks106[94 * 2 + 1]

        val landmarks5 = floatArrayOf(
            leftEyeX * bitmapWidth, leftEyeY * bitmapHeight,
            rightEyeX * bitmapWidth, rightEyeY * bitmapHeight,
            noseX * bitmapWidth, noseY * bitmapHeight,
            leftMouthX * bitmapWidth, leftMouthY * bitmapHeight,
            rightMouthX * bitmapWidth, rightMouthY * bitmapHeight
        )

        // 简单合理性校验：画面左侧点 x 应小于画面右侧点，眼睛应高于嘴角
        if (landmarks5[0] >= landmarks5[2]) {
            Log.w(TAG, "[PlanB] Left eye x (${landmarks5[0]}) >= right eye x (${landmarks5[2]}), alignment may be mirrored")
        }
        if (landmarks5[6] >= landmarks5[8]) {
            Log.w(TAG, "[PlanB] Left mouth x (${landmarks5[6]}) >= right mouth x (${landmarks5[8]}), alignment may be mirrored")
        }
        if (landmarks5[1] >= landmarks5[9]) {
            Log.w(TAG, "[PlanB] Eye y (${landmarks5[1]}) >= mouth y (${landmarks5[9]}), alignment may be flipped")
        }

        return landmarks5
    }

    private fun averageX(landmarks: FloatArray, indices: IntArray): Float {
        var sum = 0f
        for (i in indices) sum += landmarks[i * 2]
        return sum / indices.size
    }

    private fun averageY(landmarks: FloatArray, indices: IntArray): Float {
        var sum = 0f
        for (i in indices) sum += landmarks[i * 2 + 1]
        return sum / indices.size
    }

    // ═══════════════════════════════════════════════════
    //  Stage 2: Glint360K R100 特征提取 → 人脸聚类
    // ═══════════════════════════════════════════════════

    private suspend fun stage2FaceCluster(
        bitmap: Bitmap,
        mediaId: Long,
        stage1Result: Stage1Result
    ): Stage2Result? {
        val embeddings = mutableListOf<FaceEmbeddingOutput>()

        for (face in stage1Result.faces) {
            val feature = faceClusterEngine.extractFeature(
                bitmap, face.roi, face.landmarks5, mediaId
            )

            // 过滤零向量，避免误聚类
            if (isZeroVector(feature)) {
                Log.w(TAG, "[Stage 2] Zero vector embedding skipped for mediaId=$mediaId, roi=${face.roi}")
                continue
            }

            val matchedPersonId = faceClusterEngine.matchCluster(feature)

            val personId: Long = if (matchedPersonId != null) {
                faceClusterEngine.addToCluster(matchedPersonId, feature, mediaId)
                matchedPersonId
            } else {
                faceClusterEngine.createCluster(feature, mediaId)
            }

            embeddings.add(FaceEmbeddingOutput(mediaId, feature, personId))
        }

        return Stage2Result(
            faceEmbeddings = embeddings,
            personIds = embeddings.mapNotNull { it.personId }
        )
    }

    // ═══════════════════════════════════════════════════
    //  Stage 3: 多模态图像打标（VLM）
    // ═══════════════════════════════════════════════════

    private suspend fun stage3QwenTagging(
        uri: String,
        stage1Result: Stage1Result,
        stage2Result: Stage2Result?
    ): QwenTagsNormalized {
        val faceRoiJson = faceRoiToJson(stage1Result)
        return stage3QwenTagging(uri, faceRoiJson)
    }

    private fun parseQwenResponse(jsonStr: String): QwenTags? {
        return try {
            val obj = JSONObject(jsonStr)
            QwenTags(
                scene = obj.optString("scene", ""),
                activity = obj.optString("activity", ""),
                objects = obj.optJSONArray("objects")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                tags = obj.optJSONArray("tags")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                summary = obj.optString("summary", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Stage 3: failed to parse JSON: ${e.message}")
            null
        }
    }

    private fun toJsonString(result: UnifiedTagResult): String {
        val obj = JSONObject()
        val face = JSONObject()
        face.put("count", result.face.count)
        face.put("selfie", result.face.selfie)
        face.put("groupPhoto", result.face.groupPhoto)
        face.put("personIds", JSONArray(result.face.personIds))
        obj.put("face", face)
        obj.put("scene", result.scene)
        obj.put("activity", result.activity)
        obj.put("objects", JSONArray(result.objects))
        obj.put("tags", JSONArray(result.tags))
        obj.put("summary", result.summary)
        return obj.toString()
    }

    /**
     * 从 LLM 返回中提取 JSON 对象
     *
     * prompt 工程要求模型把 JSON 放在回答末尾，因此优先从最后一个 `{` 开始匹配；
     * 若解析失败则回退到第一个 `{` ... 最后一个 `}` 的兜底策略。
     */
    private fun extractJson(text: String): String? {
        // 策略 1：JSON 在末尾 → 取最后一个 `{` 到其后的最后一个 `}`
        val lastStart = text.lastIndexOf('{')
        if (lastStart != -1) {
            val endAfterLastStart = text.lastIndexOf('}')
            if (endAfterLastStart > lastStart) {
                val candidate = text.substring(lastStart, endAfterLastStart + 1)
                if (isValidJsonObject(candidate)) return candidate
            }
        }

        // 策略 2：兜底，取第一个 `{` 到全文最后一个 `}`
        val firstStart = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (firstStart != -1 && end > firstStart) {
            val candidate = text.substring(firstStart, end + 1)
            if (isValidJsonObject(candidate)) candidate else null
        } else null
    }

    private fun isValidJsonObject(text: String): Boolean {
        return try {
            org.json.JSONObject(text)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Invalid JSON object: ${e.message}")
            false
        }
    }

    /**
     * 带 OpenCL 守护的多模态推理
     *
     * @param systemPrompt 本次推理使用的 system prompt
     * - 若 [openClGuardian] 存在，则使用其超时保护与自动降级逻辑
     * - 若 OpenCL 路径返回 Timeout，自动降级到 CPU 并立即重试一次
     * - 若不存在 Guardian，回退到原始 llmEngine.imageInference
     */
    private suspend fun runVisionInference(
        bitmap: Bitmap,
        systemPrompt: String,
        userPrompt: String
    ): String {
        return if (openClGuardian != null) {
            when (val result = openClGuardian.inference(
                bitmap = bitmap,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                maxTokens = QWEN_MAX_TOKENS
            )) {
                is OpenClInferenceResult.Success -> result.response
                is OpenClInferenceResult.Timeout -> {
                    Log.w(TAG, "OpenCL timeout, retrying with CPU fallback")
                    llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
                }
                is OpenClInferenceResult.Error -> {
                    Log.w(TAG, "OpenCL error: ${result.message}, falling back to CPU")
                    llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
                }
            }
        } else {
            llmEngine.imageInference(bitmap, systemPrompt, userPrompt, maxTokens = QWEN_MAX_TOKENS)
        }
    }

    /**
     * 从 Content URI 加载 Bitmap，缩放到指定最长边，并校正 EXIF 方向。
     *
     * 内存安全（修复 OOM 闪退）：不再用 readBytes() 把整个文件一次性读入 byte[]——否则遇到
     * 大文件（实测 187MB 屏录视频）会直接撑爆 Java Heap。改为：
     * 1. 先按 MIME 拦截非图片（视频/音频等）——图像阶段对它们本就无意义，且避免任何字节读取；
     * 2. 流式两遍 decodeStream（bounds + 真正解码），内存占用仅与解码后 Bitmap 尺寸成正比，
     *    不再与文件大小成正比。代价是开两次 ContentResolver 流，其开销远小于物化整个文件。
     *
     * inSampleSize 会被 BitmapFactory 向下取整到 2 的幂次，因此实际尺寸可能略大于 maxSize。
     * 注意：返回的 Bitmap 需要调用方负责回收。
     */
    /**
     * [公开] 加载 Bitmap（Florence-2 打标等外部调用者用）。
     *
     * maxSize 默认对齐 Florence-2 输入尺寸（[Florence2Tagger.IMAGE_SIZE]=768，当前默认打标器）。
     * Qwen/MobileCLIP 等内部路径走 [loadBitmap] 显式传 [MAX_VISION_SIZE]，不受影响。
     */
    fun loadBitmapPublic(uri: String, maxSize: Int = Florence2Tagger.IMAGE_SIZE): Bitmap? = loadBitmap(uri, maxSize)

    private fun loadBitmap(uri: String, maxSize: Int): Bitmap? {
        val contentUri = Uri.parse(uri)

        // 1. MIME 拦截：非图片（视频/音频等）直接跳过，避免对大视频 readBytes 导致 OOM。
        //    getType 命中 MediaProvider 缓存且不打开流，开销极小。
        //    mime 为 null 时不拦截（个别 URI 无 mime 信息），交给下方流式解码兜底。
        val mime = try {
            context.contentResolver.getType(contentUri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query mime type for $uri: ${e.message}")
            null
        }
        if (mime != null && !isImageMimeType(mime)) {
            Log.d(TAG, "Skip non-image media (mime=$mime) at $uri")
            return null
        }

        return try {
            // 2. 流式两遍解码：先读 bounds 计算 inSampleSize，再真正解码
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(contentUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val scale = if (maxOf(bounds.outWidth, bounds.outHeight) > maxSize) {
                maxOf(bounds.outWidth, bounds.outHeight) / maxSize
            } else 1

            // inSampleSize 必须是 2 的幂次
            val sampleSize = Integer.highestOneBit(scale).coerceAtLeast(1)

            val decoded = context.contentResolver.openInputStream(contentUri)?.use { stream ->
                BitmapFactory.decodeStream(
                    stream,
                    null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
            } ?: return null

            // 校正 EXIF 方向，避免竖拍/旋转照片编码错误
            applyExifRotation(contentUri, decoded)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load bitmap from $uri: ${e.message}")
            null
        }
    }

    /**
     * 根据 EXIF 方向标签旋转 Bitmap。
     *
     * 使用 LruCache 缓存同一 URI 的旋转角度，避免 Pass 1 与 Pass 3 分离执行时重复读取 EXIF。
     *
     * @return 旋转后的新 Bitmap；无需旋转时返回原 Bitmap
     */
    private fun applyExifRotation(uri: Uri, bitmap: Bitmap): Bitmap {
        val uriString = uri.toString()
        val cached = exifRotationCache.get(uriString)
        val rotationDegrees = if (cached != null) {
            cached
        } else {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    ExifInterface(stream).rotationDegrees.also {
                        exifRotationCache.put(uriString, it)
                    }
                } ?: 0
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read EXIF orientation: ${e.message}")
                0
            }
        }

        if (rotationDegrees == 0) return bitmap

        return try {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to rotate bitmap: ${e.message}")
            bitmap
        }
    }
}
