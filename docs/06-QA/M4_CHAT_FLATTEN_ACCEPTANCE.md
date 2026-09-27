# M4 Chat 渲染拍平 + Turn 聚合验收记录（ADR-016 / spec §10 M4）

> **日期**：2026-09-27
> **范围**：①显式 RoundStarted 轮边界信号 + ②partId 分轨命名空间收口；§7.2 parts 拍平渲染（渲染源切 parts）；§7.3 Turn 聚合视觉层；§8 性能防护清单 7 项
> **spec**：`docs/superpowers/specs/2026-09-27-chat-parts-rendering-design.md` §3/§4/§7.2/§7.3/§8/§10
> **分支**：`feat/chat-parts-m4`（worktree `.worktrees/chat-parts-m4`）

## 1. 落地清单（四个 commit）

| commit | 内容 |
|--------|------|
| `9d8744d04` | 前置收口：`ChatStreamEvent.RoundStarted` 信号链（Koog `onLLMStreamingStarting` → RemoteChatEngine → ChatViewModel/ChatAgentBridge(iosMain) → `TurnStreamEvent.RoundStarted`）；`ChatStreamTurnAdapter` 显式信号闭合文本块为主、快照差分降为兜底；partId 分轨命名空间定稿（瞬态 `txt-N`/`call-N` vs 持久 `p0/p1`，写入 `MessagePart.partId` KDoc + spec §3 注记）；spec §4 事件图补 RoundStarted |
| `5fa46523d` | §7.2 渲染拍平：shared `domain/chat/ChatListFlattener` 纯函数（key=`messageId:partId` + contentType 复用桶 + turnIndex/isTurnStart/mergeWithPrevious/showCursor）；ChatScreen LazyColumn 按 flatItems 分发；`ChatAgentTextPart`（通栏 markdown）+ `ToolStatusChip` 组合件；ChatViewModel `toUiModel` parts 全量 `decodePartsOrLegacy` + `_pendingNonCardTool` + 媒体反馈两处内存快路径 parts 同源双写；i18n `chat_card_generate_failed` 五语 |
| `106c220b0` | §7.3 Turn 聚合视觉层：LazyColumn 去 spacedBy 改 per-item topGap 阶梯（index==0→0 / isTurnStart→`Spacing.lg` 16 / mergeWithPrevious→`Spacing.xs` 4 / else→`Spacing.sm` 8，design-tokens.json 零变更取最近档）；chat.yaml 三同步回填（§3.1 间距档 + §3.2 user_note/streaming_note） |
| `a143e14cf` | §8 性能清单 7 项：`compose-stability.conf` 稳定性白名单（domain.chat 整包 + MediaAsset/MediaType/FeedbackAction，shared 无 compose-runtime 依赖、白名单替代 @Immutable）；🔴 修拍平引入的滚动回归（滚底/回锚 index 从消息粒度改 flatItems item 粒度，effect key 收窄）；hasTaskCards remember 派生；isScrollInProgress 读取上移列表层；`rememberChatImageIsLive`（File.exists 移出组合期，produceState+IO 乐观初值 true）；collectAsStateWithLifecycle 全量 26 处；AiChatScreen 深色气泡收口（`PoLangForcedDarkTheme` + colorScheme 语义色，面板底色保持纯黑） |
| `612b59e75` | **GLM review 🟡1**：`compose-stability.conf` 整包递归通配收窄为显式不可变清单（逐个核对 val-only，类级 `**` 限 sealed 嵌套；streaming 包可变装配类 ChatStreamTurnAdapter/TurnPartsReducer/StreamingPacingController 排除——原通配把含 var+mutableMap 的类声明 stable，静默 stale-UI 埋雷） |
| `db742fb87` | **GLM review 🟡2**：loadMessages 全量 decode 挪出主线程——decode 段挂 `messageDecodeDispatcher`（默认 Default，经 `ChatViewModelDependencies` 显式注入；写死 Default 会逃出 runTest 调度器致 GachaTest 竞态失败，测试侧统一传 `Dispatchers.Main`）；共享态（deliver override/engineerTasks/gacha）仍主线程读写 |
| `a74544bee` | **GLM review 🟡3**：流式卡 OUTPUT_AVAILABLE 跳过以「产物行已在列表中」为前提——负载等值锚定本 turn 产物行（Room content 与 part 负载同源同值），窗口期占位 part 按已填充负载原位渲染防闪失/位置跳变；测试改 1 例 + 新增 2 例钉桩 |
| `db8284a95` | **GLM review 🔵7**：user 角色判定收口 `roleOf` 单点（ChatScreen/FloatingChatBubbleService 枚举列举式 isUser 改 modelRole；顺带修悬浮气泡 USER_IMAGE_TEXT 配色漂移） |

