# 产品文档去冗余实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-09-27-product-docs-dedupe-design.md`（v2）消除产品文档层四组冗余——文件结构保留，SSOT 归位（NFR=指标数字、PRODUCT §5.1=能力状态、TASK_STATUS=iOS 规模锚点、DOC_INDEX=纯索引），冻结线压状态卡。

**Architecture:** 纯 docs 变更，7+8 个文件。顺序：先建 SSOT 承接方（NFR、IM spec），再改释放方（PRODUCT、FEATURES），再 iOS 三件套，最后跨库引用修复与验证。**单 commit 交付**（spec §5 约定，偏离 skill 默认的多 commit——以 spec 为准）；各 Task 间以 grep/脚本验证代替 commit 检查点。

**Tech Stack:** Markdown 编辑 + `python3 scripts/check_doc_sync.py` + grep 验证。

**关键不变量（执行中随时核对）：**
- FEATURES §4/§5 的**章节标题逐字不变**（`## 4. 智能相机（辅助入口 · ❄️ 冻结待生效）`、`## 5. IM 远程控制（❄️ 冻结 · 低优先实验线）`）——外部锚点依赖（intent-router skill、IM spec §13、capability AGENTS）。
- 锚点 `#5-im-远程控制-冻结--低优先实验线`（GitHub 风格，来自 FEATURES.md:14 TOC 现值）为压缩后引用目标。
- IM spec 已有 AC-IM-11（零云端基础设施）——新增红线从 **AC-IM-12** 起编（spec v2 写的 AC-IM-11/12 作废，以此计划为准）。

---

### Task 1: NFR_SPEC.md 升格数字 SSOT

**Files:**
- Modify: `docs/01-PRODUCT/NFR_SPEC.md`

- [ ] **Step 1.1: §2 性能表追加 9 行迁移指标**

在 §2 表格末行（`| **美颜引擎** | 空帧计数异常波动 | 无告警 | 零告警 | nullFrames | 调试浮层 |` 之后）追加：

```markdown
| **Agent 交互** | Agent 响应（远程 L3/L4 推理） | < 1.5s | < 1s | 日志埋点 | QA checklist | PRODUCT.md §2.2 |
| **Agent 交互** | 交互反馈（UI 响应） | < 100ms | < 50ms | 人工感知测试 | QA checklist | PRODUCT.md §2.2 |
| **Agent 交互** | LLM 首 token 延迟（远程） | < 2s | < 1s | 日志埋点 | CI perf test | PRODUCT.md §2.2 |
| **Agent 交互** | 端到端命令执行（远程） | < 3s | < 2s | 日志埋点 | QA checklist | PRODUCT.md §2.2 |
| **Agent 交互** | ASR 识别到命令执行（远程推理） | < 3s | < 2s | 端到端计时 | QA checklist | FEATURES.md §3.5 |
| **Agent 交互** | 对话数据库查询（1000 条消息） | < 50ms | < 30ms | Room 查询计时 | CI perf test | — |
| **Agent 交互** | 对话历史恢复（重启后） | < 500ms | < 300ms | 端到端计时 | QA checklist | FEATURES.md §3.5 |
| **Agent 交互** | 远程模型切换延迟 | < 200ms | < 100ms | 端到端计时 | QA checklist | FEATURES.md §3.5 |
| **包体** | 应用包体积（含模型） | < 150MB | < 120MB | APK/AAB 产物 | CI | — |
```

- [ ] **Step 1.2: §1 目的之后加口径声明**

在 `## 1. 目的` 章节末尾（`未达标视为阻塞发布项。` 之后）追加：

```markdown

> **口径声明（2026-09-27）**：本文件是 PoLang 工程指标数字的**唯一全量出处**（红线+目标+测量方法+验收工具）。`PRODUCT.md` §2.2 仅保留产品体验红线值并与本表对齐维护；其他文档（FEATURES 等）不再复写数字，一律引用本表。
```

- [ ] **Step 1.3: 清理关联文档列死链**

§2 表「关联文档」列：所有 `PRODUCT.md#2.1` → `PRODUCT.md §5.1`；`PRODUCT.md#3.1` → `PRODUCT.md §2`。执行：

```bash
grep -n "PRODUCT.md#" docs/01-PRODUCT/NFR_SPEC.md   # 逐处核对后替换
```

- [ ] **Step 1.4: 头部版本与 §8 更新历史推进**

头部三行改为：

```markdown
**版本**：1.3  
**状态**：生效中  
**最后更新**：2026-09-27  
```

§8 表末追加：

```markdown
| 1.3 | 2026-09-27 | 升格为工程指标数字 SSOT：吸收 PRODUCT §7.2/§2.2 与 FEATURES §3.5 独有指标共 9 行（Agent 交互组 + 包体）；口径声明；关联文档列死链清理 | 项目开发者 |
```

