# 追问选项重设计（FollowupBar 全宽列表条）设计 spec

> 日期：2026-11-28
> 状态：已评审（方向 B 用户拍板 2026-11-28，Ardot 三方向 mockup 比选 + 导出图复核）
> 范围：chat 回复尾部「后续选项」的视觉与组件重设计；OpenUI 体系三端（shared / Android / iOS）+ prompt 资产 + Ardot 画布收口
> 非范围：Buttons/Button 既有组件语义、@ToAssistant 动作通道、server 侧代码

## 1. 背景与问题

2026-11-24 follow-up 批次引入了「后续选项」：prompt 教 LLM 在回复收尾输出
`Buttons([...Button(标签, @ToAssistant(全文), variant="tertiary")...], direction="row")`。
端侧按通用组件渲染 → Android 实际呈现为 **M3 TextButton 纯文字横排、8dp 间距**：

- 无底色无边框，缺乏「可点选项」的视觉可供性（affordance）；
- 靠「标签 ≤8 字」prompt 硬约束防横排溢出，长选项被迫竖排（direction=column）割裂形态；
- 该形态从未经设计（画布无此组件），是 M3 默认样式的偶然产物。

Ardot 三方向 mockup（A 胶囊 Chip 流 / B 全宽列表条 / C 气泡预告式）比选、导出图复核后
**用户拍板方向 B**（mockup 帧 `followup/B-list`，Chat 页 x=3998）。

## 2. 视觉定稿

| 属性 | 值 |
|------|------|
| 容器 | 竖排列表，条间距 8dp，每条**全宽**（fill） |
| 选项条 | cornerRadius 12dp、padding 14/12、底 `surfaceVariant`（Dark #2C2C2C / Light #F2F2F2） |
| 条内布局 | 左：标签文字 `onSurface` 14sp（fill 撑开）；右：chevron `›` `onSurfaceVariant` 16sp（垂直居中，渲染层内置装饰，LLM DSL 不写） |
| pressed 态 | 底色加深至 `surfaceContainerHigh`（Dark #222222 / Light #EDEDED，双模均比 surfaceVariant 深一级） |
| 与上文间距 | 12dp（AgentMsg 尾部） |
| 对齐 | 正文同宽；数据卡内追加时跟随卡内布局 |
| 数量 | ≤3（不放宽；与既有「一卡 Button ≤3」lint 口径解耦，FollowupBar 自校验） |
| 文字放宽 | 标签 ≤12 字（全宽行单行从容容纳，废除「>8 字改竖排」fallback；超长 ellipsis 不换行） |

绿色（`primary`）不用于追问选项：留给卡内主 CTA，追问是轻引导用中性色。
方向 B 相对 A 的收益：点击面积大（触控友好）、长标签零换行焦虑、与卡内列表语言一致。

## 3. 技术方案

### 3.1 新增 OpenUI 组件 `FollowupBar`（不复用 Buttons）

**DSL 形态**（子节点复用 Button，只消费其 label + action）：

```
FollowupBar([Button("挑最佳镜头", @ToAssistant("从上周的照片里挑出最佳镜头")), ...])
```

- shared `OpenUiLibrary`：组件白名单 103→**104**（+`FollowupBar`，prop：
  `items: COMPONENT_LIST`（必填）——与 `Buttons.buttons` 同构，学习成本最低）；
- `RenderOpenUiValidator`（shared）：
  - FollowupBar 入白名单；`items` 子节点须为 Button；
  - 每项 action **必须且仅可** `@ToAssistant`（追问语义固定为「点选即作为用户消息发出」，
    @Navigate/@Run/Mutation 不收）；
  - 项数 ≤3；标签 ≤12 字（超长拒绝并提示）；
  - 不合并进「一卡 Button ≤3」lint 计数（语义域不同）；
- **Buttons/Button 既有组件与渲染零改动**——存量持久化卡（含旧形态追问）渲染不受影响。

### 3.2 prompt 资产（shared `OpenUiPromptAssets`，server 零改动消费）

