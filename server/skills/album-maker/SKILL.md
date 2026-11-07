---
name: album-maker
description: 把一批照片做成「可翻页精选相册卡」的完整流程——圈选（search_media/refine_media_search）→ 每页 9 张 → 逐张起名配标签 → 产出一张卡内翻页的 3×3 相册卡。当用户要求把照片「做成相册 / 精选集 / 照片集 / 纪念册」时，先加载本技能。
---

# 制作相册（卡内翻页精选卡）

## 产出形态（先理解，再动手）

- **一张卡 = 一整本相册**：3×3 网格，每页 9 张，每张下方有名字、图上有标签角标。
- **翻页在卡内完成**：「上一页 / 下一页」按钮用 `@Set($page, …)` 写卡内状态，
  整卡就地重渲染换页——**禁止**用 `@ToAssistant` 发追问翻页（那会在时间线里刷出一串新卡，
  用户体验很差）。OpenUI 引擎支持计算下标与三元表达式，照片、名字、标签全部按
  `$page` 动态取值，一次写程序、页页复用。
- **照片引用**：一律 `photos[…].id`（`#photos` 投影，按搜索相关度排序，最多 60 张），
  禁字面量数字 id，禁止向用户展示 handle id。
- 单卡位图上限 12：每页恰好 9 个 Image 调用位，合规。

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

### 2. 计算页数

- 入选总数 M = min(N, 60)；总页数 P = ceil(M / 9)（M=26 → P=3，M=11 → P=2）。
- P 与 M 是**字面量**写进程序（副标题、翻页上界、越界守卫都要用），
  不要在程序里现算。

### 3. 起名与标签（平行数组）

- **名字**：每张 2~6 字短标题，从用户 query 语境 + 该张照片的 date 派生
  （如 query「杭州」+ date 2026-07-18 →「西湖晨光」「仲夏午后」）。
  诚实原则：你看不到图，名字是主题性命名——**不要编造看不见的具体事物**
  （不知道有猫就不要写「猫」）；拿不准就用日期式命名（「7 月 18 日 · 其一」）。
- **标签**：每张 1~2 个主题词，同样自 query 语境派生（如「湖景」「日落」「人像」）。
- 名字和标签**必须按入选顺序写成两个字面量数组** `names = […]` / `tags = […]`，
  长度 = M，与 photos 投影顺序一一对应。格子里用 `names[$page * 9 + i]` 取值——
  这样翻页时名字和标签才会跟着照片一起换。

### 4. 产出程序（黄金骨架照此写，替换 handle/相册名/M/P/名字/标签）

```
$page = 0
photos = Query("local_handle", { handle: "local://scan/f9d33db29ed6#photos" }, [])
names = ["西湖晨光", "断桥残影", "柳浪闻莺", "灵隐午后", "九溪烟树", "龙井茶山", "钱塘暮色", "河坊夜市", "满陇桂雨", "北高峰远眺", "吴山天风"]
tags = ["湖景", "湖景", "公园", "古刹", "溪谷", "山野", "江景", "夜景", "花事", "山景", "城景"]
root = Card([
  CardHeader("杭州精选", subtitle = "11 张 · 第 " + ($page + 1) + " / 2 页"),
  Row([
    Column([Box([Image(photos[$page * 9 + 0].id, aspect = 1), Tag(tags[$page * 9 + 0])], align = "bottomStart"), Text(names[$page * 9 + 0], maxLines = 1)], spacing = "xs"),
    Column([Box([Image(photos[$page * 9 + 1].id, aspect = 1), Tag(tags[$page * 9 + 1])], align = "bottomStart"), Text(names[$page * 9 + 1], maxLines = 1)], spacing = "xs"),
    ($page * 9 + 2 < 11) ? Column([Box([Image(photos[$page * 9 + 2].id, aspect = 1), Tag(tags[$page * 9 + 2])], align = "bottomStart"), Text(names[$page * 9 + 2], maxLines = 1)], spacing = "xs") : null
  ], align = "start"),
  Row([
    Column([Box([Image(photos[$page * 9 + 3].id, aspect = 1), Tag(tags[$page * 9 + 3])], align = "bottomStart"), Text(names[$page * 9 + 3], maxLines = 1)], spacing = "xs"),
    Column([Box([Image(photos[$page * 9 + 4].id, aspect = 1), Tag(tags[$page * 9 + 4])], align = "bottomStart"), Text(names[$page * 9 + 4], maxLines = 1)], spacing = "xs"),
    ($page * 9 + 5 < 11) ? Column([Box([Image(photos[$page * 9 + 5].id, aspect = 1), Tag(tags[$page * 9 + 5])], align = "bottomStart"), Text(names[$page * 9 + 5], maxLines = 1)], spacing = "xs") : null
  ], align = "start"),
  Row([
    Column([Box([Image(photos[$page * 9 + 6].id, aspect = 1), Tag(tags[$page * 9 + 6])], align = "bottomStart"), Text(names[$page * 9 + 6], maxLines = 1)], spacing = "xs"),
    Column([Box([Image(photos[$page * 9 + 7].id, aspect = 1), Tag(tags[$page * 9 + 7])], align = "bottomStart"), Text(names[$page * 9 + 7], maxLines = 1)], spacing = "xs"),
    ($page * 9 + 8 < 11) ? Column([Box([Image(photos[$page * 9 + 8].id, aspect = 1), Tag(tags[$page * 9 + 8])], align = "bottomStart"), Text(names[$page * 9 + 8], maxLines = 1)], spacing = "xs") : null
  ], align = "start"),
  Row([
    $page > 0 ? Button("← 上一页", @Set($page, $page - 1), "secondary") : null,
    $page < 1 ? Button("下一页 →", @Set($page, $page + 1), "secondary") : null
  ], align = "center")
])
fallbackSummary = "11 张精选，数据在原设备"
```

