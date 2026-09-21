# 坐标系管理技术决策文档

**文档编号**: ADR-003  
**创建日期**: 2026-05-03  
**最后同步**: 2026-08-23（§2.3 表对齐实际检测器类名：`MediaPipeFaceDetector`/`MnnLandmarkDetector`；移除已整删的 NCNN 与未使用的 OpenCV 引用）  
**状态**: 已采纳  
**影响范围**: 全项目（人脸检测、渲染引擎、UI 展示）

---

## 📋 目录

- [1. 背景与问题](#1-背景与问题)
- [2. 决策方案](#2-决策方案)
- [3. 技术方案详解](#3-技术方案详解)
- [4. 实施指南](#4-实施指南)
- [5. 迁移计划](#5-迁移计划)
- [6. 验收标准](#6-验收标准)
- [7. 相关文档](#7-相关文档)

---

## 1. 背景与问题

### 1.1 问题描述

在 PoLang 项目中，人脸关键点坐标存在两种不同的参照系：

1. **图像坐标系**（Image Space）
   - 基于图像边界
   - 原点：左上角 (0, 0)
   - x 轴：向右增加
   - y 轴：向下增加
   - 适用场景：GPU Shader、OpenGL 渲染、图像处理算法

2. **人脸坐标系**（Face Space / User Space）
   - 基于被拍摄者身体
   - 左/右：以被拍摄者为参照
   - 需要区分前后置摄像头
   - 适用场景：UI 文案、用户交互、业务逻辑

### 1.2 历史问题

#### 问题 1: 坐标系混用导致 Bug

```kotlin
// ❌ 历史代码：混用两种坐标系
fun calculateEyeDistance() {
    val imageLeftEye = getImageLeftEye()      // 图像坐标系
    val userRightEye = getUserRightEye()      // 人脸坐标系
    
    // Bug: 两种坐标系混合计算，结果错误
    val distance = sqrt((imageLeftEye.x - userRightEye.x)^2 + ...)
}
```

**后果**：
- 前置摄像头时计算结果错误
- 瘦脸效果位置偏移
- 妆容贴附不准确

#### 问题 2: 文档描述歧义

```markdown
❌ 模糊描述：
- "左眼外眼角"
- "右眉眉头"

问题：
- 是图像左侧还是被拍摄者左侧？
- 前置和后置是否一致？
```

**后果**：
- 新成员理解困难
- 开发效率降低
- Code Review 争议

#### 问题 3: 缺乏统一规范

- 不同模块使用不同的坐标系
- 转换逻辑分散在各处
- 没有明确的转换函数

### 1.3 业界调研

| 项目 | 坐标系类型 | 镜像处理 | 特点 |
|------|-----------|---------|------|
| Android Camera API | 图像坐标系 | 手动镜像 | 基于传感器视角 |
| MediaPipe Face Mesh | 图像坐标系 | 手动镜像 | 归一化坐标 (0-1) |
| ML Kit Face Detection | 语义坐标系 | 需转换 | 基于被拍摄者 |
| ARKit Face Tracking | 3D 世界坐标系 | 自动镜像 | 右手坐标系 |
| OpenCV | 图像坐标系 | 手动镜像 | 标准像素坐标 |

**结论**：
- 2D 人脸检测领域：图像坐标系是主流
- 但 UI/业务层常需要人脸坐标系（符合用户直觉）
- 需要在灵活性和安全性之间找到平衡

---

## 2. 决策方案

### 2.1 核心决策

> **允许两种坐标系并存，但严禁混用**

#### 决策理由

1. **灵活性需求**
   - UI 层使用人脸坐标系更符合用户直觉
   - 渲染层必须使用图像坐标系（技术标准）
   - 不同层级有不同需求

2. **安全性保障**
   - 强制标注避免歧义
   - 禁止混用防止错误
   - 明确转换边界保证可控

3. **工程实践**
   - 兼容现有代码
   - 便于团队协作
   - 降低维护成本

### 2.2 分层架构设计

```
┌─────────────────────────────────────────┐
│         坐标系分层架构                    │
├─────────────────────────────────────────┤
│                                         │
│  UI / 业务层                             │
│  ├── 可使用 [人脸坐标系]                 │
│  ├── 便于用户理解                        │
│  └── 需处理前后置差异                    │
│                                         │
│  ↓ 转换边界（明确函数）                  │
│  convertUserToImageCoordinates()        │
│  convertImageToUserCoordinates()        │
│                                         │
│  渲染 / 算法层                           │
│  ├── 必须使用 [图像坐标系]               │
│  ├── GPU Shader 处理                    │
│  ├── OpenGL 纹理映射                    │
│  └── 图像处理算法                        │
│                                         │
└─────────────────────────────────────────┘
```

### 2.3 坐标系选择策略

| 层级 | 推荐坐标系 | 原因 | 示例场景 |
|------|-----------|------|---------|
| **UI 展示层** | 人脸坐标系 | 用户更容易理解"左眼"、"右眼" | 调试浮层标签、设置界面文案 |
| **业务逻辑层** | 图像坐标系（推荐）或人脸坐标系 | 根据团队习惯统一选择 | 美颜参数计算、关键点分析 |
| **渲染引擎层** | **必须**使用图像坐标系 | GPU Shader、OpenGL 原生支持 | BeautyRenderer、Shader 处理 |
| **算法处理层** | **必须**使用图像坐标系 | MediaPipe（`MediaPipeFaceDetector` 等）、MNN（`MnnLandmarkDetector` 等）、坐标转换 |

---

## 3. 技术方案详解

### 3.1 三大强制要求

#### 要求 1: 明确标注坐标系类型

所有变量、注释、文档必须明确标注坐标系：

```kotlin
// ✅ 正确标注
val imageLeftEye = getImageLeftEye()      // [图像坐标系]
val userLeftEye = getUserLeftEye()        // [人脸坐标系]

// ❌ 错误：未标注
val leftEye = getLeftEye()                // 歧义！
```

**KDoc 规范**：

```kotlin
/**
 * [图像坐标系] 获取图像左侧眼睛的中心点
 * 
 * @return 图像坐标系中的点（原点在左上角，x 向右增加，y 向下增加）
 * @see COORDINATE_SYSTEM_STANDARD.md 坐标系规范
 */
fun getImageLeftEyeCenter(): Point {
    // ...
}

/**
 * [人脸坐标系] 获取被拍摄者左眼的中心点
 * 
 * @param isFrontCamera 是否为前置摄像头（影响坐标映射）
 * @return 图像坐标系中的点（已根据前后置自动转换）
 * @note 前置时返回图像右侧的点，后置时返回图像左侧的点
 */
fun getUserLeftEyeCenter(isFrontCamera: Boolean): Point {
    // ...
}
```

#### 要求 2: 同一作用域内禁止混用

```kotlin
// ❌ 错误：混用两种坐标系
fun processFace() {
    val imageLeftEye = getImageLeftEye()      // 图像坐标系
    val userRightEye = getUserRightEye()      // 人脸坐标系
    val distance = calculateDistance(imageLeftEye, userRightEye)  // ❌ 混用！
}

// ✅ 正确：统一使用一种坐标系
fun processFace() {
    val imageLeftEye = getImageLeftEye()
    val imageRightEye = getImageRightEye()
    val distance = calculateDistance(imageLeftEye, imageRightEye)  // ✅ 同一种
}
```

#### 要求 3: 跨坐标系转换必须有明确函数

> **规范要求**：跨坐标系转换应通过明确的转换函数完成，禁止隐式转换。
>
> **实际状态**：当前代码中坐标转换逻辑内嵌在渲染管线和检测适配层中，未提取为独立的公共 API。以下接口设计可作为未来重构的参考：

```kotlin
/**
 * [坐标转换] 将人脸坐标系转换为图像坐标系
 * 
 * @param userLandmarks 人脸坐标系的关键点列表
 * @param isFrontCamera 是否为前置摄像头
 * @return 图像坐标系的关键点列表
 */
fun convertUserToImageCoordinates(
    userLandmarks: List<Point>,
    isFrontCamera: Boolean
): List<Point> {
    return userLandmarks.map { point ->
        if (isFrontCamera) {
            // 前置摄像头：镜像翻转
            Point(1.0f - point.x, point.y)
        } else {
            // 后置摄像头：保持不变
            point
        }
    }
}

/**
 * [坐标转换] 将图像坐标系转换为人脸坐标系
 * 
 * @param imageLandmarks 图像坐标系的关键点列表
 * @param isFrontCamera 是否为前置摄像头
 * @return 人脸坐标系的关键点列表
 */
fun convertImageToUserCoordinates(
    imageLandmarks: List<Point>,
    isFrontCamera: Boolean
): List<Point> {
    // 逆转换：与上面相同（镜像是对称操作）
    return convertUserToImageCoordinates(imageLandmarks, isFrontCamera)
}
```

### 3.2 命名规范

**标准格式**：`{坐标系前缀}_{位置}_{部位}`

| 坐标系前缀 | 含义 | 示例 |
|-----------|------|------|
| `image_` | 图像坐标系（观察者视角） | `imageLeftEye`, `imageRightEyebrow` |
| `user_` 或 `face_` | 人脸坐标系（被拍摄者视角） | `userLeftEye`, `faceRightCheek` |

> **注意**：以下 `EyePosition` 枚举和 `convertUserToImageCoordinates` / `convertImageToUserCoordinates` 函数为规范示例，展示了理想的坐标系转换接口设计。实际代码中，坐标转换逻辑内嵌在 `BeautyRenderer` / `FaceMakeupPass` 的渲染管线中，未提取为独立的公共转换函数。若后续需要跨模块复用坐标转换，可参考以下接口设计进行提取。

### 3.3 文档描述规范

**强制格式**：`[{坐标系}] {位置描述}`

```markdown
❌ 错误（未标注坐标系）：
- "左眼外眼角"
- "右眉眉头"

✅ 正确（明确标注）：
- "[图像坐标系] 图像左侧眼睛的外眼角"
- "[人脸坐标系] 被拍摄者右眉的眉头"
- "[图像坐标系] 图像右侧眉毛的眉头（后置时对应被拍摄者右眉）"
```

**文档章节开头必须添加坐标系声明**：

```markdown
## 3. 人脸关键点映射

> **坐标系说明**：本节所有坐标均基于**图像坐标系**（观察者视角）。
> - 图像左侧 = x 坐标较小的一侧
> - 图像右侧 = x 坐标较大的一侧
> - 前置摄像头镜像后，图像左侧对应被拍摄者右脸

| 索引 | 部位 | 说明 |
|------|------|------|
| 52-57 | [图像坐标系] 图像右侧眼睛外轮廓 | 后置时对应被拍摄者右眼 |
| 58-63 | [图像坐标系] 图像左侧眼睛外轮廓 | 后置时对应被拍摄者左眼 |
```

---

## 4. 实施指南

### 4.1 新增文件

#### 规范文档
- `docs/07-STANDARDS/COORDINATE_SYSTEM.md` - 坐标系规范详细说明
- `docs/02-ARCHITECTURE/ADR/ADR-003-coordinate-system-management.md` - 本技术决策文档

#### 自动化检测脚本
- `scripts/check-coordinate-annotation.sh` - 检测代码中的模糊描述
- `scripts/check-doc-coordinate-annotation.sh` - 检测文档中的模糊描述

### 4.2 使用检测脚本

```bash
# 检测代码
cd /Users/guoshuai/AndroidStudioProjects/PoLang
scripts/check-coordinate-annotation.sh

# 检测文档
scripts/check-doc-coordinate-annotation.sh
```

**预期输出**：

```
🔍 检查未标注坐标系的注释...

搜索: 左眼
搜索: 右眼
搜索: 左眉
搜索: 右眉
搜索: 左侧脸
搜索: 右侧脸

✅ 坐标系标注检查通过
```

如果发现问题：

```
❌ 发现未标注坐标系的注释:
androidApp/src/main/java/com/picme/example.kt:42: // 左眼中心点

❌ 发现 1 处问题，请参考 docs/07-STANDARDS/COORDINATE_SYSTEM.md 修复
```

### 4.3 Git Pre-commit Hook（可选）

将以下内容保存为 `.git/hooks/pre-commit`：

```bash
#!/bin/bash
# .git/hooks/pre-commit
# 提交前自动检查坐标系标注

echo "🔍 运行坐标系标注检查..."

# 检查暂存的文件
STAGED_FILES=$(git diff --cached --name-only --diff-filter=ACM | grep -E "\.(kt|md)$")

if [ -z "$STAGED_FILES" ]; then
    exit 0
fi

ERRORS=0

for file in $STAGED_FILES; do
    # 检查 Kotlin 文件
    if [[ $file == *.kt ]]; then
        FUZZY_COMMENTS=$(grep -n "左眼\|右眼\|左眉\|右眉" "$file" | \
            grep -v "\[图像坐标系\]" | \
            grep -v "\[人脸坐标系\]" | \
            grep -v "imageLeft" | \
            grep -v "imageRight")
        
        if [ ! -z "$FUZZY_COMMENTS" ]; then
            echo "❌ $file 中存在未标注坐标系的注释:"
            echo "$FUZZY_COMMENTS"
            ERRORS=$((ERRORS + 1))
        fi
    fi
    
    # 检查 Markdown 文件
    if [[ $file == *.md ]]; then
        FUZZY_DOCS=$(grep -n "左眼\|右眼\|左眉\|右眉" "$file" | \
            grep -v "\[图像坐标系\]" | \
            grep -v "\[人脸坐标系\]" | \
            grep -v "图像左侧" | \
            grep -v "图像右侧")
        
        if [ ! -z "$FUZZY_DOCS" ]; then
            echo "❌ $file 中存在未标注坐标系的描述:"
            echo "$FUZZY_DOCS"
            ERRORS=$((ERRORS + 1))
        fi
    fi
done

if [ $ERRORS -gt 0 ]; then
    echo "\n❌ 发现 $ERRORS 个文件存在坐标系标注问题"
    echo "请参考 docs/07-STANDARDS/COORDINATE_SYSTEM.md 修复"
    exit 1
fi

echo "✅ 坐标系标注检查通过"
exit 0
```

启用：

```bash
chmod +x .git/hooks/pre-commit
```

---

## 5. 迁移计划

### 5.1 阶段划分

#### 阶段 1: 规范发布（第 1 周）

**目标**：团队熟悉新规范

**任务**：
- [ ] 组织团队培训，讲解新规范
- [ ] 更新 Onboarding 文档
- [ ] 更新 Code Review Checklist
- [ ] 运行检测脚本，了解现状

**产出**：
- 团队成员理解规范要求
- 现状报告（有多少文件需要修改）

#### 阶段 2: 高风险代码重构（第 2-3 周）

**目标**：修复最容易出错的代码

**优先级**：
1. 渲染引擎层（BeautyRenderer、Shader）
2. 人脸检测适配层（MnnLandmarkAdapter、MediaPipe468Adapter）
3. 坐标转换工具类

**任务**：
- [ ] 统一渲染层使用图像坐标系
- [ ] 添加明确的坐标转换函数
- [ ] 修复已知的坐标系混用 Bug
- [ ] 补充单元测试

**产出**：
- 核心模块符合规范
- 关键 Bug 已修复

#### 阶段 3: 全面清理（第 4-6 周）

**目标**：所有代码符合规范

**任务**：
- [ ] 修订所有 Kotlin 文件的注释
- [ ] 修订所有 Markdown 文档的描述
- [ ] 变量命名规范化
- [ ] 启用 Pre-commit Hook

**产出**：
- 100% 代码符合规范
- 自动化检测集成到 CI/CD

#### 阶段 4: 持续维护（长期）

**目标**：保持规范执行

**任务**：
- [ ] Code Review 严格检查
- [ ] 定期运行检测脚本
- [ ] 收集反馈优化规范
- [ ] 新人培训常态化

---

### 5.2 迁移示例

#### 示例 1: 修复混用问题

**修改前**：

```kotlin
class FaceProcessor {
    fun applyMakeup() {
        val leftEye = getLeftEye()          // 歧义！
        val rightEye = getRightEye()        // 歧义！
        
        // 可能混用了不同坐标系
        val center = (leftEye + rightEye) / 2
    }
}
```

**修改后**：

```kotlin
class FaceProcessor {
    /**
     * [图像坐标系] 应用妆容效果
     */
    fun applyMakeup() {
        // 统一使用图像坐标系
        val imageLeftEye = getImageLeftEye()
        val imageRightEye = getImageRightEye()
        
        val center = (imageLeftEye + imageRightEye) / 2
        
        // 传递给 Shader
        shader.setUniform("u_eyeCenter", center.toNDC())
    }
}
```

#### 示例 2: 添加转换函数

**修改前**：

```kotlin
class BeautyController {
    fun process(userLandmarks: List<Point>) {
        // 直接使用，没有明确转换
        renderer.render(userLandmarks)  // ❌ 坐标系不明确
    }
}
```

**修改后**：

```kotlin
class BeautyController {
    fun process(
        userLandmarks: List<Point>,  // [人脸坐标系] 输入
        isFrontCamera: Boolean
    ) {
        // 明确转换到图像坐标系
        val imageLandmarks = convertUserToImageCoordinates(
            userLandmarks,
            isFrontCamera
        )
        
        // 渲染层使用图像坐标系
        renderer.render(imageLandmarks)  // ✅ 坐标系明确
    }
}
```

#### 示例 3: 文档修订

**修改前**：

```markdown
## 人脸关键点索引

| 索引 | 部位 |
|------|------|
| 52-57 | 右眼外轮廓 |
| 58-63 | 左眼外轮廓 |
```

**修改后**：

```markdown
## 人脸关键点索引

> **坐标系说明**：本节所有坐标均基于**图像坐标系**（观察者视角）。

| 索引 | 部位 | 说明 |
|------|------|------|
| 52-57 | [图像坐标系] 图像右侧眼睛外轮廓 | 后置时对应被拍摄者右眼 |
| 58-63 | [图像坐标系] 图像左侧眼睛外轮廓 | 后置时对应被拍摄者左眼 |
```

---

## 6. 验收标准

### 6.1 文档审查

- [ ] **所有"左/右"描述都明确标注了坐标系**（`[图像坐标系]` 或 `[人脸坐标系]`）
- [ ] **文档章节开头添加了坐标系声明**（使用引用块 `>` 格式）
- [ ] 没有使用模糊的"左眼"、"右眼"等术语（必须带坐标系前缀）
- [ ] 前后置摄像头的差异有明确说明
- [ ] 添加了坐标系图示

### 6.2 代码审查

- [ ] **所有变量名包含坐标系前缀**（`imageLeft` / `imageRight` / `userLeft` / `userRight`）
- [ ] **所有注释明确标注坐标系**（`// [图像坐标系]` 或 `// [人脸坐标系]`）
- [ ] **函数注释包含坐标系说明**（KDoc 第一行必须标注）
- [ ] **同一作用域内未混用两种坐标系**（⚠️ 关键检查点！）
- [ ] **跨坐标系转换有明确的转换函数**
- [ ] 没有硬编码的 "left/right" 字符串用于 UI 显示
- [ ] 枚举定义中每个值都标注了坐标系

### 6.3 测试验证

- [ ] 前置摄像头：图像左侧显示的是被拍摄者的右眼
- [ ] 后置摄像头：图像左侧显示的是被拍摄者的左眼
- [ ] 镜像切换后，UI 标注位置正确
- [ ] 关键点调试浮层显示的位置与预期一致
- [ ] 瘦脸效果在前后置下位置一致
- [ ] 妆容贴附准确无偏移

### 6.4 自动化检测

```bash
# 运行检测脚本，必须全部通过
scripts/check-coordinate-annotation.sh    # ✅ 通过
scripts/check-doc-coordinate-annotation.sh # ✅ 通过
```

---

## 7. 相关文档

### 7.1 规范文档

- [COORDINATE_SYSTEM.md](../../07-STANDARDS/COORDINATE_SYSTEM.md) - 坐标系规范详细说明
- [BEAUTY_ENGINE_TECH_SPEC.md](../../03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 大美丽引擎技术规范（含相机预览比例与坐标转换）
- InsightFace 106 映射文档（已移除，InsightFace ONNX 路径已于 2026-05 删除）

### 7.2 技术文档

- [BEAUTY_ENGINE_TECH_SPEC.md](../../03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 大美丽引擎技术规范（含渲染链路、帧同步美妆与容灾降级恢复）

### 7.3 其他 ADR

- [ADR-001: Beauty Engine Architecture](./ADR-001-beauty-engine-architecture.md)
- [ADR-002: OpenGL Offscreen Unified Pipeline](./ADR-002-opengl-offscreen-unified-pipeline.md)

---

## 8. 附录

### 8.1 常见问题 FAQ

#### Q1: 为什么不允许只使用一种坐标系？

**A**: 因为不同层级有不同需求：
- UI 层使用人脸坐标系更符合用户直觉（用户说"左眼"指的是被拍摄者左眼）
- 渲染层必须使用图像坐标系（GPU Shader、OpenGL 的技术标准）
- 强制统一会导致某一层表达不自然

#### Q2: 如何判断应该使用哪种坐标系？

**A**: 参考分层架构：
- UI 展示 → 人脸坐标系
- 业务逻辑 → 图像坐标系（推荐）或人脸坐标系（需团队统一）
- 渲染引擎 → 必须图像坐标系
- 算法处理 → 必须图像坐标系

#### Q3: 转换函数的性能开销大吗？

**A**: 很小：
- 只是简单的 `x = 1 - x` 运算
- 只在边界处调用一次
- 不会在渲染循环中频繁调用

#### Q4: 如何处理第三方 SDK 的坐标系？

**A**: 在适配层统一转换。当前代码中，MediaPipe / MNN 的坐标转换逻辑内嵌在检测适配层（如 `FaceDetectionManager` 及相关检测器实现）中，未提取为独立的通用适配器类。以下示例展示了理想的适配层设计（InsightFace 路径已于 2026-05 移除；NCNN 已于 2026-08 整删）：

#### Q5: Code Review 时如何快速发现混用问题？

**A**: 检查以下几点：
1. 同一个函数内的变量是否都有相同的坐标系前缀？
2. 是否有 `imageLeft` 和 `userRight` 同时出现？
3. 是否有坐标系转换但没有调用转换函数？

### 8.2 变更记录

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|---------|------|
| 2026-05-03 | v1.0 | 初始版本，确立"允许两种坐标系但严禁混用"原则 | Lingma |
| 2026-06-04 | v1.1 | 修正：明确 `EyePosition` 枚举和坐标转换函数为规范示例，未在代码中实际落地；补充实际代码中的坐标处理现状说明 | RD |
| 2026-08-03 | v1.2 | NCNN 已整删，Q4 第三方 SDK 坐标系说明改为 MediaPipe / MNN | AI Agent |
| 2026-08-23 | v1.3 | §2.3 表对齐实际类名（`MediaPipeFaceDetector`/`MnnLandmarkDetector`），移除 NCNN/OpenCV 残留 | AI Agent |

### 8.3 参考资料

- Android Camera API 文档
- MediaPipe Face Mesh 文档
- ML Kit Face Detection 文档
- ARKit Face Tracking 文档
- OpenCV 坐标系说明

---

**文档结束**
