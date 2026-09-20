package local.mio.op13hyperosfix.deviceparams

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import local.mio.op13hyperosfix.FeatureTone
import local.mio.op13hyperosfix.InsetDivider
import local.mio.op13hyperosfix.Op13FixTheme
import local.mio.op13hyperosfix.SectionHeader
import local.mio.op13hyperosfix.SettingSwitchRow
import local.mio.op13hyperosfix.SettingsGroup

class DeviceParamsActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            var config by remember { mutableStateOf(readConfig()) }
            Op13FixTheme {
                DeviceParamsScreen(
                    state = config,
                    ram = RamUtils.getDisplayValue(this),
                    onStateChange = { config = it },
                    onBack = ::finish,
                    onSave = {
                        if (saveConfig(config)) {
                            Toast.makeText(
                                this,
                                "已保存，重新打开设置后生效",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show()
                        }
                    },
                    onReset = {
                        val prefs = getSharedPreferences(ConfigContract.PREFS, MODE_PRIVATE)
                        if (prefs.edit().clear().commit()) {
                            config = readConfig()
                            contentResolver.notifyChange(ConfigContract.URI, null)
                            Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "恢复失败", Toast.LENGTH_LONG).show()
                        }
                    },
                )
            }
        }
    }

    private fun readConfig(): DeviceParamsUiState {
        val prefs = getSharedPreferences(ConfigContract.PREFS, Context.MODE_PRIVATE)
        val forceDark = prefs.getBoolean(
            ConfigContract.FORCE_CUSTOM_DARK,
            ConfigContract.DEFAULT_FORCE_CUSTOM_DARK,
        )
        return DeviceParamsUiState(
            paramsEnabled = prefs.getBoolean(
                ConfigContract.ENABLED,
                ConfigContract.DEFAULT_PARAMS_ENABLED,
            ),
            beautyEnabled = prefs.getBoolean(
                ConfigContract.BEAUTY_ENABLED,
                ConfigContract.DEFAULT_BEAUTY_ENABLED,
            ),
            forceCustomDark = forceDark,
            customDual = !forceDark && prefs.getBoolean(
                ConfigContract.CUSTOM_DUAL,
                ConfigContract.DEFAULT_CUSTOM_DUAL,
            ),
            musicEnabled = prefs.getBoolean(
                ConfigContract.MUSIC_ENABLED,
                ConfigContract.DEFAULT_MUSIC_ENABLED,
            ),
            marketName = prefs.getString(
                ConfigContract.MARKET_NAME,
                ConfigContract.DEFAULT_MARKET_NAME,
            ) ?: ConfigContract.DEFAULT_MARKET_NAME,
            processor = prefs.getString(
                ConfigContract.PROCESSOR,
                ConfigContract.DEFAULT_PROCESSOR,
            ) ?: ConfigContract.DEFAULT_PROCESSOR,
            battery = prefs.getString(
                ConfigContract.BATTERY,
                ConfigContract.DEFAULT_BATTERY,
            ) ?: ConfigContract.DEFAULT_BATTERY,
            rearCamera = prefs.getString(
                ConfigContract.REAR_CAMERA,
                ConfigContract.DEFAULT_REAR_CAMERA,
            ) ?: ConfigContract.DEFAULT_REAR_CAMERA,
            frontCamera = prefs.getString(
                ConfigContract.FRONT_CAMERA,
                ConfigContract.DEFAULT_FRONT_CAMERA,
            ) ?: ConfigContract.DEFAULT_FRONT_CAMERA,
            screenSize = prefs.getString(
                ConfigContract.SCREEN_SIZE,
                ConfigContract.DEFAULT_SCREEN_SIZE,
            ) ?: ConfigContract.DEFAULT_SCREEN_SIZE,
            resolution = prefs.getString(
                ConfigContract.RESOLUTION,
                ConfigContract.DEFAULT_RESOLUTION,
            ) ?: ConfigContract.DEFAULT_RESOLUTION,
        )
    }

    private fun saveConfig(state: DeviceParamsUiState): Boolean {
        val saved = getSharedPreferences(ConfigContract.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ConfigContract.ENABLED, state.paramsEnabled)
            .putBoolean(ConfigContract.BEAUTY_ENABLED, state.beautyEnabled)
            .putBoolean(ConfigContract.FORCE_CUSTOM_DARK, state.forceCustomDark)
            .putBoolean(
                ConfigContract.CUSTOM_DUAL,
                !state.forceCustomDark && state.customDual,
            )
            .putBoolean(ConfigContract.MUSIC_ENABLED, state.musicEnabled)
            .putString(ConfigContract.MARKET_NAME, state.marketName.trim())
            .putString(ConfigContract.PROCESSOR, state.processor.trim())
            .putString(ConfigContract.BATTERY, state.battery.trim())
            .putString(ConfigContract.REAR_CAMERA, state.rearCamera.trim())
            .putString(ConfigContract.FRONT_CAMERA, state.frontCamera.trim())
            .putString(ConfigContract.SCREEN_SIZE, state.screenSize.trim())
            .putString(ConfigContract.RESOLUTION, state.resolution.trim())
            .commit()
        if (saved) contentResolver.notifyChange(ConfigContract.URI, null)
        return saved
    }
}

