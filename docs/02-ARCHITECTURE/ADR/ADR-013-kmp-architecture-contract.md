# ADR-013: KMP 架构契约（AI 友好 / 双端一致性优先）

**状态**: 决策与契约文案已定；commonMain 纯度守卫 `checkCommonMainPurity` 已落地并验证；`checkIosSeamFlat` 暂缓
**日期**: 2026-08-12
**决策**: 用户
**依赖**: ADR-005（远程推理协议标准化）、ADR-008（隐私红线）
**评估依据**: 2026-08-12 KMP 契约评估（结论已收编本 ADR）

---

## 1. 背景

用户提出《PoLang 技术选型架构契约》6 条 + 3 条元原则。逐条对照实际代码评估（见评估文档），结论：**契约 ~90% 是对现状的描述，不是待改项**；但有 4 处措辞需修正（A/B/C/D），以匹配"SKIE 中介的不对称 native"实况与 3 条元原则。

实查关键事实：
- `:shared/commonMain` 纯度 de-facto 成立：`@Composable`=0、平台 `import android.|java.`=0、`actual` 声明=0（16 处 `actual` 文本全在 KDoc 注释）。
- ADR 已到 ADR-012，本 ADR = 013；ADR-005/008/009/010 已覆盖协议分离/隐私/本地模型/链路隔离，本文引用不重复。
- `:engines:*` 是 `com.android.library`，**仅产 Android `.so`**；iOS native（`MNN.framework`+Pods+Swift）独立，不消费 engines（`shared/build.gradle.kts:57-61`）。
- commonMain 现用 Flow×15 / suspend×107 / sealed×14 / value class×1；依赖 SKIE（`shared/build.gradle.kts:5`）桥接到 Swift。
- iosMain 已 16 文件，含 `*Bridge`/`*Dto`/`FlowWatchers` 扁平 seam 与 `IosUnavailableImageInferenceEngine`（VLM expect 在 iOS 为桩）。

## 2. 决策（修正后架构契约）

### 2.0 裁决原则（优先级从高到低，冲突时前者优先）

1. **AI 友好优先**：架构首要目标是让大模型可靠生成成熟、低复杂度代码，必须考虑 LLM 能力限制与生成代码成熟度。当"Kotlin 优雅 / 抽象完备 / 隐式魔法（含 SKIE）"与之冲突时，让步。
2. **降低双端维护成本 / 提升迭代效率 / 保障 UI 一致性**。
3. **用户体验一致性**。

> 执行原则：任何新功能，先问"这是业务逻辑还是平台实现"。业务逻辑下沉 `commonMain`，平台实现留在各自端。

### 2.1 KMP 只共享业务逻辑，绝不共享 UI

- `:shared/commonMain` 仅存：Agent 编排（`AgentOrchestrator`/`CapabilityRegistry`/`PrivacyGuard`）、Repository 接口与实现、数据模型/DTO、网络层（Ktor client）、状态机、纯 Kotlin 工具。
- **禁止**：`@Composable` UI、Native 推理实现、平台权限/存储/加密逻辑。（现状已符合：0 命中）
- **明确不做 CMP**。`androidApp`=Jetpack Compose，`iosApp`=SwiftUI，各端原生维护。理由：① 与 Agent-First 冲突（CMP 隐式编译抽象，AI 调试困难）；② 相册/相机合规差异无法靠 UI 共享消除；③ 双端原生已存在，迁移成本极高、收益极低；④ 包体积增量不可接受。

### 2.2 Native 推理：各端 native 自理，`:shared` 只统一业务逻辑（修正 A）

- `:engines:*`（`mnn-core`/`agent-native`）为 **Android-only** `com.android.library`，**仅产 Android `.so`**。
- iOS 侧 native（`MNN.framework`+Pods+Swift）**独立维护，不消费 `:engines:*`**。
- 两端 native 各自自理；`:shared` KMP 只统一业务逻辑。十几个本地模型的内存预算/加载策略/生命周期状态机可在 commonMain 定义，实际加载/卸载由各端 native 执行。
- 理由：AGP 9 KMP 库插件不支持 externalNativeBuild，JNI 构建须独立 Android library 承载（`shared/build.gradle.kts:57-61`）。

