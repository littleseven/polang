---
name: ios-build-debug
description: |
  iOS 工程编译、模拟器/真机安装、日志调试的标准化流程。Use when compiling iosApp via xcodebuild, installing via simctl, debugging on simulator/device, or troubleshooting Xcode build issues.
version: 1.0.0
created: 2026-08-08
updated: 2026-08-08
maintainer: "[RD] 全栈工程师"
tags:
  - ios
  - xcodebuild
  - simctl
  - debug
  - build
---


# iOS 编译调试 Skill

> **定位**：iosApp 工程编译、模拟器/真机安装、日志调试的标准化流程。
> **触发时机**：用户需要编译 iOS、simctl 安装、查看日志或排查 xcodebuild 构建问题时自动启用。

## 标准编译流程

### 1. 编译（build-only，无签名，CI 与日常最快路径）
```bash
xcodebuild -scheme PoLang -destination 'generic/platform=iOS' build
```

### 2. 模拟器安装与启动
```bash
# 列出可用模拟器
xcrun simctl list devices available | grep iPhone
# boot + install + launch
xcrun simctl boot "iPhone 15 Pro"
xcrun simctl install booted <DerivedData>/Build/Products/Debug-iphonesimulator/PoLang.app
xcrun simctl launch booted com.mamba.picme
```

### 3. 截屏
```bash
xcrun simctl io booted screenshot /tmp/ios-shot.png
```

### 4. 清理后重启
```bash
xcrun simctl uninstall booted com.mamba.picme
xcrun simctl install booted <DerivedData>/.../PoLang.app
xcrun simctl launch booted com.mamba.picme
```

## 分层编译策略（减少等待）

```bash
# 第 1 层：Swift 类型检查（秒级；靠 swiftc 编译期，可叠 SwiftLint）
# 第 2 层：build-only（不签名，~10-60s）
xcodebuild -scheme PoLang -destination 'generic/platform=iOS' build
# 第 3 层：含签名安装到真机（仅最终验证）
xcodebuild -scheme PoLang -destination 'id=<device-id>' build
```

**规则**：每层失败立即修复，不继续下一层。详见 [error-healer](/error-healer)。

## DebugOverlay 状态画屏（真机可观测主手段）

iOS 真机日志/截屏工具链不可用：`xcrun log stream` 仅本机模拟器、devicectl 无截屏、无 libimobiledevice。**最稳可观测手段是把状态画到屏幕上**（5.1 第一天固化为基建）。

DebugOverlay 应画：权限态（Full/Limited/AddOnly/Denied）、帧计数/FPS、fetch/渲染耗时、错误文本。

```swift
// 叠加在根视图，Debug 构建常驻
ContentView()
    .overlay(alignment: .topTrailing) { DebugOverlayView(store: debugStore) }
```

## 签名

- 免费账号：7 天重签限制，开发期可接受。
- ad-hoc 真机包：5.5 TestFlight 未就绪时的交付物。
- 付费 Developer Program：5.5 TestFlight 硬前置（风险 R1）。

## Swift 编译错误速查

| 症状 | 根因 | 修复 |
|------|------|------|
| `Cannot find 'X' in scope` | 缺 import / 模块未链接 | 加 import；framework 加入 Link Binary |
| `signal 6` 崩溃 | Kotlin `@Throws` 异常跨 KN 边界 | 见 [kmp-ios-interop](/kmp-ios-interop)：边界 try/catch 兜底 |
| Metal shader 编译失败 | MSL 语法 / 地址空间 | 见 [metal-render-expert](/metal-render-expert) |
| `Undefined symbol` | framework 未 embed / `-ObjC` 缺 | MNN.framework 加入 Embed；`OTHER_LDFLAGS=-ObjC` |

## 项目特定路径

- 工程：`iosApp/PoLang.xcodeproj`
- App 源：`iosApp/PoLang/`
- MNN：`iosApp/Frameworks/MNN.framework`
- 隐私清单：`iosApp/PrivacyInfo.xcprivacy`
- 闭环脚本：`scripts/ios-dev-loop.sh`（转发壳，实际闭环在 `scripts/ios-auto-dev-loop.sh`）

## 相关文件

- [ios-dev-loop](/ios-dev-loop) — 闭环验证
- [error-healer](/error-healer) — 编译错误分类修复
- [kmp-ios-interop](/kmp-ios-interop) — signal 6 等 KN 边界坑

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 5 基建） |
