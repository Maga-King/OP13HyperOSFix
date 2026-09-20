package local.mio.op13hyperosfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class SystemUiHooks {
    private static final String ACTION_FINGERPRINT_DOWN =
            "com.sysuifpfix.action.FINGERPRINT_DOWN";
    private static final String ACTION_FINGERPRINT_UP =
            "com.sysuifpfix.action.FINGERPRINT_UP";
    private static final String ACTION_LEGACY_FINGERPRINT_DOWN =
            "com.oplus.fp.ANIM_DOWN";
    private static final String PICKUP_SETTING = "pick_up_gesture_wakeup_mode";
    private static final int USER_CURRENT = -2;
    private static final int ONEPLUS_PICKUP_SENSOR = 22;
    private static final int ONEPLUS_FOD_MOVEMENT_SENSOR = 33171026;
    private static final long QUICK_OPEN_DELAY_MS = 450L;

    private static final Set<Object> PICKUP_LISTENERS = weakSet();
    private static final Set<Object> MOVEMENT_LISTENERS = weakSet();
    private static final Set<Object> NON_UI_LISTENERS = weakSet();
    private static final Set<Object> WIRED_MANAGERS = weakSet();
    private static final Map<Object, Object> TOUCH_LISTENER_TO_ICON = weakMap();
    private static final Map<Object, Object> SHOW_RUNNABLE_TO_QUICK_VIEW = weakMap();
    private static final Map<Object, Object> QUICK_VIEW_TO_ICON = weakMap();
    private static final Map<Object, TouchState> ICON_TOUCH_STATE = weakMap();
    private static final Object AOD_POCKET_GATE_LOCK = new Object();
    private static volatile AodPocketGate aodPocketGate;

    private SystemUiHooks() {
    }

    static void install(ClassLoader loader, String processName) {
        FeatureFlagHooks.forceTrue(loader, Set.of("support_gesture_wakeup"), "systemui");
        hookSensorEventAdapters();
        hookPickupSensor(loader);
        hookFodSensors(loader);
        hookFodManager(loader);
        hookAodDoubleTapPocketGuard(loader);
        hookFullScreenAodPanelMode(loader);
        MiChargePresentationHooks.install(loader);
        VolumeIslandHooks.install(loader);
        HookLog.info("SystemUI hooks installed in " + processName);
    }

    private static void hookAodDoubleTapPocketGuard(ClassLoader loader) {
        Class<?> listenerClass = Reflect.findClass(loader,
                "com.android.systemui.shade.PulsingGestureListener");
        if (listenerClass == null) {
            HookLog.once("aod_double_tap_class_missing",
                    "PulsingGestureListener missing; stock AOD pocket guard retained");
            return;
        }

        XposedBridge.hookAllConstructors(listenerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ensureAodPocketGate();
            }
        });
        int count = Reflect.hookNamedMethods(listenerClass, "onDoubleTapEvent",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length != 0) {
                            return;
                        }
                        AodPocketGate gate = ensureAodPocketGate();
                        if (gate != null && !gate.allowsWake()) {
                            param.setResult(false);
                        }
                    }
                });
        HookLog.info("AOD double-tap pocket hooks=" + count);
    }

    private static AodPocketGate ensureAodPocketGate() {
        AodPocketGate gate = aodPocketGate;
        if (gate != null) {
            return gate;
        }
        synchronized (AOD_POCKET_GATE_LOCK) {
            gate = aodPocketGate;
            if (gate != null) {
                return gate;
            }
            Context context = currentApplicationContext();
            if (context == null) {
                return null;
            }
            try {
                gate = new AodPocketGate(context.getApplicationContext());
                gate.initialize();
                aodPocketGate = gate;
                return gate;
            } catch (Throwable error) {
                HookLog.error("AOD pocket gate initialization failed", error);
                return null;
            }
        }
    }

    private static Context currentApplicationContext() {
        try {
            Class<?> activityThread = XposedHelpers.findClass(
                    "android.app.ActivityThread", null);
            Object application = XposedHelpers.callStaticMethod(activityThread,
                    "currentApplication");
            return application instanceof Context ? (Context) application : null;
        } catch (Throwable error) {
            HookLog.once("systemui_application_missing",
                    "SystemUI application context not ready; AOD pocket gate deferred");
            return null;
        }
    }

    private static void hookFullScreenAodPanelMode(ClassLoader loader) {
        Class<?> managerClass = Reflect.findClass(loader,
                "com.android.keyguard.fullaod.MiuiFullAodManager");
        if (managerClass == null) {
            HookLog.once("full_aod_manager_missing",
                    "MiuiFullAodManager missing; Oplus panel AOD mode not installed");
            return;
        }

        XposedBridge.hookAllConstructors(managerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                syncFullScreenAodPanelMode(param.thisObject);
            }
        });

        int updateHooks = Reflect.hookNamedMethods(managerClass, "updateFullAodEnable",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        syncFullScreenAodPanelMode(param.thisObject);
                    }
                });

        int dozingHooks = 0;
        for (int index = 1; index <= 8; index++) {
            Class<?> callbackClass = Reflect.findClass(loader,
                    "com.android.keyguard.fullaod.MiuiFullAodManager$" + index);
            Method method = callbackClass == null ? null
                    : Reflect.findMethod(callbackClass, "onDozingChanged", boolean.class);
            if (method == null) {
                continue;
            }
            if (Reflect.hookMethodOnce(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object manager = Reflect.get(param.thisObject, "this$0");
                    if (manager == null) {
                        Object[] candidates = Reflect.findFieldValuesWithMethod(
                                param.thisObject, "fullAodEnable");
                        manager = candidates.length == 0 ? null : candidates[0];
                    }
                    syncFullScreenAodPanelMode(manager);
                }
            })) {
                dozingHooks++;
            }
        }
        HookLog.info("full-screen AOD panel hooks: update=" + updateHooks
                + ", dozing=" + dozingHooks);
    }

    private static void syncFullScreenAodPanelMode(Object manager) {
        if (manager == null) {
            return;
        }
        boolean enabled;
        try {
            Object value = Reflect.call(manager, "fullAodEnable");
            enabled = value instanceof Boolean && (Boolean) value;
        } catch (Throwable missingMethod) {
            enabled = Reflect.getBoolean(manager, false, "isDeviceSupport")
                    && Reflect.getBoolean(manager, false, "mAodEnable")
                    && Reflect.getBoolean(manager, false, "mAodFullScreenEnable")
                    && Reflect.getBoolean(manager, false, "isFullAodSupport");
            HookLog.once("full_aod_method_fallback",
                    "fullAodEnable unavailable; using compatible state-field fallback");
        }
        OplusAodPanelBridge.setFullScreenAodEnabled(enabled);
    }

    private static void hookSensorEventAdapters() {
        // Runtime listener classes are hooked when their owning core objects are constructed.
    }

    private static void hookPickupSensor(ClassLoader loader) {
        Class<?> injector = Reflect.findClass(loader,
                "com.android.keyguard.injector.KeyguardSensorInjector");
        if (injector == null) {
            HookLog.once("pickup_class_missing", "KeyguardSensorInjector missing");
            return;
        }

        XposedBridge.hookAllConstructors(injector, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object owner = param.thisObject;
                try {
                    Object listener = Reflect.get(owner, "mPickupSensorListener");
                    if (listener instanceof SensorEventListener) {
                        PICKUP_LISTENERS.add(listener);
                        hookRuntimeSensorListener(listener.getClass());
                    }
                    Object handlerObject = Reflect.get(owner, "mHandler");
                    if (handlerObject instanceof Handler) {
                        ((Handler) handlerObject).postDelayed(() -> {
                            try {
                                Reflect.call(owner, "updatePickupSensorRegistration");
                            } catch (Throwable error) {
                                HookLog.error("pickup registration refresh failed", error);
                            }
                        }, 250L);
                    }
                } catch (Throwable error) {
                    HookLog.error("pickup object wiring failed", error);
                }
            }
        });

        int count = Reflect.hookNamedMethods(injector, "updatePickupSensorRegistration",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (replacePickupRegistration(param.thisObject)) {
                            param.setResult(null);
                        }
                    }
                });
        HookLog.info("pickup registration hooks=" + count);
    }

    private static boolean replacePickupRegistration(Object owner) {
        SensorManager manager = asSensorManager(Reflect.get(owner, "mSensorManager"));
        Object listenerObject = Reflect.get(owner, "mPickupSensorListener");
        if (manager == null || !(listenerObject instanceof SensorEventListener)) {
            return false;
        }

        Sensor sensor = (Sensor) XposedHelpers.getAdditionalInstanceField(owner,
                "op13fix_pickup_sensor");
        if (sensor == null) {
            sensor = findSensor(manager, ONEPLUS_PICKUP_SENSOR, true);
            if (sensor == null) {
                HookLog.once("pickup_type22_missing",
                        "type 22 pickup sensor missing; MIUI registration retained");
                return false;
            }
            XposedHelpers.setAdditionalInstanceField(owner, "op13fix_pickup_sensor", sensor);
            Reflect.set(owner, sensor, "mWakeupAndSleepSensor");
            HookLog.info("pickup sensor fixed: " + sensor.getName()
                    + ", wakeUp=" + sensor.isWakeUpSensor());
        }

        Object monitor = Reflect.get(owner, "mKeyguardUpdateMonitor");
        Object interactiveValue = Reflect.get(monitor, "mDeviceInteractive");
        boolean interactive;
        if (interactiveValue instanceof Boolean) {
            interactive = (Boolean) interactiveValue;
        } else {
            Object power = Reflect.get(owner, "mPowerManager");
            interactive = power instanceof PowerManager && ((PowerManager) power).isInteractive();
        }
        boolean keyguardShowing = Reflect.getBoolean(owner, true, "mKeyguardShowing");
        boolean wokeByPickup = Reflect.getBoolean(owner, false, "mWakeupByPickUp");
        boolean pickupEnabled = isPickupEnabled(owner);
        boolean shouldRegister = pickupEnabled
                && (!interactive || (keyguardShowing && wokeByPickup));

        synchronized (owner) {
            boolean current = Reflect.getBoolean(owner, false, "mCurrentPickupSensorRegistered");
            if (current == shouldRegister) {
                return true;
            }
            Reflect.set(owner, shouldRegister, "mCurrentPickupSensorRegistered");
            int generation = additionalInt(owner, "op13fix_pickup_generation") + 1;
            XposedHelpers.setAdditionalInstanceField(owner, "op13fix_pickup_generation", generation);

            Sensor selectedSensor = sensor;
            SensorEventListener listener = (SensorEventListener) listenerObject;
            Object handlerObject = Reflect.get(owner, "mHandler");
            Handler handler = handlerObject instanceof Handler ? (Handler) handlerObject : null;
            AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                synchronized (owner) {
                    if (additionalInt(owner, "op13fix_pickup_generation") != generation) {
                        return;
                    }
                    try {
                        if (shouldRegister) {
                            if (handler != null) {
                                manager.registerListener(listener, selectedSensor,
                                        SensorManager.SENSOR_DELAY_NORMAL, handler);
                            } else {
                                manager.registerListener(listener, selectedSensor,
                                        SensorManager.SENSOR_DELAY_NORMAL);
                            }
                        } else {
                            manager.unregisterListener(listener);
                        }
                    } catch (Throwable error) {
                        HookLog.error("pickup listener update failed", error);
                    }
                }
            });
        }
        return true;
    }

    private static boolean isPickupEnabled(Object owner) {
        Object contextObject = Reflect.get(owner, "mContext");
        if (!(contextObject instanceof Context)) {
            HookLog.once("pickup_context_missing",
                    "pickup setting context unavailable; keeping sensor disabled");
            return false;
        }
        Context context = (Context) contextObject;
        try {
            Method method = Settings.System.class.getDeclaredMethod("getIntForUser",
                    android.content.ContentResolver.class, String.class,
                    int.class, int.class);
            method.setAccessible(true);
            Object value = method.invoke(null, context.getContentResolver(),
                    PICKUP_SETTING, 0, USER_CURRENT);
            if (value instanceof Integer) {
                return (Integer) value != 0;
            }
        } catch (Throwable userReadError) {
            HookLog.once("pickup_current_user_read_fallback",
                    "current-user pickup setting API unavailable; using process user");
        }
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    PICKUP_SETTING, 0) != 0;
        } catch (Throwable error) {
            HookLog.error("pickup setting read failed", error);
            return false;
        }
    }

    private static void hookFodSensors(ClassLoader loader) {
        Class<?> sensorClass = Reflect.findClass(loader,
                "com.miui.keyguard.biometrics.fod.MiuiGxzwSensor");
        if (sensorClass == null) {
            HookLog.once("fod_sensor_class_missing", "MiuiGxzwSensor missing");
            return;
        }

        Reflect.hookNamedMethods(sensorClass, "registerDozeSensor", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                registerCorrectFodSensors(param.thisObject);
            }
        });
        Reflect.hookNamedMethods(sensorClass, "unregisterDozeSensor", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object owner = param.thisObject;
                synchronized (owner) {
                    int generation = additionalInt(owner, "op13fix_fod_sensor_generation") + 1;
                    XposedHelpers.setAdditionalInstanceField(owner,
                            "op13fix_fod_sensor_generation", generation);
                }
            }
        });
    }

    private static void wireFodSensor(Object owner) {
        if (owner == null) {
            return;
        }
        Object movement = Reflect.get(owner, "mPutUpSensorListener");
        Object nonUi = Reflect.get(owner, "mNonUIListener");
        if (movement instanceof SensorEventListener) {
            MOVEMENT_LISTENERS.add(movement);
            hookRuntimeSensorListener(movement.getClass());
        }
        if (nonUi instanceof SensorEventListener) {
            NON_UI_LISTENERS.add(nonUi);
            hookRuntimeSensorListener(nonUi.getClass());
        }
        SensorManager manager = asSensorManager(Reflect.get(owner, "mSensorManager"));
        if (manager != null && findSensor(manager, Sensor.TYPE_PROXIMITY, true) != null) {
            Reflect.set(owner, true, "mSupportNonuiSensor");
        }
    }

    private static void registerCorrectFodSensors(Object owner) {
        wireFodSensor(owner);
        SensorManager manager = asSensorManager(Reflect.get(owner, "mSensorManager"));
        Object movementObject = Reflect.get(owner, "mPutUpSensorListener");
        Object nonUiObject = Reflect.get(owner, "mNonUIListener");
        if (manager == null || !(movementObject instanceof SensorEventListener)) {
            return;
        }

        Sensor movementSensor = findSensor(manager, ONEPLUS_FOD_MOVEMENT_SENSOR, true);
        Sensor nonUiSensor = findSensor(manager, Sensor.TYPE_PROXIMITY, true);
        if (movementSensor == null) {
            HookLog.once("fod_movement_missing", "FOD movement sensor type 33171026 missing");
        }
        if (nonUiSensor == null) {
            HookLog.once("fod_nonui_missing", "FOD wakeup proximity sensor missing");
        } else {
            Reflect.set(owner, true, "mSupportNonuiSensor");
        }

        synchronized (owner) {
            int generation = additionalInt(owner, "op13fix_fod_sensor_generation") + 1;
            XposedHelpers.setAdditionalInstanceField(owner, "op13fix_fod_sensor_generation",
                    generation);
            Object handlerObject = Reflect.get(owner, "mHandler");
            Handler handler = handlerObject instanceof Handler ? (Handler) handlerObject : null;
            SensorEventListener movementListener = (SensorEventListener) movementObject;
            SensorEventListener nonUiListener = nonUiObject instanceof SensorEventListener
                    ? (SensorEventListener) nonUiObject : null;
            AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
                synchronized (owner) {
                    if (additionalInt(owner, "op13fix_fod_sensor_generation") != generation) {
                        return;
                    }
                    try {
                        if (movementSensor != null) {
                            registerSensor(manager, movementListener, movementSensor, handler);
                        }
                        if (nonUiSensor != null && nonUiListener != null) {
                            registerSensor(manager, nonUiListener, nonUiSensor, handler);
                        }
                    } catch (Throwable error) {
                        HookLog.error("FOD sensor registration failed", error);
                    }
                }
            });
        }
    }

    private static void registerSensor(SensorManager manager, SensorEventListener listener,
            Sensor sensor, Handler handler) {
        if (handler != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler);
        } else {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private static void hookRuntimeSensorListener(Class<?> listenerClass) {
        Method method = Reflect.findMethod(listenerClass, "onSensorChanged", SensorEvent.class);
        Reflect.hookMethodOnce(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length == 0 || !(param.args[0] instanceof SensorEvent)) {
                    return;
                }
                SensorEvent event = (SensorEvent) param.args[0];
                if (event.values == null || event.values.length == 0 || event.sensor == null) {
                    return;
                }
                float original = event.values[0];
                float converted = original;
                Object listener = param.thisObject;
                int type = event.sensor.getType();

                if (PICKUP_LISTENERS.contains(listener) && type == ONEPLUS_PICKUP_SENSOR) {
                    if (original == 0.0f) {
                        converted = 1.0f;
                    } else if (original == 1.0f) {
                        converted = 0.0f;
                    }
                } else if (MOVEMENT_LISTENERS.contains(listener)
                        && type == ONEPLUS_FOD_MOVEMENT_SENSOR) {
                    if (original == 1.0f) {
                        converted = 2.0f;
                    } else if (original == 2.0f) {
                        converted = 1.0f;
                    }
                } else if (NON_UI_LISTENERS.contains(listener)
                        && type == Sensor.TYPE_PROXIMITY) {
                    converted = original == 0.0f ? 1.0f : 0.0f;
                }

                if (converted != original) {
                    param.setObjectExtra("op13fix_original_sensor_value", original);
                    event.values[0] = converted;
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object original = param.getObjectExtra("op13fix_original_sensor_value");
                if (original instanceof Float && param.args.length > 0
                        && param.args[0] instanceof SensorEvent) {
                    SensorEvent event = (SensorEvent) param.args[0];
                    if (event.values != null && event.values.length > 0) {
                        event.values[0] = (Float) original;
                    }
                }
            }
        });
    }

    private static void hookFodManager(ClassLoader loader) {
        Class<?> managerClass = Reflect.findClass(loader,
                "com.miui.keyguard.biometrics.fod.MiuiGxzwManager");
        if (managerClass == null) {
            HookLog.once("fod_manager_missing", "MiuiGxzwManager missing");
            return;
        }

        XposedBridge.hookAllConstructors(managerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                wireManager(param.thisObject);
            }
        });

        Reflect.hookNamedMethods(managerClass, "dismissGxzwView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                rescheduleQuickOpen(param.thisObject);
            }
        });
    }

    private static void wireManager(Object manager) {
        if (!WIRED_MANAGERS.add(manager)) {
            return;
        }
        try {
            Object icon = Reflect.get(manager, "mMiuiGxzwIconView");
            Object fodSensor = Reflect.get(icon, "mMiuiGxzwSensor");
            wireFodSensor(fodSensor);
            wireQuickOpen(icon);
            registerFingerprintReceiver(manager);
        } catch (Throwable error) {
            HookLog.error("FOD manager wiring failed", error);
        }
    }

    private static void wireQuickOpen(Object icon) {
        if (icon == null) {
            return;
        }
        Object quickView = Reflect.get(icon, "mMiuiGxzwQuickOpenView");
        Object showRunnable = Reflect.get(quickView, "mShowRunnable");
        if (quickView == null || !(showRunnable instanceof Runnable)) {
            HookLog.once("quick_objects_missing", "quick-open runtime objects missing");
            return;
        }

        TouchState state = touchState(icon);
        int pointerCandidates = 0;
        int pointerHooks = 0;
        for (Object touchListener : Reflect.findFieldValuesWithMethod(icon,
                "onPointerEvent", MotionEvent.class)) {
            Method pointerMethod = Reflect.findMethod(touchListener.getClass(),
                    "onPointerEvent", MotionEvent.class);
            if (pointerMethod == null) {
                continue;
            }
            TOUCH_LISTENER_TO_ICON.put(touchListener, icon);
            pointerCandidates++;
            if (hookPointerListener(pointerMethod)) {
                pointerHooks++;
            }
        }
        state.trackingWired = pointerCandidates > 0;
        if (!state.trackingWired) {
            HookLog.once("pointer_method_missing",
                    "quick-open pointer tracking unavailable; stale-touch gate disabled");
        }

        QUICK_VIEW_TO_ICON.put(quickView, icon);
        SHOW_RUNNABLE_TO_QUICK_VIEW.put(showRunnable, quickView);

        Method runMethod = Reflect.findMethod(showRunnable.getClass(), "run");
        Reflect.hookMethodOnce(runMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object mappedQuickView = SHOW_RUNNABLE_TO_QUICK_VIEW.get(param.thisObject);
                if (mappedQuickView == null) {
                    return;
                }
                Object mappedIcon = QUICK_VIEW_TO_ICON.get(mappedQuickView);
                if (!isLiveFodTouch(mappedIcon)) {
                    try {
                        Reflect.call(mappedQuickView, "dismiss", 1);
                    } catch (Throwable error) {
                        HookLog.error("stale quick-open cleanup failed", error);
                    }
                    param.setResult(null);
                }
            }
        });
        HookLog.info("quick-open runtime hooks wired, pointerCandidates="
                + pointerCandidates + ", pointerHooks=" + pointerHooks);
    }

    private static boolean hookPointerListener(Method pointerMethod) {
        return Reflect.hookMethodOnce(pointerMethod, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object mappedIcon = TOUCH_LISTENER_TO_ICON.get(param.thisObject);
                if (mappedIcon != null && param.args.length > 0
                        && param.args[0] instanceof MotionEvent) {
                    updateTouchState(mappedIcon, (MotionEvent) param.args[0]);
                }
            }
        });
    }

    private static void updateTouchState(Object icon, MotionEvent event) {
        TouchState state = touchState(icon);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            state.down = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            state.down = false;
        }
    }

    private static boolean isLiveFodTouch(Object icon) {
        TouchState state = ICON_TOUCH_STATE.get(icon);
        return state != null && state.trackingWired && state.down;
    }

    private static TouchState touchState(Object icon) {
        TouchState state = ICON_TOUCH_STATE.get(icon);
        if (state == null) {
            state = new TouchState();
            ICON_TOUCH_STATE.put(icon, state);
        }
        return state;
    }

    private static void rescheduleQuickOpen(Object manager) {
        Object icon = Reflect.get(manager, "mMiuiGxzwIconView");
        Object quickView = Reflect.get(icon, "mMiuiGxzwQuickOpenView");
        Object showRunnable = Reflect.get(quickView, "mShowRunnable");
        Object handlerObject = Reflect.get(quickView, "mHandler");
        if (!(showRunnable instanceof Runnable) || !(handlerObject instanceof Handler)
                || !Reflect.getBoolean(quickView, false, "mShowed")) {
            return;
        }
        Handler handler = (Handler) handlerObject;
        Runnable runnable = (Runnable) showRunnable;
        if (handler.hasCallbacks(runnable)) {
            handler.removeCallbacks(runnable);
            handler.postDelayed(runnable, QUICK_OPEN_DELAY_MS);
        }
    }

    private static void registerFingerprintReceiver(Object manager) {
        Object contextObject = Reflect.get(manager, "mContext");
        if (!(contextObject instanceof Context)) {
            HookLog.once("fod_receiver_context_missing", "FOD receiver context missing");
            return;
        }
        Context context = (Context) contextObject;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_FINGERPRINT_DOWN);
        filter.addAction(ACTION_FINGERPRINT_UP);
        filter.addAction(ACTION_LEGACY_FINGERPRINT_DOWN);
        BroadcastReceiver receiver = new FingerprintEventReceiver(manager);
        try {
            context.registerReceiver(receiver, filter, "android.permission.DEVICE_POWER", null,
                    Context.RECEIVER_EXPORTED);
            HookLog.info("fingerprint animation receiver registered with root-only permission");
        } catch (Throwable restrictedError) {
            try {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                HookLog.once("fod_receiver_unrestricted",
                        "fingerprint receiver registered without sender permission fallback");
            } catch (Throwable error) {
                HookLog.error("fingerprint animation receiver registration failed", error);
            }
        }
    }

    private static SensorManager asSensorManager(Object value) {
        return value instanceof SensorManager ? (SensorManager) value : null;
    }

    private static Sensor findSensor(SensorManager manager, int type, boolean preferWakeup) {
        Sensor sensor = manager.getDefaultSensor(type, preferWakeup);
        if (sensor != null) {
            return sensor;
        }
        for (Sensor candidate : manager.getSensorList(type)) {
            if (!preferWakeup || candidate.isWakeUpSensor()) {
                return candidate;
            }
        }
        return preferWakeup ? null : manager.getDefaultSensor(type);
    }

    private static int additionalInt(Object owner, String key) {
        Object value = XposedHelpers.getAdditionalInstanceField(owner, key);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static <T> Set<T> weakSet() {
        return Collections.newSetFromMap(Collections.synchronizedMap(new WeakHashMap<>()));
    }

    private static <K, V> Map<K, V> weakMap() {
        return Collections.synchronizedMap(new WeakHashMap<>());
    }

    private static final class TouchState {
        volatile boolean down;
        volatile boolean trackingWired;
    }

    private static final class AodPocketGate implements SensorEventListener {
        private final Context context;
        private final Handler handler;
        private final PowerManager powerManager;
        private final SensorManager sensorManager;
        private final Sensor proximity;
        private boolean registered;
        private volatile boolean known;
        private volatile boolean near;

        AodPocketGate(Context context) {
            this.context = context;
            handler = new Handler(context.getMainLooper());
            powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
            proximity = sensorManager == null ? null
                    : findSensor(sensorManager, Sensor.TYPE_PROXIMITY, true);
        }

        void initialize() {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        register();
                    } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                        unregister();
                    }
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);
            if (proximity == null) {
                HookLog.once("aod_wake_proximity_missing",
                        "wakeup proximity sensor missing; AOD pocket guard will fail open");
                return;
            }
            HookLog.info("AOD pocket sensor: " + proximity.getName()
                    + ", wakeUp=" + proximity.isWakeUpSensor());
            if (powerManager != null && !powerManager.isInteractive()) {
                register();
            }
        }

        synchronized void register() {
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
            if (event == null || event.sensor == null || event.values == null
                    || event.values.length == 0) {
                return;
            }
            float threshold = Math.min(1.0f,
                    Math.max(0.1f, event.sensor.getMaximumRange()));
            near = event.values[0] < threshold;
            known = true;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }
    }

    private static final class FingerprintEventReceiver extends BroadcastReceiver {
        private final WeakReference<Object> manager;

        FingerprintEventReceiver(Object manager) {
            this.manager = new WeakReference<>(manager);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Object target = manager.get();
            if (target == null || intent == null) {
                return;
            }
            String action = intent.getAction();
            boolean isDown = ACTION_FINGERPRINT_DOWN.equals(action)
                    || ACTION_LEGACY_FINGERPRINT_DOWN.equals(action);
            boolean isUp = ACTION_FINGERPRINT_UP.equals(action);
            if (!isDown && !isUp) {
                return;
            }
            try {
                Object icon = Reflect.get(target, "mMiuiGxzwIconView");
                if (icon == null) {
                    return;
                }
                if (isDown) {
                    Reflect.call(target, "onFpsPointerUp");
                    Reflect.call(target, "onFpsPointerDown");
                    Reflect.set(icon, true, "isCatchDownEvent");
                    return;
                }

                Reflect.set(icon, false, "isCatchDownEvent");
                Reflect.call(target, "onFpsPointerUp");
            } catch (Throwable error) {
                HookLog.error("fingerprint event dispatch failed", error);
            }
        }
    }
}
