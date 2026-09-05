# 相册整理五大核心 Feature 产品与设计规范（竞品借鉴版）

> **版本**：1.0
> **日期**：2026-09-05
> **状态**：设计稿阶段（Ardot 绘制中），未动工
> **竞品调研依据**：`docs/reviews/2026-09-05-photo-organizer-competitor-research.md`
> **现状 SSOT**：`androidApp/.../features/gallery/AGENTS.md`（§2.4 去重 2.0、§2.9 TAG 控制）、`docs/superpowers/specs/2026-08-25-album-dedup-design.md`
> **红线约束**：全部媒体处理端侧完成，零上传（[PRIVACY]）；五语同步（[I18N]）

---

## 0. 选型逻辑

竞品调研 P0/P1 共 5 项，与破浪现状对账后：

| 竞品 feature | 破浪现状 | 结论 |
|---|---|---|
| 相似/重复照片分组 + 智能选最佳 | ✅ 去重 2.0 已全量落地（三级尺度 + 保留规则 + 回收站） | **不重复做**，作为整理中心的一个类目收口 |
| 智能清理看板 | ❌ 无（数据已备：contentType/美学分/文件大小） | **F1 本期做** |
| 手势清理模式 | ❌ 无 | **F2 本期做**，加 AI 预排序差异化 |
| 自然语言整理指令 | ⚠️ 链路已在（Chat→capability.dispatch→写确认），缺媒体预览确认卡 | **F4 本期做** |
| On This Day / 回忆 | ❌ 无（数据已备：TAG + 时间 + 美学分） | **F3 本期做** |
| 私密相册 | ❌ 无 | **F5 本期做**（Keepsafe 验证的最大单点付费项） |

五个 feature 共用一条主线：**「端侧 AI 帮你把相册越用越干净」**，全部建立在已有资产（TAG 3-Pass、美学/人脸质量分、dedup contentType、MediaSearchEngine、回收站删除通路、Chat 写确认链路）之上。

---

## F1 整理中心（Cleanup Hub）

**对位竞品**：Clever Cleaner Smart Cleanup、Cleanup 看板；**破浪差异化**：不只是一个清理器，是「相册整理」Pager 页 1 的首页升级。

### 需求

- 现有「相册整理」（Dedup）主页升级为整理中心：顶部 Hero 卡显示「预计可释放 X GB」（各类目合计），下方类目卡列表：
  | 类目 | 数据来源（全部已有） | 默认动作 |
  |---|---|---|
  | 重复与相似照片 | 去重 2.0 扫描结果 | 进入现有 dedup 流程 |
  | 屏幕截图 | dedup contentType=SCREENSHOT（MediaStore RELATIVE_PATH） | 网格批量选删 |
  | 模糊/低质量照片 | `aestheticScore` 低分（NIMA）+ 无脸/构图差 | 网格批量选删，AI 预选 |
  | 闭眼/废片人像 | `faceQualityScore` 低分（eDifFIQA） | 网格批量选删，AI 预选 |
  | 大视频 | `sizeBytes` + `MediaType.VIDEO` 排序 | 网格批量选删 |
  | 文档/单据照片 | dedup contentType=DOCUMENT | 网格批量选删 |
- 每张类目卡：数量 + 占用空间 + 缩略图条（前 4 张）+ chevron；点击进类目详情网格（长按批选/全选/AI 预选切换），底部 CTA「移入回收站 N 张 · 释放 X MB」。
- 删除通路 100% 复用去重 2.0：`DedupTrashManager` 系统回收站 + 授权残留复查（IS_TRASHED 口径），完成页可 undo。
- 数据覆盖不足（TAG/美学分未扫描）时类目卡显示「需要先扫描」引导，跳转 TAG 控制页。（校准注记：v1 不做引导卡——未打标时 BLURRY/LOW_QUALITY_PORTRAITS 卡不渲染（categorizer 对未评分项不判定、空类目不生成统计卡）；后续版本补引导卡。）

### 屏清单（Ardot）

- `organize/hub` — Hero（可释放总量）+ 6 类目卡
- `organize/category_grid` — 类目详情：AI 预选态网格 + 底部 CTA
- `organize/cleaned` — 完成页：释放统计 + 撤销/查看回收站

### 验收

- AC-F1-1：各类目数量/空间统计与媒体库真实一致；Hero 合计 = 各类目去重后并集口径（同一照片不重复计入）。
- AC-F1-2：类目删除走系统回收站，30 天可恢复；授权失败不移出列表（沿 dedup 语义）。
- AC-F1-3：9,000 张相册的类目统计首屏 < 1s（统计走 Room 聚合查询，不做全量解码）。

---

## F2 手势快速整理（Swipe Review）

