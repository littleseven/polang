# 整理页类目体系重构（v2）+ UI 优化设计

> **版本**：1.0
> **日期**：2026-09-06
> **状态**：已落地（2026-09-07，Task 1-18b 完成；落地与设计的偏差见文末「落地校准注记」）
> **前置 spec**：`docs/superpowers/specs/2026-09-05-gallery-organization-5-features-design.md`（F1 整理中心 v1）
> **现状 SSOT**：`androidApp/.../features/gallery/AGENTS.md` §2.4、`androidApp/.../domain/organize/OrganizeCategorizer.kt`
> **红线约束**：全部媒体处理端侧完成，零上传（[PRIVACY]）；五语同步（[I18N]）；交互 < 100ms（[PERF]）；本期仅 Android，定稿后固化 UI spec 供 iOS 翻译（[PARITY]）
> **用户关键决策**（2026-09-06 确认）：
> 1. 整理页定位不变——仍是**清理中心**（解决「删什么」），不做浏览式分类
> 2. 类目边界重划为 6 个新类目（见 §2）
> 3. 低质量照片可能是重要的老照片/稀缺照片——采用**信号保护**（ValueGuard），不做纯交互保护
> 4. UI 痛点聚焦**类目卡表现力**（置信度标识、缩略图预览、优先级排序）

---

## 1. 背景与问题定义

整理中心 v1（2026-09-05 落地）用户反馈「分类规则很模糊，效果不及预期」。代码级根因（均有实锤）：

| # | 问题 | 证据 |
|---|------|------|
| P1 | **类目多属、Hero 重复计账** | 一张截图可同时落 SCREENSHOTS + DOCUMENTS + BLURRY 三类；Hero「预计可释放」简单求和（`DedupHomeHub.kt:181`），同一照片计 2~3 次，违反 AC-F1-1 |
| P2 | **BLURRY 名实错位** | 所谓「模糊低质」= NIMA 美学分 < 3.5，无任何模糊检测；截图/文档/白底票据天然低美学分被误吞 |
| P3 | **类目静默消失** | 未跑质量打标时 BLURRY/LOW_QUALITY_PORTRAITS 卡直接不渲染（v1 校准注记砍掉了引导卡），用户感知「类目忽有忽无」 |
| P4 | **「AI 预选」零智能** | 详情页进入时全选该类目全部命中项（`OrganizeCategoryViewModel.kt:87-92`），无 per-item 置信度，误判放大为全选删除建议 |
| P5 | **阈值全硬编码、单一全局值** | 3.5f / 0.35f / 100MiB / 20 字符每 MP，无分级、无校准 |
| P6 | **SwipeReview 与类目口径不一致** | `SwipeQueueBuilder.kt:17-22` 桶间互斥优先级与类目页集合语义不同源，角标原因漂移 |

## 2. 类目体系 v2

**类目语义**：清理向（可删建议），**互斥**——一张媒体只进一个桶，按优先级先命中先得。

| 优先级 | 类目（枚举名） | 文案（EN） | 判定信号 |
|---|---|---|---|
| 1 | `DUPLICATES` | Duplicates & similar | MD5 精确重复，或 pHash 汉明距离 ≤ 阈值（复用 `dedup_hash` 表与去重 2.0 结果） |
| 2 | `SCREEN_CONTENT` | Screenshots & recordings | 截图：RELATIVE_PATH 含 `screenshots`（沿用）；**录屏（新增）**：视频且（路径含 `screenrecord`/`录屏` 或 分辨率 = 设备屏幕分辨率） |
| 3 | `DOCUMENTS` | Documents & receipts | OCR 文字密度 > 20 字符/MP（尺寸未知退回绝对 > 200 字符）或 VLM labels 命中文档关键词表（沿用 `detectContentType`） |
| 4 | `LOW_QUALITY_PORTRAITS` | Low-quality portraits | hasFace 且 faceQualityScore < 0.35（沿用阈值，可校准） |
| 5 | `LOW_QUALITY_PHOTOS` | Blurry & low quality | **真模糊**：blurScore（Laplacian 方差）< 阈值；或**曝光异常**：exposureScore 越界（欠曝/过曝）。NIMA 美学分降为置信辅助信号，不再单独定类 |
| 6 | `LARGE_FILES` | Large files | 视频 ≥ 100 MiB（沿用）；或照片像素面积 ≥ 50MP 且大小 ≥ 20MB（超分辨率照片，新增） |

