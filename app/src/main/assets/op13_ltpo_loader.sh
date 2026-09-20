#!/system/bin/sh

BASE=/data/adb/op13_hyperos_fix
BIN="$BASE/bin"
LOG="$BASE/log"
FOD_KO="$BIN/op13_fod_bridge.ko"
LTPO_KO="$BIN/op13_ltpo_restore.ko"

/system/bin/flock -n 0 || exit 0

until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 1
done

kernel_supported=1
case "$(uname -r)" in
    6.6.*) ;;
    *)
        echo "unsupported kernel: $(uname -r); keeping userspace fallback"
        kernel_supported=0
        ;;
esac

if [ "$kernel_supported" = 1 ] \
        && ! grep -q '^op13_fod_bridge ' /proc/modules 2>/dev/null; then
    count=0
    while [ ! -d /sys/module/oplus_bsp_tp_notify ] && [ "$count" -lt 50 ]; do
        sleep 0.1
        count=$((count + 1))
    done
    if [ -d /sys/module/oplus_bsp_tp_notify ]; then
        insmod "$FOD_KO" || echo "op13_fod_bridge load failed; using /dev/kmsg fallback"
    else
        echo "oplus_bsp_tp_notify unavailable; using /dev/kmsg fallback"
    fi
fi

fod_bridge_loaded=0
if grep -q '^op13_fod_bridge ' /proc/modules 2>/dev/null; then
    fod_bridge_loaded=1
fi

if [ "$kernel_supported" = 1 ] \
        && ! grep -q '^op13_ltpo_restore ' /proc/modules 2>/dev/null; then
    insmod "$LTPO_KO" min_fps=1 delay_ms=350 apply_on_load=1 \
        || echo "op13_ltpo_restore load failed"
fi

if [ "$fod_bridge_loaded" = 1 ]; then
    pkill -x SysUIFPfix 2>/dev/null || true
elif ! pidof SysUIFPfix >/dev/null 2>&1; then
    /system/bin/setsid "$BIN/SysUIFPfix" >>"$LOG/SysUIFPfix.log" 2>&1 </dev/null &
fi