**对位竞品**：Slidebox / Swipewipe / Daily Delete；**破浪差异化**：AI 预排序——不是从最新照片顺序过，而是按「废片概率」排队（截图、模糊、连拍冗余、低美学分优先），用户单位时间决策价值最大化。

### 需求

- 全屏卡片式审阅：大图为王，右滑保留、上滑删除（进回收站）、左滑跳过（本轮不再出现）；支持点按翻下一张（长辈模式）。
- 顶部进度：「12 / 86 · 已释放 45 MB」；右上角撤销（Undo，回退上一步任何决策）。
- 排序队列（`SwipeQueueBuilder` 纯函数）：优先级桶 = 截图 > 模糊/闭眼 > 连拍冗余（dedup L3 组成员）> 低美学分 > 普通（按时间倒序）；已打「保留」决策的媒体 30 天内不再入队。
- 结束页：本次保留/删除/跳过统计 + 释放空间 + 「再来一轮」。
- 手势删除与 dedup 同一回收站通路；删除前不逐张弹系统授权（批量收集，结束或满 20 张时一次性 `createTrashRequest`）。
- 入口：整理中心 Hero 卡下方「快速整理」主按钮 + 相册页菜单。

### 屏清单（Ardot）

- `swipe/review` — 卡片主屏（大图 + 手势 hint + 进度 + 撤销）
- `swipe/done` — 一轮完成统计页

### 验收

- AC-F2-1：连续滑动 100 张无卡顿（[PERF] 交互 < 100ms，预加载前后各 3 张）。
- AC-F2-2：废片桶命中率可解释——卡片角标显示入列原因（「截图」「模糊」「连拍 3/8」）。
- AC-F2-3：Undo 可连续回退到本轮第一张；删除未提交前全部可逆。

---

## F3 回忆（Memories / On This Day）

**对位竞品**：Google Photos Memories/Recap、Daily Delete 日历；**破浪差异化**：100% 端侧生成（Google 走云端 Gemini），卖点「回忆不出手机」。

### 需求

- 相册首页顶部新增回忆横滑 carousel（最多 10 条），类型：
  - **那年今日**：同月同日跨年照片 ≥ 4 张时生成
  - **近期高光**：近 7/30 天按美学分 + 人脸质量精选
  - **人物合集**：已命名人物（「和妈妈的瞬间」），依赖人物聚类 + 关系图谱
  - **地点足迹**：同 `city` 聚类（逆地理编码列已有）
- 回忆详情页：封面大图 + 标题（自动文案，如「2023 年的今天」）+ 精选网格（美学分排序，默认 12 张，可展开全部）+ 分享（系统分享多图）。
- 生成管线：纯查询编排（Room 聚合 + 美学分排序），零新增推理；每日首次进入相册时增量重算，结果缓存 Room 表 `memories`。
- 可控性：长按 carousel 卡片可「隐藏此回忆」；设置「功能」组提供总开关。

### 屏清单（Ardot）

- `memories/carousel` — 相册首页顶部改造（carousel + 原网格）
- `memories/detail` — 回忆详情（封面 + 精选网格）

### 验收

- AC-F3-1：carousel 生成不阻塞相册首屏（异步填充，占位骨架 < 100ms 让位）。
- AC-F3-2：无任何一条回忆时 carousel 整体不占位（不显示空容器）。
- AC-F3-3：隐藏操作即时生效且可恢复（设置页「已隐藏的回忆」列表）。

---

## F4 自然语言整理（Chat 清理确认卡）

**对位竞品**：无直接对位（破浪独有链路）；**本质**：把已有 `capability.dispatch` 写确认从「纯文字确认框」升级为「媒体预览确认卡」。

### 需求

- Chat 输入「把上个月的截图和模糊的照片清掉」→ 远程 LLM 生成 StructuredFilter（复用 SearchIntent 链路）→ 本地 `MediaSearchEngine` 命中 → 聊天内插卡：
  - 命中摘要：「找到 86 张：截图 52 · 模糊 34，共占用 312 MB」
  - 九宫格缩略图预览（点按展开全屏查看器逐张排查，可取消勾选单张）
  - 风险分级沿用 `CommandRisk`：删除类必弹卡；卡内按钮「移入回收站」「再看看」「取消」
- 确认后走回收站删除通路，完成回卡：「已移入回收站 84 张 · 释放 305 MB（30 天内可恢复）」+ 撤销按钮。
- 复用 `WriteConfirmationController` 既有约束：120s 超时按拒绝、并发互斥、脚本结束在途确认失效；本 feature 只新增「媒体预览确认卡」消息类型与批量勾选模型。
- 支持整理类复合指令的渐进澄清：「照片太多帮我整理一下」→ LLM 反问选项卡（清截图/清模糊/清连拍/去重）。

### 屏清单（Ardot）

