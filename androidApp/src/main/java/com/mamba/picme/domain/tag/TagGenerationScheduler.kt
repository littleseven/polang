package com.mamba.picme.domain.tag

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.room.withTransaction
import com.mamba.picme.agent.AndroidAgentComposition
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.agent.core.model.context.MediaType
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.DevicePreference
import com.mamba.picme.beauty.api.facedetect.FaceDetectorFactory
import com.mamba.picme.beauty.api.facedetect.InferenceBackendType
import com.mamba.picme.beauty.api.facedetect.LandmarkDetectorType
import com.mamba.picme.beauty.api.facedetect.RoiDetectorType
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonRelationEntity
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.domain.model.AppLanguage
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.domain.person.RelationSnapshotEntry
import com.mamba.picme.domain.person.RelationSnapshotRestorer
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.LabelSinicizer
import com.mamba.picme.domain.tag.florence2.Florence2Tagger
import com.mamba.picme.domain.tag.florence2.Florence2Tokenizer
import com.mamba.picme.domain.tag.i18n.OpusMtTranslator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * 用于全量重聚类时保留命名人信息的快照。
 *
 * @param personId 原人物 ID
 * @param name 用户为该人物设置的名称（未命名的"我"本人也进快照，name 可为 null）
 * @param centroid 该人物所有 face embedding 的质心
 * @param isSelf 是否为"我"本人（is_self 标记需随快照恢复）
 */
data class NamedPersonSnapshot(
    val personId: Long,
    val name: String?,
    val centroid: FloatArray,
    val isSelf: Boolean = false
)

/**
 * 标签生成批处理调度器
 *
 * 管理全量扫描和单张处理的调度，提供进度回调和取消支持。
 *
 * ## 架构
 * - 通过 [dispatcher] 参数控制执行线程（Service 场景传入单线程调度器保证串行）
 * - 通过 [guard] 在每张照片处理前检查是否允许继续（电池/热状态守卫）
 *
 * ## 触发方式
 * - [scanAll]：全量扫描所有照片
 * - [scanIncremental]：增量扫描未标记照片
 * - [processSingle]：处理单张新照片
 * - [cancel]：取消进行中的扫描
 */
