# Chat 页渲染框架调研——豆包 / 千问 / ChatGPT / Muse 客户端渲染技术与卡片体系

> **调研日期**: 2026-09-26
> **调研方式**: 5 路并行（豆包 / ChatGPT / Muse 渲染补充 / 千问 / PoLang 代码摸底），每路多源交叉验证；Muse 产品面复用 `2026-09-25-meta-muse-feature-research.md`
> **核心目的**: 为 PoLang chat 页渲染架构（ADR-014 原生正文 + 沙箱 HTML 卡；`2026-09-26-html-card-two-tier-design.md` 双形态卡）提供外部对标与决策验证
> **性质**: 外部产品调研，非承诺性规划；遵循「交付即清理」约定，不需要时可直接删除
> **置信度标注**: ✅ = 有来源事实（附 URL）；🔗 = 合理推断；❓ = 无公开资料（明确声明，不编造）

---

## 0. 一页总览

| 维度 | ChatGPT | 豆包 | Meta Muse | 千问 | PoLang 现状 |
|---|---|---|---|---|---|
| 移动端技术栈 | 双端原生：Android Kotlin+Compose ✅ / iOS Swift+SwiftUI(+UIKit) ✅ | 双端原生 ✅（JD 证据）；Compose/UIKit 无披露 ❓ | 无公开资料 ❓（无 teardown，27MB base APK；内部代号 Hatch） | 原生/跨端无定论 ❓（无 teardown、JD 无栈细节；`com.aliyun.tongyi` 原位更名） | Android Compose + iOS SwiftUI |
| chat 正文渲染 | 原生组件管线；web 端 react-markdown ✅ | 原生（推断）；细节零披露 ❓ | 消息流薄壳 + 服务端工件回显 ✅ | 「流式逐字+思考可视化」官方特性 ✅；实现零披露 ❓ | 原生（compose-markdown 库 + 自研表格/代码段） |
| 流式方案 | SSE + JSON-Patch：`parts[]` append 补丁 + 哨兵换绑 ✅ | 无公开资料 ❓ | 无公开描述 ❓ | Web 端 SSE + 重试/快照参数化下发 ✅；App 端 ❓ | 累积快照 + 豆包式打字机节奏控制器 |
| 卡片体系 | 两层：一方「结构化数据+封闭原生模板」+ 三方「Apps SDK 沙箱 iframe Web bundle」 ✅ | 形态可确认（任务编排卡/技能卡/语音视频 UI）；机制零披露 ❓ | 服务端 Web 工件（含无头浏览器渲图）+ 安全关键原生弹层 ✅ | 形态清单丰富 ✅（订单支付/成品文件交付/服务入口/智能体/多媒体生成卡）；机制零披露，第三方分析指向 JSON Schema DSL 管线 🔗 | 13 值 enum + if/else 分发链；HTML_CARD 零桥沙箱 WebView |
| 动态化容器 | 沙箱 iframe（Apps SDK）+ 全屏 canvas ✅ | 推测有（天气/股市富卡）；机制不明 🔗 | 服务端 Spaces（React/Bun/Cloudflare）+ magic-moment 渲图 ✅ | 长尾服务卡疑走 H5/hybrid 容器（`tongyi-hybrid` 基建）🔗 | 沙箱 WebView（HtmlCard，零 JS 桥） |
| 桌面端 | macOS=iOS 代码库原生移植 ✅；Windows=Electron ✅ | Chromium 自研（非套壳）✅ | computer use 已证实；打包技术 ❓ | PC 客户端 v2.3.1（Win/macOS）内核 ❓；Qwen Code Desktop=Tauri 属另一产品 ✅ | 无桌面端 |

**四家共同结论（对 PoLang 最重要）**：① 已查明技术栈的三家（ChatGPT/豆包/Muse）移动端 chat 正文**无一用 WebView/跨端框架承载**，千问未定论但产品形态无反例；② 富卡片全部收敛到「结构化数据 + 受控容器（原生模板或沙箱 Web）」；③ 安全/审批类 UI 一律原生外置；④ 流式渲染工程细节各家均不公开（ChatGPT 线协议是唯一例外，来自抓包逆向；千问 Web 端 SSE 配置是第二例外，来自页面源码直抓）；⑤ 国内两家（豆包/千问）对客户端渲染**完全零工程披露**，能拿到的最深处证据分别是招聘 JD 与 Web 端配置指纹。

