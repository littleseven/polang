# 双端体验一致性差异清单（Android ↔ iOS）

> **目的**：逐屏 code 级结构对齐审计，产出按「用户体感影响」排序的差异清单，驱动对齐工作（**相机页高 ROI 项 #1/#3 已于 2026-08-10 对齐合并 main**；下一优先见 §0 策略）。
> **基准**：Android `main` 为 ground truth；契约 SSOT = `docs/08-UI-SPECS/screens/*.yaml`（camera/gallery-grid/chat/settings/model-download-center）。
> **方法**：5 个并行只读 subagent 逐屏比对 Android Compose 实现 vs iOS SwiftUI 实现；高严重度项附双侧 `文件:行` 证据。一致性框架见 [`IOS_ANDROID_UI_PARITY.md`](../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md) §0。
> **日期**：2026-08-10 · 真机 baseline 已采（iPhone 15，`scripts/auto_test_output/ios_20260810_005256/`）。
> **图例**：🔴 需一致层（信息层级/布局/功能默认/文案状态/触发时机/无障碍） · 🟢 允许平台原生差异 · 体感：高=每次用都察觉/核心流；中=细心用户察觉/边缘；低=几乎不察觉。

> **🔄 2026-08-12 更新（post-snapshot 解决批次）**：本审计定格 08-10。之后以下项已合并 main（对应行内联标 ✅）：
> - 主页跟手 Pager + 4 页常驻 + 悬浮 Tab 双渲染 bug → ✅（`e8582301`）— 关闭主-1/主-2/主-5
> - 相册 NL 搜索 + TAG 扫描 → ✅（`bb1839de` / `b78d7081`）— 关闭相-1/相-2
> - 相册大图页图像理解(VLM) / 提取文字(OCR) → ✅（`51f85cde`）— 关闭相-7
> - 图片编辑器 lite（CROP/ADJUST/FILTER/MARKUP）→ ✅（`dc021070`）— 相-8「编辑」部分
> - 聊天多会话侧栏 + 「新建会话」丢历史 bug → ✅（`54799952`）— 关闭聊-3/聊-6；流式文本经 `onText` 逐字吐已 live（聊-1 部分）
>
> **仍未解决**：相-3/4 分组模式 · 相-5 长按编辑触感 · 相-6 视频播放 · ~~相-8 证件照~~（✅ 08-16 `feat/ios-idphoto` 全量对齐） · 聊-1 节奏器精细化 · 聊-4/5 富消息类型 · §3 录像/十字星接脸/MAKEUP/风格滤镜 · §4 账号/AI记忆/ASR。**🔴 人脸聚类质量阻塞**（MNN3.5 Apple bug）见 [`IOS_TASK_STATUS.md`](../01-PRODUCT/IOS_TASK_STATUS.md) §6.1。
>
> **🔄 2026-08-16 更新（全量代码复核，3 并行审计 vs main `412e8f288`）**：08-13~08-16 三大批次合并 main——**Chat 富交互批次①②**（`015b59495`：流式节奏器 `b0b58dff2` / Markdown·表格·代码块 `bbe2b16dc`+`f2381d101` / CHART 图表卡 / 媒体反馈+模型胶囊 `4f32c30ff` / 图片消息子系统+编辑回链+全屏预览 `ccbbfe80a`→`c40445e30` / JS 沙盒 12 只读 handler `9c9621c30`）；**设置**（账号邮箱登录+quota+登出+删号 `85b686ae3` / 开发者选项+诊断日志 `c75767953`+`f3023b302`）；**相机**（Figma 6 面板还原 Phase F/F2 + Arbot 系统相机风格 `b6c486d3a`+`80776e0a4`，布局层）。相-3/4 分组 FACE/PERSON 已实做。人脸聚类 embedder 已回退 ONNX 合入 main（`ed248304`）规避 MNN3.5 bug，阈值 0.45 + 调优归零（`ac04eed19`）。
> ✅ **场景面板移除为产品方案**（2026-08-16 用户确认，非倒退）：iOS 已随 `80776e0a4` 移除 UI+内核，残留 stale 注释与 4 个 i18n key（Night/Moon/Scene Off/Moon Shot）已清；**Android 侧 `SceneSelector` 面板 + `ScenePreset` 内核 + `scene_*` strings 仍在，待按方案移除（反向 gap，机-10 翻转）**。
> **仍未解决（08-16 复核后清单）**：§3 录像 · 十字星接脸 · MAKEUP/风格滤镜接线 · 语音/AI Chat FAB · 变焦条条件显隐 · 相-6 视频播放 · ~~相-8 证件照~~（✅ 08-16 `/ios-follow idphoto` 全量对齐，chat 入口暂缺） · 相-4 拖拽多选 · 相-5 长按编辑触感 · LANDSCAPE/LOCATION 分组 · 设-2 AI 记忆 · 设-3 语音控制 · 设-5 相机美颜设置分叉 · JS 沙盒写操作 · success/error 可见反馈 · 语音输入 · 抽卡/Claude 模式 · i18n 544/1011≈54%。

---

## §0 跨屏总排序（按体感影响，含可执行性）

