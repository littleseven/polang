# PoLang iOS 任务看板

> **定位**：iOS 端工作的**单一任务看板**（What's done / What's next / Won't do）。按代码实况维护，状态以 `iosApp/` 实际 Swift 文件 + git 为准。
>
> **关系**：本文是 *执行视图*（任务 + 状态 + 优先级）；*产品规格* 见 [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md)（逐功能行为契约）；*Phase 路线图* 见 [`../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md`](../superpowers/plans/2026-08-07-polang-kmp-ios-transformation.md)（Phase 划分 SSOT + 变更记录）。三者冲突时：**代码 > 产品参考 > 本文**，但本文反映最新执行进度。
>
> **基线**：Android main v1.0.39 · iOS 截至 Phase 6.x · 最近校准 **2026-09-18**
>
> **图例**：✅ 已落地 · 🔄 部分/进行中 · 📋 规划 · ❌ 缺口 · 🚫 不对齐（平台无等价，不做）
>
> **2026-09-18 校准补记**（08-16 基线后 iosApp 32 commits 关键项，分项状态待下轮全量校准）：iOS 五语本地化补齐 ES/FR（2026-09-04，`b00867139`，EN/zh-CN/zh-TW/ES/FR）· Memory 独立页 iOS（2026-09-06，`cd2e5f102`，分区大卡 feed + MemoryPageTokens 双端 codegen）· OpenAI/Anthropic 供应商与协议分流（2026-08-21，`46ba9a81d`，BYOK 双页面）· chat 空态 v3 + 访客渐进注册引导 · 端侧模型下载完整性校验 · 配色 v2.2.3（2026-09-13 tokens SSOT）iOS 侧同步。

---

## §1 Phase 总览

| Phase | 内容 | 状态 | 出口证据 |
|---|---|---|---|
| 1 | agent-core → Koog 迁移（Android 侧） | ✅ | `:agent-core` 删除（`1cbe9353`），全链路真机回归 |
| 2 | iOS 技术排雷 Spikes（MNN/QuickJS/Metal 宿主） | ✅ | 三 spike 全绿；⏸️ 补验 B「Qwen3-VL-2B 真机」暂缓（触发点：Phase 6.1 Pass3 开工前） |
| 3 | 改名 + 目录重组 | ✅ | `323c3e1a` merge，`assembleDebug` + 安装回归全绿 |
| 4 | shared KMP 模块抽取 | ✅ | `805870e5`→`c1dc78e4`，`:shared` 五 target，107 JVM 用例全绿 |
| 5 | iOS App 骨架 + 相机管线（TestFlight 出口） | ✅ | 相册浏览 + 相机预览/拍照 + 设置骨架 |
| **6** | **iOS 功能对齐与发布准备** | **🔄** | 见 §2 |
| 7 | 演进（持续） | — | — |

**代码规模**：~29000 行 Swift（08-16 核：Camera 3771 / Settings 3175 / Gallery 2586 / Chat 2219 / Editor 1613 / Debug 1064 / Person 992 / TagScan 551 / Platform 11866 / DesignSystem+App+DI+Main 1052）+ 5 个 metal shader（beauty/lut/smoothing/warp/yuv）。

---

## §2 Phase 6 详细（当前主战场）

> **2026-08-16 优先级调整（用户拍板）**：**相册 + 聊天优先追齐**，设置页涉及项随批次一并补齐；**相机线冻结**（2026-08-16 决策：双端相机页 UI 一致性问题收敛后生效——剩余相机 UI 对齐项为**冻结前最后一批相机投入**，收敛后不再投入 parity 打磨；G5 功能深化取消；Android 场景面板移除为冻结前收尾待办）。UI 调整必须走 ui-parity-guard 三同步 + **Ardot 页面预览先行**（`sync-ardot-variables.py` / `export-ardot-snapshot.py`，快照入库 `docs/08-UI-SPECS/screens/refs/ardot/`）。

### 6.1 TAG 3-Pass 流水线 — ✅ 三 Pass 全通并合入 main；🔄 聚类质量待终验 + MetalGuardian/后台扫描待建

