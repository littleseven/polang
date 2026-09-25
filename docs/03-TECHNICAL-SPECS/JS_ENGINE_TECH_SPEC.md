# JS Engine（QuickJS 沙箱 + JSBridge）技术规格

> **版本**: 1.0
> **状态**: 已实施
> **最后更新**: 2026-07-25
> **维护者**: RD Agent
>
> **来源说明**：本文档由 2026-07-22 设计稿提炼并按当前代码现状重写（设计稿已随交付清理，git 历史可查）。原设计稿基于 Rhino + ClassShutter 语境，MVP 之后引擎已切换为 **QuickJS**（Rhino 依赖已彻底移除），本文以代码为准。
>
> **相关文档**: `TAG_GENERATION.md`（tag.audit 数据来源）、`GALLERY_SEARCH.md`（gallery.query 过滤语义）、`docs/04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`（Capability 生命周期）

---

## 1. 概述

### 1.1 目标

在 PoLang 端侧引入一个可执行 JavaScript 的沙箱运行时，把 Agent 从「选预定义命令」升级为「远程 LLM 生成程序调度端侧能力」（code-interpreter 范式）：

- **组合计算**：LLM 生成 JS，在端侧调用取数 handler 后于 JS 内完成比率/占比/环比等组合计算，单条预定义工具做不到的统计分析由脚本完成；
- **图表生成**：脚本结果经端侧 Chart 生成器（JS）产出 SVG，渲染为聊天图卡；
- **受控写操作**：JS 经 `capability.dispatch` 触发删除/收藏/选中，全部经用户确认后执行。

### 1.2 核心模块

| 层 | 模块 | 文件 | 职责 |
|----|------|------|------|
| 引擎无关层 | `:shared`（commonMain） | `agent/core/js/JsEngine.kt`、`JsValue.kt`、`JsBridge.kt`、`JsRuntime.kt`、`NativeHandler.kt`、`BuiltInHandlers.kt`、`JsBridgeException.kt`、`GallerySummaryJs.kt` | JsEngine 接口 / JS 值投影 / bridge 路由 / handler SPI / 内置演示 handler / 错误码；**不依赖任何具体 JS 引擎** |
| 引擎实现层 | `:androidApp` | `features/chat/js/QuickJsEngine.kt`、`QuickJsConverter.kt` | dokar3 quickjs-kt 适配器：eval 超时、bridge 注入、Promise/async 桥接 |
| 应用 handler 层 | `:androidApp` | `features/chat/js/GalleryScriptHandlers.kt`、`GalleryJs.kt` | gallery/media/face/tag 取数 handler（唯一注册点）与 JS↔模型字段转换 |
| 图表层 | `:androidApp` | `features/chat/js/ChartJs.kt` + `assets/js/chart_bootstrap.js` | Chart 生成器 bootstrap 加载；Chart.bar/line/pie/timeline → SVG |
| 写通路层 | `:androidApp` | `features/chat/js/CapabilityDispatchHandler.kt`、`features/chat/WriteConfirmationController.kt`、`features/chat/capability/ChatMediaWriteCapability.kt` | capability.dispatch → CommandRisk 分级 → 用户确认 → CapabilityRegistry |
| 风险分级 | `:shared`（commonMain） | `agent/core/model/command/CommandRisk.kt` | READ_ONLY / REVERSIBLE_WRITE / DESTRUCTIVE 分级表 |

**依赖约束**：QuickJS 依赖（`io.github.dokar3:quickjs-kt:1.0.5`）**仅 `:androidApp` 模块**引入；`:shared` 的 `js/` 包引擎无关，不依赖 QuickJS。

---

## 2. 引擎选型与合规

### 2.1 选型结论

| 引擎 | 结论 |
|------|------|
| **QuickJS（dokar3/quickjs-kt 1.0.5）** | ✅ **当前采用**。Kotlin DSL 绑定；Promise/await 异步模型天然契合 async handler；协程取消可真正中断 C 层死循环（超时熔断有效）；native 库 16KB page 对齐（满足 Google Play Android 15+ 要求）；Apache 2.0 |
| Rhino | ❌ 已移除。MVP 阶段选型（纯 JVM 可单测），后被 QuickJS 替代，依赖已从构建中彻底删除 |
| WebView + `@JavascriptInterface` | ❌ 不采用。加载不可信远程内容属 Google Play 明确违规场景 |
| Hermes / V8 自编译 | ❌ 不采用。体积大、面向 RN，无现成轻量绑定 |

