# 扫描进度 UX 重设计 Spec（单口径锚点 + 分层披露）

> **日期**：2026-10-01
> **状态**：已定稿（brainstorming 三方案评审，用户选定方案 A；定位=精细控制优先、只修口径；范围=扫描 Tab 为主 + 全局口径统一）
> **分支**：`feat/scan-progress-ux-redesign`
> **影响面**：`androidApp`（tag-control 页 / 任务中心 / 前台通知）+ `docs/08-UI-SPECS/screens/tag-control.yaml` + FEATURES.md §3.6；iOS 跟随走 /ios-follow

---

## 1. 背景与问题

扫描进度目前有 **4 种口径的百分比并存**、**5 处操作入口分散**，用户无法解读「到底扫到哪了」：

| 现有数字 | 口径 | 数据源 | 问题 |
|---|---|---|---|
| StatsCard 圆环 + Stages 行 | 库级（`tagPassProgress(totalMedia, remaining*)`） | `TagScanOrchestrator.getDbStats` | ✅ 唯一正确口径，2026-09-30 已统一 |
| ScanProgressCard 进度条 | 任务级 `processed/total`（一媒体多任务） | `TagGenerationService.sessionProgress` | 与圆环矛盾（45% vs 9%） |
| ScanActionCard 待处理轨道 | `(total−(remainingPass1+remainingPass3))/total` 含 Pass1 | 同上 | 第三口径 |
| 前台通知进度条 | 任务级 + 硬编码中文文案 | 同上 | 口径错 + 违反 [I18N] |
| 任务中心 UserTaskCard | 任务级 `progress=processed/total` | `TagScanTaskAdapter` | 与圆环矛盾 |

操作入口 5 处：ScanActionCard、会话控制排、Stages 弹层、相册顶栏 Play/Pause、任务中心动词。

## 2. 目标 / 非目标

**目标**：
- G1 全 app 用户可见的扫描百分比只有一个口径（库级 AI 打标完成率），所有展示面同源同舍入
- G2 扫描中用户能回答三个问题：在干什么（阶段名）、还要多久（x/y + ETA）、我能做什么（唯一主操作卡）
- G3 精细控制（Stages/Regenerate）原样保留，不打断既有主路径

**非目标**：
- 不改 UserTask 协议字段、不改引擎/编排层（`TagScanOrchestrator` 任务模型不动）
- 不重设计 Stages/Regenerate 区块结构（仅补口径标签）
- 不动相册顶栏扫描图标形态（仅状态指示器，不加数字）

## 3. 设计原则：单口径锚点 + 分层披露

**一个数字说完成度，一句话说当下，精细控制原位保留。**

- **唯一百分比** = 库级 AI 打标完成率：`round((totalMedia − remainingPass3) / totalMedia × 100)`（Double 精确路径，`percentRounded()` 既有实现）
- **任务级进度降级为叙述**：`processed/total` 不再渲染为百分比/进度条占比，只以「第 x/y 张」自然语言形态出现
- **类型系统防混口径**（Agent First 自描述）：库级口径封装为 `LibraryCompletion` 类型，与 `TagScanSessionProgress` 命名/类型双隔离

## 4. 口径立法（数据链收口） [agent-task:scan-ux-001]

**Scope**：`domain/tag/scan/`（新增 `LibraryCompletion.kt`）、`features/gallery/components/TagPassProgress.kt`、`service/tag/TagGenerationService.kt`、`di/AppContainer.kt`

**Expected Change**：
1. `TagPassProgress` / `tagPassProgress()` / `percentRounded()` 从 `features/gallery/components/` 上移到 `domain/tag/scan/LibraryCompletion.kt`（改 public，内容不变）；`TagPassProgress.kt` 删除，引用点改 import（TagGenerationControlScreen、TagPassProgressTest 随迁）
2. `TagGenerationService` 伴生对象新增 `libraryCompletion: StateFlow<LibraryCompletion?>`：
   - `data class LibraryCompletion(totalMedia: Int, remainingPass3: Int)`，`percentRounded()` 复用同一纯函数
   - 更新时机：扫描会话 RUNNING 期间每次 sessionProgress 发射时节流（≥1s）调用 `TagScanOrchestrator.getDbStats(db)` 刷新；会话终态（COMPLETED/CANCELLED）与 `onDestroy` 时置 null
3. `AppContainer` 透传 `tagGenerationLibraryCompletion: StateFlow<LibraryCompletion?>`

**Priority**：P0 | **Acceptance**：AC-1

## 5. 扫描 Tab 双态设计 [agent-task:scan-ux-002]

**Scope**：`features/gallery/components/TagGenerationControlScreen.kt` + 五语 strings.xml

### 5.1 空闲态（结构不变，只修口径与层级）

