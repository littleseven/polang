package com.mamba.picme.di

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.mamba.picme.agent.core.platform.voice.KeywordSpotterEngine
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.render.GlBeautyPreviewProviderFactory
import com.mamba.picme.beauty.api.BeautyProcessor
import com.mamba.picme.core.image.GpuBeautyProcessor
import com.mamba.picme.core.image.ImageProcessor
import com.mamba.picme.core.image.ImageProcessorImpl
import com.mamba.picme.core.common.Logger
import com.mamba.picme.core.image.ThumbnailCache
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.data.local.DedupHashDao
import com.mamba.picme.data.local.MediaDao
import com.mamba.picme.data.local.dao.PersonDao
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.beauty.api.facedetect.FaceDetectorFactory
import com.mamba.picme.data.local.MlKitOcrProcessor
import com.mamba.picme.agent.core.facade.AgentOrchestrator
import com.mamba.picme.data.indexing.IndexingTaskQueue
import com.mamba.picme.data.indexing.MediaIndexingWorker
import com.mamba.picme.data.indexing.MediaStoreObserver
import com.mamba.picme.data.preferences.UserPreferencesRepository
import com.mamba.picme.data.preferences.dataStore
import com.mamba.picme.data.repository.ChatImageStoreImpl
import com.mamba.picme.data.repository.MediaFeedbackRepository
import com.mamba.picme.data.repository.MediaFeedbackRepositoryImpl
import com.mamba.picme.data.repository.MediaRepositoryImpl
import com.mamba.picme.data.repository.OrganizeRepositoryImpl
import com.mamba.picme.data.repository.PhotoEditRecipeRepository
import com.mamba.picme.domain.aesthetic.AestheticScoreWorker
import com.mamba.picme.domain.repository.ChatImageStore
import com.mamba.picme.domain.repository.AndroidMediaRepository
import com.mamba.picme.domain.repository.OrganizeRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.search.ExplicitFirstSearchPipeline
import com.mamba.picme.domain.search.MediaFeedbackUseCase
import com.mamba.picme.domain.search.MediaSearchEngine
import com.mamba.picme.domain.search.QueryBuilder
import com.mamba.picme.domain.search.SemanticSearchEngine
import com.mamba.picme.domain.tag.ControlledVocab
import com.mamba.picme.domain.tag.i18n.BilingualVocab
import com.mamba.picme.domain.tag.i18n.ChineseQueryTranslator
import com.mamba.picme.domain.tag.i18n.OpusMtTranslator
import com.mamba.picme.domain.tag.i18n.TagTranslator
import com.mamba.picme.data.download.LlmModelDownloadManager
import com.mamba.picme.data.download.ModelPathConfig
import com.mamba.picme.domain.backup.BackupTagDataUseCase
import com.mamba.picme.domain.backup.RestoreTagDataUseCase
import com.mamba.picme.domain.backup.TagDataBackupRepository
import com.mamba.picme.domain.dedup.DedupScanController
import com.mamba.picme.domain.dedup.DedupScanner
import com.mamba.picme.domain.dedup.DedupTrashManager
import com.mamba.picme.domain.agent.capability.optimize.analyzer.HeuristicSceneAnalyzer
import com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateRenderer
import com.mamba.picme.domain.agent.capability.optimize.gacha.CandidateSampler
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeFeedbackLogger
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeGachaEngine
import com.mamba.picme.domain.agent.capability.optimize.gacha.OptimizeScorer
import com.mamba.picme.domain.agent.capability.optimize.preset.AssetPresetRepository
import com.mamba.picme.domain.aesthetic.AestheticScorer
import com.mamba.picme.domain.aesthetic.NimaScorer
import com.mamba.picme.domain.agent.capability.ImageEditCapability
import com.mamba.picme.domain.usecase.AiOptimizeUseCase
import com.mamba.picme.domain.usecase.ChatEditProcessor
import com.mamba.picme.domain.usecase.SaveChatEditResultUseCase
import com.mamba.picme.domain.usecase.GetGallerySummaryUseCase
import com.mamba.picme.domain.usecase.GetGroupedMediaUseCase
import com.mamba.picme.domain.usecase.QueryGalleryMediaUseCase
import com.mamba.picme.domain.usecase.StartTagScanUseCase
import com.mamba.picme.domain.usecase.GenerateSummaryOnDemandUseCase
import com.mamba.picme.domain.usecase.OcrProcessor
import com.mamba.picme.features.chat.ChatEditStateHolder
import com.mamba.picme.features.chat.ChatViewModel
import com.mamba.picme.data.remote.picme.PoLangAuthClient
import com.mamba.picme.features.chat.ChatImageRenderer
import com.mamba.picme.features.chat.ChatOptimizeGachaController
import com.mamba.picme.features.chat.ChatViewModelDependencies
import com.mamba.picme.features.chat.PrefsClaudeSidStore
import com.mamba.picme.features.chat.buildAppToolExecutor
import com.mamba.picme.features.chat.capability.MemoryCapability
import com.mamba.picme.features.chat.capability.PersonRelationCapability
import com.mamba.picme.domain.matting.MattingEngine
import com.mamba.picme.domain.matting.MattingEngineImpl
import com.mamba.picme.domain.memory.MemoryRepository
import com.mamba.picme.domain.person.PersonQueryResolver
import com.mamba.picme.domain.person.PersonRepository
import com.mamba.picme.features.editor.PhotoEditorViewModelFactory
import com.mamba.picme.features.editor.RecipeApplier
import com.mamba.picme.features.idphoto.IDPhotoViewModelFactory
import com.mamba.picme.features.gallery.MediaViewModel
import com.mamba.picme.features.gallery.dedup.DedupMediaSource
import com.mamba.picme.features.gallery.dedup.DedupViewModel
import com.mamba.picme.features.gallery.dedup.MediaStoreDedupMediaSource
import com.mamba.picme.features.gallery.organize.OrganizeCategoryViewModel
import com.mamba.picme.features.gallery.memories.MemoriesViewModel
import com.mamba.picme.features.gallery.swipe.SwipeReviewViewModel
import com.mamba.picme.data.preferences.DataStoreMemoryHiddenStore
import com.mamba.picme.data.preferences.DataStoreSwipeKeepHistoryStore
import com.mamba.picme.domain.memories.MemoryHiddenStore
import com.mamba.picme.domain.swipe.SwipeKeepHistoryStore
import com.mamba.picme.domain.trash.DedupTrashBackend
import com.mamba.picme.domain.trash.TrashBackend
import androidx.lifecycle.ViewModel
import com.mamba.picme.domain.organize.OrganizeCategory
import com.mamba.picme.domain.tag.FaceClusterEngine
import com.mamba.picme.domain.tag.TagGenerationScheduler
import com.mamba.picme.domain.tag.TagScanProgress
import com.mamba.picme.domain.tag.scan.TagScanSessionProgress
import com.mamba.picme.data.indexing.MediaChangeEvent
import com.mamba.picme.service.tag.TagGenerationService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first