### 2.2 Google Play 合规分析

政策原文（Device and Network Abuse）：

> "an app may not download executable code (such as dex, JAR, .so files) from a source other than Google Play. **This restriction does not apply to code that runs in a virtual machine or an interpreter where either provides indirect access to Android APIs (such as JavaScript in a webview or browser).**"

关键结论：

1. ✅ **解释器中运行、仅"间接访问" Android API 的代码被明确豁免**。QuickJS 沙箱 + 白名单 handler bridge = 间接访问 = 合规。
2. ✅ **包内 JS**（`assets/js/`，如 `chart_bootstrap.js`、演示脚本）完全合规。
3. ✅ **LLM 会话文本下发的 JS**：脚本来自 LLM tool_call 的 `code` 参数（会话文本），在沙箱内执行、**不持久化、不从网络下载 bundle**，区别于 CodePush 式"下载并执行远程 JS bundle"（真实违规判例，微软 react-native-code-push #498）。
4. ❌ 红线行为（永不触碰）：下载执行远程 JS bundle；WebView 加载不可信 http 内容。

**沙箱隔离事实**（替代原设计稿的 ClassShutter 论证）：QuickJS 是独立 C 引擎，**没有任何 Java 桥（无 LiveConnect）**，JS 天然碰不到 Java 类/反射/Android API，不需要 Rhino 式 ClassShutter 拦截。JS 与原生之间的**唯一通道**是引擎层注入的 `bridge` 全局对象（`__bridgeCall` / `__bridgeCallAsync` / `__bridgeList` / `__consoleLog` 四个绑定函数，经 bootstrap JS 包装为 `bridge.call` / `bridge.callAsync` / `bridge.list` / `console.log`），handler 白名单制——未注册的 handler 名一律报错。

**风险矩阵**：

| 行为 | Google Play | 是否采用 |
|------|------------|---------|
| 执行包内 JS（assets） | ✅ 合规 | ✅ 采用（Chart bootstrap、演示脚本） |
| 执行 LLM 会话文本 JS（不持久化） | ✅ 豁免（解释器间接访问） | ✅ 采用 |
| 受控 JSBridge（白名单 handler） | ✅ 豁免 | ✅ 采用 |
| 下载并执行远程 JS bundle | ❌ 违规（CodePush 判例） | ❌ 不做 |
| WebView 加载不可信 http JS | ❌ 明确违规 | ❌ 不做 |

---

## 3. 架构总览

### 3.1 分层与依赖方向

```
LLM (远程 ReAct)  tool_call: run_gallery_script / draw_chart
    ↓
ChatToolService（@Tool，:shared）/ RemoteControlToolService（@Tool，:androidApp）
    ↓ dispatchCommand
AgentCommand.ExecuteScript / DrawChart
    ↓ CapabilityRegistry (CHAT 场景)
ChatRunScriptCapability / ChatMediaWriteCapability (:androidApp)
    ↓ Delegate
ChatViewModel.onRunScript / onDrawChart
    ↓ jsEvalMutex 串行
JsRuntime (:shared 门面) ──register── NativeHandler (白名单)
    ↓ engine 注入
QuickJsEngine (:androidApp) ──bridge.callAsync──→ JsBridge.dispatchAsync
    ↓                                        ↓
QuickJS C 引擎 (沙箱)                   UseCase / DAO / CapabilityRegistry
```

- `:shared` 的 `js/` 包**引擎无关**：`JsEngine` 接口（`eval` / `eval(script, timeoutMs)` / `callFunction` / `installBridge` / `close`）、`JsValue`（sealed：Null/Bool/Num/Str/Obj/Arr）、`JsBridge`（handler 注册与 sync/async 分发）、`JsRuntime`（门面，引擎由调用方注入）、`NativeHandler`（Sync/Async 两种 SPI，`syncHandler`/`asyncHandler` 工厂函数）、`JsBridgeException`（错误码）。
- `:androidApp` 的 `QuickJsEngine` 是唯一生产引擎实现：dokar3 的 `evaluate` 是 suspend，用 `runBlocking` + `withTimeout` 适配同步 `JsEngine.eval`；协程取消可真正中断 C 层死循环。
- 换引擎只换注入的 `JsEngine` 实现，bridge/handler/JsValue 不变。

