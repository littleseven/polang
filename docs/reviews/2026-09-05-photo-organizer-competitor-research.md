# 相册类 App 竞品调研（破浪借鉴参考）

> 调研日期：2026-09-05
> 范围：App Store 美区相册/照片整理类头部产品 + 中文区代表产品 + Google Photos（已剔除通用手机清理类 App）
> 用途：为破浪相册（PoLang）的功能规划提供竞品 feature 参考

---

## 一、头部产品清单（排名 / 收入 / 模式）

### 海外市场（App Store 美区）

- **Slidebox: Photo Cleaner**
  - 4.8 星，App Store Editors' Choice；Android/iOS 双端
  - 手势整理相册的标杆：上滑删除、点按归档
  - 模式：免费 + $1.99/周、$4.99/月、$19.99/年、$19.99 买断
  - App Store：https://apps.apple.com/us/app/slidebox-photo-cleaner-app/id984305203
  - Google Play：https://play.google.com/store/apps/details?id=co.slidebox
- **Swipewipe: Photo Cleaner**
  - 美区工具类畅销前列的滑动清照片应用
  - 模式：订阅制
  - Sensor Tower：https://app.sensortower.com/overview/1583884012?country=US
- **Clever Cleaner（CleverFiles 出品）**
  - AI 重复/相似照片清理，完全免费、无广告、无订阅；「早期用户永久免费」冲量策略
  - 评测参考：https://www.cleverfiles.com/howto/best-iphone-photo-organizer-apps.html
- **Daily Delete（Photo Cleaner）**
  - 日历视图逐日清理 + 「每天 5 分钟」习惯养成
  - 模式：$6.99/周起，含年付
- **HashPhotos**
  - 系统相册增强：标签（可同步到 Apple Photos 搜索）、智能相册、高级过滤
  - 模式：$1.99/月、$8.99/年、$18.99 买断
- **MyPics**
  - 10 年老牌相册：嵌套相册（文件夹套文件夹）、密码锁、日历/地图视图
  - 模式：Pro $6.99、Expert $8.99 买断
- **Mylio Photos**
  - 无云跨设备同步 + 端侧 AI 打标（1000+ 物体/活动标签），摄影师向
  - 模式：$25/月、$239.99/年
- **Keepsafe（Secret Photo Vault，私密相册）**
  - 7000 万用户、36.7 万+ 评分 4.8，隐私单点做到极致
  - 模式：$4.99/月、$23.99/年
- **Google Photos**
  - 4.8 星、100 万+ 评分；AI 搜索 / Photo Stacks / 年度 Recap 的定义者
  - 模式：免费 15GB，扩容 $1.99/月起

### 中文区代表

- **减法相册**（iOS）：重复/相似照片 + 截图/视频清理
  - https://apps.apple.com/cn/app/id1580710672
- **phoom**：智能识别重复图片，支持 Live Photo/GIF/视频分类
  - https://apps.apple.com/cn/app/id1555853093
- **Tidy**（Google Play）：AI 找出垃圾照片，清理过程游戏化
  - https://play.google.com/store/apps/details?id=com.nfo.tidy
- **相册管家 / 雪梨相册**（国内安卓）：私密相册加密 + 长图拼接 + 修复美颜

---

## 二、核心 Feature 清单（按主题分组）

### 1. 手势化快速整理（Slidebox / Swipewipe / Daily Delete）

- 上滑删除、左右切换浏览、点按归入相册，全手势操作，10 分钟清几百张
- 撤销（Undo）+ 回收站兜底，让用户敢快速决策
- 游戏化：进度追踪、「今日已释放 XX MB」
- Daily Delete：「每天 5 分钟」习惯养成推送 + 日历视图逐日清理
- Slidebox 付费墙设计：免费版只能整理近 2 年的照片，深度历史需付费
- 实测文章（Android Police）：https://www.androidpolice.com/this-one-photo-cleanup-app-rid-hundreds-photos-10-minutes/

### 2. AI 重复/相似照片检测 + 自动选最佳（Clever Cleaner / 减法相册 / phoom）

- 相似照片分组，AI 自动标记 "best shot"，一键清掉其余（Smart Cleanup）
- 检测闭眼照片、模糊照片、低分辨率照片
- 截图专项清理、大文件/重视频查找
- 删除进回收站，可恢复

