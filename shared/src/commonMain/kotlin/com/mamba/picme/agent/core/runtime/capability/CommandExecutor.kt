package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.platform.logging.Logger
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.concurrent.Volatile

/**
 * 命令执行器
 *
 * 负责命令执行、超时控制和异常处理。
 *
 * chat / 飞书 / batch 等全部 tool 执行都汇聚于此，因此在此统一上报执行指标
 * （见 [recorder]），只记录纯指标，不含命令参数与业务内容。
 */
class CommandExecutor(
    internal val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {
    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L

        /**
         * registry 内层命令执行超时（毫秒）：[CapabilityRegistry] 与 CrossPageCommandQueue 共用实例
         * 的显式构造值。25s 对齐 browser 远端预算链（服务端 navTimeout 15s < 25s < 网关 30s）——
         * 内层若紧于 browser 外层 dispatch 超时，慢页面（domcontentloaded 5~15s）会被内层必杀、
         * 外层 25s 永不生效（2026-10-06 review 发现的层叠缺陷）。
         * 既有工具外层 dispatch 5s 更紧，内层放宽后不会单独触发，行为不变；
         * browser_* 外层 dispatch 超时（ChatToolService.BROWSER_DISPATCH_TIMEOUT_MS）引用本常量，两处不漂移。
         * 层叠不变式由 commonTest `CommandTimeoutLayeringTest` 钉住。
         */
        const val REGISTRY_COMMAND_TIMEOUT_MS = 25_000L

        const val ERROR_CODE_TIMEOUT = -32002
        const val ERROR_CODE_EXECUTION_FAILED = -32005

        private const val TAG = "PoLang:CommandExecutor"

        /**
         * tool 执行指标接收端，由 :androidApp 在 Application 启动时注入（全构建注入）。
         * 为 null 时不记录。
         */
        @Volatile
        var recorder: CommandExecutionRecorder? = null

        /**
         * 执行前阶段（Capability 查找失败 / 命令入队 / 调用方等待超时）的失败上报入口。
         *
         * 这些路径不经过 [execute]，此前在 tool_call_log 完全不可见（如 2026-07-29
         * 「盘点相册返回暂不支持」：METHOD_NOT_FOUND 无任何记录，只能靠 LLM 请求体反推）。
         * 与 [notifyRecorder] 同一通道、同一约束：只记纯指标，recorder 异常静默吞掉。
         */
        fun recordDispatchEvent(
            capability: String,
            commandType: String,
            success: Boolean,
            errorCode: Int?,
            errorMessage: String?,
            traceId: String?
        ) {
            try {
                recorder?.record(
                    capability = capability,
                    commandType = commandType,
                    latencyMs = 0,
                    success = success,
                    errorCode = errorCode,
                    errorMessage = errorMessage,
                    traceId = traceId
                )
            } catch (e: Exception) {
                Logger.w(TAG, "recorder.record failed", e)
            }
        }
    }

    /**
     * 执行命令（带超时）
     */
    suspend fun execute(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        capability: Capability
    ): Result<AgentAction> {
        val startMs = Clock.System.now().toEpochMilliseconds()
        val commandType = AgentCommand.getMethodName(command)
        return try {
            val result = withTimeout(timeoutMs) {
                capability.execute(command, context, pageContext)
            }
            // Capability 业务失败以 Result.success(AgentAction.Error) 返回（如引导性错误），
            // 必须按 action 语义记 success=0，否则 tool_call_log 会把失败记成成功。
            val errorAction = result.getOrNull() as? AgentAction.Error
            notifyRecorder(
                capability.name, commandType, startMs,
                success = result.isSuccess && errorAction == null,
                errorCode = errorAction?.errorCode,
                errorMessage = errorAction?.message ?: result.exceptionOrNull()?.message,
                traceId = context.traceId
            )
            result
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            val ex = CapabilityExecutionException(
                "Command execution timed out after ${timeoutMs}ms",
                ERROR_CODE_TIMEOUT,
                e
            )
            notifyRecorder(capability.name, commandType, startMs, false, ex.errorCode, ex.message, context.traceId)
            Result.failure(ex)
        } catch (e: Exception) {
            val ex = CapabilityExecutionException(
                "Command execution failed: ${e.message}",
                ERROR_CODE_EXECUTION_FAILED,
                e
            )
            notifyRecorder(capability.name, commandType, startMs, false, ex.errorCode, ex.message, context.traceId)
            Result.failure(ex)
        }
    }

    /** 上报执行指标；recorder 自身异常吞掉，绝不影响命令执行主流程。 */
    private fun notifyRecorder(
        capability: String,
        commandType: String,
        startMs: Long,
        success: Boolean,
        errorCode: Int?,
        errorMessage: String?,
        traceId: String?
    ) {
        try {
            recorder?.record(
                capability = capability,
                commandType = commandType,
                latencyMs = Clock.System.now().toEpochMilliseconds() - startMs,
                success = success,
                errorCode = errorCode,
                errorMessage = errorMessage,
                traceId = traceId
            )
        } catch (e: Exception) {
            Logger.w(TAG, "recorder.record failed", e)
        }
    }

    class CapabilityExecutionException(
        message: String,
        val errorCode: Int,
        cause: Throwable?
    ) : Exception(message, cause)
}
