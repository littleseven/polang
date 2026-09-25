---
name: image-quality-checker
description: |
  通过 adb 截屏并分析图片质量，检测黑屏、人脸位置、调试信息是否正常。
version: 1.1.1
created: 2026-05-03
updated: 2026-09-25
maintainer: "[RD] 全栈工程师"
tags:
  - adb
  - image
  - quality
  - debug
  - camera
  - testing
---


# 图片质量验证 Skill

> **定位**：通过 adb 截屏并分析图片质量，检测黑屏、人脸位置、调试信息是否正常。
> **自动化脚本**：`skills/image-quality-checker/scripts/`（`auto_check_photo.sh` / `check_quality.sh` / `analyze_image.py`）已实现；无脚本时按「快速开始」手动等效方案。


通过 adb 截屏并结合 Python 脚本自动分析画面质量，诊断渲染问题。

## 快速开始（手动命令）

> 自动化脚本位于 `skills/image-quality-checker/scripts/`；以下为原始 adb 等效方案：

### 1. 截屏并拉取
```bash
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png /tmp/screen.png
```

### 2. 快速亮度分析
```bash
python3 -c "
from PIL import Image
import numpy as np
img = Image.open('/tmp/screen.png')
arr = np.array(img)
print(f'Size: {img.size}, Mean: {arr.mean():.1f}/255')
if arr.mean() < 5: print('BLACK SCREEN!')
"
```

## 功能说明

### 自动检测项
| 检测项 | 说明 | 判定标准 |
|--------|------|----------|
| **黑屏检测** | 检查画面是否全黑或接近全黑 | 平均亮度 < 5/255 |
| **基本信息** | 分辨率、色彩模式、文件大小 | - |
| **人脸检测** | 使用 MediaPipe/MNN 检测人脸 | 至少检测到 1 张人脸 |
| **人脸位置** | 输出人脸关键点坐标及边界框 | 关键点在合理范围内 |
| **调试信息** | 检测画面中的文字覆盖（FPS、日志） | 文字清晰可读 |

## 使用场景

### 场景1: 手动验证拍照后是否有黑屏
```bash
# 1. 触发拍照（TEST_COMMAND 广播框架已随 ADR-011 退役；用 ui-driver 或手动在设备上拍照）
python3 scripts/ui_driver.py click --text "<快门按钮文案>"   # 或手动拍照
sleep 2

# 2. 拉取最新照片分析（相机照片落 /sdcard/Pictures/PoLang/）
adb shell ls -lt /sdcard/Pictures/PoLang/*.jpg | head -1
adb pull "/sdcard/Pictures/PoLang/<最新文件>" ./output.jpg

# 3. 分析亮度
python3 -c "from PIL import Image; import numpy as np; arr=np.array(Image.open('output.jpg')); print(f'Brightness: {arr.mean():.1f}/255')"
```

**输出示例**：
```
==================================================
=== 图片质量分析报告 ===
==================================================

文件: photo_20260503_132620.jpg
分辨率: 2000x3000
色彩模式: RGB
文件大小: 2.44 MB

[✓ 正常] 黑屏检测: 正常 (平均亮度: 75.8/255)
[✓ 检测到] 人脸检测: 检测到画面内容
[○ 未检测到] 调试信息: 无调试信息

==================================================
结论: 画面质量合格
==================================================

GPU 拍照日志摘要:
==================================================
D PoLang:PhotoProcessor: process DONE: elapsed=181ms
D PoLang:ImageProcessor: GPU photo processing succeeded

✅ 检查完成！
```

### 场景2: 验证 GPU 拍照是否黑屏
```bash
# 1. 触发拍照（同场景1：ui-driver 或手动拍照）
sleep 2

# 2. 拉取相册最新照片
adb shell ls -lt /sdcard/Pictures/PoLang/*.jpg | head -1
adb pull "/sdcard/Pictures/PoLang/<最新文件>" ./output.jpg

# 3. 分析
python3 -c "from PIL import Image; import numpy as np; arr=np.array(Image.open('output.jpg')); print(f'Brightness: {arr.mean():.1f}/255')"
```

### 场景3: 检查预览画面人脸检测
```bash
# 1. 截屏
adb shell screencap -p /sdcard/preview.png
adb pull /sdcard/preview.png ./preview.png

# 2. 分析
python3 -c "from PIL import Image; import numpy as np; arr=np.array(Image.open('./preview.png')); print(f'Brightness: {arr.mean():.1f}/255')"
```

### 场景3: 批量验证多张照片
```bash
for img in photos/*.jpg; do
    python3 -c "from PIL import Image; import numpy as np; arr=np.array(Image.open('$img')); print(f'{img}: brightness {arr.mean():.1f}/255')"
done
```

## 输出示例

```
=== 图片质量分析报告 ===
文件: preview.png
分辨率: 1080x2400
色彩模式: RGBA
文件大小: 2.3 MB

[✓] 黑屏检测: 正常 (平均亮度: 128/255)
[✓] 人脸检测: 检测到 1 张人脸
    - 边界框: (320, 450) - (760, 890)
    - 置信度: 0.98
    - 关键点数量: 106
[✓] 调试信息: 检测到 FPS 覆盖 (位置: 左上角)

结论: 画面质量合格
```

## 故障排除

### 检测到黑屏
- 检查 `adb logcat | grep PoLang:PhotoProcessor` 是否有 GL 错误
- 验证 FBO 状态：`glCheckFramebufferStatus`
- 确认 EGL 上下文是否正确绑定

### 未检测到人脸
- 确认光线充足，人脸正对摄像头
- 检查人脸检测引擎设置（MNN vs MediaPipe）
- 查看日志：`adb logcat | grep FaceDetector`

### 调试信息模糊
- 检查 Canvas 绘制时的抗锯齿设置
- 确认文字颜色与背景对比度足够

## 技术实现

- **截屏**: `adb shell screencap -p`
- **分析**: Python + Pillow + NumPy（纯 Python，无需 OpenCV）
- **人脸检测**: 可集成项目现有的 MNN 或 MediaPipe 模型

## 相关文件

- `skills/image-quality-checker/scripts/auto_check_photo.sh` — 自动化拍照质量检查（拉取 `/sdcard/Pictures/PoLang/` 最新照片）
- `skills/image-quality-checker/scripts/check_quality.sh` — 截屏验证流程
- `skills/image-quality-checker/scripts/analyze_image.py` — 图片质量分析
- [ui-driver](/ui-driver) — 拍照触发（TEST_COMMAND 广播已随 ADR-011 退役）

## 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| 1.1.0 | 2026-05-03 | 初始版本 |
| 1.1.1 | 2026-09-25 | 修正"脚本尚未实现"声明（scripts/ 已实装）；照片目录 DCIM→Pictures/PoLang；拍照触发改 ui-driver（TEST_COMMAND 广播已随 ADR-011 退役） |