### 3. 空间回收组合拳

- 视频压缩（号称省 95% 空间）
- Live Photo 转静态图（去掉视频部分省空间）
- 主屏小组件显示相册占用

### 4. 时间线 / 回忆玩法（Google Photos / Daily Delete）

- On This Day「那年今日」：按日期跨年浏览
- 年度 Recap：Gemini 生成高光时刻、自拍数等趣味指标；可隐藏特定人脸/照片、可重新生成、可分享
  - https://blog.google/products-and-platforms/products/photos/google-photos-2025-recap/
  - https://techcrunch.com/2025/12/03/google-photos-2025-recap-turns-to-gemini-to-find-your-highlights/
- Photo Stacks：相似照片自动折叠成堆，默认只显示最佳一张

### 5. 隐私保险库（Keepsafe —— 单点撑起 7000 万用户）

- 独立 PIN / Face ID；假密码（decoy）：输入预设密码打开无害相册
- 入侵抓拍：输错密码自动拍照
- 分相册单独上锁（Album Lock）
- 内置相机直接拍进保险库；Safe Send 限时分享

### 6. 自然语言搜索与 AI 编辑（Google Photos）

- Ask Photos：支持「我的松子柠檬饭食谱是什么」这类复杂自然语言查询
- Magic Eraser 去路人、Unblur 修复糊片
- Google Lens 识别文字与物体

### 7. 高级组织（HashPhotos / MyPics，重度用户向）

- 关键词标签（可与系统相册同步搜索）、智能相册（自定义规则）
- 嵌套文件夹、地图/日历/时间线视图
- 批量编辑、元数据查看/编辑、Wi-Fi 导入导出

---

## 三、破浪可借鉴项（按性价比排序）

### P0 — 直接复用现有资产，快速见效

- [ ] **相似/重复照片分组 + 智能选最佳**
  - 已有图片 embedding 和人脸聚类，差分组 UI 和「一键保留最佳」
  - 品类收入最高的 feature，是破浪 TAG 打标数据的自然下游应用
- [ ] **智能清理看板**
  - 截图、模糊片、大视频、连拍组分类汇总 + 释放空间预估 + 回收站兜底
  - 纯元数据 + TAG 即可实现
- [ ] **手势整理模式**（上滑删 / 左右留 / 点按归档）
  - 交互简单爽感强；结合 TAG 可做「AI 预排序」（疑似废片排前面），比 Slidebox 纯手动更省力——差异化点

### P1 — 强化 Agent 差异化

- [ ] **自然语言整理指令**
  - 「把上周的截图和模糊的连拍清掉」走 Chat → capability 链路，竞品抄不动
- [ ] **On This Day / 月度高光**
  - 基于 TAG + 时间元数据，端侧生成；主打「回忆不出手机」，对位 Google 云端 Recap，呼应 [PRIVACY] 红线

### P2 — 观察 / 可选

- [ ] **私密相册（假密码 + 入侵抓拍）**：Keepsafe 验证过的付费点，但与破浪「AI 相册」主心智有距离
- [ ] **视频压缩 / Live Photo 转静态**：工程量大、质量风险高，与 AI 主线无关，近期不建议

### 变现参考

- 相册整理类头部普遍采用周订阅（$1.99–6.99/周）或买断（$6.99–19.99）
- 成熟的付费墙模式：「免费整理近期 / 付费解锁全部历史」（Slidebox 验证有效）
- 破浪若做整理功能，「AI 预排序 + 深度历史整理」是最自然的付费分层点

---

## 四、信息来源

- 《9 Best Photo Organizer Apps for iPhone (2026)》（各 App 详细 feature 清单）：https://www.cleverfiles.com/howto/best-iphone-photo-organizer-apps.html
- Slidebox 实测（Android Police）：https://www.androidpolice.com/this-one-photo-cleanup-app-rid-hundreds-photos-10-minutes/
- Swipe 类整理 App 横评：https://www.swipephotos.com/de/compare/best-swipe-photo-apps
- Sensor Tower（Swipewipe 排名）：https://app.sensortower.com/overview/1583884012?country=US
- Google Photos 2025 Recap 官方博客：https://blog.google/products-and-platforms/products/photos/google-photos-2025-recap/
- App Store 国区：减法相册 / phoom；Google Play：Tidy
