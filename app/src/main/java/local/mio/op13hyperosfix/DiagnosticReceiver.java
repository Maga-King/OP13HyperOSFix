package local.mio.op13hyperosfix;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.util.Set;

public final class DiagnosticReceiver extends BroadcastReceiver {
    private static final String PREFS = "hook_diagnostics";
    private static final Set<String> ALLOWED = Set.of(
            "system_server", "systemui", "settings", "bluetooth",
            "securitycenter", "lhdc_bridge", "haptics_framework",
            "haptics_systemui", "haptics_settings", "ifaa", "downloadprovider");

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !DiagnosticMarkers.ACTION.equals(intent.getAction())) {
            return;
        }
        String marker = intent.getStringExtra(DiagnosticMarkers.EXTRA_MARKER);
        if (!ALLOWED.contains(marker)) {
            return;
        }
        Context storage = context.createDeviceProtectedStorageContext();
        int boot = Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        storage.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(marker + "_boot", boot)
                .putLong(marker + "_time", System.currentTimeMillis())
                .apply();
    }
}
