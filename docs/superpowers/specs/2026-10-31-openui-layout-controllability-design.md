# OpenUI 布局可控性设计（Ardot 版式范式 + 矫正/门禁混合约束）

> **日期**：2026-10-31
> **状态**：定稿待实施
> **上位约束**：`docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md`（视觉 SSOT）/ ADR-013（commonMain 纯度：规则引擎入 shared commonMain 须纯 Kotlin）/ ADR-008 [PRIVACY]（photos 投影 id，uri 不出端）
> **动机**：OpenUI 卡 v3~v8 八轮治理全走软引导（align 治理→图文固化→通用原语→经典版式→帧比例→相册签名形态，组件 21→97），「布局丑/组合不可控」依旧（v6 批次内用户原话「布局丑问题依旧没有解决」）。软引导只能降概率、不能杜绝。本轮用户定调：**保留 LLM 自由组合（GenUI 核心价值），同时让奇怪组合不可能出货**；首批 Ardot 设计四场景版式（统计报告/相册分组/前后对比/时间轴）。
> **失败 taxonomy**（用户确认命中）：**结构混乱**（嵌套过深/组件乱序/同层重复/容器错配）+ **节奏与比例失控**（间距乱/密度失衡/图文分栏失衡）。卡片膨胀、语义错配不在本轮靶内。

## 1. 机制架构：三层防御

```
LLM 自由组合（词汇 = 97 件 + 本批 3 新件）
        ↓
① OpenUiLayoutNormalizer —— 无声矫正（确定性纯函数）
        ↓
② OpenUiLayoutValidator —— 结构门禁（硬校验）
        ├─ 过 → action 帧下发 → 端上渲染（渲染器零改动，保持 dumb）
        └─ 违规 → 逐条违规清单 Observation 回灌 → LLM 同轮修正重试（上限 1 次）
```

哲学：设计语言原则 1「样式收敛在渲染器」上移一层——**组合收敛在规则层**。LLM 的自由定义在合法空间内，合法性由确定性规则保证，不靠模型自觉。

关键决策：

1. **代码落点**：`OpenUiLayoutNormalizer` / `OpenUiLayoutValidator` = shared commonMain 纯函数（规则引擎 SSOT，全枚举单测跑 jvmTest）。server 在 render_ui_openui 工具体落流前调用——完整复用 A2UI `RenderUiValidator` 已验证模式（2026-10-24 sf-012a0：actionGate 挂载、非法不落流不下行、error 逐条清单经 Observation 回灌、重试上限 1 防死循环）。server 消费 shared 的形态（直接依赖 / 副本+对账测试，iconNames「四处同源」先例）在实施计划核实既有基建后定，不新造第三套同步机制。
2. **顺序**：先矫正后门禁——压平无意义嵌套可能连带消除 R1 深度违规，减少无谓重试。矫正完全无声（不回灌、不提示）；门禁回灌清单只含门禁违规。
3. **流式预览非目标**：`openui_delta` 预览帧不矫正不拦截——瞬态制、正式帧到达自动让位，重试窗口期预览卡停留生成态即现有行为。
4. **端侧渲染器零改动**：矫正/门禁全在 server 收口，端上只渲染干净 program；iOS 机制层零改动。
5. **既有 lint 五条归宿**：图文裸拼×2（无 weights）→ 矫正 N1 吸收（软警告退役）；stat 未居中×2 → 矫正 N4 吸收；Grid 照片墙 → 门禁 R4 升硬。

## 2. 规则清单 v1（全部机器可判，不碰语义）

### 2.1 矫正层 `OpenUiLayoutNormalizer`（无声改写，只做「有唯一正确解」的修复）

| # | 规则 | 改写动作 |
|---|------|----------|
| N1 | 图文 Row 缺 weights：Row 子项含 Image 且未声明 weights → 按图位自动补 `[1,2]`（图在首位）/ `[2,1]`（图在尾位） | 补 weights |
| N2 | 节奏归一：同一容器内连续 Spacer ≥2 → 合并为 1 个（取最大档）；纵向内容流（Column/Card 直接子级）内 xs/xl 档 → 降 s/l（横排紧凑件如 IconText 行内 xs 档合法，不动） | 改写/合并 Spacer |
| N3 | 单子容器解包：Row/Column/Grid/Box/Stack 子项总数（含 Spacer）恰好 1 → 解包提升该子项（深度与节奏双杀，递归至不动点） | 递归解包 |
| N4 | stat 拼装居中：Row 直接子级 Column 内含 DISPLAY Text 且未 `align="center"` → 补 align | 补 align |

### 2.2 门禁层 `OpenUiLayoutValidator`（违规退回重试）

| # | 规则 | 违规提示（回灌文风，逐条可行动） |
|---|------|------------------|
| R1 | 深度上限：Card 嵌套链 >2 层（=设计语言 §1.2 Surface 层级 L0+L1）；Row/Column/Grid/Box/Stack 容器嵌套链 >4（**初值，Ardot 范式帧对照后可调**） | 「Card 最多两层（卡底+区块），请减少嵌套」 |
| R2 | 通栏签名件同层去重：Banner/PhotoGrid/StatRow/AvatarRow 同一 Card 直接子级出现 ≥2 | 「同卡重复通栏件，请拆卡或聚合」 |
| R3 | 图表禁横向分栏：图表族（Bar/Pie/Line/Area/HorizontalBar/Radar/Radial/Scatter/SingleStacked）出现在 Row/Grid 的非唯一子项（ChartGrid 内除外） | 「图表不可横向挤压，双图请竖排或用 ChartGrid」 |
| R4 | Grid 内 Image ≥2 → 必须换 PhotoGrid（原 lint⑤ 升硬；照片网格唯一形态 = 设计语言 §4.6） | 「照片网格请用 PhotoGrid」 |

