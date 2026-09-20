package local.mio.op13hyperosfix.monitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowCompat
import local.mio.op13hyperosfix.Op13FixTheme
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PerformanceMonitorActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var running by mutableStateOf(false)
    private var report by mutableStateOf("监视器未启动")
    private var intervalMs by mutableLongStateOf(1000L)
    private var overlayEnabled by mutableStateOf(true)
    private var dailyEnabled by mutableStateOf(false)
    private var dailySummary by mutableStateOf(DailyPowerStore.Summary())

    private val updates = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        intervalMs = getPreferences(MODE_PRIVATE).getLong("sample_ms", 1000L)
        overlayEnabled = getPreferences(MODE_PRIVATE).getBoolean("overlay_enabled", true)
        dailyEnabled = readDailyEnabled()
        val filter = IntentFilter(ProbeMonitorService.ACTION_UPDATE).apply {
            addAction(DailyPowerMonitorService.ACTION_UPDATE)
        }
        ContextCompat.registerReceiver(this, updates, filter,
            ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            Op13FixTheme {
                MonitorScreen(
                    running, report, intervalMs, overlayEnabled, dailyEnabled,
                    dailySummary, { finish() }, ::resetDaily, ::updateDailyTracking,
                    ::setInterval, ::updateOverlayPreference,
                    { if (running) stopMonitor() else startMonitor() },
                )
            }
        }
        refreshState()
    }

    override fun onResume() { super.onResume(); refreshState() }

    private fun setInterval(value: Long) {
        intervalMs = value
        getPreferences(MODE_PRIVATE).edit().putLong("sample_ms", value).apply()
    }

    private fun startMonitor() {
        if (overlayEnabled && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        requestNotificationPermission()
        ContextCompat.startForegroundService(this,
            Intent(this, ProbeMonitorService::class.java)
                .setAction(ProbeMonitorService.ACTION_START)
                .putExtra(ProbeMonitorService.EXTRA_SAMPLE_INTERVAL_MS, intervalMs)
                .putExtra(ProbeMonitorService.EXTRA_OVERLAY_ENABLED, overlayEnabled))
        running = true
    }

    private fun updateOverlayPreference(value: Boolean) {
        if (value && !Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        overlayEnabled = value
        getPreferences(MODE_PRIVATE).edit().putBoolean("overlay_enabled", value).apply()
        if (running) startService(Intent(this, ProbeMonitorService::class.java)
            .setAction(ProbeMonitorService.ACTION_SET_OVERLAY)
            .putExtra(ProbeMonitorService.EXTRA_OVERLAY_ENABLED, value))
    }

    private fun stopMonitor() {
        startService(Intent(this, ProbeMonitorService::class.java)
            .setAction(ProbeMonitorService.ACTION_STOP))
        running = false
        report = "实时监视已停止；没有残留采样任务"
    }

    private fun updateDailyTracking(value: Boolean) {
        requestNotificationPermission()
        executor.execute {
            val command = "settings put global ${DailyPowerMonitorService.SETTING} " +
                if (value) "1" else "0"
            val success = runRoot(command)
            runOnUiThread {
                if (!success) {
                    Toast.makeText(this, "ROOT 配置写入失败", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                dailyEnabled = value
                if (value) ContextCompat.startForegroundService(this,
                    Intent(this, DailyPowerMonitorService::class.java)
                        .setAction(DailyPowerMonitorService.ACTION_START))
                else startService(Intent(this, DailyPowerMonitorService::class.java)
                    .setAction(DailyPowerMonitorService.ACTION_STOP))
                refreshState()
            }
        }
    }

    private fun resetDaily() {
        executor.execute {
            DailyPowerStore.reset(this)
            runOnUiThread {
                refreshState()
                Toast.makeText(this, "耗电统计已清空", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun readDailyEnabled() = try {
        Settings.Global.getInt(contentResolver, DailyPowerMonitorService.SETTING, 0) != 0
    } catch (_: Throwable) { false }

    private fun runRoot(command: String) = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (_: Throwable) { false }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 24)
    }

    private fun refreshState() {
        running = ProbeMonitorService.isRunning()
        report = ProbeMonitorService.latestDetailed()
        dailyEnabled = readDailyEnabled()
        dailySummary = DailyPowerStore.snapshot(this)
    }

    override fun onDestroy() {
        unregisterReceiver(updates)
        executor.shutdownNow()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MonitorScreen(
    running: Boolean, report: String, intervalMs: Long, overlayEnabled: Boolean,
    dailyEnabled: Boolean, daily: DailyPowerStore.Summary, onBack: () -> Unit,
    onResetDaily: () -> Unit, onDailyChange: (Boolean) -> Unit,
    onIntervalChange: (Long) -> Unit, onOverlayChange: (Boolean) -> Unit,
    onMonitorClick: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(
            title = { Text("性能与耗电") },
            navigationIcon = { IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
            actions = { IconButton(onClick = onResetDaily) {
                Icon(Icons.Rounded.DeleteOutline, "清空耗电统计") } },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background),
        ) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DailyPowerHeader(dailyEnabled, onDailyChange) }
            if (dailyEnabled || daily.durationMs > 0) {
                item { PowerHistoryCard(daily) }
                item { PowerTotalsCard(daily) }
                if (daily.apps.isNotEmpty()) {
                    item { Text("使用场景", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) }
                    items(daily.apps.take(12), key = { it.packageName }) { PowerAppRow(it) }
                }
            }
            item { RealtimeControls(running, intervalMs, overlayEnabled,
                onIntervalChange, onOverlayChange, onMonitorClick) }
            item { Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) { SelectionContainer { Text(report, Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace) } } }
        }
    }
}

@Composable
private fun DailyPowerHeader(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MonitorHeart, null)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("日常耗电统计", style = MaterialTheme.typography.titleMedium)
                Text(if (enabled) "双电芯低频采样正在运行"
                    else "关闭时无采样进程、定时器或磁盘写入",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun PowerHistoryCard(summary: DailyPowerStore.Summary) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("使用过程", style = MaterialTheme.typography.titleMedium)
                Text("${summary.capacity.coerceAtLeast(0)}%",
                    style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(14.dp))
            if (summary.points.size < 2) {
                Surface(Modifier.fillMaxWidth().height(180.dp), RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("等待形成电量曲线")
                    }
                }
            } else Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                }
                val points = summary.points
                val first = points.first().wall
                val span = (points.last().wall - first).coerceAtLeast(1)
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = (point.wall - first).toFloat() / span * size.width
                    val y = size.height * (1f - point.capacity.coerceIn(0, 100) / 100f)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, line, style = Stroke(4f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(String.format(Locale.CHINA, "%.4f Wh", summary.energyWh))
                Text(String.format(Locale.CHINA, "%.1f°C", summary.temperatureDecic / 10.0))
                Text(String.format(Locale.CHINA, "%.3fV × 2",
                    summary.cellVoltageUv / 1_000_000.0))
                Text(if (summary.status.equals("Charging", true)) "充电中" else "使用中")
            }
        }
    }
}

