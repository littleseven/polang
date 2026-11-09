// 注入 QuickJS 沙箱的【图表生成器】bootstrap JS。
//
// 在 QuickJsEngine 创建后 eval 一次，定义全局 `Chart`：
// - Chart.bar({title, labels, values, unit?})
// - Chart.line({title, labels, values, unit?})
// - Chart.pie({title, labels, values})
// - Chart.timeline(timelineObj, {title, unit?, type?})
//
// 每个 API 返回 {chart: <svg 字符串>, summary: <给 LLM 的精简文字>}。
// - chart：含 width/height 的自描述 SVG，原生侧（AndroidSVG）直接渲染成图。
// - summary：紧凑文本（最高/最低/总计/占比），回传远程 LLM 做自然语言总结——
//   避免把整段 SVG 喂回 LLM（省 token）。
(function () {
  var W = 640, H = 380;
  var ML = 52, MR = 20, MT = 44, MB = 56;
  var PW = W - ML - MR, PH = H - MT - MB;
  var COLORS = ['#3B82F6', '#EF4444', '#10B981', '#F59E0B', '#8B5CF6', '#EC4899', '#06B6D4', '#84CC16', '#F97316', '#6366F1', '#14B8A6', '#A855F7'];

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function fmt(n) {
    n = Number(n) || 0;
    if (Math.abs(n) >= 10000) return (n / 1000).toFixed(1) + 'k';
    if (Number.isInteger(n)) return String(n);
    return (Math.round(n * 100) / 100).toString();
  }
  function pad2(n) { return (n < 10 ? '0' : '') + n; }
  function niceMax(v) {
    if (v <= 0) return 1;
    var pow = Math.pow(10, Math.floor(Math.log10(v)));
    var n = v / pow;
    var nice = n <= 1 ? 1 : (n <= 2 ? 2 : (n <= 5 ? 5 : 10));
    return nice * pow;
  }
  function ticks(maxV, count) {
    var mx = niceMax(maxV);
    count = count || 4;
    var step = mx / count;
    var arr = [];
    for (var i = 0; i <= count; i++) arr.push(step * i);
    return { max: mx, values: arr };
  }
  function header(title) {
    var s = '<svg xmlns="http://www.w3.org/2000/svg" width="' + W + '" height="' + H + '" viewBox="0 0 ' + W + ' ' + H + '" font-family="sans-serif">';
    s += '<rect width="' + W + '" height="' + H + '" fill="#ffffff"/>';
    if (title) s += '<text x="' + (W / 2) + '" y="26" text-anchor="middle" font-size="18" font-weight="600" fill="#111827">' + esc(title) + '</text>';
    return s;
  }
  function axis(t) {
    var s = '';
    for (var i = 0; i < t.values.length; i++) {
      var v = t.values[i];
      var y = MT + PH - (v / t.max) * PH;
      s += '<line x1="' + ML + '" y1="' + y + '" x2="' + (W - MR) + '" y2="' + y + '" stroke="#E5E7EB" stroke-width="1"/>';
      s += '<text x="' + (ML - 8) + '" y="' + (y + 4) + '" text-anchor="end" font-size="11" fill="#6B7280">' + fmt(v) + '</text>';
    }
    s += '<line x1="' + ML + '" y1="' + MT + '" x2="' + ML + '" y2="' + (MT + PH) + '" stroke="#9CA3AF" stroke-width="1.5"/>';
    s += '<line x1="' + ML + '" y1="' + (MT + PH) + '" x2="' + (W - MR) + '" y2="' + (MT + PH) + '" stroke="#9CA3AF" stroke-width="1.5"/>';
    return s;
  }
  function xLabels(labels) {
    var n = labels.length;
    var slot = PW / n;
    var s = '';
    for (var i = 0; i < n; i++) {
      var x = ML + slot * (i + 0.5);
      var lab = esc(labels[i]);
      var rot = n > 8 || String(labels[i]).length > 4;
      if (rot) {
        var yr = H - MB + 12;
        s += '<text x="' + x + '" y="' + yr + '" text-anchor="end" font-size="10" fill="#6B7280" transform="rotate(-40 ' + x + ' ' + yr + ')">' + lab + '</text>';
      } else {
        s += '<text x="' + x + '" y="' + (H - 18) + '" text-anchor="middle" font-size="11" fill="#6B7280">' + lab + '</text>';
      }
    }
    return s;
  }
  function summaryAxis(labels, values, unit) {
    var u = unit ? unit : '';
    var maxI = 0, minI = 0, sum = 0;
    for (var i = 0; i < values.length; i++) {
      if (values[i] > values[maxI]) maxI = i;
      if (values[i] < values[minI]) minI = i;
      sum += values[i];
    }
    return '共' + values.length + '项；最高 ' + labels[maxI] + '=' + fmt(values[maxI]) + u +
      '，最低 ' + labels[minI] + '=' + fmt(values[minI]) + u + '；总计 ' + fmt(sum) + u;
  }
  var FOOTER = '</svg>';

  function bar(spec) {
    var labels = spec.labels || [];
    var values = spec.values || [];
    var t = ticks(Math.max.apply(null, values.concat([1])));
    var slot = PW / values.length;
    var bw = slot * 0.62;
    var s = '';
    for (var i = 0; i < values.length; i++) {
      var h = (values[i] / t.max) * PH;
      var x = ML + slot * i + (slot - bw) / 2;
      var y = MT + PH - h;
      s += '<rect x="' + x + '" y="' + y + '" width="' + bw + '" height="' + h + '" fill="' + COLORS[i % COLORS.length] + '" rx="2"/>';
      s += '<text x="' + (x + bw / 2) + '" y="' + (y - 4) + '" text-anchor="middle" font-size="10" fill="#374151">' + fmt(values[i]) + '</text>';
    }
    return { chart: header(spec.title) + axis(t) + xLabels(labels) + s + FOOTER, summary: summaryAxis(labels, values, spec.unit) };
  }

  function line(spec) {
    var labels = spec.labels || [];
    var values = spec.values || [];
    var t = ticks(Math.max.apply(null, values.concat([1])));
    var n = values.length;
    var pts = '', dots = '';
    for (var i = 0; i < n; i++) {
      var x = ML + (n === 1 ? PW / 2 : PW * i / (n - 1));
      var y = MT + PH - (values[i] / t.max) * PH;
      pts += x + ',' + y + ' ';
      dots += '<circle cx="' + x + '" cy="' + y + '" r="3.5" fill="#3B82F6"/>';
    }
    var path = '<polyline points="' + pts.trim() + '" fill="none" stroke="#3B82F6" stroke-width="2.5" stroke-linejoin="round"/>';
    return { chart: header(spec.title) + axis(t) + xLabels(labels) + path + dots + FOOTER, summary: summaryAxis(labels, values, spec.unit) };
  }

  function pie(spec) {
    var labels = spec.labels || [];
    var values = spec.values || [];
    var total = 0;
    for (var i = 0; i < values.length; i++) total += (values[i] > 0 ? values[i] : 0);
    var cx = 180, cy = H / 2 + 6, r = 108;
    var slices = '', ang = -Math.PI / 2, parts = [];
    for (var i = 0; i < values.length; i++) {
      var v = values[i];
      if (v <= 0) continue;
      var a = (total > 0 ? v / total : 0) * Math.PI * 2;
      var x1 = cx + r * Math.cos(ang), y1 = cy + r * Math.sin(ang);
      var x2 = cx + r * Math.cos(ang + a), y2 = cy + r * Math.sin(ang + a);
      var large = a > Math.PI ? 1 : 0;
      slices += '<path d="M ' + cx + ' ' + cy + ' L ' + x1 + ' ' + y1 + ' A ' + r + ' ' + r + ' 0 ' + large + ' 1 ' + x2 + ' ' + y2 + ' Z" fill="' + COLORS[i % COLORS.length] + '"/>';
      parts.push({ label: labels[i], pct: total > 0 ? v / total * 100 : 0 });
      ang += a;
    }
    parts.sort(function (p, q) { return q.pct - p.pct; });
    var legend = '', ly = MT + 6;
    for (var i = 0; i < parts.length; i++) {
      legend += '<rect x="360" y="' + ly + '" width="12" height="12" fill="' + COLORS[i % COLORS.length] + '" rx="2"/>';
      legend += '<text x="380" y="' + (ly + 10) + '" font-size="12" fill="#374151">' + esc(parts[i].label) + ' ' + (Math.round(parts[i].pct * 10) / 10) + '%</text>';
      ly += 20;
    }
    var top3 = parts.slice(0, 3).map(function (p) { return p.label + ' ' + (Math.round(p.pct * 10) / 10) + '%'; }).join('，');
    return { chart: header(spec.title) + slices + legend + FOOTER, summary: '共' + parts.length + '类；' + top3 };
  }

  /**
   * 时间趋势快捷图：把 gallery.timeline 返回的 {时间戳:数量} 直接画成折线/柱状。
   * 自动把时间戳桶格式化为 YYYY-MM 标签，免去 LLM 手写日期格式化。
   * Chart.timeline(timelineObj, {title, unit?, type?}) —— type 默认 'line'，可 'bar'。
   */
  function timelineChart(obj, opts) {
    opts = opts || {};
    var keys = Object.keys(obj || {}).sort();
    var labels = keys.map(function (k) {
      var d = new Date(Number(k));
      return d.getFullYear() + '-' + pad2(d.getMonth() + 1);
    });
    var values = keys.map(function (k) { return obj[k]; });
    var s = { title: opts.title || '趋势', labels: labels, values: values };
    if (opts.unit) s.unit = opts.unit;
    return (opts.type === 'bar' ? bar : line)(s);
  }

  /**
   * ECharts 交互图卡（云端 run_gallery_script 产物通路，2026-11-08）：
   * return Chart.echarts(cfg) → {html: <自包含 HTML>, summary: <给 LLM 的文字>}。
   * html 由端侧直接渲染成 HTML 卡（不进 LLM 上下文），summary 回传 LLM 写分析——
   * LLM 零 HTML 编写，模板一次到位（polang-lib 预制 echarts、confine 防遮挡、
   * 读数行联动、主题配色），彻底消灭手写 HTML 的 DSL 嵌套事故面。
   *
   * cfg: { title?, x: [类目标签...], series: [{name, data, type?: 'bar'|'line', color?}],
   *        unit?: 数值单位, tip?: 读数行提示文案,
   *        theme?: 预置主题名（见 Chart.echartsThemes，默认 'fresh'）,
   *        style?: { 按字段覆盖主题样式 } }
   * 样式优先级：ECHARTS_BASE_STYLE < 预置主题 < cfg.style < series[].color（最高）。
   * style 可覆盖字段：palette（颜色数组）/ title / tip / axis / axisSoft / axisLine /
   *   splitLine / tooltipBg / tooltipText / height(120~600) / barMaxWidth / barRadius /
   *   lineWidth / smooth。
   * 典型用法（月度趋势；gallery.timeline 返回 {桶起始时间戳:数量}，标签需自行格式化）：
   *   var tl = await bridge.callAsync('gallery.timeline', {bucketMs: 2592000000});
   *   var keys = Object.keys(tl).sort();
   *   var x = keys.map(function (k) { var d = new Date(Number(k)); return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2); });
   *   return Chart.echarts({ title: '相册月度趋势', x: x, theme: 'ocean',
   *     series: [{name: '媒体数', data: keys.map(function (k) { return tl[k]; })}], unit: '个' });
   * 注意：gallery.timeline 不分照片/视频（无 mediaType 参数），多序列需自行用 gallery.query 聚合。
   */
  var ECHARTS_BASE_STYLE = {
    palette: ['#38BDF8', '#34D399', '#FBBF24', '#F472B6', '#A78BFA', '#F87171'],
    title: '#0F172A',
    tip: '#94A3B8',
    axis: '#64748B',
    axisSoft: '#94A3B8',
    axisLine: '#CBD5E1',
    splitLine: '#EEF2F7',
    tooltipBg: 'rgba(15,23,42,0.88)',
    tooltipText: '#F1F5F9',
    height: 240,
    barMaxWidth: 26,
    barRadius: 4,
    lineWidth: 2.5,
    smooth: true
  };

  /** 预置主题（2026-11-09）：只声明与 base 不同的字段，未知主题名静默回落 base。 */
  var ECHARTS_THEMES = {
    fresh: {},
    ocean: { palette: ['#0EA5E9', '#2563EB', '#06B6D4', '#6366F1', '#22D3EE', '#93C5FD'] },
    sunset: { palette: ['#FB923C', '#F59E0B', '#F97316', '#EF4444', '#FBBF24', '#FDBA74'] },
    violet: { palette: ['#8B5CF6', '#A78BFA', '#C084FC', '#7C3AED', '#F472B6', '#DDD6FE'] },
    forest: { palette: ['#10B981', '#34D399', '#84CC16', '#059669', '#4ADE80', '#A3E635'] }
  };

  /**
   * 图表卡兜底文案五语表（[I18N] 红线，2026-11-09）：端侧每次脚本执行前把当前应用语言
   * 写入 globalThis.__polangLang（BCP47：zh-CN/zh-TW/en/es/fr），本函数在每次发卡时解析——
   * 用户切换应用语言后新图即新语言，进程级常驻 runtime 无需重建。
   * 仅覆盖「模板兜底」文案（读数行默认提示/默认系列名/读数分隔符）；
   * title/tip/unit 由 LLM 按对话语言提供，不在此表。
   */
  var CHART_STRINGS = {
    'zh-CN': { tipDefault: '点按图表查看数值', seriesDefault: '系列', readoutSep: '：' },
    'zh-TW': { tipDefault: '點按圖表查看數值', seriesDefault: '系列', readoutSep: '：' },
    'en': { tipDefault: 'Tap the chart to see values', seriesDefault: 'Series ', readoutSep: ': ' },
    'es': { tipDefault: 'Toca el gráfico para ver los valores', seriesDefault: 'Serie ', readoutSep: ': ' },
    'fr': { tipDefault: 'Touchez le graphique pour voir les valeurs', seriesDefault: 'Série ', readoutSep: ': ' }
  };

  function chartStrings() {
    var tag = String(globalThis.__polangLang || 'zh-CN');
    return CHART_STRINGS[tag] || CHART_STRINGS[tag.split('-')[0]] || CHART_STRINGS['zh-CN'];
  }

  function echartsCard(cfg) {
    cfg = cfg || {};
    // 样式解析：base < theme < cfg.style；数值字段钳制范围防脏值打爆布局。
    var t = {}, k;
    for (k in ECHARTS_BASE_STYLE) t[k] = ECHARTS_BASE_STYLE[k];
    var themeOverride = ECHARTS_THEMES[cfg.theme] || {};
    for (k in themeOverride) t[k] = themeOverride[k];
    var styleOverride = cfg.style || {};
    for (k in styleOverride) {
      if (styleOverride[k] !== null && styleOverride[k] !== undefined) t[k] = styleOverride[k];
    }
    var palette = (Object.prototype.toString.call(t.palette) === '[object Array]' && t.palette.length)
      ? t.palette : ECHARTS_BASE_STYLE.palette;
    var height = parseInt(t.height, 10);
    if (!(height >= 120 && height <= 600)) height = 240;
    var barMaxWidth = parseInt(t.barMaxWidth, 10);
    if (!(barMaxWidth > 0 && barMaxWidth <= 200)) barMaxWidth = 26;
    var barRadius = parseInt(t.barRadius, 10);
    if (!(barRadius >= 0 && barRadius <= 50)) barRadius = 4;
    var lineWidth = Number(t.lineWidth);
    if (!(lineWidth > 0 && lineWidth <= 10)) lineWidth = 2.5;
    var smooth = !(t.smooth === false || t.smooth === 'false' || t.smooth === 0);
    var x = cfg.x || [];
    var unit = cfg.unit || '';
    var strs = chartStrings();
    var sers = (cfg.series || []).map(function (s, i) {
      var ser = {
        name: String(s.name == null ? (strs.seriesDefault + (i + 1)) : s.name),
        type: s.type === 'line' ? 'line' : 'bar',
        data: s.data || [],
        itemStyle: { color: s.color || palette[i % palette.length] }
      };
      if (ser.type === 'line') {
        ser.smooth = smooth;
        ser.symbolSize = 6;
        ser.lineStyle = { width: lineWidth };
      } else {
        ser.barMaxWidth = barMaxWidth;
        ser.itemStyle.borderRadius = [barRadius, barRadius, 0, 0];
      }
      return ser;
    });
    var multi = sers.length > 1;
    var option = {
      animation: false,
      tooltip: {
        trigger: 'axis',
        confine: true,
        backgroundColor: t.tooltipBg,
        borderWidth: 0,
        textStyle: { color: t.tooltipText, fontSize: 12 }
      },
      grid: { left: 8, right: 16, top: multi ? 34 : 12, bottom: 4, containLabel: true },
      xAxis: {
        type: 'category', data: x,
        axisLine: { lineStyle: { color: t.axisLine } },
        axisTick: { show: false },
        axisLabel: { color: t.axis, fontSize: 11 }
      },
      yAxis: {
        type: 'value',
        axisLabel: { color: t.axisSoft, fontSize: 11 },
        splitLine: { lineStyle: { color: t.splitLine } }
      },
      series: sers
    };
    if (multi) {
      option.legend = { top: 2, right: 8, itemWidth: 14, itemHeight: 8, textStyle: { color: t.axis, fontSize: 11 } };
    }
    var titleHtml = cfg.title
      ? '<div style="font-size:15px;font-weight:600;color:' + t.title + ';padding:2px 4px 6px">' + esc(cfg.title) + '</div>' : '';
    var html = '<meta name="polang-lib" content="echarts">' +
      '<div style="font-family:-apple-system,\'PingFang SC\',sans-serif;background:transparent">' +
      titleHtml +
      '<div id="tip" style="font-size:12px;color:' + t.tip + ';min-height:18px;padding:0 4px 4px">' +
      esc(cfg.tip || strs.tipDefault) + '</div>' +
      '<div id="c" style="width:100%;height:' + height + 'px"></div></div>' +
      '<script>(function(){var c=echarts.init(document.getElementById("c"));c.setOption(' + JSON.stringify(option) + ');' +
      'var tip=document.getElementById("tip"),def=tip.textContent,xs=' + JSON.stringify(x) + ',u=' + JSON.stringify(unit) + ';' +
      'c.on("updateAxisPointer",function(e){var i=e.dataIndexInside;if(i==null||i<0||i>=xs.length)return;' +
      'var ps=c.getOption().series.map(function(s){return s.name+" "+(s.data[i]==null?0:s.data[i])+u;});' +
      'tip.textContent=xs[i]+' + JSON.stringify(strs.readoutSep) + '+ps.join(" · ");});' +
      'c.on("globalout",function(){tip.textContent=def;});' +
      'window.addEventListener("resize",function(){c.resize();});})();</scr' + 'ipt>';
    var parts = sers.map(function (s) {
      var maxI = 0, sum = 0;
      for (var i = 0; i < s.data.length; i++) {
        if (Number(s.data[i]) > Number(s.data[maxI])) maxI = i;
        sum += Number(s.data[i]) || 0;
      }
      return s.name + '：峰值 ' + (x[maxI] == null ? '-' : x[maxI]) + '=' + fmt(s.data[maxI] || 0) + unit +
        '，合计 ' + fmt(sum) + unit;
    });
    var summary = (cfg.title ? cfg.title + '——' : '') + '共' + x.length + '个区间；' + parts.join('；');
    return { html: html, summary: summary };
  }

  globalThis.Chart = { bar: bar, line: line, pie: pie, timeline: timelineChart, echarts: echartsCard, echartsThemes: Object.keys(ECHARTS_THEMES) };
})();
