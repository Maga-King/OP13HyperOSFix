package local.mio.op13hyperosfix;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;

final class NativeDiagnostics {
    private NativeDiagnostics() {
    }

    static LhdcResult probeLhdc(Context context) {
        try {
            ApplicationInfo info = context.getApplicationInfo();
            File bridge = new File(info.nativeLibraryDir, "libop13_lhdc_patch.so");
            System.load(bridge.getAbsolutePath());
            int status = nativeProbeLhdc();
            return new LhdcResult(
                    (status & 1) != 0,
                    (status & 2) != 0,
                    (status & 4) != 0,
                    null);
        } catch (Throwable error) {
            return new LhdcResult(false, false, false,
                    error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private static native int nativeProbeLhdc();

    static final class LhdcResult {
        final boolean coreLoaded;
        final boolean wrapperLoaded;
        final boolean symbolsPresent;
        final String error;

        LhdcResult(boolean coreLoaded, boolean wrapperLoaded,
                   boolean symbolsPresent, String error) {
            this.coreLoaded = coreLoaded;
            this.wrapperLoaded = wrapperLoaded;
            this.symbolsPresent = symbolsPresent;
            this.error = error;
        }
    }
}
