package com.mamba.picme.server.admin

import com.mamba.picme.server.analytics.Price
import com.mamba.picme.server.analytics.defaultPrices
import com.mamba.picme.server.db.AnonymousDevices
import com.mamba.picme.server.db.Accounts
import com.mamba.picme.server.db.ApkUploads
import com.mamba.picme.server.db.BrowserSessions
import com.mamba.picme.server.db.Db
import com.mamba.picme.server.db.IosUdidRegistrations
import com.mamba.picme.server.db.LlmCallLogs
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Instant
import java.time.ZoneOffset

// ── DTO ──────────────────────────────────────────────

data class OverviewRow(
    // 今日
    val totalUsers: Long,
    val newUsersToday: Long,
    val callsToday: Long,
    val tokensToday: Long,
    val costToday: Double,
    val bytesToday: Long,
    val blockedToday: Long,
    // 累计（新增）
    val totalDevices: Long,
    val totalCalls: Long,
    val totalTokens: Long,
    val totalCost: Double,
    // 新增：今日 error + 今日/昨日新增设备 + 昨日环比
    val errorsToday: Long = 0,
    val newDevicesToday: Long = 0,
    val callsYest: Long = 0,
    val tokensYest: Long = 0,
    val costYest: Double = 0.0,
    val blockedYest: Long = 0,
    val errorsYest: Long = 0,
    val newUsersYest: Long = 0,
    val newDevicesYest: Long = 0,
)

data class DayBucket(
    val day: String, // YYYY-MM-DD (UTC)
    val calls: Long,
    val blocked: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val totalTokens: Long,
    val cost: Double,
    val bytes: Long,
    val errors: Long = 0,
)

data class UserRow(
    val id: Int,
    val email: String,
    val status: String,
    val createdAt: Long,
    val calls: Long,
    val totalTokens: Long,
    val cost: Double,
    val lastActive: Long?,
    val apiTokenMasked: String,
    val hasToken: Boolean,
    val deviceIdMasked: String,
    val lastPlatform: String,
)

data class UserDetail(
    val id: Int,
    val email: String,
    val status: String,
    val createdAt: Long,
    val llmCallsUsed: Int,
    val llmCallsLimit: Int,
    val calls: Long,
    val totalTokens: Long,
    val cost: Double,
    val blocked: Long,
    val bytes: Long,
    val lastActive: Long?,
)

data class CallRow(
    val id: Long,
    val model: String,
    val provider: String,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val costCny: Double,
    val respBytes: Int,
    val status: String,
    val latencyMs: Int?,
    val createdAt: Long,
)

data class DeviceRow(
    val id: Int,
    val deviceIdMasked: String,
    val llmCallsUsed: Int,
    val createdAt: Long,
    val lastSeenAt: Long,
    val platform: String?,
)

data class ChannelUsage(
    val provider: String,
    val calls: Long,
    val tokens: Long,
    val cost: Double,
)

data class DimStat(val key: String, val calls: Long, val tokens: Long, val cost: Double)

data class TopStat(val id: Int, val label: String, val calls: Long, val tokens: Long, val cost: Double)

data class LatencyStats(val count: Int, val p50: Int, val p95: Int)

data class CostSplit(val promptCost: Double, val completionCost: Double)

data class BrowserOverview(
    val todayCount: Long,
    val activeCount: Long,
    val failureRate7d: Double,
)

data class RangeStats(
    val days: List<DayBucket>,
    val byModel: List<DimStat>,
    val byProvider: List<DimStat>,
    val topUsers: List<TopStat>,
    val topDevices: List<TopStat>,
    val latency: LatencyStats,
    val totals: DayBucket,
    val costSplit: CostSplit = CostSplit(0.0, 0.0),
)

// ── Queries（自然日按 UTC+8；内部后台够用。聚合在内存做，trial 规模毫秒级）──

object AdminQueries {
    private const val DAY_MS = 86_400_000L

    private val CN = ZoneOffset.ofHours(8)

