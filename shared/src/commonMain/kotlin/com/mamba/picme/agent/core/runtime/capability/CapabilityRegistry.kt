package com.mamba.picme.agent.core.runtime.capability

import com.mamba.picme.agent.core.capability.Capability
import com.mamba.picme.agent.core.model.command.AgentCommand
import com.mamba.picme.agent.core.model.context.AgentAction
import com.mamba.picme.agent.core.model.context.AgentContext
import com.mamba.picme.agent.core.model.context.AgentErrorCode
import com.mamba.picme.agent.core.model.context.AgentScene
import com.mamba.picme.agent.core.model.context.PageContext
import com.mamba.picme.agent.core.model.plan.StepResult
import com.mamba.picme.agent.core.platform.logging.Logger
import com.mamba.picme.agent.core.runtime.execution.ExecutionEngine
import com.mamba.picme.agent.core.runtime.execution.ExecutionReporterImpl
import com.mamba.picme.agent.core.runtime.state.SceneManager
import kotlinx.coroutines.CoroutineScope

/**
 * 能力注册表（唯一注册容器）
 *
 * 应用级单例，负责：
 * - 按场景过滤 Capability
 * - 页面上下文传递
 * - 命令分发到对应 Capability
 * - 跨页面命令队列管理（委托给 CrossPageCommandQueue）
 *
 * **架构原则（2026-07-29 单轨收敛）**：
 * - 本类是唯一注册表：应用级 Capability 在 Application.onCreate() 注册一次、永不注销；
 *   页面级 Capability（CameraCapability）随页面进入 [register]、退出 [unregister]
 * - 注册与可用性分离：能否找到 = 注册表（静态）；能否执行 = activeScenes() + isAvailable()
 * - 支持跨页面指令排队执行
 *
 * 重构后职责拆分：
 * - CapabilityRegistry：纯注册表 + 查询 + 分发入口
 * - CommandExecutor：命令执行（超时、异常处理）
 * - CrossPageCommandQueue：跨页面队列管理
 */
