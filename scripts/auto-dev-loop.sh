#!/bin/bash
#
# Auto Dev Loop - PoLang 开发自循环脚本（Tool 化版本）
# 用途：代码修改后一键完成 编译→安装→设备验证→质量检查→报告 完整闭环
# 调用：./scripts/auto-dev-loop.sh [options]
#
# Options:
#   --no-install      仅编译和代码检查，跳过设备安装
#   --no-test         跳过设备端验证
#   --quick           快速模式：仅编译 + 安装 + UI dump（跳过详细验证）
#   --fast            极速模式：跳过 lint、unit test、instrumented test，仅编译+安装+UI dump
#   --test-suite      指定测试套件：all (默认), beauty, gallery（已弃用，使用 --instrumented）
#   --instrumented    运行 Instrumented Tests（默认跳过）
#   --with-lint       运行 ktlint + detekt（默认跳过以节省时间）
#   --help            显示帮助信息
#

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# 参数解析
NO_INSTALL=false
NO_TEST=false
QUICK_MODE=false
FAST_MODE=false
TEST_SUITE="all"
INSTRUMENTED=false
SHOW_HELP=false

RUN_LINT=false

while [[ $# -gt 0 ]]; do
    case $1 in
        --no-install) NO_INSTALL=true; shift ;;
        --no-test) NO_TEST=true; shift ;;
        --quick) QUICK_MODE=true; shift ;;
        --fast) FAST_MODE=true; shift ;;
        --test-suite) TEST_SUITE="$2"; shift 2 ;;
        --instrumented) INSTRUMENTED=true; shift ;;
        --with-lint) RUN_LINT=true; shift ;;
        --help) SHOW_HELP=true; shift ;;
        *) echo "未知参数：$1"; exit 1 ;;
    esac
done

if [ "$FAST_MODE" = true ]; then
    RUN_LINT=false
    INSTRUMENTED=false
fi

if [ "$SHOW_HELP" = true ]; then
    echo "用法：$0 [选项]"
    echo ""
    echo "选项:"
    echo "  --no-install      仅编译和代码检查，跳过设备安装"
    echo "  --no-test         跳过设备端验证"
    echo "  --quick           快速模式：仅编译 + 安装 + UI dump（跳过详细验证）"
    echo "  --fast            极速模式：跳过 lint、unit test、instrumented test，仅编译+安装+UI dump"
    echo "  --test-suite      已弃用，使用 --instrumented 运行 Instrumented Tests"
    echo "  --instrumented    运行 Instrumented Tests（默认跳过）"
    echo "  --with-lint       运行 ktlint + detekt（默认跳过以节省时间）"
    echo "  --help            显示帮助信息"
    echo ""
    echo "工作流:"
    echo "  1. 代码质量检查（ktlint + detekt，可选；--fast 跳过）"
    echo "  2. 编译 Debug APK"
    echo "  3. 安装到设备"
    echo "  4. 设备端验证（accessibility UI dump + 日志 + 广播命令测试）"
    echo "  5. 生成报告"
    exit 0
fi

# 时间戳
TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
OUTPUT_DIR="$PROJECT_ROOT/scripts/auto_test_output/$TIMESTAMP"
mkdir -p "$OUTPUT_DIR"

# 汇总状态
PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_ok() {
    echo -e "${GREEN}[PASS]${NC} $1"
    PASS_COUNT=$((PASS_COUNT + 1))
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
    WARN_COUNT=$((WARN_COUNT + 1))
}

log_fail() {
    echo -e "${RED}[FAIL]${NC} $1"
    FAIL_COUNT=$((FAIL_COUNT + 1))
}