- StatsCard / 圆环：不动（已是正确口径）
- ScanActionCard：
  - 待处理轨道公式改 `tagPassProgress(totalMedia, remainingPass3).fraction`（删含 Pass1 的第三口径）；入参从 `pendingCount = remainingPass1 + remainingPass3` 改 `remainingPass3`，chip「N pending」同步改 Pass3 口径
  - 「Rescan all」维持描边胶囊（已是次级样式），主按钮永远只有 Scan new
  - caption 文案补口径说明（见 §8 文案键表）
- Stages 四行：trailing 数字不动，description 行补分母口径（如「全库 12,430 张已检测人脸」参数化）

### 5.2 扫描态主状态卡（重设计核心）

一张卡替代现 ScanProgressCard + 独立会话控制卡（两卡合一）：

```
┌─────────────────────────────────┐
│ ● 正在打内容标签                 │ ← 标题=阶段名（currentPass 映射）
│ 第 128/500 张 · 约 6 分钟        │ ← 叙述行（任务级，非百分比）
│ ──────────────── 全库完成 45%    │ ← 细轨道=LibraryCompletion + 小字标签
│ [暂停]                    [取消] │ ← 主按钮暂停/继续 + 文字按钮取消
└─────────────────────────────────┘
```

- 标题映射：`FACE_DETECTION→正在识别人脸`、`DBSCAN→正在聚类人物`、`IMAGE_TAGGING→正在打内容标签`、`MOBILE_CLIP_ENCODING→正在编码语义`、`null→正在准备`；美学打分（AestheticProgressCard 槽位）标题「正在评估照片质量」
- 叙述行纯函数：`domain/tag/scan/ScanNarrative.kt` 产出结构化 `ScanNarrative(processed, total, etaMs)`，UI 层 stringResource 插值（西/法语语序占位符 `%1$d`/`%2$s`）
- 轨道行：仅在 `libraryCompletion != null` 时渲染；小字「全库完成 45%」参数化
- 按钮：RUNNING→[暂停]+[取消]；PAUSING→[暂停中…禁用]+[取消]；PAUSED→标题改「已暂停 · 内容标签打到一半」、主按钮[继续]+[取消]；`failed>0` 追加「重试失败项」文字按钮
- 扫描中 Stages/Regenerate 区块降透明度（`alpha=0.38f`）+ 禁用点击（`enabled=false` 语义），避免误触互斥操作
- 删除：独立「会话控制」卡（并入主状态卡）

### 5.3 中断复活卡

- 进程死亡对账后 `user_task` 表留 FAILED(PROCESS_TERMINATED)：扫描 Tab 空闲态若读到该状态（经 `AppContainer.userTaskRegistry.tasks` 过滤 `tagscan:main`），ScanActionCard 上方插一张「扫描已中断」卡：「应用被系统回收，进度已保存」+ 主按钮「从中断处继续」（=`intentScanIncremental`，增量天然续跑）
- 卡片出现时消费语义：用户点「继续」或卡片关闭后由 registry 既有流程自然清态（RETRY→start 后首帧同步覆盖）

**Priority**：P0 | **Acceptance**：AC-2、AC-3

## 6. 全局同步面 [agent-task:scan-ux-003]

**Scope**：`domain/usertask/TagScanTaskAdapter.kt`、`service/tag/TagGenerationService.kt`（buildNotification）、`features/chat/taskcenter/UserTaskCard.kt`

1. **任务中心**：`TagScanTaskAdapter.start()` 改收集 `combine(sessionProgress, libraryCompletion)`；`progress` 喂库级完成率 fraction（`null` 时回退任务级，防首帧空窗），`progressText` 保持 `"128/500"` 数字中性形态（天然无 i18n 问题）。协议字段零变更
2. **前台通知**（顺手修 [I18N] 违规）：
   - 标题：阶段名（`getString` 复用 §5.2 阶段文案键）
   - 正文：叙述行「第 128/500 张 · 约 6 分钟」参数化五语
   - `setProgress(100, libraryCompletion.percentRounded())`；libraryCompletion 为 null 时回退任务级
   - 删除硬编码中文「已暂停」「完成」「张」
3. **相册顶栏**：不动

**Priority**：P0 | **Acceptance**：AC-1、AC-4

## 7. 边界态矩阵

| 状态 | 标题 | 叙述行 | 按钮 |
|---|---|---|---|
| RUNNING | 正在{阶段} | 第 x/y 张 · 约 N 分钟（etaMs 非空才带） | 暂停 / 取消 |
| PAUSING | 正在暂停… | 同上 | 暂停中（禁用）/ 取消 |
| PAUSED | 已暂停 · {阶段}打到一半 | 第 x/y 张 | 继续 / 取消 |
| CANCELLING | 正在取消… | 等待当前任务结束 | 全禁用 |
| COMPLETED（failed=0） | —（回空闲态，caption 展示结果） | — | — |
| COMPLETED（failed>0） | 已完成 · N 张失败 | — | 重试失败项（文字按钮，保留终态卡直至下次扫描） |
| 进程死亡复活 | 扫描已中断 | 应用被系统回收，进度已保存 | 从中断处继续 |
| 美学打分（非会话） | 正在评估照片质量 | 第 x/y 张 | 无（既有互斥，不可控） |

