package com.mamba.picme.domain.chat.openui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

// ── §3.1 强转与格式化（ECMA-262 ToNumber / Number::toString 的复现） ──────────

private val decimalNumberRegex = Regex("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)([eE][+-]?\\d+)?$")
private val hexNumberRegex = Regex("^[+-]?0[xX][0-9a-fA-F]+$")

/** ECMA ToNumber(string)：空白 trim、空串=0、Infinity、0x 十六进制、十进制；解析失败=null（调用方落 0）。 */
internal fun openUiParseNumber(raw: String): Double? {
    val s = raw.trim()
    if (s.isEmpty()) return 0.0
    if (s == "Infinity" || s == "+Infinity") return Double.POSITIVE_INFINITY
    if (s == "-Infinity") return Double.NEGATIVE_INFINITY
    if (hexNumberRegex.matches(s)) {
        val negative = s.startsWith("-")
        val digits = s.removePrefix("+").removePrefix("-").substring(2)
        val value = digits.toULongOrNull(16)?.toDouble() ?: return null
        return if (negative) -value else value
    }
    if (decimalNumberRegex.matches(s)) return s.toDoubleOrNull()
    return null
}

/** §3.1 toNumber：数字是自身（NaN 钳到 0——「NaN 永不出现」），字符串解析失败=0，true=1，其余=0。 */
internal fun openUiToNumber(value: OpenUiValue): Double = when (value) {
    is OpenUiValue.Num -> if (value.v.isNaN()) 0.0 else value.v
    is OpenUiValue.Str -> openUiParseNumber(value.v) ?: 0.0
    is OpenUiValue.Bool -> if (value.v) 1.0 else 0.0
    else -> 0.0
}

/** ECMA Number::toString：整数不带小数点；NaN/Infinity 仅防御（正常求值不会产生）。 */
internal fun openUiNumToString(value: Double): String = when {
    value.isNaN() -> "NaN"
    value == Double.POSITIVE_INFINITY -> "Infinity"
    value == Double.NEGATIVE_INFINITY -> "-Infinity"
    value == floor(value) && abs(value) < 1e21 -> value.toLong().toString()
    else -> value.toString()
}

/** 字符串拼接/数组 join 用的文本形态（§3.2：null 拼成 ""）。 */
internal fun openUiToText(value: OpenUiValue): String = when (value) {
    is OpenUiValue.Null -> ""
    is OpenUiValue.Str -> value.v
    is OpenUiValue.Num -> openUiNumToString(value.v)
    is OpenUiValue.Bool -> if (value.v) "true" else "false"
    is OpenUiValue.Arr -> value.v.joinToString(",") { element -> openUiToText(element) }
    else -> "[object Object]"
}

/** §3.1 真值（JS ToBoolean）：空串/0/false/null 为假，其余（含空数组空对象组件实例）为真。 */
internal fun openUiTruthy(value: OpenUiValue): Boolean = when (value) {
    is OpenUiValue.Null -> false
    is OpenUiValue.Bool -> value.v
    is OpenUiValue.Num -> value.v != 0.0 && !value.v.isNaN()
    is OpenUiValue.Str -> value.v.isNotEmpty()
    else -> true
}

/** §3.2 宽松相等（ECMA IsLooselyEqual 复现）：`5 == "5"` 为 true；null 只等于 null。 */
internal fun openUiLooseEquals(a: OpenUiValue, b: OpenUiValue): Boolean {
    if (a is OpenUiValue.Null && b is OpenUiValue.Null) return true
    if (a is OpenUiValue.Null || b is OpenUiValue.Null) return false
    if (a is OpenUiValue.Bool) return openUiLooseEquals(OpenUiValue.Num(openUiToNumber(a)), b)
    if (b is OpenUiValue.Bool) return openUiLooseEquals(a, OpenUiValue.Num(openUiToNumber(b)))
    if (a is OpenUiValue.Num && b is OpenUiValue.Num) {
        return !a.v.isNaN() && !b.v.isNaN() && a.v == b.v
    }
    if (a is OpenUiValue.Str && b is OpenUiValue.Str) return a.v == b.v
    if (a is OpenUiValue.Num && b is OpenUiValue.Str) {
        val parsed = openUiParseNumber(b.v) ?: return false
        return !a.v.isNaN() && a.v == parsed
    }
    if (a is OpenUiValue.Str && b is OpenUiValue.Num) return openUiLooseEquals(b, a)
    // 复杂类型 vs 原始类型：ToPrimitive 后重试（数组 join(",")，对象/组件 = "[object Object]"）
    val aPrimitive = openUiToPrimitive(a)
    if (aPrimitive != null) return openUiLooseEquals(aPrimitive, b)
    val bPrimitive = openUiToPrimitive(b)
    if (bPrimitive != null) return openUiLooseEquals(a, bPrimitive)
    // 复杂 vs 复杂：引用相等（ECMA 语义；每次求值重建实例，结构相等无意义）
    return a === b
}

