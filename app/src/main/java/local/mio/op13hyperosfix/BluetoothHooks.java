package local.mio.op13hyperosfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;

final class BluetoothHooks {
    private static final String MODULE_PACKAGE = "local.mio.op13hyperosfix";
    private static final AtomicBoolean LIBRARY_LOADED = new AtomicBoolean();
    private static final AtomicBoolean PATCHED = new AtomicBoolean();
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();

    private BluetoothHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> service = Reflect.findClass(loader,
                "com.android.bluetooth.btservice.AdapterService");
        if (service == null) {
            HookLog.once("bt_adapter_missing", "Bluetooth AdapterService missing");
            return;
        }
        int hooks = 0;
        hooks += hookAfter(service, "onCreate");
        hooks += hookAfter(service, "onStartCommand");
        HookLog.info("Bluetooth LHDC lifecycle hooks=" + hooks);
    }

    private static int hookAfter(Class<?> type, String methodName) {
        return Reflect.hookNamedMethods(type, methodName, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                applyOnce(param.thisObject);
            }
        });
    }

    private static void applyOnce(Object owner) {
        if (PATCHED.get() || ATTEMPTS.incrementAndGet() > 3) {
            return;
        }
        try {
            loadNativeLibrary(owner);
            int result = nativeApplyLhdcPatch();
            if (result > 0) {
                PATCHED.set(true);
                DiagnosticMarkers.report((Context) owner, "lhdc_bridge");
                HookLog.info("LHDC V5 encoder bridge status=" + result);
            } else {
                HookLog.once("lhdc_patch_status_" + result,
                        "LHDC V5 encoder bridge skipped, status=" + result);
            }
        } catch (Throwable error) {
            HookLog.error("LHDC V5 encoder bridge failed", error);
        }
    }

    private static void loadNativeLibrary(Object owner) throws Exception {
        if (LIBRARY_LOADED.get()) {
            return;
        }
        synchronized (LIBRARY_LOADED) {
            if (LIBRARY_LOADED.get()) {
                return;
            }
            if (!(owner instanceof Context)) {
                throw new IllegalStateException("Bluetooth service Context unavailable");
            }
            Context context = (Context) owner;
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(MODULE_PACKAGE, 0);
            File library = new File(info.nativeLibraryDir, "libop13_lhdc_patch.so");
            System.load(library.getAbsolutePath());
            LIBRARY_LOADED.set(true);
        }
    }

    private static native int nativeApplyLhdcPatch();
}
