# polang Claude Code Commands 索引

> Claude Code 命令索引。所有命令定义在 `.claude/commands/*.md`，对话中通过 `/command-name` 调用。历史命令曾从 `.qoder/skills/` 迁移而来，当前以 `.claude/commands/` 为唯一事实来源。

## 可用 Commands（共 33 个）

### 🔧 开发与构建
| Command | 说明 |
|---------|------|
| `/android-build-debug` | Android 编译、安装、日志调试标准化流程 |
| `/error-healer` | Kotlin/Gradle 编译错误自动分类与修复策略 |
| `/dev-loop` | 一键编译→安装→设备验证→质量检查闭环 |
| `/i18n-validator` | 多语言同步验证（中/英/繁），禁止硬编码字符串 |
| `/android-tag-data-backup-restore` | 卸载重装（换签名键）前后备份恢复应用数据（TAG/人脸嵌入/聚类等高价值数据） |

### 📱 设备控制与调试
| Command | 说明 |
|---------|------|
| `/adb-bot` | adb 设备调试与应用生命周期管理（TEST_COMMAND 广播已移除，应用内自动化走 `/ui-driver`） |
| `/image-quality-checker` | 截屏质量分析（黑屏/亮度），注意：自动化脚本尚未实现 |

### 🧪 测试与质量
| Command | 说明 |
|---------|------|
| `/ui-driver` | PoLang UI 自动化（Accessibility 结构化文本驱动） |

> 2026-07-28 决策4：`/agent-test`、`/qa-acceptance` 及配套脚本/用例已下线清理，仅保留 `/ui-driver`。

### 🎨 渲染与图形
| Command | 说明 |
|---------|------|
| `/av-gl-expert` | OpenGL/CameraX 诊断（黑屏/Shader/EGL） |
| `/egl-state-machine` | EGL 上下文状态机管理 |
| `/coordinate-system-standard` | 人脸关键点坐标/渲染管线/UI 标注规范 |

### 🤖 AI/推理引擎
| Command | 说明 |
|---------|------|
| `/mnn-integration` | MNN 推理引擎接入（模型加载/JNI/LLM） |
| `/mnn-llm-android` | MNN-LLM 端侧大模型推理（Qwen/下载/调试） |
| `/mnn-landmark-diagnosis` | MNN 人脸关键点检测对齐诊断 |
| `/onnx-model-integration` | ONNX 模型接入专家 |
| `/mnn-modelscope-uploader` | 预训练模型转 MNN 格式并发布到 ModelScope 仓库供 Android 下载 |

### 🎯 UI/交互
| Command | 说明 |
|---------|------|
| `/compose-ui-expert` | Jetpack Compose UI（布局/状态/重组/HyperOS） |
| `/ui-parity-guard` | 双端 UI 一致性守卫（spec → token → 截图闭环硬规则） |
| `/layout-inspector-expert` | Layout Inspector 调试 Compose UI 问题 |
| `/mediapipe-landmark-mapping` | MediaPipe 468/106 点人脸关键点映射 |
| `/ardot-design-ops` | Ardot 设计运维（快照导出/token 同步/语言台账等 MCP 操作规程） |

### 📋 流程与治理
| Command | 说明 |
|---------|------|
| `/doc-sync-guardian` | 三层文档体系一致性维护 |
| `/intent-router` | 意图路由：自然语言需求→技术任务 |
| `/perf-optimizer` | 性能优化（内存泄漏/卡顿/帧率） |
| `/ios-follow` | Android 完成后 iOS 一键对等跟随（六阶段编排管线，spec/token/平台差异台账契约化） |
| `/rd-reflection` | RD 自我进化系统（复盘/经验/检查清单） |

### 🍎 iOS 开发与渲染
| Command | 说明 |
|---------|------|
| `/ios-build-debug` | iOS 编译、模拟器/真机安装、日志调试（xcodebuild/simctl） |
| `/ios-dev-loop` | iOS 一键闭环验证（编译→安装→截屏→基线对比） |
| `/swiftui-expert` | SwiftUI 布局/状态/重组/Preview，双端视觉对标 |
| `/metal-render-expert` | Metal/MSL 渲染诊断（黑屏/shader）+ GLSL→MSL 翻译 |
| `/mnn-ios-integration` | MNN.framework iOS 构建/embed + 人脸检测推理 |
| `/kmp-ios-interop` | Kotlin/Native↔Swift 互操作（signal 6/Flow/XCFramework） |
| `/ios-i18n-validator` | iOS 五语（xcstrings）同步 + 双端键对齐 |

> Phase 5 iOS 应用骨架配套 skill（对标 Android 侧 av-gl-expert / compose-ui-expert / mnn-integration 等，106pt 坐标体系双端同源）；skills 均已落地，设计稿已随交付清理。

---

## 使用方式

在 Claude Code 对话中输入 `/command-name` 即可加载对应 skill 的完整上下文。

例如：
- `/adb-bot` — 获取 adb 自动化控制能力
- `/error-healer` — 获取编译错误自动修复策略
- `/av-gl-expert` — 获取 OpenGL 诊断能力

---

> 命令源文件：`.claude/commands/*.md`
> 历史源文件：`.qoder/skills/*/SKILL.md`（已迁出，`.qoder/` 已删除）
> Canonical skills 源（SSOT）：`skills/`（经 `.kimi/skills` 软链供 kimi/OpenCode 共享）；`.claude/commands/` 为 Claude Code 专用镜像（与 SKILL.md 同文拷贝），由 `scripts/check-skill-sync.sh` 校验、从 `skills/` 同步
> 最近整理：2026-09-26（补录 `/android-tag-data-backup-restore`、`/mnn-modelscope-uploader`、`/ardot-design-ops`；重同步 `/adb-bot` 至 3.0.0；命令数 30→33，`./scripts/check-skill-sync.sh` 无漂移）。2026-08-16（补录 `/ios-follow`、新增 `/ui-parity-guard` 镜像；命令数 28→30，与 `skills/` SSOT 对齐，`./scripts/check-skill-sync.sh` 无漂移）。2026-08-08（补充 iOS 部分 7 个 skill：ios-build-debug / ios-dev-loop / swiftui-expert / metal-render-expert / mnn-ios-integration / kmp-ios-interop / ios-i18n-validator；命令数 21→28）。2026-08-03 整理记录：移除已下线 `ncnn-integration` 条目；清理 InsightFace→MNN、`/agent-test`→`/ui-driver`、`/qa-acceptance` 等过时引用；去掉易过期的「行数」列。
