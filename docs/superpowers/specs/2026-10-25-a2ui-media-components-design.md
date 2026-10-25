# A2UI 媒体组件补全（Video/AudioPlayer 17→19）设计

> **日期**：2026-10-25
> **状态**：在途
> **分支**：feat/a2ui-media-components（worktree `.worktrees/a2ui-media`）
> **触发**：用户审计后决策——「A2UI 为什么才 17 个组件，参照标准补充更多」→ 结论：官方 basic catalog v1 全量 18 个，polang 已注册 19（含自研 BarChart），唯一缺口 = Video/AudioPlayer 空盒占位。方向选定：补满标准（不引入非标组件），实现与 OpenUI 线共用。

## 1. 背景与定位

- A2UI 线在 polang 的定位 = 冻结对照组（wire-format spec §9），但「冻结」指不引入非标组件/不改线格式行为；**补满官方标准是正名**，不破坏对照组性质。
- 原占位理由「不引 Media3 依赖」已过时：`androidApp` 已依赖 media3 1.5.1（相册 `MediaPager.VideoPlayer` 在用），零新增依赖。
- 「与 OpenUI 共用」：OpenUI 89 组件库无 Video/AudioPlayer（官方库未收）；共用落点 = 播放器 Composable 放中性包 `features/chat/media/`，A2UI catalog 先行消费，OpenUI 未来加媒体组件时直接复用。

## 2. 目标 / 非目标 / 反目标

**目标**
- Video/AudioPlayer 真实渲染（Media3 ExoPlayer + PlayerView），卡片在聊天列表可播放、离开组合即释放。
- 服务端白名单 17→19，url 硬校验（同 Image 口径：仅 `https://` 公网地址，[PRIVACY] 媒体不出端）。
- 五处同步钉住：catalog json（已含，无需改）/ 端侧渲染 / 服务端白名单 / 工具描述 / 测试。

**非目标**
- 本地视频 `local://` 句柄投影（需扩 scan 投影到媒体 uri，另立任务）。
- OpenUI 线新增 Video/AudioPlayer 组件（OpenUI 官方库无此二者，属另一决策）。
- 播放器样式定制（用 Media3 默认控制器）。

**反目标**
- 为凑数引入非标组件——A2UI 保持「官方标准全集」的干净语义。

## 3. 设计

### 3.1 端侧：共用播放器组件（新文件 `features/chat/media/ChatMediaPlayers.kt`）

```kotlin
@Composable fun ChatVideoPlayer(url: String, modifier: Modifier, onError: (Throwable) -> Unit)
@Composable fun ChatAudioPlayer(url: String, description: String, modifier: Modifier, onError: (Throwable) -> Unit)
```

- 复用 `MediaPager.VideoPlayer` 模式：`remember(url)` 建 ExoPlayer（`playWhenReady=false`，卡片不自动播）、`DisposableEffect` 释放、`AndroidView` 包 `PlayerView`（`useController=true`）。
- `Player.Listener.onPlayerError` 转发 `onError`（A2UI 渲染器签名要求）。
- 尺寸：Video 默认 `fillMaxWidth().height(200dp)`（卡片语境）；AudioPlayer 紧凑条 `fillMaxWidth().height(72dp)`。尺寸常量进 `A2uiCardTokens`（a2uiCard token 节已有先例），不内联魔法数。

### 3.2 接入 `PolangA2uiCatalog`

`MaterialA2uiBasicCatalogV1Defaults.video/audioPlayer` 的空盒 lambda 换成 `ChatVideoPlayer`/`ChatAudioPlayer`。

### 3.3 服务端（`RenderUiValidator` / `RenderUiToolService`）

- `ALLOWED_COMPONENTS` +Video/AudioPlayer；`REQUIRED_PROPS` +`url`（对齐 catalog required）。
- url 校验复用 Image 分支口径：仅 `https://`（Video/AudioPlayer 同规则，违规文案指明组件）。
- 工具描述：「17 种（Video/AudioPlayer 未开放）」段改为 19 种全量 + 媒体 url 规则说明。

### 3.4 测试

- `RenderUiValidatorTest`：白名单钉住 17→19；新增 Video/AudioPlayer 正例（https url 放行）+ 负例（非 https / 缺 url 拒）。
- `RenderUiToolServiceTest`：描述钉住同步（如钉了「17」字面量）。
- `A2uiComponentAvailabilityTest` / `A2uiComponentRenderSmokeTest`：已含 Video/AudioPlayer 用例（引擎层无需改）；冒烟测试将真实实例化 ExoPlayer（Robolectric 下 media3 可建实例，不触网不播则安全——若炸则降级为懒初始化设计）。

## 4. 验收

- [ ] `./gradlew :androidApp:testDebugUnitTest --tests "*a2ui*"` 全绿
- [ ] `./gradlew -p server test --tests "*RenderUi*"` 全绿
- [ ] 真机 A2UI 卡含 https 视频可播、可暂停、滚出列表无泄漏（logcat 无 ExoPlayer 报错）
- [ ] [I18N] 无新增硬编码文案（播放器控件为 Media3 系统 UI）
- [ ] 文档同步：server/AGENTS.md、CHANGELOG 类活文档口径 17→19
