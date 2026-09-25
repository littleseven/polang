#!/usr/bin/env bash

# 通过 ADB 按秒采集应用资源变化：内存 / CPU / GPU
# 示例：
#   ./scripts/adb-resource-monitor.sh -p com.mamba.picme -i 1
#   ./scripts/adb-resource-monitor.sh -p com.mamba.picme -i 1 -d 120
#   ./scripts/adb-resource-monitor.sh -p com.mamba.picme -i 0.5 -d 60 -o /tmp/picme_monitor.csv

set -u -o pipefail

PACKAGE_NAME="com.mamba.picme"
INTERVAL_SEC="1"
DURATION_SEC="0"   # 0 表示持续运行，直到 Ctrl+C
OUTPUT_FILE=""

print_usage() {
  cat <<'EOF'
Usage:
  adb-resource-monitor.sh [options]

Options:
  -p, --package <name>     目标包名，默认: com.mamba.picme
  -i, --interval <sec>     采样间隔秒，支持小数，默认: 1
  -d, --duration <sec>     持续时长秒，默认: 0(无限)
  -o, --output <file>      输出 CSV 文件路径，默认自动生成
  -h, --help               查看帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -p|--package)
      PACKAGE_NAME="$2"
      shift 2
      ;;
    -i|--interval)
      INTERVAL_SEC="$2"
      shift 2
      ;;
    -d|--duration)
      DURATION_SEC="$2"
      shift 2
      ;;
    -o|--output)
      OUTPUT_FILE="$2"
      shift 2
      ;;
    -h|--help)
      print_usage
      exit 0
      ;;
    *)
      echo "未知参数: $1"
      print_usage
      exit 1
      ;;
  esac
done

if [[ -z "$OUTPUT_FILE" ]]; then
  ts="$(date +%Y%m%d_%H%M%S)"
  OUTPUT_FILE="$(cd "$(dirname "$0")" && pwd)/auto_test_output/adb_resource_${PACKAGE_NAME}_${ts}.csv"
fi

mkdir -p "$(dirname "$OUTPUT_FILE")"

if ! command -v adb >/dev/null 2>&1; then
  echo "❌ 未找到 adb，请先安装 Android platform-tools"
  exit 1
fi

if ! adb get-state >/dev/null 2>&1; then
  echo "❌ adb 设备未连接或未授权"
  exit 1
fi

adb_clean() {
  tr -d '\r'
}

get_pid() {
  adb shell "pidof -s $PACKAGE_NAME" 2>/dev/null | adb_clean | awk '{print $1}'
}

get_cpu_total_jiffies() {
  adb shell "cat /proc/stat | head -n 1" 2>/dev/null | adb_clean | awk '{sum=0; for(i=2;i<=NF;i++) sum+=$i; print sum}'
}

get_proc_jiffies() {
  local pid="$1"
  adb shell "cat /proc/$pid/stat" 2>/dev/null | adb_clean | awk '{print $14 + $15}'
}

get_rss_kb() {
  local pid="$1"
  adb shell "cat /proc/$pid/status | grep VmRSS" 2>/dev/null | adb_clean | awk '{print $2}'
}

get_cpu_core_count() {
  local c
  c=$(adb shell "cat /proc/stat | grep -c '^cpu[0-9]'" 2>/dev/null | adb_clean | awk '{print $1}')
  if [[ -z "$c" || "$c" == "0" ]]; then
    echo "1"
  else
    echo "$c"
  fi
}

# GPU 采集尽力而为：
# 1) Qualcomm: /sys/class/kgsl/kgsl-3d0/gpubusy (busy total)
# 2) Qualcomm: /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage
# 3) devfreq: */load 或 */utilization
get_gpu_snapshot() {
  adb shell '
if [ -r /sys/class/kgsl/kgsl-3d0/gpubusy ]; then
  echo "kgsl_gpubusy $(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)"
  exit 0
fi

if [ -r /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage ]; then
  echo "kgsl_percent $(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null)"
  exit 0
fi

if [ -r /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq ]; then
  cur=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null)
  max=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null)
  if [ -n "$cur" ] && [ -n "$max" ] && [ "$max" -gt 0 ] 2>/dev/null; then
    echo "kgsl_freq_ratio ${cur} ${max}"
    exit 0
  fi