| # | 差异 | 屏 | 体感 | 代价 | 备注 |
|---|---|---|---|---|---|
| **1** | **快门三件套错位**（尺寸 62≠76pt / 闪屏白≠黑 / 0.9·250ms≠0.6·80ms / 无 haptic·音效） | 相机 | 高 | **极低** | `DesignTokens.swift` 已定义正确值却是**死代码**——启用 token 即修一半。**最高 ROI** |
| **2** | **跟手 Pager + 4 页常驻**（iOS 松手跳变 + 页面重建 vs Android 跟手物理吸附全常驻） | 主页 | 高 | 中 | 签名交互，每次横滑+进出相机/聊天都察觉 |
| **3** | **相机右列 4/6 按钮 no-op**（比例/网格/场景/ProMode 点了没反应） | 相机 | 高 | 中 | 系统性踩空；先做"面板+最小功能"消除死按钮 |
| **4** | **相册智能功能整块未接通**（搜索/扫描/VLM/OCR/编辑/证件照/4 分组模式 全灰置或「敬请期待」） | 相册 | 高 | **高** | 智能相册核心差异化；属 Phase 6 大功能建，非纯对齐 |
| **5** | **大图查看器三缺**（长按进编辑+触感 / 视频播放 / 拖拽多选） | 相册 | 高 | 中 | 高频操作缺失 |
| **6** | **相机模式选择器纯装饰**（VIDEO/DOCUMENT 选中只改样式，快门恒走静态拍照；录像全缺） | 相机 | 高 | 中高 | 欺骗性 UI；VIDEO 接录像是较大工程 |
| **7** | **设置账号子系统全缺**（登录/quota/登出/删除账号/清除访客） | 设置 | 高 | 中 | 影响配额与账户流 |
| **8** | **人脸十字星触发语义错位**（iOS 联动点击对焦 vs Android 联动人脸检测追踪） | 相机 | 中 | 中 | 名不副实；需接人脸检测点驱动 |
| **9** | **MAKEUP tab 空壳 + 5 风格滤镜占位** | 相机 | 中 | 中 | 唇彩/腮红/TOON 等 Phase 6 |
| **10** | **AI 记忆页非功能骨架**（列表永不填充、CRUD 空闭包） | 设置 | 中 | 中 | 接 SharedKit MemoryManager/GRDB |
| 11 | 相册 PhotoInfo 字段断层（A ~12 类 vs I 5 个基础字段） | 相册 | 中 | 中 | 智能相册信息密度 |
| 12 | **聊天流式节奏器缺失**（一次性甩全文 vs Android 50ms/字逐字吐） | 聊天 | 高 | 中 | 聊天最大体感差；复刻 StreamingPacingController |
| 13 | **聊天两个 bug**：`default:break` 丢弃 text_reply/success/error + 「新建会话」=「清空」**销毁历史** | 聊天 | 高 | **极低** | 补 handleUiAction 分支 + 改 newSession 不销毁 |
| 14 | 聊天多会话侧边栏 + 9/11 消息类型 + Markdown/表格/代码渲染 | 聊天 | 高 | 高 | 大功能建，需先重构 ChatMessage 模型 |
| 15 | i18n key 覆盖 ~~239~~ ~~417~~ 544/1011≈54%（三语内部全配齐无缺漏） | 全局 | 中 | 中（机械） | 持续补 |
| 16 | 相册页悬浮 Tab 双重渲染（iOS 内部缺陷，材质/阴影加倍） | 主页 | 低 | 极低 | 删冗余 overlay |
| 17 | 美颜角标绿≠accent / 面板高 38%≠35% / 滤镜面板 53%≠50% | 相机 | 低 | 极低 | token 已有正确值 |
| 18 | 相机左列 返回/Reset no-op | 相机 | 中（返回断链） | 低 | 接线 dismiss/重置 |

> **策略**：~~相机是下一步重点~~ ✅ **相机高 ROI 项 #1（快门 token+黑闪+反馈）/ #3（右列 4 面板）已于 2026-08-10 对齐合并 main**（`f050d6ea`/`262bf406`/`0267b62f`/`19ae5942`/`04b912fa`/`e965445e`）。剩 #8（十字星接人脸）→ #9（makeup/滤镜）→ #6（录像，大工程留后）属 **G5 功能深化**（见 `IOS_TASK_STATUS.md` §6.6）。~~聊天的两个 bug（#13）~~ ✅ 已修（08-12 批次）。~~#12 流式节奏器~~ ✅（`b0b58dff2`）。#14 聊天富消息 🔄 6/11 在用（08-15 批次①②）；#7 设置账号 🔄 大部分落地（`85b686ae3`，缺清除访客）；#10 AI 记忆仍非功能骨架。**08-16 后下一步建议**（~~相机深化优先~~ → **2026-08-16 用户拍板优先级调整**）：**相册 + 聊天优先追齐**（速赢 → 功能深化 → 大工程分批），设置页涉及项随批次一并补齐；**相机线冻结**（2026-08-16 决策：双端相机页 UI 一致性问题收敛后生效——剩余相机 UI 对齐项为**冻结前最后一批相机投入**，收敛后不再投入 parity 打磨；G5 功能深化取消；Android 场景面板移除为冻结前收尾，见 §3 分类口径）。**所有 UI 调整必须走 ui-parity-guard 三同步闭环**（spec → token codegen → 双端实现），且**先在 Ardot 画布创建页面预览**（`sync-ardot-variables.py` 推 token / `export-ardot-snapshot.py` 快照入库 `docs/08-UI-SPECS/screens/refs/ardot/`），防漂移。

