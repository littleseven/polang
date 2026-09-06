package com.mamba.picme.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Transaction
    @Query("SELECT * FROM media_assets ORDER BY captureDate DESC")
    fun getAllMedia(): Flow<List<MediaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedia(mediaEntity: MediaEntity): Long

    @Delete
    suspend fun deleteMedia(mediaEntity: MediaEntity)

    @Query("DELETE FROM media_assets WHERE id IN (:ids)")
    suspend fun deleteMediaByIds(ids: List<Long>)

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun getMediaById(id: Long): MediaEntity?

    /**
     * 头像拍摄兜底：查 captureDate 不早于 notBeforeMs 的最新一张本机拍照（拍照异步入库后轮询用）。
     * 限 type=PHOTO + 相机文件名（yyyyMMdd-HHmmss，见 ImageProcessor.takePhoto），排除
     * MediaIndexingWorker 对系统相册新文件写入的兜底行（captureDate=now 会误命中）。
     * 不能用 source IS NULL：索引行 source 同为 null，且远程拍照 source 非 null 会被误排除
     */
    @Query(
        "SELECT * FROM media_assets WHERE captureDate >= :notBeforeMs AND type = 'PHOTO'" +
            " AND fileName GLOB '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9]'" +
            " ORDER BY id DESC LIMIT 1"
    )
    suspend fun getLatestMediaCapturedAfter(notBeforeMs: Long): MediaEntity?

    @Query("SELECT * FROM media_assets WHERE id IN (:ids)")
    suspend fun getMediaByIds(ids: List<Long>): List<MediaEntity>

    // ── 搜索查询 ─────────────────────────────────────────────

    /** 按标签搜索（labels JSON 数组模糊匹配） */
    @Query("SELECT * FROM media_assets WHERE labels LIKE '%' || :label || '%' ORDER BY captureDate DESC")
    suspend fun searchByLabel(label: String): List<MediaEntity>

    /** 按标签搜索（labels/labelsEn/labelsZh 三字段 OR：中英文直查 + 覆盖新老数据） */
    @Query(
        "SELECT * FROM media_assets WHERE labels LIKE '%' || :keyword || '%' " +
            "OR labelsEn LIKE '%' || :keyword || '%' " +
            "OR labelsZh LIKE '%' || :keyword || '%' ORDER BY captureDate DESC"
    )
    suspend fun searchByLabelAllFields(keyword: String): List<MediaEntity>

    /** 按 OCR 文本搜索 */
    @Query("SELECT * FROM media_assets WHERE ocrText LIKE '%' || :query || '%' ORDER BY captureDate DESC")
    suspend fun searchByOcrText(query: String): List<MediaEntity>

    /** 按地名搜索 */
    @Query("SELECT * FROM media_assets WHERE locationName LIKE '%' || :place || '%' ORDER BY captureDate DESC")
    suspend fun searchByLocation(place: String): List<MediaEntity>

    /** 按文件名搜索 */
    @Query("SELECT * FROM media_assets WHERE fileName LIKE '%' || :name || '%' ORDER BY captureDate DESC")
    suspend fun searchByFileName(name: String): List<MediaEntity>

    /** 按时间范围搜索 */
    @Query("SELECT * FROM media_assets WHERE captureDate BETWEEN :startMs AND :endMs ORDER BY captureDate DESC")
    suspend fun searchByTimeRange(startMs: Long, endMs: Long): List<MediaEntity>

    /** 综合搜索：标签 + OCR + 地名 + 文件名 */
    @Query(
        """
        SELECT * FROM media_assets WHERE
            labels LIKE '%' || :query || '%' OR
            ocrText LIKE '%' || :query || '%' OR
            locationName LIKE '%' || :query || '%' OR
            fileName LIKE '%' || :query || '%'
        ORDER BY captureDate DESC
        """
    )
    suspend fun searchAll(query: String): List<MediaEntity>

    /** 获取未索引的媒体（indexed_at IS NULL） */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getUnindexedMediaIds()")
    @Query("SELECT * FROM media_assets WHERE indexedAt IS NULL ORDER BY captureDate DESC")
    suspend fun getUnindexedMedia(): List<MediaEntity>

    /** 仅获取未索引的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE indexedAt IS NULL ORDER BY captureDate DESC")
    suspend fun getUnindexedMediaIds(): List<Long>

    /** 按 URI 查找媒体（避免加载全表） */
    @Query("SELECT * FROM media_assets WHERE uri = :uri LIMIT 1")
    suspend fun getMediaByUri(uri: String): MediaEntity?

    /** 更新索引结果 */
    @Query(
        """
        UPDATE media_assets SET
            labels = :labels,
            ocrText = :ocrText,
            latitude = :latitude,
            longitude = :longitude,
            locationName = :locationName,
            city = :city,
            indexedAt = :indexedAt
        WHERE id = :mediaId
        """
    )
    suspend fun updateIndexResult(
        mediaId: Long,
        labels: String?,
        ocrText: String?,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
        city: String?,
        indexedAt: Long
    )

    /** 回填选择：有坐标但无地名的存量媒体（历史上 Geocoder 失败）。 */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
          AND (locationName IS NULL OR locationName = '')
        """
    )
    suspend fun getMediaNeedingLocationBackfill(): List<MediaEntity>

    /** 位置 pass 选择：locationName 为空的照片(仅 PHOTO;视频无 EXIF GPS 且 ExifInterface 读视频慢/报错)。 */
    @Query("SELECT * FROM media_assets WHERE locationName IS NULL AND type = 'PHOTO' ORDER BY id LIMIT :limit")
    suspend fun getMediaNeedingLocationScan(limit: Int): List<MediaEntity>

    /** 仅写位置相关字段(不动 labels/ocr/indexedAt)。无 GPS 哨兵:locationName="". */
    @Query(
        """
        UPDATE media_assets SET
            latitude = :latitude,
            longitude = :longitude,
            locationName = :locationName,
            city = :city
        WHERE id = :mediaId
        """
    )
    suspend fun updateLocation(
        mediaId: Long,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
        city: String?
    )

    /** 获取已索引媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE indexedAt IS NOT NULL AND indexedAt > 0")
    suspend fun getIndexedCount(): Int

    /** 获取媒体总数 */
    @Query("SELECT COUNT(*) FROM media_assets")
    suspend fun getTotalCount(): Int

    /** 获取照片数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE type = 'PHOTO'")
    suspend fun getPhotoCount(): Int

    /** 获取视频数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE type = 'VIDEO'")
    suspend fun getVideoCount(): Int

    // ── 人脸聚类查询 ──────────────────────────────────────────

    /** 获取所有媒体（非 Flow，用于后台） */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getAllMediaIds() 或分页查询")
    @Query("SELECT * FROM media_assets ORDER BY captureDate DESC")
    suspend fun getAllMediaNow(): List<MediaEntity>

    /** 仅获取所有媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets ORDER BY captureDate DESC")
    suspend fun getAllMediaIds(): List<Long>

    /** 仅获取已检测到人脸的照片 ID（重提 embedding 用：只处理有人脸的，省去无人脸的重跑） */
    @Query("SELECT id FROM media_assets WHERE type = 'PHOTO' AND hasFace = 1 ORDER BY id")
    suspend fun getHasFaceMediaIds(): List<Long>

    /** 所有非空 labels（JSON 数组字符串），供 gallery.tags 聚合标签分布。轻量：只取 labels 列。 */
    @Query("SELECT labels FROM media_assets WHERE labels IS NOT NULL AND labels != ''")
    suspend fun getAllLabels(): List<String>

    /** 更新 hasFace */
    @Query("UPDATE media_assets SET hasFace = :hasFace WHERE id = :mediaId")
    suspend fun updateHasFace(mediaId: Long, hasFace: Boolean)

    /** 获取有脸但未聚类的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithFacesIds()")
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 AND (faceId IS NULL OR faceId = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFaces(): List<MediaEntity>

    /** 仅获取有脸但未聚类的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE hasFace = 1 AND (faceId IS NULL OR faceId = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFacesIds(): List<Long>

    /** 更新 faceId */
    @Query("UPDATE media_assets SET faceId = :faceId WHERE id = :mediaId")
    suspend fun updateFaceId(mediaId: Long, faceId: String)

    /** 查询指定媒体的 faceId */
    @Query("SELECT faceId FROM media_assets WHERE id = :mediaId")
    suspend fun getFaceIdByMediaId(mediaId: Long): String?

    /** 批量更新 faceId（Pass 2 DBSCAN 分配人物时避免逐条更新阻塞） */
    @Query("UPDATE media_assets SET faceId = :faceId WHERE id IN (:mediaIds)")
    suspend fun updateFaceIdBatch(mediaIds: List<Long>, faceId: String)

    /** 按 hasFace 搜索 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getHasFaceCount() / getHasFaceIds()")
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 ORDER BY captureDate DESC")
    suspend fun searchByHasFace(): List<MediaEntity>

    /** 有脸的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE hasFace = 1")
    suspend fun getHasFaceCount(): Int

    /** 仅获取有脸的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE hasFace = 1 ORDER BY captureDate DESC")
    suspend fun getHasFaceIds(): List<Long>

    /** 按 faceId 分组统计数据 */
    @Query("SELECT faceId, COUNT(*) as cnt FROM media_assets WHERE faceId IS NOT NULL AND faceId != '' GROUP BY faceId ORDER BY cnt DESC")
    suspend fun getFaceGroups(): List<FaceGroupCount>

    /** 获取某个 faceId 下的所有媒体 */
    @Query("SELECT * FROM media_assets WHERE faceId = :faceId ORDER BY captureDate DESC")
    suspend fun getMediaByFaceId(faceId: Int): List<MediaEntity>

    /** 重置所有人脸数据（含 hasFace + faceId + faceRoiResult，用于全量重新检测+聚类）
     *
     * 同时清空 semanticEmbedding，因为 MobileCLIP 语义编码已内联合并到 Pass 1。
     */
    @Query("UPDATE media_assets SET hasFace = 0, faceId = NULL, faceRoiResult = NULL, semanticEmbedding = NULL")
    suspend fun resetAllFaceData()

    /** 仅重置人脸聚类结果（保留 hasFace 检测标记，用于仅重新聚类） */
    @Query("UPDATE media_assets SET faceId = NULL")
    suspend fun resetAllFaceIds()

    /** 获取未标记 AI 标签的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getUnlabeledMediaIds() / getUnlabeledMediaCount()")
    @Query("SELECT * FROM media_assets WHERE labels IS NULL OR labels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMedia(): List<MediaEntity>

    /** 仅获取未标记 AI 标签的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE labels IS NULL OR labels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMediaIds(): List<Long>

    /** 未标记 AI 标签的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE labels IS NULL OR labels = ''")
    suspend fun getUnlabeledMediaCount(): Int

    /** 更新媒体的 AI 标签 */
    @Query("UPDATE media_assets SET labels = :labels WHERE id = :mediaId")
    suspend fun updateLabels(mediaId: Long, labels: String)

    /** 更新媒体的 ML Kit 英文标签 */
    @Query("UPDATE media_assets SET mlKitLabels = :labels WHERE id = :mediaId")
    suspend fun updateMlKitLabels(mediaId: Long, labels: String)

    /** 更新媒体的 ML Kit 中文翻译标签 */
    @Query("UPDATE media_assets SET mlKitLabelsZh = :labels WHERE id = :mediaId")
    suspend fun updateMlKitLabelsZh(mediaId: Long, labels: String)

    /** 更新媒体的英文统一标签 JSON（labelsEn，tagger 原语） */
    @Query("UPDATE media_assets SET labelsEn = :labels WHERE id = :mediaId")
    suspend fun updateLabelsEn(mediaId: Long, labels: String)

    /** 更新媒体的中文统一标签 JSON（labelsZh，由 labelsEn 汉化派生） */
    @Query("UPDATE media_assets SET labelsZh = :labels WHERE id = :mediaId")
    suspend fun updateLabelsZh(mediaId: Long, labels: String)

    /** 重置所有 AI 标签（用于强制重新标记） */
    @Query("UPDATE media_assets SET labels = NULL")
    suspend fun resetAllLabels()

    /** 重置所有 ML Kit 标签（用于强制重新标记） */
    @Query("UPDATE media_assets SET mlKitLabels = NULL")
    suspend fun resetAllMlKitLabels()

    /** 重置所有 ML Kit 中文标签 */
    @Query("UPDATE media_assets SET mlKitLabelsZh = NULL")
    suspend fun resetAllMlKitLabelsZh()

    /** 按 ML Kit 英文标签搜索（精确匹配 JSON 数组元素，避免 Vacation 命中 cat 等子串误匹配） */
    @Query("SELECT * FROM media_assets WHERE mlKitLabels LIKE '%' || '\"' || :label || '\"' || '%' ORDER BY captureDate DESC")
    suspend fun searchByMlKitLabel(label: String): List<MediaEntity>

    /** 按 ML Kit 中文标签搜索（精确匹配 JSON 数组元素） */
    @Query("SELECT * FROM media_assets WHERE mlKitLabelsZh LIKE '%' || '\"' || :label || '\"' || '%' ORDER BY captureDate DESC")
    suspend fun searchByMlKitLabelZh(label: String): List<MediaEntity>

    /** 未生成 ML Kit 标签的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getUnlabeledMlKitMediaIds() / getUnlabeledMlKitMediaCount()")
    @Query("SELECT * FROM media_assets WHERE mlKitLabels IS NULL OR mlKitLabels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMlKitMedia(): List<MediaEntity>

    /** 仅获取未生成 ML Kit 标签的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE mlKitLabels IS NULL OR mlKitLabels = '' ORDER BY captureDate DESC")
    suspend fun getUnlabeledMlKitMediaIds(): List<Long>

    /** 未生成 ML Kit 标签的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE mlKitLabels IS NULL OR mlKitLabels = ''")
    suspend fun getUnlabeledMlKitMediaCount(): Int

    /** 已有 ML Kit 标签的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE mlKitLabels IS NOT NULL AND mlKitLabels != ''")
    suspend fun getMlKitLabeledCount(): Int

    // ── 人脸 ROI 结果持久化（3-Pass 混合管道）──────────────────

    /** 获取未检测人脸 ROI 的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithoutFaceRoiIds() / getMediaWithoutFaceRoiCount()")
    @Query("SELECT * FROM media_assets WHERE faceRoiResult IS NULL ORDER BY captureDate DESC")
    suspend fun getMediaWithoutFaceRoi(): List<MediaEntity>

    /** 仅获取未检测人脸 ROI 的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE faceRoiResult IS NULL AND type = 'PHOTO' ORDER BY captureDate DESC")
    suspend fun getMediaWithoutFaceRoiIds(): List<Long>

    /** 未检测人脸 ROI 的媒体数量 */
    // 注意 type = 'PHOTO'：人脸检测/语义编码只适用于照片，视频走 loadBitmap 会被 MIME 拦截返回 null，
    // faceRoiResult 永远写不进去。若不过滤，视频会永久计入“待 Pass 1”导致计数器永不归零。
    @Query("SELECT COUNT(*) FROM media_assets WHERE faceRoiResult IS NULL AND type = 'PHOTO'")
    suspend fun getMediaWithoutFaceRoiCount(): Int

    /** 获取已检测人脸 ROI 但未生成标签的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithFaceRoiWithoutLabelsIds()")
    @Query("SELECT * FROM media_assets WHERE faceRoiResult IS NOT NULL AND (labels IS NULL OR labels = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFaceRoiWithoutLabels(): List<MediaEntity>

    /** 仅获取已检测人脸 ROI 但未生成标签的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE faceRoiResult IS NOT NULL AND (labels IS NULL OR labels = '') ORDER BY captureDate DESC")
    suspend fun getMediaWithFaceRoiWithoutLabelsIds(): List<Long>

    /** 更新人脸 ROI 检测结果 */
    @Query("UPDATE media_assets SET faceRoiResult = :json, hasFace = :hasFace WHERE id = :mediaId")
    suspend fun updateFaceRoiResult(mediaId: Long, json: String, hasFace: Boolean)

    /** 更新人脸纵向聚焦点（Pass 1 检测 / 回填产出） */
    @Query("UPDATE media_assets SET faceFocusY = :faceFocusY WHERE id = :mediaId")
    suspend fun updateFaceFocusY(mediaId: Long, faceFocusY: Float?)

    /** 更新 NIMA 美学评分（后台美学打分器回写） */
    @Query("UPDATE media_assets SET aestheticScore = :score WHERE id = :mediaId")
    suspend fun updateAestheticScore(mediaId: Long, score: Float)

    /** 更新 eDifFIQA 人脸质量评分（后台打分器回写） */
    @Query("UPDATE media_assets SET faceQualityScore = :score WHERE id = :mediaId")
    suspend fun updateFaceQualityScore(mediaId: Long, score: Float)

    /** 取未评分的照片（限数，供后台美学打分器分批处理；照片优先、最新在前） */
    @Query("SELECT * FROM media_assets WHERE aestheticScore IS NULL AND type = 'PHOTO' ORDER BY captureDate DESC LIMIT :limit")
    suspend fun getMediaWithoutAestheticScore(limit: Int): List<MediaEntity>

    /** 取未做人脸画质评分的照片（限数，供 eDifFIQA 打分器分批处理） */
    @Query("SELECT * FROM media_assets WHERE faceQualityScore IS NULL AND type = 'PHOTO' ORDER BY captureDate DESC LIMIT :limit")
    suspend fun getMediaWithoutFaceQuality(limit: Int): List<MediaEntity>

    /** 取缺任一分（美学或人脸画质）的照片，供打分器一图两分、单次解码（照片优先、最新在前）。
     *  人脸画质分仅对 Pass 1 确认含人脸（hasFace = 1）的照片视为待打分：
     *  无脸照片永远写不进 faceQualityScore，若计入会让其按时间序永久堵住队首，挡住后面的待美学分照片。 */
    @Query("SELECT * FROM media_assets WHERE (aestheticScore IS NULL OR (faceQualityScore IS NULL AND hasFace = 1)) AND type = 'PHOTO' ORDER BY captureDate DESC LIMIT :limit")
    suspend fun getMediaWithoutEitherScore(limit: Int): List<MediaEntity>

    /** 待打分照片总数（口径同 [getMediaWithoutEitherScore]，供美学打分进度展示） */
    @Query("SELECT COUNT(*) FROM media_assets WHERE (aestheticScore IS NULL OR (faceQualityScore IS NULL AND hasFace = 1)) AND type = 'PHOTO'")
    suspend fun getPendingAestheticCount(): Int

    /** 已出 NIMA 美学分的照片数（美学打分进度统计） */
    @Query("SELECT COUNT(*) FROM media_assets WHERE aestheticScore IS NOT NULL AND type = 'PHOTO'")
    suspend fun getAestheticScoredCount(): Int

    /** 清空美学/人脸画质评分（打标控制页「美学评分-全量」重打分前置） */
    @Query("UPDATE media_assets SET aestheticScore = NULL, faceQualityScore = NULL")
    suspend fun clearAestheticScores()

    /** 含人脸但尚未回填 faceFocusY 的照片（供一次性回填扫描） */
    @Query("SELECT * FROM media_assets WHERE hasFace = 1 AND faceFocusY IS NULL AND type = 'PHOTO' ORDER BY captureDate DESC")
    suspend fun getMediaWithFacesWithoutFocus(): List<MediaEntity>

    /** 检查是否有已检测 ROI 但未完成标签的媒体 */
    @Query("SELECT COUNT(*) > 0 FROM media_assets WHERE faceRoiResult IS NOT NULL AND (labels IS NULL OR labels = '')")
    suspend fun hasPendingQwenTagging(): Boolean

    /** 获取 faceRoiResult 字段 */
    @Query("SELECT faceRoiResult FROM media_assets WHERE id = :mediaId")
    suspend fun getFaceRoiResult(mediaId: Long): String?

    // ── MobileCLIP 语义编码（已内联合并到 Pass 1）──────────────────────────

    /** 更新语义 embedding */
    @Query("UPDATE media_assets SET semanticEmbedding = :embedding WHERE id = :mediaId")
    suspend fun updateSemanticEmbedding(mediaId: Long, embedding: String)

    /** 获取未编码语义 embedding 的媒体（已有 labels 但无 semanticEmbedding）。用于单独重编码场景。 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaNeedingSemanticEncodingIds()")
    @Query("SELECT * FROM media_assets WHERE labels IS NOT NULL AND labels != '' AND semanticEmbedding IS NULL ORDER BY captureDate DESC")
    suspend fun getMediaNeedingSemanticEncoding(): List<MediaEntity>

    /** 仅获取未编码语义 embedding 的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE labels IS NOT NULL AND labels != '' AND semanticEmbedding IS NULL ORDER BY captureDate DESC")
    suspend fun getMediaNeedingSemanticEncodingIds(): List<Long>

    /** 获取指定 ID 的语义 embedding */
    @Query("SELECT semanticEmbedding FROM media_assets WHERE id = :mediaId")
    suspend fun getSemanticEmbedding(mediaId: Long): String?

    /** 获取所有有语义 embedding 的媒体 */
    @Deprecated("大数据量时易造成 Java Heap OOM，请优先使用 getMediaWithSemanticEmbeddingIds() / getMediaWithSemanticEmbeddingCount()")
    @Query("SELECT * FROM media_assets WHERE semanticEmbedding IS NOT NULL AND semanticEmbedding != '' ORDER BY captureDate DESC")
    suspend fun getMediaWithSemanticEmbedding(): List<MediaEntity>

    /** 仅获取有语义 embedding 的媒体 ID（内存友好） */
    @Query("SELECT id FROM media_assets WHERE semanticEmbedding IS NOT NULL AND semanticEmbedding != '' ORDER BY captureDate DESC")
    suspend fun getMediaWithSemanticEmbeddingIds(): List<Long>

    /** 有语义 embedding 的媒体数量 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE semanticEmbedding IS NOT NULL AND semanticEmbedding != ''")
    suspend fun getMediaWithSemanticEmbeddingCount(): Int

    /** 检查是否有待语义编码的媒体（用于单独重编码场景） */
    @Query("SELECT COUNT(*) > 0 FROM media_assets WHERE labels IS NOT NULL AND labels != '' AND semanticEmbedding IS NULL")
    suspend fun hasPendingSemanticEncoding(): Boolean

    // ── 整理中心（F1）────────────────────────────────────────

    /**
     * 整理中心类目判定的轻量投影（避开 semanticEmbedding/faceRoiResult 等大列）。
     * type 列存枚举名（Room 内建 enum 转换），投影按 String 读。
     */
    @Query(
        "SELECT uri, type, captureDate, ocrText, labels, hasFace, aestheticScore, " +
            "faceQualityScore, blurScore, exposureScore, lastViewedAt, faceId FROM media_assets"
    )
    fun observeOrganizeRows(): Flow<List<OrganizeRow>>

    /** 回写模糊/曝光分（整理中心 v2 惰性补算产出）。 */
    @Query("UPDATE media_assets SET blurScore = :blur, exposureScore = :exposure WHERE uri = :uri")
    suspend fun updateQualityScores(uri: String, blur: Float?, exposure: Float?)

    /** 批量回写模糊/曝光分：单事务一次 invalidation，避免 observe 流逐行重发射（整理中心 v2 惰性补算合批）。 */
    @Transaction
    suspend fun updateQualityScoresBatch(entries: List<QualityScoreEntry>) {
        entries.forEach { entry -> updateQualityScores(entry.uri, entry.blurScore, entry.exposureScore) }
    }

    /**
     * 回写最近一次查看时间（查看器打开时调用）。60s 节流窗（60000ms）消除 60s 内重复
     * 查看同一媒体的无效触发（新照片首看仍各触发一次，属固有信号更新）：
     * ValueGuard 只判 != null，守卫零语义损失。
     */
    @Query("UPDATE media_assets SET lastViewedAt = :timestamp WHERE uri = :uri AND (lastViewedAt IS NULL OR lastViewedAt < :timestamp - 60000)")
    suspend fun updateLastViewedAt(uri: String, timestamp: Long)

    /** 各人物聚类的照片总数（faceId → 计数），整理中心 v2 人物稀缺信号。 */
    @Query(
        "SELECT faceId, COUNT(*) AS cnt FROM media_assets " +
            "WHERE faceId IS NOT NULL AND faceId != '' GROUP BY faceId"
    )
    suspend fun getPersonPhotoCounts(): List<PersonPhotoCount>

    /** 重置所有语义 embedding（用于强制重新编码/清理污染数据） */
    @Query("UPDATE media_assets SET semanticEmbedding = NULL")
    suspend fun resetAllSemanticEmbeddings()

    // ── TAG 扫描去重字段（3-Pass 混合管道）────────────────────

    /** 从未成功 TAG 扫描的媒体数量（lastTagScanAt 为 NULL），供 tag.audit 统计扫描覆盖 */
    @Query("SELECT COUNT(*) FROM media_assets WHERE lastTagScanAt IS NULL")
    suspend fun getNeverTagScannedCount(): Int

    /** 最近一次 TAG 扫描成功时间戳（全表 MAX；无记录返回 null），供 tag.audit */
    @Query("SELECT MAX(lastTagScanAt) FROM media_assets")
    suspend fun getLatestTagScanAt(): Long?

    /** 按城市分组统计媒体数量（逆地理编码城市，供 JS gallery.stats_by_city） */
    @Query(
        "SELECT city, COUNT(*) as cnt FROM media_assets " +
            "WHERE city IS NOT NULL AND city != '' GROUP BY city ORDER BY cnt DESC LIMIT :limit"
    )
    suspend fun getCityGroups(limit: Int): List<CityGroupCount>

    /** 更新最近一次 TAG 扫描成功记录 */
    @Query(
        """
        UPDATE media_assets
        SET lastTagScanAt = :timestamp, lastTagScanPasses = :passesJson
        WHERE id = :mediaId
        """
    )
    suspend fun updateLastTagScan(mediaId: Long, timestamp: Long, passesJson: String)

    /** 按拍摄时间降序获取候选媒体（newest-first） */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY captureDate DESC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanNewest(before: Long, limit: Int): List<MediaEntity>

    /** 按拍摄时间升序获取候选媒体（oldest-first） */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY captureDate ASC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanOldest(before: Long, limit: Int): List<MediaEntity>

    /**
     * 仅获取 id + lastTagScanPasses 的轻量投影，用于自动增量扫描的去重过滤。
     * 避免一次性加载 faceRoiResult / semanticEmbedding 等大字段到 Java Heap。
     */
    data class TagScanCandidateProjection(
        val id: Long,
        val lastTagScanPasses: String?
    )

    /** 按拍摄时间降序获取候选媒体 ID 与已扫描 Passes（newest-first） */
    @Query(
        """
        SELECT id, lastTagScanPasses FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
          AND type = 'PHOTO'
        ORDER BY captureDate DESC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanNewestProjection(
        before: Long,
        limit: Int
    ): List<TagScanCandidateProjection>

    /** 按拍摄时间升序获取候选媒体 ID 与已扫描 Passes（oldest-first） */
    @Query(
        """
        SELECT id, lastTagScanPasses FROM media_assets
        WHERE (lastTagScanAt IS NULL OR lastTagScanAt < :before)
          AND type = 'PHOTO'
        ORDER BY captureDate ASC, lastTagScanAt ASC
        LIMIT :limit
        """
    )
    suspend fun getMediaForIncrementalScanOldestProjection(
        before: Long,
        limit: Int
    ): List<TagScanCandidateProjection>

    /** 获取指定 ID 中最近扫描时间早于阈值的照片 */
    @Query(
        """
        SELECT * FROM media_assets
        WHERE id IN (:ids)
          AND (lastTagScanAt IS NULL OR lastTagScanAt < :before)
        ORDER BY lastTagScanAt ASC, captureDate ASC
        """
    )
    suspend fun filterMediaNeedingScan(ids: List<Long>, before: Long): List<MediaEntity>

    // ── 显式约束优先搜索：候选集内查询 ─────────────────────

    /** 按时间范围获取媒体 ID */
    @Query("SELECT id FROM media_assets WHERE captureDate BETWEEN :startMs AND :endMs")
    suspend fun getMediaIdsByTimeRange(startMs: Long, endMs: Long): List<Long>

    /** 按地点关键词获取媒体 ID */
    @Query("SELECT id FROM media_assets WHERE locationName LIKE '%' || :keyword || '%'")
    suspend fun getMediaIdsByLocationKeyword(keyword: String): List<Long>

    /** 按人脸标记获取媒体 ID */
    @Query("SELECT id FROM media_assets WHERE hasFace = 1")
    suspend fun getMediaIdsByHasFace(): List<Long>

    /** 在指定 ID 列表中搜索标签 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND labels LIKE '%' || :keyword || '%'")
    suspend fun searchLabelsInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中按标签搜索（labels/labelsEn/labelsZh 三字段 OR） */
    @Query(
        "SELECT * FROM media_assets WHERE id IN (:ids) AND (" +
            "labels LIKE '%' || :keyword || '%' " +
            "OR labelsEn LIKE '%' || :keyword || '%' " +
            "OR labelsZh LIKE '%' || :keyword || '%')"
    )
    suspend fun searchLabelsAllFieldsInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索 ML Kit 英文标签 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND mlKitLabels LIKE '%' || :keyword || '%'")
    suspend fun searchMlKitLabelsInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索 ML Kit 中文标签 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND mlKitLabelsZh LIKE '%' || :keyword || '%'")
    suspend fun searchMlKitLabelsZhInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索英文统一标签（labelsEn） */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND labelsEn LIKE '%' || :keyword || '%'")
    suspend fun searchLabelsEnInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索中文统一标签（labelsZh） */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND labelsZh LIKE '%' || :keyword || '%'")
    suspend fun searchLabelsZhInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索 OCR */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND ocrText LIKE '%' || :keyword || '%'")
    suspend fun searchOcrInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /** 在指定 ID 列表中搜索文件名 */
    @Query("SELECT * FROM media_assets WHERE id IN (:ids) AND fileName LIKE '%' || :keyword || '%'")
    suspend fun searchFileNameInIds(ids: List<Long>, keyword: String): List<MediaEntity>

    /**
     * 从备份批量更新 TAG 相关元数据字段。
     * 一次性写入 labels / mlKitLabels / mlKitLabelsZh / ocrText / 地理位置 /
     * faceRoiResult / semanticEmbedding / lastTagScanAt / lastTagScanPasses /
     * hasFace / faceId / city / faceFocusY / aestheticScore / faceQualityScore /
     * blurScore / exposureScore / lastViewedAt（整理中心 v2 信号，旧备份缺省为 null 覆盖），
     * 避免还原时多次 UPDATE。
     */
    @Query(
        """
        UPDATE media_assets SET
            labels = :labels,
            labelsEn = :labelsEn,
            labelsZh = :labelsZh,
            mlKitLabels = :mlKitLabels,
            mlKitLabelsZh = :mlKitLabelsZh,
            ocrText = :ocrText,
            latitude = :latitude,
            longitude = :longitude,
            locationName = :locationName,
            faceRoiResult = :faceRoiResult,
            semanticEmbedding = :semanticEmbedding,
            lastTagScanAt = :lastTagScanAt,
            lastTagScanPasses = :lastTagScanPasses,
            hasFace = :hasFace,
            faceId = :faceId,
            city = :city,
            faceFocusY = :faceFocusY,
            aestheticScore = :aestheticScore,
            faceQualityScore = :faceQualityScore,
            blurScore = :blurScore,
            exposureScore = :exposureScore,
            lastViewedAt = :lastViewedAt
        WHERE id = :mediaId
        """
    )
    @Suppress("LongParameterList") // 待重构：改为 data class 入参
    suspend fun updateTagMetadataFromBackup(
        mediaId: Long,
        labels: String?,
        labelsEn: String?,
        labelsZh: String?,
        mlKitLabels: String?,
        mlKitLabelsZh: String?,
        ocrText: String?,
        latitude: Double?,
        longitude: Double?,
        locationName: String?,
        faceRoiResult: String?,
        semanticEmbedding: String?,
        lastTagScanAt: Long?,
        lastTagScanPasses: String?,
        hasFace: Boolean,
        faceId: String?,
        city: String?,
        faceFocusY: Float?,
        aestheticScore: Float?,
        faceQualityScore: Float?,
        blurScore: Float?,
        exposureScore: Float?,
        lastViewedAt: Long?
    )
}

data class FaceGroupCount(
    val faceId: Int,
    val cnt: Int
)

data class CityGroupCount(
    val city: String,
    val cnt: Int
)

/** 整理中心轻量投影行：仅类目判定所需列（大列 embedding/faceRoiResult 不加载）。 */
data class OrganizeRow(
    val uri: String,
    val type: String,
    val captureDate: Long,
    val ocrText: String?,
    val labels: String?,
    val hasFace: Boolean,
    val aestheticScore: Float?,
    val faceQualityScore: Float?,
    val blurScore: Float?,
    val exposureScore: Float?,
    val lastViewedAt: Long?,
    val faceId: String?,
)

/** 人物聚类照片计数投影（整理中心 v2 人物稀缺信号）。 */
data class PersonPhotoCount(
    val faceId: String,
    val cnt: Int,
)

/** 模糊/曝光分批量回写条目（整理中心 v2 惰性补算产出，计算成功的非空分）。 */
data class QualityScoreEntry(
    val uri: String,
    val blurScore: Float,
    val exposureScore: Float,
)