---

## 1. ChatGPT（证据最充分的一家）

### 1.1 客户端技术栈：双端原生 ✅

- **Android = Kotlin + Jetpack Compose**：多个官方 JD 一致点名（"deep expertise in Kotlin, Jetpack Compose"——ChatGPT Library Team 岗；Ad Formats / ImageGen / Growth 岗同要求），且**无任何一条 JD 提及 RN/Flutter**。无公开 APK teardown。
- **iOS = Swift/SwiftUI + UIKit 混用**：JD 原文 "Building and evolving foundational Swift/SwiftUI infrastructure powering the ChatGPT mobile app"；Blind 社区共识为 SwiftUI 新界面 + UIKit 复杂组件混用。
- **macOS 桌面 = iOS 代码库移植**（原生 Swift，Apple Silicon 限定首发为旁证；社区内存实测与竞品 "Electron wrapper" 说法冲突，采信前者）；**Windows = Electron**（electronjs.org 官方名录 + 社区实测）；2025-07 统一新桌面版（Chat/Work/Codex 合并）疑似 Web 技术栈（内存显著高于 Classic），Linux 版 2026-08 跟进。
- 注意：GitHub 上 skydoves/ChatGPT-Android 是第三方演示项目（Stream Chat SDK），非官方。

### 1.2 流式渲染：SSE + JSON-Patch 线协议（抓包逆向，2025-09）✅

这是四家中唯一被逆向出细节的流式方案，参考价值最高：

- **消息模型**：一条消息 = `message.content.parts[]`（文本/图片段）+ `message.metadata.*`（引用等结构化挂件）。
- **增量协议**：后端推 `event: delta`，载荷为 patch 操作数组——文本 **append-only 追加到 `parts[N]`**；结构化富数据（引用等）独立 patch 到 `metadata.*`：
  ```json
  {"p":"", "o":"patch", "v":[
    {"p":"/message/content/parts/0", "o":"append", "v":" …文本…"},
    {"p":"/message/metadata/content_references", "o":"append", "v":[{…}]}
  ]}
  ```
- **引用卡内嵌机制（哨兵换绑）**：模型在文本流里输出**私用区 Unicode 哨兵**（如 `citeturn0search11...`），同时 `content_references` patch 携带 `matched_text/start_idx/end_idx` 定位数据；**客户端把哨兵替换为可点击引用 chip**。系统提示约束模型按此格式包裹引用。
- **web 端**：react-markdown 全量重解析（2023 社区逆向）——每 chunk 到达即整段重新 parse，是社区公认「流式 markdown 抖动」问题的根源（催生 zero-jitter 等专用库）；移动端渲染器实现无公开资料 ❓。
- **打字节奏**：无官方文章；合理推断为 chunk 到达 + 按帧合并重组（每 frame 至多一次重渲染）🔗。

### 1.3 卡片体系：两层架构 ✅

**第一方卡片 = 「服务端结构化数据 + 客户端封闭原生模板集合」**（模板集合由客户端版本决定，不是任意 JSON-UI DSL）：

| 卡片 | 机制要点 |
|---|---|
| 浏览引用 chip / Sources 侧栏 | 哨兵 + content_references 换绑（§1.2）；桌面 web 另有侧栏面板 |
| thinking 折叠块 | 只展示模型生成的推理摘要（Responses API 仅暴露 `reasoning.summary`），"Thought for N seconds" 折叠气泡，轻量原生模板 |
| 图像生成卡 | GPT-4o 起对话内联卡片（占位/加载态可长达 ~2min），完成带下载/编辑操作 |
| 购物商品卡 | 搜索答案内商品轮换卡（图/名/价/评分）；Instant Checkout 曾做到卡内 Buy + 聊天内支付面板（2026-03 退役回退外链） |
| Agent 模式进度卡 | 虚拟计算机内执行（浏览器/终端/API），UI = 逐屏叙述时间线 + 打断/接管 + Watch Mode + 完成推送通知；交付物为可编辑幻灯片/表格 |
| Canvas | 独立于消息流的文档态侧栏/全屏面板，定向局部编辑；专门训练的分类器模型判断何时打开 |

