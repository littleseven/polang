# OTA 后台下载 + 完成自动安装 — 设计

- 日期：2026-10-05
- 状态：已批准（用户口头确认，两轮问答选定关键决策）
- 背景问题：debug 包 82.5MB @ ~750KB/s ≈ 2 分钟，更新弹窗全程阻塞前台；需求为后台下载 + 完成后自动安装

## 已确认决策

| 决策点 | 选择 |
|---|---|
| 安装自动化程度 | 前台完成 → 自动拉系统安装器（一键「安装」）；后台完成 → 高优先级通知，点击拉安装器。（非系统应用无法全静默，系统硬限制） |
| 下载引擎 | 系统 DownloadManager：进度通知系统自带（免 POST_NOTIFICATIONS）、进程被杀下载不中断、断点续传白送、零新依赖 |
| 冷启动恢复判定 | RUNNING → 静默；SUCCESSFUL 且版本 > 已装 → 弹「已下载完成」对话框（按钮安装，**不自动拉**，避免故意推迟者每次冷启被强拉）；FAILED → 失败弹窗可重试 |
| 网络策略 | 允许计量网络（debug 遛狗轨、测试机为用户本人），禁漫游 |

## 架构

```
AppUpdateDialog（UI：移除 Downloading 态及其渲染——进度由系统下载通知承担）
     ↓ state
AppUpdateController（编排：enqueue / 完成分发 / 前后台感知 / 冷启动恢复）
     ↓                        ↓
OtaDownloadManager(新, data)  launchInstall（复用现有 FileProvider 通路）
```

- **OtaDownloadManager**（data 层新增）：`enqueue(url, versionCode, title): Long`（成功入队后才清旧包；title 供系统下载通知显示）、`status(id): OtaDmStatus`（RUNNING(progress)/SUCCESSFUL/FAILED(reason)/MISSING；查询失败即抛，调用方 runCatching）、`localFileFor(vc)`、`delete(id)`（dm.remove：记录+文件连删）、`clearOldFiles(except)`。下载目标 `getExternalFilesDir(DIRECTORY_DOWNLOADS)/ota/polang-<versionCode>.apk`；`setNotificationVisibility(VISIBLE)`（进行中系统通知、完成自动隐藏）；`setAllowedOverMetered(true)`、`setAllowedOverRoaming(false)`
- **`ota_paths.xml`** 增加 `external-files-path name="ota" path="ota/"`，FileProvider 直接可装、零拷贝
- **OtaRecoveryPolicy**（domain/update 纯函数）：`(installedVersionCode, installedRemoteKey?, pending?, dmStatus?) → RecoveryAction`，分支逻辑唯一集中点，JVM 矩阵测试（11 用例）。陈旧判定：pending.vc 严格更低，或同 vc 且 key 等于已装构建——**同 vc 异构建（重发）不算陈旧**，保住遛狗主路径的在途下载
- **OtaUpdatePrefs** 新增键：`pending_download_id`(Long)、`pending_download_version_code`(Long)、`pending_download_updated_at`(String)（三元组原子读/写/清；updatedAt 供安装记账重建 RemoteBuild.key）

## 数据流

1. **Available → startDownload()**：enqueue → 记 prefs(id/versionCode) → state=Idle → toast「已转后台下载，完成后自动安装」→ App 正常使用
2. **ACTION_DOWNLOAD_COMPLETE**（动态 receiver，`RECEIVER_EXPORTED`——DM 广播发自 Downloads provider 而非 system uid，NOT_EXPORTED 全版本段收不到，Google issue 302209811；安全性由 pending id 匹配 + DM 状态二次校验承担）：校验 id 匹配 → 前台（lifecycle 感知）→ `launchInstall(file)` 后**在安装发动点记账**（markInstallLaunched = markInstalledRemoteKey + 清 pending；不做 dm.remove——remove 连 APK 文件删，安装器异步读 URI 时文件必须在，清理交下次 enqueue）；两分支均发完成通知（channel `ota_complete`，覆盖 ON_STOP 滞后窗口；POST_NOTIFICATIONS 未授权则静默丢弃，冷启动恢复兜底）→ PendingIntent 点击拉安装。后台通知路径不记账（通知可能不被点，不能预 claim 安装），冷启动 OfferInstall 兜底重弹、installNow 收口——代价是通知装完后的首次冷启动多一弹，自愈
3. **冷启动**（`checkForUpdate` 前置）：读 pending id → `OtaRecoveryPolicy` 判定 → RUNNING 静默 / SUCCESSFUL→ReadyToInstall 弹窗 / FAILED→Failed 弹窗 / 无 pending→走原 fetchLatest 流程
4. **清理**：enqueue 新版本前 `clearAll()` 旧 APK；启动检测 versionCode 追平（已安装）→ 清文件与 prefs

## 错误处理

- enqueue 即时失败（存储满等）→ Failed 弹窗（重试=重新 enqueue）
- receiver 只在进程存活期有效；进程死亡场景由冷启动恢复覆盖（DownloadManager 独立于进程完成下载）
- 现有「下载完成即 markInstalledRemoteKey 防重复弹」语义保留；恢复态修复「取消安装后永不再提示」存量缺口

## i18n（×5：en/zh-rCN/zh-rTW/es/fr）

- toast：已转后台下载提示
- 完成通知：标题 + 文本（版本号占位）
- 通知渠道名
- 复用现有 ota_*（title/install/later/retry 等），ReadyToInstall 文案现成

## 测试

- `OtaRecoveryPolicyTest`（JVM）：判定矩阵（无 pending / RUNNING / SUCCESSFUL 新旧版本 / FAILED / versionCode 已追平）
- 端到端：下一个 debug 版本（1.0.47+）真机 OTA 验收（前台自动拉装、后台通知、杀进程恢复三场景）

## 改动面

`features/update/`（controller+dialog）、`data/update/OtaDownloadManager.kt`（新）、`data/preferences/OtaUpdatePrefs.kt`、`domain/update/OtaRecoveryPolicy.kt`（新）、`res/xml/ota_paths.xml`、strings ×5。不越模块边界，无新依赖。
