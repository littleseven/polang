# ADR-014: Chat 富内容渲染——原生富渲染 + 沙箱 Artifact 卡片（方案 B）

**状态**: 已定稿（方向性决策生效；实现另立 spec）
**日期**: 2026-09-25
**决策**: 用户（方案 B 选定；D1 措辞/D2 一消息一卡/D5 约束框架逐节评审认可）
**依赖**: ADR-008（隐私红线）、ADR-011（ui-driver 测试体系）、ADR-013（KMP 契约）

> **修订注记（2026-09-25）**：落地实现与本 ADR 两处方向性差异，经用户确认、留痕如下——
> 1. **呈现形态**：D2 原定为「气泡内定比预览卡 → 点击进全屏沙箱 Artifact 查看器」两段式；
>    实际落地（`HtmlCard`，见 JS_ENGINE_TECH_SPEC.md §7.1）为「**卡片内直接交互**（动态测高 +
>    ResizeObserver 高度跟随 + **完全撑开**（卡片高度=内容全高、不内滚，规避 LazyColumn 高度协商的
>    方式从定比预览改为出站 JS 测高+全高展开））+ **仅 `<a>` 外链点击进全屏落地页**
>    （`HtmlLinkPreviewOverlay`）」——全屏查看器不再是主交互入口。
> 2. **外链安全限制暂时放开**（表现力优先，用户 2026-09-25 指示）：远程 img/CSS/`<a>` href
>    放行直拉，卡片内点击外链打开全屏落地页；**底线不变**——远程 script/iframe/object/embed/
>    form/meta refresh 仍由清洗器剔除，零 JS 桥接不变。后续若收紧，收紧点在
>    `HtmlCardSanitizer` 与 `shouldInterceptRequest`，架构上可随时回退到 D5 严格模式。
> 3. **呈现形态二次演进（2026-09-26，双形态 H1）**：注记 1 的「卡片内直接交互 + 完全撑开」
>    收窄为 **Inline 形态**（短卡保留现状）；新增 **Fullpage 双态**——长卡（LLM 声明
>    `display="fullpage"` 或端侧测高 > 1.0 × 可用屏高）在聊天流内外显为固定高（≈0.5 屏）
>    实时裁剪预览（底部渐隐 + 提示条、预览态禁交互），点击进**全屏查看器**
>    （`HtmlFullpageViewer`，同沙箱、允许竖滚、全量 JS 交互）；分流终判随消息 metadata
>    持久化，形态不跳变。spec《2026-09-26-html-card-two-tier-design.md》，
>    实现细节见 JS_ENGINE_TECH_SPEC.md §7.1「UI 形态（双形态）」。

---

## 1. 背景

Chat 是主入口（2026-08 产品重心迁移后），两类渲染诉求：

- **(a) LLM 富输出表达力**：markdown/表格/代码高亮的渲染质量
- **(b) Agent 生成 UI**：tool_call 产出富卡片，不发版长出新消息形态

现状痛点（2026-09 实查）：

- markdown 走 `compose-markdown` 0.5.4（jeziellago，长期未维护），表格需自行外挂 `MarkdownTable` 处理
- 图表为 QuickJS 端侧生成 SVG → androidsvg 栅格化**静态位图**（`ChartSvgImage.kt`），无交互
- 富卡片（媒体轮播/抽卡/claude 步骤流）全部手写 Compose；全 app 无 WebView

讨论过三方案：A 原生增强 / B 混合富卡片 / C 全量 HTML 会话。市场调研（2026-09-25）结论：

- **(a) 无头部 App 用 WebView 渲染正文**：ChatGPT 移动端为双端原生（Android Kotlin+Compose / iOS Swift+SwiftUI，官方 JD 实证；**2026-09-26 勘误**——原「RN 管线」说法系与 web 端 react-markdown 栈混淆，详见 `docs/reviews/2026-09-26-chat-rendering-framework-research.md`），web 端走 react-markdown 式组件管线（流式不完整 markdown 的增量解析是专门课题，参考 react-native-streamdown）；Android 原生阵营走 Markwon 式管线
- **(b) 头部 App 全员收敛到「原生聊天流 + 沙箱 WebView 卡片/独立面板」**：Claude Artifacts、ChatGPT Apps widgets、微信小程序卡片、电商客服 H5 卡
- **C（全量 HTML 会话）无市场先例**

## 2. 决策

### D1（目标 a）：正文走原生富渲染，不走 HTML

- 替换的是**渲染库**（`compose-markdown`，长期未维护），不是 markdown 格式——LLM 输出仍以 markdown 为准（通用语，prompt 约束与工具链依赖它），新管线继续渲染 markdown。选型硬指标：**不完整 markdown 的增量解析**、表格、代码高亮、**白名单内联 HTML 原生渲染**（轻量花式排版——下划线/高亮/上标级，GitHub README 模式，见 §4-18）（具体选型在实现 spec 定）
- WebView 不用于渲染聊天正文（流式性能/文本选择/a11y/PERF 红线均不支持）

