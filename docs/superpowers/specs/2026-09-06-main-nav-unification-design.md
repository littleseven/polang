# 主页面导航架构统一（五页一面、聊天沉浸）

> **日期**：2026-09-06
> **状态**：已批准（用户选定方案 A）
> **前置**：`feat-tag-control-redesign` 合并 session（整理+扫描合并页）提交后方可实施
> **关联**：`2026-09-06-memory-page-design.md`（Memory 独立页）、`2026-09-05-gallery-organization-5-features-design.md`（F1-F5 信息架构）

## 1. 背景与问题

2026-09 主页面已演进为 5 页 HorizontalPager（相册/整理/聊天/人物/回忆），另一 session 正在把 TAG 扫描控制路由并入整理页（`OrganizeHomeScreen`，页内胶囊双 Tab）。当前导航存在：

1. **bar 与页面非 1:1**：悬浮底 bar 5 项对应「4 个 Pager 页 + 1 个路由」；合并后保留「整理」「打标」双图标指向同一页，冗余。
2. **bar 无「相册」项**：People/Memory 挂 bar 后没有显式回家路径（只能横滑多页）。
3. **页面身份错乱**：Gallery/Memory 有 bar 像 root；People/Chat/整理 无 bar 且带返回箭头像二级页——同为 Pager 页三套行为。
4. **bar 定义重复**：`GalleryScreen` 与 `MemoryScreen` 各手写一份 5 项定义，扩容恶化。
5. **回忆 icon（`Icons.Outlined.Collections` 四宫格）语义撞车**：读作「图库/收藏集」，恰是「相册」语义。
6. `Screen.People` 全工程零引用（死声明）。

## 2. 产品逻辑梳理（页面分类）

| 轴 | 页面 | 性质 |
|---|---|---|
| 内容消费 | 相册（原始时间流）/ 人物（人的切面）/ 回忆（时间·旅程·端侧生成切面） | 「场所」——可停留、可漫游 |
| 任务 | 整理中心（清理 hub + 扫描控制） | 「工具间」——进入→干活→离开 |
| 助手 | 聊天（输入驱动对话面） | 「模式」——沉浸对话 |

**页面身份规则（新增，入 AGENTS.md）**：
- **根页**：无顶栏返回箭头；渲染悬浮 bar 并高亮自身项；系统返回 = 默认行为（退出应用）。
- **二级/沉浸页**：有返回出路，不渲染 bar。

## 3. 方案选型（已批 A）

- **A（采纳）五页一面、聊天沉浸**：bar 五项与 Pager 页序 1:1（相册/整理/聊天/人物/回忆）；bar 渲染于相册/整理/人物/回忆四个根页；聊天保持沉浸二级（无 bar、顶栏返回、横滑可达）。
- B（否）全一级化：聊天也挂 bar，输入框上抬避让——对话面多一层导航元素、键盘避让双重叠加风险。
- C（否）内容三页+助手：整理降为相册页内入口——整理中心可达性降级，与 F1/F2 刚落地的投入相悖。

聊天不挂 bar 的理由：对话是「模式」不是「场所」；悬浮胶囊与输入框/键盘避让冲突。

## 4. 底 bar 设计

### 4.1 图标（已批）

| 项 | icon | 说明 |
|---|---|---|
| 相册（新增） | `Icons.Outlined.PhotoLibrary` | 照片+叠层，「照片流」直给；bar 从此有显式回家路径 |
| 整理 | `Icons.Outlined.CleaningServices` | 扫帚+水桶，对齐 Cleanup Hub 定位；替换 BurstMode |
| 聊天 | `Icons.Outlined.ChatBubble` | 保留 |
| 人物 | `Icons.Outlined.AccountCircle` | 保留 |
| 回忆 | `Icons.Outlined.AutoAwesome` | 星花，「端侧生成」语义；替换 Collections |

选中/未选中维持现状：同一 icon 仅 tint 区分（primary vs onSurface），无 outlined/filled 双态。

### 4.2 共享组件

新建 `MainFloatingBottomBar(selectedMainPage: Int, onSwitchPage: (Int) -> Unit)`（`features/common/components/`）：