## 2. 编译与单测证据（2026-09-27，全部在 worktree 实测）

- `JITPACK=true ./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest :shared:jvmTest` —— **BUILD SUCCESSFUL**（块4 提交前全量重跑）
- `JITPACK=true ./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest :shared:jvmTest :shared:compileKotlinIosArm64` —— **BUILD SUCCESSFUL**（review 修复后 2026-09-28 全量重跑，含 iosArm64）
- `JITPACK=true ./gradlew :shared:compileKotlinIosArm64` —— **BUILD SUCCESSFUL**（块1 iosMain `ChatAgentBridge` when 分支的 iOS 侧编译确认）
- `python3 scripts/gen-design-tokens.py --check` —— ✅ 生成物与 SSOT 一致（7 个文件）
- 新增在树单测：`ChatListFlattenerTest` 15 例（拍平规则全枚举：USER 整颗单 item / claude/COMMAND/PLAN_PREVIEW/AGENT_IMAGE/AGENT_EDIT_RESULT legacy 直通 / 流式卡 part 三分流——review 🟡3 后口径为 OUTPUT_AVAILABLE **且产物行在场**才跳过、窗口期原位渲染、旧 turn 历史卡行不误判 / INPUT_* 占位 / OUTPUT_ERROR 错误态 / 流式开放 Text 块 textOverride 保留打字机节拍 / key 与 turn 标记）；块1 streaming 包 4 例（adapter 3 + reducer 1）

## 3. 真机冒烟与实测数据（⏳ 待补）

> 2026-09-27 收尾时设备（R5CXB29ZY3F）未连接，`adb devices` 为空、无模拟器。以下验收项代码侧就绪，待设备可用后补测并回填：

- [ ] 装包冒烟：发消息触流式回复（打字机节拍 / RoundStarted 轮边界闭合 pre-tool 文本）、`/html`、`/chart`、长列表滚动、任务卡（`/task`）
- [ ] spec §10 M4 验收项「流式期间重组范围实测收窄」：Layout Inspector / 重组计数对比（stability 白名单生效的实测证据，目前为语义推断）
- [ ] 截图基线对比：`scripts/screenshot-diff.py` 对 `scripts/screenshot-baseline/chat/` 更新/确认 M4 基线（Turn 聚合间距阶梯为首帧视觉变化点）
- [ ] 🔵4（M4 review，不修登记）：新轮首拍状态文案残留（≤50ms）——真机流式期观察首拍帧是否有可感知的文案闪烁
- [ ] 🔵5（M4 review，不修登记）：`isListScrolling` 读取点可再收窄（当前列表层单次读取已可接受）——真机滚动 + HTML 卡同屏时确认无抖动即可，不追求进一步收窄
- [ ] 🔵6（M4 review，不修登记）：`TYPE_TOOL_ERROR` 通用文案未消费 `toolErrors.errorText`（低优先；直显前需过 DiagSanitizer 口径）——真机触发一次 OUTPUT_ERROR（如断网跑 `/chart`）确认通用失败文案展示正常

## 4. 已知瞬态行为与保留项

- **流式中途 pre-tool 文本位置**：流式开放 Text 块（txt-0）渲染在已落库卡行之下，finalize（轮边界闭合/落库）时原位归位消失——与 legacy 基线行为一致或更好，非回归
- **瞬态 parts 不落 Room**：M4 口径不变（M2 起），流式→落库边界 messageId 变更属基线行为（partId 分轨命名空间已保证各生命周期内 key 恒定）
- **AiChatScreen 面板底色**：保持纯黑不动（spec §8-7 只点名气泡；改 background 会引起相机页浮层视觉回归）；`surface.copy(α)` 三处半透底改 `onSurface.copy(α)` 后深色模式下由「近不可见」恢复为可见浅色叠加（对齐浅色模式观感，属修复）
- **§8-1 重组收窄**：当前证据为稳定性白名单语义（domain.chat 全 val-only，约定写入 conf 头注释），实测数据待 §3 补测