**第三方富 UI = Apps SDK（DevDay 2025 发布）——「沙箱 iframe + Web bundle + 桥」**：

- 第三方前端是普通 Web 代码（HTML/CSS/JS，React/Vue/Tailwind 均可），在对话内**沙箱 iframe** 渲染为交互卡片。
- 底座 MCP：tool 调用结果携带 `_meta.openai/outputTemplate` 元数据（指向打包好的 HTML/JS bundle URI），客户端用 tool 结构化输出**注水（hydrate）** widget。
- 注入 `window.openai` 组件桥供前端与宿主/MCP server 交换数据。
- UX 约束写进官方规范：观感须「像 ChatGPT 原生」——统一边距/圆角、亮暗主题感知、语义 HTML + ARIA；配套官方 UI 设计系统（openai.github.io/apps-sdk-ui）。
- 跨宿主可移植的通用规范是 MCP-UI（协议层），Apps SDK 是其 ChatGPT 收紧版。

**定性**：ChatGPT **没有做**「JSON 定义 UI → 原生渲染」的 DSL；长尾表达力交给沙箱 Web 容器，一方体验交给封闭原生模板。

### 1.4 无公开信息项（明确声明）

Android 消息列表控件与 markdown/高亮实现；iOS markdown 渲染器与 class-dump 级架构；官方流式渲染/长会话性能工程文章——均无公开资料 ❓。

---

## 2. 豆包

### 2.1 客户端技术栈：双端原生 ✅（机制层零披露 ❓）

- **JD 证据**：豆包客户端招聘要求 C/C++、Java/Kotlin、Swift/OC 双端原生栈，无 Dart/Flutter/JS 跨端要求（豆包官网/字节 jobs/BOSS 直聘多渠道一致）。
- **排除 Lynx**：Lynx 官网 "Trusted by" 仅 TikTok 与 CapCut，不含豆包；字节自研下一代跨端方案（RTS 语言 + Salamander 工具链 + Relax UI 渲染，GTLC 演讲）目标是取代/补充 Lynx，与豆包主客户端无关联证据。
- Android 是 View 还是 Compose、iOS 是 UIKit 还是 SwiftUI：**无公开资料** ❓。豆包由字节 Flow 团队开发，该团队无任何客户端技术分享。
- **桌面端 = Chromium 自研**（2024-06 上线，媒体明确「不是简单套壳」；屏幕共享走 Chromium WebRTC desktop_capture），后演化为「豆包浏览器」；Web 版 doubao.com/chat。

### 2.2 流式渲染：无公开资料 ❓

字节未发布过任何豆包聊天渲染工程文章/演讲（公众号/掘金/InfoQ/QCon/ArchSummit/GTLC 全查无）。业界流式 markdown 优化通用焦点（增量段落重排、稳定前缀不重布局、block 级 diff）只能作外部参照，不能归于豆包。

### 2.3 卡片体系：形态可确认，机制零披露 ❓

- **可确认形态**：语音通话 UI（Seeduplex 全双工模型 2026-01 全量上线，交互延迟 -250ms/抢话率 -40%，Realtime event 协议）；视频通话（2025-05）；豆包 2.0「任务编排中枢」技能卡片化入口（2026-06）；「豆包工作」GUI Agent 技能入口（2026-08）。
- **数据侧基础**：火山方舟已支持 `text.format` json_object/json_schema 结构化输出（2026-09 beta）——豆包系产品具备服务端下发结构化 JSON 的模型侧能力 ✅。
- **机制（服务端 JSON+模板 / 原生模板 / 动态容器）**：无公开资料 ❓。字节内部旁证（maimai 一手帖，2024-09）：Agent 信息抽取卡片用「Web/Lynx/小程序」多容器——说明字节内部卡片容器选型本身是多元的，不能反推豆包。

---

## 3. Meta Muse（渲染补充篇）

> 产品/功能面见 `2026-09-25-meta-muse-feature-research.md`，本节只谈渲染。

### 3.1 客户端技术栈：公开网络无解 ❓