**裁定域约束**：视频仅参与 `DUPLICATES` / `SCREEN_CONTENT` / `LARGE_FILES` 三类裁定；`DOCUMENTS` / `LOW_QUALITY_PORTRAITS` / `LOW_QUALITY_PHOTOS` 仅对图片生效。

**与 v1 的差异**：
- 截图与录屏合并为「屏幕内容」类目
- 「模糊低质」不再吞截图/文档（它们在更高优先级被截走）
- 大视频扩展为大文件（含超分辨率照片）
- `OrganizeCategory` 枚举重命名：SCREENSHOTS→SCREEN_CONTENT、BLURRY→LOW_QUALITY_PHOTOS、LARGE_VIDEOS→LARGE_FILES。枚举名是 `organize_category/{category}` 路由段，改名需全量检查引用（路由、strings、SwipeQueueBuilder）

## 3. 信号分层管线架构

`domain/organize/` 下四层纯函数管线（全部零 Android 依赖、可 JVM 单测），`OrganizeCategorizer` 重构为管线编排者：

```
SignalCollector    采集/补齐信号：Room 投影 + MediaStore meta + 惰性计算（blur/exposure）
      ↓  List<OrganizeItem>（信号完备）
CategoryArbiter    互斥裁定：按 §2 优先级先命中先得，一张图一个桶
      ↓  List<ClassifiedItem{item, category, matchedSignals}>
ValueGuard         价值保护：命中保护信号 → protected=true（见 §4）
      ↓
ConfidenceGrader   置信度分级：HIGH / MEDIUM / LOW（见 §5）
      ↓
→ 输出 OrganizeBoard：类目卡聚合（数量、字节、置信度分布、protected 数、缩略图）
  + Hero 数字（HIGH 置信且非 protected 的去重并集字节）
```

**文件结构**（新增/重构）：

| 文件 | 职责 | 状态 |
|---|---|---|
| `domain/organize/OrganizeModels.kt` | 枚举 v2 + `OrganizeItem` 扩展（新增 blurScore/exposureScore/lastViewedAt/isFavorite/personPhotoCount/isScreenRecording） | 重构 |
| `domain/organize/SignalCollector.kt` | 信号补齐编排（惰性计算触发、缺信号标注） | 新增 |
| `domain/organize/CategoryArbiter.kt` | 互斥裁定（替代现 `categoriesOf` 的集合语义） | 新增 |
| `domain/organize/ValueGuard.kt` | 价值保护过滤 | 新增 |
| `domain/organize/ConfidenceGrader.kt` | 置信度分级 | 新增 |
| `domain/organize/OrganizeCategorizer.kt` | 管线编排 Facade（对外唯一入口） | 重构 |
| `domain/organize/BlurAnalyzer.kt` | Laplacian 方差 + 亮度直方图计算（缩略图 ≤256px，纯 CPU 毫秒级） | 新增 |

**设计约束**：每层一个文件、一个职责、纯函数；阈值全部常量化集中在 `OrganizeThresholds`（object），带校准注记。符合 [AGENT-FIRST] 显式/枚举/自描述原则。

## 4. 价值保护（ValueGuard）

**适用范围**：仅 LOW_QUALITY_PHOTOS 与 LOW_QUALITY_PORTRAITS 两个类目（用户确认的「低质≠可删」场景）；截图/文档/大文件/重复不适用。

命中任一信号 → `protected=true`：

| 信号 | 规则 | 常量 |
|---|---|---|
| 年代久远 | captureDate < now − 5 年 | `OLD_PHOTO_YEARS = 5` |
| 人物稀缺 | 所属人物聚类（faceId → persons）照片总数 ≤ 3 | `PERSON_SCARCE_MAX = 3` |
| 用户互动过 | isFavorite（MediaStore IS_FAVORITE，API 29+）或 lastViewedAt ≠ null | — |