    private fun startOfDayMs(ms: Long): Long =
        Instant.ofEpochMilli(ms).atZone(CN).toLocalDate().atStartOfDay(CN).toInstant().toEpochMilli()

    private fun startOfTodayMs(now: Long): Long = startOfDayMs(now)

    private fun epochDay(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(CN).toLocalDate().toString()

    suspend fun overview(now: Long): OverviewRow = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val startToday = startOfTodayMs(now)
        val startYest = startToday - DAY_MS
        val totalUsers = Accounts.selectAll().where {
            (Accounts.status eq "active") or (Accounts.status eq "revoked")
        }.count()
        val totalDevices = AnonymousDevices.selectAll().count()
        val newToday = Accounts.selectAll().where { Accounts.createdAt greaterEq startToday }.count()
        val newYest = Accounts.selectAll().where { Accounts.createdAt greaterEq startYest }.count() - newToday
        val newDevicesToday = AnonymousDevices.selectAll().where { AnonymousDevices.createdAt greaterEq startToday }.count()
        val newDevicesYest =
            AnonymousDevices.selectAll().where { AnonymousDevices.createdAt greaterEq startYest }.count() - newDevicesToday

        // 今日 + 昨日（UTC+8 自然日）；tokens/cost/bytes 跨所有行累加（与原逻辑一致）
        var callsToday = 0L
        var blockedToday = 0L
        var errorsToday = 0L
        var tokensToday = 0L
        var costToday = 0.0
        var bytesToday = 0L
        var callsYest = 0L
        var blockedYest = 0L
        var errorsYest = 0L
        var tokensYest = 0L
        var costYest = 0.0
        LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq startYest }.forEach { r ->
            val s = r[LlmCallLogs.status]
            val isToday = r[LlmCallLogs.createdAt] >= startToday
            val tokens = r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            val cost = r[LlmCallLogs.costCny]
            if (isToday) {
                if (s == "ok") callsToday += 1
                if (s.startsWith("blocked_")) blockedToday += 1
                if (s == "upstream_error") errorsToday += 1
                tokensToday += tokens
                costToday += cost
                bytesToday += r[LlmCallLogs.respBytes].toLong()
            } else {
                if (s == "ok") callsYest += 1
                if (s.startsWith("blocked_")) blockedYest += 1
                if (s == "upstream_error") errorsYest += 1
                tokensYest += tokens
                costYest += cost
            }
        }

