---
name: html-card
description: 在 OpenUI 程序的 Html(...) 组件里撰写自包含 HTML 沙箱卡的完整规范——硬约束（端侧清洗规则/128KB 上限/禁远程脚本）、转义要点、设计指引与黄金示例。当需要在回复中使用 Html 组件承载交互小工具或自由排版时，先加载本技能。
---

# HTML 沙箱卡撰写规范（Html 组件）

## 定位：逃生舱，不是默认形态

- Markdown 与既有组件能表达的，**优先用组件**，不要用 Html 包纯文本。
- Html 只用于：交互小工具（计算器、投票、小游戏、可拖拽排序）、组件无法表达的自由排版、复杂自定义动画。

## 硬约束（端侧 HtmlCardSanitizer 清洗规则，违反即被拒或剔除）

1. **自包含**：单个完整 HTML 字符串，样式写在 `<style>` 内，不引外部 CSS。
2. **脚本**：允许内联 `<script>` 做卡内交互；**禁止远程 `<script src=...>`**（端侧直接剔除）。
3. **禁用标签**：iframe / object / embed / form；禁 `<meta http-equiv="refresh">`。
4. **体积上限 128KB**（UTF-8 字节数，超限整卡被拒）——注意写在 OpenUi 字符串里
   转义后的体积也算，控制 HTML 规模，别内嵌大段数据或 base64 图片。
5. 外链资源（https 图片）可用但不保证加载成功，关键内容必须自包含。

## OpenUi 字符串转义要点（最容易翻车的地方）

`html` 参数写在双引号字符串字面量里：`Html(html = "...")`。

- **HTML 属性一律用单引号**（`<div class='box'>`），避免 `\"` 转义膨胀与视觉噪音；
- 必须出现的双引号写成 `\"`；
- 换行写 `\n`（HTML 本身不依赖真实换行渲染，可整段一行写完，但建议按结构分行提升可读性）；
- 反斜杠写 `\\`。

## 设计指引

- 风格与宿主卡一致：圆角 12~16px、内边距 16px、字号 14~16px 正文 / 20px+ 强调数字，
  字体用系统栈（`font-family: -apple-system, 'PingFang SC', sans-serif`）；
- 浅色背景深色文字（端侧可能套深色卡片底，文字颜色显式声明，不依赖继承）；
- 交互反馈要快：所有点击/输入响应 <100ms，禁依赖网络的交互；
- 卡高自适应内容，不设固定 vh 高度；内容最多一屏半，超出用卡内滚动。

## 黄金示例（投票小组件）

```html
<div style='font-family:-apple-system,"PingFang SC",sans-serif;padding:16px;background:#fff;border-radius:14px'>
  <div style='font-size:15px;font-weight:600;color:#222;margin-bottom:10px'>这组成片你更喜欢哪张？</div>
  <div id='a' onclick='vote(this,"a")' style='padding:12px;margin:6px 0;border:1px solid #ddd;border-radius:10px;color:#333'>A · 逆光剪影</div>
  <div id='b' onclick='vote(this,"b")' style='padding:12px;margin:6px 0;border:1px solid #ddd;border-radius:10px;color:#333'>B · 正面柔光</div>
  <div id='r' style='font-size:13px;color:#888;margin-top:8px'></div>
</div>
<script>
function vote(el, v) {
  document.getElementById('a').style.borderColor = '#ddd';
  document.getElementById('b').style.borderColor = '#ddd';
  el.style.borderColor = '#ff7a45';
  el.style.color = '#ff7a45';
  document.getElementById('r').textContent = '已记录：喜欢 ' + v.toUpperCase() + '，下次精选会参考你的偏好';
}
</script>
```

在程序中的写法（单引号属性 + \n 分行）：

```
root = Card(title = "投票", content = [
  Markdown("两张都修好了，投个票帮我决定封面～"),
  Html(html = "<div style='font-family:...'>\n  ...\n</div>\n<script>\nfunction vote(...)\n</script>")
])
```
