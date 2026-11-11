package io.github.snowiris57.reconnect.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import io.github.snowiris57.reconnect.DayStats
import io.github.snowiris57.reconnect.Health
import io.github.snowiris57.reconnect.MonitorService
import io.github.snowiris57.reconnect.MonitorState
import io.github.snowiris57.reconnect.NetCheck
import io.github.snowiris57.reconnect.ProbeResult
import io.github.snowiris57.reconnect.Quality
import io.github.snowiris57.reconnect.R
import io.github.snowiris57.reconnect.Route
import io.github.snowiris57.reconnect.WifiReading
import io.github.snowiris57.reconnect.SignalInfo
import kotlin.math.roundToInt

/**
 * 電波メーターのホーム画面ウィジェット。
 * 2×2 ではメーターのみ、横に広げると状態・応答速度・再接続ボタンを表示する。
 */
class MeterWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val snap = Snapshot.capture(context)
        ids.forEach { update(context, manager, it, snap) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, id, Snapshot.capture(context))
    }

    /** ウィジェットに表示する内容 */
    private data class Snapshot(
        val route: Route,
        val wifi: WifiReading?,
        val probe: ProbeResult?,
        val signal: SignalInfo?,
        val health: Health,
        val title: String,
        val latencyMs: Long?,
        val recoveriesToday: Int,
    ) {
        /** いま使っている回線の品質と dBm */
        val quality: Float? get() = Quality.combined(if (route == Route.WIFI) wifi?.quality else signal?.quality, probe)
        val dbm: Int? get() = if (route == Route.WIFI) wifi?.rssi else signal?.rsrp

        companion object {
            fun capture(ctx: Context): Snapshot {
                val st = MonitorState.status.value
                // 監視が止まっていてもメーターは動かす
                val sig = (if (st.running) st.signal else null) ?: runCatching { NetCheck.signal(ctx) }.getOrNull()
                val route = runCatching { NetCheck.activeRoute(ctx) }.getOrDefault(st.route)
                val wifi = if (route == Route.WIFI) runCatching { NetCheck.wifi(ctx) }.getOrNull() ?: st.wifi else null
                return Snapshot(
                    route = route,
                    wifi = wifi,
                    probe = st.probe?.takeIf { st.running && st.route == route },
                    signal = sig,
                    health = if (st.running) st.health else Health.STOPPED,
                    title = when {
                        !st.running -> "監視オフ"
                        st.health == Health.UNSTABLE -> when {
                            route != Route.WIFI -> "通信が不安定"
                            wifi?.tethering == true -> "テザリング不安定"
                            else -> "Wi-Fi 不安定"
                        }
                        st.health == Health.OK && route == Route.WIFI ->
                            if (wifi?.tethering == true) "テザリング安定" else "Wi-Fi 安定"
                        st.health == Health.BAD && route == Route.WIFI -> "Wi-Fi に問題あり"
                        st.health == Health.OK -> "通信は安定"
                        st.health == Health.BAD -> "通信に問題あり"
                        st.health == Health.RECOVERING -> "再接続中…"
                        else -> st.headline
                    },
                    latencyMs = st.probe?.takeIf { it.ok && st.running }?.ms,
                    recoveriesToday = DayStats.today(MonitorState.events.value).successes,
                )
            }
        }
    }

    companion object {
        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(ctx, MeterWidget::class.java))
            if (ids.isEmpty()) return
            val snap = Snapshot.capture(ctx)
            ids.forEach { update(ctx, manager, it, snap) }
        }

        private fun update(ctx: Context, manager: AppWidgetManager, id: Int, snap: Snapshot) {
            val gauge = gaugeBitmap(ctx, snap)
            val views = if (Build.VERSION.SDK_INT >= 31) {
                RemoteViews(
                    mapOf(
                        SizeF(100f, 100f) to small(ctx, snap, gauge),
                        SizeF(230f, 100f) to wide(ctx, snap, gauge),
                    )
                )
            } else {
                val minWidth = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
                if (minWidth >= 230) wide(ctx, snap, gauge) else small(ctx, snap, gauge)
            }
            manager.updateAppWidget(id, views)
        }

        private fun gaugeBitmap(ctx: Context, snap: Snapshot) = GaugeBitmap.render(
            sizePx = (150 * ctx.resources.displayMetrics.density).roundToInt().coerceIn(200, 480),
            quality = snap.quality,
            value = snap.quality?.let { "${(it * 100).roundToInt()}" } ?: "—",
            unit = snap.dbm?.let { "$it dBm" } ?: "電波なし",
            caption = if (snap.route == Route.WIFI) snap.wifi?.caption ?: "Wi-Fi" else snap.signal?.type ?: "",
            palette = GaugeBitmap.Palette(
                track = ContextCompat.getColor(ctx, R.color.widget_track),
                text = ContextCompat.getColor(ctx, R.color.widget_text),
                sub = ContextCompat.getColor(ctx, R.color.widget_sub),
                knob = ContextCompat.getColor(ctx, R.color.widget_bg),
            ),
        )

        private fun small(ctx: Context, snap: Snapshot, gauge: android.graphics.Bitmap) =
            RemoteViews(ctx.packageName, R.layout.widget_small).apply {
                setImageViewBitmap(R.id.gauge, gauge)
                setContentDescription(R.id.gauge, describe(snap))
                setTextViewText(R.id.status, "● ${healthLabel(snap.health)}")
                setTextColor(R.id.status, healthColor(ctx, snap.health))
                setOnClickPendingIntent(R.id.widget_root, MonitorService.openAppIntent(ctx))
            }

        private fun wide(ctx: Context, snap: Snapshot, gauge: android.graphics.Bitmap) =
            RemoteViews(ctx.packageName, R.layout.widget_wide).apply {
                setImageViewBitmap(R.id.gauge, gauge)
                setContentDescription(R.id.gauge, describe(snap))
                setTextViewText(R.id.dot, "●")
                setTextColor(R.id.dot, healthColor(ctx, snap.health))
                setTextViewText(R.id.title, snap.title)
                val sub = if (snap.route == Route.WIFI) {
                    listOf(
                        snap.wifi?.ssid ?: if (snap.wifi?.tethering == true) "テザリング" else "Wi-Fi",
                        snap.wifi?.band.orEmpty(),
                    )
                } else {
                    listOf(snap.signal?.carrier.orEmpty(), snap.signal?.type.orEmpty())
                }
                setTextViewText(
                    R.id.subtitle,
                    sub.filter { it.isNotBlank() && it != "?" }.joinToString(" ・ ").ifEmpty { "モバイル回線" },
                )
                setTextViewText(R.id.latency, snap.latencyMs?.let { "$it" } ?: "—")
                if (snap.route == Route.WIFI) {
                    // TetherPulse と同じく「ネット」と「Wi-Fi 区間」の応答時間
                    setTextViewText(R.id.latency_label, "ms ネット")
                    setTextViewText(R.id.recoveries, snap.probe?.hopMs?.toString() ?: "—")
                    setTextViewText(R.id.recoveries_label, "ms Wi-Fi")
                } else {
                    setTextViewText(R.id.latency_label, "ms 応答")
                    setTextViewText(R.id.recoveries, "${snap.recoveriesToday}")
                    setTextViewText(R.id.recoveries_label, "回 今日の復旧")
                }
                setOnClickPendingIntent(R.id.widget_root, MonitorService.openAppIntent(ctx))
                setOnClickPendingIntent(R.id.reconnect, MonitorService.reconnectPendingIntent(ctx))
                if (snap.health == Health.RECOVERING) {
                    setTextViewText(R.id.reconnect, "再接続中…")
                } else {
                    setTextViewText(R.id.reconnect, "今すぐ再接続")
                }
            }

        private fun describe(snap: Snapshot): String {
            val q = snap.quality ?: return "電波メーター: 電波を取得できません"
            val name = if (snap.route == Route.WIFI) "Wi-Fi" else "モバイル回線"
            return "$name の通信品質 ${(q * 100).roundToInt()}パーセント" +
                (snap.dbm?.let { "、${it}デシベルミリワット" } ?: "")
        }

        private fun healthLabel(h: Health) = when (h) {
            Health.OK -> "正常"
            Health.BAD -> "異常"
            Health.UNSTABLE -> "不安定"
            Health.PAUSED -> "休止中"
            Health.RECOVERING -> "再接続中"
            Health.STOPPED -> "監視オフ"
        }

        private fun healthColor(ctx: Context, h: Health): Int = when (h) {
            Health.OK -> GaugeBitmap.GOOD
            Health.BAD -> GaugeBitmap.BAD
            Health.UNSTABLE -> GaugeBitmap.WARN
            Health.PAUSED -> ContextCompat.getColor(ctx, R.color.widget_sub)
            Health.RECOVERING -> 0xFF3B82F6.toInt()
            Health.STOPPED -> ContextCompat.getColor(ctx, R.color.widget_sub)
        }
    }
}
