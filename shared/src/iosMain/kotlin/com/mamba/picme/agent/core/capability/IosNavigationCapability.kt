package com.mamba.picme.agent.core.capability

import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.data.IosNavigationBridge
import kotlinx.coroutines.CancellationException

/**
 * iOS 导航 Capability —— `navigate_to` 命令的 iOS 执行端（2026-09-16 主导航统一）。
 *
 * 对齐 Android `NavigationCapability`：ChatToolService `navigate_to(destination)` →
 * `AgentCommand.NavigateTo` 经 CapabilityRegistry 路由到本能力，经 [bridge] 让 Swift UI
 * 层真实切页/弹出（此前 iOS 无执行端，命令折叠成「✅ 已执行」展示串不生效）。
 *
 * 支持目的地（main-nav.yaml §4）：camera（相机 fullScreenCover）/ gallery（切 Pager 页 0）/
 * settings（设置 fullScreenCover）/ model_center（模型中心 cover，含 Android 别名
 * llm_model_manager/asr_model_manager 与中文别名，由 Swift 桥层归一映射）；
 * debug 及其余目的地返回 [AgentErrorCode.INVALID_REQUEST]（iOS 无 Debug 页，平台差异已登记）。
 *
 * 场景：应用级能力，沿用 [BaseCapability] 默认（全场景可用）。
 */
class IosNavigationCapability(
    private val bridge: IosNavigationBridge? = null
) : BaseCapability() {

    private val tag = "PoLang:IosNavigationCapability"

    override val name: String = "ios_navigation"
    override val description: String = "导航到指定页面：相机/相册/设置/模型中心"

    override fun supportedCommands(): List<String> = listOf(COMMAND_NAVIGATE_TO)

    override fun isAvailable(): Boolean = bridge != null

    override fun getCommandDescription(command: String): String = when (command) {
        COMMAND_NAVIGATE_TO -> "导航到页面，参数: destination (camera/gallery/settings/model_center)"
        else -> super.getCommandDescription(command)
    }

    override suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> = try {
        when (command) {
            is AgentCommand.NavigateTo -> handleNavigateTo(command)
            else -> Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.METHOD_NOT_FOUND,
                    "IosNavigationCapability 不支持此命令"
                )
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 异常绝不逃逸出 Kotlin 边界（signal 6 铁律）
        Logger.e(tag, "navigate_to failed", e)
        Result.success(
            AgentAction.Error(
                command.commandId,
                AgentErrorCode.INTERNAL_ERROR,
                "导航失败：${e.message ?: "未知错误"}"
            )
        )
    }

    private fun handleNavigateTo(command: AgentCommand.NavigateTo): Result<AgentAction> {
        val navigation = bridge
            ?: return Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.CAPABILITY_UNAVAILABLE,
                    "导航暂不可用（导航桥未注入）"
                )
            )
        val accepted = try {
            navigation.navigateTo(command.destination)
        } catch (t: Throwable) {
            Logger.e(tag, "navigateTo bridge threw", t)
            false
        }
        return if (accepted) {
            Logger.i(tag, "navigate_to accepted: ${command.destination}")
            Result.success(AgentAction.Success(commandId = command.commandId, command = command))
        } else {
            Result.success(
                AgentAction.Error(
                    command.commandId,
                    AgentErrorCode.INVALID_REQUEST,
                    "iOS 暂不支持导航到 ${command.destination}"
                )
            )
        }
    }

    companion object {
        private const val COMMAND_NAVIGATE_TO = "navigate_to"
    }
}