### 3.2 异步模型（callAsync 通路）

- 所有 gallery/media/face/tag 取数 handler 与 `capability.dispatch` 均为 **async**：JS 侧必须 `await bridge.callAsync(name, args)`；对 async handler 调 `bridge.call` 会抛 `HANDLER_NOT_ASYNC_CALLABLE`。
- 链路：`bridge.callAsync` → `__bridgeCallAsync`（dokar3 `AsyncFunctionBinding`，返回 Promise）→ `JsBridge.dispatchAsync`（在注入的 CoroutineScope 内 launch，handler 内可直接 suspend 调 UseCase/DAO）→ 完成后 resume；**handler 失败（含用户拒绝写操作）→ Promise reject**，JS 可 try/catch。
- LLM 脚本与包内演示脚本由 `JsEngine.evalAsync(code, timeoutMs)` 按「async 函数体」语义执行——顶层 `return`/`await` 合法，无需自包 IIFE。`ChatViewModel.onRunScript` 与 Debug 页（JsBridgeDemo）均走此入口。
- **dokar3 1.0.5 不会解包顶层 Promise 的值**（evaluate 只 pump 完 pending job，`getEvaluateResult` 返回 Promise 对象本身，字符串化为 `Promise { <state>: "fulfilled" }`）。`QuickJsEngine.evalAsync` 因此采用**两段式 eval**：① async IIFE + `.then` 把 resolved value / rejection 写入全局变量（evaluate 返回前 job 已 pump 完）；② 第二段同步读回——rejection 转为 throw（真实 JS 错误回传 LLM），resolved value（`undefined` 归一为 `null`）作为结果。直接使用 `eval` 执行返回 Promise 的脚本只会拿到 Promise 字符串，属于用法错误。
- 并发取数支持 `Promise.all`（多个 callAsync 并行）；脚本级并发由 `ChatViewModel.jsEvalMutex` 串行（QuickJS 实例单线程，不可并发 eval）。

---

## 4. Handler 清单

### 4.1 取数 handler（12 个，全部 async、只读）

唯一注册入口：`GalleryScriptHandlers.registerGalleryHandlers`（`features/chat/js/GalleryScriptHandlers.kt`），**ChatViewModel 持久 JsRuntime 与 Debug 页 JsBridgeDemo 临时 JsRuntime 共用**，保证两条链路 handler 集合一致。新增/修改 handler 只改这里。

| Handler | 参数 | 返回 | 说明 |
|---------|------|------|------|
| `gallery.summary` | `{}` | 聚合统计对象 | totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation |
| `gallery.query` | `{label?,ocr?,location?,fromMs?,toMs?,hasFace?,person?,limit?}` | `{ids:[...], total:N}` | 多维 AND 结构化过滤（person=已命名人物名，按人脸归属过滤）；ids 截断到 limit，total 为未截断真实数 |
| `gallery.tags` | `{}` | `{标签:照片数}` | 实际打标标签分布，按计数降序 top 50 |
| `gallery.timeline` | `{fromMs?,toMs?,bucketMs?}` | `{桶起始时间戳:照片数}` | 按时间分桶，默认按月（bucketMs=2592000000；年=31536000000） |
| `gallery.intersect` | `{idsA:[...],idsB:[...],op:"intersect\|union\|diff"}` | `{ids:[...], total:N}` | 多次 query 结果集合运算（如 旅行+人脸） |
| `gallery.stats_by_tag` | `{label?,hasFace?,fromMs?,toMs?}` | `{标签:照片数}` | 条件过滤后的标签分布（如人像照片内的场景标签） |
| `gallery.stats_by_city` | `{topN?}`（默认 10 上限 50） | `{城市:照片数}` | 按城市分组的媒体计数分布（逆地理编码城市，DB 层聚合） |
| `media.meta` | `id` 或 `[id]` | 单张元数据对象 | `{id,type,captureMs,fileName,labels,locationName,city,hasFace,faceId,aestheticScore,faceQualityScore}` |
| `media.batch_meta` | `[id1,id2,...]` 或 `{ids:[...]}` | `[{...},...]` | 批量元数据，上限 50，避免循环调 media.meta |
| `face.cluster` | `{topN?}`（默认 10 上限 50） | `{clusterCount,namedCount,totalEmbeddings,unassignedEmbeddings,topPersons:[{personId,name,faceCount,coverMediaId}]}` | 人脸聚类盘点；**不含 embedding 原始数据** |
| `tag.audit` | `{topN?}`（默认 10 上限 50） | `{totalMedia,unlabeledCount,neverScannedCount,lastScanAt,outOfVocabTags:{标签:照片数}}` | 打标覆盖与词表外标签审计 |
| `tag.scan_status` | `{}` | `{active,state,...}` 或 `{active:false,state:null}` | TAG 扫描会话状态快照（active/state/sessionId/currentPass/processed/total/pending/failed/estimatedRemainingMs）；只读查询，**绝不触发扫描** |