### D2（目标 b）：`RICH_HTML` 消息类型 + 沙箱 Artifact 容器

- `ChatMessageType` 新增 `RICH_HTML`（负载：HTML 字符串或模板 ID + JSON 数据）
- **两段式呈现**：气泡内**定比预览卡**（规避 LazyColumn 高度协商难题）→ 点击进**全屏沙箱 Artifact 查看器**（常驻预热的单 WebView 实例）
- agent 新增 tool_call 能力（如 `render_html_card`）产出 HTML/模板数据 → 即得生成式 UI，不发版上新消息形态
- **一消息一卡**：一条 `RICH_HTML` 消息 = 一张卡 = 一份完整 HTML 文档，不做卡内多 WebView 拆分（实例倍增 + 高度协商回归 + 肢解 CSS 整版排布价值）。多元素组合靠**消息序列**表达（AgentText → RICH_HTML → AgentText）；卡片可携带原生 caption（复用 `content` 字段，对齐 `AGENT_IMAGE` 先例）
- 正文线现有图文混排形态不动：`USER_IMAGE_TEXT` / `AGENT_IMAGE` / `AGENT_EDIT_RESULT` / `MEDIA_RESULTS` 轮播，均为原生块组合
- 图表演进（可选后续）：Chart 卡片迁入 artifact 容器获得 Chart.js 交互（tooltip/动画/图例）；现有 SVG 静态卡保留为降级路径

### D3：沙箱安全基线（ADR-008 红线落地）

- WebView 仅加载本地/内联内容：CSP 禁外链 + `shouldOverrideUrlLoading` 拦截一切远程资源 → 媒体不可经 WebView 外泄
- JSBridge 白名单最小面（仅预览卡所需的手势/导航/保存回调），bridge 语义双端一致
- 禁通用 file 访问；`content://` 媒体经 WebViewAssetLoader 白名单注入
- 端侧 HTML 清洗与风格白名单是**同一条管线**（见 D5 防线 2）：安全与风格一鱼两吃

### D4：iOS 同构（不推翻 ADR-013）

- HTML 模板与 JSON→HTML 组装器（纯 Kotlin、JVM 可测）可入 `commonMain`；WebView/WKWebView 容器是平台实现，各端自理
- iOS 批次①② SwiftUI 原生成果不动，artifact 容器是增量能力

### D5：HTML 排版风格约束——L1/L2 双轨 + 四道防线

原则：**样式权威收归 app，LLM 只交结构/数据**（参照 email 客户端/GitHub README 的「作者交结构、阅读器出样式」模式；Claude Artifacts 为反例——故意放权换创造性，与本 App 目标相反）。

**自由度分级**：

| 级别 | LLM 交什么 | 可控度 | 定位 |
|---|---|---|---|
| **L1 模板+JSON** | 模板 ID + 数据 | 100%（模板 CSS app 管控） | 默认路径，覆盖常见形态（报告/统计卡/时间线/九宫格） |
| **L2 受控 HTML** | 语义 HTML 片段（无样式） | 结构自由、样式受注入约束 | 表达力档，"不发版上新形态"的兑现层 |
| **L3 自由 HTML** | 任意 | 无 | 仅开发者调试，永不对 LLM 开放 |

**四道防线**（针对 L2，纵深防御）：

1. **注入式 CSS**（最强）：容器渲染时注入 reset + 组件类词表 + tokens 导出的 CSS variables；禁 inline style / `<style>` 块；字体走本地 font-family 栈；Light/Dark = 变量组切换
2. **白名单清洗**（执法，不信 prompt）：标签→属性→CSS 属性三级白名单，剥 script/外链/`position:fixed`；纯 JVM 可单测；与 D3 安全是同一条管线
3. **Prompt 契约**（引导）：tool description 写明样式契约与 class 词表——只引导不执法，必须叠在清洗之上
4. **回归防线**：模板画廊页（全模板 × 样例数据 × 双主题）跑 `screenshot-diff.py` 基线对比

**token→CSS 变量源**：Ardot `export_variables` 已支持 css 格式（`--name: value` + `[data-theme]` 块），纳入 token sync 流程即为 L1/L2 的变量源，无需新建管线。

### D6：明确不做

- **C 全量 HTML 会话**：无先例，滚动/输入/键盘/a11y 全量过桥的手感税不可控；仅允许日后作为 feature flag 实验分支，不入主线
- **自研 DSL→native 卡片引擎**（蚂蚁动态卡片/Telegram Instant View 路线）：体量不匹配，为性能放弃任意 HTML 不划算

## 3. 后果