排版与机制要点（每一条都有原因）：

- **三行九格，格 = Column([Box([Image, Tag]), Text(名字)])**：
  Box 把标签以角标形式叠在照片左下（`align = "bottomStart"`，官方「图上叠角标」形态），
  名字在照片下方 maxLines = 1 防撑破网格；Column 用 `spacing = "xs"` 收紧图文间距；
  `Image(…, aspect = 1)` 方形统一造型，3×3 才整齐。
- **越界守卫**：只给「最后一页可能越界」的格子包三元——条件写字面量 M
  （`($page * 9 + 8 < 11) ? Column(…) : null`），数组里 null 会被端上丢弃，
  最后一页少几张就少几格，不留空位不报错。越界下标本身求值为 null 也不崩，
  但三元守卫让布局更干净。
- **翻页按钮**：`@Set($page, $page ± 1)`——引擎在每次状态变更后重求值整棵树，
  按钮里的 `$page ± 1` 总是相对当前页算出目标页。显示条件用字面量：
  上一页 = `$page > 0`，下一页 = `$page < P-1`（例中 P=2 所以是 `$page < 1`）。
  数组里 null 被丢弃，首页只剩「下一页」、末页只剩「上一页」。
- **页码副标题**：`"11 张 · 第 " + ($page + 1) + " / 2 页"`——数字拼接自动转整数字符串，
  随翻页联动刷新。
- **$page 是 Num 状态，重开卡片会回到第 1 页**（Num 不持久化）——这是预期行为，不用处理。
- 禁止事项：禁 `@ToAssistant` 翻页；禁 Tabs/Carousel 装多页（位图计数按程序静态算，
  超 12 整卡退回）；禁 Grid 装 Image（布局门禁）；一卡 Button ≤3。

### 5. 圈选不足 9 张 / 用户没给范围

- M ≤ 9：一页装下，不显示翻页按钮（P=1 时两个按钮条件都不成立，自然隐藏——
  程序骨架不用改，页码写「第 1 / 1 页」）。
- 用户没给主题/时间（只说「做个相册」）：先用一句 Markdown 确认范围，
  或默认取「最近三个月」并说明你的假设。

## 分享（当前版本不做）

卡内**不要**放「分享」按钮：云端工具目录目前只有
get_gallery_summary / search_media / refine_media_search 三个只读工具，
你执行不了分享，放了就是死按钮。用户提分享时，用 Markdown 告知
「整本分享能力正在接入，可以先到相册里长按多选分享」。

## 发卡前自检清单

- [ ] 每页恰好 9 个 Image 调用位（位图计数 9 ≤ 12）
- [ ] photo 全部是 `photos[$page * 9 + i].id`，无字面量数字 id
- [ ] `names` / `tags` 数组长度 = M，与投影顺序一一对应，下标写法与照片一致
- [ ] 越界格都包了三元守卫（条件用字面量 M），按钮显示条件用字面量 P
- [ ] 翻页只用 `@Set($page, …)`，没有 `@ToAssistant` 翻页
- [ ] 名字/标签是主题性命名，没编造看不见的具体事物
- [ ] 没有向用户展示 handle id
- [ ] `root = ` 独占一行，程序不包 markdown 代码围栏
- [ ] 末行写了 `fallbackSummary = "M 张精选，数据在原设备"`