---

## §1 主页（4 页 Pager + 悬浮 Tab）

骨架（页顺序/初始页=相册/4 Tab 语义/设置入口）**已对齐**。差异集中在横滑体感与状态保留。

> ✅ **2026-08-12 解决**：主-1 跟手 Pager + 主-2 4 页常驻 + 主-5 悬浮 Tab 双渲染 bug 全部修复（`e8582301`，`TabView(.page)` 替换 ZStack 条件渲染）。剩主-3 局部手势禁用 / 主-4 人物页多显 Tab / 主-6 打标 Tab 占位 / 主-7 返回语义。
>
> ✅ **2026-08-16 复核**：主-6 打标 Tab → 已进真实 `TagScanScreen`（`MainTabView.swift:56-61`）；主-4 悬浮 Tab「相册/人物页显示」为**有意设计**（`MainTabView.swift:81` 注释：相机沉浸式、聊天避让输入栏而隐藏）——与 Android「仅相册」是登记过的平台差异而非 bug。仍缺：主-3 局部手势禁用上报 · 主-7 相机返回 dismiss（左列按钮随 Arbot 重构移除后仍无接线，见 §3 机-20）。

| # | 层 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 主-1 | 🔴功能/触发 | **横滑**：Android `HorizontalPager` 跟手+物理吸附；iOS `DragGesture.onEnded` 松手跳变，拖拽期无反馈 | A:`MainPagerHost.kt:85-89` / I:`MainTabView.swift:78-88` | 高 |
| 主-2 | 🔴功能 | **页面常驻**：A `beyondViewportPageCount=3` 全常驻；I `if currentPage==X` 条件渲染，相机/聊天/人物离开即销毁（相机 session/聊天草稿重建） | A:`MainPagerHost.kt:87` / I:`MainTabView.swift:22-51` | 高 |
| 主-3 | 🔴触发 | 局部禁用外层横滑：A 相册详情/聊天全屏预览上报禁用；I 全局手势无禁用（阈值有缓解，内层横滑偶发误触切页） | A:`GalleryScreen.kt:250` / I:`MainTabView.swift:78-88` | 中 |
| 主-4 | 信息层级 | 悬浮 Tab 显隐：A 仅相册页；I 相册页**和人物页**都显 | A:`GalleryScreen.kt:840` / I:`MainTabView.swift:67-75` | 中 |
| 主-5 | 布局 | **相册页 Tab 双重渲染**（iOS bug）：两处 overlay 各画一个 FloatingBottomTab（材质/阴影加倍） | I:`MainTabView.swift:24-31` + `:67-75` | 低（极低代价修） |
| 主-6 | 功能 | 打标 Tab：A 进真实打标管理页；I 弹占位「Coming Soon」 | A:`GalleryScreen.kt:860` / I:`FloatingBottomTab.swift:13` | 中 |
| 主-7 | 功能 | 返回语义：A 系统回返回任何状态回相册；I 无系统 Back，相机页返回仅靠手势（发现性弱） | A:`MainPagerHost.kt:75` / I:`MainTabView.swift` | 中 |

**体感总评**：核心结构对齐，**横滑是最大短板**（跟手+常驻 vs 松手跳变+重建），叠加 Tab 双渲染/人物页多显等细节。

---

## §2 相册（网格 + 大图查看器）

网格骨架（3 列等比方块/人脸感知对齐/长按进选择/缩略图勾选/双指缩放/单击切栏/冷启动格言 366 条同源）**已对齐**。差异主要是「智能功能未接通」+「高频交互缺失」。

