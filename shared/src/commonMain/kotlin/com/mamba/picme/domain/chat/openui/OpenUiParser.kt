package com.mamba.picme.domain.chat.openui

/** 语句种类（OpenUI Lang 1.0 §2.1，按序分类）。 */
internal enum class OpenUiStatementKind { QUERY, MUTATION, STATE, VALUE }

/** 一条语句：`名 = 表达式`。[name] 对 state 声明含 `$` 前缀。 */
internal class OpenUiStatement(
    val name: String,
    val kind: OpenUiStatementKind,
    val expr: OpenUiExpr,
)

/** 解析产物。statements 保留程序序与重复名（后者由引擎按「后写覆盖」收口）。 */
internal class OpenUiParseResult(
    val statements: List<OpenUiStatement>,
    /** 预处理后的文本非空（区分 parse-failed 与空输入的 no-root）。 */
    val sourceHadText: Boolean,
    /** 解析器自身异常（§8.2 parse-exception，致命；客户端必须捕获）。 */
    val parseException: Boolean = false,
)

/** OpenUI Lang 1.0 表达式 AST（§1.5/§1.6）。 */
internal sealed interface OpenUiExpr {
    /** 字面量：Str/Num/Bool/Null。 */
    data class Lit(val value: OpenUiValue) : OpenUiExpr

    /** 裸标识符引用（无论大小写；只有调用头位置的大写名才是组件）。 */
    data class Ref(val name: String) : OpenUiExpr

    /** `$name` 状态引用（name 含 `$` 前缀）。 */
    data class StateRef(val name: String) : OpenUiExpr

    /** 表达式内的 `$name = expr` 绑定赋值（§1.5，M1 不求值，仅保证可解析）。 */
    data class Assign(val target: String, val value: OpenUiExpr) : OpenUiExpr

    data class Arr(val items: List<OpenUiExpr>) : OpenUiExpr
    data class Obj(val entries: List<Pair<String, OpenUiExpr>>) : OpenUiExpr

    /** 组件调用（大写调用头，且不是保留名/无 @ 的内置名）。 */
    data class CompCall(val type: String, val args: List<OpenUiExpr>) : OpenUiExpr

    /** `@name(...)` 调用（内置函数/动作/未知——未知在求值期报 unknown-function）。 */
    data class AtCall(val name: String, val args: List<OpenUiExpr>) : OpenUiExpr

    /** 保留调用形态 Query(...)（仅合法作为整条右值）。 */
    data class QueryCall(val args: List<OpenUiExpr>) : OpenUiExpr

    /** 保留调用形态 Mutation(...)（M1 解析不执行）。 */
    data class MutationCall(val args: List<OpenUiExpr>) : OpenUiExpr

    /** Action([...]) 包装（大小写均可，带不带 @ 均可）。 */
    data class ActionCall(val args: List<OpenUiExpr>) : OpenUiExpr

    data class Member(val target: OpenUiExpr, val field: String) : OpenUiExpr
    data class Index(val target: OpenUiExpr, val index: OpenUiExpr) : OpenUiExpr
    data class Unary(val op: String, val operand: OpenUiExpr) : OpenUiExpr
    data class Binary(val op: String, val left: OpenUiExpr, val right: OpenUiExpr) : OpenUiExpr
    data class Ternary(val cond: OpenUiExpr, val then: OpenUiExpr, val otherwise: OpenUiExpr) : OpenUiExpr
}

/**
 * OpenUI Lang 1.0 批量解析器：§1.2 预处理（fence 抽取 → 注释剥离 → trim）→
 * 隐式闭合（§4 流末规则）→ 词法（§1.3）→ 语句切分（§1.4）→ 表达式递归下降（§1.6）。
 *
 * 语句边界与参考实现逐 token 对齐：深度 0 的换行结束语句，除非在括号/未闭合字符串内，
 * 或三元开放中，或下一行首个非空白 token 是 `?`。
 */
internal object OpenUiParser {

