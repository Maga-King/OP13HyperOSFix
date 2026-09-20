package local.mio.op13hyperosfix;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;

/** Event-driven game touch rate and wired bypass charging policy. */
final class GameOptimizationHooks {
    static final String ACTION_CONFIG_CHANGED =
            "local.mio.op13hyperosfix.action.GAME_CONFIG_CHANGED";
    static final String ACTION_APPLY_STATE =
            "local.mio.op13hyperosfix.action.GAME_APPLY_STATE";
    static final String EXTRA_TOUCH_RATE = "touch_rate";
    static final String EXTRA_BYPASS = "bypass";

    private static final String MODULE_PACKAGE = "local.mio.op13hyperosfix";
    private static final String BOOTSTRAP_RECEIVER =
            MODULE_PACKAGE + ".RootBootstrapReceiver";
    private static final int MIN_BYPASS_BATTERY_PERCENT = 15;
    private static final long GAME_EXIT_DELAY_MS = 800L;

    private static final AtomicBoolean HOOKS_INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean CONTEXT_ATTACHED = new AtomicBoolean();
    private static final Map<Object, String> VISIBLE_ACTIVITIES =
            new WeakHashMap<>();
    private static final Object FALLBACK_FOCUSED_RECORD = new Object();

    private static Context context;
    private static Handler handler;
    private static boolean interactive = true;
    private static boolean wiredConnected;
    private static int batteryPercent = -1;
    private static int batteryTemperatureC = -1;
    private static int lastTouchRate = -1;
    private static Boolean lastBypass;
    private static final Runnable DELAYED_GAME_EXIT =
            () -> runSafely("delayed_exit", () -> evaluateAndApply(false));

    private GameOptimizationHooks() {
    }