### 4.2 写通路 handler（1 个，async）

| Handler | 参数 | 说明 |
|---------|------|------|
| `capability.dispatch` | `{method, params}` | JS → CapabilityRegistry 写通路，详见 §6。method 白名单：`delete_media {ids:[...]}` / `favorite_media {id,favorite}` / `select_media {id,selected}` / `remember_fact {content,category?}` / `forget_fact {fact_id?,query?}` / `remember_person_relation {name,relation}` / `forget_person_relation {name}` / `get_gallery_summary {}` / `recall_memory {query}` / `query_person_relation {name?}`（后三者只读直通）；其余 method 抛错 |

### 4.3 内置演示 handler（4 个）

`BuiltInHandlers`（`:shared`）在 `JsRuntime` 初始化时自动注册：`math.add`、`string.upper`、`echo`（sync）+ `device.info`（async）。纯计算/只读，用于演示 bridge 通路与作为参考实现。

---

## 5. 调用链与超时设计

### 5.1 run_gallery_script 主链路

```
LLM tool_call: run_gallery_script(code)
 → ChatToolService / RemoteControlToolService.runGalleryScript
 → dispatchCommand(AgentCommand.ExecuteScript(code))
 → ChatRunScriptCapability (CHAT 场景)
 → ChatViewModel.onRunScript(code)
    jsEvalMutex 串行 → writeConfirmationController.onScriptStarted()
    → rt.eval("(async function(){\n<code>\n})()", evalTimeoutMs)
    → finally: writeConfirmationController.onScriptEnded()（在途写确认一律拒绝）
 → 结果 JSON 作为 observation 回传 LLM 做自然语言总结
```

LLM 感知 handler 的唯一渠道是 `@Tool` 描述文本（`ChatToolService` / `RemoteControlToolService` 的 `run_gallery_script` 描述已列出全部 handler 签名与示例），新增 handler 必须同步该描述。

### 5.2 超时设计（三级）

| 超时 | 值 | 作用域 | 说明 |
|------|-----|--------|------|
| 默认 eval 超时 | **5s** | 普通脚本 | `QuickJsEngine.DEFAULT_EVAL_TIMEOUT_MS`；`withTimeout` 取消可中断 C 层死循环，抛 `SCRIPT_TIMEOUT` |
| 写确认等待 | **120s** | 单次 capability.dispatch 确认 | `CapabilityDispatchHandler.DEFAULT_CONFIRMATION_TIMEOUT_MS`；超时按拒绝处理 |
| 写脚本 eval 超时 | **180s** | 含 `capability.dispatch` 的脚本 | `ChatViewModel.WRITE_EVAL_TIMEOUT_MS`；脚本会挂起等用户确认（最长 120s）+ 可能的系统授权提示，故放宽 |

### 5.3 渲染产物拦截（图卡 / HTML 卡片）

脚本 `return Chart.bar/line/pie/timeline(...)` → 结果 `{chart:<svg>, summary:<text>}`：

- `chart`（SVG 字符串）**不喂回 LLM**，由 `ChatViewModel.emitChartMessage` 落库为 `ChatMessageType.CHART` 消息（聊天列表由 DB Flow 驱动，落库保证图卡跨重载/重启持久）；
- `summary`（精简文字）回传 LLM 做文字总结，省 token。

脚本 `return {html:<自包含HTML>, summary:<text>}` 走同一拦截通路（2026-09 新增）：

- `html` 经 `HtmlCardSanitizer`（`features/chat/HtmlCardSanitizer.kt`）清洗（128KB 上限、剔除高危标签/远程 script）后，由 `ChatViewModel.emitHtmlCardMessage` 落库为 `ChatMessageType.HTML_CARD` 消息，`HtmlCard` WebView 渲染；清洗拒绝时不落库，原因作为 observation 回传 LLM；
- `summary` 回传 LLM 做文字总结。

