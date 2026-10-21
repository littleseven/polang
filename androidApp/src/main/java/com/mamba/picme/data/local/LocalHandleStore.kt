package com.mamba.picme.data.local

import java.security.SecureRandom

/** HandleResolver 的取数接缝（Task 7 单测 fake 此接口）。 */
interface HandleLookup {
    suspend fun payloadOf(domain: String, handleId: String): String?
}

/**
 * opaque handle（`handle: <domain>/<id>`）注册/解析入口。handleId = 12 位随机 hex（对云 opaque，无可猜语义）。
 */
class LocalHandleStore(
    private val dao: LocalHandleDao,
    private val random: SecureRandom = SecureRandom(),
) : HandleLookup {

    suspend fun put(domain: String, payloadJson: String): String {
        val handleId = random.nextLong().toULong().toString(16).takeLast(12).padStart(12, '0')
        dao.upsert(
            LocalHandleEntity(
                handleId = handleId,
                domain = domain,
                payloadJson = payloadJson,
                createdAt = System.currentTimeMillis(),
            )
        )
        return handleId
    }

    override suspend fun payloadOf(domain: String, handleId: String): String? {
        val row = dao.byId(handleId) ?: return null
        return row.payloadJson.takeIf { row.domain == domain }
    }
}
