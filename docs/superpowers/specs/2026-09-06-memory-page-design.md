# Memory 独立页重设计规范（对标小米系统相册）

> **版本**：1.0
> **日期**：2026-09-06
> **状态**：已落地（编译 + JVM 单测 1247 全绿；真机验证进行中）
> **前置**：`docs/superpowers/specs/2026-09-05-gallery-organization-5-features-design.md`（F3 回忆 v1 已落地）
> **形态参考**：小米 HyperOS 系统相册「相册」tab 分区 feed（时光/旅程/人物）+ 回忆详情页（真机截图调研，2026-09-06）
> **红线约束**：端侧生成零上传（[PRIVACY]）；五语同步（[I18N]）；新页面 Android 定稿后 iOS 经 /ios-follow 对齐（[PARITY]）

---

## 1. 背景与决策

F3 v1 把回忆 carousel 挂在相册网格 header 槽，用户判断**产品形态不对**：回忆应是独立一级页面，与相册拆开。用户拍板（2026-09-06）：

1. Memory 从 Gallery 拆出，成为独立页
2. 底 bar（FloatingBottomTab）增加 Memory 入口
3. 横滑可进入 Memory 页（Pager 页）
4. 页形态参考小米系统相册

**页序决策（方案 A，用户无确认异议）**：`相册(0) / 整理(1) / 聊天(2) / 人物(3) / 回忆(4)`——追加在末尾，不动现有「相册左滑→整理」手势习惯，既有 4 页索引零变更。

## 2. 小米相册对标结论（真机截图依据）

| 小米形态 | 破浪取舍 |
|---|---|
| 「相册」tab 纵向分区 feed：人物行 / 相册网格 / **旅程**（城市大卡）/ **时光**（艺术字标题大卡） | 采用分区 feed，但只保留回忆相关分区（时光/旅程/人物） |
| 时光大卡：竖版封面 + 大标题艺术字 + 日期 | 采用（display 级加粗白字叠加封面左下） |
| 回忆详情：大封面（约屏高 55%）+ 标题/日期·N 项叠加 + 3 列网格 + **「显示优选/全部显示」分段开关** | 全部采用（封面加大、分段开关 = 精选 12 张 / 全部命中） |
| 详情封面右侧圆形播放按钮（回忆视频幻灯片） | **v1 不做**（无视频合成管线），记为 v2 候选 |

## 3. Memory 页（新 Pager 页，index 4）

**结构（纵向滚动 feed）**：

```
顶栏：「回忆」+ 副标「端侧生成 · 私密」
├─ 分区「时光」：竖版大卡（3:4）横滑行
│    卡片 = 封面 + 左下大标题艺术字（相聚时光/那年今日）+ 日期小字
│    内容 = ON_THIS_DAY + RECENT_HIGHLIGHTS 类回忆
├─ 分区「旅程」：同款大卡行
│    卡片 = 封面 + 城市名大字 + 日期范围小字（CITY 类）
│    日期范围 = 同年月本地化 yMMMM（如 en "May 2025"、zh "2025年5月"），跨月/年显示 startYear · endYear（如「2025 · 2026」，与小米一致）
├─ 分区「人物」：同款大卡行
│    卡片 = 封面 + 「与 XX 的时刻」+ N 张照片（PERSON 类）
└─ 空分区不占位；整页空态：「照片积累后会自动生成回忆」
```

- 卡片点击 → 现有详情页路由 `memory_detail/{memoryId}`（不动）
- 长按卡片 → 隐藏确认弹窗（复用现有，文案已校准不承诺恢复）
- 底 bar 在本页同样显示（5 图标态，回忆图标高亮为当前页）

**分区排序**：时光 → 旅程 → 人物（对标小米：旅程在时光前，但破浪「那年今日/近期高光」是回忆主打，时光在前）。

## 4. 回忆详情页升级

| 项 | v1 现状 | v2 变更 |
|---|---|---|
| 封面 | 16:9 全宽 | 加大到约屏高 55%，标题 + 「日期 · N 项」左下叠加 |
| 网格 | 仅精选 12 张 | 底部分段开关「精选 / 全部」：精选 = 现有 12 张；全部 = 全部命中项 |
| 分享 | 右上分享图标（ACTION_SEND_MULTIPLE） | 不变，分享集合跟随开关：精选态分享精选 12 张，全部态分享全部命中（量级受生成阈值约束，通常数十张，不设截断） |
| 播放按钮 | 无 | 不做（v2 候选） |

## 5. 数据层变更

