# 相册去重 2.0 产品与设计规范

> **版本**：1.2
> **日期**：2026-08-25（v1.1：2026-08-26 新增 §10 内容类型差异化策略；v1.2：2026-09-04 状态与实现校准）
> **状态**：全量已落地 Android（含 §10 内容类型差异化、§11 入口与导航、§12 按 Tab 细分批量）；两处 v1.0 目标未落地，见 §5.4/§5.5 校准注记；iOS 对等跟随中（AC-5）
> **设计稿**：Ardot 文件 `Organize` 页（2026-09-06 起：原 `Dedup` 页 6 屏流程稿迁入 `Organize` 页第二行，`Dedup` 页删除；`dedup/overview` 帧因被 `organize/hub` 取代同步删除，存档见 git 历史）
> **现状 SSOT**：`androidApp/.../features/gallery/AGENTS.md` §2.4、`domain/dedup/`

---

## 1. 背景与问题

现有去重（MD5 精确 + pHash 相似，汉明阈值 5 硬编码）能跑通，但体验粗糙：

1. **尺度单一**：「精确」与「相似」两级混在一起，压缩/微调版本与连拍同场景没有区分，阈值不可配。
2. **保留规则僵化**：硬编码保留 index 0（分辨率→美学分→时间），UI 不可改选；预览弹窗只读；压缩/编辑版本与原图的关系没有建模。
3. **反馈黑盒**：进页全量扫描，一个转圈跑到底，无进度、不可取消、结果不持久化。
4. **删除无兜底**：直接 MediaStore 删除，授权失败组也会被移出列表（`MediaViewModel.kt:400-402`）；无回收站。

## 2. 目标

- 用户能**理解并选择**「重复」的尺度，误删风险可控。
- 每组「留哪张」**默认合理、可解释、可改选**。
- 扫描**边扫边出结果**，随时可暂停/取消/转后台。
- 删除**有回收站兜底**，30 天可恢复。

非目标（本期不做）：视频去重、云端去重、Chat 去重 capability 实连（仅规划入口）。

## 3. 核心概念：三级重复尺度

| 级别 | 名称 | 定义 | 检测方式 | 默认 |
|------|------|------|----------|------|
| L1 | 完全重复 | 字节完全相同（同图复制/多次保存） | size+mime 分桶 → 流式 MD5 | ✅ 开 |
| L2 | 视觉重复 | 同一张图的压缩、缩放、微调、滤镜版本 | pHash 64-bit，汉明 ≤ 5（保守档）/ ≤ 8（宽松档） | ✅ 开 |
| L3 | 场景相似 | 连拍、同场景不同构图的多张 | pHash 宽松 + 拍摄时间窗口（≤ 10s 连拍优先成组）+（后续）embedding 聚类 | ⬜ 关 |

- 尺度在扫描前由用户勾选（`dedup/overview` 屏），L3 默认关闭并标注「误报较多，需逐组确认」。
- 三级结果在结果页分 Tab 展示，删除策略不同：L1 可放心批量，L2 默认批量但逐组可改，L3 必须逐组确认（无「智能全选」）。批量操作自 2026-08-26 起按当前 Tab 细分（见 §12）。

### 压缩/微调版本的关联建模

L2 组内按「版本链」标注每张图的身份 badge：

- `原图`：组内分辨率最高且文件最大、无编辑标记者。
- `已压缩`：分辨率或文件体积显著小于原图（< 50% 像素）。
- `已编辑`：MediaStore `IS_PENDING`/相对路径含编辑产物目录，或存在同原图的编辑记录（编辑器输出约定）。
- badge 驱动保留规则的默认勾选（见 §4）。

## 4. 保留规则（Keep Policy）

全局规则决定**默认勾选哪张**，每组永远可以手动改选（单选）。

| 规则 | 含义 | 排序键 |
|------|------|--------|
| 保留最高画质（默认） | 信息最多者胜 | 像素面积 ↓ → 文件大小 ↓ → 美学分 ↓ → 拍摄时间 ↓ |
| 保留原图 | 原始文件胜 | 版本链：原图 > 已编辑 > 已压缩，同层按画质 |
| 保留已编辑版 | 用户调过的胜 | 已编辑 > 原图 > 已压缩，同层按画质 |
| 保留最新 | 最近保存的胜 | 修改时间 ↓ |

