package local.mio.op13hyperosfix.monitor;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import local.mio.op13hyperosfix.R;

/** Opt-in, low-frequency daily power accounting. */
public final class DailyPowerMonitorService extends Service {
    public static final String ACTION_START =
            "local.mio.op13hyperosfix.monitor.DAILY_START";
    public static final String ACTION_STOP =
            "local.mio.op13hyperosfix.monitor.DAILY_STOP";
    public static final String ACTION_UPDATE =
            "local.mio.op13hyperosfix.monitor.DAILY_UPDATE";
    public static final String ACTION_SHOW_OVERLAY_PICKER =
            "local.mio.op13hyperosfix.monitor.DAILY_SHOW_OVERLAY_PICKER";
    public static final String SETTING = "op13_fix_daily_power_monitor";
    private static final String CHANNEL = "op13_daily_power";
    private static final int NOTIFICATION_ID = 2410;
    private static final long SAMPLE_MS = 10_000L;
    private static volatile boolean running;

    private RootSampler sampler;
    private ScheduledExecutorService executor;
    private ProbeSnapshot previous;
    private int failures;
    private OverlayModePickerWindow pickerWindow;

    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        sampler = new RootSampler(this);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                "日常耗电统计", NotificationManager.IMPORTANCE_MIN));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!enabled()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, notification("等待首次双电芯采样"));
        if (intent != null && ACTION_SHOW_OVERLAY_PICKER.equals(intent.getAction())) {
            showOverlayPicker();
            return START_STICKY;
        }
        if (!running) startSampling();
        return START_STICKY;
    }

    private void startSampling() {
        running = true;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "OP13DailyPower");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::sample, 0, SAMPLE_MS,
                TimeUnit.MILLISECONDS);
    }

    private void sample() {
        if (!running || !enabled()) {
            stopSelf();
            return;
        }
        try {
            List<String> lines = sampler.samplePower();
            ProbeSnapshot snapshot = ProbeSnapshot.parse(lines, previous);
            previous = snapshot;
            DailyPowerStore.record(this, snapshot);
            failures = 0;
            DailyPowerStore.Summary summary = DailyPowerStore.snapshot(this);
            getSystemService(NotificationManager.class).notify(
                    NOTIFICATION_ID, notification(String.format(Locale.CHINA,
                            summary.paused
                                    ? "外接供电/旁路中 · 已暂停耗电累计"
                                    : "当前 %.2f W · 平均 %.2f W · 累计 %.3f Wh",
                            Math.max(0, summary.currentPowerW), summary.averagePowerW,
                            summary.energyWh)));
            sendBroadcast(new Intent(ACTION_UPDATE).setPackage(getPackageName()));
        } catch (Throwable error) {
            failures++;
            if (failures >= 3) {
                getSystemService(NotificationManager.class).notify(
                        NOTIFICATION_ID, notification("采样暂时不可用，低频重试中"));
            }
        }
    }

    private boolean enabled() {
        try {
            return Settings.Global.getInt(getContentResolver(), SETTING, 0) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private android.app.Notification notification(String text) {
        Intent pickerIntent = new Intent(this, DailyPowerMonitorService.class)
                .setAction(ACTION_SHOW_OVERLAY_PICKER);
        PendingIntent content = PendingIntent.getService(this, 20, pickerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_probe)
                .setContentTitle("耗电统计正在运行")
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .build();
    }

    private void showOverlayPicker() {
        if (pickerWindow == null) pickerWindow = new OverlayModePickerWindow(this);
        boolean shown = pickerWindow.show(mode -> {
            Intent monitor = new Intent(this, ProbeMonitorService.class)
                    .setAction(ProbeMonitorService.isRunning()
                            ? ProbeMonitorService.ACTION_SET_OVERLAY_MODE
                            : ProbeMonitorService.ACTION_START)
                    .putExtra(ProbeMonitorService.EXTRA_OVERLAY_ENABLED, true)
                    .putExtra(ProbeMonitorService.EXTRA_OVERLAY_MODE, mode)
                    .putExtra(ProbeMonitorService.EXTRA_SAMPLE_INTERVAL_MS, 1000L);
            ContextCompat.startForegroundService(this, monitor);
        });
        if (!shown) {
            Toast.makeText(this, "请先允许一加13综合修复显示悬浮窗",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onDestroy() {
        running = false;
        if (pickerWindow != null) pickerWindow.dismiss();
        if (executor != null) executor.shutdownNow();
        if (sampler != null) sampler.close();
        DailyPowerStore.flush(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
