package com.mamba.picme.features.chat.capability

import com.mamba.picme.agent.core.inference.remote.tool.ChatToolService
import com.mamba.picme.features.gallery.capability.GalleryCapability
import com.mamba.picme.features.settings.capability.SettingsCapability
import ai.koog.agents.core.tools.annotations.Tool as KoogTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * chat @Tool 表面与 Capability 覆盖一致性门禁（spec §5 Phase 2）。
 *
 * 背景：2026-07-29 盘点故障暴露「工具存在但 Capability 未注册/不可见」整类问题；
 * 本测试保证 [ChatToolService] 的每个 @Tool 要么有 Capability 的 supportedCommands()
 * 覆盖，要么在 [EXCEPTIONS] 中登记归属——新增工具漏接 Capability 在此 fail-fast。
 */
class ChatToolCapabilityCoverageTest {

    /**
     * 无法经单例 supportedCommands() 直接校验的工具登记处：
     * - 别名：@Tool 名与命令 method 名不同（映射在 @Tool 方法体内）；
     * - 构造注入：Capability 依赖容器/框架对象，单测无法实例化；
     * - 特例：不经 Capability 的工具。
     */
    private val exceptions = mapOf(
        "record_feedback" to "别名→feedback（ChatSearchCapability）",
        "more_like_this" to "别名→more（ChatSearchCapability）",
        "exclude_constraint" to "别名→exclude（ChatSearchCapability）",
        "list_person_relations" to "别名→query_person_relation（PersonRelationCapability，容器注入）",
        "remember_person_relation" to "PersonRelationCapability（容器注入）",
        "forget_person_relation" to "PersonRelationCapability（容器注入）",
        "remember_fact" to "MemoryCapability（容器注入）",
        "forget_fact" to "MemoryCapability（容器注入）",
        "recall_memory" to "MemoryCapability（容器注入）",
        "ai_optimize" to "AiOptimizeCapability（容器注入）",
        "edit_image" to "ImageEditCapability（容器注入）",
        "adjust_image" to "handler-based（adjustImageHandler，不经 Capability）",
        "navigate_to" to "NavigationCapability（依赖 NavController）",
        "go_back" to "NavigationCapability（依赖 NavController）",
        "launch_app" to "SystemCapability（依赖 Context）",
        "open_system_settings" to "SystemCapability（依赖 Context）",
        "delay" to "registry 内建（AgentCommand.Delay 由 CapabilityRegistry 直接处理）",
        "finish" to "agent 控制指令（无对应 AgentCommand）",
        "browser_open" to "BrowserSessionCapability（BrowserTransport 容器注入）",
        "browser_navigate" to "BrowserSessionCapability（BrowserTransport 容器注入）",
        "browser_click" to "BrowserSessionCapability（BrowserTransport 容器注入）",
        "browser_type" to "BrowserSessionCapability（BrowserTransport 容器注入）",
        "browser_extract" to "BrowserSessionCapability（BrowserTransport 容器注入）",
        "browser_screenshot" to "BrowserSessionCapability（BrowserTransport 容器注入）",
        "browser_close" to "BrowserSessionCapability（BrowserTransport 容器注入）"
    )

    private val covered: Set<String> = (
        ChatSearchCapability.getInstance().supportedCommands() +
            ChatGallerySummaryCapability.getInstance().supportedCommands() +
            ChatRunScriptCapability.getInstance().supportedCommands() +
            ChatStartTagScanCapability.getInstance().supportedCommands() +
            ChatMediaWriteCapability.getInstance().supportedCommands() +
            GalleryCapability.getInstance().supportedCommands() +
            SettingsCapability.getInstance().supportedCommands()
        ).toSet()

    @Test
    fun `every chat tool has capability coverage or documented exception`() {
        // ChatToolService 已迁 Koog @Tool（customName 保蛇形 LLM-facing 名）
        val toolNames = ChatToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }
        assertTrue("ChatToolService 应暴露多个 @Tool", toolNames.size > 20)

        val uncovered = toolNames.filter { it !in covered && it !in exceptions }
        assertEquals("存在无 Capability 覆盖且未登记例外的 @Tool：$uncovered", emptyList<String>(), uncovered)
    }

    @Test
    fun `exception table has no stale entries`() {
        val toolNames = ChatToolService::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(KoogTool::class.java)?.customName?.takeIf { name -> name.isNotBlank() } }
            .toSet()
        val stale = exceptions.keys - toolNames
        assertTrue("例外表含已不存在的工具名：$stale", stale.isEmpty())
    }

    @Test
    fun `alias targets are actually covered`() {
        // 别名工具的命令 method 名必须真实存在于 Capability 覆盖集
        listOf("feedback", "more", "exclude").forEach { alias ->
            assertTrue("别名目标 $alias 未被任何 Capability 覆盖", alias in covered)
        }
    }
}
