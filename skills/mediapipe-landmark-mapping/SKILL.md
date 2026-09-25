---
name: mediapipe-landmark-mapping
description: |
  MediaPipe 468 点与 106 点人脸关键点映射规范（双端双源架构）。
version: 2.0.0
created: 2026-05-03
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - mediapipe
  - landmark
  - mapping
  - face-detection
  - opengl
---


# MediaPipe 关键点映射 Skill

> **定位**：MediaPipe 468 点与 106 点人脸关键点映射规范。
> **触发时机**：用户涉及 MediaPipe 关键点映射、468 点转 106 点或人脸关键点对齐时自动启用。
> **映射表 SSOT**：`docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md`（已合并 MediaPipe 468 参考 / 468→106 映射策略 / 火山 106 点三份历史文档）——索引细节以该文档与代码映射表为准，本 skill 不再复制易腐的具体索引表。

## 双端双源架构（现役）

关键点有 **MediaPipe 468 源** 与 **MNN 106 原生源** 两条链路，双端各自落地：

| 端 | 468→106 适配器 | MNN 原生源 | 注册/选择 |
|----|---------------|-----------|----------|
| Android | `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/internal/facedetect/adapter/MediaPipe468Adapter.kt` | 同目录 `MnnLandmarkAdapter.kt` | `FaceLandmarkAdapterRegistry.kt` |
| iOS | `iosApp/PoLang/Features/Camera/Beauty/MediaPipe468Adapter.swift` | 同目录 `MnnFaceLandmarkService.swift` | 相机管线按需切换 |

**MediaPipe468Adapter 映射结构**（Android/iOS 同源约定）：
- **轮廓 33 点（索引 0-32）**：基于 MediaPipe FACE_OVAL 路径**插值生成**（468 无 1:1 对应）
- **非轮廓 73 点（索引 33-105）**：通过固定映射表 `NON_CONTOUR_MAPPING`（intArrayOf，含瞳孔点如 74=右瞳孔 473）**直映射**
- 合计 `POINT_COUNT = 106`（`CONTOUR_POINT_COUNT=33` + `NON_CONTOUR_POINT_COUNT=73`）

## 坐标系转换

### MediaPipe → OpenGL NDC
MediaPipe 输出 468 个点，范围 [0, 1]，原点在左上角：
- X: 从左到右 0→1
- Y: 从上到下 0→1

OpenGL NDC 范围 [-1, 1]，原点在中心：
```kotlin
// 标准映射（无镜像）
ndcX = x * 2.0f - 1.0f
ndcY = -(y * 2.0f - 1.0f)  // Y轴翻转

// 前置摄像头（左右镜像）
ndcX = -(x * 2.0f - 1.0f)  // X轴翻转
ndcY = -(y * 2.0f - 1.0f)  // Y轴翻转
```

iOS Metal 侧坐标约定见 [coordinate-system-standard](/coordinate-system-standard)（双端同源）。

## 常见陷阱

### 映射表二处实现漂移
Android（`.kt`）与 iOS（`.swift`）各有一份映射实现——**改映射必须双端同改**，并以 FACE_LANDMARKS.md 为对齐基准。

### 坐标交换问题
MediaPipe 某些版本输出可能是 [y, x] 顺序：
```kotlin
// 如果画面有90度偏转，尝试交换 X/Y
val temp = x
x = y
y = temp
```

### 索引越界
- 106 点有效索引：0-105
- 禁止使用 106+（历史原因：早期引擎有 111 点，统一 106 标准后索引上限为 105）
- 替代方案：用语义相近的点替代（对照 FACE_LANDMARKS.md 索引表选点）

### 左右镜像
前置摄像头预览需要左右镜像：
```kotlin
// 前置摄像头
ndcX = -(x * 2.0f - 1.0f)
```

## 调试方法

### 可视化关键点
```kotlin
// 在屏幕上绘制点
GLES20.glDrawArrays(GLES20.GL_POINTS, 0, vertexCount)
```

### 日志输出关键点位置
```kotlin
for (i in 0 until 106) {
    Log.d(TAG, "Point $i: (${landmarks[i*2]}, ${landmarks[i*2+1]})")
}
```

对齐异常的分层排查（输入预处理/输出读取/坐标变换/坐标解析/点序映射五层框架）见 [mnn-landmark-diagnosis](/mnn-landmark-diagnosis)。

## 三角网格构建

### 避免索引越界
```kotlin
// 错误：使用 106
val indices = intArrayOf(95, 96, 106) // 越界！

// 正确：用 95 替代 106
val indices = intArrayOf(95, 96, 95) // 95 是下唇底部
```

## 相关文件

- `docs/03-TECHNICAL-SPECS/FACE_LANDMARKS.md` — 映射表与索引对照 SSOT
- `engines/beauty-engine/.../facedetect/adapter/MediaPipe468Adapter.kt` — Android 映射实现
- `iosApp/PoLang/Features/Camera/Beauty/MediaPipe468Adapter.swift` — iOS 映射实现
- [coordinate-system-standard](/coordinate-system-standard) — 坐标系规范
- [mnn-landmark-diagnosis](/mnn-landmark-diagnosis) — 关键点对齐诊断

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
| 2.0.0 | 2026-09-25 | 对齐双源架构：真实映射文件 `MediaPipe468Adapter.kt/.swift`（原 `MediaPipeTo106Mapping.kt` 不存在）；映射结构改为 33 轮廓插值 + 73 直映射；易腐索引表移交 FACE_LANDMARKS.md SSOT；补双端同改纪律 |
