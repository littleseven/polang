# Core 模块技术实现规范 (Core Infrastructure)

> **边界声明（Boundary Statement）**
> - 本文档仅承载本模块的实现细节（架构、代码约束、检查清单）。
> - 产品目标与验收口径以 `PRODUCT.md` 为准；交互流程与体验规则以 `docs/01-PRODUCT/FEATURES.md` 为准。
> - 顶层治理规则（角色协作、全局红线、文档流程）以根目录 `AGENTS.md` 为准。
> - 禁止将模块级实现细节回填到顶层 `AGENTS.md`；跨模块或专项技术内容应下沉到对应模块文档或 `docs/*_TECH_SPEC.md`。

**模块定位**：提供 PoLang 的核心组件（DesignSystem、图像处理、工具类）统一、高效的基础能力。

**主要维护者**：项目开发者

**阅读对象**：RD、AI Agent

## 1. 核心产品逻辑 (Core Product Logic)

- **[DESIGN_CONSISTENCY] 设计一致性**：所有 UI 组件必须遵循 HyperOS 视觉风格（大圆角、毛玻璃、流体动效）
- **[PERF] 性能约束**：滤镜处理时间 < 200ms，图片加载流畅无卡顿
- **[I18N] 国际化支持**：所有用户可见字符串必须使用 StringResource，支持多语言

## 2. 技术实现规范 (Technical Implementation)

### 2.1 Design System 实现

