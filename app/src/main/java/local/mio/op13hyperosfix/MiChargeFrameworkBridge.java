package local.mio.op13hyperosfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adapts the native Oplus charger snapshot to Xiaomi's existing MiCharge
 * framework pipeline. SystemUI remains untouched and consumes Xiaomi's
 * original sticky quick-charge broadcast.
 */
final class MiChargeFrameworkBridge {
    private static final String OPLUS_CHARGER_SERVICE =
            "vendor.oplus.hardware.charger.ICharger/default";
    private static final String OPLUS_CHARGER_DESCRIPTOR =
            "vendor.oplus.hardware.charger.ICharger";
    private static final int TRANSACTION_GET_CHG_CONFIG = 0x80;
    private static final int TRANSACTION_GET_SOC_DECIMAL = 76;
    private static final int MSG_QUICK_CHARGE_TYPE = 3;
    private static final int MSG_SOC_DECIMAL = 4;

    // Oplus charger HAL protocol values observed on the OnePlus 13 base.
    // The sysfs fallback is normalized to the same values below.
    private static final int OPLUS_PROFILE_PPS = 2;
    private static final int OPLUS_PROFILE_SVOOC = 3;
    private static final int OPLUS_PROFILE_UFCS = 4;

    private static final long[] DECIMAL_PROBE_DELAYS_MS = {
            250L, 650L, 1050L, 1450L, 1950L, 2600L
    };

    private static final String USB_ONLINE = "/sys/class/power_supply/usb/online";
    private static final String WIRELESS_ONLINE =
            "/sys/class/power_supply/wireless/online";
    private static final String BATTERY = "/sys/class/power_supply/battery";
    private static final String OPLUS_COMMON = "/sys/class/oplus_chg/common";
    private static final String OPLUS_BATTERY = "/sys/class/oplus_chg/battery";
    private static final String OPLUS_SUBSYSTEM_BATTERY =
            OPLUS_COMMON + "/subsystem/battery";
    private static final String OPLUS_SOC_DECIMAL = "/proc/ui_soc_decimal";

    private static final Object STATE_LOCK = new Object();
    private static final AtomicBoolean REFRESH_QUEUED = new AtomicBoolean();
    private static final AtomicBoolean MARKER_REPORTED = new AtomicBoolean();

    private static volatile Handler batteryHandler;
    private static volatile IBinder chargerBinder;
    private static volatile int frameworkPlugged = -1;
    private static volatile int frameworkLevel = -1;
    private static volatile int decimalSession;
    private static volatile boolean decimalPublished;
    private static volatile int lastDecimalProbeCenti = -1;
    private static volatile long lastDecimalProbeElapsed;
    private static volatile Decimal currentDecimal = Decimal.UNAVAILABLE;
    private static volatile Snapshot current = Snapshot.UNAVAILABLE;

    private MiChargeFrameworkBridge() {
    }

    static void install(ClassLoader loader) {
        Class<?> miCharge = Reflect.findClass(loader, "miui.util.IMiCharge");
        Class<?> handlerClass = Reflect.findClass(loader,
                "com.android.server.MiuiBatteryServiceImpl$BatteryHandler");
        Class<?> serviceClass = Reflect.findClass(loader,
                "com.android.server.MiuiBatteryServiceImpl");
        if (miCharge == null || handlerClass == null || serviceClass == null) {
            HookLog.once("micharge_bridge_classes_missing",
                    "MiCharge framework classes missing; Oplus bridge skipped");
            return;
        }

        int getterHooks = hookMiChargeGetters(miCharge);
        hookBatteryHandler(handlerClass);
        int receiverHooks = hookBatteryReceivers(loader, serviceClass);
        int ueventHooks = hookPowerSupplyObserver(loader, handlerClass);
        HookLog.info("MiCharge framework bridge installed: getters=" + getterHooks
                + ", receivers=" + receiverHooks + ", uevents=" + ueventHooks);
    }

    private static int hookMiChargeGetters(Class<?> miCharge) {
        int hooks = 0;
        hooks += hookStringGetter(miCharge, "getQuickChargeType", 0);
        hooks += hookStringGetter(miCharge, "getChargingPowerMax", 1);
        hooks += hookStringGetter(miCharge, "getCarChargingType", 2);
        hooks += hookStringGetter(miCharge, "getSocDecimal", 3);
        hooks += hookStringGetter(miCharge, "getSocDecimalRate", 4);
        return hooks;
    }

