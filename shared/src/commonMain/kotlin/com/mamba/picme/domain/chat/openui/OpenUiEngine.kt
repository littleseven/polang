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
 */
class OpenUiSession(
    private val program: String,
    private val tools: OpenUiToolResolver,
    initialState: Map<String, OpenUiValue> = emptyMap(),
) {
    private val store = mutableMapOf<String, OpenUiValue>()
    private val queryResults = mutableMapOf<String, OpenUiValue>()
    private var statements: Map<String, OpenUiStatement> = emptyMap()
    private var orderedStatements: List<OpenUiStatement> = emptyList()
    private var entryName: String? = null
    private var fallbackEntryUsed = false
    private var prepared = false
    private var lastResult: OpenUiResult? = null

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
        statements = byName
        orderedStatements = byName.values.toList()

        val runtime = OpenUiRuntime(statements, store, queryResults)

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
            "Set" -> action.target?.let { target ->
                store[target] = action.args.getOrNull(0) ?: OpenUiValue.Null
            }
            "Reset" -> {
                val targets = action.args.mapNotNull { arg -> (arg as? OpenUiValue.Str)?.v }
                    .ifEmpty { listOfNotNull(action.target) }
                targets.forEach { target -> store[target] = declaredDefault(target) }
            }
            else -> return // @ToAssistant/@OpenUrl 由渲染层消费，不动 store
        }
        reevaluate()
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
        val runtime = OpenUiRuntime(statements, store, queryResults)
        return runtime.evalExpr(statement.expr, setOf(name), name, valuePosition = false) ?: OpenUiValue.Null
    }

    private fun reevaluate() {
        val entry = entryName ?: return
        val runtime = OpenUiRuntime(statements, store, queryResults)
        val rootValue = runtime.evalStatement(entry)
        unresolved = runtime.unresolved
        val errors = runtime.errors.distinct().let { list ->
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
