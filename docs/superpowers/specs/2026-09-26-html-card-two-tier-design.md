# HTML 卡双形态（Inline / Fullpage）+ 任务卡 HTML 化——产品与交互设计 Spec

> **日期**: 2026-09-26
> **来源**: 用户新想法——「短卡 chat 内直接交互无手势冲突；长文 HTML（超一屏）手势冲突突出，外显为固定高预览卡，点击全屏」；随后扩展「任务卡也适合 HTML 形态呈现，点击展开细节」
> **上游**: ADR-014（chat 富内容渲染，含 2026-09-25 修订注记）、`JS_ENGINE_TECH_SPEC.md` §7.1（HtmlCard 实现 SSOT）、`2026-09-25-engineer-task-card-design.md`（任务卡，本 spec 修订其 D2/§6）
> **关键决策**（用户 2026-09-26 逐项确认）：混合分流判定 / 实时裁剪预览 / 全屏同沙箱允许竖滚；任务卡原地展开+超长进全屏 / 审批动作条原生外置 / 折叠展开全 HTML
> **设计稿**: Ardot 文件 polang-ui-spec（fileId 715061534788814）页面 `HtmlCard`（pageId `438:2`）8 帧，见 §13；过程稿 `docs/superpowers/specs/2026-09-26-html-card-two-tier-mockup.html`

---

## 1. 问题陈述

现状（ADR-014 修订注记落地态）：所有 HTML 卡一律「动态测高 + 完全撑开 + 卡内直接交互」，短内容体验良好，但两类场景失配：

1. **长文 HTML（超一屏）**：完全撑开后一张卡占据聊天流数屏，聊天浏览节奏被打断；且测高滞后窗口内 WebView 内容可竖滚，与 LazyColumn 的手势让渡兜底（`ChatHtmlWebView` 双策略）会被触发，上下滑冲突在此时段仍然存在。
2. **工程师任务卡**：现为原生 Compose（9-25 spec D2），当初弃 HTML 的理由是「实时刷新与静态 HTML 语义相斥」。但任务卡恰好是「折叠摘要 + 点击看细节」的天然双态形态，与 HTML 卡双形态体系同构；且端侧模板渲染（非 LLM 产物）使「样式权威在 App」，比自由 HTML 更可控。

## 2. 方案概述

把 HTML 卡升格为**双形态组件**，并回收任务卡为第二个数据源：

- **Inline 卡（短卡）**：现状保留——完全撑开、卡内直接交互、永不内滚。
- **Fullpage 卡（长卡）**：chat 内外显为**固定高实时裁剪预览**（≈0.5 屏，底部渐隐 + 提示条），预览态禁交互，点击进**全屏查看器**（同沙箱、允许竖滚、全量 JS 交互）。
- **分流 = 混合判定**：LLM 经 `render_html` 新参数 `display` 声明意图；端侧测高超阈值强制降级预览卡兜底。
- **任务卡 HTML 化**：折叠/展开态均改为端侧 L1 模板渲染 HTML；审批动作条原生外置；原地展开超一屏自动复用 Fullpage 形态。

## 3. 形态定义

| | Inline 卡（现状） | Fullpage 预览卡（新） | 全屏查看器（新） |
|---|---|---|---|
| 触发 | display=inline 且测高 ≤ 阈值 | display=fullpage，或测高 > 1.0 × 聊天可用屏高 | 点击预览卡 / 点击任务卡展开态超长部分 |
| 高度 | 内容全高（≥120dp，无上限） | 固定 ≈0.5 × 可用屏高 | 全屏 |
| 交互 | 全量 JS（点击/输入/手风琴） | **禁交互**（触控层吃掉全部触摸，点击=进全屏） | 全量 JS + 允许竖滚、显示滚动条 |
| 滚动 | 自身永不竖滚 | 自身不滚（高度恒定，WebView 拿不到手势） | 自身竖滚（无列表冲突） |
| `<a>` 外链 | `HtmlLinkPreviewOverlay` 落地页 | 同左 | 同左（叠在查看器之上，查看器自身不导航） |
| 沙箱 | 零 JS 桥 / 禁文件 / 禁 DOM Storage / 远程 script 剔除 / img·CSS 放行 | 同左 | 同左（ResizeObserver/测高管线关闭） |

