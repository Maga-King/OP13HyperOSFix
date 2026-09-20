package local.mio.op13hyperosfix.deviceparams;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedBridge;

/** Builds the former Settings.apk-only visual treatment at runtime. */
final class CustomAppearanceRenderer {
    private static final String TAG = "COSOS4Beauty: ";
    private static final int GLASS_LIGHT = 0x24ffffff;
    private static final int GLASS_DARK = 0x24ffffff;
    private static final int DARK_PRIMARY = 0xfff5f5f7;
    private static final int DARK_SECONDARY = 0xffaaaab2;

    private static final Map<View, PageState> STATES = Collections.synchronizedMap(
            new WeakHashMap<View, PageState>());

    private CustomAppearanceRenderer() {
    }

    static void apply(Object fragment, View root, HookConfig config) {
        if (root == null || config == null || !config.hasAppearanceMode()) return;
        boolean dark = config.forceCustomDark || isSystemDark(root.getContext());
        PageState state = STATES.get(root);
        if (state == null) {
            state = new PageState();
            STATES.put(root, state);
        }
        state.forceDarkActive = config.forceCustomDark;
        try {
            if (config.forceCustomDark) {
                applyForcedDarkBackdrop(state, fragment, root);
            } else {
                restoreDarkBackdrop(state);
                restoreDarkActionBar(state);
            }
            installTopCard(state, root, config, dark);
            installQuickCards(state, root, config, dark);
            styleParameterCard(root, dark);
            stylePreferences(fragment, root, dark);
            clearStructuralBackgrounds(root);
            stabilizeStockAnimations(root);
            if (config.forceCustomDark) {
                applyDarkWindow(state, fragment, root.getContext());
                applyDarkActionBar(state, fragment);
            }
            XposedBridge.log(TAG + "runtime layout applied mode="
                    + (config.forceCustomDark ? "force-dark" : (dark ? "dual-dark" : "dual-light")));
        } catch (Throwable t) {
            XposedBridge.log(TAG + "runtime layout partial failure: " + t);
        }
    }

    /**
     * OS4's dark shader is translucent over the page's night base colour.
     * Supplying only ThemeMode.DARK therefore still looks pastel in a light
     * system. Resolve just that one colour from a night Configuration and
     * apply it to the shader target; do not replace the preference Context.
     */
    private static void applyForcedDarkBackdrop(PageState state, Object fragment, View root) {
        try {
            Activity activity = activityOf(fragment);
            if (activity == null) return;
            int viewId = root.getResources().getIdentifier(
                    "bgEffectView", "id", ConfigContract.SETTINGS_PACKAGE);
            View background = viewId == 0 ? null : activity.findViewById(viewId);
            if (background == null) return;

            // createShaderEffect() replaces the target View's own drawing.  The
            // dark shader deliberately outputs translucent colours, so setting
            // a background on bgEffectView itself cannot provide the night base.
            // The colour must live on the immediate parent behind the shader.
            View backdrop = background.getParent() instanceof View
                    ? (View) background.getParent()
                    : activity.findViewById(android.R.id.content);
            if (backdrop == null) return;
            if (!state.darkBackdropCaptured || state.darkBackdropView != backdrop) {
                state.darkBackdropView = backdrop;
                state.darkBackdropOriginal = backdrop.getBackground();
                state.darkBackdropCaptured = true;
            }

            Context source = root.getContext();
            Configuration nightConfig = new Configuration(
                    source.getResources().getConfiguration());
            nightConfig.uiMode = (nightConfig.uiMode
                    & ~Configuration.UI_MODE_NIGHT_MASK)
                    | Configuration.UI_MODE_NIGHT_YES;
            Context night = source.createConfigurationContext(nightConfig);
            int colorId = night.getResources().getIdentifier(
                    "my_device_background_color", "color",
                    ConfigContract.SETTINGS_PACKAGE);
            int color = colorId == 0 ? Color.BLACK : night.getColor(colorId);
            backdrop.setBackgroundColor(color);
            backdrop.invalidate();
            XposedBridge.log(TAG + "OS4 force-dark shader backdrop=#"
                    + Integer.toHexString(color) + " target="
                    + backdrop.getClass().getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + "force-dark shader backdrop fail-open: " + t);
        }
    }