> ✅ **2026-08-12 解决**：相-1 NL 搜索（`bb1839de`，`MediaSearchEngine` 全链路 live）/ 相-2 TAG 扫描（`b78d7081`，3-Pass 全通）/ 相-7 大图页 VLM 图像理解 + OCR 提取文字（`51f85cde`）/ 相-8 大图底栏「编辑」入口（`dc021070` 编辑器 lite）。**仍缺**：相-3/4 分组模式 · 相-5 长按编辑触感 · 相-6 视频播放 · 相-8 证件照 · 相-9 PhotoInfo 字段断层。
>
> 🔄 **2026-08-16 复核**：相-3 分组模式 **FACE/PERSON 已实做**（`GalleryViewModel.swift:174-210`，hasFace 两分组 + faceId 分组；`489bf503f`+`7b674428b`），**LANDSCAPE/LOCATION 可点但仍为「待扫描」占位组、不筛选**（`GalleryViewModel.swift:211-216`，Android 按 labels/city 真分组）；相-9 PhotoInfo 补 Location 纯文本地名（`MediaPagerView.swift:541-543`，无 lat/lon、不可点跳地图）。
> ✅ **2026-08-16 批次A速赢已落地**：相-5 长按大图→编辑器+medium 触感（视频页无长按）· 相-10 删除确认收敛为仅系统 PHAsset 窗（app 层 confirmationDialog 两处移除+孤儿 key 清理）· 相-13 相邻页预热激活（`preloadAround()` ±2 页 1600²，PHCachingImageManager）· 相-14 空相册格言占位（复用 `SplashPlaceholder`；spec 漂移同步修正+**Ardot 画布 Gallery 页 `gallery/empty` 预览已建并快照入库**）。
> ✅ **2026-08-16 批次B相册功能深化已落地**（真机验证绿）：相-3 尾部 **LANDSCAPE 关键词筛选单组（74 词同源 Android LANDSCAPE_SCENES）+ LOCATION 按城市分组+无位置兜底组**（spec 同步修正：LANDSCAPE 实为筛选非按标签分组；`TagDatabase` 新增 labels/city 查询）· 相-4 **拖拽批量选择**（`69dd8c8d7` 修正版：纯 0.4s 长按进选择 + 选择模式网格层拖拽扫格+方向守卫——首版 sequenced 手势与滚动并行识别致上下滑误触，已回退该路径；取舍：失去「长按后不松手连续拖」）· 相-9 **PhotoInfo 补齐至 spec 全字段**（+来源/美学评分/人脸三行/标签 FlowRow/OCR 段；位置行可点 MKMapItem 跳地图）。**Ardot 画布 Gallery 页补 `gallery/info`+`gallery/grid` 两帧**（快照入库，共 3 帧）。**仍缺**：相-6 视频播放（全 app 无 AVPlayer）· ~~相-8 证件照~~（✅ 08-16 `/ios-follow idphoto`：toast 占位已替换为 fullScreenCover 全流程，spec `docs/08-UI-SPECS/screens/idphoto.yaml`；chat 入口待 chat MediaPager）。

| # | 子区 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 相-1 | 🔴功能 | **搜索**：A 全文检索 live；I 点弹「敬请期待」 | A:`GalleryScreen.kt:566` / I:`GalleryGridView.swift:116` | 高 |
| 相-2 | 🔴功能 | **TAG 扫描**：A toggle live+进度条；I 弹「敬请期待」 | A:`GalleryTopBar.kt:102` / I:`GalleryGridView.swift:112` | 高 |
| 相-3 | 🔴功能 | **4 分组模式**（FACE/PERSON/LANDSCAPE/LOCATION）：A 全启用；I 灰置禁用 | A:`GalleryTopBar.kt:153` / I:`GalleryGridView.swift:148` | 高 |
| 相-4 | 🔴功能 | **拖拽批量选择**：A 有；I 仅逐格点击 | A:`MediaGrid.kt:131` / I:`GalleryGridView.swift:243` | 高 |
| 相-5 | 🔴功能 | **大图长按→编辑器+触感**：A 有；I 仅 onTap | A:`MediaPager.kt:239` / I:`MediaPagerView.swift:273` | 高 |
| 相-6 | 🔴功能 | **视频播放**：A ExoPlayer 内联；I 仅静态图 | A:`MediaPager.kt:1561` / I:`MediaPagerView.swift:292` | 高 |
| 相-7 | 🔴功能 | 大图更多菜单：**图像理解(VLM)/OCR** A live；I 灰置 | A:`MediaPager.kt:926,944` / I:`MediaPagerView.swift:109` | 高 |
| 相-8 | 🔴功能 | 大图底栏：**编辑/证件照** A live；I 灰置 | A:`MediaPager.kt:1033` / I:`MediaPagerView.swift:158` | 高 |
| 相-9 | 信息层级 | **PhotoInfo 字段断层**：A ~12 类（人脸/标签/OCR/美学/重新打标/位置跳地图/预览）；I 仅 5 基础字段 | A:`MediaPager.kt:1104` / I:`MediaPagerView.swift:371` | 中 |
| 相-10 | 功能 | 删除确认：A 仅系统确认；I app 层 confirmationDialog + 系统 = 双重 | A:`GalleryScreen.kt:622` / I:`GalleryGridView.swift:338` | 中 |
| 相-11 | 布局 | 列数：A `Adaptive(110.dp)`（宽屏多列）；I 固定 3 列 | A:`MediaGrid.kt:158` / I:`GalleryGridView.swift:35` | 中 |
| 相-12 | 触发 | 分组头粘性：I `pinnedViews` 吸顶；A 不吸顶 | A:`MediaGrid.kt:166` / I:`GalleryGridView.swift:224` | 中 |
| 相-13 | 功能 | 缩略图预加载：A ±3 页 LRU+磁盘；I 无 | A:`MediaGrid.kt:98` / I:`ThumbnailView.swift:30` | 中 |
| 相-14 | 文案 | 空相册：A 格言占位；I 纯文本 "No media found" | A:`GalleryScreen.kt:766` / I:`GalleryGridView.swift:185` | 中 |

**体感总评**：iOS 相册是 Android 的「骨架级子集」。3 类系统性缺口：①智能功能未接通（搜索/扫描/VLM/OCR/编辑/证件照/分组）②高频交互缺失（拖拽选/长按编辑/视频）③信息密度断层（PhotoInfo）。前 2 类决定「基础可用性」，第 3 类是「智能差异化」。

---

## §3 相机（❄️ 冻结前收口：UI 对齐至 parity 收敛后冻结）

