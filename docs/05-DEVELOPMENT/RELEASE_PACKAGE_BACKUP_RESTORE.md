# Release 包数据备份与恢复

> 适用场景：同一部测试机需要反复安装 **release** 与 **debug** 包，因签名不同必须卸载重装，导致数据库、TAG 扫描结果、人脸聚类、用户设置等全部丢失。本文档说明 release 包可用的数据备份/恢复方案。

## 方案概览

| 方案 | 适用包 | 是否需要电脑 | 备份粒度 | 推荐场景 |
|------|--------|-------------|---------|---------|
| **应用内 SAF 导入/导出**（设置 → 备份与恢复） | release / debug | 否 | 备份模型 v5（见下） | 日常手动备份、无电脑时 |
| **`adb backup/restore`** | release / debug | 是 | 应用数据（数据库、SharedPreferences、DataStore） | 整机应用数据迁移、脚本化 |
| **`scripts/app-data-backup.sh`** | 仅 debug | 是 | 备份模型 v5（同 SAF） | 开发脚本、CI/自动化 |

> **备份模型 v5 覆盖范围**：TAG 数据库（标签、媒体 TAG 元数据含 city/faceFocusY/aestheticScore/faceQualityScore/blurScore/exposureScore/lastViewedAt、媒体-标签关联、扫描任务）、人脸 Embedding 与人物聚类、OCR 倒排索引、地理位置关系、媒体反馈、人物关系图谱（person_relations）、事实记忆（memory_facts）、聊天会话与消息（chat_sessions/chat_messages）、编辑配方（photo_edit_recipes）、DataStore 用户偏好。
> **恢复语义（v5 增补三列）**：`blurScore`/`exposureScore`/`lastViewedAt`（整理中心 v2 信号，2026-09-07 起随备份导出）恢复时走 COALESCE——旧备份缺这三字段（JSON 反序列化回退 null）时**不覆盖本机已算值**；`lastViewedAt` 是价值保护信号（查看器打开记录），不可再生，必须防冲。
> **不覆盖**：`polang_llm_log.db`（LLM/tool/JS 日志）、`chat_image_cache`（可重建缓存）、`device_id`。

## 一、应用内 SAF 导入/导出（推荐，无需 adb）

自本版本起，release 与 debug 包均在「设置 → 备份与恢复」提供入口：

1. **导出备份**：点击「导出备份」，选择保存位置（如 Downloads/PoLang），应用会把当前 TAG 数据、人脸聚类、OCR 索引、地理位置关系与 DataStore 设置写入 JSON。
2. **导入备份**：重装应用后，进入同一入口，点击「导入备份」，选择之前保存的 JSON 文件，等待恢复完成。

### 限制

- 导入依赖媒体 URI 匹配（`content://media/external/...`）。如果重装后系统媒体库 ID 变化导致 URI 无法匹配，对应媒体的 TAG 元数据将不会被恢复。
- 不同数据库 Schema 版本的应用之间恢复可能失败；建议尽量在相同版本或向后兼容版本之间操作。
- 聊天图片消息的 `content` 与编辑配方的 `outputUri` 指向旧安装的本地文件/媒体 URI，跨安装恢复后对应图片可能不存在，仅保留记录本身。

## 二、adb backup / restore

### 2.1 前置条件

- 已启用 USB 调试并连接设备。
- 应用 `AndroidManifest.xml` 已设置：
  ```xml
  android:allowBackup="true"
  android:dataExtractionRules="@xml/data_extraction_rules"
  android:fullBackupContent="@xml/backup_rules"
  ```
- 备份规则已限定只包含数据库、SharedPreferences、DataStore，并排除 `files/llm_models` 等可重新下载的大文件。

### 2.2 备份

> ⚠️ `adb backup` 已被 Google 标记为废弃，且在现代 Android 上执行时设备会弹出确认对话框，必须手动点击确认后才能生成备份文件。它更适合作为整机迁移的备选，日常推荐 SAF 或脚本方案。

```bash
adb backup -f picme-backup.ab -noapk com.mamba.picme
```

参数说明：

- `-f picme-backup.ab`：输出文件。
- `-noapk`：不备份 APK 本身（节省时间）。
- 执行后请在设备上点击「备份我的数据」并输入密码（可选）。如果未确认，输出文件大小会是 0 B。
- 如需包含共享存储中的手动备份文件，可单独用 `adb pull`：
  ```bash
  adb pull /sdcard/Android/media/com.mamba.picme/PoLangBackup/ ./picme-external-backup/
  ```

### 2.3 恢复

在目标设备上安装 release/debug 包后：

