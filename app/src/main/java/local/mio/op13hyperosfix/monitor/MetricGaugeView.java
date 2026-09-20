package local.mio.op13hyperosfix.monitor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Compact, allocation-free gauge used by the optional real-time overlay. */
final class MetricGaugeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final String label;
    private final int accent;
    private float progress;
    private String value = "--";
    private String footer = "等待采样";

    MetricGaugeView(Context context, String label, int accent) {
        super(context);
        this.label = label;
        this.accent = accent;
    }

    void setMetric(float progress, String value, String footer) {
        this.progress = Math.max(0f, Math.min(100f, progress));
        this.value = value;
        this.footer = footer;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float centerX = getWidth() / 2f;
        float centerY = 31f * density;
        float radius = 22f * density;
        float stroke = 4f * density;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        arc.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
        paint.setColor(Color.argb(42, 255, 255, 255));
        canvas.drawArc(arc, 135f, 270f, false, paint);
        paint.setColor(accent);
        canvas.drawArc(arc, 135f, 270f * progress / 100f, false, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(10f * density);
        paint.setColor(Color.rgb(239, 241, 246));
        canvas.drawText(value, centerX, centerY + 3.5f * density, paint);

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setTextSize(8f * density);
        paint.setColor(Color.rgb(166, 170, 181));
        canvas.drawText(label + "  " + footer, centerX, 72f * density, paint);
    }
}
