package com.mamba.picme.data.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import com.mamba.picme.domain.model.ModelCategory
import com.mamba.picme.domain.model.TagTranslations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * LLM 模型下载管理器
 *
 * 从 MNN 官方模型市场获取模型列表，支持从 ModelScope（魔搭）下载 MNN-LLM 模型。
 * 参考 MNN Chat 官方实现：使用 https://meta.alicdn.com/data/mnn/apis/model_market.json 获取模型配置。
 */
class LlmModelDownloadManager(context: Context) {

    companion object {
        private const val TAG = "Download"
        // 下载读写缓冲：256KB（原 8KB syscall 过密，大文件如 1.4GB llm.mnn.weight 明显拖速）
        private const val DEFAULT_BUFFER_SIZE = 262144

        /** 单文件大于此阈值走分块并行下载（默认 32MB）；小于则单连接 */
        private const val PARALLEL_DOWNLOAD_THRESHOLD = 32L * 1024 * 1024
        private const val MODEL_MARKET_URL = "https://meta.alicdn.com/data/mnn/apis/model_market.json"

        /**
         * MNN-LLM 模型固定文件列表
         */
        private val LLM_MODEL_FILES = listOf(
            "config.json",
            "llm.mnn",
            "llm.mnn.weight",
            "tokenizer.txt"
        )

        /**
         * ASR 模型固定文件列表（Sherpa-ONNX Zipformer）
         */
        private val ASR_MODEL_FILES = listOf(
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx",
            "tokens.txt"
        )

        /**
         * KWS 唤醒词模型固定文件列表（Sherpa-ONNX KWS）
         */
        private val KWS_MODEL_FILES = listOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "tokens.txt",
            "keywords.txt"
        )

        /**
         * TTS 模型固定文件列表
         */
        private val TTS_MODEL_FILES = listOf(
            "config.json",
            "tts.mnn",
            "vocab.txt"
        )

        /**
         * 人脸检测 ROI MNN 模型文件列表
         */
        private val FACE_DETECTION_ROI_MNN_FILES = listOf("det_10g.mnn")

        /**
         * 人脸检测 Landmark MNN 模型文件列表
         */
        private val FACE_DETECTION_LANDMARK_MNN_FILES = listOf("2d106det.mnn")

        /**
         * 人脸检测 ROI Det500M MNN 模型文件列表
         */
        private val FACE_DETECTION_ROI_500M_MNN_FILES = listOf("det_500m.mnn")

        /**
         * Glint360K R100 人脸嵌入 MNN 模型文件列表
         */
        private val FACE_EMBEDDING_GLINT360K_R100_MNN_FILES = listOf("glintr100.mnn")
        
        /**
         * MobileCLIP ONNX fp32 模型文件列表（MobileCLIP-S2）
         *
         * fp16 版本在 ONNX Runtime Android CPU 上会出现 NaN/Inf 输出，因此使用 fp32。
         */
        private val MOBILECLIP_MODEL_FILES = listOf(
            "vision_model.onnx",
            "text_model.onnx",
            "tokenizer.json"
        )

        /**
         * MODNet 人像抠图 ONNX 模型文件列表
         */
        private val MODNET_MODEL_FILES = listOf("modnet.onnx")

        /**
         * U2NetP 轻量抠图 ONNX 模型文件列表
         */
        private val U2NETP_MODEL_FILES = listOf("u2netp.onnx")
        private val EDIFFIQA_MODEL_FILES = listOf("ediffiqa_tiny.onnx")
        private val NIMA_MODEL_FILES = listOf("nima_mobilenet_aesthetic.onnx")

        /**
         * MNN-LLM 模型可选文件列表（存在则下载，404则跳过）
         */
        private val LLM_MODEL_OPTIONAL_FILES = listOf(
            "configuration.json",
            "llm_config.json",
            "README.md",
            "embeddings_bf16.bin"
        )