- [ ] **Step 1.5: 验证**

```bash
grep -c "PRODUCT.md#" docs/01-PRODUCT/NFR_SPEC.md   # 期望 0
python3 scripts/check_doc_sync.py                    # 期望无新增 issue
```

---

### Task 2: IM_REMOTE_CONTROL_TECH_SPEC.md 承接迁移

**Files:**
- Modify: `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`

- [ ] **Step 2.1: §10 验收表追加 AC-IM-12/13**

在 `| AC-IM-11 | **零云端基础设施**：无需部署任何云函数/Relay Server | P0 |` 之后追加：

```markdown
| AC-IM-12 | 图片回传 < 2s（处理完成到 IM 可见） | P1 |
| AC-IM-13 | 非法请求（鉴权失败）拒绝 < 500ms | P0 |
```

- [ ] **Step 2.2: §6 处理限制补队列上限**

定位 §6 中图片处理限制表述（含「超时 60s」处，`grep -n "60s\|20MB" 该文件`），在同节追加一行限制项：

```markdown
- 同时处理队列上限 5 张（防止设备过载）
```

- [ ] **Step 2.3: §13 过时锚点修复**

```
../../docs/01-PRODUCT/FEATURES.md#5-im-远程控制实验性融合入口---p2
```
→
```
../../docs/01-PRODUCT/FEATURES.md#5-im-远程控制-冻结--低优先实验线
```

- [ ] **Step 2.4: 验证**

```bash
grep -n "AC-IM-12\|AC-IM-13\|队列上限" docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md   # 期望 3 处命中
```

---

### Task 3: PRODUCT.md 四处手术

**Files:**
- Modify: `PRODUCT.md`

- [ ] **Step 3.1: §7.2 技术性能表删除换指针**

将 `### 7.2 技术性能` 整节（标题+9 行表格）替换为：

```markdown
### 7.2 技术性能

技术性能指标（红线+目标+测量方法+验收工具）SSOT = `docs/01-PRODUCT/NFR_SPEC.md` §2/§3，本节不复写。产品体验红线见 §2.2。
```

- [ ] **Step 3.2: §2.2 体验红线表替换**

将 §2.2 整节替换为（红线值与 NFR §2 对齐；首字口径统一为红线 <2s/目标 <1s，消除原 <1s 漂移；IM 2 行删除——已由 IM spec AC-IM-9/10 承接）：

```markdown
### 2.2 体验红线

> 量化口径 SSOT = `docs/01-PRODUCT/NFR_SPEC.md` §2；本表仅保留产品承诺的红线值，与 NFR 对齐维护。

| 红线 | 定义 | 验证方式 |
|------|------|----------|
| **Agent 响应** | < 1.5s（远程 L3/L4 推理；端侧文本 LLM 已于 2026-08 移除，本地档不再适用） | 日志埋点 |
| **交互反馈** | < 100ms（UI 响应） | 人工感知测试 |
| **对话首字延迟** | < 2s（远程，目标 < 1s） | 日志埋点 |
| **快门延迟** | < 50ms | 高速摄像 |
| **隐私保护** | 敏感数据优先本地处理，云端推理仅用于非敏感场景且需用户授权 | 权限清单审查 + 网络抓包 |

IM 远程控制（❄️ 冻结实验线）红线 = `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md` §10（AC-IM-9/10/12/13）。
```

- [ ] **Step 3.3: §2.1 剥离状态与代码引用**

将 §2.1 整节（`### 2.1 核心能力` 至 `### 2.2` 前）替换为下文（删尽 ✅ 标记与类名/文件名；保留分层叙事与文档引用；状态与代码证据归 §5.1）：