骨架（顶/底/快门/美颜面板布局）**高度对齐**，但功能可用率约 40%。**~~最关键发现：`DesignTokens.swift` 已定义正确 token 却全是死代码~~** ✅ **死代码已启用**（2026-08-10 相机对齐 B1 合并 main，`f050d6ea`：快门 76/58pt、闪屏 0.6/80ms 等 token 已生效）。§3.1 快门/§3.2 右列面板的 gap 已关；§3.3-3.5 录像/十字星/makeup 仍属 G5。

> ❄️ **2026-08-16 冻结决策（用户拍板）**：双端相机页 UI 一致性问题收敛（差异清零或登记为平台差异）后，相机线冻结——代码保留、不新增功能、不再投入 parity 打磨（详见 `PRODUCT.md` 2026-08-16 决策横幅）。**分类口径**：本节剩余项中，**UI 对齐类**（比例预览 ScaleType · WB chips 无消费 · 变焦条条件显隐 · 语音/AI Chat FAB 接线 · token 细节 · 返回 dismiss 接线 · Android 场景面板移除）属**冻结前最后一批相机投入**，做完即冻结；**G5 功能深化类**（机-12/13 录像 · 机-14/15/16 十字星接脸 · 机-17 MAKEUP · 机-18 风格滤镜）**取消，不再投入**。

> 🔄 **2026-08-16 复核（Figma 6 面板还原 Phase F/F2 + Arbot 系统相机风格批次后）**：布局已重构为**顶部 5 项工具栏**（Beauty/Ratio/Grid/Filter/Pro，`CameraPreviewView.swift:402-421`）+ 底部三行，原「右列」形态不存在。快门三件套 ✅ 保持未被破坏（haptic/音效/黑闪 token 均在）。面板功能真实度：网格 ✅（Canvas 真绘三分/黄金虚线）· EV/对比度/饱和度/色温 ✅（`setExposureTargetBias`+shader）· 比例 🔄（仅拍照裁剪生效，预览不切 ScaleType）· WB chips 仅 UI 无消费。
> ✅ **机-10 场景面板：移除为产品方案**（2026-08-16 用户确认）。iOS UI+内核已随 `80776e0a4` 全删（残留注释与 Night/Moon/Scene Off/Moon Shot 四个 i18n key 已清理）；**Android 侧待移除**——`SceneSelector` 仍在渲染（`CameraPreviewContent.kt:541-542`）、`ScenePreset` 内核与 Agent 场景命令通道仍在（`CameraScreenModels.kt:18` 等 5 文件）、`scene_none/night/moon` strings 三语仍在（`strings.xml:271-273`）。
> **仍全部未动**：机-12/13 录像（零实现，token 死代码）· 机-14/15/16 十字星（仍点击对焦触发 1.5s 单态，106 点只喂渲染器不驱动十字星）· 机-17 MAKEUP（「Phase 6」占位；**但 lip/blush/makeup GLSL 资产已 bundle 进 `Assets/shaders/`，无 Swift/Metal 接线**）· 机-18 风格滤镜（lock 占位 onTap 空闭包；style GLSL 资产同上已 bundle）· 机-20 左列返回/Reset（按钮随重构整体移除，返回仍无 dismiss 接线，仅横滑切页）· 机-21 变焦条 4 档硬编码常显 · 机-22 语音/AI Chat FAB 全缺 · DOCUMENT 模式仅枚举+文案壳。

### 3.1 快门（最该先治，代价极低）

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-1 | 外径 **62pt≠76**（token `ShutterTokens.diameter=76` 声明未用） | I:`ShutterButton.swift:17` / 死token:`DesignTokens.swift:251` | 高 |
| 机-2 | 内径 52pt≠58（token 未用） | I:`ShutterButton.swift:22` | 高 |
| 机-3 | 闪屏**白色≠黑色**（Android 黑闪，相机惯例） | A:`CameraScreen.kt:1517` / I:`CameraPreviewView.swift:104` | 高 |
| 机-4 | 闪屏 **0.9/250ms≠0.6/80ms**（token `flashAlpha/flashFadeMs` 未用） | I:`CameraPreviewView.swift:105,108` | 中 |
| 机-5 | haptic：A `LONG_PRESS` 有；I 无 | A:`CameraScreen.kt:1650` | 高 |
| 机-6 | 拍照音：A `CLICK` 有；I 无 | A:`CameraScreen.kt:1654` | 中 |
| 机-7 | 按压缩放：I 有 scaleEffect；A 无（**反向 gap，建议 A 补**） | I:`ShutterButton.swift:23` | 中 |

### 3.2 右列按钮（4/6 no-op，系统性踩空）

| # | 按钮 | 差异 | 证据 |
|---|---|---|---|
| 机-8 | 比例 | A 开 RatioSelector 真 panel（4:3/16:9/FULL 切 ScaleType）；I `{ }` no-op | I:`CameraPreviewView.swift:322` |
| 机-9 | 网格 | A 开 GridSelector + 实绘虚线叠加；I no-op 无叠加 | I:`CameraPreviewView.swift:323` |
| 机-10 | 场景 | A NONE/NIGHT/MOON（NIGHT→EV+1, MOON→EV-2+3.2x）；I no-op | I:`CameraPreviewView.swift:324` |
| 机-11 | ProMode | A 半屏 EV/WB/对比度/饱和度/色温；I no-op 无类型 | I:`CameraPreviewView.swift:328` |

