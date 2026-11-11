package io.github.snowiris57.reconnect

import android.content.Context
import android.net.TrafficStats
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

/** 通信量のサンプル。[rx]・[tx] は 1 秒あたりのバイト数（その前の記録からの平均） */
data class TrafficSample(val time: Long, val rx: Long, val tx: Long)

/**
 * 端末全体の受信・送信量（TrafficStats）を 10 秒ごとに記録する。
 * 見守りサービスと、開いている画面の両方から [tick] を呼ぶ。10 秒たっていなければ何もしない。
 */
object Traffic {
    const val STEP_MS = 10_000L
    private const val MAX_SAMPLES = 360 // 10 秒ごとに 1 時間
    /** これより間が空いたら、平均ではなく「記録なし」として線を切る */
    private const val GAP_MS = 45_000L

    val samples = MutableStateFlow<List<TrafficSample>>(emptyList())

    private var file: File? = null
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L // elapsedRealtime
    private var unsaved = 0

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "traffic.tsv")
        file = f
        if (f.exists()) {
            val since = System.currentTimeMillis() - 60 * 60 * 1000L
            samples.value = runCatching {
                f.readLines().mapNotNull(::parse).filter { it.time >= since }.takeLast(MAX_SAMPLES)
            }.getOrDefault(emptyList())
        }
    }

    private fun parse(line: String): TrafficSample? {
        val p = line.split('\t')
        if (p.size != 3) return null
        return TrafficSample(p[0].toLongOrNull() ?: return null, p[1].toLongOrNull() ?: return null, p[2].toLongOrNull() ?: return null)
    }

    /** 端末全体の受信・送信の累計バイト。取れない端末では null */
    fun totals(): Pair<Long, Long>? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        return rx to tx
    }

    /** 10 秒たっていれば、前回からの平均の速さを記録する */
    @Synchronized
    fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (lastAt != 0L && now - lastAt < STEP_MS - 500) return
        val (rx, tx) = totals() ?: return
        val dt = now - lastAt
        val fresh = lastAt == 0L || dt > GAP_MS || rx < lastRx || tx < lastTx // 初回・間が空いた・再起動で戻った
        if (!fresh) {
            val s = TrafficSample(
                time = System.currentTimeMillis(),
                rx = (rx - lastRx) * 1000 / dt,
                tx = (tx - lastTx) * 1000 / dt,
            )
            val since = s.time - 60 * 60 * 1000L
            samples.update { list -> (list + s).filter { it.time >= since }.takeLast(MAX_SAMPLES) }
            save()
        }
        lastRx = rx
        lastTx = tx
        lastAt = now
    }

    // 書き込みは 6 回に 1 回（約 1 分ごと）
    private fun save() {
        if (++unsaved < 6) return
        unsaved = 0
        val f = file ?: return
        runCatching {
            f.writeText(samples.value.joinToString("\n", postfix = "\n") { "${it.time}\t${it.rx}\t${it.tx}" })
        }
    }

    /** 例: "0.1 KB/s"、"7.0 MB/s" */
    fun formatRate(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

    /** 例: "980 KB"、"12.3 MB"、"1.2 GB"。1 KB 未満も 0.x KB で表す */
    fun formatBytes(bytes: Long): String {
        val kb = bytes / 1024.0
        return when {
            kb < 1000 -> String.format(Locale.US, "%.1f KB", kb)
            kb < 1000 * 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024)
            else -> String.format(Locale.US, "%.2f GB", kb / 1024 / 1024)
        }
    }
}
