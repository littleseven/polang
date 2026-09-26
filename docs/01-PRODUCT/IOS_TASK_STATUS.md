# PoLang iOS 缺口与任务状态

> **校准基线：v1.0.39 (10039) / 2026-09-20 / 事实来源=代码**
> **2026-09-26 增量对账**（docs-only 批次，见 `../reviews/2026-09-26-ios-follow-docs-reconciliation.md`）：登记 Android 09-20 后交付产生的 parity 缺口 #13-16（任务体系 / 意图路由接线 / 上报问题入口 / 顶栏左对齐），iOS 代码未动
>
> **定位**：只列**当前仍存在的真实缺口**与下一步任务，每项标注代码/commit 证据。已完成事项不罗列——验收事实见 `../reviews/`（2026-08-10 ~ 2026-09-16 共 10 篇 ios-follow 批次报告，最新 `2026-09-16-ios-follow-main-nav-memories.md` PASS）；产品现状全貌见 [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md)。
>
> **规模锚点**（2026-09-20 实测）：iosApp 185 文件 / 41808 行 Swift + 5 metal shader；shared iosMain 28 文件 / 2507 行；测试 58 文件 / 8494 行；i18n 767 键 × 五语。

---

## §1 真实缺口（按优先级排序）

| # | 缺口 | 证据 | 备注 |
|---|---|---|---|
| 1 | **端侧 VLM 打标/图像理解 = stub** | `shared/src/iosMain/.../IosUnavailableImageInferenceEngine.kt`（isLoaded=false、loadModel 恒失败、推理返回空串） | **连带**：chat 暂存图 UNDERSTAND / FIND_SIMILAR 意图不可用（`Features/Chat/ChatViewModel.swift:251` 提示文案、`:450` TODO）；修图理解类工具 iOS v1 不注册。解锁需端侧 VLM 选型落地（ORT/CoreML） |
| 2 | **T8 dedup_hash 扫描器未实现** | `Platform/Tag/TagScanOrchestrator.swift` 无 dedup 管线；`Features/Organize/Hub/OrganizeHubView.swift:163` DUPLICATES 卡恒 needsScan；契约 `docs/08-UI-SPECS/screens/organize.yaml:461 ios_duplicates_card_downgrade` | 阻塞整理 DUPLICATES 类目启用（MD5/pHash 回填 + hub 展开卡） |
| 3 | **MetalGuardian 缺失**（Android OpenClGuardian 等价） | `iosApp/PoLang/Platform` 无对应文件 | 需新设计：warmup 超时 + Metal→CPU 降级（含模型卸载重载）+ MTLDevice 丢失 + 黑名单持久化 |
| 4 | **后台扫描缺失** | `TagScanOrchestrator.swift:277 pauseForBackground()` 仅协作暂停；无 BGTaskScheduler 接入 | iOS ~30s 后台限制 → 候选方案「充电+锁屏增量」或维持手动触发（决策待拍板，可能转平台差异） |
| 5 | **Chat JS 沙盒写操作未实现** | `Platform/GalleryScriptHandlers.swift` 注释「12/12 只读 handler 齐备；capability.dispatch 写操作留 Tier 3」 | 需 capability.dispatch + 确认弹窗（对照 Android WriteConfirmationController）；**前置**：App Store 2.5.2 合规三级结论未出 |
| 6 | **编辑器两个降级项** | `Features/Editor/PhotoEditorScreen.swift` 头注：去背景顶栏按钮置灰（「敬请期待」toast）；BEAUTY 滑杆存档但渲染 DEFER | 去背景可直接复用 `Features/IdPhoto/MattingEngine.swift`（FUSION 已验收） |
| 7 | **设置页备份恢复占位** | `Features/Settings/SettingsScreen.swift:537-542`「Coming Soon」（Android 已并入该页） | 备份/恢复通路未建 |
| 8 | **chat refine_template 未接线** | 契约 `docs/08-UI-SPECS/screens/chat.yaml §15`；2026-09-16 批次审查登记 | 随下轮 chat 批次 |
| 9 | **settings_menu_entry 未接线**（设置页「相册整理」一级入口） | 2026-09-16 批次审查登记（`../reviews/2026-09-16-ios-follow-batch-main-sync.md` 技术债） | 小项 |
| 10 | **iOS 版本号未同步** | `iosApp/project.yml:34-35` MARKETING_VERSION 0.1.0 / CURRENT_PROJECT_VERSION 1（Android 已 1.0.39/10039） | 发布前必改 |
| 11 | **聚类质量真机终验**（观察项，非阻塞） | ONNX embedder 已规避 MNN3.5 bug（`Platform/ORTFaceEmbedder.swift`）；全量重扫后聚类质量待真机观察（`scripts/ios_face_sim_diag.py`） | 真机任务 |
| 12 | **iOS 测试跑批受宿主限制** | MNN.framework arm64-only 无 simulator slice → Intel 宿主模拟器链接失败；XCTest/UITest 全量+深浅双跑 SSIM 依赖真机（两篇 2026-09-16 报告「待真机终验」清单） | 流程项：真机在线后补跑 |
| 13 | **chat 任务体系三件套缺失**（工程师任务卡 + 任务中心 + 用户任务协议 M1） | iosApp 全库检索 `EngineerTask`/`TaskCenter`/`UserTask` 零命中（2026-09-26）；Android：任务卡 `aaf01902b`/`23f6b4985`/`53aa0103e`/`cabe4208e`、任务中心页 `5b3ba6728`、用户任务 M1 `8d07adecc`→`8b5bec490`（合并 `9f95ba646`） | spec [`2026-09-26-user-task-protocol-design.md`](../superpowers/specs/2026-09-26-user-task-protocol-design.md) §10 [PARITY] 要求记入 parity 台账、§11 iOS 映射按平台差异裁定（iOS 端侧 VLM 为 stub → TAG 扫描适配器需降级形态）；已拍板（2026-09-26）：全范围上 iOS，含工程师模式；实施排序见 §3-7 |
| 14 | **意图路由器 iOS 消费接线未做** | 契约已在 commonMain（`shared/.../intent/{ChatIntentContract,ChatRoutingPolicy,IntentRouter}.kt`，`9aacc1fa4`）；`iosMain IosChatPrompt.kt` 已同步；iosApp 无 IntentRouter 消费点 | ADR-015 M1/M2 已实施；iOS 仅剩 Swift chat 入口接线（XCFramework 重建后契约即得，零重写） |
| 15 | **设置页「上报问题」入口缺失** | Android `b463b23c5`（自 Chat 顶部栏迁入设置「其他」分组，`POST /v1/report-issue`）；iOS `SettingsScreen.swift` 无对应入口 | 小项；服务端端点已就绪 |
| 16 | **顶栏标题左对齐未跟** | Android `8d0fc581a`（AppTopBar 删 centered 机制）+ `c1eaeb8d5`（回忆页统一左对齐、副标移除）；iOS `Features/Gallery/Components/AppTopBar.swift:27-28` 仍 `.frame(maxWidth: .infinity)` 默认居中（phase4 微信式） | UI 跟随小项；Ardot 设计稿已左对齐终态（2026-09-26） |

