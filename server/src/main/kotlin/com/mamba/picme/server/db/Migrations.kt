package com.mamba.picme.server.db

import com.mamba.picme.server.config.AppConfig
import com.mamba.picme.server.config.SettingsService
import com.mamba.picme.server.llm.serializeModelMap
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

object Migrations {
    fun run(config: AppConfig) {
        transaction(Db.instance) {
            SchemaUtils.create(
                Rules, Assets, TelemetryEvents, LlmDailyCounters,
                Accounts, EmailVerifications, LlmCallLogs, LlmChannels,
                ApkUploads, AnonymousDevices, ServerSettings, AiEngineerWhitelists,
                ReportedIssues, IosUdidRegistrations, BrowserSessions,
            )
            // 给现存表补缺失列（如 llm_channel.default_model、apk_upload.channel），幂等
            SchemaUtils.createMissingTablesAndColumns(
                Accounts, LlmChannels, LlmCallLogs, ServerSettings,
                AiEngineerWhitelists, ReportedIssues, AnonymousDevices,
                IosUdidRegistrations, ApkUploads,
            )
            seedRules()
        }
        seedChannels(config)
        backfillDefaultModels()
        seedSettings(config)
        backfillBalanceUrls()
    }

    /**
     * 幂等加载初始推荐规则。seed_rules.sql 用 INSERT OR IGNORE，重复启动不会重复插入。
     * 若跳过此步，首次启动查不到任何规则，/recommend 必返回 404。
     */
    private fun Transaction.seedRules() {
        val sql = Migrations::class.java.getResource("/seed_rules.sql")?.readText() ?: return
        sql.lines()
            .map { it.substringBefore("--").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { stmt -> exec(stmt) }
    }

    /**
     * 首次启动播种 5 个 LLM 渠道（2 网关 + 3 直连）。已有渠道则跳过（幂等）。
     * 生效渠道：FORCE_PROVIDER=cloudflare|tokenhub 优先，否则首个 enabled（Cloudflare）。
     * env 仅此处读一次；之后由后台 /admin/channels 管理。
     */
    internal fun seedChannels(config: AppConfig) {
        transaction(Db.instance) {
            if (LlmChannels.selectAll().count() > 0) return@transaction
            val now = System.currentTimeMillis()

            val cloudflareId = LlmChannels.insert {
                it[LlmChannels.name] = "Cloudflare"
                it[LlmChannels.kind] = "gateway"
                it[LlmChannels.baseUrl] = config.cloudflareAigUrl
                it[LlmChannels.authStyle] = "cf_aig"
                it[LlmChannels.apiToken] = config.cloudflareAigToken
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "deepseek-chat" to "deepseek/deepseek-chat",
                    "deepseek-v4-flash" to "deepseek/deepseek-chat",
                ))
                it[LlmChannels.defaultModel] = CHANNEL_DEFAULT_MODEL.getValue("Cloudflare")
                it[LlmChannels.enabled] = 1
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }[LlmChannels.id]

            val tokenhubId = LlmChannels.insert {
                it[LlmChannels.name] = "TokenHub"
                it[LlmChannels.kind] = "gateway"
                it[LlmChannels.baseUrl] = config.tokenhubUrl
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = config.tokenhubApiToken
                it[LlmChannels.modelMapJson] = serializeModelMap(
                    TOKENHUB_SEED_MODELS.associateWith { model -> model }
                )
                it[LlmChannels.defaultModel] = CHANNEL_DEFAULT_MODEL.getValue("TokenHub")
                it[LlmChannels.enabled] = 1
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }[LlmChannels.id]

            LlmChannels.insert {
                it[LlmChannels.name] = "DeepSeek 直连"
                it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.deepseek.com/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "deepseek-v4-flash" to "deepseek-v4-flash",
                    "deepseek-v4-pro" to "deepseek-v4-pro",
                ))
                it[LlmChannels.defaultModel] = CHANNEL_DEFAULT_MODEL.getValue("DeepSeek 直连")
                it[LlmChannels.enabled] = 0
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }

            LlmChannels.insert {
                it[LlmChannels.name] = "GLM 直连"
                it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "deepseek-chat" to "glm-5.2",
                    "kimi-k2.6" to "glm-5.2",
                ))
                it[LlmChannels.defaultModel] = CHANNEL_DEFAULT_MODEL.getValue("GLM 直连")
                it[LlmChannels.enabled] = 0
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }

            LlmChannels.insert {
                it[LlmChannels.name] = "Kimi 直连"
                it[LlmChannels.kind] = "direct"
                it[LlmChannels.baseUrl] = "https://api.moonshot.cn/v1/chat/completions"
                it[LlmChannels.authStyle] = "bearer"
                it[LlmChannels.apiToken] = ""
                it[LlmChannels.modelMapJson] = serializeModelMap(mapOf(
                    "kimi-k2.6" to "kimi-k2.7-code",
                    "deepseek-chat" to "kimi-k2.7-code",
                ))
                it[LlmChannels.defaultModel] = CHANNEL_DEFAULT_MODEL.getValue("Kimi 直连")
                it[LlmChannels.enabled] = 0
                it[LlmChannels.isActive] = 0
                it[LlmChannels.createdAt] = now
                it[LlmChannels.updatedAt] = now
            }

            val activeId = when (config.forceProvider.trim().lowercase()) {
                "tokenhub" -> tokenhubId
                "cloudflare" -> cloudflareId
                else -> cloudflareId
            }
            LlmChannels.update({ LlmChannels.id eq activeId }) { it[LlmChannels.isActive] = 1 }
        }
    }

    /**
     * 幂等回填：现存渠道若 default_model 为空且名字命中 [CHANNEL_DEFAULT_MODEL]，则补默认值。
     * 让 prod 老版本播种的渠道升级后立即有兜底。每版启动跑一次，已填则跳过。
     */
    internal fun backfillDefaultModels() {
        transaction(Db.instance) {
            LlmChannels.selectAll().toList().forEach { row ->
                if (row[LlmChannels.defaultModel].isBlank()) {
                    val dm = CHANNEL_DEFAULT_MODEL[row[LlmChannels.name]] ?: return@forEach
                    LlmChannels.update({ LlmChannels.id eq row[LlmChannels.id] }) {
                        it[LlmChannels.defaultModel] = dm
                    }
                }
            }
        }
    }

    /**
     * 幂等播种额度默认值：仅当对应行缺失时写入 env 值。之后由后台 /admin/settings 管理，env 降级为「首次默认」。
     */
    internal fun seedSettings(config: AppConfig) {
        transaction(Db.instance) {
            val now = System.currentTimeMillis()
            seedIfAbsent(SettingsService.KEY_FREE, config.freeLlmQuota, now)
            seedIfAbsent(SettingsService.KEY_GUEST, config.guestLlmQuota, now)
            seedIfAbsent(SettingsService.KEY_BROWSER_DAILY, config.browserDailyQuota, now)
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.seedIfAbsent(key: String, value: Int, now: Long) {
        val exists = ServerSettings.selectAll().where { ServerSettings.key eq key }.firstOrNull() != null
        if (!exists) {
            ServerSettings.insert {
                it[ServerSettings.key] = key
                it[ServerSettings.value] = value
                it[ServerSettings.updatedAt] = now
            }
        }
    }

    /**
     * 幂等回填：DeepSeek 直连渠道若 balance_url 为空则补上，让老库升级后即可用余额刷新。
     */
    internal fun backfillBalanceUrls() {
        transaction(Db.instance) {
            LlmChannels.selectAll().toList().forEach { row ->
                if (row[LlmChannels.balanceUrl].isBlank()) {
                    val url = CHANNEL_BALANCE_URL[row[LlmChannels.name]] ?: return@forEach
                    LlmChannels.update({ LlmChannels.id eq row[LlmChannels.id] }) {
                        it[LlmChannels.balanceUrl] = url
                    }
                }
            }
        }
    }
}

private val TOKENHUB_SEED_MODELS = listOf(
    "deepseek-v4-flash-202605", "kimi-k2.7-code", "kimi-k2.6", "deepseek-v4-flash",
    "hy3", "kimi-k2.7-code-highspeed", "glm-5.2", "minimax-m3", "hy-role",
    "deepseek-v4-pro-202606", "hy-mt2-pro", "hy-mt2-lite", "hy-mt2-plus",
    "hunyuan-role-latest", "deepseek-v4-pro", "hy3-preview", "glm-5.1",
    "glm-5v-turbo", "minimax-m2.7", "glm-5-turbo", "qwen3.5-flash",
    "qwen3.5-plus", "minimax-m2.5", "glm-5", "kimi-k2.5",
)

/** 渠道名 → 默认上游模型。播种与回填共用；用户新建渠道默认留空（strict）。 */
private val CHANNEL_BALANCE_URL = mapOf(
    "DeepSeek 直连" to "https://api.deepseek.com/user/balance",
)

private val CHANNEL_DEFAULT_MODEL = mapOf(
    "Cloudflare" to "deepseek/deepseek-chat",
    "TokenHub" to "deepseek-v4-flash-202605",
    "DeepSeek 直连" to "deepseek-v4-flash",
    "GLM 直连" to "glm-5.2",
    "Kimi 直连" to "kimi-k2.7-code",
)
