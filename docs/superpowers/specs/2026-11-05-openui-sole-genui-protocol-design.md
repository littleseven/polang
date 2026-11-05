# OpenUI 唯一化：A2UI 全量退役 + 端侧推理方向登记

> **日期**：2026-11-05
> **状态**：已定稿待实施
> **决策记录**：用户逐节确认——① C（A 先行切默认 + B 后续单独 spec）；② C（A2UI 实现全删、决策记录留文档，外部 SDK 成熟后可重评估）；③ A（硬切，不做 sunset 分流）；方案 1（一次性拆除、服务端先切）；设计其余细节授权自主决策。
> **上位约束**：ADR-016（Chat parts 宪法，本 spec 含其修订项）、ADR-008（隐私红线）、[PARITY]/[I18N]/[DOC-SYNC] 红线、server/AGENTS.md「发卡类改动双交付铁律」。

## 1. 背景

OpenUI turn-native 已是事实默认：客户端 `genUiFormat` 默认 `openui`（2026-11-01 起，经 `X-GenUI-Format` 头分流）；服务端 turn-native 链路完整（`OpenUiTurnNative.SYSTEM_PROMPT` 响应即程序契约 + content delta 流式预览 + `ProgramGuard` 策略层纠正 + `ensureValidProgram` 终结兜底）。A2UI 线（外部 `androidx.a2ui 1.0.0-alpha01` SDK + 自研适配层）定位为对照组，样式已冻结，使命完成。

本 spec = **Milestone A：OpenUI 唯一化（A2UI 全量退役）**；Milestone B（端侧推理产 OpenUI 程序）仅登记方向（§7），单独 spec 立项。

## 2. 目标与非目标

**目标**
- GenUI 只剩一条线：OpenUI Lang 1.0 turn-native（响应即程序）。
- A2UI 实现全量拆除：外部依赖、端侧渲染、shared 契约、服务端工具与分流头。
- 兼容两面：存量 `data_ui` 持久化消息不丢（Text 兜底）；旧版本 App 不崩（程序原文文本化，接受）。
- 决策记录落位活文档（ADR-016 修订注记 + server/AGENTS.md §5 + CHAT_CARD_CATALOG 退役标注）。

**非目标**
- 不动 OpenUI 线本身的功能（组件库/校验器/流式预览原样保留，仅常量搬家）。
- 不动本地主聊天（端侧 Koog loop 的 render_html/HtmlCard 链路不受影响——本节「render_html 退役」仅指云端 Agent 线）。
- 不做 iOS 端任何改动（iOS 零 A2UI/OpenUI 实现；OpenUI iOS 渲染属后续 ios-follow 议题）。
- 不实施 Milestone B（无端侧文本 LLM 载体，需先单独立项决策模型选型）。

## 3. 拆除清单

### 3.1 server/（先部署）

| 动作 | 对象 |
|------|------|
| 删分流 | `AgentRoutes.GENUI_FORMAT_HEADER` 及 `X-GenUI-Format` 读取/分流逻辑；所有 `/agent/**` 请求恒走 turn-native 接线（SYSTEM_PROMPT/工具过滤/`forwardContentDeltas`/`finalMessageTransformer`）；收到该头无视（不报错，后向兼容旧客户端） |
| 删类 | `RenderUiToolService`、`RenderUiValidator`、`CardClaimGuard`（turn-native 经 `programGuard == null` 旁路，恒不触达）、`OpenUiToolCallStreamTracker`、`OpenUiProgramStreamExtractor`（退役保留件正式清除）、`RenderOpenUiToolService`（工具类，turn-native 起未注册） |
| 常量搬家 | `DSL_RULES`/`ICON_NAMES_TEXT`/`GOLDEN_*` 黄金示例 → 新文件 `server/.../agent/OpenUiPromptAssets.kt`（object 常量承载）；`OpenUiTurnNative.SYSTEM_PROMPT` 同源引用关系不变，引用测试改钉新位置 |
| 退役云端 render_html | `ServerTools` 删 `render_html` 工具（turn-native 已过滤，HTML 经 `Html` 组件入程序树）；发射路径 `ServerToolDispatcher.withHtmlCardSink` 删除；**`AgentEventPayload.HtmlCard`（wire `html_card`）解码分支保留**——历史 `agent_event` 行回放兼容，仅停发射 |
| 删接线 | `AgentRunner` 删 render_ui 的 actionGate/argsTransformer 接线；`ProgramGuard`/`ensureValidProgram`/`RenderOpenUiValidator`（turn-native 校验入口 `validateProgramToError`）保留 |
| 测试 | `RenderUiToolServiceTest`/`RenderUiValidatorTest` 删除；`AgentRoutesTest`/`AgentRunnerTest` 的 A2UI 分支断言改 turn-native only；`OpenUi*Test` 保留并改常量引用点 |