```markdown
### 2.1 核心能力

> 能力状态与代码证据 SSOT = §5.1「能力×状态」表；本节为产品形态叙事，不承载状态标记与实现类名。

**相册首页层（应用默认入口，唯一首页）**
- 相册网格/时间轴浏览作为打开 App 后的首屏，用户最高频的出发场景
- 底部悬浮导航聚合 相册 / 整理 / 聊天 / 人物 / 回忆五个入口（纯图标无文字标签，与主页面 Pager 页序 1:1），点击瞬时切主页面 Pager 页；打标入口已并入整理页 SCAN tab
- 设置入口统一放在顶部栏最右侧；模型中心入口在相册顶栏与设置主菜单
- 语音 Agent 入口默认隐藏：用户在设置中显式开启后，可从相册/相机通过自然语言调度能力
- 智能分类、搜索、去重、TAG 扫描等相册能力首页可达

**相册+编辑层（核心产品，资源优先投入）**
- 相册浏览：时间轴 + 缩略图，120fps 滑动
- 静态图美颜编辑：复用相机美颜管线，GPU 离屏渲染保证预览/输出一致
- AI 一键优化：抽卡闭环（4 候选 → 端侧美学评分 + 退化守卫选优），先预览后应用
- 精准局部美颜：左眼/右眼/左脸/右脸独立调节
- 对话式编辑：聊天指令调节编辑参数（「磨皮再强一点」），远程 ReAct 执行，结果回渲染至对话
- 智能抠图 / 背景去除：三后端路由（通用抠图 / 人像 / 自拍分割），支持纯背景、换背景
- 证件照制作：一寸/二寸/签证等多规格 + 背景色
- 批量处理：多图批量应用同一套美颜/滤镜参数
- 智能搜索：按日期/地点/内容/人物自然语言搜索相册（ADR-007）

**AI 对话层（二级页，相册内的助手能力）**
- 自然语言多轮对话，作为相册/编辑操作的自然语言加速器
- 远程模型多轮对话与指令路由（tool_calls）；BYOK 自定义供应商（设置页配置，OpenAI 兼容 + Anthropic 原生协议分流，用户自带 Key 直连）
- 对话历史持久化，跨会话查看
- 发送图片进行 AI 分析 / 对话式编辑
- 相册摘要：自然语言生成相册概况
- 标签扫描：端侧打标 3-Pass，可由对话触发
- JS 沙盒脚本：对话内运行相册分析/健康报告脚本
- 对话式反馈：操作确认、错误澄清、建议推荐、结果展示
- 用户问题上报：设置页「其他」分组入口，服务端脱敏后自动创建 GitHub issue
- 为主页面 Pager 页 2，从相册悬浮「聊天」Tab 或全屏横滑进入，顶部栏提供返回相册按钮

**任务层（一等产品概念，2026-09-26 升格）**
- 定位：承载「自然语言委托 → 后台任务执行 → 完成回联」任务范式——相册域长耗时操作与工程师代码任务统一以「任务」呈现（回联三件套规划中，§6.6）
- 任务中心：统一观察与控制面——Chat 顶栏入口（角标合并计数）；「工程师任务 | 后台任务」双 Tab；进行中/历史分区（历史封顶 50 条）；点击任务卡跳转来源场景（FEATURES.md §3.6）
- 用户任务协议（M1）：统一 6 态生命周期 + 声明式动词（暂停/恢复/取消/重试）；身份落库重启可见；启动对账（进程死于任务中 → 可见可重试）；已接入 TAG 扫描 + 模型下载（M2/M3 收编规划见 §6.6）
- 工程师任务卡：对话流任务卡消息——进度/审批/终态三形态，审批唯一入口（FEATURES.md §2.7）
- 语义承诺：出现在任务中心的任务都可控——未接入可控协议的体系不出现
- 差异化：「隐私版 Agent 任务」——任务数据 100% 不出端（ADR-008；对照 Muse 云端 VM）

**人物记忆与关系层**
- 事实记忆：「帮我记住…」显式声明的事实统一收口，来源含聊天工具与 JS 沙盒写通路
- 人物命名 /「我」标记：为人脸聚类命名，全局唯一「我」标记
- 人物页（主页面 Pager 页 3）：封面网格（人脸感知纵向对齐、含人脸不砍头），点封面直接改名/标关系/标「我」；AI 记忆页专注事实记忆
- 人物封面美学选择：美学评分 + 人脸质量分加权自动选最佳封面
- 人物关系图谱：声明亲属关系（配偶/子女/父母等），幂等覆盖、级联删除；关系快照支持备份导出/恢复
- 自然语言人物检索：称谓词表 → 关系图谱 → 人脸簇解析，支撑「我女儿的照片」式查询

**相机能力层（辅助入口 · ❄️ 冻结待生效）**
- 实时美颜、风格滤镜、专业模式（曝光/白平衡/对比度/饱和度/色温）、拍照/录像（GPU 离屏处理，预览/输出一致）
- 2026-08-26 起为全屏路由，唯一用户入口为头像拍摄；Agent 指令链路保留；AI 相机功能降级为 P2
- ❄️ 冻结（2026-08-16 决策）：双端 UI 对齐后生效，代码保留不加新功能（详见 §6.3 与 FEATURES.md §4 状态卡）；冻结后承担实时渲染引擎试验场 + 编辑流内容采集入口；冻结前收尾：Android 场景面板移除

**IM 远程控制层（❄️ 冻结 · 低优先实验线）**
- 通过飞书机器人远程调用相册能力（浏览/编辑/搜索/管理），设备端直连飞书 WebSocket，无云端中转
- ❄️ 冻结（2026-07）：低优先实验线，不占用 P0/P1 资源（详见 §6.7 与 `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`）

**后端服务层（PoLang Server，支撑远程推理与账号）**
- 独立 Ktor 工程（不纳入 Android 构建），部署 api.polang.net
- 定位：配置中心 + LLM 代理 + 分发管道 + 遥测收集，不做 Agent 编排（ReAct 循环在客户端）
- AI 网关：按模型路由上游（DeepSeek/腾讯 TokenHub），内置 per-IP 限流与 max_tokens 校验
- 账号体系：邮箱注册动态 Token + 免费额度管控；管理后台（用量/成本/流量）；推荐引擎纯规则型（规避算法备案）；遥测批量匿名事件
```