data class MediaViewModelDependencies(
    val repository: AndroidMediaRepository,
    val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    val ocrUseCase: OcrProcessor,
    val photoProcessor: PhotoProcessor,
    val faceDetector: FaceDetector,
    val generateSummaryOnDemandUseCase: GenerateSummaryOnDemandUseCase,
    val userSettingsRepository: UserSettingsRepository,
    val trashBackend: TrashBackend
)

class MediaViewModelFactory(
    private val dependencies: MediaViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MediaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MediaViewModel(
                repository = dependencies.repository,
                getGroupedMediaUseCase = dependencies.getGroupedMediaUseCase,
                ocrUseCase = dependencies.ocrUseCase,
                photoProcessor = dependencies.photoProcessor,
                faceDetector = dependencies.faceDetector,
                generateSummaryOnDemandUseCase = dependencies.generateSummaryOnDemandUseCase,
                userSettingsRepository = dependencies.userSettingsRepository,
                trashBackend = dependencies.trashBackend
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class DedupViewModelFactory(
    private val mediaSource: DedupMediaSource,
    private val scanner: DedupScanController,
    private val trashManager: DedupTrashManager,
    private val organizeRepository: OrganizeRepository,
    /** API < 30 无回收站授权接口，由调用方（MainActivity）注入旧删除流回调兜底。 */
    private val legacyDeleter: ((List<String>) -> Unit)?,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DedupViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DedupViewModel(
                mediaSource = mediaSource,
                scanner = scanner,
                trashManager = trashManager,
                organizeRepository = organizeRepository,
                legacyDeleter = legacyDeleter,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class ChatViewModelFactory(
    private val dependencies: ChatViewModelDependencies
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(dependencies) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/** 整理中心类目详情（F1）VM 工厂：TrashSessionController 在 VM 内构造，这里注入 backend 原料。 */
class OrganizeCategoryViewModelFactory(
    private val category: OrganizeCategory,
    private val organizeRepository: OrganizeRepository,
    private val trashManager: DedupTrashManager,
    private val silentTrashEnabled: suspend () -> Boolean = { false },
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OrganizeCategoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OrganizeCategoryViewModel(
                category = category,
                organizeRepository = organizeRepository,
                trashBackend = DedupTrashBackend(trashManager, silentTrashEnabled),
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/** 手势快速整理（F2）VM 工厂：同 F1，TrashSessionController 在 VM 内构造；附 KEEP 抑制历史存储。 */
class SwipeReviewViewModelFactory(
    private val organizeRepository: OrganizeRepository,
    private val trashManager: DedupTrashManager,
    private val keepHistoryStore: SwipeKeepHistoryStore,
    private val silentTrashEnabled: suspend () -> Boolean = { false },
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SwipeReviewViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SwipeReviewViewModel(
                organizeRepository = organizeRepository,
                trashBackend = DedupTrashBackend(trashManager, silentTrashEnabled),
                keepHistoryStore = keepHistoryStore,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

/** 回忆 carousel（F3）VM 工厂：媒体/人物 DAO + 隐藏集存储，VM 内 combine 生成。 */
class MemoriesViewModelFactory(
    private val mediaDao: MediaDao,
    private val personDao: PersonDao,
    private val hiddenStore: MemoryHiddenStore,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MemoriesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MemoriesViewModel(
                mediaDao = mediaDao,
                personDao = personDao,
                hiddenStore = hiddenStore,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

interface AppContainer {
    val repository: AndroidMediaRepository
    val userPreferencesRepository: UserSettingsRepository
    val imageProcessor: ImageProcessor
    val faceDetector: FaceDetector
    val llmModelDownloadManager: LlmModelDownloadManager
    val kwsEngine: KeywordSpotterEngine?
    val mediaSearchEngine: MediaSearchEngine
    val mediaIndexingWorker: MediaIndexingWorker
    /** TAG 生成调度器(单张 retag 走 Pass3 pipeline,与集中扫描同源) */
    val tagGenerationScheduler: TagGenerationScheduler
    /** 人脸聚类引擎（人物页进入时跑跨簇合并 pass，愈合同人拆组） */
    val faceClusterEngine: FaceClusterEngine
    /** 美学/人脸画质打分 + 封面刷新（eDifFIQA；独立后台） */
    val aestheticScoreWorker: AestheticScoreWorker
    /** TAG 生成扫描状态（只读，从 TagGenerationService 获取） */
    val tagGenerationIsScanning: kotlinx.coroutines.flow.StateFlow<Boolean>
    /** TAG 生成扫描进度（旧版兼容） */
    val tagGenerationProgress: kotlinx.coroutines.flow.StateFlow<TagScanProgress?>
    /** TAG 生成最后消息 */
    val tagGenerationLastMessage: kotlinx.coroutines.flow.StateFlow<String?>
    /** TAG 生成会话级增强进度 */
    val tagGenerationSessionProgress: kotlinx.coroutines.flow.StateFlow<TagScanSessionProgress?>
    /** 跨维度查询构建器（LLM 意图 → Room 查询） */
    val queryBuilder: QueryBuilder
    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    val thumbnailCache: ThumbnailCache

    val photoEditRecipeRepository: PhotoEditRecipeRepository
    val aiOptimizeUseCase: AiOptimizeUseCase

    /** 对话式图片编辑 Capability（全局注册） */
    val imageEditCapability: ImageEditCapability

    /** TAG 数据库备份用例 */
    val backupTagDataUseCase: BackupTagDataUseCase
    /** TAG 数据库还原用例 */
    val restoreTagDataUseCase: RestoreTagDataUseCase

    /** 相册摘要查询（chat JS handler / Debug 演示共用） */
    val getGallerySummaryUseCase: GetGallerySummaryUseCase
    /** 相册结构化查询（chat JS handler / Debug 演示共用） */
    val queryGalleryMediaUseCase: QueryGalleryMediaUseCase
    /** 人物/人脸聚类 DAO（chat JS handler face.cluster / Debug 演示共用） */
    val personDao: PersonDao
    /** Room 数据库（人物页封面解析等需读 media 表的场景共用） */
    val database: AppDatabase
    /** 人物领域仓库（命名 / "我"标记 / 人物关系图谱收口，命名对话框与聊天工具共用） */
    val personRepository: PersonRepository
    /** 通用事实记忆仓库（"帮我记住…"事实的收口，聊天工具 / JS 通路 / 设置页共用） */
    val memoryRepository: MemoryRepository

    /** 人物关系声明 Capability（CHAT 场景，聊天 remember/forget_person_relation 落点） */
    val personRelationCapability: PersonRelationCapability
    /** 事实记忆 Capability（CHAT 场景，聊天工具直调与 JS dispatch 共用落点） */
    val memoryCapability: MemoryCapability
    /** 受控词表（chat JS handler tag.audit 词表外标签比对 / Debug 演示共用） */
    val controlledVocab: ControlledVocab
    /** 服务端账号客户端（内含独立 OkHttpClient，进程级单例；设置页账号区与 chat 依赖共用） */
    val picMeAuthClient: PoLangAuthClient

    /** 去重 2.0：MD5/pHash 缓存 DAO（增量扫描） */
    val dedupHashDao: DedupHashDao
    /** 去重 2.0：分层扫描器（暂停/恢复/取消控制） */
    val dedupScanner: DedupScanner
    /** 去重 2.0：回收站删除/恢复授权管理 */
    val dedupTrashManager: DedupTrashManager

    /** 整理中心（F1）：Room + MediaStore 按 uri 合并的类目数据源 */
    val organizeRepository: OrganizeRepository

    fun createMediaViewModelFactory(): ViewModelProvider.Factory
    fun createChatViewModelFactory(): ViewModelProvider.Factory
    fun createPhotoEditorViewModelFactory(): ViewModelProvider.Factory

    fun createIDPhotoViewModelFactory(): ViewModelProvider.Factory

    /** 去重 2.0 主页 ViewModel 工厂；legacyDeleter 由持有 MediaViewModel 的调用方注入（API < 30 兜底） */
    fun createDedupViewModelFactory(
        legacyDeleter: ((List<String>) -> Unit)? = null
    ): ViewModelProvider.Factory

    /** 整理中心（F1）类目详情 ViewModel 工厂（按类目参数化） */
    fun createOrganizeCategoryViewModelFactory(category: OrganizeCategory): ViewModelProvider.Factory

    /** 手势快速整理（F2）ViewModel 工厂（无参数：队列由 VM 自建） */
    fun createSwipeReviewViewModelFactory(): ViewModelProvider.Factory

    /** 回忆 carousel（F3）ViewModel 工厂（无参数：数据源与隐藏集由容器注入） */
    fun createMemoriesViewModelFactory(): ViewModelProvider.Factory

    /** 创建 MediaStoreObserver（需要 ContentResolver，按需创建） */
    fun createMediaStoreObserver(onChange: (List<MediaChangeEvent>) -> Unit): MediaStoreObserver
}

class AppContainerImpl(
    private val context: Context,
    private val thumbnailCacheParam: ThumbnailCache
) : AppContainer {

    override val database by lazy { AppDatabase.getDatabase(context) }

    /** 双语词表（全局共享，避免重复加载） */
    private val bilingualVocab: BilingualVocab by lazy {
        BilingualVocab.loadFromAssets(context)
    }

    /** 受控词表（标签规范化 + 搜索同义词扩展） */
    override val controlledVocab: ControlledVocab by lazy {
        ControlledVocab.loadFromAssets(context)
    }

    override val personDao: PersonDao
        get() = database.personDao()

    override val personRepository: PersonRepository by lazy {
        PersonRepository(
            personDao = database.personDao(),
            relationDao = database.personRelationDao(),
            mediaDao = database.mediaDao()
        )
    }

    override val memoryRepository: MemoryRepository by lazy {
        MemoryRepository(memoryFactDao = database.memoryFactDao())
    }

    override val personRelationCapability: PersonRelationCapability by lazy {
        PersonRelationCapability(personRepository = personRepository)
    }

    override val memoryCapability: MemoryCapability by lazy {
        MemoryCapability(memoryRepository = memoryRepository)
    }

    /** OPUS-MT 翻译引擎（SentencePiece + ONNX Runtime） */
    private val opusMtTranslator: OpusMtTranslator by lazy {
        OpusMtTranslator(context)
    }

    /** 中文查询翻译器（注入 OPUS-MT 翻译引擎 + 受控词表） */
    private val chineseQueryTranslator: ChineseQueryTranslator by lazy {
        ChineseQueryTranslator(
            context = context,
            vocab = bilingualVocab,
            translator = opusMtTranslator,
            controlledVocab = controlledVocab
        )
    }

    /** 语义搜索引擎（MobileCLIP 跨模态检索，注入翻译器） */
    private val semanticSearchEngine: SemanticSearchEngine by lazy {
        SemanticSearchEngine(
            context = context,
            mediaDao = database.mediaDao(),
            queryTranslator = chineseQueryTranslator
        )
    }

    /** 显式约束优先搜索管道（注入翻译器支持跨语言扩展） */
    private val explicitFirstSearchPipeline: ExplicitFirstSearchPipeline by lazy {
        ExplicitFirstSearchPipeline(
            mediaDao = database.mediaDao(),
            personDao = database.personDao(),
            tagTranslator = TagTranslator(bilingualVocab, opusMtTranslator, controlledVocab)
        )
    }

    /** 人物查询解析器（人名/亲属称谓/“我” → personId，MediaSearchEngine 共现查询依赖） */
    private val personQueryResolver: PersonQueryResolver by lazy {
        PersonQueryResolver(personRepository)
    }

    /** 媒体搜索引擎（自然语言图片搜索） */
    override val mediaSearchEngine: MediaSearchEngine by lazy {
        MediaSearchEngine(
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            personDao = database.personDao(),
            userSettingsRepository = userPreferencesRepository,
            tagTranslator = TagTranslator(bilingualVocab, opusMtTranslator, controlledVocab),
            semanticSearchEngine = semanticSearchEngine,
            explicitFirstPipeline = explicitFirstSearchPipeline,
            mediaFeedbackUseCase = mediaFeedbackUseCase,
            personQueryResolver = personQueryResolver
        )
    }

    /** 跨维度查询构建器 */
    override val queryBuilder: QueryBuilder by lazy {
        QueryBuilder(
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            personDao = database.personDao(),
            userSettingsRepository = userPreferencesRepository,
            tagTranslator = TagTranslator(BilingualVocab.loadFromAssets(context), opusMtTranslator)
        )
    }

    /** 双级缩略图缓存（LRU 内存 + 磁盘） */
    override val thumbnailCache: ThumbnailCache = thumbnailCacheParam

    /** 图片反馈 Repository */
    private val mediaFeedbackRepository: MediaFeedbackRepository by lazy {
        MediaFeedbackRepositoryImpl(database.mediaFeedbackDao())
    }

    /** 图片反馈 UseCase */
    private val mediaFeedbackUseCase: MediaFeedbackUseCase by lazy {
        MediaFeedbackUseCase(mediaFeedbackRepository)
    }

    /** 媒体元数据索引器（ML Kit 标签+OCR+EXIF） */
    override val mediaIndexingWorker: MediaIndexingWorker by lazy {
        MediaIndexingWorker(context)
    }

    override val tagGenerationScheduler: TagGenerationScheduler by lazy {
        TagGenerationScheduler(context)
    }

    override val faceClusterEngine: FaceClusterEngine by lazy {
        FaceClusterEngine(context)
    }

    override val aestheticScoreWorker: AestheticScoreWorker by lazy {
        AestheticScoreWorker(context, tagGenerationScheduler, database)
    }

    /** TAG 生成扫描状态（从 TagGenerationService 获取） */
    override val tagGenerationIsScanning: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = TagGenerationService.isScanning

    /** TAG 生成扫描进度（旧版兼容） */
    override val tagGenerationProgress: kotlinx.coroutines.flow.StateFlow<TagScanProgress?>
        get() = TagGenerationService.progress

    /** TAG 生成最后消息 */
    override val tagGenerationLastMessage: kotlinx.coroutines.flow.StateFlow<String?>
        get() = TagGenerationService.lastScanMessage

    /** TAG 生成会话级增强进度 */
    override val tagGenerationSessionProgress: kotlinx.coroutines.flow.StateFlow<TagScanSessionProgress?>
        get() = TagGenerationService.sessionProgress

    /**
     * 创建 MediaStoreObserver。
     * 每次调用创建新实例，生命周期由调用方管理。
     */
    override fun createMediaStoreObserver(
        onChange: (List<MediaChangeEvent>) -> Unit
    ): MediaStoreObserver {
        return MediaStoreObserver(
            contentResolver = context.contentResolver,
            onChange = onChange
        )
    }

    /**
     * 静态 Bitmap 美颜处理器（拍照后 CPU 路径）。
     * ⚠️ 仅用于拍照后的静态图像后处理，与实时预览无关。
     * 实时预览美颜由 beauty-engine 模块的 BeautyPreviewEngine（GPU 路径）负责。
     */
    private val beautyProcessor: BeautyProcessor by lazy {
        GpuBeautyProcessor(context)
    }

    override val repository: AndroidMediaRepository by lazy {
        MediaRepositoryImpl(database.mediaDao(), context)
    }

    override val photoEditRecipeRepository: PhotoEditRecipeRepository by lazy {
        PhotoEditRecipeRepository(database.photoEditRecipeDao())
    }

    private val optimizeFeedbackLogger: OptimizeFeedbackLogger by lazy {
        OptimizeFeedbackLogger(database.optimizeFeedbackDao())
    }

    private val gachaAestheticScorer: AestheticScorer by lazy { NimaScorer(context) }

    private val gachaProcessingDispatcher by lazy {
        // 与编辑器同一约束：PhotoProcessor 内部 EGL 上下文必须单线程调用，
        // 线程池切换会导致 EGL 上下文失效而黑屏（见 AI_OPTIMIZATION.md §9.1）
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    }

    private val optimizeGachaEngine: OptimizeGachaEngine by lazy {
        OptimizeGachaEngine(
            sampler = CandidateSampler(),
            renderer = CandidateRenderer(
                context = context,
                // 独立 PhotoProcessor 实例：不与相机/编辑器共享 EGL 上下文（§9.1 教训）
                recipeApplier = RecipeApplier(photoProcessorFactory(context), gachaProcessingDispatcher)
            ),
            optimizeScorer = OptimizeScorer(gachaAestheticScorer),
            aestheticScorer = gachaAestheticScorer
        )
    }

    override val aiOptimizeUseCase: AiOptimizeUseCase by lazy {
        AiOptimizeUseCase(
            presetRepository = AssetPresetRepository(context),
            sceneAnalyzer = HeuristicSceneAnalyzer(context, faceDetector),
            gachaEngine = optimizeGachaEngine,
            feedbackLogger = optimizeFeedbackLogger
        )
    }

    private val tagDataBackupRepository: TagDataBackupRepository by lazy {
        TagDataBackupRepository(
            database = database,
            mediaDao = database.mediaDao(),
            tagDao = database.tagDao(),
            tagScanTaskDao = database.tagScanTaskDao(),
            personDao = database.personDao(),
            ocrWordDao = database.ocrWordDao(),
            locationDao = database.locationDao(),
            mediaFeedbackDao = database.mediaFeedbackDao(),
            chatMessageDao = database.chatMessageDao(),
            chatSessionDao = database.chatSessionDao(),
            personRelationDao = database.personRelationDao(),
            memoryFactDao = database.memoryFactDao(),
            photoEditRecipeDao = database.photoEditRecipeDao(),
            dataStore = context.dataStore
        )
    }

    override val backupTagDataUseCase: BackupTagDataUseCase by lazy {
        BackupTagDataUseCase(context, tagDataBackupRepository)
    }

    override val restoreTagDataUseCase: RestoreTagDataUseCase by lazy {
        RestoreTagDataUseCase(context, tagDataBackupRepository, backupTagDataUseCase)
    }

    override val userPreferencesRepository: UserSettingsRepository by lazy {
        UserPreferencesRepository(context)
    }

    override val faceDetector: FaceDetector by lazy {
        FaceDetectorFactory.create(context)
    }

    private val photoProcessor: PhotoProcessor by lazy {
        photoProcessorFactory(context)
    }

    private fun photoProcessorFactory(ctx: Context): PhotoProcessor {
        return GlBeautyPreviewProviderFactory().createPhotoProcessor(ctx)
    }

    private val chatEditStateHolder: ChatEditStateHolder by lazy {
        ChatEditStateHolder()
    }

    private val chatImageStore: ChatImageStore by lazy {
        ChatImageStoreImpl(context = context, dao = database.chatImageCacheDao())
    }

    private val saveChatEditResultUseCase: SaveChatEditResultUseCase by lazy {
        SaveChatEditResultUseCase(store = chatImageStore, chatMessageDao = database.chatMessageDao())
    }

    private val chatEditProcessor: ChatEditProcessor by lazy {
        ChatEditProcessor(
            photoProcessor = photoProcessor,
            faceDetector = faceDetector,
            chatImageStore = chatImageStore,
            userSettingsRepository = userPreferencesRepository
        )
    }

    override val imageEditCapability: ImageEditCapability by lazy {
        ImageEditCapability(
            context = context,
            chatEditProcessor = chatEditProcessor,
            stateHolder = chatEditStateHolder
        )
    }

    override val imageProcessor: ImageProcessor by lazy {
        ImageProcessorImpl(beautyProcessor, photoProcessor, faceDetector)
    }

    override val llmModelDownloadManager: LlmModelDownloadManager by lazy {
        LlmModelDownloadManager(context)
    }

    /**
     * KWS 唤醒词引擎（sherpa-onnx 版）
     *
     * 用于低功耗的 always-on 唤醒词检测。
     * 【重要】不允许自动降级，如果 KWS 初始化失败就直接抛异常！
     *
     * 【模型路径管理】
     * 使用 ModelPathConfig 统一管理模型路径，避免硬编码。
     * 支持灵活扩展新模型，只需在 ModelPathConfig 中添加配置。
     */
    override val kwsEngine: KeywordSpotterEngine? by lazy {
        val kwsModelDir = ModelPathConfig.getKwsModelDir(context)
        Logger.i("AppContainer", "【KWS 初始化】Starting KWS engine initialization...")
        Logger.i("AppContainer", "  Model path: ${kwsModelDir.absolutePath}")

        val missingFiles = ModelPathConfig.getMissingFiles(kwsModelDir, ModelPathConfig.KWS_MODEL_FILES)
        if (missingFiles.isNotEmpty()) {
            val errorMsg = buildString {
                append("❌ KWS 模型文件缺失，跳过 native 初始化以避免崩溃\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("  Missing files: ${missingFiles.joinToString(", ")}\n")
                append("  Expected ${ModelPathConfig.KWS_MODEL_FILES.size} files, " +
                    "found ${ModelPathConfig.KWS_MODEL_FILES.size - missingFiles.size}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        val emptyFiles = ModelPathConfig.KWS_MODEL_FILES.filter { fileName ->
            val file = java.io.File(kwsModelDir, fileName)
            file.exists() && file.length() == 0L
        }
        if (emptyFiles.isNotEmpty()) {
            val errorMsg = buildString {
                append("❌ KWS 模型文件为空（可能损坏），跳过 native 初始化以避免崩溃\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("  Empty files: ${emptyFiles.joinToString(", ")}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        Logger.i("AppContainer", "✓ KWS model files validated, creating KeywordSpotterEngine...")
        val kwsEngine = KeywordSpotterEngine(kwsModelDir.absolutePath)

        if (!kwsEngine.isAvailable()) {
            val errorMsg = buildString {
                append("❌ KWS 引擎初始化失败 - native 构造返回不可用\n")
                append("  Model dir: ${kwsModelDir.absolutePath}\n")
                append("【安全降级】返回 null，KWS 唤醒词功能不可用")
            }
            Logger.w("AppContainer", errorMsg)
            return@lazy null
        }

        Logger.i("AppContainer", "✓ KWS engine initialized successfully")
        Logger.i("AppContainer", "  Keywords: ${kwsEngine.getKeywords().joinToString(", ")}")

        kwsEngine
    }

    private val ocrProcessor: OcrProcessor by lazy {
        MlKitOcrProcessor()
    }

    override val getGallerySummaryUseCase: GetGallerySummaryUseCase by lazy {
        GetGallerySummaryUseCase(db = database)
    }

    override val queryGalleryMediaUseCase: QueryGalleryMediaUseCase by lazy {
        QueryGalleryMediaUseCase(db = database)
    }

    private val startTagScanUseCase: StartTagScanUseCase by lazy {
        StartTagScanUseCase(context = context)
    }

    val generateSummaryOnDemandUseCase: GenerateSummaryOnDemandUseCase by lazy {
        // 注入容器内单例 scheduler，使 on-demand 路径走与 Pass3/「重新打标」同源的统一规格管道，
        // 而非只写 summary 自然语言桩。两者均 by lazy，首次访问时才解析，无循环依赖。
        GenerateSummaryOnDemandUseCase(
            context = context,
            tagGenerationScheduler = tagGenerationScheduler
        )
    }

    private val mediaViewModelDependencies: MediaViewModelDependencies by lazy {
        MediaViewModelDependencies(
            repository = repository,
            getGroupedMediaUseCase = GetGroupedMediaUseCase(),
            ocrUseCase = ocrProcessor,
            photoProcessor = photoProcessor,
            faceDetector = faceDetector,
            generateSummaryOnDemandUseCase = generateSummaryOnDemandUseCase,
            userSettingsRepository = userPreferencesRepository,
            trashBackend = DedupTrashBackend(
                trashManager = dedupTrashManager,
                silentTrashEnabled = { userPreferencesRepository.mediaManageSilentTrashFlow.first() },
            )
        )
    }

    private val mediaViewModelFactory: ViewModelProvider.Factory by lazy {
        MediaViewModelFactory(mediaViewModelDependencies)
    }

    // ---------- 去重 2.0 ----------

    override val dedupHashDao: DedupHashDao
        get() = database.dedupHashDao()

    override val dedupScanner: DedupScanner by lazy {
        DedupScanner(context, dedupHashDao)
    }

    override val dedupTrashManager: DedupTrashManager by lazy {
        DedupTrashManager(context)
    }

    private val dedupMediaSource: DedupMediaSource by lazy {
        MediaStoreDedupMediaSource(context, repository)
    }

    override val organizeRepository: OrganizeRepository by lazy {
        OrganizeRepositoryImpl(
            context = context,
            mediaDao = database.mediaDao(),
            dedupHashDao = dedupHashDao,
            ioDispatcher = Dispatchers.IO,
        )
    }

    private val photoEditorViewModelFactory: ViewModelProvider.Factory by lazy {
        PhotoEditorViewModelFactory(
            appContext = context,
            photoProcessorFactory = ::photoProcessorFactory,
            faceDetector = faceDetector,
            recipeRepository = photoEditRecipeRepository,
            mediaRepository = repository,
            userSettingsRepository = userPreferencesRepository,
            aiOptimizeUseCase = aiOptimizeUseCase,
            downloadManager = llmModelDownloadManager,
            feedbackLogger = optimizeFeedbackLogger
        )
    }

    private val idPhotoViewModelFactory: ViewModelProvider.Factory by lazy {
        IDPhotoViewModelFactory(
            appContext = context,
            downloadManager = llmModelDownloadManager,
            mediaRepository = repository
        )
    }

    private val mattingEngine: MattingEngine by lazy {
        MattingEngineImpl(context, llmModelDownloadManager)
    }

    private val chatImageRenderer: ChatImageRenderer by lazy {
        ChatImageRenderer(
            context = context,
            photoProcessor = photoProcessor,
            mattingEngine = mattingEngine,
            optimizeUseCase = aiOptimizeUseCase,
            chatImageStore = chatImageStore,
            faceDetector = faceDetector,
            userSettingsRepository = userPreferencesRepository
        )
    }

    private val chatOptimizeGachaController: ChatOptimizeGachaController by lazy {
        ChatOptimizeGachaController(
            optimizeUseCase = aiOptimizeUseCase,
            chatImageRenderer = chatImageRenderer,
            chatImageStore = chatImageStore,
            feedbackLogger = optimizeFeedbackLogger,
            chatEditStateHolder = chatEditStateHolder
        )
    }

    override val picMeAuthClient: PoLangAuthClient by lazy { PoLangAuthClient() }

    private val chatViewModelDependencies: ChatViewModelDependencies by lazy {
        ChatViewModelDependencies(
            context = context,
            chatMessageDao = database.chatMessageDao(),
            chatSessionDao = database.chatSessionDao(),
            userSettingsRepository = userPreferencesRepository,
            mediaSearchEngine = mediaSearchEngine,
            mediaFeedbackRepository = mediaFeedbackRepository,
            mediaRepository = repository,
            picMeAuthClient = picMeAuthClient,
            getGallerySummaryUseCase = getGallerySummaryUseCase,
            queryGalleryMediaUseCase = queryGalleryMediaUseCase,
            startTagScanUseCase = startTagScanUseCase,
            personDao = database.personDao(),
            controlledVocab = controlledVocab,
            chatEditStateHolder = chatEditStateHolder,
            chatEditProcessor = chatEditProcessor,
            chatImageRenderer = chatImageRenderer,
            chatImageStore = chatImageStore,
            saveChatEditResultUseCase = saveChatEditResultUseCase,
            optimizeGachaController = chatOptimizeGachaController,
            tagGenerationScheduler = tagGenerationScheduler
        ).also { deps ->
            // app_tool_request 采集执行器：工厂依赖容器内数据源，构造后回填（先于 ChatViewModel 创建）
            deps.appToolExecutor = buildAppToolExecutor(deps)
            deps.claudeSidStore = PrefsClaudeSidStore(deps.context)
        }
    }

    private val chatViewModelFactory: ViewModelProvider.Factory by lazy {
        ChatViewModelFactory(chatViewModelDependencies)
    }

    override fun createMediaViewModelFactory(): ViewModelProvider.Factory {
        return mediaViewModelFactory
    }

    override fun createDedupViewModelFactory(
        legacyDeleter: ((List<String>) -> Unit)?
    ): ViewModelProvider.Factory {
        return DedupViewModelFactory(
            mediaSource = dedupMediaSource,
            scanner = dedupScanner,
            trashManager = dedupTrashManager,
            organizeRepository = organizeRepository,
            legacyDeleter = legacyDeleter,
        )
    }

    override fun createPhotoEditorViewModelFactory(): ViewModelProvider.Factory {
        return photoEditorViewModelFactory
    }

    override fun createOrganizeCategoryViewModelFactory(
        category: OrganizeCategory
    ): ViewModelProvider.Factory {
        return OrganizeCategoryViewModelFactory(
            category = category,
            organizeRepository = organizeRepository,
            trashManager = dedupTrashManager,
            silentTrashEnabled = { userPreferencesRepository.mediaManageSilentTrashFlow.first() },
        )
    }

    override fun createSwipeReviewViewModelFactory(): ViewModelProvider.Factory {
        return SwipeReviewViewModelFactory(
            organizeRepository = organizeRepository,
            trashManager = dedupTrashManager,
            keepHistoryStore = DataStoreSwipeKeepHistoryStore(context),
            silentTrashEnabled = { userPreferencesRepository.mediaManageSilentTrashFlow.first() },
        )
    }

    override fun createMemoriesViewModelFactory(): ViewModelProvider.Factory {
        return MemoriesViewModelFactory(
            mediaDao = database.mediaDao(),
            personDao = database.personDao(),
            hiddenStore = DataStoreMemoryHiddenStore(context),
        )
    }

    override fun createIDPhotoViewModelFactory(): ViewModelProvider.Factory {
        return idPhotoViewModelFactory
    }

    override fun createChatViewModelFactory(): ViewModelProvider.Factory {
        return chatViewModelFactory
    }
}
