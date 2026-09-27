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

## 2. 编译与单测证据（2026-09-27，全部在 worktree 实测）

- `JITPACK=true ./gradlew :androidApp:compileDebugKotlin :androidApp:testDebugUnitTest :shared:jvmTest` —— **BUILD SUCCESSFUL**（块4 提交前全量重跑）
- `JITPACK=true ./gradlew :shared:compileKotlinIosArm64` —— **BUILD SUCCESSFUL**（块1 iosMain `ChatAgentBridge` when 分支的 iOS 侧编译确认）
- `python3 scripts/gen-design-tokens.py --check` —— ✅ 生成物与 SSOT 一致（7 个文件）
- 新增在树单测：`ChatListFlattenerTest` 13 例（拍平规则全枚举：USER 整颗单 item / claude/COMMAND/PLAN_PREVIEW/AGENT_IMAGE/AGENT_EDIT_RESULT legacy 直通 / 流式卡 part 三分流 OUTPUT_AVAILABLE 跳过禁双显·INPUT_* 占位·OUTPUT_ERROR 错误态 / 流式开放 Text 块 textOverride 保留打字机节拍 / key 与 turn 标记）；块1 streaming 包 4 例（adapter 3 + reducer 1）

## 3. 真机冒烟与实测数据（⏳ 待补）

> 2026-09-27 收尾时设备（R5CXB29ZY3F）未连接，`adb devices` 为空、无模拟器。以下验收项代码侧就绪，待设备可用后补测并回填：

- [ ] 装包冒烟：发消息触流式回复（打字机节拍 / RoundStarted 轮边界闭合 pre-tool 文本）、`/html`、`/chart`、长列表滚动、任务卡（`/task`）
- [ ] spec §10 M4 验收项「流式期间重组范围实测收窄」：Layout Inspector / 重组计数对比（stability 白名单生效的实测证据，目前为语义推断）
- [ ] 截图基线对比：`scripts/screenshot-diff.py` 对 `scripts/screenshot-baseline/chat/` 更新/确认 M4 基线（Turn 聚合间距阶梯为首帧视觉变化点）

## 4. 已知瞬态行为与保留项

- **流式中途 pre-tool 文本位置**：流式开放 Text 块（txt-0）渲染在已落库卡行之下，finalize（轮边界闭合/落库）时原位归位消失——与 legacy 基线行为一致或更好，非回归
- **瞬态 parts 不落 Room**：M4 口径不变（M2 起），流式→落库边界 messageId 变更属基线行为（partId 分轨命名空间已保证各生命周期内 key 恒定）
- **AiChatScreen 面板底色**：保持纯黑不动（spec §8-7 只点名气泡；改 background 会引起相机页浮层视觉回归）；`surface.copy(α)` 三处半透底改 `onSurface.copy(α)` 后深色模式下由「近不可见」恢复为可见浅色叠加（对齐浅色模式观感，属修复）
- **§8-1 重组收窄**：当前证据为稳定性白名单语义（domain.chat 全 val-only，约定写入 conf 头注释），实测数据待 §3 补测
