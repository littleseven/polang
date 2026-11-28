package com.mamba.picme.server.auth

import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * 设备级匿名访客试用额度。与 [AccountService] 的账号额度同构（check/increment/revert），
 * 但按 deviceId 计量、limit 由服务端配置（非每行）。访客调用不写 llm_call_log，
 * 其用量以本表为唯一事实源。
 */
object GuestService {

    data class GuestQuotaResult(val allowed: Boolean, val remaining: Int)

    suspend fun checkAndIncrementQuota(deviceId: String, limit: Int, platform: String? = null): GuestQuotaResult {
        val now = Instant.now().toEpochMilli()
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = AnonymousDevices.selectAll()
                .where { AnonymousDevices.deviceId eq deviceId }
                .firstOrNull()

            if (row == null) {
                if (limit <= 0) return@newSuspendedTransaction GuestQuotaResult(false, 0)
                AnonymousDevices.insert {
                    it[AnonymousDevices.deviceId] = deviceId
                    it[AnonymousDevices.llmCallsUsed] = 1
                    it[AnonymousDevices.createdAt] = now
                    it[AnonymousDevices.lastSeenAt] = now
                    it[AnonymousDevices.platform] = platform
                }
                GuestQuotaResult(true, (limit - 1).coerceAtLeast(0))
            } else {
                val used = row[AnonymousDevices.llmCallsUsed]
                // platform 只在建行时写入：老数据/老客户端（无 X-Platform 头）建的行永远 NULL，
                // 这里对 NULL 行顺手回填一次（自愈），已有值不覆盖
                val backfillPlatform = platform != null && row[AnonymousDevices.platform] == null
                if (used >= limit) {
                    AnonymousDevices.update({ AnonymousDevices.id eq row[AnonymousDevices.id] }) {
                        it[AnonymousDevices.lastSeenAt] = now
                        if (backfillPlatform) it[AnonymousDevices.platform] = platform
                    }
                    GuestQuotaResult(false, 0)
                } else {
                    AnonymousDevices.update({ AnonymousDevices.id eq row[AnonymousDevices.id] }) {
                        with(SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed + 1 }
                        it[AnonymousDevices.lastSeenAt] = now
                        if (backfillPlatform) it[AnonymousDevices.platform] = platform
                    }
                    GuestQuotaResult(true, (limit - used - 1).coerceAtLeast(0))
                }
            }
        }
    }

    suspend fun revertQuota(deviceId: String) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val row = AnonymousDevices.selectAll()
                .where { AnonymousDevices.deviceId eq deviceId }
                .firstOrNull() ?: return@newSuspendedTransaction
            if (row[AnonymousDevices.llmCallsUsed] > 0) {
                AnonymousDevices.update({ AnonymousDevices.id eq row[AnonymousDevices.id] }) {
                    with(SqlExpressionBuilder) { it[llmCallsUsed] = llmCallsUsed - 1 }
                }
            }
        }
    }

    /** 读取剩余额度（不增量），用于 X-Guest-Remaining 响应头。 */
    suspend fun remainingReadOnly(deviceId: String, limit: Int): Int {
        return newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val used = AnonymousDevices.selectAll()
                .where { AnonymousDevices.deviceId eq deviceId }
                .firstOrNull()?.get(AnonymousDevices.llmCallsUsed) ?: 0
            (limit - used).coerceAtLeast(0)
        }
    }

    /** 删除某 deviceId 的访客记录（数据删除合规：用户可清除其设备级访客数据）。 */
    suspend fun deleteByDeviceId(deviceId: String) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.deleteWhere { with(SqlExpressionBuilder) { AnonymousDevices.deviceId eq deviceId } }
        }
    }

    /** 按 database id 删除访客记录（管理后台用;与 [deleteByDeviceId] 并列）。 */
    suspend fun deleteById(id: Int) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.deleteWhere { with(SqlExpressionBuilder) { AnonymousDevices.id eq id } }
        }
    }

    /** 管理员重置某访客设备的已用额度（按数据库 id）。与账号 resetQuota 同义：清零计数、保留设备记录。 */
    suspend fun resetQuota(id: Int) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            AnonymousDevices.update({ AnonymousDevices.id eq id }) {
                it[AnonymousDevices.llmCallsUsed] = 0
            }
        }
    }
}
