# 人脸坐标系与左右命名规范

**版本**: 1.0  
**创建日期**: 2026-05-03  
**状态**: ✅ 强制执行标准

---

## 📋 问题背景

在 PoLang 项目中，"左/右"的描述存在歧义，主要体现在：

1. **基于图像的左右**（Image Space）：从观察者视角看屏幕，左侧 = x 坐标小的位置
2. **基于人脸的左右**（Face Space）：从被拍摄者视角，其左手边 = 图像右侧（前置摄像头镜像后）

这种歧义导致：
- 代码注释混乱（如 `// 右眉，画面左侧`）
- 文档描述不一致
- 新成员理解困难
- Bug 修复时容易搞反方向

---

## ✅ 统一标准

### 核心原则：**允许两种坐标系，但严禁混用**

#### 强制要求

⚠️ **从本规范生效起，所有文档和代码必须满足以下要求**：

1. **明确标注坐标系类型**（`[图像坐标系]` 或 `[人脸坐标系]`）
2. **同一作用域内禁止混用两种坐标系**
3. **跨坐标系转换必须有明确的转换函数**

```kotlin
// ❌ 错误：混用两种坐标系
fun processFace() {
    val imageLeftEye = getImageLeftEye()      // [图像坐标系]
    val userRightEye = getUserRightEye()      // [人脸坐标系]
    val distance = calculateDistance(imageLeftEye, userRightEye)  // ❌ 混用！
}

// ✅ 正确：统一使用一种坐标系
fun processFace() {
    // 方案 A：统一使用图像坐标系（推荐）
    val imageLeftEye = getImageLeftEye()
    val imageRightEye = getImageRightEye()
    val distance = calculateDistance(imageLeftEye, imageRightEye)
    
    // 方案 B：统一使用人脸坐标系（需转换）
    val userLeftEye = getUserLeftEye(isFrontCamera)
    val userRightEye = getUserRightEye(isFrontCamera)
    val distance = calculateDistance(userLeftEye, userRightEye)
}

// ✅ 正确：跨坐标系转换有明确函数
fun convertUserToImageCoordinates(
    userLandmarks: List<Point>,
    isFrontCamera: Boolean
): List<Point> {
    return userLandmarks.map { point ->
        if (isFrontCamera) {
            Point(1.0f - point.x, point.y)  // 前置镜像
        } else {
            point
        }
    }
}
```

#### 坐标系定义

#### 坐标系定义

| 术语 | 定义 | 适用场景 | 示例 |
|------|------|---------|------|
| **图像坐标系** | 基于图像边界的坐标系统<br>• 原点：左上角 (0, 0)<br>• x 轴：向右增加<br>• y 轴：向下增加 | • GPU Shader 渲染<br>• OpenGL 纹理映射<br>• 图像处理算法<br>• 与显示系统对接 | `imageLeftEye`<br>`imageRightEyebrow` |
| **人脸坐标系** | 基于被拍摄者身体的坐标系统<br>• 左/右：以被拍摄者为参照<br>• 需要区分前后置摄像头 | • 业务逻辑层<br>• UI 文案显示<br>• 用户交互提示<br>• 与外部 SDK 对接 | `userLeftEye`<br>`faceRightCheek` |

**关键理解**：
- ✅ **两种坐标系都允许使用**
- ⚠️ **但必须在边界处明确转换**
- ❌ **严禁在同一作用域内混用**

#### 禁止使用的术语

❌ **禁止**：
- "左眼"、"右眼"（未指明是图像还是人脸视角）
- "左侧脸"、"右侧脸"
- "左眉毛"、"右眉毛"

✅ **必须使用**：
- "图像左侧的眼睛"（明确说明是图像坐标系）
- "图像右侧的眼睛"
- "图像左侧的脸部轮廓"

---

## 🎯 具体规则

### 规则 0: 坐标系选择策略

**项目推荐策略**：

![坐标系分层架构](assets/diagrams/coord-layered-architecture.png)

**选择建议**：

