# 任务中心页（Task Center）实施计划

> Spec：`docs/superpowers/specs/2026-09-25-engineer-task-card-design.md` US-12~16 + D7
> 范围决策：任务中心**提前落地**（spec §8 明确允许：「数据源只读，可提前做」），**不等 P2 网关改造**；US-4~6（断连对账/本地通知/进程重建恢复）仍属 P3，不在本期。

## 需求梳理（US-12~16 落地口径）

| US | 需求 | 本期实现方式 |
|----|------|-------------|
| US-12 | Chat 顶栏任务图标入口（活动任务角标计数）+ 任务卡「查看全部」次入口 | ChatTopBar actions 加 BadgedBox 图标按钮；EngineerTaskCard 加可选 `onViewAll` 参数 |
| US-13 | 分区「进行中」（含待审批，开始时间倒序）/「历史」（终态，结束时间倒序，最近 50 条）+ 空态引导 | TaskCenterViewModel 纯函数分区（JVM 可测） |
| US-14 | 列表项 = 任务卡同构紧凑形态（标题+状态 chip+meta 行+摘要）；审批中项高亮置顶带动作按钮 | 复用 EngineerTaskCard 的 chip/meta（提为 internal），新建紧凑 Composable |
| US-15 | 点击列表项回 chat 锚定对应任务卡 | Activity 级 anchor 请求态（仿 gallerySearchRequest 先例）+ 切会话 + 滚动定位 |
| US-16 | 进行中任务页内实时刷新 | Room Flow 驱动（结构性事件已逐条落库，天然刷新，无需新通道） |
| D7 | 只读管理 + 审批动作（继续/交付/重试/暂不/到此为止），不做编辑/删除 | 复用 ChatViewModel 审批动作，补 sessionId 参数化 |

## 关键事实（已勘探确认）

- 任务状态持久层 = Room `chat_messages` 表 `type='task_card'` 行，metadata JSON `engineer_task` 含全量 16 字段（`ChatModelCommonMainShim.kt:123-176`）；`content` 列 = sourceText 前 50 字可直接做列表标题
- **DAO 无跨会话按 type 查询**（`ChatMessageDao.kt` 13 个方法全是单会话）；type 是 TEXT 列，新增 @Query **零迁移**（不动 schema，Room 版本 23 不变）
- 状态机五态：`EngineerTaskStatus { RUNNING, AWAITING_CONTINUE, AWAITING_DELIVER, COMPLETED, FAILED }`（`shared/.../domain/chat/EngineerTaskState.kt:8`）；「进行中」判据 = status ∈ {RUNNING, AWAITING_CONTINUE, AWAITING_DELIVER} 且 resolution == null
- **ChatViewModel 是 Activity 级单例**（`MainActivity.kt:133-135`），任务中心页（同 Activity NavHost destination）可拿到同一实例复用审批动作
- 审批动作现状全部用 `_currentSessionId.value` 落库（`ChatViewModel.kt:655-723`）；`resolveEngineerTask(sessionId, ...)` 本身已参数化
- ChatScreen 不持有 NavController，二级跳转走 MainPagerHost lambda（`MainPagerHost.kt:175-197`，先例 :180 navigate Settings）
- 回 chat 锚定无先例，最贴切范式 = Activity 级 `gallerySearchRequest` 状态驱动（`MainActivity.kt:209` + MainPagerHost 消费）
- 无 BadgedBox 使用先例，角标需自建；`AppTopBarAction` 无角标参数（`features/common/topbar/AppTopBar.kt:163-183`）

## 实施步骤

### Step 0：工作区隔离（AGENTS.md §3.4 强制）
- `.worktrees/` 下建 worktree + 分支 `feat/task-center`，后续全部改动在该 worktree 进行

### Step 1：数据层 — DAO 跨会话查询
- `data/local/ChatMessageDao.kt` 新增：
  ```kotlin
  @Query("SELECT * FROM chat_messages WHERE type = 'task_card' ORDER BY timestamp DESC")
  fun getTaskCardMessages(): Flow<List<ChatMessageEntity>>
  ```
