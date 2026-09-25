---
name: kmp-ios-interop
description: |
  Kotlin/Native ↔ Swift 互操作铁律与 shared XCFramework 集成：signal 6 崩溃、Flow→AsyncStream、SharedBridge 约定、组合根 D7。Use when integrating the shared KMP framework into iosApp, crossing Kotlin↔Swift boundaries, or debugging signal 6/retain-cycle/interop issues.
version: 1.1.1
created: 2026-08-08
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - ios
  - kmp
  - kotlin-native
  - interop
  - shared
---


# KMP iOS 互操作 Skill

> **定位**：Kotlin/Native ↔ Swift 互操作铁律与 shared framework 集成（无 Android 对标，iOS 独有痛点）。
> **触发时机**：shared framework 集成、Kotlin ↔ Swift 边界、XCFramework embed、Flow → Swift、signal 6 崩溃时。

## SKIE（2026-08-10 起，新链路首选形态）

SKIE 0.10.14 已接入（`shared/build.gradle.kts` 插件，零侵入），spike GO 真机实证（2026-08-10 spike 报告已随交付清理，结论即本节纪律，细节查 git 历史）。**追齐期纪律：新链路一律用 SKIE 形态，不再新增 FlowWatcher / SharedBridge 式手写桥；存量桥迁移冻结至 iOS 1.0 功能冻结后。**

| Kotlin | SKIE 给 Swift 的形态 | 用法 |
|--------|---------------------|------|
| `suspend fun` | `async throws`（异常经 Swift 类型系统传导） | `try await service.foo()` |
| sealed interface/class | 真 Swift enum（`onEnum`） | `switch onEnum(of: e)` 无 default 穷举 |
| `Flow`/`SharedFlow`/`StateFlow` | `AsyncSequence`（apinotes 类型替换 + Swift 桥接） | `for await x in service.flow` |

注意：SKIE 与手写 FlowWatcher **互斥不可混用**（同一条 Flow 只能一种消费形态），迁移必须逐链路切换；闭包参数型桥（如 `ChatAgentBridge`）不受 SKIE 影响，可独立演进。首条已迁移链路：`GalleryViewModel.swift`（FlowWatcher → `for await` 直消费）。

## 核心铁律

### 1. `@Throws` 不导出异常 → signal 6 崩溃（最高频坑）

> **SKIE 时代适用范围收窄**：SKIE 覆盖的 suspend 函数异常已由 `async throws` 工具保证。本铁律仅约束 **SKIE 未覆盖的边角**——非 suspend API、闭包参数型桥（callback 里逃逸的异常）。

Kotlin 异常**不**经 `@Throws` 自动导出到 Swift；未兜底的异常跨 KN 边界会直接 **signal 6（SIGABRT）崩溃**，Swift 侧无法 catch。

**纪律**：所有 shared → Swift 边界，在 **Kotlin 侧** try/catch，兜底为 `Result` / 可空 / 字符串。`SharedBridge/` 统一此约定。

```kotlin
// shared commonMain —— 永远不让异常逃逸到 Swift
fun doSomethingSafe(): Result<Data> = runCatching { doSomethingRisky() }
```

```swift
// iosApp/PoLang/SharedBridge —— Swift 只消费 Result，绝不指望 catch Kotlin 异常
switch SharedBridge.shared.doSomethingSafe() {
case let .success(data): // ...
case let .failure(message): // 字符串，非异常
}
```

### 2. 组合根 D7 模式

- shared **不知任何 iOS 类型**；无 `PlatformContext` expect。
- `iosApp/PoLang/DI/AppContainer.swift` 构造注入 Swift actual 进 shared。
- shared 接口在 commonMain，actual 实现在 `iosApp/PoLang/Platform/`。

### 3. Flow → Swift AsyncStream

> **新链路改用 SKIE `for await` 直消费**（见顶部 SKIE 节）；本节仅适用于尚未迁移的存量 SharedBridge 链路。

Kotlin `Flow` 经 `SharedBridge` 转 Swift `AsyncStream`（打字机流式渲染等）：

```swift
for try await chunk in SharedBridge.shared.chatStream() {
    // Kotlin Flow → Swift AsyncStream
}
```

## XCFramework embed

```bash
./gradlew :shared:assembleSharedKitDebugXCFramework
# debug 日常 ~6s；Release ~4min（一次性）
```

- Build Phase 脚本按 **Gradle 构建 hash 重拷**，避免每次 Xcode 编译触发 Kotlin 全量重编。
- 产物 embed 到 iosApp；framework 体积监控（防 KN 二进制膨胀）。

## 常见坑

| 坑 | 症状 | 修复 |
|----|------|------|
| Kotlin 异常逃逸 | signal 6 崩溃 | 边界 try/catch 兜底 Result（铁律 1） |
| Flow 未释放 | 流泄漏 / retain cycle | AsyncStream + `[weak self]` |
| `PlatformContext` expect | shared 耦合 iOS 类型 | D7 组合根，删 expect |
| framework 体积暴涨 | 启动慢 / 包超限 | 监控体积；查无用符号 |
| 跨线程改 UI | 主线程断言 | `@MainActor` + `DispatchQueue.main` |

## 相关文件

- [ios-build-debug](/ios-build-debug) — XCFramework 编译 / 安装
- [doc-sync-guardian](/doc-sync-guardian) — shared 接口漂移治理
- KMP 依赖方向契约：`docs/02-ARCHITECTURE/ADR/ADR-013-kmp-architecture-contract.md`

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 4/5 跨切面 R2） |
| 1.1.0 | 2026-08-10 | SKIE 0.10.14 接入（spike GO 合入 main）：新增 SKIE 节为新链路首选形态；signal 6 铁律收窄至 SKIE 未覆盖边角；FlowWatcher/SharedBridge 式桥不再新增 |
| 1.1.1 | 2026-09-25 | 对齐 iOS 目录重组（iosApp 顶层并入 `PoLang/`）与 XCFramework 任务更名（Shared → SharedKit） |