private data class DeviceParamsUiState(
    val paramsEnabled: Boolean,
    val beautyEnabled: Boolean,
    val forceCustomDark: Boolean,
    val customDual: Boolean,
    val musicEnabled: Boolean,
    val marketName: String,
    val processor: String,
    val battery: String,
    val rearCamera: String,
    val frontCamera: String,
    val screenSize: String,
    val resolution: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceParamsScreen(
    state: DeviceParamsUiState,
    ram: String,
    onStateChange: (DeviceParamsUiState) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("设备信息与页面", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "我的设备 · OS4",
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
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 6.dp,
                bottom = 30.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "summary") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                        ) {
                            Icon(
                                Icons.Rounded.Palette,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(9.dp).size(22.dp),
                            )
                        }
                        Spacer(Modifier.size(13.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "我的设备页面",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                when {
                                    !state.beautyEnabled -> "原始外观"
                                    state.forceCustomDark -> "定制深色 · ${if (state.musicEnabled) "音乐开启" else "音乐关闭"}"
                                    state.customDual -> "定制双色 · 跟随系统"
                                    else -> "美化框架已开启"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }

            item(key = "appearance-header") {
                SectionHeader("页面外观", tone = FeatureTone.Amber)
            }
            item(key = "appearance") {
                SettingsGroup {
                    SettingSwitchRow(
                        title = "美化总开关",
                        summary = "接管“我的设备”页面布局与背景",
                        icon = Icons.Rounded.AutoAwesome,
                        checked = state.beautyEnabled,
                        enabled = true,
                        tone = FeatureTone.Amber,
                        grouped = true,
                        onCheckedChange = { onStateChange(state.copy(beautyEnabled = it)) },
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "强制定制深色",
                        summary = "仅在“我的设备”页面应用深色主题",
                        icon = Icons.Rounded.DarkMode,
                        checked = state.forceCustomDark,
                        enabled = state.beautyEnabled,
                        grouped = true,
                        onCheckedChange = {
                            onStateChange(state.copy(
                                forceCustomDark = it,
                                customDual = if (it) false else state.customDual,
                            ))
                        },
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "通用定制双色",
                        summary = "保留定制布局并跟随系统日夜模式",
                        icon = Icons.Rounded.Tonality,
                        checked = state.customDual,
                        enabled = state.beautyEnabled,
                        tone = FeatureTone.Green,
                        grouped = true,
                        onCheckedChange = {
                            onStateChange(state.copy(
                                customDual = it,
                                forceCustomDark = if (it) false else state.forceCustomDark,
                            ))
                        },
                    )
                    InsetDivider()
                    SettingSwitchRow(
                        title = "页面音乐",
                        summary = "进入页面淡入播放，离开时淡出停止",
                        icon = Icons.Rounded.MusicNote,
                        checked = state.musicEnabled,
                        enabled = state.beautyEnabled,
                        tone = FeatureTone.Red,
                        grouped = true,
                        onCheckedChange = { onStateChange(state.copy(musicEnabled = it)) },
                    )
                }
            }

            item(key = "params-header") { SectionHeader("设备参数") }
            item(key = "params-master") {
                SettingsGroup {
                    SettingSwitchRow(
                        title = "设备参数注入",
                        summary = "替换机型、基础硬件与摄像头信息",
                        icon = Icons.Rounded.Info,
                        checked = state.paramsEnabled,
                        enabled = true,
                        grouped = true,
                        onCheckedChange = { onStateChange(state.copy(paramsEnabled = it)) },
                    )
                }
            }

            item(key = "hardware-header") {
                SectionHeader("硬件信息", tone = FeatureTone.Green)
            }
            item(key = "hardware-fields") {
                HardwareFields(state, ram, onStateChange)
            }

            item(key = "actions") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.size(7.dp))
                        Text("保存")
                    }
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                    ) {
                        Icon(
                            Icons.Rounded.RestartAlt,
                            contentDescription = null,
                            Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(7.dp))
                        Text("恢复默认")
                    }
                }
            }
        }
    }
}

@Composable
private fun HardwareFields(
    state: DeviceParamsUiState,
    ram: String,
    onStateChange: (DeviceParamsUiState) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ParamField("设备/市场名称", state.marketName) {
                onStateChange(state.copy(marketName = it))
            }
            ParamField("处理器", state.processor) {
                onStateChange(state.copy(processor = it))
            }
            ParamField("电池容量", state.battery) {
                onStateChange(state.copy(battery = it))
            }
            ParamField("后置摄像头", state.rearCamera) {
                onStateChange(state.copy(rearCamera = it))
            }
            ParamField("前置摄像头", state.frontCamera) {
                onStateChange(state.copy(frontCamera = it))
            }
            ParamField("屏幕尺寸", state.screenSize) {
                onStateChange(state.copy(screenSize = it))
            }
            ParamField("分辨率", state.resolution) {
                onStateChange(state.copy(resolution = it))
            }
            OutlinedTextField(
                value = ram,
                onValueChange = {},
                label = { Text("运行内存") },
                leadingIcon = {
                    Icon(Icons.Rounded.Memory, contentDescription = null)
                },
                readOnly = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ParamField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
