# Ardot Light/Dark 全域可切 + 页帧结构重组 设计文档

- 日期: 2026-09-19
- 状态: 已确认（用户拍板）
- 范围: Ardot 云端设计稿（PoLang 破浪相册）+ 仓库快照管线（docs/08-UI-SPECS/screens/refs/ardot/）

## 1. 背景与决策链

| 日期 | 事件 |
|---|---|
| 2026-08-17 (a46dcef9c) | chat 浅色三帧 I() 重建（variableModes=Light 全树切换），全局帧尺寸定 400×890 |
| 2026-08-19 (b575b5286) | **删除浅色物理帧，定调「全站统一 Dark 正稿 + mode override 预览」** |
| 2026-09 (3321561af/16fea14d9) | floating_tab/status_bar/system_nav_bar 三件套组件化；系统条布尔双图层 Light 自适应落地 |
| 2026-09-19 (本文档) | 用户要求：Ardot 内原生切换 Light/Dark 预览，贴合 Ardot 能力效用最大化；全稿 review + 页帧结构化重组 |

### 现状盘点（2026-09-19 实测）

- 文件 10 页 / 116 顶层节点（含 IconSet 6 个）。
- `PoLang Tokens`（2:2）变量集含 **Dark/Light 双模式，默认 Dark**；modeMap: `2:0`=Dark, `79:1`=Light。
- **全文件 0 帧钉定 variableModes**（帧级与语言集 182:133 均无钉定）。
- 唯一 Light 自适应：status_bar/system_nav_bar（`sysbar-dark` 386:37 / `sysbar-light` 386:38 布尔 + 双图叠加）；**floating_tab 未适配**。
- 深扫样方（depth-1）: Camera 页 7 帧各有 13~16 处 literal 色；Editor concept_a 4 帧各 8~16 处；其余页顶层基本已绑 token（深层待全量扫描）。
- 结构混乱：store01 商店源帧散落 5 个功能页（12 帧）；组件混入 Settings 页（provider_row/model_row）与 IconSet 页（3 组件 + 2 Dark演示）；Organize 页含 gallery 前缀帧 ×2；People 页混放 5 个 240×240 cast 素材；Memories 页混放 393×120 片段帧；Editor 页两代并存。

## 2. 用户拍板决策

1. **主题策略**: 在 Ardot 中原生切换 Light/Dark 预览，贴合 Ardot 能力（= Dark 正稿 + 帧级 variableModes 钉定切换，不建物理副本）。
2. **重组结构**: 域对齐 + 素材集中（8 功能页严格前缀=页域；store01 集中；新建 Components 页）。
3. **清理力度**: 删旧代帧但**保留商店源帧**（zhTW 导出链路依赖）。
4. **Light 适配范围**: **八域全部可切**（含 Camera/Editor）。

### 实施前验证修正

- ~~chat/empty~~ **保留**——App 中 `ChatEmptyState`（登录态空屏）与 `GuestNudgeBanner`（游客变体）并存，均为活态，非两代。
- `memory/bottombar` 确认可删——已被共享 `MainFloatingBottomBar`（floating_tab 组件）取代（2026-09-06 导航统一）。

## 3. 设计方案

### §1 目标与主题策略

- Dark = 唯一正稿（PoLang Tokens 默认模式）；Light 通过**帧级 variableModes 钉定**在编辑器内即时切换预览（页根 override 引擎不支持，帧即切换单元）。
- 导出管线按 mode override 产出 Light PNG；快照入库以 Dark 正稿为准。
- 八域全部可切；PlayStore 营销图（01-08 三语 composite）、guide-promo、feature-graphic 固定深色不参与。

### §2 结构重组（域对齐 + 素材集中）

**删除 ×3**: `editor/current_crop`(118:105)、`editor/current_adjust`(118:165)、`memory/bottombar`(297:87)。

**迁移 ×32**:

| 动作 | 帧 | 数量 |
|---|---|---|
| 5 功能页 → Play Store Assets 页 | gallery/grid-store01(300:29)、search/store01(300:539)、gallery/grid-store01-zhTW(306:197 保留)、chat/store01-welcome(300:651)、chat/store01-conversation(301:13)、chat/store01-insight(301:96)、people/grid-store01(300:215)、people/detail-store01(300:723)、memory/store01-feed(300:782)、settings/privacy-store01-zh/en/tw(385:1/32/63) | 12 |
| IconSet/Settings/People → 新建 Components 页 | component/floating_tab(385:95)、component/status_bar(385:106)、component/system_nav_bar(385:107)、status_bar/Dark演示(386:43)、system_nav_bar/Dark演示(386:44)、component/provider_row(166:17)、component/model_row(167:17)、cast/mom~chris(300:1048~1052) | 12 |
| Organize → Gallery | gallery/tag_control(171:273)、gallery/tag_stage_sheet(172:113) | 2 |
| IconSet 页 | 只留 icon/spec_sheet(132:4) | — |

**页账（116 → 113 顶层 / 11 页）**: Camera 7 · Gallery 10 · Chat 7 · People 2 · Organize 10 · Memories 2 · Editor 4 · Settings 11 · IconSet 1 · **Components 12（新）** · Store 47。

**页内重排**: 每页按用户旅程/状态序行主序横排，统一间距 120px，ABSOLUTE 定位。帧名规范 `{domain}/{screen}[_{state}]`；语言/主题不进帧名。

### §3 绑定修复（Light 可切前提）

1. 全量深扫 audit（readDepth=-1 八域逐帧）: literal 色 fills/strokes/effects 清单 + IMAGE 资产清单 + per-frame 健康度。
2. literal → 双模 token: 优先绑 PoLang Tokens 既有 82 COLOR 变量（色距最近匹配）；无对应者新增语义命名变量（Dark/Light 双值）；覆盖 Camera/Editor 重灾区与图标字面色（历史坑 #E6E1E5→#1C1B1F 类）。
3. 图片布尔双图层: floating_tab 补 sysbar 式布尔自适应；照片类资产不换。
4. 按绑定能力地图核对: 渐变 stop 可绑 / cornerRadius 四角可绑 / gap 不可绑。

### §4 验证与快照闭环

- 每帧 Dark + Light 双模导出 PNG；Light 用**像素采样**核查（视觉模型对 Dark 帧会误报浅色）。
- `export-ardot-snapshot.py` 刷新 structure.json + Dark 正稿 PNG；**手工 git rm 陈旧 PNG**（快照不清理旧帧）。
- 每个 batch 后快照入库一次形成回滚点（防并发会话恢复事故重演）。

### §5 执行批次

A. audit 深扫 → B. 结构重组（删/迁/排） → C. 绑定修复（逐页） → D. 组件布尔自适应 → E. 双模导出验证 + 快照收口 + 文档同步。

## 4. 风险与坑（记忆库既有教训）

- 跨页 M 移动孤儿化 → 用已验证 M 挂载套路，batch_edit ≤25 ops 分批。
- 实例数字尺寸需先 U(null) 解绑；U(svg) 静默空转。
- 快照导出不清理旧帧 PNG → 提交前 ls 核对 + git rm。
- export 冷缓存空白 → 预热重试。
- **严禁并发会话/并行子代理操作同一文件**（相机页曾被并发会话恢复）——本任务全程单会话串行。
