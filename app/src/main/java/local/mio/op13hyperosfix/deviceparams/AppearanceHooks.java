package local.mio.op13hyperosfix.deviceparams;

import android.content.Context;
import android.view.View;

import java.lang.reflect.Field;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class AppearanceHooks {
    private static final String TAG = "COSOS4Beauty: ";
    /**
     * The OS4 background controller posts its painter setup from onCreateView.
     * Keep the page decision here so the downstream painter hook does not have
     * to depend on an Application/ContentProvider being ready during startup.
     */
    private static volatile boolean forceDarkPageActive;
    private AppearanceHooks() {
    }

    static void install(final Class<?> targetFragment, Class<?> bgDataManager,
                        ClassLoader loader) {
        if (targetFragment == null) {
            XposedBridge.log(TAG + "target fragment unavailable; beauty/music disabled");
            return;
        }
        hookFragmentLifecycle(targetFragment);
        if (bgDataManager != null) hookBackgroundData(bgDataManager);
        hookBackgroundPainter(loader);
        XposedBridge.log(TAG + "page hooks installed for " + targetFragment.getName());
    }

    private static void hookFragmentLifecycle(final Class<?> target) {
        XposedBridge.hookAllMethods(target, "onCreateView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                try {
                    Object result = param.getResult();
                    if (!(result instanceof View)) return;
                    final View root = (View) result;
                    applyPage(param.thisObject, root);
                    root.post(new Runnable() {
                        @Override
                        public void run() {
                            applyPage(param.thisObject, root);
                        }
                    });
                    root.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyPage(param.thisObject, root);
                        }
                    }, 350L);
                    root.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            applyPage(param.thisObject, root);
                        }
                    }, 900L);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "onCreateView styling fail-open: " + t);
                }
            }
        });

        XposedBridge.hookAllMethods(target, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(final MethodHookParam param) {
                try {
                    Context context = RuntimeUtils.findContext(param.thisObject);
                    HookConfig config = HookConfig.load(context);
                    if (config.beautyEnabled) {
                        final View root = getFragmentView(param.thisObject);
                        if (root != null) {
                            applyPage(param.thisObject, root);
                            root.postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    applyPage(param.thisObject, root);
                                }
                            }, 250L);
                        }
                    }
                    MusicController.onResume(param.thisObject, context);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + "onResume fail-open: " + t);
                }
            }
        });

        XposedBridge.hookAllMethods(target, "onPause", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                forceDarkPageActive = false;
                MusicController.onPause(param.thisObject);
                CustomAppearanceRenderer.restoreWindow(
                        param.thisObject, getFragmentView(param.thisObject));
            }
        });
        XposedBridge.hookAllMethods(target, "onDestroyView", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                MusicController.stopNow(param.thisObject);
                CustomAppearanceRenderer.releasePage(getFragmentView(param.thisObject));
            }
        });

        // OS4 resets the overlay-mask config whenever the version card crosses
        // the collapsed ActionBar boundary. Re-apply the page-scoped night
        // chrome after that reset without replacing PreferenceFragment's
        // Context (doing so breaks MIUIX FrameDecoration on recent builds).
        XposedBridge.hookAllMethods(target, "applyMaskConfigByVersionCardState",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        CustomAppearanceRenderer.refreshForcedDarkChrome(
                                param.thisObject, getFragmentView(param.thisObject));
                    }
                });
        XposedBridge.hookAllMethods(target, "lambda$onResume$2", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                CustomAppearanceRenderer.refreshForcedDarkChrome(
                        param.thisObject, getFragmentView(param.thisObject));
            }
        });
    }

    private static void hookBackgroundData(final Class<?> manager) {
        try {
            XposedBridge.hookAllConstructors(manager, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context context = RuntimeUtils.currentApplication();
                    HookConfig config = HookConfig.load(context);
                    if (config.forceCustomDark || forceDarkPageActive) {
                        mirrorDarkBackground(param.thisObject);
                    } else if (config.customDual) {
                        applyLightBackgroundPreset(param.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(manager, "getData", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        HookConfig config = HookConfig.load(RuntimeUtils.currentApplication());
                        if (!(config.forceCustomDark || forceDarkPageActive)
                                || param.args == null
                                || param.args.length < 2 || !(param.args[1] instanceof Enum)) {
                            return;
                        }
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        Enum<?> dark = Enum.valueOf((Class) param.args[1].getClass(), "DARK");
                        param.args[1] = dark;
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "dark shader selection fail-open: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        HookConfig config = HookConfig.load(RuntimeUtils.currentApplication());
                        if (!(config.forceCustomDark || forceDarkPageActive)) return;
                        Object dark = darkDataFor(param.thisObject,
                                param.args != null && param.args.length > 0
                                        ? param.args[0] : null);
                        if (dark != null) param.setResult(dark);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "dark shader result override fail-open: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "background hooks unavailable: " + t);
        }
    }

    /** Force the final OS4 painter input. This is later and more reliable than
     * only changing BgEffectDataManager#getData, especially after mode changes. */
    private static void hookBackgroundPainter(ClassLoader loader) {
        try {
            Class<?> painter = Class.forName(
                    "com.android.settings.device.BgEffectPainter", false, loader);
            XposedBridge.hookAllMethods(painter, "setType", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        HookConfig config = HookConfig.load(RuntimeUtils.currentApplication());
                        if (!(config.forceCustomDark || forceDarkPageActive)
                                || param.args == null || param.args.length < 2
                                || !(param.args[1] instanceof Enum)) {
                            return;
                        }
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        Enum<?> dark = Enum.valueOf((Class) param.args[1].getClass(), "DARK");
                        param.args[1] = dark;
                        XposedBridge.log(TAG + "OS4 painter ThemeMode forced to DARK");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "dark painter selection fail-open: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + "OS4 painter hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "OS4 painter hook unavailable: " + t);
        }
    }

    private static void applyPage(Object fragment, View root) {
        Context context = root.getContext();
        HookConfig config = HookConfig.load(context);
        forceDarkPageActive = config.forceCustomDark;
        if (!config.hasAppearanceMode()) return;
        CustomAppearanceRenderer.apply(fragment, root, config);
    }

    private static Object darkDataFor(Object manager, Object deviceType) throws Exception {
        String name = deviceType == null ? "" : String.valueOf(deviceType);
        String field = "TABLET".equals(name) ? "dataPadDark" : "dataPhoneDark";
        return RuntimeUtils.findField(manager.getClass(), field).get(manager);
    }

    private static void mirrorDarkBackground(Object manager) {
        try {
            Field phoneLight = RuntimeUtils.findField(manager.getClass(), "dataPhoneLight");
            Field phoneDark = RuntimeUtils.findField(manager.getClass(), "dataPhoneDark");
            Field padLight = RuntimeUtils.findField(manager.getClass(), "dataPadLight");
            Field padDark = RuntimeUtils.findField(manager.getClass(), "dataPadDark");
            phoneLight.set(manager, phoneDark.get(manager));
            padLight.set(manager, padDark.get(manager));
            XposedBridge.log(TAG + "OS4 force-dark background objects mirrored");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dark background mirror skipped on this build: " + t);
        }
    }

    private static View getFragmentView(Object fragment) {
        try {
            Object value = fragment.getClass().getMethod("getView").invoke(fragment);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void applyLightBackgroundPreset(Object manager) {
        try {
            Object light = RuntimeUtils.findField(manager.getClass(), "dataPhoneLight")
                    .get(manager);
            if (light == null) return;
            Field pointsField = RuntimeUtils.findField(light.getClass(), "uPoints");
            float[] points = (float[]) pointsField.get(light);
            if (points == null || points.length != 12) return;
            points[4] = 1.75f;
            points[7] = 2.10f;
            pointsField.set(light, points);
            RuntimeUtils.findField(light.getClass(), "uPointRadiusMulti")
                    .setFloat(light, 1.25f);
            RuntimeUtils.findField(light.getClass(), "uPointOffset")
                    .setFloat(light, 0.28f);
            XposedBridge.log(TAG + "OS4 light shader full-screen preset applied");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "light shader preset skipped on this build: " + t);
        }
    }
}
