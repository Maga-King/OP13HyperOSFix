package local.mio.op13hyperosfix;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class IfaaHooks {
    private static final String SERVICE_CLASS = "org.ifaa.aidl.manager.IfaaService";
    private static final String IFAA_DESCRIPTOR =
            "org.ifaa.aidl.manager.IfaaManagerService";
    private static final String OPLUS_PAY_SERVICE =
            "vendor.oplus.hardware.biometrics.fingerprintpay.IFingerprintPay/default";
    private static final String OPLUS_PAY_DESCRIPTOR =
            "vendor.oplus.hardware.biometrics.fingerprintpay.IFingerprintPay";
    private static final String OPLUS_IFAA_MODEL = "ONEPLUS-R23821";
    private static final int IFAA_FINGERPRINT = 1;
    private static final int IFAA_FOD = 16;
    private static final int IFAA_FINGERPRINT_AND_FOD = IFAA_FINGERPRINT | IFAA_FOD;

    private static final int TRANSACTION_GET_SUPPORT_BIO_TYPES = 1;
    private static final int TRANSACTION_GET_DEVICE_MODEL = 3;
    private static final int TRANSACTION_PROCESS_CMD = 4;
    private static final int TRANSACTION_GET_ID_LIST = 9;
    private static final int OPLUS_TRANSACTION_INVOKE_COMMAND = 6;
    private static final int HAL_LOOKUP_ATTEMPTS = 6;
    private static final long HAL_LOOKUP_RETRY_MS = 10L;

    private static final Set<Class<?>> INSTALLED_BINDER_CLASSES =
            ConcurrentHashMap.newKeySet();
    private static volatile IBinder fingerprintPayService;
    private static volatile WeakReference<Context> serviceContext =
            new WeakReference<>(null);

    private IfaaHooks() {
    }

    static void install(ClassLoader loader) {
        Class<?> serviceClass = Reflect.findClass(loader, SERVICE_CLASS);
        if (serviceClass == null) {
            HookLog.info("IFAA service class not found");
            return;
        }

        int count = Reflect.hookNamedMethods(serviceClass, "onBind", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof Context) {
                    serviceContext = new WeakReference<>((Context) param.thisObject);
                }
                Object result = param.getResult();
                if (result instanceof IBinder) {
                    installBinderHooks((IBinder) result);
                }
            }
        });
        if (count == 0) {
            HookLog.info("IFAA onBind entry not found");
            return;
        }

        DiagnosticMarkers.installApplicationReporter("ifaa");
        HookLog.info("IFAA compatibility entry installed");
    }

    private static void installBinderHooks(IBinder binder) {
        Class<?> binderClass = binder.getClass();
        if (!INSTALLED_BINDER_CLASSES.add(binderClass)) {
            return;
        }

        try {
            Method supportMethod = compatibleMethod(binderClass,
                    "getSupportBIOTypes", 0, int.class);
            Method modelMethod = compatibleMethod(binderClass,
                    "getDeviceModel", 0, String.class);
            Method processMethod = compatibleMethod(binderClass,
                    "processCmd", 1, byte[].class);
            Method idListMethod = compatibleMethod(binderClass,
                    "getIDList", 1, int[].class);

            if (supportMethod != null && modelMethod != null && processMethod != null
                    && idListMethod != null) {
                installLegacyMethodHooks(supportMethod, modelMethod, processMethod,
                        idListMethod);
                HookLog.info("IFAA OS3 method hooks installed on "
                        + binderClass.getName());
                return;
            }

            Method onTransact = Reflect.findMethod(binderClass, "onTransact",
                    int.class, Parcel.class, Parcel.class, int.class);
            if (onTransact == null) {
                throw new NoSuchMethodException("IFAA Binder onTransact");
            }
            Reflect.hookMethodOnce(onTransact, new IfaaTransactionHook());
            HookLog.info("IFAA OS4 transaction hooks installed on "
                    + binderClass.getName());
        } catch (Throwable error) {
            INSTALLED_BINDER_CLASSES.remove(binderClass);
            HookLog.error("IFAA Binder hook installation failed", error);
        }
    }

    private static Method compatibleMethod(Class<?> target, String name, int parameterCount,
            Class<?> returnType) {
        Method method = Reflect.findCompatibleMethod(target, name, parameterCount);
        return method != null && returnType.equals(method.getReturnType()) ? method : null;
    }

    private static void installLegacyMethodHooks(Method supportMethod, Method modelMethod,
            Method processMethod, Method idListMethod) {
        Reflect.hookMethodOnce(supportMethod, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) {
                return IFAA_FINGERPRINT_AND_FOD;
            }
        });
        Reflect.hookMethodOnce(modelMethod,
                XC_MethodReplacement.returnConstant(OPLUS_IFAA_MODEL));
        Reflect.hookMethodOnce(processMethod, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    return forwardOplusCommand((byte[]) param.args[0]);
                } catch (Throwable error) {
                    HookLog.once("ifaa-process-fallback",
                            "IFAA Oplus command failed, using original service: " + error);
                    return XposedBridge.invokeOriginalMethod(param.method,
                            param.thisObject, param.args);
                }
            }
        });
        Reflect.hookMethodOnce(idListMethod, new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                int authType = ((Number) param.args[0]).intValue();
                if (authType != IFAA_FINGERPRINT) {
                    return XposedBridge.invokeOriginalMethod(param.method,
                            param.thisObject, param.args);
                }
                int[] ids = readEnrolledFingerprintIds();
                if (ids != null) {
                    return ids;
                }
                HookLog.once("ifaa-id-list-fallback-" + authType,
                        "IFAA Android biometric list unavailable for type=" + authType
                                + ", using original service");
                return XposedBridge.invokeOriginalMethod(param.method,
                        param.thisObject, param.args);
            }
        });
    }

    private static final class IfaaTransactionHook extends XC_MethodHook {
        IfaaTransactionHook() {
            super(PRIORITY_HIGHEST);
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            int code = ((Number) param.args[0]).intValue();
            if (code != TRANSACTION_GET_SUPPORT_BIO_TYPES
                    && code != TRANSACTION_GET_DEVICE_MODEL
                    && code != TRANSACTION_PROCESS_CMD
                    && code != TRANSACTION_GET_ID_LIST) {
                return;
            }

            Parcel data = (Parcel) param.args[1];
            Parcel reply = (Parcel) param.args[2];
            if (data == null || reply == null) {
                return;
            }
            int startPosition = data.dataPosition();

            try {
                data.enforceInterface(IFAA_DESCRIPTOR);
                switch (code) {
                    case TRANSACTION_GET_SUPPORT_BIO_TYPES:
                        reply.writeNoException();
                        reply.writeInt(IFAA_FINGERPRINT_AND_FOD);
                        break;
                    case TRANSACTION_GET_DEVICE_MODEL:
                        reply.writeNoException();
                        reply.writeString(OPLUS_IFAA_MODEL);
                        break;
                    case TRANSACTION_PROCESS_CMD:
                        byte[] request = data.createByteArray();
                        byte[] response = forwardOplusCommand(request);
                        reply.writeNoException();
                        reply.writeByteArray(response);
                        // IFAA's legacy AIDL declares this argument inout.
                        reply.writeByteArray(request);
                        break;
                    case TRANSACTION_GET_ID_LIST:
                        int authType = data.readInt();
                        if (authType != IFAA_FINGERPRINT) {
                            data.setDataPosition(startPosition);
                            return;
                        }
                        int[] ids = readEnrolledFingerprintIds();
                        if (ids == null) {
                            throw new IllegalStateException(
                                    "Android biometric list unavailable for type=" + authType);
                        }
                        reply.writeNoException();
                        reply.writeIntArray(ids);
                        break;
                    default:
                        return;
                }
                param.setResult(true);
            } catch (Throwable error) {
                data.setDataPosition(startPosition);
                HookLog.once("ifaa-transaction-fallback-" + code,
                        "IFAA transaction " + code
                                + " failed, using original service: " + error);
            }
        }
    }

    private static byte[] forwardOplusCommand(byte[] request) throws Throwable {
        IBinder service = fingerprintPayService;
        if (service == null || !service.isBinderAlive()) {
            service = findFingerprintPayService();
            if (service == null) {
                throw new RemoteException("Oplus IFingerprintPay/default not found");
            }
            fingerprintPayService = service;
            HookLog.once("ifaa-oplus-hal-connected",
                    "IFAA connected to Oplus IFingerprintPay/default");
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(OPLUS_PAY_DESCRIPTOR);
            data.writeByteArray(request);
            if (!service.transact(OPLUS_TRANSACTION_INVOKE_COMMAND,
                    data, reply, 0)) {
                fingerprintPayService = null;
                throw new RemoteException("Oplus invoke_command is unimplemented");
            }
            reply.readException();
            return reply.createByteArray();
        } catch (Throwable error) {
            if (!service.isBinderAlive()) {
                fingerprintPayService = null;
            }
            throw error;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static IBinder findFingerprintPayService() throws Throwable {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        for (int attempt = 0; attempt < HAL_LOOKUP_ATTEMPTS; attempt++) {
            IBinder service = (IBinder) XposedHelpers.callStaticMethod(serviceManager,
                    "getService", OPLUS_PAY_SERVICE);
            if (service != null) {
                return service;
            }
            if (attempt + 1 < HAL_LOOKUP_ATTEMPTS) {
                SystemClock.sleep(HAL_LOOKUP_RETRY_MS);
            }
        }
        return null;
    }

    private static int[] readEnrolledFingerprintIds() {
        try {
            Context context = serviceContext.get();
            if (context == null) {
                return null;
            }
            Object manager = XposedHelpers.callMethod(context,
                    "getSystemService", "fingerprint");
            if (manager == null) {
                return null;
            }

            Object value;
            try {
                value = XposedHelpers.callMethod(manager, "getEnrolledFingerprints");
            } catch (Throwable noZeroArgumentMethod) {
                value = XposedHelpers.callMethod(manager, "getEnrolledFingerprints",
                        currentUserId());
            }
            if (!(value instanceof List<?>)) {
                return null;
            }

            List<?> fingerprints = (List<?>) value;
            int[] ids = new int[fingerprints.size()];
            for (int index = 0; index < fingerprints.size(); index++) {
                Object fingerprint = fingerprints.get(index);
                Object id;
                try {
                    id = XposedHelpers.callMethod(fingerprint, "getBiometricId");
                } catch (Throwable noBiometricId) {
                    id = XposedHelpers.callMethod(fingerprint, "getFingerId");
                }
                if (!(id instanceof Number)) {
                    return null;
                }
                ids[index] = ((Number) id).intValue();
            }
            return ids;
        } catch (Throwable error) {
            HookLog.once("ifaa-fingerprint-list-error",
                    "IFAA fingerprint list read failed: " + error);
            return null;
        }
    }

    private static int currentUserId() {
        try {
            Class<?> userHandle = Class.forName("android.os.UserHandle");
            Object value = XposedHelpers.callStaticMethod(userHandle, "myUserId");
            return value instanceof Number ? ((Number) value).intValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
