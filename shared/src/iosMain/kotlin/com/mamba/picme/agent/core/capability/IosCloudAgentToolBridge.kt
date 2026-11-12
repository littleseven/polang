package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolCatalog
import com.mamba.picme.agent.core.inference.remote.tool.CloudAgentToolContract
import com.mamba.picme.agent.core.inference.remote.tool.CloudToolProjectionGate
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentIdGenerator
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.MediaAsset
import com.mamba.picme.data.IosChatSearchBridge
import com.mamba.picme.data.IosLocalHandleStore
import com.mamba.picme.data.IosMediaRepositoryBridge
import com.mamba.picme.domain.chat.openui.OpenUiToolNotFoundException
import com.mamba.picme.domain.chat.openui.OpenUiToolResolver
import com.mamba.picme.domain.chat.openui.OpenUiValue
import com.mamba.picme.domain.chat.openui.ScanPhotoEntry
import com.mamba.picme.domain.repository.MediaRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer

/**
 * 云端 Agent 工具桥（ADR-018 M2，iOS 对等端）—— tool_request 帧的端侧执行器。
 *
 * 对位 Android `core/agenttools/DeviceToolExecutor`（ChatToolService 只读三件套），
 * 但**不经 CapabilityRegistry 场景门**：Android 侧 registry 要求 CHAT 场景，云端调试页
 * 在 UNKNOWN 场景调用会被排队返回占位文本（真机实证，场景解耦挂账 M3）——故本桥持有
 * 自己的 [IosChatGalleryCapability] 实例直接 `execute`（业务语义相同：相册摘要/搜索/细化），
 * intent 组装同源复用 [ChatToolService] 的 internal 构建器，零复制。
 *
 * 结果契约（与 Android DeviceToolExecutor 语义对齐；横滑卡退役后 search/refine 回执 =
 * 能力层 [AgentAction.TextReply] 计数文案，本桥原样透传）：
 * - 文本结果；失败以 `error: <message>` 前缀判定 ok=false（[AgentAction.Error] → error 前缀）；
 * - 超 [CloudAgentToolContract.MAX_RESULT_CHARS] **字符**截断 + `\n…[truncated]`（字符非字节——不切半 UTF-8）；
 * - 未知工具 → `error: unknown_tool:<tool>`；
 * - search/refine（2026-11-12 重写）：mediaType 维度过滤 + 命中集注册 local://scan 句柄
 *   （[IosLocalHandleStore]），回执 = 纯文本计数 + 数据句柄 token（对齐 Android
 *   CloudAgentGalleryTools）；写类工具（delete/share 等）仍不在映射表（云端会话只读问答）。
 *
 * 工具名/错误前缀/截断常量的 SSOT = commonMain [CloudAgentToolContract]（M3 收口，
 * Android DeviceToolExecutor 与 server DeviceToolService（分层 runtime M1，composite
 * build 真引用）同源；仅 Swift 侧持字面量副本，改动须同步核对）。
 * 出端结果一律过 [CloudToolProjectionGate] 投影闸（混合架构 M1，fail-closed），与 Android DeviceToolExecutor 同闸。
 *
 * 信号 6 铁律：一切异常（除 [CancellationException] 透传）在 Kotlin 侧兜底为 error 前缀字符串，
 * 绝不逃逸到 Swift。
 */
