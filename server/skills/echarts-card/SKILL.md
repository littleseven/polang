---
name: echarts-card
description: 交互图表（ECharts）双通路契约——默认 Chart.echarts 固定模板（零手写 HTML、零本技能加载），复杂形态才手写 HTML 卡（polang-lib 声明/防遮挡/配色/文案规范）。需要交互式/复合图表（趋势、占比、多维对比）时参考。
---

# ECharts 图表卡双通路契约

## 默认通路：Chart.echarts 固定模板（优先，零手写 HTML）

**绝大多数图表诉求不需要本技能正文**——`run_gallery_script` 的工具描述已含完整用法，
在脚本末尾一行 return 即可：

```js
// 例：月度趋势（数据必须来自 bridge.callAsync 真实聚合）
var tl = await bridge.callAsync('gallery.timeline', {bucketMs: 2592000000});
var keys = Object.keys(tl).sort();
var x = keys.map(function (k) {
  var d = new Date(Number(k));
  return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2);
});
return Chart.echarts({
  title: '相册月度趋势',
  x: x,
  series: [{name: '媒体数', data: keys.map(function (k) { return tl[k]; })}],
  unit: '个'
});
```

- `Chart.echarts(cfg)`：`{title?, x:[类目标签], series:[{name, data, type?: 'bar'|'line', color?}], unit?, tip?, theme?, style?}`。
  多序列直接给多个 series（柱线混合：各 series 自声明 type）。
- 主题配色：`theme` 选预置主题 `'fresh'(默认)/'ocean'/'sunset'/'violet'/'forest'`；
  `style` 按字段覆盖（`palette` 色板 / `height`(120~600) / `barRadius` / `lineWidth` / `smooth` 等），
  `series[].color` 单系列覆盖最优先。拿不准就全用默认。
- 固定模板自动渲染成交互图卡上屏（tooltip / 读数行联动 / 防遮挡 / 清新配色全内置），
  **HTML 不回传给你**——你只收到 summary 文字（峰值/合计），据此撰写分析。
- ⚠️ **图卡已经自动上屏，你的最终回复严禁再出现任何 Html 组件、严禁重复发卡**——
  回复 = `root = Markdown("分析文字")` 一条组件语句（// summary 首行照写）。
  真机事故：LLM 把模板图卡又手写一遍进 DSL，屏幕出现两张卡、手写那张空白。
- 严禁手写任何 HTML/ECharts 代码；严禁 SVG（Chart.bar/line/pie/timeline 云端不渲染，会报错引导）。

模板已内置（不用你操心）：polang-lib 预制 ECharts 声明、`confine: true` 防遮挡、
读数行上图表、清新色板 `#38BDF8/#34D399/#FBBF24/#F472B6/#A78BFA/#F87171`。

### 分析文案硬要求（图卡之外你的文字回复）

图卡上屏后你的回复仍是主体：**至少 100 字**真实分析——峰值/低谷月份与数值、
趋势形态（上升/集中/断档）、占比或环比结论、一两句可执行建议。数字必须与
summary 及工具结果一致，禁止复读图例、禁止空话。

### 数据真实性（第一硬约束：禁臆造）

- 图上每个数字必须来自本轮 `bridge.callAsync` 真实聚合。**禁止编造「看起来像」的数组**——
  取不到就直说限制，宁可少画一个系列。
- 单序列数据点 ≤ 24 个；时间跨度大时先按月/季聚合，不逐日堆点。
- `gallery.timeline` 不分照片/视频（无 mediaType 参数）——分类型趋势需用
  `gallery.query` 自行聚合，或接受合计口径并在文案里说明。

## 复杂通路：手写 HTML 卡（仅当 Chart.echarts 表达不了时）

适用：双 y 轴、堆叠、饼环、散点、自定义 tooltip 富文本等模板外形态。
此时才需要本节契约。

### 声明契约（违反即不生效）

HTML 里写 meta 声明依赖，**不要写 `<script src=...>`**：

```html
<meta name='polang-lib' content='echarts'>
```

渲染期端侧展开为本地预制库（ECharts 5.6.0，离线可用），inline `<script>` 里
`echarts` 全局直接可用。卡内数据与代码计入 128KB 卡体积上限——先聚合再填 option。

⚠️ **URL 红线（真机事故）**：程序文本里出现 `file://` / `content://` / `/storage/`
任一子串（哪怕在 HTML 字符串内部），命中服务端隐私校验整程序扫描——**整条回复被
替换成「回复生成失败，请重试」**。CDN 远程脚本会被端侧清洗器剔除（图表变
「图表库未加载」）。任何形态的外引脚本都是死路，只能经 meta 声明走预制库。

### 防遮挡契约（逐条照做）

1. tooltip 必写 `confine: true`——点按边缘月份时浮层不被卡片边界裁掉。
2. 图表容器 `height: 220~260px`；点按读数等说明行放图表**上方**。
3. `grid` 至少 `{left: 44, right: 16, top: 34, bottom: 28}`（有 legend 时 top ≥ 34 且 legend 固定 top: 0）。
4. 卡内不用绝对定位叠层；整卡内容控制在一屏内。

### 清新配色（深底卡片适配，照抄色板）

`#38BDF8`（天青）`#34D399`（薄荷）`#FBBF24`（暖黄）`#F472B6`（粉）`#A78BFA`（紫）`#F87171`（珊瑚）；
坐标轴 `#94A3B8`，分割线 `#EEF2F7`，tooltip 深底 `rgba(15,23,42,0.88)`。
禁用 `#ff7a45/#1a73e8` 等老旧组合；柱状加 `borderRadius: [4,4,0,0]`，折线 `smooth: true, width: 2.5`。

### OpenUI 程序包裹（手写通路的事故高发区，逐字遵守）

- 首行 `// summary: <一句话>`，第二行起 `root = ...`——**summary 只写一次、只放首行**，
  严禁把 summary 或整段程序再嵌进任何字符串内部（嵌套 = 校验失败 = 硬降级）。
- HTML 字符串作为 `Html(html = "...")` 的参数值，引号按 DSL 规则转义；
  一张卡一个 Html 组件，图片类组件合计 ≤ 12 张（含 src 类与 photos 数组元素）。
