# ios-follow main-nav-memories 批次 gap analysis（2026-09-16）

> 管线：`/ios-follow` 模式 B（功能追齐）。契约 SSOT：`docs/08-UI-SPECS/screens/main-nav.yaml` + `memories.yaml`。
> 提交链：`c76296b7e`（Stage 2 契约）→ `1e5bd5275`（Stage 3 实现）→ `0223b5b23`（Stage 5 交叉审查修复）。
> 模型交叉（铁律 4）：K3 平台层（NavigationBridge/AvatarCapture/MainTabView/shared iosMain）→ GLM review 子 agent 审；
> GLM Memories 领域层（Generator/VM/TagDatabase+Memories/UI/xcstrings）→ K3 主会话亲审。

## 审查结论：PASS（🔴 已清零）

## 🔴 阻塞（1 项，已修复）

**R1. 相机路由化断裂 7 个 UI 测试文件 + 自动化失去进相机通路**（GLM review 提出，`0223b5b23` 修复）
- 修复：`MainNavigationRouter` 新增 `-openCamera` launch arg——启动即弹相机 fullScreenCover，重建自动化直达通路
- 迁移 7 文件：
  - `GallerySpecUITests`：底栏断言 4 项 → 5 项（tab_gallery/tab_organize/tab_chat/tab_person/tab_memories）
  - `CameraSpecUITests`：setUp 带 `-openCamera`，navigateToCamera 去掉 swipeRight
  - `ChatSpecUITests` / `PoLangUITests`：navigateToCamera 改 `-openCamera` 重启直弹
  - `PoLangUITests`：滑页用例改 相册→整理→聊天→右滑回相册（相机不在滑动序列）；人物页跳出用例改 tab_memories/tab_chat 链路
  - `CompletenessDumpUITests`：4 处 tab_camera 点击全部改 `-openCamera` 重启
  - `CameraMnnLiveUITests` / `SlimOffUITests`：`-startPage 0`（语义已从相机变相册）全部改 `-openCamera`
- 附带：`CameraPreviewView.swift` 两处失真注释（-startPage 0 直进相机 / pager 常驻 stop 门控）已更正
- 验证：`xcodebuild build-for-testing`（含 PoLangUITests target）**TEST BUILD SUCCEEDED**

## 🟡 建议（6 项：4 已修 / 1 待真机 / 1 技术债）

| 项 | 内容 | 处置 |
|----|------|------|
| Y1 | cover 叠 cover（PersonInfoView 上再弹相机 cover）未经运行时验证 | ⚠️ 待真机终验（真机离线，见下）；不稳则改从最顶层 presentation context 弹出 |
| Y2 | cover dismiss 无显式 stop、濒死 session 上 restore | ✅ 已修：`.onDisappear { controller.stop() }`，restore 仅完成路径（session 存活时）做 |
| Y3 | 相机记忆回归：每次 cover 打开重置镜头/变焦/比例/网格（Android 有 CameraMemoryState 水合） | 📋 技术债：v1 不做（移植成本高）；WB 模式/色温已有 @AppStorage/UserDefaults 记忆 |
| Y4 | 头像拍摄失败滞留头像态（Android 失败也 finish） | ✅ 已修：`CaptureFlow.onFailure` 汇于 setError；`handleAvatarCaptureFailureIfNeeded` = clear + dismiss |
| Y5 | 完成回调缺 pending 同一性复检 | ✅ 已修：`guard avatarCapture.pending == pending`（对齐 Android `pending === pendingCapture`） |
| Y6 | 同视图三 fullScreenCover 无互斥 | ✅ 已修：navigate_to handler 开一 cover 前复位其余（camera/settings/editingImage） |

## 🔵 可选（登记不做，转台账/技术债）

- **B1** `MemoriesView.onAppear` 即触发 MemoriesGenerator 扫库——与 Android 全页常驻组合语义一致，不按页门控
- **B2** AvatarCaptureFinisher 单次 PHFetch 不轮询（Android 为 10×200ms Room 轮询）——PHFetch 精确反查语义等价；`performChanges` 完成后资产立即可查性未实测，随 Y1 真机终验一并观察
- **B3** 相机 cover 打开不切 Scene.CAMERA——iOS 现无相机域能力，无实际影响；后续移植相机 Agent/语音时需补
- **B4** main-nav §0 `hide_bar_when` 页内态门控（相册详情/整理三态）未实现，仅页级隐藏（!=聊天）——既有行为非本批回归，登记 gap 台账
- **B5** `person_avatar_capture` 锚点 e2e 用例——依赖人脸聚类数据（CI/模拟器恒 skip），登记技术债

