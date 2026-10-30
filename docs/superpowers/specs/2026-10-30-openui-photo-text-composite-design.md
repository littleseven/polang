# OpenUI 图文组合组件固化（photo 接入 + 食谱引导）

> **日期**：2026-10-30
> **状态**：已定稿，当日实施
> **范围**：shared（schema）+ androidApp（渲染）+ server（工具描述/校验器/lint）
> **背景文档**：`docs/08-UI-SPECS/OPENUI_CARD_DESIGN_LANGUAGE.md`

## 1. 问题（用户真机反馈，align 治理后复测）

「图文排版效果差，图文混排、环绕等不支持」——LLM 出图文卡只会 Row/Column 裸拼
`Image(photo=…)` + Text，撞 Row 等宽分栏规则（缩略图被拉成半宽栏），结构必然丑。

## 2. 结构性诊断（根因，已代码核实）

| # | 根因 | 位置 |
|---|------|------|
| R1 | 图文组合组件（`ImageText` 左图右文 / `ImageTextLarge` 通栏 hero）**只收 https `src`，不收相册 `photo` 投影 id**——相册场景下组合组件够不着照片，LLM 被迫裸拼 | shared `OpenUiLibrary` ImageText/ImageTextLarge schema |
| R2 | Row 子组件恒等宽分栏，无「定宽图 + 弹性文」的裸拼表达（M4.2 已裁定为既有口径，不返工） | `OpenUiNodeRenderer.kt` Row 分支 |
| R3 | 工具描述无「图文排版」场景→组件映射，LLM 肌肉记忆全在 Row/Column/Image 三件套 | server `RenderOpenUiToolService` 描述 |

核心结论同 align 治理：**语言缺词汇时提示词救不了**——把图文版式结构性锁进组合组件。

## 3. 设计决策

### D1 ImageText/ImageTextLarge 加 `photo` prop

- `photo`（NUMBER，可选）：#photos 投影元素 id（`photos[N].id`），与 `Image.photo` 完全同口径；与 `src`（https 公网图）二选一，皆无 → 同形占位不炸卡。
- 渲染期端侧 id→uri（`OpenUiPhotoResolver`，[PRIVACY] uri 不出端，不进 program/payload）。
- `ImageText` 版式锁死：定宽圆角缩略图（`imageSize`，钳 24..96，缺省 48）+ 标题/副题列；`ImageTextLarge`：通栏 16:9 + 粗标题 + 副题。
- 点击缩略图/大图 → 宿主动作 OpenPhoto 看大图（与 Image 组件同口径）。

### D2 工具描述「图文排版食谱」段

场景→组件固化映射（写进 render_ui_openui 工具描述）：

| 场景 | 写法 |
|------|------|
| 相册照片 + 标题副题（左图右文） | `ImageText(title=…, photo=photos[0].id, subtitle=…)` |
| 通栏大图 hero（上图下文） | `ImageTextLarge(title=…, photo=photos[0].id, subtitle=…)` |
| 多照片横滑 | `List(direction="HORIZONTAL", [Image(photo=…), …])` |
| https 网络图 | 同组件 `src="https://…"` |

明文声明：**文字环绕图片（float wrap）不支持**（Compose 无此概念，主流 GenUI 亦无），替代形态 = 上图下文 / 左图右文。

新增黄金示例 `GOLDEN_PHOTO_TEXT_PROGRAM`（companion const，测试同源钉住）：#photos 投影取数 + ImageText/ImageTextLarge 组合卡。

### D3 lint +1 条（软警告）

`Row(` 区间内裸放 `Image(` → 警告「图文并排建议改用 ImageText/ImageTextLarge 组合组件（版式固化、缩略图定宽）；Row 内裸放 Image 会被等宽分栏拉成半宽」。正则级近似（`Row\s*\(\s*\[[\s\S]{0,400}?Image\s*\(`），只建议不拒卡，同 lintComposition 既有口径。

### D4 图片总数上限口径补齐

`MAX_IMAGE_COMPONENTS` 计数 = Image 调用 + Image 区间外 `photo =` 键 + Image 区间外 src 类键——ImageText/ImageTextLarge 的 photo 引用计入整卡位图总数（OOM 护栏口径补齐，既有「Image 区间内只计一次不双计」规则同构）。

## 4. 不做（YAGNI / 明确不支持）

- 文字环绕图片（float wrap）：Compose 无对应概念，ChatGPT/A2UI 等主流 GenUI 亦无此形态；工具描述明文声明 + 替代形态引导。
- `VisualCardBlock.bgImageSrc` 接 photo（回忆卡大图底）：后评，本轮锁 ImageText 两组件。
- `ImageGallery` 接 photo 批量投影：多图已有 `List HORIZONTAL + Image(photo)` 通路覆盖。
- Row 等宽分栏规则本身不返工（M4.2 口径）；组合组件是正解，裸拼由 lint 引导。

## 5. 验收

- [ ] shared `OpenUiLibraryM2Test`：ImageText/ImageTextLarge photo prop 注册（NUMBER 可选）+ photo 引用求值存 props
- [ ] server `RenderOpenUiValidatorTest`：GOLDEN_PHOTO_TEXT_PROGRAM 过校验 + lint 正反用例 + 图片上限 photo 键计数用例
- [ ] `:shared:jvmTest` openui 套件 + server RenderOpenUi 套件 + androidApp 编译全绿
- [ ] 真机遛狗：「选一张我的照片配文字介绍做成卡片」→ ImageText 左图右文，缩略图定宽不拉栏
