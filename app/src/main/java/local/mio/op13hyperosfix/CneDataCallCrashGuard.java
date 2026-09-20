package local.mio.op13hyperosfix;

import android.net.ConnectivityManager;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

final class CneDataCallCrashGuard {
    private static final String DATA_CALL_TRACKER =
            "com.qualcomm.qti.cne.datacall.DataCallAgent$Tracker";
    private static final String DESTROY_NETWORK_REQUEST = "destroyNetworkRequest";
    private static final String CALLBACK_NOT_REGISTERED =
            "NetworkCallback was not registered";
    private static final AtomicBoolean RECOVERY_LOGGED = new AtomicBoolean();
    private static boolean installed;

    private CneDataCallCrashGuard() {
    }

    static synchronized void install() {
        if (installed) return;
        XposedHelpers.findAndHookMethod(
                ConnectivityManager.class,
                "unregisterNetworkCallback",
                ConnectivityManager.NetworkCallback.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Throwable error = param.getThrowable();
                        if (!(error instanceof IllegalArgumentException)
                                || !CALLBACK_NOT_REGISTERED.equals(error.getMessage())
                                || !stackContains(error, DATA_CALL_TRACKER,
                                        DESTROY_NETWORK_REQUEST)) {
                            return;
                        }
                        param.setResult(null);
                        if (RECOVERY_LOGGED.compareAndSet(false, true)) {
                            HookLog.info("CNE DataCall duplicate callback unregister recovered");
                        }
                    }
                });
        installed = true;
        HookLog.info("CNE DataCall crash guard installed");
    }

    private static boolean stackContains(Throwable error, String className,
            String methodName) {
        for (StackTraceElement element : error.getStackTrace()) {
            if (className.equals(element.getClassName())
                    && methodName.equals(element.getMethodName())) {
                return true;
            }
        }
        return false;
    }
}