- `MemoriesGenerator`：`Memory` 增加 `allItemUris: List<String>`（全部命中 URI，精选 `itemUris` 仍是其前 12 子集）；输出按 type 供 UI 分组，每类上限放宽（PERSON 前 3、CITY 前 2 保持不变，ON_THIS_DAY/RECENT 各 1 条不变）
- `Memory` 另增 `earliestCaptureDate: Long?` / `latestCaptureDate: Long?`（epoch ms，仅 CITY 填 hits 的 min/max，其余类型 null）——旅程卡与详情页封面的日期范围副行数据源（`cityDateRange`）
- `MemoriesViewModel`：现有 `memories`（过滤隐藏后）不变，UI 层按 `MemoryType` 分组；`observeMemory(id)` 不变
- 隐藏存储（DataStore `memory_hidden_ids`）不变

## 6. 导航与底 bar 变更

- `MainPagerHost`：`MAIN_PAGE_MEMORY = 4`，`MAIN_PAGE_COUNT = 5`；`beyondViewportPageCount` 相应 +1；返回键语义不变（非相册页回相册页）
- `FloatingBottomTab`（GalleryScreen 内）：追加第 5 项——回忆图标（`Icons.Outlined.Collections` 或同级线性图标，待 Ardot 定稿校准），`contentDescription = memory_title`，点击 → `onSwitchPage(MAIN_PAGE_MEMORY)`
- `FloatingBottomTabItem` 组件层新增 `selected: Boolean` 高亮态（当前页图标着色 `primary`，其余 `onSurface`）——现有 4 项均无高亮概念，本次为组件新增能力
- 底 bar 目前仅相册页显示：Memory 页作为一级页也应显示同款 5 图标底 bar（当前页高亮），其余 Pager 页是否统一显示不在本期范围
- `GalleryScreen`：header 槽 carousel 拆除（`memories` 订阅与 header 参数移除），相册回归纯网格；`onNavigateToMemoryDetail` 回调从 GalleryScreen 签名移除，移至 MemoryScreen

## 7. I18N 新增键（五语同步）

| 键 | EN | zh-CN | 说明 |
|---|---|---|---|
| `memory_section_time` | Time | 时光 | 分区标题 |
| `memory_section_journey` | Journeys | 旅程 | 分区标题 |
| `memory_section_people` | People | 人物 | 分区标题 |
| `memory_empty_page` | Memories will appear as your photo library grows | 照片积累后会自动生成回忆 | 整页空态 |
| `memory_detail_best` | Best | 精选 | 分段开关 |
| `memory_detail_all` | All | 全部 | 分段开关 |
| `memory_items_count` | %1$d items | %1$d 项 | 详情页副行（新增；精选态沿用 `memory_best_shots`） |

zh-TW/ES/FR 同步翻译；日期沿用 Locale 本地化（MemoryTexts.kt 已有机制）。

## 8. 验收标准

- [ ] 相册页无回忆 carousel，纯网格；底 bar 5 图标，点击回忆图标切到 Memory 页
- [ ] 相册页横滑可按页序滑到 Memory 页（页 4），反向可滑回
- [ ] Memory 页三分区按数据出现/消失；卡片点击进详情；长按出隐藏弹窗
- [ ] 详情页封面约 55% 屏高；「精选/全部」开关切换网格内容；分享随开关集合
- [ ] 返回键从 Memory 页回相册页
- [ ] 五语无硬编码；detekt 零新增；JVM 单测全绿（Generator allItemUris/分组用例）

## 9. 明确不做（YAGNI）

- 回忆视频幻灯片播放（v2 候选，需视频合成管线）
- 已隐藏回忆管理/恢复入口（v1 维持不承诺恢复）
- 其余 Pager 页底 bar 统一化
- iOS 端实现（Android 定稿固化 spec 后走 /ios-follow）

## 10. Ardot 设计稿帧清单

新页 `Memories`（page id `297:1`，polang-ui-spec fileId 715061534788814；2026-09-06 由 `Memory Page v2` 更名，旧 F3 carousel/detail 稿所在页 267:22 已整体删除），3 帧横向排开：

| 帧 | node id | 截图（`tmp/ardot-org/shots/`） |
|---|---|---|
| `memory/feed`（Memory 独立页：时光/旅程/人物分区大卡 + 5 图标底 bar） | `297:7` | `screenshot-297_7-20260906_093211260.png` |
| `memory/detail`（详情页 v2：55% 封面 + 精选/全部分段开关） | `297:65` | `screenshot-297_65-20260906_093211421.png` |
| `memory/bottombar`（底 bar 5 图标特写，回忆高亮） | `297:87` | `screenshot-297_87-20260906_093211518.png` |

绘制说明：回忆图标用 `ic/auto_fix`（魔法棒+星花）描边绑 `scheme/primary` 作高亮态（IconSet 无专用回忆组件，沿用 vault/lock 用 `ic/face` 替代先例）；卡片截断/标题阴影为有意设计。构建脚本留档 `tmp/ardot-org/build_memory_v2.py`、`t_fixicons.py`。
