package local.mio.op13hyperosfix

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SchedulerLogActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var content by mutableStateOf("正在读取...")
    private var working by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            Op13FixTheme {
                Scaffold(
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { Text("调度日志") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
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
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    enabled = !working,
                                    onClick = { loadLog() },
                                ) {
                                    Icon(Icons.Rounded.Refresh, null)
                                    Text(" 刷新")
                                }
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !working,
                                    onClick = { clearLog() },
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, null)
                                    Text(" 清空")
                                }
                            }
                        }
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                SelectionContainer {
                                    Text(
                                        content,
                                        modifier = Modifier.padding(16.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        loadLog()
    }

    private fun loadLog() = runRoot(
        "if [ -s /data/adb/op13_hyperos_fix/sched/events.log ]; then " +
            "tail -n 1000 /data/adb/op13_hyperos_fix/sched/events.log; " +
            "else echo '暂无调度日志'; fi",
    ) { output -> content = output.ifBlank { "暂无调度日志" } }

    private fun clearLog() = runRoot(
        ": > /data/adb/op13_hyperos_fix/sched/events.log && echo '日志已清空'",
    ) { output ->
        content = output.ifBlank { "日志已清空" }
        Toast.makeText(this, "调度日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun runRoot(command: String, apply: (String) -> Unit) {
        if (working) return
        working = true
        executor.execute {
            val result = try {
                val process = ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true).start()
                val finished = process.waitFor(8, TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    "读取超时"
                } else {
                    BufferedReader(InputStreamReader(
                        process.inputStream, StandardCharsets.UTF_8,
                    )).use { it.readText() }
                }
            } catch (error: Throwable) {
                "读取失败：${error.message ?: error.javaClass.simpleName}"
            }
            runOnUiThread {
                working = false
                apply(result)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