    fun parse(program: String): OpenUiParseResult {
        val prepared = prepareSource(program)
        if (prepared.isBlank()) {
            return OpenUiParseResult(emptyList(), sourceHadText = program.isNotBlank())
        }
        return try {
            val tokens = OpenUiLexer.tokenize(autoClose(prepared))
            val statements = splitStatements(tokens).mapNotNull { raw ->
                if (raw.tokens.isEmpty()) return@mapNotNull null
                val expr = ExpressionParser(raw.tokens).parse()
                OpenUiStatement(raw.name, classify(raw.isStateName, expr), expr)
            }
            OpenUiParseResult(statements, sourceHadText = true)
        } catch (e: Exception) {
            OpenUiParseResult(emptyList(), sourceHadText = true, parseException = true)
        }
    }

    /** 行尾中缀/逗号算符 → 换行延续（splitStatements 近似边界，非完整算符状态机）。 */
    private val infixContinuationKinds = setOf(
        OpenUiTokenKind.COMMA, OpenUiTokenKind.COLON, OpenUiTokenKind.QUESTION,
        OpenUiTokenKind.EQUALS, OpenUiTokenKind.EQEQ, OpenUiTokenKind.NOTEQ,
        OpenUiTokenKind.GREATER, OpenUiTokenKind.GREATEREQ,
        OpenUiTokenKind.LESS, OpenUiTokenKind.LESSEQ,
        OpenUiTokenKind.AND, OpenUiTokenKind.OR,
        OpenUiTokenKind.PLUS, OpenUiTokenKind.MINUS,
        OpenUiTokenKind.STAR, OpenUiTokenKind.SLASH, OpenUiTokenKind.PERCENT,
    )

    private fun classify(isStateName: Boolean, expr: OpenUiExpr): OpenUiStatementKind = when {
        expr is OpenUiExpr.QueryCall -> OpenUiStatementKind.QUERY
        expr is OpenUiExpr.MutationCall -> OpenUiStatementKind.MUTATION
        isStateName -> OpenUiStatementKind.STATE
        else -> OpenUiStatementKind.VALUE
    }

    // ── §1.2 预处理 ─────────────────────────────────────────────────

    /** fence 抽取 → 注释剥离 → trim（顺序规范固定）。 */
    internal fun prepareSource(text: String): String = stripComments(extractFences(text)).trim()

    /**
     * fence 抽取（§1.2）：fence 仅由行首三个反引号开启/关闭（可带语言标记）。
     * 有 fence 时拼接所有 fence 内容，外部散文忽略；fence 内双引号字符串中的
     * 反引号行不关闭 fence（单引号字符串不屏蔽）；未闭合 fence 延伸至文本尾。
     */
    private fun extractFences(text: String): String {
        val lines = text.split('\n')
        val content = StringBuilder()
        var inFence = false
        var found = false
        var inDoubleString = false
        var escaped = false
        for (line in lines) {
            if (!inFence) {
                if (line.startsWith("```")) {
                    inFence = true
                    found = true
                }
                continue
            }
            if (!inDoubleString && line.startsWith("```")) {
                inFence = false
                continue
            }
            for (element in line) {
                when {
                    escaped -> escaped = false
                    element == '\\' && inDoubleString -> escaped = true
                    element == '"' -> inDoubleString = !inDoubleString
                }
            }
            content.append(line).append('\n')
        }
        return if (found) content.toString() else text
    }

