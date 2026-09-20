package local.mio.op13hyperosfix;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class SystemServerHooks {
    private static final int ALERT_SLIDER_SCAN_CODE = 61;
    private static final int ALERT_SLIDER_KEY_CODE = KeyEvent.KEYCODE_F3;
    private static final int F4_SCAN_CODE = 62;
    private static final int F4_KEY_CODE = KeyEvent.KEYCODE_F4;
    private static final int FOD_BRIDGE_SCAN_CODE = 63;
    private static final int FOD_BRIDGE_KEY_CODE = KeyEvent.KEYCODE_F5;
    private static final int AOD_SINGLE_TAP_KEY_CODE = 354;
    private static final long DOUBLE_TAP_WINDOW_MS = 280L;
    private static final long DOUBLE_TAP_COOLDOWN_MS = 500L;
    private static final long ROOT_BOOTSTRAP_DELAY_MS = 12_000L;
    private static final long ROOT_BOOTSTRAP_FINAL_DELAY_MS = 40_000L;
    private static final String MODULE_PACKAGE = "local.mio.op13hyperosfix";
    private static final String BOOTSTRAP_RECEIVER = MODULE_PACKAGE + ".RootBootstrapReceiver";
    private static final String BOOTSTRAP_ACTION =
            MODULE_PACKAGE + ".action.LSPOSED_BOOTSTRAP";
    private static final String EXTRA_SHOW_ROOT_ERROR = "show_root_error";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String IFAA_PACKAGE = "org.ifaa.aidl.manager";
    private static final String ALERT_SLIDER_DEVICE = "hall_tri_state_key";
    private static final String ALERT_SLIDER_STATE_PATH = "/proc/tristatekey/tri_state";
    private static final String FOD_BRIDGE_DEVICE = "op13_fod_bridge";
    private static final String FINGERPRINT_DOWN_ACTION =
            "com.sysuifpfix.action.FINGERPRINT_DOWN";
    private static final String FINGERPRINT_UP_ACTION =
            "com.sysuifpfix.action.FINGERPRINT_UP";

    private static final Set<Object> STEP_FIXED =
            Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    private static final WeakHashMap<Object, F4State> F4_STATES = new WeakHashMap<>();
    private static final AtomicBoolean ROOT_BOOTSTRAP_SCHEDULED = new AtomicBoolean();

    private SystemServerHooks() {
    }

    static void install(ClassLoader loader) {
        ManageAllNotificationsHooks.installFramework(loader, "system_server");
        FeatureFlagHooks.forceTrue(loader, Set.of("support_steps_provider"), "system_server");
        hookIfaaFingerprintAccess(loader);
        hookStepCounter(loader);
        MiChargeFrameworkBridge.install(loader);
        GameOptimizationHooks.install(loader);
        hookPhoneWindowManager(loader);
        HookLog.info("system_server hooks installed");
    }

    private static void hookIfaaFingerprintAccess(ClassLoader loader) {
        Class<?> service = Reflect.findClass(loader,
                "com.android.server.biometrics.sensors.fingerprint.FingerprintService");
        if (service == null) {
            HookLog.once("ifaa_fingerprint_service_missing",
                    "FingerprintService missing; IFAA enrollment access skipped");
            return;
        }

        int hooks = Reflect.hookNamedMethods(service, "canUseFingerprint",
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!hasPackageArgument(param.args, IFAA_PACKAGE)) {
                            return;
                        }
                        int callingUid = Binder.getCallingUid();
                        if (!uidOwnsPackage(param.thisObject, callingUid, IFAA_PACKAGE)) {
                            return;
                        }
                        param.setResult(true);
                        HookLog.once("ifaa_fingerprint_access_granted",
                                "IFAA fingerprint enrollment access granted for uid="
                                        + callingUid);
                    }
                });
        if (hooks == 0) {
            HookLog.once("ifaa_can_use_fingerprint_missing",
                    "canUseFingerprint entry missing; IFAA enrollment access skipped");
        } else {
            HookLog.info("IFAA fingerprint permission hooks=" + hooks);
        }
    }

    private static boolean hasPackageArgument(Object[] args, String packageName) {
        if (args == null) {
            return false;
        }
        for (Object arg : args) {
            if (packageName.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static boolean uidOwnsPackage(Object service, int uid, String packageName) {
        try {
            Context context = ModuleConfig.systemContext();
            if (context == null) {
                Object value = Reflect.call(service, "getContext");
                if (value instanceof Context) {
                    context = (Context) value;
                }
            }
            if (context == null) {
                return false;
            }
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null) {
                return false;
            }
            for (String candidate : packages) {
                if (packageName.equals(candidate)) {
                    return true;
                }
            }
        } catch (Throwable error) {
            HookLog.once("ifaa_uid_validation_failed",
                    "IFAA caller UID validation failed: " + error);
        }
        return false;
    }

    private static void hookStepCounter(ClassLoader loader) {
        Class<?> service = Reflect.findClass(loader,
                "com.miui.server.stepcounter.StepCounterManagerService");
        if (service == null) {
            HookLog.once("step_class_missing", "step counter service missing; step fix skipped");
            return;
        }
        XposedBridge.hookAllConstructors(service, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object instance = param.thisObject;
                if (!STEP_FIXED.add(instance)) {
                    return;
                }
                try {
                    SensorManager manager = (SensorManager) Reflect.get(instance, "mSensorManager");
                    if (manager == null) {
                        HookLog.once("step_manager_missing", "step SensorManager missing");
                        return;
                    }
                    Sensor sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR, false);
                    if (sensor == null) {
                        HookLog.once("step_sensor_missing", "non-wakeup step detector missing");
                        return;
                    }
                    Reflect.set(instance, sensor, "mSensor");
                    HookLog.info("step detector fixed: type=" + sensor.getType()
                            + ", wakeUp=" + sensor.isWakeUpSensor()
                            + ", name=" + sensor.getName());
                } catch (Throwable error) {
                    HookLog.error("step detector fix failed", error);
                }
            }
        });
    }

    private static void hookPhoneWindowManager(ClassLoader loader) {
        Class<?> pwmClass = Reflect.findClass(loader, "com.android.server.policy.PhoneWindowManager");
        if (pwmClass == null) {
            HookLog.once("pwm_missing", "PhoneWindowManager missing; F4 fix skipped");
            return;
        }

        int interceptHooks = Reflect.hookNamedMethods(pwmClass, "interceptKeyBeforeQueueing",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        KeyEvent event = findKeyEvent(param.args);
                        if (event == null) {
                            return;
                        }
                        if (isAlertSliderEvent(event)) {
                            stateFor(param.thisObject).handleAlertSlider(event);
                            param.setResult(0);
                            return;
                        }
                        if (isFodBridgeEvent(event)) {
                            dispatchFodEvent(param.thisObject, event);
                            param.setResult(0);
                            return;
                        }
                        if (!isTouchPanelF4(event)) {
                            return;
                        }
                        F4State state = stateFor(param.thisObject);
                        state.handle(event);
                        param.setResult(0);
                    }
                });

        Reflect.hookNamedMethods(pwmClass, "systemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                F4State state = stateFor(param.thisObject);
                state.initializeAlertSlider();
                state.initializePocketGate();
                if (!state.isInteractive()) {
                    state.pocketGate.register();
                }
                initializeModuleRuntime(param.thisObject, loader);
                scheduleRootBootstrap(param.thisObject, state.handler);
            }
        });

        Reflect.hookNamedMethods(pwmClass, "startedGoingToSleep", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isDefaultDisplayGroup(param.args)) {
                    F4State state = stateFor(param.thisObject);
                    state.pocketGate.register();
                    GameOptimizationHooks.setInteractive(false);
                }
            }
        });

        Reflect.hookNamedMethods(pwmClass, "finishedWakingUp", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (isDefaultDisplayGroup(param.args)) {
                    F4State state = stateFor(param.thisObject);
                    state.pocketGate.unregister();
                    GameOptimizationHooks.setInteractive(true);
                }
            }
        });

        HookLog.info("F4 input-policy hooks=" + interceptHooks);
    }

    private static void initializeModuleRuntime(Object pwm, ClassLoader loader) {
        Object contextObject = Reflect.get(pwm, "mContext");
        if (!(contextObject instanceof Context)) {
            HookLog.once("runtime_context_missing",
                    "PhoneWindowManager context missing; module runtime not initialized");
            return;
        }
        ModuleConfig.attachSystemContext((Context) contextObject);
        GameOptimizationHooks.attachContext((Context) contextObject);
        DiagnosticMarkers.report((Context) contextObject, "system_server");
        CorePatchHooks.installIfEnabled(loader);
    }

    private static void scheduleRootBootstrap(Object pwm, Handler handler) {
        if (!ROOT_BOOTSTRAP_SCHEDULED.compareAndSet(false, true)) {
            return;
        }
        Object contextObject = Reflect.get(pwm, "mContext");
        if (!(contextObject instanceof Context)) {
            ROOT_BOOTSTRAP_SCHEDULED.set(false);
            HookLog.once("bootstrap_context_missing",
                    "PhoneWindowManager context missing; ROOT bootstrap skipped");
            return;
        }
        Context context = (Context) contextObject;
        handler.postDelayed(() -> {
            // systemReady can run before the module package receiver is ready. Re-report
            // alongside the already scheduled bootstrap, without adding a timer or loop.
            DiagnosticMarkers.report(context, "system_server");
            DiagnosticMarkers.report(context, "haptics_framework");
            sendRootBootstrap(context, false);
        }, ROOT_BOOTSTRAP_DELAY_MS);
        // KernelSU may expose su after Android reports boot completion. This one-shot
        // retry avoids a false warning without leaving a timer or polling loop alive.
        handler.postDelayed(() -> sendRootBootstrap(context, true),
                ROOT_BOOTSTRAP_FINAL_DELAY_MS);
    }

    private static void sendRootBootstrap(Context context, boolean showRootError) {
        try {
            Intent intent = new Intent(BOOTSTRAP_ACTION)
                    .setComponent(new ComponentName(MODULE_PACKAGE, BOOTSTRAP_RECEIVER))
                    .putExtra(EXTRA_SHOW_ROOT_ERROR, showRootError)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                            | Intent.FLAG_RECEIVER_FOREGROUND);
            context.sendBroadcast(intent);
            HookLog.info("ROOT bootstrap broadcast sent; final=" + showRootError);
        } catch (Throwable error) {
            HookLog.error("ROOT bootstrap broadcast failed", error);
        }
    }

    private static synchronized F4State stateFor(Object pwm) {
        F4State state = F4_STATES.get(pwm);
        if (state == null) {
            state = new F4State(pwm);
            F4_STATES.put(pwm, state);
        }
        return state;
    }

    private static KeyEvent findKeyEvent(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof KeyEvent) {
                return (KeyEvent) arg;
            }
        }
        return null;
    }

    private static boolean isTouchPanelF4(KeyEvent event) {
        return event.getScanCode() == F4_SCAN_CODE || event.getKeyCode() == F4_KEY_CODE;
    }

    private static boolean isAlertSliderEvent(KeyEvent event) {
        if (event.getScanCode() != ALERT_SLIDER_SCAN_CODE
                && event.getKeyCode() != ALERT_SLIDER_KEY_CODE) {
            return false;
        }
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        if (device != null && device.getName() != null
                && device.getName().contains(ALERT_SLIDER_DEVICE)) {
            return true;
        }
        // Some OS4 builds cannot resolve this virtual input device in the policy callback.
        // scan 61 remains unambiguous; the cached tri_state change prevents keyboard F3 hits.
        return event.getScanCode() == ALERT_SLIDER_SCAN_CODE;
    }

    private static boolean isFodBridgeEvent(KeyEvent event) {
        if (event.getScanCode() != FOD_BRIDGE_SCAN_CODE
                && event.getKeyCode() != FOD_BRIDGE_KEY_CODE) {
            return false;
        }
        InputDevice device = InputDevice.getDevice(event.getDeviceId());
        return device != null && FOD_BRIDGE_DEVICE.equals(device.getName());
    }

    @SuppressLint("MissingPermission")
    private static void dispatchFodEvent(Object pwm, KeyEvent event) {
        if ((event.getAction() != KeyEvent.ACTION_DOWN
                && event.getAction() != KeyEvent.ACTION_UP)
                || event.getRepeatCount() != 0) {
            return;
        }
        Object contextObject = Reflect.get(pwm, "mContext");
        if (!(contextObject instanceof Context)) {
            HookLog.once("fod_bridge_context_missing",
                    "PhoneWindowManager context missing; FOD event skipped");
            return;
        }
        try {
            String action = event.getAction() == KeyEvent.ACTION_DOWN
                    ? FINGERPRINT_DOWN_ACTION : FINGERPRINT_UP_ACTION;
            Intent intent = new Intent(action)
                    .setPackage(SYSTEMUI_PACKAGE)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            ((Context) contextObject).sendBroadcastAsUser(intent,
                    android.os.Process.myUserHandle(),
                    "android.permission.DEVICE_POWER");
        } catch (Throwable error) {
            HookLog.error("FOD bridge dispatch failed", error);
        }
    }

    private static boolean isDefaultDisplayGroup(Object[] args) {
        return args.length == 0 || !(args[0] instanceof Integer) || ((Integer) args[0]) == 0;
    }

    private static final class F4State {
        final Object pwm;
        final Handler handler;
        final PowerManager powerManager;
        final PocketGate pocketGate;
        final Context context;
        final AudioManager audioManager;
        Runnable pendingSingle;
        Runnable pendingAlertSlider;
        long pendingDeadline;
        long cooldownUntil;
        int lastAlertSliderPosition = -1;

        F4State(Object pwm) {
            this.pwm = pwm;
            Object handlerObject = Reflect.get(pwm, "mHandler");
            this.handler = handlerObject instanceof Handler ? (Handler) handlerObject : new Handler();
            Object power = Reflect.get(pwm, "mPowerManager");
            this.powerManager = power instanceof PowerManager ? (PowerManager) power : null;
            Object contextObject = Reflect.get(pwm, "mContext");
            this.context = contextObject instanceof Context ? (Context) contextObject : null;
            Object audio = context == null ? null : context.getSystemService(Context.AUDIO_SERVICE);
            this.audioManager = audio instanceof AudioManager ? (AudioManager) audio : null;
            this.pocketGate = new PocketGate(pwm, handler);
        }

        void initializePocketGate() {
            pocketGate.initialize();
        }

        void initializeAlertSlider() {
            int position = readAlertSliderPosition();
            if (position >= 1 && position <= 3) {
                synchronized (this) {
                    lastAlertSliderPosition = position;
                }
                HookLog.info("alert slider initialized at position=" + position);
            }
        }

        boolean isInteractive() {
            return powerManager != null && powerManager.isInteractive();
        }

        void handleAlertSlider(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
                return;
            }
            InputDevice device = InputDevice.getDevice(event.getDeviceId());
            HookLog.info("alert slider key down: scan=" + event.getScanCode()
                    + ", keyCode=" + event.getKeyCode()
                    + ", deviceId=" + event.getDeviceId()
                    + ", device=" + (device == null ? "unresolved" : device.getName()));
            synchronized (this) {
                if (pendingAlertSlider != null) {
                    handler.removeCallbacks(pendingAlertSlider);
                }
                pendingAlertSlider = () -> {
                    synchronized (F4State.this) {
                        pendingAlertSlider = null;
                    }
                    applyAlertSliderPosition();
                };
                // Let the hall driver publish tri_state before reading it, while coalescing bounce.
                handler.postDelayed(pendingAlertSlider, 8L);
            }
        }

        private void applyAlertSliderPosition() {
            int position = readAlertSliderPosition();
            int mode;
            if (position == 1) {
                mode = AudioManager.RINGER_MODE_SILENT;
            } else if (position == 2) {
                mode = AudioManager.RINGER_MODE_VIBRATE;
            } else if (position == 3) {
                mode = AudioManager.RINGER_MODE_NORMAL;
            } else {
                HookLog.once("alert_slider_state_invalid",
                        "invalid alert slider state: " + position);
                return;
            }
            if (audioManager == null) {
                HookLog.once("alert_slider_audio_missing", "AudioManager missing");
                return;
            }
            synchronized (this) {
                if (lastAlertSliderPosition == position
                        && getInternalRingerMode() == mode) {
                    return;
                }
                lastAlertSliderPosition = position;
            }
            try {
                Method internal = Reflect.findMethod(AudioManager.class,
                        "setRingerModeInternal", int.class);
                if (internal != null) {
                    internal.invoke(audioManager, mode);
                } else {
                    audioManager.setRingerMode(mode);
                }
                HookLog.info("alert slider position=" + position + ", ringerMode=" + mode);
            } catch (Throwable error) {
                HookLog.error("alert slider mode update failed", error);
            }
        }

        private int getInternalRingerMode() {
            try {
                Method internal = Reflect.findMethod(AudioManager.class,
                        "getRingerModeInternal");
                Object value = internal == null ? null : internal.invoke(audioManager);
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            } catch (Throwable ignored) {
            }
            return audioManager.getRingerMode();
        }

        private int readAlertSliderPosition() {
            try (BufferedReader reader = new BufferedReader(
                    new FileReader(ALERT_SLIDER_STATE_PATH))) {
                String line = reader.readLine();
                return line == null ? -1 : Integer.parseInt(line.trim());
            } catch (Throwable error) {
                HookLog.error("alert slider state read failed", error);
                return -1;
            }
        }

        void handle(KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() != 0) {
                return;
            }
            if (!ModuleConfig.isEnabled(context, ModuleConfig.DOUBLE_TAP_WAKE, true)) {
                synchronized (this) {
                    if (pendingSingle != null) {
                        handler.removeCallbacks(pendingSingle);
                        pendingSingle = null;
                        pendingDeadline = 0L;
                    }
                }
                return;
            }
            long now = SystemClock.uptimeMillis();
            synchronized (this) {
                if (isInteractive() || now < cooldownUntil) {
                    return;
                }
                if (pendingSingle != null && now <= pendingDeadline) {
                    handler.removeCallbacks(pendingSingle);
                    pendingSingle = null;
                    pendingDeadline = 0L;
                    cooldownUntil = now + DOUBLE_TAP_COOLDOWN_MS;
                    if (pocketGate.allowsWake()) {
                        wakeFully(event);
                    }
                    return;
                }

                if (pendingSingle != null) {
                    handler.removeCallbacks(pendingSingle);
                    pendingSingle = null;
                    pendingDeadline = 0L;
                }

                Runnable task = new Runnable() {
                    @Override
                    public void run() {
                        synchronized (F4State.this) {
                            if (pendingSingle != this) {
                                return;
                            }
                            pendingSingle = null;
                            pendingDeadline = 0L;
                        }
                        if (ModuleConfig.isEnabled(context, ModuleConfig.DOUBLE_TAP_WAKE, true)
                                && !isInteractive() && pocketGate.allowsWake()) {
                            injectAodSingleTap();
                        }
                    }
                };
                pendingSingle = task;
                pendingDeadline = now + DOUBLE_TAP_WINDOW_MS;
                handler.postDelayed(task, DOUBLE_TAP_WINDOW_MS);
            }
        }

        private void wakeFully(KeyEvent event) {
            try {
                Method method = Reflect.findMethod(pwm.getClass(), "wakeUpFromWakeKey", KeyEvent.class);
                if (method != null) {
                    method.invoke(pwm, event);
                    return;
                }
            } catch (Throwable error) {
                HookLog.error("wakeUpFromWakeKey failed", error);
            }
            try {
                Object policy = Reflect.get(pwm, "mWindowWakeUpPolicy");
                if (policy != null) {
                    Method method = Reflect.findCompatibleMethod(policy.getClass(), "wakeUpFromKey", 5);
                    if (method != null) {
                        method.invoke(policy, 0, event.getEventTime(), event.getKeyCode(), true,
                                event.getFlags());
                        return;
                    }
                }
                if (powerManager != null) {
                    Method method = Reflect.findCompatibleMethod(powerManager.getClass(), "wakeUp", 3);
                    if (method != null) {
                        method.invoke(powerManager, SystemClock.uptimeMillis(), 0,
                                "OP13HyperOSFix:F4_DOUBLE_TAP");
                    }
                }
            } catch (Throwable error) {
                HookLog.error("full F4 wake fallback failed", error);
            }
        }

        private void injectAodSingleTap() {
            try {
                Object inputManager = Reflect.get(pwm, "mInputManager");
                if (inputManager == null) {
                    HookLog.once("input_manager_missing", "mInputManager missing; key 354 skipped");
                    return;
                }
                Method inject = Reflect.findCompatibleMethod(inputManager.getClass(),
                        "injectInputEvent", 2);
                if (inject == null) {
                    HookLog.once("inject_method_missing", "injectInputEvent method missing");
                    return;
                }
                long now = SystemClock.uptimeMillis();
                KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                        AOD_SINGLE_TAP_KEY_CODE, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                KeyEvent up = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP,
                        AOD_SINGLE_TAP_KEY_CODE, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
                inject.invoke(inputManager, down, 0);
                inject.invoke(inputManager, up, 0);
            } catch (Throwable error) {
                HookLog.error("key 354 injection failed", error);
            }
        }
    }

    private static final class PocketGate implements SensorEventListener {
        final Object pwm;
        final Handler handler;
        SensorManager sensorManager;
        Sensor proximity;
        boolean registered;
        volatile boolean known;
        volatile boolean near;

        PocketGate(Object pwm, Handler handler) {
            this.pwm = pwm;
            this.handler = handler;
        }

        synchronized void initialize() {
            if (sensorManager != null) {
                return;
            }
            Object contextObject = Reflect.get(pwm, "mContext");
            if (!(contextObject instanceof Context)) {
                HookLog.once("pwm_context_missing", "PhoneWindowManager context missing");
                return;
            }
            sensorManager = (SensorManager) ((Context) contextObject)
                    .getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                proximity = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY, true);
            }
            if (proximity == null) {
                HookLog.once("wake_proximity_missing",
                        "wakeup proximity sensor missing; F4 pocket gate will fail open");
            } else {
                HookLog.info("F4 pocket sensor: " + proximity.getName()
                        + ", wakeUp=" + proximity.isWakeUpSensor());
            }
        }

        synchronized void register() {
            initialize();
            if (registered || sensorManager == null || proximity == null) {
                return;
            }
            known = false;
            near = false;
            registered = sensorManager.registerListener(this, proximity,
                    SensorManager.SENSOR_DELAY_NORMAL, handler);
        }

        synchronized void unregister() {
            if (registered && sensorManager != null) {
                sensorManager.unregisterListener(this);
            }
            registered = false;
            known = false;
            near = false;
        }

        boolean allowsWake() {
            return !known || !near;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event == null || event.values == null || event.values.length == 0) {
                return;
            }
            float threshold = Math.min(1.0f, Math.max(0.1f, event.sensor.getMaximumRange()));
            near = event.values[0] < threshold;
            known = true;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }
}
