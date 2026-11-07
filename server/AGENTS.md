# :server 模块

> **边界声明（Boundary Statement）**
> - 本文档仅承载 `server/` 模块的实现细节。
> - 产品目标与验收口径以 `PRODUCT.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 部署与运维细节见 `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md`。
> - API 契约与实现方案见 `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md`。

**模块定位**：`server/` 是 PoLang 的 **Ktor 后端单体服务**，独立 Gradle 工程，与 Android 客户端通过 Monorepo 管理。提供 AI 网关、账号体系、管理后台、推荐引擎、遥测收集、COS 对象存储等能力。

**主要维护者**：项目开发者

**阅读对象**：RD、CR、AI Agent

**版本**：0.9.21

**最后更新**：2026-11-07

**状态**：生效中 / 已上线

---

## 1. 模块概述

`server/` 是独立的 Ktor Gradle 工程（`rootProject.name = "picme-server"`），**不纳入 Android `settings.gradle.kts`**。通过 `./gradlew -p server` 独立构建和运行。

**核心职责**：
- **AI 网关**：`LlmProxy` + `ChannelRegistry` — 按模型自动路由到 Cloudflare AI Gateway 或腾讯 TokenHub；`stream=true` 请求 SSE 逐 chunk 透传（原样转发 stream/stream_options），usage 从流尾帧解析计费（含 `prompt_tokens_details.cached_tokens` 前缀缓存命中采集，`llm_call_log.cached_tokens`，2026-10-17）；非流式请求强制 `stream=false` 走完整响应体解析
- **账号体系**：`EmailService` + `AccountService` + `AppTokenAuth` — 邮箱注册、动态 Token、SHA-256 校验
- **管理后台**：`AdminRoutes` + `AdminViews` + `AdminQueries` — SSR HTML 运营后台（概览/用户/流量/渠道/设置/问题诊断/发包 + M2 推理追溯：调用流水/Agent 会话回放）
- **推荐引擎**：`RuleEngine` — 纯规则型场景推荐（规避算法备案）
- **遥测收集**：`TelemetryRoute` — 批量匿名事件写入 SQLite
- **COS 存储**：`CosService` — 腾讯 COS 上传/元数据/预签名 URL；APK 下载 URL 渠道路由（`apkPublicUrl(channel)`：debug 轨可经 `APK_DEBUG_PUBLIC_BASE` 指向北京轻量国内镜像，release 轨恒走 `cos.polang.net`）
- **限流**：`RateLimiter` — per-IP 令牌桶 + 日预算熔断
- **网页搜索**：`WebSearchRoute` + `WebSearchService`（websearch/）— 主聊天 `web_search` 工具上游：`POST /v1/web-search` 鉴权（X-App-Token / 访客 X-Device-Id，同 LLM 口径）→ DeepSeek Search API（`/v1/tools/search`，`need_content=true`，按次计费 5 元/千次成功请求）→ 服务端瘦身（Markdown 正文提取 https 配图 URL ≤3 条/结果 + 400 字符摘录 + ≤5 结果）回紧凑 JSON；key 走 env `DEEPSEEK_SEARCH_API_KEY`（空 = 路由 503 `web_search_unavailable` 降级，端侧能力层回不可用文案）；成功调用记 `llm_call_log`（model=`deepseek-web-search`，usage=null 成本列 0，按次成本见官方口径）
- **云端浏览器网关**：`BrowserRoute` + `browser/`（BrowserBridgeClient/BrowserConcurrencyRegistry/BrowserSessionStats）— `/v1/browser/*` 鉴权（X-App-Token）→ 每日配额 peek（open 成功才 allow 计费）→ per-user 并发=1 → 透传 xuxing browser-agent-bridge（30s 网关超时；bridge 不可达统一 503 `browser_unavailable` 且不计额度，域名结果 JSON `status` 原样透传）；M2（2026-10-09）增 `WS /v1/browser/ws` 帧流双向代理（App↔bridge 文本/二进制帧透传，鉴权/owner 校验同 HTTP 路由，失败以 WS close code 表达）；M2.5（2026-10-11）busy 自愈——open 撞占用时经 bridge `GET /session/{id}/status` 探测，仅 `session_expired` 判死后条件释放重试（`reaped_heal`），探测失败/其它状态保守 BUSY；M2.7（2026-10-11）WS 链路修复——代理应答 bridge Ping（回 Pong，OkHttp pingInterval 无 pong 判死）+ bridge→app 帧字节闸门（90KB/s 令牌窗口、窗口首帧无条件放行防全黑、容量 1 DROP_OLDEST 不排队，防 HK↔国内窄带积压堵死 pong/文本帧）
- **云端 Agent 会话域**：`agent/` 包（ADR-017/018，Koog JVM + 事件溯源 `agent_session`/`agent_event` 两表，`/agent/**` REST + SSE；BYOK X-User-Api-Key 请求级透传零留存）——M2（2026-10-17）工具桥双通道：SSE `tool_request` 帧下行 + `POST /agent/sessions/{id}/tool-results` 上行（`AgentBridgeRegistry` 挂起表 + `DeviceToolService` 端侧只读三件套适配，409 `tool_request_not_pending`）；M2.6（2026-10-17）per-user 跑轮并发=1（`AgentRoundGate` 内存租约，占用回 409 `round_in_flight`，占位在配额扣减之前）；M1（2026-10-21，混合架构投影通路）端侧结果出端过 shared CloudToolProjectionGate（fail-closed），LLM 可见描述只承诺投影（计数/摘要，不含路径/媒体清单）；OpenUI 线组件 6→7（+Image 相册照片图文混排，photo 仅许句柄投影 id（`local://scan/<id>#photos` → [{id,date}]），2026-10-24）；OpenUI 组件库 M2（2026-10-24）：`RenderOpenUiValidator` 白名单 7→14 组件（+Icon/Divider/List/Tabs/CheckBox/ChoicePicker/Slider——spec 标题「十三」系计数笔误，以 §2 枚举为准）+ Icon name 59 枚白名单正则级校验（`ICON_NAMES`，与 shared `OpenUiLibrary.iconNames` ↔ 工具描述 `ICON_NAMES_TEXT` 字面量副本同源，测试钉住）+ 描述加 7 组件签名/state 写入约定段（`$变量` 首行声明、交互组件绑定写法、裸字面量只读）/交互黄金示例 `GOLDEN_INTERACTIVE_PROGRAM`（companion const 测试同源钉住）；OpenUI 组件库 M3（2026-10-24）：白名单 14→21 组件（+Table/PieChart/LineChart/Callout/Markdown/Accordion/Switch，无新正则——本批无 Icon 名字面量类新攻击面）+ 描述加 7 组件签名/规则 6 交互组件清单补 Switch/图文报表黄金示例 `GOLDEN_REPORT_PROGRAM`（Table+PieChart+Callout 组合，companion const 测试同源钉住）；OpenUI 卡内设计语言第一刀（2026-10-25，`docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md`）：Text role 增 DISPLAY（stat tile 32sp 大数字，shared `OpenUiLibrary.textRoles` 同源）+ Icon 省略 colorRole 自动弱色引导 + BarChart >12 桶先聚合引导 + stat tile 黄金示例 `GOLDEN_STAT_TILE_PROGRAM`（companion const 测试同源钉住）；构图软警告 lint（2026-10-25）：`RenderOpenUiValidator.lintComposition`（DISPLAY >4 / Button >3 正则级计数，不拒卡；2026-10-30 +stat tile 未居中——DISPLAY+Row 分栏但全文无 `align = "center"` 时建议数字栏补居中）附在工具体「UI 已下发」回执后供 LLM 下次发卡改进（明示无需重发防双卡），gate 发射点只走硬校验不受影响（测试钉住）；align 结构治理（2026-10-30，spec 已随交付清理，git 历史可查）：Row/Column/Card 补 `align` prop（词汇/语义/缺省严格对齐官方 thesysdev/openui Stack——交叉轴五值 start|center|end|stretch|baseline、缺省 stretch = 官方 CSS 缺省「子项铺满交叉轴」；server 半信任不做 per-prop 校验、端侧 shared schema 拦非法值）+ 工具描述 stat tile 黄金示例三栏改 `align = "center"` + 主按钮（variant=primary）端侧渲染 full-width；Image 真实比例+点击大图（2026-10-25）：工具描述 aspect 改「省略时端侧按 MediaStore 元数据真实比例渲染（竖屏图不再被横框拦腰，推荐省略），强制造型才显式传」，端侧 resolver 回填 aspectRatio（API<29 BitmapFactory 边界解码兜底；元数据宽 sanity 界 [0.3, 3.0] 不套 LLM 窄 clamp）、点击经宿主动作 OpenPhoto 落 PhotoEditor；OpenUI 官方组件库全量补全（2026-10-25）：白名单 21→89（官方 82 适配 81 + 本仓既有 8，官方 Col 列式刻意不收）+ Image src https 硬校验/photo·src 二缺一（src 限任意组件 `src=`/`{src:}` 形态同规）+ 工具描述全量 89 签名（表单 name 键控 @Set($name) 直写约定段、元素形状组件不独立使用注明）+ 白名单 89 项字面量测试同源钉住；OpenUI 流式预览工具期形态（2026-10-26，ToolCallDelta 路径）已随 2026-11-06 唯一化物理删除（`OpenUiToolCallStreamTracker`/`OpenUiProgramStreamExtractor` 退役，turn-native 改走 content TextDelta 前缀预览）；图文组合固化（2026-10-30，spec 已随交付清理，git 历史可查）：ImageText/ImageTextLarge 签名加 `photo?`（相册投影 id `photos[i].id`，与 src 二选一、photo 优先，禁字面量数字）+ 工具描述增图文排版食谱段（场景→组件选型四则 + float wrap 环绕不支持声明替代形态 + 禁 Row 裸拼 Image+Text）+ 图文排版黄金示例 `GOLDEN_PHOTO_TEXT_PROGRAM`（companion const 测试同源钉住）+ lint 软警告第四条（Row 实参区间内 Image+Text 裸拼反模式提示改 ImageText/ImageTextLarge——括号配对扫描截取 Row 实参区间，纯多图并排 Row 食谱合法形态不误报）+ 单卡图片上限口径补 Image 调用区间外的命名 photo 键计数（Image(photo=…) 区间内不双计）；通用布局原语批次（2026-10-30，spec 已随交付清理，git 历史可查；用户定调「通用布局组件而不是业务组件」）：白名单 89→92（+Grid/Box/Spacer 本仓扩展通用布局件，官方 openui 通用布局仅 Stack、无 weight/Grid/Box/Spacer）+ 工具描述 Row 签名加 weights?（CSS flex-grow 比例分栏）/Grid/Box/Spacer 签名 + 「通用布局食谱」段（四宫格=Grid(columns=2)、左图右文=Row(weights=[1,2], align)、图上叠字=Box(align=bottomStart)、留白=Spacer，ImageText 系降级为便捷糖）+ 通用布局黄金示例 `GOLDEN_LAYOUT_PROGRAM`（companion const 测试同源钉住）+ lint 第四条修订（Row 内 Image+Text 裸拼仅无 weights 时警告，weights 是认可写法）；OpenUI 经典组合版式批次（2026-10-31，spec 已随交付清理，git 历史可查；用户定调「布局丑问题依旧没有解决，应该做一些经典的布局组件」）：白名单 92→95（+StatRow/StatItem/Banner——StatRow = stat tile 组件化、Banner = 通栏题图横幅含 photo 相册投影）+ ListItem 实渲染化扩展（photo/icon/trailing，经典 ListTile 行）+ VisualCardBlock/VisualCardItem +bgPhoto（题图卡相册投影）；图片计数口径扩 bgPhoto 键 + 对象字段冒号形态（`{bgPhoto: …}` 同 `photo =` 计位图）；lint 第三条 stat tile 警告文案引导换 StatRow；工具描述增经典组合版式签名段/食谱五则 + 黄金示例 `GOLDEN_CLASSIC_PROGRAM`（companion const 测试同源钉住）；相册 APP 组合方式批次（2026-10-31，无 spec，结论直落设计语言文档 v8 §4.6）：白名单 95→97（+PhotoGrid 方形照片网格/AvatarRow 人物头像条，CardHeader 扩 actionLabel/action）；图片计数口径扩 photos 数组括号配对扫描（防 `photos[0].id` 嵌套括号截断）；lint 第五条（Grid 内 Image≥2 引导换 PhotoGrid）；工具描述增相册 APP 构图规则食谱段 + 黄金示例 `GOLDEN_PHOTO_PROGRAM`（companion const 测试同源钉住），GOLDEN_LAYOUT_PROGRAM 四宫格照片墙同步改 PhotoGrid（Grid 只留图表/混合内容）；「查看全部」点击无效真机回归修复（2026-10-31）：根因 = 黄金示例只传 actionLabel 不传 action——GOLDEN_PHOTO_PROGRAM/食谱/签名段全部改 actionLabel+action 成对（`action = @ToAssistant("查看全部 <分组语境>")`，@ToAssistant 端侧自此实路由上行为用户消息，原为 M1 只记日志）+ lint 第六条（CardHeader 有 actionLabel 缺 action → 引导显式配对；端侧另有同文案兜底，OpenUiHeaderActionTest 钉住）；OpenUI action 体系审查收口（2026-11-01，fix/openui-action-system review Y1-Y7，0.9.8）：QUOTED_ACTION 白名单补 Run|Action（与 shared `OpenUiContract.ENGINE_ACTIONS` 闭集同源）+ @OpenUrl 字符串字面量首参 https 硬校验（`OPEN_URL_LITERAL` 原文扫描，与 Image src https 同族；表达式形态 `@OpenUrl($url)` 不拦，端侧 scheme 闸放行 http/https 为刻意分层）+ lint 第六条扩 ListItem（命名键形态）/ListBlock（对象字段形态）——actionLabel 缺 action 引导显式配对（端侧 labeledActionsOrDefault 兜底合成 @ToAssistant）；工具描述签名段 ListItem/ListBlock 注明「有 actionLabel 必配 action」同 CardHeader 口径；OpenUI 回合原生（turn-native，2026-11-01，spec 已随交付清理，git 历史可查）：`X-GenUI-Format: openui` 头语义升级——该线**不注册任何 GenUI 发卡工具**（render_ui_openui 退役不注册、render_html 从工具清单过滤，HTML 改以 Html 组件形态入程序树；shared `OpenUiLibrary` 组件清单 100→101 + `RenderOpenUiValidator` 白名单同步 +Html），系统提示词换 `OpenUiTurnNative.SYSTEM_PROMPT`（响应即程序契约：整个终结回复必须是一颗 OpenUI Lang 1.0 程序，纯文本也写 `root = Markdown("…")`；DSL 规则与 `OpenUiPromptAssets.DSL_RULES` 同源复用，尾部内嵌 101 组件全量清单——引用 `RenderOpenUiValidator.ALLOWED_COMPONENTS` 生成而非手抄，白名单/Icon 违规文案「全量清单见系统提示词」由此兑现）。配套四件：① `RenderOpenUiValidator.validateProgramToError`（回合原生校验入口——结构校验 + `OpenUiLayoutValidator` 布局门禁，先 normalize 再 validate；`validateToError`/`errorText` 加 `turnNative` 双分支，turn-native 口径文案「逐条修正后重新输出完整程序」）；② `OpenUiContentStreamTracker`（content TextDelta 纯文本累计 → program 前缀节流发射，≥48 字符增量且 ≥80ms 首发免时间窗，`onRoundStart` 轮次重置——响应即程序无需 JSON 转义剥离；经 SSE `kind=openui_delta` 瞬态帧旁路下行，不落 agent_event）；③ `ProgramGuard`（终结程序守卫，每轮新实例——终结文本经 validateProgramToError 判定非法 → `cloudAgentStrategy` `correctInvalidProgram` 节点携带逐条错误清单回环重发，上限 MAX_CORRECTIONS=1 防死循环）；④ `OpenUiTurnNative.ensureValidProgram`（终结兜底变换，「端上永远收到合法树」双保险之二——合法 normalize 后原样放行 / 非法兜底包 `root = Markdown("…")` 程序 / 包装自身复验失败退硬编码错误卡）。OpenUI 间距词汇统一（2026-11-01）：DSL_RULES 统一官方七档 t-shirt 刻度（`none|xs|s|m|l|xl|2xl` → 0/4/8/12/16/24/32dp）+ 新增 7.6 间距与节奏规则段（默认不写/一卡≤2档/分区>组内/连续 Spacer 合并/Card 自带 16dp 内边距边缘不贴 Spacer），legacy 别名 COMPACT|STANDARD|COMFORTABLE 仅兼容存量持久化卡、prompt 不教；`OpenUiLayoutNormalizer` N2 降档改显式映射表（xs→s、xl|2xl→l，`SPACER_RANK`/`QUOTED_SIZE` 补 2xl）；OpenUI 动作三通道官方对齐（2026-11-01，0.9.13）：工具描述三通道段官方术语重写——访问 API 改教官方 `Mutation("工具名", {args})` 语句 + `@Run(ref)`（结果 {status,data,error} 可绑定、失败中断 Action 链；写工具白名单 favorite_media/unfavorite_media），页面跳转 = library 自定义动作 @Navigate（目的地闭集七值），组合 = Action([...]) 整链；`RenderOpenUiValidator` `ALLOWED_CALLS` 补 Mutation/Action 两官方语言级调用（此前 `Action([...])` 组合写法会被误拒「不在白名单」）+ `QUOTED_ACTION` 词表删 Dispatch 保留 Navigate（注册自定义动作名，与 shared `OpenUiLibrary.customActions` 字面量副本同源）；OpenUI 大字面量 StackOverflowError 修复（2026-11-04，勾股定理 HTML 卡事故：`Html("…")` 内嵌整页 HTML 经终结校验时 `"([^"\\]|\\.)*"` 交替-星号正则 GroupCurly 逐字符递归爆栈——512KB 栈实测 2288 字符字面量即崩，拆环写法把递归深度换成转义数仍炸；Finish(reason="error: StackOverflowError") 无 assistant 落库）：字符串字面量处理全量改 `OpenUiStringScan` 字符级扫描（O(n) 零递归，前缀正则只定位开引号 + `literalAt` 扫描本体，语义与旧正则逐字对齐有测试钉住），涉及 `RenderOpenUiValidator`（STRING_LITERAL 剥离 / QUERY_CALL / SRC_STRING / OPEN_URL_LITERAL 尾捕获 / TEXT_DISPLAY_CALL 改组件树递归计数）与 `OpenUiLayoutNormalizer.stripStrings`；端侧同宗同修：`OpenUiProgramSummary`（looksLikeProgram/extractFallbackSummary 同型正则副本）与 `HtmlCardSanitizer`（`.*?</script>` 等懒惰点星整段剔除同属 GroupCurly 递归，tag 体超长/缺闭合扫到全文尾即崩，改单次手动扫描，无闭合保留语义不变）；OpenUI 本地视频批次（2026-11-04，0.9.19）：`RenderOpenUiValidator` 白名单 102→103 补 `Video(photo?, aspect?, src?)`（签名对齐 Image——photo 仅许 `#videos` 句柄投影 id 禁字面量数字、src 仅 https 公网视频、二缺一必给；单卡视频上限 MAX_VIDEO_COMPONENTS=4 ExoPlayer 内存护栏，独立于图片位图口径——Video 的 photo/src 字面量均不计入位图数，src https 校验按调用区间分流视频/图片文案；位置参数校验与 Image 同构）+ LOCAL_HANDLE_FORM fragment 闭集 +`videos`；`DeviceToolService.searchMedia` 增第 7 参 `mediaType`（'photo' 默认/'video'/'all'，Koog schema 必填空串同族口径）；DSL_RULES（现 `OpenUiPromptAssets`）增 Video 签名 + §4 增 `#videos` 取数写法段（前提是 mediaType=video/all 搜索）；**GenUI 唯一化（2026-11-06，turn-native only）**：/agent 路由面不再按 `X-GenUI-Format` 分流（旧头被忽略），恒走 OpenUI 回合原生——不注册任何 GenUI 发卡工具（render_ui/render_ui_openui/render_html 全退役，HTML 以 Html 组件形态入程序树），system prompt 恒为 `OpenUiTurnNative.SYSTEM_PROMPT`，`AgentRunner` 收编双线参数（forwardContentDeltas/finalMessageTransformer/onHtmlCard 删除，content delta 无条件透传 + ensureValidProgram 终结落流前内联调用 + ProgramGuard 无条件启用），actionGate/argsTransformer 接线与假发卡守卫 CardClaimGuard 同步退出（render_html 的 MAX_HTML_CHARS/withHtmlCardSink/HtmlCard sink 一并摘除，`AgentEventPayload.HtmlCard` 类保留供历史事件回放解码）；退役六件（RenderUiToolService/RenderUiValidator/RenderOpenUiToolService/CardClaimGuard/OpenUiToolCallStreamTracker/OpenUiProgramStreamExtractor）与 transformArgs 死代码、连带测试已物理删除（2026-11-06，0.9.20）；分层 runtime M1.5+M2（2026-11-07，spec《2026-11-07-layered-agent-runtime-design》）：OpenUI 白名单/prompt 资产真引用化——`RenderOpenUiValidator` ALLOWED_COMPONENTS/ICON_NAMES/ALLOWED_CALLS/QUOTED_ACTION/LOCAL_HANDLE_FORM 词表与 `OpenUiPromptAssets.ICON_NAMES_TEXT` 全部改引用 shared `OpenUiLibrary`/`OpenUiContract`（103 组件/59 图标/动作词表/fragment 闭集字面量副本全收口，正则 pattern 逐字节等价钉住）+ 工具桥目录驱动——`DeviceToolService` @Tool 反射方法退役，`CatalogBridgeTool` 按 shared `CloudAgentToolCatalog` 循环生成编程式工具（descriptor/参数/端侧缺省一处声明，server_time 裁定留服务端本地不入目录）
- **Skill 中心**（2026-11-07，spike 转正）：`SkillCenter` + `SkillTools` + `SkillRoute` + `skills/` — 技能（SKILL.md 指令包，回答「怎么做」；与 tool 互补）磁盘资产管理，Koog 无框架级 Skill 抽象（1.3.0 全 artifact 零 skill 类实证），`load_skill` 走 Koog 官方 Tool 扩展点（@Tool annotation 同 server_time 范式），skill 正文经 Observation 进上下文，AgentRunner/策略层零改动；渐进披露——system prompt 只注清单段（name+description，`SkillCenter.promptSection()`），正文按需加载；`skills/` 进 installDist 分发包（systemd WorkingDirectory 下缺省相对路径命中，env `POLANG_SKILLS_DIR` 可覆盖），**仓库 server/skills/ 是生产 skill SSOT**（deploy.sh rsync --delete 语义，后台直改 VPS 会被下次部署覆盖）；管理后台 `/admin/skills*` CRUD + LLM 视角预览（清单行 + load_skill 返回；v2 2026-11-07 依 Ardot 设计稿重排：列表统计行/双栏编辑实时预览/独立预览页三 Tab〔LLM 视角·原文·渲染预览，渲染走 commonmark `AdminViews.renderMarkdown` escapeHtml 防注入，编辑页实时渲染为内联 vanilla JS 零依赖〕），保存/删除即热更新（每次调用重扫盘，零部署零重启）；端侧下载端点 `GET /v1/skills[/{name}]`（端侧 load_skill 执行器为后续里程碑）；首个 skill = html-card（Html 组件 HTML 撰写规范，自 DSL_RULES/HtmlCardSanitizer 约束抽取）
- **用户问题上报**：`IssueReportRoute` — 脱敏后入库并自动同步 GitHub issue

---

## 2. 工程结构

```
server/
├── build.gradle.kts              # Kotlin 2.3.10（随 Koog 1.3.0 元数据升级）+ Ktor 3.0.3 + Exposed 0.55.0 + SQLite
├── settings.gradle.kts           # rootProject.name = "picme-server"
├── src/main/kotlin/com/mamba/picme/server/
│   ├── Application.kt            # Ktor 入口：插件装配（Routing/ContentNegotiation/Auth/StatusPages/CallLogging）
│   ├── config/
│   │   ├── AppConfig.kt          # 环境变量读取（非 HOCON，无 application.conf）
│   │   └── SettingsService.kt    # server_setting 内存快照（额度默认值/白名单，热路径零 DB 读）
│   ├── routes/
│   │   ├── HealthzRoute.kt       # GET /healthz — 存活探活
│   │   ├── RecommendRoute.kt     # POST /recommend — 场景推荐
│   │   ├── TelemetryRoute.kt     # POST /telemetry — 遥测收集
│   │   ├── AuthRoute.kt          # POST /auth/email/{send,verify}、GET /auth/quota、DELETE /auth/account、DELETE /guest/device
│   │   ├── ClaudeChatRoute.kt    # POST /v1/claude-chat、/v1/claude-deliver、GET /v1/claude-engineer/available
│   │   ├── ClaudeToolResultRoute.kt # POST /v1/claude-tool-result — App tool 结果回传
│   │   ├── IssueReportRoute.kt   # POST /v1/report-issue — 用户问题上报
│   │   ├── SkillRoute.kt         # GET /v1/skills[/{name}] — Skill 中心下载端点（2026-11-07；清单/正文，端侧 skill 资产来源）
│   │   ├── BrowserRoute.kt       # /v1/browser/{open,action,close} + GET /v1/browser/frame + WS /v1/browser/ws — 云端浏览器网关
│   │   ├── DownloadRoute.kt      # GET /download — 资源下载 + iOS 安装（/download/ios、manifest.plist、udid 注册）
│   │   ├── AppLatestRoute.kt     # GET /api/app/latest — OTA 版本查询（COS metadata 通道）
│   │   └── WebSearchRoute.kt     # POST /v1/web-search — 网页搜索网关（DeepSeek Search API）
│   ├── browser/
│   │   ├── BrowserBridgeClient.kt    # xuxing bridge HTTP/WS 客户端（X-Bridge-Token，available 探测 + wsUrl 拼 WS 地址）
│   │   ├── BrowserConcurrencyRegistry.kt # per-user 并发=1 内存租约（默认 15min，不变式：> bridge hardCap 10min 防活跃会话被 sweep 双活；重启即清，条件释放防误删新租约）
│   │   └── BrowserSessionStats.kt    # 会话统计（管理后台概览；写故障隔离不破坏响应契约）
│   ├── auth/
│   │   ├── AccountService.kt     # 账号 CRUD + token 生成/校验
│   │   ├── AppTokenAuth.kt       # X-App-Token 认证插件
│   │   ├── EmailService.kt       # 验证码发送（Resend API，按 lang 渲染五语模板，缺省中英双语）
│   │   ├── EmailLanguage.kt      # 邮件语言枚举（EN/zh-CN/zh-TW/ES/FR + 双语缺省）+ BCP-47 标签归一化
│   │   ├── GuestService.kt       # 访客设备（anonymous_device）额度管理
│   │   └── AiEngineerWhitelistService.kt # AI 工程师交付白名单
│   ├── admin/
│   │   ├── AdminRoutes.kt        # /admin/** 路由注册（含 /admin/diagnosis 问题诊断页 + M2 推理追溯三路由：/admin/llm/calls、/admin/agent/sessions[/{id}]）
│   │   ├── AdminAuth.kt          # ADMIN_TOKEN + cookie 认证
│   │   ├── AdminViews.kt         # kotlinx.html SSR 页面（含调用流水/Agent 会话回放页）
│   │   └── AdminQueries.kt       # 运营数据查询（含 llmCalls 游标分页查询 / agent 会话与事件回放查询）
│   ├── issue/
│   │   ├── IssueReportService.kt # 问题上报存库 + GitHub 同步
│   │   ├── IssueSanitizer.kt     # 隐私脱敏
│   │   └── GitHubIssueClient.kt  # GitHub REST API 客户端
│   ├── llm/
│   │   ├── ChannelConfig.kt      # 供应商配置（baseUrl/apiKey/model/routing）
│   │   ├── ChannelRegistry.kt    # 多供应商注册表
│   │   ├── ChannelRepository.kt  # 配置持久化/加载
│   │   ├── ChannelBalanceService.kt # 渠道上游余额查询/缓存
│   │   ├── LlmProxy.kt           # 流式代理 + 上游路由
│   │   └── LlmRoute.kt           # /v1/chat/completions 端点
│   ├── agent/                    # 云端 Agent 会话域（ADR-017/018，Koog JVM + 事件溯源）
│   │   ├── AgentEvent.kt         # 事件模型：六型载荷（HtmlCard 发射路径已随 render_html 退役 2026-11-06，类保留供历史回放解码）+ source/role/parentId 三维 + payload 编解码
│   │   ├── AgentTables.kt        # agent_session / agent_event 表（事件只追加，id 即事件序）
│   │   ├── AgentSessionRepository.kt # 会话生命周期状态机（running/…/finished/error；无自动终结——终态仅 /finish 与跑轮非取消异常，端断线刻意留 running，2026-11-07 语义注记见 ADR-018 §6）
│   │   ├── AgentEventRepository.kt   # 追加/回放/pending action 不变式查询
│   │   ├── AgentProjection.kt    # 事件流 → LLM 视图投影（Condenser 最小实现，策略可替换）
│   │   ├── AgentExecutorFactory.kt # Koog 执行器组装（2026-11-07 分层 runtime M1 起委托 shared `RemoteModelFactory.createKoogExecutor`——capabilities/钳制/thinking/maxTokens 端云同源；本类仅剩 CF_AIG 改写 cf-aig-authorization 与 AgentLlmTarget→RemoteModelConfig 映射）
│   │   ├── AgentLlmTarget.kt     # 运行目标解析（网关渠道 modelMap 映射 / BYOK 透传）
│   │   ├── ServerTools.kt        # 服务端工具（server_time；render_html H5 卡通道已退役 2026-11-06 GenUI 唯一化）+ pending 重放派器
│   │   ├── ProgramGuard.kt       # turn-native 终结程序守卫（2026-11-06 起无条件启用）：终结文本非合法 OpenUI 程序（validateProgramToError）→ 策略层 correctInvalidProgram 节点纠正（上限 MAX_CORRECTIONS=1）
│   │   ├── OpenUiPromptAssets.kt # OpenUI prompt 资产（DSL_RULES + ICON_NAMES_TEXT + 黄金示例，OpenUiTurnNative.SYSTEM_PROMPT 与校验器/测试同源引用；2026-11-07 M1.5 起 ICON_NAMES_TEXT 派生自 shared OpenUiLibrary.iconNames，DSL_RULES 随之非 const——GenUI 发卡工具退役后无 annotation 消费方）
│   │   ├── OpenUiContentStreamTracker.kt   # turn-native（2026-11-01）：content TextDelta 纯文本累计 → program 前缀节流发射（≥48 字符且 ≥80ms，onRoundStart 轮次重置，复用 openui_delta 帧）
│   │   ├── OpenUiTurnNative.kt   # turn-native（2026-11-01）：SYSTEM_PROMPT（响应即程序契约 + DSL_RULES 同源 + 内嵌组件全量清单引 ALLOWED_COMPONENTS 生成，103 项 @0.9.19）+ ensureValidProgram 终结兜底变换（normalize 放行 / Markdown 包装 / 硬编码错误卡）
│   │   ├── RenderOpenUiValidator.kt # OpenUI 程序结构校验（组件白名单 103 项 @0.9.19 + Icon 59 枚 + lint 软警告；turn-native 校验入口 `validateProgramToError` 供 ProgramGuard/ensureValidProgram 双消费）
│   │   ├── OpenUiLayoutNormalizer.kt # 无声矫正 N1-N4（图文 Row 补 weights/连续 Spacer 合并/单子裸容器解包/stat 数字栏居中；2026-11-06 起消费点 = ensureValidProgram 落流前 normalize）
│   │   ├── OpenUiLayoutValidator.kt  # 结构门禁 R1-R4（Card 嵌套≤2/容器链≤4/通栏签名件同层去重/图表禁横向分栏/Grid 照片墙升硬，违规 Observation 回灌）
│   │   ├── OpenUiCallTree.kt     # OpenUI 程序组件调用树字符级扫描器（span 锚定原文供 Normalizer 无损改写；残缺程序返回 null 放行不误杀）
│   │   ├── OpenUiStringScan.kt   # 字符串字面量字符级扫描（2026-11-04 SOE 修复：O(n) 零递归替代 GroupCurly 递归正则，validator/normalizer 共用）
│   │   ├── AgentBridgeRegistry.kt # 工具桥挂起表（requestId→Deferred，断线 failAll 清理）
│   │   ├── AgentRoundGate.kt     # per-user 跑轮并发=1 内存租约（M2.6；故意无 TTL，一轮最长 20 迭代×(LLM+120s 工具)，重启即清）
│   │   ├── DeviceToolService.kt  # 端侧只读三件套云端桥（tool_request 帧 + 120s 挂起；2026-11-07 分层 runtime M2 起**目录驱动**：@Tool 反射方法退役，CatalogBridgeTool 按 shared CloudAgentToolCatalog.localTools() 生成编程式 Tool<JsonObject,String>——LLM 可见 schema（名称/描述/参数集）自 descriptorFor 派生，桥层零工具知识、args 原样入帧；M1 起描述只承诺投影面、工具名/error: 前缀真引用 shared CloudAgentToolContract const；search_media display 参数（2026-10-24）：'true'=端侧透出横滑图集卡，取数/统计一律 'false'）
│   │   ├── CloudAgentStrategy.kt # 单跑 ReAct 策略的云端配置组装（2026-11-07 分层 runtime M1 起图结构收口 shared `poLangSingleRunStrategy` 配置化工厂：流式节点 + correctInvalidProgram 节点——2026-11-06 起 ProgramGuard 无条件启用，出边 = 程序校验 → finish；假发卡节点已摘除）
│   │   ├── AgentRunner.kt        # 一轮执行：投影→落流→pending 重放→Koog loop→Finish（2026-11-06 GenUI 唯一化收编：删 forwardContentDeltas/finalMessageTransformer/onHtmlCard/actionGate/argsTransformer 接线，content delta 无条件透传 + ensureValidProgram 终结落流内联 + ProgramGuard 无条件；2026-11-03 回合用量改从流式 End 帧 metaInfo 累加）
│   │   ├── SkillCenter.kt        # Skill 中心（2026-11-07）：SKILL.md 磁盘资产管理——frontmatter 解析/名称闭集+canonical 双闸防穿越/32K 截断/每轮重扫热更新；upsert/delete 写通路（后台 CRUD）；promptSection() 清单段注入
│   │   ├── SkillTools.kt         # load_skill Koog 工具（@Tool annotation 同 server_time 范式；Koog 无框架级 Skill 抽象，走官方 Tool 扩展点）
│   │   ├── TurnUsage.kt          # 回合级 LLM 用量累计（done 帧 elapsed_ms/usage 数据源；流式 End 帧 metaInfo 与非流式 onLLMCallCompleted 两路互斥累加）
│   │   ├── ToolArgsWireJson.kt   # 工具参数 wire JSON 合规转义（Koog 1.3.0 私有 JSON 树 toString 零转义缺陷修复，replayPending 回放同径）
│   │   └── AgentRoutes.kt        # /agent/** REST + SSE（BYOK X-User-Api-Key 零留存）
│   ├── recommend/RuleEngine.kt   # 规则查询 + 参数组装
│   ├── cos/CosService.kt         # COS 预签名 URL
│   ├── ratelimit/RateLimiter.kt  # 内存令牌桶 + 日预算
│   ├── analytics/
│   │   ├── UsageRecorder.kt      # LLM 调用日志记录
│   │   └── TokenUsage.kt         # Token 用量解析
│   └── db/
│       ├── Db.kt                 # HikariCP + Exposed 数据库连接
│       ├── Tables.kt             # Exposed Table 定义（含 ReportedIssues）
│       └── Migrations.kt         # Schema 创建 + seed 幂等加载
├── skills/                       # Skill 中心技能资产（2026-11-07）：<skill-name>/SKILL.md（frontmatter name+description + markdown 正文）；进 installDist 分发包，生产 SSOT
│   └── html-card/SKILL.md        # 首个技能：Html 组件自包含 HTML 沙箱卡撰写规范
├── migrations/                   # 迁移文件以 server/migrations/ 目录为准
│   ├── 001_init.sql
│   ├── 002_llm_call_log.sql
│   ├── 003_llm_channel.sql
│   ├── 004_llm_channel_default_model.sql
│   ├── 005_account_token_plain.sql
│   ├── 006_account_soft_delete.sql
│   ├── 006_llm_call_log_platform.sql
│   ├── 007_server_setting.sql
│   ├── 008_llm_channel_balance.sql
│   ├── 009_drop_diag_jobs.sql
│   ├── 010_anonymous_device_platform.sql
│   ├── 011_browser_session.sql
│   ├── 012_agent_session.sql     # 云端 Agent 会话域（ADR-018）
│   ├── 013_llm_call_log_cached_tokens.sql  # prompt 前缀缓存命中列（2026-10-17）
│   └── seed_rules.sql            # 初始推荐规则
├── src/test/kotlin/              # 测试基建（下列为示意，实际 75 个 *Test.kt + 4 个辅助类）
│   ├── ChannelRepositoryTest.kt
│   ├── AdminRoutesTest.kt
│   └── TokenUsageTest.kt
├── .env.example                  # 环境变量模板
├── deploy.sh                     # 构建 + rsync 部署
├── deploy-switch.sh              # 服务器端蓝绿切换 + 自动回滚
├── run-local.sh                  # 本地开发便捷脚本
├── picme-api.service             # systemd unit
├── OPENCLAW_DEPLOY.md            # OpenClaw 一键发布指南
└── README.md                     # 模块级 README（精简，指向详细文档）
```

---

## 3. 路由清单

| 方法 | 路径 | 优先级 | 状态 | Auth | 说明 |
|------|------|--------|------|------|------|
| GET | `/healthz` | P0 | ✅ | 无 | 存活探活 |
| POST | `/recommend` | P0 | ✅ | X-App-Token | 场景标签 → 参数包 |
| POST | `/telemetry` | P0 | ✅ | X-App-Token | 批量匿名遥测 |
| POST | `/v1/chat/completions` | P0 | ✅ | X-App-Token | LLM 代理（流式 SSE） |
| POST | `/chat/completions` | P0 | ✅ | X-App-Token | 旧路径兼容 |
| POST | `/agent/sessions` | P1 | ✅ | X-App-Token | 云端 Agent 建会话（channel=gateway\|byok，ADR-018） |
| GET | `/agent/sessions` | P1 | ✅ | X-App-Token | 我的云端会话列表 |
| GET | `/agent/sessions/{id}/events` | P1 | ✅ | X-App-Token | 事件流分页回放（after/limit） |
| POST | `/agent/sessions/{id}/messages` | P1 | ✅ | X-App-Token | 跑一轮（SSE 事件帧；BYOK 经 X-User-Api-Key 透传，零留存；per-user 并发=1，占用回 409 `round_in_flight` 不耗配额） |
| POST | `/agent/sessions/{id}/tool-results` | P1 | ✅ | X-App-Token | 端侧工具结果上行（409 tool_request_not_pending；ADR-018 工具桥） |
| POST | `/agent/sessions/{id}/finish` | P1 | ✅ | X-App-Token | 关闭会话（终态） |
| POST | `/auth/email/send` | P0 | ✅ | 无 | 发送验证码 |
| POST | `/auth/email/verify` | P0 | ✅ | 无 | 校验码换 token |
| GET | `/admin/**` | P1 | ✅ | ADMIN_TOKEN | 管理后台 SSR |
| GET | `/admin/devices` | P1 | ✅ | ADMIN_TOKEN | 未注册设备列表（anonymous_device） |
| GET | `/admin/devices/{id}/raw` | P1 | ✅ | ADMIN_TOKEN | 设备 id 复制（返回完整 device_id） |
| POST | `/admin/devices/{id}/delete` | P1 | ✅ | ADMIN_TOKEN | 删除单条设备访客记录 |
| GET | `/admin/settings` | P1 | ✅ | ADMIN_TOKEN | 全局额度默认值（free/guest）表单 + AI 工程师白名单区块（`#whitelist`） |
| POST | `/admin/settings` | P1 | ✅ | ADMIN_TOKEN | 更新全局额度默认值 |
| POST | `/admin/settings/whitelist` | P1 | ✅ | ADMIN_TOKEN | 添加 AI 工程师白名单邮箱 |
| POST | `/admin/settings/whitelist/revoke` | P1 | ✅ | ADMIN_TOKEN | 移除 AI 工程师白名单邮箱 |
| GET | `/admin/diagnosis` | P1 | ✅ | ADMIN_TOKEN | 用户上报问题列表（状态流转 / 手动同步 GitHub） |
| GET | `/admin/llm/calls` | P1 | ✅ | ADMIN_TOKEN | 调用流水（llm_call_log id 倒序游标 `before`，单页 50，满页才给下一页链接防末页死循环；`status`/`provider`/`model`/`account_id` 筛选；非法 `before`/`account_id` 回 400） |
| GET | `/admin/agent/sessions` | P1 | ✅ | ADMIN_TOKEN | Agent 会话列表（seq 倒序游标 `before_seq`，单页 50；`account_id` 筛选；非法游标/参数回 400；stalled = running 且最后事件 >30min，状态列直显 stalled 不显示 running 徽标，2026-11-07） |
| GET | `/admin/agent/sessions/{id}` | P1 | ✅ | ADMIN_TOKEN | 会话事件回放（事件 id 升序 `after` 游标，单页 200；未知 sessionId 回 404） |
| POST | `/admin/users/{id}/reset-quota` | P1 | ✅ | ADMIN_TOKEN | 清零单账号已用额度（保留 llm_call_log 历史） |
| POST | `/admin/users/{id}/limit` | P1 | ✅ | ADMIN_TOKEN | 改单账号调用上限（0=禁用但保留 token） |
| POST | `/admin/devices/{id}/reset-quota` | P1 | ✅ | ADMIN_TOKEN | 清零访客设备已用额度 |
| POST | `/admin/channels/{id}/refresh-balance` | P1 | ✅ | ADMIN_TOKEN | 刷新渠道上游余额缓存（DeepSeek 等） |
| POST | `/v1/report-issue` | P1 | ✅ | X-App-Token | 用户问题上报（脱敏 + 自动 GitHub issue） |
| GET | `/v1/skills` | P1 | ✅ | X-App-Token | Skill 中心清单（name+description，2026-11-07） |
| GET | `/v1/skills/{name}` | P1 | ✅ | X-App-Token | Skill 正文下载（≤32K 字符；404 skill_not_found） |
| GET/POST | `/admin/skills*` | P1 | ✅ | ADMIN_TOKEN | Skill 中心后台（v2 同日依 Ardot 设计稿重排）：列表（统计行+表格+prompt 清单段）/双栏编辑（LLM 视角实时预览+字符计数）/预览页 `GET /admin/skills/{name}/preview?tab=llm\|raw\|rendered`（三 Tab，rendered=commonmark 服务端渲染 markdown；编辑页正文实时渲染为内联 vanilla JS，零外部依赖），保存即热更新；v3 支持重命名（表单名≠路由名即迁移目录：写新目录→删旧目录，撞名/空名 400 拒绝不覆盖） |
| POST | `/v1/claude-chat` | P1 | ✅ | X-App-Token | AI 工程师模式 SSE 对话（只读诊断） |
| POST | `/v1/claude-tool-result` | P1 | ✅ | X-App-Token | AI 工程师 App tool 结果回传 |
| POST | `/v1/claude-deliver` | P1 | ✅ | X-App-Token + 白名单 | AI 工程师代码交付 |
| GET | `/v1/claude-engineer/available` | P1 | ✅ | X-App-Token | 返回 {available, canDeliver} |
| POST | `/v1/browser/open` | P1 | ✅ | X-App-Token | 云端浏览器开会话：每日配额 peek（成功才计费，超额 429 `quota_exceeded`）+ per-user 并发=1（占用回 `pool_exhausted`）→ 透传 bridge |
| POST | `/v1/browser/action` | P1 | ✅ | X-App-Token | 浏览器动作透传（navigate/click/type/extract/screenshot；域名结果 JSON `status` 原样回） |
| GET | `/v1/browser/frame` | P1 | ✅ | X-App-Token | watch 模式取帧（`?sessionId=`，bridge 一次性 page.screenshot） |
| POST | `/v1/browser/close` | P1 | ✅ | X-App-Token | 关会话 + 释放并发租约（条件释放防误删新租约） |
| WS | `/v1/browser/ws` | P1 | ✅ | X-App-Token | browser 帧流双向代理（M2，`?sessionId=`；owner 校验沿用 ownerTokenHash，无身份 close VIOLATED_POLICY / 缺 sessionId close CANNOT_ACCEPT / bridge 不可用 close SERVICE_RESTART；App↔bridge 文本/二进制帧透传） |
| GET | `/auth/quota` | P1 | ✅ | X-App-Token | 查询账号剩余额度 |
| DELETE | `/auth/account` | P1 | ✅ | X-App-Token | 注销账号（软删除） |
| DELETE | `/guest/device` | P1 | ✅ | X-App-Token | 清除访客设备记录（X-Device-Id 定位） |
| GET | `/download/ios` | P1 | ✅ | 无 | iOS 安装下载页 |
| GET | `/api/app/latest` | P1 | ✅ | 无 | Android OTA 版本信息（`?channel=debug\|release` 双轨，COS metadata 透传 versionCode/changelog） |
| POST | `/admin/apk/upload` | P1 | ✅ | ADMIN_TOKEN | APK 上传双轨（channel/versionCode/changelog 字段；`X-Admin-Token` header 直连返回 JSON，供 ota-publish.sh 消费） |
| GET | `/download/ios/manifest.plist` | P1 | ✅ | 无 | iOS OTA 安装 manifest（itms-services） |
| POST | `/download/ios/udid` | P1 | ✅ | 无 | iOS UDID 注册（mobileconfig 回传表单） |
| GET | `/admin/users/{id}/token` | P1 | ✅ | ADMIN_TOKEN | 查看账号 token |
| GET | `/admin/channels` | P1 | ✅ | ADMIN_TOKEN | 渠道管理列表页 |
| GET | `/admin/channels/new` | P1 | ✅ | ADMIN_TOKEN | 新建渠道页 |
| GET | `/admin/channels/{id}/edit` | P1 | ✅ | ADMIN_TOKEN | 编辑渠道页 |
| GET | `/admin/channels/{id}/token` | P1 | ✅ | ADMIN_TOKEN | 查看渠道上游 token |
| GET | `/admin/release` | P1 | ✅ | ADMIN_TOKEN | 发布页（APK/IPA 上传管理） |
| GET | `/assets/{manifest,url}` | P1 | 🚧 | X-App-Token | COS 预签名 — 待实现 |
| GET | `/agent/config` | P1 | 🚧 | X-App-Token | 供应商适配参数下发 — 待实现 |

> **browser 路由口径（2026-10-06 核定）**：owner 身份 = `ownerTokenHash()`——只认 `X-App-Token`（全局拦截器写 TokenHashKey，兜底 `validateToken`），与 claude 系同口径；**429 仅出自 `/v1/browser/open` 的每日配额 peek**（`peek` 不扣量、成功才 `allow` 计费），action/frame/close 不查配额；`BROWSER_BRIDGE_URL` 未配/bridge 不可达 → 503 `browser_unavailable` 不计额度。**超时分级层叠（守卫测试钉住，改超时须保层叠）**：bridge navTimeout 15s < App 端 registry 内层 25s = dispatch 外层 25s（同源常量）< 网关 30s——慢页面被内层必杀，外层永不触发。WS `/v1/browser/ws`（M2，2026-10-09）不查配额，鉴权 = 同一全局拦截器 + `ownerTokenHash()`，失败以 close code 表达（VIOLATED_POLICY/CANNOT_ACCEPT/SERVICE_RESTART），与 bridge 间文本/二进制帧双向透传。**busy 自愈（M2.5，2026-10-11）**：open 撞 `pool_exhausted` 前经 bridge `GET /session/{id}/status` 探测占租会话，仅 `session_expired` 判死（条件释放 + `recordClose("reaped_heal")` + 重试），null/解析失败/其它 status 一律保守 BUSY——覆盖 App 进程死亡未 close 导致的租约泄漏；并发租约默认 15min，不变式为 > bridge hardCap 10min，快速解锁由自愈探测承担而非短租约。

---

## 4. 认证体系

### 4.1 客户端认证（邮箱注册动态 Token）

```
App → POST /auth/email/send (email, lang?) → 服务端按 lang 发送对应语言验证码邮件
App → POST /auth/email/verify (email, code) → 返回 picme_at_* token
App → 后续请求带 X-App-Token: <picme_at_*>
服务端 → SHA-256(token) 匹配 account.token_hash
```

- `/healthz`、`/auth/email/send`、`/auth/email/verify` 免鉴权
- `lang` 为可选 BCP-47 标签（客户端上报 App 界面语言）；缺省或无法识别时发中英双语邮件（向后兼容旧客户端），zh-Hant/HK/MO 归一为 zh-TW，其余 zh* 为 zh-CN（`EmailLanguage.resolve`）
- 注册用户请求亦带 `X-Device-Id`,用于管理后台 device 维度展示（访客用 X-Device-Id 记设备级试用额度）
- 每账户有 `FREE_LLM_QUOTA` 免费试用额度（默认 100 次），用尽返回 403
- 额度默认值持久化于 `server_setting` 表（`free_llm_quota` / `guest_llm_quota`）；env 仅在首次启动播种，之后由 `/admin/settings` 管理，运行时经 `SettingsService` 内存快照下发（热路径零 DB 读）。单账号上限可在「用户详情」页单独覆盖（`account.llm_calls_limit`，0=禁用）
- **debug 包额度豁免**（2026-10-21）：`DEBUG_QUOTA_BYPASS=true` + 请求头 `X-App-Channel: debug` 双满足时跳过三处额度扣减（`/v1/chat/completions` 账号+访客、`/agent/sessions/{id}/messages` 网关回合、`/v1/browser/open` 每日配额 peek/计费），用量照记 `llm_call_log`；Android debug 包由 `data/remote/AppChannel.kt` 自动带头（BuildConfig.DEBUG 派生，release 发 "release"），iOS 暂不上报。头可伪造——env（默认关）是总闸。全局拦截器打标 `DebugQuotaBypassKey`，路由经 `call.isDebugQuotaBypass()` 判定；豁免请求的错误回滚（revertQuota）同步跳过，保证 increment/revert 严格配对
- **debug 包免登录（云端 Agent 调试页访客身份，2026-10-22）**：复用 DEBUG_QUOTA_BYPASS 双闸——env 开 + `X-App-Channel: debug` + `X-Device-Id` 三满足且无有效账号 token 时，全局拦截器合成稳定访客身份 sha256("debug-guest:"+deviceId)（64 hex，与真实账号 token_hash 同形可落 VARCHAR(64) 列）写入 `TokenHashKey` + `DeviceIdKey`，`/agent/**` 由此免注册可达（会话/并发闸/审计按设备维度收敛）；输入域隔离（真实 token 为 picme_at_*）保证零碰撞，绝不落 account 表。客户端侧 CloudAgentChatViewModel DEBUG 构建放宽 not_logged_in 闸（空 token 继续）+ CloudAgentClient 补发 X-Device-Id 头（deviceIdProvider 注入）。env 默认关 = 生产全封
- Token 持久化在 `account` 表，`token_hash` 字段 SHA-256 存储

### 4.2 管理后台认证

- `/admin/**` 不走 X-App-Token，用固定 `ADMIN_TOKEN` + cookie（`picme_admin = sha256(ADMIN_TOKEN)`）；CLI/脚本可改用 `X-Admin-Token: <ADMIN_TOKEN>` header 直连（如 `scripts/ota-publish.sh` 发包），header 鉴权的请求响应 JSON 而非浏览器跳转
- `ADMIN_TOKEN` 为空 → 后台禁用（全部 503）
- **强烈建议** nginx 对 `/admin` 加 IP 白名单

---

## 5. 关键设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Monorepo | `server/` 寄居在 `polang/` 下 | 端云同仓，AI 全栈协作友好，DTO/规则可端云同源 |
| 独立构建 | 不纳入 Android `settings.gradle.kts` | 安卓构建不依赖、不被拖慢 |
| 服务引擎 | Ktor CIO（纯 Kotlin 协程） | 省内存，2G 盒子友好 |
| DB | SQLite + Exposed + HikariCP | 单机单体，规则/遥测/用量结构化存储 |
| 对象存储 | 腾讯 COS（HK，`ap-hongkong`） | 100GB 标准存储包已购，预签名 URL 下发 |
| LLM 路由 | Cloudflare AI Gateway + TokenHub 双后端 | 按模型自动路由，密钥只在服务端 |
| 管理后台 | kotlinx.html SSR（零前端构建） | 同二进制部署，运营者友好 |
| OpenUI 布局可控性（2026-10-31；2026-11-06 收编） | 无声矫正 + 结构门禁双层（`OpenUiLayoutNormalizer` N1-N4 / `OpenUiLayoutValidator` R1-R4）；2026-11-06 唯一化后消费点收编为终结链路——ensureValidProgram 落流前 normalize、ProgramGuard 经 validateProgramToError 双校验（原 argsTransformer 发射点接线随发卡工具退役摘除） | 保留 LLM 自由组合，杜绝结构病态出货；spec 已随交付清理（git 历史可查），登记 CHAT_CARD_CATALOG data_ui_openui |
| OpenUI 回合原生唯一化（turn-native only，2026-11-06） | /agent 路由面不再按 `X-GenUI-Format` 分流（旧头被忽略），恒走 OpenUI 回合原生：无任何发卡工具（render_ui/render_ui_openui/render_html 全退役），响应即程序：SYSTEM_PROMPT 契约 + content delta 流式预览 + ProgramGuard 策略层纠正（上限 1 次）+ ensureValidProgram 终结兜底双保险；假发卡守卫/actionGate/argsTransformer 接线同步摘除 | 双线分流收口为单线，消除工具发卡链路的负载错形/假发卡两大约束面，流式预览从首字符开始；A2UI 回退闸废弃 |
| A2UI GenUI 线退役（2026-11-05 决策，2026-11-06 实施，0.9.20） | render_ui 工具链（RenderUiToolService/RenderUiValidator/CardClaimGuard 等退役六件）+ `X-GenUI-Format` 分流头 + shared `MessagePart.A2uiCard`/`data_ui` 值 + 端侧渲染线全量物理删除；`AgentEventPayload.HtmlCard` 解码分支保留供历史事件回放；存量 `data_ui` 消息端侧 codec/converter 墓碑兜底为 Text（不丢消息） | 对照组使命完成，OpenUI 已经生产验证；消灭双线校验/提示词执法成本；外部 androidx.a2ui 成熟后可重评估（决策记录见 ADR-016 修订注记） |
| OpenUI 间距词汇统一（2026-11-01） | 统一官方七档 t-shirt 刻度 `none\|xs\|s\|m\|l\|xl\|2xl`（→0/4/8/12/16/24/32dp，8pt 网格）+ legacy 别名 COMPACT\|STANDARD\|COMFORTABLE 保存量卡重放；DSL_RULES 新增 7.6 间距与节奏规则段（档位 dp 语义表 + 五条节奏规则），N2 降档改显式映射表（xs→s、xl\|2xl→l，修 `replace("xl","l")` 腐蚀 `2xl` 隐患） | 消灭 prompt 三套间距词汇并存，LLM 可见单词汇单语义；依据官方 thesysdev/openui spec「枚举各档何时用」口径 |

---

## 6. 构建与部署

```bash
# 本地开发
./server/run-local.sh start        # 后台启动 + 等就绪 + 打印 curl 命令
./gradlew -p server run            # 裸启动（前台，127.0.0.1:8080）

# 构建
./gradlew -p server installDist    # → build/install/picme-server/bin/picme-server

# 部署
deploy.sh                          # 开发机构建 → rsync → ssh 触发切换
deploy-switch.sh                   # 服务器端蓝绿：备份 → 切换 → restart → healthz → 失败回滚
```

> **构建依赖（2026-11-07 起，分层 runtime M1）**：server 经 composite build（settings `includeBuild("..")`）消费 `:shared` jvm 变体（`com.mamba.picme:shared:1.0.0`）——**构建 server 需要本机 Android SDK**（`:shared` 配置含 android target）；部署构建在开发机执行（`deploy.sh` 开发机构建→rsync）、VPS 只跑 jar，不受影响。

> **发卡类改动双交付铁律（2026-10-31 定；2026-11-06 唯一化后适用范围更新）**：OpenUI prompt 资产（`OpenUiPromptAssets` DSL_RULES/黄金示例、`OpenUiTurnNative.SYSTEM_PROMPT`）与校验器等改动只存在于服务端——LLM 读的是线上 jar。此类改动交付 = ① App 发包（端侧渲染器）+ ② `./server/deploy.sh` 部署（先 bump `server/build.gradle.kts` version），缺一不可（2026-10-30 三轮布局治理曾只发包不部署，线上 LLM 从未见过新约束，布局问题「依旧没解决」；原 render_ui/render_ui_openui 工具链已随唯一化退役，铁律对 OpenUI 提示词/校验资产继续生效）。
```

systemd `picme-api.service`：`JAVA_OPTS=-Xmx256m` + `MemoryMax=450M`，与 OpenClaw 共享 2G 盒子。

nginx picme 反代段落仓库 SSOT：`server/deploy/nginx/api.polang.net.conf`（2026-10-17 入册；不变式 `proxy_read_timeout 300s` > 工具桥挂起窗 120s，详见文件头注释与 `OVERSEAS_SERVER_DEPLOYMENT.md` §5.2）。

---

## 7. 编译验证

```bash
./gradlew -p server build          # 编译 + 测试
./gradlew -p server installDist    # 构建分发包
```

---

## 8. 相关文档

- `docs/03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md` — 实现方案与 API 契约
- `docs/03-TECHNICAL-SPECS/OVERSEAS_SERVER_DEPLOYMENT.md` — 部署与运维
- `server/README.md` — 模块级快速参考
- `server/OPENCLAW_DEPLOY.md` — OpenClaw 一键发布指南

---

> **维护者**：项目开发者
> **最后更新**：2026-11-06
> **状态**：生效中