- [ ] **Step 3.4: §6.3 / §6.4 / §6.7 冻结线收紧**

**§6.3** 整节（标题保持 `### 6.3 拍照线（Camera）— ❄️ 冻结（2026-08-16 决策，双端 UI 对齐后生效）`）替换为：

```markdown
### 6.3 拍照线（Camera）— ❄️ 冻结（2026-08-16 决策，双端 UI 对齐后生效）

> 相机线冻结：双端相机页 UI 一致性收敛（差异清零或登记为平台差异）后生效；代码保留、不新增功能、不再投入双端 parity 打磨。相机继续承担：**自研引擎实时路径（帧同步/EGL/实时管线）的试验场**、**编辑流的内容采集入口**。已落地能力（实时美颜/零延迟快门/帧同步美妆/专业模式/2026-08-15 工具栏改版/相机状态记忆）作为回归基线，明细见 FEATURES.md §4 状态卡与 `docs/08-UI-SPECS/screens/camera.yaml`。冻结前收尾待办：Android 场景面板按产品方案移除（`SceneSelector`/`ScenePreset`/`scene_*` strings）。原 Phase 2/3 规划取消；美颜参数记忆已提前落地（重置入口「设置 → 相机」）；编辑侧证件照制作属相册编辑线不受影响（§6.5）。
```

**§6.4** 整节（标题保持）替换为：

```markdown
### 6.4 视频线（Video）— ❄️ 冻结（Phase 3+ 按需解冻）

> 视频线冻结（2026-07）：BeautyVideoRecorder / FrameSyncManager 代码保留不删，新功能停止开发，待 Gallery/Editor + Chat 核心稳定后评估。已落地：基础录制链路（UI 触发入口待补）、录制复用预览同一 FrameSyncManager 实例（妆容不甩飞）。规划项（智能运镜/分段录制/视频滤镜/Vlog 模板/导出优化）随冻结挂起，明细见 git 历史（2026-09-27 压缩）。技术依赖链：帧同步美妆 → MediaCodec 录制管线 → GPU 性能优化（1080p@30fps）。
```

**§6.7** 整节（标题保持）替换为：

```markdown
### 6.7 IM 远程控制线（IM Remote Control）— ❄️ 冻结（Phase 3+ 按需解冻）

> IM 远程控制冻结（2026-07 冻结 · 低优先实验线，2026-07-27 曾重新激活为低优先级）：飞书 SDK 集成代码保留不删，新功能停止开发，不占用核心产品研发资源，待核心产品验证后按需重启。能力面（机器人接入/设备绑定/自然语言命令/远程浏览·编辑·管理/多设备/交互卡片/批量处理/审计日志）与体验红线 SSOT = `docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md`；交互规范现状见 FEATURES.md §5 状态卡；演进三阶段（实验启动 → 扩展 → 数据决策点）见该 spec。
```

- [ ] **Step 3.5: 验证**

```bash
grep -n "ChatEditProcessor\|MattingRouter\|IDPhotoComposer\|ExplicitFirstSearchPipeline\|ChatMessageDao\|KinshipLexicon\|coverMediaId" PRODUCT.md   # §2.1 区域期望 0 命中（§5.1/§6.x 允许）
grep -n "❄️ 冻结" PRODUCT.md | head   # 标题口径不变
python3 scripts/check_doc_sync.py
```

---

### Task 4: FEATURES.md 冻结线状态卡 + 指标引用化

**Files:**
- Modify: `docs/01-PRODUCT/FEATURES.md`

- [ ] **Step 4.1: §3.5 数字段迁出（数字已在 Task 1 入 NFR）**

将 §3.5 整节替换为：

```markdown
### 3.5 体验红线

- **量化指标**（响应延迟 / 语音响应 / 模型切换 / 对话恢复）数字与测量口径 SSOT = `NFR_SPEC.md` §2「Agent 交互」组，本节不复写
- **反馈完整**：每个用户输入必须有可见反馈，包括降级提示
- **渐进披露**：复杂能力通过对话引导发现，不一次性罗列
- **降级透明**：网络不可用时明确告知用户（可选）
```

