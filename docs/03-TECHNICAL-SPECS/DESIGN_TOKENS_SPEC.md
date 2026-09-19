# PoLang Design Tokens 规范（双端还原 SSOT）

> **工作流（2026-08-15 起，codegen 版；2026-08-19 起双向）**：`design-tokens.json` 是唯一 SSOT；双端镜像**全部由 `scripts/gen-design-tokens.py` 生成，禁止手改**。改 token = 改 JSON → 重跑生成器 → CI（`ai-gate.sh`）跑 `--check` 门禁（生成物与 JSON 不一致即 fail）。Ardot 画布为「token 活体预览 + 辅助精修面」：生成器输出 `build/design-tokens/ardot-variables.json`，由 `scripts/sync-ardot-variables.py` 与画布**双向同步**（`--push`/`--pull`/`--check` 语义见 §9；300+ 变量勿经 agent 手抄内联 `apply_variables`，易错）。
>
> 配套文件：
> - SSOT：`shared/src/commonMain/resources/design-tokens.json`（v2.2.3，_updated 2026-09-13）
> - 生成器：`scripts/gen-design-tokens.py`（含 `--check` 校验模式）
> - Ardot 双向同步：`scripts/sync-ardot-variables.py`（用法/故障排查见 `.kimi-code/ARDOT_MCP.md` §Token 同步工作流；三向语义见 §9）
> - Ardot 快照入库：`scripts/export-ardot-snapshot.py` → `docs/08-UI-SPECS/screens/refs/ardot/`（云端文档导不出可编辑源文件，结构 JSON + PNG 快照是本地 git 管理形态；改画布后重跑、diff 审查）
> - Android 生成物：`androidApp/.../core/designsystem/`（`Spacing` / `AppShapes` / `Color` / `Typography` / `DesignTokens.kt` 组件级 token）
> - Android 手写保留：`Theme.kt`（主题装配逻辑；引用 Color.kt 生成值，色值随 SSOT 自动同步）
> - iOS 生成物：`iosApp/PoLang/DesignSystem/DesignTokens.swift`
> - 规范约束：`androidApp/.../core/designsystem/AGENTS.md`（[TOKENS] / [TOKENS-SOURCE] / [SHAPE] / [EASING]）

## 1. 目的

从 Android 现有代码中**抽象提取**核心页面的 UI 风格 token，作为 iOS 移植的视觉基准，确保双端一致、提升还原度。

提取方式：10 个并行 agent 逐页通读 `androidApp` 源码，产出带 `file:line` 的 token 表 + 硬编码值清单。覆盖页面：Gallery / Search / Camera / Editor / Chat / Person+Memory / Tag / ID-Photo / Settings(+Model Center) / App Shell。

本文档 = 抽象结果 + iOS gap 分析 + 漂移记录 + 内容色板附录。

---

## 2. SSOT v1.0.0 → v2.0.0 变更

**保留**（已有，已校验）：`spacing` `radius` `icon` `topBar` `shutter` `beautyPanel` `grid` `pager` `color` `modelCenter`

**新增 — 全局基础（iOS 之前完全缺失）：**

| 组 | 用途 | 关键值 |
|---|---|---|
| `typography` | M3 baseline 字号阶梯（仅 bodyLarge 定制） | 15 个 role，各含 size/lineHeight/weight/letterSpacing |
| `colorScheme` | M3 baseline light/dark 语义色 | 29×2 = 58 个 ARGB hex（primary…surfaceContainerHighest） |
| `alpha` | 透明度语义阶梯（派生色 `.copy(alpha=N)`） | 16 档：scrimModal(0.7) … ghost(0.2) |
| `statusColor` | 语义状态色（不随主题） | success #4CAF50 / warning #FF9800 / error #E53935 / info #2196F3 |
| `motion` | 动效时长 + 缓动 | fast150 / medium300 / slow400 / blink500 / pulse1200，禁用线性 |
| `elevation` | 阴影/tonal 阶梯 | none0 / low1 / medium2 / high4 / floating6 / sheet16 |

**新增 — 共享组件：**

