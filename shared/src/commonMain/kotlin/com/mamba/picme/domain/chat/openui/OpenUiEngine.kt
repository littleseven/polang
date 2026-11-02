package com.mamba.picme.domain.chat.openui

import kotlinx.coroutines.CancellationException

/**
 * 宿主工具调用（Query 语句，§6.4）。工具缺失时实现方抛 [OpenUiToolNotFoundException]，
 * engine 捕获记 tool-not-found 错误并用 defaults；其他异常记 tool-error 同样回退 defaults。
 */
fun interface OpenUiToolResolver {
    suspend fun call(name: String, args: Map<String, OpenUiValue>): OpenUiValue
}

class OpenUiToolNotFoundException(tool: String) : Exception("OpenUI host tool not found: $tool")

data class OpenUiError(val code: String, val statementId: String? = null, val message: String = "")

sealed interface OpenUiResult {
    data class Rendered(val root: OpenUiValue.ComponentVal, val errors: List<OpenUiError>) : OpenUiResult

    /** no-root / parse-failed / parse-exception 致命。 */
    data class Failed(val errors: List<OpenUiError>) : OpenUiResult
}

/**
 * OpenUI Lang 1.0 会话（M1 批量模式）：parse → 语句分类（§2.1）→ state 初始化（§5.1）→
 * Query 统一求值一次（§6.1 M1 简化：传输整帧交付，无流式）→ entry 解析（§2.2）→
 * 组件校验恢复（§8.2/§8.4）。
 *
 * [applyAction] 是 @Set/@Reset 落点：写 state store 并重求值组件树（程序微小，全量重求值，
 * 不建增量依赖图）；Query 结果缓存不因重求值重跑（§6.1 仅 args 内字面 $var 变更才重取，M1 从简）。
 *
 * [runToolAction] 是 @Run 落点（官方 actions 语义）：target 为 Mutation 语句时执行其工具调用，
 * 结果以 `{status, data, error}` 对象入 mutation 缓存（可绑定 UI，如 `fav.status == "success"`）；
 * target 为 Query 语句时按当前 store 重取。返回值供宿主链式分发——Mutation 失败返回 false，
 * 宿主应中断后续动作链（官方：renderer 执行、失败中断后续 actions）。
 */
