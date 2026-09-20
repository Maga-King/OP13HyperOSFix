package local.mio.op13hyperosfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.media.AudioManager;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;

final class VolumeIslandHooks {
    private static final String MODULE_PACKAGE = "local.mio.op13hyperosfix";
    private static final String PLUGIN_PACKAGE_FRAGMENT = "systemui.plugin";
    private static final String SEMANTIC_SILENT_ON = "silent_mode_on";
    private static final String SEMANTIC_SILENT_OFF = "silent_mode_off";
    private static final String ICON_SILENT_ON = "op13_island_silent";
    private static final String ICON_RING = "op13_island_ring";
    private static final String ICON_VIBRATE = "op13_island_vibrate";

    private static final Set<ClassLoader> INSTALLED_LOADERS = Collections.newSetFromMap(
            Collections.synchronizedMap(new WeakHashMap<>()));

    private VolumeIslandHooks() {
    }

    static void install(ClassLoader systemUiLoader) {
        Class<?> factory = Reflect.findClass(systemUiLoader,
                "com.android.systemui.shared.plugins.PluginInstance$PluginFactory",
                "com.android.systemui.shared.plugins.PluginInstance$Factory");
        if (factory == null) {
            HookLog.once("plugin_factory_missing",
                    "SystemUI plugin factory missing; ringer island fix skipped");
            return;
        }

        int loaderHooks = Reflect.hookNamedMethods(factory, "createClassLoader",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isMiuiSystemUiPlugin(param.thisObject)
                                && param.getResult() instanceof ClassLoader) {
                            installPlugin((ClassLoader) param.getResult());
                        }
                    }
                });
        int createHooks = Reflect.hookNamedMethods(factory, "createPlugin",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object plugin = param.getResult();
                        if (isMiuiSystemUiPlugin(param.thisObject) && plugin != null) {
                            installPlugin(plugin.getClass().getClassLoader());
                        }
                    }
                });
        HookLog.info("MIUI plugin loader hooks=" + loaderHooks + "/" + createHooks);
    }

    private static boolean isMiuiSystemUiPlugin(Object factory) {
        Object appInfo = Reflect.get(factory, "pluginAppInfo", "mPluginAppInfo");
        if (appInfo instanceof ApplicationInfo) {
            String packageName = ((ApplicationInfo) appInfo).packageName;
            return packageName != null && packageName.contains(PLUGIN_PACKAGE_FRAGMENT);
        }
        Object packageName = Reflect.get(appInfo, "packageName");
        return packageName instanceof String
                && ((String) packageName).contains(PLUGIN_PACKAGE_FRAGMENT);
    }

    private static void installPlugin(ClassLoader pluginLoader) {
        if (pluginLoader == null || !INSTALLED_LOADERS.add(pluginLoader)) {
            return;
        }
        Class<?> controller = Reflect.findClass(pluginLoader,
                "com.android.systemui.miui.volume.VolumePanelViewController",
                "com.android.systemui.miui.volume.VolumeDialogViewController");
        if (controller == null) {
            INSTALLED_LOADERS.remove(pluginLoader);
            HookLog.once("volume_controller_missing",
                    "MIUI volume controller missing; ringer island fix skipped");
            return;
        }

        int ringerHooks = hookRingerUpdates(controller);
        int publishHooks = hookIslandPublisher(controller);
        int rawHooks = hookRawIslandParams(controller);
        HookLog.info("ringer island hooks=" + ringerHooks + "/" + publishHooks
                + "/" + rawHooks + " class=" + controller.getName());
    }

    private static int hookRingerUpdates(Class<?> controller) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                param.setObjectExtra("op13_old_ringer_mode",
                        Reflect.getInt(param.thisObject, -1, "mRingerMode"));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object oldValue = param.getObjectExtra("op13_old_ringer_mode");
                int oldMode = oldValue instanceof Number ? ((Number) oldValue).intValue() : -1;
                int newMode = currentRingerMode(param.thisObject);
                if (oldMode < 0 || oldMode == newMode) {
                    return;
                }
                // MIUI collapses both modes to isSilent=true, so it emits no feedback here.
                if ((oldMode == AudioManager.RINGER_MODE_SILENT
                        && newMode == AudioManager.RINGER_MODE_VIBRATE)
                        || (oldMode == AudioManager.RINGER_MODE_VIBRATE
                        && newMode == AudioManager.RINGER_MODE_SILENT)) {
                    publishModeIsland(param.thisObject, newMode);
                }
            }
        };
        int count = Reflect.hookNamedMethods(controller, "updateRingerH", callback);
        if (count == 0) {
            count = Reflect.hookNamedMethods(controller, "updateRinger", callback);
        }
        return count;
    }

    private static int hookIslandPublisher(Class<?> controller) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 5 || !(param.args[2] instanceof String)) {
                    return;
                }
                int mode = currentRingerMode(param.thisObject);
                String semantic = (String) param.args[2];
                Context context = contextOf(param.thisObject);
                if (context == null) {
                    return;
                }
                if (mode == AudioManager.RINGER_MODE_NORMAL
                        && SEMANTIC_SILENT_OFF.equals(semantic)) {
                    param.args[0] = modeText(context, mode);
                    param.args[1] = ICON_RING;
                    param.args[3] = resolveColor(context,
                            "miui_dnd_mode_on_color", 0xff8370ff);
                    param.args[4] = MODULE_PACKAGE;
                } else if (mode == AudioManager.RINGER_MODE_VIBRATE
                        && SEMANTIC_SILENT_ON.equals(semantic)) {
                    param.args[0] = modeText(context, mode);
                    param.args[1] = ICON_VIBRATE;
                    param.args[3] = resolveColor(context,
                            "miui_dnd_or_silent_mode_off_color", 0xb3ffffff);
                    param.args[4] = MODULE_PACKAGE;
                } else if (mode == AudioManager.RINGER_MODE_SILENT
                        && SEMANTIC_SILENT_ON.equals(semantic)) {
                    param.args[1] = ICON_SILENT_ON;
                    param.args[3] = resolveColor(context,
                            "miui_dnd_or_silent_mode_on_color", 0xfffa382e);
                    param.args[4] = MODULE_PACKAGE;
                }
            }
        };
        int count = Reflect.hookNamedMethods(controller, "showToastInStatusBar", callback);
        if (count != 0) {
            return count;
        }
        for (Method method : controller.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() == void.class && parameters.length == 5
                    && parameters[0] == String.class && parameters[1] == String.class
                    && parameters[2] == String.class && parameters[3] == int.class
                    && parameters[4] == String.class
                    && Reflect.hookMethodOnce(method, callback)) {
                count++;
            }
        }
        return count;
    }

    private static int hookRawIslandParams(Class<?> controller) {
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.args.length != 3 || !(param.args[1] instanceof String)) {
                    return;
                }
                String semantic = (String) param.args[1];
                int mode = currentRingerMode(param.thisObject);
                String iconName;
                if (mode == AudioManager.RINGER_MODE_NORMAL
                        && SEMANTIC_SILENT_OFF.equals(semantic)) {
                    iconName = ICON_RING;
                } else if (mode == AudioManager.RINGER_MODE_VIBRATE
                        && SEMANTIC_SILENT_ON.equals(semantic)) {
                    iconName = ICON_VIBRATE;
                } else if (mode == AudioManager.RINGER_MODE_SILENT
                        && SEMANTIC_SILENT_ON.equals(semantic)) {
                    iconName = ICON_SILENT_ON;
                } else {
                    return;
                }
                Object result = param.getResult();
                Object left = Reflect.get(result, "left");
                Object icon = Reflect.get(left, "iconParams");
                if (icon == null) {
                    return;
                }
                Reflect.set(icon, iconName, "iconResName");
                Reflect.set(icon, Integer.valueOf(0), "iconType");
                Reflect.set(icon, "svg", "iconFormat");
                Reflect.set(icon, "drawable", "category");
            }
        };
        int count = Reflect.hookNamedMethods(controller, "printStatusBarParamsRaw", callback);
        if (count != 0) {
            return count;
        }
        for (Method method : controller.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3 && parameters[0] == String.class
                    && parameters[1] == String.class && parameters[2] == int.class
                    && method.getReturnType().getName().endsWith("StatusBarGuideParams")
                    && Reflect.hookMethodOnce(method, callback)) {
                count++;
            }
        }
        return count;
    }

    private static int currentRingerMode(Object controller) {
        Object state = Reflect.get(controller, "mState");
        return Reflect.getInt(state, Reflect.getInt(controller, -1, "mRingerMode"),
                "ringerModeInternal", "mRingerModeInternal");
    }

    private static Context contextOf(Object controller) {
        Object context = Reflect.get(controller, "mContext", "context");
        return context instanceof Context ? (Context) context : null;
    }

    private static void publishModeIsland(Object controller, int mode) {
        if (!Reflect.getBoolean(controller, true, "mNeedShowDialog")) {
            return;
        }
        Context context = contextOf(controller);
        if (context == null) {
            return;
        }
        String icon = mode == AudioManager.RINGER_MODE_VIBRATE
                ? ICON_VIBRATE : ICON_SILENT_ON;
        int color = resolveColor(context, "miui_dnd_or_silent_mode_on_color", 0xffffffff);
        try {
            Reflect.call(controller, "showToastInStatusBar", modeText(context, mode), icon,
                    SEMANTIC_SILENT_ON, color, context.getPackageName());
        } catch (Throwable error) {
            HookLog.error("ringer island publish failed", error);
        }
    }

    private static int resolveColor(Context context, String name, int fallback) {
        Resources resources = context.getResources();
        int id = resources.getIdentifier(name, "color", context.getPackageName());
        if (id == 0) {
            return fallback;
        }
        try {
            return context.getColor(id);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String modeText(Context context, int mode) {
        Locale locale = context.getResources().getConfiguration().getLocales().isEmpty()
                ? Locale.getDefault()
                : context.getResources().getConfiguration().getLocales().get(0);
        boolean chinese = "zh".equals(locale.getLanguage());
        if (mode == AudioManager.RINGER_MODE_SILENT) {
            int id = context.getResources().getIdentifier(
                    "miui_silent_mode_on", "string", context.getPackageName());
            if (id != 0) {
                return context.getString(id);
            }
            return chinese ? "\u9759\u97f3\u5df2\u5f00\u542f" : "Silent mode is on";
        }
        if (mode == AudioManager.RINGER_MODE_VIBRATE) {
            return chinese ? "\u632f\u52a8\u5df2\u5f00\u542f" : "Vibration is on";
        }
        return chinese ? "\u54cd\u94c3\u5df2\u5f00\u542f" : "Ring mode is on";
    }
}