- 不动 schema、不加索引（任务卡量级小），Room 版本保持 23

### Step 2：纯逻辑分区（测试接缝）
- 新建 `features/chat/engineer/TaskCenterPartition.kt`（纯函数，对齐 EngineerTaskReducer 先例）：
  - 输入 `List<ChatMessageEntity>` → 解析 `parseEngineerTaskState`（解析失败跳过）→ 携带 sessionId/sessionTitle
  - 输出 `TaskCenterList(active: List<TaskCenterItem>, history: List<TaskCenterItem>)`
  - active：非终态且 resolution==null，**审批中（AWAITING_*）置顶**，其余按 startedAtMs 倒序
  - history：终态或已裁决，按 updatedAtMs 倒序，**取前 50**
- 新建 `androidApp/src/test/.../engineer/TaskCenterPartitionTest.kt`：分区判据/审批置顶/排序/50 条上限/解析失败跳过/resolution 终态化 分支矩阵

### Step 3：TaskCenterViewModel + DI
- 新建 `features/chat/taskcenter/TaskCenterViewModel.kt`：
  - `combine(chatMessageDao.getTaskCardMessages(), chatSessionDao.getAllSessions())` → `TaskCenterUiState`（sessionId→title 映射 join）
  - 暴露 `uiState: StateFlow<TaskCenterUiState>`
- `di/AppContainer.kt` 按 `MemoriesViewModelFactory` 先例（:211-229/:892-899）加 `createTaskCenterViewModelFactory()`

### Step 4：审批动作 sessionId 参数化（ChatViewModel 小改）
- `deliverEngineerTask`/`skipEngineerDeliver`/`abandonEngineerTask` 增加 `sessionId: String? = null` 参数（默认 `_currentSessionId.value` 保持 chat 内调用零改动）
- 新增 `fun primeEngineerTask(sessionId: String, taskId: String)`：`_engineerTasks` 无该卡时从 Room `getMessageById` 解析补种（防跨会话动作时 map 未加载导致 resolve 空转）
- 新增任务中心动作入口：
  - `deliver/skip/abandonFromCenter(sessionId, taskId)`：prime → 带 sessionId 调原方法 → **页内完成不跳转**
  - `continue/retryFromCenter(sessionId, taskId)`：prime → 非当前会话先 `switchSession(sessionId)` → 调原方法 → 由 UI 层返回 chat（新回合新卡在 chat 可见）
- 新增 `activeEngineerTaskCount: StateFlow<Int>`：Room 任务卡流解析过滤非终态计数（顶栏角标数据源，跨会话口径）

### Step 5：任务中心页面 UI
- 新建 `features/chat/taskcenter/TaskCenterScreen.kt`：
  - Scaffold + 顶栏（返回 + 标题）
  - LazyColumn 两分区：「进行中」section（审批中项高亮底色 + 动作按钮行：继续/到此为止 或 交付 push/暂不；RUNNING 项只读无动作）→「历史」section（FAILED 项带「重试」按钮）
  - 紧凑列表项 Composable `TaskCenterListItem`：标题（content）+ 状态 chip + meta 行 + 进度/结果摘要
  - 空态：双区皆空时引导文案
- `EngineerTaskCard.kt`：`EngineerTaskStatusChip`、`taskMetaText`、`formatElapsed` 提为 internal 供复用；新增可选参数 `onViewAll: (() -> Unit)? = null`（卡片头部右侧小按钮，chat 语境传入，US-12 次入口）
- 设计语言对齐现有 chat 卡片（Surface/圆角/spacing 走 DesignTokens）