### 3.2 shared/（commonMain）

| 动作 | 对象 |
|------|------|
| 删 part 类型 | `MessagePart.A2uiCard`（11→10 子类型）；`ChatListFlattener` 删 `TYPE_A2UI_CARD` contentType |
| legacy 兜底 | `MessagePartsCodec`/`MessagePartsConverter`：`data_ui` 值走既有**行级 Text 兜底**路径（复用 ADR-016 既定机制，不新造 part 类型、不动 Room schema、不做迁移）；`data_ui` 从 type 值域删除（11→10） |
| 包拆分 | `domain/chat/a2ui/` 退役拆分——删：`A2uiCardContract`、`A2uiStyleTokens`；迁 → `domain/chat/openui/`：`ScanPhotoEntry`/`ScanPhotoProjection`/`GalleryHistogramBuilder`/`HistogramBucket`（句柄投影属 OpenUI 契约，`LocalHandleEntity` scan payload 同源）；重命名迁入：`A2uiTextRole`→`OpenUiTextRole`、`A2uiColorRole`→`OpenUiColorRole`、`A2uiSpacingRole`→`OpenUiSpacingRole`（OpenUI 渲染层 4 处 import 同步改） |
| 注释 | `OpenUiContract` 类注释去「与 A2uiCardContract 对偶」表述 |

### 3.3 androidApp/

| 动作 | 对象 |
|------|------|
| 删渲染线 | `features/chat/a2ui/` 全部 11 文件（A2uiCardHost/PolangA2uiCatalog/AndroidA2uiJsonReader/HandleResolver/A2uiStyleMapper 等）+ `features/chat/a2ui/` 测试 5 件（A2uiComponentAvailabilityTest/PolangA2uiCatalogTest/A2uiComponentRenderSmokeTest/A2uiCatalogDescriptorSyncTest/BarChartSelectedBindingNormalizationTest） |
| 卸依赖 | `gradle/libs.versions.toml` + `androidApp/build.gradle.kts`：androidx.a2ui 三件套（a2ui-model/engine/compose-ui 1.0.0-alpha01） |
| 删分发 | `ChatScreen` when 删 A2uiCard 分支；`ChatViewModel` 删 data_ui 处理路径与相关 import |
| 删设置 | `CloudAgentClient` 删 `genUiFormat` 参数与 `X-GenUI-Format` 头；`UserSettingsRepository` 删 `genUiFormat`（DataStore `genui_format` key 停止读取，存量值自然失效）；设置页删「GenUI 协议选择」单选行；五语 strings 相关 key 清理 [I18N]；`CloudAgentClientTest` 头断言同步删 |
| 改 import | `CloudAgentGalleryTools`、`OpenUiCardHost`、`OpenUiNodeRenderer`、`OpenUi*Renderers`、`OpenUiCardTokens` 等 a2ui 包 import → openui 包新位置 |

### 3.4 文档 [DOC-SYNC]

