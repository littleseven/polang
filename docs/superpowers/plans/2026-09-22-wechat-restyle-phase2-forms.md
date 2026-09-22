# 微信风重设计 · Phase 2「结构性形态改造」实施计划（批次级）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans。本计划为批次级骨架——每批次开工前先 batch_read 该页帧结构（readDepth=-1），据实落 ops 级步骤再动手（画布结构以实时读数为准，预先写死 op 序列会失配）。
> 前置硬约束：单会话串行；`ardot-design-ops` skill 陷阱表全程适用（M/U/I/C/D 语法、≤25 ops/批、potentialIssues 逐条核对、渲染像素为终判）。

**Goal:** 把 spec §1 形态清单中「无法 token 化」的结构改造落到画布：微信式标签栏（替换悬浮胶囊）、设置页卡片→列表组、导航栏、头像 40dp。

**已完成前置（2026-09-22 夜，本分支）:** 色板 token v3.0 + 形态 token v3.0.1 已全量落地（双端代码+画布+快照，门禁全绿）。**B1 相机已被变量推送覆盖（只换色=已完成）**。气泡/输入/搜索/弹层圆角已 token 通道完成。

---

## T1: Components 页——floating_tab 组件重造（最高杠杆，40 实例自动跟）

**Files:** 画布 `component/floating_tab`(385:95) + `components.json` 登记 + `refs/ardot/components/` 双模预览。

**目标形态（spec §1 标签栏）:** 全宽平底条（非悬浮胶囊）：surface 底（Dark #1A1A1A）、顶部 hairline（outlineVariant）、高 ~56+底部安全区、图标 24dp + 10sp 文字、选中 `primary` / 未选中 `#888`（onSurfaceVariant）、**删除选中态胶囊指示器**。

步骤骨架：
- [x] batch_read 385:95 子树，记录现有层级（icon 集、label、胶囊指示器、ABSOLUTE 定位）
- [x] 重造：容器改全宽贴底（x=0, w=393, 底对齐 852）、cornerRadius 仅顶部 0（平底）、删胶囊指示器层、选中色绑 `scheme/primary`、未选中绑 `scheme/onSurfaceVariant`
- [x] health-check 子树 literal=0 + 钉 Light 导出 + `ardot-light-verify.py`
- [x] 抽 2 个宿主页（Gallery/Chat）导出快照，像素验证底条形制（横贯 393、顶部 hairline、绿/灰图标）
- [x] `components.json` 登记 + 组件双模预览入库

## T2: Settings 页——卡片 → 微信列表组（11 帧，工作量最大）

**目标形态（spec §1 列表 cell）:** 灰底（background）上悬白卡组（surfaceContainerLowest）：组圆角 8dp（`radius.card` 已就位）、组间距 8dp、cell 行高 ~54dp、水平 padding 16dp、左 icon+标题、右值+chevron、组内行间 hairline（末行无线）、导航栏微信化（与底同色 + 居中 17sp SemiBold + hairline——`topBar` token 已就位）。

步骤骨架（逐帧循环）：
- [x] batch_read 帧结构 → 设计组切分（现卡片语义 → 微信分组语义）
- [x] 重建容器层级为「组容器 + cell 行 + hairline」；颜色全绑 scheme 变量
- [x] 每帧：钉 Light 导出 + light-verify + Dark 快照像素抽验（灰底/白组/hairline）
- [x] 全页 health-check 归零后提交

## T3: Gallery + Chat 页收尾（17 帧残余结构项）— 2026-09-22 完结

- [x] 头像 40dp —— **N/A**：助手对话范式无头像（spec 偏差已回写）
- [x] 导航栏形态统一（T2 配方）
- [ ] chatBubble **暗色变体定值** —— 待用户提供微信 Dark 模式聊天截图采样后加 `userBubbleBgDark` 键（唯一遗留项）
- [x] searchField.backgroundAlpha 0.7 精调 —— **维持 0.7**：双模像素合成后与实底差近零（Dark 合成≈#1F1F1F 对底 #111），无视觉回归

## T4: People/Organize/Memories/Editor（30 帧跟随）

按 T2/T3 配方逐页套用；Organize/Memories 的 hero 照片区为内容不动 chrome。

## T5: B5 Store 47 帧（接入 Phase 5）

旧青玉 literal（#2FE385×3、#8FBBA6×3 等 207 处）随整帧重画消除；语言钉定/烘焙/PSA 重发全流程见 `ardot-design-ops` skill 1.4.0 行。

---

## 验收门禁（每批次必须全绿才提交）

1. `ardot-health-check.py` 归零（带 health-ignore.txt）
2. 钉 Light 导出 + `ardot-light-verify.py` 通过
3. Dark 快照像素抽验（关键形制用 ffmpeg 采样，**不信视觉模型**）
4. `sync-ardot-variables.py --check` + `gen-design-tokens.py --check` 双绿
5. 快照入库（核对陈旧 PNG → git rm）

## 衔接

Phase 3（Android 组件形态：Settings 列表组件化/导航/标签栏/头像）→ Phase 4（iOS /ios-follow）→ Phase 5（Play v2.3 + 官网——**对外发布须用户在场签发**）。


> **执行记录（2026-09-22）**：T1/T2/T4 全部落地并像素级验证（详见提交 e6cf593ad / b5ffd61ab / 498185670）。T3 除暗色气泡采样外完结。Memories hero 大标题有意保留（评审项）。帧钉 Light 预览引擎 quirk 已四路排除定位（与新变量/钉残留/缓存无关），Dark 正稿与 App 侧不受影响。
