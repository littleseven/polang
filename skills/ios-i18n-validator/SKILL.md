---
name: ios-i18n-validator
description: |
  iOS 五语（EN/zh-CN/zh-TW/ES/FR）文案同步与规范验证。Use when adding/refactoring user-facing SwiftUI strings, syncing Localizable.xcstrings, or checking for hardcoded iOS UI text.
version: 1.0.0
created: 2026-08-08
updated: 2026-08-08
maintainer: "[RD] 全栈工程师"
tags:
  - ios
  - i18n
  - localization
  - xcstrings
---


# iOS i18n 验证 Skill

> **定位**：iOS 五语（EN / zh-CN / zh-TW / ES / FR）同步与文案规范，对标 Android i18n-validator。
> **触发时机**：新增/重构用户可见 SwiftUI 文案、同步 xcstrings、检查硬编码文本时。

## 红线

`[I18N]` 五语（EN/zh-CN/zh-TW/ES/FR）同步。**禁硬编码用户可见字符串**。

## 标准流程

### 1. 提取文案到 xcstrings
```swift
// ❌ 禁止
Text("搜索照片")
// ✅ 正确
Text("search.photos.label")  // Localizable.xcstrings 键
```

### 2. 五语同步
`Resources/Localizable.xcstrings`（String Catalog，Xcode 15+）每个键必须含五语；权限用途文案（Info.plist purpose strings）在 `Resources/InfoPlist.xcstrings` 同步五语：

| key | en | zh-Hans | zh-Hant | es | fr |
|-----|----|---------|---------|----|----|
| `search.photos.label` | Search Photos | 搜索照片 | 搜尋照片 | Buscar fotos | Rechercher des photos |

### 3. 双端键对齐（S5 双端体验一致）
iOS 键与 Android `androidApp/src/main/res/values*/strings.xml` **语义对齐**（同义键用一致命名或建立映射表），避免双端文案漂移。

命名规范：`<feature>.<element>.<role>`，如 `gallery.empty.title`、`camera.shutter.hint`。

## 检查清单

- [ ] 所有用户可见 Text/Label/alert/button 用了 xcstrings 键？
- [ ] 每个新键五语齐全（en / zh-Hans / zh-Hant / es / fr）？
- [ ] 复数/格式化用 xcstrings 复数语法（Plural）？
- [ ] 与 Android 同义键对齐？
- [ ] DebugOverlay 等仅调试文案可豁免（明确标注，不进 xcstrings）。

## 相关文件

- [i18n-validator](/i18n-validator) — Android 五语验证
- iOS strings：`iosApp/PoLang/Resources/Localizable.xcstrings`（权限用途文案在 `iosApp/PoLang/Resources/InfoPlist.xcstrings`）
- Android strings：`androidApp/src/main/res/values*/strings.xml`

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-08-08 | 初始版本（Phase 5 基建） |