        /**
         * 根据模型 ID 解析其文件清单（纯函数，供 [getModelFiles] 复用，便于单测）。
         *
         * 新增模型时在此与 [getModelFilesByTags] 同步加分支。
         */
        fun modelFilesForId(modelId: String): List<String> = when {
            modelId.contains("kws", ignoreCase = true) -> KWS_MODEL_FILES
            modelId.contains("zipformer", ignoreCase = true) -> ASR_MODEL_FILES
            modelId.contains("whisper", ignoreCase = true) -> ASR_MODEL_FILES
            modelId == "face-det-retina10g-mnn" -> FACE_DETECTION_ROI_MNN_FILES
            modelId == "face-landmark-2d106-mnn" -> FACE_DETECTION_LANDMARK_MNN_FILES
            modelId == "face-det-retina500m-mnn" -> FACE_DETECTION_ROI_500M_MNN_FILES
            modelId == "face-embedding-glint360k-r100-mnn" -> FACE_EMBEDDING_GLINT360K_R100_MNN_FILES
            modelId == "mobileclip-onnx" -> MOBILECLIP_MODEL_FILES
            modelId == "opus-mt-zh-en" -> ModelPathConfig.OPUS_MT_MODEL_FILES
            modelId == "opus-mt-en-zh" -> ModelPathConfig.OPUS_MT_EN_ZH_MODEL_FILES
            modelId == "florence2_base" -> ModelPathConfig.FLORENCE2_MODEL_FILES
            modelId == "modnet-onnx" -> MODNET_MODEL_FILES
            modelId == "u2netp-onnx" -> U2NETP_MODEL_FILES
            modelId == "ediffiqa-face-quality-onnx" -> EDIFFIQA_MODEL_FILES
            modelId == "nima-aesthetic-onnx" -> NIMA_MODEL_FILES
            modelId.contains("face", ignoreCase = true) -> FACE_DETECTION_ROI_MNN_FILES
            else -> LLM_MODEL_FILES
        }
    }

    private val appContext = context.applicationContext

    /**
     * 默认标签翻译（按 PoLang 服务功能划分的分类标签），随 UI 语言本地化。
     * 覆盖模型中心顶部 Tab 与模型卡片徽章的展示文案。
     */
    private fun defaultTagTranslations(): TagTranslations = mapOf(
        "must-have" to appContext.getString(R.string.model_tag_must_have),
        "recommended" to appContext.getString(R.string.model_tag_recommended),
        "chat" to appContext.getString(R.string.model_tag_voice),
        "photo-tagging" to appContext.getString(R.string.model_tag_photo_tagging),
        "beauty-camera" to appContext.getString(R.string.model_tag_beauty_camera),
        // 保留底层技术标签的翻译，用于模型卡片徽章展示
        "face" to appContext.getString(R.string.model_tag_face),
        "Vision" to appContext.getString(R.string.model_tag_vision),
        "Audio" to appContext.getString(R.string.model_tag_audio),
        "ASR" to appContext.getString(R.string.model_tag_asr),
        "KWS" to appContext.getString(R.string.model_tag_kws),
        "TTS" to appContext.getString(R.string.model_tag_tts),
        "detection" to appContext.getString(R.string.model_tag_face),
        "landmark" to appContext.getString(R.string.model_tag_landmark),
        "speech" to appContext.getString(R.string.model_tag_speech),
        "wake-word" to appContext.getString(R.string.model_tag_kws),
        "keyword" to appContext.getString(R.string.model_tag_keyword)
    )

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** 大文件分块并行下载器（复用同一 OkHttpClient 连接池） */
    private val parallelDownloader by lazy { ParallelFileDownloader(client) }

    /** 并行下载的取消标志（按 modelId）；cancelDownload 时置 true，各分块轮询退出 */
    private val cancelFlags = ConcurrentHashMap<String, Boolean>()

    private val downloadDir: File
        get() = File(appContext.filesDir, "llm_models").also { it.mkdirs() }

    private val _downloadStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadStates = _downloadStates.asStateFlow()

    /**
     * 前台下载服务是否运行（Manager 侧镜像）。由 [updateServiceState] 在启动 service 后置 true，
     * 由 [ModelDownloadForegroundService.onDestroy] 回调 [onServiceStopped] 置 false。
     * 用途：service 运行期间只走普通 startService 刷通知，避免反复 startForegroundService
     * 放大「onCreate→startForeground 超时」竞态（曾导致 ForegroundServiceDidNotStartInTimeException）。
     */
    @Volatile
    private var fgServiceRunning = false

    private val activeDownloads = ConcurrentHashMap<String, okhttp3.Call>()

    /**
     * 暂停的下载任务：记录已下载的字节数，用于断点续传
     */
    private val pausedDownloads = ConcurrentHashMap<String, Long>()

    /**
     * 由 Manager 统一托管的下载 Job，生命周期独立于页面。
     */
    private val activeJobs = ConcurrentHashMap<String, Job>()



    /**
     * 加载可用模型配置
     *
     * 从本地 llm_models.json 加载模型列表，仅展示用户需要的模型。
     * 注意：此函数包含网络请求，必须在 IO 线程调用。
     */
    suspend fun loadAvailableModels(): List<ModelConfig> = withContext(Dispatchers.IO) {
        loadLocalModels()
    }

    /**
     * 加载模型市场数据（包含标签翻译）
     *
     * 仅从本地 llm_models.json 加载模型，不再从 MNN 官方市场获取。
     * 用户模型统一托管在 ModelScope PoLang 合集中。
     */
    suspend fun loadMarketData(): ModelMarketData = withContext(Dispatchers.IO) {
        val localModels = loadLocalModels()
        return@withContext ModelMarketData(localModels, defaultTagTranslations())
    }

    /**
     * 刷新模型市场数据
     *
     * 仅从本地 llm_models.json 加载，与 loadMarketData() 一致。
     */
    suspend fun refreshMarketData(): ModelMarketData = withContext(Dispatchers.IO) {
        val localModels = loadLocalModels()
        ModelMarketData(localModels, defaultTagTranslations())
    }

    /**
     * 从 MNN 官方模型市场获取模型列表和标签翻译
     */
    private fun fetchMarketData(): ModelMarketData {
        return try {
            val request = Request.Builder()
                .url(MODEL_MARKET_URL)
                .header("User-Agent", "PoLang-Android/1.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.w(TAG, "Model market fetch failed: HTTP ${response.code}")
                    return ModelMarketData(emptyList(), defaultTagTranslations())
                }

                val body = response.body?.string() ?: return ModelMarketData(emptyList(), defaultTagTranslations())
                parseMarketJson(body)
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to fetch model market, fallback to local", e)
            ModelMarketData(emptyList(), defaultTagTranslations())
        }
    }

    /**
     * 解析 model_market.json 响应体
     */
    private fun parseMarketJson(jsonBody: String): ModelMarketData {
        val json = JSONObject(jsonBody)

        // 解析标签翻译
        val tagTranslations = mutableMapOf<String, String>()
        val tagTranslationsObj = json.optJSONObject("tagTranslations")
        if (tagTranslationsObj != null) {
            tagTranslationsObj.keys().forEach { key ->
                tagTranslations[key] = tagTranslationsObj.getString(key)
            }
        }

        val result = mutableListOf<ModelConfig>()

        // 解析主模型列表
        val modelsArray = json.optJSONArray("models")
        if (modelsArray != null) {
            for (i in 0 until modelsArray.length()) {
                parseModelObject(modelsArray.getJSONObject(i))?.let { result.add(it) }
            }
        }

        // 解析 TTS 模型
        val ttsArray = json.optJSONArray("tts_models")
        if (ttsArray != null) {
            for (i in 0 until ttsArray.length()) {
                parseModelObject(ttsArray.getJSONObject(i), defaultTags = listOf("TTS"))?.let { result.add(it) }
            }
        }

        // 解析 ASR 模型
        val asrArray = json.optJSONArray("asr_models")
        if (asrArray != null) {
            for (i in 0 until asrArray.length()) {
                parseModelObject(asrArray.getJSONObject(i), defaultTags = listOf("ASR"))?.let { result.add(it) }
            }
        }

        Logger.i(TAG, "Parsed ${result.size} models from MNN market")
        return ModelMarketData(result, tagTranslations)
    }

    /**
     * 解析单个模型 JSON 对象
     */
    private fun parseModelObject(obj: JSONObject, defaultTags: List<String> = emptyList()): ModelConfig? {
        val modelName = obj.optString("modelName", "")
        if (modelName.isBlank()) return null

        val sourcesObj = obj.optJSONObject("sources")
        val sources = mutableMapOf<String, String>()
        if (sourcesObj != null) {
            sourcesObj.keys().forEach { key ->
                sources[key] = sourcesObj.getString(key)
            }
        }
        if (sources.isEmpty()) return null

        // 解析标签
        val tagsArray = obj.optJSONArray("tags")
        val tags = if (tagsArray != null) {
            (0 until tagsArray.length()).map { tagsArray.getString(it) }
        } else defaultTags

        // 从 JSON 读取文件列表，若不存在则根据模型类型推断
        val filesArray = obj.optJSONArray("files")
        val modelFiles = if (filesArray != null) {
            (0 until filesArray.length()).map { filesArray.getString(it) }
        } else {
            getModelFilesByTags(modelName, tags)
        }

        return ModelConfig(
            id = modelName.lowercase().replace("-mnn", "").replace(".", "-"),
            name = modelName,
            description = buildDescription(obj),
            size = obj.optLong("file_size", 0L),
            sources = sources,
            files = modelFiles,
            tags = tags
        )
    }

    /**
     * 本地缓存文件路径
     */
    private val cacheFile: File
        get() = File(appContext.cacheDir, "model_market_cache.json")

    /**
     * 从本地缓存加载市场数据
     */
    private fun loadCachedMarketData(): ModelMarketData? {
        return try {
            val file = cacheFile
            if (!file.exists() || !file.canRead()) return null

            // 检查缓存是否过期（7天）
            val maxAgeMs = 7L * 24 * 60 * 60 * 1000
            if (System.currentTimeMillis() - file.lastModified() > maxAgeMs) {
                Logger.d(TAG, "Market cache expired")
                return null
            }

            val jsonBody = file.readText()
            parseMarketJson(jsonBody)
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to load cached market data", e)
            null
        }
    }

    /**
     * 保存市场数据到本地缓存
     */
    private fun saveCachedMarketData(data: ModelMarketData) {
        try {
            val modelsArray = JSONArray()
            data.models.forEach { model ->
                modelsArray.put(JSONObject().apply {
                    put("modelName", model.name)
                    put("file_size", model.size)
                    put("vendor", "")
                    put("tags", JSONArray(model.tags))
                    put("sources", JSONObject(model.sources.toMap()))
                    put("files", JSONArray(model.files))
                })
            }
            val json = JSONObject().apply {
                put("tagTranslations", JSONObject(data.tagTranslations.toMap()))
                put("models", modelsArray)
            }
            cacheFile.writeText(json.toString())
            Logger.d(TAG, "Market data cached: ${data.models.size} models")
        } catch (e: Exception) {
            Logger.w(TAG, "Failed to cache market data", e)
        }
    }

    /**
     * 从本地 raw 资源加载模型配置
     */
    private fun loadLocalModels(): List<ModelConfig> {
        return try {
            val json = appContext.resources.openRawResource(R.raw.llm_models).bufferedReader().use { it.readText() }
            val array = JSONArray(json)
            (0 until array.length()).map { index ->
                val obj = array.getJSONObject(index)
                val sourcesObj = obj.getJSONObject("sources")
                val sources = mutableMapOf<String, String>()
                sourcesObj.keys().forEach { key ->
                    sources[key] = sourcesObj.getString(key)
                }

                val modelId = obj.getString("id")
                val modelTags = if (obj.has("tags")) {
                    val tagsArray = obj.getJSONArray("tags")
                    (0 until tagsArray.length()).map { tagsArray.getString(it) }
                } else emptyList()
                val modelFiles = if (obj.has("files")) {
                    val filesArray = obj.getJSONArray("files")
                    (0 until filesArray.length()).map { filesArray.getString(it) }
                } else {
                    getModelFiles(modelId)
                }

                ModelConfig(
                    id = modelId,
                    name = obj.getString("name"),
                    description = obj.getString("description"),
                    type = obj.optString("type", ""),
                    size = obj.getLong("size"),
                    sources = sources,
                    files = modelFiles,
                    tags = modelTags
                )
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to load local model config", e)
            emptyList()
        }
    }

    private fun buildDescription(obj: JSONObject): String {
        val vendor = obj.optString("vendor", "")
        val tags = obj.optJSONArray("tags")
        val tagList = if (tags != null) {
            (0 until tags.length()).map { tags.getString(it) }
        } else emptyList()

        return buildString {
            append(vendor)
            if (tagList.isNotEmpty()) {
                append(" ")
                append(tagList.joinToString(", "))
            }
            append(appContext.getString(R.string.model_local_inference_suffix))
        }
    }

/**
 * 检查模型是否已下载完成
 */
fun isModelDownloaded(modelId: String): Boolean {
    val modelDir = File(downloadDir, modelId)
    if (!modelDir.exists()) return false

    val expectedFiles = getModelFiles(modelId)
    if (expectedFiles.isEmpty()) return false

    // 检查所有必需文件是否存在
    return expectedFiles.all { fileName ->
        File(modelDir, fileName).exists()
    }
}

    /**
     * 根据模型 ID 获取对应的文件列表（委托 [modelFilesForId]，复用同一映射便于单测）
     */
    private fun getModelFiles(modelId: String): List<String> = modelFilesForId(modelId)

    /**
     * 根据模型标签推断文件列表
     */
    private fun getModelFilesByTags(modelId: String, tags: List<String>): List<String> {
        return when {
            tags.any { it.equals("ASR", ignoreCase = true) } -> ASR_MODEL_FILES
            tags.any { it.equals("TTS", ignoreCase = true) } -> TTS_MODEL_FILES
            tags.any { it.equals("FACE_DETECTION", ignoreCase = true) } -> FACE_DETECTION_ROI_MNN_FILES
            modelId.contains("zipformer", ignoreCase = true) -> ASR_MODEL_FILES
            modelId.contains("whisper", ignoreCase = true) -> ASR_MODEL_FILES
            modelId == "face-det-retina10g-mnn" -> FACE_DETECTION_ROI_MNN_FILES
            modelId == "face-landmark-2d106-mnn" -> FACE_DETECTION_LANDMARK_MNN_FILES
            modelId == "face-det-retina500m-mnn" -> FACE_DETECTION_ROI_500M_MNN_FILES
            modelId == "face-embedding-glint360k-r100-mnn" -> FACE_EMBEDDING_GLINT360K_R100_MNN_FILES
            modelId == "mobileclip-onnx" -> MOBILECLIP_MODEL_FILES
            modelId == "opus-mt-zh-en" -> ModelPathConfig.OPUS_MT_MODEL_FILES
            modelId == "opus-mt-en-zh" -> ModelPathConfig.OPUS_MT_EN_ZH_MODEL_FILES
            modelId == "florence2_base" -> ModelPathConfig.FLORENCE2_MODEL_FILES
            modelId == "modnet-onnx" -> MODNET_MODEL_FILES
            modelId == "u2netp-onnx" -> U2NETP_MODEL_FILES
            modelId == "ediffiqa-face-quality-onnx" -> EDIFFIQA_MODEL_FILES
            modelId == "nima-aesthetic-onnx" -> NIMA_MODEL_FILES
            modelId.contains("face", ignoreCase = true) -> FACE_DETECTION_ROI_MNN_FILES
            else -> LLM_MODEL_FILES
        }
    }

    /**
     * 获取已下载模型的目录路径
     */
    fun getModelDir(modelId: String): String? {
        return if (isModelDownloaded(modelId)) {
            File(downloadDir, modelId).absolutePath
        } else {
            null
        }
    }

    /**
     * 获取已下载模型列表
     */
    suspend fun getDownloadedModels(): List<ModelConfig> {
        return loadAvailableModels().filter { isModelDownloaded(it.id) }
    }

    /**
     * 删除已下载的模型
     */
    suspend fun deleteModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelDir = File(downloadDir, modelId)
            if (modelDir.exists()) {
                modelDir.deleteRecursively()
            }
            _downloadStates.update { it - modelId }
            Logger.i(TAG, "Model deleted: $modelId")
            true
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete model: $modelId", e)
            false
        }
    }

    /**
     * 取消正在进行的下载
     */
    fun cancelDownload(modelId: String) {
        pausedDownloads.remove(modelId)
        cancelFlags[modelId] = true // 并行分块轮询退出（单连接另由 job.cancel 取消）
        activeDownloads[modelId]?.cancel()
        activeDownloads.remove(modelId)
        activeJobs.remove(modelId)?.cancel()
        _downloadStates.update { current ->
            current + (modelId to DownloadState(modelId, DownloadStatus.CANCELLED, 0, 0))
        }
        updateServiceState()
    }

    /**
     * 启动下载任务（由 Manager 托管，页面退出后仍继续）。
     */
    fun enqueueDownload(modelId: String, modelConfig: ModelConfig? = null) {
        val existingJob = activeJobs[modelId]
        if (existingJob?.isActive == true) {
            Logger.d(TAG, "Download already running: $modelId")
            return
        }

        // 立即标记为下载中，让 UI 第一时间显示暂停按钮
        val totalBytes = modelConfig?.size ?: 0
        _downloadStates.update { current ->
            current + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, 0, totalBytes))
        }
        updateServiceState()

        val job = managerScope.launch {
            try {
                downloadModel(modelId, modelConfig).collect { progress ->
                    if (progress.status == DownloadStatus.COMPLETED ||
                        progress.status == DownloadStatus.FAILED ||
                        progress.status == DownloadStatus.CANCELLED
                    ) {
                        activeJobs.remove(modelId)
                        activeDownloads.remove(modelId)
                        updateServiceState()
                    }
                }
            } finally {
                activeJobs.remove(modelId)
                activeDownloads.remove(modelId)
                updateServiceState()
            }
        }

        activeJobs[modelId] = job
        updateServiceState()
    }

    /**
     * 恢复下载任务（由 Manager 托管，页面退出后仍继续）。
     */
    fun enqueueResume(modelId: String, modelConfig: ModelConfig? = null) {
        val existingJob = activeJobs[modelId]
        if (existingJob?.isActive == true) {
            Logger.d(TAG, "Resume ignored, download already running: $modelId")
            return
        }

        val job = managerScope.launch {
            try {
                resumeDownload(modelId, modelConfig).collect { progress ->
                    if (progress.status == DownloadStatus.COMPLETED ||
                        progress.status == DownloadStatus.FAILED ||
                        progress.status == DownloadStatus.CANCELLED
                    ) {
                        activeJobs.remove(modelId)
                        activeDownloads.remove(modelId)
                        updateServiceState()
                    }
                }
            } finally {
                activeJobs.remove(modelId)
                activeDownloads.remove(modelId)
                updateServiceState()
            }
        }

        activeJobs[modelId] = job
        updateServiceState()
    }

    /**
     * 由 [ModelDownloadForegroundService.onDestroy] 回调：前台服务已真正销毁，
     * 重置 [fgServiceRunning]，使下次 [updateServiceState] 能重新走首次启动分支。
     */
    fun onServiceStopped() {
        fgServiceRunning = false
    }

    /**
     * 供前台 Service 查询当前下载中的模型状态。
     */
    fun snapshotDownloadingStates(): List<DownloadState> {
        return _downloadStates.value.values
            .filter { state -> state.status == DownloadStatus.DOWNLOADING }
            .sortedBy { state -> state.modelId }
    }

    private fun hasAnyRunningTask(): Boolean {
        // 以 _downloadStates（DOWNLOADING）为唯一事实源，与 Service.onStartCommand 中
        // snapshotDownloadingStates() 的判定保持一致。
        // 关键：自动预下载（RecommendedModelAutoDownloader）走 downloadModel().collect{} 直连，
        // 不经过 enqueueDownload，因此 activeJobs 为空；若这里仍用 activeJobs 判定，则下载期间
        // 每次进度回调都会下发 ACTION_STOP，导致 FGS 被反复 stop→start（~500ms 一次），
        // 冷启动 + IO/主线程竞争下 onCreate() 内 startForeground() 超出系统时间窗，
        // 触发 ForegroundServiceDidNotStartInTimeException（即“startForeground 时序崩溃”）。
        return _downloadStates.value.values.any { state -> state.status == DownloadStatus.DOWNLOADING }
    }

    private fun updateServiceState() {
        val shouldRun = hasAnyRunningTask()
        // Android 13+ 需 POST_NOTIFICATIONS 才能建前台通知（startForeground 依赖）。
        val hasNotificationPermission = ContextCompat.checkSelfPermission(
            appContext, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val intent = Intent(appContext, ModelDownloadForegroundService::class.java)
        runCatching {
            when {
                // ① 需要运行 + 尚未启动：唯一走 startForegroundService 的分支（触发 onCreate→startForeground）。
                //    冷启动时由 PoLangApplication 错峰触发，避开主线程峰值，防 onCreate 内 startForeground 超时。
                shouldRun && !fgServiceRunning && hasNotificationPermission -> {
                    intent.action = ModelDownloadForegroundService.ACTION_START_OR_UPDATE
                    ContextCompat.startForegroundService(appContext, intent)
                    fgServiceRunning = true
                }
                // ② 需要运行 + 已在运行：普通 startService 投递 START_OR_UPDATE，仅刷通知。
                //    给已运行 service 投递不受后台启动限制；不触发 onCreate / 不要求 startForeground，
                //    消除「下载中反复 startForegroundService」竞态（原 reportProgress 500ms 一次的根因）。
                shouldRun && fgServiceRunning -> {
                    intent.action = ModelDownloadForegroundService.ACTION_START_OR_UPDATE
                    appContext.startService(intent)
                }
                // ③ 不需要运行 + 仍在运行：投递 STOP。**绝不** startForegroundService——
                //    否则为「停止服务」反而启动新前台服务、强制 onCreate+startForeground，冷启动下必超时。
                //    fgServiceRunning 不在此重置，交给 Service.onDestroy→onServiceStopped() 闭环。
                !shouldRun && fgServiceRunning -> {
                    intent.action = ModelDownloadForegroundService.ACTION_STOP
                    appContext.startService(intent)
                }
                // ④ 无通知权限 + Android < O 降级（API<26 无后台启动限制，普通 service）。
                shouldRun && !hasNotificationPermission &&
                    android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O -> {
                    intent.action = ModelDownloadForegroundService.ACTION_START_OR_UPDATE
                    appContext.startService(intent)
                    fgServiceRunning = true
                }
                // 其余（无通知权限 Android 8+ 不启动；或无需运行且未运行）→ 空操作
            }
        }.onFailure { throwable ->
            Logger.w(TAG, "Failed to sync foreground service state", throwable)
        }
    }

    /**
     * 暂停正在进行的下载
     *
     * 即使当前没有活跃的 HTTP Call（如在文件校验阶段），
     * 也会取消对应的 Job 并更新状态为 PAUSED，确保状态一致性。
     */
    fun pauseDownload(modelId: String) {
        val call = activeDownloads[modelId]
        val currentState = _downloadStates.value[modelId]
        val downloadedBytes = currentState?.downloadedBytes ?: 0
        pausedDownloads[modelId] = downloadedBytes

        // 取消活跃的 HTTP Call（如果存在）
        call?.cancel()
        activeDownloads.remove(modelId)

        // 取消对应的 Job，确保即使在校验阶段也能停止
        activeJobs.remove(modelId)?.cancel()

        _downloadStates.update { current ->
            current + (modelId to DownloadState(modelId, DownloadStatus.PAUSED, downloadedBytes, currentState?.totalBytes ?: 0))
        }
        Logger.i(TAG, "Download paused: $modelId at ${downloadedBytes} bytes")
        updateServiceState()
    }

    /**
     * 恢复暂停的下载
     */
    fun resumeDownload(modelId: String, modelConfig: ModelConfig? = null): Flow<DownloadProgress> = flow {
        val config = modelConfig ?: loadAvailableModels().find { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown model: $modelId")

        val modelDir = File(downloadDir, modelId).also { it.mkdirs() }
        val resumeFromBytes = pausedDownloads[modelId] ?: 0L

        // 获取实际文件列表并计算总大小
        val repoPath = config.sources["ModelScope"]
            ?: config.sources["modelscope"]
            ?: throw IOException("ModelScope source not available for $modelId")
        val fileInfos = fetchModelFileInfosFromModelScope(repoPath)
        // 仅下载 config.files 声明的文件（白名单），与 downloadModel 路径保持一致
        val declaredFiles = config.files.takeIf { it.isNotEmpty() }?.toSet()
        val allFileInfos = if (fileInfos.isNotEmpty()) {
            fileInfos.filter { !it.name.startsWith(".") && (declaredFiles == null || it.name in declaredFiles) }
        } else {
            (config.files.takeIf { it.isNotEmpty() } ?: getModelFiles(modelId))
                .map { ModelFileInfo(name = it, size = 0, sha256 = null) }
        }
        val actualTotalBytes = allFileInfos.sumOf { it.size }.coerceAtLeast(config.size)

        _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, resumeFromBytes, actualTotalBytes)) }
        updateServiceState()

        try {
            Logger.i(TAG, "Resuming model $modelId from $resumeFromBytes bytes")

            var totalDownloaded = resumeFromBytes

            val expectedFiles = allFileInfos.map { it.name }

            // 计算已完成的文件和当前文件中的偏移量
            var bytesToSkip = resumeFromBytes
            var resumeFileIndex = 0
            var resumeFileOffset = 0L

            for ((index, fileName) in expectedFiles.withIndex()) {
                val file = File(modelDir, fileName)
                val fileSize = if (file.exists()) file.length() else 0L
                if (bytesToSkip >= fileSize) {
                    bytesToSkip -= fileSize
                    totalDownloaded += fileSize
                } else {
                    resumeFileIndex = index
                    resumeFileOffset = bytesToSkip
                    break
                }
            }

            for (fileIndex in resumeFileIndex until expectedFiles.size) {
                val fileName = expectedFiles[fileIndex]

                if (activeDownloads[modelId]?.isCanceled() == true) {
                    throw IOException("Download cancelled")
                }

                val url = buildDownloadUrl(config.sources, fileName)
                val destFile = File(modelDir, fileName)

                // 文件已完整下载
                if (destFile.exists() && fileIndex > resumeFileIndex) {
                    totalDownloaded += destFile.length()
                    emit(DownloadProgress(modelId, totalDownloaded, actualTotalBytes, DownloadStatus.DOWNLOADING))
                    continue
                }

                // 部分下载的文件：使用 Range 请求断点续传
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PoLang-Android/1.0")

                if (fileIndex == resumeFileIndex && resumeFileOffset > 0 && destFile.exists()) {
                    requestBuilder.header("Range", "bytes=$resumeFileOffset-")
                    Logger.d(TAG, "Resuming $fileName from byte $resumeFileOffset")
                }

                val call = client.newCall(requestBuilder.build())
                activeDownloads[modelId] = call

                call.execute().use { response ->
                    Logger.d(TAG, "Response: HTTP ${response.code} for $fileName")
                    if (!response.isSuccessful && response.code != 206) {
                        val errorBody = response.body?.string()?.take(200) ?: ""
                        throw IOException("HTTP ${response.code} for $fileName, url=$url, body=$errorBody")
                    }

                    val body = response.body
                        ?: throw IOException("Empty response for $fileName from $url")

                    body.byteStream().use { input ->
                        val output = if (fileIndex == resumeFileIndex && resumeFileOffset > 0) {
                            destFile.outputStream().apply { channel.truncate(resumeFileOffset) }
                        } else {
                            destFile.outputStream()
                        }

                        output.use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead: Int
                            var lastEmitTime = System.currentTimeMillis()
                            var lastEmitBytes = totalDownloaded

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (call.isCanceled()) {
                                    throw IOException("Download cancelled")
                                }
                                out.write(buffer, 0, bytesRead)
                                totalDownloaded += bytesRead

                                val now = System.currentTimeMillis()
                                val bytesSinceLastEmit = totalDownloaded - lastEmitBytes
                                if (now - lastEmitTime > 500 || bytesSinceLastEmit > 1_048_576) {
                                    _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, totalDownloaded, actualTotalBytes)) }
                                    // 进度刷新不驱动 service（同 downloadModel.reportProgress）：通知由 Service 自驱刷新。
                                    emit(DownloadProgress(modelId, totalDownloaded, actualTotalBytes, DownloadStatus.DOWNLOADING))
                                    lastEmitTime = now
                                    lastEmitBytes = totalDownloaded
                                }
                            }
                        }
                    }
                }

                // 重置偏移量（后续文件从头下载）
                resumeFileOffset = 0
            }

            pausedDownloads.remove(modelId)
            _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.COMPLETED, actualTotalBytes, actualTotalBytes)) }
            updateServiceState()
            emit(DownloadProgress(modelId, actualTotalBytes, actualTotalBytes, DownloadStatus.COMPLETED))
            Logger.i(TAG, "Model download completed: $modelId")

        } catch (e: Exception) {
            // 优先按暂停意图判定：OkHttp 取消消息不定（"stream was reset: CANCEL" 等），
            // pausedDownloads 由 pauseDownload 在取消前写入，是可靠意图来源
            val pausedBytes = pausedDownloads[modelId]
            if (pausedBytes != null) {
                _downloadStates.update {
                    it + (modelId to DownloadState(modelId, DownloadStatus.PAUSED, pausedBytes, actualTotalBytes))
                }
                updateServiceState()
                emit(DownloadProgress(modelId, pausedBytes, actualTotalBytes, DownloadStatus.PAUSED))
                return@flow
            }
            if (e.message?.contains("cancel", ignoreCase = true) == true) {
                _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.CANCELLED, 0, actualTotalBytes)) }
                updateServiceState()
                emit(DownloadProgress(modelId, 0, actualTotalBytes, DownloadStatus.CANCELLED))
                return@flow
            }
            val errorMsg = e.message ?: e.javaClass.simpleName
            Logger.e(TAG, "Download failed for $modelId: $errorMsg")
            _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.FAILED, 0, actualTotalBytes)) }
            updateServiceState()
            emit(DownloadProgress(modelId, 0, actualTotalBytes, DownloadStatus.FAILED))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 下载模型
     *
     * 策略：仅使用 ModelScope（魔搭）源，国内访问最稳定。
     *
     * @param modelId 模型 ID
     * @return Flow<DownloadProgress> 下载进度流
     */
    fun downloadModel(modelId: String, modelConfig: ModelConfig? = null): Flow<DownloadProgress> = flow {
        val config = modelConfig ?: loadAvailableModels().find { it.id == modelId }
            ?: throw IllegalArgumentException("Unknown model: $modelId")

        val modelDir = File(downloadDir, modelId).also { it.mkdirs() }

        // 仅使用 ModelScope 源
        val repoPath = config.sources["ModelScope"]
            ?: config.sources["modelscope"]
            ?: throw IOException("ModelScope source not available for $modelId")

        // 优先从 ModelScope API 动态获取文件列表（包含完整文件信息和SHA256校验值）
        // 如果 API 失败，则回退到模型配置中的文件列表或默认列表
        val fileInfos = run {
            // 1. 首先尝试从 ModelScope API 获取（最准确，包含所有文件和元数据）
            val apiFileInfos = fetchModelFileInfosFromModelScope(repoPath)
            if (apiFileInfos.isNotEmpty()) {
                Logger.i(TAG, "Using ${apiFileInfos.size} files from ModelScope API")
                return@run apiFileInfos
            }

            // 2. API 失败，尝试使用配置文件中的文件列表
            if (config.files.isNotEmpty()) {
                Logger.w(TAG, "API failed, using files from config: ${config.files}")
                return@run config.files.map { ModelFileInfo(name = it, size = 0, sha256 = null) }
            }

            // 3. 最后回退到默认文件列表
            val defaultFiles = getModelFiles(modelId)
            Logger.w(TAG, "API and config failed, using default files: $defaultFiles")
            return@run defaultFiles.map { ModelFileInfo(name = it, size = 0, sha256 = null) }
        }

        // 从 API 获取的文件列表已经是完整列表（包含必需和可选文件）
        // 确定每个文件是否是可选文件
        // 仅下载 config.files 声明的文件（白名单），避免拉取 README.md / configuration.json
        // 及冗余权重（如 KWS 仓库同时含 int8 与非 int8 的 .onnx，否则会多下 ~12MB）
        val declaredFiles = config.files.takeIf { it.isNotEmpty() }?.toSet()
        val allFileInfos = fileInfos.map { fileInfo ->
            fileInfo to (fileInfo.name in LLM_MODEL_OPTIONAL_FILES)
        }.filter { (fileInfo, _) ->
            // 排除隐藏文件（如 .gitattributes）
            !fileInfo.name.startsWith(".") &&
                (declaredFiles == null || fileInfo.name in declaredFiles)
        }.map { it.first }

        if (allFileInfos.isEmpty()) {
            throw IOException("No files to download for model: $modelId")
        }

        // 计算实际总大小（基于 API 返回的文件大小，而非 config.size）
        val actualTotalBytes = allFileInfos.sumOf { it.size }.coerceAtLeast(config.size)
        if (actualTotalBytes != config.size) {
            Logger.d(TAG, "Total size adjusted: config=${config.size}, actual=$actualTotalBytes")
        }

        // 更新状态使用实际总大小
        _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, 0, actualTotalBytes)) }
        updateServiceState()

        val totalDownloaded = java.util.concurrent.atomic.AtomicLong(0L)
        cancelFlags[modelId] = false
        val progressLock = Any()
        var lastEmitTime = System.currentTimeMillis()
        var lastEmitBytes = 0L

        /**
         * 线程安全地把 [delta] 累加进总进度，并节流更新 [_downloadStates]（UI 实时进度源）。
         *
         * 不调用 flow 的 emit：并行分块下无法安全并发 emit，而 UI 走 [_downloadStates] StateFlow
         * （[enqueueDownload] 收集器也只关心终态 COMPLETED/FAILED/CANCELLED）。500ms 或 1MB 触发。
         */
        fun reportProgress(delta: Long) {
            val total = totalDownloaded.addAndGet(delta)
            synchronized(progressLock) {
                val now = System.currentTimeMillis()
                if (now - lastEmitTime > 500 || total - lastEmitBytes > 1_048_576) {
                    _downloadStates.update {
                        it + (modelId to DownloadState(modelId, DownloadStatus.DOWNLOADING, total, actualTotalBytes))
                    }
                    // 进度刷新不再驱动前台 service：下载进度通知由 ModelDownloadForegroundService 自驱
                    // 定时刷新。此处只更新 _downloadStates（UI 进度源）。曾因每次进度回调 startForegroundService
                    // 放大 onCreate→startForeground 超时竞态（详见 updateServiceState 注释）。
                    lastEmitTime = now
                    lastEmitBytes = total
                }
            }
        }

        try {
            Logger.i(TAG, "Downloading model $modelId from ModelScope: $repoPath")
            Logger.i(TAG, "Will download ${allFileInfos.size} files: ${allFileInfos.map { it.name }}")

            for (fileInfo in allFileInfos) {
                val fileName = fileInfo.name
                val expectedSize = fileInfo.size
                val expectedSha256 = fileInfo.sha256
                val isOptional = fileName in LLM_MODEL_OPTIONAL_FILES

                if (activeDownloads[modelId]?.isCanceled() == true) {
                    throw IOException("Download cancelled")
                }

                val url = buildModelScopeUrl(repoPath, fileName)
                val destFile = File(modelDir, fileName)

                // 检查文件是否已完整下载
                if (destFile.exists() && destFile.length() > 0) {
                    val actualSize = destFile.length()

                    // 1. 校验文件大小
                    if (expectedSize > 0 && actualSize == expectedSize) {
                        // 大小匹配，进一步校验 SHA256（如果提供了）
                        if (!expectedSha256.isNullOrEmpty()) {
                            if (verifyFileSha256(destFile, expectedSha256)) {
                                Logger.d(TAG, "File verified (size + SHA256): $fileName ($actualSize bytes)")
                                reportProgress(actualSize)
                                continue
                            } else {
                                Logger.w(TAG, "SHA256 mismatch for $fileName, re-downloading")
                                destFile.delete()
                            }
                        } else {
                            // 没有 SHA256，仅大小校验通过
                            Logger.d(TAG, "File size matches: $fileName ($actualSize bytes)")
                            reportProgress(actualSize)
                            continue
                        }
                    } else if (expectedSize > 0 && actualSize != expectedSize) {
                        // 大小不匹配，删除重新下载
                        Logger.w(TAG, "File size mismatch for $fileName: expected=$expectedSize, actual=$actualSize, re-downloading")
                        destFile.delete()
                    } else if (expectedSize == 0L) {
                        // API 没有返回大小信息，尝试 SHA256 校验
                        if (!expectedSha256.isNullOrEmpty() && verifyFileSha256(destFile, expectedSha256)) {
                            Logger.d(TAG, "File verified (SHA256 only): $fileName ($actualSize bytes)")
                            reportProgress(actualSize)
                            continue
                        } else {
                            // 无法校验，假设文件完整
                            Logger.d(TAG, "File exists (unknown size): $fileName ($actualSize bytes)")
                            reportProgress(actualSize)
                            continue
                        }
                    }
                }

                Logger.d(TAG, "Downloading $fileName from $url (expected: $expectedSize bytes)")

                // 大文件：分块并行下载（单连接受 CDN 单流限速，多段并发提速）
                if (expectedSize > PARALLEL_DOWNLOAD_THRESHOLD) {
                    parallelDownloader.download(
                        url = url,
                        destFile = destFile,
                        totalSize = expectedSize,
                        onBytes = { delta ->
                            reportProgress(delta)
                        },
                        isCancelled = { cancelFlags[modelId] == true }
                    )
                    verifyDownloadedFile(destFile, fileName, expectedSize, expectedSha256)
                    continue
                }

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "PoLang-Android/1.0")
                    .build()
                val call = client.newCall(request)
                activeDownloads[modelId] = call

                call.execute().use { response ->
                    Logger.d(TAG, "Response: HTTP ${response.code} for $fileName")
                    if (!response.isSuccessful) {
                        // 可选文件404时跳过，不报错
                        if (isOptional && response.code == 404) {
                            Logger.d(TAG, "Optional file not found (404), skipping: $fileName")
                            return@use
                        }
                        val errorBody = response.body?.string()?.take(200) ?: ""
                        throw IOException("HTTP ${response.code} for $fileName, url=$url, body=$errorBody")
                    }

                    val body = response.body
                        ?: throw IOException("Empty response for $fileName from $url")

                    body.byteStream().use { input ->
                        destFile.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead: Int

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                if (call.isCanceled()) {
                                    throw IOException("Download cancelled")
                                }
                                output.write(buffer, 0, bytesRead)
                                reportProgress(bytesRead.toLong())
                            }
                        }
                    }

                    // 单流路径同样需要在下载完成后校验（流提前结束不会抛异常）；
                    // 放在 use 块内，避免可选文件 404 return@use 跳过下载后被误校验
                    verifyDownloadedFile(destFile, fileName, expectedSize, expectedSha256)
                }
            }

            _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.COMPLETED, actualTotalBytes, actualTotalBytes)) }
            updateServiceState()
            emit(DownloadProgress(modelId, actualTotalBytes, actualTotalBytes, DownloadStatus.COMPLETED))
            Logger.i(TAG, "Model download completed: $modelId")

        } catch (e: Exception) {
            // 优先按暂停意图判定：OkHttp 取消消息不定（"stream was reset: CANCEL" 等），
            // pausedDownloads 由 pauseDownload 在取消前写入，是可靠意图来源
            val pausedBytes = pausedDownloads[modelId]
            if (pausedBytes != null) {
                _downloadStates.update {
                    it + (modelId to DownloadState(modelId, DownloadStatus.PAUSED, pausedBytes, actualTotalBytes))
                }
                updateServiceState()
                emit(DownloadProgress(modelId, pausedBytes, actualTotalBytes, DownloadStatus.PAUSED))
                return@flow
            }
            if (e.message?.contains("cancel", ignoreCase = true) == true) {
                _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.CANCELLED, 0, actualTotalBytes)) }
                updateServiceState()
                emit(DownloadProgress(modelId, 0, actualTotalBytes, DownloadStatus.CANCELLED))
                return@flow
            }
            val errorMsg = e.message ?: e.javaClass.simpleName
            Logger.e(TAG, "Download failed for $modelId: $errorMsg")
            _downloadStates.update { it + (modelId to DownloadState(modelId, DownloadStatus.FAILED, 0, actualTotalBytes)) }
            updateServiceState()
            emit(DownloadProgress(modelId, 0, actualTotalBytes, DownloadStatus.FAILED))
        }
    }.flowOn(Dispatchers.IO)

    private fun buildModelScopeUrl(repoPath: String, fileName: String): String {
        return "https://modelscope.cn/models/$repoPath/resolve/master/$fileName"
    }

    /**
     * 下载完成后的完整性校验：大小（如已知）+ SHA256（如仓库元数据提供）。
     *
     * 校验失败即删除损坏文件并抛 [IOException]，让本次下载标记为 FAILED，
     * 避免「大小正确、内容损坏」的模型文件被静默当作完成（如 CDN 断流导致某段截断）。
     */
    private fun verifyDownloadedFile(
        destFile: File,
        fileName: String,
        expectedSize: Long,
        expectedSha256: String?
    ) {
        val actualSize = destFile.length()
        if (expectedSize > 0 && actualSize != expectedSize) {
            destFile.delete()
            throw IOException("Size mismatch after download for $fileName: expected=$expectedSize, actual=$actualSize")
        }
        if (!expectedSha256.isNullOrEmpty() && !verifyFileSha256(destFile, expectedSha256)) {
            destFile.delete()
            throw IOException("SHA256 mismatch after download for $fileName (corrupted file deleted)")
        }
        Logger.d(TAG, "Post-download verification passed: $fileName ($actualSize bytes)")
    }

    /**
     * HuggingFace 下载 URL
     * 优先使用 hf-mirror.com（国内镜像），fallback 到 huggingface.co
     */
    private fun buildHuggingFaceUrl(repoPath: String, fileName: String, useMirror: Boolean = true): String {
        val domain = if (useMirror) "https://hf-mirror.com" else "https://huggingface.co"
        return "$domain/$repoPath/resolve/main/$fileName"
    }

    private fun buildDownloadUrl(sources: Map<String, String>, fileName: String): String {
        val hfRepo = sources["HuggingFace"] ?: sources["huggingface"]
        if (hfRepo != null) return buildHuggingFaceUrl(hfRepo, fileName)
        val msRepo = sources["ModelScope"] ?: sources["modelscope"]
        if (msRepo != null) return buildModelScopeUrl(msRepo, fileName)
        throw IOException("No valid source (HuggingFace/ModelScope) for file: $fileName")
    }

    private suspend fun fetchFileInfos(sources: Map<String, String>): List<ModelFileInfo> {
        val hfRepo = sources["HuggingFace"] ?: sources["huggingface"]
        if (hfRepo != null) return fetchFileInfosFromHuggingFace(hfRepo)
        val msRepo = sources["ModelScope"] ?: sources["modelscope"]
        if (msRepo != null) return fetchModelFileInfosFromModelScope(msRepo)
        return emptyList()
    }

    /**
     * 从 HuggingFace 获取文件列表
     *
     * 优先使用 hf-mirror.com（国内访问可用），失败后 fallback 到 huggingface.co。
     */
    private suspend fun fetchFileInfosFromHuggingFace(repoPath: String): List<ModelFileInfo> {
        // 先尝试镜像（国内可用）
        val mirrorResult = fetchHfApi("https://hf-mirror.com/api/models/$repoPath")
        if (mirrorResult.isNotEmpty()) return mirrorResult

        // fallback 到官方
        Logger.w(TAG, "hf-mirror.com failed, trying huggingface.co")
        return fetchHfApi("https://huggingface.co/api/models/$repoPath")
    }

    private suspend fun fetchHfApi(apiUrl: String): List<ModelFileInfo> = withContext(Dispatchers.IO) {
        try {
            Logger.i(TAG, "Fetching file list from: $apiUrl")
            val request = Request.Builder().url(apiUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Logger.w(TAG, "API returned ${response.code} for $apiUrl")
                return@withContext emptyList()
            }
            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val siblings = json.optJSONArray("siblings")
            if (siblings == null || siblings.length() == 0) {
                Logger.w(TAG, "API returned empty siblings for $apiUrl")
                return@withContext emptyList()
            }
            val files = mutableListOf<ModelFileInfo>()
            for (i in 0 until siblings.length()) {
                val f = siblings.getJSONObject(i)
                files.add(ModelFileInfo(
                    name = f.getString("rfilename"),
                    size = f.optLong("size", 0),
                    sha256 = null
                ))
            }
            Logger.i(TAG, "Fetched ${files.size} files from API")
            files
        } catch (e: Exception) {
            Logger.e(TAG, "API failed: $apiUrl", e)
            emptyList()
        }
    }

    /**
     * 计算文件的 SHA256 哈希值
     *
     * @param file 要计算哈希的文件
     * @return SHA256 字符串（小写十六进制），如果计算失败返回 null
     */
    private fun calculateFileSha256(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to calculate SHA256 for ${file.name}", e)
            null
        }
    }

    /**
     * 校验文件的 SHA256 哈希
     *
     * @param file 要校验的文件
     * @param expectedSha256 期望的 SHA256 值
     * @return 是否校验通过
     */
    private fun verifyFileSha256(file: File, expectedSha256: String?): Boolean {
        if (expectedSha256.isNullOrEmpty()) {
            // 没有提供 SHA256，跳过校验
            return true
        }

        val actualSha256 = calculateFileSha256(file)
        if (actualSha256 == null) {
            Logger.w(TAG, "Failed to calculate SHA256 for ${file.name}, skipping verification")
            return false
        }

        val match = actualSha256.equals(expectedSha256, ignoreCase = true)
        if (match) {
            Logger.d(TAG, "SHA256 verified for ${file.name}: $actualSha256")
        } else {
            Logger.e(TAG, "SHA256 mismatch for ${file.name}: expected=$expectedSha256, actual=$actualSha256")
        }
        return match
    }

    /**
     * 从 ModelScope API 获取模型仓库的文件列表（包含元数据）
     *
     * API: https://modelscope.cn/api/v1/models/{repoPath}/repo/files?Revision=master
     * 返回 Data.Files 数组，每个文件包含 Name、Size、Sha256 等字段
     *
     * @param repoPath 仓库路径，如 "MNN/Qwen3-0.6B-MNN"
     * @return 文件信息列表，如果 API 调用失败则返回空列表
     */
    private suspend fun fetchModelFileInfosFromModelScope(repoPath: String): List<ModelFileInfo> = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "https://modelscope.cn/api/v1/models/$repoPath/repo/files?Revision=master"
            Logger.i(TAG, "Fetching file list from ModelScope API: $apiUrl")

            val request = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", "PoLang-Android/1.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.w(TAG, "ModelScope API returned ${response.code}, falling back to default files")
                    return@withContext emptyList<ModelFileInfo>()
                }

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    Logger.w(TAG, "ModelScope API returned empty body")
                    return@withContext emptyList<ModelFileInfo>()
                }

                val json = JSONObject(responseBody)

                // 检查 API 返回状态码
                val code = json.optInt("Code", -1)
                if (code != 200) {
                    Logger.w(TAG, "ModelScope API returned Code=$code, Message=${json.optString("Message", "")}")
                    return@withContext emptyList<ModelFileInfo>()
                }

                val data = json.optJSONObject("Data")
                if (data == null) {
                    Logger.w(TAG, "ModelScope API response missing Data field")
                    return@withContext emptyList<ModelFileInfo>()
                }

                val files = data.optJSONArray("Files")
                if (files == null) {
                    Logger.w(TAG, "ModelScope API response missing Files field")
                    return@withContext emptyList<ModelFileInfo>()
                }

                val fileList = mutableListOf<ModelFileInfo>()
                for (i in 0 until files.length()) {
                    val fileObj = files.optJSONObject(i)
                    if (fileObj != null) {
                        val name = fileObj.optString("Name", "")
                        val path = fileObj.optString("Path", "")
                        val size = fileObj.optLong("Size", 0)
                        val sha256 = fileObj.optString("Sha256", "")
                        val type = fileObj.optString("Type", "blob")

                        // 只获取根目录下的文件：
                        // 1. Path 不包含 /（根目录文件）
                        // 2. Type 为 "blob"（普通文件，不是目录）
                        // 3. 排除隐藏文件（如 .gitattributes）
                        if (name.isNotEmpty() && path.isNotEmpty() &&
                            !path.contains("/") &&
                            type == "blob" &&
                            !name.startsWith(".")) {
                            fileList.add(ModelFileInfo(
                                name = name,
                                size = size,
                                sha256 = sha256.takeIf { it.isNotEmpty() }
                            ))
                        }
                    }
                }

                Logger.i(TAG, "ModelScope API returned ${fileList.size} files with metadata")
                return@withContext fileList
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to fetch file list from ModelScope API", e)
            return@withContext emptyList<ModelFileInfo>()
        }
    }

}