- Android `com.facebook.aura` base APK ≈ 27MB（v6.0.0.48.164）；**全球无任何 teardown**（9to5Google APK Insight 只拆 Google 自家应用；Android Authority 等无 Muse 拆解）；Meta 零工程披露；MSL VP Eng 职责范围含 RN/GraphQL/Litho/ComponentKit 但不能推断产品选型。
- 内部代号 **Hatch**（mouse.dev 逆向 6.8GB 运行时泄漏）。
- **要定论只能自拆**：官方渠道 APK `unzip -l | grep -E "hermes|reactnative|flutter"` 五分钟出结论（需美国 IP 渠道；注意 Facebook 广告投放的假 Muse APK 含木马）。

### 3.2 渲染架构定性：客户端薄壳 + 服务端 Web 工件 + 安全关键原生弹层 ✅

来自 mouse.dev 运行时泄漏逆向（本调研最有价值的 Muse 发现）：

- **Spaces 框架** = Muse 构建/服务 App 的最大代码工程：TypeScript starter 含 React client、server actions、Drizzle SQLite schema、Bun 配置，目录含 `worker/sdk/cloudflare/cvm`；daily.dev 独立佐证：TanStack + Bun + Tailwind 部署到 Cloudflare。
- **`magic-moment` skill 专做「卡片合成」**：内含 browser capture scripts、字体、品牌资产——**聊天流里部分富卡片实为服务端无头浏览器渲染出的图片/视频**，不是客户端原生模板。
- 另有 documents/PDF/presentations/spreadsheets 的文件 builders。
- 服务端 JSON-UI 协议 / 客户端卡片模板 schema：无公开信息 ❓（泄漏的是生成侧代码）。

### 3.3 卡片形态清单 ✅

任务进度卡（系统通知回联）/ Sentinel 审批弹层（**App UI 外置**，防注入）/ 购物三要素确认卡（订单/收货/支付）/ 浏览器动作战报 / 生成文件工件（图/曲谱/游戏/网站，Gizmodo 实测）/ 实时 Avatar 视频通话卡。OAuth 走 WebView/Custom Tab。

---

## 4. PoLang 现状基线（2026-09-26 摸底）

> 详细文件级摸底见调研过程存档；此处只列对标所需事实。

- **正文**：原生 Compose；shared `MarkdownSegmenter`（纯 regex，流式鲁棒）分 MARKDOWN/TABLE/CODE 三段——markdown 走 compose-markdown 库，表格/代码自研 Compose 渲染（表格手写 Grid 规避库的位图抖动；代码折叠+复制）。iOS：同 segmenter + AttributedString（`returnPartiallyParsedIfPossible` 流式容忍）+ 自研表格/代码视图。
- **流式**：Koog 折叠 TextDeltas 为**累积快照** → `RemoteChatEngine` sealed 事件 → `StreamingPacingController`（commonMain，打字机节奏：~50ms/字、CJK 2 字符步进、标点停顿、光标闪烁）→ 独立 `_streamingMessage` StateFlow（打字期间只有这一个槽在变）→ 完成后落 Room、DB Flow 重载列表。**Compose 重组频率 = 节拍器频率，与网络 token 无关**。
- **列表**：LazyColumn(key=id)，无 contentType、无 reverseLayout、无 derivedStateOf（靠流式状态隔离 + HtmlCard 测高缓存/滑动冻结达成性能）。
- **消息模型**：13 值 `ChatMessageType` enum + 单一 ChatMessage 平铺 nullable payload（非 sealed class、非 parts 数组）；分发 = items 块内 if/else 链（代码已挂「抽分发器」技术债标注，无注册表）。
- **卡片**：MEDIA_RESULTS 轮换 / CHART 静态 SVG（AndroidSVG 栅格化位图）/ HTML_CARD 沙箱 WebView（动态测高+完全撑开+ResizeObserver+零 JS 桥+高度 LruCache+滑动冻结）/ TASK_CARD 原生（今日 spec 正在 HTML 化为端侧 L1 模板）/ 抽卡候选条 / Claude 步骤流气泡 / 图片消息族。卡片生产：QuickJS 沙箱脚本 return 值拦截（chart svg / html / media ids）+ LLM tool_call（draw_chart / render_html）。
- **iOS 缺口**：无 TASK_CARD/HTML_CARD/ClaudeAgent 气泡/性能行（/ios-follow 排期中）。
- **二级面**：`AiChatScreen` 浮动面板（相册/相机页）用独立 sealed AgentMessage 模型；`FloatingChatBubbleService` 悬浮泡。