> 「无替代品」信号不落入 ValueGuard：互斥裁定下，有相似组的照片已进入 DUPLICATES 类目，其「保留组内最佳」由去重 2.0 的 `KeepPolicyEngine` 负责（BEST_QUALITY 规则），不在本层重复判定。

**protected 项的 UI 语义**：不默认勾选、排详情页末尾「可能是珍贵照片」区、带保护角标、**不计入 Hero 建议清理数字**（仍计入类目浏览总数）。

## 5. 置信度分级（ConfidenceGrader）

| 级别 | 定义（按类目示例） | 详情页行为 |
|---|---|---|
| HIGH | 强信号命中：MD5 精确重复；路径截图/录屏；OCR 密度 ≥ 2×阈值；blurScore < 0.5×阈值 | 默认勾选 |
| MEDIUM | 单信号命中但接近阈值：pHash 相似（非精确）；labels 关键词命中（无 OCR 佐证）；blurScore 在 0.5~1×阈值 | 仅列出，不勾选 |
| LOW | 弱信号：NIMA 低分但 blur/exposure 正常（美学差但技术质量可）等 | 仅列出，不勾选 |

protected=true 的项无论置信度一律不默认勾选（§4 优先）。

**Hero 口径**：「预计可释放 X」= 全类目 HIGH 置信且非 protected 项的**去重并集**字节数（互斥裁定保证天然去重，修复 P1/AC-F1-1）。

## 6. UI 设计

### 6.1 类目卡 v2（核心痛点：表现力）

每张类目卡：

```
┌─────────────────────────────────────┐
│ [icon] 低质量照片          [4缩略图] │
│        142 张 · 386 MB              │
│        ● 87 高置信  ○ 55 需确认     │
│                        可释放 210MB ›│
└─────────────────────────────────────┘
```

- **置信度徽标行**：高置信（实心点 + 数量）与需确认（空心点 + 数量，MEDIUM+LOW 合并）分列
- **独立释放空间**：仅统计该类目 HIGH 置信非 protected 项
- **建议优先级排序**：卡片按「HIGH 置信可释放字节」降序，最值钱的类目在最上
- **引导态回归**（修复 P3）：信号未就绪的类目渲染为半透明卡 +「需先扫描」+ 点击跳转 SCAN tab（预选对应扫描项），不再静默消失。信号就绪判定：该类目依赖的信号在库内覆盖率 > 0

### 6.2 Hero 卡修正

- 主数字 = HIGH 置信去重并集（§5）
- 副标题：「另有 N 张待你确认」（MEDIUM+LOW 非 protected 总数）
- 数字变小但真实，符合 AC-F1-1 口径

### 6.3 详情页 v2：三段分组

```
建议删除（87 张）              [全选]   ← HIGH，默认勾选
  3 列网格...
──────────────────────────────
请确认（55 张）                         ← MEDIUM/LOW，不默认勾选
  3 列网格...
──────────────────────────────
⚠ 可能是珍贵照片（12 张）               ← protected，永不预选
  3 列网格（带年代/稀缺角标）
```

- 底部 CTA 只统计已勾选项（「移入回收站 N 张 · 释放 X MB」）
- 顶栏保留「AI 预选」开关改为「置信筛选」开关（仅 HIGH / 全部）
- SwipeReview 入口保留；**SwipeQueueBuilder 改为消费同一管线产出**（修复 P6 口径漂移），桶序沿用类目优先级

### 6.4 视觉规范

- 沿用 HyperOS 风格；新增语义色（高置信/需确认/珍贵保护）走 `design-tokens.json` → codegen 双端镜像，禁止硬编码
- 全部文案五语同步（EN/zh-CN/zh-TW/ES/FR）
- 定稿后固化 `docs/08-UI-SPECS/screens/organize.yaml`（现状缺失），含 hub/类目卡/详情三段/引导态四屏

## 7. 数据层改动