## §2 平台不对齐（🚫 确认不做 / 平台本质差异）

| 项 | 结论 |
|---|---|
| 飞书/Telegram 远程控制 RPA、悬浮聊天气泡、launch_app/open_system_settings、HyperOS 后台冻结检测 | iOS 无平台等价，不移植（凭证配置页保留） |
| 删除静默化 | iOS 删除必弹系统确认窗（Apple 强制，无静默通路）——与 Android「删除不再询问」差距为平台本质差异，已台账登记 |
| 语音输入 | **无需求**：输入栏 VoiceButton 2026-09-20 已从设计资产移除（`b9dec58ba`）；设置页语音模型项为持久化占位（`SettingsScreen.swift:707`）。非任务，勿复刻 |
| 相机新功能 | ❄️ 2026-08-16 冻结决策：代码保留不加新功能，仅维护回归基线 |

## §3 下一步任务（建议顺序）

1. **真机终验批**（无代码风险，先清）：§1-11 聚类观察 + §1-12 全量测试/截图 SSIM 双跑 + 两篇 09-16 报告「待真机终验」全清单（cover 叠 cover/相机 dismiss 手感/回忆生成观感/AvatarCapture 链路/model_center 链路）。
2. **端侧 VLM 落地**（§1-1）：选型（ORT GenAI / CoreML）→ 替换 stub → 解锁 chat UNDERSTAND/FIND_SIMILAR 与修图理解工具注册。价值最高、解锁面最大。
3. **T8 dedup_hash 扫描器**（§1-2）→ DUPLICATES 类目启用（扫描器 + hub 展开卡，契约 organize.yaml §8）。
4. **小项批次**（§1-7/8/9/10/15/16）：备份恢复通路、refine_template、settings_menu_entry、版本号同步、上报问题入口、顶栏左对齐——可凑一个发布准备批。
5. **App Store 2.5.2 合规结论**（§1-5 前置）→ 结论为「可行」后排期 JS 写操作 + 确认弹窗。
6. **MetalGuardian / 后台扫描**（§1-3/4）：随 TAG 稳定性批次；后台扫描先拍板方案再动工。
7. **chat 任务体系 + 意图路由接线**（§1-13/14）：已拍板全部上（2026-09-26）。排序：#14 接线先行 → #15/16 小项批 → #13 大项（工程师模式 → 任务中心 → 用户任务 M1 骨架，TAG 适配器降级形态按 platform_differences 台账裁定）。

## §4 文档一致性注记

- `IOS_DOC_INDEX.md` §3 快照已随本轮校准同步更新（2026-09-20）；2026-09-26 增量对账新增 #13-16（iOS 代码未动，快照事实不变）。
- 维护：缺口关闭即从 §1 删除（验收留痕归 `../reviews/`）；发现新漂移在对应行补证据。不收录纯 Android 侧变更。