- [ ] **Step 4.2: §4 智能相机压状态卡（标题逐字不变）**

将 §4 整节（81 行）替换为：

```markdown
## 4. 智能相机（辅助入口 · ❄️ 冻结待生效）

> **状态卡（2026-09-27 压缩，原 81 行交互规范见 git 历史）**：本章为冻结线回归基线，不随版本演进。

- **定位**：辅助入口（ADR-005 决定 4 降级）——内容采集 + 自研引擎实时路径（帧同步/EGL/实时管线）试验场；2026-08-26 起为 NavHost 全屏路由，唯一用户入口为头像拍摄，Agent 指令链路保留
- **冻结决策（2026-08-16）**：双端相机页 UI 一致性收敛（差异清零或登记为平台差异）后生效——代码保留、不新增功能、不再投入双端 parity 打磨（原 Phase 2/3 规划取消，见 PRODUCT.md §6.3）
- **保留职责**：实时渲染引擎试验场 + 编辑流内容采集入口
- **冻结前收尾待办**：Android 场景面板按产品方案移除（`SceneSelector`/`ScenePreset`/`scene_*` strings；iOS 已删，手动入口已下线，仅 Agent `switch_scene` 链路可设置夜景/月亮预设）
- **UI 结构**：2026-08-15/16 改版——顶部居中五胶囊（美颜/比例/辅助线/滤镜/专业）+ 顶部内联滑出面板 + 美颜底部抽屉；结构与数值 SSOT = `docs/08-UI-SPECS/screens/camera.yaml`
- **回归基线**：参数范围/白平衡 GL 色温映射（5000/5600/6200/3600/4400K）等数值契约 = `camera.yaml` §19；引擎/帧同步/快门三位一体反馈 = `BEAUTY_ENGINE_TECH_SPEC.md`；**人脸十字星交互语义**：锁定人脸且设备运动时显示、画面稳定后自动淡出、避免长期遮挡构图
```

- [ ] **Step 4.3: §5 IM 压状态卡（标题逐字不变）**

将 §5 整节（114 行，含 §5.1–§5.8）替换为：

```markdown
## 5. IM 远程控制（❄️ 冻结 · 低优先实验线）

> **状态卡（2026-09-27 压缩，原 114 行交互规范见 git 历史）**。

- **定位**：实验性融合入口——通过 IM（飞书）机器人将相册核心能力（浏览/编辑/搜索/管理）暴露为远程服务；设备端直连飞书 WebSocket，无云端中转
- **冻结状态**：2026-07 冻结的低优先实验线（2026-07-27 曾重新激活为低优先级）——代码保留不删、不占用 P0/P1 资源、Phase 3+ 按需解冻（见 PRODUCT.md §6.7）
- **能力清单（一句话级）**：飞书机器人接入 / 设备绑定（配对码）/ IM 自然语言命令（LLM 解析）/ 远程浏览·搜索·编辑·管理 / 多设备 @指定 / 交互式卡片 / 批量处理 / 操作审计日志
- **隐私与安全**：端侧推理优先，图片不经任何第三方服务器中转；全程 HTTPS + 飞书标准鉴权；首次绑定需 App 内配对码确认，支持远程解绑
- **体验红线与处理限制**：全部红线（命令响应 <3s / 图片处理 <5s / 图片回传 <2s / 鉴权失败拒绝 <500ms）与限制（单张 20MB / 队列上限 5 张 / 超时 60s）SSOT = `../03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md` §10（AC-IM-9/10/12/13）与 §6
- **细节指引**：架构 / SDK 集成 / 绑定流程 / Bot 设计 / 意图分类 = 该 spec；路线图 = PRODUCT.md §6.7
```

- [ ] **Step 4.4: §7.2 性能要求表换指针**

将 §7.2 整节替换为：

```markdown
### 7.2 性能要求

性能指标（冷启动/快门延迟/参数跟手/相册滚动/GPU 处理）的数字、红线/目标与测量口径 SSOT = [`NFR_SPEC.md`](NFR_SPEC.md) §2，本节不复写。
```

- [ ] **Step 4.5: 顺手修 :649 编号错误**

`> **状态口径（与 PRODUCT.md §6.6 统一）**：` → `> **状态口径（与 PRODUCT.md §6.7 统一）**：`（该行随 §5 重写已自然消失——若采用整节替换则无需单独处理，此处仅作核验项）。

- [ ] **Step 4.6: 更新维护脚注**

文件尾 `> **最后更新**：2026-09-18（…长注…）` 替换为：

```markdown
> **维护**：项目开发者  
> **最后更新**：2026-09-27（冻结线 §4/§5 压缩为状态卡；§3.5/§7.2 指标数字 SSOT 迁 NFR_SPEC §2；IM 红线与处理限制迁 IM_REMOTE_CONTROL_TECH_SPEC §10/§6；此前 2026-09-18 对齐注记见 git 历史）
```

