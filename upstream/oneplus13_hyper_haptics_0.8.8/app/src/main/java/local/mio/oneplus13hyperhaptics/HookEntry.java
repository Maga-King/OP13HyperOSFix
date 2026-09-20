package local.mio.oneplus13hyperhaptics;

import android.app.AndroidAppHelper;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.SparseBooleanArray;

import java.util.Map;
import java.util.Set;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Runtime haptic compatibility for the OnePlus 13 HyperOS port. */
public final class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String TAG = "OnePlus13HyperHaptics";
    private static final String SYSTEM_FRAMEWORK = "android";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String SETTINGS_APP = "com.android.settings";
    private static final String XIAOMI_HAL_EXT_DESCRIPTOR =
            "vendor.hardware.vibratorfeature.IVibratorExt";
    // Kept only so upgrades can read/remove the legacy preference code path.
    // The hook that exposed this separate control is no longer installed.
    private static final String RTP_GAIN_SETTING = "mio_xiaomi_rtp_strength_percent";
    private static final String RTP_GAIN_PREFERENCE = "mio_xiaomi_rtp_strength";

    /**
     * Module-private route for the Redmi K80 Ultra 0916-tuned notification
     * long-press waveform.  Never replace native effect 1 globally: apps and
     * the stock OnePlus framework still need their original effects 0-12.
     */
    private static final int EFFECT_NOTIFICATION_LONG_PRESS = 10001;
    private static final int EFFECT_FINGERPRINT_SUCCESS = 5;
    private static final int EFFECT_CLEAR_NOTIFICATIONS = 10;
    private static final int EFFECT_FINGERPRINT_FAILURE = 12;

    private static final int STRENGTH_LIGHT = 0;
    private static final int STRENGTH_MEDIUM = 1;
    private static final int STRENGTH_STRONG = 2;

    /**
     * Xiaomi UI/RTP effect IDs that are translated in system_server. Reporting
     * only this allow-list avoids claiming support for unrelated ringtone and
     * game effects that the OnePlus actuator cannot reproduce safely.
     */
    private static final int[] MAPPED_XIAOMI_EFFECTS = {
            72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87,
            88, 90, 91, 92, 93, 96, 98, 106, 114,
            157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168,
            169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180,
            181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192,
            201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212,
            213, 214, 215, 216, 217,
            401, 402, 403, 404, 405, 406, 407, 408, 410,
            EFFECT_NOTIFICATION_LONG_PRESS
    };

    private static final AtomicBoolean FRAMEWORK_ACTIVE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SYSTEM_UI_ACTIVE_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SYSTEM_UI_CLASS_WATCHER = new AtomicBoolean(false);
    private static final AtomicBoolean EXTENDED_HAPTICS_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean FINGERPRINT_AUTH_HOOKED = new AtomicBoolean(false);
    private static final AtomicLong LAST_FINGERPRINT_SUCCESS = new AtomicLong(0L);
    private static final AtomicLong LAST_RTP_STRENGTH_PREVIEW = new AtomicLong(0L);
    private static final AtomicLong LAST_OFFICIAL_SLIDER_REQUEST = new AtomicLong(0L);
    private static final Object LAUNCHER_HAPTIC_DEDUPE_LOCK = new Object();
    private static long lastLauncherHapticUptime;
    private static String lastLauncherHapticSignature;
    private static final Set<Integer> LOGGED_MAPPINGS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> LOGGED_STRENGTH_BOOSTS =
            ConcurrentHashMap.newKeySet();
    private static final Set<Integer> LOGGED_AUDIO_RTP = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<PendingRtp> PENDING_AUDIO_RTP = new ThreadLocal<>();
    private static final Map<Object, NativeCompletionTarget> NATIVE_COMPLETION_TARGETS =
            new ConcurrentHashMap<>();
    private static volatile boolean AUDIO_RTP_HOOK_INSTALLED;
    private static volatile Handler RTP_COMPLETION_HANDLER;
    private static volatile Object XIAOMI_HAL_EXT_SHIM;
    private static final Binder XIAOMI_HAL_EXT_SHIM_BINDER = new Binder();

    @Override
    public void initZygote(StartupParam startupParam) {
        RtpPlayer.setModulePath(startupParam.modulePath);
        HapticVideoEffect.setModulePath(startupParam.modulePath);
        AudioRtpPlayer.setModulePath(startupParam.modulePath);
        RootRtpPlayer.setModulePath(startupParam.modulePath);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SYSTEM_FRAMEWORK.equals(lpparam.packageName)) {
            installHook("framework-private-effect-validation",
                    () -> hookPrivateEffectValidation(lpparam.classLoader));
            installHook("framework-supported-effects",
                    () -> hookFrameworkSupportedEffects(lpparam.classLoader));
            installHook("framework-audio-rtp",
                    () -> hookFrameworkAudioRtp(lpparam.classLoader));
            installHook("framework-effect-translation",
                    () -> hookFrameworkEffectTranslation(lpparam.classLoader));
            installHook("framework-request-dedupe",
                    () -> hookFrameworkRequestDedupe(lpparam.classLoader));
            installHook("xiaomi-haptic-strength-state",
                    () -> hookXiaomiHapticStrengthState(lpparam.classLoader));
            if (FRAMEWORK_ACTIVE_LOGGED.compareAndSet(false, true)) {
                XposedBridge.log(TAG + ": System Framework effect bridge active");
            }
            return;
        }

        if (SETTINGS_APP.equals(lpparam.packageName)) {
            installHook("settings-private-effect-validation",
                    () -> hookPrivateEffectValidation(lpparam.classLoader));
            installHook("settings-xiaomi-video-parser",
                    () -> hookSettingsHapticVideos(lpparam.classLoader));
            installHook("settings-native-haptic-strength-range",
                    () -> hookSettingsNativeHapticStrengthRange(lpparam.classLoader));
            return;
        }

        if (!SYSTEM_UI.equals(lpparam.packageName)) return;

        installHook("systemui-private-effect-validation",
                () -> hookPrivateEffectValidation(lpparam.classLoader));
        installHook("xiaomi-extended-haptics",
                () -> hookExtendedHaptics(lpparam.classLoader));
        installHook("fingerprint-authenticated",
                () -> hookFingerprintAuthenticated(lpparam.classLoader));
        installHook("fingerprint-failure", HookEntry::hookFingerprintFailure);
        if (SYSTEM_UI_ACTIVE_LOGGED.compareAndSet(false, true)) {
            XposedBridge.log(TAG + ": SystemUI hooks active");
        }
    }

    /**
     * OS4 Settings emits slider previews every few milliseconds, faster than
     * RichTap can open and fill its FIFO.  Let one request finish starting
     * instead of allowing every later request to cancel it.  The OS4 launcher
     * also submits an identical CLICK twice at the freeform gesture threshold;
     * remove only that same-package/same-effect duplicate.
     */
    private static void hookFrameworkRequestDedupe(ClassLoader classLoader) {
        Class<?> serviceClass = XposedHelpers.findClass(
                "com.android.server.vibrator.VibratorManagerService", classLoader);
        Class<?> combinedClass = XposedHelpers.findClass(
                "android.os.CombinedVibration", classLoader);
        XposedHelpers.findAndHookMethod(
                serviceClass,
                "vibrate",
                int.class,
                int.class,
                String.class,
                combinedClass,
                VibrationAttributes.class,
                String.class,
                android.os.IBinder.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String opPkg = (String) param.args[2];
                        Object effect = param.args[3];
                        VibrationAttributes attrs = (VibrationAttributes) param.args[4];
                        String reason = (String) param.args[5];
                        long now = SystemClock.uptimeMillis();

                        if (reason != null
                                && reason.contains("haptic_feedback_config_strength")
                                && reason.contains("effectId=1")) {
                            long previous = LAST_OFFICIAL_SLIDER_REQUEST.get();
                            if (now - previous < 32L) {
                                param.setResult(null);
                                return;
                            }
                            LAST_OFFICIAL_SLIDER_REQUEST.set(now);
                        }

                        if (!"com.miui.home".equals(opPkg)
                                || attrs == null
                                || attrs.getUsage() != 50
                                || effect == null) {
                            return;
                        }
                        String signature = effect.toString();
                        synchronized (LAUNCHER_HAPTIC_DEDUPE_LOCK) {
                            boolean duplicate = signature.equals(lastLauncherHapticSignature)
                                    && now - lastLauncherHapticUptime < 80L;
                            lastLauncherHapticSignature = signature;
                            lastLauncherHapticUptime = now;
                            if (duplicate) {
                                param.setResult(null);
                                XposedBridge.log(TAG
                                        + ": suppress duplicate launcher hardware haptic");
                            }
                        }
                    }
                });
        XposedBridge.log(TAG + ": OS4 slider throttle + launcher haptic dedupe active");
    }

    /**
     * Android validates predefined IDs in both the caller and system_server.
     * Permit only our module-private notification route; every stock and
     * Xiaomi effect retains the platform's original validation behavior.
     */
    private static void hookPrivateEffectValidation(ClassLoader classLoader) {
        Class<?> segmentClass = XposedHelpers.findClass(
                "android.os.vibrator.PrebakedSegment", classLoader);
        XposedHelpers.findAndHookMethod(
                segmentClass,
                "validate",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int effectId = XposedHelpers.getIntField(
                                param.thisObject, "mEffectId");
                        if (effectId == EFFECT_NOTIFICATION_LONG_PRESS) {
                            param.setResult(null);
                        }
                    }
                });
    }

    /** Add Xiaomi private UI IDs to the VibratorInfo returned to every client. */
    private static void hookFrameworkSupportedEffects(ClassLoader classLoader) {
        Class<?> serviceClass = XposedHelpers.findClass(
                "com.android.server.vibrator.VibratorManagerService", classLoader);
        XposedHelpers.findAndHookMethod(
                serviceClass,
                "getVibratorInfo",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        Object info = param.getResult();
                        if (info == null) return;

                        try {
                            SparseBooleanArray supported = (SparseBooleanArray)
                                    XposedHelpers.getObjectField(info, "mSupportedEffects");
                            if (supported == null) {
                                supported = new SparseBooleanArray();
                                // This OnePlus 13 HAL was verified with standard IDs 0-12.
                                for (int effectId = 0; effectId <= 12; effectId++) {
                                    supported.put(effectId, true);
                                }
                                XposedHelpers.setObjectField(
                                        info, "mSupportedEffects", supported);
                            }
                            for (int effectId : MAPPED_XIAOMI_EFFECTS) {
                                supported.put(effectId, true);
                            }
                            for (int effectId : RtpPlayer.packagedEffects()) {
                                supported.put(effectId, true);
                            }
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + ": failed to extend VibratorInfo");
                            XposedBridge.log(throwable);
                        }
                    }
                });
    }

    /** Translate immediately before system_server asks the OnePlus HAL to play. */
    private static void hookFrameworkEffectTranslation(ClassLoader classLoader) {
        Class<?> segmentClass = XposedHelpers.findClass(
                "android.os.vibrator.PrebakedSegment", classLoader);
        XposedHelpers.findAndHookMethod(
                segmentClass,
                "getEffectId",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int source = (int) param.getResult();
                        if (AUDIO_RTP_HOOK_INSTALLED
                                && source == 1
                                && RtpPlayer.hasPendingEffectStrength(source)) {
                            PENDING_AUDIO_RTP.set(PendingRtp.sliderPreview(source, 162));
                            return;
                        }
                        if (AUDIO_RTP_HOOK_INSTALLED
                                && source > 12
                                && !usesNativeOnePlusBackEffect(source)
                                && RtpPlayer.hasEffect(source)) {
                            PENDING_AUDIO_RTP.set(PendingRtp.normal(source));
                            if (LOGGED_AUDIO_RTP.add(source)) {
                                XposedBridge.log(TAG + ": audio-coupled Xiaomi RTP " + source);
                            }
                            return;
                        }
                        PENDING_AUDIO_RTP.remove();
                        int target = mapXiaomiEffect(source);
                        if (target == source) return;

                        param.setResult(target);
                        if (LOGGED_MAPPINGS.add(source)) {
                            XposedBridge.log(TAG + ": map Xiaomi effect "
                                    + source + " -> Oplus effect " + target);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                segmentClass,
                "getEffectStrength",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int source = XposedHelpers.getIntField(
                                param.thisObject, "mEffectId");
                        if (source >= 0 && source <= 12) {
                            // The official seekbar previews native effect 1 at
                            // every progress point. A 20 ms RTP here overlaps
                            // the next request and feels stepped/inconsistent;
                            // keep OnePlus's short calibrated effect 1 and use
                            // a stable grade derived from the official slider.
                            if (source == 1
                                    && AUDIO_RTP_HOOK_INSTALLED
                                    && RtpPlayer.hasPendingEffectStrength(source)) {
                                return;
                            }
                            if (source == 1
                                    && RtpPlayer.consumeEffectStrengthToken(source)) {
                                param.setResult(RtpPlayer.officialStrengthGrade());
                            }
                            return;
                        }
                        if (!isMappedXiaomiEffect(source)
                                || (AudioRtpPlayer.hasEffect(source)
                                        && !usesNativeOnePlusBackEffect(source))
                                || mapXiaomiEffect(source) == source) {
                            return;
                        }
                        int original = (int) param.getResult();
                        int boosted = RtpPlayer.officialStrengthGrade();
                        param.setResult(boosted);
                        if (LOGGED_STRENGTH_BOOSTS.add(source)) {
                            XposedBridge.log(TAG + ": boost Xiaomi effect " + source
                                    + " strength " + original + " -> " + boosted);
                        }
                    }
                });
    }

    /** Play packaged raw RTP through Android's haptic-A audio channel. */
    private static void hookFrameworkAudioRtp(ClassLoader classLoader) {
        AudioRtpPlayer.installHapticChannelCompatibility();
        Class<?> segmentClass = XposedHelpers.findClass(
                "android.os.vibrator.PrebakedSegment", classLoader);
        Class<?> nativeWrapperClass = XposedHelpers.findClass(
                "com.android.server.vibrator.VibratorController$NativeWrapper",
                classLoader);
        Class<?> completionListenerClass = XposedHelpers.findClassIfExists(
                "com.android.server.vibrator.HalVibrator$Callbacks", classLoader);
        final String completionMethod;
        if (completionListenerClass != null) {
            completionMethod = "onVibrationStepComplete";
        } else {
            completionListenerClass = XposedHelpers.findClass(
                    "com.android.server.vibrator.VibratorController$OnVibrationCompleteListener",
                    classLoader);
            completionMethod = "onComplete";
        }
        final Class<?> callbackClass = completionListenerClass;
        XposedHelpers.findAndHookMethod(
                nativeWrapperClass,
                "init",
                int.class,
                callbackClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int vibratorId = (int) param.args[0];
                        Object listener = param.args[1];
                        if (listener != null) {
                            NATIVE_COMPLETION_TARGETS.put(
                                    param.thisObject,
                                    new NativeCompletionTarget(
                                            vibratorId, listener, completionMethod));
                            XposedBridge.log(TAG + ": captured native completion listener for vibrator "
                                    + vibratorId);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                nativeWrapperClass,
                "perform",
                long.class,
                long.class,
                long.class,
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        PendingRtp route = PENDING_AUDIO_RTP.get();
                        PENDING_AUDIO_RTP.remove();
                        if (route == null
                                || (long) param.args[0] != route.requestedEffectId) {
                            return;
                        }

                        long duration = route.sliderPreview
                                ? RtpPlayer.performSliderPreview(
                                        route.assetEffectId, route.requestedEffectId)
                                : RtpPlayer.perform(route.assetEffectId);
                        if (duration <= 0L && route.assetEffectId != 192) {
                            duration = AudioRtpPlayer.perform(route.assetEffectId);
                        }
                        if (duration > 0L) {
                            scheduleNativeCompletion(
                                    param.thisObject,
                                    duration,
                                    (long) param.args[2],
                                    (long) param.args[3]);
                            param.setResult(duration);
                            return;
                        }

                        // The Settings video must never fall back to effect 59;
                        // that produces a few unrelated long thumps.
                        if (route.assetEffectId == 192) {
                            param.setResult(0L);
                            XposedBridge.log(TAG + ": video RTP 192 unavailable; suppress effect 59 fallback");
                            return;
                        }

                        int fallback = mapXiaomiEffect(route.requestedEffectId);
                        param.args[0] = (long) fallback;
                        XposedBridge.log(TAG + ": audio RTP " + route.assetEffectId
                                + " unavailable, fallback -> " + fallback);
                    }
                });
        XposedBridge.hookAllMethods(
                nativeWrapperClass,
                "off",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        RtpPlayer.stop();
                        AudioRtpPlayer.stop();
                    }
                });

        // Android 16/OS4's active AIDL path bypasses NativeWrapper and calls
        // VintfHalVibrator.DefaultHalVibrator.on(..., PrebakedSegment)
        // directly. Intercept this actual transport edge before it asks the
        // OnePlus HAL to perform a private Xiaomi ID.
        Class<?> defaultHalClass = XposedHelpers.findClassIfExists(
                "com.android.server.vibrator.VintfHalVibrator$DefaultHalVibrator",
                classLoader);
        if (defaultHalClass != null) {
            XposedHelpers.findAndHookMethod(
                    defaultHalClass,
                    "on",
                    long.class,
                    long.class,
                    segmentClass,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object segment = param.args[2];
                            int source = XposedHelpers.getIntField(segment, "mEffectId");
                            PendingRtp route = null;
                            if (source == 1
                                    && RtpPlayer.hasPendingEffectStrength(source)) {
                                route = PendingRtp.sliderPreview(source, 162);
                            } else if (source > 12
                                    && !usesNativeOnePlusBackEffect(source)
                                    && RtpPlayer.hasEffect(source)) {
                                route = PendingRtp.normal(source);
                            }
                            if (route == null) return;

                            long duration = route.sliderPreview
                                    ? RtpPlayer.performSliderPreview(
                                            route.assetEffectId, route.requestedEffectId)
                                    : RtpPlayer.perform(route.assetEffectId);
                            if (duration <= 0L && route.assetEffectId != 192) {
                                duration = AudioRtpPlayer.perform(route.assetEffectId);
                            }
                            if (duration <= 0L) {
                                if (route.assetEffectId == 192) {
                                    param.setResult(0L);
                                }
                                return;
                            }

                            Object callbacks = XposedHelpers.getObjectField(
                                    param.thisObject, "mCallbacks");
                            int vibratorId = XposedHelpers.getIntField(
                                    param.thisObject, "mVibratorId");
                            if (callbacks != null) {
                                NATIVE_COMPLETION_TARGETS.put(
                                        param.thisObject,
                                        new NativeCompletionTarget(
                                                vibratorId,
                                                callbacks,
                                                "onVibrationStepComplete"));
                                scheduleNativeCompletion(
                                        param.thisObject,
                                        duration,
                                        (long) param.args[0],
                                        (long) param.args[1]);
                            }
                            param.setResult(duration);
                        }
                    });
            XposedBridge.hookAllMethods(
                    defaultHalClass,
                    "off",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            RtpPlayer.stop();
                            AudioRtpPlayer.stop();
                        }
                    });
            XposedBridge.log(TAG + ": OS4 VINTF prebaked RTP transport active");
        }
        AUDIO_RTP_HOOK_INSTALLED = true;
    }

    /**
     * Mirror Xiaomi's IVibrator HAL completion contract. The K80U HAL returns
     * playLengthMs, sleeps for that duration on a detached worker, then invokes
     * IVibratorCallback.onComplete(). Our RichTap transport only acknowledges
     * file acceptance, so feed the same vibration/step IDs back through the
     * framework listener at the reported end time. The system main looper is
     * reused; no thread, polling loop or wakelock exists while idle.
     */
    private static void scheduleNativeCompletion(
            Object nativeWrapper, long duration, long vibrationId, long stepId) {
        NativeCompletionTarget target = NATIVE_COMPLETION_TARGETS.get(nativeWrapper);
        if (target == null) {
            XposedBridge.log(TAG + ": missing native completion listener for RTP");
            return;
        }
        Handler handler = RTP_COMPLETION_HANDLER;
        if (handler == null) {
            synchronized (HookEntry.class) {
                handler = RTP_COMPLETION_HANDLER;
                if (handler == null) {
                    handler = Handler.createAsync(Looper.getMainLooper());
                    RTP_COMPLETION_HANDLER = handler;
                }
            }
        }
        long delay = Math.max(1L, duration);
        handler.postDelayed(() -> {
            try {
                XposedHelpers.callMethod(
                        target.listener,
                        target.completionMethod,
                        target.vibratorId,
                        vibrationId,
                        stepId);
                XposedBridge.log(TAG + ": framework RTP complete vibration="
                        + vibrationId + " step=" + stepId);
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": failed to notify framework RTP completion");
                XposedBridge.log(throwable);
            }
        }, delay);
    }

    private static final class NativeCompletionTarget {
        final int vibratorId;
        final Object listener;
        final String completionMethod;

        NativeCompletionTarget(int vibratorId, Object listener, String completionMethod) {
            this.vibratorId = vibratorId;
            this.listener = listener;
            this.completionMethod = completionMethod;
        }
    }

    private static final class PendingRtp {
        final int requestedEffectId;
        final int assetEffectId;
        final boolean sliderPreview;

        private PendingRtp(int requestedEffectId, int assetEffectId,
                           boolean sliderPreview) {
            this.requestedEffectId = requestedEffectId;
            this.assetEffectId = assetEffectId;
            this.sliderPreview = sliderPreview;
        }

        static PendingRtp normal(int effectId) {
            return new PendingRtp(effectId, effectId, false);
        }

        static PendingRtp sliderPreview(int requestedEffectId, int assetEffectId) {
            return new PendingRtp(requestedEffectId, assetEffectId, true);
        }
    }

    private static boolean isMappedXiaomiEffect(int effectId) {
        for (int candidate : MAPPED_XIAOMI_EFFECTS) {
            if (candidate == effectId) return true;
        }
        return false;
    }

    private static boolean usesNativeOnePlusBackEffect(int effectId) {
        // These scenes are stronger through the stock OnePlus HAL's calibrated
        // perform() nodes than by replaying the same bytes as generic RTP. At
        // slider zero, retain the private path so it can suppress them fully.
        if (RtpPlayer.currentUserStrength() <= 0.0f) return false;
        switch (effectId) {
            case 88:
            case 106:
            case 114:
            case 163:
            case 188:
            case 203:
            case 410:
                return true;
            default:
                return false;
        }
    }

    /**
     * Mirror Xiaomi's two strength inputs before the compatibility bridge
     * flattens them into the single global OnePlus amplitude.  Raw RTP playback
     * can then obey the same slider and retain per-effect calibration.
     */
    private static void hookXiaomiHapticStrengthState(ClassLoader classLoader) {
        Class<?> interfaceClass = XposedHelpers.findClass(
                "vendor.hardware.vibratorfeature.IVibratorExt",
                classLoader);
        Class<?> proxyClass = XposedHelpers.findClass(
                "vendor.hardware.vibratorfeature.IVibratorExt$Stub$Proxy",
                classLoader);
        XposedHelpers.findAndHookMethod(
                proxyClass,
                "setAmplitudeExt",
                float.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        RtpPlayer.setXiaomiAmplitude(
                                (float) param.args[0], (int) param.args[1]);
                    }
                });
        XposedHelpers.findAndHookMethod(
                proxyClass,
                "configStrengthForEffect",
                int.class,
                float.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        RtpPlayer.setEffectStrength(
                                (int) param.args[0], (float) param.args[1]);
                    }
                });

        /*
         * OS4 exposes android.hardware.vibrator.IVibrator/default, but its
         * Binder extension is not Xiaomi's IVibratorExt. MIUI therefore keeps
         * mHalExt=null and every slider/config request ends in an NPE before
         * the standard OnePlus HAL performs the effect. Install an in-process
         * implementation only when Xiaomi's real extension is absent. This
         * preserves a genuine Xiaomi HAL on ROMs that provide one and avoids
         * registering a fake VINTF service or touching vendor/odm.
         */
        Class<?> serviceImplClass = XposedHelpers.findClass(
                "com.android.server.vibrator.VibratorManagerServiceImpl",
                classLoader);
        XposedBridge.hookAllMethods(
                serviceImplClass,
                "getHalExt",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object reported = param.getResult();
                        if (reported != null) {
                            try {
                                Object binderObject = XposedHelpers.callMethod(
                                        reported, "asBinder");
                                if (binderObject instanceof android.os.IBinder) {
                                    String descriptor = ((android.os.IBinder) binderObject)
                                            .getInterfaceDescriptor();
                                    if (XIAOMI_HAL_EXT_DESCRIPTOR.equals(descriptor)) {
                                        return;
                                    }
                                    XposedBridge.log(TAG + ": replace incompatible HAL extension "
                                            + descriptor + " with Xiaomi in-process shim");
                                }
                            } catch (Throwable throwable) {
                                XposedBridge.log(TAG
                                        + ": unable to identify HAL extension; using Xiaomi shim");
                            }
                        }

                        Object shim = XIAOMI_HAL_EXT_SHIM;
                        if (shim == null) {
                            synchronized (HookEntry.class) {
                                shim = XIAOMI_HAL_EXT_SHIM;
                                if (shim == null) {
                                    shim = Proxy.newProxyInstance(
                                            interfaceClass.getClassLoader(),
                                            new Class<?>[] {interfaceClass},
                                            (proxy, method, args) -> {
                                                String name = method.getName();
                                                if ("setAmplitudeExt".equals(name)) {
                                                    RtpPlayer.setXiaomiAmplitude(
                                                            ((Number) args[0]).floatValue(),
                                                            ((Number) args[1]).intValue());
                                                    return null;
                                                }
                                                if ("configStrengthForEffect".equals(name)) {
                                                    RtpPlayer.setEffectStrength(
                                                            ((Number) args[0]).intValue(),
                                                            ((Number) args[1]).floatValue());
                                                    return null;
                                                }
                                                if ("asBinder".equals(name)) {
                                                    return XIAOMI_HAL_EXT_SHIM_BINDER;
                                                }
                                                if ("getInterfaceVersion".equals(name)) return 1;
                                                if ("getInterfaceHash".equals(name)) {
                                                    return "c89ccddc3b6396de786662badc42594f02ee4957";
                                                }
                                                if ("toString".equals(name)) {
                                                    return "OnePlus13HyperHaptics.IVibratorExtShim";
                                                }
                                                if ("hashCode".equals(name)) {
                                                    return System.identityHashCode(proxy);
                                                }
                                                if ("equals".equals(name)) return proxy == args[0];

                                                // Dynamic-effect and usage calls remain safe no-ops.
                                                // Existing RTP translation handles supported private IDs.
                                                Class<?> returnType = method.getReturnType();
                                                if (returnType == boolean.class) return false;
                                                if (returnType == byte.class) return (byte) 0;
                                                if (returnType == short.class) return (short) 0;
                                                if (returnType == int.class) return 0;
                                                if (returnType == long.class) return 0L;
                                                if (returnType == float.class) return 0.0f;
                                                if (returnType == double.class) return 0.0d;
                                                if (returnType == char.class) return (char) 0;
                                                return null;
                                            });
                                    XIAOMI_HAL_EXT_SHIM = shim;
                                    XposedBridge.log(TAG
                                            + ": OS4 in-process IVibratorExt shim created");
                                }
                            }
                        }
                        XposedHelpers.setObjectField(param.thisObject, "mHalExt", shim);
                        param.setResult(shim);
                    }
                });
        XposedBridge.log(TAG + ": Xiaomi per-effect strength mirror + OS4 shim active");
    }

    /**
     * Settings has two different haptic-video engines. The main promotional
     * clip starts the complete 192 RTP once, while the sixteen small demo clips
     * use SRT timed-text cues. Keep both routes local to Settings so no native
     * OnePlus effect ID, including 1-12, is replaced globally.
     */
    private static void hookSettingsHapticVideos(ClassLoader classLoader) {
        AudioRtpPlayer.installHapticChannelCompatibility();

        Class<?> mainVideo = XposedHelpers.findClass(
                "com.android.settings.haptic.HapticDemoVideoPreference",
                classLoader);
        // The MediaPlayer rendering-start callback is already the real video
        // clock edge on this port. Start the matching RTP immediately.
        XposedHelpers.setStaticIntField(mainVideo, "VIDEO_FIRST_PLAY_DELAY_TIME", 0);
        XposedHelpers.setStaticIntField(mainVideo, "VIDEO_PLAY_DELAY_TIME", 0);
        XposedHelpers.findAndHookMethod(
                mainVideo,
                "playExtPatternById",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int effectId = (int) param.args[0];
                        if (effectId != 192) return;
                        Context context = (Context) XposedHelpers.callMethod(
                                param.thisObject, "getContext");
                        if (RootRtpPlayer.performVideo(context, effectId)) {
                            param.setResult(null);
                        }
                    }
                });
        hookVideoStops(mainVideo);

        Class<?> gridVideo = XposedHelpers.findClass(
                "com.android.settings.haptic.widget.HapticGridView",
                classLoader);
        XposedHelpers.findAndHookMethod(
                gridVideo,
                "playExtPatternById",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int effectId = (int) param.args[0];
                        // IDs 162/163 now keep Xiaomi's official IDs all the
                        // way through system_server. Their packaged assets are
                        // the clean Xiaomi 15 GestureBack originals, so this
                        // Settings hook must not consume them through the
                        // audio-haptic compatibility path: haptic-A can report
                        // successful playback on this port without driving the
                        // motor. Returning without setResult preserves the
                        // original Vibrator request.
                        if (effectId == 162 || effectId == 163) return;
                        if (!AudioRtpPlayer.hasEffect(effectId)) return;
                        Context context = (Context) XposedHelpers.callMethod(
                                param.thisObject, "getContext");
                        if (AudioRtpPlayer.performSettingsCue(
                                context, effectId, 1.0f)) {
                            param.setResult(null);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                gridVideo,
                "playPatternById",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int constant = (int) param.args[0];
                        int effectId = mapSettingsVideoConstant(constant);
                        if (effectId < 0) return;
                        float gain = settingsVideoConstantGain(constant);
                        Context context = (Context) XposedHelpers.callMethod(
                                param.thisObject, "getContext");
                        if (AudioRtpPlayer.performSettingsCue(
                                context, effectId, gain)) {
                            param.setResult(null);
                        }
                    }
                });
        hookVideoStops(gridVideo);
        XposedBridge.log(TAG + ": Settings Xiaomi haptic-video parser active");
    }

    /** Add a Settings.System-backed 0-150% gain control to Xiaomi's haptic page. */
    private static void hookSettingsRtpStrength(ClassLoader classLoader) {
        Class<?> fragmentClass = XposedHelpers.findClass(
                "com.android.settings.haptic.HapticFragment", classLoader);
        XposedHelpers.findAndHookMethod(
                fragmentClass,
                "onCreate",
                android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param)
                            throws Throwable {
                        Object fragment = param.thisObject;
                        Object existing = XposedHelpers.callMethod(
                                fragment, "findPreference", RTP_GAIN_PREFERENCE);
                        if (existing != null) return;

                        Context context = (Context) XposedHelpers.callMethod(
                                fragment, "getContext");
                        if (context == null) return;
                        Object category = XposedHelpers.callMethod(
                                fragment, "findPreference", "haptic_feedback_category_new");
                        if (category == null) return;

                        Class<?> seekBarClass = XposedHelpers.findClass(
                                "androidx.preference.SeekBarPreference", classLoader);
                        Object preference = XposedHelpers.newInstance(seekBarClass, context);
                        XposedHelpers.callMethod(preference, "setKey", RTP_GAIN_PREFERENCE);
                        XposedHelpers.callMethod(preference, "setTitle", "小米专属 RTP 震动强度");
                        XposedHelpers.callMethod(preference, "setPersistent", false);
                        XposedHelpers.callMethod(preference, "setMin", 0);
                        XposedHelpers.callMethod(preference, "setMax", 150);
                        XposedHelpers.callMethod(preference, "setSeekBarIncrement", 5);
                        try {
                            seekBarClass.getMethod(
                                    "setUpdatesContinuously", boolean.class)
                                    .invoke(preference, true);
                        } catch (Throwable ignored) {
                            // Older AndroidX revisions commit only on release.
                        }
                        int current = Settings.System.getInt(
                                context.getContentResolver(), RTP_GAIN_SETTING, 100);
                        current = Math.max(0, Math.min(150, current));
                        XposedHelpers.callMethod(preference, "setSummary",
                                "当前 " + current
                                        + "%；仅调节小米专属短 RTP，视频、铃声和一加原生触感不受影响");
                        XposedHelpers.callMethod(
                                preference, "setValue", current);

                        Class<?> listenerClass = XposedHelpers.findClass(
                                "androidx.preference.Preference$OnPreferenceChangeListener",
                                classLoader);
                        Object listener = Proxy.newProxyInstance(
                                classLoader,
                                new Class<?>[]{listenerClass},
                                (proxy, method, args) -> {
                                    if ("onPreferenceChange".equals(method.getName())
                                            && args != null
                                            && args.length >= 2
                                            && args[1] instanceof Number) {
                                        int percent = Math.max(0, Math.min(
                                                150, ((Number) args[1]).intValue()));
                                        Settings.System.putInt(
                                                context.getContentResolver(),
                                                RTP_GAIN_SETTING,
                                                percent);
                                        previewXiaomiRtpStrength(context, percent);
                                        XposedHelpers.callMethod(args[0], "setSummary",
                                                "当前 " + percent
                                                        + "%；仅调节小米专属短 RTP，视频、铃声和一加原生触感不受影响");
                                        XposedBridge.log(TAG + ": Xiaomi RTP gain="
                                                + percent + "%");
                                        return true;
                                    }
                                    return false;
                                });
                        XposedHelpers.callMethod(
                                preference, "setOnPreferenceChangeListener", listener);
                        XposedHelpers.callMethod(category, "addPreference", preference);
                        XposedBridge.log(TAG + ": Xiaomi RTP strength preference added");
                    }
                });
    }

    /**
     * Xiaomi's stock infinity seekbar maps its visual 0..100 range to only
     * 0.0..1.0.  This port previously used 1.5 at the top end; touching the
     * unmodified seekbar silently reduced that value back to 1.0. Preserve the
     * full OnePlus range while keeping the same visual scale and zero point.
     */
    private static void hookSettingsNativeHapticStrengthRange(ClassLoader classLoader) {
        Class<?> seekBarClass = XposedHelpers.findClass(
                "com.android.settings.widget.MiuiHapticInfinitySeekBar",
                classLoader);
        XposedHelpers.findAndHookMethod(
                seekBarClass,
                "progressToLevel",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        float stock = (float) param.getResult();
                        param.setResult(Math.max(0.0f, Math.min(1.5f, stock * 1.5f)));
                    }
                });
        XposedBridge.log(TAG + ": native haptic seekbar range 0.0..1.5 active");
    }

    /** Audition the approved Xiaomi 162 pulse while the private slider moves. */
    private static void previewXiaomiRtpStrength(Context context, int percent) {
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator == null) return;
        if (percent <= 0) {
            vibrator.cancel();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long previous = LAST_RTP_STRENGTH_PREVIEW.get();
        if (now - previous < 65L
                || !LAST_RTP_STRENGTH_PREVIEW.compareAndSet(previous, now)) {
            return;
        }
        vibrator.cancel();
        playOnVibrator(
                vibrator,
                context,
                162,
                STRENGTH_MEDIUM,
                "xiaomi-rtp-strength-preview");
    }

    private static void hookVideoStops(Class<?> videoClass) {
        for (String method : new String[]{
                "stopPlayingVideo", "releaseMedia", "onDestroy", "onStop", "onPageChange"
        }) {
            if (XposedBridge.hookAllMethods(
                    videoClass,
                    method,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            RootRtpPlayer.stop();
                            AudioRtpPlayer.stop();
                            try {
                                Context context = (Context) XposedHelpers.callMethod(
                                        param.thisObject, "getContext");
                                Vibrator vibrator = context == null
                                        ? null
                                        : context.getSystemService(Vibrator.class);
                                if (vibrator != null) vibrator.cancel();
                            } catch (Throwable throwable) {
                                XposedBridge.log(TAG
                                        + ": failed to cancel Settings video vibration");
                                XposedBridge.log(throwable);
                            }
                            XposedBridge.log(TAG + ": Settings haptic video stop via "
                                    + param.method.getName());
                        }
                    }).isEmpty()) {
                // Different Settings revisions expose only a subset of these
                // lifecycle methods; absence is expected and harmless.
            }
        }
    }

    /** Map MIUIX private demo constants to Xiaomi-origin short RTP samples. */
    private static int mapSettingsVideoConstant(int constant) {
        switch (constant) {
            case 0x10000005: return 5;   // MIUI_MESH_HEAVY -> heavyClick
            case 0x10000006: return 0;   // MIUI_MESH_NORMAL -> click
            case 0x10000007: return 2;   // MIUI_MESH_LIGHT -> tick
            case 0x10000008: return 3;   // MIUI_LONG_PRESS -> thud
            case 0x10000009: return 4;   // MIUI_POPUP_NORMAL -> pop
            case 0x1000000a: return 4;   // MIUI_POPUP_LIGHT -> lighter pop
            case 0x1000000b: return 3;   // MIUI_PICK_UP -> short thud
            case 0x1000000f: return 5;   // MIUI_HOLD -> heavyClick
            case 0x10000010: return 201; // MIUI_BOUNDARY_SPATIAL
            case 0x10000011: return 2;   // MIUI_BOUNDARY_TIME -> tick
            case 0x10000015: return 2;   // MIUI_GEAR_LIGHT -> tick
            default: return -1;
        }
    }

    private static float settingsVideoConstantGain(int constant) {
        switch (constant) {
            case 0x10000007: // MIUI_MESH_LIGHT
            case 0x1000000a: // MIUI_POPUP_LIGHT
            case 0x10000015: // MIUI_GEAR_LIGHT
                return 0.72f;
            case 0x10000005: // MIUI_MESH_HEAVY
            case 0x1000000f: // MIUI_HOLD
                return 1.12f;
            default:
                return 1.0f;
        }
    }

    /** Closest stable OnePlus 13 effects, selected by semantics, duration and envelope. */
    private static int mapXiaomiEffect(int source) {
        switch (source) {
            case 72: return 316; // gesture up
            case 73: return 55;  // FOD planet
            case 74:
            case 75: return 108; // wired/wireless charge
            case 76:
            case 82: return 12;  // unlock/face failure
            case 77: return 106;
            case 78: return 69;
            case 79: return 368;
            case 80: return 55;
            case 81: return 103;
            case 83: return 370;
            case 84: return 0;
            case 85: return 318; // screenshot
            case 86: return 9;
            case 87: return 62;
            case 88: return 2;   // native calibrated short click
            case 90:
            case 93: return 10;  // task/notification clean all
            case 91: return 303;
            case 92: return 105;
            case 96: return 101;
            case 98: return 41;
            case 106: return 47; // native calibrated four-pulse cadence
            case 114: return 316;// louder native calibrated three-pulse impact

            case 157: return 367;
            case 158: return 104;
            case 159: return 103; // FOD ripple
            case 160: return 366;
            case 161: return 60;
            case 162: return 7;   // native fallback if private RTP cannot start
            case 163: return 2;   // native quick-back click at the selected strength
            case 164: return 61;
            case 165:
            case 174: return 12;  // negative feedback
            case 166: return 2;
            case 167: return 46;  // positive feedback
            case 168: return 11;
            case 169: return 54;  // lockdown
            case 170: return 316;
            case 171: return 362; // todo all done
            case 172: return 101;
            case 173: return 122;
            case 175: return 100;
            case 176: return 101;
            case 177: return 64;
            case 178: return 0;
            case 179: return 62;
            case 180: return 100;
            case 181: return 112;
            case 182: return 100;
            case 183: return 310;
            case 184: return 105;
            case 185: return 100;
            case 186: return 105;
            case 187: return 363;
            case 188: return 11; // native calibrated release impulse
            case 189: return 122;
            case 190: return 308;
            case 191: return 55;
            case 192: return 59;  // long haptic-video approximation

            case 201: return 303;
            case 202:
            case 203: return 11;
            case 204: return 363;
            case 205: return 362;
            case 206: return 46;
            case 207: return 12;
            case 208: return 106;
            case 209: return 315; // screen lock
            case 210: return 109; // fingerprint unlock
            case 211: return 108;
            case 212:
            case 213: return 10;  // notification/process cleanup
            case 214: return 101;
            case 215: return 60;
            case 216: return 100;
            case 217: return 305;

            case 401: return 106;
            case 402: return 363;
            case 403: return 12;
            case 404: return 363;
            case 405: return 11;
            case 406: return 303;
            case 407: return 101;
            case 408:
            case 410: return 11;
            default: return source;
        }
    }

    /** Keep exact SystemUI aliases for deterministic strength and compatibility. */
    private static void hookExtendedHaptics(ClassLoader classLoader) {
        Class<?> hapticClass = XposedHelpers.findClassIfExists(
                "com.miui.systemui.functions.HapticFeedBackImpl", classLoader);
        if (hapticClass == null) {
            watchForDeferredSystemUiClasses();
            XposedBridge.log(TAG + ": waiting for HapticFeedBackImpl class loader");
            return;
        }
        installExtendedHapticsHook(hapticClass);
    }

    private static void installExtendedHapticsHook(Class<?> hapticClass) {
        if (!EXTENDED_HAPTICS_HOOKED.compareAndSet(false, true)) return;
        XposedHelpers.findAndHookMethod(
                hapticClass,
                "extExtHapticFeedback",
                int.class,
                int.class,
                String.class,
                int.class,
                Handler.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int primary = (int) param.args[0];
                        int fallback = (int) param.args[1];
                        Handler handler = (Handler) param.args[4];

                        if (primary == 1
                                && fallback == -1
                                && "long_press".equals(param.args[2])) {
                            // HapticFeedbackUtil rejects module-private IDs
                            // before they reach system_server. Submit the exact
                            // scene ourselves while retaining Xiaomi's Handler
                            // timing and the global haptics-enabled checks.
                            postNotificationLongPress(handler);
                            param.setResult(null);
                            return;
                        }

                        if (primary == 212 && fallback == 93) {
                            postHaptic(
                                    handler,
                                    EFFECT_CLEAR_NOTIFICATIONS,
                                    STRENGTH_LIGHT,
                                    "clear-notifications");
                            param.setResult(null);
                            return;
                        }

                        boolean successV2 = primary == 210;
                        boolean successLegacy = primary == -1 && fallback == 166;
                        if (successV2 || successLegacy) {
                            postFingerprintSuccess(handler, "fingerprint-success-ext");
                            param.setResult(null);
                            return;
                        }

                        if (primary == 207 && fallback == 165) {
                            postHaptic(
                                    handler,
                                    EFFECT_FINGERPRINT_FAILURE,
                                    STRENGTH_STRONG,
                                    "fingerprint-failure-ext");
                            param.setResult(null);
                        }
                    }
                });
        XposedBridge.log(TAG + ": extended SystemUI haptics hook active");
    }

    /**
     * Command 103 is the definitive authentication-success callback in the
     * Xiaomi FOD manager. It remains present when the optional animation path
     * stops calling extExtHapticFeedback after SystemUI has run for a while.
     */
    private static void hookFingerprintAuthenticated(ClassLoader classLoader) {
        Class<?> managerClass = XposedHelpers.findClassIfExists(
                "com.miui.keyguard.biometrics.fod.MiuiGxzwManager", classLoader);
        if (managerClass == null) {
            watchForDeferredSystemUiClasses();
            XposedBridge.log(TAG + ": waiting for MiuiGxzwManager class loader");
            return;
        }
        installFingerprintAuthenticatedHook(managerClass);
    }

    private static void installFingerprintAuthenticatedHook(Class<?> managerClass) {
        if (!FINGERPRINT_AUTH_HOOKED.compareAndSet(false, true)) return;
        XposedHelpers.findAndHookMethod(
                managerClass,
                "dealCallback",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if ((int) param.args[0] != 103) return;

                        try {
                            int unlockMode = XposedHelpers.getIntField(
                                    param.thisObject, "mGxzwUnlockMode");
                            if (unlockMode != 1 && unlockMode != 2) return;
                            Handler handler = (Handler) XposedHelpers.getObjectField(
                                    param.thisObject, "mHandler");
                            postFingerprintSuccess(handler, "fingerprint-success-auth");
                        } catch (Throwable throwable) {
                            XposedBridge.log(TAG + ": failed fingerprint auth callback");
                            XposedBridge.log(throwable);
                        }
                    }
                });
        XposedBridge.log(TAG + ": fingerprint auth hook active");
    }

    /** SystemUI loads MIUI feature jars after the package callback. */
    private static void watchForDeferredSystemUiClasses() {
        if (!SYSTEM_UI_CLASS_WATCHER.compareAndSet(false, true)) return;
        XposedBridge.hookAllMethods(
                ClassLoader.class,
                "loadClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.getResult() instanceof Class<?>)) return;
                        String name = param.args.length > 0 && param.args[0] instanceof String
                                ? (String) param.args[0]
                                : null;
                        if ("com.miui.systemui.functions.HapticFeedBackImpl".equals(name)) {
                            installExtendedHapticsHook((Class<?>) param.getResult());
                        } else if ("com.miui.keyguard.biometrics.fod.MiuiGxzwManager"
                                .equals(name)) {
                            installFingerprintAuthenticatedHook((Class<?>) param.getResult());
                        }
                    }
                });
    }

    /** Replace the FOD failure runnable's 24 ms one-shot. */
    private static void hookFingerprintFailure() {
        XposedHelpers.findAndHookMethod(
                Vibrator.class,
                "vibrate",
                long.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if ((long) param.args[0] != 24L
                                || !stackContains(
                                "com.miui.keyguard.biometrics.fod."
                                        + "MiuiGxzwAnimView$$ExternalSyntheticLambda22",
                                "run")) {
                            return;
                        }

                        playOnVibrator(
                                (Vibrator) param.thisObject,
                                currentContext(),
                                EFFECT_FINGERPRINT_FAILURE,
                                STRENGTH_STRONG,
                                "fingerprint-failure");
                        param.setResult(null);
                    }
                });
    }

    private static void postFingerprintSuccess(Handler handler, String reason) {
        long now = SystemClock.elapsedRealtime();
        while (true) {
            long previous = LAST_FINGERPRINT_SUCCESS.get();
            if (now - previous < 350L) return;
            if (LAST_FINGERPRINT_SUCCESS.compareAndSet(previous, now)) break;
        }
        postHaptic(handler, EFFECT_FINGERPRINT_SUCCESS, STRENGTH_STRONG, reason);
    }

    /**
     * Native effect 5 carries the actuator-specific calibration that is lost
     * when its bytes are replayed through generic RTP. Convert the dedicated
     * Xiaomi slider to the three strength levels accepted by perform().
     */
    private static void postNotificationLongPress(Handler handler) {
        Runnable action = () -> {
            Context context = currentContext();
            if (context == null) return;
            float official = RtpPlayer.currentUserStrength();
            if (official <= 0.0f) return;
            int strength = RtpPlayer.officialStrengthGrade();
            playPredefined(
                    context,
                    EFFECT_FINGERPRINT_SUCCESS,
                    strength,
                    "notification-long-press-native");
        };
        if (handler == null || handler.getLooper().isCurrentThread()) {
            action.run();
        } else {
            handler.post(action);
        }
    }

    private static void postHaptic(
            Handler handler, int effectId, int strength, String reason) {
        Runnable action = () -> playPredefined(
                currentContext(), effectId, strength, reason);
        if (handler == null || handler.getLooper().isCurrentThread()) {
            action.run();
        } else {
            handler.post(action);
        }
    }

    private static boolean playPredefined(
            Context context, int effectId, int strength, String reason) {
        if (context == null || !hapticsEnabled(context)) return false;
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        return playOnVibrator(vibrator, context, effectId, strength, reason);
    }

    private static boolean playOnVibrator(
            Vibrator vibrator,
            Context context,
            int effectId,
            int strength,
            String reason) {
        if (vibrator == null || context == null || !hapticsEnabled(context)) {
            return false;
        }

        try {
            VibrationEffect effect = VibrationEffect.createPredefined(effectId);
            try {
                Object adjusted = XposedHelpers.callMethod(
                        effect, "applyEffectStrength", strength);
                if (adjusted instanceof VibrationEffect) {
                    effect = (VibrationEffect) adjusted;
                }
            } catch (Throwable ignored) {
                // Keep the framework default if this hidden method changes.
            }
            vibrator.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(
                            VibrationAttributes.USAGE_TOUCH));
            XposedBridge.log(TAG + ": " + reason
                    + " -> effect=" + effectId + " strength=" + strength);
            return true;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": failed " + reason);
            XposedBridge.log(throwable);
            return false;
        }
    }

    private static boolean hapticsEnabled(Context context) {
        try {
            int feedback = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED,
                    1);
            int disabled = Settings.System.getInt(
                    context.getContentResolver(),
                    "haptic_feedback_disable",
                    0);
            int vibrate = Settings.System.getInt(
                    context.getContentResolver(),
                    Settings.System.VIBRATE_ON,
                    1);
            return feedback != 0 && disabled == 0 && vibrate != 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static Context currentContext() {
        return AndroidAppHelper.currentApplication();
    }

    private static void installHook(String name, HookInstaller installer) {
        try {
            installer.install();
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": failed to install " + name);
            XposedBridge.log(throwable);
        }
    }

    private static boolean stackContains(String className, String methodName) {
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (className.equals(element.getClassName())
                    && methodName.equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }

    private interface HookInstaller {
        void install() throws Throwable;
    }
}
