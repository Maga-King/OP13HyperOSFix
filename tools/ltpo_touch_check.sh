#!/system/bin/sh

TOUCH_TOOL=/odm/bin/touchHidlTest
TOUCH_NODE=/proc/touchpanel/report_rate
MIN_NODE=/sys/kernel/oplus_display/min_fps
ADFR_NODE=/sys/kernel/oplus_display/adfr_config
TE_NODE=/sys/kernel/oplus_display/test_te
DUMP_NODE=/sys/kernel/oplus_display/dump_info
SETTING=op13_fix_touch_sampling_rate

if [ "$(id -u 2>/dev/null)" != 0 ]; then
    echo "正在请求 ROOT 权限..."
    exec su -c "/system/bin/sh '$0'"
fi

echo "=== 一加 13 LTPO 与触控采样率诊断 ==="
echo "内核: $(uname -r)"
if grep -q '^op13_ltpo_restore ' /proc/modules 2>/dev/null; then
    echo "[OK] op13_ltpo_restore 已加载"
else
    echo "[!!] op13_ltpo_restore 未加载"
fi

echo
echo "--- 触控采样率 ---"
selected=$(settings get global "$SETTING" 2>/dev/null)
case "$selected" in
    70|120|180|240|360) ;;
    *) selected=120; echo "模块设置: 未写入，按默认值 120 Hz" ;;
esac
case "$selected" in
    180|240|360)
        if dumpsys power 2>/dev/null | grep -q 'mWakefulness=Awake'; then
            expected=$selected
        else
            expected=70
        fi
        ;;
    *) expected=$selected ;;
esac
echo "模块目标: ${selected} Hz"
echo "当前状态目标: ${expected} Hz"

if [ ! -x "$TOUCH_TOOL" ]; then
    echo "[!!] 缺少 $TOUCH_TOOL"
else
    "$TOUCH_TOOL" -c ao 0 182 >/dev/null 2>&1
    support_code=$?
    raw=$($TOUCH_TOOL -c ro 0 182 2>&1)
    read_code=$?
    if [ "$support_code" -eq 0 ]; then
        echo "[OK] HIDL report_rate(182) 可用"
    else
        echo "[!!] HIDL 能力查询失败，退出码 $support_code"
    fi
    echo "HIDL 原始读数: ${raw:-<空>} (退出码 $read_code)"
fi
if [ -r "$TOUCH_NODE" ]; then
    echo "proc 原始读数: $(cat "$TOUCH_NODE" 2>/dev/null)"
else
    echo "proc 原始读数: 节点不可读"
fi
echo "说明: 原始读数是驱动档位，不等同于 Hz；模块目标值才是写入 182 的 Hz 参数。"

echo
echo "--- LTPO 实际 TE ---"
for node in "$MIN_NODE" "$ADFR_NODE" "$TE_NODE"; do
    if [ ! -e "$node" ]; then
        echo "[!!] 缺少节点 $node"
        exit 1
    fi
done
echo "min_fps: $(cat "$MIN_NODE" 2>/dev/null)"
echo "adfr_config: $(cat "$ADFR_NODE" 2>/dev/null)"
if [ -r "$DUMP_NODE" ]; then
    cat "$DUMP_NODE" 2>/dev/null
fi

old_te=$(cat "$TE_NODE" 2>/dev/null)
restore_te() {
    case "$old_te" in
        ''|*[!0-9]*) echo 0 > "$TE_NODE" 2>/dev/null ;;
        *) echo "$old_te" > "$TE_NODE" 2>/dev/null ;;
    esac
}
trap restore_te EXIT INT TERM

echo 1 > "$TE_NODE" 2>/dev/null || {
    echo "[!!] 无法启用 test_te"
    exit 1
}
echo "请保持画面静止，3 秒后开始读取 8 次..."
sleep 3

min=999
max=0
valid=0
i=1
while [ "$i" -le 8 ]; do
    value=$(cat "$TE_NODE" 2>/dev/null)
    case "$value" in
        ''|*[!0-9]*) echo "[$i/8] TE: 无效读数 ($value)" ;;
        *)
            echo "[$i/8] TE: ${value} Hz"
            valid=$((valid + 1))
            [ "$value" -lt "$min" ] && min=$value
            [ "$value" -gt "$max" ] && max=$value
            ;;
    esac
    i=$((i + 1))
    sleep 1
done

restore_te
trap - EXIT INT TERM
echo
if [ "$valid" -eq 0 ]; then
    echo "[!!] 没有取得有效 TE 数据"
    exit 2
fi
echo "TE 范围: ${min}-${max} Hz"
if [ "$min" -le 10 ]; then
    echo "[OK] LTPO 已进入低频，最低 ${min} Hz"
elif [ "$min" -lt 120 ]; then
    echo "[OK] LTPO 已降到 120 Hz 以下，最低 ${min} Hz"
else
    echo "[??] 本次没有观察到降频；关闭动画和悬浮层后再测"
fi