/**
 * 模型文件信息（从 API 获取的元数据）
 */
data class ModelFileInfo(
    val name: String,
    val size: Long,
    val sha256: String?
)

/**
 * 模型配置
 */
data class ModelConfig(
    val id: String,
    val name: String,
    val description: String,
    val type: String = "",
    val size: Long,
    val sources: Map<String, String>,
    val files: List<String>,
    val tags: List<String> = emptyList()
) {
    companion object {
        /**
         * 必须模型 ID 集合（Tier 1：相册扫描/创建 TAG 相关，最高优先）
         *
         * 当用户进入相册启动自动扫描前，必须确保这些模型已下载。
         * 本地 LLM、ASR、KWS 已移出必须列表，改为聊天页提醒。
         */
        val REQUIRED_MODEL_IDS = setOf(
            "face-det-retina500m-mnn",  // MNN ROI (Det500M)
            "face-landmark-2d106-mnn",  // MNN 2D106
            "face-embedding-glint360k-r100-mnn", // Glint360K R100 人脸 embedding
            "florence2_base",           // 图片打标（默认 tagger，Pass 3）
            "mobileclip-onnx",          // 语义搜索
            "opus-mt-zh-en",            // 中文查询翻译
            "opus-mt-en-zh"             // 英文 summary 汉化（labelsZh）
        )

        /**
         * 聊天/语音输入相关模型 ID 集合（Tier 2：次高优先）
         *
         * 不在相册必须列表中，仅在聊天页提醒下载。
         * 端侧文本 LLM（qwen3_5_2b）已移除，聊天改走远程推理；仅保留 ASR 语音输入。
         * KWS 唤醒模型（sherpa-onnx-kws-zipformer-wenetspeech）已拆出（语音为非刚需），
         * 不随聊天模型下载、不 WiFi 静默预下载，仅在设置「语音控制」区块按需下载。
         */
        val CHAT_MODEL_IDS = setOf(
            "sherpa-onnx-zipformer-zh-en" // ASR（语音输入）
        )

        /**
         * 推荐模型 ID 集合（Tier 2：非核心，WiFi 下可静默预下载）。
         *
         * 含语音输入/唤醒（[CHAT_MODEL_IDS] + KWS）、证件照抠图、相册人脸标记预览。
         * 2026-08-19：语音能力降级为默认关闭的实验能力，语音模型（ASR/KWS）全部归入推荐，
         * 任何语音模型都不得进入 [REQUIRED_MODEL_IDS]。
         */
        val RECOMMENDED_MODEL_IDS: Set<String> = CHAT_MODEL_IDS + setOf(
            "sherpa-onnx-kws-zipformer-wenetspeech", // KWS（唤醒词）
            "modnet-onnx",                  // 证件照/抠图
            "u2netp-onnx",                  // 证件照/抠图（轻量）
            "mediapipe-face-landmarker",    // 相册人脸标记预览
            "ediffiqa-face-quality-onnx",   // 人脸封面质量评分
            "nima-aesthetic-onnx"           // 人物封面美学评分
        )
    }

    /**
     * 便捷属性：判断是否为小模型 (< 50MB)
     */
    val isSmallModel: Boolean get() = size < 50 * 1024 * 1024

    /**
     * 该模型是否为必须下载的核心模型
     */
    val isRequired: Boolean get() = id in REQUIRED_MODEL_IDS

    /** 该模型是否为推荐（非必须）模型。 */
    val isRecommended: Boolean get() = id in RECOMMENDED_MODEL_IDS

    /**
     * 获取模型的第一个分类标签，用于确定所属 Tab
     */
    fun primaryCategory(): ModelCategory {
        val firstTag = tags.firstOrNull { it != "TTS" && it != "ASR" }
            ?: tags.firstOrNull()
            ?: "Chat"
        return ModelCategory(firstTag)
    }

    /**
     * 获取模型类型的简短标签
     */
    fun getTypeLabel(tagTranslations: TagTranslations): String {
        val primaryTag = tags.firstOrNull() ?: "Chat"
        return tagTranslations[primaryTag] ?: primaryTag
    }
}

