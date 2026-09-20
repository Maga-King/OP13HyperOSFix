package local.mio.op13hyperosfix.deviceparams;

import android.net.Uri;

final class ConfigContract {
    static final String PACKAGE_NAME = "local.mio.op13hyperosfix";
    static final String SETTINGS_PACKAGE = "com.android.settings";
    static final String AUTHORITY = PACKAGE_NAME + ".deviceparams.config";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");
    static final String PREFS = "device_params";

    static final String ENABLED = "enabled";
    static final String MARKET_NAME = "market_name";
    static final String PROCESSOR = "processor";
    static final String BATTERY = "battery";
    static final String RESOLUTION = "resolution";
    static final String SCREEN_SIZE = "screen_size";
    static final String FRONT_CAMERA = "front_camera";
    static final String REAR_CAMERA = "rear_camera";
    static final String BEAUTY_ENABLED = "beauty_enabled";
    static final String FORCE_CUSTOM_DARK = "force_custom_dark";
    static final String CUSTOM_DUAL = "custom_dual";
    static final String MUSIC_ENABLED = "music_enabled";

    static final String DEFAULT_MARKET_NAME = "一加 13";
    static final String DEFAULT_PROCESSOR = "骁龙®8至尊版移动平台";
    static final String DEFAULT_BATTERY = "6000 mAh (典型值) 冰川电池";
    static final String DEFAULT_RESOLUTION = "3168×1440";
    static final String DEFAULT_SCREEN_SIZE = "6.82″";
    static final String DEFAULT_FRONT_CAMERA = "32MP";
    static final String DEFAULT_REAR_CAMERA = "50MP+50MP+50MP";

    static final boolean DEFAULT_PARAMS_ENABLED = false;
    static final boolean DEFAULT_BEAUTY_ENABLED = true;
    static final boolean DEFAULT_FORCE_CUSTOM_DARK = true;
    static final boolean DEFAULT_CUSTOM_DUAL = false;
    static final boolean DEFAULT_MUSIC_ENABLED = true;

    static final String[] COLUMNS = {
            ENABLED,
            MARKET_NAME,
            PROCESSOR,
            BATTERY,
            RESOLUTION,
            SCREEN_SIZE,
            FRONT_CAMERA,
            REAR_CAMERA,
            BEAUTY_ENABLED,
            FORCE_CUSTOM_DARK,
            CUSTOM_DUAL,
            MUSIC_ENABLED
    };

    private ConfigContract() {
    }
}
