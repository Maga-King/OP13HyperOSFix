package local.mio.op13hyperosfix;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Clean-room runtime adapter.  It deliberately has no service, timer, or
 * cross-process monitor: code is loaded only in the package selected in
 * LSPosed.  The SystemUI path piggybacks its existing BatteryStatus callback.
 */
final class MiChargeHooks {
    private static final String TAG = "OnePlus13MiChargeHook";
    private static final String SYSTEMUI = "com.android.systemui";
    private static final String SECURITY_CENTER = "com.miui.securitycenter";
    private static final String SECURITY_MANAGER = "com.miui.securitymanager";
    private static final String BATTERY = "/sys/class/power_supply/battery";
    private static final String USB = "/sys/class/power_supply/usb";
    private static final String WIRELESS =
            "/sys/class/power_supply/wireless";
    private static final String OP_BATTERY = "/sys/class/oplus_chg/battery";
    private static final String OP_COMMON = "/sys/class/oplus_chg/common";
    private static final String OP_SUBSYSTEM =
            "/sys/class/oplus_chg/common/subsystem/battery";
    private static final String OP_CPA_PROTOCOL_LIST =
            "/sys/firmware/devicetree/base/soc/oplus,cpa/oplus,protocol_list";
    private static final String[] OP_UFCS_CURRENT_PROPERTIES = {
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "silicon_p_770/oplus,curr_max_ma",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "silicon_p_770/oplus,ufcs_strategy_normal_current",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "silicon_p_770/oplus,ufcs_over_high_or_low_current",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "silicon_p_770/oplus,ufcs_ibat_over_third",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "silicon_p_770/oplus,ufcs_ibat_over_oplus",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "oplus,curr_max_ma",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "oplus,ufcs_strategy_normal_current",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "oplus,ufcs_over_high_or_low_current",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "oplus,ufcs_ibat_over_third",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "oplus,ufcs_ibat_over_oplus"
    };
    private static final String[] OP_UFCS_CURRENT_ARRAY_PROPERTIES = {
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "silicon_p_770/oplus,ufcs_strategy_high_current",
            "/sys/firmware/devicetree/base/soc/oplus,ufcs_charge/"
                    + "oplus,ufcs_strategy_high_current"
    };
    private static final String[] OP_SHUTDOWN_VOLTAGE_PROPERTIES = {
            "/sys/firmware/devicetree/base/soc/oplus,mms_gauge/"
                    + "silicon_p_770/deep_spec,uv_thr",
            "/sys/firmware/devicetree/base/soc/oplus,mms_gauge/"
                    + "deep_spec,uv_thr"
    };
    private static final String INSTANCE_STATE = "oneplus13_micharge_hook_state";
    private static final String INSTANCE_TICKER =
            "oneplus13_micharge_manual_decimal_ticker";
    private static final String INSTANCE_BATTERY_PAGE =
            "oneplus13_micharge_battery_page";
    private static final String[] BATTERY_PAGE_MATCH_KEYS = {
            "reference_battery_health",
            "reference_current_temp",
            "reference_toady_charge_time",
            "reference_cycle_count",
            "reference_production_date",
            "reference_first_use_date"
    };
    private static final AtomicBoolean SYSTEMUI_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SECURITY_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean ISLAND_PENDING_RESET = new AtomicBoolean(false);
    private static volatile boolean islandShowing;
    private static volatile int lastIslandSignature;
    private static volatile int lastStatusBarChargeTier = -1;
    private static final Set<Object> BATTERY_METER_VIEWS =
            Collections.synchronizedSet(
                    Collections.newSetFromMap(new WeakHashMap<>()));
    private static volatile WeakReference<Object> lastController =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> lastIslandListener =
            new WeakReference<>(null);
    private static volatile WeakReference<Object> lastIslandCallback =
            new WeakReference<>(null);

    static void install(
            String packageName, String processName, ClassLoader classLoader) {
        try {
            if (SYSTEMUI.equals(packageName)) {
                ChargeReader.prefetchUfcsProfile();
                hookSystemUi(classLoader);
            } else if (SECURITY_CENTER.equals(packageName)
                    || SECURITY_MANAGER.equals(packageName)) {
                ChargeReader.prefetchUfcsProfile();
                hookSecurityCenter(classLoader,
                        SECURITY_CENTER.equals(packageName)
                                && SECURITY_CENTER.equals(processName));
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": package hook failed " + packageName);
            XposedBridge.log(throwable);
        }
    }