---

## 5. 千问（阿里 Qwen App）

> 品牌脉络：通义千问 App（2023 上线）→「通义」（2024-05 更名）→「千问」（2025-11-14 版本号 3.60.0 直接跳版 5.0.0 并更名公测）。**同一 App 原位更名升级，非新包**：包名 `com.aliyun.tongyi` 未变、qianwen.com 至今使用 `tongyi-fe` 资源组与 `__TONGYI_*` 配置名 ✅。与蚂蚁「灵光」是两家公司两个产品，调研已严格区分。

### 5.1 客户端技术栈：无定论 ❓

- 原生 / Flutter / RN **均无法定论**：无公开 teardown（`libflutter`/`libhermes`/APK 分析中英文多轮检索全负），招聘岗（千问事业部 iOS 研发专家）公开渠道拿不到 JD 技术栈细节。
- 已确证包体：国内 `com.aliyun.tongyi`（应用宝）；国际 `com.tongyi.intl`（v1.2.1，XAPK ≈201MB，50 万+安装）；另有 2026-08 新上架 Qwen Studio（`ai.qwenlm.chat.android`）。
- 间接证据：千问官网反馈入口指向 `m.tongyi.com/app/tongyi/tongyi-hybrid/...`——App 体系内**存在 H5 hybrid 容器通道**（沿用通义系基建）✅；但仅证部分页面，不能推断 chat 消息流本体。
- 五分钟自证法（与 Muse 同）：官方渠道拉包后 `unzip -l | grep -E "libflutter|libhermes|flutter_assets|index.android.bundle"`。

### 5.2 流式渲染：Web 端 SSE 硬指纹 ✅，App 端零披露 ❓

qianwen.com 页面源码直抓到两组配置（本调研四家中第二个「线协议级」硬证据）：

- **SSE 断线重试**：指数退避 2s 起、超 6s 阈值转线性 +1s、上限 10s、最多 3 次——**重试策略参数化、服务端可下发**。
- **流式快照间隔配置**（`__TONGYI_SSE_SNAP_INTERVAL_CONFIG__`）——推断用于长生成任务的周期性快照/断线续传 🔗。
- 官方产品特性：「流式输出的对话体验（逐字输出、思考过程可视化）」✅。
- markdown 渲染组件、长列表虚拟化、局部刷新 diff：无公开资料 ❓。

### 5.3 卡片体系：形态最丰富的一家，机制不透明

**形态清单**（官方描述 + sspai 万字实测，均有来源）：

1. **回答富卡**：答案结构化呈现 + 图表/视频推荐；
2. **深度研究卡**（2026-08 上新，「思考研究」研究级深度回答）；
3. **任务交付卡（Artifact 类）**：官方表述「直接交付网页/应用/PPT/Word/Excel/图片等格式的**成品文件，打开即用**」——以成品文件+内置预览呈现，而非聊天内实时代码画布 ✅（预览容器推断为 WebView 🔗）；
4. **订单/交易卡**：对话内生成订单卡，内嵌「支付宝 AI 付」支付闭环（实测买电影票全程 App 内完成，不跳飞猪）；
5. **服务入口引导卡**：政务/充话费等，点击进分步表单流；
6. 定时任务卡、语音通话纪要卡（纪要+录音回放）、智能体卡（广场 + 对话内 @唤起）；
7. 多媒体生成卡：HappyHorse 视频（说话即出片）、AI 小剧场（Live Photo）、Wan 3.0 视频、AI 生图、AI PPT、可视化板书；
8. **追问 chips**：首页场景化提示词有，**对话内上下文强相关的追问引导弱**——sspai 点名改进项（千问公认短板）。

**机制**：无官方披露 ❓。第三方分析（OpenTiny/华为云社区，2025-12）：千问生成式 UI 走「意图理解 → LLM 动态生成 → **DSL（JSON Schema 约束）** → 渲染引擎解析 → 交互回流」五步管线，AI 购物卡「流式渲染、可交互、不跳淘宝即可比价/加购/下单」🔗（单一第三方来源，置信度低）。结合 hybrid 基建推测：头部卡（订单/支付）原生模板 + 长尾服务卡 H5/动态模板双轨 🔗。

### 5.4 桌面端与 Web 端

