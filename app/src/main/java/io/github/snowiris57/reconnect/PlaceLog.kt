package io.github.snowiris57.reconnect

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sqrt

/** その場所でのモバイル通信のようす。下にいくほど悪い */
enum class PlaceKind(val label: String) {
    GOOD("良好"),
    WEAK("電波が弱い"),
    UNSTABLE("不安定"),
    BAD("応答なし"),
}

/**
 * ある場所にいた間の記録。同じ場所（約 60m 以内）で続いたチェックは 1 件にまとめる。
 * [minRsrp] はその間でいちばん弱かった電波、[fails] は応答なしの回数。
 */
data class Place(
    val start: Long,
    val end: Long,
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val kind: PlaceKind,
    val minRsrp: Int?,
    val type: String,
    val count: Int,
    val fails: Int,
    val maxMs: Long?,
) {
    val bad: Boolean get() = kind != PlaceKind.GOOD
}

/** 場所の記録のしかた（設定で切り替え） */
object PlaceMode {
    const val OFF = 0
    /** 電波が弱い・不安定・応答なしのときだけ */
    const val BAD_ONLY = 1
    /** 通った道すじ全部（良好なときも） */
    const val ALL = 2
}

/** 「モバイル通信が悪かった場所」の記録。スマホの中だけに保存し、外には送らない */
object PlaceLog {
    private const val MAX_PLACES = 3000
    private const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    private const val MERGE_M = 60.0
    private const val MERGE_MS = 10 * 60 * 1000L
    private const val SAVE_EVERY_MS = 60_000L
    /** 同じ場所にとどまっている間・測位に失敗したあと、次に場所を調べるまでの間隔 */
    private const val BACKOFF_MS = 120_000L
    private const val STILL_COUNT = 3

    /** 古い順 */
    val places = MutableStateFlow<List<Place>>(emptyList())

