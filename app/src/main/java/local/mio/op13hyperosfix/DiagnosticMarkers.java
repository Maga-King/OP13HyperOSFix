package local.mio.op13hyperosfix;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public final class DiagnosticMarkers {
    static final String ACTION =
            "local.mio.op13hyperosfix.action.HOOK_DIAGNOSTIC";
    static final String EXTRA_MARKER = "marker";
    private static final String MODULE_PACKAGE = "local.mio.op13hyperosfix";
    private static final AtomicBoolean REPORTER_INSTALLED = new AtomicBoolean();
    private static final Set<String> APPLICATION_MARKERS = ConcurrentHashMap.newKeySet();

    private DiagnosticMarkers() {
    }

    public static void installApplicationReporter(String marker) {
        if (marker == null || marker.isEmpty()) {
            return;
        }
        APPLICATION_MARKERS.add(marker);
        if (!REPORTER_INSTALLED.compareAndSet(false, true)) {
            return;
        }
        XposedBridge.hookAllMethods(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Context) {
                    for (String pending : APPLICATION_MARKERS) {
                        report((Context) param.thisObject, pending);
                    }
                }
            }
        });
    }

    public static void report(Context context, String marker) {
        if (context == null || marker == null || marker.isEmpty()) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION)
                    .setComponent(new ComponentName(MODULE_PACKAGE,
                            MODULE_PACKAGE + ".DiagnosticReceiver"))
                    .putExtra(EXTRA_MARKER, marker)
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
        } catch (Throwable error) {
            HookLog.error("diagnostic marker failed: " + marker, error);
        }
    }
}
