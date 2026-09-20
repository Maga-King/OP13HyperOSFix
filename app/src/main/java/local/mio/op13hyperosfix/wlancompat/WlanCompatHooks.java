package local.mio.op13hyperosfix.wlancompat;

import android.content.pm.ApplicationInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class WlanCompatHooks implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final String HUANJI_PACKAGE = "com.miui.huanji";
    private static final String CLOUD_BACKUP_PACKAGE = "com.miui.cloudbackup";
    private static final String DEVICE_INFO_CLASS = "com.miui.cloudbackup.infos.DeviceInfo";
    private static final int FALLBACK_DEFAULT_TO_DE = 1 << 5;
    private static final int FALLBACK_DIRECT_BOOT_AWARE = 1 << 6;
    private static final int FALLBACK_PARTIALLY_DIRECT_BOOT_AWARE = 1 << 8;
    private static final String TAG = "OP13WlanCompat";
    private static volatile boolean dexKitNativeLoaded;

    @Override
    public void initZygote(StartupParam startupParam) {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            XposedBridge.hookAllMethods(activityThread, "handleBindApplication",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length == 0 || param.args[0] == null) {
                                return;
                            }
                            try {
                                ApplicationInfo appInfo = (ApplicationInfo)
                                        XposedHelpers.getObjectField(param.args[0], "appInfo");
                                if (appInfo != null && HUANJI_PACKAGE.equals(appInfo.packageName)) {
                                    forceCredentialProtectedStorage(appInfo, "early bind");
                                }
                            } catch (Throwable throwable) {
                                log("early Mi Mover storage fix failed: " + throwable);
                            }
                        }
                    });
            log("early Mi Mover bind hook installed");
        } catch (Throwable throwable) {
            log("zygote hook install failed: " + throwable);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (HUANJI_PACKAGE.equals(param.packageName)) {
            forceCredentialProtectedStorage(param.appInfo, "load package");
            return;
        }
        if (CLOUD_BACKUP_PACKAGE.equals(param.packageName)) {
            installCloudBackupHooks(param.classLoader,
                    param.appInfo == null ? null : param.appInfo.sourceDir);
        }
    }

    private static void forceCredentialProtectedStorage(ApplicationInfo appInfo, String stage) {
        if (appInfo == null) {
            return;
        }
        try {
            int mask = hiddenApplicationInfoFlag(
                    "PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE", FALLBACK_DEFAULT_TO_DE)
                    | hiddenApplicationInfoFlag(
                    "PRIVATE_FLAG_DIRECT_BOOT_AWARE", FALLBACK_DIRECT_BOOT_AWARE)
                    | hiddenApplicationInfoFlag(
                    "PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE",
                    FALLBACK_PARTIALLY_DIRECT_BOOT_AWARE);
            int privateFlags = XposedHelpers.getIntField(appInfo, "privateFlags");
            XposedHelpers.setIntField(appInfo, "privateFlags", privateFlags & ~mask);
            String credentialDataDir = (String) XposedHelpers.getObjectField(
                    appInfo, "credentialProtectedDataDir");
            if (credentialDataDir != null && !credentialDataDir.isEmpty()) {
                appInfo.dataDir = credentialDataDir;
            }
            log("Mi Mover forced to CE storage at " + stage);
        } catch (Throwable throwable) {
            log("Mi Mover CE adjustment failed at " + stage + ": " + throwable);
        }
    }

    private static int hiddenApplicationInfoFlag(String name, int fallback) {
        try {
            Field field = ApplicationInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void installCloudBackupHooks(ClassLoader classLoader, String apkPath) {
        Class<?> deviceInfo = XposedHelpers.findClassIfExists(DEVICE_INFO_CLASS, classLoader);
        int installed = 0;
        if (deviceInfo != null) {
            installed += hookMacMethod(deviceInfo, "c", false);
            installed += hookMacMethod(deviceInfo, "b", true);
        } else {
            log("Cloud Backup DeviceInfo class changed; trying DexKit fallback");
        }
        if (installed == 0) {
            Method discovered = findMacMethodWithDexKit(apkPath, classLoader);
            if (discovered != null) {
                installed += hookMacMethod(discovered, false);
            }
        }
        log("Cloud Backup MAC hooks installed=" + installed);
    }

    private static int hookMacMethod(Class<?> owner, String methodName, boolean updateCacheField) {
        try {
            Method method = owner.getDeclaredMethod(methodName);
            return hookMacMethod(method, updateCacheField);
        } catch (NoSuchMethodException ignored) {
            return 0;
        } catch (Throwable throwable) {
            log("Cloud Backup hook failed for " + methodName + "(): " + throwable);
            return 0;
        }
    }

    private static int hookMacMethod(Method method, boolean updateCacheField) {
        try {
            if (!Modifier.isStatic(method.getModifiers())
                    || method.getParameterCount() != 0
                    || method.getReturnType() != String.class) {
                return 0;
            }
            method.setAccessible(true);
            Class<?> owner = method.getDeclaringClass();
            String methodName = method.getName();
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    String original = null;
                    if (!param.hasThrowable() && param.getResult() instanceof String) {
                        original = MacRepository.normalizeMac((String) param.getResult());
                    }
                    if (MacRepository.isUsableMac(original)) {
                        return;
                    }

                    String replacement = MacClient.getWlan0Mac();
                    if (!MacRepository.isUsableMac(replacement)) {
                        return;
                    }
                    param.setResult(replacement);
                    if (updateCacheField) {
                        try {
                            XposedHelpers.setStaticObjectField(owner, "a", replacement);
                        } catch (Throwable ignored) {
                            // The return value is already repaired; cache-field names may change.
                        }
                    }
                    log("Cloud Backup MAC repaired after " + methodName + "()");
                }
            });
            return 1;
        } catch (Throwable throwable) {
            log("Cloud Backup hook failed for " + method + ": " + throwable);
            return 0;
        }
    }

    private static Method findMacMethodWithDexKit(String apkPath, ClassLoader classLoader) {
        if (apkPath == null || apkPath.isEmpty()) {
            log("DexKit fallback skipped: Cloud Backup APK path unavailable");
            return null;
        }
        DexKitBridge bridge = null;
        try {
            synchronized (WlanCompatHooks.class) {
                if (!dexKitNativeLoaded) {
                    System.loadLibrary("dexkit");
                    dexKitNativeLoaded = true;
                }
            }
            bridge = DexKitBridge.create(apkPath);
            bridge.setThreadNum(2);
            MethodDataList matches = bridge.findMethod(FindMethod.create()
                    .searchPackages("com.miui.cloudbackup")
                    .matcher(MethodMatcher.create()
                            .returnType("java.lang.String")
                            .paramCount(0)
                            .usingEqStrings("get wlan0 interface info failed",
                                    "02:00:00:00:00:00")));
            for (MethodData data : matches) {
                Method method = data.getMethodInstance(classLoader);
                if (Modifier.isStatic(method.getModifiers())
                        && method.getParameterCount() == 0
                        && method.getReturnType() == String.class) {
                    method.setAccessible(true);
                    log("Cloud Backup MAC getter discovered by DexKit: " + method);
                    return method;
                }
            }
        } catch (Throwable throwable) {
            log("Cloud Backup DexKit fallback unavailable: " + throwable);
        } finally {
            if (bridge != null) {
                try {
                    bridge.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