- `DSL_RULES`「后续选项」段重写：`Buttons([...], direction="row")` → `FollowupBar([...])`；
  删「标签 ≤8 字/较长改竖排」句，改「标签 ≤12 字，超长会被拒」；其余何时加/何时不加/
  形态兼容（裸 Markdown 升级 Column）语义不变；
- `GOLDEN_FOLLOWUP_PROGRAM` 同步换 FollowupBar 形态（summary 与正文不变）；
- 测试同源钉住（现有 `OpenUiPromptAssetsTest` 口径，字面量断言更新）。

### 3.3 双端渲染

- **Android** `OpenUiContentRenderers.kt`：新增 `OpenUiContentFollowupBar`——
  竖排 `Column`（gap 8），每条全宽 `Row`（label fill + chevron hug，垂直居中）、
  `RoundedCornerShape(12)`、底 `surfaceVariant`、`clip + clickable` pressed 加深；
  chevron 用 Material 图标 `Icons.AutoMirrored.Filled.KeyboardArrowRight` 16sp
  `onSurfaceVariant`（渲染层内置）；子 Button 递归取 label/actions 喂 `onAction`
  （复用现有 action 通道，零协议变更）；
- **iOS** `OpenUiNodeRenderer.swift`：`OpenUiFollowupBarComponent` 对等实现
  （VStack + HStack，SF Symbol `chevron.right` 同级样式），样式值对齐 §2 表；
- 双端 token 全部走现有 scheme 语义色，**零新增 token**。

### 3.4 Ardot 画布收口

1. `followup/B-list`（561:11）的 OptionRow 子树组件化为 `followup_row` 迁入
   ChatComponents 页（438:328），登记 `components.json`（含双模预览 PNG）；
2. 正典 `chat/conversation`（111:383）AgentMsg2 尾部接组件实例（对齐 §2 定稿值，
   chevron 修正为垂直居中——mockup 自检发现的基线微偏在此一并修掉）；
3. 删除 `followup/A-chips`（561:1）、`followup/C-ghost-bubbles`（561:23）两帧；
4. `ardot-health-check.py` 退出码归零（新组件子树 literal=0）+ Light 验证
   （`ardot-preview-mode.sh` + `ardot-light-verify.py`）+ `export-ardot-snapshot.py` 快照入库。

## 4. 兼容与迁移

- 历史对话里已持久化的旧形态追问（Buttons+tertiary）继续按 Buttons 渲染，不做数据迁移；
- prompt 换教后新回复自然产出 FollowupBar；旧语法仍合法（Buttons 未删），仅不再被引导；
- server /agent 线零代码改动（校验栈与 prompt 资产 U1 已收口 shared，
  server `OpenUiSharedAliases` 自动跟随）。

## 5. 测试策略

- shared 单测：OpenUiLibrary 白名单 104 断言；validator 对 FollowupBar 的
  项数/标签长度/action-only-@ToAssistant 规则（正反例）；DSL_RULES 段字面量与
  黄金示例钉住；GOLDEN_FOLLOWUP_PROGRAM 过结构校验 + lint 三闸（现有口径）；
- Android 单测：FollowupBar 渲染分支 dispatch 注册（现有 renderer 测试模式）；
- iOS：PoLangUnitTests 对等加渲染注册断言；
- 真机/模拟器目检：Dark/Light 双模 + 三选项全宽列表（含一个 12 字长标签不换行不截断）+ 点击发出消息。

## 6. 不做的事（YAGNI）

- 不做多选、滚动 carousel、「推荐项」高亮态；
- 不动 Buttons/Button/IconButton 语义与样式；
- 不做旧数据迁移；
- 不加新 design token。

## 7. 交付物清单

- [ ] shared：OpenUiLibrary +validator +OpenUiPromptAssets（DSL_RULES 段 + 黄金示例）+ 单测
- [ ] Android：OpenUiContentFollowupBar + 单测
- [ ] iOS：OpenUiFollowupBarComponent + 单测
- [ ] Ardot：followup_chip 组件化 + 正典接线 + B/C 帧删除 + health-check/Light/快照
- [ ] 模块文档同步：`shared/AGENTS.md` openui 节、`CHAT_CARD_CATALOG.md` data_genui_program 节登记 FollowupBar
