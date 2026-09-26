---
name: android-tag-data-backup-restore
description: Use when the user needs to preserve Android app data across uninstall/reinstall cycles caused by different signing keys, especially for expensive-to-regenerate content like image tags, face embeddings, or clustering results.
---

# Android 应用数据跨签名备份/恢复

## Overview

同一部测试机反复安装 release / debug 包时，签名不同导致必须卸载重装，应用数据会全部丢失。本 Skill 描述一套**无需 root** 的跨签名数据保护方案，把最耗时的 TAG 扫描结果（标签、人脸 Embedding、人物聚类、OCR、地理位置等）以及 DataStore 设置保存为快照，重装后一键恢复。

PoLang 项目已提供三种入口，覆盖 debug / release 包：

1. **PC 脚本（推荐开发/CI）**：`scripts/app-data-backup.sh`，通过 adb 广播触发应用内备份/还原。
2. **应用内 SAF 入口（推荐 release 手动操作）**：`设置 → 备份与恢复`，使用 Android Storage Access Framework 导出/导入 JSON。
3. **系统级 `adb backup/restore`**：备份整个应用数据目录，适合整机迁移。

核心依赖：

- 应用内：`TagDataBackupRepository`、`BackupTagDataUseCase`、`RestoreTagDataUseCase`
- adb 触发入口：`BackupRestoreBroadcastReceiver`（`.testing.backup.BackupRestoreBroadcastReceiver`，仅 debug 构建）的 `backup_tag_data` / `restore_tag_data` 命令
- PC 端脚本：`scripts/app-data-backup.sh`

## When to Use

- 用户在 release / debug 包之间切换，需要保留数据
- 卸载重装后需要恢复 TAG 扫描、人脸聚类、OCR 等结果，避免重新花大量时间生成
- 需要把当前设备上的 TAG 数据导出为可迁移的快照

**不适用：**

- 同签名覆盖升级（直接用 `adb install -r`，数据自动保留，无需本方案）
- 需要完整备份聊天记录、下载模型文件等（本方案只覆盖 TAG 相关数据与 DataStore 设置）

## Core Pattern

```
当前包
  ↓ 触发备份广播 / SAF 导出
生成 JSON 备份
  ↓ 保存到 PC 快照目录或用户指定位置（Downloads 等）
本地快照：scripts/app-data-snapshots/<name>/ 或 *.json
  ↓ 卸载 / 安装另一签名包
新包启动并同步媒体库
  ↓ 推送 JSON + 触发还原广播 / SAF 导入
应用内按 URI 匹配媒体，写回 Room DB 与 DataStore
```

## Quick Reference

### 方式一：PC 脚本

```bash
# 备份当前 TAG 数据（自动生成时间戳快照名）
./scripts/app-data-backup.sh backup

# 备份并指定快照名
./scripts/app-data-backup.sh backup before_release

# 先 dry-run 看媒体 URI 匹配率
./scripts/app-data-backup.sh dry-run before_release

# 恢复到当前设备
./scripts/app-data-backup.sh restore before_release

# 列出/删除快照
./scripts/app-data-backup.sh list
./scripts/app-data-backup.sh delete before_release
```

### 方式二：release 包手动 SAF

1. 在原包上打开「设置 → 备份与恢复 → 导出备份」，保存 JSON 到 Downloads。
2. 卸载原包，安装新包。
3. 打开新包，等待相册同步完成。
4. 进入「设置 → 备份与恢复 → 导入备份」，选择刚才的 JSON。

### 方式三：adb backup/restore

```bash
# 备份（不包 APK）
adb backup -f picme-backup.ab -noapk com.mamba.picme

# 切换包后恢复
adb restore picme-backup.ab
adb shell am force-stop com.mamba.picme
adb shell am start -n com.mamba.picme/.MainActivity
```

详见 `docs/05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md`。

## Full Workflow（PC 脚本）

### 1. 从当前包备份

```bash
./scripts/app-data-backup.sh backup before_switch
```

输出示例：

```
✅ 备份完成: before_switch
文件: scripts/app-data-snapshots/before_switch/tag_data_backup.json (54M)
```

### 2. 切换包

release / debug 签名不同，必须卸载重装：

```bash
adb uninstall com.mamba.picme
adb install androidApp/build/outputs/apk/debug/polang-debug.apk
# 或 adb install androidApp/build/outputs/apk/release/polang-release.apk
```

> 版本号差异不影响卸载重装后的安装。

### 3. 新包同步媒体库

打开应用，进入相册，等照片/视频全部加载出来。这一步必须完成，否则还原时 URI 匹配不上。

### 4. 先 dry-run 检查匹配率

```bash
./scripts/app-data-backup.sh dry-run before_switch
```

关注 `matchedMediaCount`，如果接近总媒体数即可继续；如果很低，说明同步还没完成，请等待。