护栏性质（非本轮重点，若既有协议护栏已覆盖件数/尺寸则不重复）：单卡渲染组件节点 ≤60 宽松 sanity 上限，超限整卡退回。

### 2.3 规则设计不变式

- 矫正只改几何/结构包装，**不改组件选型与内容**（不替 LLM 做语义决策）；
- 门禁只判结构合法性，**不判好坏/最优**（语义错配仍归食谱软引导）；
- 每条规则可独立关闭（常量级开关），误伤时快速止血。

## 3. Ardot 设计批次（首批四场景）

**新建画布页 `OpenUILayouts`**（版式范式页——性质是「场景正确版式 SSOT」，区别于 ChatComponents 散件页）。

| 交付物 | 数量 | 内容 |
|--------|------|------|
| 场景范式帧（正例整卡） | 4 | ① 统计报告（Banner 头 + StatRow + 图表（ChartGrid 或单图）+ 结论区）② 相册分组浏览（CardHeader+actionLabel + PhotoGrid，多分组节奏）③ 前后对比（CompareRow + 说明 + 建议行）④ 时间轴（Timeline + 头部）。每帧 = 场景唯一正确版式的视觉 SSOT，兼作 R 规则判据参照与黄金示例母本 |
| 新件设计帧 | 3 | CompareRow / Timeline / ChartGrid（schema 草案见 §3.1），97→100 件 |
| 反例判据 | 不画帧 | 文字化进 §2 规则表（本文档即 SSOT） |

**画布惯例照旧**：样式全绑既有 token 变量（过 token 门禁）；帧默认 Dark 出图 + Light 模式切换验证（全域可切体系）；快照导出 refs 入 `docs/08-UI-SPECS/screens/refs/ardot/`（提交前 `ls` 核对旧帧残留）；设计语言文档 §4 新增「版式范式」小节引用帧 + §5 速查表补三行 + 变更记录 v9。

### 3.1 新件 schema 草案（Ardot 定稿后可回填修订）

| 组件 | props 草案 | 语义 |
|------|-----------|------|
| CompareRow | `beforePhoto*` / `afterPhoto*`（相册投影 id）、`beforeLabel?` / `afterLabel?`、`beforeSrc?` / `afterSrc?`（https 兜底，photo 优先——Banner 双源同款约定） | 前后对比：左右等宽双图 + 图下标签 + 中缝分隔；点击 OpenPhoto。修图/优化效果对比表达位 |
| Timeline | `items*`（OBJECT_ARRAY：[{title*, subtitle?, time?, photo?}]） | 时间轴：轴线 + 节点圆点 + 内容行（photo 前件走 openUiPhotoResolver 同径）；回忆/事件流 |
| ChartGrid | `children*`（COMPONENT_LIST，限图表族——非图表子项按 R3 同条拦截） | 双图并排：columns 固定 2，图表在等宽格内自适应；R3 的合法出口 |

## 4. server prompt 重组

- 工具描述食谱段升级为「**版式菜单**」：四场景各一段紧凑范式描述（与 Ardot 范式帧一一对应：段落结构 = 帧的区块顺序）；
- 既有黄金示例重审对齐：GOLDEN_REPORT/STATS/PHOTO/CLASSIC/LAYOUT 程序与范式帧一致，且**全部必须过新门禁零违规**（fixture 钉住，防规则误伤合法形态）；
- 门禁退回违规清单文风与 A2UI 一致（逐条、可行动）。

## 5. 验收标准

1. **单测**：Normalizer/Validator 规则全枚举（jvmTest）+ 黄金程序全量过门禁零违规；
2. **回归集**：治理史反模式 fixture（等宽图文 Row、Grid 照片墙、stat 不居中、Card 三层嵌套、单子容器包裹、连续 Spacer、Row 塞图表……）逐条验证被矫正或拦截；
3. **真机**：四场景 prompt 实测无奇怪组合；流式预览→正式帧让位正常；门禁退回重试链路生效（构造违规 prompt 观察同轮修正）；
4. **iOS**：机制层零改动；三新件渲染器走 ios-follow（设计语言文档 + token 为 SSOT）。

## 6. 风险与边界

- **矫正误改**：N 规则限定「唯一正确解」改写（几何/包装），不改选型与内容；误伤时逐条开关关闭；
- **门禁误伤**：黄金示例 fixture + 反模式回归集双向钉住（合法形态零违规 / 反模式必拦）；
- **重试成本**：上限 1 次（A2UI 同款），违规清单全量回灌一轮带走；
- **不做**（YAGNI）：整卡模板组件、协议层 schema 收紧（不改官方组件语义）、语义错配硬校验、卡片膨胀治理、流式帧矫正。

## 7. 实施拆解（writing-plans 输入）

1. **M1 规则引擎**：shared 纯函数 Normalizer/Validator + 全枚举单测 + 黄金 fixture；
2. **M2 Ardot 设计**：OpenUILayouts 页四范式帧 + 三新件帧（画布评审）→ 快照入库 → 设计语言文档 v9（§0 原则「组合收敛在规则层」/ §4 版式范式 / §5 三行 / 变更记录）；
3. **M3 新件落地**：schema 注册 + 渲染器（Android）+ 真机冒烟；
4. **M4 server 接线**：actionGate 校验 + 重试 + 工具描述版式菜单 + lint 五条退役/升级；
5. **M5 验收**：回归集 + 真机四场景（iOS ios-follow 另行批次）。

M1 与 M2 可并行（规则判据初值已在本 spec，范式帧定稿后回调数值）。
