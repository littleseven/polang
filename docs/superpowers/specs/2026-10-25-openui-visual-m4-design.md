# OpenUI 视觉规范 M4：卡内设计系统（表现力追平 H5 线）设计

> **日期**：2026-10-25
> **状态**：在途
> **上位**：spec《openui-component-library-m3-design》（21 组件）、根 AGENTS.md [PARITY]
> **动机**：M3 真机验收发现 OpenUI 卡「功能可用但视觉朴素」——裸 M3 默认样式直出（Tabs 亮绿指示条、图表单色板、字阶平、间距均）。H5 卡靠 LLM 即兴 CSS 质量方差大上限高；OpenUI 路线样式 100% 收在渲染器，须一次性把渲染器样式做精。本批只做**渲染层**，不动契约/校验器/工具描述（零 server/shared 改动 → 不部署、LLM 无感）。

## 1. 范围（四项，全部落 `androidApp/.../features/chat/openui/`）

### 1.1 卡内视觉规范（OpenUI 专属 token，不动 A2UI 线）

新增 `OpenUiCardTokens`（与 A2uiCardTokens 平级同文件或新文件，**禁止改 A2uiCardTokens/A2uiStyleMapper**——A2UI 是对比实验对照组，样式冻结）：

- 字阶：HEADLINE 20sp/Bold（现 ~16 拉不开层级）、SUBHEAD 15sp/SemiBold、BODY 14sp、CAPTION 12sp 弱色
- 间距阶梯：组件间 sm(8)→md(12) 默认；Section（SUBHEAD/图表/表格前）md→lg(16)
- 圆角：Image 组件圆角 md(12dp)（现直角）；卡内子容器（Callout/Table 头）圆角沿用 AppShapes.card
- Divider：厚度 0.5dp、色 onSurface alpha 0.08（现默认过粗过亮）
- 卡内容器内边距：审视现状，局促处加大（目标：整卡呼吸感对齐 H5 卡观感）
  （验收记录：审视后维持 Spacing.lg(16) 不局促——review 🔵5 记录在案）

### 1.2 图表多色相色板

`openUiChartPalette()` 弃用「primary/tertiary/secondary + alpha 阶梯」同族色（撞绿糊脸），改 6 色相离散色板（参考 Material 图表色板：蓝/橙/绿/紫/红/青，深底可读性优先，luminance 适中）。PieChart/LineChart 用全板；BarChart 单系列保持单色但换板首色（蓝）；BarChart 选中态高亮沿用 primary 语义。

### 1.3 Tabs 胶囊分段控件

弃 M3 TabRow/ScrollableTabRow（亮绿指示条刺眼），改自绘胶囊行：圆角容器（surfaceVariant 底）+ 选中页签 primary 底 onPrimary 字胶囊 + 未选中 onSurfaceVariant 字；>4 页签横滑保留；选中态仍是渲染期本地态（M2 口径不变）。

### 1.4 空绑定兜底隐藏

`Text`/`Markdown` 的解析结果 trim 后以 `:`/`：` 结尾（绑定值为空只剩「当前选中：」式前缀）→ 丢该组件不渲染。（M4.1 真机验收修正：不限 prop 形态——Str+Binding 拼接写法 `Text("当前选中: " + $selected)` 经 eval 后槽位已是 Str 无法回溯绑定来源；已知误伤面：静态冒号尾文本同丢，可接受。）Binding 解析不出维持丢组件既有口径。

## 2. 明确不做

- 照片美学排序（#photos 投影叠加 aestheticScore）：动 shared/server 契约，另立 M5
- A2UI 线任何样式（对照组冻结）
- H5 线（render_html）任何改动

## 3. 验证

1. `JITPACK=true ./gradlew :androidApp:assembleDebug` 绿
2. 真机（92a0cfb8）：重装后**重开年度杂志会话**（卡已落库，零 LLM 重跑）对比：Tabs 胶囊化、饼图多色、柱图蓝色、标题层级、照片圆角、「当前选中：」空态隐藏
3. 交互回归：点柱 →「当前选中：2014-03」类文本复现；Tabs 切换正常

## 4. M4.2 真机验收修复（2026-10-25，非视觉项）

M4 装机验收时暴露两个 M2 期 Image 组件潜伏 bug（杂志卡「照片」页 6 张横滑图全隐、封面 3 图全占位）：

1. **photoResolver id 空间断裂**：扫描快照持有未索引系统媒体的负 synthetic id（`MediaRepositoryImpl.syntheticMediaId = -(mediaStoreId*10+typeSalt)`），而 resolver 实现直查 `MediaDao.getMediaById`（Room 自增正 id 空间）→ 负 id 恒 null → 永久占位。修复：`AppContainer.openUiPhotoResolver` 改走 `AndroidMediaRepository.getMediaById` 双通道（DAO 直查 miss 后 allMedia 合并流反查）。
2. **HORIZONTAL List 内 Image 零尺寸**：横滑行内宽度无界 → `fillMaxWidth` 失效、`aspectRatio` 双维无界塌成 0×0。修复：`OpenUiPhotoImage` 改 BoxWithConstraints，无界宽落固定条目宽 `OpenUiCardTokens.horizontalImageWidth`(200dp)，有界保持铺满。
3. **Row 在横滑 List 内 weight 分栏失效**：横滑行的无限宽约束下 `weight(1f)` 子项拿不到宽度（`Row([Image×3])` 写法塌缩）。修复：Row 分支外套 BoxWithConstraints 判界，无界宽时子项不挂 weight、按内容自尺寸。
4. **BarChart 点桶回写硬编码 $selected**：LLM 自定义绑定变量名（如 `BarChart(..., $month)` + `Text("当前选中: " + $month)`）时，点桶硬写 `$selected` → 卡读 `$month`，点了没反应。修复：回写目标跟随 selected prop 的绑定变量名，未绑定才回落 `$selected`。（A2UI 对照组同问题存在但冻结不改。）
