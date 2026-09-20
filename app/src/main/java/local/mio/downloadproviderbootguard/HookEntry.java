package local.mio.downloadproviderbootguard;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Disables only Xiaomi DownloadProvider's boot-idle self-kill path.
 *
 * <p>The real crash handler remains untouched: XCrashlytics.killSelf() is blocked only for the
 * BootHelper call path. All hooks are signature-checked and fail open after an incompatible
 * ROM update.</p>
 */
public final class HookEntry implements IXposedHookLoadPackage {
    private static final String TARGET_PACKAGE = "com.android.providers.downloads";
    private static final String EXPECTED_PROCESS = "android.process.media";
    private static final String CLOUD_PREF_CLASS =
            "com.android.providers.downloads.setting.CloudConfigPreference";
    private static final String BOOT_HELPER_CLASS =
            "com.android.providers.downloads.util.BootHelper";
    private static final String CRASHLYTICS_CLASS =
            "com.android.providers.downloads.exception.XCrashlytics";
    private static final String SHARED_PREFERENCES_IMPL_CLASS =
            "android.app.SharedPreferencesImpl";
    private static final String SHARED_PREFERENCES_EDITOR_IMPL_CLASS =
            "android.app.SharedPreferencesImpl$EditorImpl";
    private static final String BOOT_KILL_TIMEOUT_KEY = "boot_kill_timeout";
    private static final String BOOT_KILL_THREAD_PREFIX = "DMS_BootEvent";
    private static final String TAG = "DownloadProviderBootGuard";

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean FALLBACK_BLOCK_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean PROCESS_KILL_BLOCK_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean EXIT_BLOCK_LOGGED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!TARGET_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }
        if (!EXPECTED_PROCESS.equals(loadPackageParam.processName)) {
            return;
        }

        ClassLoader classLoader = loadPackageParam.classLoader;
        Class<?> cloudPreference = XposedHelpers.findClassIfExists(CLOUD_PREF_CLASS, classLoader);
        Class<?> bootHelper = XposedHelpers.findClassIfExists(BOOT_HELPER_CLASS, classLoader);
        Class<?> crashlytics = XposedHelpers.findClassIfExists(CRASHLYTICS_CLASS, classLoader);

        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        int getterHooks = hookBootKillTimeoutGetter(cloudPreference);
        int setterHooks = hookBootKillTimeoutSetter(cloudPreference);
        int preferenceFallbackHooks = hookBootKillPreferenceFallback(classLoader);
        int killPeriodHooks = hookKillPeriod(bootHelper);
        int killCheckHooks = hookBootKillCheck(bootHelper);
        int killSelfFallbackHooks = hookBootOnlyKillSelfFallback(crashlytics);
        int processKillFallbackHooks = hookBootOnlyProcessKillFallback();
        int exitFallbackHooks = hookBootOnlyExitFallback();

        log("active process=" + loadPackageParam.processName
                + ", expectedProcess=" + EXPECTED_PROCESS.equals(loadPackageParam.processName)
                + ", getter=" + getterHooks
                + ", setter=" + setterHooks
                + ", preferenceFallback=" + preferenceFallbackHooks
                + ", killPeriod=" + killPeriodHooks
                + ", killCheck=" + killCheckHooks
                + ", killSelfFallback=" + killSelfFallbackHooks
                + ", processKillFallback=" + processKillFallbackHooks
                + ", exitFallback=" + exitFallbackHooks);
    }

    private static int hookBootKillTimeoutGetter(Class<?> targetClass) {
        if (targetClass == null) {
            return 0;
        }
        int count = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            Class<?> returnType = method.getReturnType();
            if (!"getBootKillTimeout".equals(method.getName())
                    || method.getParameterTypes().length != 0
                    || (returnType != long.class && returnType != Long.class)) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(-1L);
                }
            });
            count++;
        }
        return count;
    }

    /**
     * Class-name-independent cloud-config guard. Only the one boot-idle kill key is changed;
     * every other DownloadProvider cloud field and preference access passes untouched.
     */
    private static int hookBootKillPreferenceFallback(ClassLoader classLoader) {
        int count = 0;
        Class<?> preferences = XposedHelpers.findClassIfExists(
                SHARED_PREFERENCES_IMPL_CLASS, classLoader);
        Class<?> editor = XposedHelpers.findClassIfExists(
                SHARED_PREFERENCES_EDITOR_IMPL_CLASS, classLoader);

        if (preferences != null) {
            for (Method method : preferences.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"getLong".equals(method.getName())
                        || (method.getReturnType() != long.class
                        && method.getReturnType() != Long.class)
                        || parameters.length != 2
                        || parameters[0] != String.class
                        || parameters[1] != long.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (BOOT_KILL_TIMEOUT_KEY.equals(param.args[0])) {
                                param.setResult(-1L);
                            }
                        }
                    });
                    count++;
                } catch (Throwable throwable) {
                    log("skip SharedPreferences boot-kill getter guard: "
                            + throwable.getClass().getSimpleName());
                }
            }
        }

        if (editor != null) {
            for (Method method : editor.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"putLong".equals(method.getName())
                        || parameters.length != 2
                        || parameters[0] != String.class
                        || parameters[1] != long.class) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (BOOT_KILL_TIMEOUT_KEY.equals(param.args[0])) {
                                param.args[1] = -1L;
                            }
                        }
                    });
                    count++;
                } catch (Throwable throwable) {
                    log("skip SharedPreferences boot-kill setter guard: "
                            + throwable.getClass().getSimpleName());
                }
            }
        }
        return count;
    }

    private static int hookBootKillTimeoutSetter(Class<?> targetClass) {
        if (targetClass == null) {
            return 0;
        }
        int count = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!"setBootKillTimeout".equals(method.getName())
                    || method.getReturnType() != void.class
                    || parameters.length != 1
                    || (parameters[0] != long.class && parameters[0] != Long.class)) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.args[0] = -1L;
                }
            });
            count++;
        }
        return count;
    }

    private static int hookBootKillCheck(Class<?> targetClass) {
        if (targetClass == null) {
            return 0;
        }
        int count = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!"checkProcessKill".equals(method.getName())
                    || method.getReturnType() != void.class
                    || method.getParameterTypes().length != 0) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                }
            });
            count++;
        }
        return count;
    }

    private static int hookKillPeriod(Class<?> targetClass) {
        if (targetClass == null) {
            return 0;
        }
        int count = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            Class<?> returnType = method.getReturnType();
            if (!"inKillPeriod".equals(method.getName())
                    || method.getParameterTypes().length != 0
                    || (returnType != boolean.class && returnType != Boolean.class)) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    param.setResult(Boolean.FALSE);
                }
            });
            count++;
        }
        return count;
    }

    /**
     * Last-resort guard for ROM variants that still reach XCrashlytics.killSelf(). Only the
     * BootHelper call path or its dedicated thread is blocked. Genuine CrashHandler calls pass.
     */
    private static int hookBootOnlyKillSelfFallback(Class<?> targetClass) {
        if (targetClass == null) {
            return 0;
        }
        int count = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!"killSelf".equals(method.getName())
                    || method.getReturnType() != void.class
                    || method.getParameterTypes().length != 0) {
                continue;
            }
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isBootKillContext()) {
                        return;
                    }
                    param.setResult(null);
                    if (FALLBACK_BLOCK_LOGGED.compareAndSet(false, true)) {
                        log("blocked BootHelper-only XCrashlytics.killSelf fallback");
                    }
                }
            });
            count++;
        }
        return count;
    }

    /**
     * Final process-local guard. This covers ROM updates that rename BootHelper/XCrashlytics or
     * inline the helper call, while still allowing every kill outside the dedicated boot-idle
     * self-kill context.
     */
    private static int hookBootOnlyProcessKillFallback() {
        int count = 0;
        for (Method method : android.os.Process.class.getDeclaredMethods()) {
            String name = method.getName();
            Class<?>[] parameters = method.getParameterTypes();
            if (!("killProcess".equals(name)
                    || "killProcessQuiet".equals(name)
                    || "sendSignal".equals(name)
                    || "sendSignalQuiet".equals(name))
                    || method.getReturnType() != void.class
                    || parameters.length == 0
                    || parameters[0] != int.class) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.args[0] instanceof Integer)
                                || ((Integer) param.args[0]) != android.os.Process.myPid()
                                || !isBootKillContext()) {
                            return;
                        }
                        param.setResult(null);
                        if (PROCESS_KILL_BLOCK_LOGGED.compareAndSet(false, true)) {
                            log("blocked boot-idle self kill at android.os.Process." + name);
                        }
                    }
                });
                count++;
            } catch (Throwable throwable) {
                log("skip low-level Process guard " + name + ": "
                        + throwable.getClass().getSimpleName());
            }
        }
        return count;
    }

    /**
     * Covers both System.exit() and the Runtime exit/halt implementations, but only on the
     * Xiaomi boot-idle self-kill thread/call path in android.process.media.
     */
    private static int hookBootOnlyExitFallback() {
        int count = 0;
        count += hookExitMethods(System.class, "exit");
        count += hookExitMethods(Runtime.class, "exit", "halt");
        return count;
    }

    private static int hookExitMethods(Class<?> targetClass, String... acceptedNames) {
        int count = 0;
        for (Method method : targetClass.getDeclaredMethods()) {
            if (!contains(acceptedNames, method.getName())
                    || method.getReturnType() != void.class
                    || method.getParameterTypes().length != 1
                    || method.getParameterTypes()[0] != int.class) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBootKillContext()) {
                            return;
                        }
                        param.setResult(null);
                        if (EXIT_BLOCK_LOGGED.compareAndSet(false, true)) {
                            log("blocked boot-idle self exit at "
                                    + targetClass.getName() + "." + method.getName());
                        }
                    }
                });
                count++;
            } catch (Throwable throwable) {
                log("skip low-level exit guard " + targetClass.getName() + "."
                        + method.getName() + ": " + throwable.getClass().getSimpleName());
            }
        }
        return count;
    }

    private static boolean contains(String[] values, String value) {
        for (String candidate : values) {
            if (candidate.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBootKillContext() {
        Thread thread = Thread.currentThread();
        String threadName = thread.getName();
        if (threadName != null && threadName.startsWith(BOOT_KILL_THREAD_PREFIX)) {
            return true;
        }
        for (StackTraceElement element : thread.getStackTrace()) {
            String className = element.getClassName();
            String methodName = element.getMethodName();
            if ((BOOT_HELPER_CLASS.equals(className) || className.endsWith(".BootHelper"))
                    && ("checkProcessKill".equals(methodName)
                    || methodName.toLowerCase(Locale.ROOT).contains("kill"))) {
                return true;
            }
        }
        return false;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