### Step 6：路由 + 入口接线
- `navigation/Screen.kt` 加 `object TaskCenter : Screen("task_center")`
- `MainActivity.kt` NavHost 注册（仿 LlmLog/SwipeReview 先例 :434-442/:743-747）：composable 内取 Activity 级同一 ChatViewModel + `viewModel(factory = app.container.createTaskCenterViewModelFactory())`
- **顶栏入口**：`ChatScreen.kt` `ChatTopBar`（:914-941）actions 区加任务图标（Icons.Rounded.Assignment 类），`BadgedBox` 角标显示 `activeEngineerTaskCount`（>0 才显示）；新增 `onNavigateToTaskCenter` 回调
- `MainPagerHost.kt`（:175-197 Chat 组合点）把回调接为 `navController.navigate(Screen.TaskCenter.route, navOptions { launchSingleTop = true })`
- `ChatScreen.kt:593-604` EngineerTaskCard 调用点传 `onViewAll = onNavigateToTaskCenter`

### Step 7：回 chat 锚定（US-15）
- `MainActivity.kt` 加 `chatTaskAnchorRequest by remember { mutableStateOf<Triple<String, String, Long>?>(null) }`（sessionId, taskId, nonce），仿 `gallerySearchRequest`（:209）先例
- 任务中心项点击/继续/重试动作 → 设值 + `popBackStack()` + MainPagerHost 消费时 `switchMainPage(MAIN_PAGE_CHAT)`
- `ChatScreen` 消费：LaunchedEffect(anchor) → 非当前会话先 `chatViewModel.switchSession(sessionId)` → messages 含 taskId 时 `listState.scrollToItem(index)` → 回调 consumed 置空

### Step 8：五语 i18n
- `values/`、`values-zh-rCN/`、`values-zh-rTW/`、`values-es/`、`values-fr/` 五份 strings.xml 同步新增：task_center_title / task_center_section_active / task_center_section_history / task_center_empty / task_center_view_all / task_center_cd_icon（角标 contentDescription）等

### Step 9：验证闭环
- `./gradlew :androidApp:compileDebugKotlin` 编译
- 跑新增 JVM 单测 + 既有 `ChatViewModelEngineerTaskTest` 防回归
- adb 安装真机走查：工程师模式跑一个任务 → 顶栏角标出现 → 进任务中心 → 进行中区实时刷新 → 截断/交付审批动作 → 点击项回 chat 锚定
- 按交叉原则派 GLM review 子 agent 审 diff

### Step 10：文档同步（[DOC-SYNC] 红线）
- `androidApp/AGENTS.md`：§1.2 NavHost 路由表加 TaskCenter 行、§2.1 Chat 行补任务中心、§3.2 集成点表加任务中心行
- spec `2026-09-25-engineer-task-card-design.md` §8 分期表注记：任务中心（US-12~16）已于 2026-09-26 提前落地（只读+审批动作，不含 US-4~6 回联）
- 根 `AGENTS.md` §7 工程师任务卡索引行状态更新
- 计划落档 `docs/superpowers/plans/2026-09-26-engineer-task-center.md`（对齐 P1 计划先例）

## 明确不做（沿用 spec §6）
- US-4~6 断连对账/本地通知/进程重建恢复（P3 剩余部分，依赖 P2 网关）
- 任务编辑/删除/清理/搜索/筛选（D7）
- P2 网关改造（状态接口、cost 补发、409 单任务）
- iOS 端（工程师模式 Android 独占，走 /ios-follow 另议）

## 风险与对策
| 风险 | 对策 |
|------|------|
| 跨会话动作时 `_engineerTasks` 未加载该会话卡片导致 resolve 空转 | `primeEngineerTask` 先从 Room 补种（Step 4） |
| continue/retry 切会话后 sendClaudeMessage 与异步 loadMessages 竞争 | prime 先行保证 map 有卡；发送只依赖 `_currentSessionId`（switchSession 同步置位） |
| 角标计数频繁解析 metadata | 任务卡量级小（历史封顶 50+活跃），combine 内解析可接受 |
| 新增路由忘记注册 | 检查清单对齐 androidApp/AGENTS.md §6（Screen.kt + NavHost 双注册） |