    private var file: File? = null
    private val locating = AtomicBoolean(false)
    private var dirty = false
    private var savedAt = 0L
    @Volatile private var backoffUntil = 0L

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "places.tsv")
        file = f
        if (f.exists()) {
            val since = System.currentTimeMillis() - KEEP_MS
            places.value = runCatching {
                f.readLines().mapNotNull(::parse).filter { it.end >= since }.takeLast(MAX_PLACES)
            }.getOrDefault(emptyList())
        }
    }

    /** チェックの結果から、その場所でのようすを決める。記録しないときは null */
    fun classify(mode: Int, health: Health, rsrp: Int?, weakRsrp: Int): PlaceKind? {
        val kind = when (health) {
            Health.BAD -> PlaceKind.BAD
            Health.UNSTABLE -> PlaceKind.UNSTABLE
            Health.OK -> if (rsrp != null && rsrp < weakRsrp) PlaceKind.WEAK else PlaceKind.GOOD
            else -> return null // 休止中・再接続中は記録しない
        }
        return when {
            mode == PlaceMode.ALL -> kind
            mode == PlaceMode.BAD_ONLY && kind != PlaceKind.GOOD -> kind
            else -> null
        }
    }

    /**
     * モバイル回線のチェックのたびに呼ぶ。場所を調べるのに時間がかかるので、別の処理として動かす
     * （前の測位が終わっていなければ、今回は見送る）。
     */
    fun onCheck(ctx: Context, scope: CoroutineScope, prefs: Prefs, health: Health, signal: SignalInfo?, probe: ProbeResult?) {
        val kind = classify(prefs.placeMode, health, signal?.rsrp, prefs.placeWeakRsrp) ?: return
        if (!Locator.granted(ctx)) return
        val now = System.currentTimeMillis()
        val ms = probe?.takeIf { it.ok }?.ms
        // 同じ場所にとどまっている間・測位できなかった直後は、測位を休んで直前の記録に足すだけにする
        if (SystemClock.elapsedRealtime() < backoffUntil) {
            extend(kind, signal?.rsrp, ms, now)
            return
        }
        if (!locating.compareAndSet(false, true)) return
        scope.launch {
            try {
                // GPS は悪いときだけ（通信が止まっていると、基地局・Wi-Fi からの測位ができないことがある）
                val loc = Locator.locate(ctx, allowGps = kind != PlaceKind.GOOD)
                if (loc == null) {
                    backoffUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
                    return@launch
                }
                add(
                    Place(
                        start = now, end = now, lat = loc.latitude, lon = loc.longitude, accuracy = loc.accuracy,
                        kind = kind, minRsrp = signal?.rsrp, type = signal?.type.orEmpty(), count = 1,
                        fails = if (kind == PlaceKind.BAD) 1 else 0,
                        maxMs = ms,
                    )
                )
                // 3 回続けて同じ場所なら、動いていないとみなす
                if ((places.value.lastOrNull()?.count ?: 0) >= STILL_COUNT) {
                    backoffUntil = SystemClock.elapsedRealtime() + BACKOFF_MS
                }
            } finally {
                locating.set(false)
            }
        }
    }

    /**
     * 場所を調べ直さずに、直前の記録に今回のチェックを足す（同じ傾向で、間が空いていないときだけ）。
     * 足せたら true。
     */
    @Synchronized
    fun extend(kind: PlaceKind, rsrp: Int?, ms: Long?, now: Long): Boolean {
        val list = places.value
        val last = list.lastOrNull() ?: return false
        if (last.bad != (kind != PlaceKind.GOOD) || now - last.end > MERGE_MS) return false
        places.value = list.dropLast(1) + last.copy(
            end = now,
            kind = maxOf(last.kind, kind),
            minRsrp = listOfNotNull(last.minRsrp, rsrp).minOrNull(),
            count = last.count + 1,
            fails = last.fails + if (kind == PlaceKind.BAD) 1 else 0,
            maxMs = listOfNotNull(last.maxMs, ms).maxOrNull(),
        )
        dirty = true
        return true
    }

    /** 直前の記録と同じ場所・同じ傾向（良好どうし／悪いどうし）なら 1 件にまとめる */
    @Synchronized
    fun add(p: Place) {
        val list = places.value
        val last = list.lastOrNull()
        val merged = if (
            last != null && last.bad == p.bad && p.start - last.end <= MERGE_MS &&
            distanceM(last.lat, last.lon, p.lat, p.lon) <= maxOf(MERGE_M, minOf(last.accuracy, p.accuracy).toDouble())
        ) {
            val better = if (p.accuracy < last.accuracy) p else last
            list.dropLast(1) + last.copy(
                end = p.end,
                lat = better.lat, lon = better.lon, accuracy = better.accuracy,
                kind = maxOf(last.kind, p.kind),
                minRsrp = listOfNotNull(last.minRsrp, p.minRsrp).minOrNull(),
                type = p.type.ifEmpty { last.type },
                count = last.count + p.count,
                fails = last.fails + p.fails,
                maxMs = listOfNotNull(last.maxMs, p.maxMs).maxOrNull(),
            )
        } else list + p
        val since = p.end - KEEP_MS
        places.value = merged.filter { it.end >= since }.takeLast(MAX_PLACES)
        dirty = true
        if (SystemClock.elapsedRealtime() - savedAt >= SAVE_EVERY_MS) save()
    }

    /** 書き込みは多くても 1 分に 1 回。サービスが止まるときにも呼ぶ */
    @Synchronized
    fun save() {
        if (!dirty) return
        val f = file ?: return
        runCatching {
            f.writeText(places.value.joinToString("\n", postfix = "\n") {
                String.format(
                    Locale.US, "%d\t%d\t%.6f\t%.6f\t%.0f\t%s\t%s\t%s\t%d\t%d\t%s",
                    it.start, it.end, it.lat, it.lon, it.accuracy, it.kind.name, it.minRsrp?.toString().orEmpty(),
                    it.type.replace('\t', ' '), it.count, it.fails, it.maxMs?.toString().orEmpty(),
                )
            })
        }
        dirty = false
        savedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun clear() {
        places.value = emptyList()
        dirty = false
        runCatching { file?.delete() }
    }

    private fun parse(line: String): Place? {
        val p = line.split('\t')
        if (p.size != 11) return null
        return Place(
            start = p[0].toLongOrNull() ?: return null,
            end = p[1].toLongOrNull() ?: return null,
            lat = p[2].toDoubleOrNull() ?: return null,
            lon = p[3].toDoubleOrNull() ?: return null,
            accuracy = p[4].toFloatOrNull() ?: return null,
            kind = runCatching { PlaceKind.valueOf(p[5]) }.getOrNull() ?: return null,
            minRsrp = p[6].toIntOrNull(),
            type = p[7],
            count = p[8].toIntOrNull() ?: 1,
            fails = p[9].toIntOrNull() ?: 0,
            maxMs = p[10].toLongOrNull(),
        )
    }

    /** 2 点の距離（m）。近い距離なので平面として計算する */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_320.0
        val dx = (lon2 - lon1) * 111_320.0 * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** くり返し悪くなる場所。約 150m のます目にまとめ、別々の機会に 2 回以上悪かったところを多い順に */
    fun hotspots(places: List<Place>, limit: Int = 3): List<Pair<Place, Int>> =
        places.filter { it.bad }
            .groupBy { Math.round(it.lat / 0.0015) to Math.round(it.lon / 0.0018) }
            .values
            .filter { it.size >= 2 }
            .sortedByDescending { it.size }
            .take(limit)
            .map { g -> g.maxBy { it.end } to g.size }
}
