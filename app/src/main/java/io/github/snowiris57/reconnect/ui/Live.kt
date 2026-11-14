package io.github.snowiris57.reconnect.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.snowiris57.reconnect.NetCheck
import io.github.snowiris57.reconnect.Route
import io.github.snowiris57.reconnect.SignalInfo
import io.github.snowiris57.reconnect.Traffic
import io.github.snowiris57.reconnect.WifiReading
import kotlinx.coroutines.delay

/** 画面に出す「いま」の値（モバイルと Wi-Fi の両方） */
data class LiveReadings(
    val route: Route = Route.MOBILE,
    val cell: SignalInfo? = null,
    val wifi: WifiReading? = null,
    /** いまの受信・送信（バイト/秒）。2 秒ごとの差から出す */
    val rx: Long? = null,
    val tx: Long? = null,
    /** Wi-Fi の宛先（ゲートウェイ）の IP アドレス */
    val gateway: String? = null,
)

/** 画面表示中は 2 秒ごとに読み直して、メーターを生き生きと動かす */
@Composable
fun rememberLive(): LiveReadings {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live by produceState(LiveReadings()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var prev: Pair<Long, Long>? = null
            var prevAt = 0L
            while (true) {
                // 画面を開いている間は、見守りがオフでも通信量のグラフを記録する
                Traffic.tick()
                val totals = Traffic.totals()
                val at = SystemClock.elapsedRealtime()
                val p = prev
                val dt = at - prevAt
                val rate = if (totals != null && p != null && dt > 0 && totals.first >= p.first && totals.second >= p.second) {
                    (totals.first - p.first) * 1000 / dt to (totals.second - p.second) * 1000 / dt
                } else null
                prev = totals
                prevAt = at
                value = LiveReadings(
                    route = runCatching { NetCheck.activeRoute(ctx) }.getOrDefault(Route.MOBILE),
                    cell = runCatching { NetCheck.signal(ctx) }.getOrNull(),
                    wifi = runCatching { NetCheck.wifi(ctx) }.getOrNull(),
                    rx = rate?.first ?: value.rx,
                    tx = rate?.second ?: value.tx,
                    gateway = NetCheck.wifiGateway(ctx),
                )
                delay(2_000)
            }
        }
    }
    return live
}
