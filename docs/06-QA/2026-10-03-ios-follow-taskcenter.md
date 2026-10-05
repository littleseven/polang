# ios-follow/taskcenter 验收 gap analysis（2026-10-03）

> 过程验收文档（docs/06-QA，随线清理）。管线：ios-follow 模式 B（功能追齐）。
> 交叉审查：K3 写的（shared 上提 + iOS 数据层）→ GLM review（agent-18）；GLM 写的（SwiftUI UI + i18n）→ K3 review（agent-19）。

## 范围裁定回顾

工程师任务数据链（claude-tunnel SSE→Reducer→持久化）iOS 零存在，本次口径 = **任务中心页完整（后台 Tab 全功能）+ 工程师 Tab 空态/历史态 + 任务卡渲染管线就绪**；数据链缺席登记 platform_differences 台账（task-center.yaml §7），非缺陷。

## 审查发现与处置

| 级别 | 问题 | 处置 |
|------|------|------|
| 🔴 | TaskCenterView `#if DEBUG` previewSeed 被无条件引用，Release 编译断裂（另暴露 3 处 `#Preview` 引 DEBUG-only 工厂同类断裂） | ✅ 已修（4 文件），Release 构建绿 |
| 🟡 | IosUserTaskStore persistAll 锁外执行，落盘序可倒挂 | ✅ 已修（移入 mutex） |
| 🟡 | persist 失败吞掉导致 registry 节流缓存阻断落盘重试 | ✅ 已修（抛 UserTaskPersistException 走 registry 降级不缓存路径） |
| 🟡 | 模型下载放弃启动对账（但 @Published 首帧回放 = 等价对账数据源），残留 RUNNING 行滞留 | ✅ 已实装对账（首帧比对，消失者置 FAILED/PROCESS_TERMINATED）；TAG 扫描仍有意降级（PassthroughSubject 无回放），台账已更新 |
| 🟡 | progressText `String(format:)` 未固定 locale（fr/es 输出「1,5 GB」与 Android Locale.US 不一致） | ✅ 已修 en_US_POSIX |
| 🟡 | isActive 手抄副本 ×3（两适配器 + TaskCenterStore） | ✅ 已改调 `UserTaskMapping.shared.isActive` SSOT |
| 🟡 | reloadEngineerTasks 主线程同步扫全会话 JSON（含迁移写盘副作用） | ✅ 已下沉 Task.detached（ChatHistoryStore 读路径 6 成员 nonisolated 化，使能改动已注释安全依据） |
| 🔵 ×8 | ForEach 键不稳、safeArea 背景、44pt 热区、死代码等 | 📋 登记未改（不阻塞） |

## 验收结果

| 项 | 结果 |
|----|------|
| `shared:jvmTest` | ✅（含新迁移 37 用例） |
| `shared:assemble`（含纯度守卫 + iOS 三 target metadata） | ✅ |
| `androidApp:assembleDebug` + usertask/TaskCenter/EngineerTask 单测 49 例 | ✅（Android 行为零变化） |
| XCFramework 重建 + iOS Debug 真机编译 | ✅ |
| iOS **Release** 编译（CODE_SIGNING_ALLOWED=NO） | ✅ |
| 真机安装/启动/截图非黑屏/syslog 无崩溃 | ✅（ios-auto-dev-loop 2026-10-03 16:21 全部通过） |
| Android↔iOS SSIM 像素 diff | ⚠️ 未执行（无 Android 设备在线采集参考帧） |
| PoLangTests 真机 | ⚠️ `MediaPipe468AdapterTests.testFrontCameraLeftSideBecomesRightSide` 失败——**本分支零触碰 MediaPipe/相机/该测试文件（git diff 实证），main 既有失败**，建议另行立案 |

## 已知偏差（spec 台账在册）

1. 工程师 Tab iOS 常态空态（无数据链）——platform_differences 登记
2. US-15 回锚降级：整卡点击 = 切 chat 页 + 切会话，无逐卡锚定滚动
3. 入口图标字形代位：SF `list.clipboard` 代 `mat_o_assignment`（平台材质项免检）；UserTaskCard 用 mat_o_autorenew/mat_download
4. UserTaskCard 动作禁用态 alpha 0.5 未实现（iOS 无 isProcessing/actionInFlight 状态源）
5. 9 个 i18n key 已入库暂无 iOS 消费点（审批动作预留，双端键对齐）
6. TAG 扫描重启对账降级（残留活动行由下次真实事件覆盖，不置 FAILED）

## 结论

🔴 清零，**PASS（自动验收口径）**。真机手感/观感/工程师卡 HTML 渲染观感（无真实数据，仅 Preview 验证）留用户终验。
