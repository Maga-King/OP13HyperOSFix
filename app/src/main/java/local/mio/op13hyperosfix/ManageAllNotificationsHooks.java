package local.mio.op13hyperosfix;

import java.util.HashSet;

import de.robv.android.xposed.XC_MethodHook;

final class ManageAllNotificationsHooks {
    private static final String[] SETTINGS_CLASSES = {
            "com.android.settings.notification.AppNotificationSettings",
            "com.android.settings.notification.ChannelNotificationSettings",
            "com.android.settings.notification.app.AppNotificationSettings",
            "com.android.settings.notification.app.ChannelNotificationSettings"
    };

    private ManageAllNotificationsHooks() {
    }

    static void installFramework(ClassLoader loader, String owner) {
        Class<?> channel = Reflect.findClass(loader, "android.app.NotificationChannel");
        if (channel == null) {
            HookLog.once(owner + "_notification_channel_missing",
                    owner + ": NotificationChannel missing");
            return;
        }
        int getterHooks = Reflect.hookNamedMethods(channel, "isBlockable",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(true);
                    }
                });
        int setterHooks = Reflect.hookNamedMethods(channel, "setBlockable",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0) {
                            param.args[0] = true;
                        }
                    }
                });
        HookLog.info(owner + ": manage-all notification channel hooks="
                + getterHooks + "/" + setterHooks);
    }

    static void installSettings(ClassLoader loader) {
        installFramework(loader, "settings");
        hookBlockPreferences(loader);
        hookMiuiForcedNotificationLists(loader);
    }

    private static void hookBlockPreferences(ClassLoader loader) {
        int hooks = 0;
        for (String className : SETTINGS_CLASSES) {
            Class<?> target = Reflect.findClass(loader, className);
            if (target == null) {
                continue;
            }
            hooks += Reflect.hookNamedMethods(target, "setupBlock",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object preference = Reflect.call(param.thisObject,
                                        "findPreference", "block");
                                if (preference != null) {
                                    Reflect.call(preference, "setEnabled", true);
                                }
                            } catch (Throwable error) {
                                HookLog.error("notification block preference unlock failed",
                                        error);
                            }
                        }
                    });
        }
        HookLog.info("settings: notification block preference hooks=" + hooks);
    }

    private static void hookMiuiForcedNotificationLists(ClassLoader loader) {
        Class<?> helper = Reflect.findClass(loader, "miui.util.NotificationFilterHelper");
        if (helper == null) {
            HookLog.once("notification_filter_helper_missing",
                    "settings: NotificationFilterHelper missing");
            return;
        }
        int falseHooks = 0;
        for (String name : new String[]{
                "isNotificationForcedEnabled",
                "isNotificationForcedFor",
                "canSystemNotificationBeBlocked",
                "containNonBlockableChannel"
        }) {
            final boolean result = "canSystemNotificationBeBlocked".equals(name);
            falseHooks += Reflect.hookNamedMethods(helper, name,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(result);
                        }
                    });
        }
        int listHooks = Reflect.hookNamedMethods(helper,
                "getNotificationForcedEnabledList", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(new HashSet<String>());
                    }
                });
        HookLog.info("settings: MIUI forced notification hooks="
                + falseHooks + "/" + listHooks);
    }
}
