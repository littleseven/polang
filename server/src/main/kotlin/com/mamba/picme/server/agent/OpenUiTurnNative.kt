package com.mamba.picme.server.agent

/**
 * OpenUI 回合原生（turn-native，spec《2026-11-01-openui-turn-native-design》）：
 * 系统提示词契约 + 终结消息兜底变换。
 *
 * [SYSTEM_PROMPT]：响应即程序契约——DSL 规则与 [RenderOpenUiToolService.DSL_RULES]
 * 同源复用（零漂移），工具语境改写为响应语境（无工具可回调、// summary 首行、
 * 渲染顺序约定 §3.4、纯文本也必须是程序）。契约后紧跟「回复形态先定位」
 * （2026-11-01 回复活感批次：对话类回复 = 裸 Markdown 程序、禁表单卡包装；数据卡
 * 首子级 Markdown 导语——真机实证模型被卡片示例带偏，一切回复皆表单，活人感尽失）。
 * 尾部附组件全量清单（引用 [RenderOpenUiValidator.ALLOWED_COMPONENTS] 生成而非手抄
 * ——清单文本与计数（size 插值）都随白名单自动同步，与 shared
 * `OpenUiLibrary.components` 同源字面量副本）——白名单违规文案「组件全量清单见系统
 * 提示词」由此兑现。
 */
object OpenUiTurnNative {

    /**
     * 对话形态黄金示例（2026-11-01 回复活感批次）：问答/闲聊类回复的正确形态是裸
     * Markdown 程序——自然语气、多段落（\n 转义写法即教学）、诚实交代能力边界
     * （查不到的就直说再给替代）。反例（真机实证）：把「明天穿什么」也包成
     * Card + 标题 + Callout + Timeline 表单卡。内嵌进 [SYSTEM_PROMPT]（测试同源钉住），
     * 自身须过结构校验 + 布局门禁。
     */
    const val GOLDEN_CHAT_PROGRAM =
        "// summary: 明天故宫穿搭建议\n" +
            "root = Markdown(\"明天降温，建议洋葱式穿法。\\n\\n**白天逛中轴线**走路多、" +
            "日晒足，薄长袖加防晒衣最舒服；早晚广场风大，再带件防风外套。\\n\\n" +
            "我查不到实时天气，出发前看一眼逐小时预报——有雨就带伞，宫里全程室外。\")"

    val SYSTEM_PROMPT: String =
        """你是 PoLang 云端 Agent。你的整个回复就是一颗 OpenUI Lang 1.0 程序——它就是
用户看到的界面：不允许程序之外的任何文字，不要包 markdown 代码围栏；
纯文本回复也必须写成程序（root = Markdown("…")）；闲聊一句话同样如此。
回复形态先定位（动笔前先选形态——形态选错，排版再对也不是好回复；语气像人聊天：
先回应人、先给结论，再展开细节；做不到的事直说，并给替代路径）：
- 对话/问答/解释/建议/闲聊（没有照片、图表、交互件可放）→ 整个回复就是
  root = Markdown("自然正文") 一条组件语句（// summary 首行照写）：像聊天一样写——
  可以分多段、加粗关键词、用列表；
  禁止包装成 Card + 标题 + Callout + Timeline 的表单卡（那是一份报告，不是一次对话）；
  黄金示例（用户问「明天去故宫穿什么合适」）：
""" + GOLDEN_CHAT_PROGRAM + """
- 数据/照片/图表/交互 → Card + 组件（版式范式见规则 8），但卡不做冷报表：Card 首子级
  先放一段 Markdown 自然导语（先说结论或先回应，如「今年拍了 138 张，八成在下半年」）
  再放组件，收尾补一句 Markdown 讲解或下一步建议——像在跟用户说话，不是只摆数据。
程序结构约定（边生成边渲染，骨架必须先立）：
1. 首行写 `// summary: <投影级一句话摘要>`（端上降级与历史折叠的数据源，
   摘要只含计数/结论，不含 handle id 与用户隐私细节）；
2. 第二行起 `root = ...` 声明独占一行行首（root 是组件树入口）；
3. 布局骨架组件（Column/Row/Card/Grid）先于数据组件书写；
4. Query 取数语句殿后（程序末尾），组件参数引用其变量名。
需要服务器时间时调用 server_time 工具；相册数据查询照旧调用搜索工具，
结果尾部的 handle 按数据禁令绑定进程序。
""" + RenderOpenUiToolService.DSL_RULES + """

可用组件全量清单（${RenderOpenUiValidator.ALLOWED_COMPONENTS.size} 个，与校验器白名单同源，逐字使用、禁臆造组件名；另有
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
