package local.mio.op13hyperosfix.haptics;

import android.app.Application;
import android.content.Context;

import java.io.File;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Resolves the current APK after an in-place module update changes sourceDir. */
final class ModuleApkResolver {
    private static final String TAG = "OnePlus13HyperHaptics";
    private static final String PACKAGE = "local.mio.op13hyperosfix";

    private ModuleApkResolver() {}

    static String resolve(String preferred) {
        if (preferred != null && new File(preferred).isFile()) return preferred;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Context host = (Application) XposedHelpers.callStaticMethod(
                    activityThread, "currentApplication");
            if (host == null) {
                Object thread = XposedHelpers.callStaticMethod(
                        activityThread, "currentActivityThread");
                host = (Context) XposedHelpers.callMethod(thread, "getSystemContext");
            }
            if (host == null) return null;
            Context module = host.createPackageContext(
                    PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
            String resolved = module.getApplicationInfo().sourceDir;
            return resolved != null && new File(resolved).isFile() ? resolved : null;
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": unable to resolve current module APK");
            XposedBridge.log(throwable);
            return null;
        }
    }
}
