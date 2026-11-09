package com.mamba.picme.features.chat.js

import android.content.Context

/**
 * 图表生成器 bootstrap JS 的加载器。
 *
 * 脚本本体在 `assets/js/chart_bootstrap.js`（独立 JS 文件便于 IDE 语法高亮与维护）。
 * 在 [com.mamba.picme.features.chat.js.QuickJsEngine] 创建后 eval 一次，定义全局 `Chart`：
 * - Chart.bar({title, labels, values, unit?})
 * - Chart.line({title, labels, values, unit?})
 * - Chart.pie({title, labels, values})
 * - Chart.timeline(timelineObj, {title, unit?, type?})
 * - Chart.echarts({title?, x, series, unit?, tip?})——ECharts 交互图卡固定模板
 *   （云端 run_gallery_script 产物通路：返回 {html, summary}，端侧渲染 HTML 卡，
 *   HTML 不进 LLM 上下文；SVG 系 API 云端不渲染）
 *
 * SVG 系 API 返回 `{chart: <svg 字符串>, summary: <给 LLM 的精简文字>}`。
 *
 * @see com.mamba.picme.features.chat.js.QuickJsEngine
 */
internal fun loadChartBootstrapJs(context: Context): String =
    context.assets.open("js/chart_bootstrap.js").bufferedReader().use { it.readText() }