**主题系统规范**：
- **PoLangTheme 颜色方案**：
  - 深色模式：primary 使用科技蓝 (#00E5FF)，surface 使用深灰 (#1E1E1E)
  - 浅色模式：对应调整明度，保持对比度符合 WCAG 标准
- **毛玻璃效果**：通过 `Modifier.blur(20.dp)`配合半透明白色背景，圆角统一为 28dp

**通用组件约定**：
- 卡片样式统一圆角 24dp、背景 `surface.copy(alpha = 0.8f)`、阴影高度可调
- 可点击交互带按压缩放反馈（spring 动画）

> 注：早期文档提到的 `BlurCard`/`FluidButton` 具名组件未落地为独立 Composable，以上为样式约定而非组件 API。

### 2.2 图像处理核心

#### 架构分层说明（重要）

> `core/image` 层仅处理**拍照后**的静态 Bitmap 后处理，与实时预览渲染链路完全隔离。
> 实时预览美颜（GPU 路径）由独立的 `beauty-engine` 模块承载，app 层通过 `BeautyPreviewEngine` 接口调用。

**两条处理路径**：

| 路径 | 使用场景 | 实现位置 | 技术方案 | 状态 |
|---|---|---|---|---|
| **实时预览（GPU）** | 相机预览帧 | `beauty-engine` 模块 | OpenGL ES Shader | ✅ 生产可用 |
| **拍照后处理（GPU）** | 保存前静态 Bitmap | `beauty-engine` 模块 | OpenGL ES 离屏渲染（`PhotoProcessorImpl`） | ✅ 2026-05 已落地（标准路径） |
| **拍照后处理（CPU Fallback）** | GPU 路径失败时降级 | `core/image/` | Android Canvas + ColorMatrix | ⚠️ 降级备用 |

**`core/image/` 当前文件结构**：
```
core/image/
├── GpuBeautyProcessor.kt            # BeautyProcessor CPU 实现（Canvas + ColorMatrix）
├── ImageProcessor.kt                # 拍照/录像接口 + ImageProcessorImpl 实现
├── ThumbnailCache.kt                # 双级缩略图缓存（L1 LRU 360px 位图 + L2 JPEG 磁盘）
├── ThumbnailCacheFetcher.kt         # Coil Fetcher：content:// 且宽度 ≤ THUMBNAIL_SIZE_PX 才拦截走缓存
├── BitmapSampling.kt                # 按目标尺寸降采样解码工具
├── FaceAwareAlignment.kt            # 人脸感知纵向对齐（faceFocusY → ContentScale.Crop alignment）
└── CoilConfig.kt                    # Coil 全局图片加载配置
```

> **⚠️ 阈值联动约束**：`ThumbnailCacheFetcher` 的拦截阈值必须与 `ThumbnailCache.THUMBNAIL_SIZE_PX`（360px）保持一致（直接引用同一常量，禁止另写魔法数）——拦截更大请求会返回 360px 小图放大显示导致模糊（2026-08 人物页封面糊图的根因）。

> **⚠️ 2026-05 架构下沉**：以下文件已从 `core/image/` 迁移至 `beauty-engine/api/`，实现美颜领域模型与引擎内聚：
> - `BeautySettings.kt`、`BeautyParams.kt`、`BeautyParamsConverter.kt`
> - `FilterType.kt`、`StyleFilter.kt`
> - `Face.kt`、`FaceContour.kt`、`FaceLandmark.kt`
> - `BeautyProcessor.kt`（接口契约）

**⚠️ 已清理的冗余文件（2026-04）**：
以下文件原属 `core/image/gl/` 下，与 `beauty-engine` 模块重复，已删除：
`BeautyShaders.kt`、`BeautyRenderer.kt`、`BeautyPreviewView.kt`、`CameraPreviewRenderer.kt`、
`GLRenderer.kt`、`EGLCore.kt`、`WindowSurface.kt`、`ShaderProgram.kt`

**BeautyParamsConverter**（已迁移至 `beauty-engine/api/BeautyParamsConverter.kt`）：
- 将 `BeautySettings`（UI 原始值）转换为 `BeautyParams`（Shader 归一化值）
- 负责归一化映射（UI 原始值 → [0, 1] 或 [-1, 1]）
- 将 `FilterType` 转换为 4×5 `colorMatrix`（FloatArray），传入大美丽引擎 Shader
- `FilterType.NONE` 时 `colorMatrix = null`，Shader 直通输出

**GpuBeautyProcessor（CPU 路径）注意事项**：
- 磨皮：原 RenderScript API 已废弃（Android API 31+），现改为 Canvas + ColorMatrix 亮度近似
- 实时预览磨皮的双边滤波 Shader 效果仍在 `beauty-engine` 中保留，CPU 路径为轻量兼容实现
- 日志 TAG：`PoLang:ImageProc`

**滤镜 ColorMatrix（`FilterType.toAndroidColorMatrix()`）**：
- 每个 `FilterType` 枚举值实现 `toAndroidColorMatrix(): android.graphics.ColorMatrix` 方法
- 矩阵值为 `android.graphics.ColorMatrix` 标准 4×5 行主序布局（20 个 Float）
- LEICA_CLASSIC 示例：饱和度 0.9、对比度微调 +0.05、暗部轻微压缩

**图片加载优化**：
- **Coil 全局配置**：
  - 内存缓存：占可用内存 25%，使用 MemoryCache.Builder
  - 磁盘缓存：占磁盘空间 2%，目录为 `cache/image_cache`
  - 忽略 HTTP 缓存头：本地应用设置 `respectCacheHeaders(false)`

### 2.3 工具类与扩展函数

**Logger 统一日志规范**：
- **结构化 Tag 设计**：基础 Tag 为 "PoLang"，模块 Tag 格式为`PoLang:[Module]`
- **日志缓冲机制**：维护最多 500 条内存缓存（FIFO），支持调试浮窗实时检索
- **使用示例**：
  - `Logger.d("Camera", "Capture triggered")` - 记录相机快门触发
  - `Logger.e("Gallery", "Failed to load image", exception)` - 记录图片加载失败

**Kotlin 扩展函数**：
- **URI 扩展**：`getMetadata(context)` - 从 URI 读取元数据（拍摄日期、宽度等）
- **Bitmap 扩展**：`applyColorFilter(matrix)` - 应用颜色矩阵滤镜，在 Dispatchers.Default 线程执行
- **Flow 扩展**：`debounceIf(condition, duration)` - 条件防抖，仅在满足条件时启用 debounce

### 2.4 依赖注入配置（预留扩展）

> **当前状态**：项目采用手动 DI（`AppContainer` / `AppContainerImpl`，见 `di/AGENTS.md`），无 Hilt/Dagger。以下为 Hilt 预留扩展方案，仅在将来引入 Hilt 时适用。

**Hilt 模块定义规范（预留扩展）**：
- **AppModule 提供的全局单例**：
  - **Database**：Room 数据库，使用 `@Singleton`标注
  - **ImageLoader**：Coil 全局图片加载器，配置内存和磁盘缓存
  - **OcrEngine**：离线 OCR 引擎（ML Kit）

## 3. Agent 执行规约 (Execution Rules)

- **DesignSystem 组件**：必须支持深色模式，使用 colorScheme 动态适配
- **滤镜处理**：必须在后台线程执行（Dispatchers.Default），避免阻塞 UI
- **Bitmap 管理**：必须及时回收（recycle() 或在 using 块中），避免 OOM
- **Logger 使用**：必须使用正确的 Tag 格式（`PoLang:Module`），便于检索；`GpuBeautyProcessor` 使用 `PoLang:ImageProc`
- **扩展函数**：必须使用明确的前缀，避免命名冲突
- **依赖注入**：当前为手动 DI（`AppContainer`），新增全局依赖必须同步更新 `AppContainer.kt`；若将来引入 Hilt，必须标注正确的生命周期（@Singleton / @ViewModelScoped）
- **Coil 缓存**：大小必须合理（避免 OOM），占可用内存 25% 以内
- **冗余代码防止**：`core/image/gl/` 目录下**禁止**添加 OpenGL/EGL 实现类；GL 渲染链路统一由 `beauty-engine` 模块维护

## 4. 常见陷阱检查清单 (Checklist)

- [ ] DesignSystem 组件是否支持深色模式？（使用 colorScheme）
- [ ] 滤镜处理是否在后台线程？（Dispatchers.Default）
- [ ] Bitmap 是否正确回收？（recycle() 或在 using 块中）
- [ ] Logger 是否使用了正确的 Tag 格式？（`PoLang:Module`；`GpuBeautyProcessor` 用 `PoLang:ImageProc`）
- [ ] 扩展函数是否避免了命名冲突？（使用明确的前缀）
- [ ] 新增全局依赖是否已同步更新 `AppContainer.kt`？（当前为手动 DI，无 Hilt）
- [ ] Coil 缓存大小是否合理？（避免 OOM）
- [ ] 所有用户可见字符串是否已提取到 strings.xml？（支持 I18N）
- [ ] 是否在 `core/image/gl/` 下误添加了 GL 渲染实现类？（应放在 `beauty-engine` 模块）
- [ ] `BeautyParamsConverter`（beauty-engine/api/）的 colorMatrix 是否正确处理了 `FilterType.NONE`？（应返回 null 让 Shader 直通）

## 5. 与产品文档对照 (Product Alignment)

**必须满足的产品指标**：
- ✅ HyperOS 视觉风格 → 大圆角 28dp+、毛玻璃 blur(20.dp)、流体动画
- ✅ 滤镜处理 < 200ms → 使用 ColorMatrix 或 GPU Image，后台线程执行
- ✅ 120fps 滚动 → Coil 缓存优化，懒加载大图

**技术决策记录**：
- 选择 Coil 而非 Glide：原生支持 Compose（`AsyncImage` API 简洁）、支持 Kotlin 协程、包体积更小
- RenderScript 已废弃（API 31+）：`GpuBeautyProcessor` 磨皮改为 Canvas + ColorMatrix 近似实现；实时预览磨皮仍在 `beauty-engine` 双边滤波 Shader 中
- 日志系统设计：结构化 Tag 便于 grep 检索，内存缓冲支持调试浮窗实时查看