    private static void hookSystemUi(ClassLoader classLoader) {
        final Class<?> batteryStatus = XposedHelpers.findClass(
                "com.miui.systemui.charge.BatteryStatus", classLoader);
        final Class<?> controller = XposedHelpers.findClass(
                "com.miui.charge.MiuiChargeController", classLoader);
        XposedHelpers.findAndHookMethod(
                controller,
                "checkBatteryStatus",
                batteryStatus,
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0
                                || param.args[0] == null) {
                            return;
                        }
                        Object status = param.args[0];
                        ChargeState charge = ChargeReader.readProtocol();
                        adoptNativeWirelessStatus(status, charge);
                        State state = stateFor(param.thisObject);
                        boolean fast = charge.quick >= 2 && charge.online;
                        // Decimal support is a property of the negotiated
                        // protocol, not of the first proc read. Oplus often
                        // returns "0, 0" for several seconds while PPS/UFCS
                        // finishes bringing up its own decimal curve.
                        boolean decimal = charge.power >= 50
                                && charge.online
                                && (charge.quick >= 4
                                || (charge.wireless && charge.quick >= 3));
                        boolean edge = charge.online && (!state.online
                                || state.quick != charge.quick
                                || state.power != charge.power);
                        boolean unplugged = state.online && !charge.online;
                        boolean plugged = !state.online && charge.online;
                        boolean lostFast = state.online && state.fast
                                && charge.online && !fast;
                        int oldDevice = readIntField(status,
                                "chargeDeviceType", -1);
                        int oldType = readIntField(param.thisObject,
                                "mChargeDeviceType", -1);

                        patchBatteryStatus(status, charge);
                        updateStatusBarChargeTier(
                                charge, "charge-controller");

                        if (edge) {
                            // Re-create the same state transition that Xiaomi
                            // expects on a fresh wired/protocol edge.  This
                            // clears its one-shot guards without bypassing the
                            // controller's own lockscreen/policy checks.
                            state.cancel(param.thisObject);
                            setBooleanField(param.thisObject,
                                    "mFastChargeChanged", true);
                            setBooleanField(param.thisObject,
                                    "hasShowdChargeAnim", false);
                            setBooleanField(param.thisObject,
                                    "mPendingChargeAnimation", false);
                            setBooleanField(param.thisObject,
                                    "mStateInitialized", true);
                            setIntField(param.thisObject, "mWireState", -1);
                            setIntField(param.thisObject, "mChargeSpeed", -1);
                            if (fast && param.args.length > 1) {
                                param.args[1] = Boolean.TRUE;
                            }
                            ISLAND_PENDING_RESET.set(true);
                            Object islandListener = lastIslandListener.get();
                            if (islandListener != null) {
                                resetIslandRuntime(islandListener);
                            }
                            XposedBridge.log(TAG + ": charge edge raw="
                                    + charge.rawType + " quick=" + charge.quick
                                    + " power=" + charge.power + "W");
                        } else if (oldType != charge.quick
                                || oldDevice != charge.quick) {
                            setBooleanField(param.thisObject, "mFastChargeChanged", true);
                        }

                        setBooleanField(param.thisObject, "mIsFastCharge", fast);
                        lastController = new WeakReference<>(param.thisObject);
                        state.online = charge.online;
                        state.wired = charge.wired;
                        state.rawType = charge.rawType;
                        state.quick = charge.quick;
                        state.power = charge.power;
                        state.fast = fast;
                        state.decimal = decimal;
                        state.edge = edge;
                        if (plugged || lostFast) {
                            state.probeDone = false;
                        }
                        if (unplugged || !fast) {
                            state.cancel(param.thisObject);
                        }
                        if (unplugged) {
                            state.stopProbe(param.thisObject);
                            state.probeDone = false;
                            islandShowing = false;
                            ISLAND_PENDING_RESET.set(true);
                            Object islandListener = lastIslandListener.get();
                            if (islandListener != null) {
                                resetIslandRuntime(islandListener);
                            }
                        }
                        if (SYSTEMUI_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + ": SystemUI hook active");
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        State state = stateFor(param.thisObject);
                        // The original method may derive this field again.
                        setBooleanField(param.thisObject, "mIsFastCharge", state.fast);
                        applyStatusBarChargeIcons();
                        if (state.decimal && state.edge) {
                            state.schedule(param.thisObject, 120L, 48);
                        }
                        if (state.wired && state.online
                                && !state.fast && !state.probeDone) {
                            state.scheduleProbe(param.thisObject, 50);
                        } else if (state.fast || !state.online) {
                            state.stopProbe(param.thisObject);
                        }
                        state.edge = false;
                    }
                });
        Class<?> listener = XposedHelpers.findClass(
                "com.android.systemui.devicenotification.listener."
                        + "DeviceNotificationListenerImpl$initBatteryListener$1",
                classLoader);
        XposedHelpers.findAndHookMethod(listener, "onRefreshBatteryInfo",
                batteryStatus, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] != null) {
                            lastIslandCallback =
                                    new WeakReference<>(param.thisObject);
                            ChargeState charge = ChargeReader.readProtocol();
                            adoptNativeWirelessStatus(param.args[0], charge);
                            Object islandListener = outerListener(param.thisObject);
                            if (islandListener != null) {
                                lastIslandListener =
                                        new WeakReference<>(islandListener);
                                int signature = presentationSignature(charge);
                                int previous = lastIslandSignature;
                                lastIslandSignature = signature;
                                if ((charge.online && signature != previous)
                                        || (!charge.online && previous != 0)
                                        || ISLAND_PENDING_RESET.getAndSet(false)) {
                                    resetIslandRuntime(islandListener);
                                }
                            }
                            patchBatteryStatus(param.args[0], charge);
                        }
                    }
                });
        Class<?> listenerImpl = XposedHelpers.findClass(
                "com.android.systemui.devicenotification.listener."
                        + "DeviceNotificationListenerImpl",
                classLoader);
        XposedHelpers.findAndHookMethod(listenerImpl, "onIslandStateChanged",
                boolean.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        lastIslandListener =
                                new WeakReference<>(param.thisObject);
                        boolean wasShowing = readBooleanField(
                                param.thisObject, "chargeIslandShowing", false);
                        boolean addingChargeIsland = param.args != null
                                && param.args.length >= 2
                                && Boolean.TRUE.equals(param.args[0])
                                && Boolean.TRUE.equals(param.args[1]);
                        if (addingChargeIsland && !wasShowing) {
                            resetIslandDecimal(param.thisObject);
                        } else if (ISLAND_PENDING_RESET.getAndSet(false)) {
                            resetIslandRuntime(param.thisObject);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        boolean wasShowing = islandShowing;
                        boolean nowShowing = readBooleanField(
                                param.thisObject, "chargeIslandShowing", false);
                        boolean chargeKeyEvent = param.args != null
                                && param.args.length >= 2
                                && Boolean.TRUE.equals(param.args[1]);
                        islandShowing = nowShowing;
                        Object controller = lastController.get();
                        if (nowShowing && !wasShowing && controller != null) {
                            State state = stateFor(controller);
                            if (state.decimal) {
                                state.cancel(controller);
                                state.schedule(controller, 80L, 48);
                            }
                        } else if (!nowShowing && chargeKeyEvent) {
                            // A real removal of notifyId=charge (gesture,
                            // timeout, or unplug). When a different island is
                            // merely placed in front, SystemUI also clears
                            // chargeIslandShowing, but the charge item remains
                            // in its multi-island queue and must only be paused.
                            ManualDecimalTicker.stop(param.thisObject);
                        }
                    }
                });
        try {
            Class<?> batteryControllerCallback = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.policy."
                            + "MiuiBatteryControllerImpl$1",
                    classLoader);
            XposedHelpers.findAndHookMethod(
                    batteryControllerCallback,
                    "onRefreshBatteryInfo",
                    batteryStatus,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (param.args != null
                                    && param.args.length > 0
                                    && param.args[0] != null) {
                                // This listener drives the lightning beside
                                // the status-bar battery. It runs before the
                                // charge animation controller on this build,
                                // so patch the shared BatteryStatus here too.
                                ChargeState charge =
                                        ChargeReader.readProtocol();
                                patchBatteryStatus(
                                        param.args[0], charge);
                                updateStatusBarChargeTier(
                                        charge, "battery-callback");
                            }
                        }

                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            applyStatusBarChargeIcons();
                        }
                    });
            Class<?> batteryMeterView = XposedHelpers.findClass(
                    "com.android.systemui.statusbar.views."
                            + "MiuiBatteryMeterView",
                    classLoader);
            XC_MethodHook meterIconHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(
                        MethodHookParam param) {
                    trackBatteryMeterView(param.thisObject);
                    applyStatusBarChargeIcon(param.thisObject);
                }
            };
            XposedBridge.hookAllMethods(
                    batteryMeterView, "updateChargeAndText",
                    meterIconHook);
            XposedBridge.hookAllMethods(
                    batteryMeterView, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            trackBatteryMeterView(param.thisObject);
                            applyStatusBarChargeIcon(
                                    param.thisObject);
                        }
                    });
            XposedBridge.hookAllMethods(
                    batteryMeterView, "onDetachedFromWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            BATTERY_METER_VIEWS.remove(
                                    param.thisObject);
                        }
                    });
            XposedBridge.log(TAG
                    + ": status-bar charge indicator hook active");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG
                    + ": status-bar charge indicator hook unavailable");
        }
        try {
            Class<?> strongToastCallback = XposedHelpers.findClass(
                    "com.miui.toast.MIUIStrongToastControl$1", classLoader);
            XposedHelpers.findAndHookMethod(strongToastCallback,
                    "onRefreshBatteryInfo", batteryStatus, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args != null && param.args.length > 0
                                    && param.args[0] != null) {
                                patchBatteryStatus(
                                        param.args[0],
                                        ChargeReader.readProtocol());
                            }
                        }
                    });
        } catch (Throwable ignored) {
            // Some SystemUI builds omit the legacy strong-toast callback.
        }
        XposedBridge.log(TAG + ": hooked MiuiChargeController.checkBatteryStatus");
    }

    private static void updateStatusBarChargeTier(
            ChargeState charge, String source) {
        int tier = charge.online
                ? (charge.quick >= 3 ? 3
                : charge.quick >= 2 ? 2 : 0)
                : -1;
        if (tier == lastStatusBarChargeTier) return;
        lastStatusBarChargeTier = tier;
        XposedBridge.log(TAG + ": status-bar charge tier="
                + tier + " raw=" + charge.rawType
                + " source=" + source);
        refreshStatusBarChargeViews();
    }

    private static void trackBatteryMeterView(Object meterView) {
        if (meterView != null) {
            BATTERY_METER_VIEWS.add(meterView);
        }
    }

    private static ArrayList<Object> batteryMeterViewSnapshot() {
        synchronized (BATTERY_METER_VIEWS) {
            return new ArrayList<>(BATTERY_METER_VIEWS);
        }
    }

    /**
     * Protocol negotiation completes after KeyguardUpdateMonitor's one-shot
     * plug callback. Refresh only the charging ImageView; re-entering the
     * full charge-state callback also restarts layout/Folme transitions.
     */
    private static void refreshStatusBarChargeViews() {
        Runnable refresh = () -> {
            int refreshed = 0;
            for (Object meterView : batteryMeterViewSnapshot()) {
                if (meterView instanceof View
                        && !((View) meterView).isAttachedToWindow()) {
                    continue;
                }
                try {
                    applyStatusBarChargeIcon(meterView);
                    refreshed++;
                } catch (Throwable ignored) {
                }
            }
            XposedBridge.log(TAG
                    + ": status-bar views refreshed tier="
                    + lastStatusBarChargeTier
                    + " count=" + refreshed);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refresh.run();
        } else {
            new Handler(Looper.getMainLooper()).post(refresh);
        }
    }

    private static void applyStatusBarChargeIcons() {
        for (Object meterView : batteryMeterViewSnapshot()) {
            applyStatusBarChargeIcon(meterView);
        }
    }

    /**
     * This HyperOS build exposes exactly two status-bar resources: Xiaomi's
     * ordinary single bolt and Xiaomi's quick-charge single bolt. Super
     * charging remains distinguished by animation/island wattage, not by
     * inventing a third status-bar graphic.
     */
    private static void applyStatusBarChargeIcon(Object meterView) {
        if (meterView == null || lastStatusBarChargeTier < 0) return;
        try {
            if (!readBooleanField(meterView, "mCharging", false)) return;
            setBooleanField(meterView, "mQuickCharging",
                    lastStatusBarChargeTier >= 2);
            Object imageValue = XposedHelpers.getObjectField(
                    meterView, "mBatteryChargingView");
            if (!(imageValue instanceof ImageView)) return;
            ImageView image = (ImageView) imageValue;

            // Mirror MiuiBatteryMeterView.updateChargeAndText: install the
            // official resource, reset its dark-state cache, then let
            // DarkIconDispatcher choose tint/light/dark resources.
            image.setImageTintList(null);
            Object idValue = XposedHelpers.callMethod(
                    meterView, "getHollowChargingIconId");
            if (idValue instanceof Integer) {
                image.setImageDrawable(
                        image.getContext().getDrawable((Integer) idValue));
            }
            setIntField(meterView, "mDark", 0);
            XposedHelpers.callMethod(
                    meterView, "onDarkChangedInternal");
        } catch (Throwable ignored) {
        }
    }

    private static void patchBatteryStatus(Object status, ChargeState charge) {
        // Oplus and Android already publish the wireless BatteryStatus path.
        // Leave its speed/type fields intact instead of forcing a wired
        // protocol enum onto it.
        if (charge.wireless && !charge.wired) {
            return;
        }
        setIntField(status, "chargeDeviceType", charge.quick);
        // Dynamic Island's decimal receiver keys off chargeSpeed == 3 and
        // maxChargingWattage >= 50.  Keep that contract for all 55W/100W
        // protocols while retaining Xiaomi's 1/2/3/4 device type values.
        setIntField(status, "chargeSpeed", charge.quick >= 3 ? 3 : charge.quick);
        setIntField(status, "maxChargingWattage", charge.power);
        if (charge.online && readIntField(status, "plugged", 0) != 4) {
            setIntField(status, "wireState", 11);
        }
    }

    private static void hookSecurityCenter(
            ClassLoader classLoader, boolean mainProcess) {
        final Class<?> miCharge = XposedHelpers.findClassIfExists(
                "miui.util.IMiCharge", null);
        if (miCharge != null) {
            hookString(miCharge, "getBatterySoh", () -> Integer.toString(
                    ChargeReader.readInt(OP_SUBSYSTEM + "/battery_soh", 0)));
            hookString(miCharge, "getBatteryCycleCount", () -> Integer.toString(
                    ChargeReader.readInt(
                            "/sys/class/power_supply/battery/device/battery/cycle_count", 0)));
            hookString(miCharge, "getBatteryChargeFull", () -> Long.toString(
                    ChargeReader.readInt(OP_SUBSYSTEM + "/battery_fcc", 0) * 1000L));
            hookString(miCharge, "getBatteryCapacity", () -> Integer.toString(
                    ChargeReader.readInt(BATTERY + "/capacity", 0)));
            hookString(miCharge, "getBatteryChargeType", () -> Integer.toString(
                    ChargeReader.read().quick));
            hookString(miCharge, "getChargingPowerMax", () -> Integer.toString(
                    ChargeReader.read().power));
            hookString(miCharge, "getSocDecimal", () -> Integer.toString(
                    ChargeReader.read().decimal.digits));
            hookString(miCharge, "getSocDecimalRate", () -> Integer.toString(
                    ChargeReader.read().decimal.rate));
            XposedBridge.hookAllMethods(miCharge, "isBatteryLifeFunctionSupported",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
            XposedBridge.hookAllMethods(miCharge, "getMiChargePath",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args == null || param.args.length == 0
                                    || !(param.args[0] instanceof String)) {
                                return;
                            }
                            String name = (String) param.args[0];
                            if ("charge_full_design".equals(name)) {
                                param.setResult(Long.toString(
                                        ChargeReader.readInt(
                                                OP_SUBSYSTEM
                                                        + "/design_capacity",
                                                ChargeReader.readInt(
                                                        OP_BATTERY
                                                                + "/design_capacity",
                                                        5920))
                                                * 1000L));
                            } else if ("charge_full".equals(name)
                                    || "battery_fcc".equals(name)) {
                                param.setResult(Long.toString(
                                        ChargeReader.readInt(
                                                OP_SUBSYSTEM + "/battery_fcc", 0) * 1000L));
                            } else if ("battery_soh".equals(name)) {
                                param.setResult(Integer.toString(
                                        ChargeReader.readInt(
                                                OP_SUBSYSTEM + "/battery_soh", 0)));
                            }
                        }
                    });
        } else {
            XposedBridge.log(TAG + ": IMiCharge unavailable; using direct page data only");
        }
        if (mainProcess) {
            SecurityCenterTimeHooks.install(classLoader);
            hookSecurityCenterBatteryPage(classLoader);
        }
        if (SECURITY_LOGGED.compareAndSet(false, true)) {
            XposedBridge.log(TAG + (miCharge != null
                    ? ": Security Center IMiCharge hooks active"
                    : ": Security Center direct-page fallback active"));
        }
    }

    private static void hookSecurityCenterBatteryPage(
            ClassLoader classLoader) {
        try {
            final Class<?> textPreference = XposedHelpers.findClassIfExists(
                    "miuix.preference.TextPreference", classLoader);
            if (textPreference != null) {
                XposedBridge.hookAllMethods(textPreference,
                        "onBindViewHolder", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            if (param.args == null
                                    || param.args.length == 0
                                    || param.args[0] == null) {
                                return;
                            }
                            Object keyValue;
                            try {
                                keyValue = XposedHelpers.callMethod(
                                        param.thisObject, "getKey");
                            } catch (Throwable ignored) {
                                return;
                            }
                            if (!(keyValue instanceof String)) return;
                            String key = (String) keyValue;
                            Object itemValue;
                            try {
                                itemValue = XposedHelpers.getObjectField(
                                        param.args[0], "itemView");
                            } catch (Throwable ignored) {
                                return;
                            }
                            if (!(itemValue instanceof View)) return;
                            View itemView = (View) itemValue;
                            if (isStaticBatteryDisplayKey(key)) {
                                int arrowId = itemView.getResources()
                                        .getIdentifier("arrow_right", "id",
                                                itemView.getContext()
                                                        .getPackageName());
                                View arrow = arrowId != 0
                                        ? itemView.findViewById(arrowId)
                                        : null;
                                if (arrow != null) {
                                    arrow.setVisibility(View.GONE);
                                }
                            }
                            if ("mio_battery_remaining".equals(key)) {
                                int textId = itemView.getResources()
                                        .getIdentifier("text_right", "id",
                                                itemView.getContext()
                                                        .getPackageName());
                                View rightValue = textId != 0
                                        ? itemView.findViewById(textId)
                                        : null;
                                if (rightValue instanceof TextView) {
                                    TextView right = (TextView) rightValue;
                                    right.setMaxWidth(Integer.MAX_VALUE);
                                    right.setMaxLines(1);
                                    right.setSingleLine(true);
                                    right.setEllipsize(null);
                                }
                            }
                            itemView.requestLayout();
                        }
                        });
            } else {
                XposedBridge.log(TAG
                        + ": TextPreference decoration hook unavailable");
            }

            final Class<?> preferenceGroup = XposedHelpers.findClassIfExists(
                    "androidx.preference.PreferenceGroup", classLoader);
            if (preferenceGroup != null) {
                XposedBridge.hookAllMethods(preferenceGroup,
                        "removePreference", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (param.args == null
                                    || param.args.length == 0
                                    || param.args[0] == null) {
                                return;
                            }
                            Object keyValue;
                            try {
                                keyValue = XposedHelpers.callMethod(
                                        param.args[0], "getKey");
                            } catch (Throwable ignored) {
                                return;
                            }
                            if (keyValue instanceof String
                                    && keepBatteryPreference(
                                    (String) keyValue)) {
                                param.setResult(false);
                            }
                        }
                        });
            } else {
                XposedBridge.log(TAG
                        + ": PreferenceGroup retention hook unavailable");
            }

            final Class<?> baseFragment = XposedHelpers.findClass(
                    "androidx.fragment.app.Fragment", classLoader);
            final Class<?> preferenceFragment =
                    XposedHelpers.findClassIfExists(
                            "androidx.preference.PreferenceFragmentCompat",
                            classLoader);
            XposedBridge.hookAllMethods(baseFragment, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(
                                MethodHookParam param) {
                            if (preferenceFragment != null
                                    && !preferenceFragment.isInstance(
                                    param.thisObject)) {
                                return;
                            }
                            Object value =
                                    XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject,
                                            INSTANCE_BATTERY_PAGE);
                            if (value instanceof SecurityBatteryPage) {
                                ((SecurityBatteryPage) value).start();
                                return;
                            }
                            if (!isSecurityBatteryPage(param.thisObject)) {
                                return;
                            }
                            try {
                                SecurityBatteryPage page =
                                        new SecurityBatteryPage(
                                                param.thisObject,
                                                classLoader);
                                XposedHelpers.setAdditionalInstanceField(
                                        param.thisObject,
                                        INSTANCE_BATTERY_PAGE,
                                        page);
                                page.start();
                            } catch (Throwable throwable) {
                                XposedBridge.log(TAG
                                        + ": battery page creation failed");
                                XposedBridge.log(throwable);
                            }
                        }
                    });
            XposedBridge.hookAllMethods(baseFragment, "onPause",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param) {
                            if (preferenceFragment != null
                                    && !preferenceFragment.isInstance(
                                    param.thisObject)) {
                                return;
                            }
                            stopBatteryPage(param.thisObject);
                        }
                    });
            XposedBridge.hookAllMethods(baseFragment, "onDestroyView",
                    new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (preferenceFragment != null
                            && !preferenceFragment.isInstance(
                            param.thisObject)) {
                        return;
                    }
                    stopBatteryPage(param.thisObject);
                    XposedHelpers.removeAdditionalInstanceField(
                            param.thisObject, INSTANCE_BATTERY_PAGE);
                }
            });
            XposedBridge.log(TAG
                    + ": Security Center structural battery page hooks active");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG
                    + ": Security Center battery page hook unavailable");
            XposedBridge.log(throwable);
        }
    }

    private static boolean keepBatteryPreference(String key) {
        return "reference_battery_health".equals(key)
                || "reference_current_temp".equals(key)
                || "reference_toady_charge_time".equals(key)
                || "reference_cycle_count".equals(key)
                || "reference_production_date".equals(key)
                || "reference_first_use_date".equals(key);
    }

    private static boolean isStaticBatteryDisplayKey(String key) {
        return "reference_battery_health".equals(key)
                || "reference_current_temp".equals(key)
                || "reference_toady_charge_time".equals(key);
    }

    private static boolean isSecurityBatteryPage(Object fragment) {
        boolean hasCategory = findFragmentPreference(fragment,
                "preference_key_category_battery_info") != null;
        int matches = 0;
        for (String key : BATTERY_PAGE_MATCH_KEYS) {
            if (findFragmentPreference(fragment, key) != null) {
                matches++;
            }
        }
        return (hasCategory && matches >= 2) || matches >= 4;
    }

    private static Object findFragmentPreference(
            Object fragment, String key) {
        try {
            return XposedHelpers.callMethod(fragment,
                    "findPreference", key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void stopBatteryPage(Object fragment) {
        Object value = XposedHelpers.getAdditionalInstanceField(
                fragment, INSTANCE_BATTERY_PAGE);
        if (value instanceof SecurityBatteryPage) {
            ((SecurityBatteryPage) value).stop();
        }
    }

    private static final class SecurityBatteryPage {
        private static final long REFRESH_MS = 1000L;

        private final Object fragment;
        private final ClassLoader classLoader;
        private final Context context;
        private final Handler mainHandler =
                new Handler(Looper.getMainLooper());
        private final Object category;
        private final Object health;
        private final Object temperature;
        private final Object todayChargeCount;
        private final Object cycle;
        private final Object productionDate;
        private final Object firstUseDate;
        private final Object designCapacity;
        private final Object fullCapacity;
        private final Object power;
        private final Object voltageAndShutdown;
        private final Object current;
        private final Object remaining;

        private volatile boolean running;
        private HandlerThread workerThread;
        private Handler workerHandler;
        private int shutdownVoltage;
        private int batterySoh;
        private int cycleCount;
        private int designMah;
        private int fullMah;
        private double capacityHealth;

        private final Runnable sampleRunnable = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                BatteryPageSample sample = BatteryPageSample.read(
                        shutdownVoltage, fullMah);
                mainHandler.post(() -> {
                    if (running) render(sample);
                });
                Handler handler = workerHandler;
                if (running && handler != null) {
                    handler.postDelayed(this, REFRESH_MS);
                }
            }
        };

        SecurityBatteryPage(
                Object fragment, ClassLoader classLoader) {
            this.fragment = fragment;
            this.classLoader = classLoader;
            Object contextValue = XposedHelpers.callMethod(
                    fragment, "getContext");
            if (!(contextValue instanceof Context)) {
                contextValue = XposedHelpers.callMethod(
                        fragment, "requireContext");
            }
            this.context = (Context) contextValue;
            this.category = findPreference(
                    "preference_key_category_battery_info");
            this.health = findPreference(
                    "reference_battery_health");
            this.temperature = findPreference(
                    "reference_current_temp");
            this.todayChargeCount = findPreference(
                    "reference_toady_charge_time");
            this.cycle = findPreference(
                    "reference_cycle_count");
            this.productionDate = findPreference(
                    "reference_production_date");
            this.firstUseDate = findPreference(
                    "reference_first_use_date");
            neutralizeDisplayPreference(health);
            neutralizeDisplayPreference(temperature);
            neutralizeDisplayPreference(todayChargeCount);
            setTitle(cycle, "电池循环次数");

            int order = nextOrder();
            this.designCapacity = replaceWithTextPreference(
                    "reference_battery_design_capacity",
                    "电池设计容量", order++);
            this.fullCapacity = replaceWithTextPreference(
                    "reference_battery_full_capacity",
                    "电池充满容量", order++);
            this.power = ensurePreference(
                    "mio_current_battery_power",
                    "电池功率（当前功耗）", order++);
            this.voltageAndShutdown = ensurePreference(
                    "mio_voltage_shutdown",
                    "当前电压 / 关机电压", order++);
            this.current = ensurePreference(
                    "mio_battery_current",
                    "当前电流", order++);
            this.remaining = ensurePreference(
                    "mio_battery_remaining",
                    "当前容量", order);
        }

        void refreshStaticRows() {
            batterySoh = ChargeReader.readInt(
                    OP_SUBSYSTEM + "/battery_soh",
                    ChargeReader.readInt(
                            OP_BATTERY + "/battery_soh", 0));
            cycleCount = ChargeReader.readInt(
                    "/sys/class/power_supply/battery/device/"
                            + "battery/cycle_count",
                    ChargeReader.readInt(
                            OP_BATTERY + "/battery_cc", 0));
            designMah = ChargeReader.readInt(
                    OP_SUBSYSTEM + "/design_capacity",
                    ChargeReader.readInt(
                            OP_BATTERY + "/design_capacity", 5920));
            fullMah = ChargeReader.readInt(
                    OP_SUBSYSTEM + "/battery_fcc",
                    ChargeReader.readInt(
                            OP_BATTERY + "/battery_fcc", 0));
            capacityHealth = designMah > 0 && fullMah > 0
                    ? fullMah * 100.0 / designMah : 0.0;
            shutdownVoltage = ChargeReader.readInt(
                    OP_SUBSYSTEM + "/vbat_uv",
                    ChargeReader.readInt(
                            OP_BATTERY + "/vbat_uv", 0));

            renderHealth();
            setText(cycle, cycleCount > 0
                    ? cycleCount + " 次" : "--");
            setText(designCapacity, designMah > 0
                    ? designMah + " mAh" : "--");
            setText(fullCapacity, fullMah > 0
                    ? fullMah + " mAh" : "--");
            setOptionalText(productionDate, firstReadableDate(
                    OP_SUBSYSTEM + "/battery_manu_date",
                    OP_BATTERY + "/battery_manu_date"));
            setOptionalText(firstUseDate, firstReadableDate(
                    OP_SUBSYSTEM + "/battery_first_usage_date",
                    OP_BATTERY + "/battery_first_usage_date"));
        }

        synchronized void start() {
            if (running) return;
            refreshStaticRows();
            running = true;
            workerThread = new HandlerThread(
                    "MiCharge-battery-page");
            workerThread.start();
            workerHandler = new Handler(workerThread.getLooper());
            workerHandler.post(sampleRunnable);
            XposedBridge.log(TAG
                    + ": battery page live refresh started");
        }

        synchronized void stop() {
            if (!running && workerThread == null) return;
            running = false;
            Handler handler = workerHandler;
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
            }
            HandlerThread thread = workerThread;
            workerHandler = null;
            workerThread = null;
            if (thread != null) thread.quitSafely();
            XposedBridge.log(TAG
                    + ": battery page live refresh stopped");
        }

        private void render(BatteryPageSample sample) {
            // The stock asynchronous handler replaces the numeric SOH with
            // a qualitative label after onCreatePreferences. Reassert the
            // node-backed values only while this page is visible.
            renderHealth();
            setText(cycle, cycleCount > 0
                    ? cycleCount + " 次" : "--");
            setText(designCapacity, designMah > 0
                    ? designMah + " mAh" : "--");
            setText(fullCapacity, fullMah > 0
                    ? fullMah + " mAh" : "--");
            setText(temperature, String.format(
                    Locale.getDefault(), "%.1f ℃",
                    sample.temperatureDeciC / 10.0));
            setText(power, String.format(
                    Locale.getDefault(), "%+.2f W", sample.powerW));
            setText(voltageAndShutdown,
                    sample.voltageMv + " / "
                            + sample.shutdownMv + " mV");
            setText(current, String.format(
                    Locale.getDefault(), "%+d mA",
                    sample.currentMa));
            if (sample.remainingMah > 0
                    && sample.rawRemainingMah > 0) {
                setText(remaining, sample.remainingMah
                        + " mAh（RM值 "
                        + sample.rawRemainingMah
                        + " mAh）");
            } else {
                setText(remaining, sample.remainingMah > 0
                        ? sample.remainingMah + " mAh" : "--");
            }
        }

        private void renderHealth() {
            if (capacityHealth > 0.0 && batterySoh > 0) {
                setText(health, String.format(
                        Locale.getDefault(),
                        "%d %%（官方值 %d %%）",
                        Math.round(capacityHealth), batterySoh));
            } else if (batterySoh > 0) {
                setText(health, batterySoh + " %");
            } else {
                setText(health, "--");
            }
        }

        private Object findPreference(String key) {
            try {
                return XposedHelpers.callMethod(
                        fragment, "findPreference", key);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Object ensurePreference(
                String key, String title, int order) {
            Object existing = findPreference(key);
            if (existing != null) return existing;
            if (category == null || context == null) return null;
            try {
                Class<?> textPreference = health != null
                        ? health.getClass()
                        : XposedHelpers.findClassIfExists(
                                "miuix.preference.TextPreference",
                                classLoader);
                if (textPreference == null) return null;
                Object preference;
                try {
                    preference = XposedHelpers.newInstance(
                            textPreference, context);
                } catch (Throwable first) {
                    preference = XposedHelpers.newInstance(
                            textPreference, context, null);
                }
                XposedHelpers.callMethod(preference, "setKey", key);
                XposedHelpers.callMethod(preference, "setTitle", title);
                XposedHelpers.callMethod(
                        preference, "setClickable", false);
                try {
                    XposedHelpers.callMethod(preference,
                            "setTouchAnimationEnable", false);
                } catch (Throwable ignored) {
                }
                XposedHelpers.callMethod(
                        preference, "setOrder", order);
                XposedHelpers.callMethod(
                        category, "addPreference", preference);
                return preference;
            } catch (Throwable throwable) {
                XposedBridge.log(TAG
                        + ": add battery row failed " + key);
                return null;
            }
        }

        private Object replaceWithTextPreference(
                String key, String title, int order) {
            Object existing = findPreference(key);
            if (existing != null && category != null) {
                try {
                    XposedHelpers.callMethod(
                            category, "removePreference", existing);
                } catch (Throwable ignored) {
                }
            }
            return ensurePreference(key, title, order);
        }

        private int nextOrder() {
            if (category == null) return 1000;
            try {
                int count = (Integer) XposedHelpers.callMethod(
                        category, "getPreferenceCount");
                int maximum = -1;
                for (int index = 0; index < count; index++) {
                    Object preference = XposedHelpers.callMethod(
                            category, "getPreference", index);
                    Object value = XposedHelpers.callMethod(
                            preference, "getOrder");
                    if (value instanceof Integer) {
                        maximum = Math.max(
                                maximum, (Integer) value);
                    }
                }
                return maximum < 0 ? count + 100 : maximum + 1;
            } catch (Throwable ignored) {
                return 1000;
            }
        }

        private static void setText(Object preference, String text) {
            if (preference == null || text == null
                    || text.trim().isEmpty()) {
                return;
            }
            try {
                XposedHelpers.callMethod(
                        preference, "setText", text);
                try {
                    XposedHelpers.callMethod(
                            preference, "setVisible", true);
                } catch (Throwable ignored) {
                }
            } catch (Throwable ignored) {
            }
        }

        private static void setTitle(
                Object preference, String title) {
            if (preference == null || title == null) return;
            try {
                XposedHelpers.callMethod(
                        preference, "setTitle", title);
            } catch (Throwable ignored) {
            }
        }

        private static void neutralizeDisplayPreference(
                Object preference) {
            if (preference == null) return;
            try {
                XposedHelpers.callMethod(
                        preference, "setIntent", (Object) null);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.callMethod(
                        preference, "setSelectable", false);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.callMethod(
                        preference, "setClickable", false);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.callMethod(preference,
                        "setTouchAnimationEnable", false);
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.callMethod(preference,
                        "setOnPreferenceClickListener",
                        (Object) null);
            } catch (Throwable ignored) {
            }
        }

        private static void setOptionalText(
                Object preference, String text) {
            if (preference == null) return;
            try {
                XposedHelpers.callMethod(preference,
                        "setVisible",
                        text != null && !text.trim().isEmpty());
            } catch (Throwable ignored) {
            }
            if (text != null) setText(preference, text);
        }

        private static String firstReadableDate(String... paths) {
            for (String path : paths) {
                String value = ChargeReader.read(path);
                if (value == null) continue;
                value = value.trim();
                String digits = value.replace("-", "");
                if ((digits.length() == 8)
                        && digits.matches("[0-9]+")
                        && !"00000000".equals(digits)
                        && !"99999999".equals(digits)
                        && ChargeReader.parseInt(
                        digits.substring(0, 4), 0) >= 2000) {
                    return value;
                }
            }
            return null;
        }
    }

    private static final class BatteryPageSample {
        int temperatureDeciC;
        int cell1Mv;
        int cell2Mv;
        int voltageMv;
        int shutdownMv;
        int currentMa;
        int remainingMah;
        int rawRemainingMah;
        double powerW;

        static BatteryPageSample read(
                int cachedShutdownMv, int fullCapacityMah) {
            BatteryPageSample sample = new BatteryPageSample();
            String raw = ChargeReader.read(
                    OP_BATTERY + "/bcc_parms");
            if (raw == null) {
                raw = ChargeReader.read(
                        OP_SUBSYSTEM + "/bcc_parms");
            }
            if (raw != null) {
                String[] values = raw.split(",");
                if (values.length > 11) {
                    sample.cell1Mv = ChargeReader.parseInt(
                            values[6], 0);
                    sample.currentMa = -ChargeReader.parseInt(
                            values[8], 0);
                    sample.cell2Mv = ChargeReader.parseInt(
                            values[11], 0);
                }
            }
            sample.temperatureDeciC = ChargeReader.readInt(
                    BATTERY + "/temp", 0);
            if (sample.currentMa == 0) {
                int current = ChargeReader.readInt(
                        BATTERY + "/current_now", 0);
                current = Math.abs(current) > 100000
                        ? current / 1000 : current;
                sample.currentMa = -current;
            }
            if (sample.cell1Mv > 0 && sample.cell2Mv > 0) {
                sample.voltageMv =
                        (sample.cell1Mv + sample.cell2Mv) / 2;
                sample.powerW = (sample.cell1Mv
                        + sample.cell2Mv)
                        * (double) sample.currentMa
                        / 1000000.0;
            } else {
                int voltage = ChargeReader.readInt(
                        BATTERY + "/voltage_now", 0);
                sample.voltageMv = voltage > 100000
                        ? voltage / 1000 : voltage;
                sample.cell1Mv = sample.voltageMv;
                sample.cell2Mv = sample.voltageMv;
                sample.powerW = sample.voltageMv
                        * 2.0 * (double) sample.currentMa
                        / 1000000.0;
            }
            sample.shutdownMv = cachedShutdownMv > 0
                    ? cachedShutdownMv
                    : ChargeReader.readInt(
                    OP_BATTERY + "/vbat_uv", 0);
            sample.rawRemainingMah = ChargeReader.readInt(
                    OP_SUBSYSTEM + "/battery_rm",
                    ChargeReader.readInt(
                            OP_BATTERY + "/battery_rm", 0));
            sample.remainingMah = ChargeReader.readInt(
                    BATTERY + "/charge_now", 0);
            if (sample.remainingMah > 100000) {
                sample.remainingMah /= 1000;
            }
            if (fullCapacityMah > 0
                    && sample.remainingMah
                    > Math.round(fullCapacityMah * 1.10f)) {
                int level = ChargeReader.readInt(
                        BATTERY + "/capacity", -1);
                sample.remainingMah =
                        level >= 0 && level <= 100
                                ? Math.round(
                                fullCapacityMah * level / 100.0f)
                                : 0;
            }
            if (sample.remainingMah <= 0) {
                sample.remainingMah = ChargeReader.readInt(
                        BATTERY + "/charge_counter", 0);
                if (sample.remainingMah > 100000) {
                    sample.remainingMah /= 1000;
                }
            }
            if (sample.remainingMah <= 0) {
                sample.remainingMah = sample.rawRemainingMah;
            }
            return sample;
        }
    }

    private static void hookString(Class<?> type, String method, Value value) {
        XposedBridge.hookAllMethods(type, method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(value.get());
                } catch (Throwable throwable) {
                    XposedBridge.log(TAG + ": " + method + " read failed");
                    XposedBridge.log(throwable);
                }
            }
        });
    }

    private interface Value {
        String get();
    }

    private static final class State {
        boolean online;
        boolean wired;
        boolean fast;
        boolean decimal;
        boolean edge;
        int rawType;
        int quick = -1;
        int power = -1;
        boolean scheduled;
        Runnable pending;
        boolean probeScheduled;
        boolean probeDone;
        Runnable probePending;

        void cancel(Object controller) {
            Handler handler = handler(controller);
            if (handler != null && pending != null) {
                handler.removeCallbacks(pending);
            }
            ManualDecimalTicker.stop(controller);
            Object listener = lastIslandListener.get();
            if (listener != null) {
                ManualDecimalTicker.stop(listener);
            }
            scheduled = false;
            pending = null;
        }

        void stopProbe(Object controller) {
            Handler handler = handler(controller);
            if (handler != null && probePending != null) {
                handler.removeCallbacks(probePending);
            }
            probeScheduled = false;
            probePending = null;
        }

        void scheduleProbe(Object controller, int retries) {
            if (probeScheduled || probeDone || retries <= 0) {
                return;
            }
            Handler handler = handler(controller);
            if (handler == null) {
                probeDone = true;
                return;
            }
            probeScheduled = true;
            probePending = () -> {
                probeScheduled = false;
                probePending = null;
                ChargeState charge = ChargeReader.readProtocol();
                if (!charge.online) {
                    probeDone = false;
                    return;
                }
                if (charge.quick >= 2) {
                    probeDone = true;
                    XposedBridge.log(TAG + ": protocol probe raw="
                            + charge.rawType + " quick=" + charge.quick
                            + " power=" + charge.power + "W");
                    dispatchProtocolRefresh(controller, charge);
                    return;
                }
                if (retries > 1) {
                    scheduleProbe(controller, retries - 1);
                } else {
                    probeDone = true;
                    XposedBridge.log(TAG
                            + ": protocol probe finished as ordinary charge");
                }
            };
            handler.postDelayed(probePending, 300L);
        }

        void schedule(Object controller, long delayMs, int retries) {
            if (scheduled) {
                return;
            }
            Handler handler = handler(controller);
            if (handler == null) {
                return;
            }
            scheduled = true;
            pending = () -> {
                scheduled = false;
                pending = null;
                ChargeState charge = ChargeReader.readProtocol();
                boolean decimalCapable = charge.online && decimal;
                boolean fullAnimation = readBooleanField(controller,
                        "mChargeAnimationShowing", false);
                boolean visible = fullAnimation || islandShowing;
                if (!fast || !decimalCapable) {
                    return;
                }
                if (!visible) {
                    // Wait only for SystemUI to create a visible presentation.
                    // The live calculator itself is display-lifecycle scoped.
                    if (retries > 0) {
                        schedule(controller, 250L, retries - 1);
                    }
                    return;
                }
                if (fullAnimation) {
                    ManualDecimalTicker.start(controller,
                            ManualDecimalTicker.MODE_FULL, charge);
                }
                Object listener = lastIslandListener.get();
                if (islandShowing && listener != null) {
                    ManualDecimalTicker.start(listener,
                            ManualDecimalTicker.MODE_ISLAND, charge);
                }
            };
            handler.postDelayed(pending, delayMs);
        }
    }

    /**
     * Manual decimal calculator scoped to a visible full-screen animation or
     * Dynamic Island. Sensor files are sampled twice per second; the 50 ms
     * frame only advances an in-memory accumulator and updates visible text.
     */
    private static final class ManualDecimalTicker implements Runnable {
        static final int MODE_FULL = 1;
        static final int MODE_ISLAND = 2;
        private static final long FRAME_MS = 50L;
        private static final long SAMPLE_MS = 500L;

        final Object target;
        final int mode;
        final Handler handler;
        final ChargeState presentation;
        final boolean ufcs;
        boolean running;
        int baseLevel = -1;
        int currentMa;
        int fcc = 4500;
        boolean followingReal;
        double physicalCenti;
        double shownCenti;
        long startedMs;
        long lastFrameMs;
        long lastSampleMs;
        long islandDeadlineMs;
        long lastDiagnosticMs;
        String lastText = "";

        ManualDecimalTicker(
                Object target, int mode, Handler handler,
                ChargeState presentation) {
            this.target = target;
            this.mode = mode;
            this.handler = handler;
            this.presentation = presentation;
            this.ufcs = presentation != null
                    && presentation.rawType == 15;
        }

        static void start(
                Object target, int mode, ChargeState presentation) {
            if (target == null) return;
            Object existing = XposedHelpers.getAdditionalInstanceField(
                    target, INSTANCE_TICKER);
            if (existing instanceof ManualDecimalTicker
                    && ((ManualDecimalTicker) existing).running) {
                return;
            }
            Handler handler = mode == MODE_FULL
                    ? MiChargeHooks.handler(target) : islandHandler(target);
            if (handler == null) return;
            ManualDecimalTicker ticker =
                    new ManualDecimalTicker(
                            target, mode, handler, presentation);
            XposedHelpers.setAdditionalInstanceField(
                    target, INSTANCE_TICKER, ticker);
            ticker.running = true;
            ticker.lastFrameMs = SystemClock.uptimeMillis();
            ticker.startedMs = ticker.lastFrameMs;
            if (mode == MODE_ISLAND) {
                Object status = readObjectField(target, "oldBatteryStatus");
                boolean wireless = status != null
                        && readIntField(status, "wireState", -1) == 10;
                // Match SystemUI's own charge-island lifetime exactly:
                // wired 5 s, wireless 10 s.
                ticker.islandDeadlineMs = ticker.lastFrameMs
                        + (wireless ? 10000L : 5000L);
            }
            ticker.sample(true);
            ticker.cancelStockAnimator();
            ticker.run();
        }

        static void stop(Object target) {
            if (target == null) return;
            Object existing = XposedHelpers.getAdditionalInstanceField(
                    target, INSTANCE_TICKER);
            if (!(existing instanceof ManualDecimalTicker)) return;
            ManualDecimalTicker ticker = (ManualDecimalTicker) existing;
            ticker.running = false;
            ticker.handler.removeCallbacks(ticker);
            XposedHelpers.removeAdditionalInstanceField(
                    target, INSTANCE_TICKER);
        }

        static Handler islandHandler(Object listener) {
            Object value = readObjectField(listener, "wirelessChargeHandler");
            if (!(value instanceof Handler)) {
                value = readObjectField(listener, "bgHandler");
            }
            return value instanceof Handler ? (Handler) value : null;
        }

        @Override
        public void run() {
            if (!running) {
                stop(target);
                return;
            }
            long now = SystemClock.uptimeMillis();
            boolean foreground = visible();
            if (mode == MODE_FULL && !foreground) {
                stop(target);
                return;
            }
            if (mode == MODE_ISLAND && now >= islandDeadlineMs) {
                releaseIsland();
                return;
            }
            long elapsed = Math.max(0L, Math.min(250L, now - lastFrameMs));
            lastFrameMs = now;
            if (now - lastSampleMs >= SAMPLE_MS) {
                sample(false);
            }
            if (!running || baseLevel < 0 || baseLevel >= 100) {
                stop(target);
                return;
            }

            // Coulomb-count the two-cell pack. Live VBUS x IBUS is folded
            // into currentMa by LiveChargeSample so protocol ramp-up is not
            // stuck at the tiny current observed on the first callback.
            double physicalDelta =
                    (double) currentMa * 2.0 * 10000.0 * elapsed
                    / ((double) fcc * 3600000.0);
            physicalCenti += physicalDelta;
            if (!ufcs) {
                shownCenti = physicalCenti;
            } else {
                long age = now - startedMs;
                // UFCS spends its first seconds negotiating voltage/current.
                // Add exactly one displayed centi-percent per second for the
                // first five seconds, then fade that bonus over two seconds.
                if (age <= 5000L) {
                    shownCenti += physicalDelta
                            + (double) elapsed / 1000.0;
                } else if (age <= 7000L) {
                    double fade = (7000.0 - age) / 2000.0;
                    shownCenti += physicalDelta
                            + (double) elapsed / 1000.0
                            * Math.max(0.0, fade);
                } else if (followingReal) {
                    shownCenti = physicalCenti;
                } else {
                    double gap = physicalCenti - shownCenti;
                    if (Math.abs(gap) <= 0.35) {
                        // Once the synthetic lead/lag is no longer visible at
                        // two decimals, follow the measured value exactly.
                        shownCenti = physicalCenti;
                        followingReal = true;
                    } else if (gap > 0.0) {
                        // Behind the measured value: catch up smoothly.
                        shownCenti += physicalDelta * 1.65
                                + (double) elapsed * 0.5 / 1000.0;
                    } else {
                        // Ahead of the measured value: never count backwards;
                        // advance slowly until the measured curve catches it.
                        shownCenti += physicalDelta * 0.35;
                    }
                }
            }
            double lower = baseLevel * 100.0;
            double upper = lower + 99.0;
            physicalCenti = Math.max(
                    lower, Math.min(upper, physicalCenti));
            shownCenti = Math.max(lower, Math.min(upper, shownCenti));
            String text = String.format(Locale.getDefault(),
                    "%1.2f", shownCenti / 100.0);
            // A different Dynamic Island can temporarily sit in front of the
            // charge item. Keep its short-lived accumulator continuous, but
            // never call handleDeviceNotification while charge is not the
            // foreground key, otherwise we would steal focus and renew it.
            if (foreground && !text.equals(lastText)) {
                lastText = text;
                render(text);
            }
            handler.postDelayed(this, FRAME_MS);
        }

        void sample(boolean initial) {
            LiveChargeSample sample = ChargeReader.liveSample();
            lastSampleMs = SystemClock.uptimeMillis();
            if (!sample.online) {
                running = false;
                return;
            }
            int level = systemUiLevel();
            if (level < 0 || level > 100) {
                level = ChargeReader.readInt(BATTERY + "/capacity", -1);
            }
            if (level < 0 || level > 100) {
                running = false;
                return;
            }
            fcc = sample.fcc > 0 ? sample.fcc : 4500;
            currentMa = sample.effectiveCurrentMa();
            int fraction = -1;
            if (initial || level != baseLevel) {
                baseLevel = level;
                fraction = sample.initialFraction(baseLevel);
                physicalCenti = baseLevel * 100.0 + fraction;
                shownCenti = physicalCenti;
                followingReal = false;
            }
            if (initial) {
                lastDiagnosticMs = lastSampleMs;
                XposedBridge.log(TAG + ": manual decimal start mode="
                        + (mode == MODE_FULL ? "full" : "island")
                        + " ui=" + baseLevel + " fraction="
                        + Math.max(0, fraction) + " current="
                        + currentMa + "mA"
                        + (sample.wired ? " wired" : " wireless")
                        + " raw=" + (presentation == null
                        ? 0 : presentation.rawType));
            } else if (ufcs
                    && lastSampleMs - lastDiagnosticMs >= 1000L) {
                lastDiagnosticMs = lastSampleMs;
                XposedBridge.log(TAG + ": UFCS decimal sample mode="
                        + (mode == MODE_FULL ? "full" : "island")
                        + " current=" + currentMa + "mA"
                        + " real=" + String.format(
                        Locale.US, "%.2f", physicalCenti / 100.0)
                        + " shown=" + String.format(
                        Locale.US, "%.2f", shownCenti / 100.0));
            }
        }

        int systemUiLevel() {
            Object status = mode == MODE_FULL
                    ? readObjectField(target, "mBatteryStatus")
                    : readObjectField(target, "oldBatteryStatus");
            return status == null ? -1
                    : readIntField(status, "level", -1);
        }

        boolean visible() {
            if (mode == MODE_FULL) {
                if (!readBooleanField(
                        target, "mChargeAnimationShowing", false)) {
                    return false;
                }
                Object view = readObjectField(target, "mChargeAnimationView");
                return view != null && !readBooleanField(
                        view, "mStartingDismissAnim", false);
            }
            return readBooleanField(target,
                    "chargeIslandShowing", false);
        }

        void cancelStockAnimator() {
            Object animator;
            if (mode == MODE_FULL) {
                Object view = readObjectField(target, "mChargeAnimationView");
                Object percent = view == null ? null
                        : readObjectField(view, "mChargePercentView");
                animator = percent == null ? null
                        : readObjectField(percent, "mValueAnimator");
            } else {
                animator = readObjectField(target, "valueAnimator");
            }
            if (animator != null) {
                try {
                    if (mode == MODE_ISLAND) {
                        // Take ownership without firing the stock cancel/end
                        // listener, which removes the charge island midway
                        // through our first frame.
                        XposedHelpers.callMethod(
                                animator, "removeAllUpdateListeners");
                        XposedHelpers.callMethod(
                                animator, "removeAllListeners");
                    }
                    XposedHelpers.callMethod(animator, "cancel");
                    if (mode == MODE_ISLAND) {
                        XposedHelpers.setObjectField(
                                target, "valueAnimator", null);
                    }
                } catch (Throwable ignored) {
                }
            }
            if (mode == MODE_ISLAND) {
                setBooleanField(target, "decimalAnimStarted", false);
                setBooleanField(target, "receivedDecimal", false);
            }
        }

        void releaseIsland() {
            running = false;
            handler.removeCallbacks(this);
            XposedHelpers.removeAdditionalInstanceField(
                    target, INSTANCE_TICKER);
            try {
                // This is the same cleanup path used when Xiaomi's finite
                // decimal ValueAnimator ends. It removes only notifyId=charge.
                XposedHelpers.callStaticMethod(
                        target.getClass(),
                        "access$releaseValueAnimation",
                        target);
            } catch (Throwable first) {
                try {
                    Object remove = readObjectField(
                            target, "removeChargeIslandRunnable");
                    if (remove != null) {
                        XposedHelpers.callMethod(remove, "run");
                    }
                } catch (Throwable second) {
                    XposedBridge.log(
                            TAG + ": charge island release failed");
                    XposedBridge.log(second);
                }
            }
            XposedBridge.log(TAG + ": manual decimal island finished");
        }

        void render(String text) {
            try {
                if (mode == MODE_FULL) {
                    Object view = readObjectField(
                            target, "mChargeAnimationView");
                    Object percent = readObjectField(
                            view, "mChargePercentView");
                    Object number = readObjectField(percent, "mIntegerTv");
                    XposedHelpers.callMethod(number, "setLevelText", text);
                    setIntField(percent, "mCurrentProgress", baseLevel);
                    return;
                }
                Object status = readObjectField(target, "oldBatteryStatus");
                if (status == null) return;
                if (presentation != null) {
                    // A late native battery callback can replace
                    // oldBatteryStatus after the protocol edge. Re-apply only
                    // the charge presentation fields before building notifyId
                    // "charge", otherwise maxWattage<50 returns a null model.
                    patchBatteryStatus(status, presentation);
                }
                Object model = XposedHelpers.callMethod(target,
                        "structModelForCharge", text, 3, status);
                if (model == null) {
                    XposedBridge.log(
                            TAG + ": charge island model unavailable");
                    return;
                }
                boolean wireless =
                        readIntField(status, "wireState", -1) == 10;
                Object bundle = XposedHelpers.callStaticMethod(
                        target.getClass(), "structBundleForCharge", wireless);
                XposedHelpers.callMethod(target,
                        "handleDeviceNotification", bundle, model);
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": manual decimal render failed");
                XposedBridge.log(throwable);
                running = false;
            }
        }
    }

    private static void dispatchProtocolRefresh(
            Object controller, ChargeState charge) {
        Object status = readObjectField(controller, "mBatteryStatus");
        if (status == null) {
            return;
        }
        patchBatteryStatus(status, charge);
        try {
            XposedHelpers.callMethod(
                    controller, "checkBatteryStatus", status, true);
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": controller protocol refresh failed");
            XposedBridge.log(throwable);
        }
        Object listener = lastIslandListener.get();
        if (listener != null) {
            resetIslandRuntime(listener);
        }
        Object callback = lastIslandCallback.get();
        if (callback != null) {
            try {
                XposedHelpers.callMethod(
                        callback, "onRefreshBatteryInfo", status);
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": island protocol refresh failed");
                XposedBridge.log(throwable);
            }
        }
    }

    private static State stateFor(Object controller) {
        Object existing = XposedHelpers.getAdditionalInstanceField(controller, INSTANCE_STATE);
        if (existing instanceof State) {
            return (State) existing;
        }
        State state = new State();
        XposedHelpers.setAdditionalInstanceField(controller, INSTANCE_STATE, state);
        return state;
    }

    private static Handler handler(Object controller) {
        Object value = XposedHelpers.getObjectField(controller, "mHandler");
        return value instanceof Handler ? (Handler) value : null;
    }

    private static void adoptNativeWirelessStatus(
            Object status, ChargeState charge) {
        if (!charge.wireless || charge.wired || status == null) {
            return;
        }
        int device = readIntField(status, "chargeDeviceType", 0);
        int speed = readIntField(status, "chargeSpeed", 0);
        int power = readIntField(status, "maxChargingWattage", 0);
        charge.quick = Math.max(0, Math.max(device, speed));
        charge.power = Math.max(0, power);
    }

    private static Object outerListener(Object callback) {
        try {
            return XposedHelpers.getObjectField(callback, "this$0");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readObjectField(Object object, String field) {
        try {
            return XposedHelpers.getObjectField(object, field);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int presentationSignature(ChargeState charge) {
        if (!charge.online) {
            return 0;
        }
        return ((charge.quick & 0xff) << 16) | (charge.power & 0xffff);
    }

    private static void resetIslandDecimal(Object listener) {
        setBooleanField(listener, "decimalAnimStarted", false);
        setBooleanField(listener, "receivedDecimal", false);
        setFloatField(listener, "oldRapidLevel", 0.0f);
        setFloatField(listener, "oldRapidRate", 0.0f);
    }

    private static void resetIslandRuntime(Object listener) {
        ManualDecimalTicker.stop(listener);
        resetIslandDecimal(listener);
        setIntField(listener, "oldWireState", -1);
        setIntField(listener, "oldChargeSpeed", -1);
    }

    private static int readIntField(Object object, String field, int fallback) {
        try {
            return XposedHelpers.getIntField(object, field);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setFloatField(Object object, String field, float value) {
        try {
            XposedHelpers.setFloatField(object, field, value);
        } catch (Throwable ignored) {
        }
    }

    private static boolean readBooleanField(Object object, String field, boolean fallback) {
        try {
            return XposedHelpers.getBooleanField(object, field);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void setIntField(Object object, String field, int value) {
        try {
            XposedHelpers.setIntField(object, field, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setBooleanField(Object object, String field, boolean value) {
        try {
            XposedHelpers.setBooleanField(object, field, value);
        } catch (Throwable ignored) {
        }
    }

    private static final class ChargeState {
        boolean online;
        boolean wired;
        boolean wireless;
        int rawType;
        int quick;
        int power;
        Decimal decimal = new Decimal();
    }

    private static final class LiveChargeSample {
        boolean online;
        boolean wired;
        int ibatMa;
        int ibusMa;
        int vbusMv;
        int vbatMv;
        int rm;
        int fcc;
        double smoothSoc = -1.0;

        int initialFraction(int uiLevel) {
            int procCenti = ChargeReader.currentOplusDecimalCenti();
            if (procCenti >= 0 && procCenti / 100 == uiLevel) {
                return procCenti % 100;
            }
            if (smoothSoc >= 0.0
                    && (int) Math.floor(smoothSoc) == uiLevel) {
                int centi = (int) Math.floor(smoothSoc * 100.0 + 0.0001);
                return Math.max(0, Math.min(99, centi % 100));
            }
            if (rm >= 0 && fcc > 0) {
                long rawCenti = (long) rm * 10000L / fcc;
                if (rawCenti / 100L == uiLevel) {
                    return (int) (rawCenti % 100L);
                }
            }
            // Never combine an unrelated raw-SOC fraction with the visible
            // smoothed integer. Starting at .00 is honest and cannot stall at
            // .99 while waiting for the status-bar integer to catch up.
            return 0;
        }

        int effectiveCurrentMa() {
            long gauge = Math.abs((long) ibatMa);
            long fromInput = 0L;
            if (wired && ibusMa != 0 && vbusMv > 0 && vbatMv > 0) {
                // Convert measured adapter power to the current through one
                // cell of the series pair. The ticker applies battery_count=2
                // exactly once when converting this current to display SOC.
                fromInput = Math.abs((long) ibusMa)
                        * (long) vbusMv * 88L
                        / ((long) vbatMv * 2L * 100L);
            }
            return (int) Math.max(0L,
                    Math.min(20000L, Math.max(gauge, fromInput)));
        }
    }

    private static final class Decimal {
        int digits;
        int rate;
        int initialCenti;
        int currentCenti;
        int baseLevel;
        int currentMa;
        boolean valid;
    }

    private static final class ChargeReader {
        private static final AtomicBoolean UFCS_PROFILE_STARTED =
                new AtomicBoolean(false);
        private static volatile int ufcsProfilePower;

        static void prefetchUfcsProfile() {
            if (!UFCS_PROFILE_STARTED.compareAndSet(false, true)) return;
            Thread reader = new Thread(() -> {
                int configuredPower = readUfcsCpaProfilePower();
                int currentMarker = readUfcsCurrentMarker();
                int shutdownVoltage = readShutdownVoltage();
                ufcsProfilePower =
                        configuredPower >= 150
                                || currentMarker >= 13000
                                || shutdownVoltage == 2800
                                ? 150 : 100;
                XposedBridge.log(TAG + ": UFCS profile cached "
                        + ufcsProfilePower + "W (cpa="
                        + configuredPower + "W, currentMarker="
                        + currentMarker + "mA, shutdown="
                        + shutdownVoltage + "mV)");
            }, "MiCharge-UFCS-profile");
            reader.setDaemon(true);
            try {
                reader.start();
            } catch (Throwable throwable) {
                ufcsProfilePower = 100;
                XposedBridge.log(TAG + ": UFCS profile thread failed");
            }
        }

        static ChargeState read() {
            ChargeState state = readProtocol();
            state.decimal = decimal();
            return state;
        }

        static ChargeState readProtocol() {
            ChargeState state = new ChargeState();
            state.wired = readInt(USB + "/online", 0) > 0
                    || readLogInt("wired_online", 0) > 0;
            state.wireless = readInt(WIRELESS + "/online", 0) > 0
                    || readLogInt("wls_online", 0) > 0
                    || readLogInt("wireless_online", 0) > 0;
            state.online = state.wired || state.wireless;
            state.rawType = state.wired ? rawType() : 0;
            int availablePower = availablePowerMilliwatts();
            if (state.rawType == 0 && state.wired) {
                state.rawType = rawUsbType();
            }
            state.quick = state.wired
                    ? quickType(state.rawType, availablePower) : 0;
            state.power = power(state.rawType, state.quick, availablePower);
            if (state.quick == 0) state.power = 2;
            return state;
        }

        static LiveChargeSample liveSample() {
            LiveChargeSample sample = new LiveChargeSample();
            String head = read(OP_BATTERY + "/battery_log_head");
            if (head == null) {
                head = read(OP_COMMON + "/battery_log_head");
            }
            String content = read(OP_BATTERY + "/battery_log_content");
            if (content == null) {
                content = read(OP_COMMON + "/battery_log_content");
            }
            int wiredOnline = 0;
            int wirelessOnline = 0;
            if (head != null && content != null) {
                String[] names = head.split(",");
                String[] values = content.split(",");
                for (int i = 0; i < names.length && i < values.length; i++) {
                    String name = names[i].trim();
                    String value = values[i].trim();
                    if ("wired_online".equals(name)) {
                        wiredOnline = parseInt(value, wiredOnline);
                    } else if ("wls_online".equals(name)
                            || "wireless_online".equals(name)) {
                        wirelessOnline = parseInt(value, wirelessOnline);
                    } else if ("ibat_ma".equals(name)) {
                        sample.ibatMa = parseInt(value, sample.ibatMa);
                    } else if ("wired_ibus_ma".equals(name)) {
                        sample.ibusMa = parseInt(value, sample.ibusMa);
                    } else if ("wired_vbus_mv".equals(name)) {
                        sample.vbusMv = parseInt(value, sample.vbusMv);
                    } else if ("vbat_mv".equals(name)) {
                        sample.vbatMv = parseInt(value, sample.vbatMv);
                    } else if ("batt_rm".equals(name)) {
                        sample.rm = parseInt(value, sample.rm);
                    } else if ("batt_fcc".equals(name)) {
                        sample.fcc = parseInt(value, sample.fcc);
                    } else if ("bs_smooth_soc_centi".equals(name)) {
                        sample.smoothSoc = parseDouble(
                                value, sample.smoothSoc);
                    }
                }
            }
            sample.wired = wiredOnline > 0
                    || readInt(USB + "/online", 0) > 0;
            boolean wireless = wirelessOnline > 0
                    || readInt(WIRELESS + "/online", 0) > 0;
            sample.online = sample.wired || wireless;
            if (sample.ibatMa == 0) {
                long current = Math.abs((long) readInt(
                        BATTERY + "/current_now", 0));
                if (current > 100000L) current /= 1000L;
                sample.ibatMa = (int) Math.min(
                        Integer.MAX_VALUE, current);
            }
            if (sample.vbatMv <= 0) {
                int voltage = readInt(BATTERY + "/voltage_now", 0);
                sample.vbatMv = voltage > 100000
                        ? voltage / 1000 : voltage;
            }
            if (sample.fcc <= 0) {
                sample.fcc = readInt(
                        OP_SUBSYSTEM + "/battery_fcc", 4500);
            }
            return sample;
        }

        static int rawType() {
            if (readInt(OP_COMMON + "/ufcs_online", 0) == 1) return 15;
            if (readInt(OP_BATTERY + "/ppschg_ing", 0) == 1) return 8;
            int type = readLogInt("charge_type", 0);
            if (type >= 1 && type <= 15) return type;
            type = readInt(OP_COMMON + "/protocol_type", 0);
            if (type >= 1 && type <= 15) return type;
            // voocchg_ing alone cannot distinguish VOOC from SVOOC. Keep
            // the conservative MI TURBO bucket unless Oplus reports enum 14.
            if (readInt(OP_BATTERY + "/voocchg_ing", 0) == 1) return 13;
            return 0;
        }

        static int readUfcsCurrentMarker() {
            int maximum = 0;
            for (String path : OP_UFCS_CURRENT_PROPERTIES) {
                maximum = Math.max(maximum, readBigEndianProperty(path));
            }
            for (String path : OP_UFCS_CURRENT_ARRAY_PROPERTIES) {
                maximum = Math.max(
                        maximum, readBigEndianPropertyMaximum(path));
            }
            return maximum;
        }

        static int readShutdownVoltage() {
            int runtime = readInt(OP_BATTERY + "/vbat_uv", 0);
            runtime = Math.max(runtime,
                    readInt(OP_SUBSYSTEM + "/vbat_uv", 0));
            if (runtime > 0) return runtime;
            int deviceTree = 0;
            for (String path : OP_SHUTDOWN_VOLTAGE_PROPERTIES) {
                deviceTree = Math.max(
                        deviceTree, readBigEndianProperty(path));
            }
            return deviceTree;
        }

        static int readBigEndianProperty(String path) {
            try (FileInputStream input = new FileInputStream(path)) {
                byte[] value = new byte[4];
                if (readFully(input, value)) {
                    return readBigEndianInt(value, 0);
                }
            } catch (Throwable ignored) {
                // Device-tree layouts differ between kernels; missing
                // candidates are expected and the remaining paths are tried.
            }
            return 0;
        }

        static int readBigEndianPropertyMaximum(String path) {
            int maximum = 0;
            try (FileInputStream input = new FileInputStream(path)) {
                byte[] value = new byte[4];
                while (readFully(input, value)) {
                    maximum = Math.max(
                            maximum, readBigEndianInt(value, 0));
                }
            } catch (Throwable ignored) {
                // Optional device-tree candidate.
            }
            return maximum;
        }

        static int quickType(int type, int availablePower) {
            // Xiaomi only renders the numeric "W MAX" layout for device type
            // 4. PPS therefore shares that presentation with SVOOC/UFCS.
            if (type == 8 || type == 14 || type == 15) return 4;
            // fopbatt/Oplus enum:
            // 6 PD, 7 PD_DRP, 9 PD_SDP, 11 QC2, 12 QC3, 13 VOOC.
            if (type == 6 || type == 7 || type == 9
                    || type == 11 || type == 12 || type == 13) {
                return 2;
            }
            // SDP/DCP/CDP/ACA/plain Type-C/Apple are intentionally left as
            // ordinary charging even if a stale power node looks high.
            return 0;
        }

        static int power(int type, int quick, int availablePower) {
            if (type == 14) return 100;
            if (type == 15) {
                int cached = ufcsProfilePower;
                if (cached > 0) return cached;
                return availablePower >= 145000 ? 150 : 100;
            }
            if (type == 8) return 55;
            if (quick == 2) {
                int fallback = type == 13 ? 20 : 18;
                return availablePower >= 1000
                        ? Math.max(11, Math.min(100, availablePower / 1000))
                        : fallback;
            }
            return 2;
        }

        static int readUfcsCpaProfilePower() {
            try (FileInputStream input =
                         new FileInputStream(OP_CPA_PROTOCOL_LIST)) {
                byte[] pair = new byte[8];
                while (readFully(input, pair)) {
                    int protocol = readBigEndianInt(pair, 0);
                    int watts = readBigEndianInt(pair, 4);
                    if (protocol == 4) return watts;
                }
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": UFCS profile prefetch failed");
            }
            return 0;
        }

        static boolean readFully(FileInputStream input, byte[] buffer)
                throws java.io.IOException {
            int offset = 0;
            while (offset < buffer.length) {
                int count = input.read(
                        buffer, offset, buffer.length - offset);
                if (count <= 0) return false;
                offset += count;
            }
            return true;
        }

        static int readBigEndianInt(byte[] data, int offset) {
            return ((data[offset] & 0xff) << 24)
                    | ((data[offset + 1] & 0xff) << 16)
                    | ((data[offset + 2] & 0xff) << 8)
                    | (data[offset + 3] & 0xff);
        }

        static int availablePowerMilliwatts() {
            int cpa = readInt(OP_COMMON + "/cpa_power", 0);
            int adapter = readInt(OP_COMMON + "/adapter_power", 0);
            return Math.max(cpa, adapter);
        }

        static int rawUsbType() {
            String realType = read(USB + "/real_type");
            String type = read(USB + "/type");
            String value = ((realType == null ? "" : realType) + " "
                    + (type == null ? "" : type)).toLowerCase();
            if (value.contains("pps")) return 8;
            if (value.contains("hvdcp") || value.contains("qc3")) return 12;
            if (value.contains("qc2") || value.contains("quick_charge")) return 11;
            if (value.contains("usb_pd") || value.contains("power_delivery")) return 6;
            return 0;
        }

        static Decimal decimal() {
            Decimal result = new Decimal();
            // Oplus reports absolute hundredths, not Xiaomi's
            // (decimal-digits, rate) pair: "7835, 7837" means that its own
            // kernel curve started at 78.35% and is currently at 78.37%.
            String pair = read("/proc/ui_soc_decimal");
            if (pair == null) {
                pair = readOplusDecimalBinder();
            }
            if (pair == null) return result;
            String[] parts = pair.trim().split("[,\\s]+");
            if (parts.length < 2) return result;
            try {
                int initial = Integer.parseInt(parts[0]);
                int current = Integer.parseInt(parts[1]);
                // 0,0 means that SVOOC/UFCS/PPS has not initialized the
                // decimal worker yet. Never broadcast it as Xiaomi ".00".
                if (initial <= 0 || current <= 0
                        || initial > 10000 || current > 10000
                        || current < initial) {
                    return result;
                }
                int baseLevel = readInt(BATTERY + "/capacity", -1);
                int digits = current - baseLevel * 100;
                // Oplus updates the real integer UI SOC from the same curve.
                // Wait rather than inventing a base if that battery event is
                // in the few-millisecond handoff between two integers.
                if (baseLevel < 0 || baseLevel > 100
                        || digits < 0 || digits > 99) {
                    return result;
                }
                int currentMa = decimalCurrentMa();
                int rate = decimalRate(currentMa, baseLevel);
                result.digits = digits;
                result.rate = rate;
                result.initialCenti = initial;
                result.currentCenti = current;
                result.baseLevel = baseLevel;
                result.currentMa = currentMa;
                result.valid = true;
            } catch (NumberFormatException ignored) {
            }
            return result;
        }

        static int decimalCurrentMa() {
            long current = Math.abs((long) readLogInt("ibat_ma", 0));
            if (current == 0) {
                current = Math.abs((long) readInt(
                        BATTERY + "/current_now", 0));
                // Android normally exposes microamps, while this OnePlus 13
                // kernel exposes milliamps. Accept either representation.
                if (current > 100000) current /= 1000;
            }
            return (int) Math.min(current, Integer.MAX_VALUE);
        }

        static int decimalRate(int currentMa, int uiSoc) {
            int fcc = readInt(OP_SUBSYSTEM + "/battery_fcc", 0);
            if (fcc <= 0) fcc = readLogInt("batt_fcc", 4500);
            if (fcc <= 0 || currentMa <= 0
                    || readLogInt("mmi_chg", 1) == 0) {
                return 0;
            }

            // Clean-room conversion of OnePlus' published kernel formula:
            // speed = 100000 * current_mA * 1s * battery_count
            //         / (FCC_mAh * 3600)
            // OnePlus 13 has two cells. Oplus speed is thousandths of one
            // percent per second; numerically the same integer is Xiaomi's
            // hundredths-of-one-percent delta over its 10-second animator.
            long speed = 100000L * currentMa * 2L / (fcc * 3600L);
            int smoothSoc = readLogInt("smooth_soc", uiSoc);
            if (uiSoc - smoothSoc > 2) {
                speed /= 2;
            } else if (uiSoc < smoothSoc) {
                speed *= 2;
            }
            return (int) Math.max(0, Math.min(500, speed));
        }

        static String readOplusDecimalBinder() {
            Parcel data = null;
            Parcel reply = null;
            try {
                Class<?> serviceManager = XposedHelpers.findClass(
                        "android.os.ServiceManager", null);
                Object value = XposedHelpers.callStaticMethod(serviceManager,
                        "getService",
                        "vendor.oplus.hardware.charger.ICharger/default");
                if (!(value instanceof IBinder)) return null;
                data = Parcel.obtain();
                reply = Parcel.obtain();
                data.writeInterfaceToken(
                        "vendor.oplus.hardware.charger.ICharger");
                if (!((IBinder) value).transact(76, data, reply, 0)) {
                    return null;
                }
                reply.readException();
                return reply.readString();
            } catch (Throwable ignored) {
                return null;
            } finally {
                if (reply != null) reply.recycle();
                if (data != null) data.recycle();
            }
        }

        static int currentOplusDecimalCenti() {
            String pair = read("/proc/ui_soc_decimal");
            if (pair == null) {
                pair = readOplusDecimalBinder();
            }
            if (pair == null) return -1;
            String[] parts = pair.trim().split("[,\\s]+");
            if (parts.length < 2) return -1;
            int value = parseInt(parts[1], -1);
            return value >= 0 && value <= 10000 ? value : -1;
        }

        static int parseInt(String value, int fallback) {
            try {
                return Integer.parseInt(value.trim());
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        static double parseDouble(String value, double fallback) {
            try {
                return Double.parseDouble(value.trim());
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        static int readLogInt(String key, int fallback) {
            String value = readLog(key);
            if (value == null) return fallback;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        static String readLog(String key) {
            String head = read(OP_BATTERY + "/battery_log_head");
            if (head == null) head = read(OP_COMMON + "/battery_log_head");
            String content = read(OP_BATTERY + "/battery_log_content");
            if (content == null) content = read(OP_COMMON + "/battery_log_content");
            if (head == null || content == null) return null;
            String[] names = head.split(",");
            String[] values = content.split(",");
            for (int i = 0; i < names.length && i < values.length; i++) {
                if (key.equals(names[i].trim())) return values[i].trim();
            }
            return null;
        }

        static int readInt(String path, int fallback) {
            String value = read(path);
            if (value == null) return fallback;
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        static String read(String path) {
            try {
                File file = new File(path);
                if (!file.isFile()) return null;
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    return reader.readLine();
                }
            } catch (Throwable ignored) {
                return null;
            }
        }
    }
}
