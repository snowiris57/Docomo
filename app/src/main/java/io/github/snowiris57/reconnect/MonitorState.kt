package io.github.snowiris57.reconnect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Status(
    val running: Boolean = false,
    val state: String = "-",
    val signal: String = "-",
    val probe: String = "-",
    val badCount: Int = 0,
    val failThreshold: Int = 0,
    val recoveries: Int = 0,
    val lastRecovery: String = "-",
)

/** サービスと画面で共有する状態とイベントログ */
object MonitorState {
    private const val MAX_LINES = 300
    private const val MAX_FILE_BYTES = 256 * 1024

    val status = MutableStateFlow(Status())
    val log = MutableStateFlow<List<String>>(emptyList())

    private lateinit var file: File
    private val fmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "events.log")
        if (file.exists()) {
            log.value = file.readLines().takeLast(MAX_LINES).reversed()
        }
    }

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        log.update { (listOf(line) + it).take(MAX_LINES) }
        try {
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText(file.readLines().takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
            file.appendText(line + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clearLog() {
        log.value = emptyList()
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}
