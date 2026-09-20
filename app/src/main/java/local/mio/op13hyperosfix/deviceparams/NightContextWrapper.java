package local.mio.op13hyperosfix.deviceparams;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ContextThemeWrapper;

final class NightContextWrapper extends ContextThemeWrapper {
    private final Context launchContext;

    private NightContextWrapper(Context base, Context launchContext) {
        // Start with a real Theme owned by the night Resources. The OS4
        // MyDevice child style no longer defines every MIUIX preference attr,
        // so using that style alone makes FrameDecoration resolve resource -1.
        super(base, 0);
        this.launchContext = launchContext;
    }

    static Context create(Context source) {
        if (source == null || source instanceof NightContextWrapper) return source;
        Configuration configuration = new Configuration(
                source.getResources().getConfiguration());
        configuration.uiMode = (configuration.uiMode
                & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        Context night = source.createConfigurationContext(configuration);
        int theme = source.getResources().getIdentifier(
                "ThemeMiuiSettings.MiuiSettings_MyDevice",
                "style", ConfigContract.SETTINGS_PACKAGE);
        NightContextWrapper wrapper = new NightContextWrapper(night, source);
        // Preserve all Activity/overlay attributes first, then add the page
        // style. Values referenced by those attributes are resolved against
        // the wrapper's night Configuration.
        wrapper.getTheme().setTo(source.getTheme());
        if (theme != 0) wrapper.getTheme().applyStyle(theme, true);
        return wrapper;
    }

    @Override
    public void startActivity(Intent intent) {
        launchContext.startActivity(intent);
    }

    @Override
    public void startActivity(Intent intent, Bundle options) {
        launchContext.startActivity(intent, options);
    }
}
