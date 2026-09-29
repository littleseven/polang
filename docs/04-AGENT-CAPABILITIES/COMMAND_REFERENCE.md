# PoLang Agent 命令参考手册 (Command Reference)

> **边界声明（Boundary Statement）**
> - 本文档定义所有 Agent 命令的语法、参数与使用示例。
> - 架构设计以 [`../02-ARCHITECTURE/AGENT_ARCHITECTURE.md`](02-ARCHITECTURE/AGENT_ARCHITECTURE.md) 为准。
> - 交互规范以 [`../01-PRODUCT/FEATURES.md`](01-PRODUCT/FEATURES.md) 为准。

**模块定位**: Agent 命令语法与使用示例  
**主要维护者**: 项目开发者  
**阅读对象**: 项目开发者、AI Agent  
**版本**: 1.3  
**最后更新**: 2026-08-03  

---

## 📋 目录

1. [命令格式](#1-命令格式)
2. [相机控制命令](#2-相机控制命令)
3. [相册管理命令](#3-相册管理命令)
4. [标签生成命令](#4-标签生成命令)
5. [AI 优化命令](#5-ai-优化命令)
6. [设置管理命令](#6-设置管理命令)
7. [导航命令](#7-导航命令)
8. [系统/外部 App 命令](#8-系统外部-app-命令)
9. [通用命令](#9-通用命令)
10. [相册分析命令（CHAT 场景）](#10-相册分析命令chat-场景)
11. [图片编辑命令（CHAT 场景）](#11-图片编辑命令chat-场景)

---

## 1. 命令格式

### 1.1 自然语言解析

Agent 通过 LLM 将用户自然语言输入解析为结构化命令：

```
用户输入 → LLM 解析 → AgentCommand → Capability 执行 → 结果反馈
```

### 1.2 命令类型安全

所有命令使用 `sealed class` 定义，确保类型安全。具体命令类型见项目 `AgentCommand.kt`。

---

## 2. 相机控制命令

**Capability**: `camera`  
**活跃场景**: `CAMERA`

### 2.1 拍照相关

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "拍照" | `CapturePhoto` | 立即拍照 |
| "拍一张" | `CapturePhoto` | 立即拍照 |
| "来张照片" | `CapturePhoto` | 立即拍照 |
| "3秒后拍照" | `BatchExecute([Delay(3000), CapturePhoto])` | 延迟3秒后拍照 |
| "倒计时拍照" | `BatchExecute([Delay(3000), CapturePhoto])` | 延迟拍照 |
| "3秒后调暖色调再拍照" | `BatchExecute([Delay(3000), SwitchFilter(WARM), CapturePhoto])` | 延迟+调滤镜+拍照 |

**示例**:
```
用户：拍照
Agent: ✅ 已为你拍照
```

### 2.2 录像控制

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "开始录像" | `ToggleRecording` | 启动录像 |
| "停止录像" | `ToggleRecording` | 结束录像 |
| "录视频" | `ToggleRecording` | 切换录像状态 |

### 2.3 摄像头控制

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "翻转镜头" | `FlipCamera` | 前后摄像头切换 |
| "切后置" | `FlipCamera` | 切换到后置 |
| "切前置" | `FlipCamera` | 切换到前置 |

### 2.4 变焦调节

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "放大两倍" | `AdjustZoom(2.0f)` | 2 倍变焦 |
| "拉近一点" | `AdjustZoom(1.5f)` | 1.5 倍变焦 |
| "缩小" | `AdjustZoom(0.8f)` | 缩小到 0.8 倍 |

**参数范围**: 0.5x ~ 10.0x

### 2.5 曝光调节

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "调亮一点" | `AdjustExposure(+2)` | 增加曝光 |
| "调暗一些" | `AdjustExposure(-2)` | 降低曝光 |
| "恢复曝光" | `AdjustExposure(0)` | 重置曝光 |

**参数范围**: -2 ~ +2

### 2.6 拍摄模式

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "拍照模式" | `SwitchMode("PHOTO")` | 普通拍照模式 |
| "录像模式" | `SwitchMode("VIDEO")` | 录像模式 |
| "专业模式" | `SwitchMode("PRO")` | 专业模式 |
| "文档模式" | `SwitchMode("DOCUMENT")` | 文档拍摄模式 |

**参数取值**: `PHOTO` / `VIDEO` / `PRO` / `DOCUMENT`（大写枚举，见 `CameraCapability.kt` 与 `CameraToolService.kt`）

### 2.6.1 场景模式（switch_scene）

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "夜景模式" | `SwitchScene("night")` | 夜景场景 |
| "月亮模式" | `SwitchScene("moon")` | 月亮场景 |
| "关闭场景" | `SwitchScene("none")` | 关闭场景模式 |

**参数取值**: `night` / `moon` / `none`。注意：`night` 是 `switch_scene` 的值，**不是** `switch_mode` 的值。

### 2.7 美颜调节

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "磨皮 50" | `AdjustBeauty(SMOOTH, 50)` | 磨皮强度 50 |
| "美白高一点" | `AdjustBeauty(WHITEN, currentValue+10)` | 增加美白 |
| "瘦脸 -20" | `AdjustBeauty(SLIM_FACE, -20)` | 瘦脸 -20（推脸） |
| "大眼 30" | `AdjustBeauty(EYE_SIZE, 30)` | 大眼 30 |
| "恢复美颜默认" | `AdjustBeauty(DEFAULT, 0)` | 重置所有美颜参数 |

**美颜参数范围**:
- 磨皮：0-100（默认 35）
- 美白：0-100（默认 25）
- 瘦脸：-50~+50（默认 0）
- 大眼：0-100（默认 20）
- 唇色：0-100（默认 40）
- 腮红：0-100（默认 20）
- 眉毛：0-100（默认 15）

### 2.8 滤镜与风格

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "冷调滤镜" | `SwitchFilter("COOL")` | 冷色调滤镜 |
| "胶片金" | `SwitchFilter("FILM_GOLD")` | 胶片金滤镜 |
| "徕卡经典" | `SwitchFilter("LEICA_CLASSIC")` | 徕卡经典滤镜 |
| "卡通风格" | `SwitchStyle("TOON")` | 卡通风格特效 |
| "素描效果" | `SwitchStyle("SKETCH")` | 素描风格特效 |
| "原图" | `SwitchFilter("NONE")` | 关闭滤镜 |

**参数取值**: 滤镜为大写枚举 `NONE` / `LEICA_CLASSIC` / `LEICA_VIBRANT` / `LEICA_BW` / `FILM_GOLD` / `FILM_FUJI` / `VINTAGE` / `COOL` / `WARM`（中文名如「徕卡经典」「胶片金」「冷调」亦可）；风格为 `NONE` / `TOON` / `SKETCH` / `POSTERIZE` / `EMBOSS` / `CROSSHATCH`。

### 2.9 画幅比例

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "16:9" | `SwitchRatio("16:9")` | 宽屏画幅 |
| "4:3" | `SwitchRatio("4:3")` | 标准画幅 |
| "1:1" | `SwitchRatio("1:1")` | 方形画幅 |
| "全屏" | `SwitchRatio("full")` | 全屏画幅 |

---

## 3. 相册管理命令

**Capability**: `gallery`  
**活跃场景**: `GALLERY`

### 3.1 查看照片

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "看这张照片" | `ViewMedia(currentMediaId)` | 查看当前照片 |
| "打开这张" | `ViewMedia(selectedMediaId)` | 打开选中照片 |
| "看昨天的照片" | `SearchMedia("昨天")` → `ViewMedia(result)` | 搜索并查看 |

### 3.2 删除照片

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "删除这张" | `DeleteMedia([mediaId])` | 删除当前照片 |
| "删掉这张照片" | `DeleteMedia([selectedMediaId])` | 删除选中照片 |
| "清空相册" | `DeleteMedia(allMediaIds)` | 删除所有照片（需确认） |

### 3.3 分享照片

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "分享这张" | `ShareMedia([mediaId])` | 分享当前照片 |
| "分享这张给别人" | `ShareMedia([mediaId])` | 调起系统分享面板 |

### 3.4 收藏照片

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "收藏这张" | `FavoriteMedia(mediaId, favorite=true)` | 收藏当前照片 |
| "取消收藏" | `FavoriteMedia(mediaId, favorite=false)` | 取消收藏 |

### 3.5 搜索照片

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "找昨天的照片" | `SearchMedia("昨天")` | 按时间搜索（规则解析） |
| "找有文字的照片" | `SearchMedia("文字")` | 按 OCR 内容搜索 |
| "找自拍" | `SearchMedia("自拍")` | 按标签搜索 |
| "搜索7月的美女" | `SearchMedia("7月的美女")` | 复合语义搜索 |
| "近半年小孩的照片" | `SearchMedia("近半年小孩的照片", SearchIntent(timeRange=近半年, keywords=["小孩"], hasFaces=true))` | Chat 场景 LLM 直接输出结构化意图 |
| "去年夏天的合照" | `SearchMedia("去年夏天的合照", SearchIntent(timeRange=去年夏天, keywords=["合照"], hasFaces=true))` | Chat 场景 LLM 标准化时间 |
| "去年夏天有人脸的照片" | `SearchMedia("去年夏天有人脸的照片", SearchIntent(timeRange=去年夏天, hasFaces=true))` | 人脸约束走 hasFaces 字段：路由器 hasFace 槽位 / search_media hasFace 参数 / ChatViewModel 规则兜底（QueryParser.isPeopleSearch）三路补齐 |

### 3.6 Chat 相册搜索细化（CHAT 场景）

**Capability**: `chat_gallery_search`  
**活跃场景**: `CHAT`

用于在 Chat 页对上一轮相册搜索结果进行多轮追加、收窄或反馈。

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "只要近半年的" | `RefineMediaSearch("只要近半年的", SearchIntent(timeRange=近半年))` | 在上一轮结果内按时间过滤 |
| "其中的日落" | `RefineMediaSearch("日落", SearchIntent(keywords=["日落"]))` | 在上一轮结果内追加关键词 |
| "第三张不错" | `RecordMediaFeedback(Ordinal(3), LIKE)` | 记录正反馈 |
| "不喜欢这张" | `RecordMediaFeedback(LastShown, DISLIKE)` | 记录负反馈 |
| "再来点这种" | `MoreLikeThis(LastShown)` | 基于上一张推荐相似照片 |
| "不要夜景" | `ExcludeConstraint("夜景")` | 在后续搜索中排除夜景 |

### 3.7 批量选择

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "选这张" | `SelectMedia(mediaId, selected=true)` | 选择当前媒体 |
| "取消选择" | `SelectMedia(mediaId, selected=false)` | 取消选择 |

### 3.8 视图模式切换

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "网格视图" | `SwitchViewMode("grid")` | 网格布局 |
| "列表视图" | `SwitchViewMode("list")` | 列表布局 |
| "时间线视图" | `SwitchViewMode("timeline")` | 时间线布局 |

### 3.9 人物关系（CHAT 场景）

**Capability**: `person_relation`  
**活跃场景**: `CHAT`

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "记住小宝是我女儿" | `RememberPersonRelation(name="小宝", relation="女儿")` | 声明人物关系（幂等覆盖） |
| "忘掉小宝的关系" | `ForgetPersonRelation(name="小宝")` | 遗忘与某人物的全部关系 |
| "看一下我的人物关系" | `QueryPersonRelation(name=null)` | 查询全部指向「我」的关系（`ChatToolService` 工具名 `list_person_relations`） |
| "小宝和我什么关系" | `QueryPersonRelation(name="小宝")` | 只查指定人物与「我」的关系 |

---

## 4. 标签生成命令

**Capability**: `auto_tag`（⚠️ 2026-08-03 核实：`AutoTagCapability` 代码存在但未注册到 `CapabilityRegistry`；CHAT 场景实际生效路径为 `ChatStartTagScanCapability` 的 `start_tag_scan`，详见 CAPABILITY_REGISTRY.md §8）  
**活跃场景**: `GALLERY`

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "扫描所有照片标签" | `ScanAllTags` | 触发全量标签扫描 |
| "给照片打标签" | `ScanAllTags` | 触发全量标签扫描 |
| "查看这张照片的标签" | `GetPhotoTags(photoId)` | 查询指定照片的标签 |
| "标签扫描进度" | `GetTagProgress` | 获取当前扫描进度 |
| "取消标签扫描" | `CancelTagScan` | 取消当前扫描 |

---

## 5. AI 优化命令

**Capability**: `ai_optimize`  
**活跃场景**: `GALLERY`, `CHAT`

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "优化这张照片" | `AiOptimize(imageUri, mode="fast")` | 本地快速优化 |
| "智能优化这张" | `AiOptimize(imageUri, mode="smart")` | 云端视觉模型推荐（需授权） |

**参数说明**:
- `image_uri`: 待优化图片的本地文件 URI（必填）
- `mode`: `fast`（默认，本地分析）或 `smart`（云端推荐）

---

## 6. 设置管理命令

**Capability**: `settings`  
**活跃场景**: `SETTINGS`

### 6.1 主题切换

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "深色模式" | `ChangeTheme(DARK)` | 切换到深色主题 |
| "浅色模式" | `ChangeTheme(LIGHT)` | 切换到浅色主题 |
| "跟随系统" | `ChangeTheme(FOLLOW_SYSTEM)` | 跟随系统主题 |

### 6.2 语言设置

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "英文界面" | `ChangeLanguage(EN)` | 切换到英文 |
| "繁体中文" | `ChangeLanguage(ZH_TW)` | 切换到繁体中文 |
| "简体中文" | `ChangeLanguage(ZH_CN)` | 切换到简体中文 |

### 6.3 模型管理

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "下载美颜模型" | `DownloadModel("beauty_v1")` | 下载美颜模型 |
| "下载人脸模型" | `DownloadModel("landmark_mp468")` | 下载 MediaPipe 模型 |
| "检查模型更新" | `DownloadModel("check_updates")` | 检查可用更新 |

### 6.4 人脸引擎切换

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "用 MNN 检测" | `SwitchFaceEngine(MNN)` | 切换到 MNN 引擎（GPU/CPU） |
| "用 MediaPipe 检测" | `SwitchFaceEngine(MEDIAPIPE)` | 切换到 MediaPipe 引擎（TFLite） |
| "自定义引擎" | `SwitchFaceEngine(CUSTOM)` | 使用 StageConfig 独立配置 |

**参数取值**: `FaceDetectionEngineMode` 枚举（`UserPreferences.kt`）为 `MEDIAPIPE` / `MNN` / `CUSTOM`，无 `DEFAULT`。

> **注意**: NCNN 路径已于 2026-07-05 完全移除。

### 6.5 开关设置项

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "开启调试模式" | `ToggleSetting("debug_mode", true)` | 启用调试模式 |
| "关闭自动保存" | `ToggleSetting("auto_save", false)` | 禁用自动保存 |
| "开启快门音效" | `ToggleSetting("shutter_sound", true)` | 启用快门声音 |

---

## 7. 导航命令

**Capability**: `navigation`  
**活跃场景**: `ALL`

### 7.1 页面切换

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "去相册" | `NavigateTo("gallery")` | 切换到相册页 |
| "打开设置" | `NavigateTo("settings")` | 切换到设置页 |
| "回相机" | `NavigateTo("camera")` | 返回相机页 |
| "打开聊天" | `NavigateTo("chat")` | 切换到聊天页 |
| "进入调试" | `NavigateTo("debug")` | 进入调试页 |
| "打开模型中心" | `NavigateTo("model_center")` | 切换到模型中心 |

### 7.2 返回操作

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "返回" | `GoBack` | 返回上一页 |
| "回去" | `GoBack` | 返回上一页 |
| "退出" | `GoBack` | 退出当前页面 |

---

## 8. 系统/外部 App 命令

**Capability**: `system`  
**活跃场景**: `ALL`

### 8.1 启动应用

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "打开微信" | `LaunchApp(appName="微信")` | 按应用名启动 |
| "启动支付宝" | `LaunchApp(appName="支付宝")` | 按应用名启动 |
| "打开相机" | `LaunchApp(packageName="com.android.camera")` | 按包名启动（示例） |

### 8.2 打开系统设置

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "打开WiFi设置" | `OpenSystemSettings("wifi")` | 打开无线网络设置 |
| "打开蓝牙设置" | `OpenSystemSettings("bluetooth")` | 打开蓝牙设置 |
| "打开通知设置" | `OpenSystemSettings("app_notifications")` | 打开本应用通知设置 |
| "打开无障碍设置" | `OpenSystemSettings("accessibility")` | 打开系统无障碍设置 |

---

## 9. 通用命令

### 9.1 延迟命令

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "等待1秒" | `Delay(1000)` | 延迟1000毫秒 |
| "3秒后" | `Delay(3000)` | 延迟3000毫秒 |
| "暂停一下" | `Delay(500)` | 短暂延迟 |

**说明**: `Delay` 是通用延迟原语，单位为毫秒。通常与 `BatchExecute` 组合使用，实现"延迟+X"的复合操作。

**示例**:
```
用户：3秒后拍照
Agent: 已设置3秒后拍照
[延迟3秒...]
Agent: ✅ 已为你拍照
```

### 9.2 批量执行

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "调美颜然后拍照" | `BatchExecute([AdjustBeauty(...), CapturePhoto])` | 顺序执行多个命令 |
| "3秒后调暖色调拍照" | `BatchExecute([Delay(3000), SwitchFilter(WARM), CapturePhoto])` | 延迟+滤镜+拍照 |

**说明**: `BatchExecute` 将多个命令按顺序执行，支持任意组合（包括 `Delay` + 任意命令）。

### 9.3 文本回复

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "你会什么" | `TextReply("能力介绍")` | 询问能力 |
| "今天天气怎么样" | `TextReply("闲聊回复")` | 闲聊 |
| "谢谢" | `TextReply("礼貌回复")` | 礼貌回应 |

### 9.4 澄清请求

当 Agent 无法理解用户意图时，会返回澄清请求：

```
用户：调高一点
Agent: 你想调高哪个参数？磨皮、美白还是其他？
```

### 9.5 错误处理

| 场景 | 响应 |
|------|------|
| 不支持的命令 | "抱歉，我还不支持这个功能" |
| 参数超出范围 | "磨皮最高 100，你设了多少？" |
| 上下文缺失 | "请先选择一张照片" |

---

## 10. 相册分析命令（CHAT 场景）

> 这两个命令仅由**远程 chat ReAct agent**（`ChatToolService`）使用，本地小模型不暴露（见 `AGENT_ARCHITECTURE.md` §2.4）。`run_gallery_script` 在端侧 QuickJS 沙箱执行 JS，是相册盘点/统计/组合计算的入口；`draw_chart` 把统计数据渲染成真实图片。JS 沙箱的 handler 表面见 `CAPABILITY_REGISTRY.md` §1.2。

### 10.1 执行脚本 `run_gallery_script`

| 命令 | 参数 | 描述 |
|------|------|------|
| `run_gallery_script` | `code: String`（JS 源码） | 在端侧沙箱执行 JS 做相册盘点/统计；取数只读、数据不出端；写操作走 `capability.dispatch` 经用户确认 |

**JS 内取数**（只读，`await bridge.callAsync(name, args)`）：`gallery.summary` / `gallery.query` / `gallery.tags` / `gallery.timeline` / `gallery.intersect` / `gallery.stats_by_tag` / `media.meta` / `media.batch_meta` / `face.cluster` / `tag.audit`。多个可 `Promise.all` 并发，JS 内组合计算后 `return` 结果对象回传 LLM。

**JS 内写操作**（`await bridge.callAsync('capability.dispatch', {method, params})`，弹窗确认）：`delete_media` / `favorite_media` / `select_media` / `remember_fact` / `forget_fact` / `get_gallery_summary` / `recall_memory`。

示例（盘点截图 + 批量删除）：

```js
var q = await bridge.callAsync('gallery.query', {label:'截图', limit:200});
if (q.ids.length === 0) return {deleted:0};
try {
  var r = await bridge.callAsync('capability.dispatch', {method:'delete_media', params:{ids:q.ids}});
  return {deleted:q.total, result:r};
} catch (e) { return {deleted:0, cancelled:true, reason:String(e)}; }
```

### 10.2 画图表 `draw_chart`

| 命令 | 参数 | 描述 |
|------|------|------|
| `draw_chart` | `type: String`(bar/line/pie)、`title: String`、`labels: List<String>`、`values: List<Double>`、`unit: String?` | 端侧渲染柱/折/饼图成真实图片插入聊天；展示图表的唯一方式，禁止文字/表格画图 |

**触发条件（2026-07-29 调整）**：**默认不画图**——统计/盘点类问题只用文字总结；仅当用户明确要求画图（"画/图表/柱状图/折线图/饼图/把…画成图"）时才走下述流程。

**标准流程**（严格三步）：① `run_gallery_script` 取数（只调 1 次，不分段/不重复）→ ② `draw_chart` 画图 → ③ 一句话总结。类型选择：时间趋势 → line/bar；占比/分布 → pie；数量对比 → bar。详见 `RemoteChatEngine.chatSystemPrompt`。

---

## 11. 图片编辑命令（CHAT 场景）

**Capability**: `image_edit`（`edit_image` 经 CapabilityRegistry 分发，见 `CAPABILITY_REGISTRY.md` §14）  
**活跃场景**: `CHAT`

### 11.1 对话式图片编辑 `edit_image`

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "磨皮 30" | `EditImage(params={"smoothing":30})` | 美颜调整 |
| "换胶片风" | `EditImage(params={"filter_name":"FILM_GOLD","filter_intensity":70})` | 滤镜切换（大写滤镜枚举名） |
| "再亮一点" | `EditImage(params={"brightness_delta":10})` | 多轮 delta 相对调整（单步小幅度） |
| "把路人擦掉" | `EditImage(explanation="[unsupported:erase]")` | 未支持意图，返回友好说明 |

**参数说明**（`edits` JSON 字段，均可选、只传要改的）：
- 美颜：`smoothing` / `whitening` / `big_eyes` / `lip_color` / `blush` / `eyebrow`（0~100），`slim_face`（-50~50）
- 调色：`brightness` / `exposure`（-100~100，0=不变），`contrast` / `saturation`（0~200，100=原图），`temperature`（色温开尔文 2000~8000，5000=原图，越大越暖），`tint`（-100~100）
- 滤镜/风格：`filter_name`（大写滤镜枚举，如 `FILM_GOLD` / `COOL`）、`filter_intensity`（0~100）、`style_name`
- 相对调整：`*_delta` 字段（如 `{"brightness_delta":10}` 表示再亮一点）。**模糊/相对调整必须用 `*_delta` 且单步幅度要小**；只有用户明确给出数值（"磨皮 50"）才用绝对值字段
- 单次 delta 步进上限（`ChatEditRecipeBuilder` 截断保护，超出会被 clamp）：美颜 ±10、`slim_face` ±5、亮度/曝光 ±15、对比度/饱和度 ±15、色温 ±500K、`tint` ±15；绝对值不设步进上限（视为显式数值请求）。需要更强效果时分多轮小步叠加
- `image_uri`：目标图片 URI，留空串表示用最近发送的图片

**说明**: 编辑在后台渲染完成后把结果图发到聊天中，**绝不跳转编辑页**；编辑状态按会话隔离，支持同一会话多轮叠加。未支持的编辑（消除物体 / 局部美颜）不编造参数，经 `explanation` 返回 `[unsupported:erase]` / `[unsupported:local_beauty]`。

### 11.2 显式参数调整 `adjust_image`（inline 工具，不进注册表）

| 自然语言 | 解析命令 | 说明 |
|---------|---------|------|
| "调亮一点" | `AdjustImage(brightness=+20)` | 提高亮度 |
| "增加对比度" | `AdjustImage(contrast=80)` | 提高对比度 |
| "提高饱和度" | `AdjustImage(saturation=150)` | 提高饱和度 |
| "调暖一点" | `AdjustImage(temperature=6500)` | 色温调暖 |

**参数说明**: `brightness` -100(暗)~100(亮)，0=不变；`contrast` 0~200，50=默认；`saturation` 0~200，100=默认；`temperature` 2000(冷蓝)~8000(暖黄)，5000=默认；未指定的参数留空串表示不调整。

**说明**: `adjust_image` 是 `ChatToolService.adjustImage()` @Tool 暴露的 inline 工具，**不经 `CapabilityRegistry` 分发，无对应 AgentCommand/Capability**。LLM 侧约定：显式数值调整走 `adjust_image`，其余编辑意图（滤镜/美颜/多轮 delta）走 `edit_image`。

---

## 附录：命令解析流程

### 步骤 1: 构建 System Prompt

```
你是 PoLang 的 AI 助手，帮助用户控制相机和照片管理。

当前页面：CAMERA

可用功能:
- camera: 相机控制：拍照、录像、美颜、滤镜
  • capture
  • adjust_beauty
  • switch_filter
  • ...
- navigation: 页面导航：切换页面、返回上一页
  • navigate_to
  • go_back
```

### 步骤 2: LLM 解析

```
用户输入："磨皮 50，换个冷调"

LLM 输出：[
  {"action": "adjust_beauty", "param": {"type": "smooth", "value": 50}},
  {"action": "switch_filter", "param": {"type": "COOL"}}
]
```

### 步骤 3: 批量执行

```kotlin
val commands = parseJsonResponse(output)
commands.forEach { command ->
    capabilityRegistry.execute(command, context)
}
```

### 步骤 4: 结果反馈

```
Agent: 已为你调高磨皮至 50，并切换到冷调滤镜
```

---

> **参考文档**:
> - [CAPABILITY_REGISTRY.md](04-AGENT-CAPABILITIES/CAPABILITY_REGISTRY.md) — Capability 注册表
> - [AGENT_ARCHITECTURE.md](02-ARCHITECTURE/AGENT_ARCHITECTURE.md) — Agent 架构设计
> - [FEATURES.md](01-PRODUCT/FEATURES.md) — 功能交互规范