---

## 6. capability.dispatch 写通路

### 6.1 链路

```
JS: await bridge.callAsync('capability.dispatch', {method, params})
 → CapabilityDispatchHandler (:androidApp, features/chat/js/)
    解析 {method, params} → buildCommand → AgentCommand
    → CommandRisk.ofMethod(method) 风险分级
    → 非 READ_ONLY：confirmationMutex 互斥 → WriteConfirmationController.request(...) 挂起等用户确认
    → dispatch(command) → CapabilityRegistry (CHAT 场景)
 → ChatMediaWriteCapability → ChatViewModel (Delegate)
 → 结果 AgentAction → JsValue 回传 JS
```

### 6.2 CommandRisk 风险分级

`CommandRisk`（`:shared` `agent/core/model/command/CommandRisk.kt`，纯数据映射）：

| 级别 | method | 处理 |
|------|--------|------|
| `READ_ONLY` | 未列出的所有 method（如 get_gallery_summary、recall_memory） | 直接 dispatch，无需确认 |
| `REVERSIBLE_WRITE` | `favorite_media`、`select_media`、`remember_person_relation`、`forget_person_relation`、`remember_fact`、`forget_fact` | 需用户确认 |
| `DESTRUCTIVE` | `delete_media`、`share_media` | 需用户确认，UI 用警示色 |

**维护约定**（见 CommandRisk.kt 注释）：新增破坏型 method 必须同步登记两处——① `CommandRisk.ofMethod` 分级表；② `CapabilityDispatchHandler` 的 method 白名单（`buildCommand` when 分支与 `SUPPORTED_METHODS`）。漏登分级表会被默认 READ_ONLY 直通，漏登白名单则 JS 调不通。

### 6.3 确认机制（WriteConfirmationController）

- **确认 UI**：ChatScreen 弹确认框，展示 method、风险级别、目标数量与**缩略图预览**（前 6 个，`CapabilityDispatchHandler.MAX_PREVIEW_IDS`）；
- **超时按拒绝**：单次确认挂起最长 120s，`withTimeoutOrNull` 超时返回 false；
- **并发互斥串行**：`Promise.all` 并发 callAsync 触发的多个确认由 `confirmationMutex` 串行化——同一时刻只弹一个确认框，前一个完成后下一个才弹出（各自独享完整超时时长），避免 StateFlow 单槽互相覆盖；
- **孤儿确认防护**（核心不变式——「脚本已死，确认不再生效」）：eval 超时只取消 evaluate 协程，async handler 仍存活在 bridge scope；`onScriptEnded()` 后在途确认一律拒绝（弹窗消失），脚本未运行时 `request` 立即返回 false 不弹窗——防止用户在 SCRIPT_TIMEOUT 后点「确认」仍真实执行写操作。

### 6.4 写操作落点

- `delete_media`：`ChatMediaWriteCapability`（CHAT 场景）→ 复用 `MediaRepository` 的**系统 MediaStore 授权流**（可能弹系统授权框），不可恢复；
- `favorite_media` / `select_media`：当前为 **chat 会话级 StateFlow 状态，无持久化**（App 尚无持久化收藏路径，与 GalleryCapability 的 favorite 先例一致）；
- `remember_fact` / `forget_fact`：`MemoryCapability`（CHAT 场景）→ `MemoryRepository` 落 `memory_facts` 表（source=JS_DISPATCH，设置页「AI 记忆」可见来源标签）；
- `recall_memory`：`MemoryCapability` READ_ONLY 直通，返回含 factId 的事实列表文本；
- `remember_person_relation` / `forget_person_relation` / `query_person_relation`：`PersonRelationCapability`（CHAT 场景）→ `PersonRepository` 人物关系声明/遗忘/查询（remember/forget 需确认，query 只读直通）；
- `share_media` 不在本 Capability（ChatToolService 已有通路，避免重复注册冲突）；
- ChatViewModel 未激活（chat 页不在前台）时 Capability 报 `CAPABILITY_UNAVAILABLE`，Promise reject。

---

## 7. Chart / draw_chart

