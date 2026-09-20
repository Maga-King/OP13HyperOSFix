package local.mio.op13hyperosfix.deviceparams;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

public final class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        enforceAllowedCaller();
        SharedPreferences prefs = getContext().getSharedPreferences(
                ConfigContract.PREFS, android.content.Context.MODE_PRIVATE);
        MatrixCursor cursor = new MatrixCursor(ConfigContract.COLUMNS, 1);
        cursor.addRow(new Object[]{
                prefs.getBoolean(ConfigContract.ENABLED,
                        ConfigContract.DEFAULT_PARAMS_ENABLED) ? 1 : 0,
                prefs.getString(ConfigContract.MARKET_NAME,
                        ConfigContract.DEFAULT_MARKET_NAME),
                prefs.getString(ConfigContract.PROCESSOR,
                        ConfigContract.DEFAULT_PROCESSOR),
                prefs.getString(ConfigContract.BATTERY,
                        ConfigContract.DEFAULT_BATTERY),
                prefs.getString(ConfigContract.RESOLUTION,
                        ConfigContract.DEFAULT_RESOLUTION),
                prefs.getString(ConfigContract.SCREEN_SIZE,
                        ConfigContract.DEFAULT_SCREEN_SIZE),
                prefs.getString(ConfigContract.FRONT_CAMERA,
                        ConfigContract.DEFAULT_FRONT_CAMERA),
                prefs.getString(ConfigContract.REAR_CAMERA,
                        ConfigContract.DEFAULT_REAR_CAMERA),
                prefs.getBoolean(ConfigContract.BEAUTY_ENABLED,
                        ConfigContract.DEFAULT_BEAUTY_ENABLED) ? 1 : 0,
                prefs.getBoolean(ConfigContract.FORCE_CUSTOM_DARK,
                        ConfigContract.DEFAULT_FORCE_CUSTOM_DARK) ? 1 : 0,
                prefs.getBoolean(ConfigContract.CUSTOM_DUAL,
                        ConfigContract.DEFAULT_CUSTOM_DUAL) ? 1 : 0,
                prefs.getBoolean(ConfigContract.MUSIC_ENABLED,
                        ConfigContract.DEFAULT_MUSIC_ENABLED) ? 1 : 0
        });
        cursor.setNotificationUri(getContext().getContentResolver(), ConfigContract.URI);
        return cursor;
    }

    private void enforceAllowedCaller() {
        int caller = Binder.getCallingUid();
        if (caller == android.os.Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(caller);
        if (packages != null) {
            for (String packageName : packages) {
                if (ConfigContract.SETTINGS_PACKAGE.equals(packageName)) return;
            }
        }
        throw new SecurityException("Configuration is readable only by Android Settings");
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.op13hyperosfix.deviceparams.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("read only");
    }
}