class CapabilityRegistry private constructor(
    private val sceneManager: SceneManager,
    private val externalScope: CoroutineScope? = null
) {

    companion object {
        // KMP commonMain 无 synchronized，lazy 默认 SYNCHRONIZED 模式保证同款线程安全单例语义
        private val singleton: CapabilityRegistry by lazy {
            CapabilityRegistry(SceneManager.getInstance())
        }

        /**
         * 获取单例实例（使用默认协程作用域）
         */
        fun getInstance(): CapabilityRegistry = singleton

        /**
         * 创建实例（支持注入外部协程作用域，便于生命周期绑定和测试）
         */
        fun create(
            sceneManager: SceneManager = SceneManager.getInstance(),
            scope: CoroutineScope? = null
        ): CapabilityRegistry {
            return CapabilityRegistry(sceneManager, scope)
        }
    }

    private val tag = "CapabilityRegistry"
    private val registry = mutableMapOf<String, Capability>()

    // 委托组件
    // 内层超时显式 25s（见 CommandExecutor.REGISTRY_COMMAND_TIMEOUT_MS）：默认 10s 会紧于
    // browser 外层 dispatch 25s，慢页面被内层必杀而外层永不触发；既有工具外层 5s 更紧不受影响。
    private val commandExecutor = CommandExecutor(timeoutMs = CommandExecutor.REGISTRY_COMMAND_TIMEOUT_MS)

    /** 内层执行超时（毫秒），供层叠不变式守卫测试（CommandTimeoutLayeringTest）读取实际生效值。 */
    internal val commandExecutorTimeoutMs: Long
        get() = commandExecutor.timeoutMs
    private val commandQueue = CrossPageCommandQueue(
        sceneManager = sceneManager,
        commandExecutor = commandExecutor,
        findCapability = ::findCapabilityForCommand,
        externalScope = externalScope
    )

    /** 队列状态事件流（透传自 CrossPageCommandQueue） */
    val queueEvents = commandQueue.queueEvents

    /**
     * 注册 Capability（应用级 Capability 启动期注册一次，永不注销；
     * 页面级 Capability 随页面进入注册、退出时 [unregister]）
     */
    fun register(capability: Capability) {
        val existing = registry[capability.name]
        if (existing === capability) {
            return
        }
        if (existing != null) {
            // 同名不同实例：典型场景是 Activity recreate 后新 NavigationCapability 注册——
            // 旧实例闭包捕获的 composition scope 已取消，继续持有会让 agent 导航静默失效
            // （navigate_to 返回 Success 但 scrollToPage 从未执行）。替换为存活的新实例。
            Logger.w(tag, "Capability ${capability.name} already registered with a stale instance, replacing")
        }
        registry[capability.name] = capability
        Logger.i(tag, "Registered capability: ${capability.name} " +
            "(scenes: ${capability.activeScenes().joinToString { it.name }})")
    }

    /**
     * 注销 Capability（仅页面级 Capability 使用，如 CameraCapability 随 CameraScreen 销毁注销）
     *
     * 实例感知：仅当注册表当前持有的就是该实例时才移除，防止旧实例的 onDispose
     * 竞态把已替换的新实例一并摘除。
     */
    fun unregister(capability: Capability) {
        if (registry[capability.name] === capability && registry.remove(capability.name) != null) {
            Logger.i(tag, "Unregistered capability: ${capability.name}")
        }
    }

    /**
     * 获取指定名称的 Capability
     */
    fun get(name: String): Capability? {
        return registry[name]
    }

    /**
     * 获取所有已注册的 Capability
     */
    fun getAll(): List<Capability> {
        return registry.values.toList()
    }

    /**
     * 获取当前场景下活跃的 Capability 列表
     *
     * 只返回在当前场景活跃的 Capability（不检查 isAvailable）
     * 用于构建 system prompt，让 LLM 知道当前页面"应该"支持哪些命令
     */
    fun getCapabilitiesForCurrentScene(): List<Capability> {
        val currentScene = sceneManager.currentScene.value
        return registry.values.filter { capability ->
            capability.activeScenes().contains(currentScene) ||
                    capability.activeScenes().isEmpty()
        }
    }

    /**
     * 分发命令到对应的 Capability
     *
     * **跨页面指令支持**：
     * - 如果目标 Capability 在当前场景不可用（场景不匹配或 delegate 未绑定），命令会自动入队
     * - 当目标页面激活时，队列中的命令会自动执行
     */
    suspend fun dispatch(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext? = null
    ): Result<AgentAction> {
        val commandType = command::class.simpleName ?: "Unknown"
        val currentScene = sceneManager.currentScene.value

        return when (command) {
            is AgentCommand.TextReply -> {
                Result.success(AgentAction.TextReply(commandId = command.commandId, message = command.message))
            }
            is AgentCommand.Unknown -> {
                Logger.w(tag, "[$commandType] Unknown command at $currentScene")
                Result.success(
                    AgentAction.TextReply(
                        commandId = command.commandId,
                        message = "收到你的消息了，但没理解具体意图，请再描述一下~"
                    )
                )
            }
            is AgentCommand.Error -> {
                Logger.e(tag, "[$commandType] Command error: ${command.reason}")
                Result.success(
                    AgentAction.Error(
                        commandId = command.commandId,
                        errorCode = AgentErrorCode.INVALID_REQUEST,
                        message = command.reason
                    )
                )
            }
            is AgentCommand.BatchExecute -> {
                dispatchBatch(command, context, pageContext, currentScene)
            }
            is AgentCommand.Delay -> {
                Logger.i(tag, "[Delay] Delay command (${command.delayMs}ms) is a timing primitive, handled by BatchExecute")
                Result.success(AgentAction.Success(commandId = command.commandId, command = command))
            }
            is AgentCommand.ExecutePlan -> {
                dispatchPlan(command, context, pageContext)
            }
            else -> {
                dispatchWithQueueSupport(command, context, pageContext, currentScene, commandType)
            }
        }
    }

    /**
     * 分发命令，支持跨页面排队
     */
    private suspend fun dispatchWithQueueSupport(
        command: AgentCommand,
        context: AgentContext,
        pageContext: PageContext?,
        currentScene: SceneManager.Scene,
        commandType: String
    ): Result<AgentAction> {
        val capability = findCapabilityForCommand(command)

        if (capability == null) {
            Logger.w(tag, "[$commandType] No capability found for command in scene $currentScene")
            val detail = "No capability found for command '${AgentCommand.getMethodName(command)}' in scene $currentScene"
            // 查找失败此前不过 CommandExecutor，tool_call_log 无记录（2026-07-29 盘点不可用故障即此类）
            CommandExecutor.recordDispatchEvent(
                capability = "(unresolved)",
                commandType = AgentCommand.getMethodName(command),
                success = false,
                errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                errorMessage = detail,
                traceId = context.traceId
            )
            return Result.success(
                AgentAction.Error(
                    commandId = command.commandId,
                    errorCode = AgentErrorCode.METHOD_NOT_FOUND,
                    message = "暂不支持此操作",
                    detail = detail
                )
            )
        }

        // 检查场景是否匹配
        val sceneMatch = capability.activeScenes().contains(currentScene) ||
                capability.activeScenes().isEmpty()

        // 检查 Capability 是否可用（delegate 是否绑定）
        val isAvailable = capability.isAvailable()

        // 如果场景不匹配或 Capability 不可用，将命令入队
        if (!sceneMatch || !isAvailable) {
            val reason = when {
                !sceneMatch -> "scene mismatch (current=$currentScene, required=${capability.activeScenes()})"
                else -> "delegate not bound"
            }
            Logger.i(tag, "[$commandType] Capability ${capability.name} unavailable ($reason), queuing command")
            // 入队 = 未立即执行：此前无记录，跨页指令黑洞无法排查
            CommandExecutor.recordDispatchEvent(
                capability = capability.name,
                commandType = AgentCommand.getMethodName(command),
                success = false,
                errorCode = AgentErrorCode.COMMAND_QUEUED,
                errorMessage = reason,
                traceId = context.traceId
            )
            commandQueue.enqueue(command, context, pageContext, capability)
            return Result.success(
                AgentAction.TextReply(
                    commandId = command.commandId,
                    message = "正在为您切换到对应页面执行操作..."
                )
            )
        }

        // 直接执行命令
        Logger.i(tag, "[$commandType] Dispatching to ${capability.name} in scene $currentScene")
        return commandExecutor.execute(command, context, pageContext, capability)
    }

    /**
     * 清空命令队列
     */
    suspend fun clearCommandQueue() {
        commandQueue.clear()
    }

    /**
     * 根据命令查找对应的 Capability
     *
     * 先查找当前场景的可用 Capability，找不到时查找所有已注册的 Capability（用于跨页面指令）。
     */
    private fun findCapabilityForCommand(command: AgentCommand): Capability? {
        val commandName = AgentCommand.getMethodName(command)

        // 首先在当前场景的可用 Capability 中查找
        val currentSceneCapabilities = getCapabilitiesForCurrentScene()
        val availableMatch = currentSceneCapabilities.find { capability ->
            capability.supportedCommands().contains(commandName)
        }
        if (availableMatch != null) return availableMatch

        // 如果当前场景找不到，在所有已注册的 Capability 中查找
        // 这支持跨页面指令：即使目标 Capability 当前不可用，也能找到它并排队
        return registry.values.find { it.supportedCommands().contains(commandName) }
    }

    /**
     * 构建 Capability 描述文本（用于 system prompt）
     *
     * 只包含当前场景可用且 isAvailable 的 Capability
     */
    fun buildCapabilityDescription(): String {
        val capabilities = getCapabilitiesForCurrentScene()
        return capabilities.joinToString("\n") { capability ->
            capability.buildCapabilityDescription()
        }
    }

    /**
     * 获取所有已注册 Capability 的描述（用于调试）
     */
    fun buildAllCapabilitiesDescription(): String {
        return registry.values.joinToString("\n") { capability ->
            val available = if (capability.isAvailable()) "✓" else "✗"
            "$available ${capability.buildCapabilityDescription()}"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 命令可用性检查
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 获取指定命令在当前场景是否可用
     *
     * 同时检查场景匹配和 Capability 可用性（delegate 是否绑定）
     */
    fun isCommandAvailable(command: AgentCommand): Boolean {
        val capability = findCapabilityForCommand(command) ?: return false
        val currentScene = sceneManager.currentScene.value
        return capability.activeScenes().contains(currentScene) && capability.isAvailable()
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 批量命令执行（BatchExecute）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 批量执行命令
     *
     * 顺序执行子命令列表，每个子命令独立分发到对应 Capability，
     * 收集所有子结果，汇总为 BatchResult。
     * - atomic=true 时，任一失败触发全部回滚（已执行的通过 fallback 补偿）
     */
    private suspend fun dispatchBatch(
        batchCommand: AgentCommand.BatchExecute,
        context: AgentContext,
        pageContext: PageContext?,
        currentScene: SceneManager.Scene
    ): Result<AgentAction> {
        Logger.i(tag, "[BatchExecute] Starting batch of ${batchCommand.commands.size} commands, atomic=${batchCommand.atomic}")

        if (batchCommand.commands.isEmpty()) {
            return Result.success(
                AgentAction.Error(
                    commandId = batchCommand.commandId,
                    errorCode = AgentErrorCode.INVALID_PARAMS,
                    message = "批量命令列表为空"
                )
            )
        }

        val results = mutableListOf<AgentAction>()
        val executedCommands = mutableListOf<AgentCommand>() // 用于 atomic 回滚

        for ((index, subCommand) in batchCommand.commands.withIndex()) {
            Logger.d(tag, "[BatchExecute] Executing sub-command ${index + 1}/${batchCommand.commands.size}: ${subCommand::class.simpleName}")

            // Delay 命令是定时原语，需要在此处实际等待
            if (subCommand is AgentCommand.Delay) {
                Logger.i(tag, "[BatchExecute] Executing delay of ${subCommand.delayMs}ms")
                Logger.i(tag, "[BatchExecute] Waiting ${subCommand.delayMs}ms...")
                kotlinx.coroutines.delay(subCommand.delayMs)
                Logger.i(tag, "[BatchExecute] Delay completed")
                results.add(AgentAction.Success(commandId = subCommand.commandId, command = subCommand))
                executedCommands.add(subCommand)
                continue
            }

            val subResult = dispatch(subCommand, context, pageContext)
            val action = subResult.getOrNull()

            if (subResult.isFailure || action == null) {
                val errorMsg = subResult.exceptionOrNull()?.message ?: "子命令执行失败"
                Logger.w(tag, "[BatchExecute] Sub-command ${index + 1} failed: $errorMsg")

                // atomic 模式：回滚已执行的命令
                if (batchCommand.atomic) {
                    rollbackExecuted(executedCommands, context, pageContext)
                }

                results.add(
                    AgentAction.Error(
                        commandId = subCommand.commandId,
                        errorCode = AgentErrorCode.INTERNAL_ERROR,
                        message = errorMsg,
                        detail = "Batch sub-command ${index + 1} failed"
                    )
                )
                return Result.success(
                    AgentAction.BatchResult(
                        commandId = batchCommand.commandId,
                        results = results.toList()
                    )
                )
            }

            results.add(action)
            executedCommands.add(subCommand)

            // 检查子结果是否表示失败（即使 Result 是成功的）
            if (!action.isSuccess) {
                Logger.w(tag, "[BatchExecute] Sub-command ${index + 1} returned error action")

                if (batchCommand.atomic) {
                    rollbackExecuted(executedCommands, context, pageContext)
                }

                return Result.success(
                    AgentAction.BatchResult(
                        commandId = batchCommand.commandId,
                        results = results.toList()
                    )
                )
            }
        }

        Logger.i(tag, "[BatchExecute] All ${batchCommand.commands.size} commands completed successfully")
        return Result.success(
            AgentAction.BatchResult(
                commandId = batchCommand.commandId,
                results = results.toList()
            )
        )
    }

    /**
     * 原子模式回滚
     *
     * 对已执行的命令尝试执行反向操作（简化实现，实际回滚逻辑由具体 Capability 决定）
     */
    private suspend fun rollbackExecuted(
        executedCommands: List<AgentCommand>,
        context: AgentContext,
        pageContext: PageContext?
    ) {
        Logger.w(tag, "[BatchExecute] Atomic rollback: ${executedCommands.size} commands to revert")
        // 注意：实际回滚需要每个 Capability 支持 undo 操作
        // 当前版本仅记录日志，后续可通过 Capability 扩展 undo 接口
        for (cmd in executedCommands.asReversed()) {
            Logger.d(tag, "[BatchExecute] Rollback: ${cmd::class.simpleName} (id=${cmd.commandId})")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // 执行计划分发（ExecutePlan）
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * 分发执行计划到 ExecutionEngine
     */
    private suspend fun dispatchPlan(
        planCommand: AgentCommand.ExecutePlan,
        context: AgentContext,
        pageContext: PageContext?
    ): Result<AgentAction> {
        Logger.i(tag, "[ExecutePlan] Dispatching plan: ${planCommand.plan.planId}, steps=${planCommand.plan.steps.size}")

        val engine = ExecutionEngine(
            capabilityRegistry = this,
            reporter = ExecutionReporterImpl()
        )

        return try {
            val result = engine.execute(planCommand.plan)
            Logger.i(tag, "[ExecutePlan] Plan completed: success=${result.isSuccess}, steps=${result.stepResults.size}")

            // 将 ExecutionResult 转换为 AgentAction
            if (result.isSuccess) {
                val lastAction = result.actions.lastOrNull()
                if (lastAction != null) {
                    Result.success(lastAction)
                } else {
                    Result.success(
                        AgentAction.Success(
                            commandId = planCommand.commandId,
                            command = planCommand
                        )
                    )
                }
            } else {
                val firstError = result.stepResults
                    .filterIsInstance<StepResult.Failed>()
                    .firstOrNull()
                Result.success(
                    AgentAction.Error(
                        commandId = planCommand.commandId,
                        errorCode = firstError?.action?.errorCode ?: AgentErrorCode.INTERNAL_ERROR,
                        message = firstError?.action?.message ?: "计划执行失败",
                        detail = "Plan '${planCommand.plan.planId}' failed at step ${firstError?.step?.step ?: "unknown"}"
                    )
                )
            }
        } catch (throwable: Throwable) {
            Logger.e(tag, "[ExecutePlan] Plan execution threw exception", throwable)
            Result.success(
                AgentAction.Error(
                    commandId = planCommand.commandId,
                    errorCode = AgentErrorCode.INTERNAL_ERROR,
                    message = "计划执行异常: ${throwable.message}",
                    detail = throwable.stackTraceToString()
                )
            )
        }
    }
}