**预览卡视觉**：WebView 真实渲染上半部（所见即所得），底部 120px 渐隐遮罩（向卡底色渐隐），底部居中提示条「⤢ 点击查看完整内容」（primary 色）。渲染失败 → 原生封面兜底（summary + 提示条），不白屏。

**全屏查看器**：顶栏 = ✕ 关闭 + 标题（取 `render_html` 的 summary，截断），hairline 分隔；系统返回键由宿主 BackHandler 收口（对齐 `HtmlLinkPreviewOverlay` 先例）。

## 4. 分流判定（混合）

- **LLM 声明**：`render_html(html, summary, display?)` 新增 `display: "inline" | "fullpage"`（默认 inline）。prompt 契约：长报告/多屏图文/为全屏设计的交互页必须声明 fullpage；inline 卡建议内容 ≤ 0.66 屏。
- **端侧兜底**：测高完成后判定——`display==fullpage` → 预览卡（尊重 LLM 意图，哪怕内容偏短）；否则测高 > 1.0 × 可用屏高 → 强制预览卡。
- **稳定性**：判定结果随消息持久化（消息 metadata 记 `displayMode` + 最终测高），会话重开/列表回收后形态不跳变；进程级测高 LruCache 沿用。
- **任务卡特例**：不走 `display` 参数，由展开状态与测高自动分流（§7）。

## 5. 预览卡手势与触控

- 预览态在 WebView 上叠透明触控层（Compose）：消费**点击**（→ 全屏查看器），不消费竖拖——LazyColumn 越过 touch slop 后正常接管滚动，手势冲突从根上消失（卡高恒定，无测高滞后窗口）。
- WebView 设不可聚焦/不可点击；JS 仍运行（渲染与动画需要），仅触摸不到达。
- 预览卡高度恒定 → 不参与 ResizeObserver 高度跟随，滑动防抖三件套（测高缓存/滑动冻结/抖动阈值）对其天然无负载。
- 实现偏差（2026-09-26 H1 审查接受，不修）：inline 测高兜底转 FULLPAGE 的卡其 ResizeObserver 仍在运行——转预览后 observer 反注册不做，无效回调被 displayMode 分支忽略（FULLPAGE 不参与高度跟随），无功能影响。

## 6. 全屏查看器

- 新浮层组件 `HtmlFullpageViewer`（与 `HtmlLinkPreviewOverlay` 同级 overlay，宿主 ChatScreen 统一 BackHandler 收口）。
- 加载清洗后的同一 HTML（消息 payload 直取，不重新走 LLM）；沙箱设置与卡片完全一致（零桥/禁文件/禁 DOM Storage/远程 script 已剔除/img·CSS 放行）。
- 允许竖滚、显示滚动条（长内容需位置指示）；不注入测高 JS 与 ResizeObserver。
- `<a>` 点击仍回调 `onOpenLink` → `HtmlLinkPreviewOverlay` 叠上；查看器自身永不导航。

## 7. 任务卡 HTML 化

**对 9-25 spec 的修订（用户 2026-09-26 决策，留痕）**：推翻 D2「任务卡 = 原生 Compose」与 §6「RICH_HTML 化任务卡」不做项。`TASK_CARD` 消息类型、`EngineerTaskReducer` 状态机、审批语义（US-1~16）、实施分期（P1/P2/P3）全部不变——**只换渲染层**。