| 信号 | 来源 | Schema 变更 |
|---|---|---|
| blurScore / exposureScore | `BlurAnalyzer` 惰性计算：整理页加载后对缺分图片后台分批补算（≤256px 缩略图，毫秒级/张），结果回写缓存 | `media_assets` 新增 `blurScore REAL`、`exposureScore REAL`（可空）→ **Room v22，MIGRATION_21_22**（当前库已 v21：v20=optimize_feedback、v21=dedup_hash） |
| lastViewedAt | 图片查看器打开时回写当前时间戳 | `media_assets` 新增 `lastViewedAt INTEGER`（可空，同一迁移） |
| isFavorite | MediaStore `IS_FAVORITE`（API 29+），查询时合并 | 无 |
| 录屏判定 | MediaStore 路径 + 分辨率规则 | 无 |
| 人物稀缺度 | `persons`/`face_embeddings` 计数查询（`MediaDao` 新增聚合计数） | 无 |
| 重复/相似 | 复用 `dedup_hash` 与去重 2.0 扫描结果 | 无 |

- `OrganizeRepositoryImpl` 扩展：`OrganizeRow` 投影加新列；缺分图片的补算走协程分批（每批 ≤ 50 张，让出主线程），首屏统计不等待补算完成（缺分即 LOW 信号覆盖，类目显示引导态/部分覆盖标识）
- [PERF] 约束：首屏统计 < 1s（9,000 张，沿 AC-F1-3），补算全部在后台

## 8. 错误处理与降级

| 场景 | 行为 |
|---|---|
| BlurAnalyzer 单张计算失败 | blurScore=null，该项不进 LOW_QUALITY_PHOTOS，不影响其他类目 |
| 未跑 TAG 扫描（labels/ocrText 缺失） | DOCUMENTS 类目标注「覆盖不足」徽标，仍可展示路径可判的项 |
| 未跑美学/人脸打分 | 对应类目显示引导态（§6.1），不消失 |
| Room 查询失败 | hub 空态 + 重试，不崩溃 |
| dedup 未扫描 | DUPLICATES 卡显示「扫描后发现重复」引导（沿用 v1 dedup 入口） |

## 9. 测试策略

全部四层管线纯函数 JVM 单测（`androidApp/src/test/.../domain/organize/`）：

- **CategoryArbiterTest**：互斥性（构造多命中 item 断言唯一桶）、优先级序、空信号不判定
- **ValueGuardTest**：三条保护信号各自命中/边界（5 年 ±1 天、3 张 ±1、收藏/查看）、仅适用于质量两类的断言
- **ConfidenceGraderTest**：HIGH/MEDIUM/LOW 阈值边界、protected 压制勾选
- **OrganizeCategorizerTest**（管线集成）：Hero 并集口径回归（多属 item 只计一次，AC-F1-1 防回归）
- **BlurAnalyzerTest**：固定样本图（清晰/运动模糊/欠曝/过曝各若干）分数分桶正确
- UI：截图比对闭环（`scripts/screenshot-diff.py`）

## 10. 验收标准

- **AC-R2-1（互斥）**：任一媒体在整理中心只属于一个类目；Hero 数字 = HIGH 置信非 protected 去重并集，与各类目卡数值可加总对账
- **AC-R2-2（截图不误判）**：路径含 screenshots 的图只进 SCREEN_CONTENT，不再出现在 LOW_QUALITY_PHOTOS/DOCUMENTS
- **AC-R2-3（价值保护）**：构造 5 年前/稀缺人物/已收藏/已查看四类样本，断言 protected 且不默认勾选、不计入 Hero
- **AC-R2-4（引导态）**：清空打分数据后类目卡显示「需先扫描」而非消失；补算完成后自动转为正常态
- **AC-R2-5（置信度）**：详情页三段分组正确，仅 HIGH 默认勾选；CTA 数字 = 勾选项并集
- **AC-R2-6（性能）**：9,000 张首屏统计 < 1s；BlurAnalyzer 补算不阻塞 UI（[PERF]）
- **AC-R2-7（一致性）**：SwipeReview 队列与类目详情同管线产出，同一张图两处角标原因一致
- **AC-R2-8（i18n/PARITY）**：五语同步；`docs/08-UI-SPECS/screens/organize.yaml` 固化

