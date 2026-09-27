package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.indexing.MnnEmbeddingExtractor
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonEntity
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 人脸聚类引擎
 *
 * 负责 Glint360K R100 特征提取（Stage 2a/2b）和增量化余弦距离聚类（Stage 2c）。
 *
 * ## 实现状态 (2026-07-05)
 * - **Glint360K R100 特征提取**：已集成 [MnnEmbeddingExtractor]，
 *   使用 MNN 加载 glintr100.mnn 模型提取 512 维 embedding。
 *   模型缺失时降级为零向量（聚类不生效）。
 * - **聚类算法**：增量式余弦距离匹配已实现。
 *
 * @param context Android Context（用于 Room 数据库访问和模型目录）
 */
@Suppress("TooManyFunctions") // 待重构：聚类引擎，按职责拆分
class FaceClusterEngine(private val context: Context) {

    companion object {
        private const val TAG = "FaceClusterEngine"

        /** 人脸 embedding 标准输入尺寸（112×112，与 InsightFace/ArcFace 系列对齐一致） */
        const val FACE_INPUT_SIZE = 112

        /** 特征向量维度 */
        const val EMBEDDING_DIM = 512

        /** 未分配人脸的 personId 标记 */
        const val UNASSIGNED_ID: Long = -1

        /** 调试：最多保存多少张对齐后人脸图 */
        private const val MAX_DEBUG_FACE_SAVES = 30

        /** mergeSmallClusters：凝聚式合并次数上限（自然终止，此为兜底防病态） */
        private const val MAX_MERGE_ITERATIONS = 200

        /** splitOvermergedClusters：拆分次数上限（自然终止，此为兜底） */
        private const val MAX_SPLIT_ITERATIONS = 200

        @Volatile
        private var debugFaceSaveCount = 0
    }

    private val personDao = AppDatabase.getDatabase(context).personDao()

    /**
     * 人物质心缓存：personId -> (centroid, embeddingCount)。
     * 避免 matchCluster() 每次把某个人物的全部 embedding 从 DB 读出再重新计算质心。
     * 所有通过本类修改簇的操作（createCluster / addToCluster / mergeClusters）都会同步更新缓存。
     */
    private val centroidCache = mutableMapOf<Long, Pair<FloatArray, Int>>()

    /** 人脸 embedding 提取器初始化失败后，距上次失败超过该间隔才允许重试（避免每张照片都重试加载 260MB 模型） */
    private val extractorRetryCooldownMs = 60_000L

    /**
     * Glint360K R100 嵌入提取器（首用时初始化，线程安全）。
     *
     * 失败**不永久缓存**：模型文件 mtime 变化（用户在模型中心重新下载）或冷却期过后
     * 自动重试，避免「重下模型后必须杀进程才生效」（lazy 缓存失败的教训）。
     */
    @Volatile
    private var embeddingExtractorInstance: MnnEmbeddingExtractor? = null

    @Volatile
    private var initFailedAtMs: Long = 0L

    @Volatile
    private var initFailedModelMtime: Long = -1L

    private val extractorInitLock = Any()

    private val embeddingExtractor: MnnEmbeddingExtractor?
        get() = getOrInitExtractor()

    private fun getOrInitExtractor(): MnnEmbeddingExtractor? {
        embeddingExtractorInstance?.let { return it }
        synchronized(extractorInitLock) {
            embeddingExtractorInstance?.let { return it }

            val modelDir = ModelPathConfig.getModelDir(context, "face-embedding-glint360k-r100-mnn")
            val modelFile = File(modelDir, "glintr100.mnn")

            if (initFailedAtMs > 0L) {
                val fileChanged = modelFile.lastModified() != initFailedModelMtime
                val cooldownElapsed = System.currentTimeMillis() - initFailedAtMs >= extractorRetryCooldownMs
                if (!fileChanged && !cooldownElapsed) return null
            }

            // Glint360K R100 MNN 输入/输出名：input.1 / 1333；优先尝试 OpenCL GPU，失败回退 CPU
            val extractor = MnnEmbeddingExtractor(modelFile)
            val initialized = when {
                !extractor.isModelReady -> {
                    Log.w(TAG, "Glint360K R100 model NOT found at ${modelFile.absolutePath}, face clustering will NOT work. Download glintr100.mnn to enable.")
                    false
                }
                extractor.initialize(
                    inputName = "input.1",
                    outputName = "1333",
                    useGpu = true,
                    swapRb = false
                ) -> {
                    Log.i(TAG, "Glint360K R100 model loaded: ${modelFile.absolutePath}")
                    true
                }
                else -> {
                    // 文件存在但 MNN 解释器创建失败：典型原因是文件损坏（如下载截断，
                    // 大小可能与官方一致但 SHA256 不符），日志须明确指向重下而非「未下载」
                    Log.w(TAG, "Glint360K R100 model file exists but FAILED to load (likely corrupted): ${modelFile.absolutePath}. Re-download the model in Model Center to recover.")
                    false
                }
            }

            return if (initialized) {
                initFailedAtMs = 0L
                extractor.also { embeddingExtractorInstance = it }
            } else {
                initFailedAtMs = System.currentTimeMillis()
                initFailedModelMtime = modelFile.lastModified()
                null
            }
        }
    }