    private static void restoreDarkBackdrop(PageState state) {
        if (state == null || !state.darkBackdropCaptured || state.darkBackdropView == null) {
            return;
        }
        try {
            state.darkBackdropView.setBackground(state.darkBackdropOriginal);
            state.darkBackdropView.invalidate();
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dark shader backdrop restore skipped: " + t);
        } finally {
            state.darkBackdropView = null;
            state.darkBackdropOriginal = null;
            state.darkBackdropCaptured = false;
        }
    }

    static void restoreWindow(Object fragment, View root) {
        if (root == null) return;
        PageState state = STATES.get(root);
        if (state == null) return;
        // Keep the shader's night backing through the Activity exit animation.
        // Restoring it from onPause exposes the light host for one frame. The
        // old View is discarded after the transition; mode changes while the
        // page stays alive are still handled by apply() above.
        if (!state.windowCaptured) return;
        try {
            Activity activity = activityOf(fragment);
            if (activity == null) return;
            Window window = activity.getWindow();
            window.setStatusBarColor(state.statusBarColor);
            window.setNavigationBarColor(state.navigationBarColor);
            window.getDecorView().setSystemUiVisibility(state.systemUiVisibility);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(state.systemBarsAppearance, 0x18);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "window restore skipped: " + t);
        }
    }

    static void releasePage(View root) {
        if (root != null) STATES.remove(root);
    }

    static void refreshForcedDarkChrome(Object fragment, View root) {
        if (fragment == null || root == null) return;
        PageState state = STATES.get(root);
        if (state == null || !state.forceDarkActive) return;
        applyDarkActionBar(state, fragment);
        applyDarkWindow(state, fragment, root.getContext());
    }

    /** Match an ActionBar constructed by MIUIX under UI_MODE_NIGHT_YES. */
    private static void applyDarkActionBar(PageState pageState, Object fragment) {
        try {
            Object actionBar = fragment.getClass().getMethod("getAppCompatActionBar")
                    .invoke(fragment);
            if (actionBar == null) return;

            if (!pageState.actionBarMaskCaptured
                    || pageState.actionBarObject != actionBar) {
                Object container = RuntimeUtils.findField(
                        actionBar.getClass(), "mContainerView").get(actionBar);
                if (container != null) {
                    pageState.actionBarObject = actionBar;
                    pageState.actionBarContainer = container;
                    pageState.actionBarOriginalMaskColor = RuntimeUtils.findField(
                            container.getClass(), "mMaskColor").getInt(container);
                    pageState.actionBarMaskCaptured = true;
                }
            }

            // A null OverlayMaskConfig intentionally uses MIUIX's original
            // 17-stop curve. Only the mask colour differs between day/night.
            actionBar.getClass().getMethod("setMaskColor", int.class)
                    .invoke(actionBar, Color.BLACK);

            Method getTitleView = actionBar.getClass().getMethod(
                    "getTitleView", int.class);
            for (int state = 0; state <= 2; state++) {
                Object title = getTitleView.invoke(actionBar, state);
                if (title instanceof View) {
                    tintActionBarTree(pageState, (View) title);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dark ActionBar chrome skipped: " + t);
        }
    }

    private static void tintActionBarTree(PageState state, View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (!state.actionBarTextColors.containsKey(text)) {
                state.actionBarTextColors.put(text, text.getTextColors());
            }
            text.setTextColor(DARK_PRIMARY);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintActionBarTree(state, group.getChildAt(i));
            }
        }
    }

    private static void restoreDarkActionBar(PageState state) {
        if (state == null) return;
        if (state.actionBarMaskCaptured && state.actionBarObject != null) {
            try {
                state.actionBarObject.getClass().getMethod("setMaskColor", int.class)
                        .invoke(state.actionBarObject, state.actionBarOriginalMaskColor);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "ActionBar mask restore skipped: " + t);
            }
        }
        for (Map.Entry<TextView, ColorStateList> entry
                : state.actionBarTextColors.entrySet()) {
            try {
                entry.getKey().setTextColor(entry.getValue());
            } catch (Throwable ignored) {
            }
        }
        state.actionBarTextColors.clear();
        state.actionBarObject = null;
        state.actionBarContainer = null;
        state.actionBarMaskCaptured = false;
    }

    private static void installTopCard(PageState state, View root, HookConfig config,
                                       boolean dark) {
        View stock = find(root, "miui_version_card_view");
        View placeholder = find(root, "version_card_click_view");
        if (stock == null || placeholder == null
                || !(placeholder.getParent() instanceof ViewGroup)) return;

        // MiuiVersionCard itself lives in the full-screen overlay.  Adding our card next to it
        // starts layout at y=0 and covers the status/action bars, while the click placeholder in
        // scroll_layout still reserves a second 180dp block.  Replace that placeholder in the
        // normal scroll flow instead, so the action bar inset and following cards are preserved.
        ViewGroup parent = (ViewGroup) placeholder.getParent();
        if (state.topCard == null || state.topCard.getParent() != parent) {
            state.stockVersionCard = stock;
            state.stockVersionVisibility = stock.getVisibility();
            int index = parent.indexOfChild(placeholder);
            FrameLayout card = buildTopCard(root.getContext(), stock, config, dark, state);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(root.getContext(), 180));
            parent.addView(card, Math.max(0, index), params);
            placeholder.setVisibility(View.GONE);
            stock.setVisibility(View.GONE);
            state.topCard = card;
            XposedBridge.log(TAG + "custom top card installed in scroll flow");
        }
        updateTopCard(state, config, dark);
        placeholder.setVisibility(View.GONE);
        placeholder.setLongClickable(false);
        placeholder.setOnLongClickListener(null);
        stock.setVisibility(View.GONE);
    }

    private static FrameLayout buildTopCard(Context context, final View stock,
                                            HookConfig config, boolean dark,
                                            PageState state) {
        FrameLayout outer = new FrameLayout(context);
        int horizontal = dp(context, 12);
        int vertical = dp(context, 16);
        outer.setPadding(horizontal, vertical, horizontal, vertical);
        outer.setClipToPadding(false);

        final FrameLayout card = new FrameLayout(context);
        clipRound(card, dp(context, 20));
        outer.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 150)));

        View wash = new View(context);
        card.addView(wash, matchMatch());
        state.topWash = wash;

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(context, 14), 0, dp(context, 16), 0);
        card.addView(content, matchMatch());

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView title = text(context, 34, true);
        title.setIncludeFontPadding(false);
        labels.addView(title, wrapWrap());
        TextView subtitle = text(context, 16, false);
        subtitle.setText("Flashh Chaton On Top!");
        subtitle.setSingleLine(true);
        labels.addView(subtitle, wrapWrap());
        TextView signature = text(context, 11, false);
        signature.setText("A.R.O.N.A");
        signature.setSingleLine(true);
        labels.addView(signature, wrapWrap());
        state.topTitle = title;
        state.topSubtitle = subtitle;
        state.topSignature = signature;

        FrameLayout phoneHost = new FrameLayout(context);
        phoneHost.setClipChildren(true);
        phoneHost.setClipToPadding(true);
        LinearLayout.LayoutParams phoneHostParams = new LinearLayout.LayoutParams(
                dp(context, 100), dp(context, 150));
        phoneHostParams.setMarginStart(dp(context, 10));
        phoneHostParams.setMarginEnd(dp(context, 3));
        content.addView(phoneHost, phoneHostParams);

        FrameLayout phone = new FrameLayout(context);
        clipRound(phone, dp(context, 14));
        FrameLayout.LayoutParams phoneParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 150));
        phoneParams.topMargin = dp(context, 18);
        phoneHost.addView(phone, phoneParams);

        ImageView wallpaper = new ImageView(context);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        setWallpaper(wallpaper);
        phone.addView(wallpaper, matchMatch());

        View phoneShade = new View(context);
        phoneShade.setBackgroundColor(0x44000000);
        phone.addView(phoneShade, matchMatch());

        TextClock time = clock(context, 18, "hh:mm", "HH:mm");
        FrameLayout.LayoutParams timeParams = wrapFrame(Gravity.TOP | Gravity.START);
        timeParams.setMargins(dp(context, 7), dp(context, 10), 0, 0);
        phone.addView(time, timeParams);
        TextClock date = clock(context, 12.6f, "dd/MM", "dd/MM");
        FrameLayout.LayoutParams dateParams = wrapFrame(Gravity.TOP | Gravity.START);
        dateParams.setMargins(dp(context, 7), dp(context, 30), 0, 0);
        phone.addView(date, dateParams);
        TextClock week = clock(context, 12.6f, "EEE", "EEE");
        FrameLayout.LayoutParams weekParams = wrapFrame(Gravity.TOP | Gravity.START);
        weekParams.setMargins(dp(context, 7), dp(context, 44), 0, 0);
        phone.addView(week, weekParams);

        GradientDrawable border = round(Color.TRANSPARENT, dp(context, 14));
        border.setStroke(dp(context, 3), 0xccffffff);
        phone.setForeground(border);

        outer.setClickable(true);
        outer.setFocusable(true);
        outer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stock.performClick();
            }
        });
        outer.setLongClickable(false);
        return outer;
    }

    private static void updateTopCard(PageState state, HookConfig config, boolean dark) {
        if (state.topTitle == null) return;
        int accent = monetAccent(state.topTitle.getContext());
        SpannableString title = new SpannableString("HyperOS");
        title.setSpan(new ForegroundColorSpan(accent), 5, 7,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        state.topTitle.setText(title);
        int primary = dark ? DARK_PRIMARY : 0xff17171a;
        int secondary = dark ? 0xd9f5f5f7 : 0xcc17171a;
        int tertiary = dark ? 0x99f5f5f7 : 0x9917171a;
        state.topTitle.setTextColor(primary);
        state.topSubtitle.setTextColor(secondary);
        state.topSignature.setTextColor(tertiary);
        if (state.topWash != null) {
            state.topWash.setBackgroundColor(dark ? GLASS_DARK : GLASS_LIGHT);
        }
    }

    private static void installQuickCards(PageState state, View root, HookConfig config,
                                          boolean dark) {
        View stock = find(root, "device_basic_layout");
        if (stock == null || !(stock.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) stock.getParent();
        if (state.quickCards == null || state.quickCards.getParent() != parent) {
            state.stockBasic = stock;
            state.stockBasicVisibility = stock.getVisibility();
            LinearLayout row = new LinearLayout(root.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            int index = parent.indexOfChild(stock);
            parent.addView(row, Math.max(0, index), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(root.getContext(), 130)));

            View stockName = find(root, "device_name_card_view");
            View stockStorage = find(root, "device_memory_card_view");
            state.stockNameCard = stockName;
            state.stockStorageCard = stockStorage;
            state.deviceCard = quickCard(root.getContext(), true, stockName, state);
            state.storageCard = quickCard(root.getContext(), false, stockStorage, state);
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(
                    0, dp(root.getContext(), 130), 1f);
            left.setMargins(dp(root.getContext(), 12), 0, dp(root.getContext(), 8),
                    dp(root.getContext(), 16));
            row.addView(state.deviceCard, left);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(
                    0, dp(root.getContext(), 130), 1f);
            right.setMargins(dp(root.getContext(), 4), 0, dp(root.getContext(), 12),
                    dp(root.getContext(), 16));
            row.addView(state.storageCard, right);
            stock.setVisibility(View.GONE);
            state.quickCards = row;
            XposedBridge.log(TAG + "device/storage quick cards installed");
        }
        int glass = dark ? GLASS_DARK : GLASS_LIGHT;
        state.deviceCard.setBackground(round(glass, dp(root.getContext(), 19)));
        state.storageCard.setBackground(round(glass, dp(root.getContext(), 19)));
        int primary = dark ? DARK_PRIMARY : 0xff111114;
        int secondary = dark ? DARK_SECONDARY : 0xff777780;
        int accent = monetAccent(root.getContext());
        state.deviceTitle.setTextColor(primary);
        state.deviceValue.setTextColor(secondary);
        state.storageTitle.setTextColor(primary);
        state.storageValue.setTextColor(secondary);
        if (state.deviceGlyph != null) state.deviceGlyph.setAccent(accent);
        if (state.storageProgress != null) {
            state.storageProgress.setAccent(accent);
        }
        state.deviceValue.setText(resolveDeviceName(root.getContext(), config));
        updateStorage(state, root.getContext());
    }

    private static FrameLayout quickCard(Context context, boolean device, final View stock,
                                         PageState state) {
        FrameLayout card = new FrameLayout(context);
        card.setClipToOutline(true);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(context, 16), 0, dp(context, 16), dp(context, 16));
        card.addView(content, matchMatch());
        if (device) {
            FrameLayout iconSlot = new FrameLayout(context);
            LinearLayout.LayoutParams slotParams = new LinearLayout.LayoutParams(
                    dp(context, 38), dp(context, 38));
            slotParams.topMargin = dp(context, 16);
            content.addView(iconSlot, slotParams);
            PhoneGlyph glyph = new PhoneGlyph(context);
            state.deviceGlyph = glyph;
            FrameLayout.LayoutParams glyphParams = new FrameLayout.LayoutParams(
                    dp(context, 28), dp(context, 28), Gravity.START | Gravity.CENTER_VERTICAL);
            iconSlot.addView(glyph, glyphParams);
        } else {
            StorageBar progress = new StorageBar(context);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 38));
            progressParams.setMargins(dp(context, 5), dp(context, 16), dp(context, 5), 0);
            content.addView(progress, progressParams);
            state.storageProgress = progress;
        }
        TextView title = text(context, 16, false);
        title.setText(device ? "设备名称" : "存储空间");
        LinearLayout.LayoutParams titleParams = wrapLinear();
        titleParams.topMargin = dp(context, 12);
        content.addView(title, titleParams);
        TextView value = text(context, 14, false);
        LinearLayout.LayoutParams valueParams = wrapLinear();
        valueParams.topMargin = dp(context, 4);
        content.addView(value, valueParams);
        if (device) {
            state.deviceTitle = title;
            state.deviceValue = value;
        } else {
            state.storageTitle = title;
            state.storageValue = value;
        }
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (stock != null) stock.performClick();
            }
        });
        return card;
    }

    private static void styleParameterCard(View root, boolean dark) {
        View params = find(root, "device_params");
        if (params == null) return;
        params.setBackground(round(dark ? GLASS_DARK : GLASS_LIGHT,
                dp(root.getContext(), 16)));
        if (dark) tintParameterTree(params, DARK_PRIMARY, DARK_SECONDARY);
    }

    private static void stylePreferences(Object fragment, View root, boolean dark) {
        int color = dark ? GLASS_DARK : GLASS_LIGHT;
        try {
            Field field = RuntimeUtils.findField(fragment.getClass(), "mFrameDecoration");
            styleDecoration(field.get(fragment), color);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "fragment FrameDecoration lookup skipped: " + t);
        }
        walkRecyclerDecorations(root, color);
        View prefs = find(root, "prefs_container");
        if (prefs != null && dark) tintTextTree(prefs, DARK_PRIMARY, DARK_SECONDARY);
    }

    private static void styleDecoration(Object decoration, int color) {
        if (decoration == null) return;
        setFieldIfPresent(decoration, "mCardGroupBackground", new ColorDrawable(color));
        setIntFieldIfPresent(decoration, "mGroupUnCheckedBgColor", color);
        setIntFieldIfPresent(decoration, "mCheckableFilterColorNormal", color);
        setIntFieldIfPresent(decoration, "mCheckableFilterColorChecked", color);
        setPaint(decoration, "mPaint", color);
        setPaint(decoration, "mGroupBgPaint", color);
    }

    private static void walkRecyclerDecorations(View view, int color) {
        if (view.getClass().getName().contains("RecyclerView")) {
            try {
                Method count = view.getClass().getMethod("getItemDecorationCount");
                Method at = view.getClass().getMethod("getItemDecorationAt", int.class);
                int size = (Integer) count.invoke(view);
                for (int i = 0; i < size; i++) {
                    Object decoration = at.invoke(view, i);
                    if (decoration != null
                            && decoration.getClass().getName().contains("FrameDecoration")) {
                        styleDecoration(decoration, color);
                    }
                }
                try {
                    view.getClass().getMethod("invalidateItemDecorations").invoke(view);
                } catch (Throwable ignored) {
                }
                view.invalidate();
            } catch (Throwable ignored) {
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walkRecyclerDecorations(group.getChildAt(i), color);
            }
        }
    }

    private static void clearStructuralBackgrounds(View root) {
        int l = root.getPaddingLeft();
        int t = root.getPaddingTop();
        int r = root.getPaddingRight();
        int b = root.getPaddingBottom();
        root.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(l, t, r, b);
        View scroll = find(root, "scroll_layout");
        if (scroll != null) {
            int sl = scroll.getPaddingLeft();
            int st = scroll.getPaddingTop();
            int sr = scroll.getPaddingRight();
            int sb = scroll.getPaddingBottom();
            scroll.setBackgroundColor(Color.TRANSPARENT);
            scroll.setPadding(sl, st, sr, sb);
        }
    }

    private static void stabilizeStockAnimations(View root) {
        String[] names = {"miui_logo_view", "version_layout", "update_hint_text"};
        for (String name : names) {
            View view = find(root, name);
            if (view != null && view.getVisibility() == View.VISIBLE) view.setAlpha(1f);
        }
        View stock = find(root, "miui_version_card_view");
        if (stock != null) {
            stock.setLongClickable(false);
            stock.setOnLongClickListener(null);
            try {
                stock.getClass().getMethod("stopLogoAnimation").invoke(stock);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyDarkWindow(PageState state, Object fragment, Context context) {
        try {
            Activity activity = activityOf(fragment);
            if (activity == null) return;
            Window window = activity.getWindow();
            View decor = window.getDecorView();
            if (!state.windowCaptured) {
                state.statusBarColor = window.getStatusBarColor();
                state.navigationBarColor = window.getNavigationBarColor();
                state.systemUiVisibility = decor.getSystemUiVisibility();
                WindowInsetsController controller = window.getInsetsController();
                state.systemBarsAppearance = controller == null ? 0
                        : controller.getSystemBarsAppearance();
                state.windowCaptured = true;
            }
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(0xff05050a);
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~0x2010);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(0, 0x18);
            View up = activity.findViewById(resourceId(context, "up"));
            tintImages(up, Color.WHITE);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "dark window skipped: " + t);
        }
    }

    private static void tintTextTree(View view, int primary, int secondary) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            float size = text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity;
            text.setTextColor(size <= 14.5f ? secondary : primary);
        } else if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            if (image.getDrawable() != null) image.getDrawable().mutate().setTint(secondary);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintTextTree(group.getChildAt(i), primary, secondary);
            }
        }
    }

    private static void tintParameterTree(View view, int primary, int secondary) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            int id = text.getId();
            Context context = text.getContext();
            int bigTitle = resourceId(context, "card_big_title");
            int value = resourceId(context, "card_value");
            int secondValue = resourceId(context, "second_card_value");
            int label = resourceId(context, "card_title");
            if (id == bigTitle || id == value || id == secondValue) {
                text.setTextColor(primary);
            } else if (id == label) {
                text.setTextColor(secondary);
            } else {
                // Camera section/title rows have no id.  Only their 11sp caption is secondary;
                // the section heading itself stays the same primary colour as numeric values.
                float size = text.getTextSize()
                        / text.getResources().getDisplayMetrics().scaledDensity;
                text.setTextColor(size <= 11.5f ? secondary : primary);
            }
        } else if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            if (image.getDrawable() != null) image.getDrawable().mutate().setTint(primary);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                tintParameterTree(group.getChildAt(i), primary, secondary);
            }
        }
    }

    private static void tintImages(View view, int color) {
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            if (image.getDrawable() != null) image.getDrawable().mutate().setTint(color);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) tintImages(group.getChildAt(i), color);
        }
    }

    private static String resolveDeviceName(Context context, HookConfig config) {
        if (config.paramsEnabled && config.marketName != null
                && !config.marketName.trim().isEmpty()) return config.marketName.trim();
        try {
            String name = Settings.Global.getString(context.getContentResolver(),
                    Settings.Global.DEVICE_NAME);
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (Throwable ignored) {
        }
        return Build.MODEL == null ? "Android" : Build.MODEL;
    }

    private static void updateStorage(PageState state, Context context) {
        try {
            StatFs stat = new StatFs(Environment.getDataDirectory().getAbsolutePath());
            long total = stat.getTotalBytes();
            long used = total - stat.getAvailableBytes();
            double divisor = 1024d * 1024d * 1024d;
            double totalGb = total / divisor;
            double usedGb = used / divisor;
            String totalText = totalGb >= 100 ? String.valueOf(Math.round(totalGb))
                    : String.format(java.util.Locale.US, "%.1f", totalGb);
            String stockText = textById(state.stockStorageCard, "summary");
            state.storageValue.setText(stockText != null && stockText.contains("GB")
                    ? stockText : String.format(java.util.Locale.US,
                    "%.1fGB/%sGB", usedGb, totalText));
            if (state.storageProgress != null && total > 0) {
                state.storageProgress.setRatio((float) Math.min(1d,
                        (double) used / (double) total));
            }
        } catch (Throwable t) {
            state.storageValue.setText("读取中");
        }
    }

    private static String textById(View root, String idName) {
        if (root == null) return null;
        int id = resourceId(root.getContext(), idName);
        if (id == 0) return null;
        View view = root.findViewById(id);
        if (!(view instanceof TextView)) return null;
        CharSequence text = ((TextView) view).getText();
        return text == null ? null : text.toString().trim();
    }

    private static void setWallpaper(ImageView image) {
        try {
            Drawable drawable = WallpaperManager.getInstance(image.getContext()).getDrawable();
            if (drawable != null && drawable.getConstantState() != null) {
                drawable = drawable.getConstantState().newDrawable().mutate();
            }
            image.setImageDrawable(drawable);
        } catch (Throwable t) {
            image.setBackgroundColor(0xff162660);
        }
    }

    private static Activity activityOf(Object fragment) {
        try {
            Object value = fragment.getClass().getMethod("getActivity").invoke(fragment);
            return value instanceof Activity ? (Activity) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static View find(View root, String name) {
        int id = resourceId(root.getContext(), name);
        return id == 0 ? null : root.findViewById(id);
    }

    private static int resourceId(Context context, String name) {
        return context.getResources().getIdentifier(name, "id", ConfigContract.SETTINGS_PACKAGE);
    }

    private static boolean isSystemDark(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static int monetAccent(Context context) {
        try {
            return context.getColor(android.R.color.system_accent1_500);
        } catch (Throwable ignored) {
            return 0xff7183aa;
        }
    }

    private static TextView text(Context context, int sp, boolean bold) {
        TextView view = new TextView(context);
        view.setTextSize(sp);
        view.setGravity(Gravity.START);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(0, 1.05f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private static TextClock clock(Context context, float sp, String format12, String format24) {
        TextClock view = new TextClock(context);
        view.setTextSize(sp);
        view.setTextColor(0xe6ffffff);
        view.setFormat12Hour(format12);
        view.setFormat24Hour(format24);
        view.setIncludeFontPadding(false);
        return view;
    }

    private static void clipRound(final View view, final float radius) {
        view.setClipToOutline(true);
        view.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static void setFieldIfPresent(Object owner, String name, Object value) {
        try {
            RuntimeUtils.findField(owner.getClass(), name).set(owner, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setIntFieldIfPresent(Object owner, String name, int value) {
        try {
            RuntimeUtils.findField(owner.getClass(), name).setInt(owner, value);
        } catch (Throwable ignored) {
        }
    }

    private static void setPaint(Object owner, String name, int color) {
        try {
            Object value = RuntimeUtils.findField(owner.getClass(), name).get(owner);
            if (value instanceof Paint) ((Paint) value).setColor(color);
        } catch (Throwable ignored) {
        }
    }

    private static FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static ViewGroup.LayoutParams wrapWrap() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapLinear() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams wrapFrame(int gravity) {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, gravity);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class PageState {
        View stockVersionCard;
        int stockVersionVisibility;
        FrameLayout topCard;
        View topWash;
        TextView topTitle;
        TextView topSubtitle;
        TextView topSignature;

        View stockBasic;
        int stockBasicVisibility;
        View stockNameCard;
        View stockStorageCard;
        LinearLayout quickCards;
        FrameLayout deviceCard;
        FrameLayout storageCard;
        TextView deviceTitle;
        TextView deviceValue;
        TextView storageTitle;
        TextView storageValue;
        StorageBar storageProgress;
        PhoneGlyph deviceGlyph;

        boolean windowCaptured;
        boolean darkBackdropCaptured;
        View darkBackdropView;
        Drawable darkBackdropOriginal;
        int statusBarColor;
        int navigationBarColor;
        int systemUiVisibility;
        int systemBarsAppearance;
        boolean forceDarkActive;

        boolean actionBarMaskCaptured;
        Object actionBarObject;
        Object actionBarContainer;
        int actionBarOriginalMaskColor;
        final Map<TextView, ColorStateList> actionBarTextColors =
                new IdentityHashMap<TextView, ColorStateList>();
    }

    private static final class PhoneGlyph extends View {
        private final Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint phone = new Paint(Paint.ANTI_ALIAS_FLAG);

        PhoneGlyph(Context context) {
            super(context);
            circle.setColor(monetAccent(context));
            phone.setColor(Color.WHITE);
        }

        void setAccent(int color) {
            circle.setColor(color);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            canvas.drawCircle(cx, cy, Math.min(cx, cy), circle);
            float scale = Math.min(getWidth(), getHeight()) / 32f;
            canvas.drawRoundRect(9.2f * scale, 7.2f * scale,
                    22.6064f * scale, 24.8f * scale,
                    1.4f * scale, 1.4f * scale, phone);
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setColor(circle.getColor());
            canvas.drawRoundRect(13.5f * scale, 20.2f * scale,
                    18.5f * scale, 21.8f * scale,
                    0.3f * scale, 0.3f * scale, cut);
        }
    }

    private static final class StorageBar extends View {
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float ratio;

        StorageBar(Context context) {
            super(context);
            setAccent(monetAccent(context));
        }

        void setAccent(int accent) {
            fill.setColor(accent);
            track.setColor(darken(accent, 0.78f));
            invalidate();
        }

        void setRatio(float value) {
            ratio = Math.max(0f, Math.min(1f, value));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float height = dp(getContext(), 5);
            float top = (getHeight() - height) / 2f;
            float radius = height / 2f;
            canvas.drawRoundRect(0, top, getWidth(), top + height,
                    radius, radius, track);
            float right = Math.max(height, getWidth() * ratio);
            canvas.drawRoundRect(0, top, right, top + height,
                    radius, radius, fill);
        }

        private static int darken(int color, float factor) {
            int red = Math.round(Color.red(color) * factor);
            int green = Math.round(Color.green(color) * factor);
            int blue = Math.round(Color.blue(color) * factor);
            return Color.rgb(red, green, blue);
        }
    }
}
