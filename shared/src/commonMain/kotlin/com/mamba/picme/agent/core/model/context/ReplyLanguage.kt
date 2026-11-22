package com.mamba.picme.agent.core.model.context

import com.mamba.picme.domain.model.AppLanguage

/**
 * Chat 回复语言（解析后的具体语言，非设置项）。
 *
 * 与 [AppLanguage] 分离：`AppLanguage.SYSTEM` 不是具体回复语言，必须经
 * [toReplyLanguage] 结合系统 locale 解析后使用。
 *
 * 先行落地供助手性格段选语言（2026-08-22-assistant-persona-design.md）；
 * 「回复语言跟随界面语言」规则段复用本枚举（2026-08-22-chat-reply-language-design.md）。
 */
enum class ReplyLanguage { SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ENGLISH, SPANISH, FRENCH }

/**
 * 云端 Agent（OpenUI turn-native）`X-App-Language` 头解析（2026-11-22 OpenUI 多语言批次）：
 * 端侧上报 [ReplyLanguage.name]，非法/缺失一律回落简中（向后兼容旧端不带头）。
 */
fun replyLanguageFromName(name: String?): ReplyLanguage =
    ReplyLanguage.entries.firstOrNull { it.name == name } ?: ReplyLanguage.SIMPLIFIED_CHINESE

/**
 * 把 App 界面语言设置解析为具体回复语言。
 *
 * @param systemLocaleTag 系统 locale 的 BCP-47 tag（Android: `Locale.getDefault().toLanguageTag()`），
 *   仅当设置为 [AppLanguage.SYSTEM] 时参与解析。容忍 iOS 下划线分隔形式（`zh_TW`、
 *   `zh_Hant`，先归一为 BCP-47）；粤语（`yue`/`yue-*`）按中文同规则解析（iOS 粤语系统
 *   语言下 UI 回退繁中，chat 回复语言须与之一致）。
 */
fun AppLanguage.toReplyLanguage(systemLocaleTag: String): ReplyLanguage = when (this) {
    AppLanguage.ENGLISH -> ReplyLanguage.ENGLISH
    AppLanguage.CHINESE -> ReplyLanguage.SIMPLIFIED_CHINESE
    AppLanguage.TRADITIONAL_CHINESE -> ReplyLanguage.TRADITIONAL_CHINESE
    AppLanguage.SPANISH -> ReplyLanguage.SPANISH
    AppLanguage.FRENCH -> ReplyLanguage.FRENCH
    AppLanguage.SYSTEM -> resolveSystemReplyLanguage(systemLocaleTag)
}

private fun resolveSystemReplyLanguage(localeTag: String): ReplyLanguage {
    // 先归一为 BCP-47 形态：iOS Locale.identifier 用下划线分隔（如 zh_TW、zh_Hant）
    val tag = localeTag.replace('_', '-').lowercase()
    if (tag == "es" || tag.startsWith("es-")) return ReplyLanguage.SPANISH
    if (tag == "fr" || tag.startsWith("fr-")) return ReplyLanguage.FRENCH
    // yue（粤语）按中文同规则解析：iOS 粤语系统语言下 UI 回退繁中，chat 回复语言须与之一致
    val isChinese = tag == "zh" || tag.startsWith("zh-") || tag == "yue" || tag.startsWith("yue-")
    if (!isChinese) return ReplyLanguage.ENGLISH
    return if (tag.contains("-hant") || tag.contains("-tw") ||
        tag.contains("-hk") || tag.contains("-mo")
    ) {
        ReplyLanguage.TRADITIONAL_CHINESE
    } else {
        ReplyLanguage.SIMPLIFIED_CHINESE
    }
}

/**
 * 追加到 chat system prompt 最末尾的语言规则段（RemoteChatEngine.buildPromptSuffix 拼装）。
 * 规则文本本身用目标语言书写（自我强化），并显式对抗全中文 base prompt 与中文工具输出的引力。
 * 五语文本集中此处，双端共用，防漂移。
 */
internal fun replyLanguageRuleSegment(language: ReplyLanguage): String = when (language) {
    ReplyLanguage.ENGLISH ->
        "The app's UI language is English. Always reply to the user in English, regardless of the language of this prompt, tool descriptions, or tool outputs. Tool results may be in Chinese — summarize and present them in English."
    ReplyLanguage.SIMPLIFIED_CHINESE ->
        "App 界面语言为简体中文。请始终用简体中文回复用户，无论本提示词、工具描述或工具返回内容使用何种语言。工具返回内容可能是英文或其他语言——请用简体中文总结转述。"
    ReplyLanguage.TRADITIONAL_CHINESE ->
        "App 介面語言為繁體中文。請始終用繁體中文回覆使用者，無論本提示詞、工具描述或工具回傳內容使用何種語言。工具回傳內容可能是英文或其他語言——請用繁體中文總結轉述。"
    ReplyLanguage.SPANISH ->
        "El idioma de la interfaz de la app es español. Responde siempre al usuario en español, independientemente del idioma de este prompt, de las descripciones de las herramientas o de sus salidas. Los resultados de las herramientas pueden estar en chino o en inglés — resúmelos y preséntalos en español."
    ReplyLanguage.FRENCH ->
        "La langue de l'interface de l'app est le français. Réponds toujours à l'utilisateur en français, quelle que soit la langue de ce prompt, des descriptions des outils ou de leurs sorties. Les résultats des outils peuvent être en chinois ou en anglais — résume-les et présente-les en français."
}