print_section() {
    echo ""
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${CYAN}  $1${NC}"
    echo -e "${CYAN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# ============================================
# Phase 1: 代码质量检查
# ============================================
print_section "Phase 1/4: 代码质量检查"

run_phase1() {
    local fail=0

    if [ "$RUN_LINT" = true ]; then
        # ktlint
        echo ""
        echo "→ Ktlint 格式检查..."
        if ./gradlew ktlintCheck --quiet > "$OUTPUT_DIR/ktlint.log" 2>&1; then
            log_ok "Ktlint 格式检查通过"
        else
            log_warn "Ktlint 格式检查失败 (日志：$OUTPUT_DIR/ktlint.log)"
        fi

        # detekt
        echo ""
        echo "→ Detekt 静态分析..."
        if ./gradlew detekt > "$OUTPUT_DIR/detekt.log" 2>&1; then
            log_ok "Detekt 静态分析通过"
        else
            log_warn "Detekt 静态分析失败 (日志：$OUTPUT_DIR/detekt.log)"
        fi
    else
        log_warn "跳过 lint 检查（使用 --with-lint 启用）"
    fi

    # 单元测试
    if [ "$FAST_MODE" = true ]; then
        log_warn "--fast 模式：跳过 JVM 单元测试"
    else
        echo ""
        echo "→ JVM 单元测试..."
        if ./gradlew testDebugUnitTest > "$OUTPUT_DIR/unit_test.log" 2>&1; then
            local test_summary=$(grep -E "tests? completed" "$OUTPUT_DIR/unit_test.log" | tail -1 || echo "测试完成")
            log_ok "JVM 单元测试通过 — $test_summary"
        else
            log_fail "JVM 单元测试失败 (日志：$OUTPUT_DIR/unit_test.log)"
            fail=1
        fi
    fi

    return $fail
}

# ============================================
# Phase 2: 编译
# ============================================
print_section "Phase 2/4: 编译 Debug APK"

run_phase2() {
    echo ""
    echo "→ 编译 :androidApp:assembleDebug..."
    if ./gradlew :androidApp:assembleDebug > "$OUTPUT_DIR/build.log" 2>&1; then
        log_ok "Debug APK 编译成功"
        local apk=$(find androidApp/build/outputs/apk/debug -name "*.apk" | head -1)
        local apk_size=$(du -h "$apk" | cut -f1)
        echo "   APK: $apk ($apk_size)"
        return 0
    else
        log_fail "编译失败 (日志：$OUTPUT_DIR/build.log)"
        return 1
    fi
}

# ============================================
# Phase 3: 设备安装与启动
# ============================================
print_section "Phase 3/4: 设备安装与启动"

run_phase3() {
    if [ "$NO_INSTALL" = true ]; then
        log_warn "跳过设备安装 (--no-install)"
        return 0
    fi

    echo ""
    echo "→ 检查设备连接..."
    if ! adb devices | grep -q "device$"; then
        log_warn "未检测到连接的设备，跳过设备端验证"
        NO_TEST=true
        return 0
    fi

    local device_count=$(adb devices | grep -c "device$")
    log_ok "检测到 $device_count 台设备"

    echo ""
    echo "→ 安装 APK..."
    local apk=$(find androidApp/build/outputs/apk/debug -name "*.apk" | head -1)
    if adb install -r "$apk" > "$OUTPUT_DIR/install.log" 2>&1; then
        log_ok "APK 安装成功"
    else
        echo "   尝试卸载后重装..."
        adb uninstall com.mamba.picme > /dev/null 2>&1 || true
        if adb install "$apk" > "$OUTPUT_DIR/install.log" 2>&1; then
            log_ok "APK 卸载重装成功"
        else
            log_fail "APK 安装失败 (日志：$OUTPUT_DIR/install.log)"
            return 1
        fi
    fi

    echo ""
    echo "→ 启动应用..."
    adb shell am start -n com.mamba.picme/.ui.CameraScreen > /dev/null 2>&1 || \
    adb shell am start -n com.mamba.picme/.MainActivity > /dev/null 2>&1
    sleep 3

    if adb shell ps | grep -q "com.mamba.picme"; then
        log_ok "应用已启动并在前台运行"
    else
        log_warn "应用启动状态不确定，继续后续验证"
    fi

    return 0
}

# ============================================
# 工具函数：启用并连接 Accessibility Service
# ============================================
setup_accessibility_service() {
    echo ""
    echo "→ 启用 PoLangAccessibilityService..."
    adb shell settings put secure enabled_accessibility_services com.mamba.picme/.accessibility.PoLangAccessibilityService > /dev/null 2>&1 || true
    adb shell settings put secure accessibility_enabled 1 > /dev/null 2>&1 || true

    echo "→ 设置 adb forward tcp:27183..."
    adb forward tcp:27183 tcp:27183 > /dev/null 2>&1 || true

    echo "→ 等待 Accessibility Service 就绪..."
    local attempts=0
    while [ $attempts -lt 15 ]; do
        if python3 "$PROJECT_ROOT/scripts/ui_driver.py" dump --package com.mamba.picme > /dev/null 2>&1; then
            log_ok "Accessibility Service 已就绪"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep 1
    done
    log_warn "Accessibility Service 未在 15s 内就绪，继续后续验证"
    return 1
}

# ============================================
# Phase 4: 设备端验证（Tool 化测试）
# ============================================
print_section "Phase 4/4: 设备端验证（Tool 化测试）"

run_phase4() {
    if [ "$NO_TEST" = true ]; then
        log_warn "跳过设备端测试 (--no-test 或未检测到设备)"
        return 0
    fi

    # 清除日志
    adb logcat -c > /dev/null 2>&1 || true

    # 启用并连接 accessibility service
    setup_accessibility_service

    if [ "$QUICK_MODE" = true ] || [ "$FAST_MODE" = true ]; then
        log_warn "快速/极速模式：仅执行 UI dump 确认应用正常显示"
        echo "→ 收集启动后 UI 结构..."
        sleep 2
        if python3 "$PROJECT_ROOT/scripts/ui_driver.py" dump --package com.mamba.picme > "$OUTPUT_DIR/ui_dump_startup.txt" 2>"$OUTPUT_DIR/ui_dump_startup.err"; then
            if grep -q "com.mamba.picme" "$OUTPUT_DIR/ui_dump_startup.txt" 2>/dev/null; then
                log_ok "检测到 PoLang 界面节点"
            else
                log_warn "未检测到 PoLang 界面节点"
            fi
        else
            log_warn "UI dump 失败"
        fi
        return 0
    fi

    # 4.1 收集启动后 UI 结构（结构化文本，替代截图）
    echo ""
    echo "→ 收集启动后 UI 结构 (accessibility dump)..."
    sleep 2
    if python3 "$PROJECT_ROOT/scripts/ui_driver.py" dump --package com.mamba.picme > "$OUTPUT_DIR/ui_dump_startup.txt" 2>"$OUTPUT_DIR/ui_dump_startup.err"; then
        log_ok "启动后 UI dump 已保存：$OUTPUT_DIR/ui_dump_startup.txt"
        if grep -q "com.mamba.picme" "$OUTPUT_DIR/ui_dump_startup.txt" 2>/dev/null; then
            log_ok "检测到 PoLang 界面节点"
        else
            log_warn "未检测到 PoLang 界面节点"
        fi
    else
        log_warn "启动后 UI dump 失败 (错误日志：$OUTPUT_DIR/ui_dump_startup.err)"
    fi

    # 4.2 收集日志
    echo ""
    echo "→ 收集 PoLang 日志..."
    adb logcat -d -s "PoLang:*" > "$OUTPUT_DIR/logcat_picme.txt" 2>&1 || \
    adb logcat -d > "$OUTPUT_DIR/logcat_full.txt" 2>&1
    log_ok "日志已保存：$OUTPUT_DIR/logcat_picme.txt"

    # 4.3 检查关键日志
    echo ""
    echo "→ 检查关键日志..."
    if grep -q "EGLContext created" "$OUTPUT_DIR/logcat_picme.txt" 2>/dev/null; then
        log_ok "EGL 上下文创建成功"
    else
        log_warn "未检测到 EGL 上下文创建日志"
    fi

    if grep -q "BeautyEngine.*initialized" "$OUTPUT_DIR/logcat_picme.txt" 2>/dev/null; then
        log_ok "BeautyEngine 初始化成功"
    else
        log_warn "未检测到 BeautyEngine 初始化日志"
    fi

    # 4.4 Instrumented Tests（仅 --instrumented 时运行）
    # （历史 4.4「JSON 命令测试」已随 ADR-011 退役——AgentTestBroadcastReceiver 已删除，
    #   UI 自动化验证由上方 4.1 的 ui-driver dump 承担）
    if [ "$INSTRUMENTED" = true ]; then
        echo ""
        echo "→ 运行 Instrumented Tests..."

        local test_filter=""
        case "$TEST_SUITE" in
            "beauty")
                test_filter="com.mamba.picme.tools.BeautyEngineAutomationTest"
                ;;
            "gallery")
                test_filter="com.mamba.picme.features.gallery.*Test"
                ;;
            *)
                test_filter="com.mamba.picme.*Test"
                ;;
        esac

        echo "   测试套件：$TEST_SUITE (filter: $test_filter)"

        if ./gradlew connectedDebugAndroidTest --tests "$test_filter" > "$OUTPUT_DIR/instrumented_test.log" 2>&1; then
            log_ok "Instrumented Tests 通过"
        else
            log_warn "Instrumented Tests 存在失败或跳过 (日志：$OUTPUT_DIR/instrumented_test.log)"
        fi
    else
        log_warn "跳过 Instrumented Tests（使用 --instrumented 启用）"
    fi

    return 0
}