## 11. 范围边界（非目标）

- **不做** iOS 实现（iOS 整理页为零实现，后续走 ios-follow 管线整屏翻译，本 spec 为其 SSOT）
- **不做**专用模糊检测模型（方案 2 留作升级位：`BlurAnalyzer` 接口预留，未来可替换为 MNN 模型推理）
- **不做**浏览式分类（人物/时间/地点浏览在相册页 GroupingMode，不动）
- **不做**用户可调阈值 UI（阈值常量化集中管理，校准后发布；可调 UI 留后续）
- 删除通路、回收站、Undo 完全复用现有 `DedupTrashManager`/`TrashSessionController`，不重写

---

## 12. 落地校准注记（2026-09-07，Task 1-18b 实际实现 vs 本 spec）

> 本节为落地的唯一事实补充：spec 正文描述设计意图，实现与正文冲突处**以本节为准**。

1. **Hero keeper 扣减与残留口径差**：Hero「Estimated reclaimable」= 全类目 HIGH 非保护并集字节，DUPLICATES 额外按精确组（`exactDupGroupKey`）每组扣 1 张 keeper 字节（扣组内最大 sizeBytes；守卫「组全员都在建议集才扣」），与去重结果页 `reclaimBytes`（只计非 keeper）口径一致（AC-F1-1 扩展）。**已知残留口径差**：hub 徽标 `highCount`（建议删除张数）含 keeper 张数，而 `highBytes` 与 Hero 不含 keeper 字节——张数与字节存在每组 1 张的口径差，有意保留（张数表达建议集规模，字节表达真实可释放）。
2. **聚类库内收敛 + deleteByUri 不接线**：`OrganizeRepositoryImpl.queryDuplicateInfo` 聚类输入先按当前 `media_assets` 库内 uri 过滤（dedup_hash 行在媒体删除后残留：系统相册/回收站到期删除无钩子）——幽灵成员不再虚增组大小，唯一幸存 keeper 组大小塌缩为 1 自然不成组、不进建议删除段。`DedupHashDao.deleteByUri` **有意不接线**：回收站 30 天可恢复 + 库内收敛过滤后残留行无危害，删除侧零改动。
3. **详情页 DUPLICATES 留 keeper 口径**：预选（reload/setAiPreselect）与 selectAll 均按 `exactDupGroupKey` 每组排除 1 张 keeper——keeper = 组内 `captureDate` 最新者（同值按 uri 字典序取大求稳定；精确组内容逐字节相同，选谁无质量差，故不复用 `KeepPolicyEngine`）；无组标识成员（similar-only）不参与排除。与 hub 扣减口径互为对偶，防全选/预选删光整组。
4. **§6.1「预选对应扫描项」裁剪**：引导卡点击仅切 SCAN Tab（`onOpenScan = onSelectTab(SCAN)`），不预选对应扫描项——明示裁剪为后续项。
5. **§6.3 段级全选裁剪**：「建议删除」段的段级「全选」按钮未实装；VM `selectAll`/`deselectAll` 就绪（排除 protected + DUPLICATES 留 keeper）但暂无 UI 调用方——明示裁剪为后续项。
6. **顶栏文案**：详情页顶栏开关以实现的「AI 预选」（`org_ai_preselect_on/off`）为准，§6.3 原文「置信筛选」作废；开关语义：开 = 立即勾选 HIGH 非保护项，关 = 立即清空（对称语义，手选项需重新点选）。
7. **桶序偏差**：`SwipeQueueBuilder` 桶序 SCREENSHOT → BLURRY → LOW_QUALITY_PORTRAIT → RECENT，延续 v1 UX；§5/§6.3「桶序沿用类目优先级」句面作废（类目优先级 PORTRAITS > PHOTOS，实现有意让 BLURRY 先出队）。
8. **备份三列 + COALESCE**：备份模型 v5 的 `mediaTagMetadata` 增 `blurScore`/`exposureScore`/`lastViewedAt` 三列；恢复侧 `MediaDao.updateTagMetadataFromBackup` 用 `COALESCE(:new, existing)`——旧备份缺字段（null）不冲掉本机已算值（`lastViewedAt` 是保护信号不可再生）。
9. **queryDuplicateInfo 失效键缓存（PERF）**：聚类缓存失效键 = 过滤后库内聚类输入（uri/md5/phash 排序集合）本身——media_assets 无关行写（TAG 回写、lastViewedAt 节流回写、质量分合批）不再触发全表 O(n²) 重聚类；媒体删除自然失效。原「真机验证后再定是否优化」已落地为缓存方案（耗时打点 `queryDuplicateInfo: N hashes in Xms` 保留观察）。
10. **实际阈值表**（`OrganizeThresholds` 源码抄录，校准只改这里）：

    | 常量 | 值 | 用途 |
    |---|---|---|
    | `FACE_QUALITY_LOW` | 0.35 | eDifFIQA（0~1）低于判低质人像 |
    | `BLUR_VARIANCE_LOW` | 100.0 | Laplacian 方差（256px 灰度口径）低于判真模糊 |
    | `EXPOSURE_UNDER` / `EXPOSURE_OVER` | 0.15 / 0.85 | 平均亮度（0~1）越界判欠曝/过曝 |
    | `STRONG_SIGNAL_FACTOR` | 0.5 | 「越低越差」型信号强命中 = 主阈值 × 0.5 |
    | `AESTHETIC_LOW` | 3.5 | 预留常量（沿用 v1 定义保留），当前管线未消费——不定类、不参与置信分级（主源码除定义外零引用，ConfidenceGrader 无 NIMA 分支） |
    | `LARGE_VIDEO_BYTES` | 100 MiB | 大视频阈值 |
    | `LARGE_PHOTO_PIXEL_AREA` / `LARGE_PHOTO_BYTES` | 50 MP / 20 MB | 超分辨率照片（两条件同时满足） |
    | `LARGE_FILE_STRONG_FACTOR` | 2 | 大文件强命中 = 大小 ≥ 2× 主阈值 |
    | `OLD_PHOTO_YEARS` | 5 | 老照片保护（captureDate < now − 5 年） |
    | `PERSON_SCARCE_MAX` | 3 | 人物聚类 ≤3 张判稀缺保护 |
    | `OCR_STRONG_FACTOR` | 2 | DOCUMENT 强命中 = 密度 ≥ 2× 裁定阈值 |
    | OCR 裁定阈值 | 20 字符/MP（兜底 200 字符） | SSOT 在 dedup 侧 `DedupContentTypeDetector`，此处别名保持 2× 不变式 |
    | pHash 相似阈值 | 汉明 ≤ 5 | `PerceptualHash.SIMILAR_HAMMING_THRESHOLD`，与去重 2.0 VISUAL 同口径 |

