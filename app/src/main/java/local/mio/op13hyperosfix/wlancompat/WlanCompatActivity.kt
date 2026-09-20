package local.mio.op13hyperosfix.wlancompat

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import local.mio.op13hyperosfix.FeatureTone
import local.mio.op13hyperosfix.InsetDivider
import local.mio.op13hyperosfix.Op13FixTheme
import local.mio.op13hyperosfix.SectionHeader
import local.mio.op13hyperosfix.SettingsGroup
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class WlanCompatActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var running by mutableStateOf(false)
    private var result by mutableStateOf(CacheResult.idle())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Op13FixTheme {
                WlanCompatScreen(
                    running = running,
                    result = result,
                    onBack = ::finish,
                    onRefresh = ::refreshMac,
                )
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshMac() {
        if (running) return
        running = true
        result = CacheResult("正在读取 WLAN 地址", "必要时会请求 KernelSU 授权", null)
        executor.execute {
            val source = try {
                val request = Bundle().apply { putBoolean("refresh", true) }
                contentResolver.call(
                    Uri.parse("content://${MacProvider.AUTHORITY}"),
                    MacProvider.METHOD_GET_MAC,
                    "wlan0",
                    request,
                )?.getString("source", "failed") ?: "failed"
            } catch (_: Throwable) {
                "failed"
            }
            runOnUiThread {
                result = when (source) {
                    "direct" -> CacheResult("真实地址已缓存", "来源：内核网络接口", true)
                    "root" -> CacheResult("真实地址已缓存", "来源：ROOT 读取 wlan0", true)
                    "cache" -> CacheResult("正在使用真实地址缓存", "本次读取沿用已有缓存", true)
                    "stable-fallback" -> CacheResult(
                        "未取得真实地址",
                        "当前只能使用稳定兜底值，请确认 ROOT 授权后重试",
                        false,
                    )
                    else -> CacheResult("读取失败", "Provider 未返回有效结果", false)
                }
                running = false
            }
        }
    }
}

private data class CacheResult(
    val title: String,
    val detail: String,
    val okay: Boolean?,
) {
    companion object {
        fun idle() = CacheResult("尚未初始化", "按下按钮后读取并缓存，不会持续轮询", null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WlanCompatScreen(
    running: Boolean,
    result: CacheResult,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("换机与 WLAN 兼容", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "小米换机 · 云备份",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        ) {
                            Icon(
                                Icons.Rounded.Wifi,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(9.dp).size(22.dp),
                            )
                        }
                        Spacer(Modifier.size(13.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("按需修复", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "仅在目标应用启动或手动初始化时运行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            item(key = "features-header") {
                SectionHeader("自动兼容", tone = FeatureTone.Green)
            }
            item(key = "features") {
                SettingsGroup {
                    CompatInfoRow(
                        icon = Icons.Rounded.SwapHoriz,
                        title = "小米换机数据目录",
                        summary = "启动前切回 CE 用户数据目录，避免 DE 目录冲突",
                    )
                    InsetDivider()
                    CompatInfoRow(
                        icon = Icons.Rounded.Cloud,
                        title = "云备份 WLAN 地址",
                        summary = "原始读取失败时提供已验证的真实 wlan0 地址",
                    )
                }
            }

            item(key = "cache-header") {
                SectionHeader("地址缓存", tone = FeatureTone.Amber)
            }
            item(key = "cache") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = when (result.okay) {
                        true -> MaterialTheme.colorScheme.secondaryContainer
                        false -> MaterialTheme.colorScheme.errorContainer
                        null -> MaterialTheme.colorScheme.surfaceContainer
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                result.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(
                            onClick = onRefresh,
                            enabled = !running,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (running) "正在读取" else "读取并缓存真实地址")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatInfoRow(icon: ImageVector, title: String, summary: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
