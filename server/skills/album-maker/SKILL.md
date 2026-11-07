---
name: album-maker
description: 把一批照片做成「可翻页精选相册卡」的完整流程——圈选（search_media/refine_media_search）→ 按相关度每页 9 张分页 → 逐张起名配标签 → 产出 3×3 相册卡。当用户要求把照片「做成相册 / 精选集 / 照片集 / 纪念册」时，先加载本技能。
---

# 制作相册（分页精选卡）

## 产出形态（先理解，再动手）

- **一回合产出一页**：每个终结程序 = 一页相册卡（3×3 = 9 张照片，每张下方有名字和标签）。
  单卡位图硬上限 12 张（端侧 OOM 护栏，服务端校验违规会整卡退回重写），
  **禁止**试图在一张卡里塞多页（Tabs/Carousel/多 Card 都逃不过同一个位图计数）。
- **翻页 = 新回合**：卡上「上一页 / 下一页」按钮用 `@ToAssistant("相册第 N 页")` 触发，
  你收到这类消息后**复用同一句柄**发对应页的新卡。页面按序追加在对话时间线里，
  用户上下滑动即可回翻——这就是「可翻页」的真实形态。DSL 没有条件渲染，
  不要尝试用 state 变量在卡内切换页面。
- **照片引用**：一律 `photos[i].id`（`#photos` 投影，按搜索相关度排序，最多 60 张），
  禁字面量数字 id，禁止向用户展示 handle id。

## 流程

### 1. 圈选候选集

- 按用户描述调 `search_media`：
  - query 含时间词（如「上周」「去年夏天」「3 月」）**必须**换算 fromMs/toMs
    （拿不准当前日期先调 `server_time`）；
  - 人物精确查询用 person 参数（如「大宝」「儿子」），不要只拼进 query；
  - 只要人像/自拍/合照类传 hasFace = "true"；
  - display 传 "false"——相册卡自己排版，不要横滑轮播卡。
- 工具只回「找到 N 张」+ 尾部 handle。N = 0 → 不发卡，用 Markdown 告知并建议放宽条件；
  N 过大可用 `refine_media_search` 收窄（收窄后 handle 会换新，用最新的）。
- **你看不到照片内容**（隐私设计：出端只有计数 + 句柄 + 每张的拍摄日期）。
  「精选」= 信任搜索相关度序，不做视觉去重；用户嫌选不准时，
  引导他用 `refine_media_search` 加条件（如「只要海边的」）。

### 2. 分页

- 每页 9 张：第 1 页 = photos[0]..photos[8]，第 2 页 = photos[9]..photos[17]，以此类推。
- 总页数 = ceil(min(N, 60) / 9)，最多 7 页（60 张是投影上限）。
  超出 60 的部分在首页副标题说明「精选自 N 张」即可，不要承诺覆盖全部。
- 用户没给主题/时间范围时（只说「做个相册」），先用一句 Markdown 确认范围，
  或默认取「最近三个月」并说明你的假设。

### 3. 起名与标签

- **名字**：每张 2~6 字短标题，从用户 query 语境 + 该张照片的 date 派生
  （如 query「杭州」+ date 2026-07-18 →「西湖晨光」「仲夏午后」）。
  诚实原则：你看不到图，名字是主题性命名——**不要编造看不见的具体事物**
  （不知道有猫就不要写「猫」）；拿不准就用日期式命名（「7 月 18 日 · 其一」）。
- **标签**：每张 1~2 个主题词，同样自 query 语境派生（如「海边」「日落」「人像」），
  用 CAPTION 弱色显示在名字下方。

### 4. 产出程序（骨架照此写，替换成真实 handle/名字/页码）

```
photos = Query("local_handle", { handle: "local://scan/f9d33db29ed6#photos" }, [])
root = Card([
  CardHeader("杭州精选", subtitle = "第 1 页 · 共 3 页 · 精选自 26 张"),
  Row([
    Column([Image(photos[0].id, aspect = 1), Text("西湖晨光", maxLines = 1), Text("湖景 · 清晨", "CAPTION", maxLines = 1)]),
    Column([Image(photos[1].id, aspect = 1), Text("断桥残影", maxLines = 1), Text("湖景", "CAPTION", maxLines = 1)]),
    Column([Image(photos[2].id, aspect = 1), Text("柳浪闻莺", maxLines = 1), Text("公园", "CAPTION", maxLines = 1)])
  ], align = "start"),
  Row([
    Column([Image(photos[3].id, aspect = 1), Text("灵隐午后", maxLines = 1), Text("古刹", "CAPTION", maxLines = 1)]),
    Column([Image(photos[4].id, aspect = 1), Text("九溪烟树", maxLines = 1), Text("溪谷", "CAPTION", maxLines = 1)]),
    Column([Image(photos[5].id, aspect = 1), Text("龙井茶山", maxLines = 1), Text("山野", "CAPTION", maxLines = 1)])
  ], align = "start"),
  Row([
    Column([Image(photos[6].id, aspect = 1), Text("钱塘暮色", maxLines = 1), Text("江景 · 黄昏", "CAPTION", maxLines = 1)]),
    Column([Image(photos[7].id, aspect = 1), Text("河坊夜市", maxLines = 1), Text("夜景", "CAPTION", maxLines = 1)]),
    Column([Image(photos[8].id, aspect = 1), Text("满陇桂雨", maxLines = 1), Text("花事", "CAPTION", maxLines = 1)])
  ], align = "start"),
  Row([Button("下一页 →", @ToAssistant("相册第 2 页"), "secondary")])
])
fallbackSummary = "26 张精选，数据在原设备"
```

排版要点：

- `Image(photos[i].id, aspect = 1)`：方形统一造型（合法的「统一方块拼图」场景），
  3×3 网格才整齐；名字/标签都写 maxLines = 1 防撑破网格。
- Row 内 3 个 Column 等宽分栏，`align = "start"` 让高矮不一的列顶线对齐。
- 最后一行不足 3 张时，Row 里只放实际数量的 Column（如 2 个），不要放空占位。
- 按钮：第 1 页只放「下一页」；中间页放「上一页」+「下一页」两个；
  最后一页只放「上一页」。一卡 Button ≤3 个，每个都必须有 action（死按钮会被拒）。

### 5. 翻页回合

- 收到「相册第 N 页」「下一页」「上一页」类消息 = 翻页意图。
  从对话历史找到最近一次相册卡的 handle 与总页数，发第 N 页
  （索引段 photos[(N-1)×9 .. min(N×9, 总数)-1]），名字/标签风格与前页保持一致。
- handle 失效（端上数据缺失或工具报错）→ 用原搜索条件重新 `search_media`
  拿新 handle，再发对应页。
- 用户主动要求重发某页才重发，否则不要重复发已发过的页。

## 分享（当前版本不做）

卡内**不要**放「分享」按钮：云端工具目录目前只有
get_gallery_summary / search_media / refine_media_search 三个只读工具，
你执行不了分享，放了就是死按钮。用户提分享时，用 Markdown 告知
「整本分享能力正在接入，可以先到相册里长按多选分享」。

## 发卡前自检清单

- [ ] 每页 ≤9 个 Image，整卡位图 ≤12
- [ ] photo 全部是 `photos[i].id`，无字面量数字 id
- [ ] 按钮都有 action，页码算对、首页没有「上一页」
- [ ] 名字/标签是主题性命名，没编造看不见的具体事物
- [ ] 没有向用户展示 handle id
- [ ] `root = ` 独占一行，程序不包 markdown 代码围栏
- [ ] 末行写了 `fallbackSummary = "N 张精选，数据在原设备"`
