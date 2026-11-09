---
name: android-build-debug
description: |
  Android 项目编译、安装、日志调试的标准化流程。
version: 1.1.0
created: 2026-05-03
updated: 2026-08-03
maintainer: "[RD] 全栈工程师"
tags:
  - android
  - gradle
  - build
  - debug
  - apk
---


# Android 编译调试 Skill

> **定位**：Android 项目编译、安装、日志调试的标准化流程。
> **触发时机**：用户需要编译 APK、安装应用、查看日志或排查构建问题时自动启用。


## 标准编译流程

### 1. 编译 Debug APK
```bash
# 在项目根目录执行
./gradlew :androidApp:assembleDebug
```

### 2. 安装到设备
```bash
adb install -r androidApp/build/outputs/apk/debug/polang-debug.apk
```

### 3. 查看日志（过滤 PoLang 标签）
```bash
adb logcat -s PoLang:* *:S
```

### 4. 清除日志后重新启动
```bash
adb logcat -c
adb shell am force-stop com.mamba.picme
adb shell am start -n com.mamba.picme/.MainActivity
```

## 分层编译策略（减少等待）

```bash
# 第 1 层: 语法/格式检查（~2s）
./gradlew :androidApp:ktlintCheck

# 第 2 层: Kotlin 编译到 class（~5-30s）
./gradlew :androidApp:compileDebugKotlin

# 第 3 层: beauty-engine 模块编译（~10-60s）
./gradlew :engines:beauty-engine:assembleDebug

# 第 4 层: 完整 APK（~30-120s，仅最终验证）
./gradlew :androidApp:assembleDebug
```

**规则**：每层失败后立即修复，不继续下一层。详见 [error-healer](/error-healer) 的分层验证策略。

## 编译错误排查

### 快速失败日志解析
```bash
# 提取关键错误信息（前 3 个错误）
./gradlew :androidApp:compileDebugKotlin 2>&1 | \
    grep -E "^e:\s+" | head -3 > /tmp/compile_errors.txt

# 提取文件和行号
./gradlew :androidApp:compileDebugKotlin 2>&1 | \
    grep -E "\.kt:\d+:\d+" | head -3 > /tmp/error_locations.txt
```

### 常见错误处理
1. **Kotlin 编译错误**：检查语法、导入、类型匹配
2. **Shader 编译错误**：检查 GLSL 语法、varying/uniform 声明一致性
3. **资源缺失错误**：检查 assets/、res/ 目录文件是否存在
4. **依赖冲突**：检查 gradle/libs.versions.toml

### 修复策略（联动 error-healer）
- 语法错误 → [error-healer Class A](/error-healer)
- 类型不匹配 → [error-healer Class B](/error-healer)
- 未解析引用 → [error-healer Class C](/error-healer)
- 可见性问题 → [error-healer Class D](/error-healer)
- 空安全错误 → [error-healer Class E](/error-healer)
- Gradle 构建错误 → [error-healer Class G](/error-healer)

### 快速修复流程
1. 读取错误日志定位文件和行号
2. 读取相关文件内容
3. 修复错误
4. 重新编译验证

## 运行时调试

### Shader 调试
- 使用纯色输出验证 Shader 执行：`gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0)`
- 检查 uniform 绑定：`Log.d(TAG, "uniform location: $loc")`
- 检查纹理加载：`Log.d(TAG, "texture id: $id, size: ${width}x${height}")`

### OpenGL 调试
- 检查 GL 错误：`GLES20.glGetError()`
- 检查 FBO 状态：`GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)`
- 检查 Shader 编译日志：`GLES20.glGetShaderInfoLog(shaderId)`

## 真机 androidTest 红线（2026-11-08 血泪教训）

`./gradlew connectedDebugAndroidTest` 跑完后会**自动卸载 app 与测试包，卸载即清空应用私有数据（数据库/标签/聊天记录全丢）**。

- 设备上有值钱数据（聊天历史、VLM 标签、人脸聚类）时，**先用 android-tag-data-backup-restore 快照**再跑
- 或绕开 Gradle 任务：`adb install` 两个 APK 后手动 `adb shell am instrument`，测试完不卸载
- 迁移验证类测试拉取的 DB 副本（/tmp/*.db）在确认前**不要删**

## 项目特定路径

- APK 输出：`androidApp/build/outputs/apk/debug/polang-debug.apk`
- Shader 目录：`engines/beauty-engine/src/main/assets/shaders/`
- Java/Kotlin 源码：`androidApp/src/main/java/com/mamba/picme/`
- 日志标签过滤：`adb logcat -s PoLang:* *:S`

## 相关文件

- [error-healer](/error-healer) — 编译错误自动分类与修复
- [adb-bot](/adb-bot) — 设备控制与日志收集
- [dev-loop](/dev-loop) — Dev Loop
- [ios-build-debug](/ios-build-debug) — iOS xcodebuild / simctl 对标

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.2.0 | 2026-11-08 | 新增「真机 androidTest 红线」：connectedDebugAndroidTest 测完自动卸载 app 清数据 |
| 1.1.0 | 2026-05-31 | 统一格式规范，添加定位块 |
| 1.0.0 | 2026-05-03 | 初始版本 |
