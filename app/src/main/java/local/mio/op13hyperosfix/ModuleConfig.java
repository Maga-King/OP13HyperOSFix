package local.mio.op13hyperosfix;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

final class ModuleConfig {
    static final String MORE_NOTIFICATION_SETTINGS =
            "op13_fix_more_notification_settings";
    static final String DOUBLE_TAP_WAKE = "op13_fix_double_tap_wake";
    static final String TOUCH_SAMPLING_RATE = "op13_fix_touch_sampling_rate";
    static final String GAME_TOUCH_ENABLED = "op13_fix_game_touch_enabled";
    static final String GAME_TOUCH_RATE = "op13_fix_game_touch_rate";
    static final String GAME_BYPASS_ENABLED = "op13_fix_game_bypass_enabled";
    static final String GAME_BYPASS_ENTRY_TEMP =
            "op13_fix_game_bypass_entry_temp";
    static final String GAME_PACKAGES = "op13_fix_game_packages";
    static final String GAME_APP_POLICIES = "op13_fix_game_app_policies";
    static final String SCENE_SCHEDULER_ENABLED =
            "op13_fix_scene_scheduler_enabled";
    static final String SCENE_DAILY_MODE = "op13_fix_scene_daily_mode";
    static final String SCENE_GAME_MODE = "op13_fix_scene_game_mode";
    static final String SCENE_THREAD_PLACEMENT =
            "op13_fix_scene_thread_placement";
    static final String SCENE_CONFIG_LOCK = "op13_fix_scene_config_lock";
    static final String SCENE_FAS_ENABLED = "op13_fix_scene_fas_enabled";
    static final String GAMEOPT_BLOCK_ENABLED =
            "op13_fix_gameopt_block_enabled";
    static final String SCHEDULER_LOGGING =
            "op13_fix_scheduler_logging";
    static final String DAILY_POWER_MONITOR =
            "op13_fix_daily_power_monitor";
    static final String CORE_MASTER = "op13_fix_core_master";
    static final String CORE_DOWNGRADE = "op13_fix_core_downgrade";
    static final String CORE_AUTH = "op13_fix_core_auth";
    static final String CORE_DISABLE_INTEGRITY = "op13_fix_core_disable_integrity";
    static final String CORE_SHARED_USER = "op13_fix_core_shared_user";
    static final String CORE_DIGEST = "op13_fix_core_digest";
    static final String CORE_EXACT_SIGNATURE = "op13_fix_core_exact_signature";
    static final String CORE_USE_PRE_SIGNATURE = "op13_fix_core_use_pre_signature";
    static final String CORE_LOW_API = "op13_fix_core_low_api";
    static final String CORE_DISABLE_PERSISTENT = "op13_fix_core_disable_persistent";
    static final String CORE_DISABLE_VERIFICATION = "op13_fix_core_disable_verification";
    static final String CORE_BYPASS_ISOLATION = "op13_fix_core_bypass_isolation";
    static final String CORE_ALLOW_SYSTEM_UPDATE = "op13_fix_core_allow_system_update";
    static final String CORE_PROTECT_FINGERPRINT = "op13_fix_core_protect_fingerprint";

    private static final String ACTIVE_BOOT = "op13_fix_xposed_active_boot";
    private static final String ACTIVE_API = "op13_fix_xposed_active_api";

    private static final String[] CORE_KEYS = {
            CORE_MASTER,
            CORE_DOWNGRADE,
            CORE_AUTH,
            CORE_DISABLE_INTEGRITY,
            CORE_SHARED_USER,
            CORE_DIGEST,
            CORE_EXACT_SIGNATURE,
            CORE_USE_PRE_SIGNATURE,
            CORE_LOW_API,
            CORE_DISABLE_PERSISTENT,
            CORE_DISABLE_VERIFICATION,
            CORE_BYPASS_ISOLATION,
            CORE_ALLOW_SYSTEM_UPDATE,
            CORE_PROTECT_FINGERPRINT
    };

    private static volatile Map<String, Boolean> coreValues = Collections.emptyMap();
    private static volatile Context systemContext;

    private ModuleConfig() {
    }