fi

for f in /sys/class/devfreq/*gpu*/load /sys/class/devfreq/*mali*/load; do
  if [ -r "$f" ]; then
    echo "devfreq_load $(cat "$f" 2>/dev/null)"
    exit 0
  fi
done

for f in /sys/class/devfreq/*gpu*/utilization /sys/class/devfreq/*mali*/utilization; do
  if [ -r "$f" ]; then
    echo "devfreq_util $(cat "$f" 2>/dev/null)"
    exit 0
  fi
done

if command -v dumpsys >/dev/null 2>&1; then
  mem_line=$(dumpsys gpu 2>/dev/null | grep -m 1 "^Memory snapshot for GPU")
  global_line=$(dumpsys gpu 2>/dev/null | grep -m 1 "^Global total:")
  if [ -n "$global_line" ]; then
    global_val=$(echo "$global_line" | awk "{print \$3}")
    if [ -n "$global_val" ]; then
      echo "dumpsys_gpu_mem ${global_val}"
      exit 0
    fi
  fi
fi

echo "none NA"
' 2>/dev/null | adb_clean | head -n 1
}

format_or_na() {
  local v="$1"
  if [[ -z "$v" ]]; then
    echo "NA"
  else
    echo "$v"
  fi
}

echo "timestamp,pid,cpu_total_pct,cpu_single_core_pct,rss_mb,gpu_pct,gpu_source" > "$OUTPUT_FILE"

echo "🚀 开始监控: package=$PACKAGE_NAME interval=${INTERVAL_SEC}s duration=${DURATION_SEC}s"
echo "📄 CSV 输出: $OUTPUT_FILE"
echo
printf "%-19s %-8s %-10s %-12s %-10s %-10s %-14s\n" "time" "pid" "cpu%" "cpu%(1core)" "rss(MB)" "gpu%" "gpu_source"
printf "%-19s %-8s %-10s %-12s %-10s %-10s %-14s\n" "-------------------" "--------" "----------" "------------" "----------" "----------" "--------------"

cpu_cores="$(get_cpu_core_count)"

last_pid=""
last_proc_jiffies=""
last_total_jiffies=""

last_gpu_mode=""
last_gpu_a=""
last_gpu_b=""

start_ts="$(date +%s)"

trap 'echo; echo "🛑 监控结束"; exit 0' INT TERM

