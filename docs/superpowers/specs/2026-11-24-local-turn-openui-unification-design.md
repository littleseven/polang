# 本地回合 OpenUI 统一（chat 回复内容界面统一到 OpenUI）设计 spec

> **日期**：2026-11-24
> **状态**：待用户批准（brainstorming 产物）
> **起因**：用户判断「本地体验已经落后云端，应全部统一到 OpenUI」，澄清为——**本地 Agent（端侧 Koog runtime）保留，chat 回复内容界面统一到 OpenUI**。
> **翻案声明**：本文正式修订《分层 Agent Runtime》spec（2026-11-07）§3 决策 1「L7 产出协议不合线」。新决策：**L7 合线为「turn-native OpenUI 唯一产出协议」**——本地回合终结回复与云端同一形态（整颗 OpenUI Lang 1.0 程序），兑现「One Agent, Two Runtimes，产出协议同源」。

---

## 1. 意图与为什么

云端回合（`/agent` 链路）已是 OpenUI 回合原生：整个终结回复就是一颗程序，`openui_delta` 流式渐进预览、ProgramGuard 校验回环、布局门禁、五语协商、「端上永远收到合法树」双保险。本地回合（端侧 Koog，经 `/llm` 网关）终结回复仍是纯文本气泡——视觉形态、活感、交互能力（@Set/@Run/@ToAssistant 动作、local_handle 照片填充）全面落后。

用户要的是：**同一个 chat 界面里，不管回合跑在端上还是云上，用户看到的回复内容界面是同一种东西**。runtime 落点继续是部署差异（成本考虑本地保留），产出协议不再是差异。

## 2. 目标 / 非目标 / 反目标

**目标**

1. 本地回合终结回复 = OpenUI Lang 1.0 程序（纯对话也是 `root = Markdown("…")`，与云端同契约）。
2. 流式渐进预览与云端一致（程序前缀渐进渲染，轮边界重置）。
3. 端侧「屏上永远是合法树」双保险：策略层纠正回环（FinalOutputCorrector 对偶 ProgramGuard）+ 终结落库前 ensureValidProgram 兜底。
4. 终结落库 `data_genui_program`（OpenUiCard part），与云端卡同渲染、同回灌口径（data part 不回灌 LLM）、同性能行装配。
5. prompt 契约/校验器/兜底变换**同一声明源在 shared**，server 与端侧都真引用（消灭第四份双写）。
6. Android 先行、iOS 镜像（shared 层变更天然惠及 iosMain chat 链路；SwiftUI 渲染器已就绪）。
7. 回滚闸：可一键退回纯文本路径。

**非目标**

- 不动工具产物卡（Chart/HtmlCard/任务卡/直播卡）——它们是工具产物而非终结回复，云端链路同样保留这些卡（run_gallery_script 产物拦截 HTML 卡、任务卡、直播卡），本次只统一**终结回复通道**。
- 不动本地 runtime 拓扑：Koog 在端、工具在端、记忆在端（DataStore chat_memory）、意图路由/工具子集裁剪照旧。
- 不改云端链路行为：server 侧只改 import 来源（真引用 shared），输出逐字节钉住。
- 不迁移 Room 历史消息：存量文本气泡消息原样渲染，只新回合走 OpenUI。
- 不处理 IM 通道（飞书/Telegram）的文本形态：turn-native 后 IM 收到程序原文，与云端 IM 现状一致，不在本次放大或收窄。

**反目标（做到也算失败）**

- 契约/校验器在 shared 与 server 各存一份手抄副本（必须真引用，同 M1/M1.5 治理模式）。
- 本地回复出现「程序源码糊上屏」的降级路径（嗅探失败必须兜底包装，不得直出原文）。
- 为统一而牺牲可回退性（无回滚闸直接切）。

## 3. 现状盘点（侦察结论）

本地回合链路（全部已核实）：

