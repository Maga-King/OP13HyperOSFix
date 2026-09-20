package local.mio.op13hyperosfix.monitor;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small overlay mode picker that never brings the application task to foreground. */
final class OverlayModePickerWindow {
    interface Listener {
        void onModeSelected(int mode);
    }

    private final Context context;
    private final WindowManager windowManager;
    private View root;

    OverlayModePickerWindow(Context context) {
        this.context = context.getApplicationContext();
        windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean show(Listener listener) {
        if (!Settings.canDrawOverlays(context)) return false;
        dismiss();

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(14));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(246, 24, 25, 29));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.argb(52, 255, 255, 255));
        panel.setBackground(background);
        panel.setElevation(dp(10));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(context);
        title.setText("悬浮监视");
        title.setTextColor(Color.rgb(242, 242, 246));
        title.setTextSize(16);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1f));

        TextView close = new TextView(context);
        close.setText("×");
        close.setTextSize(27);
        close.setTextColor(Color.rgb(210, 211, 218));
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭");
        close.setTooltipText("关闭");
        close.setOnClickListener(view -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(36), dp(36)));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout modes = new LinearLayout(context);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setGravity(Gravity.CENTER);
        addMode(modes, "细条", "━",
                ProbeMonitorService.OVERLAY_STRIP, listener);
        addMode(modes, "总览", "◉",
                ProbeMonitorService.OVERLAY_GAUGES, listener);
        addMode(modes, "进程", "▦",
                ProbeMonitorService.OVERLAY_PROCESSES, listener);
        addMode(modes, "线程", "☷",
                ProbeMonitorService.OVERLAY_THREADS, listener);
        panel.addView(modes, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(72)));

        panel.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                dismiss();
                return true;
            }
            return false;
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(302), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(74);
        params.setTitle("OP13OverlayModePicker");
        root = panel;
        windowManager.addView(root, params);
        root.setAlpha(0f);
        root.setScaleX(0.96f);
        root.setScaleY(0.96f);
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160L).start();
        return true;
    }

    void dismiss() {
        View current = root;
        root = null;
        if (current == null) return;
        try {
            windowManager.removeViewImmediate(current);
        } catch (Throwable ignored) {
        }
    }

    private void addMode(LinearLayout parent, String label, String symbol, int mode,
            Listener listener) {
        LinearLayout item = new LinearLayout(context);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(3), dp(5), dp(3), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(43, 45, 52));
        background.setCornerRadius(dp(7));
        item.setBackground(background);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> {
            dismiss();
            listener.onModeSelected(mode);
        });

        TextView icon = new TextView(context);
        icon.setText(symbol);
        icon.setTextSize(23);
        icon.setTextColor(Color.rgb(166, 198, 255));
        icon.setGravity(Gravity.CENTER);
        item.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        TextView text = new TextView(context);
        text.setText(label);
        text.setTextColor(Color.rgb(232, 233, 239));
        text.setTextSize(12);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(24));
        textParams.topMargin = dp(3);
        item.addView(text, textParams);

        LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(dp(62), dp(64));
        if (parent.getChildCount() > 0) itemParams.leftMargin = dp(7);
        parent.addView(item, itemParams);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
