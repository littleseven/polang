---
name: ui-driver
description: Use when automating PoLang UI interactions through structured accessibility data, replacing screenshot-based image recognition with precise text, contentDescription, or bounds-driven actions
---


# UI Driver

## Overview

Drive PoLang UI automation through structured accessibility node data instead of screenshots. The PC-side Python client `scripts/ui_driver.py` talks to `PoLangAccessibilityService` over a local JSON-RPC socket forwarded by adb.

## When to Use

- Dumping the current Android UI as text/class/bounds hierarchy
- Clicking elements by `contentDescription`, `text`, or exact bounds
- Typing into `EditText` fields after locating them
- Waiting for a UI element to appear before acting
- Avoiding screenshot + image-recognition based automation

## Why Screenshot-Based Automation Is Unreliable

| Problem | Cause |
|---------|-------|
| Resolution dependent | Different devices produce different screenshot sizes |
| Theme sensitive | Dark mode, dynamic colors change pixels |
| Animation interference | Hard to time screenshots during transitions |
| Position drift | Language/font/size changes move buttons |
| High maintenance cost | Any UI tweak requires new reference images |
| Token expensive | Base64 images are orders of magnitude larger than text |

## Recommended Approaches (in order)

### 1. Accessibility UI Driver（首选）

Use `scripts/ui_driver.py` to inspect or interact with the live UI.

### 2. Compose UI Test（Compose-only 页面）

```kotlin
composeTestRule.onNodeWithTag("exposure_slider")
    .performTouchInput { swipeRight() }
composeTestRule.onNodeWithContentDescription("切换摄像头")
    .performClick()
```

### 3. uiautomator dump（无 AccessibilityService 时备用）

```bash
adb shell uiautomator dump /sdcard/window_dump.xml
adb pull /sdcard/window_dump.xml
```

### 4. Espresso（传统 View 页面）

```kotlin
onView(withId(R.id.shutter_button)).perform(click())
onView(withText("确认")).check(matches(isDisplayed()))
```

## Quick Setup

```bash
# 1. Install debug APK
./gradlew :androidApp:installDebug --no-daemon

# 2. Enable the accessibility service
adb shell settings put secure enabled_accessibility_services \
  com.mamba.picme/.accessibility.PoLangAccessibilityService

# 3. Forward the RPC port
adb forward tcp:27183 tcp:27183

# 4. Start PoLang
adb shell am start -n com.mamba.picme/.MainActivity
```

## Core Pattern

```python
import sys
from pathlib import Path
sys.path.insert(0, str(Path("scripts").absolute()))

from ui_driver import UiDriverClient, UiNode

def has_child_text(node: UiNode, substring: str) -> bool:
    return any(
        substring in (child.text or "")
        for child in node.children
    )

with UiDriverClient() as client:
    # Dump the tree
    tree = client.dump_ui(package="com.mamba.picme")

    # Click by contentDescription
    client.click(content_description="搜索照片")

    # Find the search EditText by its placeholder child text, then input by bounds
    edit = client.find_node(
        lambda n: n.class_name == "android.widget.EditText"
                  and has_child_text(n, "搜索照片")
    )
    if edit:
        client.input_text("猫", bounds=edit.bounds)
```

## Public API Reference

| Method | Purpose |
|--------|---------|
| `dump_ui(package=...)` | Return the UI tree as `UiNode` |
| `find_nodes(text=..., content_description=..., class_name=..., clickable=..., scrollable=...)` | Find nodes server-side |
| `find_node(predicate)` | Depth-first search with a lambda |
| `click(text=\|content_description=\|bounds=...)` | Click a node; walks up to clickable ancestor if needed |
| `long_click(text=\|content_description=\|bounds=...)` | Long-click a node |
| `input_text(value, text=\|content_description=\|bounds=...)` | Set text on an `EditText` |
| `swipe((x1, y1), (x2, y2), duration_ms=300)` | Swipe gesture |
| `press_back()` | Press the back button |
| `wait_for(text, timeout_ms=5000)` | Poll until a node with matching text appears |
| `wait_for_idle(timeout_ms=5000)` | Wait for UI thread idle |

## CLI Quick Reference

```bash
python3 scripts/ui_driver.py dump
python3 scripts/ui_driver.py click --content-description "搜索照片"
python3 scripts/ui_driver.py click --text "2026-07-02"
python3 scripts/ui_driver.py input --bounds '{"left":325,"top":166,"right":1135,"bottom":322}' --value "猫"
python3 scripts/ui_driver.py swipe --start-x 600 --start-y 2000 --end-x 600 --end-y 500
python3 scripts/ui_driver.py back
python3 scripts/ui_driver.py find --content-description "关闭搜索"
```

## Common Mistakes

| Mistake | Fix |
|---------|-----|
| `ConnectionRefusedError` / `RPC error` | Re-run `adb forward tcp:27183 tcp:27183`; check that AccessibilityService is enabled and PoLang is in foreground |
| `Target node not found` | Run `dump` first; the app may already be in a different state; press back if needed. Note: `text=` zero-hit falls back to matching `contentDescription` server-side (v2.2.0+), so dump-visible labels are clickable via `--text`; use `--content-description` only to disambiguate |
| Input fails silently | Target the `EditText` node itself, not a parent container; use `bounds` if `text`/`contentDescription` are ambiguous |
| Using `client._call(...)` | Prefer public helpers; `input_text` now accepts `bounds` |
| Click returns true but nothing happens | The target may be decorative; try a parent with `contentDescription` or use `bounds` of a `Button` |

## State Management

Always verify the app state before acting:

```python
if client.find_nodes(content_description="关闭搜索"):
    client.press_back()
    time.sleep(1)
```

## Verification Script

Use the bundled end-to-end check:

```bash
python3 scripts/verify_ui_driver.py
```

Expected output: `✅ Integration test passed: search mode entered`

## Red Rules

**Never:**
1. Locate click targets by screenshot pixel matching
2. Use absolute coordinates without a fallback strategy
3. Click during animation transitions without waiting for idle
4. Proceed to the next step without verifying the current action result

## Related Skills

- [adb-bot](/adb-bot) — ADB Bot
- [dev-loop](/dev-loop) — Dev Loop

## Version History

| Version | Date | Change |
|---------|------|--------|
| 2.2.0 | 2026-11-07 | Server-side `text` matching falls back to `contentDescription` on zero hits (bottom-nav/icon labels visible in dump are now clickable via `--text`) |
| 2.1.0 | 2026-07-02 | Renamed directory and skill name from `accessibility-ui-driver` to `ui-driver` |
| 2.0.0 | 2026-07-02 | Merged `ui-automation-expert` content; renamed title to UI Driver |
| 1.0.0 | 2026-07-02 | Initial Accessibility UI Driver skill |