- 规则在 `dedup/keep_rules` 底部弹层修改，立即重算所有组的默认勾选并给出「已按规则更新 N 组」反馈。
- 组内手动改选会覆盖规则，并在组卡片上显示「已手动选择」标记，不再被规则重算覆盖。
- **改选即返回（2026-09-05 交互修正）**：组详情页点「保留这张」= 改选 + 立即收起详情页回结果列表（一次点选完成决策，组卡片「已手动选择」与预计可释放量即时反馈），不再停留待二次确认；删除仍由详情 CTA（不改选时直接点）/ 结果页底部批量 CTA 承担。全屏对比预览内的「保留这张」维持原位改选不收起（多轮比较场景）。
- **安全约束（不可突破）**：任何规则下，「全部删除」按钮都不存在——每组至少保留一张；L3 场景相似组不参与任何批量操作。

## 5. 渐进式扫描

扫描管线改为**分批流式**，结果即发现即上屏：

1. **分批枚举**：MediaStore 按 500 张一批取出，逐批处理。
2. **阶段推进**：L1（MD5，快）→ L2（pHash，慢）→ L3（若开启）。顶部进度条显示「阶段 2/3 · 已扫描 6,120 / 9,832」。
3. **流式上屏**：每凑齐一组立即插入「实时发现」列表（带平滑动效，2026-08-27 落地），用户**扫描中即可点进组处理**。「实时发现」卡片为**双大图预览布局**（header：级别 badge + meta + chevron；正文：两张等分正方形缩略图）——卡片高度即最终态、任意组恒定，与组详情/结果卡同量级，消除「紧凑小行 → 大卡」的高度跳变与插入跳闪。
4. **控制**：暂停 / 继续 / 取消（均已落地）。~~转后台（前台 Service + 通知，复用 `TagGenerationService` 模式）~~ **未落地**：`domain/dedup` 无 Service，退后台扫描随 Activity 级 ViewModel 中止（2026-09-04 校准）。
5. **断点与缓存**：pHash/MD5 结果持久化到 Room（`media_id + modified_at` 失效判断，`dedup_hash` 缓存表，db v21），二次扫描只算增量。~~扫描结果组持久化，进程重启不丢~~ **未落地**：结果组为 `DedupViewModel` Activity 级内存态，进程重启需重扫（哈希缓存使重扫成本低，2026-09-04 校准）。

## 6. 删除与回收站

- 删除走**系统回收站**：MediaStore `IS_TRASHED`（API 30+，`DedupTrashManager`），30 天内可恢复、之后由系统彻底清除；API ≤ 29 无系统回收站，由 `legacyDeleter` 走旧删除授权流（无 30 天兜底，2026-09-04 校准）。
- **授权后残留复查必须读 `IS_TRASHED` 列（2026-09-05 修复）**：`DedupTrashManager.queryExisting` 原仅以「行可查」判残留，实测 HyperOS（Android 16）对已 trash 行的直接 item-URI 查询仍返回该行（AOSP 默认查询才过滤 trash 行）→ 误判部分拒绝、整组保留不刷新（用户感知「没真删、列表未刷新」）。现投影 `_ID + IS_TRASHED`，行存在但已 trash 不算残留；列缺失（API < 29）保守视为仍存在。
- 删除确认页明确列出「保留 X 张 / 删除 Y 张 · 释放 Z MB」，授权失败**不**从结果列表移除该组（修复现状 bug）。
- 清理完成页给出总量反馈与「撤销 / 查看回收站」入口。

## 7. 信息架构与 UI 流程

```
一级入口（2026-08-26 升级，见 §11.1：设置主菜单「相册整理」行 / 相册悬浮 Tab 首项 / 相册页左滑 → 主页面 Pager 页 1；以下 dedup/* 为该页内部四态流程，非 NavHost 路由）
  └─ dedup/overview      尺度选择 + 保留规则入口 + 上次摘要 + 开始扫描
       └─ dedup/scanning   渐进扫描：进度 + 实时发现流 + 暂停/后台
            └─ dedup/results  三 Tab 结果页 + 全选本类(L1/L2 Tab) + 底部 CTA（跟随当前 Tab，§12）
                 ├─ dedup/group_detail  组内对比改选（版本链 badge + 单选保留）
                 ├─ dedup/keep_rules    保留规则底部弹层
                 └─ dedup/cleaned       完成页 + 回收站撤销
```

