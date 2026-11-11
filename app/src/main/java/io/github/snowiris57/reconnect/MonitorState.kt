package io.github.snowiris57.reconnect

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Calendar

/** 回線の健康状態 */
enum class Health { OK, BAD, PAUSED, RECOVERING, STOPPED }

data class Status(
    val running: Boolean = false,
    val health: Health = Health.STOPPED,
    val headline: String = "監視は停止中です",
    val detail: String = "",
    val route: Route = Route.MOBILE,
    val signal: SignalInfo? = null,
    val wifi: WifiReading? = null,
    val probe: ProbeResult? = null,
    val badCount: Int = 0,
    val failThreshold: Int = 2,
    val updatedAt: Long = 0,
)

/** グラフ用の計測サンプル */
data class Sample(
    val time: Long,
    val rsrp: Int?,
    val quality: Float?,
    val latencyMs: Long?,
    val ok: Boolean?,
    val health: Health,
    val route: Route = Route.MOBILE,
)

enum class EventType { START, STOP, DETECT, RECOVER_START, STEP, RECOVER_OK, RECOVER_FAIL, ERROR, INFO }

data class Event(val time: Long, val type: EventType, val message: String)

data class DayStats(val attempts: Int, val successes: Int, val failures: Int, val detections: Int) {
    val successRate: Int? get() = if (attempts == 0) null else successes * 100 / attempts

    companion object {
        fun today(events: List<Event>): DayStats {
            val start = startOfToday()
            val today = events.filter { it.time >= start }
            return DayStats(
                attempts = today.count { it.type == EventType.RECOVER_START },
                successes = today.count { it.type == EventType.RECOVER_OK },
                failures = today.count { it.type == EventType.RECOVER_FAIL },
                detections = today.count { it.type == EventType.DETECT },
            )
        }

        fun startOfToday(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

/** サービス・画面・ウィジェットで共有する状態 */
object MonitorState {
    private const val MAX_EVENTS = 500
    private const val MAX_SAMPLES = 240 // 15秒間隔で約1時間
    private const val MAX_FILE_BYTES = 256 * 1024

    val status = MutableStateFlow(Status())
    val samples = MutableStateFlow<List<Sample>>(emptyList())
    /** 新しい順 */
    val events = MutableStateFlow<List<Event>>(emptyList())

    private var file: File? = null
    private var sampleFile: File? = null
    private var unsaved = 0

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "events.tsv")
        file = f
        if (f.exists()) {
            events.value = f.readLines().mapNotNull(::parse).takeLast(MAX_EVENTS).reversed()
        }
        val sf = File(ctx.filesDir, "samples.tsv")
        sampleFile = sf
        if (sf.exists()) {
            val since = System.currentTimeMillis() - 60 * 60 * 1000L
            samples.value = sf.readLines().mapNotNull(::parseSample).filter { it.time >= since }.takeLast(MAX_SAMPLES)
        }
    }

    private fun parseSample(line: String): Sample? {
        val p = line.split('\t')
        if (p.size != 6 && p.size != 7) return null
        return Sample(
            time = p[0].toLongOrNull() ?: return null,
            rsrp = p[1].toIntOrNull(),
            quality = p[2].toFloatOrNull(),
            latencyMs = p[3].toLongOrNull(),
            ok = p[4].toBooleanStrictOrNull(),
            health = runCatching { Health.valueOf(p[5]) }.getOrNull() ?: return null,
            route = p.getOrNull(6)?.let { runCatching { Route.valueOf(it) }.getOrNull() } ?: Route.MOBILE,
        )
    }

    private fun parse(line: String): Event? {
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return null
        val time = parts[0].toLongOrNull() ?: return null
        val type = runCatching { EventType.valueOf(parts[1]) }.getOrNull() ?: return null
        return Event(time, type, parts[2])
    }

    @Synchronized
    fun event(type: EventType, message: String) {
        val e = Event(System.currentTimeMillis(), type, message.replace('\n', ' ').replace('\t', ' '))
        events.update { (listOf(e) + it).take(MAX_EVENTS) }
        val f = file ?: return
        try {
            if (f.length() > MAX_FILE_BYTES) {
                f.writeText(f.readLines().takeLast(MAX_EVENTS).joinToString("\n", postfix = "\n"))
            }
            f.appendText("${e.time}\t${e.type}\t${e.message}\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun addSample(s: Sample) {
        samples.update { (it + s).takeLast(MAX_SAMPLES) }
        // 書き込みは 4 回に 1 回（約1分ごと）
        if (++unsaved < 4) return
        unsaved = 0
        val f = sampleFile ?: return
        try {
            f.writeText(samples.value.joinToString("\n", postfix = "\n") {
                "${it.time}\t${it.rsrp ?: ""}\t${it.quality ?: ""}\t${it.latencyMs ?: ""}\t${it.ok ?: ""}\t${it.health}\t${it.route}"
            })
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun clearEvents() {
        events.value = emptyList()
        try {
            file?.delete()
        } catch (_: Exception) {
        }
    }
}
