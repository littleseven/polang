---
name: photo-browse
description: 把「看看照片」类浏览诉求组织成分组网格浏览卡的完整流程——search_media 取数（display='false'）→ CardHeader 分组头 + PhotoGrid 方形照片网格出卡 → refine_media_search 收窄换卡。当用户说「看看 / 给我看看 / 最近拍了什么 / 找些…的照片 / 翻翻…」等浏览语义时，先加载本技能。
---

# 照片浏览（分组网格浏览卡）

## 何时用 / 不用

- **用**：浏览语义——「看看」「给我看看」「最近拍了什么」「找些…的照片」「翻翻海边的」。
  一律走本流程：search_media `display = 'false'` 取数，自己出 OpenUI 浏览卡
  （分组头 + 照片网格 + 统计，比横滑轮播信息密度高、可继续交互）。
- **不用**：
  - 精确找某一张 / 快速核对存在性（「有没有那张…」）→ search_media `display = 'true'`
    让端上直接透出横滑图集卡，本技能不需要；
  - 做成可翻页精选相册（「做成相册 / 精选集 / 纪念册」）→ 改用 album-maker 技能；
  - 纯统计 / 盘点（「今年拍了多少」）→ 取数后出图表/统计卡，不出浏览卡。

## 流程

### 1. 取数

- 调 `search_media`，**display 恒传 'false'**：
  - query 含时间词（「上周」「去年夏天」「3 月」）**必须**换算 fromMs/toMs
    （拿不准当前日期先调 `server_time`）；
  - 人物精确查询用 person 参数（如「大宝」「儿子」），不要只拼进 query；
  - 只要人像/自拍/合照类传 hasFace = "true"；
  - 用户提到视频/录像/vlog 时 mediaType 传 "video"（或 "all"）。
- 工具只回「找到 N 张」+ 尾部 handle。N = 0 → 不出卡，用 Markdown 告知并建议放宽条件；
  N 过大可用 `refine_media_search` 收窄（收窄后 handle 会换新，用最新的）。
- **你看不到照片内容**（隐私设计：出端只有计数 + 句柄 + 每张的拍摄日期）。
  「相关度最高的一组」= 信任搜索相关度序，不做视觉挑选。

### 2. 出浏览卡（黄金骨架照此写，替换 handle/标题/N/语境）

```
photos = Query("local_handle", { handle: "local://scan/f9d33db29ed6#photos" }, [])
root = Card([
  CardHeader("海边的下午", subtitle = "共 37 张 · 精选前 12", actionLabel = "查看全部", action = @ToAssistant("查看全部 海边的照片")),
  PhotoGrid(photos = [photos[0].id, photos[1].id, photos[2].id, photos[3].id, photos[4].id, photos[5].id, photos[6].id, photos[7].id, photos[8].id, photos[9].id, photos[10].id, photos[11].id], columns = 3),
  Markdown("找到 37 张海边照片，先给你相关度最高的 12 张。想换个筛法（比如「只要日落的」）直接说。")
])
fallbackSummary = "37 张海边照片，数据在原设备"
```

排版与机制要点（每一条都有原因）：

- **CardHeader 分组头**：标题从用户 query 语境派生（地点/时间/主题），subtitle 写
  「共 N 张」（N > 12 时补「· 精选前 12」）；`actionLabel = "查看全部"` **必配**
  `action = @ToAssistant("查看全部 <分组语境>")`——缺 action 的「查看全部」点击无效
  （端侧有兜底但显式文案语境更准）。
- **PhotoGrid 装照片**：方形 1:1 + 人脸聚焦 + 密缝，是相册 APP 签名形态；
  **单件最多 12 张**，`photos` 数组写字面量展开（`photos[0].id` … `photos[11].id`）——
  N ≤ 12 时数组长度就写 N，不写越界下标；columns 缺省 3（2|3|4 可调）。
  ⚠️ ≥2 张照片网格一律 PhotoGrid，**禁用 Grid+Image 拼装**（帧随各图真实比例会
  犬牙交错，布局门禁直接退回）。
- **多分组**：搜索结果天然跨组时（如「这一年的照片」），可在一个 Card 内顺序排多组
  「CardHeader + PhotoGrid」（每月一组），但整卡位图上限 12——多组时给每组
  3~4 张代表作，总 PhotoGrid 元素数 ≤12。
- **收尾 Markdown**：一句话报数 + 引导下一步（换筛法/做相册），不放按钮堆。
- 照片引用一律 `photos[…].id`（`#photos` 投影，按搜索相关度排序，最多 60 张），
  禁字面量数字 id，禁止向用户展示 handle id。

### 3. 收窄换卡

- 用户看完说「只要日落的」「再筛一下 4 月的」→ 调 `refine_media_search`
  （constraint + 时间词换算 fromMs/toMs），拿**新 handle** 按第 2 步重新出浏览卡。
- 上一轮无搜索基数时设备会返回错误，此时改用 `search_media` 重新全局搜索。

## 发卡前自检清单

- [ ] search_media 的 display 传了 'false'，没有依赖横滑图集卡做浏览
- [ ] PhotoGrid 的 photos 数组 ≤12 项（多分组时全卡合计 ≤12），全部 `photos[i].id` 投影，无字面量数字 id
- [ ] N < 12 时数组长度 = N，没有越界下标
- [ ] CardHeader 有 actionLabel 就配了 action（@ToAssistant，文案带分组语境）
- [ ] 网格一律 PhotoGrid，没有 Grid+Image 拼装
- [ ] 没有向用户展示 handle id
- [ ] `root = ` 独占一行，程序不包 markdown 代码围栏
- [ ] 末行写了 `fallbackSummary = "N 张…，数据在原设备"`