> 2026-08-12 更新：分支 `feat/ios-tag-scan-core` 已合入 main（`b78d7081`），Pass2/Pass3/控制页 全部 live。

| 子项 | 状态 | 证据 / 缺口 |
|---|---|---|
| 模型中心（16 模型下载/进度/删除） | ✅ | `ModelCenterView` 真机 6/6 绿 |
| Pass1 基建（编排/对齐/embedding/MobileCLIP/GRDB） | ✅ | `Pass1Pipeline.swift` / `FaceAlignment` / `MobileClipEncoder` / `TagDatabase`（`25414e12` Step1-6） |
| 人脸检测可用（MNN 106pt + MediaPipe 468→106） | ✅ | self-test faceFound=true/106pt；2d106det 预归一化修复 |
| Pass2 聚类（自适应 k-NN 连通分量） | ✅ 已合并 | `Pass2Pipeline.swift`/`FaceClusterer.swift` k-NN 连通分量（`7b674428`），UI 接 `TagScanOrchestrator` |
| Pass3 VLM 打标（Florence-2 默认） | ✅ 已合并 | `Florence2Tagger.swift` ORT 4-session，真机验证 5 图打标成功（`ab95c3b7`）；Qwen3-VL 备选路径仍待补验 B |
| TAG 控制页 + 扫描编排 | ✅ 已合并 | `TagScanScreen`/`TagScanViewModel`/`TagScanOrchestrator` 接 `MainTabView`（`8184b85a`/`9c7bfaba`/`2b06089f`） |
| 人脸聚类质量 | 🔄 降级（平台决策已定，剩终验） | **✅ 2026-08-13 平台决策落地：ONNX embedder 回退已合入 main**（`ed248304`，`Pass1Pipeline.swift:65` 用 `ORTFaceEmbedder`，Glint360K-R100；原分支 `feat/ios-106-to-5-embedding` 已删）——MNN3.5 Apple bug 规避，MNN 2d106det 点序错乱也已弃用，检测统一 native 5pt。native 5pt 对齐 + iOS 专属阈值 0.45（`FaceClusterMaintenance.swift:16`）+ 聚类精修（重分配/拆过并簇）+ 全量扫描归零（`faf4e6df3`+`ac04eed19`）。**剩余：真机全量重扫后聚类质量终验观察**（`scripts/ios_face_sim_diag.py` 可量化）。spec/plan 已随交付清理（git 历史可查） |
| iOS MetalGuardian（替代 OpenClGuardian） | ❌ | 新设计：warmup 超时 + Metal→CPU 降级（含模型卸载重载）+ MTLDevice 丢失 + 黑名单持久化 |
| 后台扫描（ForegroundService → BGTaskScheduler） | ❌ | iOS ~30s 限制 → 进后台即 `pauseForBackground()`；改「充电+锁屏增量」或「手动触发」（双端功能差异） |

### 6.2 Chat 与 AI 指令 — 🔄 基础链路 ✅ + 富交互批次①②已合并（08-15 `015b59495`），剩批次③

✅ 远程 tool_calls 流式对话（`ChatAgentBridge`→`RemoteChatEngine`→`KoogChatAgent`）/ 思考态→首 token→流式光标 / 媒体结果卡（`media_results`）/ 空态示例 / 清空·新建 / iOS 专属 prompt（8 工具裁剪）/ 隐私契约（DTO 无文件路径/GPS/base64）

✅ **富交互批次①②（08-13~08-15 合并 main，功能面 ~20%→~70%）**：流式节奏器（`b0b58dff2` 接 commonMain `StreamingPacingController`，iosMain 工厂）/ Markdown+表格网格+代码块折叠复制（`bbe2b16dc`+`f2381d101`，commonMain `MarkdownSegmenter`）/ CHART 图表卡（`ChartSvgCard`+触发链 `0b2844581`+`a64e4acdb`）/ 媒体反馈 👍👎🔄 + 模型胶囊（`4f32c30ff`）/ 图片消息子系统（`ChatMessage.MessageType` 6/11 在用 + 上图下文 + 编辑回链 + 捏合 1-5x 全屏预览，`ccbbfe80a`→`c40445e30`）/ JS 沙盒 run_gallery_script 12 只读 handler（`27c3d58a4`→`9c9621c30`）+ 产图链修复（`1436640d8`）/ 横滑卡「查看全部」（`55739c258`）/ 工具轮渲染+气泡宽度对齐（`fdec78e41`+`f504c55a7`）