### 2.3 跨 Swift seam 必须扁平（修正 B，Principle 1）

- **commonMain 内部**（Kotlin↔Kotlin）允许 Flow/sealed/suspend，**不强行扁平化**（否则破坏 Agent runtime）。
- **跨 Swift 的 seam 必须扁平**：iosMain 的 `*Bridge`/`*Dto` 层负责 Flow→显式回调、sealed→DTO+enum tag、suspend→显式 async 回调、`value class`→显式 data class。
- **SKIE 仅作兜底，不作 correctness 依据**——大模型对隐式变换生成的 Swift 成熟度低、易错。AI 生成的 Swift 只对着扁平 seam 写，不依赖 SKIE 变换正确性。
- 连带修正：`UserPreferences.kt:207` `value class ModelCategory` 若跨 Swift 须改显式 data class。

### 2.4 expect 接口按需、扁平（修正 C）

- commonMain 可定义扁平 `expect` 接口（现状 4 个：`Platform`/`DispatcherProvider`/`AgentIdGenerator`/`KoogHttpClientFactoryProvider`，全扁平）。
- **按需引入**，不投机：当前唯一 VLM expect `ImageInferenceEngine` 在 iOS 为 unavailable 桩（`IosUnavailableImageInferenceEngine`），iOS 打标走自有 native 管线；双端共享 seam 待该能力真正需要跨端时再建。

### 2.5 合规差异显式隔离，各端自理

- 相册/相机合规差异（Android Scoped Storage vs iOS PHPhotoLibrary、后台处理策略、加密实现、iOS Privacy Manifest）**不在 commonMain 统一**。
- commonMain 仅定义合规策略（数据分级枚举、隐私规则、审计日志格式）；权限申请/存储路径/加密实现由各端原生处理。
- URI/媒体标识符在 commonMain 仅作不可解析的业务 ID（`String`），真正解析在平台层完成。

### 2.6 UI 一致性：不共享代码，共享 AI 可读设计规范 SSOT（新增 D，Principle 2+3）

- 双端 UI **不共享代码**；共享一份 **AI 可读设计规范 SSOT**（design tokens + 组件行为 + 交互流程），两端各自原生实现并对照。
- 先例：`coordinate-system-standard`（106pt 坐标双端同源）已是该模式的成熟样本，照其泛化。
- 迭代模型：Android 为 pace-setter → `/ios-follow` 一键对等跟随 → `screenshot-diff.py`/`swiftui-expert`/`ios-i18n-validator` 做 parity 校验（链路已存在，非新建）。

### 2.7 AI 编码分工

- **用户（Android 程序员）**：`commonMain` 业务逻辑、`androidApp` Compose UI、`androidMain` actual 实现。
- **AI（Claude 等）**：`iosApp` SwiftUI、`iosMain` actual 实现、engines iOS 侧桥接。
- 接口契约由 `commonMain` 的 `expect`/数据类/Flow 契约定义，AI 据此生成 iOS 实现（遵循 2.3 扁平 seam）。接口设计遵循"简单、扁平、可显式桥接"原则。

### 2.8 模块边界

```
:shared (KMP)
├── commonMain      ← 业务逻辑 + 接口契约（纯：无 UI / 无平台逻辑）
├── androidMain     ← JNI 桥接 + Android 特定实现
├── iosMain         ← Swift 扁平 seam（Bridge / DTO）
└── jvmMain         ← 仅 4 个 expect 的 jvm actual（测试用，非产物目标）

:androidApp         ← Jetpack Compose UI（pace-setter）
:iosApp             ← SwiftUI
:engines:*          ← Android-only Native（NDK/JNI，产 .so；iOS 不消费）
```

