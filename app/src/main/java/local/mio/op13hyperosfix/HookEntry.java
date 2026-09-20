package local.mio.op13hyperosfix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HookEntry implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static final local.mio.op13hyperosfix.haptics.HookEntry HAPTICS =
            new local.mio.op13hyperosfix.haptics.HookEntry();
    private static final local.mio.downloadproviderbootguard.HookEntry DOWNLOAD_BOOT_GUARD =
            new local.mio.downloadproviderbootguard.HookEntry();
    private static final local.mio.op13hyperosfix.deviceparams.MainHook DEVICE_PARAMS =
            new local.mio.op13hyperosfix.deviceparams.MainHook();
    private static final local.mio.op13hyperosfix.wlancompat.WlanCompatHooks WLAN_COMPAT =
            new local.mio.op13hyperosfix.wlancompat.WlanCompatHooks();
    private static final local.mio.coloroswalletcompat.HookEntry COLOROS_WALLET_COMPAT =
            new local.mio.coloroswalletcompat.HookEntry();

    @Override
    public void initZygote(StartupParam startupParam) {
        try {
            HAPTICS.initZygote(startupParam);
        } catch (Throwable error) {
            HookLog.error("haptics zygote initialization failed", error);
        }
        try {
            WLAN_COMPAT.initZygote(startupParam);
        } catch (Throwable error) {
            HookLog.error("Xiaomi WLAN compatibility zygote initialization failed", error);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        safely("HyperOS haptics", () -> HAPTICS.handleLoadPackage(param));
        safely("ColorOS wallet compatibility", () ->
                COLOROS_WALLET_COMPAT.handleLoadPackage(param));
        if ("android".equals(param.packageName) && "android".equals(param.processName)) {
            safely("system_server", () -> SystemServerHooks.install(param.classLoader));
        } else if ("com.android.systemui".equals(param.packageName)) {
            safely("SystemUI", () -> SystemUiHooks.install(param.classLoader,
                    param.processName));
            safely("SystemUI notification", () ->
                    NotificationSettingsHooks.installSystemUi(param.classLoader));
            DiagnosticMarkers.installApplicationReporter("systemui");
        } else if ("com.android.settings".equals(param.packageName)) {
            safely("Settings", () -> SettingsHooks.install(param.classLoader));
            safely("Settings device page", () -> DEVICE_PARAMS.handleLoadPackage(param));
            DiagnosticMarkers.installApplicationReporter("settings");
        } else if ("com.android.bluetooth".equals(param.packageName)) {
            safely("Bluetooth", () -> BluetoothHooks.install(param.classLoader));
            DiagnosticMarkers.installApplicationReporter("bluetooth");
        } else if ("com.miui.securitycenter".equals(param.packageName)
                || "com.miui.securitymanager".equals(param.packageName)) {
            safely("Security charge", () -> MiChargeHooks.install(param.packageName,
                    param.processName, param.classLoader));
            DiagnosticMarkers.installApplicationReporter("securitycenter");
        } else if ("org.ifaa.aidl.manager".equals(param.packageName)) {
            safely("IFAA payment", () -> IfaaHooks.install(param.classLoader));
        } else if ("com.android.providers.downloads".equals(param.packageName)
                && "android.process.media".equals(param.processName)) {
            safely("DownloadProvider MTP boot guard", () -> {
                DOWNLOAD_BOOT_GUARD.handleLoadPackage(param);
                DiagnosticMarkers.installApplicationReporter("downloadprovider");
            });
        } else if ("com.miui.huanji".equals(param.packageName)) {
            safely("Mi Mover CE storage", () -> WLAN_COMPAT.handleLoadPackage(param));
            DiagnosticMarkers.installApplicationReporter("wlan_huanji");
        } else if ("com.miui.cloudbackup".equals(param.packageName)) {
            safely("Cloud Backup WLAN MAC", () -> WLAN_COMPAT.handleLoadPackage(param));
            DiagnosticMarkers.installApplicationReporter("wlan_cloudbackup");
        } else if ("com.qualcomm.qti.cne".equals(param.packageName)) {
            safely("CNE DataCall crash guard", CneDataCallCrashGuard::install);
            DiagnosticMarkers.installApplicationReporter("cne_datacall");
        }
    }

    private static void safely(String component, Runnable installer) {
        try {
            installer.run();
        } catch (Throwable error) {
            HookLog.error(component + " hook installation failed", error);
        }
    }
}
