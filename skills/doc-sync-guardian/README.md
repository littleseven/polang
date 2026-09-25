# 📚 DocSync Guardian - 文档一致性守护者

## 🎯 Skill 概述

**DocSync Guardian** 是 PoLang 项目专用的文档一致性维护工具，自动化管理三层文档体系（PRODUCT.md → FEATURES.md → 模块 AGENTS.md）的同步与审计。

### 核心价值

- 🔍 **自动检测**：识别文档与代码的不一致
- 🔄 **同步更新**：提供标准化的文档更新流程
- ✅ **一致性审计**：生成跨文档对照报告
- 📊 **变更追踪**：记录重大技术决策的文档化状态

---

## 📦 文件结构

```
doc-sync-guardian/
├── SKILL.md                          # Skill 主文档（使用指南）
├── README.md                         # 本文件（快速开始）
└── scripts/
    ├── check-doc-consistency.sh      # 文档一致性检查脚本
    ├── sync-doc-template.py          # 文档同步更新模板生成器
    ├── check-i18n-sync.py            # I18N 五语资源同步检查
    └── generate-audit-report.py      # 综合审计报告生成器
```

---

## 🚀 快速开始

### 1. 日常审计（每周执行）

```bash
# 在项目根目录执行

# 运行综合审计
./skills/doc-sync-guardian/scripts/generate-audit-report.py

# 或单独运行文档一致性检查
./skills/doc-sync-guardian/scripts/check-doc-consistency.sh
```

### 2. 功能交付后同步文档

```bash
# 获取最新 commit hash
git log -1 --format=%H

# 生成文档更新草案
python3 skills/doc-sync-guardian/scripts/sync-doc-template.py \
  --commit-hash abc123def456 \
  --output /tmp/doc_update_draft.md

# 查看草案并手动更新文档
code /tmp/doc_update_draft.md
```

### 3. 检查 I18N 同步

```bash
python3 skills/doc-sync-guardian/scripts/check-i18n-sync.py
```

---

## 📋 使用场景

### 场景 1: 新功能开发完成后

**问题**: 实现了新的美颜功能，但不确定文档是否需要同步更新。

**解决方案**:
```bash
# 1. 分析最近的代码变更
python3 skills/doc-sync-guardian/scripts/sync-doc-template.py \
  --commit-hash HEAD

# 2. 根据生成的草案更新文档
# - PRODUCT.md: 补充性能指标
# - FEATURES.md: 添加交互说明
# - Camera AGENTS.md: 完善技术实现

# 3. 验证一致性
./skills/doc-sync-guardian/scripts/check-doc-consistency.sh
```

### 场景 2: Code Review 阶段

**问题**: PR 中包含大量代码变更，需要确认文档是否同步。

**解决方案**:
```bash
# 在 CR 检查清单中添加
- [ ] 运行文档一致性检查
- [ ] 确认 I18N 五语资源同步
- [ ] 验证技术决策已记录

# 执行检查
./skills/doc-sync-guardian/scripts/check-doc-consistency.sh
python3 skills/doc-sync-guardian/scripts/check-i18n-sync.py
```

### 场景 3: 定期维护（每周）

**问题**: 需要确保整个项目的文档保持最新。

**解决方案**:
```bash
# 生成综合审计报告
python3 skills/doc-sync-guardian/scripts/generate-audit-report.py

# 查看报告并采取行动
ls -lt docs/comprehensive_audit_*.md | head -1 | xargs code
```

### 场景 4: 记录重大技术决策

**问题**: 完成了拍照 GPU 化迁移，需要记录技术决策。

**解决方案**:
1. 创建技术专项文档 `docs/03-TECHNICAL-SPECS/PHOTO_GPU_TECH_SPEC.md`
2. 更新三层文档引用：
   - PRODUCT.md Section 3.1
   - FEATURES.md Section 1.3.5
   - Camera AGENTS.md Section 2.3
3. 在文档中添加决策记录（参考 SKILL.md 模板）

---

## 🔧 脚本详解

### 1. check-doc-consistency.sh

**功能**: 检查三层文档的一致性

**输出**:
- ✅ 通过项列表
- ⚠️ 警告项列表
- ❌ 错误项列表
- 详细审计报告文件

**示例输出**:
```
✅ 通过项 (12)
   • PRODUCT.md 包含性能指标定义
   • FEATURES.md 包含交互流程说明
   • 所有模块 AGENTS.md 均包含第 5 章

⚠️ 警告项 (3)
   • 缺少第 5 章: androidApp/src/main/java/com/mamba/picme/features/editor/AGENTS.md
   • 悬空引用: docs/01-PRODUCT/FEATURES.md -> docs/OLD_FEATURE.md

❌ 错误项 (0)
```

### 2. sync-doc-template.py

**功能**: 根据 Git commit 生成文档更新草案

**参数**:
- `--commit-hash`: Git commit hash（必填）
- `--output`: 输出文件路径（可选，默认 `/tmp/doc_sync_draft.md`）

**示例**:
```bash
python3 skills/doc-sync-guardian/scripts/sync-doc-template.py \
  --commit-hash abc123 \
  --output draft.md
```

**生成的草案包含**:
- 影响的模块列表
- 需要更新的文档章节
- 针对变更类型的检查清单
- 执行步骤建议

### 3. check-i18n-sync.py

**功能**: 检查五语资源（values / values-zh-rCN / values-zh-rTW / values-es / values-fr）同步情况

**检查项**:
- 缺失的字符串键
- 值不一致的字符串（用于审查）
- 各语言字符串数量统计

