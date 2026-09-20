package local.mio.op13hyperosfix;

import android.content.Context;
import android.os.BatteryManager;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Repairs OnePlus battery time sources inside Security Center only. */
final class SecurityCenterTimeHooks {
    private static final String TAG = "OnePlus13BatteryTime";
    private static final String BATTERY = "/sys/class/power_supply/battery";
    private static final long MINUTE_MS = 60_000L;
    private static final long MIN_ESTIMATE_MS = 5L * MINUTE_MS;
    private static final long MAX_REMAINING_SECONDS = 30L * 24L * 60L * 60L;
    private static final long MAX_HISTORY_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long ESTIMATE_CACHE_MS = 750L;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean CHARGE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean DISCHARGE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean USAGE_LOGGED = new AtomicBoolean(false);

    private static long estimateCacheAt;
    private static boolean estimateCacheCharging;
    private static long estimateCacheValue;
    private static volatile long historyChargeEstimateMs;
    private static volatile long historyDischargeEstimateMs;

    private SecurityCenterTimeHooks() {
    }

    static void install(ClassLoader classLoader) {
        if (!INSTALLED.compareAndSet(false, true)) return;

        boolean chargeHooked = hookChargeTimeHelper(classLoader);
        boolean dischargeHooked = hookDischargeTimeHelper(classLoader);
        hookUsageTime(classLoader);
        if (!chargeHooked || !dischargeHooked) {
            hookTitleFormatterFallback(
                    classLoader, chargeHooked, dischargeHooked);
        }
        XposedBridge.log(TAG + ": installed charge=" + chargeHooked
                + " discharge=" + dischargeHooked);
    }

