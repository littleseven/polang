---
name: adb-bot
description: Use when connecting Android devices, controlling app lifecycle, filtering logs, taking screenshots, pulling files, or checking performance metrics via adb
version: 3.0.0
created: 2026-05-03
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - adb
  - android
  - debug
  - device
---


# PoLang ADB 参考

> **定位**：通过 adb 命令调试 Android 设备与管理 PoLang 应用生命周期。
> **触发时机**：用户需要设备连接、截屏拉取、日志过滤、文件操作或性能监控时自动启用。
> **⚠️ 应用内控制已换代**：历史上的 `TEST_COMMAND` 广播控制框架（`AgentTestBroadcastReceiver`/`CameraTestCommandDispatcher`）已于 2026-07-28 随 **ADR-011** 整体退役；**应用内 UI 自动化一律用 [ui-driver](/ui-driver)**（`scripts/ui_driver.py`，无障碍结构化定位），本 skill 只承载通用 adb 调试能力。


## 快速开始

### 1. 启动应用
```bash
adb shell am start -n com.mamba.picme/.MainActivity
sleep 3
```

### 2. 截屏并拉取
```bash
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png
```

### 3. 过滤日志
```bash
adb logcat -s PoLang:* *:S
```

---

## 一、应用内自动化（经 ui-driver）

广播控制已删除；点击/输入/滑动用结构化定位（文案 / contentDescription / bounds），布局变化不失效：

```bash
# 查找控件（按文案）
python3 scripts/ui_driver.py find --text "美颜"

# 点击（如触发快门/切 Tab）
python3 scripts/ui_driver.py click --text "快门"

# 文本输入 / 滑动 / 返回
python3 scripts/ui_driver.py input --text "搜索框" --value "搜索词"
python3 scripts/ui_driver.py swipe --start-x 540 --start-y 1600 --end-x 540 --end-y 400
python3 scripts/ui_driver.py back

# dump 全树（报告归档/断言）
python3 scripts/ui_driver.py dump
```

前置：设备已连接（`adb devices`）、应用已安装并运行、无障碍服务 `com.mamba.picme/.accessibility.PoLangAccessibilityService` 已启用。详见 [ui-driver](/ui-driver)。

---

## 二、设备调试（Debug）

### 截屏分析

#### 自动截屏并查看
```bash
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png
```

#### 连续截屏监控
```bash
for i in 1 2 3; do
    adb shell screencap -p /sdcard/screen_$i.png
    adb pull /sdcard/screen_$i.png /tmp/screen_$i.png
    sleep 2
done
```

### 日志分析

#### 实时过滤日志
```bash
# 过滤特定标签
adb logcat -s PoLang:* *:S

# 过滤多个标签
adb logcat -s PoLang:BeautyRenderer:FaceMakeupPass:* *:S

# 清除后重新捕获
adb logcat -c
adb shell am force-stop com.mamba.picme
adb shell am start -n com.mamba.picme/.MainActivity
adb logcat -s PoLang:*
```

#### 导出日志到文件
```bash
adb logcat -d > /tmp/logcat.txt
grep -i "error\|exception\|failed" /tmp/logcat.txt
```

### 应用状态检查

```bash
# 检查应用是否运行
adb shell pidof com.mamba.picme

# 强制重启应用
adb shell am force-stop com.mamba.picme
adb shell am start -n com.mamba.picme/.MainActivity

# 检查 GPU/渲染状态
adb shell dumpsys gfxinfo com.mamba.picme
```

### 文件操作

```bash
# 拉取 SharedPreferences（debug 包）
adb shell run-as com.mamba.picme cat /data/data/com.mamba.picme/shared_prefs/*.xml

# 拉取数据库（debug 包）
adb shell run-as com.mamba.picme cat /data/data/com.mamba.picme/databases/*.db > /tmp/app.db

# 推送测试资源
adb push test_image.jpg /sdcard/Pictures/
```

### 渲染问题专项调试

```bash
# 检查 OpenGL 错误
adb logcat -d | grep -i "gl_error\|shader\|compile\|link"

# 检查 Shader 编译状态
adb logcat -d | grep -i "shader.*compiled\|program.*linked"

# 验证纹理加载
adb logcat -d | grep -i "texture\|bitmap\|load"
```

