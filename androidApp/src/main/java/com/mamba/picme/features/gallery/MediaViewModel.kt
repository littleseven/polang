package com.mamba.picme.features.gallery

import android.content.Context
import android.content.IntentSender
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.mamba.picme.R
import com.mamba.picme.core.common.Logger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mamba.picme.beauty.api.PhotoProcessor
import com.mamba.picme.beauty.api.facedetect.DetectionPipelineConfig
import com.mamba.picme.beauty.api.facedetect.FaceDetector
import com.mamba.picme.domain.model.GroupedMedia
import com.mamba.picme.domain.model.GroupingMode
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.domain.repository.AndroidMediaRepository
import com.mamba.picme.domain.repository.UserSettingsRepository
import com.mamba.picme.domain.trash.PreviewTrashRouting
import com.mamba.picme.domain.trash.TrashBackend
import com.mamba.picme.domain.trash.TrashOutcome
import com.mamba.picme.domain.trash.TrashSessionController
import com.mamba.picme.domain.usecase.GenerateSummaryOnDemandUseCase
import com.mamba.picme.domain.usecase.GetGroupedMediaUseCase
import com.mamba.picme.domain.usecase.OcrProcessor
import com.mamba.picme.features.camera.toDevicePreference
import com.mamba.picme.features.camera.toInferenceBackendType
import com.mamba.picme.features.camera.toLandmarkDetectorType
import com.mamba.picme.features.camera.toRoiDetectorType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MediaViewModel(
    private val repository: AndroidMediaRepository,
    private val getGroupedMediaUseCase: GetGroupedMediaUseCase,
    private val ocrUseCase: OcrProcessor,
    private val photoProcessor: PhotoProcessor,
    private val faceDetector: FaceDetector,
    private val generateSummaryOnDemandUseCase: GenerateSummaryOnDemandUseCase,
    private val userSettingsRepository: UserSettingsRepository,
    trashBackend: TrashBackend
) : ViewModel() {

    companion object {
        private const val TAG = "Gallery"
    }

    /**
     * 预览页上滑删除的回收站编排（系统回收站 30 天可恢复）。
     * 授权 IntentSender 的拉起与结果回调在 UI 层（共享组件 `TrashAuthEffects`，按 tag 分桶路由）。
     */
    val trashController = TrashSessionController(trashBackend, viewModelScope, Dispatchers.IO)

    /** 系统回收站可用性（API 30+）；宿主据此选择上滑删除提示文案。 */
    val isTrashSupported: Boolean get() = trashController.isSupported

    init {
        // 回收站授权成功（Trashed）后刷新媒体库。注意：仅 AOSP 的 MediaStore 默认查询
        // 会过滤已回收行，HyperOS/Android 16 普查会照常返回 trashed 行——过滤责任在
        // repository 查询侧（IS_TRASHED=0 selection + stale 探活视 trashed 为不存在），
        // 刷新后 Gallery/Chat/Memory 三宿主的预览列表随流自动收缩
        viewModelScope.launch {
            trashController.outcomes.collect { outcome ->
                if (outcome is TrashOutcome.Trashed && outcome.trashedUris.isNotEmpty()) {
                    repository.refreshMediaLibrary()
                }
            }
        }
    }

    private val _groupingMode = MutableStateFlow(GroupingMode.DATE)
    val groupingMode = _groupingMode.asStateFlow()

    private val _ocrState = MutableStateFlow<OcrResult?>(null)
    val ocrState: StateFlow<OcrResult?> = _ocrState.asStateFlow()

    private val _deleteAuthRequest = MutableStateFlow<DeleteAuthRequest?>(null)
    val deleteAuthRequest: StateFlow<DeleteAuthRequest?> = _deleteAuthRequest.asStateFlow()

    sealed class OcrResult {
        object Loading : OcrResult()
        data class Success(val text: String) : OcrResult()
        data class Error(val message: String) : OcrResult()
    }

    sealed class DeleteAuthRequest {
        data class Api29(val intentSender: IntentSender) : DeleteAuthRequest()
        data class Api30(val uris: List<Uri>) : DeleteAuthRequest()
    }

    fun clearOcrResult() {
        Logger.d(TAG, "Clearing OCR result")
        _ocrState.value = null
    }

    /**
     * 相册大图页人脸关键点检测结果（对标 iOS GalleryFaceDebug）。
     *
     * 复用与编辑器同源的 [FaceDetector.detectPhoto]（基于必装 MNN 模型，非可选下载），
     * 保证关键点稳定产出；points106 为归一化 [0,1] 坐标（偶数索引=x，奇数索引=y），Y-down，
     * 与解码后已应用 EXIF 朝向的 bitmap（即屏幕显示方向）一致。
     */
    sealed class FaceLandmarkResult {
        data class Success(val points106: FloatArray, val imageWidth: Int, val imageHeight: Int) : FaceLandmarkResult()
        data object NoFace : FaceLandmarkResult()
        data class Error(val message: String?) : FaceLandmarkResult()
    }

    /**
     * 对静态图执行人脸 106 关键点检测，供相册大图页叠加显示。
     *
     * 与编辑器 [PhotoEditorViewModel.detectFace] 同源：先确保检测流水线拿到设置页配置
     * （否则 FaceDetectorManager.detectPhoto 会因 isPipelineInitialized=false 直接返回 null），
     * 再以 lensFacing=BACK（camera2=1）触发，跳过 adapter 镜像（静态图已在显示方向）。
     * 100% 端侧推理（隐私红线：人脸检测不上云）。
     */
    @Suppress("TooGenericExceptionCaught") // bitmap 解码可能抛多种受检/运行时异常（IO/Security/解码失败），统一兜底
    suspend fun detectFaceLandmarks(context: Context, uri: String): FaceLandmarkResult = withContext(Dispatchers.Default) {
        ensureFaceDetectionPipeline()
        val bitmap = try {
            decodeSampledBitmap(context, uri)
        } catch (error: Exception) {
            Logger.e(TAG, "Decode bitmap failed for landmark detection", error)
            return@withContext FaceLandmarkResult.Error(error.message)
        } ?: return@withContext FaceLandmarkResult.Error(null)

        try {
            val result = runCatching { faceDetector.detectPhoto(bitmap, lensFacing = 1) }
                .onFailure { error -> Logger.e(TAG, "Face landmark detection failed", error) }
                .getOrNull()
            val points = result?.landmarks106
            if (points != null && points.size >= 2) {
                FaceLandmarkResult.Success(points, bitmap.width, bitmap.height)
            } else {
                FaceLandmarkResult.NoFace
            }
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * 确保人脸检测流水线已按用户设置初始化（ROI + Landmark 阶段配置）。
     *
     * 相册页不一定经过相机页，pipelineConfig 可能为 null，导致 detectPhoto 被跳过。
     * 逻辑与编辑器 [PhotoEditorViewModel.ensureFaceDetectionPipeline] 完全一致。
     */
    @Suppress("TooGenericExceptionCaught") // DataStore 读取/配置下发异常种类不定，初始化失败不应阻断看图
    private suspend fun ensureFaceDetectionPipeline() {
        try {
            val roiStageConfig = userSettingsRepository.roiStageConfigFlow.first()
            val landmarkStageConfig = userSettingsRepository.landmarkStageConfigFlow.first()
            val config = DetectionPipelineConfig(
                roiDetector = roiStageConfig.modelType.toRoiDetectorType(),
                landmarkDetector = landmarkStageConfig.modelType.toLandmarkDetectorType(),
                roiEngine = roiStageConfig.engineType.toInferenceBackendType(),
                landmarkEngine = landmarkStageConfig.engineType.toInferenceBackendType(),
                roiDevice = roiStageConfig.devicePreference.toDevicePreference(),
                landmarkDevice = landmarkStageConfig.devicePreference.toDevicePreference()
            )
            faceDetector.updatePipelineConfig(config)
            Logger.d(TAG, "Face detection pipeline initialized for gallery landmark")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.e(TAG, "Failed to init face detection pipeline for gallery landmark", error)
        }
    }

    /** 解码 URI 为 bitmap（按需降采样，并应用 EXIF 朝向，使其与屏幕显示方向一致）。 */
    private fun decodeSampledBitmap(context: Context, uri: String, maxDimension: Int = 2048): Bitmap? {
        val parsedUri = uri.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsedUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decodedBitmap = context.contentResolver.openInputStream(parsedUri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        } ?: return null

        return normalizeBitmapOrientation(context, parsedUri, decodedBitmap)
    }

    @Suppress("MagicNumber") // EXIF 朝向旋转角度（90/180/270）与翻转缩放（-1/1）为既定语义
    private fun normalizeBitmapOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ExifInterface(inputStream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL

        val transform = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> transform.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                transform.postRotate(90f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> transform.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                transform.postRotate(270f)
                transform.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> transform.postRotate(270f)
        }

        if (transform.isIdentity) {
            return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        }.onSuccess { transformedBitmap ->
            if (transformedBitmap !== bitmap) {
                bitmap.recycle()
            }
        }.getOrElse { error ->
            Logger.w(TAG, "Failed to normalize bitmap orientation, using original", error)
            bitmap
        }
    }

    /**
     * 按需触发 summary 生成：照片详情打开时，若 labels.summary 为空，
     * 用当前 tagger（默认 Florence-2）单张生成并写回（缓存）。批量扫描不触发（批量用 ML Kit）。
     */
    fun triggerSummaryOnDemand(mediaId: Long) {
        viewModelScope.launch {
            generateSummaryOnDemandUseCase.generateIfMissing(mediaId)
        }
    }

    /** 查看器页切换/打开时回写查看时间（fire-and-forget，不阻塞 UI）。 */
    fun markMediaViewed(uri: String) {
        viewModelScope.launch {
            repository.markMediaViewed(uri)
        }
    }

    override fun onCleared() {
        super.onCleared()
        Logger.d(TAG, "MediaViewModel cleared, releasing OCR resources")
        ocrUseCase.close()
    }

    fun recognizeTextFromCurrentImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            Logger.d(TAG, "Starting OCR for URI: $uri")
            _ocrState.value = OcrResult.Loading
            try {
                val result = ocrUseCase.recognizeFromUri(context, uri)
                _ocrState.value = if (result != null) {
                    Logger.d(TAG, "OCR Success: ${result.take(20)}...")
                    OcrResult.Success(result)
                } else {
                    Logger.w(TAG, "OCR Failed or no text found")
                    OcrResult.Error(context.getString(R.string.ocr_no_text))
                }
            } catch (e: Exception) {
                Logger.e(TAG, "OCR Exception: ${e.message}", e)
                _ocrState.value = OcrResult.Error(context.getString(R.string.ocr_failed_reason, e.message ?: ""))
            }
        }
    }

    val groupedMedia: StateFlow<List<GroupedMedia>> = combine(
        repository.allMedia,
        _groupingMode
    ) { allMedia, mode ->
        getGroupedMediaUseCase(allMedia, mode)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val allMedia: StateFlow<List<MediaAsset>> = repository.allMedia
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun setGroupingMode(mode: GroupingMode) {
        Logger.d(TAG, "Setting grouping mode to: $mode")
        _groupingMode.value = mode
    }

    fun insertMedia(mediaAsset: MediaAsset) {
        viewModelScope.launch {
            repository.insertMedia(mediaAsset)
            repository.refreshMediaLibrary()
        }
    }

    fun refreshLabels() = repository.refreshLabels()

    fun refreshMediaLibrary() {
        viewModelScope.launch {
            Logger.d(TAG, "Refreshing media library")
            repository.refreshMediaLibrary()
        }
    }

    /**
     * 预览页上滑删除入口（[tag] 为宿主分桶标识，供 TrashAuthEffects 路由授权与 outcome）：
     * API 30+ → 系统回收站（Trashed outcome 授权成功后才由宿主收缩列表）；
     * API < 30 → 降级走既有 [deleteMediaByIds] 永久删除 + 系统授权框通路。
     * 返回实际走通的 [PreviewTrashRouting.Route]，宿主据此补 legacy 分支的本地状态（如 Chat 死图清理）。
     */
    fun requestTrash(asset: MediaAsset, tag: String): PreviewTrashRouting.Route {
        val route = PreviewTrashRouting.resolve(trashController.isSupported)
        when (route) {
            PreviewTrashRouting.Route.TRASH -> {
                Logger.d(TAG, "Request trash from preview: ${asset.id} (tag=$tag)")
                trashController.requestTrash(listOf(asset.uri), tag = tag)
            }
            PreviewTrashRouting.Route.LEGACY_DELETE -> {
                Logger.d(TAG, "Trash unsupported (API<30), fallback to legacy delete: ${asset.id}")
                deleteMediaByIds(listOf(asset.id))
            }
        }
        return route
    }

    fun deleteMediaByIds(ids: List<Long>) {
        viewModelScope.launch {
            Logger.d(TAG, "Deleting media items: $ids")
            repository.deleteMediaByIds(ids)

            // 协程完成后检查是否需要用户授权，避免 GalleryScreen 同步调用导致竞态条件
            val recoverableSender = repository.getPendingRecoverableIntentSender()
            if (recoverableSender != null) {
                _deleteAuthRequest.value = DeleteAuthRequest.Api29(recoverableSender)
                return@launch
            }

            val pendingUris = repository.getPendingDeleteUris().map { uriString -> Uri.parse(uriString) }
            if (pendingUris.isNotEmpty()) {
                _deleteAuthRequest.value = DeleteAuthRequest.Api30(pendingUris)
            }
        }
    }

    fun consumeDeleteAuthRequest() {
        _deleteAuthRequest.value = null
    }

    /**
     * 获取待删除的 URI 字面值列表（`List<String>`，与 [MediaRepository] 接口对齐；用于权限请求）
     */
    fun getPendingDeleteUris(): List<String> = repository.getPendingDeleteUris()

    /**
     * 清除待删除的 URI 列表
     */
    fun clearPendingDeleteUris() {
        repository.clearPendingDeleteUris()
    }

    /**
     * 获取 Android 10 恢复性删除的 IntentSender
     */
    fun getPendingRecoverableIntentSender() = repository.getPendingRecoverableIntentSender()

    /**
     * 清除 Android 10 恢复性删除状态
     */
    fun clearPendingRecoverable() {
        repository.clearPendingRecoverable()
    }

    /**
     * 在用户授权后执行删除操作
     */
    fun executePendingDeletes() {
        viewModelScope.launch {
            Logger.d(TAG, "Executing pending deletes after user authorization")
            repository.executePendingDeletes()
        }
    }

}