## 8. 文案键（新增，五语同步 EN/zh-CN/zh-TW/ES/FR）

| 键 | EN 原值 |
|---|---|
| `tag_scan_stage_running` | "Tagging: %1$s"（阶段名插值）→ 实际拆 `tag_scan_now_face/cluster/content/semantic/aesthetic/preparing` 六个整句键，避免跨语拼接 |
| `tag_scan_narrative` | "Photo %1$d of %2$d · ~%3$s left" |
| `tag_scan_narrative_no_eta` | "Photo %1$d of %2$d" |
| `tag_scan_library_completion` | "%1$d%% of library tagged" |
| `tag_scan_paused_title` | "Paused · %1$s half done" |
| `tag_scan_interrupted_title` | "Scan interrupted" |
| `tag_scan_interrupted_desc` | "App was reclaimed by system; progress saved" |
| `tag_scan_resume_from_breakpoint` | "Resume from breakpoint" |
| `tag_scan_retry_failed` | "Retry failed items" |
| `tag_scan_caption_scope` | ScanActionCard caption 补口径："of your library" 后缀 |
| `tag_stage_scope_*` ×4 | Stages 行分母口径说明（参数化总数） |
| 通知复用 | 通知标题/正文复用上表键，删 `tag_gen_notification_idle` 外硬编码 |

## 9. 测试计划 [agent-task:scan-ux-004]

**Scope**：`androidApp/src/test/`

1. `LibraryCompletionTest`（随迁既有 TagPassProgressTest 用例 + 新增）：同一 `(totalMedia, remainingPass3)` 输入，圆环/通知/任务中心三处渲染值相等（契约测试：`percentRounded()` 唯一入口断言）
2. `ScanNarrativeTest`：叙述行结构（processed/total/etaMs 透传 + total=0 置 null）
3. `TagScanTaskAdapterTest` 更新：combine 双源后 progress=库级口径、libraryCompletion=null 回退任务级、PROCESS_TERMINATED 对账不回归
4. 状态机映射纯函数 `scanCardUiModel(state, pass, narrative, failed)` →（标题键/按钮集/轨道可见性）JVM 单测覆盖 §7 全矩阵
5. 真机走查：27 张库增量扫描，同一时刻 Tab 轨道 / 通知进度条 / 任务中心进度条三值相等；暂停→继续→取消全链路；杀进程复活出现中断卡

**Priority**：P0 | **Acceptance**：AC-5

## 10. 验收标准（Acceptance Criteria）

- **AC-1**：任一时刻，扫描 Tab 轨道、前台通知、任务中心三处百分比数值相等，且等于 `tagPassProgress(totalMedia, remainingPass3).percentRounded()`
- **AC-2**：扫描态同屏只有一个百分比（轨道小字）；任务级进度仅以「第 x/y 张」文字出现
- **AC-3**：扫描态仅一张主状态卡承载全部操作（暂停/继续/取消/重试失败），无第二张控制卡
- **AC-4**：通知与卡片文案零硬编码中文，五语资源齐全（i18n-validator 通过）
- **AC-5**：§7 边界态矩阵每态标题/按钮集与 spec 一致（单测 + 真机抽查）

## 11. 文档与 parity 同步（交付 DoD）

- `docs/08-UI-SPECS/screens/tag-control.yaml`：§scan 区块重写（scanning 态新卡片结构、口径注释更新、ScanActionCard 轨道口径修正、Stages 行口径说明）
- `docs/01-PRODUCT/FEATURES.md` §3.6：任务中心 TAG 扫描进度口径描述更新（库级完成率 + 辅助行）
- `features/gallery/AGENTS.md` §2.9：进度展示口径段落更新（单口径锚点原则 + LibraryCompletion 类型）
- iOS 跟随：本 spec 定稿合 main 后走 /ios-follow（iOS TagScanScreen 读 yaml 不读 Android 源码）
- 沉淀管线：交付时结论落位上述活文档后 `git rm` 本 spec

## 12. 里程碑

| 里程碑 | 内容 | 对应任务 |
|---|---|---|
| M1 口径立法 | scan-ux-001（LibraryCompletion 上移 + Service 流 + 容器透传）+ 单测 | P0 |
| M2 扫描 Tab | scan-ux-002（双态卡片 + Stages 口径标签 + 中断卡 + 五语）+ 单测 | P0 |
| M3 全局同步 | scan-ux-003（任务中心 + 通知）+ 单测更新 | P0 |
| M4 验证收口 | scan-ux-004 真机走查 + 文档同步（§11）+ spec 沉淀删除 | P0 |
