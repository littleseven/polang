#!/bin/bash
# MNN 模型卸载自动化测试脚本
#
# 用法:
#   chmod +x scripts/test-mnn-unload.sh
#   ./scripts/test-mnn-unload.sh
#
# 前置条件:
#   - Android 设备已连接
#   - PoLang 调试版已安装
#   - VLM 打标模型和 ASR 模型已下载

set -e

PACKAGE="com.mamba.picme"
LOG_DIR="/tmp"
LOG_FILE="$LOG_DIR/mnn_unload_test_$(date +%Y%m%d_%H%M%S).log"
TAGS="MnnResourceManager:LocalLlmEngine:SherpaOnnxAsr:VoiceCommand"

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✓${NC} $1"; }
fail() { echo -e "${RED}✗${NC} $1"; }
info() { echo -e "${YELLOW}→${NC} $1"; }

check_device() {
    info "Checking device connection..."
    if ! adb devices | grep -q "device$"; then
        fail "No Android device connected"
        exit 1
    fi
    pass "Device connected"
}

check_app() {
    info "Checking if $PACKAGE is installed..."
    if ! adb shell pm list packages | grep -q "$PACKAGE"; then
        fail "Package $PACKAGE not found"
        exit 1
    fi
    pass "Package $PACKAGE installed"
}

start_logcat() {
    adb logcat -c
    adb logcat -s "$TAGS" -v time > "$LOG_FILE" &
    echo $!
}

stop_logcat() {
    local pid=$1
    kill $pid 2>/dev/null || true
    sleep 1
}

wait_for_log() {
    local pattern=$1
    local timeout=${2:-30}
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        if grep -q "$pattern" "$LOG_FILE" 2>/dev/null; then
            return 0
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

tc_001_background_unload() {
    info "=== TC-001: Background Auto Unload ==="
    local log_pid
    log_pid=$(start_logcat)

    info "Step 1: Launch app"
    adb shell am start -n ${PACKAGE}/.MainActivity >/dev/null 2>&1
    sleep 3

    info "Step 2: Simulate voice interaction"
    # 尝试通过广播触发 LLM 加载（如果有调试入口）
    # 否则依赖用户手动说一句话
    adb shell input tap 540 1800 >/dev/null 2>&1 || true
    sleep 2

    info "Step 3: Press Home"
    adb shell input keyevent KEYCODE_HOME >/dev/null 2>&1
    sleep 2

    info "Step 4: Wait 35s for softTrim"
    sleep 35

    info "Step 5: Wait 30s more for safeUnload"
    sleep 30

    info "Step 6: Verify logs"
    stop_logcat $log_pid

    local has_softtrim=false
    local has_safeunload=false

    if grep -q "triggering soft trim" "$LOG_FILE"; then
        has_softtrim=true
        pass "softTrim triggered"
    else
        fail "softTrim not triggered"
    fi

    if grep -q "LLM fully unloaded" "$LOG_FILE" && grep -q "ASR fully unloaded" "$LOG_FILE"; then
        has_safeunload=true
        pass "safeUnload triggered, both models released"
    else
        fail "safeUnload not triggered or models not released"
        echo "--- Relevant logs ---"
        grep -E "unloaded|trimmed|soft released|safe unload" "$LOG_FILE" | tail -15 || true
    fi

    if [ "$has_softtrim" = true ] && [ "$has_safeunload" = true ]; then
        pass "TC-001 PASSED"
        return 0
    else
        fail "TC-001 FAILED"
        return 1
    fi
}

tc_003_memory_pressure() {
    info "=== TC-003: Memory Pressure Emergency Unload ==="
    local log_pid
    log_pid=$(start_logcat)

    info "Step 1: Launch app"
    adb shell am start -n ${PACKAGE}/.MainActivity >/dev/null 2>&1
    sleep 3

    info "Step 2: Simulate voice interaction"
    adb shell input tap 540 1800 >/dev/null 2>&1 || true
    sleep 2

    info "Step 3: Send RUNNING_CRITICAL"
    adb shell am send-trim-memory ${PACKAGE} RUNNING_CRITICAL >/dev/null 2>&1
    sleep 3

    info "Step 4: Verify logs"
    stop_logcat $log_pid

    local has_pressure=false
    local has_unload=false

    if grep -q "Memory pressure:.*force unload" "$LOG_FILE"; then
        has_pressure=true
        pass "Memory pressure handled"
    else
        fail "Memory pressure not handled"
    fi

    if grep -q "LLM fully unloaded" "$LOG_FILE"; then
        has_unload=true
        pass "LLM unloaded on pressure"
    else
        fail "LLM not unloaded on pressure"
    fi

    if [ "$has_pressure" = true ] && [ "$has_unload" = true ]; then
        pass "TC-003 PASSED"
        return 0
    else
        fail "TC-003 FAILED"
        echo "--- Relevant logs ---"
        grep -E "Memory pressure|force unload|fully unloaded" "$LOG_FILE" | tail -15 || true
        return 1
    fi
}

tc_004_ref_count() {
    info "=== TC-004: Reference Count Correctness ==="
    local log_pid
    log_pid=$(start_logcat)

    info "Step 1: Open camera (ASR acquire)"
    adb shell am start -n ${PACKAGE}/.MainActivity >/dev/null 2>&1
    sleep 3

    info "Step 2: Trigger voice (LLM acquire)"
    adb shell input tap 540 1800 >/dev/null 2>&1 || true
    sleep 2

    info "Step 3: Navigate to gallery (ASR release)"
    adb shell input tap 540 2000 >/dev/null 2>&1 || true
    sleep 2

    info "Step 4: Navigate back to camera (ASR re-acquire)"
    adb shell input tap 100 2000 >/dev/null 2>&1 || true
    sleep 2

    info "Step 5: Verify refCount logs"
    stop_logcat $log_pid

    local llm_acquires
    local llm_releases
    local asr_acquires
    local asr_releases

    llm_acquires=$(grep -c "LLM acquired" "$LOG_FILE" || echo 0)
    llm_releases=$(grep -c "LLM released" "$LOG_FILE" || echo 0)
    asr_acquires=$(grep -c "ASR acquired" "$LOG_FILE" || echo 0)
    asr_releases=$(grep -c "ASR released" "$LOG_FILE" || echo 0)

    info "LLM acquires: $llm_acquires, releases: $llm_releases"
    info "ASR acquires: $asr_acquires, releases: $asr_releases"

    if [ "$llm_acquires" -eq "$llm_releases" ] && [ "$asr_acquires" -eq "$asr_releases" ]; then
        pass "Ref counts balanced"
        pass "TC-004 PASSED"
        return 0
    else
        fail "Ref counts unbalanced"
        fail "TC-004 FAILED"
        return 1
    fi
}

main() {
    echo "========================================"
    echo "  MNN Resource Manager Unload Test"
    echo "========================================"
    echo "Log file: $LOG_FILE"
    echo ""

    check_device
    check_app

    local failed=0

    tc_001_background_unload || failed=$((failed + 1))

    echo ""
    info "Waiting 5s before next test..."
    sleep 5

    tc_003_memory_pressure || failed=$((failed + 1))

    echo ""
    info "Waiting 5s before next test..."
    sleep 5

    tc_004_ref_count || failed=$((failed + 1))

    echo ""
    echo "========================================"
    if [ $failed -eq 0 ]; then
        pass "All tests PASSED"
    else
        fail "$failed test(s) FAILED"
    fi
    echo "Full log: $LOG_FILE"
    echo "========================================"

    return $failed
}

main "$@"