class OpenUiSession(
    private val program: String,
    private val tools: OpenUiToolResolver,
    initialState: Map<String, OpenUiValue> = emptyMap(),
) {
    private val store = mutableMapOf<String, OpenUiValue>()
    private val queryResults = mutableMapOf<String, OpenUiValue>()
    private val mutationResults = mutableMapOf<String, OpenUiValue>()
    private var statements: Map<String, OpenUiStatement> = emptyMap()
    private var orderedStatements: List<OpenUiStatement> = emptyList()
    private var entryName: String? = null
    private var fallbackEntryUsed = false
    private var prepared = false
    private var lastResult: OpenUiResult? = null

    /** action 期错误史（@Run 工具失败/目标非法等）：reevaluate 重建 lastResult 时并入，不被冲掉。 */
    private val actionErrors = mutableListOf<OpenUiError>()

    init {
        // 宿主持久化 state 覆盖默认值（§5.1），先于 prepare 落 store
        store.putAll(initialState)
    }

    /** 最近一次求值后的 state store 快照（含 $ 前缀键）。 */
    val state: Map<String, OpenUiValue> get() = store.toMap()

    /** 孤儿语句名（entry 不可达的 value 语句，§2.3；致命 no-root 时为空）。 */
    var orphans: Set<String> = emptySet()
        private set

    /** 未解析引用名（缺失语句 + 成环点，§2.3 metadata）。 */
    var unresolved: Set<String> = emptySet()
        private set

    /** 解析+校验+Query 求值+entry 解析。幂等，可重复调用。 */
    suspend fun prepare(): OpenUiResult {
        lastResult?.let { cached -> return cached }
        prepared = true
        return try {
            prepareInternal()
        } catch (e: CancellationException) {
            throw e // 协程取消穿透（同 runQuery 口径）
        } catch (t: Throwable) {
            // §8.1「模型写的任何东西不得让客户端崩溃」（review 🟡3）：解析/求值链路上的
            // StackOverflowError 等 Error 也不逃逸，致命降级 parse-exception
            fail(
                listOf(
                    OpenUiError(
                        "parse-exception",
                        message = "解析/求值崩溃兜底：${t.message ?: t::class.simpleName}",
                    )
                )
            )
        }
    }

    private suspend fun prepareInternal(): OpenUiResult {
        val parsed = OpenUiParser.parse(program)
        if (parsed.parseException) {
            return fail(listOf(OpenUiError("parse-exception", message = "解析器自身异常")))
        }
        if (parsed.statements.isEmpty()) {
            // §8.2：响应有文本但产不出语句 → parse-failed；空输入 → no-root
            val code = if (parsed.sourceHadText) "parse-failed" else "no-root"
            return fail(listOf(OpenUiError(code, message = "无可执行语句")))
        }

        // 重名后写覆盖（§2.4）
        val byName = linkedMapOf<String, OpenUiStatement>()
        parsed.statements.forEach { statement -> byName[statement.name] = statement }

        // 内联 Query 提升（2026-10-24 all_years 事故：LLM 用命名实参把 buckets=Query(...) 内联进
        // BarChart 调用，值位置 inline-reserved 禁则致整条卡降级）：非语句右值位置的 QueryCall
        // 提升为合成 QUERY 语句（prepare 期统一求值一次的语义不变，applyAction 重求值仍读缓存），
        // 原位留 Ref。Mutation 保持不执行，不做提升。
        var hoistCounter = 0
        val synthetic = mutableListOf<OpenUiStatement>()
        fun hoist(node: OpenUiExpr): OpenUiExpr = when (node) {
            is OpenUiExpr.QueryCall -> {
                var name = "__inline_query_${hoistCounter++}"
                while (byName.containsKey(name) || synthetic.any { it.name == name }) {
                    name = "__inline_query_${hoistCounter++}"
                }
                synthetic += OpenUiStatement(name, OpenUiStatementKind.QUERY, node)
                OpenUiExpr.Ref(name)
            }
            is OpenUiExpr.Assign -> node.copy(value = hoist(node.value))
            is OpenUiExpr.Arr -> node.copy(items = node.items.map { element -> hoist(element) })
            is OpenUiExpr.Obj -> node.copy(entries = node.entries.map { (key, v) -> key to hoist(v) })
            is OpenUiExpr.CompCall -> node.copy(args = node.args.map { arg -> hoist(arg) })
            is OpenUiExpr.AtCall -> node.copy(args = node.args.map { arg -> hoist(arg) })
            is OpenUiExpr.ActionCall -> node.copy(args = node.args.map { arg -> hoist(arg) })
            is OpenUiExpr.Member -> node.copy(target = hoist(node.target))
            is OpenUiExpr.Index -> node.copy(target = hoist(node.target), index = hoist(node.index))
            is OpenUiExpr.Unary -> node.copy(operand = hoist(node.operand))
            is OpenUiExpr.Binary -> node.copy(left = hoist(node.left), right = hoist(node.right))
            is OpenUiExpr.Ternary -> node.copy(
                cond = hoist(node.cond),
                then = hoist(node.then),
                otherwise = hoist(node.otherwise),
            )
            else -> node
        }
        val hoisted = byName.values.toList().map { statement ->
            val expr = statement.expr
            if (statement.kind == OpenUiStatementKind.QUERY && expr is OpenUiExpr.QueryCall) {
                // 语句右值本身的 QueryCall 不提升，只深入其 args（嵌套 Query 仍提升）
                OpenUiStatement(
                    statement.name,
                    statement.kind,
                    expr.copy(args = expr.args.map { arg -> hoist(arg) }),
                )
            } else {
                OpenUiStatement(statement.name, statement.kind, hoist(expr))
            }
        }
        byName.clear()
        (hoisted + synthetic).forEach { statement -> byName[statement.name] = statement }
        statements = byName
        orderedStatements = byName.values.toList()

        val runtime = OpenUiRuntime(statements, store, queryResults, mutationResults)

        // state 声明默认值：仅当变量还没有值（宿主持久化 state 已先入 store）
        orderedStatements
            .filter { statement -> statement.kind == OpenUiStatementKind.STATE }
            .forEach { statement ->
                if (!store.containsKey(statement.name)) {
                    store[statement.name] =
                        runtime.evalExpr(statement.expr, setOf(statement.name), statement.name, valuePosition = false)
                            ?: OpenUiValue.Null
                }
            }

        // Query：prepare 时对每条 Query 统一求值一次（args 内引用先求值，§6.1）
        orderedStatements
            .filter { statement -> statement.kind == OpenUiStatementKind.QUERY }
            .forEach { statement -> runQuery(statement, runtime) }

        // entry 解析（§2.2）
        val rootStatement = statements["root"]
        var rootValue: OpenUiValue? = null
        var fatalNoRoot = false
        if (rootStatement != null) {
            entryName = "root"
            rootValue = runtime.evalStatement("root")
            // root 值非组件：仍是 entry，致命 no-root，无 fallback
            if (rootValue !is OpenUiValue.ComponentVal) fatalNoRoot = true
        } else {
            val candidate = orderedStatements.firstOrNull { statement ->
                statement.kind == OpenUiStatementKind.VALUE &&
                    (statement.expr as? OpenUiExpr.CompCall)?.type == OpenUiLibrary.ROOT_COMPONENT
            }
            if (candidate == null) {
                fatalNoRoot = true
            } else {
                entryName = candidate.name
                rootValue = runtime.evalStatement(candidate.name)
                if (rootValue is OpenUiValue.ComponentVal) {
                    fallbackEntryUsed = true
                } else {
                    fatalNoRoot = true
                }
            }
        }
        if (fatalNoRoot) entryName = null

        // 孤儿（§2.3）：root 在（无论值）或 fallback 渲染时计算；无 entry 则无孤儿
        val reachabilityAnchor = if (statements.containsKey("root")) "root" else entryName
        orphans = if (reachabilityAnchor != null) computeOrphans(reachabilityAnchor) else emptySet()
        unresolved = runtime.unresolved

        val errors = runtime.errors.distinct()
        return if (fatalNoRoot) {
            fail(errors + OpenUiError("no-root", message = "无可用 entry"))
        } else {
            val allErrors = if (fallbackEntryUsed) {
                errors + OpenUiError("no-root", entryName, "无 root 语句，fallback 到首个 Card 语句")
            } else {
                errors
            }
            OpenUiResult.Rendered(rootValue as OpenUiValue.ComponentVal, allErrors).also { rendered ->
                lastResult = rendered
            }
        }
    }

    /** @Set/@Reset 落点：写 state store 并重求值组件树（Query 结果缓存不重跑）。 */
    fun applyAction(action: OpenUiAction) {
        if (!prepared) return
        when (action.name) {
            OpenUiContract.Actions.SET -> action.target?.let { target ->
                store[target] = action.args.getOrNull(0) ?: OpenUiValue.Null
            }
            OpenUiContract.Actions.RESET -> {
                val targets = action.args.mapNotNull { arg -> (arg as? OpenUiValue.Str)?.v }
                    .ifEmpty { listOfNotNull(action.target) }
                targets.forEach { target -> store[target] = declaredDefault(target) }
            }
            else -> return // @Run 走 runToolAction；@ToAssistant/@OpenUrl/自定义动作由宿主消费，不动 store
        }
        reevaluateSafely()
    }

    /**
     * @Run 落点：执行 Mutation 工具调用或重取 Query（官方 actions 语义）。
     *
     * - Mutation：args 按当前 store 求值后调宿主工具，结果写 mutation 缓存
     *   （`{status: success|error, data, error}`，引用该语句的绑定随重求值刷新）；
     *   失败返回 false——宿主链式分发应中断后续动作。
     * - Query：按当前 store 重取并刷新缓存；失败记错误、保留旧缓存，不中断链（返回 true）。
     * - 其他/未知 target：记告警返回 false（链不可继续）。
     */
    suspend fun runToolAction(target: String): Boolean {
        if (!prepared) return false
        val statement = statements[target]
        if (statement == null) {
            appendActionError(OpenUiError("run-target-missing", target, "@Run 目标语句不存在：$target"))
            return false
        }
        return when (statement.kind) {
            OpenUiStatementKind.MUTATION -> runMutation(statement)
            OpenUiStatementKind.QUERY -> {
                val runtime = OpenUiRuntime(statements, store, queryResults, mutationResults)
                runQuery(statement, runtime)
                runtime.errors.forEach { error -> appendActionError(error) }
                reevaluateSafely()
                true
            }
            else -> {
                appendActionError(
                    OpenUiError("run-target-invalid", target, "@Run 目标非 Query/Mutation：$target"),
                )
                false
            }
        }
    }

    private suspend fun runMutation(statement: OpenUiStatement): Boolean {
        val call = statement.expr as? OpenUiExpr.MutationCall
        if (call == null) {
            appendActionError(
                OpenUiError("run-target-invalid", statement.name, "Mutation 语句右值非 Mutation 调用"),
            )
            return false
        }
        val runtime = OpenUiRuntime(statements, store, queryResults, mutationResults)
        val stack = setOf(statement.name)
        val toolName =
            (call.args.getOrNull(0)?.let { expr ->
                runtime.evalExpr(expr, stack, statement.name, valuePosition = false)
            } as? OpenUiValue.Str)?.v ?: ""
        val argsValue = call.args.getOrNull(1)?.let { expr ->
            runtime.evalExpr(expr, stack, statement.name, valuePosition = false)
        }
        val argsMap = (argsValue as? OpenUiValue.Obj)?.v ?: emptyMap()
        runtime.errors.forEach { error -> appendActionError(error) }
        mutationResults[statement.name] = OpenUiValue.Obj(
            mapOf(
                "status" to OpenUiValue.Str("pending"),
                "data" to OpenUiValue.Null,
                "error" to OpenUiValue.Null,
            ),
        )
        val result = try {
            val data = tools.call(toolName, argsMap)
            OpenUiValue.Obj(
                mapOf(
                    "status" to OpenUiValue.Str("success"),
                    "data" to data,
                    "error" to OpenUiValue.Null,
                ),
            )
        } catch (e: OpenUiToolNotFoundException) {
            mutationFailure(statement.name, "tool-not-found", e.message ?: "工具缺失：$toolName")
        } catch (e: CancellationException) {
            mutationResults.remove(statement.name)
            throw e // 协程取消穿透（同 runQuery 口径），不留 pending 假相
        } catch (e: Exception) {
            mutationFailure(statement.name, "tool-error", "工具 $toolName 调用失败：${e.message}")
        }
        mutationResults[statement.name] = result
        reevaluateSafely()
        return (result.v["status"] as? OpenUiValue.Str)?.v == "success"
    }

    private fun mutationFailure(name: String, code: String, message: String): OpenUiValue.Obj {
        appendActionError(OpenUiError(code, name, message))
        return OpenUiValue.Obj(
            mapOf(
                "status" to OpenUiValue.Str("error"),
                "data" to OpenUiValue.Null,
                "error" to OpenUiValue.Str(message),
            ),
        )
    }

    /** action 期错误入史并并入当前 lastResult（重求值重建 lastResult 时由 actionErrors 回填）。 */
    private fun appendActionError(error: OpenUiError) {
        actionErrors += error
        lastResult = when (val current = lastResult) {
            is OpenUiResult.Rendered -> current.copy(errors = current.errors + error)
            is OpenUiResult.Failed -> current.copy(errors = current.errors + error)
            null -> OpenUiResult.Failed(listOf(error))
        }
    }

    /**
     * §8.1 交互路径同口径兜底（2026-11-01 review Y3）：action 触发的重求值与 prepare 一样
     * 「不得让客户端崩溃」——用户点击在主线程同步进这里，任何求值路径未来的非全函数
     * 回归都不能逃逸；崩溃时保留 lastResult 旧树不动，错误并入 errors（宿主经
     * [lastErrors] 观测）。当前 eval 层是全函数（公开语言面无确定性 throw 路径），
     * 本兜底是未来回归的保险丝，故无单测钉住触发路径（注入测试缝的成本大于收益）。
     */
    private fun reevaluateSafely() {
        try {
            reevaluate()
        } catch (e: CancellationException) {
            throw e // 协程取消穿透（与 prepare 口径对齐）
        } catch (t: Throwable) {
            appendActionError(
                OpenUiError(
                    "action-exception",
                    message = "动作重求值崩溃兜底：${t.message ?: t::class.simpleName}",
                ),
            )
        }
    }

    /** 最近一次求值（prepare 或 applyAction 重求值）的错误快照——宿主观测 action 期错误的唯一出口。 */
    fun lastErrors(): List<OpenUiError> = when (val result = lastResult) {
        is OpenUiResult.Rendered -> result.errors
        is OpenUiResult.Failed -> result.errors
        null -> emptyList()
    }

    /** 当前组件树根（prepare 后可用）。 */
    fun rootOrNull(): OpenUiValue.ComponentVal? = (lastResult as? OpenUiResult.Rendered)?.root

    private fun fail(errors: List<OpenUiError>): OpenUiResult.Failed {
        val failed = OpenUiResult.Failed(errors)
        lastResult = failed
        return failed
    }

    private suspend fun runQuery(statement: OpenUiStatement, runtime: OpenUiRuntime) {
        val call = statement.expr as? OpenUiExpr.QueryCall ?: return
        val stack = setOf(statement.name)
        val toolName =
            (call.args.getOrNull(0)?.let { expr ->
                runtime.evalExpr(expr, stack, statement.name, valuePosition = false)
            } as? OpenUiValue.Str)?.v ?: ""
        val argsValue = call.args.getOrNull(1)?.let { expr ->
            runtime.evalExpr(expr, stack, statement.name, valuePosition = false)
        }
        val argsMap = (argsValue as? OpenUiValue.Obj)?.v ?: emptyMap()
        val defaults = call.args.getOrNull(2)?.let { expr ->
            runtime.evalExpr(expr, stack, statement.name, valuePosition = false)
        } ?: OpenUiValue.Null
        queryResults[statement.name] = try {
            tools.call(toolName, argsMap)
        } catch (e: OpenUiToolNotFoundException) {
            runtime.errors += OpenUiError(
                "tool-not-found",
                statement.name,
                e.message ?: "工具缺失：$toolName",
            )
            defaults
        } catch (e: CancellationException) {
            // 协程取消必须穿透（review 🟡2）：吞掉会让调用方 cancel 失效、回合无法中止
            throw e
        } catch (e: Exception) {
            runtime.errors += OpenUiError(
                "tool-error",
                statement.name,
                "工具 $toolName 调用失败：${e.message}",
            )
            defaults
        }
    }

    /** @Reset 的声明默认值（无声明 → null，§6.3）；默认值表达式按当前 store 重求值。 */
    private fun declaredDefault(name: String): OpenUiValue {
        val statement = statements[name] ?: return OpenUiValue.Null
        if (statement.kind != OpenUiStatementKind.STATE) return OpenUiValue.Null
        val runtime = OpenUiRuntime(statements, store, queryResults, mutationResults)
        return runtime.evalExpr(statement.expr, setOf(name), name, valuePosition = false) ?: OpenUiValue.Null
    }

    private fun reevaluate() {
        val entry = entryName ?: return
        val runtime = OpenUiRuntime(statements, store, queryResults, mutationResults)
        val rootValue = runtime.evalStatement(entry)
        unresolved = runtime.unresolved
        // action 期错误史回填：@Run 工具失败等不被重求值重建冲掉（重复条目 distinct 收敛）
        val errors = (runtime.errors + actionErrors).distinct().let { list ->
            if (fallbackEntryUsed) {
                list + OpenUiError("no-root", entry, "无 root 语句，fallback 到首个 Card 语句")
            } else {
                list
            }
        }
        lastResult = if (rootValue is OpenUiValue.ComponentVal) {
            OpenUiResult.Rendered(rootValue, errors)
        } else {
            OpenUiResult.Failed(errors + OpenUiError("no-root", entry, "重求值后 entry 不再是组件"))
        }
    }

    /**
     * 可达性闭包（§2.3）：自 entry 与所有 Query/Mutation 语句（prepare 期必执行，
     * 其 args 内引用计入可达）起沿引用遍历；不可达的 value 语句 = 孤儿
     * （state/query/mutation 永不孤儿）。
     */
    private fun computeOrphans(entry: String): Set<String> {
        val roots = orderedStatements
            .filter { statement ->
                statement.kind == OpenUiStatementKind.QUERY ||
                    statement.kind == OpenUiStatementKind.MUTATION
            }
            .map { statement -> statement.name }
        val reachable = linkedSetOf(entry)
        val queue = ArrayDeque(listOf(entry) + roots)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val statement = statements[name] ?: continue
            collectRefs(statement.expr).forEach { ref ->
                if (reachable.add(ref)) queue.addLast(ref)
            }
        }
        return orderedStatements
            .filter { statement -> statement.kind == OpenUiStatementKind.VALUE }
            .map { statement -> statement.name }
            .filter { name -> name !in reachable }
            .toSet()
    }

    private fun collectRefs(expr: OpenUiExpr): Set<String> {
        val out = linkedSetOf<String>()
        fun walk(node: OpenUiExpr) {
            when (node) {
                is OpenUiExpr.Ref -> out += node.name
                is OpenUiExpr.Assign -> walk(node.value)
                is OpenUiExpr.Arr -> node.items.forEach { element -> walk(element) }
                is OpenUiExpr.Obj -> node.entries.forEach { entry -> walk(entry.second) }
                is OpenUiExpr.CompCall -> node.args.forEach { arg -> walk(arg) }
                is OpenUiExpr.AtCall -> node.args.forEach { arg -> walk(arg) }
                is OpenUiExpr.QueryCall -> node.args.forEach { arg -> walk(arg) }
                is OpenUiExpr.MutationCall -> node.args.forEach { arg -> walk(arg) }
                is OpenUiExpr.ActionCall -> node.args.forEach { arg -> walk(arg) }
                is OpenUiExpr.Member -> walk(node.target)
                is OpenUiExpr.Index -> {
                    walk(node.target)
                    walk(node.index)
                }
                is OpenUiExpr.Unary -> walk(node.operand)
                is OpenUiExpr.Binary -> {
                    walk(node.left)
                    walk(node.right)
                }
                is OpenUiExpr.Ternary -> {
                    walk(node.cond)
                    walk(node.then)
                    walk(node.otherwise)
                }
                else -> Unit
            }
        }
        walk(expr)
        return out
    }
}
