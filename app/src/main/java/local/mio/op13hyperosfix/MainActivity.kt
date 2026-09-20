package local.mio.op13hyperosfix

import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Payment
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var xposedActive by mutableStateOf(false)
    private var rootResult by mutableStateOf<RootInstaller.StartResult?>(null)
    private var runtimeText by mutableStateOf("正在检测 ROOT 和修复组件状态…")
    private var working by mutableStateOf(false)
    private var doubleTapEnabled by mutableStateOf(true)
    private var touchSamplingRate by mutableStateOf(120)
    private var moreNotificationSettings by mutableStateOf(true)
    private var activationToastShown = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        doubleTapEnabled = ConfigWriter.read(this, ModuleConfig.DOUBLE_TAP_WAKE, true)
        touchSamplingRate = RootInstaller.sanitizeTouchSamplingRate(
            ConfigWriter.readInt(this, ModuleConfig.TOUCH_SAMPLING_RATE, 120),
        )
        moreNotificationSettings = ConfigWriter.read(
            this,
            ModuleConfig.MORE_NOTIFICATION_SETTINGS,
            true,
        )
        updateActivationStatus()
        setContent {
            Op13FixTheme {
                MainScreen(
                    xposedActive = xposedActive,
                    rootResult = rootResult,
                    runtimeText = runtimeText,
                    working = working,
                    doubleTapEnabled = doubleTapEnabled,
                    touchSamplingRate = touchSamplingRate,
                    moreNotificationSettings = moreNotificationSettings,
                    version = versionLabel(),
                    onDoubleTapChange = ::handleDoubleTapChange,
                    onTouchSamplingRateChange = ::handleTouchSamplingRateChange,
                    onMoreNotificationChange = ::handleMoreNotificationChange,
                    onGameOptimization = {
                        startActivity(Intent(this, GameOptimizationActivity::class.java))
                    },
                    onDeviceParams = {
                        startActivity(Intent(
                            this,
                            local.mio.op13hyperosfix.deviceparams.DeviceParamsActivity::class.java,
                        ))
                    },
                    onCorePatch = {
                        startActivity(Intent(this, CorePatchActivity::class.java))
                    },
                    onLtpoTest = {
                        startActivity(Intent(this, LtpoTestActivity::class.java))
                    },
                    onDiagnostics = {
                        startActivity(Intent(this, DiagnosticsActivity::class.java))
                    },
                    onPerformanceMonitor = {
                        startActivity(Intent(
                            this,
                            local.mio.op13hyperosfix.monitor.PerformanceMonitorActivity::class.java,
                        ))
                    },
                    onStartRoot = { runStart(forceDeploy = true, toastIfMissingRoot = true) },
                    onRefresh = ::runStatus,
                )
            }
        }
        runStart(
            forceDeploy = false,
            toastIfMissingRoot = SystemClock.elapsedRealtime() >= ROOT_BOOT_GRACE_MS,
        )
    }

    override fun onResume() {
        super.onResume()
        updateActivationStatus()
        sendBroadcast(Intent(GameOptimizationHooks.ACTION_CONFIG_CHANGED))
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun updateActivationStatus() {
        xposedActive = ModuleConfig.isXposedActive(this)
        if (!xposedActive && !activationToastShown) {
            activationToastShown = true
            Toast.makeText(
                this,
                "LSPosed 模块未激活，或本次开机尚未加载",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun handleDoubleTapChange(enabled: Boolean) {
        if (working) return
        working = true
        executor.execute {
            val write = ConfigWriter.write(ModuleConfig.DOUBLE_TAP_WAKE, enabled)
            val result = if (write.success) {
                RootInstaller.ensureStarted(this, false)
            } else {
                null
            }
            runOnUiThread {
                working = false
                if (!write.success) {
                    Toast.makeText(this, write.message, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                doubleTapEnabled = enabled
                if (result != null) {
                    rootResult = result
                    runtimeText = result.toDisplayText()
                }
                Toast.makeText(
                    this,
                    if (enabled) "双击亮屏已开启" else "双击亮屏已关闭",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun handleMoreNotificationChange(enabled: Boolean) {
        if (working) return
        working = true
        executor.execute {
            val result = ConfigWriter.write(ModuleConfig.MORE_NOTIFICATION_SETTINGS, enabled)
            runOnUiThread {
                working = false
                if (!result.success) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                moreNotificationSettings = enabled
                Toast.makeText(this, "已保存，重新打开通知设置页后生效", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun handleTouchSamplingRateChange(rate: Int) {
        if (working) return
        val selected = RootInstaller.sanitizeTouchSamplingRate(rate)
        working = true
        executor.execute {
            val write = ConfigWriter.writeInt(ModuleConfig.TOUCH_SAMPLING_RATE, selected)
            val apply = if (write.success) {
                val powerManager = getSystemService(PowerManager::class.java)
                RootInstaller.applyTouchSamplingRate(
                    this,
                    selected,
                    powerManager?.isInteractive != false,
                )
            } else {
                null
            }
            runOnUiThread {
                working = false
                if (!write.success) {
                    Toast.makeText(this, write.message, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                touchSamplingRate = selected
                if (apply?.success == false) {
                    Toast.makeText(
                        this,
                        "已保存；${apply.message}，将在下次自举时重试",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "日常触控采样率已设为 ${selected} Hz",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun runStart(forceDeploy: Boolean, toastIfMissingRoot: Boolean) {
        if (working) return
        working = true
        runtimeText = "正在执行 ROOT 自举与 SN 检查…"
        executor.execute {
            var result = RootInstaller.ensureStarted(this, forceDeploy)
            if (!forceDeploy && !result.rootGranted
                && SystemClock.elapsedRealtime() < ROOT_BOOT_GRACE_MS
            ) {
                Thread.sleep(ROOT_UI_RETRY_DELAY_MS)
                result = RootInstaller.ensureStarted(this, false)
            }
            runOnUiThread {
                rootResult = result
                runtimeText = result.toDisplayText()
                doubleTapEnabled = result.doubleTapEnabled
                working = false
                if (toastIfMissingRoot && result.shouldShowRootToast()) {
                    Toast.makeText(this, R.string.root_missing_toast, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runStatus() {
        if (working) return
        working = true
        runtimeText = "正在读取 ROOT、SN 和运行状态…"
        executor.execute {
            val result = RootInstaller.status(this)
            runOnUiThread {
                rootResult = result
                runtimeText = result.toDisplayText()
                doubleTapEnabled = result.doubleTapEnabled
                working = false
            }
        }
    }

    private fun versionLabel(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    companion object {
        const val ROOT_BOOT_GRACE_MS = 60_000L
        const val ROOT_UI_RETRY_DELAY_MS = 4_000L
        val TOUCH_SAMPLING_RATES = listOf(70, 120, 180, 240, 360)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    xposedActive: Boolean,
    rootResult: RootInstaller.StartResult?,
    runtimeText: String,
    working: Boolean,
    doubleTapEnabled: Boolean,
    touchSamplingRate: Int,
    moreNotificationSettings: Boolean,
    version: String,
    onDoubleTapChange: (Boolean) -> Unit,
    onTouchSamplingRateChange: (Int) -> Unit,
    onMoreNotificationChange: (Boolean) -> Unit,
    onGameOptimization: () -> Unit,
    onDeviceParams: () -> Unit,
    onCorePatch: () -> Unit,
    onLtpoTest: () -> Unit,
    onDiagnostics: () -> Unit,
    onPerformanceMonitor: () -> Unit,
    onStartRoot: () -> Unit,
    onRefresh: () -> Unit,
) {
    val features = remember {
        listOf(
            FeatureItem("指纹动画与速启", "补齐触摸上下行与长按速启状态", Icons.Rounded.Fingerprint),
            FeatureItem("移动、口袋与抬起", "适配一加唤醒传感器和 NonUI", Icons.Rounded.Sensors, FeatureTone.Green),
            FeatureItem("步数传感器", "修正传感器选择和唤醒属性", Icons.Rounded.DirectionsWalk, FeatureTone.Green),
            FeatureItem("三段式静音键", "响铃、振动、静音与超级岛提示", Icons.Rounded.VolumeUp, FeatureTone.Amber),
            FeatureItem("LHDC V5", "运行时补齐蓝牙编码器兼容", Icons.Rounded.Bluetooth),
            FeatureItem("通知管理", "0–6 图标、重要性和全部设置项", Icons.Rounded.Notifications),
            FeatureItem("AOD 防误触", "唤醒前读取实时接近状态", Icons.Rounded.TouchApp, FeatureTone.Green),
            FeatureItem("LTPO 恢复", "按正确时序加载 6.6 内核模块", Icons.Rounded.ScreenLockPortrait, FeatureTone.Amber),
            FeatureItem("充电与电池", "原生快充、小数动画、功率和电池页", Icons.Rounded.Bolt, FeatureTone.Amber),
            FeatureItem("游戏触控与旁路", "按应用切换采样率和有线旁路供电", Icons.Rounded.SportsEsports, FeatureTone.Green),
            FeatureItem("小米 SN 兼容", "同步识别属性，不触碰基带与 NV", Icons.Rounded.Badge, FeatureTone.Red),
            FeatureItem("澎湃震动增强", "完整 RTP、原生强度与系统触感", Icons.Rounded.VolumeUp, FeatureTone.Red),
            FeatureItem("支付宝指纹", "转接一加 IFAA 指纹支付 HAL", Icons.Rounded.Payment),
            FeatureItem("ColorOS 钱包与 eID", "补齐 eSE 路由、门禁卡、锁屏卡包和安全服务", Icons.Rounded.Payment, FeatureTone.Green),
            FeatureItem("USB/MTP 稳定性", "阻止下载管理器在开机窗口误杀媒体进程", Icons.Rounded.Usb, FeatureTone.Green),
            FeatureItem("我的设备美化", "定制深色、设备参数与页面音乐", Icons.Rounded.Palette, FeatureTone.Amber),
            FeatureItem(
                "云备份与换机修复",
                "调用时读取 WLAN 地址，换机自动使用 CE 数据目录",
                Icons.Rounded.Wifi,
                FeatureTone.Green,
            ),
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.app_icon_24),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.size(11.dp))
                        Column {
                            Text("一加 13 综合修复", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "OS4 · Android 17 · ${version.substringBefore('-')}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !working) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新状态")
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "overview") {
                OverviewCard(xposedActive, rootResult)
            }
            if (rootResult?.rootGranted == false) {
                item(key = "root-warning") {
                    RootWarningCard(working = working, onStartRoot = onStartRoot)
                }
            }

            item(key = "control-header") { SectionHeader("可控制项") }
            item(key = "controls") {
                SettingsGroup {
                    SettingSwitchRow(
                        title = "低功耗双击亮屏",
                        summary = "单击进入 AOD，双击直接亮屏",
                        icon = Icons.Rounded.TouchApp,
                        checked = doubleTapEnabled,
                        enabled = !working,
                        grouped = true,
                        onCheckedChange = onDoubleTapChange,
                    )
                    InsetDivider()
                    SettingSliderRow(
                        title = "日常触控采样率",
                        summary = "高于 120 Hz 时亮屏使用所选档位，息屏自动降至 70 Hz",
                        icon = Icons.Rounded.TouchApp,
                        value = touchSamplingRate,
                        values = MainActivity.TOUCH_SAMPLING_RATES,
                        enabled = !working,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onValueCommitted = onTouchSamplingRateChange,
                    )
                    InsetDivider()
                    NavigationRow(
                        title = "游戏触控与旁路供电",
                        summary = "自选游戏、独立采样率，仅游戏内自动旁路",
                        icon = Icons.Rounded.SportsEsports,
                        tone = FeatureTone.Green,
                        grouped = true,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onGameOptimization,
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "显示更多通知设置项",
                        summary = "恢复重要性、角标、锁屏显示和系统通知控制",
                        icon = Icons.Rounded.Tune,
                        checked = moreNotificationSettings,
                        enabled = !working,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onCheckedChange = onMoreNotificationChange,
                    )
                    InsetDivider()
                    NavigationRow(
                        title = "设备信息与页面美化",
                        summary = "定制“我的设备”页面、硬件信息和音乐",
                        icon = Icons.Rounded.Palette,
                        tone = FeatureTone.Amber,
                        grouped = true,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onDeviceParams,
                    )
                }
            }

            item(key = "features-header") {
                SectionHeader("已启用修复", tone = FeatureTone.Green)
            }
            item(key = "features") { FeatureGrid(features) }

            item(key = "tools-header") {
                SectionHeader("系统与诊断", tone = FeatureTone.Amber)
            }
            item(key = "tools") {
                SettingsGroup {
                    NavigationRow(
                        title = "包管理服务（核心破解）",
                        summary = "Android 17 签名校验与安装策略",
                        icon = Icons.Rounded.Security,
                        grouped = true,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onCorePatch,
                    )
                    InsetDivider()
                    NavigationRow(
                        title = "综合功能诊断",
                        summary = "检查 Hook、ROOT 组件、SO、传感器和节点",
                        icon = Icons.Rounded.BugReport,
                        tone = FeatureTone.Green,
                        grouped = true,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onDiagnostics,
                    )
                    InsetDivider()
                    NavigationRow(
                        title = "性能与调度监视",
                        summary = "按需悬浮显示 CPU、GPU、温度、频率投票和线程状态",
                        icon = Icons.Rounded.MonitorHeart,
                        tone = FeatureTone.Blue,
                        grouped = true,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onPerformanceMonitor,
                    )
                    InsetDivider()
                    NavigationRow(
                        title = "LTPO 实际 TE 检测",
                        summary = "执行一次 15 秒采样并显示终端输出",
                        icon = Icons.Rounded.Terminal,
                        tone = FeatureTone.Amber,
                        grouped = true,
                        trailing = { Icon(Icons.Rounded.ChevronRight, contentDescription = null) },
                        onClick = onLtpoTest,
                    )
                }
            }

            item(key = "root-header") {
                SectionHeader("ROOT 组件", tone = FeatureTone.Red)
            }
            item(key = "runtime") {
                RuntimeCard(
                    runtimeText = runtimeText,
                    working = working,
                    onStartRoot = onStartRoot,
                    onRefresh = onRefresh,
                )
            }

            item(key = "footer") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "版本 $version",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "通知设置与核心破解基于 HyperCeiler 的 AGPL-3.0 实现适配。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewCard(
    xposedActive: Boolean,
    rootResult: RootInstaller.StartResult?,
) {
    val rootKnown = rootResult != null
    val rootGranted = rootResult?.rootGranted == true
    val snValue = when {
        !rootKnown -> "检测中"
        !rootGranted -> "需 ROOT"
        rootResult?.serialSynchronized == true -> "已同步"
        rootResult?.serialAvailable == false -> "无来源"
        else -> "未同步"
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.app_icon_24),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.size(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (xposedActive) "核心修复已加载" else "等待 LSPosed 加载",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "开机自启 · 事件驱动 · 无持续轮询",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 14.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusMetric(
                    label = "LSPosed",
                    value = if (xposedActive) "已激活" else "未激活",
                    state = xposedActive,
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = "ROOT",
                    value = when {
                        !rootKnown -> "检测中"
                        rootGranted -> "已授权"
                        else -> "未授权"
                    },
                    state = if (!rootKnown) null else rootGranted,
                    modifier = Modifier.weight(1f),
                )
                StatusMetric(
                    label = "设备 SN",
                    value = snValue,
                    state = when {
                        !rootKnown -> null
                        !rootGranted -> false
                        else -> rootResult?.serialSynchronized == true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatusMetric(
    label: String,
    value: String,
    state: Boolean?,
    modifier: Modifier = Modifier,
) {
    val color = when (state) {
        true -> MaterialTheme.colorScheme.secondary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.tertiary
    }
    Column(modifier = modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(7.dp),
                shape = CircleShape,
                color = color,
                content = {},
            )
            Spacer(Modifier.size(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RootWarningCard(
    working: Boolean,
    onStartRoot: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("未获得 ROOT 权限", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "双击亮屏自举与设备属性同步需要 ROOT。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStartRoot,
                enabled = !working,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(7.dp))
                Text("授权并启动")
            }
        }
    }
}

@Composable
private fun RuntimeCard(
    runtimeText: String,
    working: Boolean,
    onStartRoot: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            SelectionContainer {
                Text(
                    runtimeText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onStartRoot,
                    enabled = !working,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(7.dp))
                    Text("授权并启动", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !working,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(7.dp))
                    Text("刷新状态", maxLines = 1)
                }
            }
        }
    }
}
