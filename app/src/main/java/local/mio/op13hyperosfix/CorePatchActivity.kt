package local.mio.op13hyperosfix

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.util.LinkedHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CorePatchActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val values = mutableStateMapOf<String, Boolean>()
    private var busyKey by mutableStateOf<String?>(null)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        allOptions.forEach { option ->
            values[option.key] = ConfigWriter.read(this, option.key, option.defaultValue)
        }
        setContent {
            Op13FixTheme {
                CorePatchScreen(
                    values = values,
                    busyKey = busyKey,
                    onBack = ::finish,
                    onToggle = ::writeOption,
                )
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun writeOption(key: String, checked: Boolean) {
        if (busyKey != null) return
        busyKey = key
        val changes = LinkedHashMap<String, Boolean>()
        changes[key] = checked
        if (key == ModuleConfig.CORE_AUTH && checked) {
            changes[ModuleConfig.CORE_DISABLE_INTEGRITY] = false
        }
        executor.execute {
            val result = ConfigWriter.write(changes)
            runOnUiThread {
                busyKey = null
                if (!result.success) {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                changes.forEach { (changedKey, value) -> values[changedKey] = value }
                Toast.makeText(this, "已保存，重启手机后生效", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private data class CoreOption(
    val key: String,
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val defaultValue: Boolean = false,
)

private val coreMaster = CoreOption(
    ModuleConfig.CORE_MASTER,
    "启用核心破解",
    "总开关；关闭时不安装核心签名 Hook",
    Icons.Rounded.Security,
)

private val coreOptions = listOf(
    CoreOption(ModuleConfig.CORE_DOWNGRADE, "允许降级安装", "允许旧版本覆盖较新版本", Icons.Rounded.Download),
    CoreOption(ModuleConfig.CORE_AUTH, "禁用包管理器签名验证", "允许安装内容或签名被修改的 APK", Icons.Rounded.LockOpen),
    CoreOption(ModuleConfig.CORE_DISABLE_INTEGRITY, "仅降低签名方案要求", "保留其余校验，仅降低最低签名方案", Icons.Rounded.GppGood),
    CoreOption(ModuleConfig.CORE_DIGEST, "禁用 APK 签名比较", "允许不同签名覆盖并修复权限签名链", Icons.Rounded.Key),
    CoreOption(ModuleConfig.CORE_SHARED_USER, "绕过共享用户签名验证", "允许 sharedUserId 内应用使用不同签名", Icons.Rounded.Android),
    CoreOption(ModuleConfig.CORE_EXACT_SIGNATURE, "禁用分包精确签名匹配", "允许 split APK 之间签名不完全一致", Icons.Rounded.VerifiedUser),
    CoreOption(ModuleConfig.CORE_USE_PRE_SIGNATURE, "优先使用已安装应用签名", "覆盖安装失败时沿用现有签名记录", Icons.Rounded.Build),
    CoreOption(ModuleConfig.CORE_DISABLE_VERIFICATION, "禁用安装验证代理", "跳过系统安装验证代理", Icons.Rounded.Block),
)

private val extraOptions = listOf(
    CoreOption(ModuleConfig.CORE_LOW_API, "允许低目标 API 应用", "安装时加入允许低目标 SDK 标志", Icons.Rounded.Android),
    CoreOption(ModuleConfig.CORE_DISABLE_PERSISTENT, "允许替换持久应用", "仅在安装事务中临时忽略 persistent", Icons.Rounded.Build),
    CoreOption(ModuleConfig.CORE_BYPASS_ISOLATION, "绕过隔离应用检查", "允许第三方安装来源更新系统应用", Icons.Rounded.SystemUpdate),
    CoreOption(ModuleConfig.CORE_ALLOW_SYSTEM_UPDATE, "允许更新系统应用", "绕过小米包管理扩展的更新限制", Icons.Rounded.SystemUpdate),
)

private val protectOptions = listOf(
    CoreOption(ModuleConfig.CORE_PROTECT_FINGERPRINT, "禁止重写指纹配置", "避免核心破解重置移植系统指纹", Icons.Rounded.Fingerprint),
)

private val allOptions = listOf(coreMaster) + coreOptions + extraOptions + protectOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorePatchScreen(
    values: Map<String, Boolean>,
    busyKey: String?,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val master = values[ModuleConfig.CORE_MASTER] == true
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("包管理服务", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "核心破解 · Android 17",
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Text(
                        "这些选项会放宽 Android 包管理安全检查。默认全部关闭，仅在明确需要时开启。",
                        modifier = Modifier.padding(15.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            item { SectionHeader("核心选项") }
            item {
                CoreOptionRow(coreMaster, values, busyKey, true, onToggle)
            }
            item {
                AnimatedVisibility(
                    visible = master,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        coreOptions.forEach { option ->
                            CoreOptionRow(
                                option,
                                values,
                                busyKey,
                                dependencyEnabled(option.key, values),
                                onToggle,
                            )
                        }
                    }
                }
            }
            item { SectionHeader("扩展安装策略") }
            extraOptions.forEach { option ->
                item(option.key) {
                    CoreOptionRow(option, values, busyKey, true, onToggle)
                }
            }
            item { SectionHeader("保护") }
            protectOptions.forEach { option ->
                item(option.key) {
                    CoreOptionRow(option, values, busyKey, true, onToggle)
                }
            }
        }
    }
}

@Composable
private fun CoreOptionRow(
    option: CoreOption,
    values: Map<String, Boolean>,
    busyKey: String?,
    dependencyEnabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    SettingSwitchRow(
        title = option.title,
        summary = option.summary,
        icon = option.icon,
        checked = values[option.key] == true,
        enabled = busyKey == null && dependencyEnabled,
        onCheckedChange = { onToggle(option.key, it) },
    )
}

private fun dependencyEnabled(key: String, values: Map<String, Boolean>): Boolean = when (key) {
    ModuleConfig.CORE_DISABLE_INTEGRITY -> values[ModuleConfig.CORE_AUTH] != true
    ModuleConfig.CORE_SHARED_USER -> values[ModuleConfig.CORE_AUTH] == true &&
        values[ModuleConfig.CORE_DIGEST] == true
    ModuleConfig.CORE_USE_PRE_SIGNATURE -> values[ModuleConfig.CORE_DIGEST] == true
    else -> true
}
