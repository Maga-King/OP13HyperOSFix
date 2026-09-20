package local.mio.op13hyperosfix.monitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** User-started foreground service. Sampling exists only while this service is running. */
public final class ProbeMonitorService extends Service {
    public static final String ACTION_START = "local.mio.op13hyperosfix.monitor.START";
    public static final String ACTION_STOP = "local.mio.op13hyperosfix.monitor.STOP";
    public static final String ACTION_UPDATE = "local.mio.op13hyperosfix.monitor.UPDATE";
    public static final String ACTION_SET_OVERLAY =
            "local.mio.op13hyperosfix.monitor.SET_OVERLAY";
    public static final String ACTION_SET_OVERLAY_MODE =
            "local.mio.op13hyperosfix.monitor.SET_OVERLAY_MODE";
    public static final String ACTION_SHOW_OVERLAY_PICKER =
            "local.mio.op13hyperosfix.monitor.SHOW_OVERLAY_PICKER";
    public static final String ACTION_ABORT_STRESS = "local.mio.op13hyperosfix.monitor.ABORT_STRESS";
    public static final String EXTRA_SAMPLE_INTERVAL_MS = "sample_interval_ms";
    public static final String EXTRA_OVERLAY_ENABLED = "overlay_enabled";
    public static final String EXTRA_OVERLAY_MODE = "overlay_mode";
    public static final int OVERLAY_STRIP = 0;
    public static final int OVERLAY_GAUGES = 1;
    public static final int OVERLAY_PROCESSES = 2;
    public static final int OVERLAY_THREADS = 3;
    private static final String CHANNEL = "mamba_probe_monitor";
    private static final int NOTIFICATION_ID = 2408;
    private static final long DEFAULT_SAMPLE_MS = 1000L;
    private static volatile String latestDetailed = "监测服务尚未启动";
    private static volatile String latestCompact = "等待首次 Root 采样";
    private static volatile float stressFps;
    private static volatile boolean running;

    private RootSampler sampler;
    private ScheduledExecutorService executor;
    private ProbeSnapshot previous;
    private long previousSystemTicks;
    private long previousSystemIdleTicks;
    private long previousAdapterTicks;
    private int previousAdapterPid = -1;
    private int sampleNumber;
    private int consecutiveFailures;
    private long sessionStartedElapsed;
    private long previousPowerElapsed;
    private double previousPowerW = -1;
    private double sessionEnergyWh;
    private long sessionPowerDurationMs;
    private long sessionSamples;
    private double sessionCpuSum;
    private double sessionGpuSum;
    private long sessionGpuSamples;
    private double sessionMinimumPowerW = Double.POSITIVE_INFINITY;
    private double sessionMaximumPowerW;
    private int sessionMaximumTempDecic = -1;
    private int sessionStartBatteryCapacity = -1;
    private long sampleIntervalMs = DEFAULT_SAMPLE_MS;
    private long nextRetryElapsed;
    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlayRoot;
    private LinearLayout overlayContent;
    private TextView overlayBody;
    private TextView collapseButton;
    private MetricGaugeView cpuGauge;
    private MetricGaugeView gpuGauge;
    private MetricGaugeView powerGauge;
    private boolean collapsed;
    private boolean overlayEnabled = true;
    private int overlayMode = OVERLAY_STRIP;
    private OverlayModePickerWindow pickerWindow;

    public static String latestDetailed() { return latestDetailed; }
    public static boolean isRunning() { return running; }
    public static void setStressFps(float fps) { stressFps = Math.max(0f, fps); }

