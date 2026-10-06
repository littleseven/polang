package com.mamba.picme.server.config

import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.ServerSettings
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * 运行时可调服务端设置的内存快照。读命中 [@Volatile] 快照（热路径零 DB 读，SQLite 单连接安全）；
 * 写（后台 /admin/settings）在同一事务内 UPSERT 并重灌快照，立即对后续请求生效。
 *
 * 当前承载三项：[KEY_FREE] / [KEY_GUEST] / [KEY_BROWSER_DAILY]。env 仅在首次播种时用作默认值，之后以 server_setting 表为准。
 */
object SettingsService {
    const val KEY_FREE = "free_llm_quota"
    const val KEY_GUEST = "guest_llm_quota"
    const val KEY_BROWSER_DAILY = "browser_daily_quota"

    data class Snapshot(val freeLlmQuota: Int, val guestLlmQuota: Int, val browserDailyQuota: Int)

    @Volatile
    private var current = Snapshot(freeLlmQuota = 1000, guestLlmQuota = 100, browserDailyQuota = 20)

    fun snapshot(): Snapshot = current

    /** 启动时从 DB 灌入快照；行缺失时保留默认值（首次启动尚未 seed 的兜底）。 */
    suspend fun load() {
        current = newSuspendedTransaction(Dispatchers.IO, Db.instance) { readAll() }
    }

    /**
     * 更新额度默认值；null 表示该项不改。UPSERT 命中行后重灌快照，返回新快照。
     */
    suspend fun update(free: Int?, guest: Int?, browserDailyQuota: Int? = null): Snapshot {
        current = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val now = Instant.now().toEpochMilli()
            free?.let { upsert(KEY_FREE, it, now) }
            guest?.let { upsert(KEY_GUEST, it, now) }
            browserDailyQuota?.let { upsert(KEY_BROWSER_DAILY, it, now) }
            readAll()
        }
        return current
    }

    private fun readAll(): Snapshot {
        val rows = ServerSettings.selectAll().associate { it[ServerSettings.key] to it[ServerSettings.value] }
        return Snapshot(
            freeLlmQuota = rows[KEY_FREE] ?: 1000,
            guestLlmQuota = rows[KEY_GUEST] ?: 100,
            browserDailyQuota = rows[KEY_BROWSER_DAILY] ?: 20,
        )
    }

    private fun upsert(key: String, value: Int, now: Long) {
        val updated = ServerSettings.update({ ServerSettings.key eq key }) {
            it[ServerSettings.value] = value
            it[ServerSettings.updatedAt] = now
        }
        if (updated == 0) {
            ServerSettings.insert {
                it[ServerSettings.key] = key
                it[ServerSettings.value] = value
                it[ServerSettings.updatedAt] = now
            }
        }
    }
}