- **Web 双栈**：国际 chat.qwen.ai = 阿里 CDN 自部署 SPA（`qwen-chat-fe` 0.3.11，无 Next.js 指纹，meta 自述支持 artifacts）；国内 qianwen.com = 阿里内部 npm（`@ali/qianwen-web` 4.8.6）发布——**千问 Web 是通义 Web 原班延续升级，非重写** ✅。
- **PC 客户端** v2.3.1（Win x64 / macOS universal，官方 CDN 直链 + Microsoft Store）；内核（Electron/Tauri）无披露 ❓。⚠️ 易混淆：**Qwen Code Desktop**（编码代理桌面端）2026-08 起转 Tauri——那是 Qwen Code，不是千问聊天客户端；B 端「千问办公 QwenWork」也是另一产品。

---

## 6. 横向洞察与对 PoLang 的启示

### 6.1 ADR-014 市场判断的交叉验证（本调研的直接任务）

| ADR-014 §6 原判断 | 验证结果 |
|---|---|
| 「(a) 无头部 App 用 WebView 渲染正文」 | ✅ **成立且强化**：ChatGPT 双端原生（Compose/SwiftUI）、豆包双端原生、Muse 客户端薄壳但正文仍是原生消息流（工件以页面/位图回显）。四家无一例外。 |
| 「ChatGPT 为 RN + react-markdown 式原生组件管线」 | ⚠️ **需修正**：移动端无 RN 证据——Android=Kotlin/Compose、iOS=Swift/SwiftUI（官方 JD 一致）；react-markdown 是 **web 端**栈；RN 说法疑与 react-native-streamdown 参考库混淆。**不影响 D1 决策**（正文原生），反而强化。 |
| 「(b) 头部 App 全员收敛到原生聊天流 + 沙箱 WebView 卡片/独立面板（Claude Artifacts、ChatGPT Apps widgets）」 | ✅ **成立**：ChatGPT Apps SDK = 沙箱 iframe + Web bundle + 桥（官方文档实证）；Muse = 服务端 Web 工件 + 无头浏览器渲图（极端云端变体）；豆包机制不明但产品形态一致。PoLang HTML_CARD 是同构路线。 |

**建议**：ADR-014 §6 市场调研条目按上表回写勘误（RN 表述改为「web 端 react-markdown / 移动端双端原生」），随下次 ADR 触碰原子同步。

### 6.2 消息模型：parts[] 是被 8 亿用户验证的形态

ChatGPT 实证线协议把消息建为 **`content.parts[]` + `metadata.*` 挂件 + append-only 补丁流**：流式只 append 到最后一个 part，重组范围天然收敛到单气泡；富挂件（引用等）独立通道追加。

对照 PoLang：13 值 enum + 平铺 nullable payload + if/else 分发链。当前痛点已自知（「抽分发器」TODO）。本次调研给出两条可选路径：
- **保守**：保持 enum，但把分发抽成 `type → renderer` 注册表（Map），消掉 if/else 链——改造小，双端可同步建模。
- **激进**：消息升格为 parts 列表模型（ChatGPT 形态），流式 append 天然局部化，卡片=part 的一种。改造大，建议仅在 chat 消息模型重构立项时采纳（与 KMP SSOT 收敛 `ChatMessage.kt` 注释里的既定方向合并考虑）。

### 6.3 卡片体系的三种流派与 PoLang 的位置

| 流派 | 代表 | 机制 | 适用前提 |
|---|---|---|---|
| A. 封闭原生模板 + 结构化数据 | ChatGPT 一方卡、豆包（推断） | 服务端 JSON/事件 → 客户端固定模板集合 | 卡片形态可枚举、迭代跟版本 |
| B. 沙箱 Web 容器 | ChatGPT Apps SDK、Claude Artifacts、**PoLang HTML_CARD** | HTML/bundle + 桥（PoLang 为零桥） | 长尾表达力、不发版长出新形态 |
| C. 服务端渲染工件 | Muse magic-moment | 云端无头浏览器渲图/录屏回显 | 算据全在云端、要跨端一致 |