设计稿共 6 屏（393×852，Dark，PoLang Tokens），见 Ardot `Organize` 页（2026-09-06 自 `Dedup` 页迁入，overview 帧已删，现存 5 屏）。

## 8. 技术要点（落实现状差距）

- 去重域模型独立为 `domain/dedup/DedupModels.kt`：`DedupGroup`（`level: DedupLevel`、`members: List<DedupMember>`，含 `versionRole: ORIGINAL/COMPRESSED/EDITED`、`keepSelected: Boolean`、`userOverride: Boolean`）；旧共享 `DuplicateGroup` 已随去重 2.0 删除。
- `PerceptualHash.SIMILAR_HAMMING_THRESHOLD` 常量化改为扫描参数（保守 5 / 宽松 8）。
- 扫描器改 `Flow<DedupScanEvent>`（Progress / GroupFound / PhaseChanged / Done），ViewModel 独立为 `DedupViewModel`（脱离共享 `MediaViewModel`）。
- 修复：预览文件名解析（`content://` → `DISPLAY_NAME` 查询）、LazyColumn 稳定 key、授权失败不移除组、组 id 稳定化（聚类成员排序后 hash）。
- 全部端侧，遵守 [PRIVACY] 红线；五语文案同步（EN/zh-CN/zh-TW/ES/FR），遵守 [I18N]。

## 9. 验收标准

- AC-1：三级尺度可勾选，L3 默认关闭且结果页无批量操作。
- AC-2：四种保留规则切换后默认勾选即时重算；手动改选不被覆盖。
- AC-3：9,000 张相册扫描中每组发现 ≤ 1s 内上屏；可暂停/取消（已落地）；~~可后台；杀进程后结果仍在~~（未落地，见 §5.4/§5.5 校准注记）。
- AC-4：删除进回收站，30 天可恢复；授权失败组保留在列表。
- AC-5：iOS 端按本 spec 对等跟随（走 ios-follow 管线）。

---

## 10. 内容类型差异化策略（v1.1）

### 10.1 动机

「重复」的语义随内容类型而不同：截图的视觉相似误报率高（同一 App 界面、内容不同）；人像连拍的价值恰恰集中在 L3 场景相似级；证件/文档承载信息，删除须最保守。单一阈值与单一保留排序无法同时服务好这三类，需按内容类型细分策略。

### 10.2 内容类型与识别依据（全部端侧、零额外推理）

| 类型 | 识别依据 | 可靠性 |
|------|----------|--------|
| `SCREENSHOT` 截图 | MediaStore `RELATIVE_PATH` 含 Screenshots 目录（API 29+；API 24-28 无该列，以 `DATA` 列路径兜底，退化基本消除） | ≈100%（系统约定） |
| `PORTRAIT` 人像 | `media_assets.hasFace = 1` 或 `faceQualityScore` 非空（TAG Pass 1 人脸检测已产出） | 高，依赖 Pass 1 覆盖 |
| `DOCUMENT` 文档/证件 | `ocrText` 文字密度超阈值（字符数 / 图面积），或 `labels` 含 document/text/receipt 类标签 | 中，依赖 TAG Pass 3 覆盖 |
| `GENERAL` 普通 | 以上皆非（含 TAG 未覆盖的存量照片） | 兜底 |

- 识别在扫描取数阶段完成（`DedupMediaSource` 组装 `ScanItem` 时携带 `contentType`），不产生新的推理开销。
- **退化原则**：TAG 未覆盖的照片一律归入 `GENERAL`，走现行默认策略——细分能力随打标覆盖率自然增强，不被覆盖率阻塞。
- 一张照片命中多类时优先级：`SCREENSHOT` > `DOCUMENT` > `PORTRAIT` > `GENERAL`（截图语义最强，证件保守性优先于人像美观）。

### 10.3 差异化策略矩阵