- [ ] **Step 4.7: 验证**

```bash
grep -n "^## 4\. 智能相机（辅助入口 · ❄️ 冻结待生效）\|^## 5\. IM 远程控制（❄️ 冻结 · 低优先实验线）" docs/01-PRODUCT/FEATURES.md   # 期望两行命中且逐字不变
grep -c "5.6\|5\.7" docs/01-PRODUCT/FEATURES.md   # 期望仅 TOC/历史残留可清理
python3 scripts/check_doc_sync.py
```

---

### Task 5: iOS 三件套去重

**Files:**
- Modify: `docs/01-PRODUCT/IOS_DOC_INDEX.md`（重写）
- Modify: `docs/01-PRODUCT/IOS_TASK_STATUS.md`（§4 注记）
- Modify: `docs/01-PRODUCT/IOS_PRODUCT_REFERENCE.md`（四处小改）

- [ ] **Step 5.1: IOS_DOC_INDEX.md 纯索引化重写**

整文件替换为：

```markdown
# iOS 文档索引（前门）

> **定位**：iOS 端文档的单一入口。2026-08-10 整合后冗余/历史文档已删除（git 历史可恢复），只保留活文档 SSOT。进度以 **origin/main** 为准，未合并分支标「in-flight」。
> **2026-09-27 纯索引化**：规模数字唯一出处 = [`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md) 规模锚点；模块状态唯一出处 = [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md)；本文件不再维护状态快照与删除档案（原 §2/§3 见 git 历史）。

## §1 活文档（现行事实来源，须保持最新）