    /**
     * 释放 Glint360K R100 嵌入模型 native 资源。
     *
     * 仅供 Service onDestroy 级联调用；模型未初始化（本轮未做聚类）则跳过，不为释放而触发模型加载。
     */
    fun release() {
        synchronized(extractorInitLock) {
            embeddingExtractorInstance?.close()
            embeddingExtractorInstance = null
        }
    }

    /** 嵌入提取器是否可用 */
    val isEmbeddingAvailable: Boolean
        get() = embeddingExtractor != null

    /**
     * 提取人脸特征向量
     *
     * 优先使用 5 点 landmarks 做仿射对齐后再输入 embedding 模型；
     * 无 landmarks 时回退到 ROI 裁剪+缩放。
     *
     * @param bitmap 原始图片
     * @param roi 人脸 ROI 区域（像素坐标）
     * @param landmarks5 RetinaFace 5 点原图像素坐标（长度 10），null 则回退旧路径
     * @param mediaId 媒体文件 ID（仅用于调试文件名）
     * @return 512 维特征向量（L2 归一化后的真实 embedding，或零向量）
     */
    suspend fun extractFeature(
        bitmap: Bitmap,
        roi: RectF,
        landmarks5: FloatArray? = null,
        mediaId: Long = -1
    ): FloatArray {
        val extractor = embeddingExtractor
        if (extractor == null) {
            Log.d(TAG, "extractFeature: no model, returning zero vector. roi=$roi")
            return FloatArray(EMBEDDING_DIM) { 0f }
        }

        return try {
            val faceBitmap = if (landmarks5 != null && landmarks5.size >= 10) {
                // 5 点仿射对齐路径
                alignFaceWithLandmarks(bitmap, landmarks5, mediaId)
            } else {
                // 回退：ROI 裁剪 + 直接缩放
                cropAndScaleFace(bitmap, roi)
            }

            // [调试] 保存前 MAX_DEBUG_FACE_SAVES 张对齐后人脸图，供人工检查对齐质量
            if (debugFaceSaveCount < MAX_DEBUG_FACE_SAVES) {
                saveDebugFace(faceBitmap, mediaId, "aligned")
            }

            // MNN 推理提取 embedding
            val embedding = extractor.extractEmbedding(faceBitmap)
            faceBitmap.recycle()

            if (embedding != null) {
                val norm = sqrt(embedding.map { it * it }.sum().toDouble())
                val valid = embedding.all { !it.isNaN() && !it.isInfinite() } && norm > 0
                if (valid) {
                    Log.d(TAG, "extractFeature: extracted ${embedding.size}-dim embedding, norm=$norm")
                    embedding
                } else {
                    Log.w(TAG, "extractFeature: invalid embedding (nan/inf or zero norm), falling back to zero vector")
                    FloatArray(EMBEDDING_DIM) { 0f }
                }
            } else {
                Log.w(TAG, "extractFeature: MNN inference returned null, falling back to zero vector")
                FloatArray(EMBEDDING_DIM) { 0f }
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractFeature: failed with exception, falling back to zero vector", e)
            FloatArray(EMBEDDING_DIM) { 0f }
        }
    }

    /**
     * 使用 5 点 landmarks 做最小二乘仿射对齐，输出 112×112 人脸图
     *
     * 5 点顺序：[左眼，右眼，鼻尖，左嘴角，右嘴角]
     * 目标模板为 InsightFace 标准 112×112 对齐坐标。
     */
    private fun alignFaceWithLandmarks(bitmap: Bitmap, landmarks5: FloatArray, mediaId: Long = -1): Bitmap {
        // InsightFace 标准 112×112 对齐目标点
        val dstPoints = floatArrayOf(
            38.2946f, 51.6963f,   // 左眼
            73.5318f, 51.5014f,   // 右眼
            56.0252f, 71.7366f,   // 鼻尖
            41.5493f, 92.3655f,   // 左嘴角
            70.7299f, 92.2041f    // 右嘴角
        )

        val transform = computeAffineTransform(landmarks5, dstPoints)

        val aligned = Bitmap.createBitmap(FACE_INPUT_SIZE, FACE_INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(aligned)
        // 黑色背景填充，防止 warp 区域外为透明
        canvas.drawColor(android.graphics.Color.BLACK)
        canvas.drawBitmap(bitmap, transform, null)
        return aligned
    }

    /**
     * 保存调试人脸图到外部缓存目录
     */
    private fun saveDebugFace(bitmap: Bitmap, mediaId: Long, suffix: String) {
        try {
            val dir = File(context.externalCacheDir, "debug_faces").apply { mkdirs() }
            val file = File(dir, "face_${mediaId}_${System.currentTimeMillis()}_$suffix.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            debugFaceSaveCount++
            Log.d(TAG, "Saved debug face: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save debug face", e)
        }
    }

    /**
     * 最小二乘求解 5 点 → 5 点的仿射变换矩阵
     *
     * 仿射模型：u = a*x + b*y + c, v = d*x + e*y + f
     * 分别对 u/v 用正规方程求解。
     */
    private fun computeAffineTransform(src: FloatArray, dst: FloatArray): Matrix {
        val n = src.size / 2

        var sx = 0f; var sy = 0f
        var sxx = 0f; var syy = 0f; var sxy = 0f
        var su = 0f; var sv = 0f
        var sxu = 0f; var syu = 0f
        var sxv = 0f; var syv = 0f

        for (i in 0 until n) {
            val x = src[i * 2]
            val y = src[i * 2 + 1]
            val u = dst[i * 2]
            val v = dst[i * 2 + 1]

            sx += x; sy += y
            sxx += x * x; syy += y * y; sxy += x * y
            su += u; sv += v
            sxu += x * u; syu += y * u
            sxv += x * v; syv += y * v
        }

        // 正规矩阵 M = | sxx sxy sx |
        //              | sxy syy sy |
        //              | sx  sy  n  |
        val m00 = sxx; val m01 = sxy; val m02 = sx
        val m10 = sxy; val m11 = syy; val m12 = sy
        val m20 = sx;  val m21 = sy;  val m22 = n.toFloat()

        val det = m00 * (m11 * m22 - m12 * m21) -
            m01 * (m10 * m22 - m12 * m20) +
            m02 * (m10 * m21 - m11 * m20)

        // 退化时回退到单位变换
        if (det == 0f) {
            Log.w(TAG, "alignFaceWithLandmarks: degenerate landmarks, fallback to identity transform")
            return Matrix()
        }

        val invDet = 1f / det

        // 伴随矩阵 / det
        val i00 = (m11 * m22 - m12 * m21) * invDet
        val i01 = -(m01 * m22 - m02 * m21) * invDet
        val i02 = (m01 * m12 - m02 * m11) * invDet
        val i10 = -(m10 * m22 - m12 * m20) * invDet
        val i11 = (m00 * m22 - m02 * m20) * invDet
        val i12 = -(m00 * m12 - m02 * m10) * invDet
        val i20 = (m10 * m21 - m11 * m20) * invDet
        val i21 = -(m00 * m21 - m01 * m20) * invDet
        val i22 = (m00 * m11 - m01 * m10) * invDet

        val a = i00 * sxu + i01 * syu + i02 * su
        val b = i10 * sxu + i11 * syu + i12 * su
        val c = i20 * sxu + i21 * syu + i22 * su

        val d = i00 * sxv + i01 * syv + i02 * sv
        val e = i10 * sxv + i11 * syv + i12 * sv
        val f = i20 * sxv + i21 * syv + i22 * sv

        val matrix = Matrix()
        matrix.setValues(floatArrayOf(a, b, c, d, e, f, 0f, 0f, 1f))
        return matrix
    }

    /**
     * 回退路径：ROI 裁剪 + 直接缩放到 112×112
     */
    private fun cropAndScaleFace(bitmap: Bitmap, roi: RectF): Bitmap {
        val marginW = (roi.width() * 0.2f).toInt().coerceAtLeast(10)
        val marginH = (roi.height() * 0.2f).toInt().coerceAtLeast(10)
        val cropX = roi.left.toInt().minus(marginW).coerceIn(0, bitmap.width)
        val cropY = roi.top.toInt().minus(marginH).coerceIn(0, bitmap.height)
        val cropW = (roi.width().toInt() + marginW * 2).coerceAtMost(bitmap.width - cropX)
        val cropH = (roi.height().toInt() + marginH * 2).coerceAtMost(bitmap.height - cropY)

        if (cropW <= 0 || cropH <= 0) {
            throw IllegalArgumentException("Invalid crop region: x=$cropX, y=$cropY, w=$cropW, h=$cropH")
        }

        val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
        val scaled = Bitmap.createScaledBitmap(cropped, FACE_INPUT_SIZE, FACE_INPUT_SIZE, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    /**
     * 匹配已有聚类簇
     *
     * 算法：计算新特征向量与所有已有簇质心的余弦相似度，
     * 若最高相似度 > COSINE_THRESHOLD，则归入该簇；否则返回 null 表示需要新建簇。
     *
     * @param feature 512 维特征向量
     * @return 匹配到的 personId，null 表示需要新建簇
     */
    suspend fun matchCluster(feature: FloatArray): Long? {
        val persons = personDao.getAllPersons()
        if (persons.isEmpty()) return null

        var bestPersonId: Long? = null
        var bestSimilarity = ClusteringConfig.COSINE_THRESHOLD

        for (person in persons) {
            val centroid = getPersonCentroidCached(person.personId) ?: continue
            val similarity = cosineSimilarity(feature, centroid)
            if (similarity > bestSimilarity) {
                bestSimilarity = similarity
                bestPersonId = person.personId
            }
        }

        return bestPersonId
    }

    /**
     * 创建新聚类簇
     *
     * @param feature 512 维特征向量（作为新簇的初始质心）
     * @param mediaId 媒体文件 ID
     * @return 新创建的 personId
     */
    suspend fun createCluster(feature: FloatArray, mediaId: Long): Long {
        val person = PersonEntity(
            faceCount = 1,
            coverMediaId = mediaId
        )
        val personId = personDao.insertPerson(person)
        Log.d(TAG, "Created new cluster: personId=$personId")

        // 写入首个 embedding
        val embeddingEntity = FaceEmbeddingEntity(
            mediaId = mediaId,
            personId = personId,
            embedding = floatArrayToByteArray(feature)
        )
        personDao.insertEmbedding(embeddingEntity)

        // 同步缓存质心
        centroidCache[personId] = feature.clone() to 1

        return personId
    }

    /**
     * 将新特征归入已有簇
     */
    suspend fun addToCluster(personId: Long, feature: FloatArray, mediaId: Long) {
        // 写入 embedding
        val embeddingEntity = FaceEmbeddingEntity(
            mediaId = mediaId,
            personId = personId,
            embedding = floatArrayToByteArray(feature)
        )
        personDao.insertEmbedding(embeddingEntity)

        // 更新人脸计数
        personDao.incrementFaceCount(personId)

        // 增量更新缓存质心，避免下次 matchCluster 全量读取 DB。
        centroidCache[personId]?.let { (oldCentroid, oldCount) ->
            val newCount = oldCount + 1
            val newCentroid = FloatArray(EMBEDDING_DIM)
            for (i in 0 until EMBEDDING_DIM) {
                newCentroid[i] = (oldCentroid[i] * oldCount + feature[i]) / newCount
            }
            centroidCache[personId] = newCentroid to newCount
        }

        Log.d(TAG, "Added to cluster: personId=$personId, mediaId=$mediaId")
    }

    /**
     * 流式增量聚类（扫描链路用）：将已存储但未分配（personId=null）的 embedding 逐个归类。
     *
     * 与 [createCluster]/[addToCluster] 的区别：Pass1 已把 embedding 写入 face_embeddings，
     * 这里用 [PersonDao.assignEmbedding] 改派 personId（不重新插入行），并同步质心缓存。
     *
     * 分配成功后同步回写 media_assets.faceId（与 Pass 2 DBSCAN 簇落库语义一致，后写覆盖先写）：
     * 相册「人物分组」按 media_assets.faceId 聚合，不回写则扫描中途（全量重聚尚未触发时）
     * 人物分组数与 persons 表严重脱节。
     *
     * @return 本次处理的 embedding 数（0 表示无未分配项）
     */
    suspend fun assignStoredEmbeddings(): Int {
        val unassigned = personDao.getUnassignedEmbeddings()
        if (unassigned.isEmpty()) return 0

        var processed = 0
        // mediaId -> personId，循环结束后按 person 分组批量回写 faceId（同媒体多人脸时后写覆盖先写）
        val mediaFaceIds = mutableMapOf<Long, Long>()
        for (entity in unassigned) {
            val feature = byteArrayToFloatArray(entity.embedding)
            processed++
            // 跳过零向量（模型缺失时 stage1 产出的占位）
            if (feature.all { value -> value == 0f }) continue

            val matchedId = matchCluster(feature)
            if (matchedId != null) {
                personDao.assignEmbedding(entity.embeddingId, matchedId)
                personDao.incrementFaceCount(matchedId)
                mediaFaceIds[entity.mediaId] = matchedId
                // 增量质心，与 addToCluster 一致
                centroidCache[matchedId]?.let { (oldCentroid, oldCount) ->
                    val newCount = oldCount + 1
                    val newCentroid = FloatArray(EMBEDDING_DIM)
                    for (i in 0 until EMBEDDING_DIM) {
                        newCentroid[i] = (oldCentroid[i] * oldCount + feature[i]) / newCount
                    }
                    centroidCache[matchedId] = newCentroid to newCount
                }
            } else {
                val personId = personDao.insertPerson(
                    PersonEntity(faceCount = 1, coverMediaId = entity.mediaId)
                )
                personDao.assignEmbedding(entity.embeddingId, personId)
                centroidCache[personId] = feature.clone() to 1
                mediaFaceIds[entity.mediaId] = personId
            }
        }
        if (mediaFaceIds.isNotEmpty()) {
            val mediaDao = AppDatabase.getDatabase(context).mediaDao()
            mediaFaceIds.entries
                .groupBy({ entry -> entry.value }, { entry -> entry.key })
                .forEach { (personId, mediaIds) ->
                    mediaDao.updateFaceIdBatch(mediaIds, personId.toString())
                }
        }
        Log.i(TAG, "Streaming-assigned $processed stored embeddings (unassigned were ${unassigned.size})")
        return processed
    }

    /**
     * 合并两个簇（将 personB 的所有 embedding 转移到 personA，删除 personB）
     */
    suspend fun mergeClusters(personA: Long, personB: Long) {
        val embeddingsB = personDao.getEmbeddingsByPerson(personB)
        for (embedding in embeddingsB) {
            personDao.assignEmbedding(embedding.embeddingId, personA)
        }

        val countB = embeddingsB.size
        // 更新 personA faceCount
        repeat(countB) { personDao.incrementFaceCount(personA) }

        // 合并缓存质心：personA = (centroidA * countA + centroidB * countB) / (countA + countB)
        val cachedA = centroidCache[personA]
        val cachedB = centroidCache[personB]
        if (cachedA != null && cachedB != null) {
            val (centroidA, countA) = cachedA
            val (centroidB, countBFromCache) = cachedB
            val totalCount = countA + countBFromCache
            val mergedCentroid = FloatArray(EMBEDDING_DIM)
            for (i in 0 until EMBEDDING_DIM) {
                mergedCentroid[i] = (centroidA[i] * countA + centroidB[i] * countBFromCache) / totalCount
            }
            centroidCache[personA] = mergedCentroid to totalCount
        } else {
            // 缓存不一致时移除 personA 缓存，下次 matchCluster 会从 DB 重新计算。
            centroidCache.remove(personA)
        }
        centroidCache.remove(personB)

        // 媒体 faceId 也指向被吸收的 personB：一并改派到 personA
        // （相册「人物分组」按 media_assets.faceId 聚合，不更新则合并后拆组仍显示）
        personDao.reassignMediaFaceId(personB.toString(), personA.toString())

        // 删除 personB
        personDao.unlinkEmbeddings(personB)
        personDao.deletePerson(personB)

        Log.d(TAG, "Merged clusters: $personB -> $personA, ${countB} embeddings moved")
    }

    /**
     * 跨簇合并 pass：把「质心相似度 ≥ [threshold]」的 person 两两合并，愈合「同一人被拆成多组」。
     *
     * 为修复「同一人因表情/角度/光线差异被拆成多个 1~4 张小分组」（如用户反馈的 #1647 / #1628），
     * 对**双方均 ≤ [ClusteringConfig.MERGE_SMALL_CLUSTER_MAX_SIZE] 的小簇**使用更宽松的
     * [ClusteringConfig.MERGE_SMALL_CLUSTER_SIMILARITY_THRESHOLD]（默认 0.50）；
     * 涉及任一较大簇时仍使用 [threshold]（默认 0.55），避免把「大宝」和「老郭」等大簇误合并。
     *
     * 凝聚式（agglomerative）：每轮在存活 person 中找相似度最高且可合并的一对 → [mergeClusters]，
     * 就地更新幸存者质心后继续，直到没有达标对。幸存者选择与「双方命名跳过」见
     * [decideSmallClusterMerge]（保留 name/isSelf/cover）。
     *
     * @return 本次执行的合并次数。
     */
    suspend fun mergeSmallClusters(
        threshold: Float = ClusteringConfig.MERGE_SIMILARITY_THRESHOLD
    ): Int {
        // personId -> 质心/计数/名/self；合并后就地更新，避免每轮全量重读 DB
        val persons = personDao.getAllPersons()
        val centroids = mutableMapOf<Long, FloatArray>()
        val counts = mutableMapOf<Long, Int>()
        val names = mutableMapOf<Long, String?>()
        val selves = mutableMapOf<Long, Boolean>()
        for (person in persons) {
            val centroid = getPersonCentroidCached(person.personId) ?: continue
            val count = personDao.getEmbeddingCount(person.personId)
            if (count <= 0) continue
            centroids[person.personId] = centroid
            counts[person.personId] = count
            names[person.personId] = person.name
            selves[person.personId] = person.isSelf
        }
        val alive = centroids.keys.toMutableSet()
        if (alive.size < 2) return 0

        val smallSize = ClusteringConfig.MERGE_SMALL_CLUSTER_MAX_SIZE
        val smallThreshold = ClusteringConfig.MERGE_SMALL_CLUSTER_SIMILARITY_THRESHOLD

        var totalMerges = 0
        var guard = 0
        while (guard++ < MAX_MERGE_ITERATIONS) {
            val list = alive.toList()
            var bestSim = Float.NEGATIVE_INFINITY
            var bestA = -1L
            var bestB = -1L
            for (i in list.indices) {
                val a = list[i]
                val ca = centroids[a] ?: continue
                for (j in i + 1 until list.size) {
                    val b = list[j]
                    val cb = centroids[b] ?: continue
                    val aNamed = !names[a].isNullOrBlank()
                    val bNamed = !names[b].isNullOrBlank()
                    if (aNamed && bNamed) continue // 双方已命名：尊重人工区分

                    val bothSmall = (counts[a] ?: 0) <= smallSize && (counts[b] ?: 0) <= smallSize
                    val effectiveThreshold = if (bothSmall) min(threshold, smallThreshold) else threshold

                    val sim = cosineSimilarity(ca, cb)
                    if (sim <= effectiveThreshold) continue
                    if (sim > bestSim) {
                        bestSim = sim
                        bestA = a
                        bestB = b
                    }
                }
            }
            if (bestA < 0) break

            val pairThreshold = when {
                (counts[bestA] ?: 0) <= smallSize && (counts[bestB] ?: 0) <= smallSize -> min(threshold, smallThreshold)
                else -> threshold
            }

            val decision = decideSmallClusterMerge(
                MergeCandidate(bestA, names[bestA], selves[bestA] == true, counts[bestA] ?: 1),
                MergeCandidate(bestB, names[bestB], selves[bestB] == true, counts[bestB] ?: 1),
                bestSim,
                pairThreshold
            ) ?: break // 理论不会 null（已过滤双方命名与 sim≤阈值）
            val survivor = decision.survivor.personId
            val absorbed = decision.absorbed.personId

            mergeClusters(survivor, absorbed)
            alive.remove(absorbed)
            counts[survivor] = (counts[survivor] ?: 0) + (counts.remove(absorbed) ?: 0)
            names.remove(absorbed)
            selves.remove(absorbed)
            // 幸存者质心已被 mergeClusters 更新进缓存，回填本地图
            getPersonCentroidCached(survivor)?.let { centroids[survivor] = it }
            centroids.remove(absorbed)

            totalMerges++
            Log.d(TAG, "mergeSmallClusters: $absorbed -> $survivor (sim=${"%.3f".format(bestSim)}), total $totalMerges")
        }
        if (totalMerges > 0) {
            Log.i(TAG, "mergeSmallClusters: $totalMerges similar cluster(s) merged (threshold=$threshold, smallThreshold=$smallThreshold)")
        }
        return totalMerges
    }

    /**
     * 拆分 pass：把「两个人被并成一组」的簇切成两个 person。
     *
     * 判定（保守）：簇 embedding ≥ [minSize]，以最远（最低相似度）两点为种子分两半，
     * 仅当两半各 ≥2、各自内聚 ≥ [intraMin]、且互相交叉 ≤ [crossMax] 才拆——较小的一半
     * spin off 为新 person。命中 140 这类「两半各自内聚、互相很低」的 k-NN 桥接误并；
     * 又不误拆高方差单人簇（内聚不够不拆）。
     *
     * @return 本次拆分次数。
     */
    suspend fun splitOvermergedClusters(
        intraMin: Float = ClusteringConfig.SPLIT_INTRA_MIN,
        crossMax: Float = ClusteringConfig.SPLIT_CROSS_MAX,
        minSize: Int = ClusteringConfig.SPLIT_MIN_CLUSTER_SIZE
    ): Int {
        var totalSplits = 0
        var guard = 0
        while (guard++ < MAX_SPLIT_ITERATIONS) {
            val persons = personDao.getAllPersons()
            var didSplit = false
            for (person in persons) {
                val entities = personDao.getEmbeddingsByPerson(person.personId)
                if (entities.size < minSize) continue
                val feats = entities.map { entity -> byteArrayToFloatArray(entity.embedding) }
                val (halfA, halfB) = twoWayPartition(feats)
                if (halfA.size < 2 || halfB.size < 2) continue
                val intraA = meanPairwiseSim(feats, halfA)
                val intraB = meanPairwiseSim(feats, halfB)
                val cross = meanCrossSim(feats, halfA, halfB)
                if (intraA < intraMin || intraB < intraMin || cross > crossMax) continue

                // 较小的一半 spin off 为新 person，较大的一半留在原 person
                val (keepIdx, spinIdx) = if (halfA.size >= halfB.size) halfA to halfB else halfB to halfA
                val spinMediaIds = spinIdx.map { idx -> entities[idx].mediaId }.distinct()
                val newPersonId = personDao.insertPerson(
                    PersonEntity(faceCount = spinIdx.size, coverMediaId = spinMediaIds.firstOrNull())
                )
                for (idx in spinIdx) {
                    personDao.assignEmbedding(entities[idx].embeddingId, newPersonId)
                }
                if (spinMediaIds.isNotEmpty()) {
                    personDao.setMediaFaceIds(spinMediaIds, newPersonId.toString())
                }
                val keepMediaIds = keepIdx.map { idx -> entities[idx].mediaId }.distinct()
                personDao.updatePersonStats(
                    person.personId,
                    faceCount = keepIdx.size,
                    coverMediaId = keepMediaIds.firstOrNull() ?: person.coverMediaId
                )
                centroidCache.remove(person.personId)
                totalSplits++
                didSplit = true
                Log.i(
                    TAG,
                    "splitOvermergedClusters: ${person.personId} -> keep ${keepIdx.size} + new $newPersonId ${spinIdx.size} " +
                        "(intraA=${"%.2f".format(intraA)} intraB=${"%.2f".format(intraB)} cross=${"%.2f".format(cross)})"
                )
                break // 拆分后 personId 变化，重新拉 persons
            }
            if (!didSplit) break
        }
        if (totalSplits > 0) {
            Log.i(TAG, "splitOvermergedClusters: $totalSplits over-merged cluster(s) split")
        }
        return totalSplits
    }

    /** 聚类维护：解散 sink（链式垃圾簇）→ 拆分（修过并）→ 合并（修欠并），供人物页进入/聚类末尾统一调用。 */
    suspend fun runClusterMaintenance(): Int {
        val sinks = dissolveSinks()
        val splits = splitOvermergedClusters()
        val merges = mergeSmallClusters()
        return sinks + splits + merges
    }

    /**
     * 解散 sink：把 k-NN 链式并出的「垃圾簇」（median 两两相似度 < [cohesionThreshold]、规模 ≥ [minSize]）
     * 解散——embedding 改派为未分配、删除 person，再用增量匹配（0.65）把释放的脸重新归入正确的人或新建小簇。
     *
     * 仅解散匿名 sink（命名人物尊重用户标记，不动）。命中 177 这类 median≈0.24 的链式 sink。
     *
     * @return 解散的 sink 数。
     */
    suspend fun dissolveSinks(
        cohesionThreshold: Float = ClusteringConfig.SINK_COHESION_MAX,
        minSize: Int = ClusteringConfig.SINK_MIN_SIZE
    ): Int {
        var dissolved = 0
        val persons = personDao.getAllPersons()
        for (person in persons) {
            if (!person.name.isNullOrBlank()) continue // 命名人物不解散
            val entities = personDao.getEmbeddingsByPerson(person.personId)
            if (entities.size < minSize) continue
            val sample = entities.take(ClusteringConfig.SINK_SAMPLE_CAP)
                .map { entity -> byteArrayToFloatArray(entity.embedding) }
            if (medianPairwiseSim(sample) >= cohesionThreshold) continue

            personDao.unlinkEmbeddings(person.personId) // personId=NULL，释放
            personDao.deletePerson(person.personId)
            centroidCache.remove(person.personId)
            dissolved++
            Log.i(TAG, "dissolveSinks: dissolved person ${person.personId} (${entities.size} emb)")
        }
        if (dissolved > 0) {
            val reassigned = assignStoredEmbeddings() // 释放的脸重新归入正确的人/新建小簇
            Log.i(TAG, "dissolveSinks: dissolved $dissolved sink(s); re-assigned $reassigned freed embeddings")
        }
        return dissolved
    }

    // ── 拆分 pass 的纯数值辅助 ─────────────────────────────────────────
    /** 以最远（最低相似度）两点为种子把向量集分两半，返回 (idxA, idxB)。 */
    private fun twoWayPartition(feats: List<FloatArray>): Pair<List<Int>, List<Int>> {
        var wi = 0
        var wj = 1
        var worst = cosineSimilarity(feats[0], feats[1])
        for (i in feats.indices) {
            for (j in i + 1 until feats.size) {
                val s = cosineSimilarity(feats[i], feats[j])
                if (s < worst) {
                    worst = s
                    wi = i
                    wj = j
                }
            }
        }
        val a = mutableListOf(wi)
        val b = mutableListOf(wj)
        for (k in feats.indices) {
            if (k == wi || k == wj) continue
            if (cosineSimilarity(feats[k], feats[wi]) >= cosineSimilarity(feats[k], feats[wj])) a.add(k) else b.add(k)
        }
        return a to b
    }

    /** 一组内（按 feats 下标）的平均两两相似度。 */
    private fun meanPairwiseSim(feats: List<FloatArray>, idx: List<Int>): Float {
        if (idx.size < 2) return 0f
        var sum = 0f
        var n = 0
        for (i in idx.indices) {
            for (j in i + 1 until idx.size) {
                sum += cosineSimilarity(feats[idx[i]], feats[idx[j]])
                n++
            }
        }
        return if (n > 0) sum / n else 0f
    }

    /** 一组向量的 median 两两相似度（sink 判定用；sample 已在外层截断控制开销）。 */
    private fun medianPairwiseSim(feats: List<FloatArray>): Float {
        if (feats.size < 2) return 1f
        val sims = ArrayList<Float>(feats.size * (feats.size - 1) / 2)
        for (i in feats.indices) {
            for (j in i + 1 until feats.size) {
                sims.add(cosineSimilarity(feats[i], feats[j]))
            }
        }
        sims.sort()
        return sims[sims.size / 2]
    }

    /** 两组（按 feats 下标）之间的平均相似度。 */
    private fun meanCrossSim(feats: List<FloatArray>, a: List<Int>, b: List<Int>): Float {
        var sum = 0f
        var n = 0
        for (i in a) {
            for (j in b) {
                sum += cosineSimilarity(feats[i], feats[j])
                n++
            }
        }
        return if (n > 0) sum / n else 0f
    }

    /**
     * 获取某个簇的质心特征向量（优先读缓存，缓存失效或缺失时从 DB 重新计算）。
     */
    private suspend fun getPersonCentroidCached(personId: Long): FloatArray? {
        // 用 DB 中的 embedding 数量校验缓存是否仍然有效。
        val dbCount = personDao.getEmbeddingCount(personId)
        if (dbCount == 0) return null

        val cached = centroidCache[personId]
        if (cached != null && cached.second == dbCount) {
            return cached.first
        }

        // 缓存缺失或数量不一致：从 DB 重新计算并回填缓存。
        val centroid = computePersonCentroidFromDb(personId) ?: return null
        centroidCache[personId] = centroid to dbCount
        return centroid
    }

    /**
     * 从数据库计算某个簇的质心特征向量（所有 embedding 的算术平均值）。
     */
    private suspend fun computePersonCentroidFromDb(personId: Long): FloatArray? {
        val embeddings = personDao.getEmbeddingsByPerson(personId)
        if (embeddings.isEmpty()) return null

        val centroid = FloatArray(EMBEDDING_DIM)
        for (entity in embeddings) {
            val feature = byteArrayToFloatArray(entity.embedding)
            for (i in 0 until EMBEDDING_DIM) {
                centroid[i] += feature[i]
            }
        }
        for (i in 0 until EMBEDDING_DIM) {
            centroid[i] /= embeddings.size.toFloat()
        }
        return centroid
    }

    /**
     * 计算两个向量的余弦相似度 [0, 1]
     */
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = sqrt(normA.toDouble()) * sqrt(normB.toDouble())
        return if (denominator == 0.0) 0f else (dot / denominator).toFloat()
    }

    private fun floatArrayToByteArray(array: FloatArray): ByteArray {
        val bytes = ByteArray(array.size * 4)
        for (i in array.indices) {
            val bits = java.lang.Float.floatToRawIntBits(array[i])
            bytes[i * 4] = (bits shr 24).toByte()
            bytes[i * 4 + 1] = (bits shr 16).toByte()
            bytes[i * 4 + 2] = (bits shr 8).toByte()
            bytes[i * 4 + 3] = bits.toByte()
        }
        return bytes
    }

    private fun byteArrayToFloatArray(bytes: ByteArray): FloatArray {
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            val bits = ((bytes[i * 4].toInt() and 0xFF) shl 24) or
                    ((bytes[i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((bytes[i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (bytes[i * 4 + 3].toInt() and 0xFF)
            floats[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return floats
    }

    /**
     * [临时调试] 导出所有 face embeddings 到 JSONL 文件，供 Python 分析阈值。
     *
     * 输出格式：每行一个 JSON 对象
     * ```json
     * {"embeddingId":1,"personId":3,"mediaId":123,"embedding":[0.1,0.2,...]}
     * ```
     *
     * @param outputFile 输出文件路径（建议 externalCacheDir/face_embeddings.jsonl）
     */
    suspend fun dumpEmbeddingsForAnalysis(outputFile: File) {
        val allEmbeddings = personDao.getAllEmbeddings()
        Log.i(TAG, "Dumping ${allEmbeddings.size} embeddings to ${outputFile.absolutePath}")

        BufferedWriter(FileWriter(outputFile)).use { writer ->
            for (entity in allEmbeddings) {
                val feature = byteArrayToFloatArray(entity.embedding)
                val json = JSONObject().apply {
                    put("embeddingId", entity.embeddingId)
                    put("personId", entity.personId ?: -1)
                    put("mediaId", entity.mediaId)
                    put("embedding", JSONArray(feature.toList()))
                }
                writer.write(json.toString())
                writer.newLine()
            }
        }

        Log.i(TAG, "Embeddings dump finished: ${outputFile.absolutePath}")
    }
}