| 策略维度 | 普通 | 人像 | 截图 | 文档/证件 |
|----------|------|------|------|-----------|
| L2 视觉阈值 | 默认（≤5） | 默认 | **收紧（≤3）** | 默认但结果不自动勾选 |
| 组内保留排序 tiebreak | 画质（现行） | **人脸质量分 → 美学分 → 画质** | 画质（现行） | 画质（现行） |
| 智能全选 / 默认预选 | L1/L2 参与 | L1/L2 参与 | **仅 L1 参与；L2 组不预选** | **仅 L1 参与；L2 组不预选** |
| L3 场景相似 | 逐组确认（现行） | **推荐开启**（连拍价值最高，扫描页对人像多时提示） | 逐组确认 | 逐组确认 |

- 「不预选」= 组出现在结果页但默认不勾选任何待删项，组卡片无保留框/删除标记，底部 CTA 不计入。
- 阈值收紧仅作用于 `SCREENSHOT` 组内成员的相互比对；跨类型不成组（截图与普通照片不进同一 VISUAL 组）。

### 10.4 UI 表达

- **组卡片**：级别 badge 旁新增内容类型 badge（中性描边小胶囊，与彩色级别 badge 区分）：`截图` / `人像` / `文档`；`GENERAL` 不显示 badge（避免噪音）。
- **footer 策略文案**按类型解释默认行为：
  - 人像：`人像组 · 已优先保留人脸质量最佳，可改选`
  - 截图：`截图相似易误判 · 本组未预选，请逐组确认`
  - 文档：`文档仅完全重复可批量 · 相似版本请逐组确认`
- **Config 页不加新入口**：类型识别与策略全自动，保持配置面简单（尺度三级 + 保留规则四选不变）。
- 组详情为**全屏页**（2026-08-27 由底部半屏弹层改全屏，对齐 `dedup/group_detail` 整屏设计帧；顶栏含返回/标题/保留规则入口，确认 CTA 固定底部）；不预选组进入详情后选择保留项即视为逐组确认，勾选生效。
- **详情 CTA 逐组立即删除（2026-09-04 行为修正）**：组详情底部「保留所选 · 删除其余 N 张」经 `DedupViewModel.deleteGroup(groupId)` 立即走系统回收站授权流（此前仅收起详情页、未真删，用户感知为「点了没反应」）；确认后**留在 Results 原地刷新**（该组消失、跨级重叠组剔除已删成员，与批量共用 `computeRemainingGroups`），不进 Cleaned 完成页；`PendingTrash.groupId` 区分逐组/批量两种授权语义。deleteUris 为空（未预选未改选）时 CTA 为「确认本组选择」纯收页。L3 场景相似的删除由此通路承担（§4 逐组确认）。
- **全屏对比预览（2026-08-27 增补）**：组详情页内点任一成员缩略图进全屏预览——组内成员横向翻页 + 双指缩放比较细节，底部展示当前页大小/日期/角色 badge 与保留状态；Results 态预览内提供「保留这张」直达改选（比较后立即决策，不必返回详情页），Scanning 态只读。

### 10.5 技术要点

- `DedupMember` 增加 `contentType: ContentType`；`KeepPolicyEngine.recommend` 在人像组按 §10.3 调整排序键。
- `DedupScanner`：VISUAL 聚类按 `contentType` 分桶后再按阈值成组（截图桶用收紧阈值）；跨桶不成组。
- `DedupGroup` 增加 `autoPreselected: Boolean`（false 时结果页不勾选、不进批量 CTA）；批量口径收口 `DedupGroup.batchEligible`——`batchDeleteUris`/`batchReclaimBytes` 统一 `filter batchEligible`（SCENE 组与未预选未改选组均不参与，详情改选 userOverride 后正常派生）。
- 识别数据源已在 `media_assets`（hasFace/faceQualityScore/ocrText/labels）与 MediaStore（RELATIVE_PATH，API<29 走 DATA 兜底；WIDTH/HEIGHT 自 API 16 可用、全版本入 projection，供 OCR 密度归一），无 schema 变更。

### 10.6 验收标准（追加）