- `RemoteChatEngine.streamChat` → 意图路由子集 → `KoogChatAgent.runChat` → `poLangSingleRunStrategy(PoLangReActStrategyConfig(toolLoopGuard=…))`（**非流式 + 无纠正**的内建节点路径）→ summary 包 `AgentCommand.TextReply` → ChatViewModel `insertAgentMessage` 落文本气泡。
- 流式文本气泡已存在：EventHandler `onLLMStreamingFrameReceived` 累积 TextDelta → `ChatStreamEvent.TextSnapshot`；`onLLMStreamingStarting` → `RoundStarted` 轮边界。
- 策略工厂 M1 已参数化：`streaming` / `finalOutputCorrector` / `onStreamingFallback` / `toolLoopGuard` 四参齐备，云端 guardedStrategy 图已在 shared。
- 端侧 OpenUI 资产已齐备：shared `OpenUiEngine`/`OpenUiSession`（含 forwardRefSkeletons 流式骨架）、`OpenUiProgramSummary`（嗅探/fallbackSummary）、`OpenUiContract.LANG_VERSION`；Android `OpenUiCardHost`/`OpenUiStreamingCard` + ChatScreen TYPE_OPENUI_CARD 分支 + `insertOpenUiCardMessage` 落库先例；iOS SwiftUI 渲染器 + `IosOpenUiPreviewFactory`（2026-11-12 就绪）。
- 校验栈在 server：`RenderOpenUiValidator`（仅 import shared `OpenUiContract`/`OpenUiLibrary`）、`OpenUiLayoutValidator`（零外部 import）、`OpenUiLayoutNormalizer`/`OpenUiStringScan`/`OpenUiCallTree`、`ProgramGuard`（实现 shared `FinalOutputCorrector`，仅 JVM AtomicInteger 一处平台依赖）、`OpenUiTurnNative`（SYSTEM_PROMPT/GOLDEN_CHAT_PROGRAM/ensureValidProgram，引用 shared `ReplyLanguage` 五语文案 SSOT）。

**已识别的实现 gap（设计内消化，不阻塞）**：

1. `guardedStrategy` 目前**不挂 toolLoopGuard 熔断边**（仅 builtinOneShotStrategy 有）——本地切 guarded 配置会丢熔断，需补边（config 字段已在）。
2. Koog 1.3.0 流式路径**不触发 `onLLMCallCompleted`**（server 0.9.17 事故）：本地切 streaming 后 token 指标会归零——需在 `onLLMStreamingFrameReceived` 的 `StreamFrame.End` 分支累加 metaInfo（server 同修法），非流式回退路径仍由 `onLLMCallCompleted` 覆盖，两路互斥。
3. ProgramGuard「每轮新实例」语义：server 每回合构建策略图故天然满足；本地 AIAgent 跨回合缓存——corrector 需按回合重置（runChat 开头 reset，与 toolLoopGuard.reset() 同点位），不得跨回合累积计数。

## 4. 设计（已定的部分）

### U1 · shared 收口（产出协议 SSOT 面）

server `agent/` 包六件上提 shared `domain/chat/openui/`（纯 Kotlin，commonMain 可落地）：

| 件 | 处置 |
|----|------|
| `RenderOpenUiValidator` / `OpenUiStringScan` / `OpenUiCallTree` | 原样上提，包名改 `domain.chat.openui` |
| `OpenUiLayoutValidator` / `OpenUiLayoutNormalizer` | 原样上提 |
| `OpenUiTurnNative` | 上提为 `OpenUiTurnNativeContract`（SYSTEM_PROMPT / GOLDEN_CHAT_PROGRAM / ensureValidProgram / escapeOpenUiString；slf4j 换 shared `Logger`） |
| `ProgramGuard` | 上提为 `OpenUiProgramGuard`，`java.util.concurrent.atomic.AtomicInteger` → `kotlin.concurrent.atomics.AtomicInt`（KoogChatAgent 已有先例），继续实现 `FinalOutputCorrector` |

server 六件改真引用 shared（删本体或留 typealias 过渡，优先后者零 diff 风险——实现者定）；server 测试（OpenUiPromptAssetsTest / RenderOpenUiValidatorTest / OpenUiLayoutValidatorTest / OpenUiTurnNativeTest）钉住行为零变化。

`OpenUiPromptAssets.DSL_RULES` 同为 server 资产：随 SYSTEM_PROMPT 依赖一并上提（SYSTEM_PROMPT 内嵌引用，不可分）。上提后 server `OpenUiPromptAssets` 若还有其余消费面（工具描述段等）保留 server 侧壳、真引用 shared 常量。

**契约参数化（builder-check Q2 裁定，我的判断）**：SYSTEM_PROMPT 上提后不是单个常量，而是「常量主体 + 两个变体槽」——① 身份行（云端「你是 PoLang 云端 Agent」/ 端侧措辞）；② `server_time` 工具句（端侧 registry 无此工具，该句剔除）。server 以默认参数组装，输出与现网**逐字节一致**（测试钉住）；端侧传端侧变体。同一声明源、参数化差异，不构成手抄副本。契约其余部分（DSL 规则、数据禁令、组件清单）两端逐字相同。

### U2 · 本地链路切换（Android 主线）

