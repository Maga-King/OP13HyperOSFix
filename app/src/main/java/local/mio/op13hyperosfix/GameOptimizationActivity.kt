package local.mio.op13hyperosfix

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SettingsSuggest
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.text.Collator
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors

class GameOptimizationActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var gameTouchEnabled by mutableStateOf(false)
    private var gameTouchRate by mutableStateOf(240)
    private var bypassEnabled by mutableStateOf(false)
    private var bypassEntryTemperature by mutableStateOf(42)
    private var sceneSchedulerEnabled by mutableStateOf(false)
    private var sceneDailyMode by mutableStateOf(0)
    private var sceneGameMode by mutableStateOf(2)
    private var sceneThreadPlacement by mutableStateOf(true)
    private var sceneConfigLock by mutableStateOf(true)
    private var sceneFasEnabled by mutableStateOf(true)
    private var gameOptBlockEnabled by mutableStateOf(true)
    private var schedulerLogging by mutableStateOf(false)
    private var selectedPackages by mutableStateOf<Set<String>>(emptySet())
    private var appPolicies by mutableStateOf<Map<String, GameAppPolicy>>(emptyMap())
    private var applications by mutableStateOf<List<GameApplication>>(emptyList())
    private var loading by mutableStateOf(true)
    private var working by mutableStateOf(false)
    @Volatile private var packageWriteGeneration = 0
    private var pendingPackageWrite: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        gameTouchEnabled = ConfigWriter.read(
            this, ModuleConfig.GAME_TOUCH_ENABLED, false,
        )
        gameTouchRate = RootInstaller.sanitizeTouchSamplingRate(
            ConfigWriter.readInt(this, ModuleConfig.GAME_TOUCH_RATE, 240),
        )
        bypassEnabled = ConfigWriter.read(
            this, ModuleConfig.GAME_BYPASS_ENABLED, false,
        )
        bypassEntryTemperature = ConfigWriter.readInt(
            this, ModuleConfig.GAME_BYPASS_ENTRY_TEMP, 42,
        ).coerceIn(30, 49)
        sceneSchedulerEnabled = ConfigWriter.read(
            this, ModuleConfig.SCENE_SCHEDULER_ENABLED, false,
        )
        sceneDailyMode = ConfigWriter.readInt(
            this, ModuleConfig.SCENE_DAILY_MODE, 0,
        ).coerceIn(0, 3)
        sceneGameMode = ConfigWriter.readInt(
            this, ModuleConfig.SCENE_GAME_MODE, 2,
        ).coerceIn(0, 3)
        sceneThreadPlacement = ConfigWriter.read(
            this, ModuleConfig.SCENE_THREAD_PLACEMENT, true,
        )
        sceneConfigLock = ConfigWriter.read(
            this, ModuleConfig.SCENE_CONFIG_LOCK, true,
        )
        sceneFasEnabled = ConfigWriter.read(
            this, ModuleConfig.SCENE_FAS_ENABLED, true,
        )
        gameOptBlockEnabled = ConfigWriter.read(
            this, ModuleConfig.GAMEOPT_BLOCK_ENABLED, true,
        )
        schedulerLogging = ConfigWriter.read(
            this, ModuleConfig.SCHEDULER_LOGGING, false,
        )
        selectedPackages = parsePackages(ModuleConfig.getString(
            this, ModuleConfig.GAME_PACKAGES, "",
        ))
        appPolicies = GameAppPolicy.parse(ModuleConfig.getString(
            this, ModuleConfig.GAME_APP_POLICIES, "",
        ))
        setContent {
            Op13FixTheme {
                GameOptimizationScreen(
                    gameTouchEnabled = gameTouchEnabled,
                    gameTouchRate = gameTouchRate,
                    bypassEnabled = bypassEnabled,
                    bypassEntryTemperature = bypassEntryTemperature,
                    sceneSchedulerEnabled = sceneSchedulerEnabled,
                    sceneDailyMode = sceneDailyMode,
                    sceneGameMode = sceneGameMode,
                    sceneThreadPlacement = sceneThreadPlacement,
                    sceneConfigLock = sceneConfigLock,
                    sceneFasEnabled = sceneFasEnabled,
                    gameOptBlockEnabled = gameOptBlockEnabled,
                    schedulerLogging = schedulerLogging,
                    selectedPackages = selectedPackages,
                    appPolicies = appPolicies,
                    applications = applications,
                    loading = loading,
                    working = working,
                    onBack = { finish() },
                    onGameTouchChange = {
                        saveBoolean(ModuleConfig.GAME_TOUCH_ENABLED, it) {
                            gameTouchEnabled = it
                        }
                    },
                    onGameRateChange = {
                        saveRate(it) { gameTouchRate = it }
                    },
                    onBypassChange = {
                        saveBoolean(ModuleConfig.GAME_BYPASS_ENABLED, it) {
                            bypassEnabled = it
                        }
                    },
                    onBypassEntryTemperatureChange = {
                        saveInteger(
                            ModuleConfig.GAME_BYPASS_ENTRY_TEMP,
                            it.coerceIn(30, 49),
                        ) { bypassEntryTemperature = it.coerceIn(30, 49) }
                    },
                    onSceneSchedulerChange = {
                        saveSceneBoolean(ModuleConfig.SCENE_SCHEDULER_ENABLED, it) {
                            sceneSchedulerEnabled = it
                        }
                    },
                    onSceneDailyModeChange = {
                        saveSceneInteger(ModuleConfig.SCENE_DAILY_MODE, it) {
                            sceneDailyMode = it
                        }
                    },
                    onSceneGameModeChange = {
                        saveSceneInteger(ModuleConfig.SCENE_GAME_MODE, it) {
                            sceneGameMode = it
                        }
                    },
                    onSceneThreadPlacementChange = {
                        saveSceneBoolean(ModuleConfig.SCENE_THREAD_PLACEMENT, it) {
                            sceneThreadPlacement = it
                        }
                    },
                    onSceneConfigLockChange = {
                        saveSceneBoolean(ModuleConfig.SCENE_CONFIG_LOCK, it) {
                            sceneConfigLock = it
                        }
                    },
                    onSceneFasChange = {
                        saveSceneBoolean(ModuleConfig.SCENE_FAS_ENABLED, it) {
                            sceneFasEnabled = it
                        }
                    },
                    onGameOptBlockChange = {
                        saveSceneBoolean(ModuleConfig.GAMEOPT_BLOCK_ENABLED, it) {
                            gameOptBlockEnabled = it
                        }
                    },
                    onSchedulerLoggingChange = {
                        saveSceneBoolean(ModuleConfig.SCHEDULER_LOGGING, it) {
                            schedulerLogging = it
                        }
                    },
                    onOpenSchedulerLog = {
                        startActivity(Intent(this, SchedulerLogActivity::class.java))
                    },
                    onPackageToggle = ::togglePackage,
                    onAppModeChange = ::updateAppMode,
                    onAppFasChange = ::updateAppFas,
                )
            }
        }
        loadApplications()
    }

    override fun onResume() {
        super.onResume()
        notifyConfigChanged()
    }

    override fun onDestroy() {
        pendingPackageWrite?.let { pending ->
            mainHandler.removeCallbacks(pending)
            pending.run()
            pendingPackageWrite = null
        }
        executor.shutdown()
        super.onDestroy()
    }

    private fun loadApplications() {
        executor.execute {
            val launchIntent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
            val entries = packageManager.queryIntentActivities(
                launchIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            ).mapNotNull { resolved ->
                val info = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
                val packageName = info.packageName ?: return@mapNotNull null
                if (packageName == this.packageName || !info.enabled) return@mapNotNull null
                GameApplication(
                    packageName = packageName,
                    label = info.loadLabel(packageManager).toString().ifBlank { packageName },
                )
            }.distinctBy { it.packageName }
                .sortedWith(compareBy(Collator.getInstance(Locale.CHINA)) { it.label })
            runOnUiThread {
                applications = entries
                loading = false
            }
        }
    }

    private fun saveBoolean(key: String, value: Boolean, commit: () -> Unit) {
        if (working) return
        working = true
        executor.execute {
            val result = ConfigWriter.write(key, value)
            runOnUiThread {
                working = false
                if (result.success) {
                    commit()
                    notifyConfigChanged()
                } else {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveRate(rate: Int, commit: () -> Unit) {
        if (working) return
        val selected = RootInstaller.sanitizeTouchSamplingRate(rate)
        working = true
        executor.execute {
            val result = ConfigWriter.writeInt(ModuleConfig.GAME_TOUCH_RATE, selected)
            runOnUiThread {
                working = false
                if (result.success) {
                    commit()
                    notifyConfigChanged()
                } else {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveInteger(key: String, value: Int, commit: () -> Unit) {
        if (working) return
        working = true
        executor.execute {
            val result = ConfigWriter.writeInt(key, value)
            runOnUiThread {
                working = false
                if (result.success) {
                    commit()
                    notifyConfigChanged()
                } else {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun saveSceneBoolean(key: String, value: Boolean, commit: () -> Unit) {
        if (working) return
        working = true
        executor.execute {
            val result = ConfigWriter.write(key, value)
            val root = if (result.success) RootInstaller.ensureStarted(this, false) else null
            runOnUiThread {
                working = false
                if (result.success && root?.rootGranted == true) {
                    commit()
                    notifyConfigChanged()
                } else {
                    Toast.makeText(
                        this,
                        if (!result.success) result.message else root?.toDisplayText(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun saveSceneInteger(key: String, value: Int, commit: () -> Unit) {
        if (working) return
        working = true
        executor.execute {
            val selected = value.coerceIn(0, 3)
            val result = ConfigWriter.writeInt(key, selected)
            val root = if (result.success) RootInstaller.ensureStarted(this, false) else null
            runOnUiThread {
                working = false
                if (result.success && root?.rootGranted == true) {
                    commit()
                    notifyConfigChanged()
                } else {
                    Toast.makeText(
                        this,
                        if (!result.success) result.message else root?.toDisplayText(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun togglePackage(packageName: String) {
        if (!GameAppPolicy.isPackageName(packageName)) return
        val updated = selectedPackages.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }.toSortedSet()
        selectedPackages = updated
        scheduleGameConfigWrite(updated, appPolicies)
    }

    private fun updateAppMode(packageName: String, mode: Int) {
        updateAppPolicy(packageName) { it.withMode(mode) }
    }

    private fun updateAppFas(packageName: String, fas: Int) {
        updateAppPolicy(packageName) { it.withFas(fas) }
    }

    private fun updateAppPolicy(
        packageName: String,
        transform: (GameAppPolicy) -> GameAppPolicy,
    ) {
        if (!GameAppPolicy.isPackageName(packageName)) return
        val next = transform(appPolicies[packageName] ?: GameAppPolicy.inherited())
        val updated = appPolicies.toMutableMap().apply {
            if (next.isInherited) remove(packageName) else put(packageName, next)
        }.toSortedMap()
        appPolicies = updated
        scheduleGameConfigWrite(selectedPackages, updated)
    }

    private fun scheduleGameConfigWrite(
        packages: Set<String>,
        policies: Map<String, GameAppPolicy>,
    ) {
        val generation = ++packageWriteGeneration
        pendingPackageWrite?.let(mainHandler::removeCallbacks)
        pendingPackageWrite = Runnable {
            pendingPackageWrite = null
            persistGameConfig(packages, policies, generation)
        }.also { mainHandler.postDelayed(it, 250L) }
    }

    private fun persistGameConfig(
        packages: Set<String>,
        policies: Map<String, GameAppPolicy>,
        generation: Int,
    ) {
        executor.execute {
            val values = LinkedHashMap<String, String>().apply {
                put(ModuleConfig.GAME_PACKAGES, packages.joinToString(","))
                put(ModuleConfig.GAME_APP_POLICIES, GameAppPolicy.encode(policies))
            }
            val result = ConfigWriter.writeStrings(values)
            if (result.success && sceneSchedulerEnabled) {
                RootInstaller.ensureStarted(this, false)
            }
            runOnUiThread {
                if (result.success) {
                    if (generation == packageWriteGeneration) notifyConfigChanged()
                } else if (generation == packageWriteGeneration) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun notifyConfigChanged() {
        sendBroadcast(Intent(GameOptimizationHooks.ACTION_CONFIG_CHANGED))
    }

    private fun parsePackages(raw: String): Set<String> = raw.split(',')
        .map(String::trim)
        .filter { it.matches(Regex("[A-Za-z0-9._]+")) }
        .toSet()

    companion object {
        val TOUCH_RATES = listOf(70, 120, 180, 240, 360)
    }
}

private data class GameApplication(val packageName: String, val label: String)

@Composable
private fun SceneModeRow(
    title: String,
    selected: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    val labels = listOf("省电", "均衡", "性能", "极速")
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(10.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    modifier = Modifier.weight(1f),
                    selected = selected == index,
                    onClick = { onSelected(index) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameOptimizationScreen(
    gameTouchEnabled: Boolean,
    gameTouchRate: Int,
    bypassEnabled: Boolean,
    bypassEntryTemperature: Int,
    sceneSchedulerEnabled: Boolean,
    sceneDailyMode: Int,
    sceneGameMode: Int,
    sceneThreadPlacement: Boolean,
    sceneConfigLock: Boolean,
    sceneFasEnabled: Boolean,
    gameOptBlockEnabled: Boolean,
    schedulerLogging: Boolean,
    selectedPackages: Set<String>,
    appPolicies: Map<String, GameAppPolicy>,
    applications: List<GameApplication>,
    loading: Boolean,
    working: Boolean,
    onBack: () -> Unit,
    onGameTouchChange: (Boolean) -> Unit,
    onGameRateChange: (Int) -> Unit,
    onBypassChange: (Boolean) -> Unit,
    onBypassEntryTemperatureChange: (Int) -> Unit,
    onSceneSchedulerChange: (Boolean) -> Unit,
    onSceneDailyModeChange: (Int) -> Unit,
    onSceneGameModeChange: (Int) -> Unit,
    onSceneThreadPlacementChange: (Boolean) -> Unit,
    onSceneConfigLockChange: (Boolean) -> Unit,
    onSceneFasChange: (Boolean) -> Unit,
    onGameOptBlockChange: (Boolean) -> Unit,
    onSchedulerLoggingChange: (Boolean) -> Unit,
    onOpenSchedulerLog: () -> Unit,
    onPackageToggle: (String) -> Unit,
    onAppModeChange: (String, Int) -> Unit,
    onAppFasChange: (String, Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    val filtered = remember(applications, query) {
        val needle = query.trim()
        if (needle.isEmpty()) applications else applications.filter {
            it.label.contains(needle, ignoreCase = true)
                    || it.packageName.contains(needle, ignoreCase = true)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("游戏与内置调度")
                        Text(
                            "已选择 ${selectedPackages.size} 个应用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
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
            item(key = "scheduler-header") {
                SectionHeader("内置调度", tone = FeatureTone.Blue)
            }
            item(key = "scheduler-controls") {
                SettingsGroup {
                    SettingSwitchRow(
                        title = "内置调度",
                        summary = "事件驱动切换，使用内置规则与本地游戏名单，不访问云端",
                        icon = Icons.Rounded.SettingsSuggest,
                        checked = sceneSchedulerEnabled,
                        enabled = !working,
                        tone = FeatureTone.Blue,
                        grouped = true,
                        onCheckedChange = onSceneSchedulerChange,
                    )
                    InsetDivider()
                    SceneModeRow(
                        title = "日常档位",
                        selected = sceneDailyMode,
                        enabled = sceneSchedulerEnabled && !working,
                        onSelected = onSceneDailyModeChange,
                    )
                    InsetDivider()
                    SceneModeRow(
                        title = "游戏档位",
                        selected = sceneGameMode,
                        enabled = sceneSchedulerEnabled && !working,
                        onSelected = onSceneGameModeChange,
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "线程放置",
                        summary = "按内置规则处理主线程、渲染线程和重负载线程",
                        icon = Icons.Rounded.Speed,
                        checked = sceneThreadPlacement,
                        enabled = sceneSchedulerEnabled && !working,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onCheckedChange = onSceneThreadPlacementChange,
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "配置锁",
                        summary = "仅亮屏时每 10 秒校验易被覆盖的辅助节点；CPU QoS 始终由内核投票保持",
                        icon = Icons.Rounded.SettingsSuggest,
                        checked = sceneConfigLock,
                        enabled = sceneSchedulerEnabled && !working,
                        tone = FeatureTone.Amber,
                        grouped = true,
                        onCheckedChange = onSceneConfigLockChange,
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "自适应帧调速",
                        summary = "仅游戏可见时读取硬件帧计数，稳帧降档、掉帧恢复；退出后零采样",
                        icon = Icons.Rounded.Speed,
                        checked = sceneFasEnabled,
                        enabled = sceneSchedulerEnabled && !working,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onCheckedChange = onSceneFasChange,
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "屏蔽 GameOpt 抢写",
                        summary = "隔离一加 GameOpt 的 CPU 频率上下限接口，防止覆盖内置调度",
                        icon = Icons.Rounded.SettingsSuggest,
                        checked = gameOptBlockEnabled,
                        enabled = !working,
                        tone = FeatureTone.Amber,
                        grouped = true,
                        onCheckedChange = onGameOptBlockChange,
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "记录调度日志",
                        summary = "仅记录策略变化，关闭时不读取、不写盘；最多 1 MiB / 1000 行",
                        icon = Icons.Rounded.Article,
                        checked = schedulerLogging,
                        enabled = sceneSchedulerEnabled && !working,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onCheckedChange = onSchedulerLoggingChange,
                    )
                    InsetDivider()
                    NavigationRow(
                        title = "查看调度日志",
                        summary = "按需读取事件记录，可刷新或清空",
                        icon = Icons.Rounded.Article,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onClick = onOpenSchedulerLog,
                    )
                }
            }
            item(key = "control-header") {
                SectionHeader("游戏策略")
            }
            item(key = "controls") {
                SettingsGroup {
                    SettingSwitchRow(
                        title = "游戏触控采样率",
                        summary = "选中的游戏可见时自动切换，退出后恢复日常档位",
                        icon = Icons.Rounded.TouchApp,
                        checked = gameTouchEnabled,
                        enabled = !working,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onCheckedChange = onGameTouchChange,
                    )
                    InsetDivider()
                    SettingSliderRow(
                        title = "游戏采样率档位",
                        summary = "仅游戏内使用；息屏始终回到 70 Hz",
                        icon = Icons.Rounded.SportsEsports,
                        value = gameTouchRate,
                        values = GameOptimizationActivity.TOUCH_RATES,
                        enabled = gameTouchEnabled && !working,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onValueCommitted = onGameRateChange,
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "游戏内旁路供电",
                        summary = "仅有线充电且电量不低于 15% 时启用，退出游戏立即恢复",
                        icon = Icons.Rounded.BatteryChargingFull,
                        checked = bypassEnabled,
                        enabled = !working,
                        tone = FeatureTone.Amber,
                        grouped = true,
                        onCheckedChange = onBypassChange,
                    )
                    InsetDivider()
                    SettingSliderRow(
                        title = "旁路进入温度",
                        summary = "退出温度固定为 ${bypassEntryTemperature - 4}°C，形成 4°C 迟滞",
                        icon = Icons.Rounded.Thermostat,
                        value = bypassEntryTemperature,
                        values = (30..49).toList(),
                        suffix = "°C",
                        enabled = bypassEnabled && !working,
                        tone = FeatureTone.Amber,
                        grouped = true,
                        onValueCommitted = onBypassEntryTemperatureChange,
                    )
                }
            }
            item(key = "apps-header") {
                SectionHeader("选择游戏应用", tone = FeatureTone.Green)
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索应用或包名") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                )
            }
            if (loading) {
                item(key = "loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            } else {
                items(filtered, key = { it.packageName }) { application ->
                    GameApplicationRow(
                        application = application,
                        selected = selectedPackages.contains(application.packageName),
                        policy = appPolicies[application.packageName]
                            ?: GameAppPolicy.inherited(),
                        expanded = expandedPackage == application.packageName,
                        enabled = true,
                        onClick = { onPackageToggle(application.packageName) },
                        onExpandedChange = {
                            expandedPackage = if (expandedPackage == application.packageName) {
                                null
                            } else {
                                application.packageName
                            }
                        },
                        onModeChange = {
                            onAppModeChange(application.packageName, it)
                        },
                        onFasChange = {
                            onAppFasChange(application.packageName, it)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GameApplicationRow(
    application: GameApplication,
    selected: Boolean,
    policy: GameAppPolicy,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onExpandedChange: () -> Unit,
    onModeChange: (Int) -> Unit,
    onFasChange: (Int) -> Unit,
) {
    val modeNames = listOf("省电", "均衡", "性能", "极速")
    val modeSummary = if (policy.mode < 0) {
        if (selected) "跟随全局游戏档" else "跟随全局日常档"
    } else {
        modeNames[policy.mode]
    }
    val fasSummary = when {
        !selected -> "FAS 仅游戏生效"
        policy.fas < 0 -> "FAS 跟随全局"
        policy.fas == 1 -> "FAS 开启"
        else -> "FAS 关闭"
    }
    Surface(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(start = 15.dp, end = 5.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Icon(
                        Icons.Rounded.SportsEsports,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(9.dp).size(21.dp),
                    )
                }
                Spacer(Modifier.size(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        application.label,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        application.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "$modeSummary · $fasSummary",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onExpandedChange, enabled = enabled) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "收起策略" else "展开策略",
                    )
                }
                Checkbox(
                    checked = selected,
                    enabled = enabled,
                    onCheckedChange = { onClick() },
                )
            }
            if (expanded) {
                InsetDivider()
                AppPolicySegmentedRow(
                    title = "应用档位",
                    summary = if (selected) "未指定时继承全局游戏档" else "未指定时继承全局日常档",
                    labels = listOf("跟随", "省电", "均衡", "性能", "极速"),
                    values = listOf(-1, 0, 1, 2, 3),
                    selected = policy.mode,
                    enabled = enabled,
                    onSelected = onModeChange,
                )
                InsetDivider()
                AppPolicySegmentedRow(
                    title = "自适应帧调速 FAS",
                    summary = if (selected) "未指定时继承全局 FAS 开关" else "勾选为游戏后生效",
                    labels = listOf("跟随", "开启", "关闭"),
                    values = listOf(-1, 1, 0),
                    selected = policy.fas,
                    enabled = enabled && selected,
                    onSelected = onFasChange,
                )
            }
        }
    }
}

@Composable
private fun AppPolicySegmentedRow(
    title: String,
    summary: String,
    labels: List<String>,
    values: List<Int>,
    selected: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(9.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            labels.forEachIndexed { index, label ->
                SegmentedButton(
                    modifier = Modifier.weight(1f),
                    selected = selected == values[index],
                    onClick = { onSelected(values[index]) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, labels.size),
                    label = {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                    },
                )
            }
        }
    }
}
