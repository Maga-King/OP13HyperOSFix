package local.mio.op13hyperosfix;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;

final class SettingsHooks {
    private SettingsHooks() {
    }

    static void install(ClassLoader loader) {
        ManageAllNotificationsHooks.installSettings(loader);
        NotificationSettingsHooks.installSettings(loader);
        FeatureFlagHooks.forceTrue(loader, Set.of("support_gesture_wakeup"), "settings");
        String[] candidates = {
                "com.android.settings.aod.AodAndLockScreenSettings",
                "com.android.settings.AodAndLockScreenSettings",
                "com.android.settings.display.AodAndLockScreenSettings"
        };
        for (String name : candidates) {
            Class<?> target = Reflect.findClass(loader, name);
            if (target == null) {
                continue;
            }
            int count = Reflect.hookNamedMethods(target, "isSupportPickupWakeup",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    });
            HookLog.info("Settings pickup capability hooks=" + count + " in " + name);
            break;
        }
    }
}