### 3.3 模式选择器 / 录像

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-12 | 模式选择器纯装饰：VIDEO/DOCUMENT 选中只改样式，快门恒走静态拍照 | I:`CameraPreviewView.swift:409` | 高 |
| 机-13 | 录像全缺：无 AVCaptureMovieFileOutput、无录像态（token 声明未用）、无计时 | I: 全缺 | 高 |

### 3.4 人脸十字星（语义错位）

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-14 | **触发源不同**：A 联动人脸检测跟脸移动；I 联动点击对焦跟手指点 | A:`CameraScreen.kt:1418` / I:`CameraGesturesView.swift:69` | 高 |
| 机-15 | 显隐时序：A 多态（220/160/320/420ms）；I 单一 1.5s hold（因不联脸） | I:`CameraGesturesView.swift:70` | 中 |
| 机-16 | 细节：L 角线宽 2pt≠3、十字 alpha 0.6≠0.8 线宽 1.5≠2 | I:`CameraGesturesView.swift:87,123` | 低 |

### 3.5 美颜 / 滤镜 / 其他

| # | 差异 | 证据 | 体感 |
|---|---|---|---|
| 机-17 | **MAKEUP tab 空壳**：唇彩/腮红全缺（占位 "Phase 6"） | I:`BeautyPanelView.swift:153` | 中 |
| 机-18 | **5 风格滤镜占位**（TOON/SKETCH/POSTERIZE/EMBOSS/CROSSHATCH lock） | I:`FilterSelectorView.swift:42` | 中 |
| 机-19 | 美颜角标**绿≠accent**；面板高 38%≠35%；滤镜面板 53%≠50% | I:`CameraPreviewView.swift:319`,`BeautyPanelView.swift:45` | 低 |
| 机-20 | 左列**返回/Reset no-op**（返回键点了没反应，导航断链） | I:`CameraPreviewView.swift:301,307` | 中 |
| 机-21 | 变焦条 4 档硬编码常显（A 按设备能力条件显隐 0.6x/3.2x） | I:`CameraPreviewView.swift:347` | 中 |
| 机-22 | 语音 FAB / AI Chat FAB：A 有（默认隐藏/常显）；I 全缺 | A:`CameraPreviewContent.kt:561` | 中 |

**已对齐项（亮点）**：美颜 FACE 4 项（范围/默认全 0）、色调 9 款 ColorMatrix（矩阵逐字节同源）、变焦条样式、缩略图、翻转、美颜/滤镜面板骨架、十字星视觉参数。

**相机节最该先治（输入下一步计划）**：①快门 token 启用+闪屏黑+反馈（极低代价）→ ②右列 4 面板最小功能 → ③十字星接人脸 → ④MAKEUP/风格滤镜 → ⑤录像（大工程）。

---

## §4 设置（主页 + 二级页）

主页框架（10 项网格 + 主题/语言快选 + Hero 卡布局 + 数据隐私 7 段 + 模型中心 16 模型）**高度一致**。差异在二级页实质功能完成度。

| # | 子区 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 设-1 | 账号 | **账号/认证全缺**（登录/quota/登出/删除/清除访客） | I:`SettingsScreen.swift:239` 占位 | 高 |
| 设-2 | AI记忆 | **非功能骨架**（列表永不填充、CRUD 空闭包） | I:`SettingsSubPages.swift:246` | 高 |
| 设-3 | 语音控制 | 完全缺失（模式+ASR/KWS 模型） | I:`SettingsSubPages.swift:49` "Not Available" | 高 |
| 设-4 | 开发者 | 缺 Shader 调试/LLM 日志/测试工具/日志管理 | I:`SettingsSubPages.swift:294` | 高 |
| 设-5 | 相机美颜设置 | **信息层级根本不同**：A=模型/Stage 配置向；I=美颜诊断/调参向（需产品决策定 SSOT） | A:`SettingsScreen.kt:546` / I:`SettingsSubPages.swift:340` | 高 |
| 设-6 | Hero 卡 | 无登录态反映（永远 "Account" 静态） | I:`SettingsScreen.swift:39` | 中 |
| 设-7 | 远程模型 | 缺编辑能力（只能删重建） | I:`SettingsSubPages.swift:123` | 中 |
| 设-8 | 通信通道 | 无连接状态显示；飞书/TG section 条件显示 vs A 双常驻 | I:`SettingsSubPages.swift:170` | 中 |
| 设-9 | 模型中心 | 缺 RecommendedHeaderCard（WiFi 预下载开关）；其余严格 1:1 | I:`ModelDownloadCenterView.swift:90` | 中 |
| 设-10 | 主页占位 | Gallery 功能 / Backup 两项 "Coming Soon" | I:`SettingsScreen.swift:210,216` | 中 |
| 设-11 | 死代码 | ModelCenterView/AboutView 已实现但不可达（NavigationLink 永不触发） | I:`SettingsScreen.swift:174` | 低 |
| 设-12 | i18n | Android `SettingsRemoteModels.kt` 3 处硬编码中文（违反 i18N 硬规则） | A:`SettingsRemoteModels.kt:43,50,73` | 中 |