    private static boolean hookChargeTimeHelper(ClassLoader classLoader) {
        Class<?> helper = XposedHelpers.findClassIfExists(
                "com.miui.powercenter.batteryhistory.a", classLoader);
        if (helper == null) return false;

        Method target = null;
        for (Method method : helper.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && parameters.length == 2
                    && Context.class.isAssignableFrom(parameters[0])
                    && List.class.isAssignableFrom(parameters[1])
                    && !method.getReturnType().isPrimitive()) {
                if (target != null) return false;
                target = method;
            }
        }
        if (target == null) return false;

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object detail = param.getResult();
                if (detail == null) return;
                Context context = param.args != null
                        && param.args.length > 0
                        && param.args[0] instanceof Context
                        ? (Context) param.args[0] : null;
                List<?> history = param.args != null
                        && param.args.length > 1
                        && param.args[1] instanceof List
                        ? (List<?>) param.args[1] : null;
                updateHistoryEstimates(history);
                long replacement = readRemainingMillis(true, context);
                if (replacement <= 0) return;
                try {
                    Field field = findPrimaryLongField(detail.getClass());
                    if (field == null) return;
                    long original = field.getLong(detail);
                    field.setLong(detail, replacement);
                    logEstimateOnce(true, original, replacement);
                } catch (Throwable throwable) {
                    XposedBridge.log(TAG
                            + ": charge result adaptation failed");
                    XposedBridge.log(throwable);
                }
            }
        });
        return true;
    }

    private static boolean hookDischargeTimeHelper(ClassLoader classLoader) {
        Class<?> helper = XposedHelpers.findClassIfExists(
                "com.miui.powercenter.batteryhistory.k", classLoader);
        if (helper == null) return false;

        Method target = null;
        for (Method method : helper.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == long.class
                    && parameters.length == 1
                    && Context.class.isAssignableFrom(parameters[0])) {
                if (target != null) return false;
                target = method;
            }
        }
        if (target == null) return false;

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context context = param.args != null
                        && param.args.length > 0
                        && param.args[0] instanceof Context
                        ? (Context) param.args[0] : null;
                long replacement = readRemainingMillis(false, context);
                if (replacement <= 0) return;
                long original = ((Number) param.getResult()).longValue();
                param.setResult(replacement);
                logEstimateOnce(false, original, replacement);
            }
        });
        return true;
    }

    private static void hookUsageTime(ClassLoader classLoader) {
        Class<?> bean = XposedHelpers.findClassIfExists(
                "com.miui.powercenter.bean.PowerLevelBean", classLoader);
        if (bean == null) {
            XposedBridge.log(TAG + ": PowerLevelBean unavailable");
            return;
        }
        XposedHelpers.findAndHookMethod(bean, "setUsageRealTime",
                long.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length != 1) {
                            return;
                        }
                        long original = ((Number) param.args[0]).longValue();
                        long historyMs = historyDurationMillis(
                                param.thisObject);
                        if (historyMs < MINUTE_MS
                                || historyMs > MAX_HISTORY_MS) {
                            return;
                        }
                        long historyUs = historyMs * 1000L;
                        if (historyUs <= original) return;
                        param.args[0] = historyUs;
                        if (USAGE_LOGGED.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + ": usage time "
                                    + original + "us -> "
                                    + historyUs + "us");
                        }
                    }
                });
        XposedBridge.hookAllMethods(bean, "setBatteryList",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args != null
                                && param.args.length == 1
                                && param.args[0] instanceof List) {
                            updateHistoryEstimates((List<?>) param.args[0]);
                        }
                    }
                });
    }

    private static void hookTitleFormatterFallback(
            ClassLoader classLoader,
            boolean chargeHooked,
            boolean dischargeHooked) {
        Class<?> holder = XposedHelpers.findClassIfExists(
                "com.miui.powercenter.batteryhistory.w0", classLoader);
        if (holder == null) return;

        Method target = null;
        for (Method method : holder.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers())
                    && method.getReturnType() == void.class
                    && parameters.length == 1
                    && parameters[0] == long.class) {
                if (target != null) return;
                target = method;
            }
        }
        if (target == null) return;

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                String status = readLine(BATTERY + "/status");
                boolean charging = isCharging(status);
                if ((charging && chargeHooked)
                        || (!charging && dischargeHooked)) {
                    return;
                }
                long replacement = readRemainingMillis(charging, null);
                if (replacement <= 0) return;
                long original = ((Number) param.args[0]).longValue();
                param.args[0] = replacement;
                logEstimateOnce(charging, original, replacement);
            }
        });
        XposedBridge.log(TAG + ": title formatter fallback active");
    }

    private static Field findPrimaryLongField(Class<?> resultClass) {
        Field[] fields = resultClass.getDeclaredFields();
        Arrays.sort(fields, Comparator.comparing(Field::getName));
        for (Field field : fields) {
            if (!Modifier.isStatic(field.getModifiers())
                    && field.getType() == long.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static long historyDurationMillis(Object bean) {
        try {
            Object value = XposedHelpers.callMethod(bean, "getBatteryList");
            if (!(value instanceof List)) return 0L;
            List<?> history = (List<?>) value;
            long segmentStart = -1L;
            long previous = -1L;
            long last = -1L;
            for (Object item : history) {
                if (item == null) continue;
                Object timeValue = XposedHelpers.callMethod(item, "getTime");
                if (!(timeValue instanceof Number)) continue;
                long time = ((Number) timeValue).longValue();
                if (time < 0) continue;
                if (segmentStart < 0
                        || (previous >= 0 && previous - time > 1_000_000L)) {
                    segmentStart = time;
                }
                previous = time;
                last = time;
            }
            return segmentStart >= 0 && last >= segmentStart
                    ? last - segmentStart : 0L;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": history duration unavailable");
            XposedBridge.log(throwable);
            return 0L;
        }
    }

    private static synchronized long readRemainingMillis(
            boolean charging, Context context) {
        long now = SystemClock.elapsedRealtime();
        if (charging == estimateCacheCharging
                && now - estimateCacheAt >= 0
                && now - estimateCacheAt < ESTIMATE_CACHE_MS) {
            return estimateCacheValue;
        }

        String status = readLine(BATTERY + "/status");
        int capacity = (int) readLong(BATTERY + "/capacity", -1L);
        long seconds = 0L;
        if (charging && isCharging(status)) {
            seconds = readLong(BATTERY + "/time_to_full_now", 0L);
            if (!isCredibleNodeEstimate(true, seconds, capacity)) {
                seconds = readLong(BATTERY + "/time_to_full_avg", 0L);
            }
        } else if (!charging && isDischarging(status)) {
            seconds = readLong(BATTERY + "/time_to_empty_avg", 0L);
        }
        long value = isCredibleNodeEstimate(charging, seconds, capacity)
                ? seconds * 1000L : 0L;
        if (value <= 0L) {
            value = charging
                    ? historyChargeEstimateMs : historyDischargeEstimateMs;
        }
        if (value <= 0L) {
            value = platformEstimateMillis(charging, context);
        }
        if (value <= 0L) {
            value = electricalEstimateMillis(charging, capacity);
        }
        value = sanitizeEstimateMillis(value);
        estimateCacheCharging = charging;
        estimateCacheValue = value;
        estimateCacheAt = now;
        return value;
    }

    private static void updateHistoryEstimates(List<?> history) {
        if (history == null || history.size() < 2) return;
        try {
            HistoryPoint[] points = new HistoryPoint[history.size()];
            int count = 0;
            for (Object item : history) {
                if (item == null) continue;
                Object timeValue = XposedHelpers.callMethod(item, "getTime");
                if (!(timeValue instanceof Number)) continue;
                Field levelField = XposedHelpers.findField(
                        item.getClass(), "batteryLevel");
                Field chargingField = XposedHelpers.findField(
                        item.getClass(), "charging");
                int level = ((Number) levelField.get(item)).intValue();
                long time = ((Number) timeValue).longValue();
                boolean charging = chargingField.getBoolean(item);
                if (time < 0L || level < 0 || level > 100) continue;
                points[count++] = new HistoryPoint(time, level, charging);
            }
            if (count < 2) return;
            Arrays.sort(points, 0, count,
                    Comparator.comparingLong(point -> point.time));

            HistoryPoint last = points[count - 1];
            HistoryPoint first = last;
            for (int index = count - 2; index >= 0; index--) {
                HistoryPoint candidate = points[index];
                if (candidate.charging != last.charging) break;
                if (first.time - candidate.time > MAX_HISTORY_MS) break;
                first = candidate;
            }
            long duration = last.time - first.time;
            int delta = last.level - first.level;
            if (duration < MIN_ESTIMATE_MS || duration > MAX_HISTORY_MS) {
                return;
            }

            long estimate = 0L;
            if (last.charging && delta >= 1 && last.level < 100) {
                estimate = duration * (100L - last.level) / delta;
                historyChargeEstimateMs = sanitizeEstimateMillis(estimate);
            } else if (!last.charging && delta <= -1 && last.level > 0) {
                estimate = duration * last.level / -delta;
                historyDischargeEstimateMs = sanitizeEstimateMillis(estimate);
            }
            if (estimate > 0L) {
                synchronized (SecurityCenterTimeHooks.class) {
                    estimateCacheAt = 0L;
                }
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": history estimate unavailable");
            XposedBridge.log(throwable);
        }
    }

    private static long platformEstimateMillis(
            boolean charging, Context context) {
        if (context == null) return 0L;
        try {
            if (charging) {
                BatteryManager manager = context.getSystemService(
                        BatteryManager.class);
                if (manager != null) {
                    return sanitizePlatformEstimate(true,
                            manager.computeChargeTimeRemaining());
                }
            } else {
                Object manager = context.getSystemService("batterystats");
                if (manager != null) {
                    Method method = manager.getClass().getMethod(
                            "computeBatteryTimeRemaining");
                    Object value = method.invoke(manager);
                    if (value instanceof Number) {
                        return sanitizePlatformEstimate(false,
                                ((Number) value).longValue());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static long electricalEstimateMillis(
            boolean charging, int capacity) {
        long fullMah = normalizeCapacityMah(readLong(
                BATTERY + "/charge_full", 0L));
        if (fullMah <= 0L) {
            fullMah = normalizeCapacityMah(readLong(
                    "/sys/class/oplus_chg/battery/battery_fcc", 0L));
        }
        long remainingMah = normalizeCapacityMah(readLong(
                BATTERY + "/charge_counter", 0L));
        if (remainingMah <= 0L) {
            remainingMah = normalizeCapacityMah(readLong(
                    "/sys/class/oplus_chg/battery/battery_rm", 0L));
        }
        if (fullMah <= 0L || remainingMah <= 0L) {
            if (capacity < 0 || capacity > 100) return 0L;
            fullMah = 5000L;
            remainingMah = fullMah * capacity / 100L;
        }
        remainingMah = Math.min(remainingMah, fullMah);

        long currentMa = Math.abs(readLong(BATTERY + "/current_now", 0L));
        if (currentMa > 100_000L) currentMa /= 1000L;
        if (charging) {
            if (currentMa < 300L || currentMa > 12_000L) currentMa = 1800L;
            return sanitizeEstimateMillis(
                    (fullMah - remainingMah) * 3_600_000L / currentMa);
        }
        if (currentMa < 80L || currentMa > 8_000L) currentMa = 400L;
        return sanitizeEstimateMillis(
                remainingMah * 3_600_000L / currentMa);
    }

    private static long normalizeCapacityMah(long value) {
        return value > 100_000L ? value / 1000L : value;
    }

    private static boolean isCredibleNodeEstimate(
            boolean charging, long seconds, int capacity) {
        if (!isSaneRemaining(seconds) || seconds * 1000L < MIN_ESTIMATE_MS) {
            return false;
        }
        // The OnePlus base publishes 3600 as an unsupported-value placeholder.
        if (!charging && seconds == 3600L) return false;
        // A sub-ten-minute full-charge estimate at a low SOC is also a stub.
        return !charging || capacity < 0 || capacity >= 90 || seconds >= 600L;
    }

    private static long sanitizeEstimateMillis(long value) {
        return value >= MIN_ESTIMATE_MS && value <= MAX_HISTORY_MS
                ? value : 0L;
    }

    private static long sanitizePlatformEstimate(
            boolean charging, long value) {
        // This base also surfaces the unsupported discharge value as exactly 1 h.
        if (!charging && value == 3_600_000L) return 0L;
        return sanitizeEstimateMillis(value);
    }

    private static final class HistoryPoint {
        final long time;
        final int level;
        final boolean charging;

        HistoryPoint(long time, int level, boolean charging) {
            this.time = time;
            this.level = level;
            this.charging = charging;
        }
    }

    private static boolean isCharging(String status) {
        return status != null && "charging".equalsIgnoreCase(status.trim());
    }

    private static boolean isDischarging(String status) {
        if (status == null) return false;
        String value = status.trim();
        return "discharging".equalsIgnoreCase(value)
                || "not charging".equalsIgnoreCase(value);
    }

    private static boolean isSaneRemaining(long seconds) {
        return seconds > 0L && seconds <= MAX_REMAINING_SECONDS;
    }

    private static long readLong(String path, long fallback) {
        String value = readLine(path);
        if (value == null) return fallback;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String readLine(String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) return null;
            try (BufferedReader reader = new BufferedReader(
                    new FileReader(file))) {
                return reader.readLine();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logEstimateOnce(
            boolean charging, long original, long replacement) {
        AtomicBoolean guard = charging ? CHARGE_LOGGED : DISCHARGE_LOGGED;
        if (guard.compareAndSet(false, true)) {
            XposedBridge.log(TAG + (charging ? ": charge " : ": discharge ")
                    + original + "ms -> " + replacement + "ms");
        }
    }
}