private fun openUiToPrimitive(value: OpenUiValue): OpenUiValue.Str? = when (value) {
    is OpenUiValue.Arr -> OpenUiValue.Str(value.v.joinToString(",") { element -> openUiToText(element) })
    is OpenUiValue.Obj, is OpenUiValue.ComponentVal -> OpenUiValue.Str("[object Object]")
    else -> null
}

/** @Filter/@Sort 的字段路径解析（§3.4：接受点路径 "customer.name"）。 */
internal fun openUiResolvePath(value: OpenUiValue, path: String): OpenUiValue {
    if (path.isEmpty()) return value
    var current = value
    for (part in path.split(".")) {
        current = when (val node = current) {
            is OpenUiValue.Obj -> node.v[part] ?: OpenUiValue.Null
            is OpenUiValue.Arr ->
                part.toIntOrNull()?.let { index -> node.v.getOrNull(index) } ?: OpenUiValue.Null
            else -> return OpenUiValue.Null
        }
    }
    return current
}

/**
 * OpenUI Lang 1.0 求值器（§3）+ 组件校验恢复（§8.2/§8.4）。
 *
 * 返回 `OpenUiValue?`：Kotlin null = dropped 哨兵（数组剔除/组件丢弃/未解析引用/
 * 成环点），与 [OpenUiValue.Null]（null 字面量）区分。哨兵经引用与三元透明传播，
 * 在标量消费点（运算符/成员访问/对象值/内置参数/组件标量 prop）收口为 Null，
 * 在数组元素与组件 children 处剔除（§2.3/§8.4）。
 *
 * 引用提升：语句任意顺序互引；环在成环点按未解析（§2.3，栈检测）。同一语句被两处
 * 引用分别求值（树复制，state store 共享，Query 结果经 queryResults 共享）。
 */
/**
 * 可绑定 prop 类型闭集（M2：BINDABLE_STRING/BOOLEAN/NUMBER）——字面量或 `$binding` 皆收，
 * `$var` 原文收为 [OpenUiValue.Binding]，渲染期从 state 快照解析。
 */
private val BINDABLE_PROP_TYPES = setOf(
    OpenUiPropType.BINDABLE_STRING,
    OpenUiPropType.BINDABLE_BOOLEAN,
    OpenUiPropType.BINDABLE_NUMBER,
)