- **Chart 生成器**：`assets/js/chart_bootstrap.js`（独立 JS 文件便于维护），`ChatViewModel.getOrCreateJsRuntime` 创建 JsRuntime 后 eval 一次，定义全局 `Chart`：`Chart.bar/line/pie({title, labels, values, unit?})` 与 `Chart.timeline(timelineObj, {title, unit?, type?})`，均返回 `{chart:<svg>, summary:<text>}`。加载由 `ChartJs.kt` 的 `loadChartBootstrapJs(context)` 完成，失败仅告警不阻断脚本能力。
- **两种触发方式**：① 脚本内 `return Chart.x(...)`（§5.3 图表拦截）；② LLM 直接 tool_call `draw_chart(type,title,labels,values,unit)` → `AgentCommand.DrawChart` → `ChatRunScriptCapability` → `ChatViewModel.onDrawChart` → `rt.eval("Chart.<fn>(<args>)")` → 图卡 + summary 回传。
- `@Tool` 描述明确：draw_chart 是展示图表的唯一方式，禁止 LLM 用文字/Markdown 表格/ASCII 画图。

## 7.1 HTML 组件卡片 / render_html（2026-09 新增）

- **定位**：`render_html` 是 draw_chart 之外的第二种渲染通路——draw_chart 负责柱/折/饼统计图，render_html 负责**用户明确要求**的更丰富展示/交互组件（可交互图表、动画演示、自定义布局卡片）；默认不用（prompt `html_card_rules` 节约束）。
- **链路**：LLM tool_call `render_html(html, summary)` → `AgentCommand.RenderHtml` → `ChatRunScriptCapability` → `ChatViewModel.onRenderHtml` → `HtmlCardSanitizer` 清洗 → 落库 `HTML_CARD` 消息 → `HtmlCard`（`features/chat/HtmlCard.kt`）WebView 渲染；summary 回传 LLM。脚本内 `return {html,...}` 走 §5.3 拦截。
- **安全模型**（渲染对象为 LLM 生成的不可信内容；2026-09-25 修订，表现力优先）：清洗器剔除远程 script/iframe/object/embed/form/meta refresh（第一道）；WebView 禁文件/DOM Storage、**零 JS 桥接**（第二道，JS 仅沙盒内启用）；**远程资源（img/CSS/a 外链）放行**（2026-09-25 暂时放开，表现力优先）——http(s) 资源直拉，`<a>` 点击经 `shouldOverrideUrlLoading` 拦截回传 `onOpenLink`，由 `HtmlLinkPreviewOverlay` 打开全屏落地页，卡片自身永不导航；**底线**：远程 JS 执行仍禁（远程 script 剔除 + 不注入 JS 桥）。
- **排版上下文**：组合根（`PoLangApplication`）按 DisplayMetrics 构建 `RenderEnvironment`（设备类型/屏幕 px+dp/卡片内容区最大宽度/卡片最大展示高度）注入 `AgentConfigurator`，拼进 chat system prompt 动态尾段（`RemoteChatEngine.buildPromptSuffix` 的 `renderEnvironment` 参数，非 golden 锁定区），引导 LLM 响应式排版；未注入（iOS 跟随期）时无该段。
- **渲染模板**：`HtmlCard.wrapHtmlDocument` 把 HTML 片段包进完整文档——注入 viewport meta（`width=device-width`，1 CSS px ≈ 1 dp，测高/排版基准统一）+ 响应式 reset（`img/video/svg/table{max-width:100%}` 等，防 LLM 产物固定宽度撑爆卡片）；已是完整文档（含 `<html`）时原样加载。
- **UI 形态**：列表卡片**动态测高 + 高度跟随**——`onPageFinished` 后经 `evaluateJavascript` 读 body 内容盒高度（`getBoundingClientRect`，不受 viewport 高度污染；出站求值，非 JS 桥接；首测 + 400ms 延迟复测覆盖 JS 布局 settle），clamp 到 [120dp, 屏高×0.66] 并动画过渡，测高完成前占位 280dp；加载后注入 **ResizeObserver** 观察 body，交互引起的内容高度变化（手风琴展开/Tab 切换）实时回传、容器高度跟随双向缩放（老内核无 ResizeObserver 时静默跳过，由首测+复测兜底）；**滑动防抖三重策略**（修「滑动中列表项边滚边变高」的上下抖动）：测高结果按 html hash 进程级 LruCache 缓存（卡片重回视口直接以最终高度出现，零占位动画）+ 列表滑动途中（`listState.isScrollInProgress`）冻结高度更新、滚停后一次性应用 + 小于 2px 的测高抖动直接忽略；卡片内直接交互（JS 点击/输入即时生效；**仅 `<a>` 外链点击进全屏落地页** `HtmlLinkPreviewOverlay`），滚动条一律隐藏，超高内容卡片内滚动、经 `rememberNestedScrollInteropConnection` 滚到边界后让渡外层列表。
- **调试入口**：DEBUG 构建 chat 输入 `/html` 注入八张冒烟测试卡（纯 HTML / 图文混排内联 SVG / Grid 仪表盘排版 / CSS 动画 / 动画图表 / 内联 JS / Tab+手风琴复合交互 / 远程用例，`HtmlCardSmokeSamples`，远程用例故意绕过清洗器直插——远程 img 应可加载、外链应开落地页，远程 script 因零桥接+网络失败无原生副作用），不走 LLM。
- **iOS 状态**：本期仅 Android 落地，iOS 待 ios-follow（WKWebView 照搬 `ChartSvgCard.swift` 模式 + 同款沙盒/动态测高/链接落地页）。