**非阻塞缺口**（归 §3 后续，批次③候选）：JS 沙盒**写操作**（capability.dispatch + 确认弹窗，对照 Android `WriteConfirmationController`）· ~~`success`/`error` 可见反馈~~ ✅（08-16 批次A：DTO 透传 method 名 + ✅/❌ 气泡 + 三语）· 5 种消息类型产生源（userImage/agentImage/command/planPreview/optimizeCandidates）· 停止生成 UI（`cancelCurrent` 已导出无入口，**Android 同缺**）· 语音输入（诚实占位）· AI 优化抽卡（仅 token 预留）· Claude 工程师模式（零实现）· ~~demo 失败文案硬编码中文~~ ✅（08-16 批次A：`chat.chart_failed`/`chat.script_failed` 三语）

### 6.3 设置与账号 — 🔄 主体完成，剩 3 项 + 4 个功能缺口

✅ 设置主页 + 全部二级页（逐像素还原）+ Model Center（BYOK）+ 端侧模型下载中心 + 主题/语言即时生效 + Telegram 通道 + Privacy Manifest

✅ **08-12 后新增**：账号邮箱注册/登录 + quota 外显（`85b686ae3`，`PoLangAuthClient` 对齐 Android ServerAuthSection）/ Hero 卡外显登录态（`c749c2d5f`）/ 远程模型编辑 API Key（`7aa9a24e2`）/ 开发者选项直显 + 诊断日志查看器（llm/tool/js 三份 JSONL 同构 Android Room 三表）+ Log Modules 多选（`c75767953`+`f3023b302`，合并 `4de24abd6`）/ 模型中心自绘返回键（`053d607de`）/ 设置网格 Gallery 卡直开扫描控制台

**剩余**：① 🔴 App Store 2.5.2 合规分析（LLM 生成代码端侧执行；iOS code-interpreter 上线前出三级结论）· ② 隐私政策页（相册用途描述 / 数据收集声明待完善）· ③ server 账号体系（~~邮箱方案~~ ✅ 已落地；Apple Sign In，P3 按需）。**功能缺口**（对齐 gap 见差异清单 §4）：清除访客数据（`PoLangAuthClient` 缺 clearGuestData）· AI 记忆页（空 State+空闭包，无数据源）· 语音控制页（三 chip 全禁用占位）· Backup 入口（Coming Soon）

### 6.4 server 端 iOS 适配 — ✅ 完成

✅ server 纯平台无关（5 适配点零阻塞）· `X-Platform: ios` header（`0b89ee3a`/`553fefba`）· 设备平台字段（`a5ecf2f5`）。Apple Sign In / APNs 按需推进。

### 6.5 验收对齐 — 🔄 部分完成

✅ iOS UI Driver + 截图自动验收（`79350b84`）· spec→UITest gap 清查

**剩余**：① 核心验收 UITests 补全（`media_pager` 标识符 + 相机/gallery 控件 identifier）· ② 结构化日志（llm/tool/js 三层）iOS 落地（未启动）

### 6.6 功能深化对齐 — 🔄 部分启动