11. **EXACT 成组条件分叉**：hub/整理侧 EXACT 成组 = 纯 MD5（`DuplicateGrouper` 消费 dedup_hash 缓存）；去重结果页 EXACT = `(sizeBytes, mime)` 分桶内 MD5——理论上同字节异扩展名文件 hub 成组而去重页不成组，实践影响忽略，注记即可。
12. **BLUR_VARIANCE_LOW 真机定标待办**：100.0 为初始值（按典型手机实拍分布取）；256px 降采样会压缩高频成分，真机实拍模糊分布待采集后用 `BlurAnalyzerTest` 样本校准。
13. **其他已登记偏差**（计划在录，不重复展开）：`SignalCollector.kt` 不单列（采集职责由 `OrganizeRepositoryImpl.mergeRows` + `DuplicateGrouper` 承担）；§6.4 语义色 token 化本期降级为 Compose `colorScheme` 直用（primary/tertiary/error）。

**落地产物索引**：UI SSOT `docs/08-UI-SPECS/screens/organize.yaml`（iOS 翻译唯一输入，[PARITY]）；模块实现规范 `features/gallery/AGENTS.md` §2.4；i18n 12 个 `org_*` 新键 + `org_hero_across` plurals 五语同步（旧键 `org_cat_screenshots`/`org_cat_large_videos` 已删）。