/**
 * 模型市场数据（包含模型列表和标签翻译）
 */
data class ModelMarketData(
    val models: List<ModelConfig>,
    val tagTranslations: TagTranslations
) {
    /**
     * 服务功能分类标签集合（用于顶部 Tab 分类）
     * 语音(chat)分类调整到末尾。
     */
    private val serviceCategoryTags = listOf("must-have", "recommended", "photo-tagging", "beauty-camera", "chat")

    /**
     * 获取所有可用的服务功能分类标签
     */
    fun getCategories(): List<ModelCategory> {
        val categories = serviceCategoryTags
            .filter { tag -> models.any { tag in it.tags } }
            .map { ModelCategory(it) }
            .toMutableList()

        // 未分类的模型放入 "All"
        val assignedIds = categories.flatMap { category ->
            when (category.tag) {
                "must-have" -> models.filter { it.isRequired }.map { it.id }
                "recommended" -> models.filter { it.isRecommended }.map { it.id }
                else -> models.filter { category.tag in it.tags }.map { it.id }
            }
        }.toSet()
        val unassigned = models.filter { it.id !in assignedIds }
        if (unassigned.isNotEmpty()) {
            categories.add(ModelCategory.ALL)
        }

        return categories
    }

    /**
     * 按服务功能分类标签分组模型
     * 允许同一模型出现在多个分类中（如 must-have + beauty-camera）。
     */
    fun groupByCategory(): Map<ModelCategory, List<ModelConfig>> {
        val result = mutableMapOf<ModelCategory, List<ModelConfig>>()

        for (categoryTag in serviceCategoryTags) {
            val categoryModels = when (categoryTag) {
                "must-have" -> models.filter { it.isRequired }
                "recommended" -> models.filter { it.isRecommended }
                else -> models.filter { categoryTag in it.tags }
            }
            if (categoryModels.isNotEmpty()) {
                result[ModelCategory(categoryTag)] = categoryModels
            }
        }

        // 未分类的放入 "All"
        val assignedIds = result.values.flatten().map { it.id }.toSet()
        val unassigned = models.filter { it.id !in assignedIds }
        if (unassigned.isNotEmpty()) {
            result[ModelCategory.ALL] = unassigned
        }

        return result
    }
}

/**
 * 下载进度
 */
data class DownloadProgress(
    val modelId: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val status: DownloadStatus
) {
    val progressPercent: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()) else 0f
}

/**
 * 下载状态
 */
enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * 内部下载状态
 */
data class DownloadState(
    val modelId: String,
    val status: DownloadStatus,
    val downloadedBytes: Long,
    val totalBytes: Long
)
