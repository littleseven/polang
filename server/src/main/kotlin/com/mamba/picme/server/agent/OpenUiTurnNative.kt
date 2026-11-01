package com.mamba.picme.server.agent

/**
 * OpenUI 回合原生（turn-native，spec《2026-11-01-openui-turn-native-design》）：
 * 系统提示词契约 + 终结消息兜底变换。
 *
 * [SYSTEM_PROMPT]：响应即程序契约——DSL 规则与 [RenderOpenUiToolService.DSL_RULES]
 * 同源复用（零漂移），工具语境改写为响应语境（无工具可回调、// summary 首行、
 * 渲染顺序约定 §3.4、纯文本也必须是程序）。
 * 尾部附组件全量清单（101 个，引用 [RenderOpenUiValidator.ALLOWED_COMPONENTS]
 * 生成而非手抄，与 shared `OpenUiLibrary.components` 同源字面量副本）——白名单
 * 违规文案「组件全量清单见系统提示词」由此兑现。
 */
object OpenUiTurnNative {

    private const val D = "$"

    val SYSTEM_PROMPT: String =
        """你是 PoLang 云端 Agent。你的整个回复就是一颗 OpenUI Lang 1.0 程序——它就是
用户看到的界面：不允许程序之外的任何文字，不要包 markdown 代码围栏；
纯文本回复也必须写成程序（root = Markdown("…")）；闲聊一句话同样如此。
程序结构约定（边生成边渲染，骨架必须先立）：
1. 首行写 `// summary: <投影级一句话摘要>`（端上降级与历史折叠的数据源，
   摘要只含计数/结论，不含 handle id 与用户隐私细节）；
2. 第二行起 `root = ...` 声明独占一行行首（root 是组件树入口）；
3. 布局骨架组件（Column/Row/Card/Grid）先于数据组件书写；
4. Query 取数语句殿后（程序末尾），组件参数引用其变量名。
需要服务器时间时调用 server_time 工具；相册数据查询照旧调用搜索工具，
结果尾部的 handle 按数据禁令绑定进程序。
""" + RenderOpenUiToolService.DSL_RULES + """

可用组件全量清单（101 个，与校验器白名单同源，逐字使用、禁臆造组件名；另有
Query 取数语句）：
""" + RenderOpenUiValidator.ALLOWED_COMPONENTS.joinToString(", ")

    /**
     * 终结消息兜底变换（AgentRunner.finalMessageTransformer 消费，spec§3.2 双保险）：
     * 合法 → normalize 后原样放行；非法（ProgramGuard 纠正用尽）→ 兜底包成
     * Markdown 程序，端上永远收到合法树。
     */
    fun ensureValidProgram(text: String): String {
        val normalized = OpenUiLayoutNormalizer.normalize(text)
        if (RenderOpenUiValidator.validate(normalized).isEmpty() &&
            OpenUiLayoutValidator.validate(normalized).isEmpty()
        ) {
            return normalized
        }
        val wrapped = "// summary: 文本回复\nroot = Markdown(\"${escapeOpenUiString(text)}\")"
        // 防御分支：兜底包装自身复验（极端超长等），仍失败则硬编码保底
        if (RenderOpenUiValidator.validateProgramToError(wrapped) != null) {
            return "// summary: 错误\nroot = Markdown(\"回复生成失败，请重试\")"
        }
        return wrapped
    }

    /** OpenUI 双引号字符串转义（JSON 转义子集，对齐 OpenUiLexer 双引规则）。 */
    internal fun escapeOpenUiString(raw: String): String = buildString(raw.length + 8) {
        for (c in raw) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