    /** 注释剥离（§1.2）：`//` 与 `#` 到行尾；字符串内（含多行字符串）不算注释。 */
    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        var quote: Char? = null
        var escaped = false
        while (i < n) {
            val c = text[i]
            if (quote != null) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> quote = null
                }
                i++
                continue
            }
            when {
                c == '"' || c == '\'' -> {
                    quote = c
                    out.append(c)
                    i++
                }
                c == '#' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 隐式闭合（§4 规则 2/7，批量解析 = 流结束）：闭合未终结字符串
     * （末尾在转义中途则先补 `\`），再按逆序闭合未匹配括号；不匹配的闭括号不动栈。
     */
    internal fun autoClose(input: String): String {
        val stack = mutableListOf<Char>()
        var inStr: Char? = null
        var escaped = false
        for (c in input) {
            when {
                escaped -> escaped = false
                c == '\\' && inStr != null -> escaped = true
                inStr != null -> if (c == inStr) inStr = null
                c == '"' || c == '\'' -> inStr = c
                c == '(' || c == '[' || c == '{' -> stack += c
                c == ')' && stack.lastOrNull() == '(' -> stack.removeAt(stack.lastIndex)
                c == ']' && stack.lastOrNull() == '[' -> stack.removeAt(stack.lastIndex)
                c == '}' && stack.lastOrNull() == '{' -> stack.removeAt(stack.lastIndex)
            }
        }
        if (inStr == null && stack.isEmpty()) return input
        val out = StringBuilder(input)
        if (inStr != null) {
            if (escaped) out.append('\\')
            out.append(inStr)
        }
        for (j in stack.indices.reversed()) {
            out.append(
                when (stack[j]) {
                    '(' -> ')'
                    '[' -> ']'
                    else -> '}'
                },
            )
        }
        return out.toString()
    }

    // ── §1.4 语句切分 ───────────────────────────────────────────────

    private class RawStatement(val name: String, val isStateName: Boolean, val tokens: List<OpenUiToken>)

    /**
     * 语句 = `名 = 表达式`，深度 0 的换行结束；括号内换行忽略；三元开放中
     * （`?` 起至其 `:` 后首个完整操作数）换行延续；下一行首 token 是 `?` 也延续。
     * 无此形态的行静默跳过。
     */
    private fun splitStatements(tokens: List<OpenUiToken>): List<RawStatement> {
        val statements = mutableListOf<RawStatement>()
        var pos = 0
        while (pos < tokens.size) {
            while (pos < tokens.size && tokens[pos].kind == OpenUiTokenKind.NEWLINE) pos++
            if (pos >= tokens.size || tokens[pos].kind == OpenUiTokenKind.EOF) break

            val head = tokens[pos]
            val isName = head.kind == OpenUiTokenKind.IDENT ||
                head.kind == OpenUiTokenKind.TYPE ||
                head.kind == OpenUiTokenKind.STATEVAR
            if (!isName) {
                while (pos < tokens.size &&
                    tokens[pos].kind != OpenUiTokenKind.NEWLINE &&
                    tokens[pos].kind != OpenUiTokenKind.EOF
                ) {
                    pos++
                }
                continue
            }
            val name = head.text
            val isStateName = head.kind == OpenUiTokenKind.STATEVAR
            pos++
            if (pos >= tokens.size || tokens[pos].kind != OpenUiTokenKind.EQUALS) {
                while (pos < tokens.size &&
                    tokens[pos].kind != OpenUiTokenKind.NEWLINE &&
                    tokens[pos].kind != OpenUiTokenKind.EOF
                ) {
                    pos++
                }
                continue
            }
            pos++

            val exprTokens = mutableListOf<OpenUiToken>()
            var depth = 0
            var ternaryDepth = 0
            // depth-0 三元冒号已消耗但 else 操作数未至（§1.4 规则 3：`:` 后换行延续）；
            // 任一非换行 token 到来即清零。缺此标志时 `a = c ? "x" :` 换行即断句、else 静默丢
            var elsePending = false
            while (pos < tokens.size && tokens[pos].kind != OpenUiTokenKind.EOF) {
                val kind = tokens[pos].kind
                // 近似边界（review 🟡1）：除三元 `?`/`:` 前瞻与 elsePending 外，再以「行尾是
                // 中缀/逗号算符」做换行延续（如 `b +\n c`）；非完整算符状态机——行尾是完整
                // 操作数但表达式实际未完的其它形态（如调用缺右括号已被 depth 覆盖除外）不保证
                val trailingOperator = exprTokens.lastOrNull()?.kind in infixContinuationKinds
                if (kind == OpenUiTokenKind.NEWLINE && depth <= 0 && ternaryDepth <= 0 &&
                    !elsePending && !trailingOperator
                ) {
                    // 换行前前瞻：下一有意义 token 是 `?` 则三元跨行延续
                    var peek = pos + 1
                    while (peek < tokens.size && tokens[peek].kind == OpenUiTokenKind.NEWLINE) peek++
                    val nextKind = if (peek < tokens.size) tokens[peek].kind else OpenUiTokenKind.EOF
                    if (nextKind == OpenUiTokenKind.QUESTION ||
                        (nextKind == OpenUiTokenKind.COLON && ternaryDepth > 0)
                    ) {
                        pos++
                        continue
                    }
                    break
                }
                if (kind == OpenUiTokenKind.NEWLINE) {
                    pos++
                    continue
                }
                if (kind == OpenUiTokenKind.COLON && depth == 0 && ternaryDepth > 0) {
                    ternaryDepth--
                    elsePending = true
                } else {
                    elsePending = false
                    when (kind) {
                        OpenUiTokenKind.LPAREN, OpenUiTokenKind.LBRACK, OpenUiTokenKind.LBRACE -> depth++
                        OpenUiTokenKind.RPAREN, OpenUiTokenKind.RBRACK, OpenUiTokenKind.RBRACE ->
                            if (depth > 0) depth--
                        OpenUiTokenKind.QUESTION -> if (depth == 0) ternaryDepth++
                        else -> Unit
                    }
                }
                exprTokens += tokens[pos]
                pos++
            }
            if (exprTokens.isNotEmpty()) statements += RawStatement(name, isStateName, exprTokens)
        }
        return statements
    }

    // ── §1.6 表达式递归下降（Pratt） ────────────────────────────────

    /** 无 @ 时需要特别对待的保留/内置名（对齐参考实现 BUILTIN_CALLS + Query/Mutation）。 */
    private val builtinNames = setOf(
        "Count", "First", "Last", "Sum", "Avg", "Min", "Max",
        "Filter", "Sort", "Round", "Abs", "Floor", "Ceil", "Each",
        "Set", "Reset", "Run", "ToAssistant", "OpenUrl", "Action",
    )

    private class ExpressionParser(private val tokens: List<OpenUiToken>) {
        private var pos = 0

        fun parse(): OpenUiExpr = parseExpr(0)

        private fun cur(): OpenUiToken = tokens.getOrNull(pos) ?: EOF_TOKEN

        private fun peekNext(): OpenUiToken = tokens.getOrNull(pos + 1) ?: EOF_TOKEN

        private fun adv(): OpenUiToken {
            val token = cur()
            if (pos < tokens.size) pos++
            return token
        }

        private fun eat(kind: OpenUiTokenKind) {
            if (cur().kind == kind) pos++
        }

        private fun infixPrecedence(token: OpenUiToken): Int = when (token.kind) {
            OpenUiTokenKind.QUESTION -> 1
            OpenUiTokenKind.OR -> 2
            OpenUiTokenKind.AND -> 3
            OpenUiTokenKind.EQEQ, OpenUiTokenKind.NOTEQ -> 4
            OpenUiTokenKind.GREATER, OpenUiTokenKind.LESS,
            OpenUiTokenKind.GREATEREQ, OpenUiTokenKind.LESSEQ,
            -> 5
            OpenUiTokenKind.PLUS, OpenUiTokenKind.MINUS -> 6
            OpenUiTokenKind.STAR, OpenUiTokenKind.SLASH, OpenUiTokenKind.PERCENT -> 7
            OpenUiTokenKind.DOT, OpenUiTokenKind.LBRACK -> 9
            else -> 0
        }

        private fun parseExpr(minPrec: Int): OpenUiExpr {
            var left = parsePrefix()
            while (infixPrecedence(cur()) > minPrec) {
                left = parseInfix(left)
            }
            return left
        }

        private fun parsePrefix(): OpenUiExpr {
            val token = cur()
            return when (token.kind) {
                OpenUiTokenKind.STR -> {
                    adv()
                    OpenUiExpr.Lit(OpenUiValue.Str(token.text))
                }
                OpenUiTokenKind.NUM -> {
                    adv()
                    OpenUiExpr.Lit(OpenUiValue.Num(token.num))
                }
                OpenUiTokenKind.TRUE -> {
                    adv()
                    OpenUiExpr.Lit(OpenUiValue.Bool(true))
                }
                OpenUiTokenKind.FALSE -> {
                    adv()
                    OpenUiExpr.Lit(OpenUiValue.Bool(false))
                }
                OpenUiTokenKind.NULL -> {
                    adv()
                    OpenUiExpr.Lit(OpenUiValue.Null)
                }
                OpenUiTokenKind.LBRACK -> parseArray()
                OpenUiTokenKind.LBRACE -> parseObject()
                OpenUiTokenKind.STATEVAR -> {
                    adv()
                    if (cur().kind == OpenUiTokenKind.EQUALS) {
                        adv()
                        OpenUiExpr.Assign(token.text, parseExpr(0))
                    } else {
                        OpenUiExpr.StateRef(token.text)
                    }
                }
                OpenUiTokenKind.TYPE -> {
                    // 内置/保留名须带 @（仅 Action 豁免）；Count(x) 解析为引用 Count，参数丢失
                    if (peekNext().kind == OpenUiTokenKind.LPAREN &&
                        (!builtinNames.contains(token.text) || token.text == "Action")
                    ) {
                        parseCall(token.text)
                    } else {
                        adv()
                        OpenUiExpr.Ref(token.text)
                    }
                }
                OpenUiTokenKind.BUILTINCALL -> {
                    if (peekNext().kind == OpenUiTokenKind.LPAREN) {
                        parseCall("@" + token.text)
                    } else {
                        adv()
                        OpenUiExpr.Ref(token.text)
                    }
                }
                OpenUiTokenKind.IDENT -> {
                    adv()
                    OpenUiExpr.Ref(token.text)
                }
                OpenUiTokenKind.NOT -> {
                    adv()
                    OpenUiExpr.Unary("!", parseExpr(8))
                }
                OpenUiTokenKind.MINUS -> {
                    adv()
                    OpenUiExpr.Unary("-", parseExpr(8))
                }
                OpenUiTokenKind.LPAREN -> {
                    adv()
                    val inner = parseExpr(0)
                    eat(OpenUiTokenKind.RPAREN)
                    inner
                }
                else -> {
                    adv()
                    OpenUiExpr.Lit(OpenUiValue.Null)
                }
            }
        }

        private fun parseInfix(left: OpenUiExpr): OpenUiExpr {
            val token = cur()
            return when (token.kind) {
                OpenUiTokenKind.PLUS -> {
                    adv()
                    OpenUiExpr.Binary("+", left, parseExpr(6))
                }
                OpenUiTokenKind.MINUS -> {
                    adv()
                    OpenUiExpr.Binary("-", left, parseExpr(6))
                }
                OpenUiTokenKind.STAR -> {
                    adv()
                    OpenUiExpr.Binary("*", left, parseExpr(7))
                }
                OpenUiTokenKind.SLASH -> {
                    adv()
                    OpenUiExpr.Binary("/", left, parseExpr(7))
                }
                OpenUiTokenKind.PERCENT -> {
                    adv()
                    OpenUiExpr.Binary("%", left, parseExpr(7))
                }
                OpenUiTokenKind.EQEQ -> {
                    adv()
                    OpenUiExpr.Binary("==", left, parseExpr(4))
                }
                OpenUiTokenKind.NOTEQ -> {
                    adv()
                    OpenUiExpr.Binary("!=", left, parseExpr(4))
                }
                OpenUiTokenKind.GREATER -> {
                    adv()
                    OpenUiExpr.Binary(">", left, parseExpr(5))
                }
                OpenUiTokenKind.LESS -> {
                    adv()
                    OpenUiExpr.Binary("<", left, parseExpr(5))
                }
                OpenUiTokenKind.GREATEREQ -> {
                    adv()
                    OpenUiExpr.Binary(">=", left, parseExpr(5))
                }
                OpenUiTokenKind.LESSEQ -> {
                    adv()
                    OpenUiExpr.Binary("<=", left, parseExpr(5))
                }
                OpenUiTokenKind.AND -> {
                    adv()
                    OpenUiExpr.Binary("&&", left, parseExpr(3))
                }
                OpenUiTokenKind.OR -> {
                    adv()
                    OpenUiExpr.Binary("||", left, parseExpr(2))
                }
                OpenUiTokenKind.QUESTION -> {
                    adv()
                    val thenBranch = parseExpr(0)
                    eat(OpenUiTokenKind.COLON)
                    OpenUiExpr.Ternary(left, thenBranch, parseExpr(0))
                }
                OpenUiTokenKind.DOT -> {
                    adv()
                    OpenUiExpr.Member(left, readFieldName())
                }
                OpenUiTokenKind.LBRACK -> {
                    adv()
                    val index = parseExpr(0)
                    eat(OpenUiTokenKind.RBRACK)
                    OpenUiExpr.Index(left, index)
                }
                else -> left
            }
        }

        /** 字段名与对象 key 同 token 集；数字 key 按 JS String(num) 格式化（`m.0.1` = 字段 "0.1"）。 */
        private fun readFieldName(): String {
            val token = cur()
            return when (token.kind) {
                OpenUiTokenKind.IDENT, OpenUiTokenKind.TYPE, OpenUiTokenKind.STR -> {
                    adv()
                    token.text
                }
                OpenUiTokenKind.NUM -> {
                    adv()
                    openUiNumToString(token.num)
                }
                OpenUiTokenKind.STATEVAR -> {
                    adv()
                    token.text.removePrefix("$")
                }
                else -> {
                    adv()
                    "?"
                }
            }
        }

        private fun parseCall(name: String): OpenUiExpr {
            adv() // 调用头名
            eat(OpenUiTokenKind.LPAREN)
            val args = mutableListOf<OpenUiExpr>()
            while (cur().kind != OpenUiTokenKind.RPAREN && cur().kind != OpenUiTokenKind.EOF) {
                args += parseExpr(0)
                if (cur().kind == OpenUiTokenKind.COMMA) adv()
            }
            eat(OpenUiTokenKind.RPAREN)
            return when {
                name.startsWith("@") -> {
                    val atName = name.removePrefix("@")
                    if (atName.equals("Action", ignoreCase = true)) {
                        OpenUiExpr.ActionCall(args)
                    } else {
                        OpenUiExpr.AtCall(atName, args)
                    }
                }
                name == "Query" -> OpenUiExpr.QueryCall(args)
                name == "Mutation" -> OpenUiExpr.MutationCall(args)
                name.equals("Action", ignoreCase = true) -> OpenUiExpr.ActionCall(args)
                else -> OpenUiExpr.CompCall(name, args)
            }
        }

        private fun parseArray(): OpenUiExpr {
            adv() // [
            val items = mutableListOf<OpenUiExpr>()
            while (cur().kind != OpenUiTokenKind.RBRACK && cur().kind != OpenUiTokenKind.EOF) {
                items += parseExpr(0)
                if (cur().kind == OpenUiTokenKind.COMMA) adv()
            }
            eat(OpenUiTokenKind.RBRACK)
            return OpenUiExpr.Arr(items)
        }

        private fun parseObject(): OpenUiExpr {
            adv() // {
            val entries = mutableListOf<Pair<String, OpenUiExpr>>()
            while (cur().kind != OpenUiTokenKind.RBRACE && cur().kind != OpenUiTokenKind.EOF) {
                val key = readFieldName()
                eat(OpenUiTokenKind.COLON)
                entries += key to parseExpr(0)
                if (cur().kind == OpenUiTokenKind.COMMA) adv()
            }
            eat(OpenUiTokenKind.RBRACE)
            return OpenUiExpr.Obj(entries)
        }

        private val EOF_TOKEN = OpenUiToken(OpenUiTokenKind.EOF)
    }
}