| 切片 | 状态 | 备注 |
|---|---|---|
| 人物页 UI | ✅ | 1311 行（`PersonView`/`PersonInfoView`/`PersonViewModel`/`PersonStore`，`02806687`）；**后端未接** shared |
| 相册人脸关键点交互 | 🔄 | 近期 commits（debug 门控 + 缩放跟随） |
| 相机规格 gap（~~快门 80ms 反馈~~ ✅ / 面板互斥·点空白收起 ✅） | 🔄 | 快门三件套 `f050d6ea`；互斥 `f53d23847`+`8cf5177c6`（Phase F2）；美颜默认值细节仍待对齐 |
| 跟手横滑 Pager + 4 页常驻 | ✅ | `TabView(.page)` 替换 ZStack 条件渲染（`e8582301`），跟手物理吸附 + 4 页常驻；悬浮 Tab 双渲染 bug 同修 |
| 自然语言搜索（整链路） | ✅ | `MediaSearchEngine`（635 行）+ `QueryParser`/`SemanticSearchEngine`+MobileCLIP 全 live（`bb1839de`） |
| Chat 富交互（节奏器/富消息/JS 沙盒/反馈/图表） | ✅ 批次①② | 08-15 `015b59495` 合并；JS 写操作+确认弹窗留批次③（见 §6.2） |
| 相机 Figma 6 面板还原 + Arbot 系统相机风格 | ✅ 布局层 | Phase F/F2（`06561f28c` 等，08-14）+ `b6c486d3a`/`80776e0a4`（08-15/16）；**场景面板按产品方案移除**（✅ iOS UI+内核已删+残留清理；Android 侧 SceneSelector/ScenePreset/scene_* strings 待删，反向 gap）；功能深化（录像/十字星接脸/MAKEUP·风格滤镜接线）未动——**2026-08-16 冻结决策后不再投入**（GLSL 资产已 bundle 留档） |
| 相册分组模式 | 🔄 | FACE/PERSON 已实做（`489bf503f`+`7b674428b`）；LANDSCAPE/LOCATION 仍是「待扫描」占位组，待按 labels/city 真分组 |
| **批次A 速赢（2026-08-16，相册+聊天优先级调整后首批）** | ✅ | 长按大图→编辑+触感 · 删除确认收敛仅系统窗 · 相邻页预热激活 · 空相册格言（spec 修正 + **Ardot Gallery/empty 页面预览**+快照入库+导出脚本多页化）· chat success/error 可见反馈 + demo 文案三语；device 构建绿 |
| **批次B 相册功能深化（2026-08-16）** | ✅ | LANDSCAPE 筛选单组（74 词同源）+ LOCATION 城市分组+无位置兜底 · 拖拽批量选择（长按拖/选择态拖·加减模式）· PhotoInfo 补齐 spec 全字段（美学/人脸三行/标签 FlowRow/OCR/位置跳地图）；Ardot Gallery 页补 info/grid 帧（共 3 帧入库）；真机 dev-loop 全过 |

---

## §3 缺口（Phase 6+，需新建）

> 按依赖深度与解锁价值排序。前置依赖标注于「关键替换 / 依赖」列。**可执行任务拆分见 [`../superpowers/plans/2026-08-10-ios-implementation-tasks.md`](../superpowers/plans/2026-08-10-ios-implementation-tasks.md)。**

| # | 功能域 | 缺口 | 关键替换 / 依赖 | 阻塞 |
|---|---|---|---|---|
| G1 | TAG | ~~Pass2 聚类 + Pass3 VLM + 控制页~~ ✅ 已合并（`b78d7081`）；~~聚类 embedding 平台决策~~ ✅ ONNX 已定并合入 main（`ed248304`）；剩 **聚类质量终验** + MetalGuardian + 后台扫描 | MetalGuardian 新设计；FGS→BGTaskScheduler | 聚类质量终验（观察项，不再硬阻塞） |
| ~~G2~~ | ~~搜索~~ | ~~整条 NL 搜索链路~~ → ✅ 已落地 | `MediaSearchEngine`+`QueryParser`+`SemanticSearchEngine`+MobileCLIP 全 live（`bb1839de`） | — |
| G3 | 编辑 | 静态美颜编辑器 / 智能抠图 / 证件照 / AI 一键优化抽卡 | FBO→Metal MSL；`BeautyParams`/`FilterType`/`StyleFilter` 已 commonMain；ONNX Runtime iOS 可用；FUSION 纯数组可移植 | 无 |
| G4 | 人物/记忆 | 关系图谱后端 / 封面美学（NIMA+eDifFIQA）/ 事实记忆 | Room→SQLDelight；NNAPI→CoreML/Metal；`KinshipLexicon`/`PersonQueryResolver` 纯 Kotlin 宜下沉 shared；UI 骨架已有 | G1 Pass2（人脸聚类） |
| ~~G5~~ | 相机 | ❄️ **冻结取消（2026-08-16 决策）**：~~录像（美颜录制）/ 十字星时序 / 风格特效 5 项 / 语音入口~~ 不再投入；相机冻结后仅维护既有功能（引擎试验场 + 内容采集入口） | ~~Metal 美颜录制；Sherpa-ONNX iOS 单独实现；风格 GLSL→MSL~~（方案留档，GLSL 资产已 bundle） | — |
| G6 | 设置 | 账号登录 / quota / WiFi 静默预下载 / 备份恢复 | `PoLangAuthClient` 等价 + `X-Platform: ios` + `UIDocumentPicker` + App Store 2.5.2（JS 下发声明） | 6.3①合规结论 |
| G7 | Chat 补全 | ~~多会话 / JS 画图 / 反馈 / 图片附件~~ ✅ 批次①②已落（`015b59495`）；剩 JS 写操作+确认弹窗 / 语音输入 / 抽卡 / Claude 模式 / 5 种消息类型产生源 | shared 契约就绪 | 6.3①（JS 写操作） |

