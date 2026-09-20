package local.mio.op13hyperosfix.monitor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.ViewHeadline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import local.mio.op13hyperosfix.Op13FixTheme

class OverlayPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        setContent {
            Op13FixTheme {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("悬浮监视", style = MaterialTheme.typography.headlineSmall)
                                Text("只读显示，不修改性能策略",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Rounded.Close, "关闭")
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PickerItem("细条", Icons.Rounded.ViewHeadline,
                                Modifier.weight(1f)) { launch(ProbeMonitorService.OVERLAY_STRIP) }
                            PickerItem("总览", Icons.Rounded.DataUsage,
                                Modifier.weight(1f)) { launch(ProbeMonitorService.OVERLAY_GAUGES) }
                            PickerItem("进程", Icons.Rounded.ListAlt,
                                Modifier.weight(1f)) { launch(ProbeMonitorService.OVERLAY_PROCESSES) }
                            PickerItem("线程", Icons.Rounded.AccountTree,
                                Modifier.weight(1f)) { launch(ProbeMonitorService.OVERLAY_THREADS) }
                        }
                    }
                }
            }
        }
    }

    private fun launch(mode: Int) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先允许显示悬浮窗", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            finish()
            return
        }
        val action = if (ProbeMonitorService.isRunning())
            ProbeMonitorService.ACTION_SET_OVERLAY_MODE else ProbeMonitorService.ACTION_START
        ContextCompat.startForegroundService(this,
            Intent(this, ProbeMonitorService::class.java)
                .setAction(action)
                .putExtra(ProbeMonitorService.EXTRA_OVERLAY_ENABLED, true)
                .putExtra(ProbeMonitorService.EXTRA_OVERLAY_MODE, mode)
                .putExtra(ProbeMonitorService.EXTRA_SAMPLE_INTERVAL_MS, 1000L))
        finish()
    }
}

@Composable
private fun PickerItem(
    label: String,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.aspectRatio(0.82f).clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