- 内部 5 个 `FloatingBottomTabItem` 定长表驱动：icon + contentDescription（stringResource）+ 页索引常量（`MAIN_PAGE_*`）。
- `selectedMainPage == 自身` → `selected = true`、点击 no-op；未选中 → `onSwitchPage(index)`（既有 `switchMainPage` 瞬时切页）。
- 4 个调用点：`GalleryScreen`（传 `MAIN_PAGE_GALLERY`）/ `OrganizeHomeScreen`（`MAIN_PAGE_DEDUP`）/ `PersonScreen`（`MAIN_PAGE_PEOPLE`）/ `MemoryScreen`（`MAIN_PAGE_MEMORY`），各自放进页根 `Box`（Pager 单根铁律，页面自控 z 序与遮挡 padding）。
- 删除 `GalleryScreen`/`MemoryScreen` 两份手写定义。

### 4.3 打标入口收敛

- 移除 bar「打标」项（`Icons.Outlined.Sell` + `onNavigateToTagControl`）。
- ORGANIZE/SCAN 由整理页内胶囊切换；`organizeTabRequest` 一次性深链机制保留，聊天 `ChatStartTagScanCapability` 等外部触发仍直达 SCAN tab。
- `tag_scan_control` 字符串按实际引用清理（若仅 bar 使用则五语删除）。

## 5. 各页改造清单

| 页面 | 改动 |
|---|---|
| 相册 | bar 换共享组件；相册项高亮（`MAIN_PAGE_GALLERY`） |
| 整理中心 | 挂 bar（整理项高亮）；双图标收敛为 1；embedded 子页（Dedup/TagControl）顶栏返回箭头按根页规则去除 |
| 聊天 | **零改动**（沉浸身份：顶栏返回/无 bar/横滑） |
| 人物 | 升根页：移除 `AppTopBarNavBack`；挂 bar（人物项高亮）；列表底部 contentPadding 对齐 Memory 的 96dp 避让；标题/统计副标/三 actions（过滤/打分/重聚类）保留 |
| 回忆 | icon → `AutoAwesome`；bar 换共享组件 |
| 导航 | `Screen.People` 死声明删除 |

## 6. Memory ↔ People 关系收口

- **身份对称**：同为根页、bar 相邻项、Pager 横滑相邻（3↔4）；互达 = bar 一击或横滑，双向有出路。
- **内容层维持现状**：Memory 人物分区卡 → `memory_detail`；People 封面 → 相册按人过滤。
- 数据层既有耦合（`PersonDao.observeAll` 改名驱动 Memory 人物分区重生成）不变。
- **v2 可选非目标**：People 卡片「看 TA 的回忆」、Memory 人物卡「在人物页查看」等内容级直达。

## 7. i18n / 文档 / 设计同步

- 新增 `tab_gallery`（五语：EN/zh-CN/zh-TW/es/fr）作相册项 contentDescription。
- 文档同原子提交：`PRODUCT.md` §1.2/§2.1 bar 描述、`docs/01-PRODUCT/FEATURES.md`、`androidApp/AGENTS.md` 主页面表与页面身份规则、`features/gallery/AGENTS.md` §2.12（Memory icon 与底 bar 段）、person 模块文档。
- Ardot：含底 bar 的帧 icon 同步（`docs/08-UI-SPECS/screens/refs/ardot/`，实现时核对 manifest）。
- iOS：Android 落地验收后走 `/ios-follow`（独立批次）。

## 8. 实施顺序（并发协调）

1. **前置**：合并 session 提交（其未提交改动覆盖 `MainActivity`/`MainPagerHost`/`Screen.kt`/`OrganizeHomeScreen`/AGENTS 等本设计触碰文件）。
2. 在其提交之上开 `feat/main-nav-unification` 分支（worktree 隔离），避免与主树并发会话互踩。
3. 验收：四根页 bar 高亮正确 + People 无返回箭头 + 五图标正确（ui-driver/auto-dev-loop 截图）；i18n 五语同步；ktlint/detekt 零增量；既有单测绿（`MemoriesViewModelTest` 等）。

## 9. 边界情况

- bar 遮挡：People 新增底部 padding，其余根页已有避让；整理 hub 滚动内容补避让。
- 聊天页不涉及键盘+bar 叠加（不挂 bar）。
- 选中项点击 no-op（防重复切页）。
- 路由页（memory_detail/organize_category/swipe_review/settings 等）无 bar，不受影响。