# ============================================
# Phase 5: 报告生成
# ============================================
print_section "Phase 5/5: 生成报告"

run_phase5() {
    local report_file="$OUTPUT_DIR/report.md"

    cat > "$report_file" << EOF
# PoLang Auto Dev Loop 报告

**时间**: $(date '+%Y-%m-%d %H:%M:%S')  
**输出目录**: $OUTPUT_DIR  
**模式**: ${QUICK_MODE:+快速模式}${NO_INSTALL:+ [无安装]}${NO_TEST:+ [无设备测试]}${TEST_SUITE:+ [测试套件：$TEST_SUITE]}

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

## 日志文件

EOF

    for log in "$OUTPUT_DIR"/*.log "$OUTPUT_DIR"/*.txt; do
        [ -f "$log" ] || continue
        local name=$(basename "$log")
        local size=$(du -h "$log" | cut -f1)
        echo "- **$name** ($size)" >> "$report_file"
    done

    echo "" >> "$report_file"
    echo "## 结论" >> "$report_file"
    if [ $FAIL_COUNT -eq 0 ]; then
        echo -e "\n${GREEN}✅ Auto Dev Loop 全部通过！${NC}" >> "$report_file"
        echo -e "\n${GREEN}✅ Auto Dev Loop 全部通过！${NC}"
    else
        echo -e "\n${RED}❌ Auto Dev Loop 存在 $FAIL_COUNT 项失败，请检查日志。${NC}" >> "$report_file"
        echo -e "\n${RED}❌ Auto Dev Loop 存在 $FAIL_COUNT 项失败，请检查日志。${NC}"
    fi

    echo ""
    echo -e "📄 完整报告：${CYAN}$report_file${NC}"
    echo -e "📁 输出目录：${CYAN}$OUTPUT_DIR${NC}"
}

# ============================================
# 主流程
# ============================================
echo ""
echo -e "${CYAN}╔════════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║     🤖 PoLang Auto Dev Loop - 开发自循环                  ║${NC}"
echo -e "${CYAN}║     Tool 化测试版本（基于 Capability 接口）              ║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo "项目路径：$PROJECT_ROOT"
echo "时间：$(date '+%Y-%m-%d %H:%M:%S')"
echo "输出目录：$OUTPUT_DIR"
echo ""

# 执行各阶段
run_phase1 || true
run_phase2 || true
run_phase3 || true
run_phase4 || true
run_phase5

# 退出码
if [ $FAIL_COUNT -eq 0 ]; then
    exit 0
else
    exit 1
fi
