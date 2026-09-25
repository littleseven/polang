---
name: dev-loop
description: Use when running the full PoLang development self-heal loop from code check through install, device verification, and report generation
version: 2.3.0
created: 2026-05-03
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - android
  - build
  - test
  - automation
  - ci
---


# Dev Loop

> **定位**：PoLang 开发自循环自动化，一键完成编译到报告完整闭环。
> **触发时机**：用户需要快速验证改动、执行完整开发闭环或 CI 检查时自动启用。


## 设计目标

消除当前 AI Agent 工作流中**编译后需人工干预**的断点：

```
修改前: 代码修改 → ./gradlew assembleDebug → [人工] adb install → [人工] 打开应用 → [人工] 验证
修改后: 代码修改 → ./scripts/auto-dev-loop.sh → 全自动闭环（含报告）
```

## 快速开始

### 标准自循环（推荐）

代码修改后执行：

```bash
# 在项目根目录执行
./scripts/auto-dev-loop.sh
```

自动完成：
1. **代码检查** — ktlint + detekt + JVM unit tests
2. **编译** — `./gradlew :androidApp:assembleDebug`
3. **安装** — 自动 `adb install -r`
4. **设备验证** — 启动应用 + ui-driver UI dump（`scripts/ui_driver.py`，无障碍结构化数据）+ 收集日志
5. **报告生成** — Markdown 格式报告 + 所有日志/截图归档

> 历史的 JSON 命令测试（`AgentTestBroadcastReceiver`）与 `regression-test.sh` 已于 2026-07-28 随 **ADR-011**（退役非 ui-driver 测试）整体删除；设备端 UI 验证一律走 [ui-driver](/ui-driver)。

### 快速模式（仅编译+安装+启动）

```bash
./scripts/auto-dev-loop.sh --quick
```

### 纯代码检查（无设备）

```bash
./scripts/auto-dev-loop.sh --no-install
```

## 工作流集成

### 在 AI Agent 工作流中使用

**场景1: 代码修改后的标准验证**
```
1. RD 完成代码修改
2. 执行: ./scripts/auto-dev-loop.sh
3. 读取报告: scripts/auto_test_output/<timestamp>/report.md
4. 如果有失败 → 自动修复 → 重新执行
5. 如果全部通过 → 进入 CR/QA 环节
```

**场景2: 修复 Bug 后的定向验证**
```
1. 修复美颜相关 Bug
2. 执行: python3 scripts/ui_driver.py dump / find / click（按文案/位置定位控件）
3. 结合 auto-dev-loop.sh 报告中的 ui_dump_startup.txt 与 logcat 验证
```

**场景3: PR 提交前的完整验证**
```
1. 执行: ./scripts/ai-gate.sh（代码级检查）
2. 执行: ./scripts/auto-dev-loop.sh（设备级验证）
3. 全部通过 → 提交代码
```

## 输出目录结构

```
scripts/auto_test_output/
└── 20260509_143022/                    # 时间戳目录
    ├── report.md                       # 汇总报告
    ├── ktlint.log                      # 格式检查日志
    ├── detekt.log                      # 静态分析日志
    ├── unit_test.log                   # 单元测试日志
    ├── build.log                       # 编译日志
    ├── install.log                     # 安装日志
    ├── ui_dump_startup.txt             # 启动后 UI 结构化 dump（ui-driver）
    ├── logcat_picme.txt                # PoLang 标签日志
    ├── logcat_full.txt                 # 全量 logcat
    └── instrumented_test.log           # Instrumented test 日志
```

## 脚本参数对照

| 脚本 | 参数 | 说明 |
|------|------|------|
| `auto-dev-loop.sh` | `--no-install` | 跳过设备安装 |
| `auto-dev-loop.sh` | `--no-test` | 跳过设备端测试 |
| `auto-dev-loop.sh` | `--quick` | 快速模式（仅编译+安装+UI dump） |
| `ui_driver.py` | `dump` / `find` / `click` / `input` / `swipe` / `back` | 结构化 UI 自动化（ADR-011 后主要测试方法） |

## 故障排除

### 设备未连接
```
[WARN] 未检测到连接的设备，跳过设备端验证
```
**解决**: 连接设备后重试，或添加 `--no-install` 仅做代码检查。

### 安装失败（签名冲突）
```
尝试卸载后重装...
```
脚本会自动尝试卸载后重装，无需人工干预。

### Instrumented Test 无设备任务
```
[WARN] Instrumented Tests 跳过（无设备或无测试任务）
```
如果项目未配置 `connectedDebugAndroidTest` 任务，此警告可忽略。

### 截屏坐标不准确
老的坐标点击方案已废弃；一律用 ui-driver 按文案/`contentDescription`/bounds 定位，布局变化不会失效。

## 扩展指南

### 添加新的验证用例

用 ui-driver 组合（无广播框架，ADR-011 后唯一测试方法）：

```bash
# 定位并点击（按文案，返回结构化结果便于断言）
python3 scripts/ui_driver.py find --text "美颜"
python3 scripts/ui_driver.py click --text "磨皮"
# dump 全树供报告归档
python3 scripts/ui_driver.py dump > ui_dump_case.json
```

### 集成到 CI/CD

在 GitHub Actions / GitLab CI 中添加步骤：

```yaml
- name: Auto Dev Loop
  run: ./scripts/auto-dev-loop.sh --no-install
```

## 相关文件

- `scripts/auto-dev-loop.sh` — Dev Loop
- `scripts/ui_driver.py` — 结构化 UI 自动化（主要测试方法）
- `scripts/ai-gate.sh` — 代码级质量门禁
- `/android-build-debug` — 编译调试参考
- `/adb-bot` — adb 命令参考
- `/ui-driver` — 结构化 UI 自动化说明
- `/image-quality-checker` — 图片质量分析
- `/compose-ui-expert` — UI 验证参考
- `/perf-optimizer` — 性能基线对比
- `/i18n-validator` — 多语言验证
- `/ios-dev-loop` — iOS 闭环对标（simctl / screenshot-diff）

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
| 2.2.0 | 2026-08-03 | 参数与流程整理 |
| 2.3.0 | 2026-09-25 | 对齐 ADR-011：删 regression-test.sh/AgentTestBroadcastReceiver/--capture 段落，设备验证改 ui-driver；输出目录对齐脚本实况 |