### 性能监控

```bash
# FPS 监控
adb shell dumpsys gfxinfo com.mamba.picme | grep -i "jank\|frame"

# 内存使用
adb shell dumpsys meminfo com.mamba.picme
```

---

## 三、自动化测试流程

### 完整调试流程（ui-driver + 调试结合）
```bash
#!/bin/bash
# 1. 确保应用运行
if ! adb shell pidof com.mamba.picme > /dev/null; then
    adb shell am start -n com.mamba.picme/.MainActivity
    sleep 3
fi

# 2. 清除日志
adb logcat -c

# 3. 截屏（操作前）
adb shell screencap -p /sdcard/before.png
adb pull /sdcard/before.png /tmp/before.png

# 4. 执行 UI 操作（结构化定位）
python3 scripts/ui_driver.py click --text "滤镜"
python3 scripts/ui_driver.py click --text "快门"

# 5. 等待渲染完成
sleep 1

# 6. 截屏（操作后）
adb shell screencap -p /sdcard/after.png
adb pull /sdcard/after.png /tmp/after.png

# 7. 收集日志
adb logcat -d > /tmp/logcat.txt
```

---

## 四、常用命令速查

| 命令 | 用途 |
|------|------|
| `adb devices` | 检查设备连接 |
| `adb shell screencap -p /sdcard/screen.png` | 截屏 |
| `adb pull /sdcard/screen.png /tmp/` | 拉取文件 |
| `adb logcat -c` | 清除日志 |
| `adb logcat -s TAG:*` | 过滤日志 |
| `adb shell input tap x y` | 模拟点击（盲坐标，优先用 ui-driver） |
| `adb shell input swipe x1 y1 x2 y2` | 模拟滑动 |
| `adb shell am start -n pkg/.Activity` | 启动 Activity |
| `adb shell am force-stop pkg` | 强制停止应用 |
| `adb shell pidof pkg` | 检查进程是否存在 |
| `adb shell dumpsys gfxinfo pkg` | GPU 渲染信息 |
| `adb shell dumpsys meminfo pkg` | 内存信息 |
| `python3 scripts/ui_driver.py <子命令>` | 应用内 UI 自动化（唯一测试方法） |

---

## 五、故障排除

### ui-driver 无响应
1. 确认应用在前台运行：`adb shell pidof com.mamba.picme`
2. 确认无障碍服务已启用（设置 → 无障碍 → PoLangAccessibilityService）
3. `python3 scripts/ui_driver.py dump` 确认能取到 UI 树

### 编译后行为未变
- **常见陷阱**：修改代码后只运行 `compileDebugKotlin` 不会重新生成 APK，必须运行 `assembleDebug`

### 截屏失败
- 检查设备是否连接：`adb devices`
- 检查存储权限：`adb shell ls /sdcard/`

---

## 六、技术说明

- **UI 自动化通路**：`PoLangAccessibilityService`（debug 源集，端口 27183）↔ `scripts/ui_driver.py`（结构化 JSON 协议）
- **历史框架**：`TEST_COMMAND` 广播 + `CameraTestCommandDispatcher` 已删除（ADR-011，`docs/02-ARCHITECTURE/ADR/ADR-011-retire-non-ui-driver-tests.md`）；旧命令列表 `commands.md` 已随之移除，查 git 历史

---

## 相关文件

- [ui-driver](/ui-driver) — UI Driver（应用内自动化唯一入口）
- [dev-loop](/dev-loop) — 编译→安装→验证闭环
- [image-quality-checker](/image-quality-checker) — 截图质量分析

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.0.0 | 2026-05-03 | 初始版本 |
| 1.1.0 | 2026-05-31 | 统一格式规范，添加定位块 |
| 3.0.0 | 2026-09-25 | 对齐 ADR-011：删除整章 TEST_COMMAND 广播控制（类/接收器/commands.md 均已退役），应用内自动化改经 ui-driver；保留通用 adb 调试能力 |