| 文档 | 职责 |
|---|---|
| [`IOS_TASK_STATUS.md`](IOS_TASK_STATUS.md) | **缺口看板**：真实缺口 + 下一步任务（每项带代码/commit 证据）+ 规模锚点 |
| [`IOS_PRODUCT_REFERENCE.md`](IOS_PRODUCT_REFERENCE.md) | **产品实现参考**：逐模块现状 + 双端能力对照，以 iOS 代码为准 |
| [`../reviews/2026-08-10-ios-android-consistency-gap.md`](../reviews/2026-08-10-ios-android-consistency-gap.md) | **5 屏 code 级差异审计**（最新；相机项已完成） |
| [`../../docs/08-UI-SPECS/PARITY_MASTER_PLAN.md`](../../docs/08-UI-SPECS/PARITY_MASTER_PLAN.md) | **Parity 顶层架构**（五层防线） |
| [`../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md`](../03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md) | **Parity 方法论** |
| [`../../docs/08-UI-SPECS/README.md`](../../docs/08-UI-SPECS/README.md) | **Vibe Coding 流程** |
| [`../../docs/08-UI-SPECS/screens/*.yaml`](../../docs/08-UI-SPECS/screens/) | **逐屏契约**：camera / gallery-grid / chat / settings / model-download-center |
```

（删除原 §2 删除档案、§3 状态快照、`/ios-follow` 待审批 spec 行——该 spec 已交付为 `.claude/commands/ios-follow.md`，git 历史可查。）

- [ ] **Step 5.2: IOS_TASK_STATUS.md §4 注记改写**

§4 第一条 bullet 替换为：

```markdown
- `IOS_DOC_INDEX.md` 已纯索引化（2026-09-27）：原 §2 删除档案与 §3 状态快照撤销——删除清单查 git 历史，状态事实 = 本文件规模锚点 + `IOS_PRODUCT_REFERENCE.md` 对照表。
```

- [ ] **Step 5.3: IOS_PRODUCT_REFERENCE.md 四处小改**

1. **删 §1.5 整节**（「文档前门一致性注记」——引用已删的 DOC_INDEX §3，断链）。
2. **删 §1.4 整节**（版本号漂移注记——与 TASK_STATUS §1 #10 双写）。§1 保留 1.1–1.3。
3. **头部「数字口径」句与 §1.1/§1.3 规模数字改引用**：头部 bullet `> **数字口径**：行数均为 2026-09-20 实测…单独统计：58 文件 / 8494 行…` 替换为：

```markdown
> **数字口径**：总量规模数字（主 target / iosMain / 测试）唯一出处 = `IOS_TASK_STATUS.md` 规模锚点（2026-09-20 实测）；本文件 §1.1/§1.2 保留**模块级拆分**（仅本文件有）。
```

   §1.1 首句 `iOS 主 target（iosApp/PoLang）：**185 个 Swift 文件 / 41808 行**；另有 5 个 Metal shader…` → `iOS 主 target（`iosApp/PoLang`）总量见规模锚点（185 文件 / 41808 行 / 5 Metal shader，2026-09-20）；模块拆分如下：`；§1.3 首句 `**28 个 Kotlin 文件 / 2507 行**` → `28 个 Kotlin 文件 / 2507 行（2026-09-20，总量归规模锚点）`。
4. **§3 各模块「登记缺口」行压缩为编号引用**。先读 §3.1–§3.5（约 94–140 行）核对缺口行，然后按映射改写（示例，实际按所在模块对应）：

```markdown
- **登记缺口**：去背景顶栏按钮置灰（「敬请期待」toast，MattingEngine 已在 IdPhoto 存在但未接进编辑器）；BEAUTY 滑杆可调+参数存档，渲染 DEFER。
```
→
```markdown
- **登记缺口**：#6（见 `IOS_TASK_STATUS.md` §1，含代码证据）。
```

   已知映射：编辑器 §3.7→#6；TAG §3.9→#1/#2/#3/#4；设置 §3.11→#5/#7（+2.5.2 为 #5 前置）；Chat/相册/整理等节按 TASK_STATUS §1 对号入座；无对应编号的保留原句。

- [ ] **Step 5.4: 验证**

```bash
grep -n "IOS_DOC_INDEX.md §3\|IOS_DOC_INDEX.md §2" docs/01-PRODUCT/*.md docs/superpowers/README.md   # 期望 0（README 已在 Task 6 处理亦可）
grep -c "185 文件\|185 个" docs/01-PRODUCT/IOS_PRODUCT_REFERENCE.md docs/01-PRODUCT/IOS_TASK_STATUS.md docs/01-PRODUCT/IOS_DOC_INDEX.md   # 数字只应出现在 REFERENCE 模块拆分注记与 TASK_STATUS 锚点
```

---

### Task 6: 跨库引用修复（7 处）

**Files:**
- Modify: `androidApp/src/main/java/com/mamba/picme/domain/agent/capability/AGENTS.md:7`
- Modify: `docs/superpowers/README.md:38`
- Modify: `docs/00-INDEX.md:25`
- Modify: `skills/i18n-validator/SKILL.md` + `.claude/commands/i18n-validator.md:102`（镜像同改）
- Modify: `androidApp/src/main/java/com/mamba/picme/core/designsystem/AGENTS.md:125-126`
- Modify: `skills/doc-sync-guardian/SKILL.md:96` + `.claude/commands/doc-sync-guardian.md:79`（镜像同改）

- [ ] **Step 6.1: capability AGENTS.md 锚点**

`docs/01-PRODUCT/FEATURES.md#5-im-远程控制融合入口` → `docs/01-PRODUCT/FEATURES.md#5-im-远程控制-冻结--低优先实验线`

- [ ] **Step 6.2: superpowers README 去 §2 指针**

`不留悬空链接（与 \`docs/01-PRODUCT/IOS_DOC_INDEX.md\` §2 同款约定）。` → `不留悬空链接（已随交付清理的文档查 git 历史：\`git log --all -- <path>\`）。`

- [ ] **Step 6.3: 00-INDEX 描述去「状态快照」**

`iOS 侧文档索引（缺口看板 / 产品参考 / 状态快照）` → `iOS 侧文档索引（缺口看板 / 产品参考）`

- [ ] **Step 6.4: i18n-validator 小节号修正（SSOT+镜像两处）**

先 `grep -n "Section 4.1.1" skills/i18n-validator/SKILL.md .claude/commands/i18n-validator.md`，命中处 `（Section 4.1.1）` → `（§7.1 术语对照）`。

- [ ] **Step 6.5: designsystem AGENTS.md 小节号修正**

`docs/01-PRODUCT/FEATURES.md Section 3: HyperOS 视觉风格` → `docs/01-PRODUCT/FEATURES.md §6.1: HyperOS 风格`；`docs/01-PRODUCT/FEATURES.md Section 3.1: 色彩系统` → `docs/01-PRODUCT/FEATURES.md §6.2: 色彩系统`。

- [ ] **Step 6.6: doc-sync-guardian 示例句更换（SSOT+镜像两处）**

表格行 `| 交互流程描述 | \`docs/01-PRODUCT/FEATURES.md\` | "点击快门触发三位一体反馈" |` → `| 交互流程描述 | \`docs/01-PRODUCT/FEATURES.md\` | "聊天指令调节编辑参数（对话式编辑）" |`

- [ ] **Step 6.7: 验证**

```bash
grep -rn "远程控制融合入口\|远程控制实验性融合入口\|Section 4.1.1\|三位一体反馈" --include="*.md" androidApp skills .claude/commands docs/00-INDEX.md docs/superpowers/README.md docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md   # 期望 0 命中（docs/reviews 历史快照除外）
./scripts/check-skill-sync.sh   # 期望无漂移
```

---

### Task 7: 终验 + 单 commit 交付

**Files:**
- Delete: `docs/superpowers/specs/2026-09-27-product-docs-dedupe-design.md`（交付即清理）
- Delete: `docs/superpowers/plans/2026-09-27-product-docs-dedupe.md`（本计划，随交付清理）
- Commit: 全部改动

- [ ] **Step 7.1: 门禁脚本**

```bash
python3 scripts/check_doc_sync.py        # 期望通过/无新增 issue
./scripts/check-skill-sync.sh            # 期望无漂移
```

- [ ] **Step 7.2: 悬空引用终扫（排除历史快照与在途目录）**

```bash
grep -rn "PRODUCT.md §7.2\|FEATURES.md §5.6\|IOS_DOC_INDEX.md §2\|IOS_DOC_INDEX.md §3\|Section 4.1.1" --include="*.md" . 2>/dev/null | grep -v "\.git/\|docs-site/\|docs/reviews/\|docs/superpowers/\|build/"
# 期望 0 行
```

- [ ] **Step 7.3: 指标单点核验**

```bash
# 冷启动：目标值定义仅 NFR 一处
grep -rn "500ms" PRODUCT.md docs/01-PRODUCT/FEATURES.md docs/01-PRODUCT/NFR_SPEC.md | grep -v "NFR_SPEC"
# 快门 50ms / 参数 100ms / 帧同步 16px 同理抽查；首字/首 token：PRODUCT §2.2 仅红线 <2s，<1.5s 变体应清零
grep -rn "1\.5s" docs/01-PRODUCT/FEATURES.md   # 期望 0（§3.5 已迁）
```

- [ ] **Step 7.4: 行数对账**

```bash
wc -l PRODUCT.md docs/01-PRODUCT/FEATURES.md docs/01-PRODUCT/NFR_SPEC.md docs/01-PRODUCT/IOS_*.md
# 预期量级：PRODUCT ~470 / FEATURES ~690 / NFR ~150 / DOC_INDEX ~22 / REFERENCE ~255 / TASK_STATUS 55
```

- [ ] **Step 7.5: 单 commit（含 spec/plan 交付清理）**

```bash
git add PRODUCT.md docs/01-PRODUCT/ docs/03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md \
  androidApp/src/main/java/com/mamba/picme/domain/agent/capability/AGENTS.md \
  androidApp/src/main/java/com/mamba/picme/core/designsystem/AGENTS.md \
  docs/superpowers/README.md docs/00-INDEX.md skills/i18n-validator/SKILL.md \
  skills/doc-sync-guardian/SKILL.md .claude/commands/i18n-validator.md .claude/commands/doc-sync-guardian.md \
  docs/superpowers/specs/2026-09-27-product-docs-dedupe-design.md docs/superpowers/plans/2026-09-27-product-docs-dedupe.md
git commit -m "docs: 产品文档去冗余——SSOT 归位（NFR=指标/PRODUCT §5.1=状态/TASK_STATUS=iOS 规模锚点）+ 冻结线压状态卡

- PRODUCT: §7.2 删表指向 NFR；§2.2 红线对齐 NFR（首字口径统一 <2s 红线）；§2.1 剥离状态与代码引用；§6.3/6.4/6.7 冻结线收紧
- FEATURES: §4/§5 压状态卡（标题锚点不变）；§3.5/§7.2 指标迁 NFR；IM 红线/队列上限迁 IM spec（AC-IM-12/13）
- NFR_SPEC: 升格工程指标数字 SSOT（吸收 9 行，v1.3）
- iOS: DOC_INDEX 纯索引化；REFERENCE 规模数字改引用+删 §1.4/§1.5；TASK_STATUS §4 注记
- 引用修复 7 处（capability/designsystem AGENTS、IM spec §13、superpowers README、00-INDEX、i18n-validator/doc-sync skill+镜像）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

注意：工作区已有 10 个 ardot 截图改动（`docs/08-UI-SPECS/screens/refs/ardot/*.png`）**不属于本次交付，不得混入 commit**——`git add` 仅按上述显式路径。

---

## Self-Review 记录

- **Spec 覆盖**：spec §3.1→Task 3；§3.2→Task 4；§3.3→Task 1；§3.4→Task 5；§3.5→Task 2；§4→Task 2/6；§5→Task 7。全覆盖。
- **占位符**：Step 2.2/5.3-4/6.4 为「grep 定位 + 规则 + 示例」型步骤（目标行内容已核读过或属机械变换），非 TBD。
- **一致性**：AC 编号修正为 12/13（覆盖 spec v2 的 11/12 笔误）；§4/§5 标题逐字不变的约束在 Task 4 与「关键不变量」双重声明；两处 skill 修改均 SSOT+镜像成对。