**体感总评**：主页框架是双端对齐标杆；二级页两梯队——「完全空白」（账号/AI记忆）与「部分实现关键缺失」（语音/开发者/相机美颜设置分叉）。**模型中心（除 Recommended 卡）是 1:1 对齐的参照基准**。

> 🔄 **2026-08-16 复核**：设-1 账号 🔄 **大部分落地**——邮箱验证码登录/quota 外显+进度条/登出/删除账号 ✅（`85b686ae3`，`PoLangAuthClient` 四方法），**仍缺清除访客**（无 clearGuestData，对照 Android `SettingsServerAuth.kt:364`）；设-4 开发者 🔄 **大部分对齐**——直显（2026-08-15 用户定不做 7 连点，差异已登记）+ 诊断日志查看器（llm/tool/js 三份 JSONL 同构 Android Room 三表）+ Log Modules 多选 ✅（`c75767953`+`f3023b302`），Shader 调试仅存值不消费、测试工具仅 Image Download（Search/JSBridge/Accessibility 灰显 Android only）；设-6 Hero 登录态 ✅（`c749c2d5f`）；设-7 远程模型编辑 ✅（`7aa9a24e2`）；设-9 模型中心补自绘返回键（`053d607de`）；设-10 Gallery 卡已真入口（直开 TagScanScreen）✅、Backup 仍 Coming Soon；设-11 ModelCenterView 已可达 ✅，AboutView 仍死代码 + **新增** `AiAgentSettingsView`/`CameraBeautySettingsView` 两个死代码。
> ✅ **批次C 已落（2026-08-16）**：设-1 尾部**清除访客数据**（`PoLangAuthClient.clearGuestData` DELETE /guest/device + 数据隐私页按钮+toast 三语，`7e8ced4a1`）——设-1 全关。**仍缺**：设-2 AI 记忆（`facts` 空 State + 空闭包，无数据源，GRDB 8 表无 memory_facts）· 设-3 语音控制（三 chip 全禁用占位，无模式切换/ASR 管理）· 设-5 相机美颜设置仍诊断向**且已不可达**（Android 的 Stage/模型配置向无对应）。

---

## §5 聊天

骨架阶段，约 Android 基准端 ~20% 功能面。输入卡 24dp 圆角、thinking 三点（6dp/400ms/160ms stagger）、BlinkCursor（">" 500ms）、空态布局、示例 chip 点击**已对齐**。

> ✅ **2026-08-12 解决**：聊-3「新建会话」丢历史 bug（`newSession()` 改为建新 thread + `switchSession`，不再调 `clearHistory`）/ 聊-6 多会话侧栏（`54799952`，`ChatThreadSidebarView`）。聊-2 流式文本经 `onText` 逐字吐已 live（`handleUiAction` 的 `default:break` 仅分发 UI 动作 kind，不丢流式文本）；`success`/`error` 工具确认反馈仍待补（轻微）。**仍缺**：聊-1 节奏器精细化 · 聊-4/5 富消息类型（图片/图表/表格/代码，需重构 `ChatMessage`）· 聊-7/8 媒体反馈与附件。
>
> 🔄 **2026-08-16 复核（富交互批次①②合并 `015b59495` 后，功能面 ~20%→~70%）**：聊-1 节奏器 ✅（`b0b58dff2` 接 commonMain `StreamingPacingController`，iosMain 工厂）；聊-4/5 富消息 🔄 **6/11 在用**——Markdown 分段+表格网格+代码块折叠复制 ✅（`bbe2b16dc`+`f2381d101`，commonMain `MarkdownSegmenter`）、CHART 图表卡 ✅（ChartSvgCard+触发链）、图片消息 ✅（userImageText 上图下文 + agentEditResult 编辑回链 + 捏合 1-5x 全屏预览），缺 userImage/agentImage/command/planPreview/optimizeCandidates 产生源；聊-7 媒体反馈 👍👎🔄 ✅（`4f32c30ff`）；聊-8 模型胶囊 + 相册选图带图发送 ✅；聊-9 JS 沙盒 12 只读 handler + run_gallery_script 产图链 ✅（`9c9621c30`+`1436640d8`），**写操作/确认弹窗 ❌**；聊-10「查看全部」横滑卡 ✅（`55739c258`）；工具轮渲染+气泡宽度对齐（`fdec78e41`/`f504c55a7`）。
> ✅ **2026-08-16 批次A速赢已落地**：聊-2 success/error 可见反馈（DTO 透传 command method 名 → Swift「✅ 已执行 …」/「❌ …」气泡，三语 key `chat.command_executed`；对齐 Android `ChatViewModel.kt:1409/1419`——iOS 原「静默对齐」注释系误解）· 图表/脚本 demo 失败文案 i18n 三语（`chat.chart_failed`/`chat.script_failed`）。**仍缺**：语音输入（诚实占位 toast）· 停止生成 UI（`cancelCurrent` 已导出无入口，**Android 也没有——双端共同缺**）· AI 优化抽卡（仅 token 预留）· Claude 工程师模式（零实现）。