while true; do
  now_epoch="$(date +%s)"
  if [[ "$DURATION_SEC" != "0" ]]; then
    elapsed=$(( now_epoch - start_ts ))
    if (( elapsed >= DURATION_SEC )); then
      echo
      echo "✅ 已达到设定时长 ${DURATION_SEC}s，监控结束"
      break
    fi
  fi

  ts_human="$(date '+%Y-%m-%d %H:%M:%S')"
  pid="$(get_pid)"

  cpu_total_pct="NA"
  cpu_single_pct="NA"
  rss_mb="NA"
  gpu_pct="NA"
  gpu_source="none"

  if [[ -n "$pid" ]]; then
    rss_kb="$(get_rss_kb "$pid")"
    if [[ -n "$rss_kb" ]]; then
      rss_mb="$(awk -v v="$rss_kb" 'BEGIN{printf "%.2f", v/1024}')"
    fi

    proc_jiffies="$(get_proc_jiffies "$pid")"
    total_jiffies="$(get_cpu_total_jiffies)"

    if [[ -n "$proc_jiffies" && -n "$total_jiffies" && "$pid" == "$last_pid" && -n "$last_proc_jiffies" && -n "$last_total_jiffies" ]]; then
      cpu_total_pct="$(awk -v p_now="$proc_jiffies" -v p_prev="$last_proc_jiffies" -v t_now="$total_jiffies" -v t_prev="$last_total_jiffies" 'BEGIN{
        dp=p_now-p_prev; dt=t_now-t_prev;
        if (dt<=0 || dp<0) {print "NA"} else {printf "%.2f", (dp/dt)*100}
      }')"
      if [[ "$cpu_total_pct" != "NA" ]]; then
        cpu_single_pct="$(awk -v c="$cpu_total_pct" -v cores="$cpu_cores" 'BEGIN{printf "%.2f", c*cores}')"
      fi
    fi

    last_pid="$pid"
    last_proc_jiffies="$proc_jiffies"
    last_total_jiffies="$total_jiffies"

    gpu_line="$(get_gpu_snapshot)"
    gpu_mode="$(echo "$gpu_line" | awk '{print $1}')"
    gpu_rest="$(echo "$gpu_line" | cut -d' ' -f2-)"

    case "$gpu_mode" in
      kgsl_gpubusy)
        gpu_source="kgsl_gpubusy"
        gpu_a="$(echo "$gpu_rest" | awk '{print $1}')"
        gpu_b="$(echo "$gpu_rest" | awk '{print $2}')"
        if [[ "$last_gpu_mode" == "kgsl_gpubusy" && -n "$last_gpu_a" && -n "$last_gpu_b" && -n "$gpu_a" && -n "$gpu_b" ]]; then
          gpu_pct="$(awk -v a_now="$gpu_a" -v b_now="$gpu_b" -v a_prev="$last_gpu_a" -v b_prev="$last_gpu_b" 'BEGIN{
            da=a_now-a_prev; db=b_now-b_prev;
            if (db<=0 || da<0) {print "NA"} else {printf "%.2f", (da/db)*100}
          }')"
        fi
        last_gpu_mode="$gpu_mode"
        last_gpu_a="$gpu_a"
        last_gpu_b="$gpu_b"
        ;;
      kgsl_percent)
        gpu_source="kgsl_percent"
        gpu_pct="$(echo "$gpu_rest" | awk '{print $1}')"
        last_gpu_mode="$gpu_mode"
        last_gpu_a=""
        last_gpu_b=""
        ;;
      kgsl_freq_ratio)
        gpu_source="kgsl_freq_ratio"
        cur_freq="$(echo "$gpu_rest" | awk '{print $1}')"
        max_freq="$(echo "$gpu_rest" | awk '{print $2}')"
        if [[ -n "$cur_freq" && -n "$max_freq" && "$max_freq" != "0" ]]; then
          gpu_pct="$(awk -v cur="$cur_freq" -v max="$max_freq" 'BEGIN{printf "%.2f", (cur/max)*100}')"
        else
          gpu_pct="NA"
        fi
        last_gpu_mode="$gpu_mode"
        last_gpu_a=""
        last_gpu_b=""
        ;;
      devfreq_load|devfreq_util)
        gpu_source="$gpu_mode"
        gpu_pct="$(echo "$gpu_rest" | awk '{print $1}')"
        last_gpu_mode="$gpu_mode"
        last_gpu_a=""
        last_gpu_b=""
        ;;
      dumpsys_gpu_mem)
        gpu_source="dumpsys_gpu_mem"
        gpu_pct="NA"
        last_gpu_mode="$gpu_mode"
        last_gpu_a=""
        last_gpu_b=""
        ;;
      *)
        gpu_source="none"
        gpu_pct="NA"
        last_gpu_mode=""
        last_gpu_a=""
        last_gpu_b=""
        ;;
    esac
  else
    last_pid=""
    last_proc_jiffies=""
    last_total_jiffies=""
    last_gpu_mode=""
    last_gpu_a=""
    last_gpu_b=""
  fi

  pid_out="$(format_or_na "$pid")"
  cpu_total_out="$(format_or_na "$cpu_total_pct")"
  cpu_single_out="$(format_or_na "$cpu_single_pct")"
  rss_out="$(format_or_na "$rss_mb")"
  gpu_out="$(format_or_na "$gpu_pct")"

  printf "%-19s %-8s %-10s %-12s %-10s %-10s %-14s\n" "$ts_human" "$pid_out" "$cpu_total_out" "$cpu_single_out" "$rss_out" "$gpu_out" "$gpu_source"
  echo "$ts_human,$pid_out,$cpu_total_out,$cpu_single_out,$rss_out,$gpu_out,$gpu_source" >> "$OUTPUT_FILE"

  sleep "$INTERVAL_SEC"
done

echo "📄 监控数据已保存: $OUTPUT_FILE"