- ✅ (a)(b) 均按市场验证形态落地；模板与桥协议双端复用；图表从静态位图升级为可交互
- ✅ 沙箱面收敛在卡片/查看器内，正文仍全原生（流式/文本选择/a11y 零妥协）
- ⚠️ 新增维护面：预热常驻 WebView 的内存预算（中端机实测定）、JSBridge 白名单纪律、ui-driver 对 web 内容的适配（在 ADR-011 体系内扩展）
- ⚠️ `RICH_HTML` 负载进 Room metadata 的序列化管线需扩展
- ⚠️ 模板库与 class 词表需随形态演进持续维护（画廊回归基线防漂移）
- ⚠️ 远程 LLM 产出 HTML = 新攻击面：CSP + 禁网 + 模板白名单三道闸，实现 spec 必须含安全测试

## 4. 已知风险与开放问题（实现 spec 前须收敛）

**🔴 架构级缺口（spec 前必须裁决）**

1. **预览卡内容来源**：原生样式卡（快/稳，失真实版面）vs 离屏 WebView 渲染截 bitmap（真实，多一条异步渲染+缓存管线）
2. **JS 来源铁律**：LLM 永不产代码只产数据；交互能力 = app 预注入的受控组件库（Chart.js wrapper 等），L2 HTML 只能声明式引用
3. **CSS 特性基线**：minSdk 24 + 无 Play 服务国产 ROM 的老 WebView（`aspect-ratio` 等新特性不可用），注入 CSS 与模板按基线写
4. **主题同步协议**：App 内主题 ≠ 系统主题，WebView 不自动跟随；`data-theme` 注入 + 已开卡片热切换时机

**🟡 数据与兼容（spec 中定）**

5. L1 模板版本化与历史消息降级（旧会话引用已改/删模板）
6. 清洗时机（保存时冻结 vs 渲染时一致）+ RICH_HTML payload 体积上限
7. 完整降级链：L2 清洗失败 → L1 校验失败 → 纯文本兜底，不允许白屏
8. L1 模板静态文案的五语 i18n 注入机制

**🟡 安全对抗面（D3 白名单配套）**

9. 绕过样例库：`<svg><script>` / event handler 属性 / CSS `url()` 外带 / `data:`·`blob:` URI / `meta refresh` / `iframe·srcdoc` / `javascript:` 链接——白名单单测必须覆盖
10. `onRenderProcessGone` 渲染进程死亡恢复策略
11. 提示注入链复核：相册 OCR/标签 → 远程 LLM → 恶意 HTML；`render_html_card` 与其他能力组合的权限面

**🟢 工程与产品周边（上线前兜住）**

12. 内存/电池实测：中端机常驻 WebView RSS 预算；JS 动画 onPause 冻结
13. ui-driver 对 WebView a11y 树的适配（artifact 交互回归路径，否则只守得住静态外观）
14. 卡片导出图片（分享）的离屏渲染管线预留
15. L1 模板的 Ardot 设计稿覆盖（Light/Dark 双模式），设计管线成本入账
16. `FloatingChatBubbleService` 中 RICH_HTML 降级为仅预览卡
17. SVG 静态图表卡与 Chart.js 卡并存的迁移窗口与终态时间表
18. **正文轻量花式排版与 HTML 防御**（2026-09-26 增，随富内容三通道路由决策——`2026-09-26-html-card-two-tier-design.md` §8）：① D1 选库须评估**白名单内联 HTML 的原生渲染**（`<u> <mark> <sup> <sub> <kbd> <br>` 级，原生组件渲染、不进 WebView）；② 实现验收含**正文流块级 HTML 防御**——检测到渲染级 HTML → 剥离或降级为代码块，不允许半渲染（防 LLM 自发嵌 HTML 绕过 `render_html` 通道）

## 5. 状态

| 项 | 状态 |
|---|---|
| 方向决策（本 ADR） | ✅ 已定稿（2026-09-25） |
| 正文渲染管线选型（D1） | ⏳ 实现 spec |
| RICH_HTML + Artifact 容器（D2/D3） | ✅ Android 已落地（2026-09-25 合 main；呈现形态按顶部修订注记，实现 SSOT = `JS_ENGINE_TECH_SPEC.md` §7.1） |
| 排版风格约束分级（D5） | ✅ 用户已认可（2026-09-25）；模板库/词表落地在实现 spec |
| 开放问题收敛（§4，18 条） | 🔄 部分随 Android 落地裁决（预览卡形态、外链策略见顶部修订注记）；余量随后续迭代收敛 |
| iOS 同构落地（D4） | ⏳ /ios-follow 排期 |

## 6. 相关

- 依赖：ADR-008、ADR-011、ADR-013
- 市场调研（2026-09-25，本轮对话存档；**2026-09-26 扩充勘误**：四家系统调研见 `docs/reviews/2026-09-26-chat-rendering-framework-research.md`，ChatGPT「RN」系误记，实为双端原生）：ChatGPT（双端原生管线 + Apps widgets 沙箱）、Claude Artifacts、微信小程序卡片、字节小程序卡片形态、电商客服 H5 卡