### 5. 执行还原

```bash
# 如果应用正在后台同步，建议先 force-stop 再还原
adb shell am force-stop com.mamba.picme

./scripts/app-data-backup.sh restore before_switch
```

## What Is Backed Up

备份 JSON 包含以下数据：

| 数据 | 说明 |
|---|---|
| `tags` | 标签表 |
| `mediaTagMetadata` | 媒体 TAG 元数据（labels、OCR、语义嵌入、faceRoi、lastTagScanAt 等；v5 增补 `blurScore`/`exposureScore`/`lastViewedAt` 三列整理中心信号） |
| `crossRefs` | 媒体-标签关联 |
| `scanTasks` | TAG 扫描任务（恢复后重置为 PENDING） |
| `persons` | 人物聚类结果 |
| `faceEmbeddings` | 人脸 Embedding（Pass 1 产物，Base64） |
| `personRelations` | 人物关系（含 customLabel 自定义称呼） |
| `memoryFacts` | AI 记忆事实 |
| `chatSessions` / `chatMessages` | 聊天会话与消息 |
| `photoEditRecipes` | 照片编辑配方 |
| `mediaFeedback` | AI 优化反馈 |
| `ocrWords` / `ocrWordOccurrences` | OCR 倒排索引 |
| `locationHierarchy` / `mediaLocations` | 地理位置关系 |
| `preferences` | DataStore 用户偏好：账号、Token（Cloudflare/飞书/服务端）、主题、语言、相机记忆、AI Agent 配置等 |

**不备份：** 下载的模型文件。

## Backup File Location

- **PC 脚本默认快照目录**：`scripts/app-data-snapshots/<name>/tag_data_backup.json`
- **设备端中转目录**：`/sdcard/Android/media/com.mamba.picme/PoLangBackup/tag_data_backup.json`
  - 属于应用自身外部媒体目录，无需额外权限即可读写。
  - adb 可直接 `pull/push`，同时支持 debug 与 release 包。
  - 脚本在备份/恢复完成后会清理该中转文件。
- **SAF 导出目录**：由用户在系统文件选择器中指定（如 Downloads/PicMe）。

## Common Mistakes

| 问题 | 原因 | 修复 |
|---|---|---|
| 还原时报超时 | 设备端操作未完成，或应用进程未启动 | 脚本已先 `am start` 启动 MainActivity；检查日志 `tag=BackupRestoreReceiver` |
| `matchedMediaCount` 很低 | 新包媒体库还没同步完 | 等相册完全加载后再 dry-run / restore |
| adb 广播无响应 | Android 16 上 `am broadcast` 必须显式指定组件名 | 脚本已使用 `-n com.mamba.picme/.testing.backup.BackupRestoreBroadcastReceiver`（仅 debug 构建；release 走应用内 SAF） |
| 扫描任务变多 | 还原追加了备份里的任务，原有任务也在 | 调度器会按 `lastTagScanAt` 自动跳过已扫描媒体 |
| release 包无法使用脚本 | 旧脚本依赖 `run-as` | 当前脚本已改为外部媒体目录 `adb pull/push`，release 包可用 |
| SAF 导入提示匹配 0 | 备份时与恢复时的媒体 URI 不一致 | 确认照片未被删除，且新包已完成媒体库同步 |

## Implementation Details

- 跨安装匹配键是 **MediaStore URI**（`content://media/external/...`），不依赖应用内自增 `mediaId`。
- 标签以 `name` 为唯一键重建，避免 `tagId` 变化导致关联失效。
- 人物/Embedding 以旧 `personId` 做映射，恢复为新 `personId`。
- 还原在单个 SQLite 事务中执行，保证原子性。
- DataStore 用户偏好通过 `dataStore.data.first()` 导出为 key-value 列表，恢复时通过 `dataStore.edit` 写入，避免直接操作 `preferences_pb` 文件锁。
- 备份 JSON 使用 **Moshi + Okio 流式读写**（`JsonAdapter.toJson(BufferedSink)` / `fromJson(BufferedSource)`），避免大备份一次性序列化为字符串导致 OOM。
- 备份 JSON 内置 `version` 字段（当前为 5），未来可用于 Schema 兼容性检查。

## Project-Specific Paths

```
androidApp/src/main/java/com/mamba/picme/domain/backup/
androidApp/src/main/java/com/mamba/picme/features/backuprestore/BackupRestoreActivity.kt
androidApp/src/debug/java/com/mamba/picme/testing/backup/BackupRestoreBroadcastReceiver.kt
androidApp/src/main/res/xml/data_extraction_rules.xml
androidApp/src/main/res/xml/backup_rules.xml
scripts/app-data-backup.sh
scripts/app-data-snapshots/
docs/05-DEVELOPMENT/RELEASE_PACKAGE_BACKUP_RESTORE.md
```
