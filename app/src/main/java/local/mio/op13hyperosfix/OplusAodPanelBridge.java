package local.mio.op13hyperosfix;

import android.os.IBinder;
import android.os.Parcel;

import java.lang.reflect.Method;

final class OplusAodPanelBridge {
    private static final String SERVICE_NAME =
            "vendor.oplus.hardware.displaypanelfeature.IDisplayPanelFeature/default";
    private static final String DESCRIPTOR =
            "vendor.oplus.hardware.displaypanelfeature.IDisplayPanelFeature";
    private static final int FEATURE_LONGRUI_AOD = 217;
    private static final int FULL_SCREEN_AOD_MODE = 1 << 2;
    private static final int GET_FEATURE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    private static final int SET_FEATURE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int UNKNOWN_MODE = Integer.MIN_VALUE;
    private static final Object LOCK = new Object();

    private static IBinder panelService;
    private static boolean capabilityChecked;
    private static boolean fullScreenAodSupported;
    private static int lastMode = UNKNOWN_MODE;

    private OplusAodPanelBridge() {
    }

    static boolean setFullScreenAodEnabled(boolean enabled) {
        synchronized (LOCK) {
            int mode = enabled ? FULL_SCREEN_AOD_MODE : 0;
            try {
                IBinder service = getPanelServiceLocked();
                if (service == null) {
                    return false;
                }
                if (!capabilityChecked) {
                    int capability = getFeatureLocked(service, FEATURE_LONGRUI_AOD);
                    fullScreenAodSupported = (capability & FULL_SCREEN_AOD_MODE) != 0;
                    capabilityChecked = true;
                    HookLog.info("Oplus AOD capability=0x"
                            + Integer.toHexString(capability));
                }
                if (!fullScreenAodSupported) {
                    HookLog.once("oplus_full_aod_unsupported",
                            "Oplus panel does not advertise full-screen AOD mode");
                    return false;
                }
                if (lastMode == mode && service.isBinderAlive()) {
                    return true;
                }
                setFeatureLocked(service, FEATURE_LONGRUI_AOD, mode);
                lastMode = mode;
                HookLog.info("Oplus full-screen AOD panel mode=" + mode);
                return true;
            } catch (Throwable error) {
                resetServiceLocked();
                HookLog.error("Oplus full-screen AOD panel update failed", error);
                return false;
            }
        }
    }

    private static IBinder getPanelServiceLocked() throws Exception {
        if (panelService != null && panelService.isBinderAlive()) {
            return panelService;
        }
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder service = (IBinder) getService.invoke(null, SERVICE_NAME);
        if (service == null) {
            HookLog.once("oplus_panel_service_missing",
                    "Oplus display panel feature service is unavailable");
            return null;
        }
        service.linkToDeath(() -> {
            synchronized (LOCK) {
                resetServiceLocked();
            }
        }, 0);
        panelService = service;
        capabilityChecked = false;
        lastMode = UNKNOWN_MODE;
        return service;
    }

    private static int getFeatureLocked(IBinder service, int feature) throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(feature);
            data.writeInt(1);
            if (!service.transact(GET_FEATURE_TRANSACTION, data, reply, 0)) {
                throw new IllegalStateException("getFeature transaction was not handled");
            }
            reply.readException();
            int result = reply.readInt();
            int[] values = new int[1];
            reply.readIntArray(values);
            if (result != 0) {
                throw new IllegalStateException("getFeature result=" + result);
            }
            return values[0];
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void setFeatureLocked(IBinder service, int feature, int value)
            throws Exception {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeInt(feature);
            data.writeIntArray(new int[]{value});
            if (!service.transact(SET_FEATURE_TRANSACTION, data, reply, 0)) {
                throw new IllegalStateException("setFeature transaction was not handled");
            }
            reply.readException();
            int result = reply.readInt();
            if (result != 0) {
                throw new IllegalStateException("setFeature result=" + result);
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void resetServiceLocked() {
        panelService = null;
        capabilityChecked = false;
        fullScreenAodSupported = false;
        lastMode = UNKNOWN_MODE;
    }
}
