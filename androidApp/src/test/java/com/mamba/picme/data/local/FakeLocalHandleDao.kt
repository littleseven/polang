package com.mamba.picme.data.local

/**
 * 内存版 [LocalHandleDao] 共享测试夹具（CloudAgentGalleryToolsTest /
 * CloudAgentChatViewModelTest / LocalHandleStoreTest 三方复用，避免逐处复制）。
 */
class FakeLocalHandleDao : LocalHandleDao {
    val rows = mutableMapOf<String, LocalHandleEntity>()
    override suspend fun upsert(handle: LocalHandleEntity) {
        rows[handle.handleId] = handle
    }
    override suspend fun byId(handleId: String): LocalHandleEntity? = rows[handleId]
    override suspend fun deleteOlderThan(beforeMs: Long): Int {
        val doomed = rows.values.filter { row -> row.createdAt < beforeMs }.map { row -> row.handleId }
        doomed.forEach { handleId -> rows.remove(handleId) }
        return doomed.size
    }
}
