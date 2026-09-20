package local.mio.op13hyperosfix

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DiagnosticsActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var report by mutableStateOf("正在检查所有组件…\n")
    private var running by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Op13FixTheme {
                DiagnosticsScreen(
                    report = report,
                    running = running,
                    onBack = ::finish,
                    onRefresh = ::runDiagnostics,
                )
            }
        }
        runDiagnostics()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runDiagnostics() {
        if (running) return
        running = true
        report = "正在检查所有组件…\n"
        executor.execute {
            val result = DiagnosticsRunner.run(this)
            runOnUiThread {
                report = result
                running = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(
    report: String,
    running: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val scroll = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("综合功能诊断", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (running) "正在检查" else "检查完成",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = MaterialTheme.shapes.medium,
                color = Color(0xFF0D1117),
            ) {
                SelectionContainer {
                    Text(
                        text = report,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(14.dp),
                        color = Color(0xFFD7E6D9),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (!running) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onRefresh, modifier = Modifier.height(46.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("重新诊断")
                    }
                }
            }
        }
    }
}

private object DiagnosticsRunner {
    private const val PREFS = "hook_diagnostics"
    private const val TIMEOUT_SECONDS = 12L

    fun run(context: Context): String {
        val output = StringBuilder()
        output.append("=== 一加 13 澎湃综合修复诊断 ===\n")
        output.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
            .append("\n\n")

        output.append("[LSPosed Hook]\n")
        status(output, ModuleConfig.isXposedActive(context), "LSPosed 本次开机激活标记")
        marker(output, context, "system_server", "system_server 输入策略、步数与核心框架")
        marker(output, context, "micharge_bridge", "Oplus Charger 快充、功率与 SOC 小数链")
        marker(output, context, "systemui", "SystemUI 指纹、传感器、AOD 与三段键")
        marker(output, context, "settings", "设置通知选项与图标数量")
        marker(output, context, "bluetooth", "蓝牙进程 Hook 入口")
        marker(output, context, "securitycenter", "手机管家充电、电池与剩余时间")
        marker(output, context, "lhdc_bridge", "蓝牙进程 LHDC 编码接口桥接")
        marker(output, context, "ifaa", "支付宝 IFAA 指纹支付与一加 HAL 转接")
        marker(output, context, "downloadprovider", "DownloadProvider 开机防杀与 MTP 稳定性")
        marker(output, context, "wlan_huanji", "小米换机 CE 数据目录兼容")
        marker(output, context, "wlan_cloudbackup", "云备份 WLAN 地址兼容")
        marker(output, context, "cne_datacall", "高通 CNE 数据链重复注销防崩")

        output.append("\n[澎湃震动增强]\n")
        marker(output, context, "haptics_framework", "framework 私有效果、强度与 RTP 传输入口")
        marker(output, context, "haptics_systemui", "SystemUI 通知与指纹触感入口")
        marker(output, context, "haptics_settings", "设置触感视频与原生强度入口")
        val rtpCount = try {
            context.assets.list("rtp")?.count { it.endsWith(".bin") } ?: 0
        } catch (_: Throwable) {
            0
        }
        status(output, rtpCount == 194, "完整 RTP 资源：$rtpCount/194")
        val helperPresent = try {
            context.assets.open("root/rtp_root_helper").use { it.read() >= 0 }
        } catch (_: Throwable) {
            false
        }
        status(output, helperPresent, "按需 ROOT RTP helper")
        val videoPresent = try {
            context.resources.openRawResourceFd(R.raw.haptic_video_192).use { it.length > 0 }
        } catch (_: Throwable) {
            false
        }
        status(output, videoPresent, "触感视频 192 音频轨")

        output.append("\n[LHDC 动态库]\n")
        val lhdc = NativeDiagnostics.probeLhdc(context)
        status(output, lhdc.coreLoaded, "liblhdcv5.so dlopen")
        status(output, lhdc.wrapperLoaded, "liblhdcv5BT_enc.so dlopen")
        status(output, lhdc.symbolsPresent, "LHDC wrapper 关键编码符号")
        if (lhdc.error != null) output.append("[ERR] ").append(lhdc.error).append('\n')

        output.append("\n[ROOT 组件]\n")
        val root = RootInstaller.status(context)
        status(output, root.rootGranted, "ROOT 授权")
        if (root.doubleTapEnabled) {
            status(output, root.f4Running, "双击亮屏 f4_wake_bin")
        } else {
            output.append("[OFF] 双击亮屏：用户已关闭\n")
        }
        status(output, root.fodBridgeLoaded || root.fpRunning,
            if (root.fodBridgeLoaded) "指纹事件：内核桥接" else "指纹事件：日志回退")
        status(output, root.ltpoLoaded || !root.bootCompleted, "LTPO 内核模块")
        status(output, root.serialAvailable && root.serialSynchronized, "小米 SN 属性同步")

        output.append("\n[功能配置]\n")
        config(output, context, ModuleConfig.DOUBLE_TAP_WAKE, true, "低功耗双击亮屏")
        config(output, context, ModuleConfig.MORE_NOTIFICATION_SETTINGS, true,
            "更多通知设置项")
        config(output, context, ModuleConfig.CORE_MASTER, false, "核心破解总开关")
        config(output, context, ModuleConfig.SCENE_SCHEDULER_ENABLED, false, "内置调度")
        config(output, context, ModuleConfig.SCENE_FAS_ENABLED, true, "游戏自适应帧调速")

        output.append("\n[设备与节点]\n")
        val shell = runRootShell()
        if (shell.output.isNotBlank()) output.append(shell.output.trim()).append('\n')
        if (shell.timedOut) output.append("[ERR] ROOT 节点检查超时\n")
        else if (shell.exitCode != 0) {
            output.append("[ERR] ROOT 节点检查退出码 ").append(shell.exitCode).append('\n')
        }

        output.append("\n说明：进程标记只表示该作用域已实际加载模块入口；")
            .append("依赖手势或外设的行为仍需实际触发验证。\n")
        return output.toString()
    }

    private fun marker(output: StringBuilder, context: Context, key: String, label: String) {
        val resolver = context.contentResolver
        val boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val markedBoot = prefs.getInt("${key}_boot", -3)
        val current = boot >= 0 && (markedBoot == boot || markedBoot == boot - 1)
        output.append(if (current) "[OK] " else "[WAIT] ")
            .append(label)
            .append(if (current) "\n" else "：本次开机尚未收到标记\n")
    }

    private fun status(output: StringBuilder, okay: Boolean, label: String) {
        output.append(if (okay) "[OK] " else "[ERR] ").append(label).append('\n')
    }

    private fun config(
        output: StringBuilder,
        context: Context,
        key: String,
        defaultValue: Boolean,
        label: String,
    ) {
        val enabled = ModuleConfig.isEnabled(context, key, defaultValue)
        output.append(if (enabled) "[ON] " else "[OFF] ").append(label).append('\n')
    }

    private fun runRootShell(): ShellResult {
        val script = """
            [ "$(id -u 2>/dev/null)" = 0 ] || exit 90
            echo "[INFO] 内核：$(uname -r)"
            echo "[INFO] 页大小：$(getconf PAGE_SIZE 2>/dev/null)"
            check_node() {
              if [ -e "$1" ]; then echo "[OK] 节点 $1"; else echo "[ERR] 缺少节点 $1"; fi
            }
            check_node /sys/kernel/oplus_display/min_fps
            check_node /sys/kernel/oplus_display/adfr_config
            check_node /sys/kernel/oplus_display/test_te
            check_node /proc/tristatekey/tri_state
            check_node /sys/class/drm/card0/card0-sde-crtc-0/measured_fps
            if grep -q '^fas active=' /proc/op13_scene_sched 2>/dev/null; then
              echo "[OK] FAS 原子内核投票接口"
              grep '^fas active=' /proc/op13_scene_sched 2>/dev/null | sed 's/^/[INFO] /'
            else
              echo "[WARN] FAS 内核接口未加载或内置调度已关闭"
            fi
            if [ -r /data/adb/op13_hyperos_fix/sched/fas.state ]; then
              sed 's/^/[FAS] /' /data/adb/op13_hyperos_fix/sched/fas.state
            fi
            if dumpsys sensorservice 2>/dev/null | grep -Eiq '33171026|pick_up_motion|Step Detector'; then
              echo "[OK] 一加移动/抬起/步数传感器可见"
            else
              echo "[WARN] 未在 sensorservice 摘要中匹配移动/抬起/步数传感器"
            fi
        """.trimIndent()
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val text = BufferedReader(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
            ).use { it.readText() }
            ShellResult(if (finished) process.exitValue() else -1, text, !finished)
        } catch (error: Throwable) {
            process?.destroyForcibly()
            ShellResult(-1, "[ERR] ${error.javaClass.simpleName}: ${error.message}", false)
        }
    }

    private data class ShellResult(
        val exitCode: Int,
        val output: String,
        val timedOut: Boolean,
    )
}