- PoLang 已同时具备 A（CHART/MEDIA_RESULTS/TASK_CARD 原生模板）与 B（HTML_CARD），与头部 App 收敛形态**一致，无需改向**。
- Muse 的 C 流派对 PoLang 不适用（媒体红线 + 算据在端侧），但其「复杂视觉交给 Web 管线、简单结构交给模板」的分工思想与 ADR-014 L1/L2 双轨同构——外部再佐证。
- 千问补了一条中间形态信号：第三方分析（OpenTiny，单一来源、置信度低 🔗）指其生成式 UI 走「LLM → JSON Schema DSL → 渲染引擎」管线——四家中唯一接近「JSON-UI DSL」的线索；即便为真也只用于服务编排场景（AI 购物卡），非正文富内容。**PoLang D6「不自研 DSL→native 卡片引擎」不受影响**。
- **ChatGPT Apps SDK 的 UX 约束值得抄**：官方要求第三方卡片「观感像原生」（统一边距/圆角/主题感知/ARIA）+ 官方 UI 设计系统——对应 PoLang 的 L1/L2 样式权威收归 app（D5）与 token→CSS 变量注入，方向一致且对方做得更体系化（有官方设计系统站点）。

### 6.4 哨兵内嵌：流式富内容的抗乱序方案

ChatGPT 引用卡的**私用区哨兵 + metadata 定位 + 客户端换绑**是流式中途插入富元素且不破坏 markdown 解析的实证方案（比在 markdown AST 里塞自定义节点抗截断/乱序）。PoLang 当前富卡片全部走「独立消息槽」（tool_call → 独立 card message），**没有**正文内嵌富元素的需求压力；若未来要做「正文内引用相册实体/人物 chip」，哨兵方案是首选参考，届时 metadata 通道对应 PoLang 的消息 metadata 字段。

### 6.5 审批/安全 UI 原生外置：三家一致的外部佐证

Muse Sentinel 审批弹层在 App UI 而非对话流（防提示注入）；ChatGPT Instant Checkout 支付面板独立于消息流；PoLang 双形态 spec §7「审批动作条原生外置」+ Tier A 确认体系——三方殊途同归，**该决策无需再议**。

### 6.6 双形态卡（今日 spec）的对标输入

- **Fullpage 预览卡 + 全屏查看器** ↔ ChatGPT Canvas（独立面板）与 Apps SDK 卡片（iframe 内滚动）都选择「不占聊天流」呈现长内容；Muse 干脆把长内容做成独立页面工件。PoLang 的「预览卡渐隐 + 点击全屏」是移动端聊天流约束下合理的第三条路，无先例冲突。
- **任务卡端侧 L1 模板渲染** ↔ ChatGPT Agent 模式进度卡（原生时间线模板 + 事件流驱动）同构；Muse 任务卡因算据在云端走了工件路线。PoLang 端侧模板 + 状态 JSON + 节流重渲染（500ms 合帧）与 ChatGPT「事件流驱动原生模板」一致，闪烁问题对方同样用「整卡重绘」消化（无增量 DOM 补丁公开方案）——spec 中「P1 实测定夺是否需增量通道」的保守策略正确。
- **打字机节奏**：PoLang `StreamingPacingController`（50ms/字、CJK 步进、标点停顿）与业界通用做法（chunk 合帧、每 frame 至多一次重组）同向，且比 ChatGPT web 端「每 chunk 全量重解析」更稳——PoLang 这条已经领先 web 端实现，无需对标改动。
- **千问的 Artifact 形态**是「成品文件交付 + 打开即用」（网页/PPT/Word/Excel/图片），而非 ChatGPT Canvas 式聊天内实时代码画布——比 Canvas 轻。PoLang 的 `AGENT_EDIT_RESULT` / 脚本产物交付已是「成品交付」路线，同构；今日 spec 的 Fullpage 查看器则对应其「内置预览容器」。

### 6.8 千问的两个增量借鉴点

1. **SSE 重试/快照参数化下发**：千问把流式断线重试（指数→线性混合退避、上限 10s、3 次重试）与快照间隔做成服务端可配的页面级配置 ✅——PoLang 远程推理链路（Koog → api.polang.net 网关）可直接借鉴：把重试/续传参数收敛到网关配置下发，双端（Android/iOS）不用各自发版调参。长生成任务（工程师任务卡的多轮 tool_calls）恰是最需要断线续传的场景。
2. **对话内上下文追问 chips 是千问公认短板**（sspai 点名改进项）——PoLang 相册域数据在手（GalleryCapability 搜索结果、人物/时间聚合），若做「按人物过滤 / 换时间段 / 只看收藏」类强相关追问 chips，即形成对千问式泛化 chips 的差异化优势。

