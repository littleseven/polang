# M3 Chat 正文渲染改造验收记录（ADR-016 / spec §10 M3）

> **日期**：2026-09-27
> **范围**：mikepenz multiplatform-markdown-renderer 0.41.0 替换 jeziellago compose-markdown（Chat 正文 MARKDOWN 段 + AiChatScreen 浮动面板 + MediaPager 视觉结果 + 悬浮气泡四处）
> **spec**：`docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md` §7.1/§10

## 1. spike 四项硬指标结论（证据：spike 提交 `7ae4fac49` / `6bef974e8`，已随落地清理，git 历史可查）

| 指标 | 结论 | 关键数据 |
|------|------|----------|
| 流式逐 chunk 追加不崩不闪 | ✅ 过 | JVM：739 字代表文 370 个 2 字符步长前缀 + 49 行级前缀 + 9 边缘用例 parse 零异常，均值 2.35ms；真机（Redmi 24129PN74C, 51912a5c）debug Activity 6 字/40ms 循环重放多轮零 FATAL。「不闪」靠 retainState 机制 + 静态截图，无帧级测量（人工体感项） |
| 表格 | ✅ 过（带保留项） | 截图实证表头加粗/多列/长格省略号/超宽横滚；保留项：无 CJK 计宽/全屏预览 → 落地保留自研 AgentTable，库 table 不启用 |
| 代码高亮 | ✅ 过 | `-code` 模块 highlightedCodeFence（SnipMe Highlights，异步）；无折叠 → 落地用自定义 codeFence 包自研折叠/复制 |
| 白名单内联 HTML | ✅ 过（需自定义 ~40 行） | 库默认丢弃标签；markdownAnnotator 钩子实现 u/mark/sup/sub。🔴 spike 真机实证竞态坑：annotator 闭包捕获外部 content → retainState 下旧树配新 annotator 必现 StringIndexOutOfBounds，必须用 lambda 首参 content（已写入 AgentMarkdown KDoc 与单测） |

## 2. immediate 定案（M3 review 🟡1，真机实测）

官方明示 `immediate=true` 阻塞 composition 不宜全量上生产。真机（Redmi 24129PN74C）instrument 打点（临时用例，取数后已删）：

| 文档长度 | 稳态中位（ART 预热后） | 冷进程首次 |
|----------|------------------------|------------|
| 700 字 | 2.1ms | ~20ms（一次性） |
| 1500 字 | 4.1ms | |
| 3000 字 | 7.8ms | |
| 5000 字 | 12.3ms | |

**定案**：按内容长度门控 `IMMEDIATE_PARSE_MAX_CHARS = 1500`——≤1500 字同步解析（稳态 ≤~4ms，远低于 16.6ms 帧预算）保首帧无空窗；>1500 字走异步 + retainState + 库内 conflate 防抖，规避流式期每 pacing tick 一次主线程 parse 的累积卡顿。数据已写入 `AgentMarkdown.kt` KDoc。

## 3. 落地验证（feat/chat-parts-m1 → main 46a6e91fb；验证收尾 feat/chat-parts-m4）

- 编译与单测：`:androidApp:compileDebugKotlin :androidApp:testDebugUnitTest :shared:jvmTest` 全绿
- 依赖：jeziellago 全仓零残留（toml/gradle/import 三处 grep 确认）
- 真机冒烟（51912a5c）：chat 发英文消息触发流式回复，全程零 FATAL；流式中段/终态渲染正确（降档标题/粗斜体/行内代码底纹/列表/代码块折叠）
- 截图证据（仓库内基线）：`scripts/screenshot-baseline/chat/`（agent_text_streaming / agent_text_body / agent_code_collapsed 三帧 + README）
- 在树单测（M3 review 🟡2）：`InlineHtmlAnnotatorTest` 4 例（白名单样式/非白名单丢弃/未闭合泄漏+Builder 复位/变体丢弃）+ `MarkdownStreamRobustnessTest` 3 例（字符步长前缀/行级前缀/未闭合边缘）

## 4. 已知保留项

- 表格 CJK 计宽/全屏预览保留自研 AgentTable；库 codeFence 高亮着色路径（不分段调用点）冒烟未实测（组件已在 ChatScreen 路径复用同款折叠/复制 UI）
- 流式「不闪」无帧级测量，人工体感项
- AiChatScreen 深色气泡 `Color.White`/`Color.DarkGray` 硬编码为历史欠账，已登记 spec §8 性能清单第 7 条，M4 一并收口
