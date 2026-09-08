# Google Play Store Listing（ASO 优化文案）

破浪相册 / PoLang 的 Google Play 商店上架文案。

> **⚠️ 路径迁移（2026-08-20）**：三语**文案**文件已迁移至 GPP（gradle-play-publisher）约定目录
> **`androidApp/src/main/play/listings/{en-US,zh-CN,zh-TW}/`**，文件名不变（`title.txt` /
> `short-description.txt` / `full-description.txt`），由 `./scripts/play-publish.sh --listing-only`
> 或 CI 自动同步到 Play Console，**不再手动粘贴**。**图形素材仍保留在本目录**（见下文「图形素材」）。
> 运维手册：`docs/05-DEVELOPMENT/GOOGLE_PLAY_RELEASE_AUTOMATION.md`。

## 目录结构（文案，新）

```
androidApp/src/main/play/
├── default-language.txt        ← zh-CN（以 Console 线上为准，2026-08-20 bootstrap 实测；GPP 翻译合并基准）
├── listings/
│   ├── en-US/   ← 全球英文
│   │   ├── title.txt               (≤30 字符)
│   │   ├── short-description.txt   (≤80 字符)
│   │   └── full-description.txt    (≤4000 字符)
│   ├── zh-CN/   ← 简体中文（海外华人 / 简中用户）
│   └── zh-TW/   ← 繁体中文（台湾 / 香港）
└── release-notes/<lang>/<track>.txt  ← 发布说明（≤500 字符，由 play-publish.sh --notes 写入）
```

## 字段权重（ASO 关键）

Google Play **没有独立关键词字段**，它索引标题 + 短描述 + 完整描述的全文。权重排序：

| 字段 | 字符上限 | 权重 |
|------|----------|------|
| 标题 title | 30 | 🔴 最高（最强排名信号） |
| 短描述 short-description | 80 | 🟠 高（索引 + 转化） |
| 完整描述 full-description | 4000 | 🟡 中（密度重复 3–5 次） |

## 文案外的 Console 配置（仍需手动）

- **Tags**：勾选最相关的 5 个（优先 Photo Gallery / Photo Editor / Beauty / Filter / Camera）
- **Category**：保持 `Photography`
- **App name** 字段即标题，改后会触发重新审核

> 本目录是**商店上架素材**，独立于 App 内 `strings.xml`（应用内显示名）。改动不影响 App 代码。

## 图形素材（2026-08-23 槽位重排：6→8）

每个语言目录（Play Console 手机截图上限 8 张，en-US 已用满）：

```
google-play-listing/<locale>/
├── feature-graphic.png        1024×500 置顶横幅（Play Console → Main store listing → Feature graphic）
├── screenshots/               1080×1920 ×8（三语已全部换新；zh-CN 源图复用 zh-TW 繁体截图+简体文案，用户拍板），按顺序上传
│   ├── 01-gallery.png         AI 智能整理（相册/相簿网格）
│   ├── 02-search.png          自然语言搜索（chat 同学/同學搜索 + Found/找到 结果）
│   ├── 03-chat.png            对话式多轮检索 + 聊天中修图（多轮精炼卖点）
│   ├── 04-people.png          人物关系图谱（标注关系→图谱→按关系搜索）
│   ├── 05-person-groups.png   自动人脸聚类（本地算法·向量×标签·不上云）
│   ├── 06-chat-welcome.png    AI 助手欢迎页（小浪/Xiaolang；2026-08-23 替换原 library 槽位）
│   ├── 07-insight.png         相册洞察（chat 健康报告）
│   └── 08-privacy.png         端侧隐私（Settings/設定）
└── screenshot-captions.json   文案 SSOT：标题/副标语 ↔ frame ↔ 源截图的映射
```

- 设计与产出在 Ardot 文件页面 **Play Store Assets**：改文案改图在 Ardot 里改 frame（命名 `<序号>-<scene>/<locale>`），`export_nodes` 重新导出即可；文案以 `screenshot-captions.json` 为准同步
- 🖼️ **商店图 SSOT = 设计稿**（2026-09-08 原则）：01/02 的照片内容改 Gallery 页设计帧 `gallery/grid-store01` / `search/store01`，达标后导出灌 Play 帧 Screen 节点（不再直接动烘焙截图）。01 的人像素材源图 = `assets/gallery-faces/`（CogView-4 生成，白富美/高富帅方向，含瓦片映射表）；en/中文双模式导出经设计帧 variableModes 切换
- **en-US 源截图 = `docs-site/assets/shots-src/en-US/`**（2026-08-23 真机重截，DarkMode 英文 UI，1200×2670；spare-gallery2.jpg 第二张相册网格备用未上槽）。成品 JPG 镜像同步于 `docs-site/assets/shots/en-US/`（官网 At a Glance 区引用）
- ⚠️ **重截候选**（i18n 残留/杂质）：en 03-chat 含「有脸」小标签；zh-TW 07-insight 回复含简体「相册」、03-chat 历史消息为简体、02/03 可见调试统计行（18895 7573ms 类）——上架前建议重截替换
- 📌 **识别教训（2026-08-23）**：批量并行读图时内联渲染顺序会乱，场景识别必须以 OCR 逐张核对为准，勿目测指认（本次曾据乱序目测错配全部槽位，用户发现图文不符后修正）
- **zh-TW 已换新（2026-08-23）**：源图 `docs-site/assets/shots-src/zh-TW/`（繁体 DarkMode 8 屏），含新建 05-person-groups/06-chat-welcome 帧 + feature-graphic 换图
- **zh-CN 已换新（2026-08-23）**：**源图复用 zh-TW 繁体截图 + 简体文案**（用户拍板）；如后续要求简体 UI 截图，按繁体 8 屏清单重截（相册网格 / chat 同学搜索 / chat 人脸精炼 / 人物页 / 人物分组相册 / chat 空欢迎页 / chat 健康报告 / 设置页，聊天类务必**新会话**避免跨语言历史残留）后灌 `shots-src/zh-CN/` 重导即可
- ⚠️ 03/04/06 源图含真实人物照片与人名（内部测试数据），上架前确认可公开使用，否则重新截取替换
- 📤 **上传方式**：当前图形素材手动传 Console；如需纳入自动化，迁入 GPP 约定目录 `androidApp/src/main/play/listings/<lang>/graphics/{feature-graphic,phone-screenshots}/` 后即可随 `publishListing` 上传（维度约束见运维手册）

> 策略与关键词地图见 `docs/superpowers/specs/2026-08-08-google-play-aso-design.md`（第二轮迭代见其 §7）。
> 检索词台账（词表分层/覆盖计数/双周搜索词报告记录）：`google-play-listing/keyword-ledger.md`（2026-08-24 起）。
