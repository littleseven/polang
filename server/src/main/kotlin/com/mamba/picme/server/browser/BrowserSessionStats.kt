package com.mamba.picme.server.browser

import com.mamba.picme.server.db.BrowserSessions
import com.mamba.picme.server.db.Db
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

/** browser 会话统计落库（spec §7 管理后台「概览」数据源）。 */
class BrowserSessionStats {

    suspend fun recordOpen(tokenHash: String, sessionId: String, now: Long = System.currentTimeMillis()) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            BrowserSessions.insert {
                it[BrowserSessions.tokenHash] = tokenHash
                it[BrowserSessions.sessionId] = sessionId
                it[startedAt] = now
            }
        }
    }

    suspend fun recordClose(sessionId: String, outcome: String, now: Long = System.currentTimeMillis()) {
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            BrowserSessions.update({ (BrowserSessions.sessionId eq sessionId) and BrowserSessions.endedAt.isNull() }) {
                it[endedAt] = now
                it[BrowserSessions.outcome] = outcome
            }
        }
    }
}