1. **prompt**：chat system prompt 恒追加 turn-native 契约段（`OpenUiTurnNativeContract.SYSTEM_PROMPT` 本体 + `openUiReplyLanguageRuleSegment` 五语段），位置在技能清单段之后恒最末；子集裁剪路径（toolSetOverride）同样追加。`ChatPromptRules` 中与「回复即程序」冲突的自然语言回复指引按节修订（规则节有稳定 id，按节审查）。
2. **策略**：`KoogChatAgent.buildAgent` 切换为 `PoLangReActStrategyConfig(streaming = true, finalOutputCorrector = <per-turn guard>, toolLoopGuard = toolLoopGuard)`；补 guardedStrategy 的 loopGuard 熔断边；corrector 每回合 reset；`onStreamingFallback` 接 shared Logger。
3. **metrics**：`StreamFrame.End` 分支累加 token（修法同 server 0.9.17），与 `onLLMCallCompleted` 两路互斥。
4. **流式预览**：ChatViewModel/turn 装配层对 TextSnapshot 做程序前缀嗅探——命中即切 `OPENUI_STREAM_PREVIEW_ID` 瞬态预览消息（复用云端 `OpenUiStreamingCard` + forwardRefSkeletons）；RoundStarted 重置预览。首帧即判（模型按契约首行写 `// summary:`，从第一帧即呈程序形态）。
5. **终结兜底与落库**（builder-check Q3 裁定，我的判断）：
   - `ensureValidProgram` 放 **engine 层**（`processChatReAct` 成功包装处，`streamChatReAct` 组装 `StreamChatResult` 前）——`fullResponse` 对一切消费方（UI/IM/TextReply）恒为合法程序，与云端「server 落流前已兜底」口径一致。
   - ChatViewModel 设**统一嗅探分流点**：assistant 文本落库前（`handleAgentAction` TextReply 分支、dispatch-fallback 分支、纯文本分支）经同一嗅探函数——程序形态 → OpenUiCard part（复用 `insertOpenUiCardMessage` part 构造；`modelUsed` 用本地模型标签而非 `cloud_agent`；`langVersion = OpenUiContract.LANG_VERSION` 本地真值，无服务器协商）；非程序形态理论不可达（engine 已兜底），真到则落文本气泡（防御）。性能元数据走既有 `performanceMetadataJson` 装配。
   - **用户停止半截路径**：已嗅探为程序形态的半截内容 = 瞬态预览，**丢弃不落库**（云端同口径——预览从不落库）；仍处文本形态的工具轮叙述半截 → 保留现行为（落文本）。半截程序原文永不可落文本气泡（反目标）。
6. **回滚闸**：DataStore 设置项（默认开）+ 调试页开关；关 = 现行纯文本路径逐字节保留（策略回 builtinOneShot、prompt 不追加契约段、落库回文本气泡）。机制（builder-check Q4 裁定）：per-turn 读取，纳入 `getChatAgent` 的 configChanged 比对（切换后下一条消息生效，重建 AIAgent）；契约段在 `toolSetPromptCache` 缓存的 base prompt **之外**追加（同技能清单段层位），子集缓存键不受污染。
7. **IM/无 UI 通道**：`StreamChatResult.commands` 语义不动（TextReply 携带 engine 兜底后的程序文本——与云端 IM 收到 server 兜底文本同口径），IM 通道零改动。
8. **打字机与中间轮**（builder-check Q5 裁定，我的判断）：工具轮之间的叙述文本（非程序形态）保持现行文本气泡 + pacingController 打字机；终结轮程序首帧命中嗅探后切换为预览卡（云端同样只有终结轮是程序，天然同构）。
9. **端侧工具句柄回执（2026-11-24 用户拍板选项 A，builder-check Q1）**：本地三工具（`search_media` / `refine_media_search` / `get_gallery_summary`）回执尾部附 `local://scan/<12hex>#photos` 句柄并注册 `LocalHandleStore`——对齐云端工具桥（`CloudAgentGalleryTools`）既有生产机制，iOS 复用 `IosCloudAgentToolBridge` 句柄注册先例。工具描述改写为「media id → handle」双轨措辞：media id 保留（收藏等写工具与现有工具卡消费），handle 专供 OpenUI 程序绑定；`media://` 写入程序在端侧同样被数据禁令拒绝。落地后 PhotoGrid/Photo/Video 组件在本地终卡可用，契约数据禁令段两端逐字相同（无需第三个变体槽）。

### U3 · iOS 镜像