    private static int hookStringGetter(Class<?> target, String name, int valueKind) {
        return Reflect.hookNamedMethods(target, name,
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (valueKind >= 3) {
                            Decimal decimal = currentDecimal;
                            if (!decimal.valid) {
                                return;
                            }
                            param.setResult(Integer.toString(valueKind == 3
                                    ? decimal.digits : decimal.rate));
                            return;
                        }
                        Snapshot state = current;
                        if (!state.available) {
                            return;
                        }
                        int value;
                        if (valueKind == 0) {
                            value = state.quickType;
                        } else if (valueKind == 1) {
                            value = state.powerWatts;
                        } else {
                            value = -1;
                        }
                        param.setResult(Integer.toString(value));
                    }
                });
    }

    private static void hookBatteryHandler(Class<?> handlerClass) {
        XposedBridge.hookAllConstructors(handlerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Handler) {
                    batteryHandler = (Handler) param.thisObject;
                    scheduleRefresh("handler-init");
                }
            }
        });

        Method sendMessage = Reflect.findMethod(handlerClass, "sendMessage",
                int.class, int.class);
        Reflect.hookMethodOnce(sendMessage,
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length != 2
                                || !(param.args[0] instanceof Number)
                                || ((Number) param.args[0]).intValue()
                                != MSG_QUICK_CHARGE_TYPE) {
                            return;
                        }
                        Snapshot state = current;
                        if (state.available) {
                            param.args[1] = state.quickType;
                        }
                    }
                });
    }

    private static int hookBatteryReceivers(ClassLoader loader, Class<?> serviceClass) {
        Set<Class<?>> candidates = new HashSet<>();
        try {
            for (Class<?> candidate : serviceClass.getDeclaredClasses()) {
                candidates.add(candidate);
            }
        } catch (Throwable ignored) {
        }
        for (int index = 1; index <= 24; index++) {
            Class<?> candidate = Reflect.findClass(loader,
                    serviceClass.getName() + "$" + index);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        int hooks = 0;
        for (Class<?> candidate : candidates) {
            if (!BroadcastReceiver.class.isAssignableFrom(candidate)) {
                continue;
            }
            Method onReceive = Reflect.findMethod(candidate, "onReceive",
                    Context.class, Intent.class);
            if (onReceive == null) {
                continue;
            }
            if (Reflect.hookMethodOnce(onReceive, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Intent intent = param.args.length > 1 && param.args[1] instanceof Intent
                            ? (Intent) param.args[1] : null;
                    if (intent == null
                            || !Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                        return;
                    }
                    frameworkPlugged = intent.getIntExtra("plugged", 0);
                    frameworkLevel = intent.getIntExtra("level", -1);
                    scheduleRefresh("battery-changed");
                }
            })) {
                hooks++;
            }
        }
        return hooks;
    }

    private static int hookPowerSupplyObserver(ClassLoader loader,
            Class<?> handlerClass) {
        Set<Class<?>> candidates = new HashSet<>();
        try {
            for (Class<?> candidate : handlerClass.getDeclaredClasses()) {
                candidates.add(candidate);
            }
        } catch (Throwable ignored) {
        }
        Class<?> named = Reflect.findClass(loader,
                handlerClass.getName() + "$BatteryUEventObserver");
        if (named != null) {
            candidates.add(named);
        }

        int hooks = 0;
        for (Class<?> candidate : candidates) {
            for (Method method : candidate.getDeclaredMethods()) {
                if (!"onUEvent".equals(method.getName())
                        || method.getParameterTypes().length != 1) {
                    continue;
                }
                if (Reflect.hookMethodOnce(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object event = param.args.length == 1 ? param.args[0] : null;
                        if (eventValue(event, "POWER_SUPPLY_QUICK_CHARGE_TYPE") == null) {
                            return;
                        }
                        RefreshResult result = refreshState("quick-charge-uevent");
                        if (result.changed) {
                            postPublish(result);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object event = param.args.length == 1 ? param.args[0] : null;
                        String name = eventValue(event, "POWER_SUPPLY_NAME");
                        if (isChargePowerSupply(name)) {
                            scheduleRefresh("power-supply-uevent:" + name);
                        }
                    }
                })) {
                    hooks++;
                }
            }
        }
        return hooks;
    }

    private static String eventValue(Object event, String key) {
        if (event == null) {
            return null;
        }
        try {
            Object value = Reflect.call(event, "get", key);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isChargePowerSupply(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.contains("usb")
                || normalized.contains("wireless")
                || normalized.equals("ac")
                || normalized.equals("main");
    }

    private static void scheduleRefresh(String reason) {
        Handler handler = batteryHandler;
        if (handler == null || !REFRESH_QUEUED.compareAndSet(false, true)) {
            return;
        }
        if (!handler.post(() -> {
            try {
                RefreshResult result = refreshState(reason);
                if (result.changed) {
                    publishQuickCharge(handler, result.state);
                }
                if (result.startDecimalSession) {
                    scheduleDecimalSession(handler, result.decimalSession);
                }
            } finally {
                REFRESH_QUEUED.set(false);
            }
        })) {
            REFRESH_QUEUED.set(false);
        }
    }

    private static void postPublish(RefreshResult result) {
        Handler handler = batteryHandler;
        if (handler != null) {
            handler.post(() -> {
                publishQuickCharge(handler, result.state);
                if (result.startDecimalSession) {
                    scheduleDecimalSession(handler, result.decimalSession);
                }
            });
        }
    }

    private static void publishQuickCharge(Handler handler, Snapshot state) {
        if (!state.available) {
            return;
        }
        handler.removeMessages(MSG_QUICK_CHARGE_TYPE);
        android.os.Message message = handler.obtainMessage(MSG_QUICK_CHARGE_TYPE);
        message.arg1 = state.quickType;
        handler.sendMessage(message);
    }

    private static void scheduleDecimalSession(Handler handler, int session) {
        for (int attempt = 0; attempt < DECIMAL_PROBE_DELAYS_MS.length; attempt++) {
            final int probe = attempt;
            handler.postDelayed(() -> publishDecimalIfReady(handler, session, probe),
                    DECIMAL_PROBE_DELAYS_MS[attempt]);
        }
    }

    private static void publishDecimalIfReady(Handler handler, int session, int attempt) {
        synchronized (STATE_LOCK) {
            if (session != decimalSession || decimalPublished
                    || !current.online || current.quickType != 4) {
                return;
            }
        }

        Decimal decimal = readOplusDecimal();
        if (!decimal.valid) {
            if (attempt == DECIMAL_PROBE_DELAYS_MS.length - 1) {
                HookLog.once("oplus_soc_decimal_not_ready",
                        "Oplus SOC decimal stayed unavailable for this charge session");
            }
            return;
        }
        boolean finalAttempt = attempt == DECIMAL_PROBE_DELAYS_MS.length - 1;
        if (!decimal.measuredRate && decimal.currentMa < 3000 && !finalAttempt) {
            return;
        }

        Snapshot state = current;
        int minimumRate = minimumDecimalRate(state);
        boolean highPowerOplus = state.oplusProfile == OPLUS_PROFILE_SVOOC
                || state.oplusProfile == OPLUS_PROFILE_UFCS;
        if (highPowerOplus && decimal.rate < minimumRate
                && attempt < DECIMAL_PROBE_DELAYS_MS.length - 2) {
            // ColorOS raises the decimal curve roughly two seconds after the
            // high-power protocol settles. Wait for that finite ramp window
            // instead of locking Xiaomi's ten-second animator to the initial
            // low-current segment.
            return;
        }
        decimal = decimal.withRate(Math.max(decimal.rate, minimumRate));

        synchronized (STATE_LOCK) {
            if (session != decimalSession || decimalPublished
                    || !current.online || current.quickType != 4) {
                return;
            }
            currentDecimal = decimal;
            decimalPublished = true;
        }

        handler.removeMessages(MSG_SOC_DECIMAL);
        Message message = handler.obtainMessage(MSG_SOC_DECIMAL);
        message.arg1 = decimal.digits;
        message.arg2 = decimal.rate;
        handler.sendMessage(message);
        HookLog.info("MiCharge decimal published: digits=" + decimal.digits
                + ", rate=" + decimal.rate + ", oplus=" + decimal.initialCenti
                + "->" + decimal.currentCenti + ", level=" + decimal.baseLevel
                + ", current=" + decimal.currentMa + "mA, measured="
                + decimal.measuredRate + ", profile=" + state.oplusProfile);
    }

    private static RefreshResult refreshState(String reason) {
        synchronized (STATE_LOCK) {
            OplusData data = readOplusSnapshot();
            if (data == null) {
                data = readSysfsFallback();
            }
            if (data == null) {
                return RefreshResult.UNCHANGED;
            }

            int sysfsOnline = readOnlineState();
            boolean online = frameworkPlugged > 0 || sysfsOnline > 0;
            Snapshot next = translate(data, online);
            Snapshot previous = current;
            current = next;
            reportMarkerIfPossible();

            boolean wasDecimalCharge = previous.available && previous.online
                    && previous.quickType == 4;
            boolean isDecimalCharge = next.online && next.quickType == 4;
            boolean startDecimalSession = false;
            int session = decimalSession;
            if (!isDecimalCharge) {
                if (wasDecimalCharge || decimalPublished || currentDecimal.valid) {
                    decimalSession++;
                }
                decimalPublished = false;
                currentDecimal = Decimal.UNAVAILABLE;
                resetDecimalProbeLocked();
            } else if (!wasDecimalCharge) {
                session = ++decimalSession;
                decimalPublished = false;
                currentDecimal = Decimal.UNAVAILABLE;
                resetDecimalProbeLocked();
                startDecimalSession = true;
            }

            boolean changed = !previous.available
                    ? next.online
                    : !previous.sameState(next);
            if (changed) {
                HookLog.info("MiCharge bridge state: online=" + next.online
                        + ", uiIconType=" + data.uiIconType
                        + ", fast=" + data.fastCharge
                        + ", pps=" + data.ppsCharging
                        + ", quick=" + next.quickType
                        + ", power=" + next.powerWatts + "W, source="
                        + data.source + ", trigger=" + reason);
            }
            return new RefreshResult(next, changed, startDecimalSession, session);
        }
    }

    private static Snapshot translate(OplusData data, boolean online) {
        int power = maximum(normalizePower(data.uiPower),
                normalizePower(data.cpaPower), normalizePower(data.ppsPower));
        int quick = 0;
        if (online) {
            if (data.ppsCharging > 0 || data.uiIconType >= 3
                    || (power >= 50
                    && (data.fastCharge > 0 || data.uiIconType > 0))) {
                quick = 4;
            } else if (data.fastCharge > 0 || data.uiIconType > 0) {
                quick = 2;
            }
        }

        if (quick > 0 && power < 10) {
            power = fallbackPowerWatts();
        }
        if (quick == 4 && data.ppsCharging == OPLUS_PROFILE_PPS
                && power < 50) {
            // Newer OnePlus 13 firmware advertises its PPS tier as 55 W even
            // when the live negotiated value exposed by the HAL is 33 W.
            power = 55;
        } else if (quick == 4 && data.ppsCharging == OPLUS_PROFILE_SVOOC
                && power < 50) {
            power = 100;
        } else if (quick == 4 && power < 10) {
            power = 100;
        } else if (quick == 2 && power < 10) {
            power = 18;
        } else if (quick == 0) {
            power = 0;
        }
        return new Snapshot(true, online, quick, Math.min(power, 300),
                data.ppsCharging);
    }

    private static int minimumDecimalRate(Snapshot state) {
        if (state.oplusProfile == OPLUS_PROFILE_SVOOC
                || state.oplusProfile == OPLUS_PROFILE_UFCS) {
            return 60;
        }
        if (state.oplusProfile == OPLUS_PROFILE_PPS) {
            return 20;
        }
        return state.powerWatts >= 80 ? 45
                : state.powerWatts >= 50 ? 30 : 20;
    }

    private static Decimal readOplusDecimal() {
        String pair = readOplusDecimalBinder();
        if (pair == null || pair.trim().isEmpty()) {
            pair = readText(OPLUS_SOC_DECIMAL);
        }
        if (pair == null) {
            return Decimal.UNAVAILABLE;
        }
        String[] parts = pair.trim().split("[,\\s]+");
        if (parts.length < 2) {
            return Decimal.UNAVAILABLE;
        }
        try {
            int initial = Integer.parseInt(parts[0]);
            int now = Integer.parseInt(parts[1]);
            if (initial <= 0 || now <= 0 || initial > 10000 || now > 10000
                    || now < initial) {
                return Decimal.UNAVAILABLE;
            }

            long sampledAt = SystemClock.elapsedRealtime();
            int animationStart = now;
            int rate = 0;
            boolean measuredRate = false;

            // ColorOS treats the HAL pair as the start and end of its next
            // one-second segment. Xiaomi animates one supplied delta for ten
            // seconds, so scaling that segment by ten preserves the same
            // visible percentage-per-second speed.
            if (now > initial) {
                rate = (int) Math.min(500L, (long) (now - initial) * 10L);
                animationStart = initial;
                measuredRate = true;
            }

            synchronized (STATE_LOCK) {
                if (!measuredRate && lastDecimalProbeCenti >= 0
                        && now > lastDecimalProbeCenti
                        && sampledAt > lastDecimalProbeElapsed) {
                    long elapsed = sampledAt - lastDecimalProbeElapsed;
                    long projected = Math.round((now - lastDecimalProbeCenti)
                            * 10000.0d / elapsed);
                    if (projected > 0) {
                        rate = (int) Math.min(500L, projected);
                        measuredRate = true;
                    }
                }
                lastDecimalProbeCenti = now;
                lastDecimalProbeElapsed = sampledAt;
            }

            int level = frameworkLevel;
            if (level < 0 || level > 100) {
                level = readInt(BATTERY + "/capacity", -1);
            }
            int digits = animationStart - level * 100;
            if (digits < 0 || digits > 99) {
                animationStart = now;
                digits = animationStart - level * 100;
            }
            if (level < 0 || level >= 100 || digits < 0 || digits > 99) {
                return Decimal.UNAVAILABLE;
            }

            int currentMa = decimalCurrentMa();
            if (!measuredRate) {
                rate = decimalRate(currentMa, level);
            }
            return new Decimal(true, digits, rate, initial, now, level,
                    currentMa, measuredRate);
        } catch (NumberFormatException ignored) {
            return Decimal.UNAVAILABLE;
        }
    }

    private static void resetDecimalProbeLocked() {
        lastDecimalProbeCenti = -1;
        lastDecimalProbeElapsed = 0L;
    }

    private static String readOplusDecimalBinder() {
        Parcel request = null;
        Parcel reply = null;
        try {
            IBinder binder = getChargerBinder();
            if (binder == null) {
                return null;
            }
            request = Parcel.obtain();
            reply = Parcel.obtain();
            request.writeInterfaceToken(OPLUS_CHARGER_DESCRIPTOR);
            if (!binder.transact(TRANSACTION_GET_SOC_DECIMAL, request, reply, 0)) {
                return null;
            }
            reply.readException();
            return reply.readString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (reply != null) {
                reply.recycle();
            }
            if (request != null) {
                request.recycle();
            }
        }
    }

    private static int decimalCurrentMa() {
        long currentMa = Math.abs((long) readBatteryLogInt("ibat_ma", 0));
        if (currentMa == 0) {
            currentMa = Math.abs((long) readInt(BATTERY + "/current_now", 0));
            if (currentMa > 100000L) {
                currentMa /= 1000L;
            }
        }
        return (int) Math.min(currentMa, Integer.MAX_VALUE);
    }

    private static int decimalRate(int currentMa, int uiSoc) {
        int fcc = readInt(OPLUS_SUBSYSTEM_BATTERY + "/battery_fcc", 0);
        if (fcc <= 0) {
            fcc = readBatteryLogInt("batt_fcc", 4500);
        }
        if (fcc <= 0 || currentMa <= 0 || readBatteryLogInt("mmi_chg", 1) == 0) {
            return 0;
        }

        // Oplus reports thousandths of one percent per second. For a dual-cell
        // OnePlus 13 this integer is also Xiaomi's hundredths delta used by
        // its stock decimal animator.
        long rate = 100000L * currentMa * 2L / (fcc * 3600L);
        int smoothSoc = readBatteryLogInt("smooth_soc", uiSoc);
        if (uiSoc - smoothSoc > 2) {
            rate /= 2L;
        } else if (uiSoc < smoothSoc) {
            rate *= 2L;
        }
        return (int) Math.max(0L, Math.min(500L, rate));
    }

    private static OplusData readOplusSnapshot() {
        Parcel request = null;
        Parcel reply = null;
        try {
            IBinder binder = getChargerBinder();
            if (binder == null) {
                return null;
            }
            request = Parcel.obtain();
            reply = Parcel.obtain();
            request.writeInterfaceToken(OPLUS_CHARGER_DESCRIPTOR);
            request.writeInt(0x1e);
            request.writeString("Update");
            request.writeInt(1);
            if (!binder.transact(TRANSACTION_GET_CHG_CONFIG, request, reply, 0)) {
                chargerBinder = null;
                return null;
            }
            reply.readException();
            String json = reply.readString();
            if (json == null || json.isEmpty()) {
                return null;
            }
            JSONObject root = new JSONObject(json);
            JSONObject update = root.optJSONObject("Update");
            if (update == null) {
                return null;
            }
            OplusData data = new OplusData("oplus-hal");
            data.uiIconType = flexibleInt(update, "uiIconType", 0);
            data.uiPower = flexibleInt(update, "uiPower", 0);
            data.cpaPower = flexibleInt(update, "cpaPower", 0);
            data.ppsCharging = flexibleInt(update, "ppsCharging", 0);
            data.ppsPower = flexibleInt(update, "ppsChargePower", 0);
            data.fastCharge = flexibleInt(update, "fastCharge", 0);
            return data;
        } catch (Throwable error) {
            chargerBinder = null;
            HookLog.once("oplus_charger_snapshot_failed",
                    "Oplus charger snapshot unavailable; using sysfs fallback: " + error);
            return null;
        } finally {
            if (reply != null) {
                reply.recycle();
            }
            if (request != null) {
                request.recycle();
            }
        }
    }

    private static IBinder getChargerBinder() {
        IBinder cached = chargerBinder;
        if (cached != null && cached.isBinderAlive()) {
            return cached;
        }
        try {
            Class<?> serviceManager = XposedHelpers.findClass(
                    "android.os.ServiceManager", null);
            Object value = XposedHelpers.callStaticMethod(serviceManager,
                    "checkService", OPLUS_CHARGER_SERVICE);
            if (value instanceof IBinder) {
                chargerBinder = (IBinder) value;
                return chargerBinder;
            }
        } catch (Throwable error) {
            HookLog.once("oplus_charger_service_lookup_failed",
                    "Oplus charger service lookup failed: " + error);
        }
        return null;
    }

    private static OplusData readSysfsFallback() {
        int rawType = readBatteryLogInt("charge_type", 0);
        if (rawType == 0) {
            rawType = readInt(OPLUS_COMMON + "/protocol_type", 0);
        }
        if (rawType == 0 && readInt(OPLUS_BATTERY + "/ppschg_ing", 0) > 0) {
            rawType = 8;
        }
        if (rawType == 0 && readInt(OPLUS_COMMON + "/ufcs_online", 0) > 0) {
            rawType = 15;
        }
        if (rawType == 0 && readInt(OPLUS_BATTERY + "/voocchg_ing", 0) > 0) {
            rawType = 13;
        }
        int adapterPower = Math.max(readInt(OPLUS_COMMON + "/cpa_power", 0),
                readInt(OPLUS_COMMON + "/adapter_power", 0));
        if (rawType == 0 && adapterPower <= 0 && readOnlineState() < 0) {
            return null;
        }

        OplusData data = new OplusData("sysfs");
        data.ppsCharging = rawType == 8 ? OPLUS_PROFILE_PPS
                : rawType == 14 ? OPLUS_PROFILE_SVOOC
                : rawType == 15 ? OPLUS_PROFILE_UFCS : 0;
        data.fastCharge = rawType >= 6 ? 1 : 0;
        data.uiIconType = rawType == 14 || rawType == 15 ? 3
                : rawType >= 6 ? 1 : 0;
        data.cpaPower = adapterPower;
        if (rawType == 8 && adapterPower <= 0) {
            data.ppsPower = 55;
        }
        return data;
    }

    private static int flexibleInt(JSONObject object, String key, int fallback) {
        Object value = object.opt(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString().trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static int readOnlineState() {
        int usb = readInt(USB_ONLINE, -1);
        int wireless = readInt(WIRELESS_ONLINE, -1);
        if (usb < 0 && wireless < 0) {
            return -1;
        }
        return usb > 0 || wireless > 0 ? 1 : 0;
    }

    private static int fallbackPowerWatts() {
        int power = maximum(normalizePower(readInt(OPLUS_COMMON + "/cpa_power", 0)),
                normalizePower(readInt(OPLUS_COMMON + "/adapter_power", 0)),
                normalizePower(readInt(OPLUS_COMMON + "/ui_power", 0)));
        return power;
    }

    private static int normalizePower(int value) {
        if (value <= 0) {
            return 0;
        }
        if (value > 300) {
            return Math.max(1, (value + 500) / 1000);
        }
        return value;
    }

    private static int maximum(int first, int second, int third) {
        return Math.max(first, Math.max(second, third));
    }

    private static int readBatteryLogInt(String key, int fallback) {
        String head = readText(OPLUS_BATTERY + "/battery_log_head");
        String content = readText(OPLUS_BATTERY + "/battery_log_content");
        if (head == null || content == null) {
            return fallback;
        }
        String[] names = head.split(",");
        String[] values = content.split(",");
        for (int index = 0; index < names.length && index < values.length; index++) {
            if (key.equals(names[index].trim())) {
                try {
                    return Integer.parseInt(values[index].trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static int readInt(String path, int fallback) {
        String value = readText(path);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String readText(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            return reader.readLine();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void reportMarkerIfPossible() {
        Context context = ModuleConfig.systemContext();
        if (context != null && MARKER_REPORTED.compareAndSet(false, true)) {
            DiagnosticMarkers.report(context, "micharge_bridge");
        }
    }

    private static final class OplusData {
        final String source;
        int uiIconType;
        int uiPower;
        int cpaPower;
        int ppsCharging;
        int ppsPower;
        int fastCharge;

        OplusData(String source) {
            this.source = source;
        }
    }

    private static final class Snapshot {
        static final Snapshot UNAVAILABLE = new Snapshot(false, false, 0, 0, 0);

        final boolean available;
        final boolean online;
        final int quickType;
        final int powerWatts;
        final int oplusProfile;

        Snapshot(boolean available, boolean online, int quickType, int powerWatts,
                int oplusProfile) {
            this.available = available;
            this.online = online;
            this.quickType = quickType;
            this.powerWatts = powerWatts;
            this.oplusProfile = oplusProfile;
        }

        boolean sameState(Snapshot other) {
            return online == other.online
                    && quickType == other.quickType
                    && powerWatts == other.powerWatts
                    && oplusProfile == other.oplusProfile;
        }
    }

    private static final class Decimal {
        static final Decimal UNAVAILABLE =
                new Decimal(false, 0, 0, 0, 0, -1, 0, false);

        final boolean valid;
        final int digits;
        final int rate;
        final int initialCenti;
        final int currentCenti;
        final int baseLevel;
        final int currentMa;
        final boolean measuredRate;

        Decimal(boolean valid, int digits, int rate, int initialCenti,
                int currentCenti, int baseLevel, int currentMa,
                boolean measuredRate) {
            this.valid = valid;
            this.digits = digits;
            this.rate = rate;
            this.initialCenti = initialCenti;
            this.currentCenti = currentCenti;
            this.baseLevel = baseLevel;
            this.currentMa = currentMa;
            this.measuredRate = measuredRate;
        }

        Decimal withRate(int newRate) {
            return new Decimal(valid, digits, newRate, initialCenti,
                    currentCenti, baseLevel, currentMa, measuredRate);
        }
    }

    private static final class RefreshResult {
        static final RefreshResult UNCHANGED =
                new RefreshResult(Snapshot.UNAVAILABLE, false, false, 0);

        final Snapshot state;
        final boolean changed;
        final boolean startDecimalSession;
        final int decimalSession;

        RefreshResult(Snapshot state, boolean changed, boolean startDecimalSession,
                int decimalSession) {
            this.state = state;
            this.changed = changed;
            this.startDecimalSession = startDecimalSession;
            this.decimalSession = decimalSession;
        }
    }
}