## GLM Memories 领域层 K3 亲审结论（要点）

MemoriesGenerator / MemoriesViewModel / TagDatabase+Memories 全绿，仅两个 🔵 可接受不修：
- 30 天最近窗口开闭界与 Android 差 1ms（`>=` vs `>`），无用户可感知影响
- 同分平局 tie-break 比 Android 多 uri 稳定项（排序更确定，不破坏契约）

## 验收结果三栏

### ✅ 自动通过
- `JITPACK=true ./gradlew :shared:jvmTest` 绿（Stage 4 已验，本轮无 shared 变更）
- `:shared:assembleSharedDebugXCFramework` 重建成功（IosNavigationBridge/IosNavigationCapability/新页序契约入 framework）
- `xcodebuild build` + `build-for-testing`（含 UITest target）均 SUCCEEDED
- 交叉审查 🔴 清零；i18n 五语 27 键全覆盖（0 删 0 改，纯重排序已脚本核实）
- pbxproj 新文件全注册；token 镜像未手改（本批无新 token）

### ⚠️ 待真机终验（真机「郭帅的iPhone」离线 → 全链路降级）
- Y1 cover 叠 cover：人物信息页 → 相机角标 → 头像拍摄全链路（含 Finisher 设封面 + avatarCoverUpdated 刷新）
- 相机 dismiss 落回来源页手感；头像态前置切换/提示胶囊观感
- 截图比对浅色+深色双跑（SSIM ≥ 0.80）；回忆页/底栏五项像素级对齐
- XCTest（MemoriesGeneratorTests 12 例）+ UITest 全量跑批（MNN.framework arm64-only，本机 Intel 宿主模拟器链接不过，只能真机）
- 回忆页生成观感（照片质量分/人物聚类数据依赖真机库）
- 【清零轮新增】设置 Hero 卡角标只弹相机 cover 不跳账号页；拍照后 hero 头像刷新
- 【清零轮新增】相机记忆水合体感（重开 cover 镜头/变焦/比例/网格恢复；头像拍摄结束恢复记录朝向）
- 【清零轮新增】回忆分享文件 URL 导出（大集合内存表现 + 临时文件清理）
- 【清零轮新增】UITest 用例5 testPersonAvatarCaptureEntry 真机跑批（人脸聚类数据依赖真机库）
- 【清零轮新增】navigate_to(model_center) 真机链路（cover 打开/返回键退出）+ 多选态底 bar 隐藏体感

### 📋 技术债清单（2026-09-17 清零轮全部清偿 ✅，见本分支最新提交「fix(ios): 技术债清零轮」）
1. ~~设置页 Hero 卡头像拍摄入口~~ ✅ 已做：SettingsScreen hero 卡 + 角标入口（settings_avatar_capture → selfTarget）
2. ~~Agent navigate_to 其余目的地~~ ✅ 已做：model_center 落地（router + NavigationBridge + 模型中心 cover）；别名双端 1:1（lowercase + 中文别名）；debug 转为正式平台差异（登记 §4/§5，非债）
3. ~~Y3 相机记忆水合~~ ✅ 已做：镜头朝向/变焦/比例/网格四个 UserDefaults 键水合（camera_lens_front/camera_zoom_preset/camera_ratio/camera_grid，camera.yaml §2.5）
4. ~~B5 person_avatar_capture 锚点 e2e 用例~~ ✅ 已做：person_cell_<id> 锚点 + PoLangUITests testPersonAvatarCaptureEntry（无人脸聚类数据时 XCTSkip）
5. ~~B4 底 bar 页内态隐藏门控~~ ✅ 已做：GalleryGridView onSelectionModeChanged 上报 → MainTabView overlay 门控；搜索网格多选态横滑阻断（审查 🟡 修复）
6. ~~B3 Scene.CAMERA 切换~~ ✅ 已做：IosAgentComposition.onCameraRouteChanged（打开暂存进入前场景→CAMERA，关闭恢复暂存 + 页映射兜底）
7. ~~回忆分享大集合内存风险~~ ✅ 已做：MemoryDetailView 分享改 MemorySharePayload 文件 URL 导出（PHAssetResourceManager 临时文件，sheet 关闭清理）

> 清零轮另消化交叉审查 1🔴/3🟡/2🔵：model_center cover 补 NavigationStack（🔴）、人物页相机 dismiss 场景滞留修复（🟡）、搜索网格多选横滑阻断（🟡）、navigate_to lowercase+中文别名对齐（🟡）、gallery 分支四 cover 互斥（🔵）、NavigationBridge 注释同步（🔵）。