- `chat/cleanup_confirm` — 聊天内媒体预览确认卡
- `chat/cleanup_done` — 完成回卡（含撤销）

### 验收

- AC-F4-1：确认卡缩略图与实际命中集一致；取消勾选单张后数量/空间即时重算。
- AC-F4-2：120s 无操作按拒绝处理，媒体零变更。
- AC-F4-3：隐私红线——命中筛选 100% 本地，远程 LLM 只见文本 query 与结构化统计。

---

## F5 私密相册（Vault）

**对位竞品**：Keepsafe（7000 万用户验证）；**破浪差异化**：与主相册一体（Keepsafe 是独立仓库），端侧加密，无云同步争议。

### 需求

- 锁屏页：PIN（6 位）+ 生物识别（BiometricPrompt）双因子可选；连续 5 次错误锁定 5 分钟。
- 移入：相册多选菜单 / 查看器菜单「移入私密相册」→ 复制到 app 私有目录（AES-GCM 加密存储）→ 原文件走系统回收站删除通路（可 30 天反悔）；移入完成 snackbar 提示。
- 库内：网格 + 查看器（复用 MediaPager 组件模型，数据源为 vault 私有表）；支持移出（解密回 MediaStore 原目录）。
- 数据库 `vault_items` 表仅存元数据（原文件名/尺寸/移入时间/缩略图密钥句柄），不存明文路径。
- v1 非目标：假密码（decoy）、入侵抓拍、云备份（与 [PRIVACY] 端侧定位冲突，留待付费层设计）。
- 入口：设置「功能」组列表行 + 相册多选菜单；主界面不留常驻 Tab（避免暴露其存在）。

### 屏清单（Ardot）

- `vault/lock` — PIN 解锁屏（生物识别按钮）
- `vault/grid` — 库内网格（含多选/移出）

### 验收

- AC-F5-1：移入后系统相册/其他 App 不可见原文件；vault 文件抽机取证（adb pull 私有目录）为密文。
- AC-F5-2：移出完整还原到原目录，元数据（拍摄时间/GPS）不丢。
- AC-F5-3：解锁失败锁定计时重启 App 不重置（持久化失败计数）。

---

## 五 feature 信息架构总图

```
主页面 Pager（页序不变）
├─ 页0 相册 Gallery      → 顶部新增 memories carousel（F3）；多选菜单新增「移入私密相册」（F5）
├─ 页1 相册整理 Dedup    → 首页升级为整理中心 hub（F1）：Hero + 快速整理（F2 入口）+ 6 类目卡（重复照片进原 dedup 流程）
├─ 页2 聊天 Chat         → 新增媒体预览确认卡消息类型（F4）
└─ 页3 人物 People       → 不动（F3 人物合集的数据源）
NavHost 新增路由：
├─ swipe_review          → F2 全屏手势整理
├─ organize_category     → F1 类目详情
└─ vault                 → F5 私密相册（lock/grid 内部状态机）
```

## 设计稿规格（Ardot）

- 文件：`polang-ui-spec`（id 715061534788814）；新建页 `Organize`（F1+F2）、`Memories`（F3）、`Vault`（F5）；F4 两帧加入既有 `Chat` 页
- 帧规格：393×852，Dark mode，PoLang Tokens 变量集（`scheme/*` 语义色 + typography/spacing/radius 变量），命名 `feature/screen_state`
- 视觉基线对齐既有稿：卡片=surfaceContainer r16、弹层=surfaceContainerHighest、描边=outlineVariant、强调=primary（#8FD6C6）、品牌渐变=ChatBubbleTokens
- 文案：英文为准（英文体验优先原则），键名前缀规划 `org_*`（F1）/ `swipe_*`（F2）/ `memory_*`（F3）/ `chat_cleanup_*`（F4）/ `vault_*`（F5）

### 帧清单（2026-09-05 已绘制，11 帧全验收）

| 帧名 | node id | 所在页（page id） |
|---|---|---|
| `organize/hub` | 267:24 | Organize（267:21） |
| `organize/category_grid` | 267:150 | Organize |
| `organize/cleaned` | 267:280 | Organize |
| `swipe/review` | 267:312 | Organize |
| `swipe/done` | 267:329 | Organize |
| `memories/carousel` | 267:356 | Memories（267:22） |
| `memories/detail` | 267:407 | Memories |
| `chat/cleanup_confirm` | 267:437 | Chat（111:319） |
| `chat/cleanup_done` | 267:518 | Chat |
| `vault/lock` | 267:649 | Vault（267:23） |
| `vault/grid` | 267:698 | Vault |

- 留档截图：`tmp/ardot-org/shots/`（每帧终版 PNG）
- 已知遗留：`vault/lock` 生物识别键暂用 `ic/face` 组件，待 IconSet 补 fingerprint 主组件后替换