---

## §4 平台不对齐（🚫 不计划复刻）

| 功能 | 原因 |
|---|---|
| 飞书/Telegram 远程控制（跨应用 a11y RPA） | iOS 无 AccessibilityService 等价，`RemoteControlToolService` click/scroll/input 不可移植 |
| 悬浮聊天气泡 | iOS 无系统悬浮窗（`TYPE_APPLICATION_OVERLAY`）等价 |
| `launch_app` / `open_system_settings` 强能力 | iOS 沙盒限制，`SystemCapability` 能力远弱于 Android |
| HyperOS 后台冻结检测 | iOS 无厂商冻结问题，`BackgroundScanGuard` 无意义 |

---

## §5 已移除（iOS 勿复刻）

端侧文本 LLM（`qwen3_5_2b`，2026-08 移除）· GPUPixel（自研引擎替代）· InsightFace ONNX / NCNN（MediaPipe+MNN 替代）· langchain4j fork（Koog 替代）· `PrivacyGuard.isRemoteAllowed()` 死代码 · shared 侧 `AiAgentMode.LOCAL`（已删；仅 app 层 `UserPreferences.AiAgentMode` 保留 LOCAL 遗留枚举值，iOS 勿复刻）。

---

## §6 漂移记录（文档 vs 代码）

> 本看板与两份源文档保持同步；历史漂移修正登记于此，最新一次见下。

