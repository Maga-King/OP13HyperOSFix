package local.mio.op13hyperosfix

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LtpoTestActivity : ComponentActivity() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var terminalText by mutableStateOf(HEADER)
    private var running by mutableStateOf(false)
    private var exitCode by mutableStateOf<Int?>(null)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            Op13FixTheme {
                LtpoTerminalScreen(
                    text = terminalText,
                    running = running,
                    exitCode = exitCode,
                    onBack = ::finish,
                    onRunAgain = ::runTest,
                )
            }
        }
        runTest()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    private fun runTest() {
        if (running) return
        running = true
        exitCode = null
        terminalText = HEADER
        executor.execute {
            val result = LtpoTester.run(this) { line ->
                runOnUiThread { terminalText += "$line\n" }
            }
            runOnUiThread {
                exitCode = result.exitCode
                running = false
                terminalText += when {
                    result.error != null -> "\n[错误] ${result.error}\n"
                    result.timedOut -> "\n[超时] 进程已终止，cleanup 已执行。\n"
                    else -> "\n[进程结束，退出码 ${result.exitCode}]\n"
                }
            }
        }
    }

    private companion object {
        const val HEADER = "$ su -c /system/bin/sh ltpo_test.sh\n\n"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LtpoTerminalScreen(
    text: String,
    running: Boolean,
    exitCode: Int?,
    onBack: () -> Unit,
    onRunAgain: () -> Unit,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(text) {
        scroll.scrollTo(scroll.maxValue)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LTPO 检测终端", style = MaterialTheme.typography.titleLarge)
                        Text(
                            when {
                                running -> "正在采样"
                                exitCode == 0 -> "检测完成"
                                else -> "检测已结束"
                            },
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
                        text = text,
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
                    Button(onClick = onRunAgain, modifier = Modifier.height(46.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text("重新检测")
                    }
                }
            }
        }
    }
}

private object LtpoTester {
    private const val TIMEOUT_SECONDS = 25L

    data class Result(
        val exitCode: Int,
        val timedOut: Boolean,
        val error: String? = null,
    )

    fun run(context: Context, onLine: (String) -> Unit): Result {
        var process: Process? = null
        return try {
            val storage = context.createDeviceProtectedStorageContext()
            val script = File(storage.filesDir, "ltpo_test.sh")
            storage.assets.open("ltpo_test.sh").use { input ->
                script.outputStream().use { output -> input.copyTo(output) }
            }
            process = ProcessBuilder(
                "su",
                "-c",
                "/system/bin/sh ${shellQuote(script.absolutePath)}",
            ).redirectErrorStream(true).start()

            val timedOut = AtomicBoolean(false)
            val runningProcess = process
            val watchdog = Thread({
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS))
                    if (runningProcess.isAlive) {
                        timedOut.set(true)
                        runningProcess.destroy()
                        Thread.sleep(300L)
                        if (runningProcess.isAlive) runningProcess.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }, "OP13LtpoWatchdog")
            watchdog.start()

            BufferedReader(
                InputStreamReader(process.inputStream, StandardCharsets.UTF_8),
            ).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
            process.waitFor(1L, TimeUnit.SECONDS)
            watchdog.interrupt()
            Result(
                exitCode = if (process.isAlive) -1 else process.exitValue(),
                timedOut = timedOut.get(),
            )
        } catch (error: Throwable) {
            process?.destroyForcibly()
            Result(-1, false, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
