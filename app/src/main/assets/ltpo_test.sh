#!/system/bin/sh

MIN_NODE=/sys/kernel/oplus_display/min_fps
ADFR_NODE=/sys/kernel/oplus_display/adfr_config
TE_NODE=/sys/kernel/oplus_display/test_te
DUMP_NODE=/sys/kernel/oplus_display/dump_info

for node in "$MIN_NODE" "$ADFR_NODE" "$TE_NODE"; do
    if [ ! -e "$node" ]; then
        echo "错误：缺少节点 $node"
        exit 1
    fi
done

cleanup() {
    echo 0 > "$TE_NODE" 2>/dev/null
}
trap cleanup EXIT INT TERM

echo "=== OnePlus LTPO 实际 TE 测试 ==="
echo "测试期间请保持屏幕亮着、画面静止，不要触摸屏幕。"
echo "min_fps:    $(cat "$MIN_NODE")"
echo "adfr_config: $(cat "$ADFR_NODE")"
if [ -r "$DUMP_NODE" ]; then
    cat "$DUMP_NODE"
fi
echo
echo "启用内核 test_te，等待 3 秒进入空闲状态..."

echo 1 > "$TE_NODE" || {
    echo "错误：无法启用 test_te"
    exit 1
}
sleep 3

min=999
max=0
valid=0
i=1
while [ "$i" -le 12 ]; do
    value=$(cat "$TE_NODE" 2>/dev/null)
    case "$value" in
        ''|*[!0-9]*)
            echo "[$i/12] TE: 无效读数 ($value)"
            ;;
        *)
            echo "[$i/12] TE: ${value} Hz"
            valid=$((valid + 1))
            [ "$value" -lt "$min" ] && min=$value
            [ "$value" -gt "$max" ] && max=$value
            ;;
    esac
    i=$((i + 1))
    sleep 1
done

cleanup
trap - EXIT INT TERM

echo
if [ "$valid" -eq 0 ]; then
    echo "结果：没有取得有效 TE 数据，无法判断。"
    exit 2
fi

echo "采样范围：${min}-${max} Hz"
if [ "$min" -le 10 ]; then
    echo "结果：LTPO 生效，面板空闲时已降到 ${min} Hz。"
elif [ "$min" -lt 120 ]; then
    echo "结果：LTPO 生效，面板刷新率已低于 120 Hz。"
else
    echo "结果：本次没有观察到降频。请关闭动画/悬浮层后保持画面静止重测。"
fi
