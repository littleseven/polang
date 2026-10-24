# OpenUI 图文混排：Image 组件 + #photos 句柄投影设计

> **日期**：2026-10-24
> **状态**：已定稿（Android 先行；iOS 跟随走 ios-follow）
> **上位约束**：`2026-10-24-openui-wire-format-toggle-design.md`（OpenUI 主 spec）、`2026-10-21-cloud-local-hybrid-a2ui-genui-design.md`（local:// handle 体系 D1/D2 隐私投影）、ADR-016（parts 模型）

## 1. 背景与目标

OpenUI 线（`render_ui_openui`）现有六组件 Text/Row/Column/Card/Button/BarChart，**无图片组件**——LLM 无法产出「图文混排」卡（照片 + 文字排版）。A2UI 线虽有 `Image` 组件但仅限 `https://` 公网图（[PRIVACY]：相册照片永不出设备），同样不能展示相册照片。

**目标**：OpenUI 卡支持展示相册照片，与文字/图表自由混排。

**非目标**：`@Each` 列表渲染（M1 仍不做，LLM 展开固定索引 `photos[0..N]`）；A2UI 线相册图片（对偶改造另行立项）；视频。

## 2. 核心决策

### D1：照片引用 = 句柄投影出 id 清单，渲染期端侧 id→uri

隐私红线不变式：program/payload 绝不出现 uri/路径（validator `MEDIA_PATH` 禁令保持）。链路：

```
LLM program: photos = Query("local_handle", { handle: "local://scan/<id>#photos" }, [])
             root = Card([..., Image(photos[0].id), ...])
端侧: handle → LocalHandleStore payload（ScanPhotoEntry{id,captureDate} 清单，现有）
      → #photos 投影 → [{id: Num, date: "yyyy-MM-dd"}]（cap 60）
      → Image 渲染期 photoId → MediaDao.getMediaById → (uri, faceFocusY)
      → Coil AsyncImage（原生管线按目标尺寸降采样解码；ThumbnailCacheFetcher
        只拦 ≤360px 请求，全宽卡图不命中，故不宣称复用双级缩略图缓存）
```

uri 只在渲染期端侧解析，不进 program、不进 handle payload、不回灌 LLM。

### D2：#photos 是 scan 句柄的新 fragment（投影分流）

`resolveLocalHandle`（OpenUiCardHost）按 `parsed.fragment` 分流：

| fragment | 投影 | 用途 |
|----------|------|------|
| 空 / `histogram`（现状） | `[{label, count}]` 月桶 | BarChart 统计卡 |
| `photos`（新增） | `[{id, date}]`（cap 60，保持搜索相关度原序，date="yyyy-MM-dd"） | Image 图文混排卡 |

未知 fragment → `HandleResolution.Err`（整卡降级，宁缺毋滥口径不变）；服务端 validator 的 fragment 闭集白名单（`#histogram|#photos`）把 typo 拦在同轮纠错（review 🟡4），单卡 Image 数 ≤12 的 OOM 护栏同在服务端（review 🟡3）。

投影纯函数落 shared commonMain `domain/chat/a2ui/ScanPhotoProjection.kt`（与 `GalleryHistogramBuilder` 同包同理）：`toPhotoRefs(entries, cap=60, timeZone)` → `List<PhotoRef(id: Long, date: String)>`，kotlinx-datetime 格式化，纯函数可测。

### D3：Image 组件签名（OpenUiLibrary 第七组件）

```
Image(photo*, aspect?)
```

- `photo: NUMBER` 必填——相册资产 id（`photos[i].id` 求值结果；Num(Double) 渲染期 toLong，2^53 精度内安全）；
- `aspect: NUMBER` 可选——宽高比（宽/高），默认 4:3≈1.333，渲染期 clamp 到 [0.5, 2.5]；
- 无新 prop 类型（复用 NUMBER），`OpenUiEval.checkPropValue` 零改动；
- 渲染：`fillMaxWidth + aspectRatio(aspect) + clip(card 形) + ContentScale.Crop + faceAwareVerticalAlignment(faceFocusY)`；加载中/解析失败 = surfaceVariant 占位 Box（不炸整卡，半信任输入防御同现有 `else -> Box` 口径）；
- Row 内多张并排自动等宽（现有 weight(1f) 分栏机制直接生效）。

### D4：id→uri 解析接缝（DI 显式注入）

- androidApp 新增 `features/chat/openui/OpenUiPhotoResolver.kt`：fun interface `suspend fun resolve(photoId: Long): OpenUiPhoto?`，`data class OpenUiPhoto(uri: String, faceFocusY: Float?)`；
- AppContainer 新增 `openUiPhotoResolver`（实现 = `database.mediaDao().getMediaById(id)` 映射），ChatScreen 与 `localHandleLookup` 同点取出注入 `OpenUiCardHost` → `OpenUiReadyContent` → `OpenUiNodeRenderer(photoResolver=…)` → `OpenUiPhotoImage` composable（produceState 懒解析 + Coil）。

### D5：服务端同步（字面量副本双向核对）

- `RenderOpenUiValidator`：`ALLOWED_COMPONENTS` + "Image"（七组件）；`MEDIA_PATH` 禁令不变；`LOCAL_HANDLE_FORM` 已容许任意 fragment，零改动；
- `RenderOpenUiToolService` `@LLMDescription`：加 Image 签名 + `#photos` Query 写法 + 图文混排黄金示例（搜索→拿 handle→`photos = Query(...#photos...)`→`Image(photos[0].id)` 与 Text 混排）；强调「Image 的 photo 只许填 photos 数组元素的 id，禁写字面量数字 id（防臆造）」。

### D6：双端一致性（[PARITY]）

Android 定稿后本 spec 即 iOS 跟随依据（组件签名/投影形态/渲染语义三节为契约面）；iOS 实装走 ios-follow 管线，本批次不含。

## 3. 验收

1. `JITPACK=true ./gradlew :shared:jvmTest` 绿（新增 ScanPhotoProjection + Image schema 用例）；
2. `./gradlew -p server build` 绿（validator 新用例：Image 放行、#photos 形态放行、content:// 仍拦截）；
3. `./gradlew :androidApp:assembleDebug` 编译过；
4. 服务端 `./deploy.sh` 部署后真机验证：GenUI 开关 = openui，新会话发「搜索 XX 照片做一张图文混排卡」→ 卡内照片与文字同卡混排、徽标 `OpenUI v1.0 · p0` 在、点徽标 program 查看器可见 Image 组件、handle 缺失降级口径不变。

## 4. 明确不做

- `@Each` 列表渲染（OpenUiEval.kt:349 M1 显式空实现保持）；
- A2UI 线相册照片（其 Image 仍限 https；两线可比性差异记入对比实验台账）；
- 图片点击进全屏预览（后续迭代）。
