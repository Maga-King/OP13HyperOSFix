package local.mio.op13hyperosfix.deviceparams;

import android.content.pm.ApplicationInfo;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.io.Closeable;
import java.lang.reflect.Method;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Exact OS4 names first, DexKit string fingerprints only when an OTA renamed them. */
final class CompatResolver implements Closeable {
    private static final String TAG = "COSOS4Compat: ";
    private static boolean nativeLoaded;

    private final ClassLoader loader;
    private final String apkPath;
    private DexKitBridge bridge;

    CompatResolver(XC_LoadPackage.LoadPackageParam lpparam) {
        loader = lpparam.classLoader;
        ApplicationInfo appInfo = lpparam.appInfo;
        apkPath = appInfo == null ? null : appInfo.sourceDir;
    }

    Class<?> exactClass(String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    Class<?> findMyDeviceClass() {
        Class<?> exact = exactClass("com.android.settings.device.MiuiMyDeviceSettings");
        if (exact != null) return exact;
        return classUsingString("provision_about_page_v85x", "my-device fragment");
    }

    Class<?> findBgDataManagerClass() {
        Class<?> exact = exactClass(
                "com.android.settings.device.controller.BgEffectDataManager");
        if (exact != null) return exact;
        return classUsingString("Unsupported device type: ", "background data manager");
    }

    Method findMarketNameMethod() {
        Class<?> exactClass = exactClass(
                "com.android.settings.device.MiuiAboutPhoneUtils");
        if (exactClass != null) {
            try {
                return exactClass.getDeclaredMethod("getDeviceMarketName");
            } catch (Throwable ignored) {
            }
        }
        return methodUsingString("ro.product.marketname", 0,
                "java.lang.String", "market name");
    }

    Method findCacheReadMethod(String key) {
        Class<?> exactClass = exactClass(
                "com.android.settings.device.DeviceServiceCache");
        if (exactClass != null) {
            String name = "basic_info_key".equals(key)
                    ? "readBasicInfo" : "readCameraInfo";
            try {
                return exactClass.getDeclaredMethod(name, android.content.Context.class);
            } catch (Throwable ignored) {
            }
        }
        return methodUsingString(key, 1, "java.lang.String", key + " cache reader");
    }

    private Class<?> classUsingString(String marker, String purpose) {
        DexKitBridge dex = dexKit();
        if (dex == null) return null;
        try {
            MethodDataList list = dex.findMethod(FindMethod.create()
                    .searchPackages("com.android.settings")
                    .matcher(MethodMatcher.create().usingEqStrings(marker)));
            for (MethodData data : list) {
                try {
                    Class<?> candidate = Class.forName(
                            data.getDeclaredClassName(), false, loader);
                    XposedBridge.log(TAG + purpose + " discovered as "
                            + candidate.getName() + " by marker " + marker);
                    return candidate;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + purpose + " DexKit search failed: " + t);
        }
        return null;
    }

    private Method methodUsingString(String marker, int paramCount,
                                     String returnType, String purpose) {
        DexKitBridge dex = dexKit();
        if (dex == null) return null;
        try {
            MethodMatcher matcher = MethodMatcher.create()
                    .returnType(returnType)
                    .paramCount(paramCount)
                    .usingEqStrings(marker);
            MethodDataList list = dex.findMethod(FindMethod.create()
                    .searchPackages("com.android.settings")
                    .matcher(matcher));
            for (MethodData data : list) {
                try {
                    Method method = data.getMethodInstance(loader);
                    method.setAccessible(true);
                    XposedBridge.log(TAG + purpose + " discovered as " + method);
                    return method;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + purpose + " DexKit search failed: " + t);
        }
        return null;
    }

    private DexKitBridge dexKit() {
        if (bridge != null) return bridge;
        if (apkPath == null || apkPath.length() == 0) {
            XposedBridge.log(TAG + "DexKit skipped: Settings APK path unavailable");
            return null;
        }
        try {
            synchronized (CompatResolver.class) {
                if (!nativeLoaded) {
                    System.loadLibrary("dexkit");
                    nativeLoaded = true;
                }
            }
            bridge = DexKitBridge.create(apkPath);
            bridge.setThreadNum(2);
            XposedBridge.log(TAG + "DexKit opened only for OTA fallback: " + apkPath);
            return bridge;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "DexKit unavailable; exact hooks remain active: " + t);
            return null;
        }
    }

    @Override
    public void close() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Throwable ignored) {
            }
            bridge = null;
        }
    }
}