- **HTML 来源**：端侧 **L1 模板 + 任务状态 JSON**（App 渲染，非 LLM 产物）——ADR-014 D5 的 L1 轨首个兑现场景；样式权威在 App，安全面远小于自由 HTML；模板 CSS 走 token 导出的 CSS variables，Light/Dark 随主题切换。
- **形态**：
  - 折叠态 = inline HTML 卡：header（⚙ + 标题 + 状态 chip）、当前阶段（最近 tool_use 摘要）、meta（轮次/耗时/成本）、进度条、底部「点击展开细节 ▾」。
  - 点击 → **卡内原地展开**：+ 阶段时间线、最近事件列表、diff 摘要；高度跟随（ResizeObserver 现有管线）；提示变「点击收起 ▴」。
  - 展开后测高 > 1.0 屏 → 自动转 Fullpage 预览形态（渐隐 + 提示条），点击进全屏查看器看完整事件流/日志。
- **实时刷新**：SSE/状态流变化 → 重新生成 HTML 并**节流重渲染**（500ms 合帧；列表滑动中冻结，滚停后应用——复用 HTML 卡防抖机制）。闪烁风险（WebView 整页重载）为已知代价，P1 实测定夺是否需增量 DOM 补丁通道（预留，不在本期）。
- **审批动作条原生外置**：HTML 卡底部拼接原生 Compose 动作条（圆角延续、表面色稍深）——running=[停止]、approval=[继续/到此为止]、done=[交付 push/暂不]、failed=[重试]。零 JS 桥接红线不动，D5 审批外置语义延续；动作状态与卡片同源（同一任务状态模型）。
- **任务中心页**（US-12~16）列表项继续用现有原生紧凑形态，不 HTML 化（页面内多卡并存，WebView 实例成本不划算）。

## 8. Prompt 契约变更

- `render_html` tool description 增加 `display` 参数说明与适用判据。
- `html_card_rules` 增加：inline 卡建议内容高度 ≤ 0.66 屏；长报告/多屏内容声明 `fullpage`；fullpage 卡把导航/关键交互设计在顶部（用户可能先看预览）。
- `RenderEnvironment` 注入追加：预览卡固定高度（0.5 屏 px 值）与分流阈值（1.0 屏 px 值），LLM 排版有确定基准。

## 9. 降级链与错误处理

1. 清洗拒绝 → 不落库，原因回传 LLM（现状不变）。
2. 预览卡 WebView 渲染失败（`onRenderProcessGone`/加载错误）→ 原生封面兜底（summary + 「点击查看完整内容」），点击仍进全屏（全屏内再失败 → 错误占位 + 关闭）。
3. 任务卡模板渲染失败 → 回退纯文本摘要卡（状态 + 标题），动作条不受影响（原生层独立）。
4. 测高异常（≤0 或 NaN）→ 保持占位高并按 inline 处理（现状逻辑），不触发强制预览。

## 10. 数据与持久化

- `HTML_CARD` 消息 metadata 扩展：`display`（LLM 声明原值）、`displayMode`（端侧终判 inline/fullpage）、`measuredHeightPx`。
- 任务卡：`TASK_CARD` payload 不变（状态 JSON），HTML 为渲染期产物，不落库——进程重建后按状态重渲染（对齐 US-6 恢复路径）。
- Room 迁移：metadata 为 JSON 扩展字段，无 schema 变更（实现时核实序列化管线，对齐 ADR-014 §3 已知项）。

## 11. 安全模型

- 零 JS 桥接、清洗器、远程资源策略全部沿用现状，本 spec 不新开任何原生指令面（URL scheme 方案已被用户否决）。
- 任务卡 HTML 由端侧模板 + 状态 JSON 组装：状态文本字段（阶段摘要/事件行）来自云主机 Claude Code 输出，属半可信——入模板前按纯文本转义（HTML escape），不经清洗器白名单（模板本身可信，变量插值是唯一注入面）。
- 全屏查看器不放宽任何沙箱设置（DOM Storage 仍禁——与 `HtmlLinkPreviewOverlay` 的通用 web 内容定位不同，查看器渲染的是已知清洗产物）。

## 12. iOS 同构

- 双形态（预览卡/全屏查看器/分流判定）与任务卡 HTML 化均走 /ios-follow 排期；WKWebView 照搬同款沙箱/测高/渐隐预览。
- 记入 parity 台账（`docs/08-UI-SPECS/PARITY_MASTER_PLAN.md` 平台差异层）；chat.yaml 登记新形态。