    @Override public void onCreate() {
        super.onCreate();
        sampler = new RootSampler(this);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHOW_OVERLAY_PICKER.equals(action)) {
            startForeground(NOTIFICATION_ID, notification("性能监视正在运行"));
            showOverlayPicker();
            return START_NOT_STICKY;
        }
        if (ACTION_SET_OVERLAY.equals(action)) {
            overlayEnabled = intent != null && intent.getBooleanExtra(
                    EXTRA_OVERLAY_ENABLED, true);
            if (overlayEnabled) {
                startForeground(NOTIFICATION_ID, notification("性能监视正在运行"));
                if (!running) startSampling();
                ensureOverlay();
            } else {
                removeOverlay();
                if (!running) stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (ACTION_SET_OVERLAY_MODE.equals(action)) {
            startForeground(NOTIFICATION_ID, notification("性能监视正在运行"));
            if (!running) startSampling();
            overlayMode = intent == null ? OVERLAY_STRIP : Math.max(OVERLAY_STRIP,
                    Math.min(OVERLAY_THREADS, intent.getIntExtra(
                            EXTRA_OVERLAY_MODE, OVERLAY_STRIP)));
            overlayEnabled = true;
            removeOverlay();
            ensureOverlay();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            sampleIntervalMs = clampInterval(intent.getLongExtra(
                    EXTRA_SAMPLE_INTERVAL_MS, DEFAULT_SAMPLE_MS));
            overlayEnabled = intent.getBooleanExtra(EXTRA_OVERLAY_ENABLED, true);
            overlayMode = Math.max(OVERLAY_STRIP, Math.min(OVERLAY_THREADS,
                    intent.getIntExtra(EXTRA_OVERLAY_MODE, overlayMode)));
        }
        startForeground(NOTIFICATION_ID, notification("正在请求 Root 只读采样"));
        if (!running) startSampling();
        if (overlayEnabled) ensureOverlay();
        return START_NOT_STICKY;
    }

    private void startSampling() {
        running = true;
        sessionStartedElapsed = android.os.SystemClock.elapsedRealtime();
        previousPowerElapsed = 0;
        previousPowerW = -1;
        sessionEnergyWh = 0;
        sessionPowerDurationMs = 0;
        sessionSamples = 0;
        sessionCpuSum = 0;
        sessionGpuSum = 0;
        sessionGpuSamples = 0;
        sessionMinimumPowerW = Double.POSITIVE_INFINITY;
        sessionMaximumPowerW = 0;
        sessionMaximumTempDecic = -1;
        sessionStartBatteryCapacity = -1;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "MambaProbeSampler");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::takeSample, 0,
                sampleIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void takeSample() {
        if (!running || android.os.SystemClock.elapsedRealtime() < nextRetryElapsed)
            return;
        try {
            int fullSampleEvery = Math.max(1,
                    (int) Math.ceil(2_000.0 / sampleIntervalMs));
            boolean full = sampleNumber++ % fullSampleEvery == 0;
            List<String> lines = overlayMode == OVERLAY_PROCESSES
                    ? sampler.sampleMonitor() : sampler.sample(full);
            ProbeSnapshot snapshot = ProbeSnapshot.parse(lines, previous);
            calculatePerformanceAndPower(snapshot);
            calculateProcessCpu(snapshot);
            calculateAdapterCpu(snapshot);
            previous = snapshot;
            consecutiveFailures = 0;
            nextRetryElapsed = 0;
            ThreadPolicyVerifier.Result binding = ThreadPolicyVerifier.verify(snapshot);
            latestCompact = snapshot.compactReport(binding, stressFps);
            latestDetailed = snapshot.detailedReport(binding, stressFps);
            getMainExecutor().execute(() -> {
                renderOverlay(snapshot);
                sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
            });
            if (stressFps > 0 && snapshot.tempDecic >= 530) {
                sendBroadcast(new Intent(ACTION_ABORT_STRESS).setPackage(getPackageName()));
            }
        } catch (IOException error) {
            if (!running || Thread.currentThread().isInterrupted()) return;
            consecutiveFailures++;
            long delay = Math.min(5_000L,
                    500L << Math.min(3, consecutiveFailures - 1));
            nextRetryElapsed = android.os.SystemClock.elapsedRealtime() + delay;
            latestCompact = "Root 采样失败\n" + error.getMessage();
            latestDetailed = latestCompact + "\n\n将在 " + delay
                    + "ms 后自动重连；没有执行任何调度节点写入。";
            getMainExecutor().execute(() -> {
                if (overlayBody != null) overlayBody.setText(latestCompact);
                sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
            });
        } catch (Throwable error) {
            latestCompact = "解析采样失败\n" + error.getClass().getSimpleName();
            latestDetailed = latestCompact;
        }
    }

    private void calculateAdapterCpu(ProbeSnapshot snapshot) {
        if (snapshot.adapterPid > 0 && snapshot.adapterPid == previousAdapterPid
                && snapshot.systemTicks > previousSystemTicks
                && snapshot.adapterTicks >= previousAdapterTicks) {
            long systemDelta = snapshot.systemTicks - previousSystemTicks;
            long processDelta = snapshot.adapterTicks - previousAdapterTicks;
            snapshot.adapterCpuDevicePercent = clamp(processDelta * 100.0 / systemDelta, 0, 100);
            snapshot.adapterCpuCorePercent = clamp(snapshot.adapterCpuDevicePercent
                    * snapshot.onlineCores, 0, snapshot.onlineCores * 100.0);
        }
        previousSystemTicks = snapshot.systemTicks;
        previousAdapterTicks = snapshot.adapterTicks;
        previousAdapterPid = snapshot.adapterPid;
    }

    private void calculateProcessCpu(ProbeSnapshot snapshot) {
        if (previous == null || snapshot.processes.isEmpty()
                || previous.processes.isEmpty()) return;
        long total = snapshot.systemTicks - previous.systemTicks;
        if (total <= 0) return;
        Map<Integer, ProbeSnapshot.ProcessStat> old = new HashMap<>();
        for (ProbeSnapshot.ProcessStat process : previous.processes)
            old.put(process.pid, process);
        for (ProbeSnapshot.ProcessStat process : snapshot.processes) {
            ProbeSnapshot.ProcessStat before = old.get(process.pid);
            if (before == null || process.ticks < before.ticks) continue;
            process.cpuPercent = clamp((process.ticks - before.ticks) * 100.0
                    / total * snapshot.onlineCores, 0,
                    snapshot.onlineCores * 100.0);
        }
    }

    private void calculatePerformanceAndPower(ProbeSnapshot snapshot) {
        if (snapshot.systemTicks > previousSystemTicks
                && snapshot.systemIdleTicks >= previousSystemIdleTicks) {
            long total = snapshot.systemTicks - previousSystemTicks;
            long idle = snapshot.systemIdleTicks - previousSystemIdleTicks;
            if (total > 0) snapshot.systemCpuPercent = clamp(
                    (total - Math.min(total, idle)) * 100.0 / total, 0, 100);
        }
        previousSystemIdleTicks = snapshot.systemIdleTicks;

        long now = android.os.SystemClock.elapsedRealtime();
        if (snapshot.batteryPowerW >= 0 && snapshot.batteryPowerW <= 250) {
            if (previousPowerW >= 0 && previousPowerElapsed > 0) {
                long delta = now - previousPowerElapsed;
                long maximumGap = Math.max(5_000L, sampleIntervalMs * 3L);
                if (delta > 0 && delta <= maximumGap) {
                    sessionEnergyWh += (previousPowerW + snapshot.batteryPowerW)
                            * 0.5 * delta / 3_600_000.0;
                    sessionPowerDurationMs += delta;
                }
            }
            previousPowerW = snapshot.batteryPowerW;
            previousPowerElapsed = now;
        } else {
            previousPowerW = -1;
            previousPowerElapsed = 0;
        }
        snapshot.sessionElapsedMs = Math.max(0, now - sessionStartedElapsed);
        snapshot.sessionEnergyWh = sessionEnergyWh;
        snapshot.averagePowerW = sessionPowerDurationMs > 0
                ? sessionEnergyWh * 3_600_000.0 / sessionPowerDurationMs : 0;
        sessionSamples++;
        sessionCpuSum += snapshot.systemCpuPercent;
        if (snapshot.gpu.busy >= 0) {
            sessionGpuSum += snapshot.gpu.busy;
            sessionGpuSamples++;
        }
        if (snapshot.batteryPowerW >= 0 && snapshot.batteryPowerW <= 250) {
            sessionMinimumPowerW = Math.min(sessionMinimumPowerW,
                    snapshot.batteryPowerW);
            sessionMaximumPowerW = Math.max(sessionMaximumPowerW,
                    snapshot.batteryPowerW);
        }
        sessionMaximumTempDecic = Math.max(sessionMaximumTempDecic,
                snapshot.tempDecic);
        if (sessionStartBatteryCapacity < 0 && snapshot.batteryCapacity >= 0)
            sessionStartBatteryCapacity = snapshot.batteryCapacity;
        snapshot.sessionSamples = sessionSamples;
        snapshot.sessionAverageCpuPercent = sessionCpuSum / sessionSamples;
        snapshot.sessionAverageGpuPercent = sessionGpuSamples > 0
                ? sessionGpuSum / sessionGpuSamples : 0;
        snapshot.sessionMinimumPowerW = Double.isFinite(sessionMinimumPowerW)
                ? sessionMinimumPowerW : -1;
        snapshot.sessionMaximumPowerW = sessionMaximumPowerW;
        snapshot.sessionMaximumTempDecic = sessionMaximumTempDecic;
        snapshot.sessionBatteryDeltaPercent = sessionStartBatteryCapacity >= 0
                && snapshot.batteryCapacity >= 0
                ? snapshot.batteryCapacity - sessionStartBatteryCapacity : 0;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long clampInterval(long value) {
        if (value <= 250L) return 250L;
        if (value <= 500L) return 500L;
        if (value <= 1000L) return 1000L;
        return 2000L;
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "性能与功率实时监测", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("仅在用户主动开启悬浮监测时运行");
        manager.createNotificationChannel(channel);
    }

    private Notification notification(String text) {
        Intent pickerIntent = new Intent(this, ProbeMonitorService.class)
                .setAction(ACTION_SHOW_OVERLAY_PICKER);
        PendingIntent content = PendingIntent.getService(this, 21, pickerIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, ProbeMonitorService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(local.mio.op13hyperosfix.R.drawable.ic_stat_probe)
                .setContentTitle("性能与功率正在监测")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, "停止", stop)
                .build();
    }

    private void showOverlayPicker() {
        if (pickerWindow == null) pickerWindow = new OverlayModePickerWindow(this);
        pickerWindow.show(mode -> {
            overlayMode = mode;
            overlayEnabled = true;
            removeOverlay();
            ensureOverlay();
        });
    }

    private void ensureOverlay() {
        if (overlayRoot != null || !Settings.canDrawOverlays(this)) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.VERTICAL);
        overlayRoot.setPadding(dp(10), dp(8), dp(10), dp(10));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(242, 22, 23, 27));
        background.setCornerRadius(dp(8));
        background.setStroke(dp(1), Color.argb(48, 255, 255, 255));
        overlayRoot.setBackground(background);
        overlayRoot.setElevation(dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        View accent = new View(this);
        GradientDrawable accentBackground = new GradientDrawable();
        accentBackground.setColor(Color.rgb(72, 214, 174));
        accentBackground.setCornerRadius(dp(2));
        accent.setBackground(accentBackground);
        LinearLayout.LayoutParams accentParams = new LinearLayout.LayoutParams(dp(3), dp(15));
        accentParams.setMarginEnd(dp(7));
        header.addView(accent, accentParams);
        TextView title = overlayText(overlayTitle(), 12, true);
        title.setTextColor(Color.rgb(237, 239, 244));
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        collapseButton = overlayText("—", 18, true);
        collapseButton.setGravity(Gravity.CENTER);
        collapseButton.setOnClickListener(v -> toggleCollapsed());
        header.addView(collapseButton, new LinearLayout.LayoutParams(dp(30), dp(28)));
        TextView close = overlayText("×", 19, false);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> stopSelf());
        header.addView(close, new LinearLayout.LayoutParams(dp(30), dp(28)));
        overlayRoot.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        overlayContent = new LinearLayout(this);
        overlayContent.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        contentParams.topMargin = dp(5);
        overlayRoot.addView(overlayContent, contentParams);
        if (overlayMode == OVERLAY_GAUGES) {
            LinearLayout gauges = new LinearLayout(this);
            gauges.setOrientation(LinearLayout.HORIZONTAL);
            gauges.setGravity(Gravity.CENTER);
            cpuGauge = new MetricGaugeView(this, "CPU", Color.rgb(72, 214, 174));
            gpuGauge = new MetricGaugeView(this, "GPU", Color.rgb(126, 159, 255));
            powerGauge = new MetricGaugeView(this, "功耗", Color.rgb(255, 188, 79));
            gauges.addView(cpuGauge, new LinearLayout.LayoutParams(0, dp(78), 1));
            gauges.addView(gpuGauge, new LinearLayout.LayoutParams(0, dp(78), 1));
            gauges.addView(powerGauge, new LinearLayout.LayoutParams(0, dp(78), 1));
            overlayContent.addView(gauges, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(78)));
        } else {
            overlayBody = overlayText(latestCompact, overlayMode == OVERLAY_STRIP ? 9 : 10,
                    false);
            overlayBody.setTextColor(Color.rgb(226, 229, 236));
            overlayBody.setTypeface(Typeface.MONOSPACE);
            overlayBody.setLineSpacing(dp(1), 1.08f);
            overlayContent.addView(overlayBody, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        int width = overlayMode == OVERLAY_STRIP ? 238
                : overlayMode == OVERLAY_GAUGES ? 258
                : overlayMode == OVERLAY_PROCESSES ? 292 : 310;
        overlayParams = new WindowManager.LayoutParams(
                dp(width), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(12);
        overlayParams.y = dp(96);
        installDrag(header);
        windowManager.addView(overlayRoot, overlayParams);
        overlayRoot.setAlpha(0f);
        overlayRoot.setScaleX(0.94f);
        overlayRoot.setScaleY(0.94f);
        overlayRoot.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(180L).setInterpolator(new DecelerateInterpolator()).start();
    }

    private void renderOverlay(ProbeSnapshot snapshot) {
        if (overlayMode == OVERLAY_GAUGES && cpuGauge != null
                && gpuGauge != null && powerGauge != null) {
            cpuGauge.setMetric((float) snapshot.systemCpuPercent,
                    String.format(Locale.CHINA, "%.0f%%", snapshot.systemCpuPercent),
                    String.format(Locale.CHINA, "%.0f MHz", snapshot.policy0.current / 1000.0));
            gpuGauge.setMetric((float) Math.max(0, snapshot.gpu.busy),
                    String.format(Locale.CHINA, "%.0f%%", Math.max(0, snapshot.gpu.busy)),
                    String.format(Locale.CHINA, "%.0f MHz", snapshot.gpu.current / 1_000_000.0));
            float batteryProgress = Math.max(0, Math.min(100, snapshot.batteryCapacity));
            powerGauge.setMetric(batteryProgress,
                    String.format(Locale.CHINA, "%.2f W", Math.max(0, snapshot.batteryPowerW)),
                    String.format(Locale.CHINA, "%.1f°C", Math.max(0, snapshot.tempDecic) / 10.0));
        } else if (overlayBody != null) {
            overlayBody.setText(overlayReport(snapshot));
        }
    }

    private String overlayTitle() {
        return switch (overlayMode) {
            case OVERLAY_GAUGES -> "硬件总览";
            case OVERLAY_PROCESSES -> "进程负载";
            case OVERLAY_THREADS -> "当前应用线程";
            default -> "性能细条";
        };
    }

    private String overlayReport(ProbeSnapshot snapshot) {
        return switch (overlayMode) {
            case OVERLAY_GAUGES -> String.format(Locale.CHINA,
                    "CPU  %.0f%%        GPU  %.0f%%        电量  %d%%\n"
                            + "%.0f MHz       %.0f MHz       %.2f W · %.1f°C",
                    snapshot.systemCpuPercent, Math.max(0, snapshot.gpu.busy),
                    Math.max(0, snapshot.batteryCapacity),
                    snapshot.policy0.current / 1000.0,
                    snapshot.gpu.current / 1_000_000.0,
                    Math.max(0, snapshot.batteryPowerW),
                    Math.max(0, snapshot.tempDecic) / 10.0);
            case OVERLAY_PROCESSES -> String.format(Locale.CHINA,
                    "%s\nRAM %.1f / %.1f GB",
                    processReport(snapshot),
                    Math.max(0, snapshot.memoryTotalKb - snapshot.memoryAvailableKb)
                            / 1048576.0, snapshot.memoryTotalKb / 1048576.0);
            case OVERLAY_THREADS -> {
                StringBuilder out = new StringBuilder();
                out.append(snapshot.mainPackage == null || snapshot.mainPackage.isBlank()
                        ? "等待前台应用" : snapshot.mainPackage).append("\n");
                int count = 0;
                for (ProbeSnapshot.TaskAffinity task : snapshot.tasks) {
                    if (!task.packageName.equals(snapshot.mainPackage)) continue;
                    if (count++ >= 12) break;
                    out.append(task.tid).append("  ").append(task.comm)
                            .append("  CPU ").append(task.allowedList).append('\n');
                }
                if (count == 0) out.append("暂无可显示线程；等待完整采样");
                yield out.toString().trim();
            }
            default -> String.format(Locale.CHINA,
                    "CPU %.0f%% %.0f  ·  GPU %.0f%% %.0f  ·  %.2fW",
                    snapshot.systemCpuPercent, snapshot.policy0.current / 1000.0,
                    Math.max(0, snapshot.gpu.busy),
                    snapshot.gpu.current / 1_000_000.0,
                    Math.max(0, snapshot.batteryPowerW));
        };
    }

    private String processReport(ProbeSnapshot snapshot) {
        ArrayList<ProbeSnapshot.ProcessStat> processes =
                new ArrayList<>(snapshot.processes);
        processes.sort(Comparator.comparingDouble(
                (ProbeSnapshot.ProcessStat item) -> item.cpuPercent).reversed());
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (ProbeSnapshot.ProcessStat process : processes) {
            if (process.cpuPercent <= 0.01 && count >= 4) continue;
            String name = process.processName;
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (name.length() > 24) name = name.substring(name.length() - 24);
            out.append(String.format(Locale.CHINA, "%-24s %5.1f%%\n",
                    name, process.cpuPercent));
            if (++count >= 8) break;
        }
        return count == 0 ? "等待第二次进程采样" : out.toString().trim();
    }

    private void removeOverlay() {
        if (windowManager != null && overlayRoot != null) {
            try { windowManager.removeView(overlayRoot); } catch (Exception ignored) {}
        }
        overlayRoot = null;
        overlayContent = null;
        overlayBody = null;
        overlayParams = null;
        collapseButton = null;
        cpuGauge = null;
        gpuGauge = null;
        powerGauge = null;
        collapsed = false;
    }

    private TextView overlayText(String text, int sp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.WHITE);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void installDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            int originX;
            int originY;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        downX = event.getRawX();
                        downY = event.getRawY();
                        originX = overlayParams.x;
                        originY = overlayParams.y;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        int displayWidth = getResources().getDisplayMetrics().widthPixels;
                        int displayHeight = getResources().getDisplayMetrics().heightPixels;
                        int maxX = Math.max(0, displayWidth - overlayRoot.getWidth());
                        int maxY = Math.max(0, displayHeight - overlayRoot.getHeight());
                        overlayParams.x = Math.max(0, Math.min(maxX,
                                originX + Math.round(event.getRawX() - downX)));
                        overlayParams.y = Math.max(0, Math.min(maxY,
                                originY + Math.round(event.getRawY() - downY)));
                        if (windowManager != null && overlayRoot != null)
                            windowManager.updateViewLayout(overlayRoot, overlayParams);
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> {
                        if (Math.abs(event.getRawX() - downX) < dp(4)
                                && Math.abs(event.getRawY() - downY) < dp(4))
                            toggleCollapsed();
                        return true;
                    }
                }
                return false;
            }
        });
    }

    private void toggleCollapsed() {
        collapsed = !collapsed;
        if (overlayContent != null) {
            overlayContent.animate().cancel();
            if (collapsed) {
                overlayContent.animate().alpha(0f).translationY(-dp(4))
                        .setDuration(120L).withEndAction(() -> {
                            if (collapsed && overlayContent != null) {
                                overlayContent.setVisibility(View.GONE);
                                if (windowManager != null && overlayRoot != null)
                                    windowManager.updateViewLayout(overlayRoot, overlayParams);
                            }
                        }).start();
            } else {
                overlayContent.setVisibility(View.VISIBLE);
                overlayContent.setAlpha(0f);
                overlayContent.setTranslationY(-dp(4));
                overlayContent.animate().alpha(1f).translationY(0f)
                        .setDuration(150L).setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        if (collapseButton != null) collapseButton.setText(collapsed ? "+" : "—");
        if (windowManager != null && overlayRoot != null)
            windowManager.updateViewLayout(overlayRoot, overlayParams);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
        running = false;
        stressFps = 0;
        if (pickerWindow != null) pickerWindow.dismiss();
        if (executor != null) executor.shutdownNow();
        if (sampler != null) sampler.close();
        removeOverlay();
        stopForeground(STOP_FOREGROUND_REMOVE);
        sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
