---
name: gallery-script
description: run_gallery_script 端侧 JS 沙箱的完整 handler 参考与调用约定——gallery.*/media.*/face.*/tag.* 只读取数目录、capability.dispatch 写操作清单、并发取数与批量删除示例。当需要用 run_gallery_script 执行相册盘点/统计/批量操作脚本时，先加载本技能。
---

# 相册 JS 沙箱（run_gallery_script）handler 参考

## 调用约定（先读，违反即报错）

- 所有 handler 均为异步，**必须用 `await bridge.callAsync(name, args)` 调用**（`bridge.call` 已禁用，调用会报错）。
- 取数类 handler 只读、数据不出端；删除/收藏等写操作走 `capability.dispatch`，会在端侧弹窗等用户确认。
- 用 `return` 把结果对象回传给你做总结；在 JS 内完成组合计算（如某标签占比 = query.total / summary.totalMedia；环比 = 本月/上月-1）。
- **画交互图表（ECharts）**：脚本中调用 `Chart.echarts({title?, x:[类目标签], series:[{name, data, type?: 'bar'|'line', color?}], unit?, tip?, theme?: 预置配色 fresh(默认)/ocean/sunset/violet/forest, style?: 按字段覆盖样式})` 即自动渲染成图卡上屏——**调用即发卡**（HTML 不回传，你收到 summary 文字用于撰写分析）；`return` 只写统计小对象（计数/峰值/分布），禁止把 `Chart.echarts` 的返回对象（含 html）塞进 return；勿手写任何 HTML/ECharts/SVG（SVG 的 Chart.bar/line/pie/timeline 云端不渲染）。
- 可并发取数：

```js
var r = await Promise.all([
  bridge.callAsync('gallery.summary', {}),
  bridge.callAsync('gallery.tags', {})
]);
var s = r[0], t = r[1];
```

## 只读 handler 目录

| handler | 参数 | 返回 |
|---------|------|------|
| `gallery.summary` | `{}` | 相册聚合统计：`totalPhotos/totalVideos/totalMedia/hasFaceCount/personClusterCount/namedPersonCount/labeledCount/unlabeledCount/semanticEncodedCount/remainingPass1/remainingPass3/isScanning/currentPass/recommendation` |
| `gallery.query` | `{label?,ocr?,location?,fromMs?,toMs?,hasFace?,person?,limit?}` | 结构化过滤命中 `{ids:[...], total:N}`（多维 AND，全可选；ids 已截断到 limit，total 为未截断真实数） |
| `gallery.tags` | `{}` | 实际打标标签分布 `{标签:照片数}`（按计数降序 top 50） |
| `gallery.timeline` | `{fromMs?,toMs?,bucketMs?}` | 按时间分桶统计 `{桶起始时间戳:照片数}`（默认按月，bucketMs=2592000000=月/31536000000=年） |
| `gallery.intersect` | `{idsA:[...],idsB:[...],op:"intersect\|union\|diff"}` | 集合交并差 `{ids:[...],total:N}`（用于多次 query 结果交叉，如旅行+人脸） |
| `gallery.stats_by_tag` | `{label?,hasFace?,fromMs?,toMs?}` | 条件过滤后的标签分布（如人像照片内的场景标签） |
| `gallery.stats_by_city` | `{topN?}` | 按城市分组的媒体计数分布 `{城市:照片数}`（topN 默认 10 上限 50） |
| `media.meta` | `id` | 单张元数据 `{id,type,captureMs,fileName,labels:[...],locationName,city,hasFace,faceId,aestheticScore,faceQualityScore}`（不含路径/GPS/OCR/向量） |
| `media.batch_meta` | `[id1,id2,...]` | 批量元数据 `[{...},...]`（上限 50，避免循环调 media.meta） |
| `face.cluster` | `{topN?}` | 人脸聚类盘点 `{clusterCount,namedCount,totalEmbeddings,unassignedEmbeddings,topPersons:[{personId,name,faceCount,coverMediaId}]}`（topN 默认 10 上限 50，不含 embedding 原始数据） |
| `tag.audit` | `{topN?}` | 打标覆盖审计 `{totalMedia,unlabeledCount,neverScannedCount,lastScanAt,outOfVocabTags:{标签:照片数}}`（词表外标签 topN 默认 10 上限 50） |
| `tag.scan_status` | `{}` | TAG 扫描会话状态快照 `{active,state,sessionId,currentPass,processed,total,pending,failed,estimatedRemainingMs}`（只读查询，绝不触发扫描；无会话时仅回 `{active:false,state:null}`） |

## 写操作（capability.dispatch，弹窗确认）

用 `await bridge.callAsync('capability.dispatch', {method, params})`。写操作会在端侧弹窗等用户确认（拒绝或超时 Promise 会 reject，**必须 try/catch**）。支持的 method：

| method | params | 说明 |
|--------|--------|------|
| `delete_media` | `{ids:[数字id,...]}` | 删除，不可恢复 |
| `favorite_media` | `{id:数字id, favorite:true/false}` | 收藏/取消收藏 |
| `select_media` | `{id:数字id, selected:true/false}` | 选中/取消选中 |
| `remember_fact` | `{content:文本, category?:文本}` | 记事实 |
| `forget_fact` | `{fact_id?:数字id, query?:文本}` | 忘事实 |
| `remember_person_relation` | `{name:人物名, relation:关系称谓}` | 记人物关系 |
| `forget_person_relation` | `{name:人物名}` | 忘人物关系 |
| `get_gallery_summary` | `{}` | 只读直通 |
| `recall_memory` | `{query:文本}` | 只读直通 |
| `query_person_relation` | `{name?:人物名}` | 只读直通 |

其余 method 会报错。

## UI 效果契约

- 本工具自身只回传数据/文本给你总结，不直接产生界面。
- `return` 的对象若含 `ids` 数组（gallery.query/intersect 的命中），端侧会自动把这些照片补展示为横滑卡片——**纯统计/盘点脚本不要 return ids**。
- 需要给用户看图时优先用 `search_media`（直接出卡片）。

## 示例

简单取数：

```js
var s = await bridge.callAsync('gallery.summary', {});
var t = await bridge.callAsync('gallery.tags', {});
return { total: s.totalMedia, topTags: t };
```

完整流程（找出截图标签照片并批量删除）：

```js
var q = await bridge.callAsync('gallery.query', {label: '截图', limit: 200});
if (q.ids.length === 0) { return {deleted: 0}; }
try {
  var r = await bridge.callAsync('capability.dispatch', {method: 'delete_media', params: {ids: q.ids}});
  return {deleted: q.total, result: r};
} catch (e) {
  return {deleted: 0, cancelled: true, reason: String(e)};
}
```
