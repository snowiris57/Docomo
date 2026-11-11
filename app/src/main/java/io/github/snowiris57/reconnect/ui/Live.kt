package io.github.snowiris57.reconnect.ui

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
import io.github.snowiris57.reconnect.WifiReading
import kotlinx.coroutines.delay

/** 画面に出す「いま」の値（モバイルと Wi-Fi の両方） */
data class LiveReadings(
    val route: Route = Route.MOBILE,
    val cell: SignalInfo? = null,
    val wifi: WifiReading? = null,
)

/** 画面表示中は 2 秒ごとに読み直して、メーターを生き生きと動かす */
@Composable
fun rememberLive(): LiveReadings {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val live by produceState(LiveReadings()) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = LiveReadings(
                    route = runCatching { NetCheck.activeRoute(ctx) }.getOrDefault(Route.MOBILE),
                    cell = runCatching { NetCheck.signal(ctx) }.getOrNull(),
                    wifi = runCatching { NetCheck.wifi(ctx) }.getOrNull(),
                )
                delay(2_000)
            }
        }
    }
    return live
}