internal class OpenUiRuntime(
    private val statements: Map<String, OpenUiStatement>,
    val store: MutableMap<String, OpenUiValue>,
    private val queryResults: Map<String, OpenUiValue>,
) {
    val errors = mutableListOf<OpenUiError>()
    val unresolved = linkedSetOf<String>()

    /** 求值命名语句；未解析/成环/被丢弃 → null（dropped 哨兵）。 */
    fun evalStatement(name: String): OpenUiValue? = resolveStatement(name, emptySet())

    private fun resolveStatement(name: String, stack: Set<String>): OpenUiValue? {
        val statement = statements[name]
        if (statement == null || name in stack) {
            unresolved += name
            return null
        }
        return when (statement.kind) {
            OpenUiStatementKind.STATE -> store[name] ?: OpenUiValue.Null
            OpenUiStatementKind.QUERY -> queryResults[name] ?: OpenUiValue.Null
            // M1 不执行 Mutation（spec §4.5）：引用解析为 idle 形态
            OpenUiStatementKind.MUTATION -> OpenUiValue.Obj(
                mapOf(
                    "status" to OpenUiValue.Str("idle"),
                    "data" to OpenUiValue.Null,
                    "error" to OpenUiValue.Null,
                ),
            )
            OpenUiStatementKind.VALUE ->
                evalExpr(statement.expr, stack + name, statement.name, valuePosition = false)
        }
    }

    fun evalExpr(
        expr: OpenUiExpr,
        stack: Set<String>,
        statementId: String?,
        valuePosition: Boolean,
    ): OpenUiValue? = when (expr) {
        is OpenUiExpr.Lit -> expr.value
        is OpenUiExpr.StateRef -> {
            // 未声明的 $name 自动以 null 声明（§5.1）
            if (!store.containsKey(expr.name)) store[expr.name] = OpenUiValue.Null
            store[expr.name]
        }
        is OpenUiExpr.Ref -> resolveStatement(expr.name, stack)
        // M1 不做双向绑定（spec §4.5）：绑定赋值只求值不生效
        is OpenUiExpr.Assign -> OpenUiValue.Null
        is OpenUiExpr.Arr -> OpenUiValue.Arr(
            expr.items.mapNotNull { element ->
                evalExpr(element, stack, statementId, valuePosition = true)
            },
        )
        is OpenUiExpr.Obj -> OpenUiValue.Obj(
            expr.entries.associate { (key, valueExpr) ->
                key to (evalExpr(valueExpr, stack, statementId, valuePosition = false) ?: OpenUiValue.Null)
            },
        )
        is OpenUiExpr.QueryCall, is OpenUiExpr.MutationCall ->
            // Query 内联形态已在 prepare 提升为合成语句（OpenUiEngine.prepareInternal），
            // 正常不会走到这里；Mutation M1 不执行，仍守 inline-reserved
            if (valuePosition) {
                errors += OpenUiError("inline-reserved", statementId, "Query/Mutation 不能内联在值位置")
                null
            } else {
                OpenUiValue.Null
            }
        is OpenUiExpr.ActionCall -> evalActionWrapper(expr, stack, statementId)
        is OpenUiExpr.AtCall -> evalAtCall(expr, stack, statementId)
        is OpenUiExpr.CompCall -> evalComponent(expr, stack, statementId)
        is OpenUiExpr.Member -> evalMember(expr, stack, statementId)
        is OpenUiExpr.Index -> evalIndex(expr, stack, statementId)
        is OpenUiExpr.Unary -> evalUnary(expr, stack, statementId)
        is OpenUiExpr.Binary -> evalBinary(expr, stack, statementId)
        is OpenUiExpr.Ternary -> {
            val cond = evalExpr(expr.cond, stack, statementId, valuePosition = false) ?: OpenUiValue.Null
            if (openUiTruthy(cond)) {
                evalExpr(expr.then, stack, statementId, valuePosition)
            } else {
                evalExpr(expr.otherwise, stack, statementId, valuePosition)
            }
        }
    }

    // ── 运算符（§3.2） ──────────────────────────────────────────────

    private fun operand(expr: OpenUiExpr, stack: Set<String>, statementId: String?): OpenUiValue =
        evalExpr(expr, stack, statementId, valuePosition = false) ?: OpenUiValue.Null

    private fun evalUnary(expr: OpenUiExpr.Unary, stack: Set<String>, statementId: String?): OpenUiValue =
        when (expr.op) {
            "!" -> OpenUiValue.Bool(!openUiTruthy(operand(expr.operand, stack, statementId)))
            else -> OpenUiValue.Num(-openUiToNumber(operand(expr.operand, stack, statementId)))
        }

    private fun evalBinary(expr: OpenUiExpr.Binary, stack: Set<String>, statementId: String?): OpenUiValue {
        // && / || 短路并返回决定侧操作数
        if (expr.op == "&&") {
            val left = operand(expr.left, stack, statementId)
            return if (openUiTruthy(left)) operand(expr.right, stack, statementId) else left
        }
        if (expr.op == "||") {
            val left = operand(expr.left, stack, statementId)
            return if (openUiTruthy(left)) left else operand(expr.right, stack, statementId)
        }
        val left = operand(expr.left, stack, statementId)
        val right = operand(expr.right, stack, statementId)
        return when (expr.op) {
            "+" ->
                if (left is OpenUiValue.Str || right is OpenUiValue.Str) {
                    OpenUiValue.Str(openUiToText(left) + openUiToText(right))
                } else {
                    OpenUiValue.Num(openUiToNumber(left) + openUiToNumber(right))
                }
            "-" -> OpenUiValue.Num(openUiToNumber(left) - openUiToNumber(right))
            "*" -> OpenUiValue.Num(openUiToNumber(left) * openUiToNumber(right))
            "/" -> {
                val divisor = openUiToNumber(right)
                OpenUiValue.Num(if (divisor == 0.0) 0.0 else openUiToNumber(left) / divisor)
            }
            "%" -> {
                val divisor = openUiToNumber(right)
                OpenUiValue.Num(if (divisor == 0.0) 0.0 else openUiToNumber(left) % divisor)
            }
            "==" -> OpenUiValue.Bool(openUiLooseEquals(left, right))
            "!=" -> OpenUiValue.Bool(!openUiLooseEquals(left, right))
            ">" -> OpenUiValue.Bool(openUiToNumber(left) > openUiToNumber(right))
            "<" -> OpenUiValue.Bool(openUiToNumber(left) < openUiToNumber(right))
            ">=" -> OpenUiValue.Bool(openUiToNumber(left) >= openUiToNumber(right))
            "<=" -> OpenUiValue.Bool(openUiToNumber(left) <= openUiToNumber(right))
            else -> OpenUiValue.Null
        }
    }

    // ── 成员访问与索引（§3.3） ───────────────────────────────────────

    private fun evalMember(expr: OpenUiExpr.Member, stack: Set<String>, statementId: String?): OpenUiValue {
        return memberAccess(operand(expr.target, stack, statementId), expr.field)
    }

    private fun memberAccess(target: OpenUiValue, field: String): OpenUiValue = when (target) {
        is OpenUiValue.Null -> OpenUiValue.Null
        is OpenUiValue.Obj -> target.v[field] ?: OpenUiValue.Null
        is OpenUiValue.Arr ->
            if (field == "length") {
                OpenUiValue.Num(target.v.size.toDouble())
            } else {
                // 数组 pluck：逐元素取字段，缺失处为 null（§3.3）
                OpenUiValue.Arr(target.v.map { element -> pluckOne(element, field) })
            }
        // 组件实例在表达式里当对象：成员访问 = null（§3.1）；标量同理
        else -> OpenUiValue.Null
    }

    private fun pluckOne(element: OpenUiValue, field: String): OpenUiValue = when (element) {
        is OpenUiValue.Obj -> element.v[field] ?: OpenUiValue.Null
        is OpenUiValue.Arr ->
            field.toIntOrNull()?.let { index -> element.v.getOrNull(index) } ?: OpenUiValue.Null
        else -> OpenUiValue.Null
    }

    private fun evalIndex(expr: OpenUiExpr.Index, stack: Set<String>, statementId: String?): OpenUiValue {
        val target = operand(expr.target, stack, statementId)
        val index = operand(expr.index, stack, statementId)
        if (target is OpenUiValue.Null || index is OpenUiValue.Null) return OpenUiValue.Null
        return when (target) {
            is OpenUiValue.Arr ->
                target.v.getOrNull(openUiToNumber(index).toInt()) ?: OpenUiValue.Null
            is OpenUiValue.Obj -> target.v[openUiToText(index)] ?: OpenUiValue.Null
            else -> OpenUiValue.Null
        }
    }

    // ── @ 调用：内置函数（§3.4）与动作（§6.3） ───────────────────────

    private fun arg(
        args: List<OpenUiExpr>,
        index: Int,
        stack: Set<String>,
        statementId: String?,
    ): OpenUiValue =
        args.getOrNull(index)?.let { expr -> evalExpr(expr, stack, statementId, valuePosition = false) }
            ?: OpenUiValue.Null

    private fun evalAtCall(expr: OpenUiExpr.AtCall, stack: Set<String>, statementId: String?): OpenUiValue? =
        when (expr.name) {
            "Count" -> {
                val array = arg(expr.args, 0, stack, statementId)
                OpenUiValue.Num(if (array is OpenUiValue.Arr) array.v.size.toDouble() else 0.0)
            }
            "First" -> {
                val array = arg(expr.args, 0, stack, statementId)
                if (array is OpenUiValue.Arr) array.v.firstOrNull() ?: OpenUiValue.Null else OpenUiValue.Null
            }
            "Last" -> {
                val array = arg(expr.args, 0, stack, statementId)
                if (array is OpenUiValue.Arr) array.v.lastOrNull() ?: OpenUiValue.Null else OpenUiValue.Null
            }
            "Sum" -> {
                val array = arg(expr.args, 0, stack, statementId)
                if (array is OpenUiValue.Arr) {
                    OpenUiValue.Num(array.v.sumOf { element -> openUiToNumber(element) })
                } else {
                    OpenUiValue.Num(0.0)
                }
            }
            "Avg" -> {
                val array = arg(expr.args, 0, stack, statementId)
                if (array is OpenUiValue.Arr && array.v.isNotEmpty()) {
                    OpenUiValue.Num(array.v.sumOf { element -> openUiToNumber(element) } / array.v.size)
                } else {
                    OpenUiValue.Num(0.0)
                }
            }
            "Min" -> minMax(expr, stack, statementId, isMin = true)
            "Max" -> minMax(expr, stack, statementId, isMin = false)
            "Filter" -> evalFilter(expr, stack, statementId)
            "Sort" -> evalSort(expr, stack, statementId)
            "Round" -> {
                val number = openUiToNumber(arg(expr.args, 0, stack, statementId))
                val decimals = openUiToNumber(arg(expr.args, 1, stack, statementId)).toInt()
                val factor = 10.0.pow(decimals)
                // JS Math.round：半数向 +∞ 进位；kotlin.math.round 是银行家舍入，不可用
                OpenUiValue.Num(floor(number * factor + 0.5) / factor)
            }
            "Abs" -> OpenUiValue.Num(abs(openUiToNumber(arg(expr.args, 0, stack, statementId))))
            "Floor" -> OpenUiValue.Num(floor(openUiToNumber(arg(expr.args, 0, stack, statementId))))
            "Ceil" -> OpenUiValue.Num(ceil(openUiToNumber(arg(expr.args, 0, stack, statementId))))
            // M1 不实施 @Each（spec §4.5）：求值为 null，不报错（它是合法内置）
            "Each" -> OpenUiValue.Null
            "Set" -> evalSetAction(expr, stack, statementId)
            "Reset" -> evalResetAction(expr)
            "Run" -> {
                val ref = expr.args.getOrNull(0) as? OpenUiExpr.Ref
                OpenUiValue.ActionVal(
                    if (ref == null) emptyList() else listOf(OpenUiAction("Run", emptyList(), target = ref.name)),
                )
            }
            "ToAssistant" -> OpenUiValue.ActionVal(
                listOf(
                    OpenUiAction(
                        "ToAssistant",
                        buildList {
                            add(arg(expr.args, 0, stack, statementId))
                            if (expr.args.size > 1) add(arg(expr.args, 1, stack, statementId))
                        },
                    ),
                ),
            )
            "OpenUrl" -> OpenUiValue.ActionVal(
                listOf(OpenUiAction("OpenUrl", listOf(arg(expr.args, 0, stack, statementId)))),
            )
            else -> {
                // M1 无 library 自定义 function/action 注册（spec §4.5）
                errors += OpenUiError("unknown-function", statementId, "未知 @调用 ${expr.name}")
                OpenUiValue.Null
            }
        }

    private fun minMax(
        expr: OpenUiExpr.AtCall,
        stack: Set<String>,
        statementId: String?,
        isMin: Boolean,
    ): OpenUiValue {
        val array = arg(expr.args, 0, stack, statementId)
        if (array !is OpenUiValue.Arr || array.v.isEmpty()) return OpenUiValue.Num(0.0)
        val numbers = array.v.map { element -> openUiToNumber(element) }
        return OpenUiValue.Num(if (isMin) numbers.min() else numbers.max())
    }

    private fun evalFilter(expr: OpenUiExpr.AtCall, stack: Set<String>, statementId: String?): OpenUiValue {
        val array = arg(expr.args, 0, stack, statementId)
        if (array !is OpenUiValue.Arr) return OpenUiValue.Arr(emptyList())
        val field = (arg(expr.args, 1, stack, statementId) as? OpenUiValue.Str)?.v ?: ""
        val op = (arg(expr.args, 2, stack, statementId) as? OpenUiValue.Str)?.v ?: "=="
        val value = arg(expr.args, 3, stack, statementId)
        return OpenUiValue.Arr(
            array.v.filter { element ->
                val candidate = if (field.isEmpty()) element else openUiResolvePath(element, field)
                when (op) {
                    "==" -> openUiLooseEquals(candidate, value)
                    "!=" -> !openUiLooseEquals(candidate, value)
                    ">" -> openUiToNumber(candidate) > openUiToNumber(value)
                    "<" -> openUiToNumber(candidate) < openUiToNumber(value)
                    ">=" -> openUiToNumber(candidate) >= openUiToNumber(value)
                    "<=" -> openUiToNumber(candidate) <= openUiToNumber(value)
                    "contains" -> openUiToText(candidate).contains(openUiToText(value))
                    else -> false // 未知 op 不匹配任何元素（§3.4）
                }
            },
        )
    }

    private fun evalSort(expr: OpenUiExpr.AtCall, stack: Set<String>, statementId: String?): OpenUiValue {
        val array = arg(expr.args, 0, stack, statementId)
        if (array !is OpenUiValue.Arr) return array // 非数组原样返回（§3.4）
        val field = (arg(expr.args, 1, stack, statementId) as? OpenUiValue.Str)?.v ?: ""
        val desc = (arg(expr.args, 2, stack, statementId) as? OpenUiValue.Str)?.v == "desc"
        // sortedWith 是稳定排序（§3.4 stable sort）
        val sorted = array.v.sortedWith { left, right ->
            val av = if (field.isEmpty()) left else openUiResolvePath(left, field)
            val bv = if (field.isEmpty()) right else openUiResolvePath(right, field)
            val aNum = sortableNumber(av)
            val bNum = sortableNumber(bv)
            val cmp = if (aNum != null && bNum != null) {
                aNum.compareTo(bNum)
            } else {
                // locale 序近似（commonMain 无 java.util.Locale，review 🟡5）：
                // 大小写不敏感主比较 + 码点序 tiebreak，对齐 spec §3.4 示例（a 排在 B 前）
                val aText = openUiToText(av)
                val bText = openUiToText(bv)
                aText.lowercase().compareTo(bText.lowercase())
                    .takeIf { it != 0 } ?: aText.compareTo(bText)
            }
            if (desc) -cmp else cmp
        }
        return OpenUiValue.Arr(sorted)
    }

    /** @Sort 数值性判定：数字，或可解析为数字的非空字符串（§3.4）。 */
    private fun sortableNumber(value: OpenUiValue): Double? = when (value) {
        is OpenUiValue.Num -> if (value.v.isNaN()) null else value.v
        is OpenUiValue.Str -> if (value.v.isEmpty()) null else openUiParseNumber(value.v)
        else -> null
    }

    // ── 动作（§6.3 M1 简化） ────────────────────────────────────────

    /** @Set($var, value)：target=变量名（含 $），args=[已求值参数]。 */
    private fun evalSetAction(expr: OpenUiExpr.AtCall, stack: Set<String>, statementId: String?): OpenUiValue {
        val target = expr.args.getOrNull(0) as? OpenUiExpr.StateRef
            ?: return OpenUiValue.ActionVal(emptyList())
        val value = arg(expr.args, 1, stack, statementId)
        return OpenUiValue.ActionVal(listOf(OpenUiAction("Set", listOf(value), target = target.name)))
    }

    /** @Reset($a, $b, ...)：args=全部变量名（Str），target=首个。 */
    private fun evalResetAction(expr: OpenUiExpr.AtCall): OpenUiValue {
        val targets = expr.args.filterIsInstance<OpenUiExpr.StateRef>().map { ref -> ref.name }
        if (targets.isEmpty()) return OpenUiValue.ActionVal(emptyList())
        return OpenUiValue.ActionVal(
            listOf(
                OpenUiAction(
                    "Reset",
                    targets.map { name -> OpenUiValue.Str(name) },
                    target = targets.first(),
                ),
            ),
        )
    }

    /** Action([...])：拍平数组元素中的 ActionVal；非数组/非动作元素丢弃。 */
    private fun evalActionWrapper(
        expr: OpenUiExpr.ActionCall,
        stack: Set<String>,
        statementId: String?,
    ): OpenUiValue {
        val inner = expr.args.getOrNull(0)
            ?.let { argExpr -> evalExpr(argExpr, stack, statementId, valuePosition = false) }
        val actions = when (inner) {
            is OpenUiValue.Arr -> inner.v.flatMap { element ->
                (element as? OpenUiValue.ActionVal)?.actions ?: emptyList()
            }
            is OpenUiValue.ActionVal -> inner.actions
            else -> emptyList()
        }
        return OpenUiValue.ActionVal(actions)
    }

    // ── 组件校验（§8.2/§8.4） ───────────────────────────────────────

    private sealed interface PropOutcome {
        data class Store(val value: OpenUiValue) : PropOutcome
        data object Omit : PropOutcome
        data object DropComponent : PropOutcome
    }

    private fun evalComponent(
        call: OpenUiExpr.CompCall,
        stack: Set<String>,
        statementId: String?,
    ): OpenUiValue? {
        val spec = OpenUiLibrary.components[call.type]
        if (spec == null) {
            errors += OpenUiError("unknown-component", statementId, "未知组件 ${call.type}")
            return null
        }
        // 命名实参归位（2026-10-24 all_years 事故：LLM 写 name=value 被位置化误配整条卡降级）：
        // Assign 节点按 prop 名绑定（后写覆盖，同语句重复名收口），位置参数按 spec 序填未占用槽位。
        val bound = HashMap<String, OpenUiExpr>()
        val positional = mutableListOf<OpenUiExpr>()
        for (arg in call.args) {
            if (arg is OpenUiExpr.Assign) {
                if (spec.props.none { prop -> prop.name == arg.target }) {
                    errors += OpenUiError("unknown-prop", statementId, "${call.type}.${arg.target} 未知参数名")
                } else {
                    bound[arg.target] = arg.value
                }
            } else {
                positional += arg
            }
        }
        if (positional.size + bound.size > spec.props.size) {
            errors += OpenUiError(
                "excess-args",
                statementId,
                "${call.type} 期望 ${spec.props.size} 个参数，实得 ${positional.size + bound.size}，多余丢弃",
            )
        }
        var cursor = 0
        for (arg in positional) {
            while (cursor < spec.props.size && bound.containsKey(spec.props[cursor].name)) cursor++
            if (cursor >= spec.props.size) break // 多余位置参数丢弃（excess-args 已记）
            bound[spec.props[cursor].name] = arg
            cursor++
        }
        val props = linkedMapOf<String, OpenUiValue>()
        for (prop in spec.props) {
            val argExpr = bound[prop.name]
            if (argExpr == null) {
                when {
                    !prop.required -> prop.default?.let { default -> props[prop.name] = default }
                    prop.default != null -> {
                        errors += OpenUiError("missing-required", statementId, "${call.type}.${prop.name}")
                        props[prop.name] = prop.default
                    }
                    else -> {
                        errors += OpenUiError("missing-required", statementId, "${call.type}.${prop.name}")
                        return null
                    }
                }
                continue
            }
            // 可绑定属性收 $var 原文为 Binding（§5.2；BINDABLE_STRING/BOOLEAN/NUMBER
            // 三类同口径：字面量或 Binding 皆收，Binding 渲染期从 state 快照解析）
            if (prop.type in BINDABLE_PROP_TYPES && argExpr is OpenUiExpr.StateRef) {
                props[prop.name] = OpenUiValue.Binding(argExpr.name)
                continue
            }
            val value = evalExpr(argExpr, stack, statementId, valuePosition = true) ?: OpenUiValue.Null
            if (value is OpenUiValue.Null) {
                when {
                    // null 跳过可选参数（§1.5），静默取默认
                    !prop.required -> prop.default?.let { default -> props[prop.name] = default }
                    prop.default != null -> {
                        errors += OpenUiError("null-required", statementId, "${call.type}.${prop.name}")
                        props[prop.name] = prop.default
                    }
                    else -> {
                        errors += OpenUiError("null-required", statementId, "${call.type}.${prop.name}")
                        return null
                    }
                }
                continue
            }
            when (val outcome = checkPropValue(call.type, prop, value, statementId)) {
                is PropOutcome.Store -> props[prop.name] = outcome.value
                PropOutcome.Omit -> Unit
                PropOutcome.DropComponent -> return null
            }
        }
        return OpenUiValue.ComponentVal(call.type, props, statementId ?: "")
    }

    private fun checkPropValue(
        component: String,
        prop: OpenUiProp,
        value: OpenUiValue,
        statementId: String?,
    ): PropOutcome {
        when (prop.type) {
            OpenUiPropType.COMPONENT_LIST -> {
                if (value !is OpenUiValue.Arr) return typeMismatch(component, prop, statementId)
                // 组件槽只收组件：数据（对象/数组/字符串/数字/布尔）是 type-mismatch 并剔除；
                // 显式 null 字面量保留（渲染为空）（§8.4）
                val items = value.v.mapNotNull { element ->
                    when (element) {
                        is OpenUiValue.ComponentVal, OpenUiValue.Null -> element
                        else -> {
                            errors += OpenUiError(
                                "type-mismatch",
                                statementId,
                                "$component.${prop.name} 只收组件",
                            )
                            null
                        }
                    }
                }
                return PropOutcome.Store(OpenUiValue.Arr(items))
            }
            OpenUiPropType.OBJECT_ARRAY -> {
                if (value !is OpenUiValue.Arr) return typeMismatch(component, prop, statementId)
                val items = value.v.mapNotNull { element ->
                    val valid = element is OpenUiValue.Obj && prop.objectFields.all { field ->
                        objectFieldOk(element.v[field.name], field)
                    }
                    if (valid) {
                        element
                    } else {
                        errors += OpenUiError(
                            "type-mismatch",
                            statementId,
                            "$component.${prop.name} 元素形态不符",
                        )
                        null
                    }
                }
                return PropOutcome.Store(OpenUiValue.Arr(items))
            }
            OpenUiPropType.PRIMITIVE_ARRAY -> {
                // M3（spec §2）：元素限 Str/Num/Bool 或一层嵌套 Arr（表格行）；任一元素
                // 不符（含两层嵌套）即整体 type-mismatch——必填丢组件不炸卡（§8.2 既有口径）
                if (value !is OpenUiValue.Arr || !value.v.all { element ->
                        primitiveArrayElementOk(element)
                    }
                ) {
                    return typeMismatch(component, prop, statementId)
                }
                return PropOutcome.Store(value)
            }
            else -> {
                val ok = when (prop.type) {
                    OpenUiPropType.STRING -> value is OpenUiValue.Str
                    OpenUiPropType.NUMBER -> value is OpenUiValue.Num
                    OpenUiPropType.BOOLEAN -> value is OpenUiValue.Bool
                    OpenUiPropType.ENUM -> value is OpenUiValue.Str && value.v in prop.enumValues
                    OpenUiPropType.BINDABLE_STRING ->
                        value is OpenUiValue.Str || value is OpenUiValue.Binding
                    OpenUiPropType.BINDABLE_BOOLEAN ->
                        value is OpenUiValue.Bool || value is OpenUiValue.Binding
                    OpenUiPropType.BINDABLE_NUMBER ->
                        value is OpenUiValue.Num || value is OpenUiValue.Binding
                    OpenUiPropType.ACTION ->
                        value is OpenUiValue.ActionVal ||
                            (value is OpenUiValue.Arr && value.v.all { e -> e is OpenUiValue.ActionVal })
                    else -> false
                }
                return if (ok) PropOutcome.Store(value) else typeMismatch(component, prop, statementId)
            }
        }
    }

    private fun objectFieldOk(value: OpenUiValue?, field: OpenUiProp): Boolean {
        if (value == null || value is OpenUiValue.Null) return !field.required
        return when (field.type) {
            OpenUiPropType.STRING -> value is OpenUiValue.Str
            OpenUiPropType.NUMBER -> value is OpenUiValue.Num
            OpenUiPropType.BOOLEAN -> value is OpenUiValue.Bool
            OpenUiPropType.ENUM -> value is OpenUiValue.Str && value.v in field.enumValues
            // Tabs.tabs.children（M2）：对象数组字段内的组件数组递归校验——
            // 元素只收组件（显式 null 字面量保留，与 COMPONENT_LIST 槽位同口径）
            OpenUiPropType.COMPONENT_LIST ->
                value is OpenUiValue.Arr &&
                    value.v.all { element ->
                        element is OpenUiValue.ComponentVal || element is OpenUiValue.Null
                    }
            // LineChart.series[].values（M3）：对象数组字段内的原始值数组递归校验，
            // 与 PRIMITIVE_ARRAY 槽位同口径（Tabs COMPONENT_LIST 递归同机制）
            OpenUiPropType.PRIMITIVE_ARRAY ->
                value is OpenUiValue.Arr && value.v.all { element -> primitiveArrayElementOk(element) }
            else -> true
        }
    }

    /** PRIMITIVE_ARRAY 元素形态：Str/Num/Bool 或一层嵌套 Arr（内层元素同限原始值；两层嵌套拒）。 */
    private fun primitiveArrayElementOk(element: OpenUiValue): Boolean = when (element) {
        is OpenUiValue.Str, is OpenUiValue.Num, is OpenUiValue.Bool -> true
        is OpenUiValue.Arr -> element.v.all { inner ->
            inner is OpenUiValue.Str || inner is OpenUiValue.Num || inner is OpenUiValue.Bool
        }
        else -> false
    }

    /** §8.2 type-mismatch 恢复：schema 默认值 → 可选剔除 → 必填丢组件。 */
    private fun typeMismatch(component: String, prop: OpenUiProp, statementId: String?): PropOutcome {
        errors += OpenUiError("type-mismatch", statementId, "$component.${prop.name} 类型不符")
        return when {
            prop.default != null -> PropOutcome.Store(prop.default)
            !prop.required -> PropOutcome.Omit
            else -> PropOutcome.DropComponent
        }
    }
}
