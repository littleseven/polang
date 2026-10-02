#!/bin/bash
#
# iOS Auto Dev Loop — 真机无人值守自循环（对标 scripts/auto-dev-loop.sh Android 侧）
#
# 用途：iOS 改动后一键完成 工程准备 → 编译 → 安装 → 设备验证 → 报告 完整闭环，
#       任何阶段失败都隔离上报（不中途退出），最终给出 PASS/WARN/FAIL 裁决。
#
# 调用：./scripts/ios-auto-dev-loop.sh [选项]
#
# Options:
#   --no-install         跳过安装（仅工程准备+编译+单测）
#   --no-test            跳过设备端验证（截图/日志/diff）
#   --quick              快速模式：编译+安装+截图（跳过单测与详细验证）
#   --fast               极速模式：跳过 pod/xcodegen/单测，仅编译+安装+截图
#   --skip-prep          跳过 xcodegen + pod install（假定工程已就绪）
#   --device ID          指定设备 UDID（默认自动检测首个真机）
#   --screenshot NAME    截图命名（默认 polang-loop）
#   --diff               启动后做 Android↔iOS 跨端像素对比（需 /tmp/android-ui-reference 存在）
#   --help               显示帮助
#
# 与 Android auto-dev-loop.sh 对齐（5 阶段）：
#   Phase 1  工程准备 + 单元测试（xcodegen/pod + shared:jvmTest [+PoLangTests]）
#   Phase 2  编译 Debug（真机）
#   Phase 3  安装 + 启动（devicectl，卸载重装兜底）
#   Phase 4  设备验证（截图 + 黑屏体检 + syslog 关键日志 + 可选跨端 diff）
#   Phase 5  报告（report.md + 退出码）

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IOS_APP="$PROJECT_ROOT/iosApp"
WORKSPACE="$IOS_APP/PoLang.xcworkspace"
SCHEME=PoLang
BUNDLE_ID="com.mamba.picme"
DERIVED_DATA="$IOS_APP/build/derivedData"
APP_PATH="$DERIVED_DATA/Build/Products/Debug-iphoneos/PoLang.app"
VENV_PYMD3="$PROJECT_ROOT/tmp/venv_pmd3/bin"

cd "$PROJECT_ROOT"

# ── 参数 ───────────────────────────────────────────────────────────
NO_INSTALL=false
NO_TEST=false
QUICK_MODE=false
FAST_MODE=false
SKIP_PREP=false
RUN_DIFF=false
SHOW_HELP=false
DEVICE_ID=""
SHOT_NAME="polang-loop"

while [[ $# -gt 0 ]]; do
    case $1 in
        --no-install)  NO_INSTALL=true; shift ;;
        --no-test)     NO_TEST=true; shift ;;
        --quick)       QUICK_MODE=true; shift ;;
        --fast)        FAST_MODE=true; SKIP_PREP=true; shift ;;
        --skip-prep)   SKIP_PREP=true; shift ;;
        --diff)        RUN_DIFF=true; shift ;;
        --device)      DEVICE_ID="$2"; shift 2 ;;
        --screenshot)  SHOT_NAME="$2"; shift 2 ;;
        --help)        SHOW_HELP=true; shift ;;
        *) echo "未知参数：$1（--help 查看用法）"; exit 1 ;;
    esac
done

if $SHOW_HELP; then
    sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
fi

if $FAST_MODE; then QUICK_MODE=true; fi

# ── 颜色 & 计数 ─────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; NC='\033[0m'