@Composable
private fun PowerTotalsCard(summary: DailyPowerStore.Summary) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            MetricValue(String.format(Locale.CHINA, "%.2fW", summary.averagePowerW), "平均功耗")
            MetricValue(duration(summary.durationMs), "已统计")
            MetricValue(if (summary.remainingHours > 0)
                duration((summary.remainingHours * 3_600_000).toLong()) else "--", "理论续航")
        }
    }
}

@Composable
private fun MetricValue(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PowerAppRow(app: DailyPowerStore.AppRecord) {
    val context = LocalContext.current
    val label = try {
        val info = context.packageManager.getApplicationInfo(app.packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    } catch (_: Throwable) { app.packageName }
    val icon = try { context.packageManager.getApplicationIcon(app.packageName)
        .toBitmap(96, 96).asImageBitmap() } catch (_: Throwable) { null }
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, null, Modifier.size(46.dp),
                contentScale = ContentScale.Fit)
            else Surface(Modifier.size(46.dp), RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer) {}
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium)
                Text(String.format(Locale.CHINA, "AVG %.2fW, %.0f°C   MAX %.0f°C",
                    app.averagePowerW(), app.averageTemperatureC(),
                    app.maximumTemperatureDecic / 10.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(duration(app.durationMs), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RealtimeControls(
    running: Boolean, intervalMs: Long, overlayEnabled: Boolean,
    onIntervalChange: (Long) -> Unit, onOverlayChange: (Boolean) -> Unit,
    onMonitorClick: () -> Unit,
) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp)) {
            Text("实时性能监视", style = MaterialTheme.typography.titleMedium)
            Text("独立于日常耗电统计，停止后销毁完整硬件采样器",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2000L to "省电", 1000L to "均衡", 500L to "实时").forEach { (v, label) ->
                    if (intervalMs == v) Button(modifier = Modifier.weight(1f), enabled = !running,
                        onClick = { onIntervalChange(v) }) { Text(label) }
                    else OutlinedButton(modifier = Modifier.weight(1f), enabled = !running,
                        onClick = { onIntervalChange(v) }) { Text(label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("显示悬浮窗", style = MaterialTheme.typography.titleSmall)
                    Text("关闭后只保留页面、通知和会话统计",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(overlayEnabled, onCheckedChange = onOverlayChange)
            }
            Spacer(Modifier.height(12.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = onMonitorClick) {
                Text(if (running) "停止实时监视" else "开始实时监视")
            }
        }
    }
}

private fun duration(millis: Long): String {
    val seconds = (millis / 1000).coerceAtLeast(0)
    val hours = seconds / 3600
    val minutes = (seconds / 60) % 60
    val remain = seconds % 60
    return if (hours > 0) "${hours}h${minutes}m" else "${minutes}m${remain}s"
}