/**
 * 追加到云端 Agent（OpenUI turn-native）system prompt 最末尾的语言规则段
 *（server `AgentRoutes` 按 `X-App-Language` 头选段，2026-11-22 OpenUI 多语言批次）。
 *
 * 与 chat 版 [replyLanguageRuleSegment] 的差异：云端回复本体是 OpenUI 程序，规则必须显式
 * 覆盖程序内一切用户可见文案（Markdown 正文/标题/标签/按钮/Callout/summary 行），同时
 * 钉死 DSL 词汇（组件名/枚举值/@动作名）与 handle id 不翻译。规则文本本身用目标语言
 * 书写（自我强化），对抗全中文 DSL 规则与黄金示例的引力。五语文本集中此处，端云同源防漂移。
 */
fun openUiReplyLanguageRuleSegment(language: ReplyLanguage): String = when (language) {
    ReplyLanguage.ENGLISH ->
        "The app's UI language is English. All user-facing text in the program (Markdown body, titles, labels, buttons, Callout text, the // summary line) must always be written in English, regardless of the language of this prompt, the golden examples, or tool outputs. Tool outputs may be in Chinese — translate and summarize them in English. Keep DSL vocabulary (component names, enum values, role/tone values, @action names) and handle ids unchanged."
    ReplyLanguage.SIMPLIFIED_CHINESE ->
        "App 界面语言为简体中文。程序中的一切用户可见文案（Markdown 正文、标题、标签、按钮、Callout 文本、// summary 摘要行）必须始终用简体中文书写，无论本提示词、黄金示例或工具返回内容使用何种语言。工具返回内容可能是英文或其他语言——请翻译转述。DSL 词汇（组件名、枚举值、role/tone 取值、@动作名）与 handle id 保持不变。"
    ReplyLanguage.TRADITIONAL_CHINESE ->
        "App 介面語言為繁體中文。程式中的一切使用者可見文案（Markdown 正文、標題、標籤、按鈕、Callout 文字、// summary 摘要行）必須始終用繁體中文書寫，無論本提示詞、黃金範例或工具回傳內容使用何種語言。工具回傳內容可能是英文或其他語言——請翻譯轉述。DSL 詞彙（元件名、列舉值、role/tone 取值、@動作名）與 handle id 保持不變。"
    ReplyLanguage.SPANISH ->
        "El idioma de la interfaz de la app es español. Todo el texto visible para el usuario en el programa (cuerpo Markdown, títulos, etiquetas, botones, texto de Callout, la línea // summary) debe escribirse siempre en español, independientemente del idioma de este prompt, de los ejemplos o de las salidas de las herramientas. Las salidas de las herramientas pueden estar en chino — tradúcelas y resúmelas en español. Mantén sin cambios el vocabulario del DSL (nombres de componentes, valores de enumeración, valores de role/tone, nombres de @acciones) y los handle id."
    ReplyLanguage.FRENCH ->
        "La langue de l'interface de l'app est le français. Tout le texte visible par l'utilisateur dans le programme (corps Markdown, titres, étiquettes, boutons, texte des Callout, la ligne // summary) doit toujours être rédigé en français, quelle que soit la langue de ce prompt, des exemples ou des sorties des outils. Les sorties des outils peuvent être en chinois — traduis-les et résume-les en français. Conserve inchangé le vocabulaire du DSL (noms de composants, valeurs d'énumération, valeurs de role/tone, noms d'@actions) et les handle id."
}

/**
 * `ensureValidProgram` 终结兜底的五语文案（2026-11-22 OpenUI 多语言批次）：
 * 非法输出 Markdown 包装时的 summary 标签 / 包装复验仍失败时硬降级错误卡的 summary 与正文。
 */
fun openUiWrappedSummaryLabel(language: ReplyLanguage): String = when (language) {
    ReplyLanguage.ENGLISH -> "Text reply"
    ReplyLanguage.SIMPLIFIED_CHINESE -> "文本回复"
    ReplyLanguage.TRADITIONAL_CHINESE -> "文字回覆"
    ReplyLanguage.SPANISH -> "Respuesta de texto"
    ReplyLanguage.FRENCH -> "Réponse textuelle"
}

/** 硬降级错误卡的 summary 标签（`// summary: <label>`）。 */
fun openUiHardFallbackSummaryLabel(language: ReplyLanguage): String = when (language) {
    ReplyLanguage.ENGLISH -> "Error"
    ReplyLanguage.SIMPLIFIED_CHINESE -> "错误"
    ReplyLanguage.TRADITIONAL_CHINESE -> "錯誤"
    ReplyLanguage.SPANISH -> "Error"
    ReplyLanguage.FRENCH -> "Erreur"
}

/** 硬降级错误卡的用户可见正文。 */
fun openUiHardFallbackMessage(language: ReplyLanguage): String = when (language) {
    ReplyLanguage.ENGLISH -> "Failed to generate the reply, please try again"
    ReplyLanguage.SIMPLIFIED_CHINESE -> "回复生成失败，请重试"
    ReplyLanguage.TRADITIONAL_CHINESE -> "回覆產生失敗，請重試"
    ReplyLanguage.SPANISH -> "No se pudo generar la respuesta, inténtalo de nuevo"
    ReplyLanguage.FRENCH -> "Échec de la génération de la réponse, veuillez réessayer"
}