```bash
adb restore picme-backup.ab
```

恢复完成后建议强制停止应用再打开：

```bash
adb shell am force-stop com.mamba.picme
adb shell am start -n com.mamba.picme/.MainActivity
```

### 2.4 验证

```bash
adb shell run-as com.mamba.picme ls -l databases/
adb shell run-as com.mamba.picme ls -l shared_prefs/
adb shell run-as com.mamba.picme ls -l files/datastore/
```

> 注意：`run-as` 仅对 debug 签名或 `android:debuggable=true` 的包有效；release 包无法使用 `run-as`，但 `adb restore` 仍会写入数据，可通过应用功能验证。

### 2.5 Android 版本差异

- **Android 11 及以下**：`adb backup` 默认包含应用数据，兼容性较好。
- **Android 12+**：Google 收紧了 `adb backup` 行为，只有通过 `data-extraction-rules` 显式 `include` 的数据才会被导出。本项目规则文件已做适配，只导出数据库、SharedPreferences 和 DataStore。
- 若 `adb restore` 后数据未出现，请检查系统是否启用了本地备份传输：
  ```bash
  adb shell bmgr transport com.android.localtransport/.LocalTransport
  ```
- 部分国产 ROM 可能限制或移除 `adb backup` 的本地传输，导致无法使用；此时请改用 SAF 或 `scripts/app-data-backup.sh`。

## 三、scripts/app-data-backup.sh（仅 debug 包）

脚本通过 adb 广播调用 debug 构建中的 `BackupRestoreBroadcastReceiver`
（`androidApp/src/debug/java/com/mamba/picme/testing/backup/`，action `com.mamba.picme.AGENT_TEST`），
备份粒度与 SAF 完全一致（备份模型 v5），适合开发/CI 自动化：

```bash
./scripts/app-data-backup.sh backup before_release   # 备份到本地快照
./scripts/app-data-backup.sh dry-run before_release  # 模拟恢复，只统计媒体匹配
./scripts/app-data-backup.sh restore before_release  # 恢复快照
./scripts/app-data-backup.sh list                    # 列出快照
```

> 历史说明：旧入口 `AgentTestBroadcastReceiver` 已随 ADR-011 退役，
> 当前 receiver 仅保留 `backup_tag_data` / `restore_tag_data` 两个命令。
> release 包不包含该 receiver，请改用 SAF 方案。

## 四、跨版本注意事项

1. **数据库 Schema 变化**：如果两个安装包的数据库版本不同，`adb restore` 恢复后 Room 会按当前版本的迁移规则处理；若迁移不存在会崩溃。建议先升级应用再恢复，或降级数据库版本。
2. **媒体 URI 变化**：SAF JSON 恢复以 URI 为键；如果换机或系统重置导致媒体 URI 变化，需重新扫描媒体库。
3. **账号/Token**：DataStore 设置（含 API key、Token、模型配置）会随 `adb backup` 或 SAF JSON 一起恢复，但云端会话/授权状态可能已失效，必要时重新登录。

## 五、故障排查

| 现象 | 可能原因 | 处理 |
|------|---------|------|
| `adb restore` 后应用数据仍为空 | Android 12+ 备份范围受限；或使用了 `-apk` 覆盖安装 | 检查 `data_extraction_rules` 是否包含数据库；确认恢复的是目标包 |
| SAF 导入提示「匹配 0 个媒体」 | 备份时与恢复时的媒体 URI 不一致 | 确保重装后系统相册已重新扫描，且照片未被删除 |
| JSON 导入崩溃 | 备份 JSON 与当前数据库 Schema 不兼容 | 使用相同版本应用恢复，或联系开发更新备份模型 |
| `adb backup` 文件为 0 B 或极小 | 未在设备上点击确认；或系统未导出应用数据 | 在设备弹窗中确认备份；检查 `allowBackup=true` 与规则文件；切换本地 transport |

## 六、相关文件

- 备份规则：`androidApp/src/main/res/xml/data_extraction_rules.xml`
- 兼容规则：`androidApp/src/main/res/xml/backup_rules.xml`
- 应用内 SAF 入口：`androidApp/src/main/java/com/mamba/picme/features/backuprestore/BackupRestoreActivity.kt`
- 备份仓库：`androidApp/src/main/java/com/mamba/picme/domain/backup/TagDataBackupRepository.kt`
- 脚本入口：`scripts/app-data-backup.sh`
- 脚本化广播入口（仅 debug）：`androidApp/src/debug/java/com/mamba/picme/testing/backup/BackupRestoreBroadcastReceiver.kt`
