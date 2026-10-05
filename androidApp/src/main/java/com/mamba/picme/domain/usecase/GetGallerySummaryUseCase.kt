package com.mamba.picme.domain.usecase

import com.mamba.picme.agent.core.model.context.GallerySummary
import com.mamba.picme.data.local.AppDatabase
import com.mamba.picme.domain.tag.scan.TagScanOrchestrator
import com.mamba.picme.service.tag.TagGenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 获取本地相册摘要。
 *
 * - 读取-only，不写数据库，不触发扫描。
 * - 全部使用 COUNT(*) 查询 + StateFlow 读取，目标耗时 < 50ms。
 * - 返回 null 表示读取失败（如 Room 异常），上层应按“无数据”处理。
 */
class GetGallerySummaryUseCase(
    private val db: AppDatabase
) {
    companion object {
        private const val PASS1_RATIO_THRESHOLD = 0.1
        private const val PASS3_RATIO_THRESHOLD = 0.3
    }

    suspend operator fun invoke(includeDetails: Boolean = false): GallerySummary? = withContext(Dispatchers.IO) {
        runCatching {
            val stats = TagScanOrchestrator.getDbStats(db)
            val progress = TagGenerationService.sessionProgress.value
            val isScanning = TagGenerationService.isScanning.value

            val recommendation = when {
                // 阈值判定分母用照片口径：remainingForPass1/3 均为 photo-only（视频不进流水线）
                stats.photoCount > 0 && stats.remainingForPass1 > stats.photoCount * PASS1_RATIO_THRESHOLD ->
                    GallerySummary.ScanRecommendation.PASS1_FIRST
                stats.photoCount > 0 && stats.remainingForPass3 > stats.photoCount * PASS3_RATIO_THRESHOLD ->
                    GallerySummary.ScanRecommendation.PASS3_FULL
                stats.remainingForPass3 > 0 ->
                    GallerySummary.ScanRecommendation.INCREMENTAL
                else ->
                    GallerySummary.ScanRecommendation.NONE
            }

            val currentPass = progress?.currentPass?.name
            val scanProgressText = if (isScanning && progress != null) {
                "${progress.processed}/${progress.total}"
            } else null

            GallerySummary(
                totalPhotos = db.mediaDao().getPhotoCount(),
                totalVideos = db.mediaDao().getVideoCount(),
                totalMedia = stats.totalMedia,
                hasFaceCount = stats.withFace,
                personClusterCount = stats.personCount,
                namedPersonCount = stats.namedPersonCount,
                labeledCount = stats.withLabels,
                unlabeledCount = stats.remainingForPass3,
                semanticEncodedCount = stats.withSemantic,
                remainingPass1 = stats.remainingForPass1,
                remainingPass3 = stats.remainingForPass3,
                isScanning = isScanning,
                currentPass = currentPass,
                scanProgressText = scanProgressText,
                recommendation = recommendation,
                includeDetails = includeDetails
            )
        }.getOrNull()
    }
}
