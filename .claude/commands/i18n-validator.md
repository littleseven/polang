# I18N 验证专家 (Internationalization Validator)

> **定位**：确保所有用户可见文案同步覆盖 EN / zh-CN / zh-TW / ES / FR，禁止硬编码。
> **触发时机**：新增功能、修改文案、PR 审查、QA 验收时。

---

## 核心原则

1. **五语同步**：新增文案必须同时更新 `values`、`values-zh-rCN`、`values-zh-rTW`、`values-es`、`values-fr`
2. **禁止硬编码**：Kotlin/Java 源码中禁止出现用户可见的字符串字面量
3. **命名规范**：字符串资源 ID 采用小驼峰 `[feature]_[description]`
4. **占位符标准**：使用 Android 标准占位符，确保各语言语义通顺

---

## 验证流程

### Step 1: 硬编码检测

```bash
# 检查 Kotlin 源码中的硬编码中文/英文
./scripts/check-i18n-hardcode.sh

# 手动检查（关键文件）
grep -rn "\"[a-zA-Z\u4e00-\u9fa5]\{3,\}\"" androidApp/src/main/java/com/mamba/picme/ --include="*.kt" | \
    grep -v "Log\." | grep -v "TAG" | grep -v "http"
```

**判定标准**：用户可见的文案（非日志、非调试）必须走 `stringResource()` 或 `getString()`

### Step 2: 五语资源完整性

```bash
# 对比五语言资源文件
python3 scripts/check_i18n_sync.py
```

**检查项**：
- [ ] `values/strings.xml` 中的每个 key 在 `values-zh-rCN`、`values-zh-rTW`、`values-es` 和 `values-fr` 中存在
- [ ] 无重复 key
- [ ] 无空值
- [ ] 占位符数量一致（`%1$s`、`%2$d`）

### Step 3: 术语一致性

**关键术语对照表**（摘自 FEATURES.md）：

| 英文 | 简体中文 | 繁体中文 | 资源 ID |
|------|----------|----------|---------|
| Gallery | 相册 | 相簿 | `nav_gallery` |
| Settings | 设置 | 設定 | `nav_settings` |
| Smoothing | 磨皮 | 磨皮 | `beauty_smoothing` |
| Slim Face | 瘦脸 | 瘦臉 | `beauty_slim_face` |
| Extract Text | 提取文字 | 提取文字 | `ocr_extract_text` |

**禁止**：同一功能在不同位置使用不同翻译。

---

## 常见陷阱

| 陷阱 | 症状 | 修复 |
|------|------|------|
| **只更新默认语言** | 切换语言后显示英文 | 同步更新 zh-rCN / zh-rTW |
| **硬编码 Toast** | 切换语言后 Toast 不变 | 改用 `stringResource()` |
| **占位符不匹配** | 运行时崩溃 `FormatException` | 检查 `%1$s` vs `%1$d` |
| **复数未处理** | 英文单复数错误 | 使用 `plurals` 资源 |
| **日期格式硬编码** | 不符合地区习惯 | 使用 `DateTimeFormatter` with locale |

---

## 自动化检查

### CI 集成

```yaml
# .github/workflows/ai-gate.yml 中添加
- name: I18N Validation
  run: |
    python3 scripts/check_i18n_sync.py
    ./scripts/check-i18n-hardcode.sh
```

### 快速修复流程

```bash
# 1. 发现缺失
python3 scripts/check_i18n_sync.py
# 输出: ❌ Missing in zh-rCN: beauty_new_feature

# 2. 补充翻译
# values-zh-rCN/strings.xml: <string name="beauty_new_feature">新功能</string>
# values-zh-rTW/strings.xml: <string name="beauty_new_feature">新功能</string>

# 3. 重新验证
python3 scripts/check_i18n_sync.py
```

## 相关文件

- [docs/01-PRODUCT/FEATURES.md](docs/01-PRODUCT/FEATURES.md) — 交互规范（文案口径）
- [PRODUCT.md](PRODUCT.md) — I18N 规范定义
- [compose-ui-expert](/compose-ui-expert) — UI 文案硬编码检查
- [doc-sync-guardian](/doc-sync-guardian) — 文档一致性同步
- [ios-i18n-validator](/ios-i18n-validator) — iOS xcstrings 五语对标（双端键对齐）

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-25 | 初始版本 |