| 层级 | 推荐坐标系 | 原因 |
|------|-----------|------|
| **UI 展示层** | 人脸坐标系 | 用户更容易理解“左眼”、“右眼” |
| **业务逻辑层** | 图像坐标系（推荐）或人脸坐标系 | 根据团队习惯统一选择 |
| **渲染引擎层** | **必须**使用图像坐标系 | GPU Shader、OpenGL 原生支持 |
| **算法处理层** | **必须**使用图像坐标系 | OpenCV、MediaPipe 等库的标准 |

**重要原则**：
- ✅ 每个模块内部必须统一使用一种坐标系
- ✅ 模块间通过明确的转换函数交接
- ❌ 禁止在同一个函数/类中混用两种坐标系

---

### 规则 1: 关键点命名

**标准格式**：`{坐标系前缀}_{位置}_{部位}`

**坐标系前缀**：
- `image_` - 图像坐标系（观察者视角，x 向右增加）
- `user_` 或 `face_` - 人脸坐标系（被拍摄者视角）

| 正确命名 | 错误命名 | 说明 |
|---------|---------|------|
| `imageLeftEye` | `leftEye` / `userLeftEye` | 明确指出是图像左侧 |
| `imageRightEye` | `rightEye` | 明确指出是图像右侧 |
| `userLeftEye` | `leftEye` | 明确指出是被拍摄者左眼 |
| `imageLeftEyebrow` | `leftEyebrow` | 图像左侧的眉毛 |
| `faceCenter` | - | 脸部中心（无歧义，可不加前缀） |
| `noseTip` | - | 鼻尖（对称器官，无歧义） |

### 规则 2: 文档描述

**强制格式**：`[{坐标系}] {位置描述}`

```markdown
❌ 错误（未标注坐标系）：
- "左眼外眼角"
- "右眉眉头"
- "下巴左侧"

✅ 正确（明确标注）：
- "[图像坐标系] 图像左侧眼睛的外眼角"
- "[图像坐标系] 图像右侧眉毛的眉头"
- "[人脸坐标系] 被拍摄者左脸的下巴轮廓"
- "[图像坐标系] 图像左侧的下巴轮廓（前置时对应被拍摄者右脸）"
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

### 规则 3: 代码注释

**强制格式**：`// [{坐标系}] {描述}`

```kotlin
// ❌ 错误：未标注坐标系
// 右眼外轮廓（画面左侧）
val rightEyeContour = landmarks.slice(52..57)

// ✅ 正确：明确标注坐标系
// [图像坐标系] 图像右侧的眼睛外轮廓（后置时对应被拍摄者右眼）
val imageRightEyeContour = landmarks.slice(52..57)

// ✅ 正确：语义标注（需说明转换关系）
// [人脸坐标系] 被拍摄者右眼（前置时位于图像右侧，后置时位于图像左侧）
val userRightEye = if (isFrontCamera) {
    landmarks[IMAGE_RIGHT_EYE_INDICES]  // 前置：图像右侧
} else {
    landmarks[IMAGE_LEFT_EYE_INDICES]   // 后置：图像左侧
}
```

**函数注释必须包含坐标系说明**：

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

### 规则 4: 变量命名

**Kotlin 命名规范**：

```kotlin
// ❌ 错误：未标注坐标系，存在歧义
val leftEye = face.leftEye
val rightEye = face.rightEye

// ✅ 正确：图像坐标系（推荐，项目统一标准）
val imageLeftEye = face.getImageLeftEye()    // 图像左侧的眼睛
val imageRightEye = face.getImageRightEye()  // 图像右侧的眼睛

// ✅ 可接受：人脸坐标系（需明确标注，谨慎使用）
val userLeftEye = face.getUserLeftEye(isFrontCamera)    // 被拍摄者左眼
val userRightEye = face.getUserRightEye(isFrontCamera)  // 被拍摄者右眼
```

**枚举定义示例**：

```kotlin
/**
 * 眼睛位置枚举（明确标注坐标系）
 */
enum class EyePosition {
    /** [图像坐标系] 图像左侧的眼睛（x 坐标较小） */
    IMAGE_LEFT,
    
    /** [图像坐标系] 图像右侧的眼睛（x 坐标较大） */
    IMAGE_RIGHT,
    
    /** [人脸坐标系] 被拍摄者左眼（前置时对应 IMAGE_RIGHT） */
    USER_LEFT,
    
    /** [人脸坐标系] 被拍摄者右眼（前置时对应 IMAGE_LEFT） */
    USER_RIGHT
}
```