### 6.7 明确不建议跟进的

- **跨端 UI 框架**：四家移动端无一采用（豆包双端原生、ChatGPT 双端原生、Muse 未知、PoLang 双端原生）——Compose+SwiftUI 双栈是行业常态，不是债。
- **JSON-UI DSL**：ChatGPT 明确没做（两层体系替代）；ADR-014 D6「不自研 DSL→native 卡片引擎」获直接佐证。
- **服务端渲图（Muse 流派）**：与 ADR-008 媒体红线冲突，且 PoLang 算据在端侧，不适用。

---

## 附录：来源总表（按家分组）

**ChatGPT**：官方——openai.com/index/introducing-apps-in-chatgpt（Apps SDK，2025-10）· developers.openai.com/apps-sdk · github.com/openai/openai-apps-sdk-examples（outputTemplate/widget 机制原文）· openai.github.io/apps-sdk-ui · /introducing-canvas/（2024-10）· /introducing-chatgpt-agent/（2025-07）· /introducing-4o-image-generation/（2025-03）· help.openai.com 9237897（引用）· careers JD（Android Ad Formats / iOS Applied Foundations）；社区/逆向——funnelstory.ai 流式协议抓包（2025-09，SSE patch+哨兵）· github.com/terminalcommandnewsletter/everything-chatgpt（web 端 react-markdown 逆向）· thenewstack.io Apps SDK 分析（2025-10-08）· Blind iOS 栈讨论 · electronjs.org 名录（Windows=Electron）· HN macOS 移植讨论。

**豆包**：lynxjs.org（Trusted by 无豆包，2026-09-26 核实）· 豆包/字节招聘页（o.doubao.com / jobs.bytedance.com / BOSS 直聘）· seed.bytedance.com Seeduplex 博客（全文精读）· docs.volcengine.com Realtime 协议（2026-09-11）· 火山方舟结构化输出文档（beta，~2026-09-22）· 范绍贵 GTLC PPT（tool.lu/deck/W3 + scribd 存档）+ InfoQ 报道 · maimai 字节 Agent 卡片容器帖（2024-09-09）· 桌面 Chromium：钛媒体 2025-08 / 新浪财经 2025-04 / 腾讯新闻 2024-06 / WPS 社区逆向。

**Muse（渲染补充）**：mouse.dev/blog/muse-runtime-export（核心：6.8GB 运行时泄漏逆向，Spaces/magic-moment）· techcrunch.com Connect 汇总（2026-09-23）· theverge.com 上手（2026-09-10）· gizmodo.com 上手（工件形态）· daily.dev Mac/Spaces（TanStack+Bun+Cloudflare 佐证）· apkcombo.com（包信息）· about.fb.com/news/2026/09/introducing-muse-personal-ai-agent · LinkedIn MSL VP Eng 档案。

**千问**：一手页面直抓——qianwen.com 源码（`@ali/qianwen-web` 4.8.6 / SSE 重试与快照配置 / PC 客户端 v2.3.1 直链）· chat.qwen.ai 源码（`qwen-chat-fe` 0.3.11）· 应用宝包名页（com.aliyun.tongyi）· App Store（id6466723876「千问-原通义千问app」）· APKCombo（com.tongyi.intl v1.2.1）· Google Play（ai.qwenlm.chat.android）· 酷安官方描述；深度分析——sspai 万字实测（sspai.com/post/109365，2026-05-03）· OpenTiny 生成式 UI 分析（华为云社区 bbs.huaweicloud.com/blogs/470830，2025-12-15，DSL 管线唯一来源）；新闻/数据——新浪财经千问五项新功能（2026-08-07）· Microsoft Store 上架页 · daily.dev（Qwen Code Desktop 转 Tauri，2026-08-27）。

**PoLang 摸底**：代码级（ChatScreen.kt / ChatViewModel.kt / ChatMessageType.kt / MarkdownSegmenter.kt / StreamingPacingController.kt / HtmlCard.kt / EngineerTaskCard.kt / ChatView.swift 等，详见 git 状态）。