        // 累计（仅 ok 行，全量）
        var totalCalls = 0L
        var totalTokens = 0L
        var totalCost = 0.0
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            totalCalls += 1
            totalTokens += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            totalCost += r[LlmCallLogs.costCny]
        }

        OverviewRow(
            totalUsers = totalUsers,
            newUsersToday = newToday,
            callsToday = callsToday,
            tokensToday = tokensToday,
            costToday = costToday,
            bytesToday = bytesToday,
            blockedToday = blockedToday,
            totalDevices = totalDevices,
            totalCalls = totalCalls,
            totalTokens = totalTokens,
            totalCost = totalCost,
            errorsToday = errorsToday,
            newDevicesToday = newDevicesToday,
            callsYest = callsYest,
            tokensYest = tokensYest,
            costYest = costYest,
            blockedYest = blockedYest,
            errorsYest = errorsYest,
            newUsersYest = newYest,
            newDevicesYest = newDevicesYest,
        )
    }

    /** browser 会话统计：当日开启（UTC+8 自然日，与 overview 口径一致）/ 活跃（未结束）/ 近 7 日失败率（expired 占比）。 */
    suspend fun browserOverview(now: Long): BrowserOverview = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val startToday = startOfTodayMs(now)
        val weekStart = now - 7 * DAY_MS
        val today = BrowserSessions.selectAll().where { BrowserSessions.startedAt greaterEq startToday }.count()
        val active = BrowserSessions.selectAll().where { BrowserSessions.endedAt.isNull() }.count()
        val weekTotal = BrowserSessions.selectAll().where { BrowserSessions.startedAt greaterEq weekStart }.count()
        val weekFailed = BrowserSessions.selectAll().where {
            (BrowserSessions.startedAt greaterEq weekStart) and (BrowserSessions.outcome eq "expired")
        }.count()
        BrowserOverview(
            todayCount = today,
            activeCount = active,
            failureRate7d = if (weekTotal == 0L) 0.0 else weekFailed.toDouble() / weekTotal,
        )
    }

    /** 按 provider 聚合成功调用消耗（全量）。供渠道页展示。 */
    suspend fun channelUsage(): Map<String, ChannelUsage> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val acc = HashMap<String, ChannelUsage>()
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            val p = r[LlmCallLogs.provider]
            val tokens = r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            val cost = r[LlmCallLogs.costCny]
            val cur = acc[p]
            acc[p] = if (cur == null) ChannelUsage(p, 1, tokens, cost)
            else ChannelUsage(p, cur.calls + 1, cur.tokens + tokens, cur.cost + cost)
        }
        acc
    }

    suspend fun dailySeries(days: Int, now: Long): List<DayBucket> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val startToday = startOfTodayMs(now)
            val since = startToday - (days - 1) * DAY_MS
            val acc = LinkedHashMap<String, DayAcc>()
            for (i in 0 until days) {
                val dayStart = startToday - (days - 1 - i) * DAY_MS
                acc[epochDay(dayStart)] = DayAcc()
            }
            LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq since }.forEach { r ->
                val a = acc[epochDay(r[LlmCallLogs.createdAt])] ?: return@forEach
                val status = r[LlmCallLogs.status]
                if (status == "ok") a.calls += 1L
                if (status.startsWith("blocked_")) a.blocked += 1L
                if (status == "upstream_error") a.errors += 1L
                a.promptTokens += r[LlmCallLogs.promptTokens]?.toLong() ?: 0L
                a.completionTokens += r[LlmCallLogs.completionTokens]?.toLong() ?: 0L
                a.totalTokens += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
                a.cost += r[LlmCallLogs.costCny]
                a.bytes += r[LlmCallLogs.respBytes].toLong()
            }
            acc.map { (day, a) ->
                DayBucket(day, a.calls, a.blocked, a.promptTokens, a.completionTokens, a.totalTokens, a.cost, a.bytes, a.errors)
            }
        }

    /** 对所选天数范围单次扫描，内存扇出每日序列 + 模型/渠道分布 + Top 用户/设备 + 延迟分位 + 合计。 */
    suspend fun rangeStats(days: Int, now: Long, prices: Map<String, Price> = defaultPrices()): RangeStats = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val startToday = startOfTodayMs(now)
        val since = startToday - (days - 1) * DAY_MS
        val dayAcc = LinkedHashMap<String, DayAcc>()
        for (i in 0 until days) {
            val ds = startToday - (days - 1 - i) * DAY_MS
            dayAcc[epochDay(ds)] = DayAcc()
        }
        val byModel = HashMap<String, DimAcc>()
        val byProvider = HashMap<String, DimAcc>()
        val userAcc = HashMap<Int, DimAcc>()
        val devAcc = HashMap<String, DimAcc>()
        val latencies = ArrayList<Int>()
        var promptCost = 0.0
        var completionCost = 0.0
        LlmCallLogs.selectAll().where { LlmCallLogs.createdAt greaterEq since }.forEach { r ->
            val t = r[LlmCallLogs.createdAt]
            val status = r[LlmCallLogs.status]
            val tokens = r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            val cost = r[LlmCallLogs.costCny]
            val bytes = r[LlmCallLogs.respBytes].toLong()
            dayAcc[epochDay(t)]?.let { a ->
                if (status == "ok") a.calls += 1L
                if (status.startsWith("blocked_")) a.blocked += 1L
                if (status == "upstream_error") a.errors += 1L
                a.promptTokens += r[LlmCallLogs.promptTokens]?.toLong() ?: 0L
                a.completionTokens += r[LlmCallLogs.completionTokens]?.toLong() ?: 0L
                a.totalTokens += tokens
                a.cost += cost
                a.bytes += bytes
            }
            if (status == "ok") {
                byModel.acc(r[LlmCallLogs.model]).add(1, tokens, cost)
                byProvider.acc(r[LlmCallLogs.provider]).add(1, tokens, cost)
                userAcc.acc(r[LlmCallLogs.accountId]).add(1, tokens, cost)
                r[LlmCallLogs.deviceId]?.let { devAcc.acc(it).add(1, tokens, cost) }
                r[LlmCallLogs.latencyMs]?.let { latencies.add(it) }
                prices[r[LlmCallLogs.model]]?.let { p ->
                    promptCost += (r[LlmCallLogs.promptTokens] ?: 0) / 1_000_000.0 * p.inPerMillion
                    completionCost += (r[LlmCallLogs.completionTokens] ?: 0) / 1_000_000.0 * p.outPerMillion
                }
            }
        }
        val dayBuckets = dayAcc.map { (day, a) ->
            DayBucket(day, a.calls, a.blocked, a.promptTokens, a.completionTokens, a.totalTokens, a.cost, a.bytes, a.errors)
        }
        val ta = DayAcc()
        dayAcc.values.forEach { a ->
            ta.calls += a.calls; ta.blocked += a.blocked; ta.errors += a.errors
            ta.promptTokens += a.promptTokens; ta.completionTokens += a.completionTokens
            ta.totalTokens += a.totalTokens; ta.cost += a.cost; ta.bytes += a.bytes
        }
        val totals = DayBucket("合计", ta.calls, ta.blocked, ta.promptTokens, ta.completionTokens, ta.totalTokens, ta.cost, ta.bytes, ta.errors)
        val topUserEntries = userAcc.entries.sortedByDescending { it.value.calls }.take(5)
        val emailById = if (topUserEntries.isEmpty()) emptyMap()
        else Accounts.selectAll().where { Accounts.id inList topUserEntries.map { it.key } }
            .associate { it[Accounts.id] to it[Accounts.email] }
        val topUsers = topUserEntries.map { e ->
            TopStat(e.key, maskEmail(emailById[e.key] ?: "#${e.key}"), e.value.calls, e.value.tokens, e.value.cost)
        }
        val topDevices = devAcc.entries.sortedByDescending { it.value.calls }.take(5)
            .map { e -> TopStat(0, maskDeviceId(e.key), e.value.calls, e.value.tokens, e.value.cost) }
        val lat = if (latencies.isEmpty()) LatencyStats(0, 0, 0)
        else { latencies.sort(); val n = latencies.size; LatencyStats(n, latencies[(0.50 * (n - 1)).toInt()], latencies[(0.95 * (n - 1)).toInt()]) }
        RangeStats(
            days = dayBuckets,
            byModel = byModel.map { DimStat(it.key, it.value.calls, it.value.tokens, it.value.cost) },
            byProvider = byProvider.map { DimStat(it.key, it.value.calls, it.value.tokens, it.value.cost) },
            topUsers = topUsers,
            topDevices = topDevices,
            latency = lat,
            totals = totals,
            costSplit = CostSplit(promptCost, completionCost),
        )
    }

    suspend fun usersList(): List<UserRow> = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val calls = HashMap<Int, Long>()
        val tokens = HashMap<Int, Long>()
        val cost = HashMap<Int, Double>()
        val lastDevTime = HashMap<Int, Long>()
        val lastDeviceId = HashMap<Int, String>()
        val lastPlatform = HashMap<Int, String>()
        LlmCallLogs.selectAll().where { LlmCallLogs.status eq "ok" }.forEach { r ->
            val id = r[LlmCallLogs.accountId]
            calls[id] = (calls[id] ?: 0L) + 1
            tokens[id] = (tokens[id] ?: 0L) + (r[LlmCallLogs.totalTokens]?.toLong() ?: 0L)
            cost[id] = (cost[id] ?: 0.0) + r[LlmCallLogs.costCny]
            val dev = r[LlmCallLogs.deviceId]
            if (dev != null) {
                val t = r[LlmCallLogs.createdAt]
                if (lastDevTime[id]?.let { t > it } != false) {
                    lastDevTime[id] = t
                    lastDeviceId[id] = dev
                }
            }
            r[LlmCallLogs.platform]?.let { p -> lastPlatform[id] = p }
        }
        val lastActive = HashMap<Int, Long>()
        LlmCallLogs.selectAll().forEach { r ->
            val id = r[LlmCallLogs.accountId]
            val t = r[LlmCallLogs.createdAt]
            val cur = lastActive[id]
            if (cur == null || t > cur) lastActive[id] = t
        }
        Accounts.selectAll().orderBy(Accounts.createdAt to SortOrder.DESC).map { a ->
            val id = a[Accounts.id]
            UserRow(
                id = id,
                email = a[Accounts.email],
                status = a[Accounts.status],
                createdAt = a[Accounts.createdAt],
                calls = calls[id] ?: 0L,
                totalTokens = tokens[id] ?: 0L,
                cost = cost[id] ?: 0.0,
                lastActive = lastActive[id],
                apiTokenMasked = maskToken(a[Accounts.tokenPlain]),
                hasToken = a[Accounts.tokenPlain].isNotEmpty(),
                deviceIdMasked = lastDeviceId[id]?.let { maskDeviceId(it) } ?: "—",
                lastPlatform = lastPlatform[id] ?: "—",
            )
        }
    }

    suspend fun userDetail(id: Int): UserDetail? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        val acc = Accounts.selectAll().where { Accounts.id eq id }.firstOrNull()
            ?: return@newSuspendedTransaction null
        var calls = 0L
        var blocked = 0L
        var tokens = 0L
        var cost = 0.0
        var bytes = 0L
        var lastActive: Long? = null
        LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq id }.forEach { r ->
            val s = r[LlmCallLogs.status]
            if (s == "ok") calls += 1
            if (s.startsWith("blocked_")) blocked += 1
            tokens += r[LlmCallLogs.totalTokens]?.toLong() ?: 0L
            cost += r[LlmCallLogs.costCny]
            bytes += r[LlmCallLogs.respBytes].toLong()
            val t = r[LlmCallLogs.createdAt]
            if (lastActive == null || t > lastActive!!) lastActive = t
        }
        UserDetail(
            id = id,
            email = acc[Accounts.email],
            status = acc[Accounts.status],
            createdAt = acc[Accounts.createdAt],
            llmCallsUsed = acc[Accounts.llmCallsUsed],
            llmCallsLimit = acc[Accounts.llmCallsLimit],
            calls = calls,
            totalTokens = tokens,
            cost = cost,
            blocked = blocked,
            bytes = bytes,
            lastActive = lastActive,
        )
    }

    suspend fun recentCalls(accountId: Int, limit: Int): List<CallRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            LlmCallLogs.selectAll().where { LlmCallLogs.accountId eq accountId }
                .orderBy(LlmCallLogs.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { r ->
                    CallRow(
                        id = r[LlmCallLogs.id],
                        model = r[LlmCallLogs.model],
                        provider = r[LlmCallLogs.provider],
                        promptTokens = r[LlmCallLogs.promptTokens],
                        completionTokens = r[LlmCallLogs.completionTokens],
                        totalTokens = r[LlmCallLogs.totalTokens],
                        costCny = r[LlmCallLogs.costCny],
                        respBytes = r[LlmCallLogs.respBytes],
                        status = r[LlmCallLogs.status],
                        latencyMs = r[LlmCallLogs.latencyMs],
                        createdAt = r[LlmCallLogs.createdAt],
                    )
                }
        }

    suspend fun devicesList(limit: Int = 1000, platform: String? = null): List<DeviceRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            val query = if (!platform.isNullOrBlank()) {
                AnonymousDevices.selectAll().where { AnonymousDevices.platform eq platform }
            } else {
                AnonymousDevices.selectAll()
            }
            query
                .orderBy(AnonymousDevices.lastSeenAt to SortOrder.DESC)
                .limit(limit)
                .map { r ->
                    DeviceRow(
                        id = r[AnonymousDevices.id],
                        deviceIdMasked = maskDeviceId(r[AnonymousDevices.deviceId]),
                        llmCallsUsed = r[AnonymousDevices.llmCallsUsed],
                        createdAt = r[AnonymousDevices.createdAt],
                        lastSeenAt = r[AnonymousDevices.lastSeenAt],
                        platform = r[AnonymousDevices.platform],
                    )
                }
        }

    suspend fun deviceRawId(id: Int): String? = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AnonymousDevices.selectAll().where { AnonymousDevices.id eq id }
            .firstOrNull()?.get(AnonymousDevices.deviceId)
    }

    suspend fun usersCount(): Long = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        Accounts.selectAll().count()
    }

    suspend fun devicesCount(): Long = newSuspendedTransaction(Dispatchers.IO, Db.instance) {
        AnonymousDevices.selectAll().count()
    }

    private class DayAcc {
        var calls = 0L
        var blocked = 0L
        var promptTokens = 0L
        var completionTokens = 0L
        var totalTokens = 0L
        var cost = 0.0
        var bytes = 0L
        var errors = 0L
    }

    private class DimAcc {
        var calls = 0L
        var tokens = 0L
        var cost = 0.0
        fun add(c: Long, t: Long, co: Double) {
            calls += c; tokens += t; cost += co
        }
    }

    private fun <K> HashMap<K, DimAcc>.acc(key: K): DimAcc = get(key) ?: DimAcc().also { put(key, it) }

    private fun maskEmail(email: String): String {
        val at = email.indexOf('@')
        return if (at <= 1) email else email.substring(0, 1) + "***" + email.substring(at)
    }

    data class ApkUploadRow(
        val id: Int,
        val channel: String,
        val version: String,
        val fileName: String,
        val fileSize: Long,
        val status: String,
        val message: String?,
        val createdAt: Long,
    )

    suspend fun apkUploadHistory(limit: Int = 50): List<ApkUploadRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            ApkUploads.selectAll()
                .orderBy(ApkUploads.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { r ->
                    ApkUploadRow(
                        id = r[ApkUploads.id],
                        channel = r[ApkUploads.channel],
                        version = r[ApkUploads.version],
                        fileName = r[ApkUploads.fileName],
                        fileSize = r[ApkUploads.fileSize],
                        status = r[ApkUploads.status],
                        message = r[ApkUploads.message],
                        createdAt = r[ApkUploads.createdAt],
                    )
                }
        }

    data class IosUdidRow(
        val id: Int,
        val udid: String,
        val nickname: String?,
        val createdAt: Long,
        val status: String,
    )

    suspend fun iosUdidList(): List<IosUdidRow> =
        newSuspendedTransaction(Dispatchers.IO, Db.instance) {
            IosUdidRegistrations.selectAll()
                .orderBy(IosUdidRegistrations.createdAt to SortOrder.DESC)
                .map { r ->
                    IosUdidRow(
                        id = r[IosUdidRegistrations.id],
                        udid = r[IosUdidRegistrations.udid],
                        nickname = r[IosUdidRegistrations.nickname],
                        createdAt = r[IosUdidRegistrations.createdAt],
                        status = r[IosUdidRegistrations.status],
                    )
                }
        }

    /** 与 ChannelRepository.maskToken 同形：前4+••••+后4 便于辨认；空 → 「—」。 */
    private fun maskToken(token: String): String = when {
        token.isEmpty() -> "—"
        token.length <= 8 -> "••••" + token.takeLast(4)
        else -> token.take(4) + "••••" + token.takeLast(4)
    }

    /** device_id 掩码:前 6 + •••• + 后 4;长度 ≤ 10 时只露后 4。与 [maskToken] 同形。 */
    private fun maskDeviceId(deviceId: String): String = when {
        deviceId.length <= 10 -> "••••" + deviceId.takeLast(4)
        else -> deviceId.take(6) + "••••" + deviceId.takeLast(4)
    }
}