### 规则 5: 禁止混用检查

**同一作用域内禁止混用两种坐标系**：

```kotlin
// ❌ 错误：混用图像坐标系和人脸坐标系
class FaceProcessor {
    fun process() {
        val imageLeftEye = landmarks[IMAGE_LEFT_EYE]     // 图像坐标系
        val userRightEye = getUserRightEye()              // 人脸坐标系
        
        // ❌ 错误：两种坐标系混合计算
        val center = (imageLeftEye + userRightEye) / 2
    }
}

// ✅ 正确：统一使用图像坐标系
class FaceProcessor {
    fun process() {
        val imageLeftEye = landmarks[IMAGE_LEFT_EYE]
        val imageRightEye = landmarks[IMAGE_RIGHT_EYE]
        
        // ✅ 正确：同一种坐标系计算
        val center = (imageLeftEye + imageRightEye) / 2
    }
}

// ✅ 正确：如需转换，使用明确的转换函数
class FaceProcessor {
    fun process() {
        // 从人脸坐标系转换为图像坐标系
        val userLandmarks = getUserLandmarks()
        val imageLandmarks = convertUserToImageCoordinates(
            userLandmarks, 
            isFrontCamera
        )
        
        // 统一使用图像坐标系处理
        val imageLeftEye = imageLandmarks[IMAGE_LEFT_EYE]
        val imageRightEye = imageLandmarks[IMAGE_RIGHT_EYE]
        val center = (imageLeftEye + imageRightEye) / 2
    }
}
```

**Code Review 检查点**：
- [ ] 同一个函数内是否只使用一种坐标系？
- [ ] 如有坐标系转换，是否有明确的转换函数？
- [ ] 变量命名是否清晰体现坐标系类型？
- [ ] 注释是否说明了坐标系转换的原因？

---

## 📊 坐标系对照表

### 前置摄像头（镜像模式）

![前置摄像头 · 镜像显示](assets/diagrams/coord-eyes-front.png)

### 后置摄像头（非镜像模式）

![后置摄像头 · 无镜像](assets/diagrams/coord-eyes-back.png)

---

## 🔧 迁移指南

### Step 1: 识别需要修改的位置

搜索以下模式：

```bash
# 搜索模糊的左右描述
grep -r "左眼\|右眼\|左眉\|右眉" docs/ --include="*.md"
grep -r "leftEye\|rightEye" androidApp/src/ --include="*.kt" | grep -v "imageLeft\|imageRight"
```

### Step 2: 按优先级修改

#### 优先级 1: 技术文档（最高）

**文件列表**：
- `docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md`（含相机预览比例与坐标转换、人脸关键点使用）
- `skills/av-gl-expert/SKILL.md`

**修改示例**：

```markdown
# 修改前
| 33 | 右眉眉头（画面左侧） | 43 |
| 38 | 左眉眉头（画面右侧） | 101 |

# 修改后
| 33 | 图像右侧眉毛的眉头（被拍摄者右眉） | 43 |
| 38 | 图像左侧眉毛的眉头（被拍摄者左眉） | 101 |
```

#### 优先级 2: 代码注释

**文件列表**：
- `engines/beauty-engine/src/main/java/com/mamba/picme/beauty/render/BeautyRenderer.kt`
- `androidApp/src/main/java/com/mamba/picme/core/image/ImageProcessor.kt`

**修改示例**：

```kotlin
// 修改前
// 右眼外轮廓：统一 52-57（画面左侧）
val rightEyeContour = unifiedLandmarks.slice(52..57)

// 修改后
// 图像左侧的眼睛外轮廓（对应被拍摄者右眼）：统一索引 52-57
val imageLeftEyeContour = unifiedLandmarks.slice(52..57)
```

#### 优先级 3: 变量命名（谨慎执行）

**注意**：变量命名修改影响范围大，建议分阶段进行：