shared 层（prompt/策略/校验/兜底）变更天然生效于 iosMain chat 链路；iOS 侧接线：ChatViewModel 终结嗅探落 `data_genui_program` + 流式预览接 `IosOpenUiPreviewFactory` + 回滚闸镜像。口径逐条对齐 Android U2。

### U4 · 文档与决策落档

- 分层 runtime spec §3 决策 1 修订（L7 合线声明 + 本 spec 指针）；L7 行「产出 Profile」表述更新。
- `CHAT_PROTOCOL_TECH_SPEC.md`（本地回合段）、`CHAT_CARD_CATALOG.md`（data_genui_program 节增本地回合产出来源）、`shared/AGENTS.md`（openui 包新件登记）、`androidApp/AGENTS.md`（chat 模块链路口径）、`server/AGENTS.md`（六件真引用收口）。

## 5. 约束

- [PRIVACY]：程序/摘要不含媒体文件与隐私细节（summary 只含计数/结论，契约已有，本地沿用）。
- [I18N]：契约段五语化复用 shared `ReplyLanguage` SSOT；新增 UI 文案（回滚闸设置项等）五语同步。
- [PARITY]：iOS 镜像是交付条件，不是可选项。
- server 构建/行为零变化：U1 合入以 server 全量测试绿 + 输出逐字节断言为准。
- 工作区隔离：实现开工前建 `.worktrees/` 隔离工作区（AGENTS.md §3.4）。

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| 模型契约遵从度（本地子集裁剪路径 prompt 拼装不同） | 子集/全量两路径各自的契约遵从真机验证矩阵；纠正回环 + 兜底双保险兜底 |
| token 成本上升（契约段 + 组件清单每轮约数千 tokens） | 与云端同源同成本结构（用户已接受）；prompt 前缀缓存天然受益（契约段位置稳定恒最末） |
| 尾延迟增加（纠正回环最多 +1 轮 LLM） | MAX_CORRECTIONS=1 与云端一致；流式预览掩盖首字节后延迟 |
| 纯对话从气泡变 OpenUI 卡的 UX 变化（复制/无障碍/视觉密度） | 云端已验证形态；回滚闸保底；真机对比验收 |
| streaming 切换引入 metrics/行为漂移 | gap §3 已列修法；双路互斥测试钉住 |
| AIAgent 跨回合缓存致 corrector 计数累积 | 回合开头 reset（同 toolLoopGuard 点位），测试钉住 |

## 7. 验收要点

1. `JITPACK=true ./gradlew :shared:jvmTest` 绿（新增：校验器/兜底/守卫 commonTest 移植 + loopGuard 边 + End 帧 metrics）。
2. `./gradlew -p server build` 绿（真引用收口零行为变化）。
3. `./gradlew :androidApp:compileDebugKotlinAndroid` + 相关单测绿。
4. 真机闭环（auto-dev-loop）：本地回合——纯对话出 Markdown 程序卡、数据问答出组件卡、流式预览渐进生长、故意非法输出被纠正/兜底、回滚闸切换两态对等。
5. iOS 模拟器/真机同场景镜像验收。

## 8. 留给实现者的判断

- 六件上提的 server 侧过渡形态（typealias vs 直接改 import）。
- 预览嗅探的节流参数（对齐 server `OpenUiContentStreamTracker` 的 ≥48 字符 / ≥80ms 还是端侧自调）。
- 契约段与技能清单段的精确拼接序（两段都声称「恒最末」，需定一个）。
- ChatPromptRules 冲突节的具体修订措辞。

## 9. 用户已拍板

- 本地 Agent 保留（runtime 拓扑不动）。
- chat 回复内容界面统一到 OpenUI（= 终结回复 turn-native 化，本 spec 全部范围）。

## 10. 我的判断（用户可推翻）

- 工具产物卡本次不动（§2 非目标）——「回复内容界面统一」按云端口径对齐，工具卡云端也保留。
- 回滚闸用运行时 DataStore 设置（默认开）而非编译期常量——利于灰度与真机 A/B。
- 本地回合的 langVersion 直接写 `OpenUiContract.LANG_VERSION`（本地校验器与渲染器同源，无协商需求）。
- 契约参数化（两个变体槽，server 默认逐字节钉住）、ensureValidProgram 层位（engine 层）、回滚闸缓存键机制、打字机口径——builder-check Q2~Q5 的裁定，见 §4 对应条目。

## 11. 已拍板（2026-11-24）：本地回合照片/视频展示通路 = 选项 A

端侧三工具回执附 handle + LocalHandleStore 注册（工作项见 §4 U2.9）。本地终卡可用 PhotoGrid/Photo/Video 组件，统一名副其实。