- AC-6：截图 VISUAL 组默认不预选且不计入批量 CTA；人像组默认保留项为人脸质量分最高者；TAG 未覆盖照片全部归入 GENERAL 且行为与 v1.0 一致。
- AC-7：内容类型 badge 与策略文案五语同步（EN/zh-CN/zh-TW/ES/FR）；类型识别不产生额外扫描耗时（取数阶段顺带判定）。

## §11 入口与导航（2026-08-26）

去重 2.0（相册整理）从设置二级入口升级为一级入口，并打通头像拍摄链路。

### 11.1 相册整理入口

- **Pager 页 1（2026-08-26 二轮升级）**：相册整理从 NavHost 路由升级为主页面 Pager 正式页，页序改为 **相册(0) / 相册整理(1) / 聊天(2) / 人物(3)**（`MAIN_PAGE_*` 常量，`MainPagerHost.kt`）；相机页从 Pager 移除（路由化，见 §11.2）。`Screen.DedupHome` NavHost 路由随之删除，避免双宿主。相册页**左滑**进入相册整理由外层 HorizontalPager 原生承载（原「页内手势与 pager 抢事件」TODO 删除）。
- **设置主菜单**：Gallery 行更名「相册扫描」（`gallery_settings`：Gallery Scan / 相册扫描 / 相簿掃描；该 key 同时是 TagControl 页标题，同步生效为期望行为）；其下方新增「相册整理」行（`gallery_cleanup`：Gallery Cleanup / 相册整理 / 相簿整理，`Icons.Rounded.BurstMode`），经 `switchMainPage(MAIN_PAGE_DEDUP)` 弹回 Main 并切页。
- **悬浮底部 Tab**：GalleryScreen 悬浮 Tab 第一项由相机改为相册整理（`Icons.Outlined.BurstMode`，与同行 Outlined 图标风格一致），点击 `onSwitchPage(MAIN_PAGE_DEDUP)` 瞬时切相邻页（与底部 Tab 瞬时切页风格一致）；相机已路由化，不再有常驻入口。
- **TagControl 头部**：`GallerySettingsHeader` 移除「管理重复照片」行；`manage_duplicates` key 保留（仅剩休眠 GALLERY 二级页死代码引用）。
- **返回行为**：相册整理页顶栏返回与系统返回键均切回相册页（Pager 页 0），不弹栈——系统返回键由 `MainPagerHost` 的「非相册页回相册」BackHandler 统一消费；内部 Config→Scanning→Results→Cleaned 四态流程不变，`DedupViewModel` 为 Activity 级，Pager 托管安全。

### 11.2 头像拍摄链路（V1）

- **入口**：人物编辑页 `AvatarHeader` 相机角标（头像本体点击仍开封面选择 Sheet）；设置账号 Hero 卡头像新增相机角标。
- **相机路由化（2026-08-26 二轮）**：相机从主页面 Pager 页 0 移出，改为 NavHost 全屏路由 `Screen.Camera`（MainActivity 注册 destination），**唯一用户入口为头像拍摄**；Agent 指令 `navigate_to(camera)` 链路保留，`NavigationCapability` 对 CAMERA 改为 navigate 相机路由（不登记 avatar pending）。相机会话门控由 Pager `isActivePage` 改为路由生命周期驱动（`backStackEntry.lifecycle.currentStateFlow ≥ RESUMED` 激活，弹栈/退后台即解绑释放，语义等价）；相机权限申请 UI 内聚在 `CameraScreen` 内，路由进入同样触发。
- **会话控制**：`features/common/avatar/AvatarCaptureController`（进程内单例 StateFlow，与 `RemotePhotoTracker` 同风格）登记 `PendingAvatarCapture(target=Person(personId)|Self, origin=PEOPLE_PAGE|GALLERY_PAGE|SETTINGS_PAGE)`；调用方 `begin()` 后 `navigate(Screen.Camera)`。
- **相机头像态**（`CameraScreen/CameraContent`）：检测 pending → 记忆水合后默认切前置（`FEATURE_CAMERA_FRONT` 缺失静默保持后置）→ 顶部胶囊提示（`avatar_capture_hint`：Capture avatar / 拍摄头像 / 拍攝頭像 / Capturar avatar / Capturer l'avatar）；离开相机路由视为取消（`DisposableEffect` onDispose 清 pending + 恢复进入前镜头）。
- **落库设封面**：`handleCaptureClick` 新增 `onPhotoCompleted` 钩子 → `AvatarCaptureFinisher` 以快门时间戳为下界轮询 Room 最新媒体（兜底策略：拍照回调不透出 mediaId 且 `insertMedia` 异步入库，注释已写明取舍）→ 复用 `PersonRepository.updateCover`；Self 目标经 `getSelfPerson()` 解析，未标记「我」则跳过（记日志）。
- **返回**：完成/失败后清 pending 并 `popBackStack` 回来源页（来源页均在返回栈上——Settings 为 NavHost 页，人物编辑为 Pager 内 People/Gallery 页且 pagerState 提升在 Activity 层，弹栈后自然落回；origin 仅作诊断记录，不再驱动返回导航）。