| 日期 | 漂移 | 修正 | 提交 |
|---|---|---|---|
| 2026-08-10 | D1 人物页「0 文件」 | → 1311 行 UI 骨架已落地（后端未接） | `7832df62` |
| 2026-08-10 | D2 6.1 TAG「未启动」 | → Pass1 基建已建（端到端未组装） | `7832df62` |
| 2026-08-10 | D3「44 commits 未 push」 | → 已全推，main 与 origin 同步 | `7832df62` |
| 2026-08-10 | D4 i18n「无 zh-Hant」 | → 三语就绪，key 191→239 | `7832df62` |
| 2026-08-10 | 全面漂移扫描（A/B/C/D/E 共 25 处） | 6 文档修正：产品参考 11 处（场景同步已接入 / TAG Pass1 已移植 / 人物页非占位 / swipe 手势 / i18n §4.2 传播遗漏等）、路线图风险登记 6 项已解风险关闭、parity spec 清单+RTL+gap 引用、2 份 gap-analysis 加快照 banner；详见 [`2026-08-10-ios-kmp-doc-drift-audit.md`](../reviews/2026-08-10-ios-kmp-doc-drift-audit.md) | 见审计报告 §1 |
| 2026-08-10 | **整合审计**：TAG Pass2/3/控制页「未实现/未接线」→ in-flight 分支待合并；相机「下一步」→ 已对齐合并；i18n 分支 ~323 待合并；PARITY_MASTER_PLAN 自诊错误；跟踪策略明确（main 为准 + in-flight） | 本文 §6.1 + §7 + 产品参考/路线图/implementation-tasks/parity/gap 7 文档修正 + **删 19 份冗余/历史文档（git 留史）** + [`IOS_DOC_INDEX.md`](IOS_DOC_INDEX.md) 瘦身为前门 | 见 [`../reviews/2026-08-10-ios-doc-consolidation-audit.md`](../reviews/2026-08-10-ios-doc-consolidation-audit.md) |
| 2026-08-12 | 看板滞后 main：08-10 后大量合并未反映（TAG 3-Pass 合入 `b78d7081`、搜索 `bb1839de`、聊天多会话 `54799952`、首页跟手 Pager `e8582301`、大图页 VLM/OCR `51f85cde`、编辑器 lite `dc021070`） | §6.1 Pass2/3/控制页→✅已合并 + 新增🔴聚类质量阻塞行；§6.2 移除多会话侧栏缺口；§6.6 跟手 Pager + 自然语言搜索→✅；§3 G1/G2 更新（G2 已落地）；gap 文档加 post-08-10 解决批次 banner；i18n 239→417 | 本次提交 |
| 2026-08-16 | 看板滞后 main 4 天（08-13~08-16 ~40 个 iOS 提交未反映：chat 富交互批次①②、开发者选项+诊断日志、账号登录、相机 Figma 面板还原+Arbot 风格、聚类 ONNX 决策）；§6.1 仍写「main 走 MNN」与代码不符（实际 `Pass1Pipeline.swift:65` 用 `ORTFaceEmbedder`）；代码规模 12.8k→29k；i18n 417→544/1011 | §6.1 聚类行改「ONNX 已合入+终验观察」+标题🔴→🔄；§6.2 批次①②✅+缺口重列（写操作/反馈/语音/抽卡）；§6.3 账号/开发者选项✅+4 功能缺口；§6.6 加 3 行+相机规格行更新；§3 G1/G7 更新；基线校准 08-16；gap 文档加 08-16 全量复核批注（3 并行审计）；**场景面板移除经用户确认为产品方案**（非倒退，iOS 残留注释+4 i18n key 已清，Android 侧待移除登记为反向 gap） | 本次提交 |
| 2026-08-16 | **相机线冻结决策**（用户拍板）：双端相机页 UI 一致性收敛后冻结，代码保留；方向聚焦 **Chat 式相册管理/搜索 + 图片后处理智能编辑**；G5 功能深化取消 | §2 优先级注记改冻结语义；§3 G5 标 ❄️ 取消；§6.6 相机行补冻结说明；同步 PRODUCT.md（决策横幅/§2.1/§5.2/§6.1/§6.3/§6.7）、FEATURES.md §4、CLAUDE.md、双端差异清单 §0/§3 | 本次提交 |
| 2026-09-18 | 看板滞后 main（08-16 后 iosApp ~32 个提交未反映：五语、Memory 独立页、供应商协议分流、chat 空态 v3+访客引导、下载完整性校验、配色 v2.2.3 等）；基线版本过期（v1.0.34→v1.0.39） | 头部基线改 v1.0.39 + 校准日期 09-18 + 补记段（见头部）；§2/§3 分项状态待下轮全量校准，暂未改写 | 本次提交 |

---

## §7 维护说明

- **更新时机**：每完成一个 Phase 6 子项 / 缺口功能落地 / 发现新漂移时更新本文 §2 / §3 / §6。
- **状态判定**：以 **origin/main** 为准（稳定/已交付）；未合并分支工作在对应子项标注「🔄 in-flight: `<分支>` 待合并」。代码核验以 `iosApp/PoLang/Features/` 实际 Swift 文件 + git commit 为辅证（非文档自述）。
- **文档入口**：活文档清单见 [`IOS_DOC_INDEX.md`](IOS_DOC_INDEX.md)（历史/冗余文档已于 2026-08-10 删除，git 历史可查）；本轮整合留痕见 [`../reviews/2026-08-10-ios-doc-consolidation-audit.md`](../reviews/2026-08-10-ios-doc-consolidation-audit.md)。
- **新建功能**：落地后从 §3 移至 §2 对应子项（或新增 ✅ 行），并在 §6 登记漂移修正。
- **不收录**：纯 Android 侧变更（除非影响 shared 契约面）。
