# DocSync Guardian - 参考文档

> 本文件存放从 SKILL.md 拆分的详细模板、脚本代码和示例。

---

## §脚本工具

### check-doc-consistency.sh

```bash
#!/bin/bash
# 文档一致性快速检查脚本

echo "🔍 开始文档一致性检查..."

# 1. 检查 PRODUCT.md 指标是否在 FEATURES.md 有承接
echo "✅ 检查 PRODUCT.md -> FEATURES.md 引用链..."
python3 scripts/check_product_features_alignment.py

# 2. 检查模块 AGENTS.md 第 5 章完整性
echo "✅ 检查模块 AGENTS.md Product Alignment 章节..."
for agents_file in $(find androidApp engines -name "AGENTS.md"); do
    if ! grep -q "## 5. 与产品文档对照" "$agents_file"; then
        echo "⚠️  缺少第 5 章: $agents_file"
    fi
done

# 3. 检查 I18N 五语资源同步
echo "✅ 检查国际化资源同步..."
python3 scripts/check_i18n_sync.py

# 4. 生成报告
echo "📊 生成审计报告..."
python3 scripts/generate_audit_report.py

echo "✅ 检查完成！详见 docs/05-DEVELOPMENT/audit_report_$(date +%Y%m%d).md"
```

### sync-doc-template.py

```python
#!/usr/bin/env python3
"""
文档同步更新模板生成器
用法: python3 sync-doc-template.py --commit-hash abc123 --module Camera
"""

import argparse
import subprocess
from pathlib import Path

def get_changed_files(commit_hash):
    """获取指定 commit 变更的文件列表"""
    result = subprocess.run(
        ["git", "diff", "--name-only", f"{commit_hash}~1", commit_hash],
        capture_output=True, text=True
    )
    return result.stdout.strip().split('\n')

def determine_affected_docs(changed_files):
    """根据变更文件判断需要更新的文档"""
    affected_docs = {
        "PRODUCT.md": False,
        "docs/01-PRODUCT/FEATURES.md": False,
        "modules": set()
    }
    for file in changed_files:
        if "features/camera" in file:
            affected_docs["modules"].add("Camera")
        elif "features/gallery" in file:
            affected_docs["modules"].add("Gallery")
        elif "beauty-engine" in file:
            affected_docs["modules"].add("BeautyEngine")
    return affected_docs

def generate_update_draft(affected_docs):
    """生成文档更新草案"""
    draft = "## 📝 文档更新草案\n\n"
    if affected_docs["modules"]:
        for module in affected_docs["modules"]:
            draft += f"### {module} 模块 AGENTS.md\n"
            draft += f"**需要更新章节**:\n"
            draft += f"- Section 2: 技术实现规范\n"
            draft += f"- Section 4: 检查清单\n"
            draft += f"- Section 5: 产品对照\n\n"
    return draft

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="生成文档同步更新草案")
    parser.add_argument("--commit-hash", required=True, help="Git commit hash")
    parser.add_argument("--module", help="指定模块名（可选）")
    args = parser.parse_args()
    changed_files = get_changed_files(args.commit_hash)
    affected_docs = determine_affected_docs(changed_files)
    draft = generate_update_draft(affected_docs)
    print(draft)
    with open("/tmp/doc_sync_draft.md", "w") as f:
        f.write(draft)
    print("💾 草案已保存到 /tmp/doc_sync_draft.md")
```

---

## §更新草案模板

以"拍照 GPU 离屏渲染"为例：

```markdown
## 📝 文档更新草案

**变更来源**: Git Commit abc123 - "实现拍照 GPU 离屏渲染"

### 1. PRODUCT.md
**当前位置**: Section 3.1 美颜系统
**需要添加**:
- **拍照 GPU 化（2026-05 已落地）**：
    - **目标**：预览与拍照效果一致性从 70-85% 提升至 99%+
    - **性能指标**：1080p < 300ms，4K < 800ms
    - **降级策略**：GPU 失败时自动回退 CPU 路径

### 2. docs/01-PRODUCT/FEATURES.md
**当前位置**: Section 1.3.5
**需要添加**:
- **拍照后处理性能（大美丽 GPU 化，2026-05）**：
    - **处理时间目标**：1080p < 300ms，4K < 800ms
    - **一致性目标**：预览与拍照效果一致性 ≥ 99%
    - **降级策略**：GPU 离屏渲染失败时自动回退 CPU 路径

### 3. 模块 AGENTS.md
**当前位置**: Section 2.3
**需要添加**:
- EGL Offscreen Context 创建
- 复用预览 Shader 链路
- Texture -> PBO -> JPEG 编码
- 检查清单：降级/OOM/耗时

### 4. docs/03-TECHNICAL-SPECS/BEAUTY_ENGINE_TECH_SPEC.md
**新增章节**: 拍照 GPU 化方案（架构设计 / EGL 管理 / 降级策略）

### 更新优先级
🔴 **高**: PRODUCT.md, Camera AGENTS.md
🟡 **中**: FEATURES.md
🟢 **低**: BIG_BEAUTY_TECH_SPEC.md
```

---

## §使用示例

### 示例 1: 日常审计

```
用户: "检查文档一致性"

[CO] 收到指令，启动文档一致性审计
[CR] 执行三层文档对照检查...
     ✅ PRODUCT.md 15 项指标全部有承接
     ⚠️  发现 3 处不一致（详见审计报告）
[RD] 修复不一致项...
     - 更新 Camera AGENTS.md 废弃引擎引用
     - 同步唇色色号实现与文档
[QA] 验收文档更新质量...
     ✅ 所有链接有效，术语一致
[CO] 生成审计报告并汇总
     ✅ 审计完成，发现 3 个问题已全部修复
```

### 示例 2: 功能交付后同步文档

```
用户: "同步更新文档 Camera"

[CO] 识别变更范围：Git Commit xyz789 - "实现曝光滑杆实时预览"
[CR] 确定需要更新的文档：
     - docs/01-PRODUCT/FEATURES.md Section 1.2（交互流程）
     - app/.../camera/AGENTS.md Section 2（技术实现）
[RD] 生成更新草案并应用...
     ✅ FEATURES.md: 补充滑杆触感反馈说明
     ✅ AGENTS.md: 添加状态管理检查清单
[QA] 验收文档质量...
     ✅ 与代码实现一致，无遗漏
[CO] 提交文档更新
     git commit -m "docs: 同步曝光滑杆功能文档"
```

### 示例 3: 记录重大技术决策

```
用户: "记录重大变更 拍照 GPU 化"

[CO] 启动重大变更记录流程
[RD] 收集决策信息...
     - 背景: 预览/拍照效果不一致（70-85%）
     - 方案: GPU 离屏渲染复用 Shader
     - 指标: 1080p < 300ms, 一致性 ≥ 99%
[RD] 创建技术专项文档...
     ✅ docs/PHOTO_GPU_TECH_SPEC.md
[RD] 更新三层文档引用...
     ✅ PRODUCT.md Section 3.1
     ✅ FEATURES.md Section 1.3.5
     ✅ Camera AGENTS.md Section 2.3
[CR] 审查文档完整性...
     ✅ 备选方案、风险评估、验收标准齐全
[CO] 提交并通知团队
     ✅ 决策记录完成，团队已同步
```