```kotlin
// Phase 1: 添加别名（向后兼容）
@Deprecated("使用 imageLeftEye 替代", ReplaceWith("imageLeftEye"))
val leftEye = imageLeftEye

// Phase 2: 逐步迁移调用方
// Phase 3: 移除旧命名
```

---

## 📝 最佳实践

### 1. 始终明确坐标系

```kotlin
/**
 * 获取图像左侧眼睛的中心点
 * 
 * @return 图像坐标系中的点（原点在左上角，x 向右增加，y 向下增加）
 */
fun getImageLeftEyeCenter(): Point {
    // ...
}
```

### 2. 使用枚举避免歧义

```kotlin
enum class EyePosition {
    IMAGE_LEFT,   // 图像左侧的眼睛
    IMAGE_RIGHT   // 图像右侧的眼睛
}

fun getEyeCenter(position: EyePosition): Point {
    return when (position) {
        EyePosition.IMAGE_LEFT -> landmarks[IMAGE_LEFT_EYE_INDEX]
        EyePosition.IMAGE_RIGHT -> landmarks[IMAGE_RIGHT_EYE_INDEX]
    }
}
```

### 3. 在函数名中体现坐标系

```kotlin
// ❌ 不明确
fun getLeftEye(): Point

// ✅ 明确
fun getImageLeftEye(): Point
fun getFaceSpaceLeftEye(): Point  // 如果确实需要人脸坐标系
```

### 4. 文档中添加坐标系图示

```markdown
## 坐标系说明

本文档中所有坐标均基于**图像坐标系**：

```
(0, 0) ───────────→ x 增加
  │
  │    图像左侧       图像右侧
  │    (x 小)        (x 大)
  │
  ↓ y 增加
```
```

---

## 🚨 常见陷阱

### 陷阱 1: 混淆前后置摄像头

```kotlin
// ❌ 错误：假设前置和后置的"左眼"相同
val leftEye = if (isFrontCamera) {
    face.leftEye  // 前置：这是图像右侧！
} else {
    face.leftEye  // 后置：这是图像左侧
}

// ✅ 正确：统一使用图像坐标系
val imageLeftEye = face.getImageLeftEye()  // 永远是图像左侧
```

### 陷阱 2: 镜像翻转后忘记更新命名

```kotlin
// ❌ 错误：镜像后仍然叫 leftEye
val mirroredLeftEye = mirror(face.leftEye)  // 实际已变成图像右侧

// ✅ 正确：镜像后重新评估位置
val imageLeftEye = if (isMirrored) {
    face.originalRightEye  // 镜像后，原右眼变成图像左侧
} else {
    face.originalLeftEye
}
```

### 陷阱 3: 文档与代码不一致

```markdown
# 文档说：
"左眼使用索引 58-63"

# 代码却是：
val imageLeftEye = landmarks[58..63]  // 实际是图像左侧

# 问题：文档的"左眼"指什么？
```

**解决方案**：文档必须与代码使用相同的术语。

---

## ✅ 验收检查清单

### 🚨 强制要求（必须全部满足）

#### 文档审查

- [ ] **所有“左/右”描述都明确标注了坐标系**（`[图像坐标系]` 或 `[人脸坐标系]`）
- [ ] **文档章节开头添加了坐标系声明**（使用引用块 `>` 格式）
- [ ] 没有使用模糊的“左眼”、“右眼”等术语（必须带坐标系前缀）
- [ ] 前后置摄像头的差异有明确说明
- [ ] 添加了坐标系图示

#### 代码审查

- [ ] **所有变量名包含坐标系前缀**（`imageLeft` / `imageRight` / `userLeft` / `userRight`）
- [ ] **所有注释明确标注坐标系**（`// [图像坐标系]` 或 `// [人脸坐标系]`）
- [ ] **函数注释包含坐标系说明**（KDoc 第一行必须标注）
- [ ] **同一作用域内未混用两种坐标系**（关键检查点！）
- [ ] 跨坐标系转换有明确的转换函数
- [ ] 没有硬编码的 "left/right" 字符串用于 UI 显示
- [ ] 枚举定义中每个值都标注了坐标系

