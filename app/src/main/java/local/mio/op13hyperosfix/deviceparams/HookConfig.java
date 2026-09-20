package local.mio.op13hyperosfix.deviceparams;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;

import de.robv.android.xposed.XposedBridge;

final class HookConfig {
    private static final String TAG = "COSOS4Config: ";
    private static volatile HookConfig cached;
    private static volatile long cachedAt;

    boolean paramsEnabled;
    String marketName = "";
    String processor = "";
    String battery = "";
    String resolution = "";
    String screenSize = "";
    String frontCamera = "";
    String rearCamera = "";
    boolean beautyEnabled;
    boolean forceCustomDark;
    boolean customDual;
    boolean musicEnabled;

    static HookConfig load(Context context) {
        long now = SystemClock.uptimeMillis();
        HookConfig recent = cached;
        if (recent != null && now - cachedAt < 500L) return recent;
        return loadFresh(context, now);
    }

    private static synchronized HookConfig loadFresh(Context context, long now) {
        HookConfig recent = cached;
        if (recent != null && now - cachedAt < 500L) return recent;
        HookConfig result = new HookConfig();
        if (context == null) return result;
        Cursor cursor = null;
        try {
            ContentResolver resolver = context.getContentResolver();
            cursor = resolver.query(ConfigContract.URI, ConfigContract.COLUMNS,
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                cached = result;
                cachedAt = now;
                return result;
            }
            result.paramsEnabled = getInt(cursor, ConfigContract.ENABLED) == 1;
            result.marketName = getString(cursor, ConfigContract.MARKET_NAME);
            result.processor = getString(cursor, ConfigContract.PROCESSOR);
            result.battery = getString(cursor, ConfigContract.BATTERY);
            result.resolution = getString(cursor, ConfigContract.RESOLUTION);
            result.screenSize = getString(cursor, ConfigContract.SCREEN_SIZE);
            result.frontCamera = getString(cursor, ConfigContract.FRONT_CAMERA);
            result.rearCamera = getString(cursor, ConfigContract.REAR_CAMERA);
            result.beautyEnabled = getInt(cursor, ConfigContract.BEAUTY_ENABLED) == 1;
            result.forceCustomDark = result.beautyEnabled
                    && getInt(cursor, ConfigContract.FORCE_CUSTOM_DARK) == 1;
            result.customDual = result.beautyEnabled && !result.forceCustomDark
                    && getInt(cursor, ConfigContract.CUSTOM_DUAL) == 1;
            result.musicEnabled = result.beautyEnabled
                    && getInt(cursor, ConfigContract.MUSIC_ENABLED) == 1;
        } catch (Throwable t) {
            XposedBridge.log(TAG + "read fail-open: " + t);
            result.paramsEnabled = false;
            result.beautyEnabled = false;
            result.musicEnabled = false;
        } finally {
            if (cursor != null) cursor.close();
        }
        cached = result;
        cachedAt = now;
        return result;
    }

    boolean hasAppearanceMode() {
        return beautyEnabled && (forceCustomDark || customDual);
    }

    private static int getInt(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index >= 0 ? cursor.getInt(index) : 0;
    }

    private static String getString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0) return "";
        String value = cursor.getString(index);
        return value == null ? "" : value;
    }
}