**示例输出**:
```
✅ 加载 en: 156 个字符串
✅ 加载 zh-CN: 156 个字符串
✅ 加载 zh-TW: 154 个字符串

❌ 缺失的字符串
### zh-TW 缺少 2 个字符串:
- `camera_exposure_slider_label`
- `gallery_batch_share_button`
```

### 4. generate-audit-report.py

**功能**: 生成综合审计报告（整合所有检查结果）

**输出**: 
- 控制台摘要
- 详细报告文件 `docs/comprehensive_audit_YYYYMMDD_HHMMSS.md`

---

## 📊 审计报告解读

### 报告结构

```markdown
# 📊 PoLang 项目综合审计报告

## 🔍 审计范围
## 📋 执行检查
  ### 1. 文档一致性检查
  ### 2. I18N 五语资源同步检查
## 📈 总体评估
  ### 关键指标
  ### 优先级建议
## 🎯 下一步行动
```

### 关键指标说明

| 指标 | 含义 | 目标值 |
|------|------|--------|
| 文档引用链完整性 | PRODUCT.md → FEATURES.md → AGENTS.md 是否有断裂 | 100% |
| I18N 同步率 | 五语资源键数量一致性 | 100% |
| 模块规范符合率 | AGENTS.md 第 5 章完整率 | 100% |
| 链接有效性 | Markdown 链接无悬空引用 | 100% |

### 优先级建议

- 🔴 **高优先级**: 必须立即修复（如文档与代码冲突）
- 🟡 **中优先级**: 本周内修复（如过时文档）
- 🟢 **低优先级**: 有空时优化（如文档结构调整）

---

## 🎓 最佳实践

### 1. 集成到 CI/CD（推荐）

在 `.github/workflows/ci.yml` 中添加：

```yaml
- name: Check Documentation Consistency
  run: |
    ./skills/doc-sync-guardian/scripts/check-doc-consistency.sh
    
- name: Check I18N Sync
  run: |
    python3 skills/doc-sync-guardian/scripts/check-i18n-sync.py
```

### 2. 添加到 Git Hook

在 `.git/hooks/pre-commit` 中添加：

```bash
#!/bin/bash
# 提交前自动检查文档一致性
echo "🔍 检查文档一致性..."
./skills/doc-sync-guardian/scripts/check-doc-consistency.sh || {
  echo "⚠️  文档一致性检查失败，请修复后再提交"
  exit 1
}
```

### 3. 定期审计计划

```bash
# 添加到 crontab（每周一上午 9 点执行）
0 9 * * 1 # 在项目根目录执行 && \
  python3 skills/doc-sync-guardian/scripts/generate-audit-report.py
```

### 4. PR 模板集成

在 `.github/pull_request_template.md` 中添加：

```markdown
## 📝 文档更新确认

- [ ] 已更新 PRODUCT.md（如有产品指标变更）
- [ ] 已更新 FEATURES.md（如有交互流程变更）
- [ ] 已更新模块 AGENTS.md（如有技术实现变更）
- [ ] 已运行文档一致性检查并通过
- [ ] 已同步 I18N 五语资源
```

---

## 🚨 常见问题

### Q1: 脚本执行失败怎么办？

**A**: 
1. 检查 Python 版本（需要 3.9+）
   ```bash
   python3 --version
   ```
2. 检查脚本权限
   ```bash
   chmod +x skills/doc-sync-guardian/scripts/*.sh
   chmod +x skills/doc-sync-guardian/scripts/*.py
   ```
3. 查看详细错误信息
   ```bash
   bash -x skills/doc-sync-guardian/scripts/check-doc-consistency.sh
   ```

### Q2: 如何自定义检查规则？

**A**: 编辑对应的脚本文件：
- 文档一致性: `check-doc-consistency.sh`
- I18N 检查: `check-i18n-sync.py`

在脚本中添加自定义检查逻辑即可。

### Q3: 审计报告太大怎么办？

**A**: 
1. 限制输出长度（修改 `generate-audit-report.py`）
2. 只运行特定检查
   ```bash
   # 仅检查文档一致性
   ./skills/doc-sync-guardian/scripts/check-doc-consistency.sh
   
   # 仅检查 I18N
   python3 skills/doc-sync-guardian/scripts/check-i18n-sync.py
   ```

### Q4: 如何处理历史遗留的文档问题？

**A**: 
1. 运行审计生成完整报告
2. 按优先级分批修复（先高优先级，后低优先级）
3. 每次修复后重新运行审计验证
4. 对于无法立即修复的问题，在报告中标记为"已知问题"

---

## 📚 相关文档

- [SKILL.md](SKILL.md) - 完整的 Skill 使用指南
- [AGENTS.md](../../AGENTS.md) - PoLang 顶层 Agent 治理规范
- [AGENTS.md](../../AGENTS.md) - 顶层 Agent 治理规范
- [PRODUCT.md](../../PRODUCT.md) - 产品需求规格说明书
- [docs/01-PRODUCT/FEATURES.md](../../docs/01-PRODUCT/FEATURES.md) - 功能交互细节规范

---

## 🤝 贡献指南

欢迎提交改进建议或 Bug 修复：

1. Fork 本项目
2. 创建特性分支 (`git checkout -b feature/amazing-skill`)
3. 提交更改 (`git commit -m 'Add amazing skill'`)
4. 推送到分支 (`git push origin feature/amazing-skill`)
5. 开启 Pull Request

---

## 📝 许可证

本 Skill 遵循 PoLang 项目许可证。

---

**Skill 版本**: 1.0  
**创建日期**: 2026-05-03  
**维护者**: [CR] 规范守护者 + [CO] 协调者  
**反馈渠道**: 在项目 Issues 中提出
