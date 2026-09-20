package local.mio.op13hyperosfix.wlancompat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

public final class MacProvider extends ContentProvider {
    static final String AUTHORITY = "local.mio.op13hyperosfix.wlancompat.mac";
    static final String METHOD_GET_MAC = "get_mac";
    private static final String HUANJI_PACKAGE = "com.miui.huanji";
    private static final String CLOUD_BACKUP_PACKAGE = "com.miui.cloudbackup";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context context = requireContext();
        enforceAllowedCaller(context);
        if (!METHOD_GET_MAC.equals(method)) {
            throw new IllegalArgumentException("Unsupported provider method");
        }

        boolean refresh = extras != null && extras.getBoolean("refresh", false);
        MacValue value = MacRepository.resolve(context, arg, refresh);
        Bundle result = new Bundle();
        result.putString("mac", value.mac);
        result.putString("source", value.source);
        return result;
    }

    private void enforceAllowedCaller(Context context) {
        int callingUid = Binder.getCallingUid();
        if (callingUid == Process.myUid()) {
            return;
        }
        String callingPackage = getCallingPackage();
        if (isAllowedPackage(callingPackage)) {
            return;
        }
        if (callingPackage != null) {
            throw new SecurityException("Caller is not in the Xiaomi compatibility allow-list");
        }
        PackageManager packageManager = context.getPackageManager();
        String[] packages = packageManager.getPackagesForUid(callingUid);
        if (packages != null) {
            for (String packageName : packages) {
                if (isAllowedPackage(packageName)) {
                    return;
                }
            }
        }
        throw new SecurityException("Caller is not in the Xiaomi compatibility allow-list");
    }

    private static boolean isAllowedPackage(String packageName) {
        return HUANJI_PACKAGE.equals(packageName)
                || CLOUD_BACKUP_PACKAGE.equals(packageName);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
