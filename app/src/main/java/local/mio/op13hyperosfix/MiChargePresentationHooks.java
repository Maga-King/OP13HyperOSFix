package local.mio.op13hyperosfix;

import android.provider.Settings;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Enables Xiaomi's own W MAX presentation without replacing charge models. */
final class MiChargePresentationHooks {
    private static final String DOUBLE_CHARGE_PROPERTY =
            "persist.vendor.accelerate.charge";
    private static final String FAST_CHARGE_SETTING = "key_fast_charge_enabled";
    private static final AtomicBoolean MAX_FALLBACK_REPORTED = new AtomicBoolean();

    private MiChargePresentationHooks() {
    }

    static void install(ClassLoader loader) {
        int propertyHooks = hookDoubleChargeProperty();
        int settingHooks = hookFastChargeSetting();

        // The property hook handles normal class initialization. This fallback
        // covers SystemUI builds that initialized ChargeUtils unusually early.
        Class<?> chargeUtils = Reflect.findClass(loader, "com.miui.charge.ChargeUtils");
        if (chargeUtils != null) {
            forceDoubleCharge(chargeUtils);
        }
        int controllerHooks = hookFastChargeController(loader);
        int closedUiHooks = hookDoubleChargeClosedUi(chargeUtils);
        int modelHooks = hookChargeModel(loader, chargeUtils);
        HookLog.info("MiCharge W MAX presentation installed: properties="
                + propertyHooks + ", settings=" + settingHooks
                + ", controllers=" + controllerHooks
                + ", closedUi=" + closedUiHooks + ", models=" + modelHooks);
    }

    private static int hookDoubleChargeProperty() {
        Class<?> systemProperties = Reflect.findClass(null,
                "android.os.SystemProperties");
        Method method = Reflect.findMethod(systemProperties, "getBoolean",
                String.class, boolean.class);
        return Reflect.hookMethodOnce(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length >= 1
                        && DOUBLE_CHARGE_PROPERTY.equals(param.args[0])) {
                    param.setResult(true);
                }
            }
        }) ? 1 : 0;
    }

    private static int hookFastChargeSetting() {
        return Reflect.hookNamedMethods(Settings.Secure.class, "getInt",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length >= 2
                                && FAST_CHARGE_SETTING.equals(param.args[1])) {
                            param.setResult(1);
                        }
                    }
                });
    }

    private static int hookFastChargeController(ClassLoader loader) {
        Class<?> controller = Reflect.findClass(loader,
                "com.miui.charge.MiuiChargeController");
        return Reflect.hookNamedMethods(controller, "onContentChanged",
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length >= 2
                                && FAST_CHARGE_SETTING.equals(param.args[0])) {
                            param.args[1] = "1";
                        }
                    }
                });
    }

    private static int hookDoubleChargeClosedUi(Class<?> chargeUtils) {
        Method method = Reflect.findMethod(chargeUtils,
                "shouldShowDoubleChargeClosedUI");
        return Reflect.hookMethodOnce(method,
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // The transplanted Oplus base cannot provide Xiaomi's
                        // charger-verification bit. Keep the official enabled
                        // branch so TurboView uses its gold "W MAX" view.
                        param.setResult(false);
                    }
                }) ? 1 : 0;
    }

    private static int hookChargeModel(ClassLoader loader, Class<?> chargeUtils) {
        Class<?> listener = Reflect.findClass(loader,
                "com.android.systemui.devicenotification.listener.DeviceNotificationListenerImpl");
        return Reflect.hookNamedMethods(listener, "structModelForCharge",
                new XC_MethodHook(XC_MethodHook.PRIORITY_HIGHEST) {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isHighPowerModel(param)) {
                            forceDoubleCharge(chargeUtils);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isHighPowerModel(param) || param.getResult() == null) {
                            return;
                        }
                        appendMaxIfNeeded(param);
                    }
                });
    }

    private static boolean isHighPowerModel(XC_MethodHook.MethodHookParam param) {
        return param.args.length >= 2 && param.args[1] instanceof Number
                && ((Number) param.args[1]).intValue() == 3;
    }

    private static void forceDoubleCharge(Class<?> chargeUtils) {
        if (chargeUtils == null) {
            return;
        }
        try {
            XposedHelpers.setStaticBooleanField(chargeUtils,
                    "SUPPORT_DOUBLE_CHARGE", true);
        } catch (Throwable ignored) {
            // The result-level fallback below also covers final ART fields.
        }
    }

    private static void appendMaxIfNeeded(XC_MethodHook.MethodHookParam param) {
        try {
            Object model = param.getResult();
            Object left = Reflect.call(model, "getLeft");
            Object textParams = Reflect.call(left, "getTextParams");
            Object rawText = Reflect.call(textParams, "getText");
            if (!(rawText instanceof String)) {
                return;
            }
            String text = (String) rawText;
            if (!text.endsWith("W") || text.endsWith("W MAX")) {
                return;
            }
            String maxText = text + " MAX";
            if (!Reflect.set(textParams, maxText, "text")) {
                Object patchedText = Reflect.call(textParams, "copy", maxText,
                        Reflect.call(textParams, "getTextColor"),
                        Reflect.call(textParams, "getTurnAnim"));
                Object patchedLeft = Reflect.call(left, "copy", patchedText,
                        Reflect.call(left, "getIconParams"));
                Object patchedModel = Reflect.call(model, "copy", patchedLeft,
                        Reflect.call(model, "getRight"),
                        Reflect.call(model, "getGlowEffect"));
                param.setResult(patchedModel);
            }
            if (MAX_FALLBACK_REPORTED.compareAndSet(false, true)) {
                HookLog.info("MiCharge W MAX result verified: " + maxText);
            }
        } catch (Throwable error) {
            HookLog.once("micharge_max_result_failed",
                    "MiCharge W MAX result fallback failed: " + error);
        }
    }
}