class IosCloudAgentToolBridge(
    repository: MediaRepository,
    bridge: IosMediaRepositoryBridge,
    searchBridge: IosChatSearchBridge? = null,
    /** OpenUI 云端回合 local:// 句柄存储（命中集注册 / 卡端补全共用同一实例）。 */
    private val handleStore: IosLocalHandleStore? = null
) {

    /**
     * 自有能力实例（独立于组合根注册到 CapabilityRegistry 的那颗——搜索轮次快照互不染指；
     * 云端会话的 refine 基数由本实例的内存态承载）。
     */
    private val capability = IosChatGalleryCapability(repository, bridge, searchBridge)

    private val handleJson = kotlinx.serialization.json.Json { encodeDefaults = true }

    /** OpenUI 动作三通道之访问 API：Mutation 写工具白名单（favorite_media/unfavorite_media，
     * 对齐 Android ChatScreen openUiWriteTools 接线集）；经自有能力直执（Photos 桥收藏）。 */
    val openUiWriteTools: OpenUiToolResolver = OpenUiToolResolver { name, args ->
        when (name) {
            OpenUiWriteToolsNames.FAVORITE_MEDIA, OpenUiWriteToolsNames.UNFAVORITE_MEDIA -> {
                val mediaId = (args["id"] as? OpenUiValue.Num)?.v?.toLong()
                    ?: throw IllegalArgumentException("$name 缺 id 参数")
                val favorite = name == OpenUiWriteToolsNames.FAVORITE_MEDIA
                val message = favoriteMediaForOpenUi(mediaId, favorite)
                OpenUiValue.Obj(
                    mapOf(
                        "ok" to OpenUiValue.Bool(true),
                        "favorite" to OpenUiValue.Bool(favorite),
                        "text" to OpenUiValue.Str(message),
                    )
                )
            }
            else -> throw OpenUiToolNotFoundException(name)
        }
    }

    /** 工具名白名单（对齐 androidApp OpenUiWriteTools 常量；shared 目录无此常量，此处收口）。 */
    private object OpenUiWriteToolsNames {
        const val FAVORITE_MEDIA = "favorite_media"
        const val UNFAVORITE_MEDIA = "unfavorite_media"
    }

    /** 收藏直执（[openUiWriteTools] 内部）：经 AgentCommand.FavoriteMedia → Photos 桥。 */
    private suspend fun favoriteMediaForOpenUi(mediaId: Long, favorite: Boolean): String {
        val result = capability.execute(
            AgentCommand.FavoriteMedia(commandId = AgentIdGenerator.nextId(), mediaId = mediaId.toString(), favorite = favorite),
            AgentContext(scene = AgentScene.CHAT, memorySessionId = "cloud_agent"),
            null
        )
        return result.fold(
            onSuccess = { action -> (action as? AgentAction.TextReply)?.message ?: "ok" },
            onFailure = { "收藏操作失败" }
        )
    }

    /** 工具分发入口（Swift 侧逐帧调用）。args 全 String 空容忍（对齐 SKIE 契约形态）。 */
    suspend fun execute(tool: String, args: Map<String, String>): String {
        val result = try {
            when (tool) {
                CloudAgentToolContract.TOOL_GET_GALLERY_SUMMARY -> getGallerySummary()
                CloudAgentToolContract.TOOL_SEARCH_MEDIA -> {
                    // 参数名自 shared 目录读取（分层 runtime M2；键序 = 目录声明序）。
                    // mediaType（第 6 参，2026-11-12 接线）：目录缺省 "photo"。
                    val names = CloudAgentToolCatalog.paramNames(tool)
                    searchMedia(
                        query = args[names[0]].orEmpty(),
                        person = args[names[1]].orEmpty(),
                        fromMs = args[names[2]].orEmpty(),
                        toMs = args[names[3]].orEmpty(),
                        hasFace = args[names[4]].orEmpty(),
                        mediaType = names.getOrNull(5)?.let { args[it] }?.ifEmpty { null }
                            ?: CloudAgentToolCatalog.defaultFor(tool, names.getOrNull(5) ?: "").ifEmpty { "photo" },
                    )
                }
                CloudAgentToolContract.TOOL_REFINE_MEDIA_SEARCH -> {
                    val names = CloudAgentToolCatalog.paramNames(tool)
                    refineMediaSearch(
                        constraint = args[names[0]].orEmpty(),
                        fromMs = args[names[1]].orEmpty(),
                        toMs = args[names[2]].orEmpty(),
                    )
                }
                else -> return CloudAgentToolContract.unknownTool(tool)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            "${CloudAgentToolContract.ERROR_PREFIX} ${e.message ?: "unknown"}"
        }
        return CloudAgentToolContract.truncate(CloudToolProjectionGate.gate(tool, result))
    }

    /** @Tool get_gallery_summary 对等：相册摘要。 */
    suspend fun getGallerySummary(): String =
        executeCommand(AgentCommand.GetGallerySummary(includeDetails = false))

    /**
     * @Tool search_media 对等（2026-11-12 重写为 mediaType 维度 + 句柄注册）：
     * intent 组装同源 [ChatToolService.structuredSearchIntent]；回执 = 纯文本计数 +
     * 数据句柄 token（对齐 Android CloudAgentGalleryTools——命中集注册 local://scan
     * 句柄供 OpenUI 卡取数，uri 不出端）。
     */
    suspend fun searchMedia(
        query: String,
        person: String,
        fromMs: String,
        toMs: String,
        hasFace: String,
        mediaType: String = "photo"
    ): String {
        val intent = ChatToolService.getInstance()
            .structuredSearchIntent(query, person, fromMs, toMs, hasFace)
        val assets = capability.searchMediaForCloud(query, intent, mediaType)
        val (unit, noun) = unitNounOf(mediaType)
        val displayQuery = query.ifBlank { "全部" }
        if (assets.isEmpty()) return "找到 0 $unit「$displayQuery」的$noun"
        return appendHandleSuffix("找到 ${assets.size} $unit「$displayQuery」的$noun", assets)
    }

    /** @Tool refine_media_search 对等：无基数明确错误引导；类型过滤跟随基数；in-set 空保留基数。 */
    suspend fun refineMediaSearch(
        constraint: String,
        fromMs: String,
        toMs: String
    ): String {
        val intent = ChatToolService.getInstance().refineTimeIntent(fromMs, toMs)
        val prior = capability.lastSearchSnapshot()
        if (prior.isEmpty()) {
            return "${CloudAgentToolContract.ERROR_PREFIX} 没有上一轮搜索结果可细化：请改用 search_media 重新全局搜索" +
                "（人物/时间用 person/fromMs/toMs 结构化参数）"
        }
        val baseMediaType = cloudSearchMediaType()
        val (unit, noun) = unitNounOf(baseMediaType)
        val refined = capability.refineMediaSearchForCloud(constraint, intent)
            ?: return "在上一轮 ${prior.size} ${unit}结果内没有找到「$constraint」的$noun"
        return appendHandleSuffix("在上一轮结果内找到 ${refined.size} $unit「$constraint」的$noun", refined)
    }

    /** 回执文案的量词与名词（照片=张/照片，视频=段/视频，混合=个/媒体；Android 同字面量）。 */
    private fun unitNounOf(mediaType: String): Pair<String, String> = when (mediaType.lowercase()) {
        "video" -> "段" to "视频"
        "all" -> "个" to "媒体"
        else -> "张" to "照片"
    }

    /** 最近一轮搜索的类型口径（供 refine 文案；capability 内存态）。 */
    private fun cloudSearchMediaType(): String = capability.lastSearchMediaTypeIdiom()

    /**
     * 命中集注册 local://scan 句柄并拼 token 尾缀（`（数据句柄 handle: scan/<id>）`）；
     * 句柄存储缺位 / 编码写失败降级为纯前缀（搜索本身已成功，不走 error: 协议——
     * 对齐 Android CloudAgentGalleryTools.appendHandleSuffix）。mediaType 随 entry 落
     * payload（#photos/#videos 投影按类型分流）。
     */
    private fun appendHandleSuffix(prefix: String, assets: List<MediaAsset>): String {
        val store = handleStore ?: return prefix
        return try {
            val entries = assets.map { asset ->
                ScanPhotoEntry(
                    id = asset.id,
                    captureDate = asset.captureDate,
                    mediaType = asset.type.name
                )
            }
            "$prefix（数据句柄 ${store.put(com.mamba.picme.domain.chat.openui.OpenUiContract.DOMAIN_SCAN, handleJson.encodeToString(ListSerializer(ScanPhotoEntry.serializer()), entries))}）"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 注册失败只降级不带 token，不影响成功搜索的回执语义
            prefix
        }
    }

    /**
     * 直调能力执行（绕过 CapabilityRegistry 场景门，见类头注释）并把 [AgentAction] 映射为
     * 工具结果文本：TextReply→原文；Error→`error: ` 前缀。
     */
    private suspend fun executeCommand(command: AgentCommand): String {
        val result = capability.execute(
            command,
            AgentContext(scene = AgentScene.CHAT, memorySessionId = "cloud_agent"),
            null
        )
        return result.fold(
            onSuccess = { action -> actionToText(action) },
            onFailure = { "${CloudAgentToolContract.ERROR_PREFIX} ${it.message ?: "dispatch failed"}" },
        )
    }

    private fun actionToText(action: AgentAction): String = when (action) {
        is AgentAction.TextReply -> action.message
        is AgentAction.Error -> "${CloudAgentToolContract.ERROR_PREFIX} ${action.message}"
        else -> "ok"
    }
}
