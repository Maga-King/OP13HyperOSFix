package local.mio.op13hyperosfix.deviceparams;

import android.app.ActivityManager;
import android.content.Context;

final class RamUtils {
    private RamUtils() {
    }

    static int getPhysicalRamGb(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(
                    Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            // OEM memory sizes are decimal GB. Rounding upward also accounts for memory
            // reserved by firmware, so a 16 GB device does not become 14 or 15 GB.
            int result = (int) Math.ceil(info.totalMem / 1_000_000_000d);
            return result > 0 ? result : 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    static String getBasicParamValue(Context context) {
        int gb = getPhysicalRamGb(context);
        return gb > 0 ? String.valueOf(gb) : "";
    }

    static String getDisplayValue(Context context) {
        int gb = getPhysicalRamGb(context);
        return gb > 0 ? gb + " GB（自动获取）" : "读取失败（注入时将放弃覆盖）";
    }
}