#### 测试验证

- [ ] 前置摄像头：图像左侧显示的是被拍摄者的右眼
- [ ] 后置摄像头：图像左侧显示的是被拍摄者的左眼
- [ ] 镜像切换后，UI 标注位置正确
- [ ] 关键点调试浮层显示的位置与预期一致

---

### 🔍 自动化检测脚本

#### 检测未标注坐标系的注释

```bash
#!/bin/bash
# scripts/check-coordinate-annotation.sh
# 检测代码中未标注坐标系的左右描述

echo "🔍 检查未标注坐标系的注释..."

# 搜索常见的模糊描述
FUZZY_PATTERNS=(
    "左眼"
    "右眼"
    "左眉"
    "右眉"
    "左侧脸"
    "右侧脸"
)

for pattern in "${FUZZY_PATTERNS[@]}"; do
    echo "\n搜索: $pattern"
    grep -rn "$pattern" androidApp/src/ --include="*.kt" | grep -v "\[图像坐标系\]" | grep -v "\[人脸坐标系\]" | grep -v "imageLeft" | grep -v "imageRight" | grep -v "userLeft" | grep -v "userRight"
done

echo "\n✅ 检查完成"
```

#### 检测未标注坐标系的文档

```bash
#!/bin/bash
# scripts/check-doc-coordinate-annotation.sh
# 检测文档中未标注坐标系的左右描述

echo "🔍 检查文档中的坐标系标注..."

# 搜索 Markdown 文件中的模糊描述
grep -rn "左眼\|右眼\|左眉\|右眉" docs/ --include="*.md" | \
    grep -v "\[图像坐标系\]" | \
    grep -v "\[人脸坐标系\]" | \
    grep -v "图像左侧" | \
    grep -v "图像右侧" | \
    grep -v "被拍摄者"

echo "\n✅ 检查完成"
```

#### Git Pre-commit Hook（可选）

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

---

## 📚 参考资源

### 内部文档
- [BEAUTY_ENGINE_TECH_SPEC.md](../03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md) - 相机预览比例、坐标转换与人脸关键点使用

### 外部资源
- [OpenCV Coordinate System](https://docs.opencv.org/master/d2/d44/tutorial_py_image_basic_ops.html)
- [MediaPipe Face Mesh Coordinates](https://google.github.io/mediapipe/solutions/face_mesh)

---

## 🔄 维护策略

### 定期审计

每季度执行一次全文档扫描：

```bash
# 查找潜在的歧义描述
find docs/ -name "*.md" -exec grep -l "左眼\|右眼\|左眉\|右眉" {} \;

# 检查代码注释
find androidApp/src/ -name "*.kt" -exec grep -n "//.*左眼\|//.*右眼" {} +
```

### 新人培训

在新成员 onboarding 文档中加入本规范：

```markdown
## PoLang 开发规范

### 坐标系与命名
- 阅读 [人脸坐标系与左右命名规范](../02-ARCHITECTURE/ADR/ADR-003-coordinate-system-management.md)
- 理解图像坐标系 vs 人脸坐标系的区别
- 掌握前置/后置摄像头的镜像差异
```

### CI/CD 集成（可选）

添加 lint 规则检测模糊命名：

```kotlin
// custom-lint-rules/AmbiguousNamingDetector.kt
class AmbiguousNamingDetector : Detector(), Detector.UastScanner {
    override fun getApplicableUastTypes() = listOf<UClass>(
        UVariableDeclaration::class.java
    )
    
    override fun createUastHandler(context: JavaContext) = object : UElementHandler() {
        override fun visitVariable(node: UVariableDeclaration) {
            val name = node.name
            if (name.matches(Regex("^(left|right)(Eye|Eyebrow|Cheek)$"))) {
                context.report(
                    issue = AMBIGUOUS_NAMING,
                    location = context.getLocation(node),
                    message = "变量名 '$name' 有歧义，请使用 'imageLeft' 或 'imageRight' 前缀"
                )
            }
        }
    }
}
```

---

**批准人**: 项目开发者  
**生效日期**: 2026-05-03  
**下次审查**: 2026-11-03
