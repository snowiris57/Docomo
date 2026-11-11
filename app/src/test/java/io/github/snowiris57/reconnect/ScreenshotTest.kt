package io.github.snowiris57.reconnect

import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.NightMode
import com.android.resources.ScreenOrientation
import android.widget.FrameLayout
import android.view.Gravity
import android.graphics.drawable.GradientDrawable
import io.github.snowiris57.reconnect.ui.AppActions
import io.github.snowiris57.reconnect.ui.ReConnectApp
import io.github.snowiris57.reconnect.ui.ReConnectTheme
import io.github.snowiris57.reconnect.ui.SetupState
import io.github.snowiris57.reconnect.ui.ShizukuStatus
import io.github.snowiris57.reconnect.widget.MeterWidget
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.snowiris57.reconnect.ui.LiveReadings
import io.github.snowiris57.reconnect.ui.TrafficCard
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.sin

/**
 * 端末なしで画面とウィジェットを描画して見た目を確認するためのスクリーンショット。
 * ./gradlew recordPaparazziDebug → app/src/test/snapshots/images/
 */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_6,
        theme = "android:Theme.Material.Light.NoActionBar",
        maxPercentDifference = 0.1,
    )

    private val sig = SignalInfo(level = 3, rsrp = -97, type = "5G NSA", carrier = "NTT DOCOMO")

    @Before
    fun seed() {
        val now = System.currentTimeMillis()
        val samples = (0 until 200).map { i ->
            val t = now - (200 - i) * 15_000L
            val dip = i in 120..131
            val q = if (dip) 0.18f + 0.05f * sin(i.toFloat()) else (0.68f + 0.08f * sin(i / 9f) + 0.03f * sin(i * 1.7f))
            Sample(t, (q * 60 - 140).toInt(), q, if (dip) null else 40L + (i % 7) * 6, !dip || i < 124, if (dip && i >= 124) Health.BAD else Health.OK)
        }
        MonitorState.samples.value = samples
        // 通信量: ふだんは少なく、途中で動画などのまとまった受信
        Traffic.samples.value = (0 until 360).map { i ->
            val burst = i in 300..330
            val rx = if (burst) (3_500_000 + 2_500_000 * sin(i * 1.3)).toLong() else (800 + 600 * sin(i / 5.0) + (if (i % 37 == 0) 40_000 else 0)).toLong()
            TrafficSample(now - (360 - i) * 10_000L, rx.coerceAtLeast(0), (300 + 200 * sin(i / 3.0) + (if (burst) 60_000 else 0)).toLong())
        }
        val e = { minAgo: Int, type: EventType, msg: String -> Event(now - minAgo * 60_000L, type, msg) }
        MonitorState.events.value = listOf(
            e(17, EventType.RECOVER_OK, "通信が復旧しました"),
            e(18, EventType.STEP, "Step 1: モバイルデータ OFF → ON"),
            e(18, EventType.RECOVER_START, "自動で再接続を開始（応答なし — パケ止まりの疑い）"),
            e(19, EventType.DETECT, "異常を検知: 応答なし — パケ止まりの疑い"),
            e(52, EventType.START, "監視を開始しました"),
        )
        MonitorState.status.value = Status(
            running = true, health = Health.OK, headline = "通信は安定しています",
            detail = "15秒ごとに見守っています", signal = sig, probe = ProbeResult(true, 42),
            failThreshold = 2, updatedAt = now,
        )
    }

    private fun app(
        dark: Boolean,
        setup: SetupState,
        tab: Int = 0,
        tall: Boolean = false,
        onboarded: Boolean = true,
        touch: Float? = null,
        base: DeviceConfig = DeviceConfig.PIXEL_6,
        fontScale: Float = 1f,
    ) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = base.copy(
                nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
                screenHeight = if (tall) 5200 else base.screenHeight,
                fontScale = fontScale,
            )
        )
        paparazzi.snapshot {
            ReConnectTheme(dark = dark, dynamic = false) {
                ReConnectApp(
                    prefs = Prefs(paparazzi.context),
                    setup = setup,
                    monitoring = true,
                    onboarded = onboarded,
                    actions = AppActions(),
                    initialTab = tab,
                    preview = true,
                    previewTouch = touch,
                )
            }
        }
    }

    private val ready = SetupState(ShizukuStatus.GRANTED, permissions = true, battery = true)

    @Test fun home_light() = app(false, ready)
    @Test fun home_dark() = app(true, ready)
    @Test fun home_full() = app(false, ready, tall = true)
    @Test fun home_setup() = app(false, SetupState(ShizukuStatus.NOT_GRANTED, permissions = true, battery = false))
    @Test fun history_light() = app(false, ready, tab = 1)
    @Test fun welcome_light() = app(false, ready, onboarded = false)
    @Test fun welcome_dark() = app(true, ready, onboarded = false)
    @Test fun home_chart_touch() = app(false, ready, tall = true, touch = 0.62f)
    @Test fun home_large_font() = app(false, ready, fontScale = 1.3f)
    @Test fun home_tablet() = app(true, ready, base = DeviceConfig.PIXEL_C)

    @Test fun home_recovering() {
        MonitorState.status.value = MonitorState.status.value.copy(
            health = Health.RECOVERING, headline = "再接続しています…", detail = "モバイルデータを入れ直しています",
        )
        app(false, ready)
    }

    // 実機と同じ状況: iPhone がすぐそばで電波は最強だが、ネットの応答が遅い
    private val tether = WifiReading(
        rssi = -24, linkMbps = 77, freqMhz = 2437, standard = "Wi-Fi 6", ssid = "Y's_iPhone", tethering = true,
    )
    private val slowProbe = ProbeResult(true, 486, hopMs = 23)

    private fun wifiMode() {
        MonitorState.status.value = MonitorState.status.value.copy(
            route = Route.WIFI, wifi = tether, health = Health.UNSTABLE, headline = "テザリングが不安定です",
            detail = "応答 486ms（目安は 300ms 以内）", probe = slowProbe,
        )
        // 直近の一部を Wi-Fi に
        MonitorState.samples.value = MonitorState.samples.value.mapIndexed { i, s ->
            if (i >= 160) s.copy(
                route = Route.WIFI, rsrp = -24, health = Health.UNSTABLE,
                quality = Quality.combined(tether.quality, slowProbe.copy(ms = 380L + (i % 5) * 40)),
            ) else s
        }
    }

    @Test fun home_wifi_light() { wifiMode(); app(false, ready, tall = true) }
    @Test fun home_wifi_dark() { wifiMode(); app(true, ready) }
    @Test fun home_wifi_noname() {
        wifiMode()
        MonitorState.status.value = MonitorState.status.value.copy(wifi = tether.copy(ssid = null))
        app(false, ready.copy(locationGranted = false))
    }

    private fun trafficCard(dark: Boolean, touch: Float? = null) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_6.copy(
                nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
                screenHeight = 1150,
            )
        )
        val traffic = Traffic.samples.value
        paparazzi.snapshot {
            ReConnectTheme(dark = dark, dynamic = false) {
                Box(Modifier.background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                    TrafficCard(traffic, LiveReadings(rx = 1200, tx = 150), previewTouch = touch)
                }
            }
        }
    }

    @Test fun traffic_light() = trafficCard(false)
    @Test fun traffic_dark_touch() = trafficCard(true, touch = 0.88f)
    @Test fun home_widget_hint() = app(false, ready.copy(canPinWidget = true), tall = true)
    @Test fun settings_dark() = app(true, ready, tab = 2)

    /**
     * ウィジェットを描く。実機の 2×2 はだいたい 150×150dp。
     * [w]・[h] は dp。中身は MeterWidget と同じ関数で作る。
     */
    private fun widget(layout: Int, snap: MeterWidget.Snapshot, w: Int, h: Int, dark: Boolean = true) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_6.copy(
                nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
                screenWidth = ((w + 40) * 2.625f).toInt(),
                screenHeight = ((h + 40) * 2.625f).toInt(),
                orientation = if (w > h) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
            )
        )
        val ctx = paparazzi.context
        val d = ctx.resources.displayMetrics.density
        val v = LayoutInflater.from(ctx).inflate(layout, null)
        val stage = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(0xFF1B2735.toInt(), 0xFF090A0F.toInt()) else intArrayOf(0xFFB8E1DD.toInt(), 0xFFE9D8F5.toInt()),
            )
            addView(v, FrameLayout.LayoutParams((w * d).toInt(), (h * d).toInt(), Gravity.CENTER))
        }
        val t = MeterWidget.texts(ctx, snap)
        v.setBackgroundResource(MeterWidget.background(snap.health))
        v.findViewById<ImageView>(R.id.gauge).setImageBitmap(MeterWidget.gaugeBitmap(ctx, snap))
        v.findViewById<TextView>(R.id.value).apply { text = t.value; setTextColor(t.valueColor) }
        v.findViewById<TextView>(R.id.status).apply { text = t.status; setTextColor(t.statusColor) }
        if (layout != R.layout.widget_small) {
            v.findViewById<TextView>(R.id.side).apply { text = t.side; setTextColor(t.valueColor) }
            v.findViewById<TextView>(R.id.line1).text = t.line1
            val wide = layout == R.layout.widget_wide
            v.findViewById<TextView>(R.id.line2).text = if (wide) t.line2Wide else t.line2
            v.findViewById<TextView>(R.id.line3).apply {
                text = t.line3
                visibility = if (wide && t.line3.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
            v.findViewById<TextView>(R.id.latency).text = t.latency
        }
        paparazzi.snapshot(stage)
    }

    /** 実機のスクリーンショットと同じ状況（iPhone のテザリング、-29 dBm、ネット 325ms） */
    private val wifiSnap = MeterWidget.Snapshot(
        route = Route.WIFI,
        wifi = tether.copy(rssi = -29, linkMbps = 68),
        probe = ProbeResult(true, 325, hopMs = 9),
        signal = sig, health = Health.OK, recoveriesToday = 0,
    )
    private val mobileSnap = MeterWidget.Snapshot(
        route = Route.MOBILE, wifi = null, probe = ProbeResult(true, 42),
        signal = sig, health = Health.OK, recoveriesToday = 1,
    )

    @Test fun widget_meter_wifi() = widget(R.layout.widget_meter, wifiSnap, 150, 150)
    @Test fun widget_meter_wifi_unstable() = widget(
        R.layout.widget_meter, wifiSnap.copy(health = Health.UNSTABLE, probe = slowProbe, wifi = tether), 150, 150,
    )
    @Test fun widget_meter_wifi_noname() = widget(R.layout.widget_meter, wifiSnap.copy(wifi = wifiSnap.wifi?.copy(ssid = null)), 150, 150)
    @Test fun widget_meter_mobile() = widget(R.layout.widget_meter, mobileSnap, 150, 150)
    @Test fun widget_meter_short() = widget(R.layout.widget_meter, wifiSnap, 130, 135)
    @Test fun widget_meter_off() = widget(
        R.layout.widget_meter, mobileSnap.copy(health = Health.STOPPED, probe = null), 150, 150, dark = false,
    )
    @Test fun widget_small() = widget(R.layout.widget_small, wifiSnap, 110, 110)
    @Test fun widget_wide_wifi() = widget(R.layout.widget_wide, wifiSnap, 320, 150)
    @Test fun widget_wide_mobile() = widget(R.layout.widget_wide, mobileSnap.copy(health = Health.BAD, probe = ProbeResult(false, 0)), 320, 150)
}
