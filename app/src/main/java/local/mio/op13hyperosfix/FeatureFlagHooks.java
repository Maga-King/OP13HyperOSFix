package local.mio.op13hyperosfix;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;

final class FeatureFlagHooks {
    private FeatureFlagHooks() {
    }

    static void forceTrue(ClassLoader loader, Set<String> keys, String owner) {
        Class<?> featureParser = Reflect.findClass(loader, "miui.util.FeatureParser");
        if (featureParser == null) {
            HookLog.once(owner + "_feature_missing", owner + ": FeatureParser missing");
            return;
        }
        int count = Reflect.hookNamedMethods(featureParser, "getBoolean", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length > 0 && param.args[0] instanceof String
                        && keys.contains(param.args[0])) {
                    param.setResult(true);
                }
            }
        });
        HookLog.once(owner + "_feature_hook", owner + ": FeatureParser hooks=" + count
                + ", keys=" + keys);
    }
}
