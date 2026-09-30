# 扫描页入口便捷化 + 图库统计圆环口径澄清 — 设计稿

- 日期：2026-09-30
- 状态：已与用户对齐方向（本文即决策记录）
- 端：Android 先行，iOS 经 `/ios-follow` 后续跟随（输入 = `docs/08-UI-SPECS/screens/tag-control.yaml`，随本次同步）

## 1. 背景与问题

用户反馈两条：

1. **进入扫描页非常不方便**。扫描页（`TagGenerationControlScreen`）自 2026-09-06 导航统一后是「整理」页内的一个 tab（非独立底 bar 项）。`MainPagerHost.kt` 的 `onBarSwitchPage` 在每次点底 bar「整理」时**无条件重置** `organizeTab = OrganizeTab.ORGANIZE`——用户哪怕上一秒就在扫描 tab，切走再回来也被扔回去重 tab，须再点一次顶部「扫描」胶囊。相册顶栏 ▶/⏸ 图标是一键开扫/暂停，不进扫描页。
2. **图库统计的 9% 含义模糊**。hero 圆环中心裸写 "9%"，caption 为「X 张照片 · 语义索引已覆盖 9%」。实际口径是 `withSemantic / totalMedia`（已生成 MobileCLIP 语义向量的照片占比，Pass3 的一个子步）——工程黑话 + 裸百分比 + 同页另有 人脸%/内容标签%/美学% 三个不同口径的百分比并存，用户无法知道 9% 对应哪个、涨满意味着什么。

## 2. 决策记录

- 入口：**记住上次 tab + 扫描进行中智能落扫描 tab**（不恢复独立 bar 项、不动底 bar 五项结构，不违背 2026-09-06 导航决策）。
- 圆环：**统一到「AI 打标完成率」**——与阶段行「内容标签」完全同口径 `(totalMedia − remainingPass3) / totalMedia`，caption 换用户语言；**圆环中心加「AI 打标」微标签**。

## 3. 变更 A：扫描页入口（`MainPagerHost.kt`）

现状（`onBarSwitchPage`，约 105-108 行）：

```kotlin
val onBarSwitchPage: (Int) -> Unit = { index ->
    if (index == MAIN_PAGE_DEDUP) organizeTab = OrganizeTab.ORGANIZE
    onSwitchPage(index)
}
```

改为：

```kotlin
val onBarSwitchPage: (Int) -> Unit = { index ->
    if (index == MAIN_PAGE_DEDUP && TagGenerationService.isScanning.value) {
        organizeTab = OrganizeTab.SCAN
    }
    onSwitchPage(index)
}
```

- 删除无条件重置：`organizeTab` 本就是 `rememberSaveable`，删除后自然记住上次手动选择的 tab。
- 智能落点：点「整理」时若 `TagGenerationService.isScanning.value == true` → 落 SCAN tab（扫描进行中点整理，查进度是最高频意图）。
- **边界**：暂停（paused）态 `isScanning=false`，落上次记住的 tab——不把暂停计入「进行中」，保持判断简单。
- 同步更新该方法上方注释（103-104 行「目标为整理页时预选 ORGANIZE tab」一段）。
- 不动：滑动切页路径（本就不重置）；`organizeTabRequest` 深链（设置页等外部入口）；`OrganizeHomeRoute` 结构；底 bar。

## 4. 变更 B：图库统计圆环（`TagGenerationControlScreen.kt` + 五语 strings）

1. `StatsCard` 的 hero 圆环口径由 `withSemantic * 100 / totalMedia` 改为与阶段行**同一计算**：`tagPassProgress(totalMedia, remainingPass3).fraction` → 百分比（复用 `stagePercentText` 的舍入，杜绝同页 9% vs 10% 的舍入漂移；数据同源 `stats.remainingForPass3`，零新增查询）。
2. `StatsCard` 签名：`withSemantic: Int` 参数替换为 `remainingPass3: Int`（hero 不再消费 embedding 数）。
3. 圆环中心两行："9%"（13sp SemiBold）+ 下方「AI 打标」微标签（约 8sp，`onSurfaceVariant`）。
4. 「人脸 Embedding」瓦片（`embeddingCount`）**保留不动**——术语问题另行处理，不混入本次。
5. `GetGallerySummaryUseCase` 的 `semanticEncodedCount`（chat 相册摘要用）**不动**，与本 UI 无关。

### 4.1 i18n（五语同步）

`stats_hero_caption`（改内容，参数仍为 `%1$d`）：

| 语言 | 文案 |
|---|---|
| EN (default) | `photos · %1$d%% AI-tagged` |
| zh-rCN | `张照片 · AI 打标已覆盖 %1$d%%` |
| zh-rTW | `張照片 · AI 打標已覆蓋 %1$d%%` |
| es | `fotos · %1$d%% etiquetadas por IA` |
| fr | `photos · %1$d%% étiquetées par IA` |

`tag_stats_ring_label`（新增，圆环微标签；终稿措辞实现评审时可微调）：

| 语言 | 文案 |
|---|---|
| EN | `AI-tagged` |
| zh-rCN | `AI 打标` |
| zh-rTW | `AI 打標` |
| es | `Etiquetado IA` |
| fr | `Étiquetage IA` |

## 5. 文档同步（与代码同原子提交）

- `docs/08-UI-SPECS/screens/tag-control.yaml`：`library.stats_card.hero_number` 的 `text`/`caption` 行与 `progress_ring` 说明改为新口径（iOS 重写的唯一输入，必须同步）。
- `MainPagerHost.kt` 相关注释随代码更新。
- **范围外（后续项，不进本次）**：Ardot 画布（`gallery/tag_control` 帧）文案同步；iOS 实现（`/ios-follow`）。

## 6. 验证

编译 + `./scripts/auto-dev-loop.sh` 真机走查：

1. 整理↔扫描手动切换后离开再点底 bar「整理」→ 落上次 tab（不再被重置）。
2. 扫描进行中点「整理」→ 落扫描 tab；扫描暂停时 → 落上次 tab。
3. 圆环 % 与阶段行「内容标签」% 一致；caption 为新文案。
4. 五语切换抽查 caption 与微标签。