### 11.3 五语 key 清单

| key | EN | zh-CN | zh-TW | ES | FR |
|---|---|---|---|---|---|
| `gallery_settings`（更名） | Gallery Scan | 相册扫描 | 相簿掃描 | Escaneo de galería | Analyse de la galerie |
| `gallery_cleanup`（新增） | Gallery Cleanup | 相册整理 | 相簿整理 | Limpieza de galería | Nettoyage de la galerie |
| `avatar_capture_hint`（新增） | Capture avatar | 拍摄头像 | 拍攝頭像 | Capturar avatar | Capturer l'avatar |

## §12 结果页按类型细分处理（2026-08-26）

结果页批量操作从全局口径改为**当前 Tab 口径**，用户逐类型处理；不再只有全局删除。

- **底部 CTA 跟随 Tab**：只删除当前 Tab 内 batchEligible 组的待删项，文案「删除本类 N 张 · 释放 X」（`dedup_delete_cta_scoped`）；当前 Tab 无可批量删除项（如全是未预选截图组）时 CTA 由提示代替（`dedup_tab_batch_empty`）；Hero 统计区保持全局口径（全部类型合计可释放）。
- **全选 chip 跟随 Tab**：「智能全选」更名「全选本类」（`dedup_smart_select_tab`），只对当前 Tab 的 autoPreselected 组清 override 并重算默认勾选；L3 场景相似 Tab 不展示该 chip。
- **L3 无批量入口（沿用 §4 安全约束）**：SCENE Tab 底部 CTA 由提示文案代替（`dedup_scene_batch_hint`：场景相似需逐组确认 · 不参与批量删除）。
- **口径收口**：`DedupViewModel.tabBatchUris(state)` = `batchDeleteUris(groups.filter level == selectedTab)`，底部 CTA 计数、`deleteSelected()` 及其 IPC 窗口一致性复查共用；授权 IPC 窗口内切 Tab 会因选择集不一致安全放弃该次授权。
- **删除后继续整理**：`DedupUiState.Cleaned` 增加 `remainingGroups`（本次未涉及的组：其他 Tab + 未预选组）。非空时完成页主操作为「继续整理 · 还剩 N 组」（`dedup_continue_remaining`，`continueWithRemaining()` → Results 剩余组并切到还有组的第一个 Tab），「完成」降为次操作；为空时维持原完成页布局。
- **跨级重叠组防过度清空**：构建 `remainingGroups` 时逐组剔除已入回收站的成员——keepUri 被删的组按当前 policy 重算保留项（override 随改选对象消失而失效），存活成员不足 2 的组直接移出；杜绝快照回灌后把某组最后一张也送进回收站。
- **已知限制**：「继续整理」回 Results 后，完成页的「全部撤销」入口随之关闭，本批已删项只能去系统回收站恢复（30 天内）。
- **五语 key 变更**：删 `dedup_smart_select_all`、`dedup_delete_cta`；增 `dedup_smart_select_tab`、`dedup_delete_cta_scoped`、`dedup_scene_batch_hint`、`dedup_continue_remaining`。

### 12.1 验收标准（追加）

- AC-8：结果页底部 CTA 与全选 chip 仅作用于当前 Tab；切 Tab 后计数/文案随之变化；SCENE Tab 无批量入口（提示代替）。
- AC-9：按类型删除授权完成后，若还有其他组，完成页可「继续整理」回到结果页且剩余组完整（含其他 Tab 的 userOverride 不丢失）。
