# PoLang 文档导航索引

> 本索引是 PoLang 技术文档的总览。左侧边栏可按章节浏览，顶部支持全文搜索。

---

## 文档地图

| 层级 | 路径 | 说明 |
|------|------|------|
| **交互规范** | [`01-PRODUCT/FEATURES.md`](./01-PRODUCT/FEATURES.md) | 用户交互流程与体验规则 |
| **使用指南** | [`01-PRODUCT/SETUP_GUIDE.md`](./01-PRODUCT/SETUP_GUIDE.md) | 新用户使用前提与首次设置（官网/Play 复用 SSOT） |
| **性能红线** | [`01-PRODUCT/NFR_SPEC.md`](./01-PRODUCT/NFR_SPEC.md) | 性能/稳定性/隐私量化指标 |
| **Agent 架构** | [`02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](./02-ARCHITECTURE/AGENT_ARCHITECTURE.md) | Agent 运行时架构、本地/远程推理 |
| **模块架构** | [`02-ARCHITECTURE/MODULE_ARCHITECTURE.md`](./02-ARCHITECTURE/MODULE_ARCHITECTURE.md) | Gradle 模块划分与依赖关系 |
| **C4 系统模型** | [`02-ARCHITECTURE/c4/polang-system.summary.md`](./02-ARCHITECTURE/c4/polang-system.summary.md) | 代码反向建模的当前架构（Structurizr DSL + 证据索引；L1/L2/L3 三视图信息图渲染，2026-09-27） |
| **架构决策** | [`02-ARCHITECTURE/ADR/README.md`](./02-ARCHITECTURE/ADR/README.md) | ADR 索引（现役 11 篇：001/002/003/005/007/008/011/012/013/014/015；历史篇 004/006/009/010 已于 2026-08-23 清理） |
| **能力注册** | [`04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md`](./04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md) | Capability 列表、命令映射、实现指南 |
| **命令参考** | [`04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md`](./04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md) | 命令语法与示例 |
| **开发规范** | [`05-DEVELOPMENT/DEVELOPMENT.md`](./05-DEVELOPMENT/DEVELOPMENT.md) | 双螺旋工作流、代码审查、CI 规则 |
| **本地环境** | [`05-DEVELOPMENT/LOCAL_ENVIRONMENT.md`](./05-DEVELOPMENT/LOCAL_ENVIRONMENT.md) | 本机开发路径与工具上下文 |
| **Play 发布自动化** | [`05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md`](./05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md) | Google Play 构建发布流水线与商店素材 |
| **发布包备份恢复** | `05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md`（内部，不上线） | 发布产物备份与恢复流程 |
| **双端 UI 契约** | `08-UI-SPECS/PARITY_MASTER_PLAN.md`（内部，不上线） | 双端一致性总纲（五层防线）+ `screens/*.yaml` 逐屏规格 |
| **iOS 文档前门** | [`01-PRODUCT/IOS_DOC_INDEX.md`](./01-PRODUCT/IOS_DOC_INDEX.md) | iOS 侧文档索引（缺口看板 / 产品参考 / 状态快照） |
| **性能基线** | [`06-QA/PERFORMANCE_BASELINE_REPORT.md`](./06-QA/PERFORMANCE_BASELINE_REPORT.md) | 历史性能 trace 报告合集 |
| **坐标系标准** | [`07-STANDARDS/COORDINATE_SYSTEM.md`](./07-STANDARDS/COORDINATE_SYSTEM.md) | 图像/人脸坐标系与命名规范 |
| **术语词典** | [`07-STANDARDS/GLOSSARY.md`](./07-STANDARDS/GLOSSARY.md) | 统一术语定义 |

---

## 技术规范速查

| 文档 | 主题 |
|------|------|
| [`AI_OPTIMIZATION.md`](./03-TECHNICAL-SPECS/AI_OPTIMIZATION.md) | AI 一键图片优化方案与参数标准 |
| [`TAG_GENERATION.md`](./03-TECHNICAL-SPECS/TAG_GENERATION.md) | 相册自动 TAG 生成（3-Pass Pipeline） |
| [`GALLERY_SEARCH.md`](./03-TECHNICAL-SPECS/GALLERY_SEARCH.md) | 相册自然语言搜索完整链路 |
| [`ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md`](./03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md) | 端侧推理引擎与模型全景梳理（无文本 LLM，保留 VLM/人脸/ASR） |
| [`MNN_LLM_OPERATIONS.md`](./03-TECHNICAL-SPECS/MNN_LLM_OPERATIONS.md) | 端侧 VLM 打标引擎运维与资源管理 |
| [`MNN_LANDMARK_DIAGNOSIS.md`](./03-TECHNICAL-SPECS/MNN_LANDMARK_DIAGNOSIS.md) | MNN Landmark 检测路径诊断与修复方法论 |
| [`ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md`](./03-TECHNICAL-SPECS/ONDEVICE_IMAGE_UNDERSTANDING_MODELS.md) | 端侧图片理解模型调研 |
| [`VOICE_STACK.md`](./03-TECHNICAL-SPECS/VOICE_STACK.md) | 语音栈：唤醒词、KWS 与 ASR |
| [`BEAUTY_ENGINE_TECH_SPEC.md`](./03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) | 大美丽引擎技术规格（帧同步美妆、容灾降级） |
| [`FACE_DETECTION_ENGINE_ARCHITECTURE.md`](./03-TECHNICAL-SPECS/FACE_DETECTION_ENGINE_ARCHITECTURE.md) | 人脸检测引擎架构 |
| [`FACE_LANDMARKS.md`](./03-TECHNICAL-SPECS/FACE_LANDMARKS.md) | MediaPipe 468 / 火山 106 点参考与映射 |
| [`IM_REMOTE_CONTROL_TECH_SPEC.md`](./03-TECHNICAL-SPECS/IM_REMOTE_CONTROL_TECH_SPEC.md) | IM（飞书）远程控制（实验性） |
| [`JS_ENGINE_TECH_SPEC.md`](./03-TECHNICAL-SPECS/JS_ENGINE_TECH_SPEC.md) | JS 沙盒引擎（QuickJS + JSBridge，对话内运行相册分析脚本） |
| [`DESIGN_TOKENS_SPEC.md`](./03-TECHNICAL-SPECS/DESIGN_TOKENS_SPEC.md) | Design Token SSOT（codegen 双端镜像 + CI 门禁 + Ardot 预览层） |
| [`IOS_ANDROID_UI_PARITY.md`](./03-TECHNICAL-SPECS/IOS_ANDROID_UI_PARITY.md) | 双端 UI 对齐方法论（度量体系 / 系统栏 / 无障碍 / 深色 / 动效 / 验证闭环） |
| [`AI_IMAGE_EDITING_CAPABILITY_GAP.md`](./03-TECHNICAL-SPECS/AI_IMAGE_EDITING_CAPABILITY_GAP.md) | AI 修图能力缺口分析（人脸修复等待实施项上游） |
| [`SMART_OPTIMIZE_VLM_DESIGN.md`](./03-TECHNICAL-SPECS/SMART_OPTIMIZE_VLM_DESIGN.md) | 智能优化 VLM 设计（抽卡/美学打分） |
| `OVERSEAS_SERVER_DEPLOYMENT.md`（内部，不上线） | 海外服务器部署（Ktor 网关运维） |
| [`SERVER_IMPLEMENTATION_PLAN.md`](./03-TECHNICAL-SPECS/SERVER_IMPLEMENTATION_PLAN.md) | 自研后端实现计划（账户/管理台） |

---

## 文档地图：spec / plan / ADR 集中管理（2026-08-23 起）

> 按**生命周期**分四类，各有唯一的家；由 `scripts/check_doc_sync.py` 检查 4（散逸门禁）机器强制——`*spec*/*plan*/*design*/*adr*` 命名的 git 追踪文档出现在批准目录之外即 FAIL。

| 生命周期 | 唯一位置 | 放什么 | 清理规则 |
|---|---|---|---|
| **决策（活）** | [`02-ARCHITECTURE/ADR/`](./02-ARCHITECTURE/ADR/README.md)（索引） | 架构决策记录（why） | 失效即删，编号不复用 |
| **规范（活）** | [`03-TECHNICAL-SPECS/`](./03-TECHNICAL-SPECS/) + 产品规格于 [`01-PRODUCT/`](./01-PRODUCT/) | 技术/产品规范 SSOT（how） | 随代码原子更新 |
| **双端 UI 契约（活）** | `docs/08-UI-SPECS/`（内部，不上线） | `PARITY_MASTER_PLAN` + `screens/*.yaml` 逐屏规格 | 随三同步（spec+双端代码+token）更新 |
| **在途工作文档** | `docs/superpowers/{specs,plans}/`（内部，不上线） | AI 协作设计/执行计划 | **交付即清理**，git 历史即归档 |
| **时间点快照** | `docs/reviews/` | 审计/验收/复盘报告 | 只增不改 |

> 仓库根与模块目录**不放** spec/plan/design 命名文档（原根 `specs/` 已于 2026-08-23 迁入 `docs/08-UI-SPECS/`）。例外登记于 `scripts/check_doc_sync.py` 的 `DOC_GATE_WHITELIST`。

---

## 快速导航

- **新人入门** → [FEATURES](./01-PRODUCT/FEATURES.md) → [AGENT_ARCHITECTURE](./02-ARCHITECTURE/AGENT_ARCHITECTURE.md) → [COMMAND_REFERENCE](./04-AGENT-CAPABILITIES/COMMAND_REFERENCE.md)
- **实现新功能** → [FEATURES](./01-PRODUCT/FEATURES.md) → [CAPABILITY_REGISTRY](./04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md)
- **排查性能问题** → [NFR_SPEC](./01-PRODUCT/NFR_SPEC.md) → [ON_DEVICE_INFERENCE_INVENTORY](./03-TECHNICAL-SPECS/ON_DEVICE_INFERENCE_INVENTORY_TECH_SPEC.md) → [BEAUTY_ENGINE_TECH_SPEC](./03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md)
- **执行 QA / 看性能基线** → [PERFORMANCE_BASELINE_REPORT](./06-QA/PERFORMANCE_BASELINE_REPORT.md) → [BEAUTY_ENGINE_TECH_SPEC](./03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md)
