package com.mamba.picme.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mamba.picme.data.local.entity.FaceEmbeddingEntity
import com.mamba.picme.data.local.entity.PersonEntity
import com.mamba.picme.data.model.MediaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonDao {

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    @Update
    suspend fun updatePerson(person: PersonEntity)

    @Query("UPDATE persons SET name = :name, updatedAt = :now WHERE personId = :personId")
    suspend fun updatePersonName(personId: Long, name: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET faceCount = :faceCount, coverMediaId = :coverMediaId, updatedAt = :now WHERE personId = :personId")
    suspend fun updatePersonStats(personId: Long, faceCount: Int, coverMediaId: Long?, now: Long = System.currentTimeMillis())

    @Query("SELECT COUNT(*) FROM persons WHERE name IS NOT NULL AND name != ''")
    suspend fun getNamedPersonCount(): Int

    @Query("SELECT * FROM persons ORDER BY faceCount DESC")
    suspend fun getAllPersons(): List<PersonEntity>

    /** 观察全部人物（回忆生成器输入：命名/self 标记变化均触发重发）。 */
    @Query("SELECT * FROM persons")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT COUNT(*) FROM persons")
    suspend fun getPersonCount(): Int

    @Query("SELECT * FROM persons WHERE personId = :personId")
    suspend fun getPerson(personId: Long): PersonEntity?

    /** 按人物名称模糊匹配（用于自然语言搜索中按人名找照片） */
    @Query("SELECT * FROM persons WHERE name LIKE '%' || :name || '%' LIMIT 1")
    suspend fun findPersonByName(name: String): PersonEntity?

    @Query("UPDATE persons SET faceCount = faceCount + 1, updatedAt = :now WHERE personId = :personId")
    suspend fun incrementFaceCount(personId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET faceCount = MAX(faceCount - 1, 0), updatedAt = :now WHERE personId = :personId")
    suspend fun decrementFaceCount(personId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET coverMediaId = :coverMediaId, updatedAt = :now WHERE personId = :personId")
    suspend fun updateCoverMedia(personId: Long, coverMediaId: Long, now: Long = System.currentTimeMillis())

    @Query(
        """
        SELECT DISTINCT m.* FROM media_assets m
        INNER JOIN face_embeddings e ON m.id = e.mediaId
        WHERE e.personId = :personId
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun getMediaByPerson(personId: Long): List<MediaEntity>

    /**
     * 按人物取媒体，优先返回单人照（同媒体人脸数少者优先），再按拍摄时间倒序。
     * 供封面选择，避免选中合影。
     */
    @Query(
        """
        SELECT DISTINCT m.* FROM media_assets m
        INNER JOIN face_embeddings e ON m.id = e.mediaId
        WHERE e.personId = :personId
        GROUP BY m.id
        ORDER BY COUNT(e.embeddingId) ASC, m.captureDate DESC
        """
    )
    suspend fun getMediaByPersonOrderedForCover(personId: Long): List<MediaEntity>

    /** 按 personId 取媒体 id（轻量，仅供 gallery.query 的 person 过滤做交集）。 */
    @Query("SELECT DISTINCT mediaId FROM face_embeddings WHERE personId = :personId")
    suspend fun getMediaIdsByPerson(personId: Long): List<Long>

    /**
     * 多人物共现查询：返回同时包含所有指定人物的媒体
     * （HAVING COUNT(DISTINCT personId) = 传入 ids 数，确保每人都出现）
     */
    @Query(
        """
        SELECT m.* FROM media_assets m
        WHERE m.id IN (
            SELECT mediaId FROM face_embeddings
            WHERE personId IN (:personIds)
            GROUP BY mediaId
            HAVING COUNT(DISTINCT personId) = :personCount
        )
        ORDER BY m.captureDate DESC
        """
    )
    suspend fun getMediaByPersonsCooccurrence(personIds: List<Long>, personCount: Int): List<MediaEntity>

    /** 标记/取消"我"本人（is_self 列） */
    @Query("UPDATE persons SET is_self = :isSelf, updatedAt = :now WHERE personId = :personId")
    suspend fun setSelf(personId: Long, isSelf: Boolean, now: Long = System.currentTimeMillis())

    /** 全局唯一"我"约束：设置新 self 前清除旧标记 */
    @Query("UPDATE persons SET is_self = 0 WHERE is_self = 1")
    suspend fun clearSelfFlags()

    @Query("SELECT * FROM persons WHERE is_self = 1 LIMIT 1")
    suspend fun getSelfPerson(): PersonEntity?

    /** 观察"我"本人（is_self = 1）：标记/取消、改名、封面变更、重聚恢复均触发重发 */
    @Query("SELECT * FROM persons WHERE is_self = 1 LIMIT 1")
    fun observeSelfPerson(): Flow<PersonEntity?>

    @Insert
    suspend fun insertEmbedding(embedding: FaceEmbeddingEntity): Long

    @Insert
    suspend fun insertEmbeddings(embeddings: List<FaceEmbeddingEntity>): List<Long>

    @Query("SELECT * FROM face_embeddings WHERE personId = :personId")
    suspend fun getEmbeddingsByPerson(personId: Long): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings WHERE personId IS NULL")
    suspend fun getUnassignedEmbeddings(): List<FaceEmbeddingEntity>

    @Query("SELECT * FROM face_embeddings")
    suspend fun getAllEmbeddings(): List<FaceEmbeddingEntity>

    @Query("UPDATE face_embeddings SET personId = :personId WHERE embeddingId = :embeddingId")
    suspend fun assignEmbedding(embeddingId: Long, personId: Long)

    /** 未分配人物的 embedding 数量（内存友好的 COUNT，替代 getUnassignedEmbeddings().size） */
    @Query("SELECT COUNT(*) FROM face_embeddings WHERE personId IS NULL")
    suspend fun getUnassignedEmbeddingCount(): Int

    @Query("SELECT COUNT(*) FROM face_embeddings")
    suspend fun getAllEmbeddingCount(): Int

    @Query("SELECT COUNT(*) FROM face_embeddings WHERE personId = :personId")
    suspend fun getEmbeddingCount(personId: Long): Int

    @Query("DELETE FROM persons WHERE personId = :personId")
    suspend fun deletePerson(personId: Long)

    @Query("UPDATE face_embeddings SET personId = NULL WHERE personId = :personId")
    suspend fun unlinkEmbeddings(personId: Long)

    @Query("SELECT * FROM face_embeddings WHERE mediaId = :mediaId")
    suspend fun getEmbeddingsByMedia(mediaId: Long): List<FaceEmbeddingEntity>

    @Query("DELETE FROM face_embeddings WHERE mediaId = :mediaId")
    suspend fun deleteEmbeddingsByMedia(mediaId: Long)

    /** 按 mediaId 列表批量删除 embedding（媒体删除级联清理用） */
    @Query("DELETE FROM face_embeddings WHERE mediaId IN (:mediaIds)")
    suspend fun deleteEmbeddingsByMediaIds(mediaIds: List<Long>)

    /** 按 mediaId 批量更新 personId（用于聚类后分配） */
    @Query("UPDATE face_embeddings SET personId = :personId WHERE mediaId = :mediaId")
    suspend fun assignEmbeddingByMediaId(mediaId: Long, personId: Long)

    /** 按 mediaId 列表批量更新 personId（避免逐条更新阻塞主流程） */
    @Query("UPDATE face_embeddings SET personId = :personId WHERE mediaId IN (:mediaIds)")
    suspend fun assignEmbeddingsByMediaIds(mediaIds: List<Long>, personId: Long)

    /** 清空 face_embeddings 和 persons 表（不删除 trigger 依赖的表） */
    @Query("DELETE FROM face_embeddings")
    suspend fun clearAllEmbeddings()

    @Query("DELETE FROM persons")
    suspend fun clearAllPersons()

    /** 重置所有 embedding 的 personId 为 NULL（重聚类前调用） */
    @Query("UPDATE face_embeddings SET personId = NULL")
    suspend fun resetAllEmbeddingAssignments()

    // ── persons 表对齐（reconcile）──────────────────────────────────────
    // 媒体删除 / Pass1 重检测 / 增量聚类 都可能让 persons 表与 face_embeddings、
    // media_assets 失联：孤儿人物行、faceCount 失配、coverMediaId 悬空。
    // 以下 4 步幂等，可重复执行；由 [reconcilePersons] 在单事务内完成。

    /** 删除指向已不存在媒体的 embedding（媒体已被删但 embedding 残留） */
    @Query("DELETE FROM face_embeddings WHERE mediaId NOT IN (SELECT id FROM media_assets)")
    suspend fun deleteOrphanEmbeddings()

    /** 删除无任何 embedding 的孤儿人物（无照片即无人物） */
    @Query(
        """
        DELETE FROM persons WHERE personId NOT IN (
            SELECT DISTINCT personId FROM face_embeddings WHERE personId IS NOT NULL
        )
        """
    )
    suspend fun deleteOrphanPersons()

    /** 用真实 embedding 数重算 faceCount（修正历史失配计数） */
    @Query(
        """
        UPDATE persons SET faceCount = (
            SELECT COUNT(*) FROM face_embeddings fe WHERE fe.personId = persons.personId
        ), updatedAt = :now
        """
    )
    suspend fun recomputeFaceCounts(now: Long)

    /** 修复悬空 coverMediaId：改指本簇任一存活 embedding 的 mediaId */
    @Query(
        """
        UPDATE persons SET coverMediaId = (
            SELECT fe.mediaId FROM face_embeddings fe
            WHERE fe.personId = persons.personId
            ORDER BY fe.mediaId LIMIT 1
        )
        WHERE coverMediaId IS NULL OR coverMediaId NOT IN (SELECT id FROM media_assets)
        """
    )
    suspend fun fixDanglingCovers()

    /** 合并 personB→personA 时，把媒体上指向 personB 的 faceId 改指 personA。 */
    @Query("UPDATE media_assets SET faceId = :newPersonId WHERE faceId = :oldPersonId")
    suspend fun reassignMediaFaceId(oldPersonId: String, newPersonId: String)

    /** 拆分 pass：把指定一组媒体的 faceId 改指新 person（拆出子团时用）。 */
    @Query("UPDATE media_assets SET faceId = :personId WHERE id IN (:mediaIds)")
    suspend fun setMediaFaceIds(mediaIds: List<Long>, personId: String)

    /**
     * 修复悬空 faceId：媒体 faceId 指向已删除的 person（被合并），改指该媒体 embedding 现属 person；
     * 无 embedding 归属则置空。幂等，在 [reconcilePersons] 内随人物页进入/聚类完成触发。
     */
    @Query(
        """
        UPDATE media_assets SET faceId = (
            SELECT CAST(fe.personId AS TEXT) FROM face_embeddings fe
            WHERE fe.mediaId = media_assets.id AND fe.personId IS NOT NULL
            ORDER BY fe.embeddingId LIMIT 1
        )
        WHERE faceId IS NOT NULL AND faceId != ''
        AND CAST(faceId AS INTEGER) NOT IN (SELECT personId FROM persons)
        """
    )
    suspend fun reconcileDanglingFaceIds()

    /**
     * 回填缺失 faceId：媒体有人脸且 embedding 已分配人物、但 faceId 为空时，
     * 改指该媒体首个已分配 embedding 的人物（与 [reconcileDanglingFaceIds] 同一取值口径）。
     * 修复流式增量聚类历史版本不回写 media_assets.faceId 留下的存量（相册人物分组漏组）。幂等。
     */
    @Query(
        """
        UPDATE media_assets SET faceId = (
            SELECT CAST(fe.personId AS TEXT) FROM face_embeddings fe
            WHERE fe.mediaId = media_assets.id AND fe.personId IS NOT NULL
            ORDER BY fe.embeddingId LIMIT 1
        )
        WHERE (faceId IS NULL OR faceId = '') AND hasFace = 1
        AND EXISTS (
            SELECT 1 FROM face_embeddings fe2
            WHERE fe2.mediaId = media_assets.id AND fe2.personId IS NOT NULL
        )
        """
    )
    suspend fun backfillFaceIdsFromEmbeddings()

    /**
     * 单事务对齐 persons 表：清孤儿 embedding → 删孤儿人物 → 重算 faceCount → 修悬空封面 →
     * 回填缺失 faceId → 修悬空 faceId。
     *
     * 调用时机：进入人物页前、聚类（DBSCAN）完成后、媒体删除后。幂等。
     */
    @Transaction
    suspend fun reconcilePersons(now: Long = System.currentTimeMillis()) {
        deleteOrphanEmbeddings()
        deleteOrphanPersons()
        recomputeFaceCounts(now)
        fixDanglingCovers()
        backfillFaceIdsFromEmbeddings()
        reconcileDanglingFaceIds()
    }

    /** 按人物统计包含 TA 的照片数（去重 mediaId），用于未命名人物的外显张数。 */
    @Query(
        """
        SELECT personId, COUNT(DISTINCT mediaId) AS count FROM face_embeddings
        WHERE personId IS NOT NULL
        GROUP BY personId
        """
    )
    suspend fun getDistinctMediaCounts(): List<PersonMediaCount>

    /**
     * 人物关联媒体 ID 全集：人脸聚类媒体 ∪ 三字段标签提及媒体（[name] 非空时才生效）。
     *
     * 人物页外显张数与点开后的详情列表（GalleryScreen.applyPersonFilter）共用这一口径，
     * 保证两处数字一致。人名是精确约束，不走搜索引擎：语义/OCR 泛化召回会混入本口径之外
     * 的照片，且对每个命名人物跑全量 search() 会并行拖入 MobileCLIP/OPUS-MT 初始化，
     * 在 256MB 堆上引发 OOM（2026-08-01 事故）。
     *
     * @param name 人物名；空串时标签提及分支自动失效，仅返回聚类媒体（未命名人物口径不变）
     */
    @Query(
        """
        SELECT DISTINCT mediaId FROM face_embeddings WHERE personId = :personId
        UNION
        SELECT id FROM media_assets WHERE :name != '' AND (
            labels LIKE '%' || :name || '%'
            OR labelsEn LIKE '%' || :name || '%'
            OR labelsZh LIKE '%' || :name || '%'
        )
        """
    )
    suspend fun getPersonMediaIds(personId: Long, name: String): List<Long>
}

/** 人物 → 去重照片数（与 faceCount 不同，faceCount 是人脸 embedding 数）。 */
data class PersonMediaCount(
    val personId: Long,
    val count: Int
)