    static void install(ClassLoader loader) {
        if (!HOOKS_INSTALLED.compareAndSet(false, true)) return;
        Class<?> activityRecord = Reflect.findClass(
                loader, "com.android.server.wm.ActivityRecord");
        int visibleHooks = 0;
        int goneHooks = 0;
        if (activityRecord != null) {
            visibleHooks = Reflect.hookNamedMethods(
                    activityRecord, "onWindowsVisible", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            runSafely("activity_visible",
                                    () -> onActivityVisible(param.thisObject));
                        }
                    });
            goneHooks = Reflect.hookNamedMethods(
                    activityRecord, "onWindowsGone", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            runSafely("activity_gone",
                                    () -> onActivityGone(param.thisObject));
                        }
                    });
        }

        if (visibleHooks == 0 || goneHooks == 0) {
            Class<?> service = Reflect.findClass(
                    loader, "com.android.server.wm.ActivityTaskManagerService");
            int fallback = service == null ? 0 : Reflect.hookNamedMethods(
                    service, "setLastResumedActivityUncheckLocked",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object record = param.args != null
                                    && param.args.length > 0
                                    ? param.args[0] : null;
                            Object focusedRecord = record;
                            runSafely("focused_activity",
                                    () -> onFocusedActivity(focusedRecord));
                        }
                    });
            HookLog.info("game foreground fallback hooks=" + fallback);
        }
        HookLog.info("game visible activity hooks=" + visibleHooks
                + "/" + goneHooks);
    }

    static void attachContext(Context source) {
        if (source == null || !CONTEXT_ATTACHED.compareAndSet(false, true)) return;
        runSafely("attach_context", () -> {
            Context application = source.getApplicationContext();
            context = application != null ? application : source;
            handler = Handler.createAsync(Looper.getMainLooper());
            PowerManager power = context.getSystemService(PowerManager.class);
            interactive = power == null || power.isInteractive();

            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(ACTION_CONFIG_CHANGED);
            Intent sticky = context.registerReceiver(
                    STATE_RECEIVER, filter, Context.RECEIVER_EXPORTED);
            if (sticky != null) updateBattery(sticky);
            scheduleEvaluation(true, false);
        });
    }

    static void setInteractive(boolean value) {
        runSafely("interactive_state", () -> {
            synchronized (VISIBLE_ACTIVITIES) {
                if (interactive == value) return;
                interactive = value;
            }
            scheduleEvaluation(false, false);
        });
    }

    private static final BroadcastReceiver STATE_RECEIVER =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    runSafely("state_broadcast", () -> {
                        if (intent == null) return;
                        if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                            boolean changed = updateBattery(intent);
                            if (changed) scheduleEvaluation(false, false);
                        } else if (ACTION_CONFIG_CHANGED.equals(intent.getAction())) {
                            scheduleEvaluation(true, false);
                        }
                    });
                }
            };

    private static boolean updateBattery(Intent intent) {
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        boolean wired = (plugged & (BatteryManager.BATTERY_PLUGGED_AC
                | BatteryManager.BATTERY_PLUGGED_USB)) != 0;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int percent = level >= 0 && scale > 0
                ? Math.round(level * 100f / scale) : -1;
        int temperatureTenths = intent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int temperatureC = temperatureTenths == Integer.MIN_VALUE
                ? -1 : Math.round(temperatureTenths / 10f);
        synchronized (VISIBLE_ACTIVITIES) {
            boolean changed = wiredConnected != wired
                    || (percent >= 0 && percent != batteryPercent)
                    || (temperatureC >= 0
                    && temperatureC != batteryTemperatureC);
            wiredConnected = wired;
            if (percent >= 0) batteryPercent = percent;
            if (temperatureC >= 0) batteryTemperatureC = temperatureC;
            return changed;
        }
    }

    private static void onActivityVisible(Object record) {
        String packageName = packageName(record);
        if (packageName == null) return;
        synchronized (VISIBLE_ACTIVITIES) {
            VISIBLE_ACTIVITIES.put(record, packageName);
        }
        scheduleEvaluation(false, false);
    }

    private static void onActivityGone(Object record) {
        synchronized (VISIBLE_ACTIVITIES) {
            VISIBLE_ACTIVITIES.remove(record);
        }
        scheduleEvaluation(false, true);
    }

    private static void onFocusedActivity(Object record) {
        String packageName = packageName(record);
        synchronized (VISIBLE_ACTIVITIES) {
            VISIBLE_ACTIVITIES.clear();
            if (packageName != null) {
                VISIBLE_ACTIVITIES.put(FALLBACK_FOCUSED_RECORD, packageName);
            }
        }
        scheduleEvaluation(false, packageName == null);
    }

    private static String packageName(Object record) {
        if (record == null) return null;
        Object value = Reflect.get(record, "packageName");
        if (!(value instanceof String)) return null;
        String packageName = ((String) value).trim();
        return packageName.isEmpty() ? null : packageName;
    }

    private static void scheduleEvaluation(boolean force, boolean delayedExit) {
        Handler target = handler;
        if (target == null) return;
        target.removeCallbacks(DELAYED_GAME_EXIT);
        if (delayedExit && !force) {
            target.postDelayed(DELAYED_GAME_EXIT, GAME_EXIT_DELAY_MS);
        } else {
            target.post(() -> runSafely("evaluate", () -> evaluateAndApply(force)));
        }
    }

    private static void runSafely(String stage, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable error) {
            HookLog.once("game_optimization_" + stage,
                    "game optimization disabled for " + stage + ": " + error);
        }
    }

    private static void evaluateAndApply(boolean force) {
        Context currentContext = context;
        if (currentContext == null) return;
        int dailyRate = RootInstaller.sanitizeTouchSamplingRate(
                ModuleConfig.getInt(currentContext,
                        ModuleConfig.TOUCH_SAMPLING_RATE, 120));
        int gameRate = RootInstaller.sanitizeTouchSamplingRate(
                ModuleConfig.getInt(currentContext,
                        ModuleConfig.GAME_TOUCH_RATE, 240));
        boolean gameTouchEnabled = ModuleConfig.isEnabled(
                currentContext, ModuleConfig.GAME_TOUCH_ENABLED, false);
        boolean bypassEnabled = ModuleConfig.isEnabled(
                currentContext, ModuleConfig.GAME_BYPASS_ENABLED, false);
        int bypassEntryTemperature = clamp(ModuleConfig.getInt(currentContext,
                ModuleConfig.GAME_BYPASS_ENTRY_TEMP, 42), 30, 49);
        int bypassExitTemperature = bypassEntryTemperature - 4;
        Set<String> gamePackages = parsePackages(ModuleConfig.getString(
                currentContext, ModuleConfig.GAME_PACKAGES, ""));

        final boolean screenOn;
        final boolean inSelectedGame;
        final boolean wired;
        final int percent;
        final int temperatureC;
        synchronized (VISIBLE_ACTIVITIES) {
            screenOn = interactive;
            boolean visible = false;
            for (String packageName : VISIBLE_ACTIVITIES.values()) {
                if (gamePackages.contains(packageName)) {
                    visible = true;
                    break;
                }
            }
            inSelectedGame = screenOn && visible;
            wired = wiredConnected;
            percent = batteryPercent;
            temperatureC = batteryTemperatureC;
        }

        int targetRate;
        if (!screenOn && (dailyRate > 120 || gameTouchEnabled)) {
            targetRate = 70;
        } else if (inSelectedGame && gameTouchEnabled) {
            targetRate = gameRate;
        } else {
            targetRate = dailyRate;
        }
        boolean bypassWasActive;
        synchronized (VISIBLE_ACTIVITIES) {
            bypassWasActive = Boolean.TRUE.equals(lastBypass);
        }
        boolean temperatureAllowsBypass = temperatureC >= 0
                && (bypassWasActive
                ? temperatureC > bypassExitTemperature
                : temperatureC >= bypassEntryTemperature);
        boolean targetBypass = inSelectedGame
                && bypassEnabled
                && wired
                && percent >= MIN_BYPASS_BATTERY_PERCENT
                && temperatureAllowsBypass;
        synchronized (VISIBLE_ACTIVITIES) {
            if (!force && lastTouchRate == targetRate
                    && lastBypass != null && lastBypass == targetBypass) {
                return;
            }
            lastTouchRate = targetRate;
            lastBypass = targetBypass;
        }
        sendState(currentContext, targetRate, targetBypass);
    }

    private static Set<String> parsePackages(String raw) {
        Set<String> packages = new HashSet<>();
        if (raw == null || raw.isEmpty()) return packages;
        for (String item : raw.split(",")) {
            String packageName = item.trim();
            if (packageName.matches("[A-Za-z0-9._]+")) {
                packages.add(packageName);
            }
        }
        return packages;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void sendState(Context source, int touchRate, boolean bypass) {
        try {
            Intent intent = new Intent(ACTION_APPLY_STATE)
                    .setComponent(new ComponentName(
                            MODULE_PACKAGE, BOOTSTRAP_RECEIVER))
                    .putExtra(EXTRA_TOUCH_RATE, touchRate)
                    .putExtra(EXTRA_BYPASS, bypass)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                            | Intent.FLAG_RECEIVER_FOREGROUND);
            source.sendBroadcast(intent);
        } catch (Throwable error) {
            HookLog.once("game_state_broadcast",
                    "game optimization broadcast failed: " + error);
        }
    }
}