## 13. 设计稿索引（Ardot · HtmlCard 页 438:2）

| 帧 | frameId | 内容 |
|---|---|---|
| `htmlcard/inline` | `438:3` | 短卡聊天场景（仪表盘卡，直接交互） |
| `htmlcard/preview` | `438:60` | 长卡固定高预览（渐隐 + 提示条） |
| `htmlcard/fullpage` | `438:98` | 全屏查看器（顶栏 + 竖滚 + 手风琴） |
| `htmlcard/notes` | `438:139` | 交互与工程批注 ×7 |
| `taskcard/collapsed` | `438:181` | 任务卡折叠态（running） |
| `taskcard/expanded` | `438:208` | 任务卡原地展开态 + 原生动作条 |
| `taskcard/approval` | `438:264` | 审批态 + 原生动作条 |
| `taskcard/notes` | `438:296` | 任务卡 HTML 化批注 ×5 |

画布质量：health-check 退出码 0（literal=0）；Light 双模验证 8/8 通过。已知例外：hero/渐隐遮罩为字面渐变（引擎渐变+变量 stop 不绘制陷阱），遮罩按 Dark 底色渐隐，Light 下呈暗色晕染（实现时渐隐遮罩用运行时卡底色，天然规避）。

## 14. 测试决策

- **分流判定**：纯函数（display × 测高 × 阈值 → displayMode）JVM 单测，覆盖 LLM 误判两方向与边界（=阈值）。
- **预览卡**：ui-driver 点击进全屏、竖拖滚列表不触发卡内交互；screenshot-diff 基线新增预览卡帧。
- **全屏查看器**：沙箱设置回归（零桥/DOM Storage 禁）；`<a>` 走落地页浮层。
- **任务卡**：`EngineerTaskReducer` 既有单测不动；新增「状态 JSON → HTML 模板组装」纯 Kotlin 单测（转义/四态/字段缺失降级）；节流合帧逻辑单测；渲染失败回退文本卡。
- **冒烟**：DEBUG `/html` 注入长文卡（超一屏）验证自动转预览；任务卡四态 HTML 渲染走 `HtmlCardSmokeSamples` 同类入口。

## 15. 明确不做

- URL scheme / JS 桥等卡内原生指令通道（审批动作条原生外置已覆盖诉求）；
- 增量 DOM 补丁刷新通道（任务卡闪烁若可接受则永不做，预留评估）；
- 任务中心页列表项 HTML 化；
- 预览卡内横向交互保留（预览态一律禁交互，YAGNI）；
- iOS 完整实现（/ios-follow 排期）。

## 16. 实施分期建议

| 期 | 范围 | 前置 |
|---|---|---|
| **H1 双形态主干** | display 参数 + 分流判定 + 预览卡 + 全屏查看器 + prompt 契约 | 无，可立即开工 |
| **H2 任务卡 HTML 化** | L1 模板 + 组装器 + 原地展开 + 原生动作条 + 节流重渲染 | H1（复用分流/预览/全屏件）；与任务卡 P1 合流 |
| **H3 ios-follow** | WKWebView 同构 + parity 验收 | H1/H2 Android 定稿 |

---

## 附：对既有文档的修订留痕

- `2026-09-25-engineer-task-card-design.md`：D2「原生卡片」与 §6「RICH_HTML 化任务卡」不做项被本 spec §7 推翻（用户 2026-09-26）；该 spec 头部已加修订注记指回本 spec。
- `JS_ENGINE_TECH_SPEC.md` §7.1：实现落地时按双形态更新「UI 形态」段（完全撑开不再是无上限唯一形态），随代码原子同步（DOC-SYNC）。
- `ADR-014`：顶部修订注记第 1 条（呈现形态）在本 spec 后二次演进——「卡片内直接交互 + 完全撑开」收窄为 Inline 形态，新增 Fullpage 双态；实施时回写 ADR 注记。