    static boolean isEnabled(Context context, String key, boolean defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        try {
            return Settings.Global.getInt(context.getContentResolver(), key,
                    defaultValue ? 1 : 0) != 0;
        } catch (Throwable error) {
            HookLog.once("config_read_" + key,
                    "cannot read config " + key + ": " + error);
            return defaultValue;
        }
    }

    static int getInt(Context context, String key, int defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(), key, defaultValue);
        } catch (Throwable error) {
            HookLog.once("config_read_" + key,
                    "cannot read config " + key + ": " + error);
            return defaultValue;
        }
    }

    static String getString(Context context, String key, String defaultValue) {
        if (context == null) {
            return defaultValue;
        }
        try {
            String value = Settings.Global.getString(
                    context.getContentResolver(), key);
            return value == null ? defaultValue : value;
        } catch (Throwable error) {
            HookLog.once("config_read_" + key,
                    "cannot read config " + key + ": " + error);
            return defaultValue;
        }
    }

    static synchronized void attachSystemContext(Context context) {
        Context application = context == null ? null : context.getApplicationContext();
        systemContext = application != null ? application : context;
        reloadCore();
        markXposedActive();
    }

    static synchronized void reloadCore() {
        Context context = systemContext;
        if (context == null) {
            coreValues = Collections.emptyMap();
            return;
        }
        Map<String, Boolean> values = new HashMap<>();
        for (String key : CORE_KEYS) {
            values.put(key, isEnabled(context, key, false));
        }
        coreValues = Collections.unmodifiableMap(values);
    }

    static boolean coreMasterEnabled() {
        return value(CORE_MASTER);
    }

    static boolean coreFeatureEnabled(String key) {
        return coreMasterEnabled() && value(key);
    }

    static boolean independentFeatureEnabled(String key) {
        return value(key);
    }

    static boolean usePreSignatureEnabled() {
        return coreFeatureEnabled(CORE_DIGEST)
                && coreFeatureEnabled(CORE_USE_PRE_SIGNATURE);
    }

    static boolean sharedUserEnabled() {
        return coreFeatureEnabled(CORE_DIGEST)
                && coreFeatureEnabled(CORE_SHARED_USER);
    }

    static boolean hasAnyCoreHookEnabled() {
        if (coreMasterEnabled()) {
            for (String key : new String[]{
                    CORE_DOWNGRADE, CORE_AUTH, CORE_DISABLE_INTEGRITY,
                    CORE_SHARED_USER, CORE_DIGEST, CORE_EXACT_SIGNATURE,
                    CORE_USE_PRE_SIGNATURE, CORE_DISABLE_VERIFICATION}) {
                if (value(key)) {
                    return true;
                }
            }
        }
        return independentFeatureEnabled(CORE_LOW_API)
                || independentFeatureEnabled(CORE_DISABLE_PERSISTENT)
                || independentFeatureEnabled(CORE_BYPASS_ISOLATION)
                || independentFeatureEnabled(CORE_ALLOW_SYSTEM_UPDATE)
                || independentFeatureEnabled(CORE_PROTECT_FINGERPRINT);
    }

    static boolean isXposedActive(Context context) {
        if (context == null) {
            return false;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            int boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
            int activeBoot = Settings.Global.getInt(resolver, ACTIVE_BOOT, -2);
            int activeApi = Settings.Global.getInt(resolver, ACTIVE_API, -1);
            // HyperOS increments BOOT_COUNT after system_server systemReady. The marker
            // written from that callback can therefore trail the final value by one.
            return boot >= 0 && activeApi == Build.VERSION.SDK_INT
                    && (activeBoot == boot || activeBoot == boot - 1);
        } catch (Throwable ignored) {
            return false;
        }
    }

    static Context systemContext() {
        return systemContext;
    }

    private static boolean value(String key) {
        return Boolean.TRUE.equals(coreValues.get(key));
    }

    private static void markXposedActive() {
        Context context = systemContext;
        if (context == null) {
            return;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            int boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1);
            Settings.Global.putInt(resolver, ACTIVE_BOOT, boot);
            Settings.Global.putInt(resolver, ACTIVE_API, Build.VERSION.SDK_INT);
        } catch (Throwable error) {
            HookLog.error("cannot persist LSPosed active marker", error);
        }
    }
}