class TagGenerationScheduler(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val guard: suspend () -> GuardResult = { GuardResult.ALLOW },
    private val getThrottleMs: () -> Long = { 1000L },
    private val getPass3CooldownMs: () -> Long = { DEFAULT_PASS3_COOLDOWN_MS },
    private val userSettingsRepository: UserSettingsRepository = UserPreferencesRepository(context)
) {

    /**
     * 当前打标模型 key：首选 Florence-2（ORT，恒英文打标），未下载回退 Qwen3-VL-2B（MNN）；
     * 手动指定覆盖首选。详见 [TaggerModelSelector]。
     *
     * Florence-2 走 ORT 不经 MNN，故其可用性按模型目录存在性校验（与 MNN 缓存无关）。
     * **关键**：AUTO/默认路径同样要认 Florence-2 —— [TaggerModelSelector.resolve] 的 isAvailable
     * 必须对 florence2_base 走目录检查，否则 MNN 的 [isModelAvailable][LocalLlmEngine.isModelAvailable]
     * 不识别 ORT 模型会误判为不可用，进而把首选 Florence-2 降级到 Qwen（若 Qwen native load
     * 失败 → 每张 Pass3 任务抛 "Model not loaded" → Pass 3 全量失败）。
     */
    private val taggerModelKey: String
        get() {
            val raw = userSettingsRepository.getTaggerModelKeyBlocking()
            val engine = AndroidAgentComposition.localLlmEngine
            return TaggerModelSelector.resolve(
                raw = raw,
                isAvailable = { key ->
                    if (key == TaggerModelSelector.defaultKey) {
                        // Florence-2 走 ORT：按模型目录 + 文件数校验，不能用 MNN 缓存判定可用性
                        val dir = ModelPathConfig.getModelDir(context, ModelPathConfig.MODEL_ID_FLORENCE2)
                        dir.exists() && (dir.listFiles()?.size ?: 0) >= 10
                    } else {
                        engine.isModelAvailable(key)
                    }
                }
            )
        }

    /**
     * 当前打标/图像理解模型 key（对外只读入口）。Chat 页单图理解等场景经此与打标同源选模型，
     * 避免各自重复「读设置 + 下载感知兜底」的解析逻辑。
     *
     * 内部阻塞读取 DataStore，调用方需在 IO 上下文使用。
     */
    fun currentTaggerModelKey(): String = taggerModelKey

    companion object {
        private const val DEFAULT_PASS3_COOLDOWN_MS = 800L
        private const val TAG = "TagScheduler"

        /**
         * 照片解码失败哨兵：写入 faceRoiResult 使“faceRoiResult IS NULL”口径收敛，
         * 防止损坏 / 本机不支持的格式 / URI 失效的照片被增量 Pass 1 无限重选。
         * 字段与 faceRoiToJson 对齐（parseFaceRoi 兼容），额外 decodeError 标记便于排查；
         * FULL 全量重扫 resetAllFaceData() 会清空该列以备将来重试。
         */
        private const val DECODE_FAILURE_ROI_JSON =
            """{"hasFace":false,"faceCount":0,"isSelfie":false,"isGroupPhoto":false,"decodeError":true}"""

        /** 批次大小：每处理此数量照片后强制冷却 */
        private const val BATCH_SIZE = 10

        /** 批次间强制冷却时间（ms） */
        private const val BATCH_COOLDOWN_MS = 15_000L

        /** 增量扫描单次最大处理量 */
        private const val INCREMENTAL_MAX_PHOTOS = 50

        /** hasFace 清理时批量加载 MediaEntity 的大小，防止 Java Heap OOM */
        private const val CLEANUP_BATCH_SIZE = 100
    }

    /**
     * 守卫检查结果
     */
    enum class GuardResult {
        /** 允许继续 */
        ALLOW,
        /** 暂停等待（增大节流间隔） */
        PAUSE,
        /** 终止扫描 */
        ABORT
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var currentJob: Job? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    /** 防止「重新聚类」被并发/重复触发：同一人被多次重提并重复分组。 */
    private val reembedMutex = Mutex()

    /**
     * 产出率哨兵：Pass 1 检测到人脸但 embedding 提取为空的累计次数
     * （人脸模型缺失/损坏的特征信号）。由 [TagScanOrchestrator] 在会话收尾时
     * 经 [consumeFaceEmbeddingFailureCount] 读取并清零，转为用户可见警告。
     */
    private val faceEmbeddingFailureCount = AtomicInteger(0)

    /** 读取并清零「检测到人脸但 embedding 为空」的累计次数（供会话收尾告警） */
    fun consumeFaceEmbeddingFailureCount(): Int = faceEmbeddingFailureCount.getAndSet(0)

    private val _progress = MutableStateFlow<TagScanProgress?>(null)
    val progress: StateFlow<TagScanProgress?> = _progress.asStateFlow()

    private val _lastScanMessage = MutableStateFlow<String?>(null)
    val lastScanMessage: StateFlow<String?> = _lastScanMessage.asStateFlow()

    private val db = AppDatabase.getDatabase(context)
    private val personDao = db.personDao()

    /** 人物领域仓库（关系图谱快照导出/恢复走其收口 API） */
    private val personRepository by lazy {
        PersonRepository(
            personDao = db.personDao(),
            relationDao = db.personRelationDao(),
            mediaDao = db.mediaDao()
        )
    }
    private val vocab = ControlledVocab.loadFromAssets(context)
    private val enToZhTranslatorLazy = lazy {
        // 双语 opus-mt-en-zh 不需要 `>>eng<<` 语言标签前缀（那是多语模型的用法）；
        // 实测加 tag 会诱发幻觉（"白马王子"），故 useLangTag=false。
        OpusMtTranslator(
            context,
            ModelPathConfig.getModelDir(context, ModelPathConfig.MODEL_ID_OPUS_MT_EN_ZH),
            initialSrcTag = ">>eng<<",
            useLangTag = false
        )
    }
    private val enToZhTranslator: OpusMtTranslator by enToZhTranslatorLazy

    private val labelSinicizer: LabelSinicizer by lazy {
        LabelSinicizer(
            controlledVocab = vocab,
            bilingualVocab = BilingualVocab.loadFromAssets(context),
            // en→zh summary：opus-mt-en-zh 翻译整句。
            // 模型未下载/初始化失败时 translate 原样返回英文（OpusMtTranslator 内部兜底），summary_zh 暂留英文。
            translateSummary = { en -> enToZhTranslator.translate(en) },
            // en→zh 单标签兜底：Florence-2 等开放词汇模型会产出词表外的自由标签（portrait/pendant/...），
            // 词表未命中时走 MT，避免 labelsZh 留一堆英文。
            translateLabel = { en -> enToZhTranslator.translate(en) }
        )
    }

    /** Florence-2 tagger（ORT，独立于 MNN 桥）。tagger 为 florence2_base 时使用。 */
    private val florence2TaggerLazy = lazy {
        val dir = ModelPathConfig.getModelDir(context, ModelPathConfig.MODEL_ID_FLORENCE2)
        if (dir.exists() && (dir.listFiles()?.size ?: 0) >= 10) {
            Florence2Tokenizer.load(dir)
            Florence2Tagger(dir).also { it.init() }
        } else {
            null
        }
    }
    private val florence2Tagger: Florence2Tagger? by florence2TaggerLazy
    private val normalizer = TagNormalizer(vocab)
    private val faceClusterEngine = FaceClusterEngine(context)

    private val openClGuardian: OpenClGuardian by lazy {
        OpenClGuardian(
            context = context,
            engine = AndroidAgentComposition.localLlmEngine,
            prefs = userSettingsRepository,
            modelId = taggerModelKey
        )
    }

    private val pipelineLazy = lazy {
        val faceDetector = FaceDetectorFactory.create(context)
        // 【关键修复】必须调用 updatePipelineConfig()，否则 FaceDetectorManager
        // 的 isPipelineInitialized 保持 false，所有 detectPhoto() 静默返回 null。
        // 方案 B：ROI 与 2D106 landmark 均走 MNN + OpenCL GPU（FORCE_GPU）。
        faceDetector.updatePipelineConfig(DetectionPipelineConfig(
            roiDetector = RoiDetectorType.DET10G,
            landmarkDetector = LandmarkDetectorType.INSIGHTFACE_2D106,
            roiEngine = InferenceBackendType.MNN,
            landmarkEngine = InferenceBackendType.MNN,
            roiDevice = DevicePreference.FORCE_GPU,
            landmarkDevice = DevicePreference.FORCE_GPU
        ))
        val llmEngine = AndroidAgentComposition.localLlmEngine
        val mobileClip = MobileClipEngine(context)
        val tokenizer = MobileClipTokenizer(context)
        val classifier = MobileClipTagClassifier(mobileClip, tokenizer, vocab)
        TagGenerationPipeline(
            context = context,
            faceDetector = faceDetector,
            llmEngine = llmEngine,
            faceClusterEngine = faceClusterEngine,
            normalizer = normalizer,
            openClGuardian = openClGuardian,
            mobileClipEngine = mobileClip,
            mobileClipTagClassifier = classifier,
            florence2TaggerProvider = { florence2Tagger },
            taggerModelKeyProvider = { taggerModelKey }
        )
    }
    private val pipeline: TagGenerationPipeline by pipelineLazy

    /**
     * 级联释放本调度器持有的全部推理引擎 native 资源。
     *
     * 仅供 TagGenerationService.onDestroy() 在任务已全部取消后调用，调用后本实例不可再用。
     * 各 lazy 引擎未初始化（本轮未用到）则跳过，不为释放而触发模型加载。
     */
    fun release() {
        scope.cancel()
        if (pipelineLazy.isInitialized()) {
            runCatching { pipeline.release() }
                .onFailure { Log.w(TAG, "release pipeline failed: ${it.message}") }
        }
        runCatching { faceClusterEngine.release() }
            .onFailure { Log.w(TAG, "release faceClusterEngine failed: ${it.message}") }
        if (florence2TaggerLazy.isInitialized()) {
            runCatching { florence2Tagger?.release() }
                .onFailure { Log.w(TAG, "release florence2Tagger failed: ${it.message}") }
        }
        if (enToZhTranslatorLazy.isInitialized()) {
            runCatching { enToZhTranslator.release() }
                .onFailure { Log.w(TAG, "release enToZhTranslator failed: ${it.message}") }
        }
    }

    /**
     * 触发全量 3-Pass 混合扫描
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.scheduleAutoScan] / [TagScanOrchestrator.schedulePass]。
     * 旧方法基于局部计数器，与任务队列系统的进度不同源，会导致统计不一致。
     */
    @Deprecated(
        "Use TagScanOrchestrator.scheduleAutoScan() or schedulePass() instead",
        ReplaceWith("TagScanOrchestrator(context, this).scheduleAutoScan()")
    )
    fun scanAll(progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }) {
        throw NotImplementedError(
            "scanAll is deprecated. Use TagScanOrchestrator.scheduleAutoScan() or schedulePass() instead."
        )
    }

    /** 处理单张新照片 */
    suspend fun processSingle(uri: String, mediaId: Long) {
        scope.launch {
            try {
                if (!ensureModelLoaded()) return@launch

                val resultJson = pipeline.processPhoto(
                    uri = uri,
                    lensFacing = CameraSelector.LENS_FACING_BACK,
                    mediaId = mediaId
                )

                if (resultJson.isNotEmpty()) {
                    persistUnifiedTags(mediaId, resultJson)
                }
                Log.d(TAG, "Single photo processed: mediaId=$mediaId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process single photo $mediaId: ${e.message}")
            }
        }
    }

    /**
     * 单张同步处理(照片信息弹窗「重新打标」用):走完整 Pass3 pipeline(与集中扫描同源),
     * 返回结构化标签 JSON(scene/activity/objects/tags/summary),并写入 db。
     *
     * 与 [processSingle] 区别:同步 suspend 返回 resultJson(供 UI 即时刷新),
     * 不经 dispatcher 队列/批控节流(单张用户显式触发)。
     *
     * @return 与当前 UI 语言匹配的结构化标签 JSON(英文 UI→labelsEn,其余→labelsZh);
     * null 表示失败(模型未加载 / 媒体不存在 / 空结果)。
     */
    suspend fun processSingleSync(uri: String): String? = withContext(Dispatchers.IO) {
        if (!ensureModelLoaded()) return@withContext null
        val entity = db.mediaDao().getMediaByUri(uri) ?: return@withContext null
        val resultJson = pipeline.processPhoto(
            uri = uri,
            lensFacing = CameraSelector.LENS_FACING_BACK,
            mediaId = entity.id
        )
        if (resultJson.isEmpty()) return@withContext null
        persistUnifiedTags(entity.id, resultJson)
    }

    /**
     * 预览页「图像理解」入口（entry 2）：对单张图片生成自然语言描述。
     *
     * 模型与 entry 1/3 同源（复用已解析的 [taggerModelKey]）：
     * - Florence-2 → `Florence2Tagger.summary`（英文 caption）；中文 UI → en→zh 翻译。
     * - Qwen3-VL-2B → `imageInference`，按 UI 语言直出提示词。
     *
     * 输出语言跟随 [userSettingsRepository] 的 appLanguage（zh-TW 复用 zh 译文）。
     *
     * @return 描述文本；模型不可用 / 解码失败 / 推理空 → null。
     */
    suspend fun describeImage(uri: String): String? = withContext(Dispatchers.IO) {
        val bitmap = pipeline.loadBitmapPublic(uri) ?: return@withContext null
        try {
            describeLoadedBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * [describeImage] 的 Bitmap 重载：调用方已持有解码好的 Bitmap 时使用
     *（如 Chat 页单图理解，图片在内部存储、无 content:// URI，避免二次解码）。
     *
     * Bitmap 所有权归调用方，本函数不负责回收。
     *
     * @return 描述文本；模型不可用 / 推理空 → null。
     */
    suspend fun describeImage(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        describeLoadedBitmap(bitmap)
    }

    private suspend fun describeLoadedBitmap(bitmap: Bitmap): String? {
        val lang = userSettingsRepository.getAppLanguageBlocking()
        val strategy = ImageDescriptionStrategyResolver.resolve(taggerModelKey, lang)
        return if (taggerModelKey == "florence2_base") {
            val tagger = florence2Tagger
            if (tagger == null || !tagger.isInit) return null
            val caption = tagger.tag(bitmap).summary
            if (caption.isBlank()) return null
            if (strategy.needsZhTranslate) enToZhTranslator.translate(caption) else caption
        } else {
            if (!ensureModelLoaded()) return null
            val engine = AndroidAgentComposition.localLlmEngine
            val result = engine.imageInference(
                bitmap = bitmap,
                systemPrompt = strategy.systemPrompt,
                userPrompt = strategy.userPrompt,
                maxTokens = 256
            )
            result.ifEmpty { null }
        }
    }

    /**
     * 取消进行中的扫描
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.cancel]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.cancel() instead",
        ReplaceWith("TagScanOrchestrator(context, this).cancel()")
    )
    fun cancel() {
        throw NotImplementedError(
            "cancel() is deprecated. Use TagScanOrchestrator.cancel() instead."
        )
    }

    /**
     * 增量扫描：3-Pass 混合模型，仅处理未标记标签的照片
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.scheduleAutoScan]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.scheduleAutoScan() instead",
        ReplaceWith("TagScanOrchestrator(context, this).scheduleAutoScan()")
    )
    fun scanIncremental(
        maxPhotos: Int = INCREMENTAL_MAX_PHOTOS,
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanIncremental is deprecated. Use TagScanOrchestrator.scheduleAutoScan() instead."
        )
    }

    // ═══════════════════════════════════════════════════
    //  独立阶段扫描（分阶段批量控制）
    // ═══════════════════════════════════════════════════

    /**
     * [Pass 1 独立执行] 全量人脸检测 + 人脸 Embedding + MobileCLIP 语义编码（内联合并）
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.FACE_DETECTION, mode=FULL) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.FACE_DETECTION, mode = FULL)")
    )
    fun scanPass1(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass1 is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.FACE_DETECTION, mode = FULL)."
        )
    }

    /**
     * [Pass 2 独立执行] DBSCAN 全局聚类
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.DBSCAN) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.DBSCAN)")
    )
    fun scanPass2(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass2 is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.DBSCAN)."
        )
    }

    /**
     * [Pass 3 独立执行] 仅进行图像打标
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.IMAGE_TAGGING) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.IMAGE_TAGGING)")
    )
    fun scanPass3(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass3 is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.IMAGE_TAGGING)."
        )
    }

    /**
     * [Pass 3 重新生成] 清空已有标签后全量重标
     *
     * @deprecated 已迁移到 [TagScanOrchestrator.schedulePass]。
     */
    @Deprecated(
        "Use TagScanOrchestrator.schedulePass(TagScanPass.IMAGE_TAGGING, mode=FULL) instead",
        ReplaceWith("TagScanOrchestrator(context, this).schedulePass(TagScanPass.IMAGE_TAGGING, mode = FULL)")
    )
    fun scanPass3Full(
        progressCallback: suspend (processed: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        throw NotImplementedError(
            "scanPass3Full is deprecated. Use TagScanOrchestrator.schedulePass(TagScanPass.IMAGE_TAGGING, mode = FULL)."
        )
    }

    // ═══════════════════════════════════════════════════
    //  守卫检查
    // ═══════════════════════════════════════════════════

    /** 电池/热状态守卫检查 */
    private suspend fun guardCheck(): Boolean {
        when (guard()) {
            GuardResult.ABORT -> {
                Log.i(TAG, "Guard ABORT")
                return false
            }
            GuardResult.PAUSE -> {
                Log.d(TAG, "Guard PAUSE, extended throttle (${getThrottleMs()}ms)")
                delay(getThrottleMs())
                // PAUSE 不跳过当前照片，仅增加节流
            }
            GuardResult.ALLOW -> { /* continue */ }
        }
        return true
    }

    // ═══════════════════════════════════════════════════
    //  Pass 2: 人脸聚类（方案 A DBSCAN / 方案 B 密度自适应 k-NN）
    // ═══════════════════════════════════════════════════

    /**
     * 基于 face_embeddings 表执行人脸聚类。
     * 当前由 [ClusteringConfig.USE_ADAPTIVE_CLUSTERING] 控制使用方案 B 或方案 A。
     *
     * @param namedPersonSnapshots 全量重聚类时传入的已命名人物质心快照，
     *                             用于将新簇与旧人物匹配并复用 personId/name。
     */
    private suspend fun runDbscanClustering(
        dao: com.mamba.picme.data.local.MediaDao,
        namedPersonSnapshots: List<NamedPersonSnapshot> = emptyList()
    ) {
        val unassigned = personDao.getUnassignedEmbeddings()
        if (unassigned.isEmpty()) {
            Log.i(TAG, "DBSCAN: no unassigned embeddings, skipping")
            return
        }

        // 按 mediaId 分组，同时记录每个 (mediaId, faceIndex) 对应的 embeddingId
        val embeddingsMap = mutableMapOf<Long, MutableList<FloatArray>>()
        val embeddingIdMap = mutableMapOf<Pair<Long, Int>, Long>()
        for (emb in unassigned) {
            val feature = byteArrayToFloatArray(emb.embedding)
            val list = embeddingsMap.getOrPut(emb.mediaId) { mutableListOf() }
            val faceIndex = list.size
            list.add(feature)
            embeddingIdMap[emb.mediaId to faceIndex] = emb.embeddingId
        }

        if (embeddingsMap.size < 2) {
            // 仅一张照片有 face：直接建单簇
            if (embeddingsMap.size == 1) {
                val singleMediaId = embeddingsMap.keys.first()
                val personId = personDao.insertPerson(
                    com.mamba.picme.data.local.entity.PersonEntity(
                        faceCount = embeddingsMap[singleMediaId]!!.size,
                        coverMediaId = singleMediaId
                    )
                )
                dao.updateFaceId(singleMediaId, personId.toString())
                personDao.assignEmbeddingByMediaId(mediaId = singleMediaId, personId = personId)
                Log.i(TAG, "DBSCAN: single media with faces -> personId=$personId")
            }
            return
        }

        // 展平索引
        val fullFlatIndex = mutableListOf<Pair<Long, Int>>()
        for ((mediaId, faceEmbs) in embeddingsMap) {
            for (i in faceEmbs.indices) {
                fullFlatIndex.add(mediaId to i)
            }
        }

        // 分块（2026-10-02 OOM 修复）：未分配积压 > KNN_CHUNK_SIZE 时按序切块，
        // 块间以全人物质心快照续接（同人簇跨块复用既有 person，不因切块分裂）
        val chunked = fullFlatIndex.size > ClusteringConfig.KNN_CHUNK_SIZE
        val chunks = if (chunked) {
            Log.w(TAG, "Clustering chunked: ${fullFlatIndex.size} unassigned faces > " +
                "${ClusteringConfig.KNN_CHUNK_SIZE}, splitting into " +
                "${(fullFlatIndex.size + ClusteringConfig.KNN_CHUNK_SIZE - 1) / ClusteringConfig.KNN_CHUNK_SIZE} chunks")
            fullFlatIndex.chunked(ClusteringConfig.KNN_CHUNK_SIZE)
        } else {
            listOf(fullFlatIndex)
        }

        var totalAssigned = 0
        var totalReused = 0
        var totalNoise = 0
        var totalMergedNoise = 0
        for ((chunkIndex, flatIndex) in chunks.withIndex()) {
            // 块间续接：重读全人物质心（含未命名），让后块与前块产出的人物按质心合并
            val availableSnapshots = if (chunkIndex == 0) {
                namedPersonSnapshots.toMutableList()
            } else {
                buildAllPersonSnapshots().toMutableList()
            }

            // 诊断：分析 embedding 两两相似度分布
            logEmbeddingSimilarityDistribution(embeddingsMap, flatIndex)

            // 选择聚类策略：方案 B（密度自适应 k-NN 图聚类）或方案 A（DBSCAN）；
            // OOM 熔断：升级为会话级失败而非进程崩溃——避免 START_STICKY 自动续跑成崩溃循环
            val clusters = try {
                if (ClusteringConfig.USE_ADAPTIVE_CLUSTERING) {
                    AdaptiveFaceClusterer.cluster(
                        embeddingsMap = embeddingsMap,
                        flatIndex = flatIndex,
                        k = ClusteringConfig.KNN_K,
                        minSimilarity = ClusteringConfig.KNN_MIN_SIMILARITY,
                        minClusterSize = ClusteringConfig.KNN_MIN_CLUSTER_SIZE
                    ).also {
                        Log.i(TAG, "Adaptive k-NN clustering: ${it.size} cluster keys from ${flatIndex.size} face embeddings")
                    }
                } else {
                    var dbscanClusters = dbscanCluster(embeddingsMap, flatIndex, ClusteringConfig.DBSCAN_EPS, ClusteringConfig.DBSCAN_MIN_PTS)
                    Log.i(TAG, "DBSCAN: ${dbscanClusters.size} clusters from ${flatIndex.size} face embeddings")
                    // 验证簇内部一致性，分裂不健康的簇
                    validateAndSplitClusters(dbscanClusters, embeddingsMap)
                }
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "Clustering OOM (chunk faces=${flatIndex.size}, chunked=$chunked) — fail session, no crash loop", oom)
                throw IllegalStateException("Face clustering OOM at ${flatIndex.size} faces")
            }

        // 分配 personId：按簇批量写入，避免逐条 UPDATE 阻塞协程/线程
        val sorted = clusters.entries
            .filter { it.key != -1 }
            .sortedByDescending { it.value.size }

        // 预计算各簇质心，用于后续噪声点归并
        val clusterCentroids = sorted.associate { entry ->
            entry.key to computeClusterCentroid(entry.value, embeddingsMap)
        }

        Log.i(TAG, "DBSCAN assignment start: ${sorted.size} clusters to persist (chunk ${chunkIndex + 1}/${chunks.size})")
        var assignedCount = 0
        var reusedCount = 0
        val clusterKeyToPersonId = mutableMapOf<Int, Long>()
        db.withTransaction {
            for ((index, entry) in sorted.withIndex()) {
                val mediaIds = entry.value.map { it.first }.distinct()
                val totalFaces = entry.value.size
                val centroid = clusterCentroids[entry.key]
                Log.d(TAG, "DBSCAN persisting cluster #$index (key=${entry.key}, $totalFaces faces, ${mediaIds.size} media)")

                val personId = findOrCreatePersonForCluster(
                    mediaIds = mediaIds,
                    totalFaces = totalFaces,
                    centroid = centroid,
                    availableSnapshots = availableSnapshots
                )
                if (personId in namedPersonSnapshots.map { it.personId }) {
                    reusedCount++
                }
                clusterKeyToPersonId[entry.key] = personId
                Log.d(TAG, "DBSCAN assigned personId=$personId for cluster #$index")
                // 批量更新 media_assets.faceId（同一人物的所有媒体一次性写入）
                dao.updateFaceIdBatch(mediaIds, personId.toString())
                assignedCount += entry.value.size
                // 批量更新 face_embeddings.personId
                if (mediaIds.isNotEmpty()) {
                    personDao.assignEmbeddingsByMediaIds(mediaIds, personId)
                }
            }
        }

        // 处理噪声点：仅将明显归属于已有簇的边界点回收，
        // 不再为每个噪声点创建单例，避免照片较少的人脸产生过多碎片簇。
        val noisePoints = clusters[-1] ?: emptyList()
        var mergedNoiseCount = 0
        if (noisePoints.isNotEmpty()) {
            db.withTransaction {
                for ((mediaId, faceIndex) in noisePoints) {
                    val emb = embeddingsMap[mediaId]?.getOrNull(faceIndex) ?: continue
                    val embeddingId = embeddingIdMap[mediaId to faceIndex] ?: continue

                    // 找到最近簇质心
                    var bestKey: Int? = null
                    var bestSim = -1f
                    for ((key, centroid) in clusterCentroids) {
                        val sim = 1f - cosineDistance(emb, centroid)
                        if (sim > bestSim) {
                            bestSim = sim
                            bestKey = key
                        }
                    }

                    val mergeThreshold = if (ClusteringConfig.USE_ADAPTIVE_CLUSTERING) {
                        ClusteringConfig.KNN_MIN_SIMILARITY
                    } else {
                        1f - ClusteringConfig.DBSCAN_EPS
                    }
                    val targetPersonId = if (bestKey != null && bestSim >= mergeThreshold) {
                        clusterKeyToPersonId[bestKey]
                    } else null

                    if (targetPersonId != null) {
                        // 归并到已有簇
                        personDao.incrementFaceCount(targetPersonId)
                        personDao.assignEmbedding(embeddingId, targetPersonId)
                        val existingFaceId = dao.getFaceIdByMediaId(mediaId)
                        if (existingFaceId.isNullOrBlank()) {
                            dao.updateFaceId(mediaId, targetPersonId.toString())
                        }
                        mergedNoiseCount++
                    }
                }
            }
        }

        val noiseCount = noisePoints.size
        totalAssigned += assignedCount
        totalReused += reusedCount
        totalNoise += noiseCount
        totalMergedNoise += mergedNoiseCount
        } // chunk loop end

        Log.i(TAG, "DBSCAN done: $totalAssigned faces clustered" +
            "${if (chunked) " in ${chunks.size} chunks" else ""}, " +
            "reused=$totalReused, noise=$totalNoise (merged=$totalMergedNoise, remaining=${totalNoise - totalMergedNoise})")

        // 【关键修复】校验 hasFace 标记：清理有 hasFace=true 但无有效 embedding 的媒体
        // 这些媒体可能是之前误检（RetinaFace 误报）或零向量过滤后的残留
        cleanupInvalidHasFace(dao)
        backfillFaceFocus(dao)
    }

    /**
     * 为新聚类簇寻找或创建对应的人物记录。
     *
     * 优先将新簇质心与 [availableSnapshots] 中已命名人质的心做余弦相似度匹配，
     * 相似度 ≥ [ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY] 时复用旧 personId 与 name，
     * 否则插入新人物。每个旧人物最多被复用一次。
     */
    private suspend fun findOrCreatePersonForCluster(
        mediaIds: List<Long>,
        totalFaces: Int,
        centroid: FloatArray?,
        availableSnapshots: MutableList<NamedPersonSnapshot>
    ): Long {
        val coverMediaId = mediaIds.firstOrNull()

        // 没有可用快照或无法计算质心时直接新建
        if (centroid == null || availableSnapshots.isEmpty()) {
            return personDao.insertPerson(
                com.mamba.picme.data.local.entity.PersonEntity(
                    faceCount = totalFaces,
                    coverMediaId = coverMediaId
                )
            )
        }

        var bestIndex = -1
        var bestSim = -1f
        availableSnapshots.forEachIndexed { index, snapshot ->
            val sim = 1f - cosineDistance(centroid, snapshot.centroid)
            if (sim > bestSim) {
                bestSim = sim
                bestIndex = index
            }
        }

        if (bestIndex < 0 || bestSim < ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY) {
            // 保名未命中诊断（2026-10-12 名字清空事故）：快照在但没匹配上时必须留下现场
            Log.w(TAG, "Name preserve MISS: snapshotCount=${availableSnapshots.size}, " +
                "bestSim=${String.format("%.3f", bestSim)}, " +
                "threshold=${ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY}, " +
                "clusterFaces=$totalFaces -> new person created")
        }
        return if (bestIndex >= 0 && bestSim >= ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY) {
            val snapshot = availableSnapshots.removeAt(bestIndex)
            if (personDao.getPerson(snapshot.personId) == null) {
                // 全量重聚已清表：UPDATE 是 no-op，必须按原 personId 显式重建人物行，
                // 否则 name/is_self 丢失、face_embeddings/person_relations 指向悬挂 id
                personDao.insertPerson(
                    com.mamba.picme.data.local.entity.PersonEntity(
                        personId = snapshot.personId,
                        name = snapshot.name,
                        faceCount = totalFaces,
                        coverMediaId = coverMediaId,
                        isSelf = snapshot.isSelf
                    )
                )
            } else {
                // 增量聚类：人物行仍在，仅更新统计
                personDao.updatePersonStats(
                    personId = snapshot.personId,
                    faceCount = totalFaces,
                    coverMediaId = coverMediaId
                )
            }
            Log.i(TAG, "Reused named person id=${snapshot.personId} name=${snapshot.name} " +
                "for new cluster (sim=${String.format("%.3f", bestSim)})")
            snapshot.personId
        } else {
            personDao.insertPerson(
                com.mamba.picme.data.local.entity.PersonEntity(
                    faceCount = totalFaces,
                    coverMediaId = coverMediaId
                )
            )
        }
    }

    /**
     * 清理无效的 hasFace 标记
     *
     * 对 hasFace=true 但 face_embeddings 表中没有对应记录的照片，
     * 重置 hasFace=false 并清除 faceRoiResult，避免误检照片进入人脸分组。
     */
    private suspend fun cleanupInvalidHasFace(dao: com.mamba.picme.data.local.MediaDao) {
        val allHasFaceIds = dao.getHasFaceIds()
        val mediaWithEmbeddings = personDao.getAllEmbeddings().map { it.mediaId }.toSet()

        var cleanedCount = 0
        allHasFaceIds.chunked(CLEANUP_BATCH_SIZE).forEach { batchIds ->
            val batchEntities = dao.getMediaByIds(batchIds)
            for (media in batchEntities) {
                if (media.id !in mediaWithEmbeddings) {
                    // 无有效 embedding：重置 hasFace 并清除 faceRoiResult
                    dao.updateFaceRoiResult(media.id, "", false)
                    cleanedCount++
                    Log.w(TAG, "Cleanup invalid hasFace: mediaId=${media.id} has no valid embedding, reset hasFace=false")
                }
            }
        }

        if (cleanedCount > 0) {
            Log.w(TAG, "Cleanup invalid hasFace: $cleanedCount media reset from hasFace=true to false")
        }
    }

    /**
     * 一次性回填老照片的 faceFocusY（hasFace=1 但 faceFocusY IS NULL）。
     *
     * 仅做轻量人脸检测算纵向聚焦点，不重提 embedding / 不重算 MobileCLIP。
     * 幂等：已回填的（faceFocusY NOT NULL）自动跳过。挂在聚类流程末尾，随扫描渐进覆盖。
     */
    private suspend fun backfillFaceFocus(dao: com.mamba.picme.data.local.MediaDao) {
        val pending = dao.getMediaWithFacesWithoutFocus()
        if (pending.isEmpty()) return
        Log.i(TAG, "Backfilling faceFocusY for ${pending.size} media")
        for (media in pending) {
            currentCoroutineContext().ensureActive()
            val focusY = pipeline.detectFaceFocusY(media.uri) ?: continue
            dao.updateFaceFocusY(media.id, focusY)
        }
        Log.i(TAG, "faceFocusY backfill done")
    }

    /**
     * 验证簇内部一致性：计算簇内所有点对的平均余弦相似度。
     * 低于 ClusteringConfig.CLUSTER_COHESION_MIN 则用更严格的 eps 递归分裂。
     */
    private fun validateAndSplitClusters(
        clusters: Map<Int, List<Pair<Long, Int>>>,
        embeddings: Map<Long, List<FloatArray>>
    ): Map<Int, List<Pair<Long, Int>>> {
        val result = mutableMapOf<Int, List<Pair<Long, Int>>>()
        for ((clusterId, members) in clusters) {
            if (clusterId == -1 || members.size <= 2) {
                result[clusterId] = members
                continue
            }
            val sampleSize = minOf(members.size, 20)
            var totalSim = 0f
            var pairCount = 0
            for (i in 0 until sampleSize) {
                for (j in i + 1 until sampleSize) {
                    val embI = embeddings[members[i].first]?.getOrNull(members[i].second) ?: continue
                    val embJ = embeddings[members[j].first]?.getOrNull(members[j].second) ?: continue
                    totalSim += 1f - cosineDistance(embI, embJ)
                    pairCount++
                }
            }
            if (pairCount == 0) {
                result[clusterId] = members
                continue
            }
            val avgSimilarity = totalSim / pairCount
            Log.d(TAG, "Cluster $clusterId (${members.size} faces) avg similarity: ${String.format("%.3f", avgSimilarity)}")
            if (avgSimilarity < ClusteringConfig.CLUSTER_COHESION_MIN) {
                Log.w(TAG, "Cluster $clusterId cohesion too low (${String.format("%.3f", avgSimilarity)}), splitting")
                val subClusters = dbscanCluster(embeddings, members, ClusteringConfig.DBSCAN_EPS * 0.75f, ClusteringConfig.DBSCAN_MIN_PTS)
                var newId = clusterId * 1000
                for ((_, subMembers) in subClusters) {
                    result[newId++] = subMembers
                }
            } else {
                result[clusterId] = members
            }
        }
        return result
    }

    /** DBSCAN 核心算法 */
    private fun dbscanCluster(
        embeddings: Map<Long, List<FloatArray>>,
        flatIndex: List<Pair<Long, Int>>,
        eps: Float,
        minPts: Int
    ): Map<Int, List<Pair<Long, Int>>> {
        val n = flatIndex.size
        val labels = IntArray(n) { 0 }
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != 0) continue

            val centerEmb = embeddings[flatIndex[i].first]?.getOrNull(flatIndex[i].second) ?: continue
            val neighbors = mutableListOf<Int>()
            for (j in 0 until n) {
                if (i == j) continue
                val otherEmb = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: continue
                if (cosineDistance(centerEmb, otherEmb) <= eps) {
                    neighbors.add(j)
                }
            }

            if (neighbors.size < minPts) {
                labels[i] = -1
                continue
            }

            clusterId++
            labels[i] = clusterId
            val seedSet = neighbors.toMutableList()

            var idx = 0
            while (idx < seedSet.size) {
                val q = seedSet[idx]
                if (labels[q] == -1) labels[q] = clusterId
                if (labels[q] == 0) {
                    labels[q] = clusterId
                    val qEmb = embeddings[flatIndex[q].first]?.getOrNull(flatIndex[q].second) ?: run { idx++; continue }
                    val qn = mutableListOf<Int>()
                    for (j in 0 until n) {
                        if (q == j) continue
                        val otherEmb = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: continue
                        if (cosineDistance(qEmb, otherEmb) <= eps) {
                            qn.add(j)
                        }
                    }
                    if (qn.size >= minPts) {
                        for (ni in qn) {
                            if (ni !in seedSet) seedSet.add(ni)
                        }
                    }
                }
                idx++
            }
        }

        val result = mutableMapOf<Int, MutableList<Pair<Long, Int>>>()
        for (i in 0 until n) {
            val l = labels[i]
            result.getOrPut(l) { mutableListOf() }.add(flatIndex[i])
        }
        return result
    }

    /**
     * 诊断：统计所有 embedding 两两余弦相似度的分布。
     *
     * 采样计算（最多 3000 对），输出最小/最大/平均/标准差和直方图，
     * 用于判断 embedding 是否有区分度。
     */
    private fun logEmbeddingSimilarityDistribution(
        embeddings: Map<Long, List<FloatArray>>,
        flatIndex: List<Pair<Long, Int>>
    ) {
        if (flatIndex.size < 2) return

        val random = java.util.Random(42)
        val similarities = mutableListOf<Float>()
        val maxSamples = 3000
        var attempts = 0
        val totalPairs = flatIndex.size * (flatIndex.size - 1L) / 2

        while (similarities.size < maxSamples && attempts < maxSamples * 3 && totalPairs > 0) {
            val i = random.nextInt(flatIndex.size)
            var j = random.nextInt(flatIndex.size)
            if (i == j) {
                attempts++
                continue
            }
            val embI = embeddings[flatIndex[i].first]?.getOrNull(flatIndex[i].second) ?: run { attempts++; continue }
            val embJ = embeddings[flatIndex[j].first]?.getOrNull(flatIndex[j].second) ?: run { attempts++; continue }
            val sim = 1f - cosineDistance(embI, embJ)
            similarities.add(sim)
            attempts++
        }

        if (similarities.isEmpty()) return

        similarities.sort()
        val minSim = similarities.first()
        val maxSim = similarities.last()
        val meanSim = similarities.average().toFloat()
        val variance = similarities.map { (it - meanSim) * (it - meanSim) }.average().toFloat()
        val stdSim = kotlin.math.sqrt(variance)

        // 直方图：按相似度区间统计
        val buckets = IntArray(10) // [0,0.1), [0.1,0.2), ..., [0.9,1.0]
        for (sim in similarities) {
            val idx = (sim * 10).toInt().coerceIn(0, 9)
            buckets[idx]++
        }

        Log.i(TAG, "Embedding similarity distribution: pairs=${similarities.size}, " +
            "min=${String.format("%.3f", minSim)}, max=${String.format("%.3f", maxSim)}, " +
            "mean=${String.format("%.3f", meanSim)}, std=${String.format("%.3f", stdSim)}")
        Log.i(TAG, "Similarity histogram: ${buckets.joinToString(" ") { "%.1f:%d".format(it / 10.0, it) }}")
    }

    /** 余弦距离: 1 - cosine_similarity，范围 [0, 2] */
    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val similarity = dot / (sqrt(normA) * sqrt(normB))
        return (1f - similarity).coerceAtLeast(0f)
    }

    /** 计算簇的质心（成员 embedding 的算术平均） */
    private fun computeClusterCentroid(
        members: List<Pair<Long, Int>>,
        embeddings: Map<Long, List<FloatArray>>
    ): FloatArray {
        val dim = embeddings.values.firstOrNull()?.firstOrNull()?.size ?: 512
        val sum = FloatArray(dim)
        var count = 0
        for ((mediaId, faceIndex) in members) {
            val emb = embeddings[mediaId]?.getOrNull(faceIndex) ?: continue
            for (i in emb.indices) {
                sum[i] += emb[i]
            }
            count++
        }
        if (count == 0) return FloatArray(dim)
        for (i in sum.indices) {
            sum[i] /= count
        }
        return sum
    }

    // ═══════════════════════════════════════════════════
    //  序列化辅助
    // ═══════════════════════════════════════════════════

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

    private fun unifiedTagToJson(result: UnifiedTagResult): String {
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

    /** 解析 [unifiedTagToJson] 产出的统一标签 JSON 回 [UnifiedTagResult]。 */
    private fun jsonToUnifiedTag(json: String): UnifiedTagResult {
        val obj = JSONObject(json)
        val faceObj = obj.optJSONObject("face")
        val face = FaceTagInfo(
            count = faceObj?.optInt("count") ?: 0,
            selfie = faceObj?.optBoolean("selfie") ?: false,
            groupPhoto = faceObj?.optBoolean("groupPhoto") ?: false,
            personIds = faceObj?.optJSONArray("personIds")
                ?.let { arr -> (0 until arr.length()).map { arr.getLong(it) } }
                ?: emptyList()
        )
        return UnifiedTagResult(
            face = face,
            scene = obj.optString("scene"),
            activity = obj.optString("activity"),
            objects = obj.optJSONArray("objects")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            tags = obj.optJSONArray("tags")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                ?: emptyList(),
            summary = obj.optString("summary")
        )
    }

    /**
     * 按双字段规范持久化统一标签（[enJson] 为英文原语 JSON）：
     * labelsEn=英文原语；labelsZh=由 [LabelSinicizer] 离线汉化派生；labels 作 labelsZh 别名。
     *
     * @return 与当前 UI 语言匹配的 JSON（英文/西语/法语 UI→enJson，其余→zhJson），供调用方即时展示
     */
    private suspend fun persistUnifiedTags(mediaId: Long, enJson: String): String {
        val zhJson = unifiedTagToJson(labelSinicizer.sinicize(jsonToUnifiedTag(enJson)))
        val dao = db.mediaDao()
        dao.updateLabelsEn(mediaId, enJson)
        dao.updateLabelsZh(mediaId, zhJson)
        dao.updateLabels(mediaId, zhJson)
        return when (userSettingsRepository.getAppLanguageBlocking()) {
            AppLanguage.ENGLISH, AppLanguage.SPANISH, AppLanguage.FRENCH -> enJson
            else -> zhJson
        }
    }

    private suspend fun ensureModelLoaded(): Boolean {
        val orchestrator = AgentOrchestrator.getInstance()
        val engine = AndroidAgentComposition.localLlmEngine

        if (!engine.isModelAvailable(taggerModelKey)) {
            Log.w(TAG, "Model not downloaded: $taggerModelKey")
            return false
        }

        // 由 OpenClGuardian 决定使用 OpenCL 还是 CPU（含黑名单、降级冷却、用户偏好）
        val useCpu = openClGuardian.shouldUseCpu()

        // 如果已按 Guardian 策略加载了正确后端，直接复用，避免 Pass 3 每张照片都卸载重装。
        if (engine.isLoaded && engine.isLoadedAs(taggerModelKey, useOpencl = !useCpu)) {
            Log.i(TAG, "Model already loaded with requested backend, reusing")
            return true
        }

        // 如果已加载但后端不匹配（如 OpenCL 与 CPU 切换），先完整卸载再重新加载。
        if (engine.isLoaded) {
            Log.i(TAG, "Model loaded with different backend, unloading before reload")
            engine.unload()
            // unload 通过 backgroundScope.launch 投递到 modelDispatcher 异步执行，
            // 给 modelDispatcher 时间处理 unload 后再投递 loadModel
            delay(1000)
        }

        // OpenCL GPU 路径（如果允许）→ 失败后降级 CPU
        if (!useCpu) {
            Log.i(TAG, "Loading LLM model with OpenCL (GPU): $taggerModelKey")
            val openclResult = orchestrator.localModelService.ensureModelLoaded(
                modelId = taggerModelKey,
                useOpencl = true,
                caller = "TagGenerationScheduler:OpenCL"
            )
            if (openclResult.isSuccess) {
                Log.i(TAG, "Model loaded with OpenCL (GPU) acceleration")
                // 加载成功后执行轻量 warmup，验证 OpenCL 可实际推理
                val health = openClGuardian.warmup()
                if (health == OpenClHealth.Healthy) {
                    return true
                }
                Log.w(TAG, "OpenCL warmup failed ($health), falling back to CPU")
                engine.unload()
                delay(1000)
            } else {
                Log.w(TAG, "OpenCL load failed: ${openclResult.exceptionOrNull()?.message}, " +
                    "falling back to CPU")
            }
        }

        // CPU 加载（默认路径 + OpenCL 失败/降级）
        Log.i(TAG, "Loading LLM model with CPU: $taggerModelKey")
        val cpuResult = orchestrator.localModelService.ensureModelLoaded(
            modelId = taggerModelKey,
            useOpencl = false,
            caller = "TagGenerationScheduler:CPU"
        )
        return if (cpuResult.isSuccess) {
            Log.i(TAG, "Model loaded with CPU")
            true
        } else {
            Log.w(TAG, "CPU load failed: ${cpuResult.exceptionOrNull()?.message}")
            false
        }
    }

    // ═══════════════════════════════════════════════════
    //  原子任务执行器（供 TagScanOrchestrator 调用）
    // ═══════════════════════════════════════════════════

    /**
     * [原子任务] Pass 1：单张媒体的人脸检测 + Embedding 提取
     */
    /** 供美学/人脸画质打分用：扫描级人脸检测 + 5 点 landmarks（复用 pipeline，保证 landmarks5）。 */
    fun detectFacesForScoring(bitmap: android.graphics.Bitmap): List<FaceRoi> =
        pipeline.detectFacesWithLandmarks5(bitmap)

    suspend fun executeFaceDetection(mediaId: Long): Boolean {
        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return false

        val result = pipeline.stage1WithEmbeddings(
            uri = entity.uri,
            lensFacing = androidx.camera.core.CameraSelector.LENS_FACING_BACK,
            mediaId = entity.id
        )

        // 若任务已被取消，丢弃本次结果，避免取消后仍写入数据库
        currentCoroutineContext().ensureActive()

        // 【关键修复】只有当检测到有效 embedding 时才标记 hasFace=true
        val hasValidFace = result.faceRoiJson != null && result.embeddings.isNotEmpty()

        // 产出率哨兵：检测到人脸但 embedding 为空 → 人脸模型缺失/损坏的典型信号
        // （extractFeature 返回零向量被过滤）。静默降级曾导致 embedding/persons 全 0
        // 而任务全部显示 COMPLETED，这里累计计数供会话收尾时转为可见警告。
        if (result.faceRoiJson != null && !hasValidFace && result.faceDetected) {
            val failures = faceEmbeddingFailureCount.incrementAndGet()
            Log.w(TAG, "[Pass 1] Face detected but no embedding extracted " +
                "(face embedding model missing/broken?) mediaId=${entity.id}, totalFailures=$failures")
        }
        if (result.faceRoiJson != null) {
            dao.updateFaceRoiResult(entity.id, result.faceRoiJson, hasValidFace)
        } else if (entity.type == MediaType.PHOTO) {
            // 照片解码失败（loadBitmap 返回 null：损坏 / 本机不支持的格式 / URI 失效）。
            // 写哨兵使 faceRoiResult IS NULL 口径收敛，避免这批照片被增量 Pass 1 无限重选。
            Log.w(TAG, "[Pass 1] Photo decode failed; writing sentinel for mediaId=${entity.id}")
            dao.updateFaceRoiResult(entity.id, DECODE_FAILURE_ROI_JSON, false)
        }
        // 人脸纵向聚焦点（供列表缩略图纵向对齐）；无人脸时 result.faceFocusY == null，跳过
        val faceFocusY = result.faceFocusY
        if (faceFocusY != null) {
            dao.updateFaceFocusY(entity.id, faceFocusY)
        }
        // 视频不写：loadBitmap 对非图片返回 null 属预期行为，选片层已按 type=PHOTO 排除。

        // 先清除该媒体旧 embedding，避免全量重扫时产生重复记录
        personDao.deleteEmbeddingsByMedia(entity.id)

        // 批量写入 embedding，减少 Room WAL checkpoint 和事务开销。
        if (result.embeddings.isNotEmpty()) {
            val embeddingEntities = result.embeddings.map { embedding ->
                com.mamba.picme.data.local.entity.FaceEmbeddingEntity(
                    mediaId = entity.id,
                    personId = null,
                    embedding = floatArrayToByteArray(embedding)
                )
            }
            personDao.insertEmbeddings(embeddingEntities)
        }

        // MobileCLIP 语义编码已在 stage1WithEmbeddings 中复用 faceBitmap 完成
        val semanticEmbedding = result.semanticEmbedding
        if (semanticEmbedding != null) {
            try {
                dao.updateSemanticEmbedding(entity.id, semanticEmbedding)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist semantic embedding for media ${entity.id}: ${e.message}")
            }
        }

        // 固定轮询间隔（TagScanOrchestrator.POLL_INTERVAL_MS）已提供任务间散热间隙，
        // 原子任务内部不再额外节流，避免把 Pass 1 实际推理时间放大数倍。

        return hasValidFace
    }

    /**
     * Pass1 流式攒批聚类：把当前未分配的 embedding 流式归类，返回本次归类的 embedding 数。
     * 异常被吞并记录日志，绝不影响 Pass1 主流程（DBSCAN 末批会兜底）。 */
    suspend fun runStreamingClusterBatch(): Int {
        return try {
            faceClusterEngine.assignStoredEmbeddings()
        } catch (e: Exception) {
            Log.w(TAG, "Streaming cluster batch failed (will rely on DBSCAN): ${e.message}")
            0
        }
    }

    /**
     * [原子任务] Pass 2：人脸聚类（密度自适应 / DBSCAN）
     *
     * @param preserveNamedPersons 是否在聚类前保存已命名人物质心，
     *                             并在聚类后尝试复用 personId/name。
     * @param isFullRescan 是否全量重聚类。为 true 时会在捕获快照后清空旧人物簇、
     *                     重置 embedding 分配并清除媒体上的 faceId。
     */
    suspend fun executeDbscan(preserveNamedPersons: Boolean = false, isFullRescan: Boolean = false) {
        val snapshots = if (preserveNamedPersons) buildNamedPersonSnapshots() else emptyList()
        // 关系表随人物快照一起导出：clearAllPersons 会 CASCADE 清空 person_relations
        val relationSnapshots = if (preserveNamedPersons) buildRelationSnapshots() else emptyList()
        executeDbscanInternal(snapshots, relationSnapshots, isFullRescan)
    }

    /**
     * 重聚类主体（命名/关系快照已在外部建好）。供 [executeDbscan] 与 [reembedFacesAndRecluster] 复用。
     *
     * isFullRescan=true 时清空旧 persons/embedding 分配/faceId 后用 [runDbscanClustering] 重聚；
     * [namedSnapshots] 用于复用 personId/name，[relationSnapshots] 用于恢复人物关系。
     */
    private suspend fun executeDbscanInternal(
        namedSnapshots: List<NamedPersonSnapshot>,
        relationSnapshots: List<RelationSnapshotEntry>,
        isFullRescan: Boolean
    ) {
        val dao = db.mediaDao()
        if (isFullRescan) {
            Log.i(TAG, "DBSCAN full rescan: clearing old persons/assignments/faceIds after snapshot capture")
            db.personDao().clearAllPersons()
            db.personDao().resetAllEmbeddingAssignments()
            dao.resetAllFaceIds()
        }

        runDbscanClustering(dao, namedSnapshots)

        // 保名兜底（2026-10-02 名字/关系清空事故）：聚类期「逐簇消费快照」的质心匹配
        // 对小命名簇会整体漏掉（簇构成随参数漂移 → 失配 → 名字被清后无人认领，
        // 6 个命名+isSelf+全部关系一次全灭实证）。此处对未复用快照做全局二次匹配重挂。
        reattachUnpreservedNames(namedSnapshots)

        if (relationSnapshots.isNotEmpty()) {
            restorePersonRelations(relationSnapshots)
        }

        // 对齐 persons 表：增量聚类/历史数据可能留下孤儿人物、faceCount 失配、悬空封面。
        db.personDao().reconcilePersons(System.currentTimeMillis())

        // 聚类维护：先拆分（修 k-NN 桥接把两个不同的人并成一组）再合并（修 faceId 冻结把同一人拆成多组）。
        val maintained = faceClusterEngine.runClusterMaintenance()
        if (maintained > 0) {
            Log.i(TAG, "Post-cluster maintenance: $maintained change(s) (split+merge)")
            db.personDao().reconcilePersons(System.currentTimeMillis())
        }
    }

    /**
     * 重提「已有人脸」照片的 embedding（走对齐路径）并全量重聚类——治本低质 embedding
     * （旧无对齐产物 / landmarks 缺失回退）导致的拆组与错并（如 140）。
     *
     * 仅重处理 hasFace 媒体（不重跑无人脸的，约全量 1/7）。**保名**：重提前 personId 还在，
     * 先建命名/关系快照；重提会把 personId 清空，再用快照走 [executeDbscanInternal] 复用 name/关系。
     *
     * @param onProgress 进度回调 (已处理, 总数)，每 50 张及末尾触发。
     */
    suspend fun reembedFacesAndRecluster(onProgress: (processed: Int, total: Int) -> Unit = { _, _ -> }) {
        // 防止并发/重复触发：用户快速连点「重新聚类」或自动扫描与手动重聚类并发，
        // 会导致同一人被多次重提并重复分组。
        if (!reembedMutex.tryLock()) {
            Log.w(TAG, "reembedFacesAndRecluster: already running, ignore concurrent call")
            return
        }
        _isScanning.value = true
        try {
            // 1. 重提前建快照（personId 仍在）—— 重提后 personId 会被清空，届时再建就空了
            val namedSnapshots = buildNamedPersonSnapshots()
            val relationSnapshots = buildRelationSnapshots()
            Log.i(TAG, "reembedFacesAndRecluster: snapshotted ${namedSnapshots.size} named persons, ${relationSnapshots.size} relations")

            // 2. 逐张重提（executeFaceDetection 内部清旧 embedding 再插新对齐 embedding）
            val ids = db.mediaDao().getHasFaceMediaIds()
            Log.i(TAG, "reembedFacesAndRecluster: re-embedding ${ids.size} hasFace media (aligned)")
            for ((index, mediaId) in ids.withIndex()) {
                currentCoroutineContext().ensureActive()
                try {
                    executeFaceDetection(mediaId)
                } catch (e: Exception) {
                    Log.w(TAG, "reembed: failed mediaId=$mediaId: ${e.message}")
                }
                if ((index + 1) % 50 == 0 || index == ids.lastIndex) {
                    onProgress(index + 1, ids.size)
                    Log.i(TAG, "reembed progress: ${index + 1}/${ids.size}")
                }
            }

            // 3. 带预建快照全量重聚类（保名/保关系）
            executeDbscanInternal(namedSnapshots, relationSnapshots, isFullRescan = true)
            Log.i(TAG, "reembedFacesAndRecluster: done")
        } finally {
            _isScanning.value = false
            reembedMutex.unlock()
        }
    }

    /**
     * 导出全部人物关系为按名字 + isSelf 标记的快照（重聚清表前调用）。
     * 两端人物查不到（脏数据）的关系直接丢弃并打日志。
     */
    private suspend fun buildRelationSnapshots(): List<RelationSnapshotEntry> {
        val relations = personRepository.exportAllRelations()
        if (relations.isEmpty()) return emptyList()

        val personsById = personDao.getAllPersons().associateBy { person -> person.personId }
        val snapshots = mutableListOf<RelationSnapshotEntry>()
        for (relation in relations) {
            val subject = personsById[relation.subjectPersonId]
            val obj = personsById[relation.objectPersonId]
            if (subject == null || obj == null) {
                Log.w(TAG, "Relation snapshot dropped: relationId=${relation.relationId} " +
                    "references missing person (subject=${relation.subjectPersonId}, object=${relation.objectPersonId})")
                continue
            }
            snapshots.add(
                RelationSnapshotEntry(
                    subjectName = subject.name,
                    subjectIsSelf = subject.isSelf,
                    objectName = obj.name,
                    objectIsSelf = obj.isSelf,
                    predicate = relation.predicate,
                    source = relation.source,
                    customLabel = relation.customLabel
                )
            )
        }
        Log.i(TAG, "Built ${snapshots.size} person relation snapshots for re-clustering preservation")
        return snapshots
    }

    /**
     * 重聚收尾：按名字 / isSelf 标记把关系快照解析回新 personId 并批量写回。
     * 无法解析的（人物在重聚中消失）打日志丢弃。
     */
    private suspend fun restorePersonRelations(snapshots: List<RelationSnapshotEntry>) {
        // 预取重聚后的 persons，映射保持纯函数（lambda 内不能调 suspend DAO）
        val persons = personDao.getAllPersons()
        val selfPersonId = persons.firstOrNull { person -> person.isSelf }?.personId
        val plan = RelationSnapshotRestorer.buildRestorePlan(snapshots) { name, isSelf ->
            when {
                // SELF 以 is_self 标记为准，不依赖名字
                isSelf -> selfPersonId
                name.isNullOrBlank() -> null
                // 精确匹配，不用 LIKE 避免"宝"误中"小宝"
                else -> persons.firstOrNull { person -> person.name == name }?.personId
            }
        }

        if (plan.restored.isNotEmpty()) {
            personRepository.restoreRelations(
                plan.restored.map { resolved ->
                    PersonRelationEntity(
                        subjectPersonId = resolved.subjectPersonId,
                        objectPersonId = resolved.objectPersonId,
                        predicate = resolved.predicate,
                        source = resolved.source,
                        customLabel = resolved.customLabel
                    )
                }
            )
        }
        for (entry in plan.dropped) {
            Log.w(TAG, "Person relation dropped after re-clustering: " +
                "subject=${entry.subjectName}(self=${entry.subjectIsSelf}) " +
                "predicate=${entry.predicate} object=${entry.objectName}(self=${entry.objectIsSelf})")
        }
        Log.i(TAG, "Person relations restored: ${plan.restored.size}, dropped: ${plan.dropped.size}")
    }

    /**
     * 构建已命名人物质心快照，供全量重聚类时复用 personId/name。
     *
     * "我"本人（is_self = 1）即使未命名也进快照，否则重聚后 is_self 标记与
     * 指向"我"的人物关系将丢失。
     */
    private suspend fun buildNamedPersonSnapshots(): List<NamedPersonSnapshot> {
        val persons = personDao.getAllPersons().filter { !it.name.isNullOrBlank() || it.isSelf }
        if (persons.isEmpty()) return emptyList()

        val snapshots = mutableListOf<NamedPersonSnapshot>()
        for (person in persons) {
            val embeddings = personDao.getEmbeddingsByPerson(person.personId)
            if (embeddings.isEmpty()) continue
            val centroid = computeCentroid(embeddings.map { byteArrayToFloatArray(it.embedding) })
            snapshots.add(
                NamedPersonSnapshot(
                    personId = person.personId,
                    name = person.name,
                    centroid = centroid,
                    isSelf = person.isSelf
                )
            )
        }
        Log.i(TAG, "Built ${snapshots.size} named person snapshots for clustering preservation")
        return snapshots
    }

    /**
     * 保名兜底：把聚类期未被复用的命名/isSelf 快照，对重聚后的全量人物做
     * 全局最佳质心匹配并重挂名字与 is_self（目标须为未命名人物，避免覆盖已复用者）。
     * 关系快照按名字回挂，名字找回即关系自愈。
     */
    private suspend fun reattachUnpreservedNames(namedSnapshots: List<NamedPersonSnapshot>) {
        if (namedSnapshots.isEmpty()) return
        val persons = personDao.getAllPersons()
        val existingIds = persons.map { it.personId }.toSet()
        val missing = namedSnapshots.filter { it.personId !in existingIds }
        if (missing.isEmpty()) {
            Log.i(TAG, "Name reattachment: all ${namedSnapshots.size} snapshot(s) reused in-cluster")
            return
        }
        // 现存人物质心（仅未命名者可作为重挂目标）
        val candidates = mutableListOf<Pair<Long, FloatArray>>()
        for (person in persons) {
            if (!person.name.isNullOrBlank()) continue
            val embeddings = personDao.getEmbeddingsByPerson(person.personId)
            if (embeddings.isEmpty()) continue
            candidates.add(person.personId to computeCentroid(embeddings.map { byteArrayToFloatArray(it.embedding) }))
        }
        var reattached = 0
        for (snap in missing) {
            var bestId = -1L
            var bestSim = -1f
            for ((personId, centroid) in candidates) {
                val sim = 1f - cosineDistance(snap.centroid, centroid)
                if (sim > bestSim) {
                    bestSim = sim
                    bestId = personId
                }
            }
            if (bestId > 0 && bestSim >= ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY) {
                if (!snap.name.isNullOrBlank()) {
                    personDao.updatePersonName(bestId, snap.name)
                    reattached++
                }
                if (snap.isSelf) {
                    personDao.clearSelfFlags()
                    personDao.setSelf(bestId, true)
                }
                Log.i(TAG, "Name reattached: '${snap.name}' (self=${snap.isSelf}) -> person $bestId " +
                    "(sim=${String.format("%.3f", bestSim)})")
            } else {
                Log.w(TAG, "Name reattach MISS: '${snap.name}' self=${snap.isSelf}, " +
                    "bestSim=${String.format("%.3f", bestSim)} (candidates=${candidates.size}) — name lost")
            }
        }
        Log.i(TAG, "Name reattachment: $reattached/${missing.size} recovered")
    }

    /**
     * 全人物质心快照（含未命名）——分块聚类的块间续接用：
     * 后块簇质心与前块产出人物按 [ClusteringConfig.NAME_PRESERVE_MIN_SIMILARITY] 匹配置复用，
     * 同人的簇跨块合并到同一 person，不因切块而分裂。
     */
    private suspend fun buildAllPersonSnapshots(): List<NamedPersonSnapshot> {
        val persons = personDao.getAllPersons()
        if (persons.isEmpty()) return emptyList()
        val snapshots = mutableListOf<NamedPersonSnapshot>()
        for (person in persons) {
            val embeddings = personDao.getEmbeddingsByPerson(person.personId)
            if (embeddings.isEmpty()) continue
            val centroid = computeCentroid(embeddings.map { byteArrayToFloatArray(it.embedding) })
            snapshots.add(
                NamedPersonSnapshot(
                    personId = person.personId,
                    name = person.name,
                    centroid = centroid,
                    isSelf = person.isSelf
                )
            )
        }
        return snapshots
    }

    /** 计算一组 embedding 的算术平均质心 */
    private fun computeCentroid(embeddings: List<FloatArray>): FloatArray {
        if (embeddings.isEmpty()) return FloatArray(512)
        val dim = embeddings.first().size
        val sum = FloatArray(dim)
        for (emb in embeddings) {
            for (i in 0 until dim) {
                sum[i] += emb[i]
            }
        }
        for (i in 0 until dim) {
            sum[i] /= embeddings.size
        }
        return sum
    }

    /**
     * [原子任务] Pass 3：单张媒体的图像打标
     *
     * 质量优先方案：按 tagger 配置分流（默认 Florence-2 ORT，备选 Qwen3-VL / SmolVLM 等 MNN VLM）生成 scene/activity/objects/tags/summary。
     * 相比 ML Kit，标签语义更准确，能区分"纸"、"墙"等无意义背景与真实主体。
     */
    suspend fun executeImageTagging(mediaId: Long) {
        // 接回守卫：热 SEVERE / 电量危机时 ABORT，抛异常 → 任务 FAILED → handleTaskFailure 退避重试（自带散热窗口）。
        // 热 MODERATE / 电量低时 guardCheck 内部已 delay(getThrottleMs())，不抛异常。
        check(guardCheck()) {
            "[Pass 3] Guard ABORT (thermal/battery) mediaId=$mediaId"
        }

        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return

        val startMs = System.currentTimeMillis()

        // 可用性守卫（保留批量 fail-fast → FAILED → 退避重试语义）
        if (taggerModelKey == "florence2_base") {
            val tagger = florence2Tagger
            check(tagger != null && tagger.isInit) {
                "[Pass 3] Florence-2 not available for mediaId=$mediaId"
            }
        } else {
            check(ensureModelLoaded()) {
                "[Pass 3] Model not loaded for mediaId=$mediaId"
            }
        }

        // Stage-3 统一分流（与 retag 同源）：runStage3Unified 内部按 taggerModelKey
        // 选 Florence-2 / Qwen3-VL，保证单张/批量同模型同提示词。
        val stage3 = pipeline.runStage3Unified(entity.uri, entity.faceRoiResult)
        currentCoroutineContext().ensureActive()
        val faceInfo = parseFaceRoiForUnifiedResult(entity.faceRoiResult, entity.faceId)
        val unified = stage3.copy(face = faceInfo)

        // 恒英文：labelsEn 存英文原语；labelsZh 由 LabelSinicizer 离线汉化派生。
        persistUnifiedTags(entity.id, unifiedTagToJson(unified))

        Log.d(TAG, "[Benchmark] Pass 3 done: mediaId=$mediaId, " +
            "durationMs=${System.currentTimeMillis() - startMs}, tagger=" +
            if (taggerModelKey == "florence2_base") "Florence-2" else "Qwen")

        // Pass3 连续执行发热严重：每张推理后自适应散热（热状态越高间歇越长）。
        // SEVERE 及以上已由上面的 guardCheck ABORT 兜底，不会走到这里。
        delay(getPass3CooldownMs())
    }

    /**
     * 从 Pass 1 持久化的 faceRoiResult 恢复人脸上下文，并结合 Pass 2 写入的 faceId
     * 组装最终 labels.face 字段。
     */
    private fun parseFaceRoiForUnifiedResult(
        faceRoiJson: String?,
        faceId: String?
    ): FaceTagInfo {
        if (faceRoiJson.isNullOrEmpty()) return FaceTagInfo()
        return try {
            val obj = JSONObject(faceRoiJson)
            val personIds = faceId?.split(",")?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
            FaceTagInfo(
                count = obj.optInt("faceCount", 0),
                selfie = obj.optBoolean("isSelfie", false),
                groupPhoto = obj.optBoolean("isGroupPhoto", false),
                personIds = personIds
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse faceRoi JSON: ${e.message}")
            FaceTagInfo()
        }
    }

    /**
     * [单独重编码任务] 单张媒体的 MobileCLIP 语义编码。
     *
     * 注意：常规扫描已将该阶段内联合并到 Pass 1。此方法保留用于：
     * - 历史 [TagScanPass.MOBILE_CLIP_ENCODING] 任务兼容
     * - 单独对某张媒体重新生成语义编码
     */
    suspend fun executeMobileClipEncoding(mediaId: Long) {
        val dao = db.mediaDao()
        val entity = dao.getMediaById(mediaId) ?: return

        val embedding = pipeline.stage4MobileClipEncoding(entity.uri, entity.id)

        // 若任务已被取消，丢弃本次结果
        currentCoroutineContext().ensureActive()

        if (embedding != null) {
            dao.updateSemanticEmbedding(entity.id, embedding)
        }
    }

    /**
     * 批量 Pass 3 前准备：确保打标模型已加载
     */
    suspend fun prepareTaggerModel(): Boolean = ensureModelLoaded()

    /**
     * 预热 MobileCLIP 标签分类器。
     * 应在 Pass 3 会话开始前调用一次，避免每张图片都重复初始化/预计算文本 embedding。
     */
    fun warmUpMobileClipClassifier(): Boolean {
        return pipeline.warmUpMobileClipClassifier()
    }

    /**
     * 卸载 LLM 模型释放 ~4GB 内存
     *
     * 扫描完成后调用，防止后台服务持续占用内存和散热资源。
     * 使用 [LocalLlmEngine.trimMemory] 清理 KV cache，保留模型权重以
     * 供后续扫描复用。再次进入 Pass 3 时 [ensureModelLoaded] 会负责
     * 按用户偏好选择后端重新加载。
     */
    private fun unloadLlm() {
        try {
            val engine = AndroidAgentComposition.localLlmEngine
            if (engine.isLoaded) {
                Log.i(TAG, "Unloading LLM model to free memory")
                engine.trimMemory()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unload LLM: ${e.message}")
        }
    }
}
