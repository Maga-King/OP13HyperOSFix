package local.mio.op13hyperosfix.wlancompat;

import android.app.Application;
import android.net.Uri;
import android.os.Bundle;

import de.robv.android.xposed.XposedHelpers;

final class MacClient {
    private MacClient() {
    }

    static String getWlan0Mac() {
        try {
            Class<?> activityThread = XposedHelpers.findClass("android.app.ActivityThread", null);
            Application application = (Application) XposedHelpers.callStaticMethod(
                    activityThread, "currentApplication");
            if (application == null) {
                return null;
            }
            Bundle request = new Bundle();
            request.putBoolean("refresh", true);
            Bundle response = application.getContentResolver().call(
                    Uri.parse("content://" + MacProvider.AUTHORITY),
                    MacProvider.METHOD_GET_MAC,
                    "wlan0",
                    request);
            if (response == null) {
                return null;
            }
            String value = MacRepository.normalizeMac(response.getString("mac"));
            if (MacRepository.isUsableMac(value)) {
                return value;
            }
        } catch (Throwable throwable) {
            WlanCompatHooks.log(
                    "MAC provider unavailable: " + throwable.getClass().getSimpleName());
        }
        return null;
    }
}