PASS_COUNT=0; WARN_COUNT=0; FAIL_COUNT=0
TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/ios_$TIMESTAMP"
mkdir -p "$OUTPUT_DIR"

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_ok()   { echo -e "${GREEN}[PASS]${NC} $1"; PASS_COUNT=$((PASS_COUNT + 1)); }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; WARN_COUNT=$((WARN_COUNT + 1)); }
log_fail() { echo -e "${RED}[FAIL]${NC} $1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
print_section() {
    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# ── 工具：设备检测 / pymobiledevice3 检测 ───────────────────────────
detect_device() {
    if [ -n "$DEVICE_ID" ]; then echo "$DEVICE_ID"; return 0; fi
    if [ -n "${IOS_DEVICE_ID:-}" ]; then echo "$IOS_DEVICE_ID"; return 0; fi
    # devicectl 文本表只给 coredevice UUID(8-4-4-4-12)；硬件 UDID(8hex-16hex) 仅在 JSON 的
    # hardwareProperties.udid。pymobiledevice3 --udid 与 devicectl --device 都认硬件 UDID。
    local tmp_json; tmp_json=$(mktemp -t polang_devicectl)
    local udid=""
    if xcrun devicectl list devices --json-output "$tmp_json" >/dev/null 2>&1; then
        udid=$(python3 - "$tmp_json" <<'PY' 2>/dev/null
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(1)
for dev in d.get("result", {}).get("devices", []):
    udid = (dev.get("hardwareProperties") or {}).get("udid") or dev.get("identifier")
    if udid:
        print(udid); break
PY
        )
    fi
    rm -f "$tmp_json"
    [ -n "$udid" ] && { echo "$udid"; return 0; }
    return 1
}

detect_pymd3() {
    for cand in "$VENV_PYMD3/pymobiledevice3" "$(command -v pymobiledevice3 2>/dev/null)"; do
        [ -n "$cand" ] && [ -x "$cand" ] && { echo "$cand"; return 0; }
    done
    return 1
}

detect_py() {
    for cand in "$VENV_PYMD3/python3" "$(command -v python3 2>/dev/null)"; do
        [ -n "$cand" ] && [ -x "$cand" ] && { echo "$cand"; return 0; }
    done
    return 1
}

# ── 横幅 ───────────────────────────────────────────────────────────
echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     🤖 PoLang iOS Auto Dev Loop — 真机无人值守自循环      ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "项目路径：$PROJECT_ROOT"
echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
echo "输出目录：$OUTPUT_DIR"
echo ""

# 设备解析
if PYMD3=$(detect_pymd3); then
    log_info "pymobiledevice3: $PYMD3"
else
    log_warn "pymobiledevice3 未就绪（截图/syslog 阶段将降级跳过）"
fi
if PY3=$(detect_py); then log_info "python3: $PY3"; fi

if RESOLVED_DEVICE=$(detect_device); then
    DEVICE_ID="$RESOLVED_DEVICE"
    log_ok "目标设备：$DEVICE_ID"
else
    log_warn "未检测到物理真机（devicectl）—— 安装/验证阶段将跳过"
    NO_INSTALL=true; NO_TEST=true
fi

# ============================================
# Phase 1: 工程准备 + 单元测试
# ============================================
print_section "Phase 1/5: 工程准备 + 单元测试"

run_phase1() {
    local fail=0

    if $SKIP_PREP; then
        log_warn "跳过 xcodegen + pod install（--skip-prep / --fast）"
    else
        echo ""
        echo "→ xcodegen generate..."
        cd "$IOS_APP"
        if xcodegen generate > "$OUTPUT_DIR/xcodegen.log" 2>&1; then
            log_ok "xcodegen"
        else
            log_fail "xcodegen 失败（见 $OUTPUT_DIR/xcodegen.log）"
            cd "$PROJECT_ROOT"; return 1
        fi

        echo "→ pod install..."
        if pod install >> "$OUTPUT_DIR/pod_install.log" 2>&1; then
            log_ok "pod install"
        else
            log_warn "pod install 有警告/失败（见 $OUTPUT_DIR/pod_install.log，继续）"
        fi
        mkdir -p build && touch build/.shared-kit-hash
        cd "$PROJECT_ROOT"
    fi

    if $QUICK_MODE; then
        log_warn "--quick/--fast：跳过单元测试"
    else
        echo ""
        echo "→ shared:jvmTest..."
        if JITPACK=true ./gradlew :shared:jvmTest > "$OUTPUT_DIR/jvm_test.log" 2>&1; then
            local summary
            summary=$(grep -E "tests? completed|BUILD SUCCESSFUL" "$OUTPUT_DIR/jvm_test.log" | tail -1 || echo "完成")
            log_ok "shared:jvmTest — $summary"
        else
            log_fail "shared:jvmTest 失败（见 $OUTPUT_DIR/jvm_test.log）"
            fail=1
        fi

        if ! $NO_INSTALL; then
            echo "→ PoLangTests（iOS 真机）..."
            if xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
                -destination "id=$DEVICE_ID" -configuration Debug \
                -only-testing:PoLangTests \
                -derivedDataPath "$DERIVED_DATA" \
                -allowProvisioningUpdates CODE_SIGN_STYLE=Automatic \
                test > "$OUTPUT_DIR/ios_test.log" 2>&1; then
                log_ok "PoLangTests 通过"
            else
                log_fail "PoLangTests 失败（见 $OUTPUT_DIR/ios_test.log）"
                grep -E "failed|XCTAssert|error:" "$OUTPUT_DIR/ios_test.log" | head -5
                fail=1
            fi
        fi
    fi

    return $fail
}

# ============================================
# Phase 2: 编译
# ============================================
print_section "Phase 2/5: 编译 Debug（真机）"

run_phase2() {
    echo ""
    echo "→ xcodebuild build..."
    if xcodebuild -workspace "$WORKSPACE" -scheme "$SCHEME" \
        -destination "id=$DEVICE_ID" -configuration Debug \
        -derivedDataPath "$DERIVED_DATA" \
        -allowProvisioningUpdates CODE_SIGN_STYLE=Automatic \
        build > "$OUTPUT_DIR/build.log" 2>&1; then
        log_ok "Debug 编译成功"
        if [ -f "$APP_PATH" ]; then
            local size; size=$(du -h "$APP_PATH" | cut -f1)
            echo "   .app: $APP_PATH ($size)"
        fi
        return 0
    else
        log_fail "编译失败（见 $OUTPUT_DIR/build.log）"
        tail -8 "$OUTPUT_DIR/build.log" 2>/dev/null
        return 1
    fi
}

# ============================================
# Phase 3: 安装 + 启动
# ============================================
print_section "Phase 3/5: 安装 + 启动"

run_phase3() {
    if $NO_INSTALL; then
        log_warn "跳过安装 + 启动（--no-install 或无设备）"
        return 0
    fi

    echo ""
    echo "→ 安装 .app..."
    if xcrun devicectl device install app --device "$DEVICE_ID" "$APP_PATH" \
        > "$OUTPUT_DIR/install.log" 2>&1; then
        log_ok "安装成功"
    else
        echo "   卸载后重装..."
        xcrun devicectl device uninstall app --device "$DEVICE_ID" "$BUNDLE_ID" > /dev/null 2>&1 || true
        if xcrun devicectl device install app --device "$DEVICE_ID" "$APP_PATH" \
            > "$OUTPUT_DIR/install.log" 2>&1; then
            log_ok "卸载重装成功"
        else
            log_fail "安装失败（见 $OUTPUT_DIR/install.log）"
            return 1
        fi
    fi

    echo "→ 启动 App..."
    if xcrun devicectl device process launch --device "$DEVICE_ID" "$BUNDLE_ID" \
        > "$OUTPUT_DIR/launch.log" 2>&1; then
        log_ok "App 已启动"
    else
        log_warn "启动命令返回非零（见 $OUTPUT_DIR/launch.log，可能已在运行）"
    fi
    sleep 3
    return 0
}

# ============================================
# Phase 4: 设备验证（截图 + 黑屏体检 + 日志 + 可选 diff）
# ============================================
print_section "Phase 4/5: 设备验证"

run_phase4() {
    if $NO_TEST || $NO_INSTALL; then
        log_warn "跳过设备验证（--no-test 或无设备）"
        return 0
    fi

    local shot_ok=false
    local SHOT_PATH="$OUTPUT_DIR/${SHOT_NAME}.png"

    # 4.1 截图（pymobiledevice3 dvt screenshot）
    echo ""
    echo "→ 截图..."
    if [ -n "$PYMD3" ]; then
        if "$PYMD3" developer dvt screenshot "$SHOT_PATH" --udid "$DEVICE_ID" \
            > "$OUTPUT_DIR/screenshot.log" 2>&1; then
            if [ -f "$SHOT_PATH" ]; then
                log_ok "截图 → $SHOT_PATH"
                shot_ok=true
            else
                log_fail "截图文件未生成"
            fi
        else
            log_fail "截图失败（见 $OUTPUT_DIR/screenshot.log）"
        fi
    else
        log_warn "pymobiledevice3 不可用，跳过截图"
    fi

    # 4.2 黑屏 / 质量体检（PIL mean brightness）
    if $shot_ok && [ -n "$PY3" ]; then
        echo "→ 截图质量体检..."
        local brightness
        brightness=$("$PY3" - "$SHOT_PATH" <<'PY' 2>/dev/null
import sys
try:
    from PIL import Image
    im = Image.open(sys.argv[1]).convert("L")
    px = list(im.getdata())
    mean = sum(px) / len(px)
    print(f"{mean:.1f}")
except Exception:
    print("NA")
PY
        )
        if [ -n "$brightness" ] && [ "$brightness" != "NA" ]; then
            if awk -v b="$brightness" 'BEGIN{exit !(b < 8)}'; then
                log_fail "疑似黑屏（平均亮度 $brightness < 8）"
            else
                log_ok "截图非黑屏（平均亮度 $brightness）"
            fi
        else
            log_warn "亮度体检跳过（PIL 不可用）"
        fi
    fi

    # 4.3 关键日志（短期 syslog 捕获，检查崩溃/启动标记）
    if [ -n "$PYMD3" ]; then
        echo "→ 采集 syslog（~3s）..."
        "$PYMD3" syslog live > "$OUTPUT_DIR/syslog.log" 2>"$OUTPUT_DIR/syslog.err" &
        local sl_pid=$!
        sleep 3
        kill "$sl_pid" 2>/dev/null || true
        wait "$sl_pid" 2>/dev/null || true

        if grep -qiE "crashed|SIGABRT|SIGSEGV|panic" "$OUTPUT_DIR/syslog.log" 2>/dev/null; then
            log_fail "syslog 检测到崩溃信号（见 $OUTPUT_DIR/syslog.log）"
            grep -iE "crashed|SIGABRT|SIGSEGV" "$OUTPUT_DIR/syslog.log" | head -3
        else
            log_ok "syslog 未见崩溃信号"
        fi
        if grep -q "$BUNDLE_ID" "$OUTPUT_DIR/syslog.log" 2>/dev/null; then
            log_ok "syslog 出现 App 进程记录"
        else
            log_warn "syslog 未见 App 进程记录（可能过滤范围外）"
        fi
    fi

    # 4.4 跨端像素对比（可选）
    if $RUN_DIFF; then
        echo "→ Android ↔ iOS 跨端对比..."
        local driver="$PROJECT_ROOT/scripts/ios_ui_driver.py"
        if [ -f "$driver" ] && [ -d /tmp/android-ui-reference ]; then
            cp "$SHOT_PATH" "/tmp/ios-ui-reference/${SHOT_NAME}.png" 2>/dev/null || true
            mkdir -p /tmp/ios-ui-reference
            cp "$SHOT_PATH" "/tmp/ios-ui-reference/${SHOT_NAME}.png" 2>/dev/null || true
            if "$PY3" "$driver" diff --threshold 0.80 > "$OUTPUT_DIR/cross_diff.log" 2>&1; then
                log_ok "跨端对比完成（见 $OUTPUT_DIR/cross_diff.log）"
            else
                log_warn "跨端对比存在差异或失败（见 $OUTPUT_DIR/cross_diff.log）"
                tail -5 "$OUTPUT_DIR/cross_diff.log" 2>/dev/null
            fi
        else
            log_warn "跳过跨端对比（无 $driver 或 /tmp/android-ui-reference）"
        fi
    else
        log_info "跨端对比未启用（--diff）"
    fi

    return 0
}

# ============================================
# Phase 5: 报告
# ============================================
print_section "Phase 5/5: 报告"

run_phase5() {
    local report_file="$OUTPUT_DIR/report.md"
    cat > "$report_file" << EOF
# PoLang iOS Auto Dev Loop 报告

**时间**: $(date '+%Y-%m-%d %H:%M:%S')
**设备**: ${DEVICE_ID:-无}
**输出目录**: $OUTPUT_DIR
**模式**: ${QUICK_MODE:+快速}${FAST_MODE:+ [极速]}${NO_INSTALL:+ [无安装]}${NO_TEST:+ [无验证]}${RUN_DIFF:+ [跨端对比]}

## 结果汇总

| 状态 | 数量 |
|------|------|
| ✅ 通过 | $PASS_COUNT |
| ⚠️ 警告 | $WARN_COUNT |
| ❌ 失败 | $FAIL_COUNT |

## 输出文件

\`\`\`
$(ls -la "$OUTPUT_DIR" | tail -n +4)
\`\`\`

## 结论

EOF
    if [ $FAIL_COUNT -eq 0 ]; then
        echo -e "\n${GREEN}✅ iOS Auto Dev Loop 全部通过！${NC}" | tee -a "$report_file"
    else
        echo -e "\n${RED}❌ iOS Auto Dev Loop 存在 $FAIL_COUNT 项失败，请检查日志。${NC}" | tee -a "$report_file"
    fi

    echo ""
    echo -e "📄 完整报告：${CYAN}$report_file${NC}"
    echo -e "📁 输出目录：${CYAN}$OUTPUT_DIR${NC}"
}

# ── 主流程（阶段隔离：任一阶段失败不中断后续）──────────────────────
run_phase1 || true
run_phase2 || true
run_phase3 || true
run_phase4 || true
run_phase5

if [ $FAIL_COUNT -eq 0 ]; then exit 0; else exit 1; fi