| 组 | 用途 |
|---|---|
| `appSlider` | 全 app 统一滑杆（camera/editor/beauty 共享）：胶囊轨道 6 + 白圆点 thumb 18 + primary 2dp 描边 + 按压 1.15× + 150ms |
| `bottomTab` | 悬浮胶囊导航（FloatingBottomTab）：**cornerRadius=28（v1 漏配，已补）** + shadow6 + tonal3 + icon24 |
| `bottomSheet` | 相机/编辑器共享面板外壳：corner24 + shadow16 + surface@0.95 + 渐变 scrim + dragHandle 36×4 |
| `chip` | FilterChip/AssistChip 几何：height36 + unselected surfaceVariant@0.5 |
| `badge` | 标签徽章：tag radius6 + primary@0.12 + dot6；required #E53935 Bold |

**新增 — 页面级（紧凑，仅取可复用/差异化 token）：** `camera` / `chatBubble` / `settings` / `editor`

**修正：** `shutter` 补全录制态（inner 28dp/4r）、`beautyPanel` 补全 heightRatio 边界、`topBar` 补 titleFontWeight。

**v2.1.0（2026-08-15，相机顶部工具栏改版）：**
- `camera` 组收编原 iOS 手写 CameraTokens：`topToolBar*`（padding/radius/spacing）、`panelCornerRadius`、`inlineFilterPanelHeight`、`panelBackground`(#F21C1A1F)、`cameraAccentOn`、`toolBarUnselectedBg`、`modeSwitcherSpacing`、`zoomBar*`/`zoomCapsule*`；删除死 token（`bottomActionButton*`、各底部面板 heightRatio——面板已改顶部内联）。
- 美颜面板高度唯一 SSOT = `beautyPanel.heightRatio`（0.35 → **0.40**，容下磨皮/美白/瘦脸/大眼 4 行 + Tab 栏）；iOS 由 `CameraTokens.beautyPanelHeightRatio`（手写）切换到 `BeautyPanelTokens.heightRatio`（生成）。
- 生成器新增 `RAW_SWIFT_VALUES` 直出表：JSON 中 `"@xxx"` 占位字符串（语义引用，classify=skip）可经该表为 Swift 生成原始属性行（如 `cameraAccent = Color.accentColor`——系统动态色不可冻结为 hex）；Android 侧不生成（对应语义走 colorScheme 角色，如相机 accent=primary）。`SWIFT_CG_FLOAT_KEYS` 增补 `beautyPanel.heightRatio`（iOS `ControlPanel(heightRatio:)` 形参为 CGFloat）。

**v2.2.2（2026-08-18，「青玉绿×Cloud Dancer」，已退役为历史）：**
- scheme 25 角色切换青玉系：中性轴=潘通 2026 年度色 Cloud Dancer 暖乳白 `#F0EEE9`（暗色暖黑 `#171412`）；primary=青玉绿家族（Light `#1F5C54` / Dark `#8FD6C6`）；tertiary=翡翠绿（commit 276caa49c）。
- 主题无关单值：cameraAccent `#0F766E`；chatBubble 品牌渐变 `#0F766E→#5EA88F`（暗帧）/`#43937E`（浅帧深端）；用户气泡 userBubbleBg `#0F766E` + 白字。
- 已被 v2.2.3 整体取代，仅存 git 历史。

**v2.2.3（2026-09-13，「A 氧气薄荷(Light) / B 能量绿(Dark)」，现行）：**
- scheme 25 角色重写：Light=薄荷白底 surface `#F1FAF5` + primary `#0E9F6E`；Dark=近黑绿底 surface `#071510` + 发光绿 primary `#2FE385`；容器梯度/outline 全套跟进（commit 457a854da）。
- 主题无关单值（取 Dark 侧）：cameraAccent 单值 `#2FE385`；chatBubble 品牌渐变 brandGradient `#1EA75B→#7CEFA8`；用户气泡 userBubbleBg `#2FE385`、深字 userBubbleOn `#062B18`。
- 产线：Ardot 画布调色 → `sync --pull` 回流 → `gen-design-tokens` 双端镜像 → `--push` 零漂移收敛；Android 真机双模式像素级验收通过。

---

## 3. Android 代码缺陷（已修复历史记录：2026-08-19 关闭 dynamicColor + Color.kt 纳入 codegen）

> 原前提已不成立：`PoLangTheme` 自 2026-08-19 起全局 `dynamicColor=false`（Android 12+ 默认 Material You 动态取色已关闭，双端一致），且 `Color.kt` 已纳入 codegen 生成物——#1/#2 已修复，仅作历史记录。iOS 无动态取色，仍**必须用 SSOT 标准值**。

1. ~~**`Color.kt:21` `ErrorLight = Color(0xB32610)`** — 6 位 int 被当作 ARGB → `0x00B32610` → **alpha=00 全透明**~~。**已修复**：`Color.kt` 现为生成物（`ErrorLight = Color(0xFFB3261E)`，文件头 GENERATED 标记，色值随 SSOT 自动同步）。
2. ~~**`Theme.kt:64-65` Dark scheme** — `tertiaryContainer = TertiaryDark`、`onTertiaryContainer = TertiaryDark`~~。**已修复**：现为 `tertiaryContainer = TertiaryContainerDark` / `onTertiaryContainer = OnTertiaryContainerDark`，引用 Color.kt 生成值，色值随 SSOT 自动同步。
3. **`Theme.kt` 未显式设置** `surfaceVariant` / `outline` / `outlineVariant` / `surfaceContainer*` — 走 M3 默认（**仍属实，2026-09-18 核验**）。SSOT 已补全 baseline 值（iOS 直接用，不再猜）。

---

## 4. 🔴 Token 消费漂移（Android 代码大量绕过自己的 token）

抽取发现：Android 代码**普遍未引用** `Spacing` / `AppShapes` / SSOT，而是在各页面内联硬编码。这是还原度的最大隐患——**iOS 必须以 SSOT + 本规范为准，而不是照抄 Android 内联值**（内联值之间偶有 1dp 不一致）。

典型漂移：
- **相机**快门 `76.dp` 直接硬编码，未引用 `shutter.diameter`；对焦环 `#00E5FF` 硬编码（虽等价 `color.focusRing`）。
- **设置**全屏绕过 `Spacing`/`AppShapes`：每个 `12.dp`/`16.dp`/`RoundedCornerShape(12.dp)` 都是内联。
- **App Shell 导航**过渡 `tween(400)` **未指定 easing**（`motion` 规范要求 FastOutSlowIn）。
- **Search** 存在 i18N 违规：硬编码中文 `未找到匹配…`/`搜索…`/`搜索图标`（违反 [I18N] 红线，须走 `strings.xml`/`Localizable`）。
- **相机 doc overlay** 用了原始 px（疑似 bug，非 dp）。

> 建议（非本次范围）：后续可逐步把 Android 内联值替换为 `MaterialTheme.spacing.*` / `MaterialTheme.appShapes.*` 引用，消除碎片化。

---

## 5. iOS 应用指南（关键 gap）

### 5.1 字号（最高优先级）
iOS 无 M3。`AppTypography.bodyLarge.font` 等 15 个 role 直接产出 SwiftUI `Font`，`.lineSpacing`/`.tracking` 用 `lineHeight`/`letterSpacing`。内联字重覆盖（模型卡名 SemiBold、必备徽章 Bold）见 `AppTypography.WeightOverride`。

### 5.2 配色（最高优先级）
iOS 无 Material You 动态取色。用 `AppColorScheme.light`/`.dark` 作为语义色基准，按 `AppSettings.colorScheme`（system/light/dark）切换。派生色用 `AppAlpha`：例 次要文本 `scheme.onSurface.opacity(AppAlpha.secondary)`，占位 `…placeholder`，模态遮罩 `Color.black.opacity(AppAlpha.scrimModal)`。

### 5.3 强制深色场景
相机 / 证件照 / Tag 对话框 / Chat overlay 是「黑底白字」overlay 词汇：用 `Color.white` + `AppAlpha`、`Color.black` + `AppAlpha`、`AppColors.panelBackground`(#CC000000)，**不走** colorScheme（参考 `bottomSheet.gradientStops`）。

### 5.4 主题切换入口
`iosApp/PoLang/App/PoLangApp.swift` 的 `AppSettings`（themeMode/appLanguage，`@AppStorage`）→ `.preferredColorScheme`。已就绪，配合 `AppColorScheme` 使用。

---

## 6. 内容色板附录（产品内容数据，未入 token JSON）

以下为**静态内容色**（非设计 token），iOS 须照搬 hex 以保证一致。各为已知有序集合。

### 6.1 标注笔触色（markup，7 色）— `MarkupPanel.kt:52-58`
| 名 | hex |
|---|---|
| red | `#FF3B30` |
| orange | `#FF9500` |
| yellow | `#FFCC00` |
| green | `#34C759` |
| blue | `#0A84FF` |
| white | `#FFFFFF` |
| black | `#000000` |

### 6.2 腮红家族（blush，3 色）— `ColorSelectors.kt:49-51`
| 名 | hex |
|---|---|
| pink | `#FF8DAA` |
| orange | `#FFA85C` |
| plum | `#9B3D6A` |

### 6.3 唇色盘（lip，12 色）— `ColorSelectors.kt:119-130`
`#D4757D` `#C43343` `#FF7F50` `#E0527C` `#FF6B9D` `#9B2335` `#FFA07A` `#CD5C5C` `#DC143C` `#FFB6C1` `#B22222` `#FF1493`

### 6.4 证件照底色预设（idphoto，产品数据）
`#438EDB`（蓝）/ `#D9001B`（红）/ `#FFFFFF`（白）

---

## 7. 页面级还原要点（iOS 待实现页）

### Camera（强制深色）
对焦环 `AppColors.focusRing` + `CameraTokens.focusRingDiameter=100`/stroke3/corner20/cross16；快门 `ShutterTokens`（照片态 76 外环/58 内核，录像态 28dp 4r 方）；控制按钮 48/24、idle `Black@0.5`/active primary；模式 tab 13sp（选中 Bold primary / 未选 `White@0.6`，**无 pill**）；三个共享底部面板用 `BottomSheetTokens`；语音唤醒脉冲 1200ms（alpha 0.3→1.0）。

### Chat（双形态）
**ChatScreen**（M3 浅色，DeepSeek 白卡输入）：input corner24 + shadow4 + Black@0.08 ambient / @0.12 spot。**AiChatScreen**（黑底 overlay）：panel Black/corner20、bubble DarkGray@0.85(#2D2D2D)。气泡不对称 20/20/4（user tail bottomStart，agent tail bottomEnd）；bubbleMaxWidth 360 / imageMaxWidth 240 / pad 16h-12v / 文本 14sp-20lh；胶囊按钮 corner16（active primary@0.12 / inactive surfaceVariant@0.5）；overlay alpha 阶 0.03→0.9。状态色用 `StatusColor`。

### Gallery / Search（主入口）
网格 `GridTokens`（110/2/2 方形 Crop）；`TopBarTokens`（48/17sp/36btn/22icon）；`BottomTabTokens`（28 corner 悬浮）；相册预览进场 fade300+scale400 FastOutSlowIn（0.2↔1.0）；search debounce 300ms。SearchField 自建：corner24 / surfaceVariant@0.7 / 14sp / clear 20dp。**Search 须先修 i18N 违规。**

### Editor
顶栏复用 `TopBarTokens`；底部工具栏 FilterChip（无显式高，M3 默认 ~32）；滑杆用 `AppSliderTokens`；滤镜项 72 宽/64 thumb/10sp label/选中描边3-渐变；gacha 卡 84 thumb/corner8/inner6；棋盘格 16 cell (#E6E6E6/#BDBDBD)；裁剪浮动按钮 44/`Black@0.45`/CircleShape/White icon。

### Settings（+Model Center）
`SettingsTokens`：行 56（无副标题）/64（有副标题）、section `surfaceContainerHighest`、chevron 20@0.6；hero 头像 48/`primaryContainer`/CircleShape；分类卡 icon 28 primary。Model Center 标签色以 `ModelCenterTokens` 固定 hex 为准（**注意 Android `getTagColor` 用主题角色与 JSON 分歧——iOS 统一用 token**）。

### Person / Memory（最干净）
全色 token 化，零硬编码 hex；**无关系图谱**（用 FilterChip FlowRow 分组 + OutlinedTextField）；人物卡 16 corner/`surfaceContainerLow`/1 elevation/双列 12 间距/封面 1:1；关系 chip 8 corner（self `primaryContainer` / other `surfaceContainerHighest`）/12sp。

### ID Photo（强制深色）
根 `#101010`；预览 220dp×动态/corner4/白底；色样 40 circle/选中环 3-未选 1；未选 chip `#2A2A2A`；边缘面板 `AppSliderTokens`；背景预设见 §6.4。

### Tag
photo-info tag chip `primary@0.2`/corner6/12sp/White/8h-4v；section header 14sp Bold `White@0.8`；dialog `#2D2D2D`/corner20/scrim `Black@0.7`；成功 `#4CAF50`；警告条 `#FFF3CD`/`#856404`；**无分类配色**（person/scene/object 共用一套）。

---

## 8. 双端同步检查清单

新增/改动 UI 时：
- [ ] 只改 `design-tokens.json`（SSOT），然后跑 `python3 scripts/gen-design-tokens.py` 重新生成双端镜像
- [ ] **禁止手改生成物**（`DesignTokens.swift` / `Spacing.kt` / `AppShapes.kt` / `Color.kt` / `Typography.kt` / `DesignTokens.kt`——文件头有 GENERATED 标记）；`ai-gate.sh` 的 `--check` 门禁会拦截
- [ ] 尺寸只引用 `Spacing`/`IconSize`/`*Tokens`，禁内联 dp
- [ ] 圆角只引用 `AppRadius`/`AppShapes`
- [ ] 颜色：随主题→`AppColorScheme`+`AppAlpha`；固定功能→`AppColors`/`StatusColor`；内容色→§6 附录
- [ ] 动效用 `AppMotion`，禁线性
- [ ] 字体用 `AppTypography` role
- [ ] （可选）改完色值/尺寸后跑 Ardot 同步：`python3 scripts/sync-ardot-variables.py`（push；画布精修后 `--pull` 回流、`--check` 漂移门禁，语义见 §9；前置：Ardot 桌面端已开云端文档 `polang-ui-spec`；变量集 `PoLang Tokens`，Dark/Light 双模式）；`capture_screenshot` 留档；画布结构变更另跑 `python3 scripts/export-ardot-snapshot.py` 快照入库
- [ ] 用户可见文本五语同步（Android `values`/`values-zh-rCN`/`values-zh-rTW`/`values-es`/`values-fr`；iOS `Localizable`/`*.xcstrings`）

---

## 9. 双向同步（2026-08-19）

`design-tokens.json` 仍是**唯一 SSOT**（双端代码生成物唯一来源）；Ardot 画布定位为**辅助精修面**——在真稿上调色/调尺寸后经回流管道反哺 SSOT，不承担代码源头职责。`scripts/sync-ardot-variables.py` 三向用法：

| 命令 | 方向 | 语义 |
|---|---|---|
| `sync-ardot-variables.py`（默认 `--push`） | JSON → 画布 | 以 JSON 覆盖画布同名变量（值 + scope） |
| `sync-ardot-variables.py --pull` | 画布 → JSON | 回写 SSOT 并自动重跑 `gen-design-tokens.py` 刷新生成物；加 `--prune` 额外删 JSON 有而画布无的键 |
| `sync-ardot-variables.py --check` | 只读比对 | 值 + mode + scope 三维比对，漂移清单 + exit 1 |

- **冲突语义 = 显式方向，不自动合并**：两侧同键不同值时无自动裁决；`--check` 的漂移清单就是「显式选方向」的决策输入（`--push` 以 JSON 为准 / `--pull` 以画布为准）。
- **画布独有变量豁免（2026-09-20）**：`--ignore-file`（默认 `docs/08-UI-SPECS/screens/refs/ardot/sync-ignore.txt`）登记「画布机制变量/已迁移残留」，不计 NEW 漂移、`--pull` 不回流。典型：sysbar-dark/light（status bar 图层可见性布尔开关，codegen 无 BOOLEAN 域）；已迁移变量的画布残留（MCP 无变量删除 API）。注意 `apply_variables` 默认 merge 模式不删画布变量，`replace=true` 会删除未提及变量——**禁用**。
- **CI 门禁建议**：`ai-gate.sh` 在 `gen --check` 之外**并列 `sync --check`**（前者守护 生成物↔JSON，后者守护 JSON↔画布），任一红即 fail；收口基线 440=440 零漂移。
- **绑定原则**（2026-08-19 绑定清扫收口：五页 + IconSet ~600 处、零像素回归）：字面量先比对「== 变量当前 mode 解析值」再绑，等值绑定前后像素 diff 必须 = 0；豁免（不绑）= 坐标、内容色（§6）、派生值、一次性值。
- **已知限制**（绑定面之外，保持字面量、以像素零回归为准）：auto-layout `gap`/`padding`；fills 内 paint 级 `opacity`（仅 paint.color 可绑，绑后 opacity 保字面或换 alpha token）；渐变 `stop` 色；整帧 `cornerRadius` 简写不可绑，圆角须走四角逐角键（topLeft/topRight/bottomLeft/bottomRight，如 chatBubble 20/20/4 不对称尾角）。token JSON **新增组**须同步注册 `gen-design-tokens.py` 的 `ENUM_NAMES` / `IOS_ORDER`（及按需 `RAW_SWIFT_VALUES` / `SWIFT_CG_FLOAT_KEYS`），否则不进双端生成物与 push payload。

---

_提取日期：2026-08-09 · SSOT v2.2.3（_updated 2026-09-13）· 10 页全量抽取_
