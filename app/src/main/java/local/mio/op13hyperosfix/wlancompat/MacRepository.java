package local.mio.op13hyperosfix.wlancompat;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class MacRepository {
    private static final String PREFS = "mac_cache";
    private static final Pattern MAC_PATTERN =
            Pattern.compile("^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$");
    private static final Set<String> ALLOWED_INTERFACES = new HashSet<>(Arrays.asList(
            "wlan0", "ap0", "swlan0", "wlan1", "p2p0"));

    private MacRepository() {
    }

    static MacValue resolve(Context context, String requestedInterface, boolean forceRefresh) {
        String interfaceName = normalizeInterface(requestedInterface);
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String cacheKey = "real_" + interfaceName;

        if (!forceRefresh) {
            String cached = normalizeMac(preferences.getString(cacheKey, null));
            if (isUsableMac(cached)) {
                return new MacValue(cached, "cache");
            }
        }

        String direct = readOneLine(new File("/sys/class/net/" + interfaceName + "/address"));
        if (isUsableMac(direct)) {
            preferences.edit().putString(cacheKey, direct).apply();
            return new MacValue(direct, "direct");
        }

        String rooted = readWithRoot(interfaceName);
        if (isUsableMac(rooted)) {
            preferences.edit().putString(cacheKey, rooted).apply();
            return new MacValue(rooted, "root");
        }

        String cached = normalizeMac(preferences.getString(cacheKey, null));
        if (isUsableMac(cached)) {
            return new MacValue(cached, "cache");
        }

        String fallbackKey = "fallback_" + interfaceName;
        String fallback = normalizeMac(preferences.getString(fallbackKey, null));
        if (!isUsableMac(fallback)) {
            fallback = makeStableFallback(context, interfaceName);
            preferences.edit().putString(fallbackKey, fallback).apply();
        }
        return new MacValue(fallback, "stable-fallback");
    }

    static boolean isUsableMac(String value) {
        String mac = normalizeMac(value);
        return mac != null
                && MAC_PATTERN.matcher(mac).matches()
                && !"00:00:00:00:00:00".equals(mac)
                && !"02:00:00:00:00:00".equals(mac)
                && !"ff:ff:ff:ff:ff:ff".equals(mac)
                && !mac.startsWith("12:34:56:");
    }

    static String normalizeMac(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('-', ':');
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeInterface(String requestedInterface) {
        if (requestedInterface != null && ALLOWED_INTERFACES.contains(requestedInterface)) {
            return requestedInterface;
        }
        return "wlan0";
    }

    private static String readOneLine(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return normalizeMac(reader.readLine());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readWithRoot(String interfaceName) {
        Process process = null;
        try {
            String command = "/system/bin/cat /sys/class/net/" + interfaceName + "/address";
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(2500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return normalizeMac(reader.readLine());
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String makeStableFallback(Context context, String interfaceName) {
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) {
                androidId = "missing-android-id";
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((androidId + ":mio-wlan:" + interfaceName)
                    .getBytes(StandardCharsets.UTF_8));
            bytes[0] = (byte) ((bytes[0] | 0x02) & 0xfe);
            StringBuilder builder = new StringBuilder(17);
            for (int i = 0; i < 6; i++) {
                if (i > 0) {
                    builder.append(':');
                }
                builder.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "02:6d:69:6f:00:01";
        }
    }
}
