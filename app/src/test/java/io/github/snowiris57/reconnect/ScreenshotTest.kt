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
import io.github.snowiris57.reconnect.widget.GaugeBitmap
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

    @Test fun home_widget_hint() = app(false, ready.copy(canPinWidget = true), tall = true)
    @Test fun settings_dark() = app(true, ready, tab = 2)

    private fun widget(layout: Int, dark: Boolean, wide: Boolean) {
        paparazzi.unsafeUpdateConfig(
            deviceConfig = DeviceConfig.PIXEL_6.copy(
                nightMode = if (dark) NightMode.NIGHT else NightMode.NOTNIGHT,
                screenWidth = if (wide) 1200 else 700,
                screenHeight = 700,
                orientation = if (wide) ScreenOrientation.LANDSCAPE else ScreenOrientation.PORTRAIT,
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
            addView(v, FrameLayout.LayoutParams(((if (wide) 330 else 160) * d).toInt(), (160 * d).toInt(), Gravity.CENTER))
        }
        val gauge = GaugeBitmap.render(
            sizePx = 400, quality = sig.quality, value = "72", unit = "-97 dBm", caption = "5G NSA",
            palette = GaugeBitmap.Palette(
                track = ctx.getColor(R.color.widget_track), text = ctx.getColor(R.color.widget_text),
                sub = ctx.getColor(R.color.widget_sub), knob = ctx.getColor(R.color.widget_bg),
            ),
        )
        v.findViewById<ImageView>(R.id.gauge).setImageBitmap(gauge)
        if (wide) {
            v.findViewById<TextView>(R.id.dot).setTextColor(GaugeBitmap.GOOD)
            v.findViewById<TextView>(R.id.subtitle).text = "NTT DOCOMO ・ 5G NSA"
            v.findViewById<TextView>(R.id.latency).text = "42"
            v.findViewById<TextView>(R.id.recoveries).text = "1"
        } else {
            v.findViewById<TextView>(R.id.status).apply { text = "● 正常"; setTextColor(GaugeBitmap.GOOD) }
        }
        paparazzi.snapshot(stage)
    }

    @Test fun widget_small_light() = widget(R.layout.widget_small, false, false)
    @Test fun widget_small_dark() = widget(R.layout.widget_small, true, false)
    @Test fun widget_wide_light() = widget(R.layout.widget_wide, false, true)
    @Test fun widget_wide_dark() = widget(R.layout.widget_wide, true, true)
}