| # | 子区 | 差异 | 证据 | 体感 |
|---|---|---|---|---|
| 聊-1 | 🔴功能 | **流式节奏器缺失**：A `StreamingPacingController`（50ms/字+标点+100+换行+200+CJK 分块）；I `onText` 直写 snapshot，**一次性甩全文** | A:`StreamingPacingController.kt` / I:`ChatViewModel.swift:52` | 高 |
| 聊-2 | 🔴功能 | **`default:break` 丢弃** text_reply/success/error（DTO 4 kind 仅 media_results 消费，工具执行离散动作永不可见） | I:`ChatViewModel.swift:137` | 高（bug，极低代价） |
| 聊-3 | 🔴功能 | **「新建会话」=「清空」**：I 调 `clearHistory()` 销毁当前会话；A `newSession()` 保留历史 | A:`ChatScreen.kt:839` / I:`ChatView.swift:93` | 高（数据丢失，极低代价） |
| 聊-4 | 🔴信息层级 | **9/11 消息类型缺**（图片/图表/表格/代码/优化候选/编辑结果）；I `ChatMessage` 仅 role，须先重构模型 | A:`ChatScreen.kt:2378` / I:`ChatMessage.swift:8` | 高 |
| 聊-5 | 🔴文案 | Agent 文本无 **Markdown/表格/代码块**渲染（纯 Text） | A:`ChatScreen.kt:1042` / I:`ChatView.swift:249` | 高 |
| 聊-6 | 🔴功能 | **多会话侧边栏全缺**（280dp 抽屉/列表/切换/重命名/删除/自动标题）；Menu 弹「coming soon」 | A:`ChatThreadSidebar.kt` / I:`ChatView.swift:75` | 高 |
| 聊-7 | 🔴功能 | 媒体反馈 👍👎🔄 缺；图片全屏预览/相册结果预览缺；写操作确认弹窗缺 | A:`MediaResultsCarousel.kt:153` / I:`ChatView.swift:355` | 高 |
| 聊-8 | 🔴功能 | 模型胶囊 / 相册选图 / 图片意图芯片 / 待发缩略图 缺 | A:`ChatScreen.kt:1928,1966` / I:`ChatView.swift:164` | 高 |
| 聊-9 | 功能 | 图表/表格全屏预览、JS 沙盒（找相似/画图）、排除约束、访客注册缺 | A:`ChatScreen.kt:657,682` | 中 |
| 聊-10 | 文案 | 媒体标题/日期/查看全部缺；示例文案硬编码英文（违反 i18n）；流式光标位置错位（下方 vs 内联右侧） | I:`ChatView.swift:417,259` | 中 |

**体感总评**：~20% 功能面。**最危险 = 聊-2/聊-3 两个 bug**（结果不可见 + 误操作丢历史，代价极低应速修）；**最大体感差 = 聊-1 流式节奏器**（一次性甩文失去逐字体验）。聊-4~8 为大功能建，需先重构 `ChatMessage` 模型承接 11 种类型。

---

## §6 方法论与下一步

- **契约 SSOT**：`docs/08-UI-SPECS/screens/*.yaml`（camera/gallery-grid/chat/settings/model-download-center）。对齐 = iOS 实现 → yaml 契约（yaml 镜像 Android）。
- **真机验证**：`./scripts/ios-auto-dev-loop.sh --quick --screenshot <name>`（baseline 已采，iPhone 15）。相机视觉类改动用 before/after 截图 + syslog 崩溃检查。
- **下一步**：~~相机页对齐（T7b）~~ ✅ **已完成并合并 main（2026-08-10）**——快门 token 启用+黑闪+反馈（`f050d6ea`）+ 右列 4 面板（比例/网格/场景/ProMode + 面板互斥状态机，`262bf406`/`0267b62f`/`19ae5942`/`04b912fa`/`e965445e`）。§3 剩余 #8 十字星接人脸 / #9 makeup·风格滤镜 / #6 录像属 **G5 功能深化**（见 [`IOS_TASK_STATUS.md`](../01-PRODUCT/IOS_TASK_STATUS.md) §6.6 / `plans/2026-08-10-ios-implementation-tasks.md`（已随交付清理，git 历史可查） T9）。
- **2026-08-16 下一步（优先级调整后）**：~~批次A 速赢~~ ✅ **已落地**（相-5/10/13/14 + 聊-2 + demo 文案 i18n；spec 修正 + Ardot Gallery/empty 预览 + 快照入库 + 导出脚本多页化）。~~批次B 相册功能~~ ✅ **已落地**（LANDSCAPE/LOCATION 分组真实现 · PhotoInfo 全字段 · 拖拽多选；Ardot Gallery 页 3 帧入库；device 构建+真机 dev-loop 全过）。→ **批次C 聊天③+设置涉及项**（JS 写操作+确认弹窗 · 消息类型产生源 · 清除访客/AI 记忆等随批）→ **大工程**（视频播放 · 证件照 · 语音输入 · 抽卡）。相机 G5 暂缓；Android 场景面板移除待办保留。UI 类改动一律先 Ardot 页面预览。
- **不混入本批**：相册/设置的「整块功能缺失」（搜索/编辑/账号/录像等 Phase 6 大功能）单列计划，不在纯对齐批次内。

---

## §7 不算差异（🟢 平台原生 / 已对齐）

字体（Roboto vs SF）、图标族（Material vs SF Symbols）、材质（涟漪 vs 高亮、毛玻璃）、动效曲线、系统返回/权限流形态——均允许平台原生差异。各屏已对齐的骨架项见各节「已对齐」说明。