| 文档 | 改动 |
|------|------|
| `docs/02-ARCHITECTURE/ADR/ADR-016` | **修订**（宪法「偏离须先修订本 ADR」）：D1 parts 清单去 A2uiCard + 顶部修订注记——A2UI 线 2026-11-05 退役（对照使命完成、OpenUI 已经生产验证、消灭双线校验执法成本；外部 androidx.a2ui 成熟后可重评估） |
| `docs/03-TECHNICAL-SPECS/CHAT_CARD_CATALOG.md` | `data_ui` 节改退役标注（保留历史登记 + 退役日期 + 指向本 spec 名）；`data_ui_openui` 节升为唯一 GenUI 线 |
| `server/AGENTS.md` | §1 云端 Agent 职责串删 A2UI/render_ui 表述；§5 决策表加退役行 |
| `shared/AGENTS.md` | §2 `domain/chat` 注记：子类型 11→10、a2ui 包拆分迁移记录 |
| `androidApp/AGENTS.md`、`features/settings/AGENTS.md` | Chat/设置行同步删 GenUI 选择表述 |
| `docs-site/a2ui/` | 删除（catalog/v1.json 仅自引用；CHAT_CARD_CATALOG 引用同步摘除） |
| `PRODUCT.md`/`FEATURES.md` | grep 核查 A2UI 提及，有则同步 |

## 4. 错误处理与兼容

- **旧版本 App**（无 OpenUI 渲染或不发头）：服务端硬切后一律收 turn-native 程序；无 OpenUI 渲染能力的旧端表现为程序原文文本化，不崩溃。测试期装机量小，接受（已定：硬切，不做 sunset）。
- **存量 `data_ui` 消息**：codec/converter 行级 Text 兜底，不丢消息（ADR-016 既定口径）。
- **服务端收到 `X-GenUI-Format` 头**：无视，不报错（旧客户端请求后向兼容）。
- **历史 `agent_event` 含 `html_card` 载荷**：解码分支保留，回放不炸。

## 5. 上线顺序（双交付铁律）

1. `./gradlew -p server build` 全绿 → bump `server/build.gradle.kts` version → `./server/deploy.sh` 部署（服务端先切 = 任意版本客户端都不会再收到 A2UI 卡，安全方向单一）。
2. App 侧拆除完成 → 发包（debug OTA + Play internal）。

## 6. 验收

- `./gradlew -p server build` 全绿（含改后 AgentRoutesTest/AgentRunnerTest）。
- `JITPACK=true ./gradlew :shared:jvmTest :shared:assemble` 全绿（assemble 门槛防 commonMain 纯度问题）。
- `./gradlew :androidApp:assembleDebug` + androidApp 单测全绿。
- `scripts/ai-gate.sh` 通过（含 check_doc_sync 第 5/6 项文档检查）。
- 真机冒烟：① 云端 Agent 回合正常出 OpenUI 卡（turn-native 链路流式预览→成卡）；② 打开含历史 A2UI 卡的会话不崩、显示兜底文本；③ 设置页无 GenUI 选择行。

## 7. Milestone B 方向登记（本 spec 不实施）

端侧推理直产 OpenUI 程序：端侧小文本模型（MNN 载体，选型待定）+ grammar/JSON-schema 约束解码输出 OpenUI Lang，复用 `OpenUiLibrary` 组件库 SSOT 与 `OpenUiEngine` 端侧校验做输出门禁；场景 = 离线兜底 / 省云端成本。前提决策（模型选型、约束解码在 MNN 的可行性 spike、质量基线）由单独 spec 立项。协议层无需为此预留任何服务端机制（端侧不经服务器，`X-GenUI-Format` 不保留）。

## 8. 风险

| 风险 | 缓解 |
|------|------|
| a2ui 包双用，误删 OpenUI 依赖件 | §3.2 拆分表逐件定性；`:shared:assemble` + androidApp 编译兜底 |
| 常量搬家后 prompt 漂移 | 引用同源不变 + 既有 golden 测试改钉新位置后必须全绿 |
| render_html 事件模型误删历史解码 | 解码分支保留有测试钉住（回放用例） |
| 硬切影响旧版本用户 | 已决策接受；表现为文本化不崩溃 |

## 9. 回退

服务端 git revert + `deploy-switch.sh` 回滚即恢复 A2UI 分流（拆除前 tag 留点）；App 侧回滚上一版本。两端独立可回退。