## 3. 实现要点

- **commonMain 纯度（de-facto 已成立）**：`@Composable`=0、`actual` 声明=0、`import android.|java.`=0；16 处 `actual` 文本全在 KDoc 注释。
- **构建期守卫 `checkCommonMainPurity`（✅ 已落地，`shared/build.gradle.kts`）**：扫描 `src/commonMain/kotlin`，命中 `@Composable` / `import (android|java|androidx.compose)` / `actual` 声明任一即 build fail；绑 `compileKotlinMetadata`，androidApp 每次构建都校验。已验证：clean tree PASS、植入 4 类违规 FAIL（精确行号）。
- **`checkIosSeamFlat`（📭 暂缓）**：原拟 grep iosMain 裸 `Flow<` / `value class`，但实读 iosMain 发现 seam 已自律扁平——`FlowWatchers` 消费 Flow 只对外暴露 `FlowWatcher`/回调、`ChatUiActionDto` 把 sealed 扁平成 DTO+`kind`。裸 `Flow<` grep 会误伤合法的 `fun Flow<T>.watch(...)` 桥接 helper，需语义级判定（仅 public Swift-facing 面）才能无误伤，投入不划算，待 seam 规模扩大再议。
- ⚠️ **发现 doc 漂移**：`CLAUDE.md` 称 FQN 规则由 `checkNoFullyQualifiedName` 强制，但该 task 实际仅在 `androidApp/build.gradle.kts:81` 注释引用、全仓无定义，**未生效**。→ **已处理（2026-08-23）**：`CLAUDE.md` 表述已改为「约定规则，enforcement task 未实现」；若要机器判定（实测代码中仍有 ~10 处真实 FQN 引用需先清理），留作单独议题。
- **小修**：`UserPreferences.kt:207` `value class ModelCategory` 若跨 Swift 改显式 data class。
- **doc-sync**：CLAUDE.md 的 ADR 引用陈旧（称 ADR-008 为最新，实到 012）需修；本 ADR 入册。

## 4. 后果

- ✅ 契约匹配实况，消除"engines 双端产物""有 SKIE 兜底就行"两类误导性表述。
- ✅ commonMain 纯度 + seam 扁平 = 可机器判定，防住单次坏提交搞挂 iOS 编译。
- ✅ AI 生成的 Swift 只对着扁平 seam 写，正确率与成熟度提升（Principle 1）。
- ✅ UI 一致性有 SSOT + parity 工具闭环，不依赖共享 UI 代码（Principle 2+3）。
- ⚠️ iosMain Bridge 层需自律维持扁平（靠守卫 + review 兜底）。
- ⚠️ UI 设计规范 SSOT 需持续维护（Stage 3，缓做）。

## 5. 状态

| 项 | 状态 |
|---|---|
| 决策与 ADR 文案 | ✅ 2026-08-12 |
| 契约 4 处修正（A/B/C/D） | ✅ 本文 §2 |
| `checkCommonMainPurity` 守卫 | ✅ 2026-08-12（`shared/build.gradle.kts`，绑 `compileKotlinMetadata`，已验证 pass/fail） |
| `checkIosSeamFlat` 守卫 | 📭 暂缓（seam 已自律扁平；grep 级检查误伤合法 Flow 桥接 helper，需语义级判定） |
| `value class ModelCategory` 跨界修正 | ⏳ 待确认是否跨 Swift |
| CLAUDE.md ADR 引用修复 | ✅ 已完成（CLAUDE.md 现引用至 ADR-013） |
| UI 设计规范 SSOT 整合 | ⏳ Stage 3（缓做） |

## 6. 相关

- ADR-005（远程推理协议标准化）、ADR-008（隐私红线）
- 先例 / 工具：`coordinate-system-standard` skill、`/ios-follow` skill、`screenshot-diff.py`、`swiftui-expert`、`ios-i18n-validator`