---

## 8. 飞书链路现状

- `RemoteControlToolService`（飞书等 IM 渠道使用的 @Tool 集）已补齐 `run_gallery_script` 完整 handler 描述与 `draw_chart` @Tool，与 chat 链路共用同一 `AgentCommand.ExecuteScript` / `DrawChart` 分发。
- 执行落点与 chat **共享 JsRuntime**（经 `ChatRunScriptCapability` 的 Delegate 指向 ChatViewModel）——**chat 页不在前台时 delegate 未绑定，脚本执行不可用**（`CAPABILITY_UNAVAILABLE`）。
- 飞书侧无图卡 UI：`draw_chart` 的 SVG 无法在飞书消息中渲染，只把 `summary` 文本回传。SVG 图片回传属未来演进（§10）。

---

## 9. 错误码与隐私边界

### 9.1 错误码（JsBridgeException）

对外仅暴露错误码 + 通用信息，不泄露内部栈/路径（过审最小信息原则）：

| 错误码 | 场景 |
|--------|------|
| `HANDLER_NOT_FOUND` | handler 未注册（sync 分发抛异常；async 分发走 Promise reject） |
| `HANDLER_ERROR` | handler 执行抛异常 / 用户拒绝写操作 / 确认超时 / dispatch 失败 |
| `HANDLER_NOT_ASYNC_CALLABLE` | 对 async handler 调 bridge.call |
| `SCRIPT_TIMEOUT` | eval 超过超时阈值（5s / 180s） |
| `SCRIPT_ERROR` | JS 语法/运行时错误 |
| `SANDBOX_VIOLATION` | 沙箱违规（预留；QuickJS 无 Java 桥，天然不可达） |
| `FUNCTION_NOT_FOUND` | callFunction 目标函数不存在 |

### 9.2 隐私边界（[PRIVACY] 红线）

- 取数 handler **全部只读**，数据不出端；仅脚本 return 的聚合结果（counts/比率）作为文本 observation 回传远程 LLM；
- `GalleryJs` 白名单转换：`media.meta` / `batch_meta` **不回** uri / latitude / longitude / ocrText / 任何 embedding/ROI；
- `face.cluster` 只回聚类统计与 topPersons 基本信息，**不含 embedding 原始数据**；
- 图片/人脸/OCR 原始内容 100% 端侧。

---

## 10. 运行可观测性（Agent 终端运行感知层）

JS 沙盒是 **Agent 终端运行感知层**的端侧执行层：与 `llm_call_log`（推理层）、`tool_call_log`（行动层）并列，
每次沙盒执行产生一条结构化事件（Agent First §2.4：事件可被 AI 消费），落 `polang_llm_log.db` 的 **`js_run_log`** 表，
三表按时间对齐即可还原一次请求的完整端侧链路（设计稿已随交付清理，本节即现行事实源）。

- **事件模型**：`JsRunEvent`（`:shared` `agent/core/js/`，引擎无关）：`source`（chat/debug_page）、`kind`（eval/evalAsync/callFunction）、
  `script`（仅 DEBUG，cap 4000）、`scriptLength`、`success`、`errorCode`、`errorMessage`（含 JS 栈，cap 500）、`resultPreview`（仅 DEBUG，cap 1000）、`latencyMs`。
- **埋点**：`JsRuntime.runRecorded` 统一包裹三个执行入口——计时 + 记录 + **错误原样重抛**（执行语义不变）；
  recorder 异常被吞（双保险：`runCatching` + Room 实现自吞）。
- **注入**：`JsRuntime.recorder` / `JsRuntime.captureContent` 静态装配（镜像 `RemoteModelFactory.recorder`），
  `PoLangApplication` 启动时注入 `RoomJsRunRecorder`（`captureContent = BuildConfig.DEBUG`）。
- **错误归一**：`QuickJsEngine.runEval` 把 dokar3 `QuickJsException` 包装为 `JsBridgeException(SCRIPT_ERROR)`，
  超时为 `SCRIPT_TIMEOUT`，其余异常归 `UNKNOWN`（引擎无关层不可见 dokar3 类型）。
- **三环感知**：环内（错误即时回传 LLM 自愈，已具备）→ 环外（本表，人/QA Agent 事后消费）→ 自我感知（预留 `diag.*` 只读 handler 环内查询运行史）。
- **保留策略**：保留最近 200 条、日级 guard prune；release 构建 `script`/`resultPreview` 为 null（仅落指标，隐私红线）。
- **Debug 查看页**：LLM 调用日志页第三 Tab「JS 运行」：列表（时间/来源/kind/耗时/错误码）+ 详情（脚本/错误栈/结果预览，可复制）。
- **已知限制**：① 脚本内创建但未 await 的游离 Promise，其 rejection 无法捕获（dokar3 1.0.5 不暴露 host promise rejection tracker）；② C 层死循环（`while(true){}`）不可被 `withTimeout` 中断（dokar3 1.0.5 未暴露 `evaluationTimeoutMillis`/中断 handler，2026-07-26 实测确认）——该次执行永不产生事件，且 chat 链路 `jsEvalMutex` 被占死需重启 App。

---

## 11. 未来演进

| 方向 | 说明 | 前置条件 |
|------|------|---------|
| **存储占用分析** | LLM 经 JS 分析相册存储占用（如「哪些大视频可清理」） | `MediaEntity` 需新增 `size` 列（Room 迁移），handler 白名单放行 size 字段 |
| **飞书 SVG 栅格化回传** | draw_chart 在飞书链路把 SVG 栅格化为 PNG，经 `FeishuChannelHandler.sendImage(imageBytes, messageId)` 回传图片消息 | SVG → Bitmap 栅格化器；飞书链路 delegate 与 chat 页生命周期解耦 |
| **favorite/select 持久化** | 收藏/选中从会话级 StateFlow 升级为持久化存储 | 产品定义收藏语义；DB/DataStore 落点；与 Gallery 页收藏态统一 |
| **JS 插件化** | 用户/第三方脚本作为「智能相册插件」载体（包内或受信来源） | 远程 JS 属高合规门槛：须第一方 HTTPS 白名单 + 完整性校验 + 强沙箱 + 数据安全申报；默认不开启 |
| **跨平台复用** | `:shared` 的 js/ 包引擎无关（已随 Phase 4 KMP 化为 commonMain），纯算法/参数计算类 JS 可与 Web/iOS 共享；API 稳定后可抽离独立 `:jsbridge` Gradle 模块 | QuickJS iOS 绑定或换引擎适配器 |

---

## 12. 测试

- **JVM 单测**（引擎无关层 + 应用 handler 层，不依赖 QuickJS native）：`CapabilityDispatchHandler`（解析/分级/确认/拒绝/超时/并发互斥）、`WriteConfirmationController`（孤儿确认防护）、`ChatRunScriptCapability` / `ChatMediaWriteCapability`（命令路由与错误路径）、`CommandRisk`（分级表）、`JsRuntimeObservabilityTest`（运行事件埋点：成功/失败/错误分类/captureContent/recorder 异常隔离）、`EvalAsyncContractTest`（evalAsync 契约）、`JsRunLogDaoTest`（js_run_log 读写/prune）。
- **真机验证**：Debug 页 JsBridge 演示入口（与 chat 共用 `registerGalleryHandlers`）；chat 页实测 `run_gallery_script` / `draw_chart` / 写确认弹窗全流程；Debug「JS 运行」Tab 核查成功/失败事件落库。
- QuickJS 引擎层（`QuickJsEngine`/`QuickJsConverter`）依赖 native .so，不在 JVM 单测覆盖范围，经真机回归验证。
